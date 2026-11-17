using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class ClassCreationTests {
 [Fact] public void OwnershipMigrationMatchesTheModel(){using var db=new DesignTimeData().CreateDbContext([]);Assert.False(db.Database.HasPendingModelChanges());}
 [Fact] public async Task UnboundClassKeepsOwnerAndRequiresExplicitCourseWithRetrySafety() {
  await using var f=new Factory();using var admin=await f.Login("owner-admin",true);
  var campus=(await Factory.Post(admin,"/api/admin/catalog/campuses",new{name="本校区"})).GetProperty("id").GetGuid();
  var course=(await Factory.Post(admin,"/api/admin/catalog/courses",new{name="数学"})).GetProperty("id").GetGuid();
  var clients=new List<HttpClient>();var ids=new List<Guid>();
  foreach(var n in new[]{"甲","乙"}){var id=(await Factory.Post(admin,"/api/admin/catalog/staff",new{name=n})).GetProperty("id").GetGuid();ids.Add(id);var invite=await Factory.Post(admin,$"/api/admin/staff/{id}/invite",new{});var client=await f.Login("owner-"+n);await Factory.Post(client,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});clients.Add(client);}
  using var a=clients[0];using var b=clients[1];
  var input=new{name="初二数学A+上午班",campusId=campus,requestId="no-course"};var created=await Factory.Post(a,"/api/classes",input);var cl=created.GetProperty("id").GetGuid();
  Assert.Equal(cl,(await Factory.Post(a,"/api/classes",input)).GetProperty("id").GetGuid());
  var w=await a.GetFromJsonAsync<JsonElement>("/api/workspace");Assert.Contains(w.GetProperty("classes").EnumerateArray(),x=>x.GetProperty("id").GetGuid()==cl);Assert.Empty(w.GetProperty("assignments").EnumerateArray());
  foreach(var client in new[]{a,admin}){var d=await client.GetFromJsonAsync<JsonElement>($"/api/classes/{cl}");Assert.Empty(d.GetProperty("courses").EnumerateArray());Assert.Empty(d.GetProperty("handouts").EnumerateArray());}
  Assert.Equal(HttpStatusCode.Forbidden,(await b.GetAsync($"/api/classes/{cl}")).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await a.GetAsync($"/api/classes/{cl}?courseId={course}")).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await b.PostAsJsonAsync($"/api/classes/{cl}/courses",new{courseId=course})).StatusCode);
  Assert.Equal(HttpStatusCode.BadRequest,(await a.PostAsJsonAsync($"/api/classes/{cl}/courses",new{courseId=Guid.NewGuid()})).StatusCode);
  await Factory.Post(a,$"/api/classes/{cl}/students/batch",new{names=new[]{"学生甲"},requestId="roster"});
  for(var i=0;i<2;i++)await Factory.Post(a,$"/api/classes/{cl}/courses",new{courseId=course});
  Assert.Equal(HttpStatusCode.OK,(await a.GetAsync($"/api/classes/{cl}/books?courseId={course}")).StatusCode);
  Assert.Equal(cl,(await Factory.Post(a,"/api/classes",input)).GetProperty("id").GetGuid());
  using var services=f.Services.CreateScope();var db=services.ServiceProvider.GetRequiredService<Data>();Assert.Single(await db.Assignments.Where(x=>x.ClassId==cl).ToArrayAsync());Assert.Single(await db.Audits.Where(x=>x.TargetId==cl&&x.Action=="classes.create").ToArrayAsync());Assert.Single(await db.Audits.Where(x=>x.TargetId==cl&&x.Action=="classes.course.add").ToArrayAsync());
 }
 [Fact] public async Task TeacherCanCreateCampusAndDuplicateNamesReuseWithinCity() {
  await using var f=new Factory();using var admin=await f.Login("campus-admin",true);var id=(await Factory.Post(admin,"/api/admin/catalog/staff",new{name="教师"})).GetProperty("id").GetGuid();var invite=await Factory.Post(admin,$"/api/admin/staff/{id}/invite",new{});using var teacher=await f.Login("campus-teacher");await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});
  var input=new{name="新校区",city="济南"};var campus=await Factory.Post(teacher,"/api/campuses",input);var campusId=campus.GetProperty("id").GetGuid();Assert.Equal(campusId,(await Factory.Post(teacher,"/api/campuses",input)).GetProperty("id").GetGuid());
  var w=await teacher.GetFromJsonAsync<JsonElement>("/api/workspace");Assert.Contains(w.GetProperty("campuses").EnumerateArray(),x=>x.GetProperty("id").GetGuid()==campusId);
  Assert.NotEqual(campusId,(await Factory.Post(teacher,"/api/campuses",new{name="新校区",city="广州"})).GetProperty("id").GetGuid());
  Assert.Equal(HttpStatusCode.BadRequest,(await teacher.PostAsJsonAsync("/api/campuses",new{name=" ",city="济南"})).StatusCode);
  Assert.Equal(HttpStatusCode.BadRequest,(await teacher.PostAsJsonAsync("/api/campuses",new{name="第三校区",city=""})).StatusCode);
  using var stranger=await f.Login("unbound-user");Assert.Equal(HttpStatusCode.Unauthorized,(await stranger.PostAsJsonAsync("/api/campuses",input)).StatusCode);
  using var services=f.Services.CreateScope();var db=services.ServiceProvider.GetRequiredService<Data>();Assert.Single(await db.Campuses.Where(x=>x.Id==campusId).ToArrayAsync());Assert.Single(await db.Audits.Where(x=>x.TargetId==campusId).ToArrayAsync());
 }
}
