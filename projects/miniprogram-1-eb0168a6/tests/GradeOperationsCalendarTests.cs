using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Siyue;
using Xunit;

public class GradeOperationsCalendarTests
{
    [Fact]
    public async Task SharedTemplateProducesIndependentIdempotentCalendarsWithGradeScopedEditing()
    {
        await using var factory = new Factory();
        using var admin = await factory.Login("admin", true);
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "年级运营验收" });
        var staffId = staff.GetProperty("id").GetGuid();
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staffId}/invite", new { });
        using var owner = await factory.Login("grade-operations-owner");
        await Factory.Post(owner, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });

        const string root = "/api/grade-calendar";
        var first = root + "/" + Uri.EscapeDataString("高一");
        var second = root + "/" + Uri.EscapeDataString("高二");
        var assignment = first + "/owners/" + staffId;
        var template = new { title = "验收模板", steps = new[] { new { offset = -7, action = "活动预告" }, new { offset = 0, action = "现场核对" } } };
        Assert.Equal(HttpStatusCode.Forbidden, (await owner.PostAsJsonAsync(root + "/templates", template)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await owner.GetAsync(first + "/events?month=2026-11")).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PutAsJsonAsync(assignment, new { })).StatusCode);
        var row = await Factory.Post(admin, root + "/templates", template);
        var input = new { templateId = row.GetProperty("id").GetGuid(), anchor = "2026-11-15" };
        Assert.Equal(2, (await Factory.Post(owner, first + "/events", input)).GetProperty("added").GetInt32());
        Assert.Equal(0, (await Factory.Post(owner, first + "/events", input)).GetProperty("added").GetInt32());
        Assert.Equal(HttpStatusCode.Forbidden, (await owner.PostAsJsonAsync(second + "/events", input)).StatusCode);
        Assert.Equal(2, (await Factory.Post(admin, second + "/events", input)).GetProperty("added").GetInt32());
        var events = await owner.GetFromJsonAsync<JsonElement>(first + "/events?month=2026-11");
        var eventId = events[0].GetProperty("id").GetGuid();
        Assert.Equal("2026-11-08", events[0].GetProperty("date").GetString());
        Assert.Equal(HttpStatusCode.NoContent, (await owner.PutAsJsonAsync(first + "/events/" + eventId, new { date = "2026-11-09" })).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await owner.PutAsJsonAsync(first + "/events/" + eventId, new { done = true })).StatusCode);
        var updated = (await owner.GetFromJsonAsync<JsonElement>(first + "/events?month=2026-11"))[0];
        Assert.Equal("2026-11-09", updated.GetProperty("date").GetString());
        Assert.True(updated.GetProperty("done").GetBoolean());
        var independent = (await admin.GetFromJsonAsync<JsonElement>(second + "/events?month=2026-11"))[0];
        Assert.Equal("2026-11-08", independent.GetProperty("date").GetString());
        Assert.False(independent.GetProperty("done").GetBoolean());
        Assert.Equal(HttpStatusCode.NoContent, (await admin.DeleteAsync(assignment)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await owner.GetAsync(first + "/events?month=2026-11")).StatusCode);
    }
}
