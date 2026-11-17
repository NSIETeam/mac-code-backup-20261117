using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class DeferredPrintingTests
{
    sealed record Setup(HttpClient Teacher, HttpClient Device, Guid TeacherId, Guid ClassId, Guid OtherClassId, Guid CourseId, Guid CampusId, Guid PrinterId, Guid DeviceId);
    static async Task<Setup> Prepare(Factory f, HttpClient admin, bool guangzhou)
    {
        var teacherId = (await Factory.Post(admin,"/api/admin/catalog/staff",new{name="跨校区教师"})).GetProperty("id").GetGuid();
        var invite=await Factory.Post(admin,$"/api/admin/staff/{teacherId}/invite",new{});
        var teacher=await f.Login("cross-campus-teacher"); await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});
        using var services=f.Services.CreateScope();var db=services.ServiceProvider.GetRequiredService<Data>();
        var home=new Campus{Name="银座"};var other=new Campus{Name="广州"};var course=new Course{Name="数学"};
        var cl=new SchoolClass{Name="自己的班",CampusId=home.Id};var foreign=new SchoolClass{Name="别人的班",CampusId=other.Id};
        db.AddRange(home,other,course,cl,foreign,new Assignment{StaffId=teacherId,ClassId=cl.Id,CourseId=course.Id});await db.SaveChangesAsync();
        var device=f.CreateClient(); Guid deviceId,printerId;
        if(guangzhou){
            var binding=await Factory.Post(admin,"/api/admin/guangzhou-bindings",new{campusId=other.Id,name="广州测试打印机"});
            var enrollment=await Factory.Post(device,"/api/v2/agent/enroll",new{code=binding.GetProperty("code").GetString()});
            device.DefaultRequestHeaders.Add("X-Print-Agent-Token",enrollment.GetProperty("credential").GetString());
            await Factory.Post(device,"/api/v2/agent/heartbeat",new{availability="ready"});
            var p=await db.Printers.SingleAsync(p=>p.CampusId==other.Id);deviceId=p.DeviceId;printerId=p.Id;
        }else{
            var binding=await Factory.Post(admin,"/api/admin/device-bindings",new{campusId=other.Id});
            var enrollment=await Factory.Post(device,"/agent/bind",new{code=binding.GetProperty("code").GetString(),name="广州测试控制电脑"});
            device.DefaultRequestHeaders.Authorization=new("Bearer",enrollment.GetProperty("token").GetString());deviceId=enrollment.GetProperty("id").GetGuid();
            var p=new Printer{DeviceId=deviceId,CampusId=other.Id,Name="广州测试打印机",QueueName="test",Duplex=true,Status="ready"};db.Add(p);await db.SaveChangesAsync();printerId=p.Id;
        }
        return new(teacher,device,teacherId,cl.Id,foreign.Id,course.Id,other.Id,printerId,deviceId);
    }
    static async Task<ExportDocument> Document(Factory f,Setup s,string state="queued",bool foreign=false)
    {
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
        var d=new ExportDocument{StaffId=s.TeacherId,ClassId=foreign?s.OtherClassId:s.ClassId,CourseId=s.CourseId,StudentName="队列验收",State=state};db.Add(d);await db.SaveChangesAsync();return d;
    }
    static async Task Ready(Factory f,Guid id)
    {
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var d=await db.Documents.FindAsync(id);var pdf=TeacherWorkspaceTests.Pdf();
        d!.PdfKey="test/"+id+".pdf";d.PdfHash=Convert.ToHexStringLower(SHA256.HashData(pdf));d.State="ready";
        await scope.ServiceProvider.GetRequiredService<IObjects>().Put(d.PdfKey,new MemoryStream(pdf),"application/pdf");await db.SaveChangesAsync();
    }
    static async Task<bool> Tick(Factory f){using var scope=f.Services.CreateScope();return await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(default);}

    [Fact] public async Task TeachersSeeSharedDevicesWithoutAccessToForeignClassesOrDocuments()
    {
        await using var f=new Factory();using var admin=await f.Login("admin",true);var s=await Prepare(f,admin,false);using var teacher=s.Teacher;using var device=s.Device;
        var workspace=await teacher.GetFromJsonAsync<JsonElement>("/api/workspace");
        Assert.Single(workspace.GetProperty("classes").EnumerateArray());Assert.Contains(workspace.GetProperty("campuses").EnumerateArray(),c=>c.GetProperty("id").GetGuid()==s.CampusId);
        Assert.Contains(workspace.GetProperty("printCampuses").EnumerateArray(),c=>c.GetProperty("id").GetGuid()==s.CampusId);
        Assert.Equal((await admin.GetFromJsonAsync<JsonElement>("/api/printers")).GetArrayLength(),(await teacher.GetFromJsonAsync<JsonElement>("/api/printers")).GetArrayLength());
        Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"/api/classes/{s.OtherClassId}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync("/api/admin/catalog")).StatusCode);
        var foreign=await Document(f,s,foreign:true);var own=await Document(f,s);
        Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync("/api/print-jobs",new{documentIds=new[]{own.Id,foreign.Id},printerId=s.PrinterId,copies=1,duplex=false,requestKey="foreign-document",waitForDocuments=true})).StatusCode);
        using(var scope=f.Services.CreateScope())Assert.Empty(await scope.ServiceProvider.GetRequiredService<Data>().PrintJobs.ToListAsync());
        await Ready(f,own.Id);
        var jobs=await Factory.Post(teacher,"/api/print-jobs",new{documentIds=new[]{own.Id},printerId=s.PrinterId,copies=1,duplex=false,requestKey="allowed-document"});Assert.Equal("queued",jobs[0].GetProperty("state").GetString());
    }

    [Theory][InlineData(false)][InlineData(true)]
    public async Task DeferredBatchSurvivesClientExitPartialFailureAndWorkerReplay(bool guangzhou)
    {
        await using var f=new Factory();using var admin=await f.Login("admin",true);var s=await Prepare(f,admin,guangzhou);using var teacher=s.Teacher;using var device=s.Device;
        var first=await Document(f,s);var failed=await Document(f,s);var waiting=await Document(f,s,"processing");
        var body=new{documentIds=new[]{first.Id,failed.Id,waiting.Id},printerId=s.PrinterId,copies=2,duplex=false,requestKey="durable-batch-key",waitForDocuments=true};
        var jobs=await Factory.Post(teacher,"/api/print-jobs",body);Assert.All(jobs.EnumerateArray(),j=>Assert.Equal("preparing",j.GetProperty("state").GetString()));
        var replays=await Task.WhenAll(Enumerable.Range(0,12).Select(_=>Factory.Post(teacher,"/api/print-jobs",body)));Assert.All(replays,replay=>Assert.Equal(jobs.ToString(),replay.ToString()));
        Assert.False(await Tick(f)); // Waiting documents do not consume worker attempts or block other work.
        var claimPath=guangzhou?"/api/v2/agent/claim":"/agent/claim";
        var before=await Factory.Post(device,claimPath,new{});if(guangzhou)Assert.Equal(JsonValueKind.Null,before.GetProperty("job").ValueKind);else Assert.Equal(JsonValueKind.Undefined,before.ValueKind);
        await Ready(f,first.Id);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();await db.Documents.Where(d=>d.Id==failed.Id).ExecuteUpdateAsync(u=>u.SetProperty(d=>d.State,"failed"));}
        Assert.True(await Tick(f));Assert.True(await Tick(f));Assert.False(await Tick(f));
        Guid firstJob;
        using(var scope=f.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();var list=await db.PrintJobs.ToListAsync();Assert.Equal(3,list.Count);
            firstJob=list.Single(j=>j.DocumentId==first.Id).Id;Assert.Equal("queued",list.Single(j=>j.Id==firstJob).State);Assert.Equal("failed",list.Single(j=>j.DocumentId==failed.Id).State);Assert.Equal("preparing",list.Single(j=>j.DocumentId==waiting.Id).State);
            Assert.Equal(guangzhou?1:0,await db.GuangzhouJobs.CountAsync());Assert.False(await db.Work.AnyAsync(w=>w.Kind=="export-preview"));
        }
        var claimed=await Factory.Post(device,claimPath,new{});var claimedId=guangzhou?claimed.GetProperty("job").GetProperty("id").GetGuid():claimed.GetProperty("id").GetGuid();Assert.Equal(firstJob,claimedId);
        await f.Run("print-prepare",firstJob); // Simulated restart after preparation committed but before work completion.
        using(var scope=f.Services.CreateScope())Assert.Equal("claimed",(await scope.ServiceProvider.GetRequiredService<Data>().PrintJobs.FindAsync(firstJob))!.State);
        Assert.Equal(3,(await Factory.Post(teacher,"/api/print-jobs",body)).GetArrayLength()); // Lost response still resolves even after one document failed.
        await Ready(f,waiting.Id);Assert.True(await Tick(f));
        using(var scope=f.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(3,await db.PrintJobs.CountAsync());Assert.Equal("queued",(await db.PrintJobs.SingleAsync(j=>j.DocumentId==waiting.Id)).State);
            if(guangzhou)Assert.All(await db.GuangzhouJobs.ToListAsync(),j=>Assert.Equal(4,j.Pages));
        }
        Assert.Equal(HttpStatusCode.Conflict,(await teacher.PostAsJsonAsync("/api/print-jobs",new{documentIds=new[]{first.Id},printerId=s.PrinterId,copies=3,duplex=false,requestKey="durable-batch-key",waitForDocuments=true})).StatusCode);
    }

    [Fact] public async Task PendingPrintsRespectDisabledDevicesAndExplicitOptIn()
    {
        await using var f=new Factory();using var admin=await f.Login("admin",true);var s=await Prepare(f,admin,false);using var teacher=s.Teacher;using var device=s.Device;var d=await Document(f,s);
        Assert.Equal(HttpStatusCode.Conflict,(await teacher.PostAsJsonAsync("/api/print-jobs",new{documentIds=new[]{d.Id},printerId=s.PrinterId,copies=1,duplex=false,requestKey="needs-opt-in"})).StatusCode);
        await Factory.Post(teacher,"/api/print-jobs",new{documentIds=new[]{d.Id},printerId=s.PrinterId,copies=1,duplex=false,requestKey="waiting-disable",waitForDocuments=true});
        using(var scope=f.Services.CreateScope())await scope.ServiceProvider.GetRequiredService<Data>().Devices.Where(x=>x.Id==s.DeviceId).ExecuteUpdateAsync(u=>u.SetProperty(x=>x.Enabled,false));
        await Ready(f,d.Id);Assert.True(await Tick(f));
        using(var scope=f.Services.CreateScope())Assert.Equal("failed",(await scope.ServiceProvider.GetRequiredService<Data>().PrintJobs.SingleAsync()).State);
    }
}
