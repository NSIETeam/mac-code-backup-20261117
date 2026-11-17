using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class OrganizationTests
{
    [Fact]
    public async Task ExistingPreviewDatabaseUpgradesWithoutLosingLectureOrStudentLinks()
    {
        var root = Path.Combine(Path.GetTempPath(), "siyue-schema-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        await using var db = new Data(new DbContextOptionsBuilder<Data>().UseSqlite("Data Source=" + Path.Combine(root, "preview.db")).Options);
        var oldSchema = db.Database.GenerateCreateScript().Replace("\"ClassId\" TEXT NULL", "\"ClassId\" TEXT NOT NULL");
        await db.Database.ExecuteSqlRawAsync(oldSchema.Replace("{", "{{").Replace("}", "}}"));
        var campus = new Campus { Name = "原校区" }; var course = new Course { Name = "原课程" }; var cl = new SchoolClass { Name = "原班级", CampusId = campus.Id };
        var lecture = new Lecture { ClassId = cl.Id, CourseId = course.Id, Title = "原讲次", Sequence = 1 }; var student = new Student { ClassId = cl.Id, Name = "原学生", Code = "001" }; var h = new Handout { LectureId = lecture.Id, Version = 1 }; var q = new Question { HandoutId = h.Id };
        db.AddRange(campus, course, cl, lecture, student, h, q, new WrongQuestion { StudentId = student.Id, QuestionId = q.Id }); await db.SaveChangesAsync(); db.ChangeTracker.Clear();
        await db.Database.ExecuteSqlRawAsync("DROP TABLE LessonPlans; DROP TABLE Pairings;");
        await db.Database.ExecuteSqlRawAsync("CREATE TABLE LessonPlans (Id TEXT NOT NULL PRIMARY KEY, StaffId TEXT NOT NULL, ClassId TEXT NOT NULL, CourseId TEXT NOT NULL, Title TEXT NOT NULL, Content TEXT NOT NULL, UpdatedAt INTEGER NOT NULL)");
        await DevelopmentSchema.Upgrade(db); await DevelopmentSchema.Upgrade(db); Assert.Empty(await db.LessonPlans.ToListAsync());
        Assert.Equal(cl.Id, (await db.Lectures.FindAsync(lecture.Id))!.ClassId); Assert.Equal(lecture.Id, (await db.Handouts.FindAsync(h.Id))!.LectureId); Assert.Single(await db.WrongQuestions.ToListAsync());
        db.Lectures.Add(new Lecture { ClassId = null, CourseId = course.Id, Title = "共享讲次", Sequence = 2 }); await db.SaveChangesAsync();
        await db.Database.OpenConnectionAsync(); using var command = db.Database.GetDbConnection().CreateCommand(); command.CommandText = "PRAGMA foreign_key_check"; await using var reader = await command.ExecuteReaderAsync(); Assert.False(await reader.ReadAsync());
    }

    [Fact]
    public async Task CourseHandoutsAreSharedButRostersAndWrongQuestionsStayInTheirClass()
    {
        await using var f = new Factory(); using var admin = await f.Login("org-admin", true);
        var campus = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "共享校区" });
        var course = await Factory.Post(admin, "/api/admin/catalog/courses", new { name = "统一数学课程" }); var courseId = course.GetProperty("id").GetGuid();
        // Shared lectures can be prepared before any classes exist.
        var lecture = await Factory.Post(admin, "/api/lectures", new { classId = (Guid?)null, courseId, title = "共享第一讲", sequence = 1 }); var lectureId = lecture.GetProperty("id").GetGuid();
        var courseDetail = await admin.GetFromJsonAsync<JsonElement>($"/api/courses/{courseId}/lectures"); Assert.Single(courseDetail.GetProperty("lectures").EnumerateArray());
        var classes = new List<Guid>(); var students = new List<Guid>(); var teachers = new List<HttpClient>();
        for (var i = 0; i < 2; i++) {
            var cl = await Factory.Post(admin, "/api/admin/catalog/classes", new { name = "班级" + i, campusId = campus.GetProperty("id").GetGuid() }); var cid = cl.GetProperty("id").GetGuid(); classes.Add(cid);
            students.Add((await Factory.Post(admin, "/api/admin/catalog/students", new { name = "学生" + i, code = "001", classId = cid })).GetProperty("id").GetGuid());
            var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "教师" + i }); var sid = staff.GetProperty("id").GetGuid();
            await Factory.Post(admin, "/api/admin/catalog/assignments", new { staffId = sid, classId = cid, courseId });
            var invite = await Factory.Post(admin, $"/api/admin/staff/{sid}/invite", new {}); var teacher = await f.Login("shared-teacher" + i); teachers.Add(teacher);
            await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        }
        Guid hid, qid; using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); var h = new Handout { LectureId = lectureId, Version = 1, State = "published", FileName = "共享讲义.docx" }; var q = new Question { HandoutId = h.Id, Label = "例1", Confirmed = true };
            db.Handouts.Add(h); db.Questions.Add(q); await db.SaveChangesAsync(); hid = h.Id; qid = q.Id;
        }
        for (var i = 0; i < 2; i++) {
            var detail = await teachers[i].GetFromJsonAsync<JsonElement>($"/api/classes/{classes[i]}?courseId={courseId}"); Assert.Equal(lectureId, detail.GetProperty("lectures")[0].GetProperty("id").GetGuid()); Assert.Single(detail.GetProperty("students").EnumerateArray());
            var h = await teachers[i].GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}?classId={classes[i]}"); Assert.Equal(students[i], h.GetProperty("students")[0].GetProperty("id").GetGuid());
        }
        Assert.Equal(HttpStatusCode.Forbidden, (await teachers[0].GetAsync($"/api/handouts/{hid}?classId={classes[1]}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teachers[0].GetAsync($"/api/handouts/{hid}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teachers[0].PutAsJsonAsync("/api/wrong-questions", new { handoutId = hid, classId = classes[0], studentIds = new[] { students[1] }, questionIds = new[] { qid } })).StatusCode);
        Assert.True((await teachers[0].PutAsJsonAsync("/api/wrong-questions", new { handoutId = hid, classId = classes[0], studentIds = new[] { students[0] }, questionIds = new[] { qid } })).IsSuccessStatusCode);
        var otherDetail = await teachers[1].GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}?classId={classes[1]}"); Assert.Empty(otherDetail.GetProperty("wrong").EnumerateArray());
        var batch = await Factory.Post(teachers[0], "/api/exports", new { classId = classes[0], courseId, studentIds = new[] { students[0] }, lectureIds = new[] { lectureId } }); Assert.Single(batch.GetProperty("documents").EnumerateArray());
        // Existing class-only lectures remain private to their original class.
        var privateLecture = await Factory.Post(admin, "/api/lectures", new { classId = classes[0], courseId, title = "本班补充", sequence = 2 });
        var otherClass = await teachers[1].GetFromJsonAsync<JsonElement>($"/api/classes/{classes[1]}?courseId={courseId}"); Assert.Single(otherClass.GetProperty("lectures").EnumerateArray());
        await Factory.Post(admin, $"/api/lectures/{privateLecture.GetProperty("id").GetGuid()}/share", new {});
        var sharedClass = await teachers[1].GetFromJsonAsync<JsonElement>($"/api/classes/{classes[1]}?courseId={courseId}"); Assert.Equal(2, sharedClass.GetProperty("lectures").GetArrayLength());
        foreach (var teacher in teachers) teacher.Dispose();
    }

    [Fact]
    public async Task LessonPlansBelongToAssignedTeachersAndCannotBeEditedByOtherTeachers()
    {
        await using var f = new Factory(); using var admin = await f.Login("plan-admin", true);
        var campus = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "教案校区" });
        var cl = await Factory.Post(admin, "/api/admin/catalog/classes", new { name = "教案班", campusId = campus.GetProperty("id").GetGuid() }); var cid = cl.GetProperty("id").GetGuid();
        var course = await Factory.Post(admin, "/api/admin/catalog/courses", new { name = "数学" }); var courseId = course.GetProperty("id").GetGuid();
        var staffIds = new List<Guid>(); var teachers = new List<HttpClient>();
        for (var i = 0; i < 2; i++) {
            var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "备课教师" + i }); var sid = staff.GetProperty("id").GetGuid(); staffIds.Add(sid);
            await Factory.Post(admin, "/api/admin/catalog/assignments", new { staffId = sid, classId = cid, courseId });
            var invite = await Factory.Post(admin, $"/api/admin/staff/{sid}/invite", new {}); var teacher = await f.Login("plan-teacher" + i); teachers.Add(teacher); await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        }
        var plan = await Factory.Post(teachers[0], $"/api/classes/{cid}/plans", new { courseId, title = "方程教学", content = "目标：理解等式\n课堂：小组练习", staffId = staffIds[1] }); Assert.Equal(staffIds[0], plan.GetProperty("staffId").GetGuid());
        var pid = plan.GetProperty("id").GetGuid();
        Assert.Empty((await teachers[1].GetFromJsonAsync<JsonElement>($"/api/classes/{cid}/plans?courseId={courseId}")).EnumerateArray());
        Assert.Equal(HttpStatusCode.Forbidden, (await teachers[1].PostAsJsonAsync($"/api/classes/{cid}/plans", new { id = pid, courseId, title = "覆盖他人", content = "不可覆盖" })).StatusCode);
        await Factory.Post(admin, $"/api/classes/{cid}/plans", new { id = pid, courseId, staffId = staffIds[0], title = "管理员修订", content = "修订内容" });
        var plans = await teachers[0].GetFromJsonAsync<JsonElement>($"/api/classes/{cid}/plans?courseId={courseId}"); Assert.Equal("管理员修订", plans[0].GetProperty("title").GetString());
        var wordPath = Path.Combine(f.Root, "教案.docx"); SystemTests.Fixture(wordPath); var bytes = await File.ReadAllBytesAsync(wordPath);
        MultipartFormDataContent WordForm(string name, string content = "") {
            var form = new MultipartFormDataContent(); form.Add(new StringContent(courseId.ToString()), "courseId"); form.Add(new StringContent(pid.ToString()), "id"); form.Add(new StringContent("Word教案"), "title"); form.Add(new StringContent(content), "content"); form.Add(new StringContent(name), "fileName"); form.Add(new ByteArrayContent(bytes), "file", name); return form;
        }
        using (var form = WordForm("教案.docx")) Assert.True((await teachers[0].PostAsync($"/api/classes/{cid}/plans/word", form)).IsSuccessStatusCode);
        var download = await teachers[0].GetFromJsonAsync<JsonElement>($"/api/lesson-plans/{pid}/word"); Assert.Equal("docx", download.GetProperty("format").GetString()); Assert.Equal(bytes, await teachers[0].GetByteArrayAsync(download.GetProperty("url").GetString()!));
        Assert.Equal(HttpStatusCode.Forbidden, (await teachers[1].GetAsync($"/api/lesson-plans/{pid}/word")).StatusCode);
        using (var form = WordForm("别人的教案.docx")) Assert.Equal(HttpStatusCode.Forbidden, (await teachers[1].PostAsync($"/api/classes/{cid}/plans/word", form)).StatusCode);
        using (var form = WordForm("替换后的教案.docx")) Assert.True((await teachers[0].PostAsync($"/api/classes/{cid}/plans/word", form)).IsSuccessStatusCode);
        using (var form = WordForm("错误文件.txt")) Assert.Equal(HttpStatusCode.BadRequest, (await teachers[0].PostAsync($"/api/classes/{cid}/plans/word", form)).StatusCode);
        plans = await teachers[0].GetFromJsonAsync<JsonElement>($"/api/classes/{cid}/plans?courseId={courseId}"); Assert.Equal("替换后的教案.docx", plans[0].GetProperty("wordFileName").GetString()); Assert.Equal("", plans[0].GetProperty("content").GetString());

        foreach (var teacher in teachers) teacher.Dispose();
    }

    [Fact]
    public async Task DeviceConnectionRequiresAdminIsIdempotentAndWaitsForRealHeartbeat()
    {
        await using var f = new Factory(); using var admin = await f.Login("connection-admin", true); using var device = f.CreateClient();
        var campus = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "设备校区" }); var cid = campus.GetProperty("id").GetGuid();
        var other = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "其他校区" });
        var pairing = await Factory.Post(device, "/agent/pairings", new { name = "校区电脑" }); var code = pairing.GetProperty("code").GetString();
        device.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", pairing.GetProperty("pollToken").GetString());
        Assert.Equal("pending", (await device.GetFromJsonAsync<JsonElement>("/agent/pairings/status")).GetProperty("state").GetString());
        using var pending = await f.Login("unbound-connector"); Assert.Equal(HttpStatusCode.Unauthorized, (await pending.PostAsJsonAsync("/api/admin/device-connections", new { campusId = cid, code })).StatusCode);
        var connected = await Factory.Post(admin, "/api/admin/device-connections", new { campusId = cid, code }); var id = connected.GetProperty("id").GetGuid();
        var retried = await Factory.Post(admin, "/api/admin/device-connections", new { campusId = cid, code }); Assert.Equal(id, retried.GetProperty("id").GetGuid());
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PostAsJsonAsync("/api/admin/device-connections", new { campusId = other.GetProperty("id").GetGuid(), code })).StatusCode);
        Assert.Equal(id, (await device.GetFromJsonAsync<JsonElement>("/agent/pairings/status")).GetProperty("id").GetGuid());
        var catalog = await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog"); Assert.Equal(JsonValueKind.Null, catalog.GetProperty("devices")[0].GetProperty("lastSeen").ValueKind);
        device.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", pairing.GetProperty("deviceToken").GetString());
        await Factory.Post(device, "/agent/heartbeat", new { printers = new[] { new { queueName = "校区打印机", duplex = true } } });
        catalog = await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog"); Assert.Equal(JsonValueKind.String, catalog.GetProperty("devices")[0].GetProperty("lastSeen").ValueKind); Assert.False(catalog.GetProperty("printers")[0].GetProperty("enabled").GetBoolean());
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); await db.Pairings.ExecuteUpdateAsync(s => s.SetProperty(x => x.ExpiresAt, Clock.Now.AddMinutes(-1)));
        Assert.Equal(HttpStatusCode.BadRequest, (await admin.PostAsJsonAsync("/api/admin/device-connections", new { campusId = cid, code })).StatusCode);
    }
}
