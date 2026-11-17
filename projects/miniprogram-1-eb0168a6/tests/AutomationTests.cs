using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class AutomationTests
{
    static async Task<HttpClient> Key(Factory f, HttpClient owner, params string[] scopes)
    {
        var created = await Factory.Post(owner, "/api/automation/credentials", new { name="Agent 验收", scopes, expiresInDays=1 });
        var client = f.CreateClient(); client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", created.GetProperty("token").GetString()); return client;
    }
    static async Task<(SchoolClass Cl, Student Student, Handout Handout, Question Q, Question Q2, Printer Printer, ExportDocument D1, ExportDocument D2)> Seed(Factory f, HttpClient admin)
    {
        var me = await admin.GetFromJsonAsync<JsonElement>("/api/me"); var staffId = me.GetProperty("id").GetGuid();
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus{Name="隔离验收校区"}; var course = new Course{Name="数学"}; var cl = new SchoolClass{Name="Agent 班",CampusId=campus.Id};
        var student = new Student{Name="学生",Code="01",ClassId=cl.Id}; var lecture = new Lecture{ClassId=cl.Id,CourseId=course.Id,Sequence=1,Title="测试"};
        var h = new Handout{LectureId=lecture.Id,State="published"}; var q = new Question{HandoutId=h.Id,Label="例1",Order=1}; var q2 = new Question{HandoutId=h.Id,Label="练1",Order=2};
        var device = new Device{CampusId=campus.Id,Name="隔离设备",TokenHash=Secrets.New()}; var printer = new Printer{DeviceId=device.Id,CampusId=campus.Id,Name="不出纸测试队列"};
        ExportDocument Doc() => new(){BatchId=Guid.NewGuid(),StaffId=staffId,ClassId=cl.Id,CourseId=course.Id,StudentId=student.Id,StudentName=student.Name,State="ready",PdfKey="fixture.pdf"};
        var d1=Doc(); var d2=Doc(); db.AddRange(campus,course,cl,student,lecture,h,q,q2,device,printer,d1,d2); await db.SaveChangesAsync();
        return(cl,student,h,q,q2,printer,d1,d2);
    }
    [Fact] public async Task ContractContainsEveryAllowedRouteAndRealRequestSchemas()
    {
        await using var f = new Factory(); using var client=f.CreateClient();
        var response=await client.GetAsync("/openapi/agent-v1.json"); Assert.True(response.IsSuccessStatusCode,await response.Content.ReadAsStringAsync());
        var text=await response.Content.ReadAsStringAsync(); var doc=JsonDocument.Parse(text).RootElement; var paths=doc.GetProperty("paths");
        Assert.Equal(AutomationCatalog.Operations.Length, paths.EnumerateObject().Sum(x=>x.Value.EnumerateObject().Count()));
        foreach(var operation in AutomationCatalog.Operations) {
            var defined=paths.GetProperty(operation.Path).GetProperty(operation.Method.ToLowerInvariant()); Assert.Equal(operation.Id,defined.GetProperty("operationId").GetString());
            Assert.Equal(operation.Retry,defined.GetProperty("x-agent").GetProperty("retry").GetString());
        }
        Assert.False(paths.TryGetProperty("/agent/claim",out _)); Assert.False(paths.TryGetProperty("/api/admin/learning-model",out _));
        Assert.True(paths.GetProperty("/api/print-jobs").GetProperty("post").TryGetProperty("requestBody",out _));
        Assert.True(paths.GetProperty("/api/classes/{classId}/books").GetProperty("post").GetProperty("requestBody").GetProperty("content").TryGetProperty("multipart/form-data",out _));
        var output=Environment.GetEnvironmentVariable("SIYUE_AGENT_CONTRACT_OUTPUT"); if(output!=null) { Directory.CreateDirectory(output); await File.WriteAllTextAsync(Path.Combine(output,"openapi.json"),text); await File.WriteAllTextAsync(Path.Combine(output,"operations.json"),Json.Write(AutomationCatalog.Operations)); }
    }
    [Fact] public async Task CredentialsAreScopedHashedRevocableAndCannotMintMoreCredentials()
    {
        await using var f=new Factory(); using var admin=await f.Login("credential-admin",true); using var reader=await Key(f,admin,"read");
        Assert.Equal(HttpStatusCode.OK,(await reader.GetAsync("/api/workspace")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,(await reader.PostAsJsonAsync("/api/classes",new{name="班",campusId=Guid.NewGuid(),courseId=Guid.NewGuid(),requestId="x"})).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,(await reader.PostAsJsonAsync("/api/automation/credentials",new{name="提权",scopes=new[]{"organization:write"}})).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,(await reader.GetAsync("/api/admin/learning-model")).StatusCode);
        var keys=await admin.GetFromJsonAsync<JsonElement>("/api/automation/credentials"); Assert.DoesNotContain("tokenHash",keys.GetRawText()); Assert.DoesNotContain("sya_",keys.GetRawText());
        using(var scope=f.Services.CreateScope()) { var row=await scope.ServiceProvider.GetRequiredService<Data>().AutomationCredentials.SingleAsync(); Assert.Equal(Secrets.Hash(reader.DefaultRequestHeaders.Authorization!.Parameter!),row.TokenHash); }
        Assert.Equal(HttpStatusCode.NoContent,(await admin.DeleteAsync("/api/automation/credentials/"+keys[0].GetProperty("id").GetGuid())).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,(await reader.GetAsync("/api/workspace")).StatusCode);
        using var expired=await Key(f,admin,"read"); using(var scope=f.Services.CreateScope())await scope.ServiceProvider.GetRequiredService<Data>().AutomationCredentials.ExecuteUpdateAsync(x=>x.SetProperty(v=>v.ExpiresAt,Clock.Now.AddSeconds(-1)));
        Assert.Equal(HttpStatusCode.Unauthorized,(await expired.GetAsync("/api/workspace")).StatusCode);
    }
    [Fact] public async Task TeacherKeyCannotCrossClassesOrKeepAccessAfterAssignmentRevocation()
    {
        await using var f=new Factory(); using var admin=await f.Login("scope-admin",true); var data=await Seed(f,admin);
        var staff=await Factory.Post(admin,"/api/admin/catalog/staff",new{name="接入教师"}); var sid=staff.GetProperty("id").GetGuid();
        var invite=await Factory.Post(admin,$"/api/admin/staff/{sid}/invite",new{}); using var teacher=await f.Login("scope-teacher"); await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});
        Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync("/api/automation/credentials",new{name="越权",scopes=new[]{"organization:write"}})).StatusCode);
        using var key=await Key(f,teacher,"read","teaching:write");
        var path=$"/api/automation/v1/tasks/export/{data.D1.Id}";
        Assert.Equal(HttpStatusCode.Forbidden,(await key.GetAsync(path)).StatusCode);
        var assignment=await Factory.Post(admin,"/api/admin/catalog/assignments",new{staffId=sid,classId=data.Cl.Id,courseId=data.D1.CourseId});
        Assert.Equal(HttpStatusCode.OK,(await key.GetAsync(path)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,(await key.GetAsync("/api/admin/catalog")).StatusCode);
        await admin.DeleteAsync("/api/admin/assignments/"+assignment.GetProperty("id").GetGuid()); Assert.Equal(HttpStatusCode.Forbidden,(await key.GetAsync(path)).StatusCode);
        using(var scope=f.Services.CreateScope()) await scope.ServiceProvider.GetRequiredService<Data>().Staff.Where(x=>x.Id==sid).ExecuteUpdateAsync(x=>x.SetProperty(s=>s.Enabled,false));
        Assert.Equal(HttpStatusCode.Unauthorized,(await key.GetAsync("/api/workspace")).StatusCode);
    }
    [Fact] public async Task AdmissionIsBoundedAndReturnsRetryAfterWithoutChangingBusinessData()
    {
        await using var f=new Factory(); using var admin=await f.Login("limit-admin",true); using var key=await Key(f,admin,"read");
        var me=await admin.GetFromJsonAsync<JsonElement>("/api/me"); var admission=f.Services.GetRequiredService<AutomationAdmission>();
        var leases=Enumerable.Range(0,admission.OwnerConcurrency).Select(_=>admission.Owner(me.GetProperty("id").GetGuid())).ToArray();
        try { var r=await key.GetAsync("/api/workspace"); Assert.Equal(HttpStatusCode.TooManyRequests,r.StatusCode); Assert.Equal(TimeSpan.FromSeconds(2),r.Headers.RetryAfter!.Delta); Assert.Equal("RATE_LIMITED",(await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString()); }
        finally { foreach(var lease in leases)lease.Dispose(); }
        Assert.Equal(HttpStatusCode.OK,(await key.GetAsync("/api/workspace")).StatusCode);
        var global=Enumerable.Range(0,admission.GlobalConcurrency).Select(_=>admission.Global()).ToArray();
        try { Assert.Equal(HttpStatusCode.TooManyRequests,(await key.GetAsync("/api/workspace")).StatusCode); Assert.Equal(HttpStatusCode.OK,(await admin.GetAsync("/api/workspace")).StatusCode); }
        finally {foreach(var lease in global)lease.Dispose();}
    }
    [Fact] public async Task ConcurrentPrintRetriesProduceOneBatchAndDifferentPayloadConflictsEvenForDisjointDocuments()
    {
        await using var f=new Factory(); using var admin=await f.Login("print-admin",true); var d=await Seed(f,admin); using var key=await Key(f,admin,"read","printing:write");
        object Input(Guid doc,string requestKey)=>new{documentIds=new[]{doc},printerId=d.Printer.Id,copies=1,duplex=false,requestKey};
        // Eight overlapping calls exercise database arbitration, not a process-local lock.
        var responses=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>key.PostAsJsonAsync("/api/print-jobs",Input(d.D1.Id,"identical-print"))));
        foreach(var r in responses)Assert.True(r.IsSuccessStatusCode,await r.Content.ReadAsStringAsync());
        var ids=new List<Guid>();foreach(var r in responses)ids.Add((await r.Content.ReadFromJsonAsync<JsonElement>())[0].GetProperty("id").GetGuid()); Assert.Single(ids.Distinct());
        var competing=await Task.WhenAll(key.PostAsJsonAsync("/api/print-jobs",Input(d.D1.Id,"conflicting-print")),key.PostAsJsonAsync("/api/print-jobs",Input(d.D2.Id,"conflicting-print")));
        Assert.Single(competing,x=>x.StatusCode==HttpStatusCode.OK);Assert.Single(competing,x=>x.StatusCode==HttpStatusCode.Conflict);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(2,await db.PrintJobs.CountAsync());Assert.Equal(2,await db.PrintRequests.CountAsync());
        var job=await db.PrintJobs.FirstAsync();job.State="uncertain";await db.SaveChangesAsync();
        var state=await key.GetFromJsonAsync<JsonElement>($"/api/automation/v1/tasks/print/{job.Id}");Assert.True(state.GetProperty("needsReview").GetBoolean());Assert.True(state.GetProperty("terminal").GetBoolean());
        Assert.Equal(HttpStatusCode.Conflict,(await key.PostAsJsonAsync($"/api/print-jobs/{job.Id}/reprint",new{requestKey="no-implicit-reprint"})).StatusCode);
    }
    [Fact] public async Task ConcurrentMarksConflictAndExportsReuseDurableTasksWithoutCreatingPreviewGrants()
    {
        await using var f=new Factory();using var admin=await f.Login("workflow-admin",true);var d=await Seed(f,admin);using var key=await Key(f,admin,"read","teaching:write","documents:write");
        var session=await Factory.Post(key,"/api/class-sessions",new{classId=d.Cl.Id,handoutId=d.Handout.Id,lessonDate="2026-10-29",requestKey="same-session"});var id=session.GetProperty("id").GetGuid();
        var path=$"/api/class-sessions/{id}/students/{d.Student.Id}";
        var mutations=await Task.WhenAll(key.PutAsJsonAsync(path,new{revision=0,questionIds=new[]{d.Q.Id}}),key.PutAsJsonAsync(path,new{revision=0,questionIds=new[]{d.Q2.Id}}));
        Assert.Single(mutations,x=>x.IsSuccessStatusCode);Assert.Single(mutations,x=>x.StatusCode==HttpStatusCode.Conflict);
        var read=await key.GetFromJsonAsync<JsonElement>($"/api/automation/v1/sessions/{id}");var mark=read.GetProperty("marks")[0];Assert.Equal(1,mark.GetProperty("revision").GetInt32());
        Assert.True((await key.PutAsJsonAsync(path,new{revision=0,questionIds=mark.GetProperty("questionIds")})).IsSuccessStatusCode);
        var exports=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>key.PostAsJsonAsync($"/api/class-sessions/{id}/exports",new{studentIds=new[]{d.Student.Id},layout="analysis",requestKey="stable-export-key"})));
        foreach(var e in exports)Assert.True(e.IsSuccessStatusCode,await e.Content.ReadAsStringAsync());
        var doc=(await exports[0].Content.ReadFromJsonAsync<JsonElement>()).GetProperty("documents")[0].GetProperty("id").GetGuid();
        var status=await key.GetFromJsonAsync<JsonElement>($"/api/automation/v1/tasks/export/{doc}");Assert.Equal("queued",status.GetProperty("state").GetString());Assert.False(status.GetProperty("terminal").GetBoolean());
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(1,await db.Work.CountAsync(x=>x.EntityId==doc));Assert.Equal(0,await db.Grants.CountAsync());
        await db.Documents.Where(x=>x.Id==doc).ExecuteUpdateAsync(x=>x.SetProperty(v=>v.State,"failed"));
        Assert.Equal("failed",(await key.GetFromJsonAsync<JsonElement>($"/api/automation/v1/tasks/export/{doc}")).GetProperty("state").GetString());
        Assert.Equal("ready",(await key.GetFromJsonAsync<JsonElement>($"/api/automation/v1/tasks/export/{d.D1.Id}")).GetProperty("state").GetString());
    }
    [Fact] public async Task UploadAndRateLimitsRejectBeforeSideEffectsAndClassListPaginates()
    {
        await using var f=new Factory(); using var admin=await f.Login("bounded-admin",true); var data=await Seed(f,admin); using var key=await Key(f,admin,"read","teaching:write");
        using(var scope=f.Services.CreateScope()) {var db=scope.ServiceProvider.GetRequiredService<Data>();db.Classes.AddRange(Enumerable.Range(0,3).Select(i=>new SchoolClass{Name="分页班"+i,CampusId=data.Cl.CampusId}));await db.SaveChangesAsync();}
        var first=await key.GetFromJsonAsync<JsonElement>("/api/automation/v1/classes?query="+Uri.EscapeDataString("分页班")+"&page=1&pageSize=2");
        var second=await key.GetFromJsonAsync<JsonElement>("/api/automation/v1/classes?query="+Uri.EscapeDataString("分页班")+"&page=2&pageSize=2");
        Assert.True(first.GetProperty("hasMore").GetBoolean());Assert.False(second.GetProperty("hasMore").GetBoolean());Assert.Equal(2,first.GetProperty("items").GetArrayLength());Assert.Equal(1,second.GetProperty("items").GetArrayLength());
        Assert.Equal(HttpStatusCode.BadRequest,(await key.GetAsync("/api/automation/v1/classes?pageSize=101")).StatusCode);
        var admission=f.Services.GetRequiredService<AutomationAdmission>();var leases=Enumerable.Range(0,admission.UploadConcurrency).Select(_=>admission.Upload()).ToArray();
        try {using var form=new MultipartFormDataContent();form.Add(new ByteArrayContent([1,2,3]),"file","fake.pdf");Assert.Equal(HttpStatusCode.TooManyRequests,(await key.PostAsync($"/api/lectures/{data.Handout.LectureId}/handouts",form)).StatusCode);}
        finally{foreach(var lease in leases)lease.Dispose();}
        using(var scope=f.Services.CreateScope())Assert.Equal(1,await scope.ServiceProvider.GetRequiredService<Data>().Handouts.CountAsync());
        var me=await admin.GetFromJsonAsync<JsonElement>("/api/me");for(var i=0;i<admission.OwnerBurst;i++){using var lease=admission.Rate(me.GetProperty("id").GetGuid());}
        Assert.Equal(HttpStatusCode.TooManyRequests,(await key.GetAsync("/api/workspace")).StatusCode);
    }

}
