using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class InstallationProfileTests
{
    [Fact] public async Task InitializationAutomaticallyConnectsAndPreservesAdminChanges()
    {
        await using var f = new Factory(); using var admin = await f.Login("profile-admin", true); using var agent = f.CreateClient();
        var campus = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "验收校区" });
        var otherCampus = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "其他校区" });
        var key = Secrets.New();
        var input = new { name = "验收控制电脑", enrollmentKey = key, profile = new { campusName = "验收校区", queueName = "Canon Test Queue", printerAlias = "验收校区 东" } };
        var first = await Factory.Post(agent, "/agent/enrollments", input);
        var second = await Factory.Post(agent, "/agent/enrollments", input);
        Assert.Equal(first.GetProperty("id").GetGuid(), second.GetProperty("id").GetGuid());
        Assert.Empty((await admin.GetFromJsonAsync<JsonElement>("/api/admin/device-enrollments")).EnumerateArray());
        agent.DefaultRequestHeaders.Authorization = new("Bearer", first.GetProperty("pollToken").GetString());
        var connection = await agent.GetFromJsonAsync<JsonElement>("/agent/pairings/status");
        Assert.Equal("connected", connection.GetProperty("state").GetString());
        Assert.Equal(campus.GetProperty("id").GetGuid(), connection.GetProperty("campusId").GetGuid());
        agent.DefaultRequestHeaders.Authorization = new("Bearer", first.GetProperty("deviceToken").GetString());
        await Factory.Post(agent, "/agent/heartbeat", new { printers = new[] { new { queueName = "Canon Test Queue", duplex = true, status = "ready" }, new { queueName = "PDF", duplex = false, status = "ready" } } });
        var applied = await Factory.Post(agent, "/agent/installation-profile", input.profile);
        Assert.Equal("验收校区 东", applied.GetProperty("name").GetString()); Assert.True(applied.GetProperty("enabled").GetBoolean());
        var printerId = applied.GetProperty("id").GetGuid();
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PutAsJsonAsync("/api/admin/printers/" + printerId, new { name = "管理员修改名称", enabled = false })).StatusCode);
        await Factory.Post(agent, "/agent/enrollments", input);
        await Factory.Post(agent, "/agent/heartbeat", new { printers = new[] { new { queueName = "Canon Test Queue", duplex = true, status = "ready" } } });
        var afterAdmin = await agent.GetFromJsonAsync<JsonElement>("/agent/installation-profile");
        var edited = afterAdmin.GetProperty("printers").EnumerateArray().Single(p => p.GetProperty("queueName").GetString() == "Canon Test Queue");
        Assert.False(edited.GetProperty("enabled").GetBoolean()); Assert.Equal("管理员修改名称", edited.GetProperty("name").GetString());
        await Factory.Post(agent, "/agent/installation-profile", input.profile);
        await Factory.Post(agent, "/agent/installation-profile", new { campusName = "验收校区", queueName = "Canon Test Queue", printerAlias = "本机主动修改名称" });
        Assert.Equal(HttpStatusCode.Conflict, (await agent.PostAsJsonAsync("/agent/installation-profile", new { campusName = "其他校区", queueName = "Canon Test Queue", printerAlias = "其他校区 东" })).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await agent.PostAsJsonAsync("/agent/installation-profile", new { campusName = "验收校区", queueName = "别的电脑队列", printerAlias = "别的电脑" })).StatusCode);
        var profile = await agent.GetFromJsonAsync<JsonElement>("/agent/installation-profile"); Assert.Equal("验收校区", profile.GetProperty("campusName").GetString());
        Assert.Equal("PDF", profile.GetProperty("printers").EnumerateArray().Single(p => p.GetProperty("queueName").GetString() == "PDF").GetProperty("name").GetString());
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        Assert.Equal(1, await db.Audits.CountAsync(x => x.Action == "device.printer-alias"));
    }
    [Fact] public async Task InvalidProfileDoesNotRegisterDevice()
    {
        await using var f = new Factory(); using var client = f.CreateClient();
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync("/agent/enrollments", new { name = "test", enrollmentKey = Secrets.New(), profile = new { campusName = " ", queueName = "Q", printerAlias = "东" } })).StatusCode);
        using var scope = f.Services.CreateScope(); Assert.Empty(await scope.ServiceProvider.GetRequiredService<Data>().Pairings.ToListAsync());
    }
    [Fact] public async Task NewCampusIsCreatedOnceAndAnotherEnrollmentCannotTakeOverExistingPrinter()
    {
        await using var f = new Factory(); using var admin = await f.Login("auto-admin", true); using var client = f.CreateClient();
        var profile = new { campusName = "新校区", queueName = "Queue", printerAlias = "新校区东侧打印机" };
        var first = await Factory.Post(client, "/agent/enrollments", new { name = "PC", enrollmentKey = Secrets.New(), profile });
        Assert.Equal(HttpStatusCode.Conflict, (await client.PostAsJsonAsync("/agent/enrollments", new { name = "PC2", enrollmentKey = Secrets.New(), profile })).StatusCode);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        Assert.Single(await db.Campuses.Where(c => c.Name == "新校区").ToListAsync());
        Assert.Single(await db.Printers.Where(p => p.Name == "新校区东侧打印机" && p.Enabled).ToListAsync());
        var d = await db.Devices.SingleAsync();
        var target = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "调整后校区" });
        Assert.Equal(HttpStatusCode.NoContent, (await admin.PutAsJsonAsync("/api/admin/devices/" + d.Id, new { name = "调整后设备", campusId = target.GetProperty("id").GetGuid(), enabled = true })).StatusCode);
        db.ChangeTracker.Clear(); var updated = await db.Devices.SingleAsync(); var printer = await db.Printers.SingleAsync();
        Assert.Equal("调整后设备", updated.Name); Assert.Equal(updated.CampusId, printer.CampusId); Assert.Equal(target.GetProperty("id").GetGuid(), printer.CampusId);
    }
}
