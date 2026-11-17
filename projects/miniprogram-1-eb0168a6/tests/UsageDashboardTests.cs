using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class UsageDashboardTests
{
 [Fact] public void AliasesMergeWithoutMergingDifferentRoomsOrDifferentCities(){
  var rows=UsageDashboard.Places([new(){Name="领秀城"},new(){Name="领秀城校区"},new(){Name="洪楼"},new(){Name="洪楼校区",City="济南"},new(){Name="万达写字楼C座1407"},new(){Name="万达C座1407"},new(){Name="万达B座908",City="广州"},new(){Name="中华",City="广州"},new(){Name="中华校区",City="济南"}]);
  Assert.Equal(6,rows.Length);Assert.Equal(2,rows.Single(p=>p.Name=="领秀城").SourceIds.Length);Assert.Equal("济南",rows.Single(p=>p.Name=="洪楼").City);Assert.All(rows.Where(p=>p.Name.Contains("万达")),p=>Assert.Equal("济南",p.City));Assert.Equal(2,rows.Count(p=>p.Name=="中华"));
 }
 [Theory][InlineData("初二数学周日班",true)][InlineData("默认测试班级",false)][InlineData("设备打印诊断（非教学班）",false)][InlineData("网页全流程验收",false)]
 public void ExplicitNonTeachingClassesExcluded(string name,bool teaching)=>Assert.Equal(teaching,UsageDashboard.IsTeachingClass(name));
 [Fact] public async Task UsesActualClassesMergesAliasesDeduplicatesAndKeepsInactiveTeachers(){
  await using var f=new Factory();using var admin=await f.Login("dash-admin",true);
  var a=new Campus{Name="领秀城",City="济南"};var alias=new Campus{Name="领秀城校区"};var b=new Campus{Name="万达C座1407"};
  var t=new Staff{Name="跨校老师",OpenId="development:dash-teacher"};var quiet=new Staff{Name="未使用老师"};var owner=new Staff{Name="创建班级的教务"};
  var c1=new SchoolClass{Name="甲班",CampusId=a.Id,OwnerStaffId=owner.Id};var c2=new SchoolClass{Name="乙班",CampusId=alias.Id};var c3=new SchoolClass{Name="丙班",CampusId=b.Id};var test=new SchoolClass{Name="验收演练班",CampusId=b.Id};var course=new Course{Name="数学"};
  var s1=new Student{Name="学生甲",Code="1",ClassId=c1.Id};var s2=new Student{Name="学生乙",Code="2",ClassId=c2.Id};var at=new DateTimeOffset(2026,11,12,0,0,0,TimeSpan.FromHours(8));
  var device=new Device{Name="万达电脑",CampusId=b.Id};var printer=new Printer{Name="万达打印机",DeviceId=device.Id,CampusId=b.Id};
  var batch=Guid.NewGuid();var d1=new ExportDocument{StaffId=t.Id,ClassId=c1.Id,StudentId=s1.Id,BatchId=batch,State="ready",CreatedAt=at};var d2=new ExportDocument{StaffId=t.Id,ClassId=c2.Id,StudentId=s2.Id,BatchId=batch,State="ready",CreatedAt=at.AddDays(1)};var unknown=new ExportDocument{StaffId=t.Id,Kind="file",State="ready",CreatedAt=at};
  using(var scope=f.Services.CreateScope()){
   var db=scope.ServiceProvider.GetRequiredService<Data>();db.Staff.AddRange(t,quiet,owner);db.Campuses.AddRange(a,alias,b);db.Classes.AddRange(c1,c2,c3,test);db.Courses.Add(course);db.Students.AddRange(s1,s2);db.Devices.Add(device);db.Printers.Add(printer);db.Documents.AddRange(d1,d2,unknown);
   db.Documents.Add(new ExportDocument{StaffId=t.Id,ClassId=test.Id,State="ready",CreatedAt=at});
   foreach(var c in new[]{c1,c2,c3})db.Assignments.Add(new Assignment{StaffId=t.Id,ClassId=c.Id,CourseId=course.Id});db.Assignments.Add(new Assignment{StaffId=quiet.Id,ClassId=c3.Id,CourseId=course.Id});
   db.Audits.AddRange(new Audit{StaffId=t.Id,Action="class-session.mark",TargetId=s1.Id,CreatedAt=at},new Audit{StaffId=quiet.Id,Action="identity.password-login",TargetId=quiet.Id,CreatedAt=at},new Audit{StaffId=t.Id,Action="book.upload",TargetId=c3.Id,CreatedAt=at.AddSeconds(-1)},new Audit{StaffId=t.Id,Action="book.upload",TargetId=c3.Id,CreatedAt=at.AddDays(2)});
   db.PrintJobs.AddRange(new PrintJob{StaffId=t.Id,DocumentId=d1.Id,DeviceId=device.Id,PrinterId=printer.Id,CreatedAt=at,State="submitted",RequestKey="class"},new PrintJob{StaffId=t.Id,DocumentId=unknown.Id,DeviceId=device.Id,PrinterId=printer.Id,CreatedAt=at,State="submitted",RequestKey="file"});await db.SaveChangesAsync();
  }
  const string url="/api/admin/usage-dashboard?start=2026-11-12&end=2026-11-13";var r=await admin.GetFromJsonAsync<JsonElement>(url);
  var cs=r.GetProperty("campuses").EnumerateArray().ToArray();Assert.Equal(2,cs.Length);var city=cs.Single(c=>c.GetProperty("name").GetString()=="领秀城");var cm=city.GetProperty("metrics");Assert.Equal(1,cm.GetProperty("teachers").GetInt32());Assert.Equal(1,cm.GetProperty("activeTeachers").GetInt32());Assert.Equal(2,cm.GetProperty("readyDocuments").GetInt32());Assert.Equal(1,cm.GetProperty("exportBatches").GetInt32());Assert.Equal(1,cm.GetProperty("printTasks").GetInt32());Assert.Equal(2,cm.GetProperty("studentsCovered").GetInt32());Assert.All(city.GetProperty("daily").EnumerateArray(),d=>Assert.Equal(1,d.GetProperty("activeTeachers").GetInt32()));
  var other=cs.Single(c=>c.GetProperty("name").GetString().Contains("万达"));Assert.Equal("济南",other.GetProperty("city").GetString());Assert.Equal(0,other.GetProperty("metrics").GetProperty("usageRate").GetDouble());Assert.Equal(2,other.GetProperty("metrics").GetProperty("teachers").GetInt32());Assert.Equal(0,other.GetProperty("metrics").GetProperty("printTasks").GetInt32());
  Assert.Equal(2,r.GetProperty("summary").GetProperty("teachers").GetInt32());Assert.Equal(0.5,r.GetProperty("summary").GetProperty("usageRate").GetDouble());Assert.Equal(2,r.GetProperty("teachers").GetArrayLength());Assert.Equal(1,r.GetProperty("coverage").GetProperty("unattributedPrints").GetInt32());Assert.Equal(1,r.GetProperty("coverage").GetProperty("excludedTestClasses").GetInt32());
  var scoped=await admin.GetFromJsonAsync<JsonElement>(url+"&campusId="+b.Id);Assert.Equal(0,scoped.GetProperty("summary").GetProperty("activeTeachers").GetInt32());Assert.All(scoped.GetProperty("teachers").EnumerateArray(),x=>Assert.Equal(0,x.GetProperty("metrics").GetProperty("teachingDays").GetInt32()));
  var aliasScoped=await admin.GetFromJsonAsync<JsonElement>(url+"&campusId="+alias.Id);Assert.Equal(2,aliasScoped.GetProperty("summary").GetProperty("readyDocuments").GetInt32());
  using var guest=f.CreateClient();Assert.Equal(HttpStatusCode.Unauthorized,(await guest.GetAsync(url)).StatusCode);using var teacher=await f.Login("dash-teacher");Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync(url)).StatusCode);
 }
}
