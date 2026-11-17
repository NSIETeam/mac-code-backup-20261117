using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class AcademicPilotTests
{
    const string Api = "/api/academic-pilot";

    sealed class PilotFactory(bool enabled = true) : Factory
    {
        public readonly Guid AuthorId = Guid.NewGuid();
        public readonly Guid ReviewerId = Guid.NewGuid();
        public readonly Guid ForeignTeacherId = Guid.NewGuid();
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["AcademicPilot:Enabled"] = enabled.ToString(),
                ["AcademicPilot:AllowedStaffIds"] = $"{AuthorId},{ReviewerId},{ForeignTeacherId}",
                ["AcademicPilot:ReviewerStaffIds"] = ReviewerId.ToString()
            }));
        }
    }

    sealed record Fixture(Guid ClassId, Guid CourseId, Guid SessionId, Guid StudentId,
        Guid EmptyStudentId, Guid UnreviewedStudentId, Guid QuestionId, Guid OtherClassId);

    static async Task<Fixture> Seed(PilotFactory f)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "试点测试校区" };
        var course = new Course { Name = "数学" };
        var schoolClass = new SchoolClass { CampusId = campus.Id, Name = "试点一班" };
        var otherClass = new SchoolClass { CampusId = campus.Id, Name = "其他班" };
        var author = new Staff { Id = f.AuthorId, Name = "资料整理员", Role = "teacher", OpenId = "development:pilot-author" };
        var reviewer = new Staff { Id = f.ReviewerId, Name = "试点教务", Role = "teacher", OpenId = "development:pilot-reviewer" };
        var foreign = new Staff { Id = f.ForeignTeacherId, Name = "其他班教师", Role = "teacher", OpenId = "development:pilot-foreign" };
        var outsider = new Staff { Name = "非试点管理员", Role = "admin", OpenId = "development:pilot-outsider" };
        var student = new Student { ClassId = schoolClass.Id, Name = "测试学生甲", Code = "001" };
        var empty = new Student { ClassId = schoolClass.Id, Name = "测试学生乙", Code = "002" };
        var unreviewed = new Student { ClassId = schoolClass.Id, Name = "测试学生丙", Code = "003" };
        var lecture = new Lecture { ClassId = schoolClass.Id, CourseId = course.Id, Title = "分数应用", Sequence = 1 };
        var handout = new Handout { LectureId = lecture.Id, Version = 1, State = "published", FileName = "试点讲义.docx" };
        var question = new Question { HandoutId = handout.Id, Label = "例1", Order = 1, Confirmed = true,
            BlocksJson = Json.Write(new[] { new ContentBlock("", "计算 1/2 + 1/3", "paragraph") }) };
        var session = new ClassSession { ClassId = schoolClass.Id, CourseId = course.Id,
            HandoutId = handout.Id, StaffId = author.Id, LessonDate = "2026-10-31" };
        db.AddRange(campus, course, schoolClass, otherClass, author, reviewer, foreign, outsider,
            student, empty, unreviewed, lecture, handout, question, session);
        db.Assignments.AddRange(
            new Assignment { StaffId = author.Id, ClassId = schoolClass.Id, CourseId = course.Id },
            new Assignment { StaffId = reviewer.Id, ClassId = schoolClass.Id, CourseId = course.Id },
            new Assignment { StaffId = foreign.Id, ClassId = otherClass.Id, CourseId = course.Id });
        db.ClassSessionMarks.AddRange(
            new ClassSessionMark { ClassSessionId = session.Id, StudentId = student.Id, Reviewed = true,
                Revision = 1, RecordedBy = author.Id, WrongIdsJson = Json.Write(new[] { question.Id }),
                NotesJson = Json.Write(new Dictionary<Guid, string> { [question.Id] = "通分步骤需要复习" }) },
            new ClassSessionMark { ClassSessionId = session.Id, StudentId = empty.Id, Reviewed = true,
                Revision = 1, RecordedBy = author.Id },
            new ClassSessionMark { ClassSessionId = session.Id, StudentId = unreviewed.Id, Reviewed = false,
                Revision = 0, RecordedBy = author.Id });
        await db.SaveChangesAsync();
        return new(schoolClass.Id, course.Id, session.Id, student.Id, empty.Id, unreviewed.Id, question.Id, otherClass.Id);
    }

    static async Task<JsonElement> Get(HttpClient client, string path)
    {
        var response = await client.GetAsync(path);
        Assert.True(response.IsSuccessStatusCode, await response.Content.ReadAsStringAsync());
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    static async Task<Guid> Draft(HttpClient client, Fixture x, Guid? studentId = null)
        => (await Factory.Post(client, Api + "/drafts", new { classSessionId = x.SessionId, studentId = studentId ?? x.StudentId })).GetProperty("id").GetGuid();

    static async Task<JsonElement> Edit(HttpClient client, Guid id, string body = "请结合本次例1的通分步骤进行复习。", string recipientLabel = "测试学生甲的监护人")
    {
        var before = await Get(client, $"{Api}/drafts/{id}");
        var response = await client.PutAsJsonAsync($"{Api}/drafts/{id}", new
        {
            revision = before.GetProperty("revision").GetInt32(), body, recipientLabel, channel = "wecom_private"
        });
        Assert.True(response.IsSuccessStatusCode, await response.Content.ReadAsStringAsync());
        return await Get(client, $"{Api}/drafts/{id}");
    }

    static async Task<JsonElement> Approve(HttpClient reviewer, Guid id)
    {
        var draft = await Get(reviewer, $"{Api}/drafts/{id}");
        await Factory.Post(reviewer, $"{Api}/drafts/{id}/approve", new { revision = draft.GetProperty("revision").GetInt32(), confirmed = true });
        return await Get(reviewer, $"{Api}/drafts/{id}");
    }

    static async Task<(int Documents, int PrintJobs, int Work)> PrintCounts(PilotFactory f)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        return (await db.Documents.CountAsync(), await db.PrintJobs.CountAsync(), await db.Work.CountAsync());
    }

    [Fact]
    public async Task DisabledPilotIsHiddenAndExistingRoutesStillWork()
    {
        await using var f = new PilotFactory(false);
        var x = await Seed(f);
        using var reviewer = await f.Login("pilot-reviewer");
        Assert.Equal(HttpStatusCode.NotFound, (await reviewer.GetAsync(Api + "/me")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await reviewer.GetAsync(Api + "/sessions")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await reviewer.PostAsJsonAsync(Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await reviewer.GetAsync("/api/me")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await reviewer.GetAsync("/api/class-sessions")).StatusCode);
    }

    [Fact]
    public async Task PilotPageHasOneCanonicalDirectoryUrlAndAssetsDisappearWhenDisabled()
    {
        await using (var enabled = new PilotFactory())
        {
            using var client = enabled.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false });
            var directory = await client.GetAsync("/academic-pilot/");
            Assert.Equal(HttpStatusCode.OK, directory.StatusCode);
            Assert.Equal("text/html", directory.Content.Headers.ContentType?.MediaType);
            Assert.Contains("login-form", await directory.Content.ReadAsStringAsync());
            var redirect = await client.GetAsync("/academic-pilot");
            Assert.Equal(HttpStatusCode.Redirect, redirect.StatusCode);
            Assert.Equal(new Uri("http://localhost/academic-pilot/"), redirect.Headers.Location);
            Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/academic-pilot/app.js")).StatusCode);
        }
        await using (var disabled = new PilotFactory(false))
        {
            using var client = disabled.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false });
            Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync("/academic-pilot")).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync("/academic-pilot/")).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync("/academic-pilot/app.js")).StatusCode);
        }
    }

    [Fact]
    public async Task AllowlistDoesNotImplicitlyIncludeAdminsAndDoesNotExpandClassAccess()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        using var outsider = await f.Login("pilot-outsider");
        using var foreign = await f.Login("pilot-foreign");
        Assert.False((await Get(author, Api + "/me")).GetProperty("canReview").GetBoolean());
        Assert.True((await Get(reviewer, Api + "/me")).GetProperty("canReview").GetBoolean());
        Assert.Equal(HttpStatusCode.NotFound, (await outsider.GetAsync(Api + "/me")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await outsider.GetAsync(Api + "/sessions")).StatusCode);
        Assert.Empty((await Get(foreign, Api + "/sessions")).EnumerateArray());
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync($"{Api}/sessions/{x.SessionId}")).StatusCode);
        var id = await Draft(author, x);
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync($"{Api}/drafts/{id}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.PostAsJsonAsync(Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).StatusCode);
    }

    [Fact]
    public async Task DraftCreationUsesReviewedFactsIsIdempotentAndNeverEnqueuesPrinting()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        var counts = await PrintCounts(f);
        var id = await Draft(author, x);
        Assert.Equal(id, await Draft(author, x));
        var detail = await Get(author, $"{Api}/drafts/{id}");
        Assert.Equal("draft", detail.GetProperty("state").GetString());
        Assert.False(detail.GetProperty("sourceStale").GetBoolean());
        var question = Assert.Single(detail.GetProperty("source").GetProperty("questions").EnumerateArray());
        Assert.Equal(x.QuestionId, question.GetProperty("id").GetGuid());
        Assert.Equal("例1", question.GetProperty("label").GetString());
        Assert.Equal("通分步骤需要复习", question.GetProperty("note").GetString());
        var empty = await Draft(author, x, x.EmptyStudentId);
        var emptyDetail = await Get(author, $"{Api}/drafts/{empty}");
        Assert.Empty(emptyDetail.GetProperty("source").GetProperty("questions").EnumerateArray());
        Assert.DoesNotContain("全部掌握", emptyDetail.GetProperty("body").GetString());
        Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync(Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.UnreviewedStudentId })).StatusCode);
        Assert.Equal(counts, await PrintCounts(f));
    }

    [Fact]
    public async Task ApprovalRequiresReviewerExplicitConfirmationAndRecipient()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        var id = await Draft(author, x);
        var draft = await Get(author, $"{Api}/drafts/{id}");
        var initialRevision = draft.GetProperty("revision").GetInt32();
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/drafts/{id}/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/approve", new { revision = initialRevision, confirmed = true })).StatusCode);
        draft = await Edit(author, id);
        var revision = draft.GetProperty("revision").GetInt32();
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PostAsJsonAsync($"{Api}/drafts/{id}/approve", new { revision, confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/approve", new { revision, confirmed = false })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/approve", new { revision = initialRevision, confirmed = true })).StatusCode);
        var approved = await Approve(reviewer, id);
        Assert.Equal("approved", approved.GetProperty("state").GetString());
        var handoff = await Get(reviewer, $"{Api}/drafts/{id}/handoff");
        Assert.Equal(approved.GetProperty("body").GetString(), handoff.GetProperty("body").GetString());
        Assert.Equal("测试学生甲的监护人", handoff.GetProperty("recipientLabel").GetString());
        Assert.Empty(approved.GetProperty("communications").EnumerateArray());
        Assert.Equal((0, 0, 0), await PrintCounts(f));
    }

    [Fact]
    public async Task EditingApprovedContentInvalidatesApprovalAndRejectsLostUpdates()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        var id = await Draft(author, x);
        await Edit(author, id);
        var approved = await Approve(reviewer, id);
        var oldRevision = approved.GetProperty("revision").GetInt32();
        var edited = await Edit(author, id, "请先复习通分，再独立完成这道题。", "另一个已核实的监护人");
        Assert.Equal("draft", edited.GetProperty("state").GetString());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/drafts/{id}/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/communications", new { revision = oldRevision, requestKey = "after-edit", confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync($"{Api}/drafts/{id}", new { revision = oldRevision, body = "旧页面覆盖", recipientLabel = "测试监护人", channel = "wecom_private" })).StatusCode);
        Assert.Equal("请先复习通分，再独立完成这道题。", (await Get(author, $"{Api}/drafts/{id}")).GetProperty("body").GetString());
    }

    [Fact]
    public async Task ChangedClassroomMarksInvalidateDraftAndPreviouslyApprovedHandoff()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        var id = await Draft(author, x);
        await Edit(author, id);
        var approved = await Approve(reviewer, id);
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var mark = await db.ClassSessionMarks.SingleAsync(m => m.ClassSessionId == x.SessionId && m.StudentId == x.StudentId);
            mark.Revision++;
            mark.NotesJson = Json.Write(new Dictionary<Guid, string> { [x.QuestionId] = "老师重新确认的通分问题" });
            await db.SaveChangesAsync();
        }
        var stale = await Get(author, $"{Api}/drafts/{id}");
        Assert.True(stale.GetProperty("sourceStale").GetBoolean());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/drafts/{id}/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/approve", new { revision = stale.GetProperty("revision").GetInt32(), confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/communications", new { revision = approved.GetProperty("revision").GetInt32(), requestKey = "stale-source", confirmed = true })).StatusCode);
        await Factory.Post(author, $"{Api}/drafts/{id}/refresh", new { revision = stale.GetProperty("revision").GetInt32() });
        var refreshed = await Get(author, $"{Api}/drafts/{id}");
        Assert.False(refreshed.GetProperty("sourceStale").GetBoolean());
        Assert.Equal("draft", refreshed.GetProperty("state").GetString());
        Assert.Equal("老师重新确认的通分问题", refreshed.GetProperty("source").GetProperty("questions")[0].GetProperty("note").GetString());
    }

    [Fact]
    public async Task NewRetestEvidenceRequiresRefreshBeforeApproval()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        var id = await Draft(author, x);
        var draft = await Edit(author, id);
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.LearningEvents.Add(new LearningEvent { StudentId = x.StudentId, ClassId = x.ClassId, CourseId = x.CourseId,
                QuestionId = x.QuestionId, Kind = "retest", State = "correct", RecordedBy = f.AuthorId, RequestKey = "new-retest" });
            await db.SaveChangesAsync();
        }
        Assert.True((await Get(author, $"{Api}/drafts/{id}")).GetProperty("sourceStale").GetBoolean());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/approve", new { revision = draft.GetProperty("revision").GetInt32(), confirmed = true })).StatusCode);
    }

    [Fact]
    public async Task ManualCommunicationNeedsHumanConfirmationAndPreservesApprovedSnapshot()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        var id = await Draft(author, x);
        await Edit(author, id);
        var approved = await Approve(reviewer, id);
        var revision = approved.GetProperty("revision").GetInt32();
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PostAsJsonAsync($"{Api}/drafts/{id}/communications", new { revision, requestKey = "manual-send-1", confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/communications", new { revision, requestKey = "manual-send-1", confirmed = false })).StatusCode);
        var body = new { revision, requestKey = "manual-send-1", confirmed = true };
        var result = await Factory.Post(reviewer, $"{Api}/drafts/{id}/communications", body);
        var repeat = await Factory.Post(reviewer, $"{Api}/drafts/{id}/communications", body);
        var communicationId = result.GetProperty("id").GetGuid();
        Assert.Equal(communicationId, repeat.GetProperty("id").GetGuid());
        var sent = await Get(reviewer, $"{Api}/drafts/{id}");
        Assert.Equal("communicated", sent.GetProperty("state").GetString());
        var communication = Assert.Single(sent.GetProperty("communications").EnumerateArray());
        Assert.Equal(approved.GetProperty("body").GetString(), communication.GetProperty("body").GetString());
        Assert.Equal(approved.GetProperty("recipientLabel").GetString(), communication.GetProperty("recipientLabel").GetString());
        Assert.Equal("awaiting_reply", communication.GetProperty("status").GetString());
        Assert.Equal((0, 0, 0), await PrintCounts(f));

        var reply = await reviewer.PutAsJsonAsync($"{Api}/communications/{communicationId}", new
        {
            revision = communication.GetProperty("revision").GetInt32(), responseNote = "家长表示今晚会和孩子一起检查。",
            followUpOn = "2026-11-03", status = "follow_up"
        });
        Assert.True(reply.IsSuccessStatusCode, await reply.Content.ReadAsStringAsync());
        communication = Assert.Single((await Get(reviewer, $"{Api}/drafts/{id}")).GetProperty("communications").EnumerateArray());
        Assert.Equal("follow_up", communication.GetProperty("status").GetString());
        Assert.Equal("家长表示今晚会和孩子一起检查。", communication.GetProperty("responseNote").GetString());
        Assert.Equal(approved.GetProperty("body").GetString(), communication.GetProperty("body").GetString());

        await Edit(author, id, "新的反馈，需要再次审核。", "已重新确认的监护人");
        var revised = await Approve(reviewer, id);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/communications", new
        {
            revision = revised.GetProperty("revision").GetInt32(), requestKey = "manual-send-1", confirmed = true
        })).StatusCode);
        communication = Assert.Single((await Get(reviewer, $"{Api}/drafts/{id}")).GetProperty("communications").EnumerateArray());
        Assert.Equal(approved.GetProperty("body").GetString(), communication.GetProperty("body").GetString());
    }

    [Fact]
    public async Task CrossClassStudentCannotBeInjectedIntoAnAuthorizedClassroom()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        var otherStudent = new Student { ClassId = x.OtherClassId, Code = "other-1", Name = "其他班学生" };
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.Students.Add(otherStudent);
            await db.SaveChangesAsync();
        }
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PostAsJsonAsync(Api + "/drafts",
            new { classSessionId = x.SessionId, studentId = otherStudent.Id })).StatusCode);
        using var checkScope = f.Services.CreateScope();
        Assert.Empty(await checkScope.ServiceProvider.GetRequiredService<Data>().AcademicFeedbacks.ToListAsync());
        Assert.Equal((0, 0, 0), await PrintCounts(f));
    }

    [Fact]
    public async Task WithdrawingReviewedStatusAllowsOpeningTheExistingDraftButBlocksRefreshAndHandoff()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        var id = await Draft(author, x);
        await Edit(author, id);
        var approved = await Approve(reviewer, id);
        var revision = approved.GetProperty("revision").GetInt32();
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var mark = await db.ClassSessionMarks.SingleAsync(m => m.ClassSessionId == x.SessionId && m.StudentId == x.StudentId);
            mark.Reviewed = false;
            mark.Revision++;
            await db.SaveChangesAsync();
        }
        Assert.True((await Get(author, $"{Api}/drafts/{id}")).GetProperty("sourceStale").GetBoolean());
        var reopened = await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId });
        Assert.Equal(id, reopened.GetProperty("id").GetGuid());
        var unchanged = await Get(author, $"{Api}/drafts/{id}");
        Assert.Equal(revision, unchanged.GetProperty("revision").GetInt32());
        Assert.Equal(approved.GetProperty("body").GetString(), unchanged.GetProperty("body").GetString());
        Assert.Equal(approved.GetProperty("approvedAt").GetString(), unchanged.GetProperty("approvedAt").GetString());
        Assert.True(unchanged.GetProperty("sourceStale").GetBoolean());
        Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync($"{Api}/drafts/{id}/refresh", new { revision })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/drafts/{id}/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/communications",
            new { revision, requestKey = "review-withdrawn", confirmed = true })).StatusCode);
        Assert.Empty((await Get(reviewer, $"{Api}/drafts/{id}")).GetProperty("communications").EnumerateArray());
    }

    [Fact]
    public async Task ConcurrentCreatesAndManualConfirmationsCannotDuplicateBusinessRecords()
    {
        await using var f = new PilotFactory();
        var x = await Seed(f);
        using var author = await f.Login("pilot-author");
        using var reviewer = await f.Login("pilot-reviewer");
        var responses = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => author.PostAsJsonAsync(Api + "/drafts",
            new { classSessionId = x.SessionId, studentId = x.StudentId })));
        foreach (var response in responses)
            Assert.True(response.StatusCode is HttpStatusCode.OK or HttpStatusCode.Conflict, await response.Content.ReadAsStringAsync());
        Assert.Contains(responses, response => response.StatusCode == HttpStatusCode.OK);
        Guid id;
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            id = Assert.Single(await db.AcademicFeedbacks.ToListAsync()).Id;
            Assert.Single(await db.AcademicPilotEvents.Where(e => e.Action == "draft.created").ToListAsync());
        }
        await Edit(author, id);
        var approved = await Approve(reviewer, id);
        var input = new { revision = approved.GetProperty("revision").GetInt32(), requestKey = "concurrent-manual-send", confirmed = true };
        var confirmations = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => reviewer.PostAsJsonAsync($"{Api}/drafts/{id}/communications", input)));
        foreach (var response in confirmations)
            Assert.True(response.StatusCode is HttpStatusCode.OK or HttpStatusCode.Conflict, await response.Content.ReadAsStringAsync());
        Assert.Contains(confirmations, response => response.StatusCode == HttpStatusCode.OK);
        var repeat = await Factory.Post(reviewer, $"{Api}/drafts/{id}/communications", input);
        var detail = await Get(reviewer, $"{Api}/drafts/{id}");
        var sent = Assert.Single(detail.GetProperty("communications").EnumerateArray());
        Assert.Equal(repeat.GetProperty("id").GetGuid(), sent.GetProperty("id").GetGuid());
        Assert.Equal("communicated", detail.GetProperty("state").GetString());
        Assert.Equal((0, 0, 0), await PrintCounts(f));
    }
}
