using Microsoft.EntityFrameworkCore;
namespace Siyue;
public record ClassEditInput(string Name, Guid CampusId, string ExpectedName, Guid ExpectedCampusId);
public record StudentInput(string Name, string? Code = null);
public record QuickClassInput(string Name, Guid CampusId, Guid? CourseId = null, string RequestId = "");
public record ClassCourseInput(Guid CourseId);
public record QuickCampusInput(string Name, string City);
public record StudentBatchInput(string[] Names, string RequestId);
public static class TeacherWorkspace
{
    public static void MapTeacherWorkspace(this WebApplication app)
    {
        app.MapPost("/api/classes", async (QuickClassInput input, Access a, Data db) => {
            var actor = a.User; var name = Guard.Name(input.Name);
            Guard.That(!string.IsNullOrWhiteSpace(input.RequestId) && input.RequestId.Length <= 120, "请重新打开添加班级页面");
            Guard.That(await db.Campuses.AnyAsync(x => x.Id == input.CampusId) && (!input.CourseId.HasValue || await db.Courses.AnyAsync(x => x.Id == input.CourseId)), "请选择有效的校区和课程");
            // All teachers can create their own class in any active campus/course.
            // The new assignment belongs only to its creator; existing classes remain scoped.
            Guard.That(actor.Role is "admin" or "teacher", "当前身份不能创建班级", 403);
            var id = new Guid(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"class:{actor.Id}:{input.RequestId}"))[..16]);
            var existing = await db.Classes.FindAsync(id);
            if (existing != null) { Guard.That(existing.Name == name && existing.CampusId == input.CampusId && (input.CourseId.HasValue ? await db.Assignments.AnyAsync(x => x.ClassId == id && x.CourseId == input.CourseId && x.StaffId == actor.Id) : existing.OwnerStaffId == actor.Id), "本次添加已完成，请返回首页查看", 409); return existing; }
            var created = new SchoolClass { Id = id, Name = name, CampusId = input.CampusId, OwnerStaffId = actor.Id };
            db.Classes.Add(created); if (input.CourseId.HasValue) db.Assignments.Add(new Assignment { ClassId = id, CourseId = input.CourseId.Value, StaffId = actor.Id });
            db.Audits.Add(new Audit { StaffId = actor.Id, Action = "classes.create", TargetId = id });
            await db.SaveChangesAsync(); return created;
        });
        app.MapPost("/api/classes/{classId:guid}/courses", async (Guid classId, ClassCourseInput input, Access a, Data db) => {
            await a.Class(classId); var actor = a.User;
            var schoolClass = await db.Classes.FindAsync(classId);
            Guard.That(actor.Role == "admin" || actor.Role == "teacher" && schoolClass!.OwnerStaffId == actor.Id, "只有班级创建者或管理员可以添加课程", 403);
            Guard.That(await db.Courses.AnyAsync(x => x.Id == input.CourseId), "请选择有效的课程");
            var existing = await db.Assignments.FirstOrDefaultAsync(x => x.ClassId == classId && x.CourseId == input.CourseId && x.StaffId == actor.Id);
            if (existing != null) return Results.NoContent();
            var id = new Guid(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"class-course:{actor.Id}:{classId}:{input.CourseId}"))[..16]);
            db.Assignments.Add(new Assignment { Id = id, ClassId = classId, CourseId = input.CourseId, StaffId = actor.Id });
            db.Audits.Add(new Audit { StaffId = actor.Id, Action = "classes.course.add", TargetId = classId });
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException) { db.ChangeTracker.Clear(); if (!await db.Assignments.AnyAsync(x => x.Id == id)) throw; }
            return Results.NoContent();
        });
        app.MapPost("/api/campuses", async (QuickCampusInput input, Access a, Data db) => {
            var actor = a.User; Guard.That(actor.Role is "admin" or "teacher", "当前身份不能创建校区", 403);
            var name = Guard.Name(input.Name).Normalize(); var city = Guard.Name(input.City).Normalize();
            var existing = await db.Campuses.FirstOrDefaultAsync(x => x.Name == name && x.City == city);
            if (existing != null) return existing;
            var id = new Guid(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"campus:{city}:{name}"))[..16]);
            Guard.That(!await db.Campuses.IgnoreQueryFilters().AnyAsync(x => x.Id == id), "此校区已停用，请联系管理员处理", 409);
            var campus = new Campus { Id = id, Name = name, City = city }; db.Campuses.Add(campus);
            db.Audits.Add(new Audit { StaffId = actor.Id, Action = "campuses.create", TargetId = id });
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException) { db.ChangeTracker.Clear(); var saved = await db.Campuses.FindAsync(id); if (saved == null) throw; return saved; }
            return campus;
        });
        app.MapPut("/api/classes/{classId:guid}", async (Guid classId, ClassEditInput input, Access a, Data db) => {
            await a.Class(classId);
            Guard.That(a.User.Role is "teacher" or "admin", "当前身份不能修改班级", 403);
            var name = Guard.Name(input.Name);
            Guard.That(await db.Campuses.AnyAsync(x => x.Id == input.CampusId), "请选择有效的校区");
            await using var tx = await db.Database.BeginTransactionAsync();
            Guard.That(await db.Classes.Where(x => x.Id == classId && x.Name == input.ExpectedName && x.CampusId == input.ExpectedCampusId)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.Name, name).SetProperty(x => x.CampusId, input.CampusId)) == 1,
                "班级信息已被修改，请刷新后重试", 409);
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "classes.update", TargetId = classId });
            await db.SaveChangesAsync(); await tx.CommitAsync(); return Results.NoContent();
        });
        app.MapPost("/api/classes/{classId:guid}/students/batch", async (Guid classId, StudentBatchInput input, Access a, Data db) => {
            await a.Class(classId); Guard.That(!string.IsNullOrWhiteSpace(input.RequestId) && input.RequestId.Length <= 120 && input.Names is { Length: > 0 and <= 200 }, "每次可添加1–200名学生");
            var names = input.Names.Select(x => Guard.Name(x ?? "")).ToArray();
            Guard.That(names.Distinct().Count() == names.Length, "名单中有重复姓名，请核对后再添加");
            var prefix = $"auto-{a.User.Id:N}-{Secrets.Hash(input.RequestId)[..24]}-";
            var existing = await db.Students.Where(x => x.ClassId == classId && x.Code.StartsWith(prefix)).ToArrayAsync();
            if (existing.Length > 0) { Guard.That(existing.Length == names.Length && names.Select((name, index) => existing.Any(s => s.Code == prefix + index && s.Name == name)).All(x => x), "本次名单已保存，请返回名单查看", 409); return existing; }
            var students = names.Select((name,index) => new Student { Name = name, ClassId = classId, Code = prefix + index }).ToArray();
            db.Students.AddRange(students);
            foreach (var student in students) db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "student.create", TargetId = student.Id });
            await db.SaveChangesAsync(); return students;
        });
        app.MapGet("/api/classes/{classId:guid}/students", async (Guid classId, Access a, Data db) => {
            await a.Class(classId); return await db.Students.Where(x => x.ClassId == classId).OrderBy(x => x.Code).ToArrayAsync();
        });
        app.MapPost("/api/classes/{classId:guid}/students", (Guid classId, StudentInput input, Access a, Data db) => Save(classId, null, input, a, db));
        app.MapPut("/api/classes/{classId:guid}/students/{id:guid}", (Guid classId, Guid id, StudentInput input, Access a, Data db) => Save(classId, id, input, a, db));
        app.MapPost("/api/classes/{classId:guid}/students/import", (Guid classId, HttpRequest req, Data db, Access a) => CatalogEndpoints.ImportRoster("students", req, db, a, classId)).DisableAntiforgery();
        app.MapGet("/api/admin/activity", async (int? page, Guid? staffId, Data db, Access a) => {
            a.Admin(); var index = Math.Clamp(page ?? 0, 0, 10000);
            var query = db.Audits.Where(x => (!staffId.HasValue || x.StaffId == staffId) && (!x.StaffId.HasValue || db.Staff.Any(s => s.Id == x.StaffId)));
            var rows = await (from audit in query join staff in db.Staff.IgnoreQueryFilters() on audit.StaffId equals staff.Id into actors from actor in actors.DefaultIfEmpty()
                orderby audit.CreatedAt descending, audit.Id
                select new { audit.Id, audit.Action, audit.TargetId, audit.CreatedAt, audit.StaffId, actorName = actor == null ? "系统维护" : actor.Name }).Skip(index * 50).Take(51).ToListAsync();
            var targets = rows.Select(x => x.TargetId).ToArray(); var names = new Dictionary<Guid,string>();
            foreach (var x in await db.Students.Where(x => targets.Contains(x.Id)).ToListAsync()) names[x.Id] = x.Name;
            foreach (var x in await db.Lectures.Where(x => targets.Contains(x.Id)).ToListAsync()) names[x.Id] = "第" + x.Sequence + "讲 · " + x.Title;
            foreach (var x in await db.Handouts.Where(x => targets.Contains(x.Id)).ToListAsync()) names[x.Id] = x.FileName;
            foreach (var x in await db.Documents.Where(x => targets.Contains(x.Id)).ToListAsync()) names[x.Id] = x.Kind == "file" ? x.SourceFileName : x.StudentName + "错题本";
            foreach (var x in await db.Classes.Where(x => targets.Contains(x.Id)).ToListAsync()) names[x.Id] = x.Name;
            foreach (var x in await db.LessonPlans.Where(x => targets.Contains(x.Id)).ToListAsync()) names[x.Id] = x.Title;
            return new { hasMore = rows.Count > 50, items = rows.Take(50).Select(x => new { x.Id, x.Action, x.TargetId, x.CreatedAt, x.StaffId, x.actorName, targetName = names.GetValueOrDefault(x.TargetId, "") }) };
        });
    }
    static async Task<Student> Save(Guid classId, Guid? id, StudentInput input, Access a, Data db)
    {
        await a.Class(classId);
        var student = id.HasValue ? await db.Students.FindAsync(id) ?? throw new ApiException(404, "学生不存在") : new Student { ClassId = classId };
        Guard.That(student.ClassId == classId, "无权修改其他班级学生", 403);
        student.Name = Guard.Name(input.Name); student.Code = string.IsNullOrWhiteSpace(input.Code) ? (id.HasValue ? student.Code : "auto-" + student.Id.ToString("N")) : Guard.Name(input.Code);
        if (!id.HasValue) db.Students.Add(student);
        db.Audits.Add(new Audit { StaffId = a.User.Id, Action = id.HasValue ? "student.update" : "student.create", TargetId = student.Id });
        await db.SaveChangesAsync(); return student;
    }
}
