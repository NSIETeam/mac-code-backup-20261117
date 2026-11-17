using System.Net;
using System.Net.Http.Json;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.EntityFrameworkCore;
using Siyue.Collaboration;
using Xunit;

public class FakeIdentity:IIdentityDirectory{
 public static readonly Guid Admin=Guid.Parse("00000000-0000-0000-0000-000000000001"),Owner=Guid.Parse("00000000-0000-0000-0000-000000000002"),Peer=Guid.Parse("00000000-0000-0000-0000-000000000003"),Outsider=Guid.Parse("00000000-0000-0000-0000-000000000004");
 public readonly Dictionary<string,Person> Users=new(){["admin"]=new(Admin,"管理员","admin","admin",true),["owner"]=new(Owner,"张老师","teacher","zhang",true),["peer"]=new(Peer,"张老师","teacher","zhang2",true),["outsider"]=new(Outsider,"李老师","teacher","li",true)};
 public Task<Person?> Session(string hash,CancellationToken ct=default)=>Task.FromResult(Users.Where(x=>Check.Hash(x.Key)==hash&&x.Value.Enabled).Select(x=>x.Value).FirstOrDefault());
 public Task<Person[]> People(CancellationToken ct=default)=>Task.FromResult(Users.Values.ToArray());
}
public class AppFactory(string pathBase=""):WebApplicationFactory<Program>{
 public readonly string DirectoryPath=Path.Combine(Path.GetTempPath(),"siyue-collaboration-tests-"+Guid.NewGuid().ToString("N"));public readonly FakeIdentity Identity=new();
 protected override void ConfigureWebHost(IWebHostBuilder builder){Directory.CreateDirectory(DirectoryPath);builder.UseEnvironment("Testing");builder.UseSetting("PathBase",pathBase);builder.UseSetting("Oidc:Issuer","https://localhost"+pathBase+"/api/collaboration/oidc/");builder.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{{"ConnectionStrings:Collaboration","Data Source="+Path.Combine(DirectoryPath,"tests.db")},{"PathBase",pathBase},{"Oidc:Issuer","https://localhost"+pathBase+"/api/collaboration/oidc/"},{"Gitea:WebhookSecret","test-webhook-secret"},{"Attachments:Directory",Path.Combine(DirectoryPath,"attachments")}}));builder.ConfigureServices(s=>{s.RemoveAll<IIdentityDirectory>();s.AddSingleton<IIdentityDirectory>(Identity);});}
 public HttpClient Client(string name){var c=CreateClient(new(){BaseAddress=new Uri("https://localhost"),AllowAutoRedirect=false});c.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",name);return c;}
 public async Task WithDb(Func<CollaborationDb,Task> fn){using var scope=Services.CreateScope();await fn(scope.ServiceProvider.GetRequiredService<CollaborationDb>());}
}
public class CollaborationTests{
 const string Api="/api/collaboration";
 static async Task<JsonElement> Post(HttpClient c,string url,object body){var r=await c.PostAsJsonAsync(url,body);Assert.True(r.IsSuccessStatusCode,await r.Content.ReadAsStringAsync());return r.StatusCode==HttpStatusCode.NoContent?default:await r.Content.ReadFromJsonAsync<JsonElement>();}
 static async Task<Guid> Project(HttpClient owner,bool confidential=false){var result=await Post(owner,Api+"/projects",new{name="协作验收",goal="业务目标",approach="测试方案",department="教研",summary="公开进度",confidential});return result.GetProperty("id").GetGuid();}
 [Fact]
 public async Task UnreadCountUsesTheSignedInUsersVisibleNotifications()
 {
  await using var factory=new AppFactory();
  using var owner=factory.Client("owner");
  using var peer=factory.Client("peer");
  var now=DateTime.UtcNow;
  await factory.WithDb(async db=>{
   for(var i=0;i<201;i++)db.Notifications.Add(new Notification {
    StaffId=FakeIdentity.Owner,EventKey=$"notice-{i}",CreatedAt=now.AddSeconds(i),
    ReadAt=i is 0 or 200?null:now
   });
   db.Notifications.Add(new Notification {StaffId=FakeIdentity.Peer,EventKey="peer-notice"});
   await db.SaveChangesAsync();
  });
  var path=Api+"/notifications/unread-count";
  Assert.Equal(1,(await owner.GetFromJsonAsync<JsonElement>(path)).GetProperty("count").GetInt32());
  var notes=await owner.GetFromJsonAsync<JsonElement>(Api+"/notifications");
  await Post(owner,Api+"/notifications/"+notes[0].GetProperty("id").GetGuid()+"/read",new {});
  Assert.Equal(0,(await owner.GetFromJsonAsync<JsonElement>(path)).GetProperty("count").GetInt32());
  Assert.Equal(1,(await peer.GetFromJsonAsync<JsonElement>(path)).GetProperty("count").GetInt32());
  using var anonymous=factory.CreateClient();
  Assert.Equal(HttpStatusCode.Unauthorized,(await anonymous.GetAsync(path)).StatusCode);
 }
 [Fact]
 public async Task SharedInboxAcceptsTheLabSessionAndKeepsConfidentialNamesPrivate()
 {
  await using var factory=new AppFactory();
  using var owner=factory.Client("owner");
  var project=await Project(owner,true);
  await factory.WithDb(async db=>{
   db.Notifications.Add(new Notification {StaffId=FakeIdentity.Outsider,ProjectId=project,EventKey="private"});
   await db.SaveChangesAsync();
  });
  using var cookie=factory.CreateClient();
  cookie.DefaultRequestHeaders.Add("Cookie","siyue_print_session=outsider");
  Assert.Equal(HttpStatusCode.Unauthorized,(await cookie.GetAsync(Api+"/notifications")).StatusCode);
  cookie.DefaultRequestHeaders.Add("X-Siyue-Inbox","1");
  Assert.Equal(HttpStatusCode.Unauthorized,(await cookie.GetAsync(Api+"/projects")).StatusCode);
  var notes=await cookie.GetFromJsonAsync<JsonElement>(Api+"/notifications");
  var note=Assert.Single(notes.EnumerateArray());
  Assert.Equal(JsonValueKind.Null,note.GetProperty("projectName").ValueKind);
  await Post(cookie,Api+"/notifications/"+note.GetProperty("id").GetGuid()+"/read",new {});
  Assert.Equal(0,(await cookie.GetFromJsonAsync<JsonElement>(Api+"/notifications/unread-count")).GetProperty("count").GetInt32());
 }
 [Fact]public async Task NodeActionsThreadRepliesAndRemindersStayWithinProjectAndKeepRevisions(){
  await using var f=new AppFactory();using var owner=f.Client("owner");using var peer=f.Client("peer");using var outsider=f.Client("outsider");var p=await Project(owner);var other=await Project(owner);
  await Post(owner,$"{Api}/projects/{p}/members",new{staffId=FakeIdentity.Peer});
  var task=await Post(owner,$"{Api}/projects/{p}/tasks",new{title="原型验证",ownerId=FakeIdentity.Peer});var tid=task.GetProperty("id").GetGuid();var req=Guid.NewGuid().ToString();
  var action=await Post(owner,$"{Api}/projects/{p}/actions",new{title="11月16日试用",taskId=tid,plannedOn="2026-11-16",requestId=req});var aid=action.GetProperty("id").GetGuid();
  Assert.Equal(aid,(await Post(owner,$"{Api}/projects/{p}/actions",new{title="11月16日试用",taskId=tid,requestId=req})).GetProperty("id").GetGuid());
  Assert.Equal(HttpStatusCode.Forbidden,(await outsider.PostAsJsonAsync($"{Api}/projects/{p}/actions",new{title="越权",requestId=Guid.NewGuid().ToString()})).StatusCode);
  var body=new{title="更新动作",taskId=tid,state="review",revision=1,body=$"请 @[张老师](staff:{FakeIdentity.Peer}) 查看"};Assert.Equal(HttpStatusCode.OK,(await owner.PutAsJsonAsync($"{Api}/projects/{p}/actions/{aid}",body)).StatusCode);Assert.Equal(HttpStatusCode.Conflict,(await owner.PutAsJsonAsync($"{Api}/projects/{p}/actions/{aid}",body)).StatusCode);
  var comment=await Post(peer,$"{Api}/projects/{p}/progress",new{body="试用反馈",actionId=aid,taskId=tid,requestId=Guid.NewGuid().ToString()});
  await Post(owner,$"{Api}/projects/{p}/progress",new{body="收到反馈",actionId=aid,parentId=comment.GetProperty("id").GetGuid(),requestId=Guid.NewGuid().ToString()});
  Assert.Equal(HttpStatusCode.NotFound,(await owner.PostAsJsonAsync($"{Api}/projects/{other}/progress",new{body="跨项目回复",actionId=aid,requestId=Guid.NewGuid().ToString()})).StatusCode);
  var different=await Post(owner,$"{Api}/projects/{p}/actions",new{title="其他节点",requestId=Guid.NewGuid().ToString()});Assert.Equal(HttpStatusCode.BadRequest,(await owner.PostAsJsonAsync($"{Api}/projects/{p}/progress",new{body="跨节点回复",actionId=different.GetProperty("id").GetGuid(),parentId=comment.GetProperty("id").GetGuid(),requestId=Guid.NewGuid().ToString()})).StatusCode);
  var reminder=new{staffIds=new[]{FakeIdentity.Peer},requestId=Guid.NewGuid().ToString(),message="请补充试用结果"};await Post(owner,$"{Api}/projects/{p}/actions/{aid}/remind",reminder);await Post(owner,$"{Api}/projects/{p}/actions/{aid}/remind",reminder);
  var notes=await peer.GetFromJsonAsync<JsonElement>(Api+"/notifications");Assert.Single(notes.EnumerateArray(),n=>n.GetProperty("kind").GetString()=="action_reminder");Assert.Contains(notes.EnumerateArray(),n=>n.GetProperty("kind").GetString()=="action_reply");
  var detail=await owner.GetFromJsonAsync<JsonElement>($"{Api}/projects/{p}");Assert.Equal(2,detail.GetProperty("actions").GetArrayLength());Assert.Equal(2,detail.GetProperty("progress").GetArrayLength());
  Assert.Equal(HttpStatusCode.BadRequest,(await owner.PostAsJsonAsync($"{Api}/projects/{p}/actions",new{title="其他代码",kind="code",repositoryId=Guid.NewGuid(),requestId=Guid.NewGuid().ToString()})).StatusCode);
 }
 [Fact]public async Task VerifiedGitPushCreatesOneTaskActionEvenBeforeBuildAutomation(){
  await using var f=new AppFactory();using var owner=f.Client("owner");var p=await Project(owner);var t=await Post(owner,$"{Api}/projects/{p}/tasks",new{title="代码动作",ownerId=FakeIdentity.Owner});var tid=t.GetProperty("id").GetGuid();
  await f.WithDb(async db=>{var repo=new Repository{Name="code",FullName="siyue/code",Enabled=true};db.Repositories.Add(repo);(await db.Projects.FindAsync(p))!.RepositoryId=repo.Id;await db.SaveChangesAsync();var payload=JsonSerializer.Serialize(new{repository=new{full_name=repo.FullName},sender=new{login=Gitea.Login(FakeIdentity.Owner)},@ref="refs/heads/task/"+tid,after=new string('b',40)});await CollaborationWorker.ProcessEvent(db,new(){Kind="push",Payload=payload});await CollaborationWorker.ProcessEvent(db,new(){Kind="push",Payload=payload});var action=Assert.Single(await db.TaskActions.ToArrayAsync());Assert.Equal(tid,action.TaskId);Assert.Equal(FakeIdentity.Owner,action.AuthorId);Assert.Equal("code",action.Kind);Assert.Empty(await db.Builds.ToArrayAsync());Assert.Empty(await db.Acceptances.ToArrayAsync());Assert.Empty(await db.Releases.ToArrayAsync());});
 }
 [Fact]public async Task SourceHostingDoesNotQueueBuildUntilAutomationIsConfigured(){
  await using var f=new AppFactory();using var owner=f.Client("owner");await Project(owner);await f.WithDb(async db=>{var repo=new Repository{Name="source",AutomationEnabled=false};db.Repositories.Add(repo);await db.SaveChangesAsync();await CollaborationWorker.Queue(db,repo,new string('a',40),"main","main","import");Assert.Empty(await db.Builds.ToArrayAsync());repo.AutomationEnabled=true;await db.SaveChangesAsync();await CollaborationWorker.Queue(db,repo,new string('a',40),"main","main","import");Assert.Single(await db.Builds.ToArrayAsync());});
 }
 [Fact]public async Task ImportedUnassignedProjectCanBeEditedAndAssignedWithoutInventingAnOwner(){
  await using var f=new AppFactory();using var admin=f.Client("admin");using var peer=f.Client("peer");var p=new Project{Name="原表项目",OwnerId=null};
  await f.WithDb(async db=>{db.Projects.Add(p);await db.SaveChangesAsync();});
  var rows=await peer.GetFromJsonAsync<JsonElement>(Api+"/projects");Assert.Equal(JsonValueKind.Null,rows[0].GetProperty("ownerId").ValueKind);
  Assert.Equal(HttpStatusCode.Forbidden,(await peer.GetAsync($"{Api}/projects/{p.Id}")).StatusCode);
  await Post(peer,$"{Api}/projects/{p.Id}/join-requests",new{});await Post(peer,$"{Api}/projects/{p.Id}/join-requests",new{});
  var notices=await admin.GetFromJsonAsync<JsonElement>(Api+"/notifications");Assert.Single(notices.EnumerateArray());
  var task=await Post(admin,$"{Api}/projects/{p.Id}/tasks",new{title="核验原表成果",ownerId=(Guid?)null,state="review",kind="milestone"});var tid=task.GetProperty("id").GetGuid();
  await Post(admin,$"{Api}/projects/{p.Id}/tasks",new{title="A 后续任务",ownerId=(Guid?)null,state="planned",kind="milestone"});
  var summary=await admin.GetFromJsonAsync<JsonElement>(Api+"/projects");Assert.Equal("核验原表成果",summary[0].GetProperty("nextTitle").GetString());
  Assert.Equal(JsonValueKind.Null,task.GetProperty("ownerId").ValueKind);Assert.Equal(JsonValueKind.Null,task.GetProperty("completedAt").ValueKind);
  var edit=await admin.PutAsJsonAsync($"{Api}/projects/{p.Id}/tasks/{tid}",new{title="补充验收要求",ownerId=(Guid?)null,state="review",revision=1});Assert.Equal(HttpStatusCode.OK,edit.StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await peer.PostAsJsonAsync($"{Api}/projects/{p.Id}/owner",new{staffId=FakeIdentity.Peer})).StatusCode);
  await Post(admin,$"{Api}/projects/{p.Id}/members",new{staffId=FakeIdentity.Peer});await Post(admin,$"{Api}/projects/{p.Id}/owner",new{staffId=FakeIdentity.Peer});
  var detail=await peer.GetFromJsonAsync<JsonElement>($"{Api}/projects/{p.Id}");Assert.True(detail.GetProperty("canManage").GetBoolean());Assert.Equal(FakeIdentity.Peer,detail.GetProperty("project").GetProperty("ownerId").GetGuid());
 }
 [Fact]public async Task PublicSummariesNeverExposePrivateDetailsAndConfidentialProjectsAreHidden(){
  await using var f=new AppFactory();using var owner=f.Client("owner");using var outsider=f.Client("outsider");var p=await Project(owner);var hidden=await Project(owner,true);
  var rows=await outsider.GetFromJsonAsync<JsonElement>(Api+"/projects");Assert.Single(rows.EnumerateArray());Assert.False(rows[0].TryGetProperty("approach",out _));Assert.False(rows[0].TryGetProperty("repositoryId",out _));Assert.Equal(HttpStatusCode.Forbidden,(await outsider.GetAsync($"{Api}/projects/{p}")).StatusCode);Assert.Equal(HttpStatusCode.NotFound,(await outsider.GetAsync($"{Api}/projects/{hidden}")).StatusCode);
  await Post(outsider,$"{Api}/projects/{p}/join-requests",new{});await Post(outsider,$"{Api}/projects/{p}/join-requests",new{});var notes=await owner.GetFromJsonAsync<JsonElement>(Api+"/notifications");Assert.Single(notes.EnumerateArray());
 }
 [Fact]public async Task MembersInvitePeersButCannotElevatePublishOrInviteIntoConfidentialProject(){
  await using var f=new AppFactory();using var owner=f.Client("owner");using var peer=f.Client("peer");var p=await Project(owner);
  await Post(owner,$"{Api}/projects/{p}/members",new{staffId=FakeIdentity.Peer});await Post(peer,$"{Api}/projects/{p}/members",new{staffId=FakeIdentity.Outsider,role="admin"});
  var d=await peer.GetFromJsonAsync<JsonElement>($"{Api}/projects/{p}");Assert.All(d.GetProperty("members").EnumerateArray(),m=>Assert.NotEqual("admin",m.GetProperty("role").GetString()));Assert.False(d.GetProperty("canManage").GetBoolean());
  Assert.Equal(HttpStatusCode.Forbidden,(await peer.PostAsJsonAsync($"{Api}/projects/{p}/releases/{Guid.NewGuid()}/publish",new{})).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await peer.DeleteAsync($"{Api}/projects/{p}/members/{FakeIdentity.Outsider}")).StatusCode);
  var secret=await Project(owner,true);await Post(owner,$"{Api}/projects/{secret}/members",new{staffId=FakeIdentity.Peer});Assert.Equal(HttpStatusCode.Forbidden,(await peer.PostAsJsonAsync($"{Api}/projects/{secret}/members",new{staffId=FakeIdentity.Outsider})).StatusCode);
 }
 [Fact]public async Task OptimisticRevisionStopsLostUpdatesAndBlockersRequireAnAssignee(){
  await using var f=new AppFactory();using var owner=f.Client("owner");var p=await Project(owner);var body=new{title="检查任务",ownerId=FakeIdentity.Owner,description="开始",criteria="能访问",state="planned"};var task=await Post(owner,$"{Api}/projects/{p}/tasks",body);var id=task.GetProperty("id").GetGuid();
  var update=new{title="修改",ownerId=FakeIdentity.Owner,revision=1,state="in_progress"};Assert.Equal(HttpStatusCode.OK,(await owner.PutAsJsonAsync($"{Api}/projects/{p}/tasks/{id}",update)).StatusCode);Assert.Equal(HttpStatusCode.Conflict,(await owner.PutAsJsonAsync($"{Api}/projects/{p}/tasks/{id}",update)).StatusCode);
  Assert.Equal(HttpStatusCode.BadRequest,(await owner.PutAsJsonAsync($"{Api}/projects/{p}/tasks/{id}",new{title="阻塞",ownerId=FakeIdentity.Owner,revision=2,state="blocked",blocker="缺资料"})).StatusCode);
  Assert.Equal(HttpStatusCode.BadRequest,(await owner.PostAsJsonAsync($"{Api}/projects/{p}/tasks",new{title="日期",ownerId=FakeIdentity.Owner,startsOn="2026-11-16",dueOn="2026-11-14"})).StatusCode);
 }
 [Fact]public async Task MentionsAreIdBasedIdempotentAndNeverGrantMembership(){
  await using var f=new AppFactory();using var owner=f.Client("owner");using var peer=f.Client("peer");var p=await Project(owner);var request=new{body=$"请 @[张老师](staff:{FakeIdentity.Peer}) 协助",next="整理资料",requestId=Guid.NewGuid().ToString()};
  await Post(owner,$"{Api}/projects/{p}/progress",request);await Post(owner,$"{Api}/projects/{p}/progress",request);
  var notes=await peer.GetFromJsonAsync<JsonElement>(Api+"/notifications");Assert.Single(notes.EnumerateArray());Assert.Equal(HttpStatusCode.Forbidden,(await peer.GetAsync($"{Api}/projects/{p}")).StatusCode);
  var id=notes[0].GetProperty("id").GetGuid();await Post(owner,$"{Api}/notifications/{id}/read",new{});Assert.Equal(JsonValueKind.Null,(await peer.GetFromJsonAsync<JsonElement>(Api+"/notifications"))[0].GetProperty("readAt").ValueKind);
  await Post(peer,$"{Api}/notifications/{id}/read",new{});Assert.NotEqual(JsonValueKind.Null,(await peer.GetFromJsonAsync<JsonElement>(Api+"/notifications"))[0].GetProperty("readAt").ValueKind);
 }
 [Fact]public async Task RemovedAndDisabledAccountsCannotAccessDetailsOrAttachments(){
  await using var f=new AppFactory();using var owner=f.Client("owner");using var peer=f.Client("peer");var p=await Project(owner);await Post(owner,$"{Api}/projects/{p}/members",new{staffId=FakeIdentity.Peer});
  using var form=new MultipartFormDataContent();form.Add(new ByteArrayContent(Encoding.UTF8.GetBytes("附件内容")),"file","readme.txt");var result=await owner.PostAsync($"{Api}/projects/{p}/attachments",form);Assert.True(result.IsSuccessStatusCode);var file=(await result.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();Assert.Equal(HttpStatusCode.OK,(await peer.GetAsync($"{Api}/projects/{p}/attachments/{file}")).StatusCode);
  Assert.Equal(HttpStatusCode.NoContent,(await owner.DeleteAsync($"{Api}/projects/{p}/members/{FakeIdentity.Peer}")).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await peer.GetAsync($"{Api}/projects/{p}/attachments/{file}")).StatusCode);
  f.Identity.Users["owner"]=f.Identity.Users["owner"] with{Enabled=false};Assert.Equal(HttpStatusCode.Unauthorized,(await owner.GetAsync(Api+"/projects")).StatusCode);
 }
 [Fact]public async Task AcceptanceIsBoundToExactArtifactAndOnlyAdminCanApproveRelease(){
  await using var f=new AppFactory();using var owner=f.Client("owner");using var admin=f.Client("admin");var p=await Project(owner);var build=new Build{RepositoryId=Guid.NewGuid(),Sha=new string('a',40),ArtifactDigest=new string('b',64),State="tested",Kind="main"};
  await f.WithDb(async db=>{db.Repositories.Add(new(){Id=build.RepositoryId,Name="pilot",FullName="siyue/pilot"});db.Builds.Add(build);var project=await db.Projects.FindAsync(p);project!.RepositoryId=build.RepositoryId;project.TestingBuildId=build.Id;await db.SaveChangesAsync();});
  Assert.Equal(HttpStatusCode.Conflict,(await owner.PostAsJsonAsync($"{Api}/projects/{p}/acceptances",new{buildId=build.Id,passed=true,notes="通过"})).StatusCode);
  await Post(owner,$"{Api}/projects/{p}/testing/lock",new{buildId=build.Id,passed=false,notes=""});await Post(owner,$"{Api}/projects/{p}/acceptances",new{buildId=build.Id,passed=true,notes="功能已逐项验收"});
  var release=await Post(owner,$"{Api}/projects/{p}/releases",new{buildId=build.Id,notes="发布改动"});var rid=release.GetProperty("id").GetGuid();Assert.Equal(HttpStatusCode.Forbidden,(await owner.PostAsJsonAsync($"{Api}/projects/{p}/releases/{rid}/publish",new{})).StatusCode);
  await f.WithDb(async db=>{var b=await db.Builds.FindAsync(build.Id);b!.ArtifactDigest=new string('c',64);await db.SaveChangesAsync();});Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"{Api}/projects/{p}/releases/{rid}/publish",new{})).StatusCode);
  await f.WithDb(async db=>{var b=await db.Builds.FindAsync(build.Id);b!.ArtifactDigest=build.ArtifactDigest;await db.SaveChangesAsync();});var approved=await Post(admin,$"{Api}/projects/{p}/releases/{rid}/publish",new{});Assert.Equal("queued",approved.GetProperty("state").GetString());
  Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"{Api}/projects/{p}/releases/{rid}/publish",new{})).StatusCode);
 }
 [Fact]public async Task NewTestBuildNeverInheritsAnOldAcceptance(){
  await using var f=new AppFactory();using var owner=f.Client("owner");var p=await Project(owner);Guid repo=Guid.NewGuid(),old=Guid.NewGuid(),next=Guid.NewGuid();
  await f.WithDb(async db=>{var project=await db.Projects.FindAsync(p);project!.RepositoryId=repo;project.TestingBuildId=next;db.Builds.AddRange(new Build{Id=old,RepositoryId=repo,State="tested",Sha=new string('a',40),ArtifactDigest=new string('b',64)},new Build{Id=next,RepositoryId=repo,State="tested",Sha=new string('c',40),ArtifactDigest=new string('d',64)});db.Acceptances.Add(new(){ProjectId=p,BuildId=old,StaffId=FakeIdentity.Owner,Passed=true,Sha=new string('a',40),ArtifactDigest=new string('b',64)});await db.SaveChangesAsync();});
  Assert.Equal(HttpStatusCode.Conflict,(await owner.PostAsJsonAsync($"{Api}/projects/{p}/releases",new{buildId=next,notes="尝试复用验收"})).StatusCode);Assert.Equal(HttpStatusCode.Conflict,(await owner.PostAsJsonAsync($"{Api}/projects/{p}/releases",new{buildId=old,notes="旧版本"})).StatusCode);
 }
 [Fact]public async Task WebhookRejectsForgeryAndDeduplicatesAuthenticDeliveries(){
  await using var f=new AppFactory();using var client=f.Client("owner");var payload="{\"zen\":\"hello\"}";var delivery=Guid.NewGuid().ToString();
  async Task<HttpResponseMessage> Send(string sig){var msg=new HttpRequestMessage(HttpMethod.Post,Api+"/hooks/gitea"){Content=new StringContent(payload,Encoding.UTF8,"application/json")};msg.Headers.Add("X-Gitea-Signature",sig);msg.Headers.Add("X-Gitea-Delivery",delivery);msg.Headers.Add("X-Gitea-Event","ping");return await client.SendAsync(msg);}
  Assert.Equal(HttpStatusCode.Unauthorized,(await Send("forged")).StatusCode);var signature=Convert.ToHexStringLower(HMACSHA256.HashData(Encoding.UTF8.GetBytes("test-webhook-secret"),Encoding.UTF8.GetBytes(payload)));Assert.Equal(HttpStatusCode.Accepted,(await Send(signature)).StatusCode);Assert.True((await (await Send(signature)).Content.ReadFromJsonAsync<JsonElement>()).GetProperty("duplicate").GetBoolean());
 }
 [Fact]public async Task OidcMetadataUsesExactRegisteredPathsAndDoesNotAcceptArbitraryCallback(){
  await using var f=new AppFactory();using var client=f.Client("owner");var result=await client.GetFromJsonAsync<JsonElement>(Api+"/oidc/.well-known/openid-configuration");Assert.Equal("https://localhost/api/collaboration/oidc/authorize",result.GetProperty("authorization_endpoint").GetString());
  var response=await client.GetAsync(Api+"/oidc/authorize?client_id=unknown&response_type=code&redirect_uri=https://evil.example/&scope=openid");Assert.Equal(HttpStatusCode.BadRequest,response.StatusCode);
 }
 [Fact]public async Task OidcSubpathSurvivesRepeatedClientRegistrationAndExchangesOriginalSession(){
  await using var f=new AppFactory("/miniapp");using var client=f.Client("owner");
  var config=new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?>{{"Oidc:ClientId","gitea-test"},{"Oidc:ClientSecret","test-secret-private-client"},{"Oidc:RedirectUri","https://localhost/code/callback"}}).Build();
  using(var scope=f.Services.CreateScope()){await LoginApi.Register(scope.ServiceProvider,config);await LoginApi.Register(scope.ServiceProvider,config);}
  var settings=f.Services.GetRequiredService<IConfiguration>();Assert.Equal("/miniapp",settings["PathBase"]);var oidc=f.Services.GetRequiredService<Microsoft.Extensions.Options.IOptionsMonitor<OpenIddict.Server.OpenIddictServerOptions>>().CurrentValue;Assert.Contains(oidc.ConfigurationEndpointUris,x=>x.ToString()=="/miniapp/api/collaboration/oidc/.well-known/openid-configuration");var metadata=await client.GetFromJsonAsync<JsonElement>("/miniapp"+Api+"/oidc/.well-known/openid-configuration");Assert.Equal("https://localhost/miniapp/api/collaboration/oidc/authorize",metadata.GetProperty("authorization_endpoint").GetString());
  await Post(client,"/miniapp"+Api+"/session",new{});
  var response=await client.GetAsync("/miniapp"+Api+"/oidc/authorize?client_id=gitea-test&response_type=code&redirect_uri=https%3A%2F%2Flocalhost%2Fcode%2Fcallback&scope=openid%20profile%20email&state=test-state");
  Assert.Equal(HttpStatusCode.Redirect,response.StatusCode);var callback=response.Headers.Location!;Assert.Equal("/code/callback",callback.AbsolutePath);
  var query=Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(callback.Query);Assert.Equal("test-state",query["state"].ToString());
  client.DefaultRequestHeaders.Authorization=null;
  var token=await client.PostAsync("/miniapp"+Api+"/oidc/token",new FormUrlEncodedContent(new Dictionary<string,string>{{"grant_type","authorization_code"},{"client_id","gitea-test"},{"client_secret","test-secret-private-client"},{"code",query["code"].ToString()},{"redirect_uri","https://localhost/code/callback"}}));
  Assert.True(token.IsSuccessStatusCode,await token.Content.ReadAsStringAsync());var result=await token.Content.ReadFromJsonAsync<JsonElement>();
  client.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",result.GetProperty("access_token").GetString());
  var user=await client.GetFromJsonAsync<JsonElement>("/miniapp"+Api+"/oidc/userinfo");Assert.Equal(FakeIdentity.Owner.ToString(),user.GetProperty("sub").GetString());
  f.Identity.Users["owner"]=f.Identity.Users["owner"] with{Enabled=false};Assert.Equal(HttpStatusCode.Unauthorized,(await client.GetAsync("/miniapp"+Api+"/oidc/userinfo")).StatusCode);
 }

}
