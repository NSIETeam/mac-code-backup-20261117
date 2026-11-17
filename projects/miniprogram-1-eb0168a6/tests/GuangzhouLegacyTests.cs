using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class GuangzhouLegacyTests {
    sealed class LegacyHandler:HttpMessageHandler {
        public Guid Id=Guid.NewGuid(),Queue=Guid.NewGuid(),OldJob=Guid.NewGuid();public string Mode="generating",LastBody="";public int Claims;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage r,CancellationToken ct){
            if(r.Headers.GetValues("X-Print-Agent-Token").Single()!=Id+".original-device-secret")return new(HttpStatusCode.Unauthorized);
            var path=r.RequestUri!.AbsolutePath;object result=new{ok=true};
            if(path.EndsWith("/identity"))result=new{id=Id,queue_id=Queue,campus_id=7,campus_name="原校区",protocol_version=2,protocol_revision=3};
            else if(path.EndsWith("/claim")){Claims++;result=Mode=="generating"?new{job=(object?)null,reason="head_generating"}:Mode=="empty"?new{job=(object?)null,reason="empty"}:new{job=(object?)new{id=OldJob,state="claimed"},reason="existing"};}
            else if(path.Contains("/jobs/")){LastBody=r.Content==null?"":await r.Content.ReadAsStringAsync(ct);result=new{state="resolved",original_service=true};}
            return new(HttpStatusCode.OK){Content=new StringContent(JsonSerializer.Serialize(result),Encoding.UTF8,"application/json")};
        }
    }
    sealed class LegacyFactory:Factory {
        public readonly LegacyHandler Handler=new();
        protected override void ConfigureWebHost(IWebHostBuilder builder){base.ConfigureWebHost(builder);builder.ConfigureTestServices(s=>s.AddHttpClient("guangzhou-legacy").ConfigurePrimaryHttpMessageHandler(()=>Handler));}
    }
    [Fact] public async Task ExistingIdentityAndOriginalTasksRemainAuthoritativeWithoutRebinding(){
        await using var f=new LegacyFactory();using var admin=await f.Login("legacy-admin",true);var d=f.Handler;
        Guid jobId;using(var scope=f.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();var campus=new Campus{Name="新的显示名称"};db.Campuses.Add(campus);
            db.Devices.Add(new Device{Id=d.Id,CampusId=campus.Id,Name="原电脑",TokenHash="not-a-migrated-secret"});
            db.GuangzhouDevices.Add(new GuangzhouDevice{Id=d.Id,QueueId=d.Queue,CampusNumber=7,Legacy=true,IdentityCampusName="原校区",Enrolled=true,Availability="ready",BindingHash="not-enrollable"});
            var printer=new Printer{CampusId=campus.Id,DeviceId=d.Id,Name="原打印机"};db.Printers.Add(printer);
            var owner=await db.Staff.SingleAsync();var doc=new ExportDocument{StaffId=owner.Id,State="ready",Kind="file"};db.Documents.Add(doc);
            var job=new PrintJob{StaffId=owner.Id,DocumentId=doc.Id,PrinterId=printer.Id,DeviceId=d.Id,RequestKey="legacy-test"};db.PrintJobs.Add(job);jobId=job.Id;
            db.GuangzhouJobs.Add(new GuangzhouJob{Id=job.Id,Pages=2,Hash=new string('a',64),Bytes=10});await db.SaveChangesAsync();
        }
        using var client=f.CreateClient();client.DefaultRequestHeaders.Add("X-Print-Agent-Token",d.Id+".original-device-secret");
        var identity=await client.GetFromJsonAsync<JsonElement>("/api/v2/agent/identity");Assert.Equal("原校区",identity.GetProperty("campus_name").GetString());Assert.Equal(d.Queue,identity.GetProperty("queue_id").GetGuid());
        await Factory.Post(client,"/api/v2/agent/heartbeat",new{availability="ready"});
        var blocked=await Factory.Post(client,"/api/v2/agent/claim",new{});Assert.Equal("head_generating",blocked.GetProperty("reason").GetString());
        d.Mode="old-job";var original=await Factory.Post(client,"/api/v2/agent/claim",new{});Assert.Equal(d.OldJob,original.GetProperty("job").GetProperty("id").GetGuid());
        var history=await client.GetFromJsonAsync<JsonElement>($"/api/v2/agent/jobs/{d.OldJob}");Assert.True(history.GetProperty("original_service").GetBoolean());
        var key=Guid.NewGuid();await Factory.Post(client,$"/api/v2/agent/jobs/{d.OldJob}/status",new{queue_id=d.Queue,pdf_sha256=new string('a',64),pdf_bytes=10,state="uncertain",execution_key=key});Assert.Equal(key,JsonDocument.Parse(d.LastBody).RootElement.GetProperty("execution_key").GetGuid());
        d.Mode="empty";var current=await Factory.Post(client,"/api/v2/agent/claim",new{});Assert.Equal(jobId,current.GetProperty("job").GetProperty("id").GetGuid());
        var calls=d.Claims;var reconnect=await Factory.Post(client,"/api/v2/agent/claim",new{});Assert.True(reconnect.GetProperty("job").GetProperty("reconcile_only").GetBoolean());Assert.Equal(calls,d.Claims);
        using var stranger=f.CreateClient();stranger.DefaultRequestHeaders.Add("X-Print-Agent-Token",d.Id+".wrong-secret");Assert.Equal(HttpStatusCode.Unauthorized,(await stranger.GetAsync("/api/v2/agent/identity")).StatusCode);
        using var sessionToken=f.CreateClient();sessionToken.DefaultRequestHeaders.Authorization=new("Bearer",d.Id+".original-device-secret");Assert.Equal(HttpStatusCode.Unauthorized,(await sessionToken.GetAsync("/api/admin/catalog")).StatusCode);
    }
}
