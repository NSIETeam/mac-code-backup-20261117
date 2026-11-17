using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class HandoutBulkConfirmTests
{
    [Fact]
    public async Task BulkConfirmationKeepsExceptionsAndRequiresScopeAndCurrentRevision()
    {
        await using var f = new Factory(); using var admin = await f.Login("bulk-admin", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "校区" }; var course = new Course { Name = "数学" };
        var schoolClass = new SchoolClass { Name = "校对班", CampusId = campus.Id };
        var lecture = new Lecture { ClassId = schoolClass.Id, CourseId = course.Id, Title = "方程", Sequence = 1 };
        var handout = new Handout { LectureId = lecture.Id, State = "draft" };
        var ready = new Question { HandoutId = handout.Id, Label = "例1", PreviewsJson = "[\"preview.png\"]" };
        var warning = new Question { HandoutId = handout.Id, Label = "例2", PreviewsJson = "[\"preview.png\"]", IssuesJson = Json.Write(new[] { new Issue("review", "请核对", false) }) };
        var blocked = new Question { HandoutId = handout.Id, Label = "例3", PreviewsJson = "[\"preview.png\"]", IssuesJson = Json.Write(new[] { new Issue("missing", "内容缺失", true) }) };
        var pending = new Question { HandoutId = handout.Id, Label = "例4" };
        var w=WordDocuments.W;
        foreach(var q in new[]{ready,warning,blocked,pending})q.BlocksJson=Json.Write(new[]{WordDocuments.Block(new System.Xml.Linq.XElement(w+"p",new System.Xml.Linq.XElement(w+"r",new System.Xml.Linq.XElement(w+"t",q.Label+" 计算 2+2。"))))});
        db.AddRange(campus, course, schoolClass, lecture, handout, ready, warning, blocked, pending); await db.SaveChangesAsync();
        var url = $"/api/handouts/{handout.Id}/confirm-all";
        using var other = await f.Login("bulk-other");
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "其他班教师" });
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staff.GetProperty("id").GetGuid()}/invite", new { });
        await Factory.Post(other, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        Assert.Equal(HttpStatusCode.Forbidden, (await other.PostAsJsonAsync(url, new { revision = 0 })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PostAsJsonAsync(url, new { revision = 2 })).StatusCode);
        var result = await Factory.Post(admin, url, new { revision = 0 });
        Assert.Equal(2, result.GetProperty("confirmed").GetInt32()); Assert.Equal(2, result.GetProperty("remaining").GetInt32());
        db.ChangeTracker.Clear(); var qs = await db.Questions.ToArrayAsync();
        Assert.True(qs.Single(q => q.Id == ready.Id).Confirmed); Assert.True(qs.Single(q => q.Id == warning.Id).Confirmed);
        Assert.False(qs.Single(q => q.Id == blocked.Id).Confirmed); Assert.False(qs.Single(q => q.Id == pending.Id).Confirmed);
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PostAsJsonAsync(url, new { revision = 0 })).StatusCode);
        Assert.False((await admin.PostAsJsonAsync($"/api/handouts/{handout.Id}/publish", new { revision = 1 })).IsSuccessStatusCode);
        var h = (await db.Handouts.FindAsync(handout.Id))!; h.State = "published"; await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PostAsJsonAsync(url, new { revision = 1 })).StatusCode);
        Assert.Single(await db.Audits.Where(a => a.Action == "handout.confirm-all").ToListAsync());
    }
}
