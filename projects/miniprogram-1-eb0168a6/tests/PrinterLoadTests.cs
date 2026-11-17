using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class PrinterLoadTests
{
 [Fact] public async Task BacklogSeparatesQueuedActiveAndFinishedAndIncludesOtherTeachersWithoutExposingTheirDocuments()
 {
  await using var f=new Factory();using var admin=await f.Login("load-admin",true);
  var campus=new Campus{Name="模拟校区"};var device=new Device{Name="模拟东侧",CampusId=campus.Id,LastSeen=Clock.Now,TokenHash=Secrets.Hash("load-device-token")};
  var p=new Printer{Name="佳能",QueueName="Canon",DeviceId=device.Id,CampusId=campus.Id,Enabled=true};
  var disabled=new Printer{Name="停用",QueueName="disabled",DeviceId=device.Id,CampusId=campus.Id,Enabled=false};
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(campus,device,p,disabled); foreach(var state in new[]{"queued","queued","claimed","submitting","submitted","failed","uncertain"}) db.PrintJobs.Add(new PrintJob{PrinterId=p.Id,DeviceId=device.Id,StaffId=Guid.NewGuid(),State=state,RequestKey=Guid.NewGuid().ToString(),Detail="不能泄露的学生资料"});await db.SaveChangesAsync();}
  using var agent=f.CreateClient();agent.DefaultRequestHeaders.Authorization=new("Bearer","load-device-token");
  await Factory.Post(agent,"/agent/heartbeat",new{printers=new[]{new{queueName="Canon",duplex=true,status="busy",jobCount=5}},agentVersion="2.1.2"});
  var printers=await admin.GetFromJsonAsync<JsonElement>("/api/printers");var row=Assert.Single(printers.EnumerateArray());
  Assert.Equal(5,row.GetProperty("jobCount").GetInt32());Assert.Equal(2,row.GetProperty("serverQueuedCount").GetInt32());Assert.Equal(2,row.GetProperty("serverActiveCount").GetInt32());Assert.True(row.GetProperty("loadObservedAt").GetDateTimeOffset()>Clock.Now.AddSeconds(-10));Assert.DoesNotContain("不能泄露",printers.ToString());
  await Factory.Post(agent,"/agent/heartbeat",new{printers=new[]{new{queueName="Canon",duplex=true,status="ready",jobCount=0}},agentVersion="2.1.2"});
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();await db.PrintJobs.Where(x=>x.PrinterId==p.Id).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,"submitted"));}
  row=(await admin.GetFromJsonAsync<JsonElement>("/api/printers")).EnumerateArray().Single();Assert.Equal(0,row.GetProperty("jobCount").GetInt32());Assert.Equal(0,row.GetProperty("serverQueuedCount").GetInt32());Assert.Equal(0,row.GetProperty("serverActiveCount").GetInt32());
 }
}
