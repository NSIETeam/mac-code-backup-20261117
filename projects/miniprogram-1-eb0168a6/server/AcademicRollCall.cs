using System.Data;
using System.Globalization;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Npgsql;

namespace Siyue;

public record AcademicRollCallExceptionInput(Guid StudentId, string Status, Guid? TransferClassId = null, string? TransferOn = null);
public record AcademicRollCallInput(string Snapshot, bool Confirmed, AcademicRollCallExceptionInput[] Exceptions);

public static class AcademicRollCall
{
    sealed record RosterStudent(Guid StudentId, string Name);
    sealed record TransferClass(Guid Id, string Name);

    static async Task<RosterStudent[]> Roster(ClassSession session, Data db) => await
        (from mark in db.ClassSessionMarks.AsNoTracking()
         join student in db.Students on mark.StudentId equals student.Id
         where mark.ClassSessionId == session.Id && student.ClassId == session.ClassId
         orderby student.Name, student.Id
         select new RosterStudent(student.Id, student.Name)).ToArrayAsync();

    static async Task<TransferClass[]> TransferClasses(ClassSession session, Data db, Access a)
    {
        var uid = a.User.Id; var admin = a.User.Role == "admin";
        return await db.Classes.AsNoTracking().Where(c => c.Id != session.ClassId &&
            (admin
                ? db.Assignments.Any(x => x.ClassId == c.Id && x.CourseId == session.CourseId) || db.ClassSessions.Any(x => x.ClassId == c.Id && x.CourseId == session.CourseId)
                : db.Assignments.Any(x => x.StaffId == uid && x.ClassId == c.Id && x.CourseId == session.CourseId)))
            .OrderBy(c => c.Name).ThenBy(c => c.Id).Select(c => new TransferClass(c.Id, c.Name)).ToArrayAsync();
    }

    static string Snapshot(ClassSession session, RosterStudent[] roster, AcademicAttendance[] records) => Secrets.Hash(Json.Write(new {
        session.Id, session.ClassId, session.CourseId, session.LessonDate,
        students = roster.OrderBy(x => x.StudentId),
        attendance = records.OrderBy(x => x.StudentId).Select(x => new {
            x.StudentId, x.Revision, x.Status, x.Note, x.MakeupStatus, x.MakeupOn, x.TransferClassId, x.TransferOn
        })
    }));

    static async Task<object> View(ClassSession session, Data db, Access a)
    {
        var roster = await Roster(session, db);
        var ids = roster.Select(x => x.StudentId).ToArray();
        var records = await db.AcademicAttendances.AsNoTracking().Where(x => x.ClassSessionId == session.Id && ids.Contains(x.StudentId)).ToArrayAsync();
        var attendance = records.ToDictionary(x => x.StudentId);
        var targetIds = records.Where(x => x.TransferClassId.HasValue).Select(x => x.TransferClassId!.Value).Distinct().ToArray();
        var targetNames = await db.Classes.AsNoTracking().Where(x => targetIds.Contains(x.Id)).ToDictionaryAsync(x => x.Id, x => x.Name);
        var incoming = await (from row in db.AcademicAttendances.AsNoTracking()
            join source in db.ClassSessions on row.ClassSessionId equals source.Id
            join sourceClass in db.Classes on source.ClassId equals sourceClass.Id
            join student in db.Students on row.StudentId equals student.Id
            where row.Status == "transfer" && row.TransferClassId == session.ClassId && row.TransferOn == session.LessonDate &&
                source.CourseId == session.CourseId && student.ClassId == source.ClassId &&
                db.ClassSessionMarks.Any(x => x.ClassSessionId == source.Id && x.StudentId == student.Id)
            orderby student.Name
            select new { studentId = student.Id, student.Name, sourceClassName = sourceClass.Name, sourceLessonDate = source.LessonDate, row.TransferOn }).ToArrayAsync();
        return new {
            snapshot = Snapshot(session, roster, records),
            students = roster.Select(student => {
                var row = attendance.GetValueOrDefault(student.StudentId);
                return new {
                    student.StudentId, student.Name, status = row?.Status ?? "unrecorded", revision = row?.Revision ?? 0,
                    note = row?.Note ?? "", transferClassId = row?.TransferClassId,
                    transferClassName = row?.TransferClassId is Guid target ? targetNames.GetValueOrDefault(target, "待核实班级") : null,
                    transferOn = row?.TransferOn
                };
            }),
            transferClasses = await TransferClasses(session, db, a), incomingTransfers = incoming
        };
    }

    static async Task<object> Save(Guid id, AcademicRollCallInput input, Data db, Access a)
    {
        Guard.That(input.Confirmed, "请确认本次点名结果后保存");
        Guard.That(input.Snapshot is { Length: 64 }, "请先读取本课次点名名单");
        Guard.That(input.Exceptions is { Length: <= 1000 } && input.Exceptions.All(x => x != null), "异常学生名单无效");
        Guard.That(input.Exceptions.Select(x => x.StudentId).Distinct().Count() == input.Exceptions.Length, "异常学生不能重复");

        // One transaction includes the roster snapshot check and every attendance row.
        // Serializable isolation also protects initially missing rows during concurrent saves.
        await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var session = await AcademicPilot.Session(id, db, a);
        var roster = await Roster(session, db);
        var ids = roster.Select(x => x.StudentId).ToHashSet();
        Guard.That(roster.Length > 0, "本课次没有可点名的学生");
        var records = await db.AcademicAttendances.Where(x => x.ClassSessionId == id && ids.Contains(x.StudentId)).ToArrayAsync();
        Guard.That(Snapshot(session, roster, records) == input.Snapshot, "名单或点名记录已更新，请刷新后重新核对", 409);
        var targets = (await TransferClasses(session, db, a)).Select(x => x.Id).ToHashSet();
        foreach (var item in input.Exceptions) {
            Guard.That(ids.Contains(item.StudentId), "学生不属于本课次名单", 403);
            Guard.That(item.Status is "leave" or "transfer", "请选择请假或临时调班级");
            if (item.Status == "transfer") {
                Guard.That(item.TransferClassId.HasValue && item.TransferClassId != session.ClassId, "请选择其他目标班级");
                await a.Class(item.TransferClassId.Value, session.CourseId);
                Guard.That(targets.Contains(item.TransferClassId.Value), "目标班级未开设本课程或没有访问权限", 403);
                Guard.That(item.TransferOn != null && DateOnly.TryParseExact(item.TransferOn, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _), "请选择有效的临时调班日期");
            } else Guard.That(item.TransferClassId == null && item.TransferOn == null, "请假记录不应带临时调班安排");
        }

        var exceptions = input.Exceptions.ToDictionary(x => x.StudentId);
        var existing = records.ToDictionary(x => x.StudentId);
        foreach (var student in roster) {
            var exception = exceptions.GetValueOrDefault(student.StudentId);
            var status = exception?.Status ?? "present";
            var row = existing.GetValueOrDefault(student.StudentId);
            if (row != null && row.Status == status && row.TransferClassId == exception?.TransferClassId && row.TransferOn == exception?.TransferOn) continue;
            if (row == null) {
                row = new() { ClassSessionId = id, StudentId = student.StudentId };
                db.AcademicAttendances.Add(row);
            }
            if (status == "leave") {
                if (row.Status != "leave" || row.MakeupStatus == "none") { row.MakeupStatus = "pending"; row.MakeupOn = null; }
            } else { row.MakeupStatus = "none"; row.MakeupOn = null; }
            row.Status = status; row.TransferClassId = exception?.TransferClassId; row.TransferOn = exception?.TransferOn;
            row.Revision++; row.RecordedBy = a.User.Id; row.UpdatedAt = Clock.Now;
            db.AcademicOperationEvents.Add(new() {
                EntityId = row.Id, ClassSessionId = id, ActorId = a.User.Id,
                Action = "attendance.roll-call", SnapshotJson = Json.Write(row)
            });
        }
        await db.SaveChangesAsync();
        await transaction.CommitAsync();
        return await View(session, db, a);
    }

    public static void MapEndpoints(RouteGroupBuilder group)
    {
        group.MapGet("/sessions/{id:guid}/roll-call", async (Guid id, Data db, Access a) =>
            await View(await AcademicPilot.Session(id, db, a), db, a));
        group.MapPut("/sessions/{id:guid}/roll-call", async (Guid id, AcademicRollCallInput input, Data db, Access a) => {
            try { return Results.Ok(await Save(id, input, db, a)); }
            catch (PostgresException error) when (error.SqlState is "40001" or "40P01") { throw new ApiException(409, "点名记录正在被修改，请刷新后重新核对"); }
            catch (SqliteException error) when (error.SqliteErrorCode is 5 or 6) { throw new ApiException(409, "点名记录正在被修改，请刷新后重新核对"); }
        });
    }
}
