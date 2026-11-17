using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class StudentFollowUpTests
{
    [Fact]
    public async Task NotesAreClassScopedAndSaveRetriesDoNotDuplicateThem()
    {
        await using var factory = new Factory();
        using var admin = await factory.Login("admin", true);
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "测试校区" };
        var first = new SchoolClass { CampusId = campus.Id, Name = "一班" };
        var second = new SchoolClass { CampusId = campus.Id, Name = "二班" };
        var student = new Student { ClassId = first.Id, Name = "测试学员" };
        var course = new Course { Name = "测试课程" };
        db.AddRange(campus, first, second, student, course);
        await db.SaveChangesAsync();

        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "测试教师" });
        var staffId = staff.GetProperty("id").GetGuid();
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staffId}/invite", new { });
        using var teacher = await factory.Login("follow-up-teacher");
        await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });

        var path = $"/api/classes/{first.Id}/students/{student.Id}/follow-ups";
        var other = $"/api/classes/{second.Id}/students/{student.Id}/follow-ups";
        var note = new { note = "下周核对错题整理", requestKey = "retry-test", dueOn = "2026-11-05" };
        var alerts = $"/api/classes/{first.Id}/follow-up-alerts";
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync(alerts)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync(path)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.PostAsJsonAsync(path, note)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await admin.GetAsync(other)).StatusCode);

        var grantsPath = $"/api/admin/classes/{first.Id}/follow-up-grants";
        var grantInput = new { staffId, department = "招生" };
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.PostAsJsonAsync(grantsPath, grantInput)).StatusCode);
        var grant = await Factory.Post(admin, grantsPath, grantInput);
        Assert.Single((await teacher.GetFromJsonAsync<JsonElement>("/api/student-follow-up/classes")).EnumerateArray());
        Assert.Equal(HttpStatusCode.OK, (await teacher.GetAsync($"/api/classes/{first.Id}/follow-up-roster")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await teacher.GetAsync(path)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/classes/{first.Id}/students")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/classes/{second.Id}/follow-up-roster")).StatusCode);
        var grantId = grant.GetProperty("id").GetGuid();
        Assert.Equal(HttpStatusCode.NoContent, (await admin.DeleteAsync($"{grantsPath}/{grantId}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync(path)).StatusCode);
        Assert.Empty((await teacher.GetFromJsonAsync<JsonElement>("/api/student-follow-up/classes")).EnumerateArray());

        db.Assignments.Add(new Assignment { ClassId = first.Id, StaffId = staffId, CourseId = course.Id });
        await db.SaveChangesAsync();
        var saved = await Factory.Post(teacher, path, note);
        var retry = await Factory.Post(teacher, path, note);
        Assert.Equal(saved.GetProperty("id").GetGuid(), retry.GetProperty("id").GetGuid());
        Assert.Equal(HttpStatusCode.Conflict, (await teacher.PostAsJsonAsync(path, new { note = "修改的文字", note.requestKey })).StatusCode);
        var result = await admin.GetFromJsonAsync<JsonElement>(path);
        Assert.Equal(note.note, Assert.Single(result.GetProperty("notes").EnumerateArray()).GetProperty("note").GetString());
        var alert = Assert.Single((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());
        Assert.Equal(note.dueOn, alert.GetProperty("dueOn").GetString());
        Assert.False(alert.TryGetProperty("note", out _));
        var id = saved.GetProperty("id").GetGuid();
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PostAsJsonAsync($"{path}/{id}/resolve", new { })).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PostAsJsonAsync($"{path}/{id}/resolve", new { })).StatusCode);
        Assert.Empty((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());
        Assert.NotNull((await db.StudentFollowUps.SingleAsync()).ResolvedAt);
        Assert.Single(await db.Audits.Where(a => a.Action == "student.followup.resolved").ToListAsync());
 
        var otherCourse = new Course { Name = "未授权课程" };
        var lecture = new Lecture { CourseId = otherCourse.Id, Title = "考勤验收" };
        var handout = new Handout { LectureId = lecture.Id };
        var today = Clock.Now.ToOffset(TimeSpan.FromHours(8));
        var firstSession = new ClassSession { ClassId = first.Id, CourseId = otherCourse.Id, HandoutId = handout.Id, LessonDate = today.AddDays(-2).ToString("yyyy-MM-dd") };
        var secondSession = new ClassSession { ClassId = first.Id, CourseId = otherCourse.Id, HandoutId = handout.Id, LessonDate = today.AddDays(-1).ToString("yyyy-MM-dd") };
        var leave = new AcademicAttendance { ClassSessionId = firstSession.Id, StudentId = student.Id, Status = "leave", MakeupStatus = "pending" };
        var absent = new AcademicAttendance { ClassSessionId = secondSession.Id, StudentId = student.Id, Status = "absent" };
        db.AddRange(otherCourse, lecture, handout, firstSession, secondSession, leave, absent);
        await db.SaveChangesAsync();

        var attendance = Assert.Single((await admin.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());
        Assert.Equal("attendance", attendance.GetProperty("kind").GetString());
        Assert.Equal(2, attendance.GetProperty("count").GetInt32());
        Assert.Empty((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());
        await Factory.Post(admin, grantsPath, new { staffId, department = "教务" });
        Assert.Single((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());

        leave.MakeupStatus = "scheduled";
        await db.SaveChangesAsync();
        Assert.Empty((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());

        var homework = new AcademicCollection
        {
            ClassId = first.Id, State = "approved", DueOn = firstSession.LessonDate,
            RequestKey = "overdue-alert-fixture",
            RosterJson = System.Text.Json.JsonSerializer.Serialize(new[] { new CollectionRoster(student.Id, student.Name) }, new JsonSerializerOptions(JsonSerializerDefaults.Web))
        };
        db.AcademicCollections.Add(homework);
        await db.SaveChangesAsync();
        var overdue = Assert.Single((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());
        Assert.Equal("homework", overdue.GetProperty("kind").GetString());
        Assert.Equal(student.Id, overdue.GetProperty("studentId").GetGuid());

        var submission = new AcademicSubmission { CollectionId = homework.Id, Name = student.Name };
        db.AcademicSubmissions.Add(submission);
        await db.SaveChangesAsync();
        Assert.Empty((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());
        submission.StudentId = student.Id;
        await db.SaveChangesAsync();
        Assert.Empty((await teacher.GetFromJsonAsync<JsonElement>(alerts)).EnumerateArray());
    }
}
