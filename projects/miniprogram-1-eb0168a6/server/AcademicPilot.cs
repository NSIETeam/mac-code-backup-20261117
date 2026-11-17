using Microsoft.EntityFrameworkCore;
using System.Globalization;

namespace Siyue;

public class AcademicFeedback : Entity
{
    public Guid ClassSessionId { get; set; }
    public Guid StudentId { get; set; }
    public Guid CreatedBy { get; set; }
    public string SourceJson { get; set; } = "{}";
    public string SourceHash { get; set; } = "";
    public string Body { get; set; } = "";
    public string RecipientLabel { get; set; } = "";
    public string Channel { get; set; } = "wecom_private";
    public string State { get; set; } = "draft";
    public int Revision { get; set; }
    public Guid? ApprovedBy { get; set; }
    public DateTimeOffset? ApprovedAt { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
    public DateTimeOffset UpdatedAt { get; set; } = Clock.Now;
}
public class AcademicCommunication : Entity
{
    public Guid FeedbackId { get; set; }
    public int FeedbackRevision { get; set; }
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
public class AcademicPilotEvent : Entity
{
    public Guid FeedbackId { get; set; }
    public Guid StaffId { get; set; }
    public string Action { get; set; } = "";
    public int Revision { get; set; }
    public string SnapshotJson { get; set; } = "{}";
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public partial class Data
{
    public DbSet<AcademicFeedback> AcademicFeedbacks => Set<AcademicFeedback>();
    public DbSet<AcademicCommunication> AcademicCommunications => Set<AcademicCommunication>();
    public DbSet<AcademicPilotEvent> AcademicPilotEvents => Set<AcademicPilotEvent>();
}
public record AcademicDraftInput(Guid ClassSessionId, Guid StudentId);
public record AcademicEditInput(int Revision, string Body, string RecipientLabel, string Channel);
public record AcademicRevisionInput(int Revision);
public record AcademicApprovalInput(int Revision, bool Confirmed);
public record AcademicSentInput(int Revision, string RequestKey, bool Confirmed);
public record AcademicFollowUpInput(int Revision, string ResponseNote, string? FollowUpOn, string Status);
public record AcademicQuestion(Guid Id, string Label, string Note, string? RetestState, DateTimeOffset? RetestAt);
public record AcademicSource(Guid ClassSessionId, Guid StudentId, Guid ClassId, Guid CourseId, Guid HandoutId,
    string StudentName, string ClassName, string CourseName, string LessonDate, string LectureTitle,
    int MarkRevision, bool Reviewed, AcademicQuestion[] Questions, AcademicOperationsEvidence? Operations = null, AcademicPracticeEvidence[]? Practice = null);

public static partial class AcademicPilot
{
    public static bool IsPath(PathString path) => path.StartsWithSegments("/api/academic-pilot") || path.StartsWithSegments("/academic-pilot");
    public static void ConfigureModel(ModelBuilder b)
    {
        b.Entity<AcademicFeedback>().HasIndex(x => new { x.ClassSessionId, x.StudentId }).IsUnique();
        b.Entity<AcademicFeedback>().HasOne<ClassSession>().WithMany().HasForeignKey(x => x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicFeedback>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicFeedback>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicCommunication>().HasOne<AcademicFeedback>().WithMany().HasForeignKey(x => x.FeedbackId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AcademicCommunication>().HasIndex(x => new { x.ConfirmedBy, x.RequestKey }).IsUnique();
        b.Entity<AcademicCommunication>().HasIndex(x => new { x.FeedbackId, x.FeedbackRevision }).IsUnique();
        b.Entity<AcademicCommunication>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<AcademicPilotEvent>().HasOne<AcademicFeedback>().WithMany().HasForeignKey(x => x.FeedbackId).OnDelete(DeleteBehavior.Restrict);
    }
    static string[] Entries(IConfiguration c, string key) => (c["AcademicPilot:" + key] ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    internal static async Task<bool> Listed(IConfiguration c, Data db, Guid staffId, string prefix)
    {
        if (Entries(c, prefix + "StaffIds").Any(x => Guid.TryParse(x, out var id) && id == staffId)) return true;
        var accounts = Entries(c, prefix + "Accounts").Select(x => x.ToLowerInvariant()).ToArray();
        if (accounts.Length == 0) return false;
        if (await db.PasswordAccounts.AnyAsync(x => x.StaffId == staffId && accounts.Contains(x.Account))) return true;
        var openIds = accounts.Select(x => "password:" + x).ToArray();
        return await db.Staff.AnyAsync(x => x.Id == staffId && openIds.Contains(x.OpenId!));
    }
    internal static async Task Gate(HttpContext ctx, bool review = false)
    {
        var c = ctx.RequestServices.GetRequiredService<IConfiguration>();
        var db = ctx.RequestServices.GetRequiredService<Data>();
        var a = ctx.RequestServices.GetRequiredService<Access>();
        Guard.That(c.GetValue<bool>("AcademicPilot:Enabled") && await Listed(c, db, a.User.Id, "Allowed"), "入口不存在", 404);
        if (review) {
            Guard.That(!Automation.UsesCredential(ctx), "请由教务本人登录后确认", 403);
            Guard.That(await Listed(c, db, a.User.Id, "Reviewer"), "此账号没有教务审核权限", 403);
        }
    }
    internal static async Task<ClassSession> Session(Guid id, Data db, Access a)
    {
        var s = await db.ClassSessions.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id) ?? throw new ApiException(404, "课堂不存在");
        await a.Class(s.ClassId, s.CourseId); return s;
    }
    static async Task<AcademicFeedback> Feedback(Guid id, Data db, Access a)
    {
        var f = await db.AcademicFeedbacks.SingleOrDefaultAsync(x => x.Id == id) ?? throw new ApiException(404, "反馈不存在");
        var s = await Session(f.ClassSessionId, db, a);
        Guard.That(await db.Students.AnyAsync(x => x.Id == f.StudentId && x.ClassId == s.ClassId), "学生所属班级已变化，请核实后再处理", 409);
        return f;
    }
    internal static void Revision(int revision, int current) => Guard.That(revision == current, "内容已更新，请刷新后重新核对", 409);
    static void Event(Data db, AcademicFeedback f, Access a, string action) => db.AcademicPilotEvents.Add(new() {
        FeedbackId = f.Id, StaffId = a.User.Id, Action = action, Revision = f.Revision,
        SnapshotJson = Json.Write(new { f.Body, f.RecipientLabel, f.Channel, f.State, f.SourceHash, f.ApprovedBy, f.ApprovedAt })
    });
    static void ResetApproval(AcademicFeedback f) { f.State = "draft"; f.ApprovedBy = null; f.ApprovedAt = null; f.UpdatedAt = Clock.Now; f.Revision++; }

    internal static async Task<AcademicSource> Source(Guid sessionId, Guid studentId, Data db, Access a)
    {
        var s = await Session(sessionId, db, a);
        var student = await db.Students.AsNoTracking().SingleOrDefaultAsync(x => x.Id == studentId && x.ClassId == s.ClassId)
            ?? throw new ApiException(403, "学生不属于当前班级");
        var mark = await db.ClassSessionMarks.AsNoTracking().SingleOrDefaultAsync(x => x.ClassSessionId == s.Id && x.StudentId == student.Id)
            ?? throw new ApiException(409, "课堂尚无该学生的批改记录");
        var ids = Json.Read<Guid[]>(mark.WrongIdsJson).Distinct().ToArray();
        var notes = Json.Read<Dictionary<Guid, string>>(mark.NotesJson);
        var questions = await db.Questions.AsNoTracking().Where(x => ids.Contains(x.Id) && x.HandoutId == s.HandoutId).OrderBy(x => x.Order).ThenBy(x => x.Id).ToListAsync();
        Guard.That(questions.Count == ids.Length, "错题来源不完整，请先核对课堂记录", 409);
        var retests = await db.LearningEvents.AsNoTracking().Where(x => x.StudentId == student.Id && x.ClassId == s.ClassId && x.CourseId == s.CourseId && x.Kind == "retest" && x.RecordedAt >= s.CreatedAt && ids.Contains(x.QuestionId)).OrderByDescending(x => x.RecordedAt).ThenByDescending(x => x.Id).ToListAsync();
        var practice = await db.AcademicPractices.AsNoTracking().Where(x => x.ClassSessionId == s.Id && x.StudentId == student.Id && ids.Contains(x.QuestionId)).OrderBy(x => x.QuestionId).ToArrayAsync();
        var h = await db.Handouts.AsNoTracking().SingleAsync(x => x.Id == s.HandoutId);
        var l = await db.Lectures.AsNoTracking().SingleAsync(x => x.Id == h.LectureId);
        return new(s.Id, student.Id, s.ClassId, s.CourseId, s.HandoutId, student.Name,
            (await db.Classes.AsNoTracking().SingleAsync(x => x.Id == s.ClassId)).Name,
            (await db.Courses.AsNoTracking().SingleAsync(x => x.Id == s.CourseId)).Name,
            s.LessonDate, l.Title, mark.Revision, mark.Reviewed,
            questions.Select(q => { var retest = retests.FirstOrDefault(x => x.QuestionId == q.Id); var beta = practice.FirstOrDefault(x => x.QuestionId == q.Id);
                var useBeta = beta?.RetestAt != null && beta.RetestState != "unrecorded" && (retest == null || beta.RetestAt >= retest.RecordedAt);
                return new AcademicQuestion(q.Id, q.Label, notes.GetValueOrDefault(q.Id, ""), useBeta ? beta!.RetestState : retest?.State, useBeta ? beta!.RetestAt : retest?.RecordedAt); }).ToArray(),
            await AcademicOperations.Evidence(sessionId, studentId, db),
            practice.Select(x => new AcademicPracticeEvidence(x.QuestionId, x.Corrected, x.RetestState, x.Note, x.Revision, x.RetestAt)).ToArray());
    }
    internal static string Draft(AcademicSource s)
    {
        var lines = new List<string> { $"家长您好，以下是{s.StudentName}在{s.LessonDate}《{s.LectureTitle}》的学习反馈：", "" };
        if (s.Questions.Length == 0) lines.Add("本次已完成错题登记，当前没有登记错题。该记录不等同于对全部知识掌握情况的判断。后续可结合独立练习继续观察。");
        else {
            lines.Add($"本次登记需关注的题目共{s.Questions.Length}道：{string.Join("、", s.Questions.Select(x => x.Label))}。");
            foreach (var q in s.Questions.Where(x => !string.IsNullOrWhiteSpace(x.Note))) lines.Add($"{q.Label}课堂备注：{q.Note}");
            var correct = s.Questions.Count(x => x.RetestState == "correct");
            var wrong = s.Questions.Count(x => x.RetestState == "wrong");
            var pending = s.Questions.Count(x => x.RetestState == null || x.RetestState == "unfinished");
            lines.Add($"本次课堂建立后的复测记录：最近一次做对{correct}道，仍做错{wrong}道，尚无已完成复测记录{pending}道。");
            lines.Add("建议先对上述题目独立重做，再由老师核对结果；如有具体困难，请反馈给我们继续跟进。");
        }
        if (s.Operations is { } op) {
            if (op.AttendanceStatus is "present" or "late" or "leave" or "absent" or "transfer") lines.Add("本次考勤登记：" + (op.AttendanceStatus switch { "present" => "到课", "late" => "迟到", "leave" => "请假", "transfer" => "临时调班", _ => "缺勤" }) + "。");
            if (op.HomeworkTitle != null) lines.Add($"作业《{op.HomeworkTitle}》：{AcademicBeta.HomeworkStatus(op.HomeworkStatus)}。");
        }
        if (s.Practice is { Length: > 0 }) lines.Add($"教务登记已订正{s.Practice.Count(x => x.Corrected)}道错题。");
        lines.Add(""); lines.Add("以上依据已登记的课堂记录整理，具体学习情况以老师核实为准。");
        return string.Join("\n", lines);
    }
    static async Task<bool> Stale(AcademicFeedback f, Data db, Access a)
    {
        var source = await Source(f.ClassSessionId, f.StudentId, db, a);
        return !source.Reviewed || Secrets.Hash(Json.Write(source)) != f.SourceHash;
    }
    static async Task Current(AcademicFeedback f, Data db, Access a) => Guard.That(!await Stale(f, db, a), "错题或复测记录已变化，请更新依据并重新审核", 409);
    static object Summary(AcademicFeedback f) { var s = Json.Read<AcademicSource>(f.SourceJson); return new { f.Id, f.ClassSessionId, f.StudentId, f.Revision, f.State, f.RecipientLabel, f.UpdatedAt, s.StudentName, s.ClassName, s.LessonDate }; }

    public static void MapAcademicPilot(this WebApplication app)
    {
        app.MapGet("/academic-pilot/math/{**asset}", (string asset, IWebHostEnvironment env, HttpContext ctx) => {
            Guard.That(asset is "katex.min.js" or "katex.min.css" or "contrib/auto-render.min.js" or "LICENSE.txt"
                || System.Text.RegularExpressions.Regex.IsMatch(asset, @"\Afonts/KaTeX_[A-Za-z0-9_-]+\.woff2\z"), "资源不存在", 404);
            ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
            var type = asset.EndsWith(".js") ? "text/javascript; charset=utf-8" : asset.EndsWith(".css") ? "text/css; charset=utf-8" : asset.EndsWith(".woff2") ? "font/woff2" : "text/plain; charset=utf-8";
            return Results.File(Path.Combine(env.ContentRootPath, "AcademicPilotAssets", "math", asset), type);
        });
        // Assets are separate from wwwroot; nothing is linked from existing client pages.
        app.MapGet("/academic-pilot/{file?}", (string? file, IWebHostEnvironment env, HttpContext ctx, IConfiguration config) => {
            ctx.Response.Headers.CacheControl = "no-store";
            ctx.Response.Headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; font-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
            ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
            ctx.Response.Headers["Referrer-Policy"] = "no-referrer";
            if (string.IsNullOrEmpty(file) && !ctx.Request.Path.Value!.EndsWith('/'))
                return Results.Redirect((config["PublicUrl"] ?? "").TrimEnd('/') + "/academic-pilot/");
            var name = string.IsNullOrEmpty(file) ? "index.html" : file;
            Guard.That(name is "index.html" or "app.js" or "homework-workflow.js" or "style.css", "入口不存在", 404);
            return Results.File(Path.Combine(env.ContentRootPath, "AcademicPilotAssets", name), name.EndsWith(".js") ? "text/javascript; charset=utf-8" : name.EndsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
        });
        var group = app.MapGroup("/api/academic-pilot").AddEndpointFilter(async (context, next) => { await Gate(context.HttpContext); return await next(context); });
        AcademicOperations.MapEndpoints(group);
        AcademicHomeworkCollection.Map(app, group);
        AcademicParentPortal.Map(app, group);
        AcademicHomeworkGrading.Map(group);
        AcademicHomeworkWorkflow.Map(group);
        AcademicRollCall.MapEndpoints(group);
        AcademicBeta.MapEndpoints(group);
        AcademicRecommendations.MapEndpoints(group);
        AcademicTraining.MapEndpoints(group);
        group.MapGet("/me", async (Access a, Data db, IConfiguration c) => new { a.User.Name, canReview = await Listed(c, db, a.User.Id, "Reviewer"), deliveryMode = "manual", beta = true });
        group.MapGet("/sessions", async (Guid? classId, Access a, Data db) => {
            if (classId.HasValue) await a.Class(classId.Value);
            var uid = a.User.Id; var admin = a.User.Role == "admin";
            return await (from s in db.ClassSessions join cl in db.Classes on s.ClassId equals cl.Id join c in db.Courses on s.CourseId equals c.Id join h in db.Handouts on s.HandoutId equals h.Id join l in db.Lectures on h.LectureId equals l.Id
                where (!classId.HasValue || s.ClassId == classId.Value) && (admin || db.Assignments.Any(x => x.StaffId == uid && x.ClassId == s.ClassId && x.CourseId == s.CourseId))
                orderby s.LessonDate descending, s.CreatedAt descending
                select new { s.Id, s.ClassId, className = cl.Name, courseName = c.Name, s.LessonDate, title = l.Title, reviewed = db.ClassSessionMarks.Count(m => m.ClassSessionId == s.Id && m.Reviewed), total = db.ClassSessionMarks.Count(m => m.ClassSessionId == s.Id) }).Take(200).ToArrayAsync();
        });
        group.MapGet("/sessions/{id:guid}", async (Guid id, Access a, Data db) => {
            var s = await Session(id, db, a);
            var rows = await (from m in db.ClassSessionMarks join st in db.Students on m.StudentId equals st.Id where m.ClassSessionId == id && st.ClassId == s.ClassId orderby st.Name select new { st.Id, st.Name, m.Reviewed, m.WrongIdsJson }).ToArrayAsync();
            var feedbacks = await db.AcademicFeedbacks.Where(x => x.ClassSessionId == id).ToDictionaryAsync(x => x.StudentId);
            return new { session = s, students = rows.Select(x => new { studentId = x.Id, x.Name, x.Reviewed, wrongCount = Json.Read<Guid[]>(x.WrongIdsJson).Length, feedbackId = feedbacks.GetValueOrDefault(x.Id)?.Id, state = feedbacks.GetValueOrDefault(x.Id)?.State }) };
        });
        group.MapGet("/drafts", async (Guid? classSessionId, Access a, Data db) => {
            if (classSessionId.HasValue) await Session(classSessionId.Value, db, a);
            var uid = a.User.Id; var admin = a.User.Role == "admin";
            var rows = await (from f in db.AcademicFeedbacks join s in db.ClassSessions on f.ClassSessionId equals s.Id where db.Classes.Any(c => c.Id == s.ClassId) && db.Courses.Any(c => c.Id == s.CourseId) && db.Students.Any(st => st.Id == f.StudentId && st.ClassId == s.ClassId) && (!classSessionId.HasValue || f.ClassSessionId == classSessionId) && (admin || db.Assignments.Any(x => x.StaffId == uid && x.ClassId == s.ClassId && x.CourseId == s.CourseId)) orderby f.UpdatedAt descending select f).Take(200).ToArrayAsync();
            return rows.Select(Summary);
        });
        group.MapPost("/drafts", async (AcademicDraftInput input, Access a, Data db) => {
            var f = await GetOrCreateFeedback(input.ClassSessionId, input.StudentId, db, a);
            return Results.Ok(new { f.Id, editorKind = "feedback" });
        });
        group.MapGet("/drafts/{id:guid}", SharedDetail);
        group.MapPut("/drafts/{id:guid}", SharedEdit);
        group.MapPost("/drafts/{id:guid}/refresh", SharedRefresh);
        group.MapPost("/drafts/{id:guid}/approve", SharedApprove);
        group.MapGet("/drafts/{id:guid}/handoff", SharedHandoff);
        group.MapPost("/drafts/{id:guid}/communications", SharedCommunicate);
        group.MapPut("/communications/{id:guid}", async (Guid id, AcademicFollowUpInput input, HttpContext ctx, Access a, Data db) => {
            await Gate(ctx, true);
            var log = await db.AcademicCommunications.SingleOrDefaultAsync(x => x.Id == id) ?? throw new ApiException(404, "沟通记录不存在");
            var f = await Feedback(log.FeedbackId, db, a); Revision(input.Revision, log.Revision);
            Guard.That(input.ResponseNote is { Length: <= 4000 } && input.Status is "awaiting_reply" or "follow_up" or "resolved", "请填写有效的沟通结果");
            Guard.That(input.FollowUpOn == null || DateOnly.TryParseExact(input.FollowUpOn, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _), "跟进日期无效");
            Guard.That(input.Status == "awaiting_reply" || !string.IsNullOrWhiteSpace(input.ResponseNote), "请记录家长反馈或实际处理结果");
            Guard.That(input.Status != "follow_up" || input.FollowUpOn != null, "待跟进事项请填写跟进日期");
            log.ResponseNote = input.ResponseNote.Trim(); log.FollowUpOn = input.FollowUpOn; log.Status = input.Status; log.Revision++;
            db.AcademicPilotEvents.Add(new() { FeedbackId = f.Id, StaffId = a.User.Id, Action = "communication.follow_up", Revision = log.Revision, SnapshotJson = Json.Write(new { communicationId = log.Id, log.Status, log.ResponseNote, log.FollowUpOn }) });
            await db.SaveChangesAsync(); return Results.Ok(new { log.Id });
        });
    }
}
