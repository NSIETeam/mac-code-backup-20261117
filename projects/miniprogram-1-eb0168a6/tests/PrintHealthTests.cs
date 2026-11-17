using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class PrintHealthTests
{
    sealed class FailingObjects:IObjects {
        public int Failures;
        public Task Put(string k,Stream s,string t,CancellationToken ct=default)=>Task.CompletedTask;
        public Task<Stream> Get(string k,CancellationToken ct=default){if(k==QueueEstimates.MetricsKey)throw new FileNotFoundException();if(Failures-->0)throw new IOException("temporary storage outage");return Task.FromResult<Stream>(new MemoryStream(TeacherWorkspaceTests.Pdf()));}
    }
    sealed class IoFactory:Factory {
        public readonly FailingObjects Objects=new();
        protected override void ConfigureWebHost(IWebHostBuilder b){base.ConfigureWebHost(b);b.ConfigureTestServices(s=>s.AddSingleton<IObjects>(Objects));}
    }
    [Theory][InlineData(1,true)][InlineData(9,false)]
    public async Task PreparationIoRetriesAreBoundedAndNeverCreateAnotherPrintJob(int failures,bool success){
        await using var f=new IoFactory();await f.Login("io-admin",true);f.Objects.Failures=failures;
        var c=new Campus();var d=new Device{CampusId=c.Id};var p=new Printer{DeviceId=d.Id,CampusId=c.Id};
        var doc=new ExportDocument{State="ready",PdfKey="test.pdf",PdfHash=Convert.ToHexStringLower(SHA256.HashData(TeacherWorkspaceTests.Pdf()))};
        var j=new PrintJob{DeviceId=d.Id,PrinterId=p.Id,DocumentId=doc.Id,State="preparing",Copies=1};var w=new WorkItem{Kind="print-prepare",EntityId=j.Id};
        await WithDb(f,async db=>{db.AddRange(c,d,p,doc,j,w,new GuangzhouDevice{Id=d.Id,Enrolled=true});return await db.SaveChangesAsync();});
        for(var n=0;n<3;n++){
            using(var scope=f.Services.CreateScope())await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(default);
            var state=await WithDb(f,db=>db.Work.Where(x=>x.Id==w.Id).Select(x=>x.State).SingleAsync());if(state=="done"||state=="failed")break;
            // Backoff prevents a tight loop while a storage service is down.
            using(var scope=f.Services.CreateScope())Assert.False(await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(default));
            await WithDb(f,db=>db.Work.Where(x=>x.Id==w.Id).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.LeaseUntil,Clock.Now.AddSeconds(-1))));
        }
        Assert.Equal(success?"queued":"failed",await WithDb(f,db=>db.PrintJobs.Where(x=>x.Id==j.Id).Select(x=>x.State).SingleAsync()));
        Assert.Equal(success?2:3,await WithDb(f,db=>db.Work.Where(x=>x.Id==w.Id).Select(x=>x.Attempts).SingleAsync()));
        Assert.Equal(1,await WithDb(f,db=>db.PrintJobs.CountAsync()));Assert.Equal(0,await Recover(f));
    }
    static async Task<T> WithDb<T>(Factory f,Func<Data,Task<T>> action){using var s=f.Services.CreateScope();return await action(s.ServiceProvider.GetRequiredService<Data>());}
    static Task<int> Recover(Factory f)=>WithDb(f,db=>PrintHealth.Recover(db));
    [Fact] public async Task MissingGuangzhouPreparationIsRecoveredOnceAndOriginalClaimIdentitySurvives()
    {
        await using var f=new Factory();using var admin=await f.Login("health-admin",true);
        var c=new Campus{Name="亚运城"};var d=new Device{CampusId=c.Id,LastSeen=Clock.Now,TokenHash=Secrets.Hash("health-secret")};var p=new Printer{CampusId=c.Id,DeviceId=d.Id,Status="ready",StatusAt=Clock.Now};
        var doc=new ExportDocument{State="ready",PdfKey="test/health.pdf",PdfHash=Convert.ToHexStringLower(SHA256.HashData(TeacherWorkspaceTests.Pdf()))};
        var job=new PrintJob{Copies=1,DocumentId=doc.Id,PrinterId=p.Id,DeviceId=d.Id,CreatedAt=Clock.Now.AddMinutes(-2)};
        await WithDb(f,async db=>{db.AddRange(c,d,p,doc,job,new GuangzhouDevice{Id=d.Id,Enrolled=true,Availability="ready"});return await db.SaveChangesAsync();});
        using(var scope=f.Services.CreateScope())await scope.ServiceProvider.GetRequiredService<IObjects>().Put(doc.PdfKey,new MemoryStream(TeacherWorkspaceTests.Pdf()),"application/pdf");
        using var agent=f.CreateClient();agent.DefaultRequestHeaders.Add("X-Print-Agent-Token",d.Id+".health-secret");
        var before=await Factory.Post(agent,"/api/v2/agent/claim",new{});Assert.Equal("preparing",before.GetProperty("reason").GetString());
        var recovered=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>Recover(f)));Assert.Equal(1,recovered.Sum());
        Assert.Equal(1,await WithDb(f,db=>db.Work.CountAsync(w=>w.EntityId==job.Id)));
        await f.Run("print-prepare",job.Id);
        var first=await Factory.Post(agent,"/api/v2/agent/claim",new{});Assert.Equal(job.Id,first.GetProperty("job").GetProperty("id").GetGuid());Assert.False(first.GetProperty("job").GetProperty("reconcile_only").GetBoolean());
        Assert.Equal(0,await Recover(f));
        var next=await Factory.Post(agent,"/api/v2/agent/claim",new{});Assert.Equal(job.Id,next.GetProperty("job").GetProperty("id").GetGuid());Assert.True(next.GetProperty("job").GetProperty("reconcile_only").GetBoolean());
    }
    [Fact] public async Task RecoveryNeverReplaysExecutedUnknownOrDisabledTargetsAndIsBounded()
    {
        await using var f=new Factory();await f.Login("health-safety",true);
        var c=new Campus{Name="广州"};var d=new Device{CampusId=c.Id};var p=new Printer{CampusId=c.Id,DeviceId=d.Id};
        var jobs=new[]{new PrintJob{State="submitted"},new PrintJob{State="uncertain"},new PrintJob{State="submitting"},new PrintJob{State="claimed"},new PrintJob{State="queued",ExecutionJson="{}"},new PrintJob{State="preparing",ClaimToken="already-claimed"},new PrintJob{State="preparing",LeaseUntil=Clock.Now.AddMinutes(-1)},new PrintJob{State="preparing"},new PrintJob{State="preparing"}};
        await WithDb(f,async db=>{db.AddRange(c,d,p);foreach(var j in jobs){j.RequestKey=j.Id.ToString();j.PrinterId=p.Id;j.DeviceId=d.Id;j.CreatedAt=Clock.Now.AddMinutes(-5);db.Add(j);}db.Add(new WorkItem{Kind="print-prepare",EntityId=jobs[7].Id,State="failed",Attempts=3});db.Add(new GuangzhouJob{Id=jobs[8].Id,State="dispatching",ExecutionKey=Guid.NewGuid()});return await db.SaveChangesAsync();});
        Assert.Equal(0,await Recover(f));
        await WithDb(f,async db=>{var other=new PrintJob{State="preparing",PrinterId=p.Id,DeviceId=d.Id,CreatedAt=Clock.Now.AddMinutes(-2)};db.Add(other);await db.Printers.Where(x=>x.Id==p.Id).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.Suppressed,true).SetProperty(x=>x.Enabled,false));return await db.SaveChangesAsync();});
        Assert.Equal(0,await Recover(f));Assert.Equal(1,await WithDb(f,db=>db.Work.CountAsync()));
    }
    [Fact] public async Task CampusAssociationRepairsOnlyTheSameEnabledControllerAndShowsOfflineTruth()
    {
        await using var f=new Factory();using var admin=await f.Login("health-campus",true);using var teacher=await f.Login("health-teacher");
        var old=new Campus{Name="万博校区",Archived=true};var current=new Campus{Name="万博"};var d=new Device{CampusId=current.Id,LastSeen=Clock.Now.AddDays(-3)};
        var p=new Printer{Name="保留的队列",DeviceId=d.Id,CampusId=old.Id,Status="ready",StatusAt=Clock.Now.AddDays(-3)};var deleted=new Printer{QueueName="deleted",DeviceId=d.Id,CampusId=old.Id,Suppressed=true,Enabled=false};
        await WithDb(f,async db=>{db.AddRange(old,current,d,p,deleted);return await db.SaveChangesAsync();});
        Assert.Equal(1,await Recover(f));Assert.Equal(0,await Recover(f));
        Assert.Equal(current.Id,await WithDb(f,db=>db.Printers.Where(x=>x.Id==p.Id).Select(x=>x.CampusId).SingleAsync()));Assert.Equal(old.Id,await WithDb(f,db=>db.Printers.Where(x=>x.Id==deleted.Id).Select(x=>x.CampusId).SingleAsync()));
        var health=await WithDb(f,db=>PrintHealth.Inspect(db));Assert.Equal("offline",Assert.Single(health).Code);
        var catalog=await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog");Assert.Equal("offline",catalog.GetProperty("printerHealth")[0].GetProperty("code").GetString());
        Assert.NotEqual(HttpStatusCode.OK,(await teacher.GetAsync("/api/admin/catalog")).StatusCode);
        await WithDb(f,db=>db.Devices.Where(x=>x.Id==d.Id).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.LastSeen,Clock.Now)));
        Assert.Equal("stale",Assert.Single(await WithDb(f,db=>PrintHealth.Inspect(db))).Code);
        await WithDb(f,db=>db.Printers.Where(x=>x.Id==p.Id).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.StatusAt,Clock.Now)));
        Assert.Equal("ready",Assert.Single(await WithDb(f,db=>PrintHealth.Inspect(db))).Code);
    }
}
