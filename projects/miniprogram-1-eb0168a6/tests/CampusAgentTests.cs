using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Siyue.Printing;
using Xunit;
public class CampusAgentTests
{
    [Theory]
    [InlineData("https://example.com/miniapp", "https://example.com/miniapp/files/token", true)]
    [InlineData("https://example.com/miniapp", "https://example.com/files/token", false)]
    [InlineData("https://example.com/miniapp", "https://other.com/miniapp/files/token", false)]
    [InlineData("https://example.com/miniapp", "https://example.com/miniapp/files/../../api/token", false)]
    [InlineData("https://example.com/miniapp", "http://example.com/miniapp/files/token", false)]
    public void DownloadIsScopedToConfiguredServerAndApp(string address, string file, bool allowed) => Assert.Equal(allowed, new ServerEndpoint(address).AllowsFile(new Uri(file)));
    [Fact] public void ChangingServerOrDeviceSeparatesJournalsAndIpDoesNotDisableTls()
    {
        var id = Guid.NewGuid(); var a = new ServerEndpoint("https://example.com/miniapp", "192.0.2.1");
        Assert.Equal("example.com", a.CreateClient().BaseAddress!.Host); Assert.NotEqual(a.Scope(id), new ServerEndpoint("https://other.com/miniapp").Scope(id)); Assert.NotEqual(a.Scope(id), a.Scope(Guid.NewGuid()));
        Assert.Throws<ArgumentException>(() => new ServerEndpoint("http://192.0.2.1")); Assert.Throws<ArgumentException>(() => new ServerEndpoint("http://localhost", "192.0.2.1")); Assert.Throws<ArgumentException>(() => new ServerEndpoint("https://user:password@example.com")); Assert.Throws<ArgumentException>(() => new ServerEndpoint("https://example.com?secret=x"));
    }
    [Theory]
    [InlineData(0u,"ready")][InlineData(128u,"offline")][InlineData(1u,"paused")][InlineData(8u,"error")][InlineData(16u,"error")][InlineData(131072u,"warning")][InlineData(1024u,"busy")][InlineData(128u|1024u,"offline")]
    public void MapsSpoolerFailuresBeforeBusy(uint flags,string expected) => Assert.Equal(expected,SpoolerStatus.Describe(flags).Status);

    [Fact] public async Task AutomaticRegistrationIsPersistentAdminAssignedAndDeviceScoped()
    {
        await using var f = new Factory(); using var admin = await f.Login("auto-admin",true); using var agent = f.CreateClient();
        var key = Secrets.New(); var input = new {name="校区控制电脑",enrollmentKey=key};
        var first = await Factory.Post(agent,"/agent/enrollments",input); var again = await Factory.Post(agent,"/agent/enrollments",input);
        Assert.Equal(first.GetProperty("id").GetString(),again.GetProperty("id").GetString()); Assert.Equal(first.GetProperty("deviceToken").GetString(),again.GetProperty("deviceToken").GetString());
        Assert.Equal(HttpStatusCode.Unauthorized,(await agent.GetAsync("/api/admin/device-enrollments")).StatusCode);
        var pending = await admin.GetFromJsonAsync<JsonElement>("/api/admin/device-enrollments"); Assert.Single(pending.EnumerateArray()); Assert.False(pending[0].TryGetProperty("deviceToken",out _));
        var campus = await Factory.Post(admin,"/api/admin/catalog/campuses",new {name="自动登记校区"});
        var assigned = await Factory.Post(admin,"/api/admin/device-connections",new {campusId=campus.GetProperty("id").GetGuid(),pairingId=first.GetProperty("id").GetGuid()});
        Assert.Empty((await admin.GetFromJsonAsync<JsonElement>("/api/admin/device-enrollments")).EnumerateArray());
        agent.DefaultRequestHeaders.Authorization = new("Bearer",first.GetProperty("pollToken").GetString());
        var status = await agent.GetFromJsonAsync<JsonElement>("/agent/pairings/status"); Assert.Equal("connected",status.GetProperty("state").GetString()); Assert.Equal(assigned.GetProperty("id").GetString(),status.GetProperty("id").GetString());
        // A lost enrollment response / restart cannot create a duplicate device.
        var resumed = await Factory.Post(agent,"/agent/enrollments",input); Assert.Equal(first.GetProperty("id").GetString(),resumed.GetProperty("id").GetString());
        agent.DefaultRequestHeaders.Authorization = new("Bearer",first.GetProperty("deviceToken").GetString());
        await Factory.Post(agent,"/agent/heartbeat", new {printers=new[]{new {queueName="佳能",duplex=true,status="error",rawStatus=8,jobCount=2,detail="卡纸",driver="Canon",port="IP_192.0.2.2"}},agentVersion="2.0.0",machineName="PC-1"});
        var catalog = await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog"); var p=catalog.GetProperty("printers")[0]; Assert.Equal("error",p.GetProperty("status").GetString()); Assert.False(p.GetProperty("enabled").GetBoolean());
        await Factory.Post(agent,"/agent/heartbeat",new {printers=Array.Empty<object>(),complete=false,error="超时",agentVersion="2.0.0"});
        catalog=await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog"); Assert.Equal("unknown",catalog.GetProperty("printers")[0].GetProperty("status").GetString());
        await Factory.Post(agent,"/agent/heartbeat",new {printers=Array.Empty<object>(),complete=true,agentVersion="2.0.0"});
        catalog=await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog"); Assert.Equal("missing",catalog.GetProperty("printers")[0].GetProperty("status").GetString());
        var duplicate = new {printers=new[]{new {queueName="A",duplex=false},new{queueName="a",duplex=false}}}; Assert.Equal(HttpStatusCode.BadRequest,(await agent.PostAsJsonAsync("/agent/heartbeat",duplicate)).StatusCode);
        // A second device must not overwrite or delete this device's queues.
        using var other = f.CreateClient(); var second=await Factory.Post(other,"/agent/enrollments",new {name="另一电脑",enrollmentKey=Secrets.New()});
        await Factory.Post(admin,"/api/admin/device-connections",new {campusId=campus.GetProperty("id").GetGuid(),pairingId=second.GetProperty("id").GetGuid()});
        other.DefaultRequestHeaders.Authorization=new("Bearer",second.GetProperty("deviceToken").GetString());
        await Factory.Post(other,"/agent/heartbeat",new {printers=new[]{new {queueName="佳能",duplex=false,status="ready"}},agentVersion="2.0.0"});
        catalog=await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog"); Assert.Equal(2,catalog.GetProperty("printers").GetArrayLength()); Assert.Equal("missing",catalog.GetProperty("printers").EnumerateArray().Single(x=>x.GetProperty("id").GetGuid()==p.GetProperty("id").GetGuid()).GetProperty("status").GetString());
    }
    [Fact] public async Task ClaimWaitsForFreshHealthyQueueAndDoesNotSendToOtherDevice()
    {
        await using var f=new Factory(); using var agent=f.CreateClient(); var token=Secrets.New(); var d=new Device {Name="控制电脑",TokenHash=Secrets.Hash(token),AgentVersion="2.0.0"}; var printer=new Printer {DeviceId=d.Id,Name="佳能",QueueName="佳能",Status="error",StatusAt=Clock.Now}; var doc=new ExportDocument {PdfKey="test.pdf",PdfHash=new string('a',64)}; var job=new PrintJob {DeviceId=d.Id,PrinterId=printer.Id,DocumentId=doc.Id,RequestKey="claim-test"};
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(d,printer,doc,job);await db.SaveChangesAsync();}
        agent.DefaultRequestHeaders.Authorization=new("Bearer",token);
        Assert.Equal(HttpStatusCode.NoContent,(await agent.PostAsJsonAsync("/agent/claim",new{})).StatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();await db.Printers.ExecuteUpdateAsync(s=>s.SetProperty(x=>x.Status,"ready").SetProperty(x=>x.StatusAt,Clock.Now.AddMinutes(-2)));}
        Assert.Equal(HttpStatusCode.NoContent,(await agent.PostAsJsonAsync("/agent/claim",new{})).StatusCode);
        await Factory.Post(agent,"/agent/heartbeat",new {printers=new[]{new{queueName="佳能",duplex=false,status="ready"}},agentVersion="2.0.0"});
        var claimed=await Factory.Post(agent,"/agent/claim",new{}); Assert.Equal(job.Id,claimed.GetProperty("id").GetGuid());
        Assert.Equal(HttpStatusCode.NoContent,(await agent.PostAsJsonAsync("/agent/claim",new{})).StatusCode);
    }

    [Fact]
    public async Task MicrosoftPdfQueuesAreSkippedWithoutDiscardingPhysicalPrinterTelemetry()
    {
        await using var factory = new Factory();
        using var agent = factory.CreateClient();
        var token = Secrets.New();
        var device = new Device { Name = "校区电脑", TokenHash = Secrets.Hash(token) };
        var existing = new Printer { DeviceId = device.Id, QueueName = "历史 PDF 别名", Enabled = true };
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        db.AddRange(device, existing);
        await db.SaveChangesAsync();
        db.ChangeTracker.Clear();
        agent.DefaultRequestHeaders.Authorization = new("Bearer", token);

        await Factory.Post(agent, "/agent/heartbeat", new HeartbeatInput([
            new("Microsoft Print to PDF", false),
            new("另一个别名", false, Driver: "Microsoft Print To PDF"),
            new(existing.QueueName, false, Driver: "Microsoft Print To PDF"),
            new("Canon", true, "ready", Driver: "Canon Generic UFR II")
        ]));
        Assert.NotNull((await db.Devices.SingleAsync()).LastSeen);
        var printers = await db.Printers.ToArrayAsync();
        Assert.Equal(2, printers.Length);
        Assert.True(printers.Single(p => p.Id == existing.Id).Suppressed);
        Assert.False(printers.Single(p => p.Id == existing.Id).Enabled);
        Assert.Equal("ready", printers.Single(p => p.QueueName == "Canon").Status);
    }
}
