using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Xml.Linq;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class AutomaticClassSessionTests
{
    static Question ReadyQuestion(Guid handoutId) => new() {
        HandoutId = handoutId, Confirmed = true, Label = "例1", PreviewsJson = "[\"preview.png\"]",
        BlocksJson = Json.Write(new[] { WordDocuments.Block(new XElement(WordDocuments.W + "p",
            new XElement(WordDocuments.W + "r", new XElement(WordDocuments.W + "t", "例1 求 1+1。")))) })
    };

    [Fact]
    public async Task PublishingCreatesClassEntryWithoutInventingLessonDateAndDoesNotDuplicateVersions()
    {
        await using var f = new Factory(); using var admin = await f.Login("auto-session-publish", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "测试" }; var course = new Course { Name = "数学" };
        var cl = new SchoolClass { Name = "测试班", CampusId = campus.Id };
        var lecture = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 2, Title = "方程" };
        var h = new Handout { LectureId = lecture.Id, State = "draft" };
        var student = new Student { ClassId = cl.Id, Name = "学生甲", Code = "01" };
        db.AddRange(campus, course, cl, lecture, h, student, ReadyQuestion(h.Id)); await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/publish", new { revision = 0 })).StatusCode);
        var session = Assert.Single(await db.ClassSessions.AsNoTracking().ToArrayAsync());
        Assert.Equal(cl.Id, session.ClassId); Assert.Equal(h.Id, session.HandoutId); Assert.Equal("", session.LessonDate);
        Assert.Equal(student.Id, Assert.Single(await db.ClassSessionMarks.ToArrayAsync()).StudentId);
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/publish", new { revision = 0 })).StatusCode);
        var v2 = new Handout { LectureId = lecture.Id, State = "draft", Version = 2 };
        db.AddRange(v2, ReadyQuestion(v2.Id)); await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PostAsJsonAsync($"/api/handouts/{v2.Id}/publish", new { revision = 0 })).StatusCode);
        Assert.Equal(h.Id, Assert.Single(await db.ClassSessions.AsNoTracking().ToArrayAsync()).HandoutId);
        Assert.Single(await db.Audits.Where(x => x.Action == "class-session.auto-create").ToArrayAsync());
    }

    [Fact]
    public async Task ExistingPublishedLecturesSyncInNumericOrderWithoutDraftsDuplicatesOrOtherClasses()
    {
        await using var f = new Factory(); using var admin = await f.Login("auto-session-sync", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "测试" }; var course = new Course { Name = "数学" };
        var cl = new SchoolClass { Name = "测试班", CampusId = campus.Id }; var other = new SchoolClass { Name = "另一班", CampusId = campus.Id };
        db.AddRange(campus, course, cl, other);
        ClassSession? manual = null;
        foreach (var seq in new[] { 10, 2, 1, 3 }) {
            var lecture = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = seq, Title = "讲次" + seq };
            var h = new Handout { LectureId = lecture.Id, State = seq == 3 ? "draft" : "published" };
            db.AddRange(lecture, h);
            if (seq == 2) { manual = new ClassSession { ClassId = cl.Id, CourseId = course.Id, HandoutId = h.Id, LessonDate = "2026-10-12" }; db.Add(manual); }
        }
        foreach (var classId in new Guid?[] { other.Id, null }) {
            var lecture = new Lecture { ClassId = classId, CourseId = course.Id, Sequence = 1, Title = "不归入本班" };
            db.AddRange(lecture, new Handout { LectureId = lecture.Id, State = "published" });
        }
        await db.SaveChangesAsync();
        var first = await Factory.Post(admin, $"/api/classes/{cl.Id}/sync-sessions", new { }); Assert.Equal(2, first.GetProperty("created").GetInt32());
        Assert.Equal(0, (await Factory.Post(admin, $"/api/classes/{cl.Id}/sync-sessions", new { })).GetProperty("created").GetInt32());
        var rows = await admin.GetFromJsonAsync<JsonElement>($"/api/class-sessions?classId={cl.Id}");
        Assert.Equal(new[] { 1, 2, 10 }, rows.EnumerateArray().Select(r => r.GetProperty("sequence").GetInt32()).ToArray());
        Assert.Equal("2026-10-12", (await db.ClassSessions.AsNoTracking().SingleAsync(x => x.Id == manual!.Id)).LessonDate);
        Assert.Equal(3, await db.ClassSessions.CountAsync());
    }

    [Fact]
    public async Task RosterCanBeAddedAfterPublicationWithoutChangingReviewedSessions()
    {
        await using var f = new Factory(); using var admin = await f.Login("auto-session-roster", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "测试" }; var course = new Course { Name = "数学" }; var cl = new SchoolClass { Name = "空名单班", CampusId = campus.Id };
        var lecture = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 1, Title = "方程" }; var h = new Handout { LectureId = lecture.Id, State = "draft" };
        db.AddRange(campus, course, cl, lecture, h, ReadyQuestion(h.Id)); await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/publish", new { revision = 0 })).StatusCode);
        var session = await db.ClassSessions.SingleAsync(); Assert.Empty(await db.ClassSessionMarks.ToArrayAsync());
        var student = new Student { ClassId = cl.Id, Name = "后来导入", Code = "01" }; db.Add(student); await db.SaveChangesAsync();
        await Factory.Post(admin, $"/api/classes/{cl.Id}/sync-sessions", new { });
        var mark = Assert.Single(await db.ClassSessionMarks.ToArrayAsync()); mark.Reviewed = true; mark.Revision = 4; mark.WrongIdsJson = "[]";
        db.Add(new Student { ClassId = cl.Id, Name = "更晚导入", Code = "02" }); await db.SaveChangesAsync();
        await Factory.Post(admin, $"/api/classes/{cl.Id}/sync-sessions", new { });
        db.ChangeTracker.Clear(); mark = Assert.Single(await db.ClassSessionMarks.ToArrayAsync()); Assert.True(mark.Reviewed); Assert.Equal(4, mark.Revision);
        Assert.Equal(session.Id, Assert.Single(await db.ClassSessions.ToArrayAsync()).Id);
    }

    [Fact]
    public async Task SynchronizationRespectsTeacherCourseAndClassScope()
    {
        await using var f = new Factory(); using var admin = await f.Login("auto-session-scope", true); using var teacher = await f.Login("auto-session-teacher");
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "任课教师" }); var staffId = staff.GetProperty("id").GetGuid();
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staffId}/invite", new { }); await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "测试" }; var math = new Course { Name = "数学" }; var english = new Course { Name = "英语" };
        var cl = new SchoolClass { Name = "任课班", CampusId = campus.Id }; var other = new SchoolClass { Name = "无权班", CampusId = campus.Id };
        db.AddRange(campus, math, english, cl, other, new Assignment { StaffId = staffId, ClassId = cl.Id, CourseId = math.Id });
        foreach (var course in new[] { math, english }) { var l = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 1, Title = course.Name }; db.AddRange(l, new Handout { LectureId = l.Id, State = "published" }); }
        await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.PostAsJsonAsync($"/api/classes/{other.Id}/sync-sessions", new { })).StatusCode);
        Assert.Equal(1, (await Factory.Post(teacher, $"/api/classes/{cl.Id}/sync-sessions", new { })).GetProperty("created").GetInt32());
        Assert.Equal(math.Id, Assert.Single(await db.ClassSessions.ToArrayAsync()).CourseId);
    }
}
