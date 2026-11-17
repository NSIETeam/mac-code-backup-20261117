using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class CatalogRemovalTests
{
    static async Task<HttpClient> Teacher(Factory f,HttpClient admin){var staff=await Factory.Post(admin,"/api/admin/catalog/staff",new{name="普通教师"});var invite=await Factory.Post(admin,$"/api/admin/staff/{staff.GetProperty("id").GetGuid()}/invite",new{});var client=await f.Login("teacher");await Factory.Post(client,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});return client;}

    [Fact]
    public async Task AdminCanArchiveClassesAndAccountsWhileRetainingHistoricalRows()
    {
        await using var factory = new Factory();
        using var admin = await factory.Login("admin", true);
        using var teacher = await Teacher(factory, admin);
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var actorId = await db.Staff.Where(s => s.Role == "admin").Select(s => s.Id).SingleAsync();
        var teacherId = await db.Staff.Where(s => s.Role == "teacher").Select(s => s.Id).SingleAsync();
        var campus = new Campus { Name = "归档测试校区" };
        var schoolClass = new SchoolClass { Name = "归档测试班", CampusId = campus.Id };
        db.AddRange(campus, schoolClass);
        await db.SaveChangesAsync();

        var classUrl = $"/api/admin/catalog/classes/{schoolClass.Id}";
        var staffUrl = $"/api/admin/catalog/staff/{teacherId}";
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.DeleteAsync(classUrl)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.DeleteAsync(staffUrl)).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await admin.DeleteAsync($"/api/admin/catalog/staff/{actorId}")).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await admin.DeleteAsync(classUrl)).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await admin.DeleteAsync(staffUrl)).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await teacher.GetAsync("/api/workspace")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await admin.DeleteAsync(classUrl)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await admin.DeleteAsync(staffUrl)).StatusCode);
        Assert.False(await db.Classes.AnyAsync(c => c.Id == schoolClass.Id));
        Assert.False(await db.Staff.AnyAsync(u => u.Id == teacherId));
        Assert.True((await db.Classes.IgnoreQueryFilters().AsNoTracking().SingleAsync(c => c.Id == schoolClass.Id)).Archived);
        var archivedStaff = await db.Staff.IgnoreQueryFilters().AsNoTracking().SingleAsync(u => u.Id == teacherId);
        Assert.True(archivedStaff.Archived);
        Assert.False(archivedStaff.Enabled);
    }

    [Fact]
    public async Task CampusDeletionProtectsClassesRevokesBindingsAndRetainsHistory()
    {
        await using var f=new Factory();using var admin=await f.Login("admin",true);using var stranger=await Teacher(f,admin);
        var campus=(await Factory.Post(admin,"/api/admin/catalog/campuses",new{name="临时校区",city="济南"})).GetProperty("id").GetGuid();
        var url=$"/api/admin/catalog/campuses/{campus}";
        Assert.Equal(HttpStatusCode.OK,(await admin.PutAsJsonAsync(url,new{name="临时校区改名"})).StatusCode);
        Assert.Equal("济南",(await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog")).GetProperty("campuses").EnumerateArray().Single(c=>c.GetProperty("id").GetGuid()==campus).GetProperty("city").GetString());
        Assert.Equal(HttpStatusCode.Forbidden,(await stranger.DeleteAsync(url)).StatusCode);
        var binding=await Factory.Post(admin,"/api/admin/device-bindings",new{campusId=campus});
        Guid cl;
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();var c=new SchoolClass{CampusId=campus,Name="保留班级"};cl=c.Id;db.Add(c);await db.SaveChangesAsync();}
        Assert.Equal(HttpStatusCode.Conflict,(await admin.DeleteAsync(url)).StatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.True(await db.Campuses.AnyAsync(c=>c.Id==campus));await db.Classes.Where(c=>c.Id==cl).ExecuteUpdateAsync(s=>s.SetProperty(c=>c.Archived,true));}
        Assert.Equal(HttpStatusCode.NoContent,(await admin.DeleteAsync(url)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,(await admin.DeleteAsync(url)).StatusCode);
        using var device=f.CreateClient();Assert.Equal(HttpStatusCode.Forbidden,(await device.PostAsJsonAsync("/agent/bind",new{code=binding.GetProperty("code").GetString(),name="迟到设备"})).StatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.False(await db.Campuses.AnyAsync(c=>c.Id==campus));Assert.True((await db.Campuses.IgnoreQueryFilters().SingleAsync(c=>c.Id==campus)).Archived);Assert.True(await db.Classes.IgnoreQueryFilters().AnyAsync(c=>c.Id==cl));Assert.Single(await db.Audits.Where(a=>a.Action=="campus.delete").ToListAsync());}
    }

    [Theory][InlineData("preparing")][InlineData("queued")][InlineData("claimed")][InlineData("submitting")][InlineData("uncertain")]
    public async Task PrinterDeletionBlocksUnfinishedJobsAndNeverResurrectsAfterHeartbeat(string state)
    {
        await using var f=new Factory();using var admin=await f.Login("admin",true);using var stranger=await Teacher(f,admin);
        var campus=(await Factory.Post(admin,"/api/admin/catalog/campuses",new{name="临时设备校区"})).GetProperty("id").GetGuid();
        var binding=await Factory.Post(admin,"/api/admin/device-bindings",new{campusId=campus});using var device=f.CreateClient();
        var bound=await Factory.Post(device,"/agent/bind",new{code=binding.GetProperty("code").GetString(),name="测试电脑"});var deviceId=bound.GetProperty("id").GetGuid();device.DefaultRequestHeaders.Authorization=new("Bearer",bound.GetProperty("token").GetString());
        await Factory.Post(device,"/agent/heartbeat",new{printers=new[]{new{queueName="test",duplex=true,status="ready"}}});
        Guid printerId,jobId;
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();var p=await db.Printers.SingleAsync();p.Enabled=true;printerId=p.Id;var doc=new ExportDocument{StaffId=await db.Staff.Select(s=>s.Id).FirstAsync()};var job=new PrintJob{DeviceId=deviceId,PrinterId=p.Id,DocumentId=doc.Id,StaffId=doc.StaffId,State=state};jobId=job.Id;db.AddRange(doc,job);await db.SaveChangesAsync();}
        var url=$"/api/admin/printers/{printerId}";
        Assert.Equal(HttpStatusCode.Forbidden,(await stranger.DeleteAsync(url)).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict,(await admin.DeleteAsync($"/api/admin/catalog/campuses/{campus}")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict,(await admin.DeleteAsync(url)).StatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.True((await db.Printers.SingleAsync()).Enabled);Assert.False((await db.Printers.SingleAsync()).Suppressed);await db.PrintJobs.Where(j=>j.Id==jobId).ExecuteUpdateAsync(s=>s.SetProperty(j=>j.State,"submitted"));}
        Assert.Equal(HttpStatusCode.NoContent,(await admin.DeleteAsync(url)).StatusCode);
        await Factory.Post(device,"/agent/heartbeat",new{printers=new[]{new{queueName="test",duplex=true,status="ready"}}});
        Assert.Empty((await admin.GetFromJsonAsync<JsonElement>("/api/printers")).EnumerateArray());
        Assert.Empty((await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog")).GetProperty("printers").EnumerateArray());
        Assert.Equal(HttpStatusCode.NotFound,(await admin.PutAsJsonAsync(url,new{name="复活",enabled=true})).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent,(await admin.DeleteAsync($"/api/admin/catalog/campuses/{campus}")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,(await device.PostAsJsonAsync("/agent/heartbeat",new{printers=Array.Empty<object>()})).StatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal("submitted",(await db.PrintJobs.SingleAsync()).State);Assert.True((await db.Printers.SingleAsync()).Suppressed);Assert.False((await db.Devices.SingleAsync()).Enabled);}
    }
}
