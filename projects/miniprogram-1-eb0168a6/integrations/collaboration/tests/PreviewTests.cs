using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue.Collaboration;
using Xunit;
public class PreviewFactory:AppFactory {
 public readonly List<JsonElement> Calls=[];
 protected override void ConfigureWebHost(IWebHostBuilder builder){base.ConfigureWebHost(builder);builder.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{{"Delivery:Secret","isolated-test-secret"}}));builder.ConfigureServices(s=>s.AddHttpClient("deployer").ConfigurePrimaryHttpMessageHandler(()=>new PreviewHandler(Calls)));}
 class PreviewHandler(List<JsonElement> calls):HttpMessageHandler {
  protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct){var body=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct)).RootElement.Clone();calls.Add(body);return new(HttpStatusCode.OK){Content=request.RequestUri!.AbsolutePath=="/describe"?JsonContent.Create(new{entry="index.html",notes="模拟"}):JsonContent.Create(new{status=200,type="text/html",body=Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("<html><head></head><body><script>const exported = '<html><head></head><body>export</body></html>';</script>preview</body></html>"))})};}
 }
}
public class PreviewTests {
 [Fact]public async Task NewPushSupersedesOnlyQueuedChecksOnTheSameBranch(){
  await using var f=new AppFactory();
  await f.WithDb(async db=>{var r=new Repository{Name="queue",FullName="org/queue",DeploymentKey="queue",Enabled=true,AutomationEnabled=true};db.Repositories.Add(r);await db.SaveChangesAsync();await CollaborationWorker.Queue(db,r,new string('a',40),"task/one","check","teacher");await CollaborationWorker.Queue(db,r,new string('b',40),"task/two","check","teacher");await CollaborationWorker.Queue(db,r,new string('c',40),"task/one","check","teacher");var builds=db.Builds.ToArray();Assert.Equal("superseded",builds.Single(b=>b.Sha==new string('a',40)).State);Assert.Equal("queued",builds.Single(b=>b.Sha==new string('b',40)).State);Assert.Equal("queued",builds.Single(b=>b.Sha==new string('c',40)).State);});
 }

 [Fact]public async Task PreviewPinsTheBuildAndDigestAndRechecksMembershipAndSession(){
  await using var f=new PreviewFactory();using var c=f.Client("owner");var repo=new Repository{Name="preview-test",FullName="org/preview-test",DeploymentKey="preview-test",Enabled=true,AutomationEnabled=true};var p=new Project{Name="测试",OwnerId=FakeIdentity.Owner,RepositoryId=repo.Id};var b=new Build{RepositoryId=repo.Id,Sha=new string('a',40),ArtifactDigest=new string('b',64),State="previewed"};
  await f.WithDb(async db=>{db.AddRange(repo,p,b);await db.SaveChangesAsync();});
  var response=await c.GetAsync($"/api/collaboration/projects/{p.Id}/preview?buildId={b.Id}");Assert.Equal(HttpStatusCode.OK,response.StatusCode);var url=(await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("url").GetString()!;
  await f.WithDb(async db=>{var row=await db.Projects.FindAsync(p.Id);row!.TestingBuildId=Guid.NewGuid();await db.SaveChangesAsync();});
  c.DefaultRequestHeaders.Add("Idempotency-Key","isolated-write-once");c.DefaultRequestHeaders.Add("X-Material-Name","fixture.txt");var preview=await c.GetAsync(url);Assert.Equal("isolated-write-once",f.Calls.Last().GetProperty("idempotencyKey").GetString());Assert.Equal("fixture.txt",f.Calls.Last().GetProperty("materialName").GetString());Assert.Contains("Idempotency-Key",preview.Headers.GetValues("Access-Control-Allow-Headers").Single());Assert.Equal(HttpStatusCode.OK,preview.StatusCode);Assert.Equal(b.Id,f.Calls.Last().GetProperty("buildId").GetGuid());Assert.Equal(b.ArtifactDigest,f.Calls.Last().GetProperty("digest").GetString());Assert.Contains("sandbox allow-scripts allow-forms",preview.Headers.GetValues("Content-Security-Policy").Single());Assert.DoesNotContain("allow-same-origin",preview.Headers.GetValues("Content-Security-Policy").Single());
  var html=await preview.Content.ReadAsStringAsync();Assert.Contains("const exported = '<html><head></head><body>export</body></html>'",html);Assert.Single(System.Text.RegularExpressions.Regex.Matches(html,"Runs in an opaque-origin sandbox"));
  using var outsider=f.Client("outsider");Assert.Equal(HttpStatusCode.Forbidden,(await outsider.GetAsync($"/api/collaboration/projects/{p.Id}/preview?buildId={b.Id}")).StatusCode);
  f.Identity.Users.Remove("owner");Assert.Equal(HttpStatusCode.Unauthorized,(await c.GetAsync(url)).StatusCode);
 }
 [Theory][InlineData("failed")][InlineData("queued")][InlineData("passed")]
 public async Task PreviewRejectsBuildsThatHaveNotCompletedDeployment(string state){
  await using var f=new AppFactory();using var c=f.Client("owner");var r=new Repository{Name="repo",FullName="org/repo",DeploymentKey="repo",Enabled=true};var p=new Project{Name="项目",OwnerId=FakeIdentity.Owner,RepositoryId=r.Id};var b=new Build{RepositoryId=r.Id,State=state};await f.WithDb(async db=>{db.AddRange(r,p,b);await db.SaveChangesAsync();});Assert.Equal(HttpStatusCode.Conflict,(await c.GetAsync($"/api/collaboration/projects/{p.Id}/preview?buildId={b.Id}")).StatusCode);
 }
}
