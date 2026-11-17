using Microsoft.EntityFrameworkCore;

namespace Siyue;

public class StudentFollowUp : Entity
{
    public Guid ClassId { get; set; }
    public Guid StudentId { get; set; }
    public Guid StaffId { get; set; }
    public string StaffName { get; set; } = "";
    public string Note { get; set; } = "";
    public string RequestKey { get; set; } = "";
    public string DueOn { get; set; } = "";
    public DateTimeOffset? ResolvedAt { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}

public class StudentFollowUpGrant : Entity
{
    public Guid ClassId { get; set; }
    public Guid StaffId { get; set; }
    public string Department { get; set; } = "";
}

public partial class Data
{
    public DbSet<StudentFollowUpGrant> StudentFollowUpGrants => Set<StudentFollowUpGrant>();
    public DbSet<StudentFollowUp> StudentFollowUps => Set<StudentFollowUp>();
}

public record StudentFollowUpInput(string? Note, string? RequestKey, string? DueOn = null);

public record StudentFollowUpGrantInput(Guid StaffId, string Department);

public static class StudentFollowUpEndpoints
{
    public static void MapStudentFollowUps(this WebApplication app)
    {
        app.MapGet("/api/student-follow-up/classes", async (Data db, Access access) =>
        {
            var actor = access.User;
            return await db.Classes.AsNoTracking().Where(c => actor.Role == "admin"
                || db.Assignments.Any(a => a.ClassId == c.Id && a.StaffId == actor.Id && db.Courses.Any(course => course.Id == a.CourseId))
                || db.StudentFollowUpGrants.Any(g => g.ClassId == c.Id && g.StaffId == actor.Id))
                .OrderBy(c => c.Name).Select(c => new { c.Id, c.Name }).ToArrayAsync();
        });
        app.MapGet("/api/classes/{classId:guid}/follow-up-roster", async (Guid classId, Data db, Access access) =>
        {
            await CheckClass(classId, db, access);
            return await db.Students.AsNoTracking().Where(s => s.ClassId == classId)
                .OrderBy(s => s.Name).ThenBy(s => s.Id).Select(s => new { s.Id, s.Name }).ToArrayAsync();
        });
        var grants = app.MapGroup("/api/admin/classes/{classId:guid}/follow-up-grants");
        grants.MapGet("", async (Guid classId, Data db, Access access) =>
        {
            access.Admin();
            await access.Class(classId);
            return await (from grant in db.StudentFollowUpGrants.AsNoTracking()
                          join staff in db.Staff on grant.StaffId equals staff.Id
                          where grant.ClassId == classId && staff.Enabled
                          orderby grant.Department, staff.Name
                          select new { grant.Id, grant.StaffId, staff.Name, grant.Department }).ToArrayAsync();
        });
        grants.MapPost("", async (Guid classId, StudentFollowUpGrantInput input, Data db, Access access) =>
        {
            access.Admin();
            await access.Class(classId);
            Guard.That(input.Department is "教务" or "教研" or "招生", "请选择有效的岗位");
            Guard.That(await db.Staff.AnyAsync(s => s.Id == input.StaffId && s.Enabled && s.Role != "admin"), "请选择已启用的员工");
            var query = db.StudentFollowUpGrants.Where(g => g.ClassId == classId && g.StaffId == input.StaffId);
            var existing = await query.AsNoTracking().SingleOrDefaultAsync();
            if (existing != null)
            {
                Guard.That(existing.Department == input.Department, "该员工已有授权，请先撤销原授权", 409);
                return Results.Ok(new { existing.Id });
            }
            var grant = new StudentFollowUpGrant { ClassId = classId, StaffId = input.StaffId, Department = input.Department };
            db.StudentFollowUpGrants.Add(grant);
            db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "student.followup.granted", TargetId = grant.Id });
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException)
            {
                existing = await query.AsNoTracking().SingleOrDefaultAsync();
                if (existing == null) throw;
                Guard.That(existing.Department == input.Department, "该员工已有授权，请先撤销原授权", 409);
                return Results.Ok(new { existing.Id });
            }
            return Results.Ok(new { grant.Id });
        });
        grants.MapDelete("/{id:guid}", async (Guid classId, Guid id, Data db, Access access) =>
        {
            access.Admin();
            await access.Class(classId);
            await using var transaction = await db.Database.BeginTransactionAsync();
            var removed = await db.StudentFollowUpGrants.Where(g => g.ClassId == classId && g.Id == id).ExecuteDeleteAsync();
            if (removed > 0)
            {
                db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "student.followup.revoked", TargetId = id });
                await db.SaveChangesAsync();
            }
            await transaction.CommitAsync();
            return Results.NoContent();
        });
        app.MapGet("/api/classes/{classId:guid}/follow-up-alerts", async (Guid classId, Data db, Access access) =>
        {
            await CheckClass(classId, db, access);
            var alerts = await (from note in db.StudentFollowUps.AsNoTracking()
                                join student in db.Students on note.StudentId equals student.Id
                                where note.ClassId == classId && student.ClassId == classId
                                    && note.DueOn != "" && note.ResolvedAt == null
                                orderby note.DueOn, note.CreatedAt, note.Id
                                select new { note.Id, note.StudentId, studentName = student.Name, note.DueOn, Kind = "communication", Count = 1 })
                .Take(100).ToArrayAsync();
            var actor = access.User;
            var canSeeClassOperations = actor.Role == "admin" || await db.StudentFollowUpGrants.AnyAsync(g =>
                g.ClassId == classId && g.StaffId == actor.Id && g.Department == "教务");
            var today = DateOnly.FromDateTime(Clock.Now.ToOffset(TimeSpan.FromHours(8)).DateTime);
            var recentDates = Enumerable.Range(0, 30).Select(offset =>
                today.AddDays(-offset).ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture)).ToArray();
            var attendance = await (from row in db.AcademicAttendances.AsNoTracking()
                                    join session in db.ClassSessions on row.ClassSessionId equals session.Id
                                    join student in db.Students on row.StudentId equals student.Id
                                    where session.ClassId == classId && student.ClassId == classId
                                        && recentDates.Contains(session.LessonDate)
                                        && (row.Status == "leave" || row.Status == "absent")
                                        && (row.MakeupStatus == "none" || row.MakeupStatus == "pending")
                                        && (canSeeClassOperations || db.Assignments.Any(a =>
                                            a.ClassId == classId && a.StaffId == actor.Id && a.CourseId == session.CourseId))
                                    select new { row.Id, row.StudentId, studentName = student.Name, session.LessonDate })
                .ToArrayAsync();
            var repeated = attendance.GroupBy(row => row.StudentId)
                .Where(group => group.Count() >= 2)
                .Select(group => {
                    var latest = group.OrderByDescending(row => row.LessonDate).ThenBy(row => row.Id).First();
                    return new { latest.Id, latest.StudentId, latest.studentName, DueOn = latest.LessonDate, Kind = "attendance", Count = group.Count() };
                })
                .OrderByDescending(alert => alert.Count).ThenBy(alert => alert.Id).Take(100);
            var canSeeHomework = canSeeClassOperations || await db.Assignments.AnyAsync(a =>
                a.ClassId == classId && a.StaffId == actor.Id);
            var overdue = canSeeHomework
                ? await OverdueHomework(classId, recentDates[1..], db)
                : [];
            return Results.Ok(repeated.Concat(overdue.Select(x => new
            {
                Id = x.StudentId, x.StudentId, studentName = x.StudentName, x.DueOn, Kind = "homework", x.Count
            })).Concat(alerts));
        });
        var group = app.MapGroup("/api/classes/{classId:guid}/students/{studentId:guid}/follow-ups");
        group.MapGet("", async (Guid classId, Guid studentId, Data db, Access access) =>
        {
            await CheckScope(classId, studentId, db, access);
            var actor = access.User;
            var sessions = db.ClassSessions.Where(s => s.ClassId == classId);
            if (actor.Role != "admin")
                sessions = sessions.Where(s => db.Assignments.Any(a => a.ClassId == classId && a.StaffId == actor.Id && a.CourseId == s.CourseId));
            var recent = await (from mark in db.ClassSessionMarks
                                join session in sessions on mark.ClassSessionId equals session.Id
                                where mark.StudentId == studentId
                                orderby session.LessonDate descending, session.CreatedAt descending
                                select new { session.LessonDate, mark.Reviewed, mark.WrongIdsJson })
                                .Take(10).ToArrayAsync();
            var notes = await db.StudentFollowUps.AsNoTracking()
                .Where(n => n.ClassId == classId && n.StudentId == studentId)
                .OrderByDescending(n => n.CreatedAt).ThenByDescending(n => n.Id)
                .Take(50).Select(n => new { n.Id, n.StaffName, n.Note, n.CreatedAt, n.DueOn, n.ResolvedAt }).ToArrayAsync();
            return Results.Ok(new
            {
                recent = recent.Select(s => new { s.LessonDate, s.Reviewed, wrongCount = s.Reviewed ? (int?)Json.Read<Guid[]>(s.WrongIdsJson).Length : null }),
                notes
            });
        });
        group.MapPost("", async (Guid classId, Guid studentId, StudentFollowUpInput input, Data db, Access access) =>
        {
            await CheckScope(classId, studentId, db, access);
            var note = input.Note?.Trim() ?? "";
            var key = input.RequestKey?.Trim() ?? "";
            Guard.That(note.Length is > 0 and <= 2000 && key.Length is > 0 and <= 100, "请填写 1–2000 字的沟通记录");
            var dueOn = input.DueOn?.Trim() ?? "";
            Guard.That(dueOn.Length == 0 || DateOnly.TryParseExact(dueOn, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out _), "请选择有效的跟进日期");
            var actor = access.User;
            var query = db.StudentFollowUps.Where(n => n.ClassId == classId && n.StudentId == studentId && n.StaffId == actor.Id && n.RequestKey == key);
            var existing = await query.AsNoTracking().SingleOrDefaultAsync();
            if (existing != null) return Saved(existing, note, dueOn);
            var item = new StudentFollowUp { ClassId = classId, StudentId = studentId, StaffId = actor.Id, StaffName = actor.Name, Note = note, RequestKey = key, DueOn = dueOn };
            db.StudentFollowUps.Add(item);
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException)
            {
                existing = await query.AsNoTracking().SingleOrDefaultAsync();
                if (existing == null) throw;
                return Saved(existing, note, dueOn);
            }
            return Saved(item, note, dueOn);
        });
        group.MapPost("/{id:guid}/resolve", async (Guid classId, Guid studentId, Guid id, Data db, Access access) =>
        {
            await CheckScope(classId, studentId, db, access);
            var target = db.StudentFollowUps.Where(n => n.Id == id && n.ClassId == classId && n.StudentId == studentId && n.DueOn != "");
            await using var transaction = await db.Database.BeginTransactionAsync();
            var now = Clock.Now;
            var changed = await target.Where(n => n.ResolvedAt == null).ExecuteUpdateAsync(update => update.SetProperty(n => n.ResolvedAt, now));
            if (changed == 0)
                Guard.That(await target.AnyAsync(), "跟进事项不存在", 404);
            else
            {
                db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "student.followup.resolved", TargetId = id });
                await db.SaveChangesAsync();
            }
            await transaction.CommitAsync();
            return Results.NoContent();
        });
    }

    static IResult Saved(StudentFollowUp item, string note, string dueOn)
    {
        Guard.That(item.Note == note && item.DueOn == dueOn, "同一保存请求的内容已变更，请重新提交", 409);
        return Results.Ok(new { item.Id });
    }

    sealed record HomeworkAlert(Guid StudentId, string StudentName, string DueOn, int Count);

    static async Task<HomeworkAlert[]> OverdueHomework(Guid classId, string[] overdueDates, Data db)
    {
        var collections = await db.AcademicCollections.AsNoTracking()
            .Where(c => c.ClassId == classId && c.State == "approved" && overdueDates.Contains(c.DueOn))
            .OrderByDescending(c => c.DueOn).ThenBy(c => c.Id).Take(50).ToArrayAsync();
        if (collections.Length == 0) return [];
        var names = await db.Students.AsNoTracking().Where(s => s.ClassId == classId)
            .ToDictionaryAsync(s => s.Id, s => s.Name);
        var alerts = new Dictionary<Guid, HomeworkAlert>();
        foreach (var collection in collections)
        {
            if (!await AcademicTrainingRetesting.CollectionCurrent(collection.Id, db)) continue;
            var (missing, pending) = await AcademicTeacherTodos.Missing(collection, db);
            if (pending > 0) continue;
            foreach (var student in missing)
            {
                if (!names.TryGetValue(student.Id, out var name)) continue;
                alerts[student.Id] = alerts.TryGetValue(student.Id, out var alert)
                    ? alert with { Count = alert.Count + 1 }
                    : new(student.Id, name, collection.DueOn, 1);
            }
        }
        return alerts.Values.OrderByDescending(x => x.Count).ThenBy(x => x.StudentId).Take(100).ToArray();
    }

    static async Task CheckClass(Guid classId, Data db, Access access)
    {
        var actor = access.User;
        if (actor.Role != "admin" && await db.StudentFollowUpGrants.AnyAsync(g => g.ClassId == classId && g.StaffId == actor.Id))
        {
            Guard.That(await db.Classes.AnyAsync(c => c.Id == classId), "班级不存在", 404);
            return;
        }
        await access.Class(classId);
    }

    static async Task CheckScope(Guid classId, Guid studentId, Data db, Access access)
    {
        await CheckClass(classId, db, access);
        Guard.That(await db.Students.AnyAsync(s => s.Id == studentId && s.ClassId == classId), "该学员不在此班级", 404);
    }
}
