using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.EntityFrameworkCore;
using Siyue;
using Xunit;
public class TeachingOperationsTests
{
 [Fact]public void QuotasSurviveServiceRestart(){var dir=Path.Combine(Path.GetTempPath(),"teaching-quota-"+Guid.NewGuid());try{var cfg=new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?>{{"TeachingTools:Directory",dir}}).Build();var first=new TeachingAdmission(cfg);var owner=Guid.NewGuid();for(int i=0;i<6;i++)first.Admit(owner,"lesson");var restarted=new TeachingAdmission(cfg);Assert.Equal(429,Assert.Throws<ApiException>(()=>restarted.Admit(owner,"lesson")).Status);restarted.Admit(owner,"essays");}finally{Directory.Delete(dir,true);}}
 [Fact]public async Task DateEditsAreScopedOptimisticAndTeachingUsageDistinguishesPreview(){
  await using var f=new Factory();using var admin=await f.Login("ops-admin",true);var cfg=f.Services.GetRequiredService<IConfiguration>();cfg["TeachingTools:Directory"]=Path.Combine(f.Root,"teaching");cfg["TeacherUsage:MiniappId"]="wx-ops";
  var staff=new Staff{Name="验收教师",OpenId="development:ops-teacher"};var campus=new Campus{Name="验收"};var cl=new SchoolClass{Name="验收班",CampusId=campus.Id};var course=new Course{Name="英语"};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id};var handout=new Handout{LectureId=lecture.Id,State="published"};var session=new ClassSession{ClassId=cl.Id,CourseId=course.Id,HandoutId=handout.Id,StaffId=staff.Id};
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(staff,campus,cl,course,lecture,handout,session,new Assignment{ClassId=cl.Id,CourseId=course.Id,StaffId=staff.Id});await db.SaveChangesAsync();}
  using var teacher=await f.Login("ops-teacher");teacher.DefaultRequestHeaders.Referrer=new Uri("https://servicewechat.com/wx-ops/devtools/page-frame.html");var date=$"/api/teaching-tools/sessions/{session.Id}/date";
  Assert.Equal(HttpStatusCode.BadRequest,(await teacher.PutAsJsonAsync(date,new{lessonDate="unknown",expectedDate=""})).StatusCode);
  Assert.Equal(HttpStatusCode.OK,(await teacher.PutAsJsonAsync(date,new{lessonDate="2026-11-14",expectedDate=""})).StatusCode);
  Assert.Equal(HttpStatusCode.Conflict,(await teacher.PutAsJsonAsync(date,new{lessonDate="2026-11-13",expectedDate=""})).StatusCode);
  var job=await Factory.Post(teacher,"/api/teaching-tools/jobs",new{classId=cl.Id,kind="lesson",requestId=Guid.NewGuid(),input=new{classId=cl.Id,unit="Unit 1",grammar="present",goal="school day"}});
  Assert.Equal(HttpStatusCode.Conflict,(await teacher.GetAsync($"/api/teaching-tools/jobs/{job.GetProperty("id").GetGuid()}/word?audience=student")).StatusCode);
  var day=Clock.Now.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd");JsonElement[] rows=[];
  for(int i=0;i<20;i++){rows=(await admin.GetFromJsonAsync<JsonElement>($"/api/admin/teacher-usage?start={day}&end={day}")).GetProperty("items").EnumerateArray().ToArray();if(rows.Any(r=>r.GetProperty("action").GetString()=="提交英语备课生成"))break;await Task.Delay(50);}
  Assert.Contains(rows,r=>r.GetProperty("action").GetString()=="提交英语备课生成"&&r.GetProperty("source").GetString()=="preview");Assert.Contains(rows,r=>r.GetProperty("action").GetString()=="导出英语Word未成功");
 }
 [Fact]public async Task OfflineAndFaultyPrintersRejectNewWorkAndExceptionCasesNeverReprint(){
  await using var f=new Factory();using var admin=await f.Login("print-case-admin",true);f.Services.GetRequiredService<IConfiguration>()["Research:Directory"]=Path.Combine(f.Root,"research");
  using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var owner=await db.Staff.FirstAsync(s=>s.Role=="admin");var campus=new Campus{Name="测试校区"};var device=new Device{CampusId=campus.Id,LastSeen=Clock.Now.AddDays(-1)};var printer=new Printer{CampusId=campus.Id,DeviceId=device.Id,Name="实体打印机",Status="ready"};var doc=new ExportDocument{StaffId=owner.Id,State="ready",Kind="file"};var job=new PrintJob{PrinterId=printer.Id,DeviceId=device.Id,DocumentId=doc.Id,StaffId=owner.Id,State="uncertain",CreatedAt=Clock.Now.AddDays(-1)};db.AddRange(campus,device,printer,doc,job);await db.SaveChangesAsync();
  Assert.Equal(409,(await Assert.ThrowsAsync<ApiException>(()=>PrintExceptions.RequireAvailable(printer,db))).Status);device.LastSeen=Clock.Now;printer.Status="missing";await db.SaveChangesAsync();Assert.Equal(409,(await Assert.ThrowsAsync<ApiException>(()=>PrintExceptions.RequireAvailable(printer,db))).Status);printer.Status="ready";await db.SaveChangesAsync();Assert.Equal(409,(await Assert.ThrowsAsync<ApiException>(()=>PrintExceptions.RequireAvailable(printer,db))).Status);
  var list=await admin.GetFromJsonAsync<JsonElement>("/api/admin/print-exceptions");Assert.Single(list.GetProperty("items").EnumerateArray());
  await Factory.Post(admin,$"/api/admin/print-exceptions/{job.Id}/assign",new{staffId=owner.Id,note="联系现场核对实际出纸，保留原任务",dueHours=24});
  list=await admin.GetFromJsonAsync<JsonElement>("/api/admin/print-exceptions");Assert.Equal(owner.Id,list.GetProperty("items")[0].GetProperty("assignment").GetProperty("staffId").GetGuid());Assert.Equal("uncertain",(await db.PrintJobs.AsNoTracking().SingleAsync()).State);Assert.Equal(1,await db.PrintJobs.CountAsync());Assert.Empty(await db.Work.ToArrayAsync());
  using var guest=f.CreateClient();Assert.Equal(HttpStatusCode.Unauthorized,(await guest.GetAsync("/api/admin/print-exceptions")).StatusCode);
 }
}
