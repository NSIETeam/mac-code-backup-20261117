using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class UsageStatisticsTests
{
    [Theory]
    [InlineData("campuses.create","创建校区")]
    [InlineData("research.submit","提交资料审核")]
    [InlineData("future.feature.action","future.feature.action")]
    public void AdditionalTeacherOperationsRemainVisible(string code,string label)
    {
        var result=UsageStatistics.Describe(code);Assert.Equal("unattributed",result.Source);Assert.Equal(label,result.Action);
        Assert.Null(UsageStatistics.Describe("maintenance.teacher-account.create").Action);
        Assert.Equal("miniapp",UsageStatistics.Describe("usage.miniapp.future.action").Source);
        Assert.Equal("preview",UsageStatistics.Describe("usage.miniapp-preview.future.action").Source);
    }
    [Fact]
    public async Task RosterZeroUsageDuplicatesDateBoundaryAndPrintStatesAreExplicit()
    {
        await using var f=new Factory();using var admin=await f.Login("stats-admin",true);
        var a=Guid.NewGuid();var b=Guid.NewGuid();var disabled=Guid.NewGuid();var test=Guid.NewGuid();
        using(var scope=f.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();
            db.Staff.AddRange(new Staff{Id=a,Name="同名老师",OpenId="development:stats-teacher"},new Staff{Id=b,Name="同名老师"},new Staff{Id=disabled,Name="归档老师",Archived=true,Enabled=false},new Staff{Id=test,Name="test_teacher"});
            var at=new DateTimeOffset(2026,11,11,16,0,0,TimeSpan.Zero);
            foreach(var action in new[]{"print.submit","usage.miniapp.print.submit","identity.password-login","maintenance.teacher-account.create","usage.miniapp-preview.workspace"})db.Audits.Add(new Audit{StaffId=a,Action=action,CreatedAt=at,TargetId=a});
            db.Audits.Add(new Audit{StaffId=a,Action="book.upload",CreatedAt=at.AddSeconds(-1),TargetId=a});
            db.Audits.Add(new Audit{StaffId=a,Action="book.upload",CreatedAt=at.AddDays(1),TargetId=a});
            db.Audits.Add(new Audit{StaffId=disabled,Action="identity.password-login",CreatedAt=at,TargetId=disabled});
            db.Audits.Add(new Audit{StaffId=test,Action="print.submit",CreatedAt=at,TargetId=test});
            var campus=new Campus{Name="统计校区"};var cl=new SchoolClass{Name="统计班",CampusId=campus.Id};var course=new Course{Name="统计课程"};
            var device=new Device{Name="电脑",CampusId=campus.Id};var printer=new Printer{Name="打印机",CampusId=campus.Id,DeviceId=device.Id};var doc=new ExportDocument{StaffId=a};
            db.Campuses.Add(campus);db.Classes.Add(cl);db.Courses.Add(course);db.Devices.Add(device);db.Printers.Add(printer);db.Documents.Add(doc);
            db.Assignments.Add(new Assignment{StaffId=a,ClassId=cl.Id,CourseId=course.Id});
            foreach(var state in new[]{"submitted","uncertain","failed","queued"})db.PrintJobs.Add(new PrintJob{StaffId=a,DocumentId=doc.Id,DeviceId=device.Id,PrinterId=printer.Id,State=state,Copies=2,CreatedAt=at,RequestKey=Guid.NewGuid().ToString()});
            await db.SaveChangesAsync();
        }
        const string path="/api/admin/usage-statistics?start=2026-11-12&end=2026-11-12";
        var result=await admin.GetFromJsonAsync<JsonElement>(path);var rows=result.GetProperty("rows").EnumerateArray().ToArray();
        Assert.Equal(3,rows.Length);Assert.DoesNotContain(rows,x=>x.GetProperty("id").GetGuid()==test);
        var first=Assert.Single(rows,x=>x.GetProperty("id").GetGuid()==a);
        Assert.Equal(1,first.GetProperty("businessCount").GetInt32());Assert.Equal(1,first.GetProperty("miniappCount").GetInt32());Assert.Equal(1,first.GetProperty("loginCount").GetInt32());Assert.Equal(1,first.GetProperty("activeDays").GetInt32());
        Assert.Equal(4,first.GetProperty("printCount").GetInt32());Assert.Equal(8,first.GetProperty("copies").GetInt32());
        foreach(var key in new[]{"submitted","failed","uncertain","pending"})Assert.Equal(1,first.GetProperty(key).GetInt32());
        Assert.Equal("统计校区",first.GetProperty("campuses")[0].GetString());
        var empty=Assert.Single(rows,x=>x.GetProperty("id").GetGuid()==b);Assert.Equal("无使用记录",empty.GetProperty("status").GetString());Assert.Equal(JsonValueKind.Null,empty.GetProperty("lastAt").ValueKind);
        Assert.Equal("仅登录或改密",Assert.Single(rows,x=>x.GetProperty("id").GetGuid()==disabled).GetProperty("status").GetString());
        var detail=await admin.GetFromJsonAsync<JsonElement>($"/api/admin/usage-statistics/{a}?start=2026-11-12&end=2026-11-12");
        Assert.Equal(3,detail.GetProperty("total").GetInt32());Assert.Equal("2026-11-12",detail.GetProperty("daily")[0].GetProperty("day").GetString());Assert.Equal(4,detail.GetProperty("jobs").GetArrayLength());
        using var guest=f.CreateClient();Assert.Equal(HttpStatusCode.Unauthorized,(await guest.GetAsync(path)).StatusCode);
        using var teacher=await f.Login("stats-teacher");Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync(path)).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"/api/admin/usage-statistics/{a}?start=2026-11-12&end=2026-11-12")).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await admin.GetAsync("/api/admin/usage-statistics?start=2026-11-13&end=2026-11-12")).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await admin.GetAsync("/api/admin/usage-statistics?start=bad&end=2026-11-12")).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await admin.GetAsync($"/api/admin/usage-statistics/{a}?start=2026-11-12&end=2026-11-12&page=-1")).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await admin.GetAsync($"/api/admin/usage-statistics/{a}?start=2026-11-12&end=2026-11-12&day=2026-11-11")).StatusCode);
        var multi=await admin.GetFromJsonAsync<JsonElement>($"/api/admin/usage-statistics/{a}?start=2026-11-11&end=2026-11-13&day=2026-11-11");
        Assert.Equal(3,multi.GetProperty("daily").GetArrayLength());Assert.Equal(5,multi.GetProperty("periodTotal").GetInt32());Assert.Equal(1,multi.GetProperty("total").GetInt32());Assert.Equal("2026-11-11",multi.GetProperty("selectedDay").GetString());
        Assert.Equal("上传教材",multi.GetProperty("items")[0].GetProperty("action").GetString());Assert.Equal("同名老师",multi.GetProperty("items")[0].GetProperty("targetName").GetString());Assert.Empty(multi.GetProperty("jobs").EnumerateArray());
    }
    [Fact]
    public async Task DetailsPaginateWithoutDroppingDailyTotals()
    {
        await using var f=new Factory();using var admin=await f.Login("stats-page",true);var id=Guid.NewGuid();
        using(var scope=f.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();db.Staff.Add(new Staff{Id=id,Name="分页老师"});
            for(var i=0;i<101;i++)db.Audits.Add(new Audit{StaffId=id,Action="handout.publish",TargetId=id,CreatedAt=new DateTimeOffset(2026,11,12,8,0,0,TimeSpan.Zero).AddSeconds(i)});
            await db.SaveChangesAsync();
        }
        var url=$"/api/admin/usage-statistics/{id}?start=2026-11-12&end=2026-11-12";
        var p0=await admin.GetFromJsonAsync<JsonElement>(url);var p1=await admin.GetFromJsonAsync<JsonElement>(url+"&page=1");
        Assert.Equal(100,p0.GetProperty("items").GetArrayLength());Assert.True(p0.GetProperty("hasMore").GetBoolean());Assert.Equal(1,p1.GetProperty("items").GetArrayLength());Assert.False(p1.GetProperty("hasMore").GetBoolean());
        Assert.Equal(101,p1.GetProperty("daily")[0].GetProperty("business").GetInt32());
        Assert.DoesNotContain(p0.GetProperty("items").EnumerateArray(),x=>x.GetProperty("id").GetGuid()==p1.GetProperty("items")[0].GetProperty("id").GetGuid());
    }
}
