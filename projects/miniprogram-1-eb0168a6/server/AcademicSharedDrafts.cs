using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
namespace Siyue;

public record AcademicSharedRequest(Guid ClassSessionId, Guid? StudentId, string Kind, Guid EntityId, string EditorKind);
public static partial class AcademicPilot
{
    internal static Guid SharedRequestId(Guid staffId, string requestKey) => new(SHA256.HashData(Encoding.UTF8.GetBytes("academic-message-request:" + Json.Write(new { staffId, requestKey })))[..16]);

    internal static async Task<AcademicSharedRequest?> SharedRequest(AcademicMessageInput input, Data db, Access a)
    {
        var id = SharedRequestId(a.User.Id, input.RequestKey);
        var entry = await db.AcademicBetaEvents.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id);
        if (entry == null) return null;
        Guard.That(entry.StaffId == a.User.Id && entry.Action == "shared.message.request", "操作标识已被占用", 409);
        var prior = Json.Read<AcademicSharedRequest>(entry.SnapshotJson);
        Guard.That(prior.ClassSessionId == input.ClassSessionId && prior.StudentId == input.StudentId && prior.Kind == input.Kind, "操作标识已用于其他内容", 409);
        return prior;
    }

    internal static void RememberSharedRequest(AcademicMessageInput input, Guid entityId, string editorKind, Data db, Access a) => db.AcademicBetaEvents.Add(new() {
        Id = SharedRequestId(a.User.Id, input.RequestKey), EntityId = entityId, ClassSessionId = input.ClassSessionId,
        StaffId = a.User.Id, Action = "shared.message.request",
        SnapshotJson = Json.Write(new AcademicSharedRequest(input.ClassSessionId, input.StudentId, input.Kind, entityId, editorKind))
    });

    internal static async Task<AcademicFeedback> GetOrCreateFeedback(Guid sessionId, Guid studentId, Data db, Access a)
    {
        await AcademicBeta.Student(sessionId, studentId, db, a);
        var existing = await db.AcademicFeedbacks.SingleOrDefaultAsync(x => x.ClassSessionId == sessionId && x.StudentId == studentId);
        if (existing != null) return existing;
        var source = await Source(sessionId, studentId, db, a);
        Guard.That(source.Reviewed, "请老师完成本次课堂错题登记后再生成反馈", 409);
        var json = Json.Write(source); var hash = Secrets.Hash(json);
        // Old messages are immutable pre-merge versions. Recover their text on explicit
        // creation, while never inheriting an approval or claiming old evidence is current.
        var legacy = await db.AcademicMessages.AsNoTracking().Where(x => x.Kind == "learning_feedback" && x.ClassSessionId == sessionId && x.StudentId == studentId).OrderByDescending(x => x.UpdatedAt).ThenByDescending(x => x.Id).FirstOrDefaultAsync();
        if (legacy != null) {
            hash = "legacy-source-requires-review";
            try {
                using var document = JsonDocument.Parse(legacy.SourceJson);
                var oldSource = document.RootElement.GetProperty("relevant").Deserialize<AcademicSource>(Json.Options);
                if (oldSource?.ClassSessionId == sessionId && oldSource.StudentId == studentId) { json = Json.Write(oldSource); hash = Secrets.Hash(json); }
            } catch (Exception error) when (error is JsonException or KeyNotFoundException or InvalidOperationException) { }
        }
        var f = new AcademicFeedback { ClassSessionId = sessionId, StudentId = studentId, CreatedBy = a.User.Id,
            SourceJson = json, SourceHash = hash, Body = legacy?.Body ?? Draft(source), RecipientLabel = legacy?.RecipientLabel ?? "", Channel = "wecom_private" };
        db.AcademicFeedbacks.Add(f); Event(db, f, a, legacy == null ? "draft.created" : "draft.recovered_from_message");
        try { await db.SaveChangesAsync(); }
        catch (DbUpdateException) when (db.Database.CurrentTransaction == null) {
            // The unique lesson/student key is the authority across both entry points.
            db.ChangeTracker.Clear();
            var winner = await db.AcademicFeedbacks.SingleOrDefaultAsync(x => x.ClassSessionId == sessionId && x.StudentId == studentId);
            if (winner == null) throw;
            return winner;
        }
        return f;
    }

    internal static async Task<Guid?> MessageFeedbackId(Guid id, Data db, Access a, bool requireCanonical = false)
    {
        if (await db.AcademicFeedbacks.AnyAsync(x => x.Id == id)) return (await Feedback(id, db, a)).Id;
        var legacy = await db.AcademicMessages.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id);
        if (legacy?.Kind != "learning_feedback") return null;
        await Session(legacy.ClassSessionId, db, a);
        Guard.That(legacy.StudentId.HasValue, "旧学情消息缺少学生，请先核实", 409);
        await AcademicBeta.Student(legacy.ClassSessionId, legacy.StudentId!.Value, db, a);
        var shared = await db.AcademicFeedbacks.SingleOrDefaultAsync(x => x.ClassSessionId == legacy.ClassSessionId && x.StudentId == legacy.StudentId);
        Guard.That(shared != null, "此旧消息尚未建立共用草稿，请在家长沟通中选择该学生并打开／生成学情草稿；旧正文和发送历史会保留", 409);
        Guard.That(!requireCanonical, "此消息已合并为共用草稿，请重新打开并核对当前内容后操作", 409);
        return shared!.Id;
    }

    internal static async Task<object> SharedDetail(Guid id, Access a, Data db)
    {
            var f = await Feedback(id, db, a);
            var communications = await db.AcademicCommunications.AsNoTracking().Where(x => x.FeedbackId == id).OrderByDescending(x => x.ConfirmedAt).ToArrayAsync();
            var returned = await db.AcademicBetaEvents.AsNoTracking().Where(x => x.EntityId == id && x.Action == "feedback.returned").OrderByDescending(x => x.CreatedAt).Select(x => x.SnapshotJson).FirstOrDefaultAsync();
            using var returnDocument = returned == null ? null : System.Text.Json.JsonDocument.Parse(returned);
            var returnReason = returnDocument != null && returnDocument.RootElement.GetProperty("revision").GetInt32() == f.Revision ? returnDocument.RootElement.GetProperty("reason").GetString() : null;
            var source = Json.Read<AcademicSource>(f.SourceJson);
            var legacy = await db.AcademicMessages.AsNoTracking().Where(x => x.Kind == "learning_feedback" && x.ClassSessionId == f.ClassSessionId && x.StudentId == f.StudentId).OrderByDescending(x => x.UpdatedAt).ToArrayAsync();
            var legacyIds = legacy.Select(x => x.Id).ToArray();
            var oldCommunications = await db.AcademicMessageCommunications.AsNoTracking().Where(x => legacyIds.Contains(x.MessageId)).OrderByDescending(x => x.ConfirmedAt).ToArrayAsync();
            var legacyDrafts = legacy.Select(m => new { m.Id, m.Title, m.Body, m.RecipientLabel, m.Channel, m.State, m.Revision, m.UpdatedAt,
                communications = oldCommunications.Where(x => x.MessageId == m.Id).Select(x => new { x.Id, x.MessageId, x.MessageRevision, x.ConfirmedBy, x.RequestKey, x.RecipientLabel, x.Channel, x.Body, x.SourceHash, x.ConfirmedAt, x.Status, x.ResponseNote, x.FollowUpOn, x.Revision, editorKind = "message", endpoint = "/message-communications/" + x.Id }) });
            return new { f.Id, f.ClassSessionId, f.StudentId, editorKind = "feedback", kind = "learning_feedback", title = source.StudentName + " · 学情反馈", f.Revision, f.State, f.Body, f.RecipientLabel, f.Channel, returnReason, reviewNote = returnReason, sourceStale = await Stale(f, db, a), source, f.ApprovedAt,
                approvedByName = f.ApprovedBy == null ? null : await db.Staff.Where(x => x.Id == f.ApprovedBy).Select(x => x.Name).FirstOrDefaultAsync(), communications, legacyDrafts };
    }
    internal static async Task<IResult> SharedEdit(Guid id, AcademicEditInput input, Access a, Data db)
    {
            var f = await Feedback(id, db, a); Revision(input.Revision, f.Revision);
            Guard.That(input.Body is { Length: > 0 and <= 12000 } && !string.IsNullOrWhiteSpace(input.Body), "请填写反馈正文，最多12000字");
            Guard.That(input.RecipientLabel is { Length: <= 120 } && input.Channel == "wecom_private", "请选择企业微信私聊并填写有效收件对象");
            f.Body = input.Body.Trim(); f.RecipientLabel = input.RecipientLabel.Trim(); f.Channel = input.Channel;
            ResetApproval(f); Event(db, f, a, "draft.edited"); await db.SaveChangesAsync(); return Results.Ok(new { f.Id });
    }
    internal static async Task<IResult> SharedRefresh(Guid id, AcademicRevisionInput input, Access a, Data db)
    {
            var f = await Feedback(id, db, a); Revision(input.Revision, f.Revision);
            var source = await Source(f.ClassSessionId, f.StudentId, db, a); Guard.That(source.Reviewed, "老师尚未完成课堂登记", 409);
            f.SourceJson = Json.Write(source); f.SourceHash = Secrets.Hash(f.SourceJson); f.Body = Draft(source);
            ResetApproval(f); Event(db, f, a, "source.refreshed"); await db.SaveChangesAsync(); return Results.Ok(new { f.Id });
    }
    internal static async Task<IResult> SharedApprove(Guid id, AcademicApprovalInput input, HttpContext ctx, Access a, Data db)
    {
            await Gate(ctx, true); Guard.That(input.Confirmed, "请教务核对正文和收件对象后明确确认");
            var f = await Feedback(id, db, a); Revision(input.Revision, f.Revision);
            Guard.That(f.State == "draft", "当前反馈不是待审核状态", 409);
            Guard.That(!string.IsNullOrWhiteSpace(f.RecipientLabel), "请先填写并核实对应家长的企业微信名称");
            await Current(f, db, a); f.State = "approved"; f.ApprovedBy = a.User.Id; f.ApprovedAt = Clock.Now; f.Revision++; f.UpdatedAt = Clock.Now;
            Event(db, f, a, "review.approved"); await db.SaveChangesAsync(); return Results.Ok(new { f.Id });
    }
    internal static async Task<object> SharedHandoff(Guid id, HttpContext ctx, Access a, Data db)
    {
            await Gate(ctx, true); var f = await Feedback(id, db, a);
            Guard.That(f.State == "approved" && f.ApprovedBy.HasValue, "请先完成教务审核", 409); await Current(f, db, a);
            return new { f.Body, f.RecipientLabel, f.Channel, f.Revision };
    }
    internal static async Task<IResult> SharedCommunicate(Guid id, AcademicSentInput input, HttpContext ctx, Access a, Data db)
    {
            await Gate(ctx, true); Guard.That(input.Confirmed, "请仅在企业微信实际发送后确认登记");
            Guard.That(input.RequestKey is { Length: >= 8 and <= 100 }, "缺少有效操作标识");
            var f = await Feedback(id, db, a);
            var prior = await db.AcademicCommunications.SingleOrDefaultAsync(x => x.ConfirmedBy == a.User.Id && x.RequestKey == input.RequestKey);
            if (prior != null) { Guard.That(prior.FeedbackId == id && prior.FeedbackRevision == input.Revision, "该操作标识已用于其他内容", 409); return Results.Ok(new { prior.Id }); }
            Guard.That(!await db.AcademicMessageCommunications.AnyAsync(x => x.ConfirmedBy == a.User.Id && x.RequestKey == input.RequestKey), "该操作标识已用于其他发送记录，请核对历史", 409);
            Revision(input.Revision, f.Revision); Guard.That(f.State == "approved" && f.ApprovedBy.HasValue, "请先完成教务审核", 409); await Current(f, db, a);
            var log = new AcademicCommunication { FeedbackId = id, FeedbackRevision = f.Revision, ConfirmedBy = a.User.Id, RequestKey = input.RequestKey, RecipientLabel = f.RecipientLabel, Channel = f.Channel, Body = f.Body, SourceHash = f.SourceHash };
            db.AcademicCommunications.Add(log); f.State = "communicated"; f.Revision++; f.UpdatedAt = Clock.Now;
            Event(db, f, a, "communication.manually_confirmed"); await db.SaveChangesAsync(); return Results.Ok(new { log.Id });
    }

    internal static async Task<IResult> SharedReturn(Guid id, AcademicReturnInput input, HttpContext ctx, Data db, Access a)
    {
        await Gate(ctx, true);
        var f = await Feedback(id, db, a); Revision(input.Revision, f.Revision);
        Guard.That(f.State is "draft" or "approved", "已经登记的发送不能撤回，请创建新的审核版本", 409);
        Guard.That(!string.IsNullOrWhiteSpace(input.Reason) && input.Reason.Length <= 1000, "请填写退回原因，最多1000字");
        ResetApproval(f);
        AcademicBeta.Event(db, a, f.Id, f.ClassSessionId, "feedback.returned", new { reason = input.Reason.Trim(), f.Revision });
        await db.SaveChangesAsync(); return Results.Ok(new { f.Id });
    }
}
