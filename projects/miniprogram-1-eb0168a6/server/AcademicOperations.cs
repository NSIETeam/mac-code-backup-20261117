using Microsoft.EntityFrameworkCore;
using System.Globalization;
using System.Text;

namespace Siyue;

public class AcademicPreparation : Entity
{
    public Guid ClassSessionId { get; set; }
    public bool MaterialsReady { get; set; }
    public bool RosterChecked { get; set; }
    public bool HomeworkPlanned { get; set; }
    public string Note { get; set; } = "";
    public int Revision { get; set; }
    public Guid RecordedBy { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public class AcademicAttendance : Entity
{
    public Guid ClassSessionId { get; set; }
    public Guid StudentId { get; set; }
    public string Status { get; set; } = "unrecorded";
    public string Note { get; set; } = "";
    public string MakeupStatus { get; set; } = "none";
    public string? MakeupOn { get; set; }
    public Guid? TransferClassId { get; set; }
    public string? TransferOn { get; set; }
    public int Revision { get; set; }
    public Guid RecordedBy { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public class AcademicHomework : Entity
{
    public Guid ClassSessionId { get; set; }
    public string Title { get; set; } = "";
    public string Requirements { get; set; } = "";
    public string? DueOn { get; set; }
    public int Revision { get; set; }
    public Guid RecordedBy { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public class AcademicHomeworkRecord : Entity
{
    public Guid ClassSessionId { get; set; }
    public Guid StudentId { get; set; }
    public int HomeworkRevision { get; set; }
    public string Status { get; set; } = "unrecorded";
    public string Note { get; set; } = "";
    public int Revision { get; set; }
    public Guid RecordedBy { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public class AcademicTask : Entity
{
    public Guid ClassSessionId { get; set; }
    public Guid? StudentId { get; set; }
    public string Title { get; set; } = "";
    public string Category { get; set; } = "other";
    public string? DueOn { get; set; }
    public string Status { get; set; } = "open";
    public string Note { get; set; } = "";
    public string RequestKey { get; set; } = "";
    public string RequestHash { get; set; } = "";
    public Guid CreatedBy { get; set; }
    public Guid RecordedBy { get; set; }
    public int Revision { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public class AcademicOperationEvent : Entity
{
    public Guid EntityId { get; set; }
    public Guid? ClassSessionId { get; set; }
    public Guid ActorId { get; set; }
    public string Action { get; set; } = "";
    public string SnapshotJson { get; set; } = "{}";
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public partial class Data
{
    public DbSet<AcademicPreparation> AcademicPreparations => Set<AcademicPreparation>();
    public DbSet<AcademicAttendance> AcademicAttendances => Set<AcademicAttendance>();
    public DbSet<AcademicHomework> AcademicHomeworks => Set<AcademicHomework>();
    public DbSet<AcademicHomeworkRecord> AcademicHomeworkRecords => Set<AcademicHomeworkRecord>();
    public DbSet<AcademicTask> AcademicTasks => Set<AcademicTask>();
    public DbSet<AcademicOperationEvent> AcademicOperationEvents => Set<AcademicOperationEvent>();
}
public record AcademicPreparationInput(int Revision, bool MaterialsReady, bool RosterChecked, bool HomeworkPlanned, string Note);
public record AcademicAttendanceInput(int Revision, string Status, string Note, string MakeupStatus = "none", string? MakeupOn = null);
public record AcademicHomeworkInput(int Revision, string Title, string? DueOn, string Requirements);
public record AcademicHomeworkRecordInput(int Revision, int HomeworkRevision, string Status, string Note);
public record AcademicTaskInput(Guid ClassSessionId, Guid? StudentId, string Title, string Category, string? DueOn, string Note, string RequestKey);
public record AcademicTaskEditInput(int Revision, Guid ClassSessionId, Guid? StudentId, string Title, string Category, string? DueOn, string Note, string Status);
public record AcademicOperationsEvidence(string? AttendanceStatus, string? AttendanceNote, int AttendanceRevision,
    string? HomeworkTitle, string? HomeworkStatus, string? HomeworkNote, int HomeworkRevision, int HomeworkRecordRevision);
public record AcademicHomeworkMessageEvidence(string Title, string Requirements, string? DueOn, int Revision, string? StudentStatus, int StudentRevision);
public record AcademicTodo(string Key, string Kind, string Title, Guid? StudentId, string? StudentName, Guid ClassSessionId,
    string? DueOn, string Status, Guid? OriginId, string Action, bool Overdue);

public static class AcademicOperations
{
    public static void ConfigureModel(ModelBuilder b)
    {
        b.Entity<AcademicPreparation>().HasIndex(x => x.ClassSessionId).IsUnique();
        b.Entity<AcademicPreparation>().HasOne<ClassSession>().WithMany().HasForeignKey(x => x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicPreparation>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicAttendance>().HasIndex(x => new { x.ClassSessionId, x.StudentId }).IsUnique();
        b.Entity<AcademicAttendance>().HasOne<ClassSession>().WithMany().HasForeignKey(x => x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicAttendance>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicAttendance>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicAttendance>().HasIndex(x => new { x.TransferClassId, x.TransferOn });
        b.Entity<AcademicHomework>().HasIndex(x => x.ClassSessionId).IsUnique();
        b.Entity<AcademicHomework>().HasOne<ClassSession>().WithMany().HasForeignKey(x => x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicHomework>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicHomeworkRecord>().HasIndex(x => new { x.ClassSessionId, x.StudentId }).IsUnique();
        b.Entity<AcademicHomeworkRecord>().HasOne<ClassSession>().WithMany().HasForeignKey(x => x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicHomeworkRecord>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicHomeworkRecord>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicTask>().HasIndex(x => new { x.CreatedBy, x.RequestKey }).IsUnique();
        b.Entity<AcademicTask>().HasOne<ClassSession>().WithMany().HasForeignKey(x => x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicTask>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicTask>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicOperationEvent>().HasIndex(x => new { x.ClassSessionId, x.CreatedAt });
    }
    static string Clean(string? text, int maximum, string label, bool required = false)
    {
        Guard.That(text != null && text.Length <= maximum && (!required || !string.IsNullOrWhiteSpace(text)), $"{label}无效，最多{maximum}字");
        return text!.Trim();
    }
    static string? Date(string? value)
    {
        Guard.That(value == null || DateOnly.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _), "日期格式须为 yyyy-MM-dd");
        return value;
    }
    static string Today() => Clock.Now.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    static bool IsOverdue(string? due, string today) => due != null && string.CompareOrdinal(due, today) < 0;
    static void Audit(Data db, Access a, Entity entity, Guid sessionId, string action) => db.AcademicOperationEvents.Add(new() {
        EntityId = entity.Id, ClassSessionId = sessionId, ActorId = a.User.Id, Action = action, SnapshotJson = Json.Write((object)entity)
    });
    static async Task<Student> StudentScope(ClassSession session, Guid studentId, Data db)
    {
        var student = await db.Students.AsNoTracking().SingleOrDefaultAsync(x => x.Id == studentId && x.ClassId == session.ClassId);
        Guard.That(student != null && await db.ClassSessionMarks.AnyAsync(x => x.ClassSessionId == session.Id && x.StudentId == studentId), "学生不属于本课次名单", 403);
        return student!;
    }
    static async Task<Student[]> Roster(ClassSession session, Data db) => await db.Students.AsNoTracking()
        .Where(x => x.ClassId == session.ClassId && db.ClassSessionMarks.Any(m => m.ClassSessionId == session.Id && m.StudentId == x.Id))
        .OrderBy(x => x.Name).ThenBy(x => x.Id).ToArrayAsync();
    static object Attendance(Student s, AcademicAttendance? row) => new {
        studentId = s.Id, s.Name, revision = row?.Revision ?? 0, status = row?.Status ?? "unrecorded", note = row?.Note ?? "",
        makeupStatus = row?.MakeupStatus ?? "none", makeupOn = row?.MakeupOn, transferClassId = row?.TransferClassId, transferOn = row?.TransferOn
    };
    static string HomeworkStatus(AcademicHomework assignment, AcademicHomeworkRecord? record) => record?.HomeworkRevision == assignment.Revision ? record.Status : "unrecorded";
    static object HomeworkStudent(Student student, AcademicHomework? assignment, AcademicHomeworkRecord? row) => new {
        studentId = student.Id, student.Name, revision = row?.Revision ?? 0,
        status = assignment == null ? "unrecorded" : HomeworkStatus(assignment, row),
        note = assignment != null && row?.HomeworkRevision == assignment.Revision ? row.Note : ""
    };
    static void TaskFields(string category, string? due, string status)
    {
        Guard.That(category is "attendance" or "homework" or "correction" or "retest" or "parent" or "other", "待办类别无效");
        Guard.That(status is "open" or "done", "待办状态无效"); Date(due);
    }
    static object TaskView(AcademicTask row, string? studentName) => new { row.Id, row.ClassSessionId, row.StudentId, studentName, row.Title, row.Category, row.DueOn, row.Status, row.Note, row.Revision };

    public static async Task<AcademicOperationsEvidence> Evidence(Guid sessionId, Guid studentId, Data db)
    {
        var attendance = await db.AcademicAttendances.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == sessionId && x.StudentId == studentId);
        var homework = await db.AcademicHomeworks.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == sessionId);
        var record = await db.AcademicHomeworkRecords.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == sessionId && x.StudentId == studentId);
        var current = homework != null && record?.HomeworkRevision == homework.Revision;
        var attendanceNote = attendance?.Note;
        if (attendance?.Status == "transfer" && attendance.TransferClassId.HasValue) {
            var targetName = await db.Classes.Where(x => x.Id == attendance.TransferClassId).Select(x => x.Name).SingleOrDefaultAsync() ?? "待核实班级";
            attendanceNote = string.Join("；", new[] { attendanceNote, $"临时调班至{targetName}，日期{attendance.TransferOn}" }.Where(x => !string.IsNullOrWhiteSpace(x)));
        }
        return new(attendance?.Status, attendanceNote, attendance?.Revision ?? 0, homework?.Title,
            homework == null ? null : HomeworkStatus(homework, record), current ? record!.Note : null, homework?.Revision ?? 0, record?.Revision ?? 0);
    }

    public static async Task<AcademicHomeworkMessageEvidence?> HomeworkMessageEvidence(Guid sessionId, Guid? studentId, Data db)
    {
        var homework = await db.AcademicHomeworks.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == sessionId);
        if (homework == null) return null;
        var record = studentId.HasValue ? await db.AcademicHomeworkRecords.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == sessionId && x.StudentId == studentId.Value) : null;
        return new(homework.Title, homework.Requirements, homework.DueOn, homework.Revision, studentId.HasValue ? HomeworkStatus(homework, record) : null, record?.Revision ?? 0);
    }

    public static void MapEndpoints(RouteGroupBuilder group)
    {
        group.MapGet("/sessions/{id:guid}/preparation", async (Guid id, Data db, Access a) => {
            var session = await AcademicPilot.Session(id, db, a);
            var row = await db.AcademicPreparations.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == id);
            var handout = await db.Handouts.AsNoTracking().SingleAsync(x => x.Id == session.HandoutId);
            var lecture = await db.Lectures.AsNoTracking().SingleAsync(x => x.Id == handout.LectureId);
            var roster = await Roster(session, db);
            var questions = await db.Questions.AsNoTracking().Where(x => x.HandoutId == handout.Id).Select(x => x.Confirmed).ToArrayAsync();
            var staffId = a.User.Id; var admin = a.User.Role == "admin";
            var plans = await db.LessonPlans.AsNoTracking().Where(x => x.ClassId == session.ClassId && x.CourseId == session.CourseId && (admin || x.StaffId == staffId)).OrderByDescending(x => x.UpdatedAt).Select(x => new { x.Id, x.Title }).ToArrayAsync();
            var missing = new List<string>();
            if (roster.Length == 0) missing.Add("课次名单为空");
            if (handout.State != "published") missing.Add("讲义尚未发布");
            if (questions.Length == 0 || questions.Any(x => !x)) missing.Add("讲义题目未全部确认");
            if (plans.Length == 0) missing.Add("未登记教案");
            if (row?.RosterChecked != true) missing.Add("名单待核对");
            if (row?.MaterialsReady != true) missing.Add("资料待备齐");
            if (row?.HomeworkPlanned != true) missing.Add("作业安排待确认");
            if (!await db.AcademicHomeworks.AnyAsync(x => x.ClassSessionId == id)) missing.Add("作业内容未登记");
            return new {
                revision = row?.Revision ?? 0, session.LessonDate,
                className = (await db.Classes.AsNoTracking().SingleAsync(x => x.Id == session.ClassId)).Name,
                courseName = (await db.Courses.AsNoTracking().SingleAsync(x => x.Id == session.CourseId)).Name,
                title = lecture.Title, studentCount = roster.Length, handoutState = handout.State,
                confirmedQuestions = questions.Count(x => x), totalQuestions = questions.Length, lessonPlans = plans,
                materialsReady = row?.MaterialsReady ?? false, rosterChecked = row?.RosterChecked ?? false,
                homeworkPlanned = row?.HomeworkPlanned ?? false, note = row?.Note ?? "", missing
            };
        });
        group.MapPut("/sessions/{id:guid}/preparation", async (Guid id, AcademicPreparationInput input, Data db, Access a) => {
            await AcademicPilot.Session(id, db, a); var note = Clean(input.Note, 2000, "准备备注");
            var row = await db.AcademicPreparations.SingleOrDefaultAsync(x => x.ClassSessionId == id);
            AcademicPilot.Revision(input.Revision, row?.Revision ?? 0);
            if (row == null) { row = new() { ClassSessionId = id }; db.AcademicPreparations.Add(row); }
            row.MaterialsReady = input.MaterialsReady; row.RosterChecked = input.RosterChecked; row.HomeworkPlanned = input.HomeworkPlanned;
            row.Note = note; row.Revision++; row.RecordedBy = a.User.Id; row.UpdatedAt = Clock.Now;
            Audit(db, a, row, id, "preparation.saved"); await db.SaveChangesAsync(); return Results.Ok(new { row.Id, row.Revision });
        });
        group.MapGet("/sessions/{id:guid}/attendance", async (Guid id, Data db, Access a) => {
            var session = await AcademicPilot.Session(id, db, a); var roster = await Roster(session, db);
            var rows = await db.AcademicAttendances.AsNoTracking().Where(x => x.ClassSessionId == id).ToDictionaryAsync(x => x.StudentId);
            return roster.Select(s => Attendance(s, rows.GetValueOrDefault(s.Id)));
        });
        group.MapPut("/sessions/{id:guid}/attendance/{studentId:guid}", async (Guid id, Guid studentId, AcademicAttendanceInput input, Data db, Access a) => {
            var session = await AcademicPilot.Session(id, db, a); var student = await StudentScope(session, studentId, db);
            var note = Clean(input.Note, 2000, "考勤备注"); Date(input.MakeupOn);
            Guard.That(input.Status is "unrecorded" or "present" or "late" or "leave" or "absent", "考勤状态无效");
            if (input.Status is "leave" or "absent") {
                Guard.That(input.MakeupStatus is "pending" or "scheduled" or "done", "请为请假或缺勤记录补课状态");
                Guard.That(input.MakeupStatus == "pending" ? input.MakeupOn == null : input.MakeupOn != null, "待安排无需日期，已安排或已完成须填写补课日期");
                Guard.That(input.MakeupStatus != "done" || note.Length > 0, "补课完成须填写实际记录");
            } else Guard.That(input.MakeupStatus == "none" && input.MakeupOn == null, "当前考勤状态不应带补课安排");
            var row = await db.AcademicAttendances.SingleOrDefaultAsync(x => x.ClassSessionId == id && x.StudentId == studentId);
            AcademicPilot.Revision(input.Revision, row?.Revision ?? 0);
            if (row == null) { row = new() { ClassSessionId = id, StudentId = studentId }; db.AcademicAttendances.Add(row); }
            row.Status = input.Status; row.Note = note; row.MakeupStatus = input.MakeupStatus; row.MakeupOn = input.MakeupOn;
            row.TransferClassId = null; row.TransferOn = null;
            row.Revision++; row.RecordedBy = a.User.Id; row.UpdatedAt = Clock.Now;
            Audit(db, a, row, id, "attendance.saved"); await db.SaveChangesAsync(); return Results.Ok(Attendance(student, row));
        });
        group.MapGet("/sessions/{id:guid}/homework", async (Guid id, Data db, Access a) => {
            var session = await AcademicPilot.Session(id, db, a); var roster = await Roster(session, db);
            var row = await db.AcademicHomeworks.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == id);
            var records = await db.AcademicHomeworkRecords.AsNoTracking().Where(x => x.ClassSessionId == id).ToDictionaryAsync(x => x.StudentId);
            return new { id = row?.Id, revision = row?.Revision ?? 0, title = row?.Title ?? "", dueOn = row?.DueOn, requirements = row?.Requirements ?? "", students = roster.Select(s => HomeworkStudent(s, row, records.GetValueOrDefault(s.Id))) };
        });
        group.MapPut("/sessions/{id:guid}/homework", async (Guid id, AcademicHomeworkInput input, Data db, Access a) => {
            await AcademicPilot.Session(id, db, a); var title = Clean(input.Title, 120, "作业名称", true); var requirements = Clean(input.Requirements, 4000, "作业要求"); Date(input.DueOn);
            var row = await db.AcademicHomeworks.SingleOrDefaultAsync(x => x.ClassSessionId == id);
            AcademicPilot.Revision(input.Revision, row?.Revision ?? 0);
            if (row != null && row.Title == title && row.Requirements == requirements && row.DueOn == input.DueOn) return Results.Ok(new { row.Id, row.Revision });
            if (row == null) { row = new() { ClassSessionId = id }; db.AcademicHomeworks.Add(row); }
            row.Title = title; row.Requirements = requirements; row.DueOn = input.DueOn;
            row.Revision++; row.RecordedBy = a.User.Id; row.UpdatedAt = Clock.Now;
            Audit(db, a, row, id, "homework.configured"); await db.SaveChangesAsync(); return Results.Ok(new { row.Id, row.Revision });
        });
        group.MapPut("/sessions/{id:guid}/homework/{studentId:guid}", async (Guid id, Guid studentId, AcademicHomeworkRecordInput input, Data db, Access a) => {
            var session = await AcademicPilot.Session(id, db, a); var student = await StudentScope(session, studentId, db);
            var assignment = await db.AcademicHomeworks.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == id) ?? throw new ApiException(409, "请先登记本课次作业");
            AcademicPilot.Revision(input.HomeworkRevision, assignment.Revision);
            Guard.That(input.Status is "unrecorded" or "missing" or "submitted" or "reviewed", "作业状态无效");
            var note = Clean(input.Note, 2000, "作业备注");
            var row = await db.AcademicHomeworkRecords.SingleOrDefaultAsync(x => x.ClassSessionId == id && x.StudentId == studentId);
            AcademicPilot.Revision(input.Revision, row?.Revision ?? 0);
            if (row == null) { row = new() { ClassSessionId = id, StudentId = studentId }; db.AcademicHomeworkRecords.Add(row); }
            row.HomeworkRevision = assignment.Revision; row.Status = input.Status; row.Note = note;
            row.Revision++; row.RecordedBy = a.User.Id; row.UpdatedAt = Clock.Now;
            Audit(db, a, row, id, "homework.student_saved"); await db.SaveChangesAsync(); return Results.Ok(HomeworkStudent(student, assignment, row));
        });
        group.MapGet("/tasks", async (Guid? classSessionId, Data db, Access a) => {
            var scope = await Snapshot.Load(classSessionId, db, a);
            return scope.Tasks.OrderBy(x => x.Status).ThenBy(x => x.DueOn ?? "9999-12-31").ThenByDescending(x => x.CreatedAt).Select(x => TaskView(x, scope.Name(x.ClassSessionId, x.StudentId)));
        });
        group.MapPost("/tasks", async (AcademicTaskInput input, Data db, Access a) => {
            var session = await AcademicPilot.Session(input.ClassSessionId, db, a);
            if (input.StudentId.HasValue) await StudentScope(session, input.StudentId.Value, db);
            var title = Clean(input.Title, 120, "待办标题", true); var note = Clean(input.Note, 4000, "待办备注");
            var key = Clean(input.RequestKey, 100, "请求标识", true); Guard.That(key.Length >= 8, "请求标识至少8位");
            TaskFields(input.Category, input.DueOn, "open");
            var hash = Secrets.Hash(Json.Write(new { input.ClassSessionId, input.StudentId, title, input.Category, input.DueOn, note }));
            var row = await db.AcademicTasks.SingleOrDefaultAsync(x => x.CreatedBy == a.User.Id && x.RequestKey == key);
            if (row != null) { Guard.That(row.RequestHash == hash, "请求标识已用于其他待办", 409); return Results.Ok(new { row.Id, row.Revision }); }
            row = new() { ClassSessionId = input.ClassSessionId, StudentId = input.StudentId, Title = title, Category = input.Category, DueOn = input.DueOn, Note = note, RequestKey = key, RequestHash = hash, CreatedBy = a.User.Id, RecordedBy = a.User.Id, Revision = 1 };
            db.AcademicTasks.Add(row); Audit(db, a, row, row.ClassSessionId, "task.created"); await db.SaveChangesAsync(); return Results.Ok(new { row.Id, row.Revision });
        });
        group.MapPut("/tasks/{id:guid}", async (Guid id, AcademicTaskEditInput input, Data db, Access a) => {
            var row = await db.AcademicTasks.SingleOrDefaultAsync(x => x.Id == id) ?? throw new ApiException(404, "待办不存在");
            var session = await AcademicPilot.Session(row.ClassSessionId, db, a);
            if (row.StudentId.HasValue) await StudentScope(session, row.StudentId.Value, db);
            AcademicPilot.Revision(input.Revision, row.Revision);
            Guard.That(input.ClassSessionId == row.ClassSessionId && input.StudentId == row.StudentId, "已有待办不可变更课次或学生", 409);
            TaskFields(input.Category, input.DueOn, input.Status);
            row.Title = Clean(input.Title, 120, "待办标题", true); row.Note = Clean(input.Note, 4000, "待办备注");
            row.Category = input.Category; row.DueOn = input.DueOn; row.Status = input.Status;
            row.Revision++; row.RecordedBy = a.User.Id; row.UpdatedAt = Clock.Now;
            Audit(db, a, row, row.ClassSessionId, "task.saved"); await db.SaveChangesAsync(); return Results.Ok(new { row.Id, row.Revision });
        });
        group.MapGet("/todos", async (Guid? classSessionId, Data db, Access a) => await Todos(await Snapshot.Load(classSessionId, db, a), db));
        group.MapGet("/statistics", async (string from, string to, Guid? classSessionId, Data db, Access a) => {
            Date(from); Date(to); Guard.That(string.CompareOrdinal(from, to) <= 0 && DateOnly.ParseExact(to, "yyyy-MM-dd", CultureInfo.InvariantCulture).DayNumber - DateOnly.ParseExact(from, "yyyy-MM-dd", CultureInfo.InvariantCulture).DayNumber <= 92, "统计范围应为1至93天");
            var scope = await Snapshot.Load(classSessionId, db, a, from, to); return Statistics(scope, from, to);
        });
    }

    record RosterRow(Guid ClassSessionId, Guid StudentId, string Name, bool Reviewed, string WrongIdsJson);
    sealed class Snapshot
    {
        public ClassSession[] Sessions { get; init; } = [];
        public Question[] Questions { get; init; } = [];
        public RosterRow[] Students { get; init; } = [];
        public AcademicAttendance[] Attendances { get; init; } = [];
        public AcademicHomework[] Homeworks { get; init; } = [];
        public AcademicHomeworkRecord[] HomeworkRecords { get; init; } = [];
        public AcademicFeedback[] Feedbacks { get; init; } = [];
        public AcademicCommunication[] Communications { get; init; } = [];
        public AcademicTask[] Tasks { get; init; } = [];
        public AcademicMessage[] Messages { get; init; } = [];
        public AcademicMessageCommunication[] MessageCommunications { get; init; } = [];
        public string? Name(Guid sessionId, Guid? studentId) => Students.FirstOrDefault(x => x.ClassSessionId == sessionId && x.StudentId == studentId)?.Name;
        public Guid[] WrongIds(RosterRow row) {
            var session = Sessions.Single(x => x.Id == row.ClassSessionId);
            var ids = Questions.Where(x => x.HandoutId == session.HandoutId).Select(x => x.Id).ToHashSet();
            return Json.Read<Guid[]>(row.WrongIdsJson).Distinct().Where(ids.Contains).ToArray();
        }
        public static async Task<Snapshot> Load(Guid? sessionId, Data db, Access a, string? from = null, string? to = null)
        {
            if (sessionId.HasValue) await AcademicPilot.Session(sessionId.Value, db, a);
            var uid = a.User.Id; var admin = a.User.Role == "admin";
            var query = from s in db.ClassSessions.AsNoTracking()
                join c in db.Classes on s.ClassId equals c.Id join course in db.Courses on s.CourseId equals course.Id
                where (!sessionId.HasValue || s.Id == sessionId) && (admin || db.Assignments.Any(x => x.StaffId == uid && x.ClassId == s.ClassId && x.CourseId == s.CourseId))
                select s;
            if (from != null) query = query.Where(s => s.LessonDate.CompareTo(from) >= 0);
            if (to != null) query = query.Where(s => s.LessonDate.CompareTo(to) <= 0);
            var sessions = await query.ToArrayAsync(); var ids = sessions.Select(x => x.Id).ToArray();
            var handoutIds = sessions.Select(x => x.HandoutId).Distinct().ToArray();
            var questions = await db.Questions.AsNoTracking().Where(x => handoutIds.Contains(x.HandoutId)).Select(x => new Question { Id = x.Id, HandoutId = x.HandoutId, Label = x.Label }).ToArrayAsync();
            var students = await (from m in db.ClassSessionMarks.AsNoTracking() join s in db.ClassSessions on m.ClassSessionId equals s.Id join st in db.Students on m.StudentId equals st.Id
                where ids.Contains(s.Id) && st.ClassId == s.ClassId select new RosterRow(s.Id, st.Id, st.Name, m.Reviewed, m.WrongIdsJson)).ToArrayAsync();
            var pairs = students.Select(x => (x.ClassSessionId, x.StudentId)).ToHashSet();
            var feedbacks = (await db.AcademicFeedbacks.AsNoTracking().Where(x => ids.Contains(x.ClassSessionId)).ToArrayAsync()).Where(x => pairs.Contains((x.ClassSessionId, x.StudentId))).ToArray();
            var feedbackIds = feedbacks.Select(x => x.Id).ToArray();
            var messages = (await db.AcademicMessages.AsNoTracking().Where(x => ids.Contains(x.ClassSessionId)).ToArrayAsync()).Where(x => x.StudentId == null || pairs.Contains((x.ClassSessionId, x.StudentId.Value))).ToArray();
            var messageIds = messages.Select(x => x.Id).ToArray();
            return new() {
                Sessions = sessions, Questions = questions, Students = students, Feedbacks = feedbacks, Messages = messages,
                Attendances = (await db.AcademicAttendances.AsNoTracking().Where(x => ids.Contains(x.ClassSessionId)).ToArrayAsync()).Where(x => pairs.Contains((x.ClassSessionId, x.StudentId))).ToArray(),
                Homeworks = await db.AcademicHomeworks.AsNoTracking().Where(x => ids.Contains(x.ClassSessionId)).ToArrayAsync(),
                HomeworkRecords = (await db.AcademicHomeworkRecords.AsNoTracking().Where(x => ids.Contains(x.ClassSessionId)).ToArrayAsync()).Where(x => pairs.Contains((x.ClassSessionId, x.StudentId))).ToArray(),
                Communications = await db.AcademicCommunications.AsNoTracking().Where(x => feedbackIds.Contains(x.FeedbackId)).ToArrayAsync(),
                MessageCommunications = await db.AcademicMessageCommunications.AsNoTracking().Where(x => messageIds.Contains(x.MessageId)).ToArrayAsync(),
                Tasks = (await db.AcademicTasks.AsNoTracking().Where(x => ids.Contains(x.ClassSessionId)).ToArrayAsync()).Where(x => x.StudentId == null || pairs.Contains((x.ClassSessionId, x.StudentId.Value))).ToArray()
            };
        }
    }

    static async Task<AcademicTodo[]> Todos(Snapshot scope, Data db)
    {
        var result = new List<AcademicTodo>(); var today = Today();
        var sessions = scope.Sessions.ToDictionary(x => x.Id);
        var attendance = scope.Attendances.ToDictionary(x => (x.ClassSessionId, x.StudentId));
        var homeworks = scope.Homeworks.ToDictionary(x => x.ClassSessionId);
        var homeworkRecords = scope.HomeworkRecords.ToDictionary(x => (x.ClassSessionId, x.StudentId));
        void Add(string key, string kind, string title, Guid sessionId, Guid? studentId, string? due, Guid? origin, string action, string status = "open") =>
            result.Add(new(key, kind, title, studentId, scope.Name(sessionId, studentId), sessionId, due, status, origin, action, IsOverdue(due, today)));
        foreach (var student in scope.Students) {
            var session = sessions[student.ClassSessionId]; var key = (session.Id, student.StudentId);
            var att = attendance.GetValueOrDefault(key);
            if (att == null || att.Status == "unrecorded") Add($"attendance:{session.Id}:{student.StudentId}", "attendance_unrecorded", "考勤未登记", session.Id, student.StudentId, session.LessonDate, att?.Id, "attendance");
            if (att?.Status is "leave" or "absent" && att.MakeupStatus != "done") Add($"makeup:{att.Id}", "makeup", att.MakeupStatus == "scheduled" ? "补课待完成" : "补课待安排", session.Id, student.StudentId, att.MakeupOn, att.Id, "attendance");
            if (homeworks.TryGetValue(session.Id, out var homework)) {
                var record = homeworkRecords.GetValueOrDefault(key); var status = HomeworkStatus(homework, record);
                var title = status switch { "missing" => "作业未提交", "submitted" => "作业待批改", "unrecorded" => "作业提交情况未登记", _ => "" };
                if (title.Length > 0) Add($"homework:{session.Id}:{student.StudentId}", "homework_" + status, title, session.Id, student.StudentId, homework.DueOn, record?.Id ?? homework.Id, "homework", status);
            }
            if (!student.Reviewed) Add($"marking:{session.Id}:{student.StudentId}", "marking", "课堂错题待教师登记", session.Id, student.StudentId, session.LessonDate, null, "wrong");
        }
        foreach (var feedback in scope.Feedbacks) {
            if (feedback.State == "draft") Add("feedback:" + feedback.Id, "feedback_review", "学情反馈待审核", feedback.ClassSessionId, feedback.StudentId, null, feedback.Id, "review");
            if (feedback.State == "approved") Add("feedback:" + feedback.Id, "feedback_send", "已审核反馈待沟通", feedback.ClassSessionId, feedback.StudentId, null, feedback.Id, "communication");
        }
        foreach (var communication in scope.Communications.Where(x => x.Status != "resolved")) {
            var feedback = scope.Feedbacks.Single(x => x.Id == communication.FeedbackId);
            Add("feedback-communication:" + communication.Id, "parent_" + communication.Status,
                communication.Status == "follow_up" ? "家长沟通待跟进" : "等待家长回复", feedback.ClassSessionId, feedback.StudentId,
                communication.FollowUpOn, feedback.Id, "communication", communication.Status);
        }
        // Learning-feedback messages are retained only as historical records.
        // Their current draft and review state live in AcademicFeedbacks.
        foreach (var message in scope.Messages.Where(x => x.Kind != "learning_feedback")) {
            if (message.State == "draft") Add("message:" + message.Id, "message_review", message.Title + " · 待审核", message.ClassSessionId, message.StudentId, null, message.Id, "review");
            if (message.State == "approved") Add("message:" + message.Id, "message_send", message.Title + " · 待沟通", message.ClassSessionId, message.StudentId, null, message.Id, "communication");
        }
        foreach (var communication in scope.MessageCommunications.Where(x => x.Status != "resolved")) {
            var message = scope.Messages.Single(x => x.Id == communication.MessageId);
            Add("message-communication:" + communication.Id, "message_parent_" + communication.Status,
                communication.Status == "follow_up" ? message.Title + " · 待跟进" : message.Title + " · 等待回复", message.ClassSessionId, message.StudentId,
                communication.FollowUpOn, message.Id, "communication", communication.Status);
        }
        foreach (var task in scope.Tasks.Where(x => x.Status == "open")) Add("task:" + task.Id, "manual", task.Title, task.ClassSessionId, task.StudentId, task.DueOn, task.Id, "followup");
        var ids = sessions.Keys.ToArray();
        var practice = (await db.AcademicPractices.AsNoTracking().Where(x => ids.Contains(x.ClassSessionId)).ToArrayAsync()).ToDictionary(x => (x.ClassSessionId, x.StudentId, x.QuestionId));
        var studentIds = scope.Students.Select(x => x.StudentId).Distinct().ToArray();
        var questionIds = scope.Questions.Select(x => x.Id).ToArray();
        var retests = await db.LearningEvents.AsNoTracking().Where(x => x.Kind == "retest" && studentIds.Contains(x.StudentId) && questionIds.Contains(x.QuestionId)).OrderByDescending(x => x.RecordedAt).ThenByDescending(x => x.Id).ToArrayAsync();
        foreach (var student in scope.Students.Where(x => x.Reviewed)) {
            var session = sessions[student.ClassSessionId];
            foreach (var questionId in scope.WrongIds(student)) {
                var row = practice.GetValueOrDefault((session.Id, student.StudentId, questionId));
                var legacy = retests.FirstOrDefault(x => x.StudentId == student.StudentId && x.QuestionId == questionId && x.ClassId == session.ClassId && x.CourseId == session.CourseId && x.RecordedAt >= session.CreatedAt);
                var useBeta = row != null && row.RetestState != "unrecorded" && row.RetestAt.HasValue && (legacy == null || row.RetestAt.Value >= legacy.RecordedAt);
                var retest = useBeta ? row!.RetestState : legacy?.State ?? "unrecorded";
                var needsCorrection = row?.Corrected != true;
                if (!needsCorrection && retest == "correct") continue;
                var question = scope.Questions.Single(x => x.Id == questionId);
                var actions = new List<string>();
                if (needsCorrection) actions.Add("订正待登记");
                if (retest != "correct") actions.Add(retest == "wrong" ? "复测仍错误" : "待完成复测");
                Add($"practice:{session.Id}:{student.StudentId}:{questionId}", "practice", question.Label + "：" + string.Join("、", actions), session.Id, student.StudentId, null, questionId, "wrong");
            }
        }
        return result.OrderByDescending(x => x.Overdue).ThenBy(x => x.DueOn ?? "9999-12-31").ThenBy(x => x.ClassSessionId).ThenBy(x => x.StudentName).ThenBy(x => x.Key).ToArray();
    }

    record DailyCounts(string Date, int SessionCount, int StudentSessions, int Present, int Late, int Leave, int Absent,
        int AttendanceUnrecorded, int HomeworkMissing, int HomeworkSubmitted, int HomeworkReviewed, int HomeworkUnrecorded, int WrongQuestionCount, int Transfer);
    static object Statistics(Snapshot scope, string from, string to)
    {
        var attendance = scope.Attendances.ToDictionary(x => (x.ClassSessionId, x.StudentId));
        var homeworks = scope.Homeworks.ToDictionary(x => x.ClassSessionId);
        var records = scope.HomeworkRecords.ToDictionary(x => (x.ClassSessionId, x.StudentId));
        string Att(RosterRow student) => attendance.GetValueOrDefault((student.ClassSessionId, student.StudentId))?.Status ?? "unrecorded";
        string? Hw(RosterRow student) => homeworks.TryGetValue(student.ClassSessionId, out var homework) ? HomeworkStatus(homework, records.GetValueOrDefault((student.ClassSessionId, student.StudentId))) : null;
        var attStates = scope.Students.Select(Att).ToArray(); var hwStates = scope.Students.Select(Hw).Where(x => x != null).ToArray();
        var attendanceCounts = new { unrecorded = attStates.Count(x => x == "unrecorded"), present = attStates.Count(x => x == "present"), late = attStates.Count(x => x == "late"), leave = attStates.Count(x => x == "leave"), absent = attStates.Count(x => x == "absent"), transfer = attStates.Count(x => x == "transfer") };
        var homeworkCounts = new { configuredSessions = scope.Homeworks.Length, unconfiguredSessions = scope.Sessions.Length - scope.Homeworks.Length,
            unrecorded = hwStates.Count(x => x == "unrecorded"), missing = hwStates.Count(x => x == "missing"), submitted = hwStates.Count(x => x == "submitted"), reviewed = hwStates.Count(x => x == "reviewed") };
        var marking = new { reviewed = scope.Students.Count(x => x.Reviewed), unreviewed = scope.Students.Count(x => !x.Reviewed), wrongQuestionCount = scope.Students.Where(x => x.Reviewed).Sum(x => scope.WrongIds(x).Length) };
        var feedback = new { draft = scope.Feedbacks.Count(x => x.State == "draft"), approved = scope.Feedbacks.Count(x => x.State == "approved"), communicated = scope.Feedbacks.Count(x => x.State == "communicated") };
        var notices = scope.Messages.Where(x => x.Kind != "learning_feedback").ToArray();
        var messages = new { draft = notices.Count(x => x.State == "draft"), approved = notices.Count(x => x.State == "approved"), communicated = notices.Count(x => x.State == "communicated") };
        var communicationStates = scope.Communications.Select(x => x.Status).Concat(scope.MessageCommunications.Select(x => x.Status)).ToArray();
        var communications = new { awaitingReply = communicationStates.Count(x => x == "awaiting_reply"), followUp = communicationStates.Count(x => x == "follow_up"), resolved = communicationStates.Count(x => x == "resolved") };
        var today = Today();
        var tasks = new { open = scope.Tasks.Count(x => x.Status == "open"), done = scope.Tasks.Count(x => x.Status == "done"), overdue = scope.Tasks.Count(x => x.Status == "open" && IsOverdue(x.DueOn, today)) };
        var daily = scope.Sessions.GroupBy(x => x.LessonDate).OrderBy(x => x.Key).Select(group => {
            var ids = group.Select(x => x.Id).ToHashSet(); var students = scope.Students.Where(x => ids.Contains(x.ClassSessionId)).ToArray();
            var aa = students.Select(Att).ToArray(); var hh = students.Select(Hw).ToArray();
            return new DailyCounts(group.Key, group.Count(), students.Length, aa.Count(x => x == "present"), aa.Count(x => x == "late"), aa.Count(x => x == "leave"), aa.Count(x => x == "absent"), aa.Count(x => x == "unrecorded"), hh.Count(x => x == "missing"), hh.Count(x => x == "submitted"), hh.Count(x => x == "reviewed"), hh.Count(x => x == "unrecorded"), students.Where(x => x.Reviewed).Sum(x => scope.WrongIds(x).Length), aa.Count(x => x == "transfer"));
        }).ToArray();
        var report = new StringBuilder();
        report.AppendLine($"教务周报（Beta）｜{from} 至 {to}");
        report.AppendLine("统计口径：按课次日期汇总这些课次的当前登记状态；未登记单独列示，沟通次数指人工确认的发送记录。");
        report.AppendLine($"课次 {scope.Sessions.Length} 节，名单 {scope.Students.Length} 人次。");
        report.AppendLine($"考勤：到课 {attendanceCounts.present}，迟到 {attendanceCounts.late}，请假 {attendanceCounts.leave}，缺勤 {attendanceCounts.absent}，临时调班 {attendanceCounts.transfer}，未登记 {attendanceCounts.unrecorded} 人次。");
        report.AppendLine($"作业：已安排 {homeworkCounts.configuredSessions} 节，未安排 {homeworkCounts.unconfiguredSessions} 节；已批改 {homeworkCounts.reviewed}，已交待批 {homeworkCounts.submitted}，确认未交 {homeworkCounts.missing}，提交情况未登记 {homeworkCounts.unrecorded} 人次。");
        report.AppendLine($"课堂登记：已批改 {marking.reviewed}，未批改 {marking.unreviewed} 人次；已批改记录中的错题 {marking.wrongQuestionCount} 道次。");
        report.AppendLine($"学情反馈：待审核 {feedback.draft}，已审核待沟通 {feedback.approved}，已登记沟通 {feedback.communicated} 份。");
        report.AppendLine($"通知草稿：待审核 {messages.draft}，已审核待沟通 {messages.approved}，已登记沟通 {messages.communicated} 份。");
        report.AppendLine($"家长沟通：等待回复 {communications.awaitingReply}，待跟进 {communications.followUp}，已处理 {communications.resolved} 条。");
        report.AppendLine($"人工待办：未完成 {tasks.open}，其中截至{today}已逾期 {tasks.overdue}；已完成 {tasks.done} 条。");
        return new { from, to, sessionCount = scope.Sessions.Length, studentSessions = scope.Students.Length, attendance = attendanceCounts,
            homework = homeworkCounts, marking, feedback, messages, communications, tasks, daily, weeklyReport = report.ToString().TrimEnd() };
    }
}
