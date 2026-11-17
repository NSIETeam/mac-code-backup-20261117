using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class QueueEstimateEndpointTests
{
    [Fact]
    public async Task AuthorizedLecturePagesExposeEstimatesWithoutOtherUsersQueueDetails()
    {
        await using var f = new Factory(); using var admin = await f.Login("queue-endpoints", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var campus = new Campus { Name = "测试" }; var course = new Course { Name = "数学" };
        var cl = new SchoolClass { Name = "本班", CampusId = campus.Id }; var other = new SchoolClass { Name = "不可泄露的班级", CampusId = campus.Id };
        var l = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 1, Title = "方程" };
        var h = new Handout { LectureId = l.Id, State = "queued" };
        var b = new BookImport { ClassId = cl.Id, CourseId = course.Id, State = "queued", OriginalKey = "queue-test.pdf", FileName = "本班讲义.pdf" };
        b.SectionsJson = Json.Write(new[] { new BookSection(0, 1, 1, "方程", HandoutId: h.Id) });
        var hidden = new WorkItem { Kind = "import", EntityId = Guid.NewGuid(), State = "running", Claim = "never-expose-claim", LeaseUntil = Clock.Now.AddMinutes(5) };
        db.AddRange(campus, course, cl, other, l, h, b, hidden,
            new WorkItem { Kind = "book-auto", EntityId = b.Id }, new WorkItem { Kind = "import", EntityId = h.Id });
        await db.SaveChangesAsync(); await QueueEstimates.Started(objects, hidden, hidden.Claim);
        foreach (var path in new[] { $"/api/classes/{cl.Id}", $"/api/classes/{cl.Id}/books?courseId={course.Id}", $"/api/books/{b.Id}", $"/api/handouts/{h.Id}?classId={cl.Id}" }) {
            var response = await admin.GetAsync(path); var body = await response.Content.ReadAsStringAsync();
            Assert.Equal(HttpStatusCode.OK, response.StatusCode); Assert.Contains("\"queue\"", body); Assert.Contains("\"waitSeconds\"", body);
            Assert.DoesNotContain(hidden.EntityId.ToString(), body); Assert.DoesNotContain(hidden.Id.ToString(), body);
            Assert.DoesNotContain(hidden.Claim, body); Assert.DoesNotContain(other.Name, body);
        }
        var detail = await admin.GetFromJsonAsync<JsonElement>($"/api/books/{b.Id}");
        Assert.Equal("queued", detail.GetProperty("queue").GetProperty("state").GetString());
        Assert.True(detail.GetProperty("queue").GetProperty("ahead").GetInt32() >= 1);
        Assert.True(detail.GetProperty("queue").GetProperty("waitSeconds").GetInt64() > 0);
        using var stranger = await f.Login("queue-endpoints-stranger");
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "未授权教师" });
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staff.GetProperty("id").GetGuid()}/invite", new { });
        await Factory.Post(stranger, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        foreach (var path in new[] { $"/api/books/{b.Id}", $"/api/handouts/{h.Id}?classId={cl.Id}", $"/api/classes/{cl.Id}/books?courseId={course.Id}" })
            Assert.Equal(HttpStatusCode.Forbidden, (await stranger.GetAsync(path)).StatusCode);
    }
}
