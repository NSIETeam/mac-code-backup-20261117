using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;
public class CampusSelectionTests {
[Fact]public async Task AllTeachersCanCreateTheirFirstClassAnywhereWithoutReadingOthers(){
await using var f=new Factory();using var admin=await f.Login("campus-root",true);
async Task<JsonElement> Post(string url,object b)=>await Factory.Post(admin,url,b);
var a=(await Post("/api/admin/catalog/campuses",new{name="济南"})).GetProperty("id").GetGuid();var b=(await Post("/api/admin/catalog/campuses",new{name="广州"})).GetProperty("id").GetGuid();
var math=(await Post("/api/admin/catalog/courses",new{name="数学"})).GetProperty("id").GetGuid();var english=(await Post("/api/admin/catalog/courses",new{name="英语"})).GetProperty("id").GetGuid();
var own=(await Post("/api/admin/catalog/classes",new{name="本班",campusId=a})).GetProperty("id").GetGuid();var foreign=(await Post("/api/admin/catalog/classes",new{name="他人班",campusId=b})).GetProperty("id").GetGuid();
var id=(await Post("/api/admin/catalog/staff",new{name="跨校区教师"})).GetProperty("id").GetGuid();await Post("/api/admin/catalog/assignments",new{staffId=id,classId=own,courseId=math});
using var teacher=await f.Login("campus-teacher");var invite=await Post($"/api/admin/staff/{id}/invite",new{});await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});
var body=new{name="广州新班",campusId=b,courseId=math,requestId="new-campus-class"};Assert.Equal(HttpStatusCode.OK,(await teacher.PostAsJsonAsync("/api/classes",body)).StatusCode);
Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PutAsJsonAsync($"/api/admin/catalog/staff/{id}",new{name="跨校区教师",canSelectAnyCampus=true})).StatusCode);
var grant=await admin.PutAsJsonAsync($"/api/admin/catalog/staff/{id}",new{name="跨校区教师",role="teacher",enabled=true,canSelectAnyCampus=true});Assert.Equal(HttpStatusCode.OK,grant.StatusCode);
var w=await teacher.GetFromJsonAsync<JsonElement>("/api/workspace");Assert.Equal(2,w.GetProperty("campuses").GetArrayLength());Assert.Equal(2,w.GetProperty("classes").GetArrayLength());Assert.Equal(2,w.GetProperty("courses").GetArrayLength());
var created=await Factory.Post(teacher,"/api/classes",body);Assert.Equal(b,created.GetProperty("campusId").GetGuid());
Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"/api/classes/{foreign}")).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"/api/classes/{foreign}/students")).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync("/api/admin/catalog")).StatusCode);
Assert.Equal(HttpStatusCode.OK,(await teacher.PostAsJsonAsync("/api/classes",new{name="自己的英语新班",campusId=b,courseId=english,requestId="foreign-course"})).StatusCode);
// A brand new teacher needs no placeholder class or teaching assignment to get started.
var emptyId=(await Post("/api/admin/catalog/staff",new{name="新教师"})).GetProperty("id").GetGuid();using var newcomer=await f.Login("empty-teacher");var code=await Post($"/api/admin/staff/{emptyId}/invite",new{});await Factory.Post(newcomer,"/api/auth/bind",new{code=code.GetProperty("code").GetString()});
var empty=await newcomer.GetFromJsonAsync<JsonElement>("/api/workspace");Assert.Empty(empty.GetProperty("classes").EnumerateArray());Assert.Equal(2,empty.GetProperty("campuses").GetArrayLength());Assert.Equal(2,empty.GetProperty("courses").GetArrayLength());
var first=await Factory.Post(newcomer,"/api/classes",new{name="首个班级",campusId=b,courseId=math,requestId="first-class"});Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync("/api/classes/"+first.GetProperty("id").GetGuid())).StatusCode);
// Older clients editing the name must not accidentally revoke the independent permission.
await admin.PutAsJsonAsync($"/api/admin/catalog/staff/{id}",new{name="更名",role="teacher",enabled=true});var catalog=await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog");Assert.True(catalog.GetProperty("staff").EnumerateArray().Single(s=>s.GetProperty("id").GetGuid()==id).GetProperty("canSelectAnyCampus").GetBoolean());
}}
