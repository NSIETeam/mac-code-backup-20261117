using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public partial class AcademicBetaTests
{
    [Fact]
    public async Task LearningFeedbackEntrypointsShareOneDraftAndOnePendingReviewAndTodo()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var before = await OriginalBusinessSnapshot(f);
        var id = (await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).GetProperty("id").GetGuid();
        var opened = await Factory.Post(author, Api + "/messages", new { classSessionId = x.SessionId, studentId = x.StudentId, kind = "learning_feedback", requestKey = "open-shared-learning-draft" });
        Assert.Equal(id, opened.GetProperty("id").GetGuid());
        Assert.Equal("feedback", opened.GetProperty("editorKind").GetString());
        Assert.Equal(id, await Message(author, x, "learning_feedback", x.StudentId, "open-shared-learning-draft"));
        Assert.Equal(id, await Message(author, x, "learning_feedback", x.StudentId, "another-entry-same-draft"));
        var list = Assert.Single((await Get(author, Api + "/messages?classSessionId=" + x.SessionId)).EnumerateArray());
        Assert.Equal(id, list.GetProperty("id").GetGuid());
        Assert.Equal("feedback", list.GetProperty("editorKind").GetString());
        Assert.Equal("learning_feedback", list.GetProperty("kind").GetString());
        Assert.Equal(id, Assert.Single((await Get(reviewer, Api + "/reviews?classSessionId=" + x.SessionId)).EnumerateArray()).GetProperty("id").GetGuid());
        var todo = Assert.Single((await Get(author, Api + "/todos?classSessionId=" + x.SessionId)).EnumerateArray(), item => item.GetProperty("kind").GetString() is "feedback_review" or "message_review");
        Assert.Equal(id, todo.GetProperty("originId").GetGuid());
        var stats = await Get(author, Api + "/statistics?from=2026-10-31&to=2026-10-31&classSessionId=" + x.SessionId);
        Assert.Equal(1, stats.GetProperty("feedback").GetProperty("draft").GetInt32());
        Assert.Equal(0, stats.GetProperty("messages").GetProperty("draft").GetInt32());
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            Assert.Single(await db.AcademicFeedbacks.ToArrayAsync());
            Assert.Empty(await db.AcademicMessages.ToArrayAsync());
        }
        var second = await Message(author, x, "learning_feedback", x.SecondStudentId, "message-entry-first");
        Assert.Equal(second, (await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.SecondStudentId })).GetProperty("id").GetGuid());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task EditsFromEitherEntrypointShareBodyRecipientRevisionAndInvalidateApproval()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var before = await OriginalBusinessSnapshot(f);
        var id = await Message(author, x, "learning_feedback", x.StudentId);
        var draftPath = $"{Api}/drafts/{id}";
        var messagePath = $"{Api}/messages/{id}";
        var draft = await Get(author, draftPath);
        await Put(author, draftPath, new { revision = Revision(draft), body = "老师核实后的第一版反馈。", recipientLabel = "甲的已核实监护人", channel = "wecom_private" });
        var message = await Get(author, messagePath);
        Assert.Equal("老师核实后的第一版反馈。", message.GetProperty("body").GetString());
        Assert.Equal("甲的已核实监护人", message.GetProperty("recipientLabel").GetString());
        Assert.Equal("feedback", message.GetProperty("editorKind").GetString());
        var oldRevision = Revision(message);
        await Put(author, messagePath, new { revision = oldRevision, body = "家长沟通入口修订后的唯一正文。", recipientLabel = "甲的已确认家长私聊", channel = "wecom_private" });
        draft = await Get(author, draftPath);
        Assert.Equal("家长沟通入口修订后的唯一正文。", draft.GetProperty("body").GetString());
        Assert.Equal("甲的已确认家长私聊", draft.GetProperty("recipientLabel").GetString());
        Assert.Equal(oldRevision + 1, Revision(draft));
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(draftPath, new { revision = oldRevision, body = "另一页的过期正文", recipientLabel = "旧对象", channel = "wecom_private" })).StatusCode);
        foreach (var path in new[] { draftPath, messagePath })
        {
            Assert.Equal(HttpStatusCode.Forbidden, (await author.PostAsJsonAsync(path + "/approve", new { revision = Revision(draft), confirmed = true })).StatusCode);
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync(path + "/handoff")).StatusCode);
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(path + "/communications", new { revision = Revision(draft), requestKey = Guid.NewGuid().ToString("N"), confirmed = true })).StatusCode);
        }
        Assert.Equal(HttpStatusCode.BadRequest, (await reviewer.PostAsJsonAsync(messagePath + "/approve", new { revision = Revision(draft), confirmed = false })).StatusCode);
        await Factory.Post(reviewer, messagePath + "/approve", new { revision = Revision(draft), confirmed = true });
        var approved = await Get(reviewer, draftPath);
        Assert.Equal("approved", approved.GetProperty("state").GetString());
        Assert.Equal("家长沟通入口修订后的唯一正文。", (await Get(reviewer, messagePath + "/handoff")).GetProperty("body").GetString());
        await Put(author, draftPath, new { revision = Revision(approved), body = "审核后更正的内容，必须重新审核。", recipientLabel = "甲的已确认家长私聊", channel = "wecom_private" });
        foreach (var path in new[] { draftPath, messagePath })
        {
            var changed = await Get(author, path);
            Assert.Equal("draft", changed.GetProperty("state").GetString());
            Assert.Equal(JsonValueKind.Null, changed.GetProperty("approvedAt").ValueKind);
            Assert.Equal(JsonValueKind.Null, changed.GetProperty("approvedByName").ValueKind);
            Assert.Empty(changed.GetProperty("communications").EnumerateArray());
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync(path + "/handoff")).StatusCode);
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(path + "/communications", new { revision = Revision(changed), requestKey = Guid.NewGuid().ToString("N"), confirmed = true })).StatusCode);
        }
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task SharedDraftCreationIsUniqueAcrossConcurrentEntrypointsAndRequestKeysCannotChangeContext()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        var creates = await Task.WhenAll(Enumerable.Range(0, 8).Select(i => i % 2 == 0
            ? author.PostAsJsonAsync(Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })
            : author.PostAsJsonAsync(Api + "/messages", new { classSessionId = x.SessionId, studentId = x.StudentId, kind = "learning_feedback", requestKey = "concurrent-shared-key" })));
        foreach (var response in creates) Assert.True(response.StatusCode is HttpStatusCode.OK or HttpStatusCode.Conflict, await response.Content.ReadAsStringAsync());
        Assert.Contains(creates, response => response.StatusCode == HttpStatusCode.OK);
        var id = await Message(author, x, "learning_feedback", x.StudentId, "concurrent-shared-key");
        foreach (var response in creates.Where(r => r.StatusCode == HttpStatusCode.OK))
            Assert.Equal(id, (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid());
        foreach (var input in new object[] {
            new { classSessionId = x.SessionId, studentId = (Guid?)x.SecondStudentId, kind = "learning_feedback", requestKey = "concurrent-shared-key" },
            new { classSessionId = x.SessionId, studentId = (Guid?)null, kind = "class_notice", requestKey = "concurrent-shared-key" } })
            Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync(Api + "/messages", input)).StatusCode);
        await Message(author, x, key: "existing-general-key");
        Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync(Api + "/messages", new { classSessionId = x.SessionId, studentId = x.StudentId, kind = "learning_feedback", requestKey = "existing-general-key" })).StatusCode);
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            Assert.Single(await db.AcademicFeedbacks.ToArrayAsync());
            Assert.Equal("class_notice", Assert.Single(await db.AcademicMessages.ToArrayAsync()).Kind);
        }
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task LegacyLearningMessageIdsAreReadOnlyAliasesAndPreserveTheirOriginalTextAndSentHistory()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var before = await OriginalBusinessSnapshot(f);
        var canonicalId = (await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).GetProperty("id").GetGuid();
        var canonicalPath = $"{Api}/drafts/{canonicalId}";
        var canonical = await Get(author, canonicalPath);
        await Put(author, canonicalPath, new { revision = Revision(canonical), body = "已人工修订的共享正文，不能被旧副本覆盖。", recipientLabel = "甲的已核实家长", channel = "wecom_private" });
        var legacyId = Guid.NewGuid();
        var legacyLogId = Guid.NewGuid();
        string legacySnapshot;
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var old = new AcademicMessage { Id = legacyId, ClassSessionId = x.SessionId, StudentId = x.StudentId, CreatedBy = f.AuthorId,
                RequestKey = "legacy-personal-message", Kind = "learning_feedback", Title = "旧学习反馈", Body = "合并前的人工沟通正文。",
                SourceJson = Json.Write(new { relevant = canonical.GetProperty("source") }), SourceHash = "legacy-source-hash", RecipientLabel = "合并前的家长名称",
                Channel = "wecom_private", State = "approved", Revision = 7, ApprovedBy = f.ReviewerId, ApprovedAt = Clock.Now };
            var log = new AcademicMessageCommunication { Id = legacyLogId, MessageId = legacyId, MessageRevision = 5,
                ConfirmedBy = f.ReviewerId, RequestKey = "legacy-real-send", RecipientLabel = "实际发送时的家长名称", Channel = "wecom_private",
                Body = "过去已实际发送的正文。", SourceHash = "past-source-hash", Status = "awaiting_reply" };
            db.AddRange(old, log); await db.SaveChangesAsync();
            legacySnapshot = Json.Write(new { old, log });
        }
        var legacyPath = $"{Api}/messages/{legacyId}";
        var canonicalMessagePath = $"{Api}/messages/{canonicalId}";
        var detail = await Get(author, legacyPath);
        Assert.Equal(canonicalId, detail.GetProperty("id").GetGuid());
        Assert.Equal("已人工修订的共享正文，不能被旧副本覆盖。", detail.GetProperty("body").GetString());
        Assert.Equal("draft", detail.GetProperty("state").GetString());
        Assert.Equal(JsonValueKind.Null, detail.GetProperty("approvedAt").ValueKind);
        var archive = Assert.Single(detail.GetProperty("legacyDrafts").EnumerateArray());
        Assert.Equal(legacyId, archive.GetProperty("id").GetGuid());
        Assert.Equal("合并前的人工沟通正文。", archive.GetProperty("body").GetString());
        Assert.Equal("过去已实际发送的正文。", Assert.Single(archive.GetProperty("communications").EnumerateArray()).GetProperty("body").GetString());
        Assert.Equal(canonicalId, Assert.Single((await Get(author, Api + "/messages?classSessionId=" + x.SessionId)).EnumerateArray()).GetProperty("id").GetGuid());
        Assert.Equal(canonicalId, Assert.Single((await Get(reviewer, Api + "/reviews?classSessionId=" + x.SessionId)).EnumerateArray()).GetProperty("id").GetGuid());
        Assert.Single((await Get(author, Api + "/todos?classSessionId=" + x.SessionId)).EnumerateArray(), row => row.GetProperty("kind").GetString() is "feedback_review" or "message_review");
        var stats = await Get(author, Api + "/statistics?from=2026-10-31&to=2026-10-31&classSessionId=" + x.SessionId);
        Assert.Equal(1, stats.GetProperty("feedback").GetProperty("draft").GetInt32());
        Assert.Equal(0, stats.GetProperty("messages").GetProperty("approved").GetInt32());
        Assert.Equal(1, stats.GetProperty("communications").GetProperty("awaitingReply").GetInt32());
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(legacyPath, new { revision = 7, body = "旧副本窗口覆盖", recipientLabel = "旧对象", channel = "wecom_private" })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(legacyPath, new { revision = Revision(detail), body = "旧地址不可直接写共享稿", recipientLabel = "旧对象", channel = "wecom_private" })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync(legacyPath + "/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(legacyPath + "/communications", new { revision = Revision(detail), requestKey = "old-approval-cannot-send", confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(legacyPath + "/communications", new { revision = 5, requestKey = "legacy-real-send", confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(legacyPath + "/approve", new { revision = Revision(detail), confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync(legacyPath + "/refresh", new { revision = Revision(detail) })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(legacyPath + "/return", new { revision = Revision(detail), reason = "旧地址不改变共享稿" })).StatusCode);
        await Put(author, canonicalMessagePath, new { revision = Revision(detail), body = "从旧入口重新打开后保存到同一份草稿。", recipientLabel = "甲的已核实家长", channel = "wecom_private" });
        canonical = await Get(author, canonicalPath);
        Assert.Equal("从旧入口重新打开后保存到同一份草稿。", canonical.GetProperty("body").GetString());
        await Factory.Post(reviewer, canonicalMessagePath + "/approve", new { revision = Revision(canonical), confirmed = true });
        canonical = await Get(reviewer, canonicalPath);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(canonicalMessagePath + "/communications", new { revision = Revision(canonical), requestKey = "legacy-real-send", confirmed = true })).StatusCode);
        var input = new { revision = Revision(canonical), requestKey = "same-shared-manual-send", confirmed = true };
        var sent = await Factory.Post(reviewer, canonicalMessagePath + "/communications", input);
        Assert.Equal(sent.GetProperty("id").GetGuid(), (await Factory.Post(reviewer, canonicalPath + "/communications", input)).GetProperty("id").GetGuid());
        var after = await Get(reviewer, legacyPath);
        Assert.Equal("communicated", after.GetProperty("state").GetString());
        Assert.Equal("从旧入口重新打开后保存到同一份草稿。", Assert.Single(after.GetProperty("communications").EnumerateArray()).GetProperty("body").GetString());
        var noticeId = await Message(author, x, key: "cross-kind-notice");
        await EditMessage(author, noticeId, "另一个班级通知。");
        var notice = await ApproveMessage(reviewer, noticeId);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/messages/{noticeId}/communications", new { revision = Revision(notice), requestKey = "same-shared-manual-send", confirmed = true })).StatusCode);
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            Assert.Equal(legacySnapshot, Json.Write(new { old = await db.AcademicMessages.SingleAsync(m => m.Id == legacyId), log = await db.AcademicMessageCommunications.SingleAsync(c => c.Id == legacyLogId) }));
            Assert.Single(await db.AcademicCommunications.ToArrayAsync());
            Assert.Single(await db.AcademicMessageCommunications.ToArrayAsync());
        }
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task LegacyRevisionCollisionCannotApproveEditOrSendADifferentCanonicalBody()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var id = await Message(author, x, "learning_feedback", x.StudentId);
        var path = $"{Api}/drafts/{id}";
        var draft = await Get(author, path);
        await Put(author, path, new { revision = Revision(draft), body = "新的共享正文，尚未由教务审核。", recipientLabel = "新的已核实家长", channel = "wecom_private" });
        draft = await Get(author, path);
        var oldId = Guid.NewGuid();
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.AcademicMessages.Add(new AcademicMessage { Id = oldId, ClassSessionId = x.SessionId, StudentId = x.StudentId, CreatedBy = f.AuthorId,
                RequestKey = "legacy-colliding-revision", Kind = "learning_feedback", Title = "旧消息",
                Body = "旧页面核对的是另一份正文。", RecipientLabel = "旧页面家长", Channel = "wecom_private", State = "approved",
                Revision = Revision(draft), ApprovedBy = f.ReviewerId, ApprovedAt = Clock.Now });
            await db.SaveChangesAsync();
        }
        var before = await OriginalBusinessSnapshot(f);
        var oldPath = $"{Api}/messages/{oldId}";
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(oldPath + "/approve", new { revision = Revision(draft), confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(oldPath, new { revision = Revision(draft), body = "旧窗口静默覆盖", recipientLabel = "旧页面家长", channel = "wecom_private" })).StatusCode);
        var untouched = await Get(reviewer, path);
        Assert.Equal("draft", untouched.GetProperty("state").GetString());
        Assert.Equal(draft.GetProperty("body").GetString(), untouched.GetProperty("body").GetString());
        Assert.Equal(Revision(draft), Revision(untouched));
        await Factory.Post(reviewer, path + "/approve", new { revision = Revision(draft), confirmed = true });
        var approved = await Get(reviewer, path);
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var old = await db.AcademicMessages.SingleAsync(m => m.Id == oldId);
            old.Revision = Revision(approved);
            await db.SaveChangesAsync();
        }
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync(oldPath + "/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(oldPath + "/communications", new { revision = Revision(approved), requestKey = "collision-must-not-send", confirmed = true })).StatusCode);
        approved = await Get(reviewer, path);
        Assert.Equal("approved", approved.GetProperty("state").GetString());
        Assert.Empty(approved.GetProperty("communications").EnumerateArray());
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            Assert.Empty(await db.AcademicCommunications.ToArrayAsync());
            Assert.Empty(await db.AcademicMessageCommunications.ToArrayAsync());
        }
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task OpeningAnOrphanLegacyMessageDoesNotWriteAndExplicitOpeningPreservesItsTextWithoutApproval()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var id = (await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).GetProperty("id").GetGuid();
        var source = (await Get(author, $"{Api}/drafts/{id}")).GetProperty("source");
        var oldId = Guid.NewGuid();
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.AcademicPilotEvents.RemoveRange(await db.AcademicPilotEvents.Where(e => e.FeedbackId == id).ToArrayAsync());
            db.AcademicFeedbacks.Remove(await db.AcademicFeedbacks.SingleAsync(d => d.Id == id));
            db.AcademicMessages.Add(new AcademicMessage { Id = oldId, ClassSessionId = x.SessionId, StudentId = x.StudentId, CreatedBy = f.AuthorId,
                RequestKey = "orphan-legacy-learning", Kind = "learning_feedback", Title = "只有旧沟通草稿", Body = "旧稿中的老师人工修订必须保留。",
                SourceJson = Json.Write(new { relevant = source }), SourceHash = "legacy-source", RecipientLabel = "已记录的监护人", Channel = "wecom_private",
                State = "approved", Revision = 9, ApprovedBy = f.ReviewerId, ApprovedAt = Clock.Now });
            await db.SaveChangesAsync();
        }
        var before = await OriginalBusinessSnapshot(f);
        Assert.Equal(HttpStatusCode.Conflict, (await author.GetAsync($"{Api}/messages/{oldId}")).StatusCode);
        using (var scope = f.Services.CreateScope()) Assert.Empty(await scope.ServiceProvider.GetRequiredService<Data>().AcademicFeedbacks.ToArrayAsync());
        var canonicalId = await Message(author, x, "learning_feedback", x.StudentId, "open-orphan-canonically");
        var canonical = await Get(author, $"{Api}/drafts/{canonicalId}");
        Assert.Equal("旧稿中的老师人工修订必须保留。", canonical.GetProperty("body").GetString());
        Assert.Equal("已记录的监护人", canonical.GetProperty("recipientLabel").GetString());
        Assert.Equal("draft", canonical.GetProperty("state").GetString());
        Assert.Equal(JsonValueKind.Null, canonical.GetProperty("approvedAt").ValueKind);
        Assert.Single(canonical.GetProperty("legacyDrafts").EnumerateArray());
        Assert.Equal(canonicalId, (await Get(author, $"{Api}/messages/{oldId}")).GetProperty("id").GetGuid());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/messages/{oldId}/handoff")).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task CanonicalAndLegacyMessageAliasesCannotExposeAnotherTeachersClass()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var foreign = await f.Login("beta-foreign");
        using var outsider = await f.Login("beta-outsider");
        var id = await Message(author, x, "learning_feedback", x.StudentId);
        var oldId = Guid.NewGuid();
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.AcademicMessages.Add(new AcademicMessage { Id = oldId, ClassSessionId = x.SessionId, StudentId = x.StudentId, CreatedBy = f.AuthorId,
                RequestKey = "legacy-scope-check", Kind = "learning_feedback", Body = "禁止跨班读取", Channel = "wecom_private" });
            await db.SaveChangesAsync();
        }
        var before = await OriginalBusinessSnapshot(f);
        foreach (var alias in new[] { id, oldId })
        {
            Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync($"{Api}/messages/{alias}")).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden, (await foreign.PutAsJsonAsync($"{Api}/messages/{alias}", new { revision = 0, body = "跨班覆盖", recipientLabel = "别班家长", channel = "wecom_private" })).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound, (await outsider.GetAsync($"{Api}/messages/{alias}")).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound, (await outsider.PutAsJsonAsync($"{Api}/messages/{alias}", new { revision = 0, body = "未开放账号", recipientLabel = "家长", channel = "wecom_private" })).StatusCode);
        }
        Assert.Empty((await Get(foreign, Api + "/messages")).EnumerateArray());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }
}
