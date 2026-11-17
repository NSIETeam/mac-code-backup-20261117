using System.Net;
using System.Net.Http.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.EntityFrameworkCore;
using Siyue;
using Siyue.Printing;
using Xunit;
public class ExecutionMonitoringTests
{
    static ExecutionSnapshot Sample() { var now=DateTimeOffset.UtcNow;return new(Guid.NewGuid(),"Canon",now,now,ProcessId:123); }
    [Theory][InlineData(1u,1u)][InlineData(16u,64u)][InlineData(128u,32u)][InlineData(0u,0u)]
 public void QueueLevelProblemsAreIncludedInJobEvidence(uint printer,uint job) => Assert.Equal(job,ExecutionTelemetry.PrinterToJobFlags(printer));
 [Fact] public void RedactedCompletionMatchesOnlyTheObservedJobOnTheSameQueueAfterStart()
    {
        var x=Sample() with {WindowsJobIds=[34]};var now=DateTimeOffset.UtcNow;
        Assert.True(ExecutionTelemetry.MatchesCompletion(x,"Canon",34,null,now));
        Assert.False(ExecutionTelemetry.MatchesCompletion(x,"Other Canon",34,null,now));
        Assert.False(ExecutionTelemetry.MatchesCompletion(x,"Canon",35,null,now));
        Assert.False(ExecutionTelemetry.MatchesCompletion(x,"Canon",34,null,x.StartedAt.AddMinutes(-1)));
    }
    [Fact] public void ExitZeroAndQueueDisappearanceAreNotProofOfCompletion()
    {
        var x=Sample() with {ExitCode=0,ProcessExitedAt=DateTimeOffset.UtcNow,WindowsJobIds=[34],InQueue=false};
        Assert.Equal("awaiting_result",ExecutionTelemetry.Evaluate(x,DateTimeOffset.UtcNow).Phase);
        Assert.Equal("uncertain",ExecutionTelemetry.Evaluate(x,DateTimeOffset.UtcNow.AddMinutes(6)).Phase);
        Assert.Equal("system_completed",ExecutionTelemetry.Evaluate(x with {SystemCompletedAt=DateTimeOffset.UtcNow},DateTimeOffset.UtcNow).Phase);
    }
    [Fact] public void ResumedQueueClearsAnEarlierErrorAndNonzeroExitNeverClaimsCompletion()
    {
        var x=Sample() with {InQueue=true,QueueFlags=32};Assert.Equal("blocked",ExecutionTelemetry.Evaluate(x,DateTimeOffset.UtcNow).Phase);
        Assert.Equal("printing",ExecutionTelemetry.Evaluate(x with {QueueFlags=16},DateTimeOffset.UtcNow).Phase);
        Assert.Equal("uncertain",ExecutionTelemetry.Evaluate(x with {ExitCode=70,SystemCompletedAt=DateTimeOffset.UtcNow},DateTimeOffset.UtcNow).Phase);
    }
    [Fact] public void ReceiptsSurviveRestartWithoutContainingClaimCredentials()
    {
        var path=Path.Combine(Path.GetTempPath(),Guid.NewGuid().ToString());
        try {var x=Sample();var store=new ExecutionStore(path);store.Start(x.JobId,x.QueueName);store.Change(x.JobId,j=>j with {ProcessId=123,ExitCode=0});var loaded=new ExecutionStore(path).Snapshot();Assert.Single(loaded);Assert.Equal(123,loaded[0].ProcessId);Assert.Equal(0,loaded[0].ExitCode);Assert.DoesNotContain("claimToken",File.ReadAllText(Directory.GetFiles(path,"*.json")[0]));}
        finally {Directory.Delete(path,true);}
    }
    [Fact] public async Task DeviceCannotForgeAnotherDevicesExecutionOrRegressCompletion()
    {
        await using var f=new Factory();using var agent=f.CreateClient();var token=Secrets.New();var d=new Device{Name="campus",TokenHash=Secrets.Hash(token)};var p=new Printer{DeviceId=d.Id,QueueName="Canon",Name="Canon"};var j=new PrintJob{DeviceId=d.Id,PrinterId=p.Id,State="submitting",RequestKey="monitor-own"};var other=new PrintJob{DeviceId=Guid.NewGuid(),PrinterId=p.Id,State="submitting",RequestKey="monitor-other"};
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();db.AddRange(d,p,j,other);await db.SaveChangesAsync();}
        agent.DefaultRequestHeaders.Authorization=new("Bearer",token);
        var x=Sample() with {JobId=j.Id};
        object Body(ExecutionSnapshot r)=>new {printers=Array.Empty<object>(),complete=false,monitorVersion="1.0.0",executions=new[]{r}};
        Assert.Equal(HttpStatusCode.Forbidden,(await agent.PostAsJsonAsync("/agent/heartbeat",Body(x with{JobId=other.Id}))).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await agent.PostAsJsonAsync("/agent/heartbeat",Body(x with{Phase="system_completed"}))).StatusCode);
        var completed=x with {Phase="system_completed",ExitCode=0,SystemCompletedAt=DateTimeOffset.UtcNow};await Factory.Post(agent,"/agent/heartbeat",Body(completed));
        await Factory.Post(agent,"/agent/heartbeat",Body(x with {UpdatedAt=DateTimeOffset.UtcNow}));
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal("system_completed",MonitorTelemetry.Read((await db.PrintJobs.FindAsync(j.Id))!.ExecutionJson)!.Phase);}
        // A legacy heartbeat must retain the latest durable execution receipt.
        await Factory.Post(agent,"/agent/heartbeat",new{printers=Array.Empty<object>(),complete=false,agentVersion="2.1.1"});
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal("system_completed",MonitorTelemetry.Read((await db.PrintJobs.FindAsync(j.Id))!.ExecutionJson)!.Phase);}
    }
    [Fact] public async Task FreshUnreachablePrinterIsNotClaimed()
    {
        await using var f=new Factory();using var agent=f.CreateClient();var token=Secrets.New();var d=new Device{TokenHash=Secrets.Hash(token),AgentVersion="2.1.4"};var p=new Printer{DeviceId=d.Id,QueueName="Canon",Status="ready",StatusAt=Clock.Now,DeviceReachable=false,DeviceCheckedAt=Clock.Now};var doc=new ExportDocument{PdfKey="fixture.pdf",PdfHash=new string('a',64)};var j=new PrintJob{DeviceId=d.Id,PrinterId=p.Id,DocumentId=doc.Id};
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();db.AddRange(d,p,doc,j);await db.SaveChangesAsync();}
        agent.DefaultRequestHeaders.Authorization=new("Bearer",token);Assert.Equal(HttpStatusCode.NoContent,(await agent.PostAsJsonAsync("/agent/claim",new{})).StatusCode);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();await db.Printers.Where(x=>x.Id==p.Id).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.DeviceReachable,true));}
        Assert.Equal(HttpStatusCode.OK,(await agent.PostAsJsonAsync("/agent/claim",new{})).StatusCode);
    }
}
