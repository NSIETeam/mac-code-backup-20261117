using Microsoft.EntityFrameworkCore;
using System.Globalization;
namespace Siyue;

public class AcademicPractice : Entity
{
    public Guid ClassSessionId { get; set; }
    public Guid StudentId { get; set; }
    public Guid QuestionId { get; set; }
    public bool Corrected { get; set; }
    public DateTimeOffset? RetestAt { get; set; }
    public string RetestState { get; set; } = "unrecorded";
    public string Note { get; set; } = "";
    public int Revision { get; set; }
    public Guid RecordedBy { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public record AcademicPracticeInput(int Revision, bool Corrected, string RetestState, string Note, bool RecordRetest = false);
public record AcademicPracticeEvidence(Guid QuestionId, bool Corrected, string RetestState, string Note, int Revision, DateTimeOffset? RetestAt);
public class AcademicMessage : Entity
{
    public Guid ClassSessionId { get; set; }
    public Guid? StudentId { get; set; }
    public Guid CreatedBy { get; set; }
    public string RequestKey { get; set; } = "";
    public string Kind { get; set; } = "class_notice";
    public string Title { get; set; } = "";
    public string SourceJson { get; set; } = "{}";
    public string SourceHash { get; set; } = "";
    public string Body { get; set; } = "";
    public string RecipientLabel { get; set; } = "";
    public string Channel { get; set; } = "wecom_group";
    public string State { get; set; } = "draft";
    public string ReviewNote { get; set; } = "";
    public int Revision { get; set; }
    public Guid? ApprovedBy { get; set; }
    public DateTimeOffset? ApprovedAt { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public class AcademicMessageCommunication : Entity
{
    public Guid MessageId { get; set; }
    public int MessageRevision { get; set; }
    public Guid ConfirmedBy { get; set; }
    public string RequestKey { get; set; } = "";
    public string RecipientLabel { get; set; } = "";
    public string Channel { get; set; } = "wecom_private";
    public string Body { get; set; } = "";
    public string SourceHash { get; set; } = "";
    public DateTimeOffset ConfirmedAt { get; set; } = Clock.Now;
    public string Status { get; set; } = "awaiting_reply";
    public string ResponseNote { get; set; } = "";
    public string? FollowUpOn { get; set; }
    public int Revision { get; set; }
}
public class AcademicBetaEvent : Entity
{
    public Guid EntityId { get; set; }
    public Guid ClassSessionId { get; set; }
    public Guid StaffId { get; set; }
    public string Action { get; set; } = "";
    public string SnapshotJson { get; set; } = "{}";
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public partial class Data
{
    public DbSet<AcademicPractice> AcademicPractices => Set<AcademicPractice>();
    public DbSet<AcademicMessage> AcademicMessages => Set<AcademicMessage>();
    public DbSet<AcademicMessageCommunication> AcademicMessageCommunications => Set<AcademicMessageCommunication>();
    public DbSet<AcademicBetaEvent> AcademicBetaEvents => Set<AcademicBetaEvent>();
}
public record AcademicMessageInput(Guid ClassSessionId, Guid? StudentId, string Kind, string RequestKey);
public record AcademicReturnInput(int Revision, string Reason);
public record AcademicPreparedMessage(string SourceJson, string SourceHash, string Body, string Title);
public record AcademicMessageListItem(Guid Id, Guid ClassSessionId, Guid? StudentId, string Kind, string Title, string RecipientLabel, string Channel, string State, int Revision, DateTimeOffset UpdatedAt, string EditorKind, bool SharedDraftMissing = false);
public static class AcademicBeta
{
    public static void ConfigureModel(ModelBuilder b)
    {
        b.Entity<AcademicPractice>().HasIndex(x => new { x.ClassSessionId, x.StudentId, x.QuestionId }).IsUnique();
        b.Entity<AcademicPractice>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicMessage>().HasIndex(x => new { x.CreatedBy, x.RequestKey }).IsUnique();
        b.Entity<AcademicMessage>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicMessageCommunication>().HasIndex(x => new { x.ConfirmedBy, x.RequestKey }).IsUnique();
        b.Entity<AcademicMessageCommunication>().HasIndex(x => new { x.MessageId, x.MessageRevision }).IsUnique();
        b.Entity<AcademicMessageCommunication>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicMessageCommunication>().HasOne<AcademicMessage>().WithMany().HasForeignKey(x => x.MessageId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicBetaEvent>().HasIndex(x => new { x.EntityId, x.CreatedAt });
    }
    public static void Event(Data db, Access a, Guid entity, Guid session, string action, object snapshot) => db.AcademicBetaEvents.Add(new() {
        EntityId = entity, ClassSessionId = session, StaffId = a.User.Id, Action = action, SnapshotJson = Json.Write(snapshot)
    });
    public static async Task<Student> Student(Guid sessionId, Guid studentId, Data db, Access a)
    {
        var s = await AcademicPilot.Session(sessionId, db, a);
        var student = await db.Students.AsNoTracking().SingleOrDefaultAsync(x => x.Id == studentId && x.ClassId == s.ClassId);
        Guard.That(student != null && await db.ClassSessionMarks.AnyAsync(x => x.ClassSessionId == s.Id && x.StudentId == studentId), "学生不属于本课次", 403);
        return student!;
    }
    static async Task<AcademicMessage> Message(Guid id, Data db, Access a)
    {
        var m = await db.AcademicMessages.SingleOrDefaultAsync(x => x.Id == id) ?? throw new ApiException(404, "消息不存在");
        await AcademicPilot.Session(m.ClassSessionId, db, a);
        if (m.StudentId.HasValue) await Student(m.ClassSessionId, m.StudentId.Value, db, a);
        return m;
    }
    static void Reset(AcademicMessage m)
    {
        m.State = "draft"; m.ApprovedBy = null; m.ApprovedAt = null; m.ReviewNote = ""; m.Revision++; m.UpdatedAt = Clock.Now;
    }
    static void Snapshot(Data db, Access a, AcademicMessage m, string action) => Event(db, a, m.Id, m.ClassSessionId, action,
        new { m.Revision, m.Body, m.RecipientLabel, m.Channel, m.State, m.SourceHash, m.ApprovedBy, m.ApprovedAt, m.ReviewNote });
    static void ValidateFollowUp(AcademicFollowUpInput input)
    {
        Guard.That(input.ResponseNote is { Length: <= 4000 } && input.Status is "awaiting_reply" or "follow_up" or "resolved", "请填写有效沟通结果");
        Guard.That(input.FollowUpOn == null || DateOnly.TryParseExact(input.FollowUpOn, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _), "跟进日期无效");
        Guard.That(input.Status == "awaiting_reply" || !string.IsNullOrWhiteSpace(input.ResponseNote), "请记录实际回复或处理结果");
        Guard.That(input.Status != "follow_up" || input.FollowUpOn != null, "请填写下次跟进日期");
    }
    static async Task<AcademicPreparedMessage> Prepare(Guid sessionId, Guid? studentId, string kind, Data db, Access a)
    {
        Guard.That(kind is "class_notice" or "homework_reminder" or "learning_feedback", "请选择有效消息类型");
        var s = await AcademicPilot.Session(sessionId, db, a);
        var student = studentId.HasValue ? await Student(sessionId, studentId.Value, db, a) : null;
        var cl = await db.Classes.AsNoTracking().SingleAsync(x => x.Id == s.ClassId);
        var course = await db.Courses.AsNoTracking().SingleAsync(x => x.Id == s.CourseId);
        var h = await db.Handouts.AsNoTracking().SingleAsync(x => x.Id == s.HandoutId);
        var lecture = await db.Lectures.AsNoTracking().SingleAsync(x => x.Id == h.LectureId);
        object? relevant = null; string body; string title;
        if (kind == "learning_feedback") {
            Guard.That(studentId.HasValue, "学情反馈需要选择具体学生");
            var source = await AcademicPilot.Source(sessionId, studentId!.Value, db, a);
            Guard.That(source.Reviewed, "老师完成课堂批改后才能生成学情反馈", 409);
            relevant = source; body = AcademicPilot.Draft(source); title = student!.Name + " · 学情反馈";
        } else if (kind == "homework_reminder") {
            var homework = await AcademicOperations.HomeworkMessageEvidence(sessionId, studentId, db);
            Guard.That(homework != null, "请先登记本课次作业", 409);
            relevant = homework;
            title = "作业提醒";
            body = $"家长您好，{cl.Name}{s.LessonDate}《{lecture.Title}》的作业安排如下：\n\n{homework!.Title}";
            if (!string.IsNullOrWhiteSpace(homework.Requirements)) body += "\n" + homework.Requirements;
            if (homework.DueOn != null) body += "\n提交日期：" + homework.DueOn;
            if (student != null) {
                body = student.Name + "家长您好，" + body[5..];
                body += "\n目前登记状态：" + HomeworkStatus(homework.StudentStatus);
            }
            body += "\n如已完成请按老师要求提交，有困难请联系老师。";
        } else {
            title = "上课通知";
            body = $"家长您好，{cl.Name}的课次安排：\n日期：{s.LessonDate}\n课程：{course.Name}\n学习内容：{lecture.Title}\n请按老师通知的时间和地点参加。";
        }
        var json = Json.Write(new { sessionId, s.ClassId, s.CourseId, s.LessonDate, className = cl.Name, courseName = course.Name, title = lecture.Title, kind, studentId, studentName = student?.Name, relevant });
        return new(json, Secrets.Hash(json), body, title);
    }
    public static string HomeworkStatus(string? status) => status switch { "missing" => "未交", "submitted" => "已交待批改", "reviewed" => "已批改", _ => "尚未登记" };
    static async Task<bool> Stale(AcademicMessage m, Data db, Access a)
    {
        try { return (await Prepare(m.ClassSessionId, m.StudentId, m.Kind, db, a)).SourceHash != m.SourceHash; }
        catch (ApiException error) when (error.Status == 409) { return true; }
    }
    static async Task Current(AcademicMessage m, Data db, Access a) => Guard.That(!await Stale(m, db, a), "来源记录已变化，请更新并重新审核", 409);

    static async Task<AcademicMessageListItem[]> ListMessages(Guid? classSessionId, Data db, Access a)
    {
        if (classSessionId.HasValue) await AcademicPilot.Session(classSessionId.Value, db, a);
        var uid = a.User.Id; var admin = a.User.Role == "admin";
        var scope = db.ClassSessions.Where(s => db.Classes.Any(c => c.Id == s.ClassId) && db.Courses.Any(c => c.Id == s.CourseId) && (!classSessionId.HasValue || s.Id == classSessionId) && (admin || db.Assignments.Any(x => x.StaffId == uid && x.ClassId == s.ClassId && x.CourseId == s.CourseId))).Select(s => s.Id);
        var feedbacks = await db.AcademicFeedbacks.AsNoTracking().Where(f => scope.Contains(f.ClassSessionId) && db.ClassSessions.Any(s => s.Id == f.ClassSessionId && db.Students.Any(st => st.Id == f.StudentId && st.ClassId == s.ClassId))).OrderByDescending(x => x.UpdatedAt).Take(500).ToArrayAsync();
        var messages = await db.AcademicMessages.AsNoTracking().Where(m => scope.Contains(m.ClassSessionId) && (m.StudentId == null || db.ClassSessions.Any(s => s.Id == m.ClassSessionId && db.Students.Any(st => st.Id == m.StudentId && st.ClassId == s.ClassId))) && (m.Kind != "learning_feedback" || !db.AcademicFeedbacks.Any(f => f.ClassSessionId == m.ClassSessionId && f.StudentId == m.StudentId))).OrderByDescending(x => x.UpdatedAt).Take(500).ToArrayAsync();
        var rows = feedbacks.Select(f => new AcademicMessageListItem(f.Id, f.ClassSessionId, f.StudentId, "learning_feedback", Json.Read<AcademicSource>(f.SourceJson).StudentName + " · 学情反馈", f.RecipientLabel, f.Channel, f.State, f.Revision, f.UpdatedAt, "feedback")).ToList();
        foreach (var m in messages.Where(m => m.Kind != "learning_feedback").Concat(messages.Where(m => m.Kind == "learning_feedback").GroupBy(m => new { m.ClassSessionId, m.StudentId }).Select(g => g.First())))
            rows.Add(new(m.Id, m.ClassSessionId, m.StudentId, m.Kind, m.Title, m.RecipientLabel, m.Channel, m.State, m.Revision, m.UpdatedAt, "message", m.Kind == "learning_feedback"));
        return rows.OrderByDescending(x => x.UpdatedAt).Take(500).ToArray();
    }

    static async Task<IResult> CreateMessage(AcademicMessageInput input, Data db, Access a)
    {
        Guard.That(input.RequestKey is { Length: >= 8 and <= 120 }, "缺少有效操作标识");
        Guard.That(input.Kind is "class_notice" or "homework_reminder" or "learning_feedback", "请选择有效消息类型");
        await AcademicPilot.Session(input.ClassSessionId, db, a);
        if (input.StudentId.HasValue) await Student(input.ClassSessionId, input.StudentId.Value, db, a);
        for (var attempt = 0; ; attempt++) {
            await using var transaction = await db.Database.BeginTransactionAsync();
            try {
                var binding = await AcademicPilot.SharedRequest(input, db, a);
                var prior = await db.AcademicMessages.SingleOrDefaultAsync(x => x.CreatedBy == a.User.Id && x.RequestKey == input.RequestKey);
                if (prior != null) Guard.That(prior.ClassSessionId == input.ClassSessionId && prior.StudentId == input.StudentId && prior.Kind == input.Kind, "操作标识已用于其他内容", 409);
                Guid id; string editorKind;
                if (input.Kind == "learning_feedback") {
                    Guard.That(input.StudentId.HasValue, "学情反馈需要选择具体学生");
                    var feedback = await AcademicPilot.GetOrCreateFeedback(input.ClassSessionId, input.StudentId!.Value, db, a);
                    id = feedback.Id; editorKind = "feedback";
                } else if (prior != null) { id = prior.Id; editorKind = "message"; }
                else {
                    Guard.That(binding == null, "已保存的消息记录缺失，请先核实", 409);
                    var ready = await Prepare(input.ClassSessionId, input.StudentId, input.Kind, db, a);
                    var message = new AcademicMessage { ClassSessionId = input.ClassSessionId, StudentId = input.StudentId, CreatedBy = a.User.Id, RequestKey = input.RequestKey,
                        Kind = input.Kind, Title = ready.Title, Body = ready.Body, SourceJson = ready.SourceJson, SourceHash = ready.SourceHash,
                        Channel = input.StudentId.HasValue ? "wecom_private" : "wecom_group" };
                    db.AcademicMessages.Add(message); Snapshot(db, a, message, "message.created");
                    id = message.Id; editorKind = "message";
                }
                if (binding == null) AcademicPilot.RememberSharedRequest(input, id, editorKind, db, a);
                else Guard.That(binding.EntityId == id && binding.EditorKind == editorKind, "操作标识指向其他记录", 409);
                await db.SaveChangesAsync(); await transaction.CommitAsync();
                return Results.Ok(new { id, editorKind });
            } catch (DbUpdateException) when (attempt < 2) {
                await transaction.RollbackAsync(); db.ChangeTracker.Clear();
                // Retry after unique-key races so either entry point resolves the winner.
            }
        }
    }

    public static void MapEndpoints(RouteGroupBuilder group)
    {
        group.MapGet("/sessions/{id:guid}/wrong-records", async (Guid id, Data db, Access a) => {
            var s = await AcademicPilot.Session(id, db, a);
            var students = await (from m in db.ClassSessionMarks join st in db.Students on m.StudentId equals st.Id where m.ClassSessionId == id && st.ClassId == s.ClassId orderby st.Name select new { student = st, mark = m }).ToArrayAsync();
            var rows = new List<object>();
            foreach (var row in students) {
                var wrongIds = Json.Read<Guid[]>(row.mark.WrongIdsJson);
                var notes = Json.Read<Dictionary<Guid,string>>(row.mark.NotesJson);
                var questions = await db.Questions.AsNoTracking().Where(x => x.HandoutId == s.HandoutId && wrongIds.Contains(x.Id)).OrderBy(x => x.Order).ThenBy(x => x.Id).ToArrayAsync();
                var practices = await db.AcademicPractices.AsNoTracking().Where(x => x.ClassSessionId == id && x.StudentId == row.student.Id).ToDictionaryAsync(x => x.QuestionId);
                var events = await db.LearningEvents.AsNoTracking().Where(x => x.StudentId == row.student.Id && x.ClassId == s.ClassId && x.CourseId == s.CourseId && x.Kind == "retest" && x.RecordedAt >= s.CreatedAt && wrongIds.Contains(x.QuestionId)).OrderByDescending(x => x.RecordedAt).ThenByDescending(x => x.Id).ToArrayAsync();
                rows.Add(new { studentId = row.student.Id, row.student.Name, row.mark.Reviewed, questions = questions.Select(q => {
                    var practice = practices.GetValueOrDefault(q.Id); var legacy = events.FirstOrDefault(x => x.QuestionId == q.Id);
                    return new { questionId = q.Id, q.Label, note = notes.GetValueOrDefault(q.Id, ""), legacyRetestState = legacy?.State, legacyRetestAt = legacy?.RecordedAt,
                        revision = practice?.Revision ?? 0, corrected = practice?.Corrected ?? false, retestState = practice?.RetestState ?? "unrecorded", retestAt = practice?.RetestAt, practiceNote = practice?.Note ?? "" };
                }) });
            }
            return new { students = rows };
        });
        group.MapPut("/sessions/{id:guid}/practice/{studentId:guid}/{questionId:guid}", async (Guid id, Guid studentId, Guid questionId, AcademicPracticeInput input, Data db, Access a) => {
            var s = await AcademicPilot.Session(id, db, a); await Student(id, studentId, db, a);
            var mark = await db.ClassSessionMarks.AsNoTracking().SingleAsync(x => x.ClassSessionId == id && x.StudentId == studentId);
            Guard.That(mark.Reviewed && Json.Read<Guid[]>(mark.WrongIdsJson).Contains(questionId) && await db.Questions.AnyAsync(x => x.Id == questionId && x.HandoutId == s.HandoutId), "请先完成该学生的错题登记，且仅能处理本课次错题", 409);
            Guard.That(input.RetestState is "unrecorded" or "correct" or "wrong" or "unfinished", "复测状态无效");
            Guard.That(input.Note is { Length: <= 2000 }, "备注最多2000字");
            var p = await db.AcademicPractices.SingleOrDefaultAsync(x => x.ClassSessionId == id && x.StudentId == studentId && x.QuestionId == questionId);
            AcademicPilot.Revision(input.Revision, p?.Revision ?? 0);
            if (p == null) { p = new() { ClassSessionId = id, StudentId = studentId, QuestionId = questionId }; db.AcademicPractices.Add(p); }
            Guard.That(!input.RecordRetest || input.RetestState != "unrecorded", "请先选择实际复测结果");
            if (input.RetestState != p.RetestState || input.RecordRetest) p.RetestAt = input.RetestState == "unrecorded" ? null : Clock.Now;
            p.Corrected = input.Corrected; p.RetestState = input.RetestState; p.Note = input.Note.Trim(); p.Revision++; p.RecordedBy = a.User.Id; p.UpdatedAt = Clock.Now;
            Event(db, a, p.Id, id, "practice.saved", new { studentId, questionId, p.Corrected, p.RetestState, p.RetestAt, p.Note, p.Revision });
            await db.SaveChangesAsync(); return Results.Ok(new { p.Id, p.Revision });
        });
        group.MapGet("/reviews", async (Guid? classSessionId, HttpContext ctx, Data db, Access a) => {
            await AcademicPilot.Gate(ctx, true);
            if (classSessionId.HasValue) await AcademicPilot.Session(classSessionId.Value, db, a);
            var uid = a.User.Id; var admin = a.User.Role == "admin";
            var scope = db.ClassSessions.Where(s => db.Classes.Any(c => c.Id == s.ClassId) && db.Courses.Any(c => c.Id == s.CourseId) && (!classSessionId.HasValue || s.Id == classSessionId) && (admin || db.Assignments.Any(x => x.StaffId == uid && x.ClassId == s.ClassId && x.CourseId == s.CourseId))).Select(s => s.Id);
            var feedbacks = await db.AcademicFeedbacks.AsNoTracking().Where(f => scope.Contains(f.ClassSessionId) && f.State == "draft" && db.ClassSessions.Any(s => s.Id == f.ClassSessionId && db.Students.Any(st => st.Id == f.StudentId && st.ClassId == s.ClassId))).OrderBy(x => x.UpdatedAt).Take(500).ToArrayAsync();
            var messages = await db.AcademicMessages.AsNoTracking().Where(m => m.Kind != "learning_feedback" && scope.Contains(m.ClassSessionId) && m.State == "draft" && (m.StudentId == null || db.ClassSessions.Any(s => s.Id == m.ClassSessionId && db.Students.Any(st => st.Id == m.StudentId && st.ClassId == s.ClassId)))).OrderBy(x => x.UpdatedAt).Take(500).ToArrayAsync();
            var rows = feedbacks.Select(f => { var source = Json.Read<AcademicSource>(f.SourceJson); return new { f.Id, kind = "feedback", f.ClassSessionId, f.StudentId, source.StudentName, title = source.StudentName + " · 学情反馈", f.RecipientLabel, f.State, f.Revision, f.UpdatedAt }; }).Cast<object>().ToList();
            foreach (var m in messages) rows.Add(new { m.Id, kind = "message", m.ClassSessionId, m.StudentId, studentName = m.StudentId == null ? null : await db.Students.Where(x => x.Id == m.StudentId).Select(x => x.Name).FirstOrDefaultAsync(), m.Title, m.RecipientLabel, m.State, m.Revision, m.UpdatedAt });
            return rows;
        });
        group.MapPost("/drafts/{id:guid}/return", AcademicPilot.SharedReturn);
        MapMessages(group);
    }
    static void MapMessages(RouteGroupBuilder group)
    {
        group.MapGet("/messages", ListMessages);
        group.MapPost("/messages", CreateMessage);
        group.MapGet("/messages/{id:guid}", async (Guid id, Data db, Access a) => {
            var shared = await AcademicPilot.MessageFeedbackId(id, db, a);
            if (shared.HasValue) return await AcademicPilot.SharedDetail(shared.Value, a, db);
            var m = await Message(id, db, a);
            var communications = await db.AcademicMessageCommunications.AsNoTracking().Where(x => x.MessageId == id).OrderByDescending(x => x.ConfirmedAt).ToArrayAsync();
            return new { m.Id, m.ClassSessionId, m.StudentId, m.Kind, m.Title, m.Body, m.RecipientLabel, m.Channel, m.State, m.Revision, sourceStale = await Stale(m, db, a),
                m.ReviewNote, m.ApprovedAt, approvedByName = m.ApprovedBy.HasValue ? await db.Staff.Where(x => x.Id == m.ApprovedBy).Select(x => x.Name).FirstOrDefaultAsync() : null, communications };
        });
        group.MapPut("/messages/{id:guid}", async (Guid id, AcademicEditInput input, Data db, Access a) => {
            var shared = await AcademicPilot.MessageFeedbackId(id, db, a, requireCanonical: true);
            if (shared.HasValue) return await AcademicPilot.SharedEdit(shared.Value, input, a, db);
            var m = await Message(id, db, a); AcademicPilot.Revision(input.Revision, m.Revision);
            Guard.That(input.Body is { Length: > 0 and <= 12000 } && !string.IsNullOrWhiteSpace(input.Body), "请填写反馈正文，最多12000字");
            Guard.That(input.RecipientLabel is { Length: <= 120 } && input.Channel is "wecom_private" or "wecom_group", "请填写有效接收对象与渠道");
            Guard.That(m.StudentId == null || input.Channel == "wecom_private", "学生个人反馈只能交接到家长私聊");
            m.Body = input.Body.Trim(); m.RecipientLabel = input.RecipientLabel.Trim(); m.Channel = input.Channel; Reset(m);
            Snapshot(db, a, m, "message.edited"); await db.SaveChangesAsync(); return Results.Ok(new { m.Id });
        });
        group.MapPost("/messages/{id:guid}/refresh", async (Guid id, AcademicRevisionInput input, Data db, Access a) => {
            var shared = await AcademicPilot.MessageFeedbackId(id, db, a, requireCanonical: true);
            if (shared.HasValue) return await AcademicPilot.SharedRefresh(shared.Value, input, a, db);
            var m = await Message(id, db, a); AcademicPilot.Revision(input.Revision, m.Revision);
            var ready = await Prepare(m.ClassSessionId, m.StudentId, m.Kind, db, a);
            m.Body = ready.Body; m.SourceJson = ready.SourceJson; m.SourceHash = ready.SourceHash; m.Title = ready.Title; Reset(m);
            Snapshot(db, a, m, "message.refreshed"); await db.SaveChangesAsync(); return Results.Ok(new { m.Id });
        });
        group.MapPost("/messages/{id:guid}/approve", async (Guid id, AcademicApprovalInput input, HttpContext ctx, Data db, Access a) => {
            var shared = await AcademicPilot.MessageFeedbackId(id, db, a, requireCanonical: true);
            if (shared.HasValue) return await AcademicPilot.SharedApprove(shared.Value, input, ctx, a, db);
            await AcademicPilot.Gate(ctx, true); Guard.That(input.Confirmed, "请教务明确核对内容和接收对象后确认");
            var m = await Message(id, db, a); AcademicPilot.Revision(input.Revision, m.Revision);
            Guard.That(m.State == "draft" && !string.IsNullOrWhiteSpace(m.RecipientLabel), "请填写接收对象并审核待审版本", 409);
            await Current(m, db, a); m.State = "approved"; m.ApprovedBy = a.User.Id; m.ApprovedAt = Clock.Now; m.ReviewNote = ""; m.Revision++; m.UpdatedAt = Clock.Now;
            Snapshot(db, a, m, "message.approved"); await db.SaveChangesAsync(); return Results.Ok(new { m.Id });
        });
        group.MapPost("/messages/{id:guid}/return", async (Guid id, AcademicReturnInput input, HttpContext ctx, Data db, Access a) => {
            var shared = await AcademicPilot.MessageFeedbackId(id, db, a, requireCanonical: true);
            if (shared.HasValue) return await AcademicPilot.SharedReturn(shared.Value, input, ctx, db, a);
            await AcademicPilot.Gate(ctx, true); var m = await Message(id, db, a); AcademicPilot.Revision(input.Revision, m.Revision);
            Guard.That(m.State is "draft" or "approved", "已登记的发送不能撤回", 409);
            Guard.That(!string.IsNullOrWhiteSpace(input.Reason) && input.Reason.Length <= 1000, "请填写退回原因，最多1000字");
            Reset(m); m.ReviewNote = input.Reason.Trim(); Snapshot(db, a, m, "message.returned"); await db.SaveChangesAsync(); return Results.Ok(new { m.Id });
        });
        group.MapGet("/messages/{id:guid}/handoff", async (Guid id, HttpContext ctx, Data db, Access a) => {
            var shared = await AcademicPilot.MessageFeedbackId(id, db, a, requireCanonical: true);
            if (shared.HasValue) return await AcademicPilot.SharedHandoff(shared.Value, ctx, a, db);
            await AcademicPilot.Gate(ctx, true); var m = await Message(id, db, a);
            Guard.That(m.State == "approved" && m.ApprovedBy.HasValue, "请先完成教务审核", 409); await Current(m, db, a);
            return new { m.Body, m.RecipientLabel, m.Channel, m.Revision };
        });
        group.MapPost("/messages/{id:guid}/communications", async (Guid id, AcademicSentInput input, HttpContext ctx, Data db, Access a) => {
            var shared = await AcademicPilot.MessageFeedbackId(id, db, a, requireCanonical: true);
            if (shared.HasValue) return await AcademicPilot.SharedCommunicate(shared.Value, input, ctx, a, db);
            await AcademicPilot.Gate(ctx, true); Guard.That(input.Confirmed, "仅在企业微信实际发送后确认登记");
            Guard.That(input.RequestKey is { Length: >= 8 and <= 100 }, "缺少有效操作标识");
            var m = await Message(id, db, a);
            var old = await db.AcademicMessageCommunications.SingleOrDefaultAsync(x => x.ConfirmedBy == a.User.Id && x.RequestKey == input.RequestKey);
            if (old != null) { Guard.That(old.MessageId == id && old.MessageRevision == input.Revision, "操作标识已用于其他内容", 409); return Results.Ok(new { old.Id }); }
            Guard.That(!await db.AcademicCommunications.AnyAsync(x => x.ConfirmedBy == a.User.Id && x.RequestKey == input.RequestKey), "操作标识已用于其他发送记录", 409);
            AcademicPilot.Revision(input.Revision, m.Revision);
            Guard.That(m.State == "approved" && m.ApprovedBy.HasValue, "请先完成教务审核", 409); await Current(m, db, a);
            var log = new AcademicMessageCommunication { MessageId = id, MessageRevision = m.Revision, ConfirmedBy = a.User.Id, RequestKey = input.RequestKey, RecipientLabel = m.RecipientLabel, Channel = m.Channel, Body = m.Body, SourceHash = m.SourceHash };
            db.AcademicMessageCommunications.Add(log); m.State = "communicated"; m.Revision++; m.UpdatedAt = Clock.Now;
            Snapshot(db, a, m, "message.manually_confirmed"); await db.SaveChangesAsync(); return Results.Ok(new { log.Id });
        });
        group.MapPut("/message-communications/{id:guid}", async (Guid id, AcademicFollowUpInput input, HttpContext ctx, Data db, Access a) => {
            await AcademicPilot.Gate(ctx, true);
            var log = await db.AcademicMessageCommunications.SingleOrDefaultAsync(x => x.Id == id) ?? throw new ApiException(404, "沟通记录不存在");
            var m = await Message(log.MessageId, db, a); AcademicPilot.Revision(input.Revision, log.Revision); ValidateFollowUp(input);
            log.Status = input.Status; log.ResponseNote = input.ResponseNote.Trim(); log.FollowUpOn = input.FollowUpOn; log.Revision++;
            Event(db, a, m.Id, m.ClassSessionId, "message.followup", new { log.Id, log.Status, log.ResponseNote, log.FollowUpOn, log.Revision });
            await db.SaveChangesAsync(); return Results.Ok(new { log.Id });
        });
    }
}
