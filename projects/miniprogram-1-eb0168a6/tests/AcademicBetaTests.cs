using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public partial class AcademicBetaTests
{
    const string Api = "/api/academic-pilot";

    class BetaFactory(bool enabled = true) : Factory
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
                ["AcademicPilot:ReviewerStaffIds"] = ReviewerId.ToString(),
                ["AcademicPilot:AllowedAccounts"] = "",
                ["AcademicPilot:ReviewerAccounts"] = ""
            }));
        }
    }

    sealed record Fixture(Guid ClassId, Guid CourseId, Guid SessionId, Guid StudentId,
        Guid SecondStudentId, Guid ThirdStudentId, Guid UnreviewedStudentId, Guid QuestionId,
        Guid OtherQuestionId, Guid ForeignSessionId, Guid ForeignStudentId, Guid OtherCourseSessionId);

    static async Task<Fixture> Seed(BetaFactory f)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "Beta验收校区" };
        var course = new Course { Name = "数学" };
        var otherCourse = new Course { Name = "未授权语文" };
        var schoolClass = new SchoolClass { CampusId = campus.Id, Name = "Beta本班" };
        var foreignClass = new SchoolClass { CampusId = campus.Id, Name = "不可外泄的其他班" };
        var author = new Staff { Id = f.AuthorId, Name = "Beta整理老师", Role = "teacher", OpenId = "development:beta-author" };
        var reviewer = new Staff { Id = f.ReviewerId, Name = "Beta审核教务", Role = "teacher", OpenId = "development:beta-reviewer" };
        var foreign = new Staff { Id = f.ForeignTeacherId, Name = "其他班老师", Role = "teacher", OpenId = "development:beta-foreign" };
        var outsider = new Staff { Name = "未开通的管理员", Role = "admin", OpenId = "development:beta-outsider" };
        var student = new Student { ClassId = schoolClass.Id, Name = "本班学生甲", Code = "001" };
        var second = new Student { ClassId = schoolClass.Id, Name = "本班学生乙", Code = "002" };
        var third = new Student { ClassId = schoolClass.Id, Name = "本班学生丙", Code = "003" };
        var unreviewed = new Student { ClassId = schoolClass.Id, Name = "本班学生丁", Code = "004" };
        var foreignStudent = new Student { ClassId = foreignClass.Id, Name = "不可外泄的其他班学生", Code = "001" };
        var lecture = new Lecture { ClassId = null, CourseId = course.Id, Title = "分数通分", Sequence = 1 };
        var otherLecture = new Lecture { ClassId = null, CourseId = otherCourse.Id, Title = "不可外泄的语文课", Sequence = 1 };
        var handout = new Handout { LectureId = lecture.Id, Version = 1, State = "published", FileName = "Beta数学.docx" };
        var otherHandout = new Handout { LectureId = otherLecture.Id, Version = 1, State = "published", FileName = "Beta语文.docx" };
        var question = new Question { HandoutId = handout.Id, Label = "例1", Order = 1, Confirmed = true,
            BlocksJson = Json.Write(new[] { new ContentBlock("", "计算 1/2 + 1/3", "paragraph") }) };
        var otherQuestion = new Question { HandoutId = handout.Id, Label = "练1", Order = 2, Confirmed = true };
        var session = new ClassSession { ClassId = schoolClass.Id, CourseId = course.Id, HandoutId = handout.Id, StaffId = author.Id, LessonDate = "2026-10-31" };
        var foreignSession = new ClassSession { ClassId = foreignClass.Id, CourseId = course.Id, HandoutId = handout.Id, StaffId = foreign.Id, LessonDate = "2026-10-31" };
        var otherCourseSession = new ClassSession { ClassId = schoolClass.Id, CourseId = otherCourse.Id, HandoutId = otherHandout.Id, StaffId = foreign.Id, LessonDate = "2026-10-31" };
        db.AddRange(campus, course, otherCourse, schoolClass, foreignClass, author, reviewer, foreign, outsider,
            student, second, third, unreviewed, foreignStudent, lecture, otherLecture, handout, otherHandout,
            question, otherQuestion, session, foreignSession, otherCourseSession);
        db.Assignments.AddRange(
            new Assignment { StaffId = author.Id, ClassId = schoolClass.Id, CourseId = course.Id },
            new Assignment { StaffId = reviewer.Id, ClassId = schoolClass.Id, CourseId = course.Id },
            new Assignment { StaffId = foreign.Id, ClassId = foreignClass.Id, CourseId = course.Id });
        db.ClassSessionMarks.AddRange(
            new ClassSessionMark { ClassSessionId = session.Id, StudentId = student.Id, Reviewed = true, Revision = 1,
                RecordedBy = author.Id, WrongIdsJson = Json.Write(new[] { question.Id }), NotesJson = Json.Write(new Dictionary<Guid, string> { [question.Id] = "通分过程需复核" }) },
            new ClassSessionMark { ClassSessionId = session.Id, StudentId = second.Id, Reviewed = true, Revision = 1, RecordedBy = author.Id },
            new ClassSessionMark { ClassSessionId = session.Id, StudentId = third.Id, Reviewed = true, Revision = 1, RecordedBy = author.Id },
            new ClassSessionMark { ClassSessionId = session.Id, StudentId = unreviewed.Id, Reviewed = false, Revision = 0, RecordedBy = author.Id },
            new ClassSessionMark { ClassSessionId = foreignSession.Id, StudentId = foreignStudent.Id, Reviewed = true, Revision = 1,
                RecordedBy = foreign.Id, WrongIdsJson = Json.Write(new[] { question.Id }) });
        await db.SaveChangesAsync();
        return new(schoolClass.Id, course.Id, session.Id, student.Id, second.Id, third.Id, unreviewed.Id,
            question.Id, otherQuestion.Id, foreignSession.Id, foreignStudent.Id, otherCourseSession.Id);
    }

    static async Task<JsonElement> Get(HttpClient client, string path)
    {
        var response = await client.GetAsync(path);
        Assert.True(response.IsSuccessStatusCode, $"{path}: {await response.Content.ReadAsStringAsync()}");
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    static async Task<JsonElement> Put(HttpClient client, string path, object body)
    {
        var response = await client.PutAsJsonAsync(path, body);
        Assert.True(response.IsSuccessStatusCode, $"{path}: {await response.Content.ReadAsStringAsync()}");
        return response.StatusCode == HttpStatusCode.NoContent ? default : await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    static int Revision(JsonElement value) => value.GetProperty("revision").GetInt32();
    static JsonElement StudentRow(JsonElement value, Guid studentId) => (value.ValueKind == JsonValueKind.Array ? value : value.GetProperty("students")).EnumerateArray().Single(x => x.GetProperty("studentId").GetGuid() == studentId);

    static async Task<string> OriginalBusinessSnapshot(BetaFactory f)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        return Json.Write(new
        {
            documents = await db.Documents.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            printJobs = await db.PrintJobs.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            work = await db.Work.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            audits = await db.Audits.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            marks = await db.ClassSessionMarks.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            learningEvents = await db.LearningEvents.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            wrongQuestions = await db.WrongQuestions.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            attendance = await db.Attendances.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            lessonPlans = await db.LessonPlans.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync()
        });
    }

    static async Task<Guid> Message(HttpClient client, Fixture x, string kind = "class_notice", Guid? studentId = null, string? key = null)
        => (await Factory.Post(client, Api + "/messages", new { classSessionId = x.SessionId, studentId, kind, requestKey = key ?? Guid.NewGuid().ToString("N") })).GetProperty("id").GetGuid();

    static async Task<JsonElement> EditMessage(HttpClient client, Guid id, string body, string recipient = "已核实的本班家长群", string channel = "wecom_group")
    {
        var detail = await Get(client, $"{Api}/messages/{id}");
        await Put(client, $"{Api}/messages/{id}", new { revision = Revision(detail), body, recipientLabel = recipient, channel });
        return await Get(client, $"{Api}/messages/{id}");
    }

    static async Task<JsonElement> ApproveMessage(HttpClient client, Guid id)
    {
        var detail = await Get(client, $"{Api}/messages/{id}");
        await Factory.Post(client, $"{Api}/messages/{id}/approve", new { revision = Revision(detail), confirmed = true });
        return await Get(client, $"{Api}/messages/{id}");
    }

    static async Task<JsonElement> ConfigureHomework(HttpClient client, Guid sessionId, string title = "分数课后练习", string requirements = "独立完成例1和练1")
    {
        var path = $"{Api}/sessions/{sessionId}/homework";
        var old = await Get(client, path);
        await Put(client, path, new { revision = Revision(old), title, requirements, dueOn = "2026-11-02" });
        return await Get(client, path);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task EveryBetaEndpointIsHiddenWhenDisabledOrForANonPilotAdministrator(bool enabled)
    {
        await using var f = new BetaFactory(enabled);
        var x = await Seed(f);
        using var client = await f.Login(enabled ? "beta-outsider" : "beta-reviewer");
        var id = Guid.NewGuid();
        var session = $"{Api}/sessions/{x.SessionId}";
        var before = await OriginalBusinessSnapshot(f);
        var requests = new (HttpMethod Method, string Path, object? Body)[]
        {
            (HttpMethod.Get, session + "/preparation", null),
            (HttpMethod.Put, session + "/preparation", new { revision = 0, materialsReady = true, rosterChecked = true, homeworkPlanned = true, note = "已检查" }),
            (HttpMethod.Get, session + "/attendance", null),
            (HttpMethod.Put, session + $"/attendance/{x.StudentId}", new { revision = 0, status = "present", note = "", makeupStatus = "none" }),
            (HttpMethod.Get, session + "/homework", null),
            (HttpMethod.Put, session + "/homework", new { revision = 0, title = "练习", dueOn = "2026-11-02", requirements = "独立完成" }),
            (HttpMethod.Put, session + $"/homework/{x.StudentId}", new { revision = 0, homeworkRevision = 1, status = "submitted", note = "" }),
            (HttpMethod.Get, session + "/wrong-records", null),
            (HttpMethod.Put, session + $"/practice/{x.StudentId}/{x.QuestionId}", new { revision = 0, corrected = true, retestState = "correct", note = "复测" }),
            (HttpMethod.Get, Api + "/tasks", null),
            (HttpMethod.Post, Api + "/tasks", new { classSessionId = x.SessionId, studentId = x.StudentId, title = "核实", category = "parent", dueOn = "2026-11-02", note = "", requestKey = "hidden-task-key" }),
            (HttpMethod.Put, $"{Api}/tasks/{id}", new { revision = 1, classSessionId = x.SessionId, studentId = x.StudentId, title = "核实", category = "parent", dueOn = "2026-11-02", note = "", status = "done" }),
            (HttpMethod.Get, Api + "/todos", null),
            (HttpMethod.Get, Api + "/statistics?from=2026-10-26&to=2026-11-01", null),
            (HttpMethod.Get, Api + "/reviews", null),
            (HttpMethod.Post, $"{Api}/drafts/{id}/return", new { revision = 1, reason = "需要核实" }),
            (HttpMethod.Get, Api + "/messages", null),
            (HttpMethod.Post, Api + "/messages", new { classSessionId = x.SessionId, studentId = (Guid?)null, kind = "class_notice", requestKey = "hidden-message-key" }),
            (HttpMethod.Get, $"{Api}/messages/{id}", null),
            (HttpMethod.Put, $"{Api}/messages/{id}", new { revision = 1, body = "请携带讲义", recipientLabel = "家长群", channel = "wecom_group" }),
            (HttpMethod.Post, $"{Api}/messages/{id}/refresh", new { revision = 1 }),
            (HttpMethod.Post, $"{Api}/messages/{id}/approve", new { revision = 1, confirmed = true }),
            (HttpMethod.Post, $"{Api}/messages/{id}/return", new { revision = 1, reason = "需要核实" }),
            (HttpMethod.Get, $"{Api}/messages/{id}/handoff", null),
            (HttpMethod.Post, $"{Api}/messages/{id}/communications", new { revision = 1, requestKey = "hidden-confirmation-key", confirmed = true }),
            (HttpMethod.Put, $"{Api}/message-communications/{id}", new { revision = 1, responseNote = "已回访", followUpOn = (string?)null, status = "resolved" })
        };
        foreach (var spec in requests)
        {
            using var request = new HttpRequestMessage(spec.Method, spec.Path);
            if (spec.Body != null) request.Content = JsonContent.Create(spec.Body);
            using var response = await client.SendAsync(request);
            Assert.True(response.StatusCode == HttpStatusCode.NotFound, $"{spec.Method} {spec.Path}: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
        }
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/me")).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task PreparationDoesNotInferChecksAndRejectsAnotherEditorsStaleRevision()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        var path = $"{Api}/sessions/{x.SessionId}/preparation";
        var initial = await Get(author, path);
        Assert.Equal(0, Revision(initial));
        Assert.Equal(4, initial.GetProperty("studentCount").GetInt32());
        Assert.False(initial.GetProperty("materialsReady").GetBoolean());
        Assert.False(initial.GetProperty("rosterChecked").GetBoolean());
        Assert.Contains(initial.GetProperty("missing").EnumerateArray(), row => row.GetString() == "未登记教案");
        await Put(author, path, new { revision = 0, materialsReady = true, rosterChecked = true, homeworkPlanned = false, note = "讲义已备齐，作业要求待确认" });
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(path, new { revision = 0, materialsReady = false, rosterChecked = false, homeworkPlanned = true, note = "旧页面" })).StatusCode);
        var saved = await Get(author, path);
        Assert.Equal(1, Revision(saved));
        Assert.True(saved.GetProperty("materialsReady").GetBoolean());
        Assert.False(saved.GetProperty("homeworkPlanned").GetBoolean());
        Assert.Equal("讲义已备齐，作业要求待确认", saved.GetProperty("note").GetString());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task PreparationDoesNotExposeAnotherTeachersPrivateLessonPlan()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.LessonPlans.AddRange(
                new LessonPlan { ClassId = x.ClassId, CourseId = x.CourseId, StaffId = f.AuthorId, Title = "整理老师自己的教案", Content = "自己的备课内容" },
                new LessonPlan { ClassId = x.ClassId, CourseId = x.CourseId, StaffId = f.ReviewerId, Title = "另一个老师私有教案", Content = "不可跨教师泄露" });
            await db.SaveChangesAsync();
        }
        var before = await OriginalBusinessSnapshot(f);
        var ownPlans = (await Get(author, $"{Api}/sessions/{x.SessionId}/preparation")).GetProperty("lessonPlans");
        Assert.Equal("整理老师自己的教案", Assert.Single(ownPlans.EnumerateArray()).GetProperty("title").GetString());
        var reviewerPlans = (await Get(reviewer, $"{Api}/sessions/{x.SessionId}/preparation")).GetProperty("lessonPlans");
        Assert.Equal("另一个老师私有教案", Assert.Single(reviewerPlans.EnumerateArray()).GetProperty("title").GetString());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task AttendanceDistinguishesUnrecordedLateLeaveAndAbsenceWithValidatedMakeupDates()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        var path = $"{Api}/sessions/{x.SessionId}/attendance";
        var initial = await Get(author, path);
        Assert.Equal(4, initial.GetArrayLength());
        Assert.All(initial.EnumerateArray(), row => { Assert.Equal("unrecorded", row.GetProperty("status").GetString()); Assert.Equal(0, Revision(row)); });
        using (var scope = f.Services.CreateScope()) Assert.Equal(0, await scope.ServiceProvider.GetRequiredService<Data>().AcademicAttendances.CountAsync());
        await Put(author, path + $"/{x.StudentId}", new { revision = 0, status = "late", note = "迟到十分钟", makeupStatus = "none" });
        await Put(author, path + $"/{x.SecondStudentId}", new { revision = 0, status = "leave", note = "家长请假", makeupStatus = "pending" });
        await Put(author, path + $"/{x.ThirdStudentId}", new { revision = 0, status = "absent", note = "联系家长核实", makeupStatus = "scheduled", makeupOn = "2026-11-02" });
        var rows = await Get(author, path);
        Assert.Equal("late", StudentRow(rows, x.StudentId).GetProperty("status").GetString());
        Assert.Equal("leave", StudentRow(rows, x.SecondStudentId).GetProperty("status").GetString());
        Assert.Equal("absent", StudentRow(rows, x.ThirdStudentId).GetProperty("status").GetString());
        Assert.Equal("unrecorded", StudentRow(rows, x.UnreviewedStudentId).GetProperty("status").GetString());
        var invalid = new object[]
        {
            new { revision = 0, status = "absent", note = "", makeupStatus = "none", makeupOn = (string?)null },
            new { revision = 0, status = "leave", note = "", makeupStatus = "scheduled", makeupOn = (string?)null },
            new { revision = 0, status = "absent", note = "", makeupStatus = "scheduled", makeupOn = "2026-02-30" },
            new { revision = 0, status = "absent", note = "", makeupStatus = "done", makeupOn = "2026-11-02" },
            new { revision = 0, status = "present", note = "", makeupStatus = "scheduled", makeupOn = "2026-11-02" },
            new { revision = 0, status = "leave", note = "", makeupStatus = "pending", makeupOn = "2026-11-02" }
        };
        foreach (var input in invalid) Assert.Equal(HttpStatusCode.BadRequest, (await author.PutAsJsonAsync(path + $"/{x.UnreviewedStudentId}", input)).StatusCode);
        await Put(author, path + $"/{x.ThirdStudentId}", new { revision = 1, status = "absent", note = "补课已完成并复核例1", makeupStatus = "done", makeupOn = "2026-11-02" });
        var madeUp = StudentRow(await Get(author, path), x.ThirdStudentId);
        Assert.Equal("absent", madeUp.GetProperty("status").GetString());
        Assert.Equal("done", madeUp.GetProperty("makeupStatus").GetString());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task HomeworkTracksFourDistinctStatesAndNewAssignmentInvalidatesOldSubmissionStates()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        var path = $"{Api}/sessions/{x.SessionId}/homework";
        var initial = await Get(author, path);
        Assert.Equal(0, Revision(initial));
        Assert.All(initial.GetProperty("students").EnumerateArray(), row => Assert.Equal("unrecorded", row.GetProperty("status").GetString()));
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(path + $"/{x.StudentId}", new { revision = 0, homeworkRevision = 0, status = "missing", note = "" })).StatusCode);
        var assignment = await ConfigureHomework(author, x.SessionId);
        var homeworkRevision = Revision(assignment);
        await Put(author, path + $"/{x.StudentId}", new { revision = 0, homeworkRevision, status = "missing", note = "家长已核实未交" });
        await Put(author, path + $"/{x.SecondStudentId}", new { revision = 0, homeworkRevision, status = "submitted", note = "已收到，老师待批" });
        await Put(author, path + $"/{x.ThirdStudentId}", new { revision = 0, homeworkRevision, status = "reviewed", note = "已完成批改" });
        var states = await Get(author, path);
        Assert.Equal("missing", StudentRow(states, x.StudentId).GetProperty("status").GetString());
        Assert.Equal("submitted", StudentRow(states, x.SecondStudentId).GetProperty("status").GetString());
        Assert.Equal("reviewed", StudentRow(states, x.ThirdStudentId).GetProperty("status").GetString());
        Assert.Equal("unrecorded", StudentRow(states, x.UnreviewedStudentId).GetProperty("status").GetString());
        Assert.Equal(homeworkRevision, Revision(await ConfigureHomework(author, x.SessionId)));
        var revised = await ConfigureHomework(author, x.SessionId, "另一份分数练习", "新增练2，请重新提交");
        Assert.Equal(homeworkRevision + 1, Revision(revised));
        Assert.All(revised.GetProperty("students").EnumerateArray(), row => { Assert.Equal("unrecorded", row.GetProperty("status").GetString()); Assert.Equal("", row.GetProperty("note").GetString()); });
        Assert.Equal(1, Revision(StudentRow(revised, x.ThirdStudentId)));
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(path + $"/{x.ThirdStudentId}", new { revision = 1, homeworkRevision, status = "reviewed", note = "旧作业页面" })).StatusCode);
        await Put(author, path + $"/{x.ThirdStudentId}", new { revision = 1, homeworkRevision = Revision(revised), status = "submitted", note = "新作业已收到" });
        Assert.Equal("submitted", StudentRow(await Get(author, path), x.ThirdStudentId).GetProperty("status").GetString());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task EveryOperationRetainsClassCourseAndStudentScope()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var foreign = await f.Login("beta-foreign");
        foreach (var suffix in new[] { "preparation", "attendance", "homework", "wrong-records" })
        {
            Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync($"{Api}/sessions/{x.SessionId}/{suffix}")).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden, (await author.GetAsync($"{Api}/sessions/{x.OtherCourseSessionId}/{suffix}")).StatusCode);
        }
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PutAsJsonAsync($"{Api}/sessions/{x.SessionId}/attendance/{x.ForeignStudentId}", new { revision = 0, status = "present", note = "", makeupStatus = "none" })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PutAsJsonAsync($"{Api}/sessions/{x.SessionId}/homework/{x.ForeignStudentId}", new { revision = 0, homeworkRevision = 1, status = "submitted", note = "" })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PostAsJsonAsync(Api + "/tasks", new { classSessionId = x.SessionId, studentId = x.ForeignStudentId, title = "跨学生写入", category = "other", dueOn = (string?)null, note = "", requestKey = "cross-student-task" })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PostAsJsonAsync(Api + "/messages", new { classSessionId = x.SessionId, studentId = x.ForeignStudentId, kind = "class_notice", requestKey = "cross-student-message" })).StatusCode);
        foreach (var path in new[] { "/tasks", "/todos", "/messages", "/statistics?from=2026-10-26&to=2026-11-01" })
        {
            var separator = path.Contains('?') ? "&" : "?";
            Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync(Api + path + separator + "classSessionId=" + x.SessionId)).StatusCode);
        }
        Assert.Equal(HttpStatusCode.Forbidden, (await author.GetAsync(Api + "/reviews")).StatusCode);
    }

    [Fact]
    public async Task ManualTasksValidateDatesAreIdempotentAndCompletionRemovesOnlyTheirOwnTodo()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var foreign = await f.Login("beta-foreign");
        var before = await OriginalBusinessSnapshot(f);
        var input = new { classSessionId = x.SessionId, studentId = x.StudentId, title = "核实复测安排", category = "retest", dueOn = "2026-11-02", note = "下次课前联系", requestKey = "manual-task-create-once" };
        var created = await Factory.Post(author, Api + "/tasks", input);
        var id = created.GetProperty("id").GetGuid();
        Assert.Equal(id, (await Factory.Post(author, Api + "/tasks", input)).GetProperty("id").GetGuid());
        Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync(Api + "/tasks", new { input.classSessionId, input.studentId, title = "不同内容", input.category, input.dueOn, input.note, input.requestKey })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await author.PostAsJsonAsync(Api + "/tasks", new { input.classSessionId, input.studentId, input.title, input.category, dueOn = "2026-02-30", input.note, requestKey = "invalid-task-date" })).StatusCode);
        var list = await Get(author, Api + "/tasks");
        var row = Assert.Single(list.EnumerateArray());
        Assert.Equal("open", row.GetProperty("status").GetString());
        Assert.Equal("2026-11-02", row.GetProperty("dueOn").GetString());
        var todos = await Get(author, Api + "/todos");
        Assert.Contains(todos.EnumerateArray(), todo => todo.GetProperty("key").GetString() == "task:" + id);
        Assert.Contains(todos.EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "practice");
        Assert.DoesNotContain((await Get(foreign, Api + "/tasks")).EnumerateArray(), task => task.GetProperty("id").GetGuid() == id);
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.PutAsJsonAsync($"{Api}/tasks/{id}", new { revision = Revision(row), input.classSessionId, input.studentId, input.title, input.category, input.dueOn, input.note, status = "done" })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync($"{Api}/tasks/{id}", new { revision = Revision(row), input.classSessionId, studentId = x.SecondStudentId, input.title, input.category, input.dueOn, input.note, status = "done" })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await author.PutAsJsonAsync($"{Api}/tasks/{id}", new { revision = Revision(row), input.classSessionId, input.studentId, input.title, input.category, input.dueOn, input.note, status = "sent" })).StatusCode);
        await Put(author, $"{Api}/tasks/{id}", new { revision = Revision(row), input.classSessionId, input.studentId, input.title, input.category, input.dueOn, note = "已约定复测时间", status = "done" });
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync($"{Api}/tasks/{id}", new { revision = Revision(row), input.classSessionId, input.studentId, input.title, input.category, input.dueOn, input.note, status = "open" })).StatusCode);
        todos = await Get(author, Api + "/todos");
        Assert.DoesNotContain(todos.EnumerateArray(), todo => todo.GetProperty("key").GetString() == "task:" + id);
        Assert.Contains(todos.EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "practice");
        Assert.Equal("done", Assert.Single((await Get(author, Api + "/tasks")).EnumerateArray()).GetProperty("status").GetString());
        var stats = await Get(author, Api + "/statistics?from=2026-10-26&to=2026-11-01");
        Assert.Equal(1, stats.GetProperty("tasks").GetProperty("done").GetInt32());
        Assert.Equal(0, stats.GetProperty("tasks").GetProperty("open").GetInt32());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task StatisticsAndTodosRespectScopeAndCountUnknownSeparatelyFromConfirmedMissing()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var foreign = await f.Login("beta-foreign");
        var before = await OriginalBusinessSnapshot(f);
        var statsPath = Api + "/statistics?from=2026-10-26&to=2026-11-01";
        var stats = await Get(author, statsPath);
        Assert.Equal(1, stats.GetProperty("sessionCount").GetInt32());
        Assert.Equal(4, stats.GetProperty("studentSessions").GetInt32());
        Assert.Equal(4, stats.GetProperty("attendance").GetProperty("unrecorded").GetInt32());
        Assert.Equal(0, stats.GetProperty("attendance").GetProperty("present").GetInt32());
        Assert.Equal(0, stats.GetProperty("homework").GetProperty("missing").GetInt32());
        Assert.Equal(1, stats.GetProperty("homework").GetProperty("unconfiguredSessions").GetInt32());
        var homework = await ConfigureHomework(author, x.SessionId);
        await Put(author, $"{Api}/sessions/{x.SessionId}/attendance/{x.StudentId}", new { revision = 0, status = "present", note = "", makeupStatus = "none" });
        await Put(author, $"{Api}/sessions/{x.SessionId}/attendance/{x.SecondStudentId}", new { revision = 0, status = "late", note = "", makeupStatus = "none" });
        await Put(author, $"{Api}/sessions/{x.SessionId}/attendance/{x.ThirdStudentId}", new { revision = 0, status = "leave", note = "家长请假", makeupStatus = "pending" });
        await Put(author, $"{Api}/sessions/{x.SessionId}/homework/{x.StudentId}", new { revision = 0, homeworkRevision = Revision(homework), status = "missing", note = "已核实" });
        await Put(author, $"{Api}/sessions/{x.SessionId}/homework/{x.SecondStudentId}", new { revision = 0, homeworkRevision = Revision(homework), status = "submitted", note = "老师待批" });
        await Put(author, $"{Api}/sessions/{x.SessionId}/homework/{x.ThirdStudentId}", new { revision = 0, homeworkRevision = Revision(homework), status = "reviewed", note = "已批" });
        await Factory.Post(foreign, Api + "/tasks", new { classSessionId = x.ForeignSessionId, studentId = x.ForeignStudentId, title = "不可外泄的他班任务", category = "parent", dueOn = "2026-11-02", note = "不可外泄的内容", requestKey = "foreign-task-only" });
        stats = await Get(author, statsPath);
        foreach (var field in new[] { "present", "late", "leave", "unrecorded" }) Assert.Equal(1, stats.GetProperty("attendance").GetProperty(field).GetInt32());
        Assert.Equal(0, stats.GetProperty("attendance").GetProperty("absent").GetInt32());
        foreach (var field in new[] { "missing", "submitted", "reviewed", "unrecorded" }) Assert.Equal(1, stats.GetProperty("homework").GetProperty(field).GetInt32());
        Assert.Equal(3, stats.GetProperty("marking").GetProperty("reviewed").GetInt32());
        Assert.Equal(1, stats.GetProperty("marking").GetProperty("unreviewed").GetInt32());
        Assert.Equal(1, stats.GetProperty("marking").GetProperty("wrongQuestionCount").GetInt32());
        Assert.Equal(0, stats.GetProperty("tasks").GetProperty("open").GetInt32());
        var daily = Assert.Single(stats.GetProperty("daily").EnumerateArray());
        Assert.Equal("2026-10-31", daily.GetProperty("date").GetString());
        Assert.Equal(1, daily.GetProperty("attendanceUnrecorded").GetInt32());
        var report = stats.GetProperty("weeklyReport").GetString()!;
        Assert.Contains("未登记", report);
        Assert.Contains("已交待批", report);
        Assert.Contains("当前登记状态", report);
        Assert.DoesNotContain("不可外泄", report);
        var todos = await Get(author, Api + "/todos");
        Assert.All(todos.EnumerateArray(), todo => Assert.Equal(x.SessionId, todo.GetProperty("classSessionId").GetGuid()));
        Assert.Single(todos.EnumerateArray(), t => t.GetProperty("kind").GetString() == "attendance_unrecorded");
        Assert.Contains(todos.EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "homework_missing" && todo.GetProperty("studentId").GetGuid() == x.StudentId);
        Assert.Contains(todos.EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "homework_submitted" && todo.GetProperty("studentId").GetGuid() == x.SecondStudentId);
        Assert.Contains(todos.EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "homework_unrecorded" && todo.GetProperty("studentId").GetGuid() == x.UnreviewedStudentId);
        Assert.DoesNotContain("不可外泄", todos.GetRawText());
        await ConfigureHomework(author, x.SessionId, "更新后的作业", "重新核实提交记录");
        stats = await Get(author, statsPath);
        Assert.Equal(4, stats.GetProperty("homework").GetProperty("unrecorded").GetInt32());
        Assert.Equal(0, stats.GetProperty("homework").GetProperty("reviewed").GetInt32());
        todos = await Get(author, Api + "/todos");
        Assert.Equal(4, todos.EnumerateArray().Count(t => t.GetProperty("kind").GetString() == "homework_unrecorded"));
        Assert.DoesNotContain(todos.EnumerateArray(), todo => todo.GetProperty("kind").GetString() is "homework_missing" or "homework_submitted");
        Assert.Equal(0, (await Get(author, Api + "/statistics?from=2026-11-02&to=2026-11-08")).GetProperty("sessionCount").GetInt32());
        foreach (var range in new[] { "from=2026-02-30&to=2026-03-01", "from=2026-11-02&to=2026-10-31", "from=2026-01-01&to=2026-12-31" })
            Assert.Equal(HttpStatusCode.BadRequest, (await author.GetAsync(Api + "/statistics?" + range)).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Theory]
    [InlineData("attendance")]
    [InlineData("homework")]
    [InlineData("homeworkAssignment")]
    [InlineData("practice")]
    public async Task NewOperationalEvidenceInvalidatesTheSharedFeedbackThroughBothEntrypoints(string change)
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var before = await OriginalBusinessSnapshot(f);
        var homework = await ConfigureHomework(author, x.SessionId);
        var feedbackId = (await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).GetProperty("id").GetGuid();
        var feedbackPath = $"{Api}/drafts/{feedbackId}";
        var draft = await Get(author, feedbackPath);
        await Put(author, feedbackPath, new { revision = Revision(draft), body = draft.GetProperty("body").GetString(), recipientLabel = "本班学生甲的监护人", channel = "wecom_private" });
        draft = await Get(author, feedbackPath);
        await Factory.Post(reviewer, feedbackPath + "/approve", new { revision = Revision(draft), confirmed = true });
        var messageId = await Message(author, x, "learning_feedback", x.StudentId);
        Assert.Equal(feedbackId, messageId);
        var messagePath = $"{Api}/messages/{messageId}";
        var approved = await Get(reviewer, messagePath);
        Assert.Equal("approved", approved.GetProperty("state").GetString());
        switch (change)
        {
            case "attendance":
                await Put(author, $"{Api}/sessions/{x.SessionId}/attendance/{x.StudentId}", new { revision = 0, status = "late", note = "教务核实迟到", makeupStatus = "none" });
                break;
            case "homework":
                await Put(author, $"{Api}/sessions/{x.SessionId}/homework/{x.StudentId}", new { revision = 0, homeworkRevision = Revision(homework), status = "submitted", note = "已交待批" });
                break;
            case "homeworkAssignment":
                await ConfigureHomework(author, x.SessionId, "更正后的作业", "变更了练习范围");
                break;
            case "practice":
                await Put(author, $"{Api}/sessions/{x.SessionId}/practice/{x.StudentId}/{x.QuestionId}", new { revision = 0, corrected = true, retestState = "correct", note = "独立复测做对" });
                break;
        }
        foreach (var path in new[] { feedbackPath, messagePath })
        {
            Assert.True((await Get(reviewer, path)).GetProperty("sourceStale").GetBoolean());
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync(path + "/handoff")).StatusCode);
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(path + "/communications", new { revision = Revision(approved), requestKey = Guid.NewGuid().ToString("N"), confirmed = true })).StatusCode);
        }
        await Factory.Post(author, messagePath + "/refresh", new { revision = Revision(approved) });
        foreach (var path in new[] { feedbackPath, messagePath })
        {
            var refreshed = await Get(author, path);
            Assert.False(refreshed.GetProperty("sourceStale").GetBoolean());
            Assert.Equal("draft", refreshed.GetProperty("state").GetString());
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync(path + "/handoff")).StatusCode);
        }
        var current = await Get(reviewer, feedbackPath);
        await Factory.Post(reviewer, feedbackPath + "/approve", new { revision = Revision(current), confirmed = true });
        foreach (var path in new[] { feedbackPath, messagePath })
            Assert.Equal(HttpStatusCode.OK, (await reviewer.GetAsync(path + "/handoff")).StatusCode);
        var source = (await Get(author, feedbackPath)).GetProperty("source");
        if (change == "attendance") Assert.Equal("late", source.GetProperty("operations").GetProperty("attendanceStatus").GetString());
        if (change == "homework") Assert.Equal("submitted", source.GetProperty("operations").GetProperty("homeworkStatus").GetString());
        if (change == "homeworkAssignment") Assert.Equal("更正后的作业", source.GetProperty("operations").GetProperty("homeworkTitle").GetString());
        if (change == "practice")
        {
            var practice = Assert.Single(source.GetProperty("practice").EnumerateArray());
            Assert.True(practice.GetProperty("corrected").GetBoolean());
            Assert.Equal("correct", practice.GetProperty("retestState").GetString());
            Assert.Equal("correct", Assert.Single(source.GetProperty("questions").EnumerateArray()).GetProperty("retestState").GetString());
        }
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task BetaPracticeReadsReviewedWrongQuestionsWithoutChangingOriginalLearningOrPrinting()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        var path = $"{Api}/sessions/{x.SessionId}";
        var roster = await Get(author, path + "/wrong-records");
        var student = StudentRow(roster, x.StudentId);
        Assert.True(student.GetProperty("reviewed").GetBoolean());
        var question = Assert.Single(student.GetProperty("questions").EnumerateArray());
        Assert.Equal(x.QuestionId, question.GetProperty("questionId").GetGuid());
        Assert.False(question.GetProperty("corrected").GetBoolean());
        Assert.Equal("unrecorded", question.GetProperty("retestState").GetString());
        Assert.False(StudentRow(roster, x.UnreviewedStudentId).GetProperty("reviewed").GetBoolean());
        await Put(author, $"{path}/practice/{x.StudentId}/{x.QuestionId}", new { revision = 0, corrected = true, retestState = "wrong", note = "已订正但独立复测仍错" });
        question = Assert.Single(StudentRow(await Get(author, path + "/wrong-records"), x.StudentId).GetProperty("questions").EnumerateArray());
        Assert.True(question.GetProperty("corrected").GetBoolean());
        Assert.Equal("wrong", question.GetProperty("retestState").GetString());
        Assert.Equal("已订正但独立复测仍错", question.GetProperty("practiceNote").GetString());
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync($"{path}/practice/{x.StudentId}/{x.QuestionId}", new { revision = 0, corrected = false, retestState = "correct", note = "旧窗口" })).StatusCode);
        Assert.False((await author.PutAsJsonAsync($"{path}/practice/{x.StudentId}/{x.OtherQuestionId}", new { revision = 0, corrected = true, retestState = "correct", note = "未登记为错题" })).IsSuccessStatusCode);
        Assert.False((await author.PutAsJsonAsync($"{path}/practice/{x.UnreviewedStudentId}/{x.QuestionId}", new { revision = 0, corrected = true, retestState = "correct", note = "未批改" })).IsSuccessStatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PutAsJsonAsync($"{path}/practice/{x.ForeignStudentId}/{x.QuestionId}", new { revision = 0, corrected = true, retestState = "correct", note = "他班学生" })).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task GroupDraftsContainNoStudentRosterAndPersonalFeedbackRequiresAReviewedStudent()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        await ConfigureHomework(author, x.SessionId);
        foreach (var kind in new[] { "class_notice", "homework_reminder" })
        {
            var id = await Message(author, x, kind);
            var draft = await Get(author, $"{Api}/messages/{id}");
            Assert.Equal("wecom_group", draft.GetProperty("channel").GetString());
            Assert.Equal("draft", draft.GetProperty("state").GetString());
            var text = draft.GetProperty("body").GetString()!;
            foreach (var name in new[] { "本班学生甲", "本班学生乙", "本班学生丙", "本班学生丁", "不可外泄的其他班学生" }) Assert.DoesNotContain(name, text);
        }
        Assert.Equal(HttpStatusCode.BadRequest, (await author.PostAsJsonAsync(Api + "/messages", new { classSessionId = x.SessionId, studentId = (Guid?)null, kind = "learning_feedback", requestKey = "feedback-without-student" })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync(Api + "/messages", new { classSessionId = x.SessionId, studentId = x.UnreviewedStudentId, kind = "learning_feedback", requestKey = "feedback-not-reviewed" })).StatusCode);
        var personal = await Message(author, x, "learning_feedback", x.StudentId);
        var detail = await Get(author, $"{Api}/messages/{personal}");
        Assert.Equal("wecom_private", detail.GetProperty("channel").GetString());
        Assert.Equal(HttpStatusCode.BadRequest, (await author.PutAsJsonAsync($"{Api}/messages/{personal}", new { revision = Revision(detail), body = "学情反馈", recipientLabel = "家长群", channel = "wecom_group" })).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task MessagesRequireSeparateReviewerConfirmationAndKeepImmutableManualCommunicationHistory()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var before = await OriginalBusinessSnapshot(f);
        var id = await Message(author, x, key: "class-notice-once");
        Assert.Equal(id, await Message(author, x, key: "class-notice-once"));
        Assert.Equal(HttpStatusCode.Conflict, (await author.PostAsJsonAsync(Api + "/messages", new { classSessionId = x.SessionId, studentId = x.StudentId, kind = "class_notice", requestKey = "class-notice-once" })).StatusCode);
        var draft = await EditMessage(author, id, "本周课程请携带讲义。", "本班家长群A");
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/messages/{id}/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/messages/{id}/communications", new { revision = Revision(draft), requestKey = "not-approved", confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PostAsJsonAsync($"{Api}/messages/{id}/approve", new { revision = Revision(draft), confirmed = true })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await reviewer.PostAsJsonAsync($"{Api}/messages/{id}/approve", new { revision = Revision(draft), confirmed = false })).StatusCode);
        var approved = await ApproveMessage(reviewer, id);
        Assert.Empty(approved.GetProperty("communications").EnumerateArray());
        Assert.Equal("本周课程请携带讲义。", (await Get(reviewer, $"{Api}/messages/{id}/handoff")).GetProperty("body").GetString());
        Assert.Equal(HttpStatusCode.BadRequest, (await reviewer.PostAsJsonAsync($"{Api}/messages/{id}/communications", new { revision = Revision(approved), requestKey = "manual-notice-once", confirmed = false })).StatusCode);
        var sentInput = new { revision = Revision(approved), requestKey = "manual-notice-once", confirmed = true };
        var sent = await Factory.Post(reviewer, $"{Api}/messages/{id}/communications", sentInput);
        Assert.Equal(sent.GetProperty("id").GetGuid(), (await Factory.Post(reviewer, $"{Api}/messages/{id}/communications", sentInput)).GetProperty("id").GetGuid());
        var after = await Get(reviewer, $"{Api}/messages/{id}");
        Assert.Equal("communicated", after.GetProperty("state").GetString());
        var log = Assert.Single(after.GetProperty("communications").EnumerateArray());
        Assert.Equal("awaiting_reply", log.GetProperty("status").GetString());
        Assert.Equal("本周课程请携带讲义。", log.GetProperty("body").GetString());
        Assert.Equal("本班家长群A", log.GetProperty("recipientLabel").GetString());
        await Put(reviewer, $"{Api}/message-communications/{sent.GetProperty("id").GetGuid()}", new { revision = Revision(log), responseNote = "家长已收到，仍需后续核实", followUpOn = "2026-11-02", status = "follow_up" });
        var edited = await EditMessage(author, id, "更新后的下一次通知。", "本班家长群B");
        Assert.Equal("draft", edited.GetProperty("state").GetString());
        log = Assert.Single(edited.GetProperty("communications").EnumerateArray());
        Assert.Equal("本周课程请携带讲义。", log.GetProperty("body").GetString());
        Assert.Equal("本班家长群A", log.GetProperty("recipientLabel").GetString());
        Assert.Equal("follow_up", log.GetProperty("status").GetString());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/messages/{id}/handoff")).StatusCode);
        approved = await ApproveMessage(reviewer, id);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/messages/{id}/communications", new { revision = Revision(approved), requestKey = "manual-notice-once", confirmed = true })).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task ReviewQueueAndReturnRequireClassScopeAndCannotEraseSentHistory()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        using var foreign = await f.Login("beta-foreign");
        var id = await Message(author, x);
        await EditMessage(author, id, "请核对课次安排。");
        var review = await Get(reviewer, Api + "/reviews");
        Assert.Contains(review.EnumerateArray(), row => row.GetProperty("id").GetGuid() == id && row.GetProperty("kind").GetString() == "message");
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync($"{Api}/messages/{id}")).StatusCode);
        Assert.DoesNotContain((await Get(foreign, Api + "/messages")).EnumerateArray(), row => row.GetProperty("id").GetGuid() == id);
        var approved = await ApproveMessage(reviewer, id);
        Assert.DoesNotContain((await Get(reviewer, Api + "/reviews")).EnumerateArray(), row => row.GetProperty("id").GetGuid() == id);
        Assert.Equal(HttpStatusCode.BadRequest, (await reviewer.PostAsJsonAsync($"{Api}/messages/{id}/return", new { revision = Revision(approved), reason = "" })).StatusCode);
        await Factory.Post(reviewer, $"{Api}/messages/{id}/return", new { revision = Revision(approved), reason = "课程时间需要再确认" });
        var returned = await Get(reviewer, $"{Api}/messages/{id}");
        Assert.Equal("draft", returned.GetProperty("state").GetString());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/messages/{id}/handoff")).StatusCode);
        approved = await ApproveMessage(reviewer, id);
        await Factory.Post(reviewer, $"{Api}/messages/{id}/communications", new { revision = Revision(approved), requestKey = "return-sent-history", confirmed = true });
        var communicated = await Get(reviewer, $"{Api}/messages/{id}");
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync($"{Api}/messages/{id}/return", new { revision = Revision(communicated), reason = "不可撤销已发生的发送" })).StatusCode);
        Assert.Single((await Get(reviewer, $"{Api}/messages/{id}")).GetProperty("communications").EnumerateArray());
    }

    [Fact]
    public async Task HomeworkMessageSourceTracksAssignmentAndOnlyItsOwnStudentsSubmission()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var before = await OriginalBusinessSnapshot(f);
        var homework = await ConfigureHomework(author, x.SessionId);
        var groupId = await Message(author, x, "homework_reminder");
        var personalId = await Message(author, x, "homework_reminder", x.StudentId);
        var group = await Get(author, $"{Api}/messages/{groupId}");
        var personal = await Get(author, $"{Api}/messages/{personalId}");
        Assert.Contains("尚未登记", personal.GetProperty("body").GetString());
        Assert.DoesNotContain("未交", personal.GetProperty("body").GetString());
        await EditMessage(author, groupId, group.GetProperty("body").GetString()!);
        await EditMessage(author, personalId, personal.GetProperty("body").GetString()!, "学生甲家长私聊", "wecom_private");
        await ApproveMessage(reviewer, groupId);
        await ApproveMessage(reviewer, personalId);
        await Put(author, $"{Api}/sessions/{x.SessionId}/homework/{x.StudentId}", new { revision = 0, homeworkRevision = Revision(homework), status = "submitted", note = "已收到" });
        Assert.False((await Get(author, $"{Api}/messages/{groupId}")).GetProperty("sourceStale").GetBoolean());
        Assert.True((await Get(author, $"{Api}/messages/{personalId}")).GetProperty("sourceStale").GetBoolean());
        Assert.Equal(HttpStatusCode.OK, (await reviewer.GetAsync($"{Api}/messages/{groupId}/handoff")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/messages/{personalId}/handoff")).StatusCode);
        await ConfigureHomework(author, x.SessionId, "新的作业安排", "新练习要求");
        Assert.True((await Get(author, $"{Api}/messages/{groupId}")).GetProperty("sourceStale").GetBoolean());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/messages/{groupId}/handoff")).StatusCode);
        personal = await Get(author, $"{Api}/messages/{personalId}");
        await Factory.Post(author, $"{Api}/messages/{personalId}/refresh", new { revision = Revision(personal) });
        personal = await Get(author, $"{Api}/messages/{personalId}");
        Assert.Contains("新的作业安排", personal.GetProperty("body").GetString());
        Assert.Contains("尚未登记", personal.GetProperty("body").GetString());
        Assert.DoesNotContain("已交待批改", personal.GetProperty("body").GetString());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task LatestLegacyRetestAndBetaCorrectionAgreeAcrossFeedbackAndTodos()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var path = $"{Api}/sessions/{x.SessionId}/practice/{x.StudentId}/{x.QuestionId}";
        await Put(author, path, new { revision = 0, corrected = true, retestState = "wrong", note = "较早一次复测仍错" });
        Assert.Contains((await Get(author, Api + "/todos")).EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "practice");
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.LearningEvents.Add(new LearningEvent { StudentId = x.StudentId, ClassId = x.ClassId, CourseId = x.CourseId,
                QuestionId = x.QuestionId, Kind = "retest", State = "correct", RecordedBy = f.AuthorId,
                RecordedAt = Clock.Now.AddMinutes(1), RequestKey = "later-original-retest" });
            await db.SaveChangesAsync();
        }
        var beforeReads = await OriginalBusinessSnapshot(f);
        Assert.DoesNotContain((await Get(author, Api + "/todos")).EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "practice");
        var id = (await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).GetProperty("id").GetGuid();
        var question = Assert.Single((await Get(author, $"{Api}/drafts/{id}")).GetProperty("source").GetProperty("questions").EnumerateArray());
        Assert.Equal("correct", question.GetProperty("retestState").GetString());
        await Put(author, path, new { revision = 1, corrected = true, retestState = "unrecorded", note = "Beta没有新的复测结论" });
        Assert.DoesNotContain((await Get(author, Api + "/todos")).EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "practice");
        Assert.Equal(beforeReads, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task EditingCorrectionNotesCannotReDateAnOldRetestButAnExplicitNewRetestCan()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var path = $"{Api}/sessions/{x.SessionId}/practice/{x.StudentId}/{x.QuestionId}";
        await Put(author, path, new { revision = 0, corrected = true, retestState = "wrong", note = "最初复测做错" });
        var olderRetestAt = Clock.Now.AddMinutes(-3);
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var session = await db.ClassSessions.SingleAsync(s => s.Id == x.SessionId);
            session.CreatedAt = Clock.Now.AddMinutes(-5);
            var practice = await db.AcademicPractices.SingleAsync();
            practice.UpdatedAt = olderRetestAt;
            practice.RetestAt = olderRetestAt;
            db.LearningEvents.Add(new LearningEvent { StudentId = x.StudentId, ClassId = x.ClassId, CourseId = x.CourseId,
                QuestionId = x.QuestionId, Kind = "retest", State = "correct", RecordedBy = f.AuthorId,
                RecordedAt = Clock.Now.AddMinutes(-2), RequestKey = "later-retest-before-note-edit" });
            await db.SaveChangesAsync();
        }
        var before = await OriginalBusinessSnapshot(f);
        var id = (await Factory.Post(author, Api + "/drafts", new { classSessionId = x.SessionId, studentId = x.StudentId })).GetProperty("id").GetGuid();
        var draftPath = $"{Api}/drafts/{id}";
        var draft = await Get(author, draftPath);
        Assert.Equal("correct", Assert.Single(draft.GetProperty("source").GetProperty("questions").EnumerateArray()).GetProperty("retestState").GetString());
        await Put(author, path, new { revision = 1, corrected = true, retestState = "wrong", note = "只补充订正过程，没有进行新的复测" });
        using (var scope = f.Services.CreateScope())
        {
            var practice = await scope.ServiceProvider.GetRequiredService<Data>().AcademicPractices.SingleAsync();
            Assert.Equal(olderRetestAt, practice.RetestAt);
            Assert.True(practice.UpdatedAt > olderRetestAt);
        }
        Assert.True((await Get(author, draftPath)).GetProperty("sourceStale").GetBoolean());
        await Factory.Post(author, draftPath + "/refresh", new { revision = Revision(draft) });
        draft = await Get(author, draftPath);
        Assert.Equal("correct", Assert.Single(draft.GetProperty("source").GetProperty("questions").EnumerateArray()).GetProperty("retestState").GetString());
        Assert.DoesNotContain((await Get(author, Api + "/todos")).EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "practice");

        await Put(author, path, new { revision = 2, corrected = true, retestState = "wrong", note = "刚完成一次新的复测，结果又做错", recordRetest = true });
        using (var scope = f.Services.CreateScope())
            Assert.True((await scope.ServiceProvider.GetRequiredService<Data>().AcademicPractices.SingleAsync()).RetestAt > olderRetestAt);
        Assert.True((await Get(author, draftPath)).GetProperty("sourceStale").GetBoolean());
        await Factory.Post(author, draftPath + "/refresh", new { revision = Revision(draft) });
        draft = await Get(author, draftPath);
        Assert.Equal("wrong", Assert.Single(draft.GetProperty("source").GetProperty("questions").EnumerateArray()).GetProperty("retestState").GetString());
        var todo = Assert.Single((await Get(author, Api + "/todos")).EnumerateArray(), todo => todo.GetProperty("kind").GetString() == "practice");
        Assert.Contains("复测仍错误", todo.GetProperty("title").GetString());
        Assert.Equal(HttpStatusCode.BadRequest, (await author.PutAsJsonAsync(path, new { revision = 3, corrected = true, retestState = "unrecorded", note = "没有实际结果不能确认复测", recordRetest = true })).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task ConcurrentMessageCreationAndManualConfirmationDoNotDuplicateRecords()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var before = await OriginalBusinessSnapshot(f);
        var input = new { classSessionId = x.SessionId, studentId = (Guid?)null, kind = "class_notice", requestKey = "concurrent-message-create" };
        var creates = await Task.WhenAll(Enumerable.Range(0, 6).Select(_ => author.PostAsJsonAsync(Api + "/messages", input)));
        foreach (var response in creates) Assert.True(response.StatusCode is HttpStatusCode.OK or HttpStatusCode.Conflict, await response.Content.ReadAsStringAsync());
        Assert.Contains(creates, response => response.StatusCode == HttpStatusCode.OK);
        var id = Assert.Single((await Get(author, Api + "/messages")).EnumerateArray()).GetProperty("id").GetGuid();
        await EditMessage(author, id, "请带好本周讲义。");
        var approved = await ApproveMessage(reviewer, id);
        var sentInput = new { revision = Revision(approved), requestKey = "concurrent-message-confirm", confirmed = true };
        var sends = await Task.WhenAll(Enumerable.Range(0, 6).Select(_ => reviewer.PostAsJsonAsync($"{Api}/messages/{id}/communications", sentInput)));
        foreach (var response in sends) Assert.True(response.StatusCode is HttpStatusCode.OK or HttpStatusCode.Conflict, await response.Content.ReadAsStringAsync());
        Assert.Contains(sends, response => response.StatusCode == HttpStatusCode.OK);
        var log = Assert.Single((await Get(reviewer, $"{Api}/messages/{id}")).GetProperty("communications").EnumerateArray());
        Assert.Equal(log.GetProperty("id").GetGuid(), (await Factory.Post(reviewer, $"{Api}/messages/{id}/communications", sentInput)).GetProperty("id").GetGuid());
        Assert.Equal("awaiting_reply", log.GetProperty("status").GetString());
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }
}
