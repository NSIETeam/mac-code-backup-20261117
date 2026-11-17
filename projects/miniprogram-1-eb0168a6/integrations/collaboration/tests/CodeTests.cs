using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue.Collaboration;
using Xunit;
public class CodeFactory:AppFactory {
 public readonly FakeGit Git=new();
 protected override void ConfigureWebHost(IWebHostBuilder b){base.ConfigureWebHost(b);b.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{{"Gitea:Token","test-code-service"},{"Gitea:AuthSourceId","1"}}));b.ConfigureServices(s=>s.AddHttpClient("gitea").ConfigurePrimaryHttpMessageHandler(()=>Git));}
}
public class FakeGit:HttpMessageHandler {
 public string Head=new('b',40);public string? CheckState="success";public bool RejectWrite;public List<(string Path,string Verb,string? Sudo,JsonElement Body)> Writes=[];
 public readonly Dictionary<string,JsonElement> BranchProtection=new();
 protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage req,CancellationToken ct){
  var path=Uri.UnescapeDataString(req.RequestUri!.AbsolutePath);JsonElement body=req.Content==null?default:JsonSerializer.Deserialize<JsonElement>(await req.Content.ReadAsStringAsync(ct));
  if(req.Method!=HttpMethod.Get){Writes.Add((path,req.Method.Method,req.Headers.TryGetValues("Sudo",out var h)?h.Single():null,body));if(RejectWrite&&path.Contains("/contents/"))return new(HttpStatusCode.Conflict){Content=JsonContent.Create(new{message="conflict"})};}
  object value=new{};
  if(path.EndsWith("/collaborators"))value=Array.Empty<object>();
  else if(path.Contains("/branch_protections/")){
   if(req.Method==HttpMethod.Patch)BranchProtection[path]=body;
   if(BranchProtection.TryGetValue(path,out var rule))value=rule;
  }
  else if(path.Contains("/users/"))value=new{id=1,login="staff",full_name="老师"};
  else if(path.Contains("/branches/task/"))return new(HttpStatusCode.NotFound){Content=JsonContent.Create(new{})};
  else if(path.EndsWith("/pulls/1"))value=new{number=1,title="修改",body="说明",state="open",merged=false,mergeable=true,head=new{@ref="task/test",sha=Head},@base=new{@ref="main"},user=new{login="staff"},updated_at="2026-11-15T00:00:00Z"};
  else if(path.EndsWith("/status"))value=new{statuses=CheckState==null?null:new[]{new{context="siyue/ci",status=CheckState,description="test"}}};
  else if(path.Contains("/contents/"))value=req.Method==HttpMethod.Get?new{type="file",encoding="base64",size=5,sha=new string('c',40),content=Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("hello"))}:new{commit=new{sha=Head}};
  else if(path.EndsWith("/merge")||path.EndsWith("/reviews"))return new(HttpStatusCode.NoContent);
  return new(HttpStatusCode.OK){Content=JsonContent.Create(value)};
 }
}
public class CodeTests {
 const string Api="/api/collaboration";
 [Fact]
 public async Task RepositorySyncReusesUserWorkAndRepairsChangedBranchRules()
 {
  await using var factory=new CodeFactory();
  var fixture=await Setup(factory);
  await using var scope=factory.Services.CreateAsyncScope();
  var services=scope.ServiceProvider;
  var git=services.GetRequiredService<Gitea>();
  var db=services.GetRequiredService<CollaborationDb>();
  var parent=(await db.Repositories.FindAsync(fixture.Repo.Id))!;
  var child=(await db.Repositories.FindAsync(fixture.Child.Id))!;
  child.Enabled=true;
  await db.SaveChangesAsync();
  var people=await factory.Identity.People();
  var batch=new HashSet<Guid>();
  await git.SyncRepository(parent,people,batch);
  var users=factory.Git.Writes.Count(w=>w.Path.Contains("/admin/users/"));
  Assert.True(users>0);
  await git.SyncRepository(child,people,batch);
  Assert.Equal(users,factory.Git.Writes.Count(w=>w.Path.Contains("/admin/users/")));
  Assert.Equal(2,factory.Git.BranchProtection.Count);
  factory.Git.Writes.Clear();
  await git.SyncRepository(parent,people,batch);
  Assert.DoesNotContain(factory.Git.Writes,w=>w.Path.Contains("/branch_protections/"));
  var broken=factory.Git.BranchProtection.Keys.Single(k=>k.Contains("/parent/"));
  factory.Git.BranchProtection[broken]=JsonSerializer.SerializeToElement(new {enable_force_push=true});
  await git.SyncRepository(parent,people,batch);
  Assert.False(Assert.Single(factory.Git.Writes,w=>w.Path==broken).Body.GetProperty("enable_force_push").GetBoolean());
 }
 async Task<(Project Project,Repository Repo,Repository Child,Repository Other,WorkTask Task)> Setup(CodeFactory f){var repo=new Repository{Name="parent",FullName="siyue/parent",Enabled=true,AutomationEnabled=true};var child=new Repository{Name="child",FullName="siyue/child",ParentRepositoryId=repo.Id};var other=new Repository{Name="other",FullName="siyue/other"};var project=new Project{Name="代码项目",RepositoryId=repo.Id,OwnerId=FakeIdentity.Owner};var task=new WorkTask{ProjectId=project.Id,Title="开发任务",OwnerId=FakeIdentity.Owner};await f.WithDb(async db=>{db.Repositories.AddRange(repo,child,other);db.Projects.Add(project);db.Tasks.Add(task);db.Members.AddRange(new Member{ProjectId=project.Id,StaffId=FakeIdentity.Owner,Role="owner"},new Member{ProjectId=project.Id,StaffId=FakeIdentity.Peer});await db.SaveChangesAsync();});return(project,repo,child,other,task);}
 [Fact]public async Task CodeBrowseIsProjectScopedAndFamilyCannotEscapeToUnrelatedRepository(){await using var f=new CodeFactory();var x=await Setup(f);using var owner=f.Client("owner");using var outsider=f.Client("outsider");var path=$"{Api}/projects/{x.Project.Id}/code/file?path=README.md";
  Assert.Equal(HttpStatusCode.Forbidden,(await outsider.GetAsync(path)).StatusCode);Assert.Equal(HttpStatusCode.OK,(await owner.GetAsync(path+"&repositoryId="+x.Child.Id)).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await owner.GetAsync(path+"&repositoryId="+x.Other.Id)).StatusCode);
  Assert.Equal(HttpStatusCode.BadRequest,(await owner.GetAsync(path+"&reference=../bad")).StatusCode);Assert.Equal(HttpStatusCode.BadRequest,(await owner.GetAsync($"{Api}/projects/{x.Project.Id}/code/file?path=../secret")).StatusCode);
  var grant=await(await owner.PostAsJsonAsync(Api+"/agent/grants",new{name="Agent",projectIds=new[]{x.Project.Id},scope="contribute",hours=1})).Content.ReadFromJsonAsync<JsonElement>();using var agent=f.Client(grant.GetProperty("token").GetString()!);
  Assert.Equal(HttpStatusCode.OK,(await agent.GetAsync(path)).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await agent.GetAsync(path+"&repositoryId="+x.Child.Id)).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await agent.PostAsJsonAsync($"{Api}/projects/{x.Project.Id}/code/pulls/1/merge",new{headSha=f.Git.Head})).StatusCode);
 }
 [Fact]public async Task MergeRequiresOwnerCurrentHeadAndChecksAndUsesActualCallerWithoutForce(){await using var f=new CodeFactory();var x=await Setup(f);using var owner=f.Client("owner");using var peer=f.Client("peer");var url=$"{Api}/projects/{x.Project.Id}/code/pulls/1/merge";
  Assert.Equal(HttpStatusCode.Forbidden,(await peer.PostAsJsonAsync(url,new{headSha=f.Git.Head})).StatusCode);Assert.Equal(HttpStatusCode.Conflict,(await owner.PostAsJsonAsync(url,new{headSha=new string('a',40)})).StatusCode);
  f.Git.CheckState="failure";Assert.Equal(HttpStatusCode.Conflict,(await owner.PostAsJsonAsync(url,new{headSha=f.Git.Head})).StatusCode);Assert.DoesNotContain(f.Git.Writes,w=>w.Path.EndsWith("/merge"));
  f.Git.CheckState="success";Assert.Equal(HttpStatusCode.NoContent,(await owner.PostAsJsonAsync(url,new{headSha=f.Git.Head})).StatusCode);var request=Assert.Single(f.Git.Writes,w=>w.Path.EndsWith("/merge"));Assert.Equal(Gitea.Login(FakeIdentity.Owner),request.Sudo);Assert.Equal(f.Git.Head,request.Body.GetProperty("head_commit_id").GetString());Assert.False(request.Body.GetProperty("force_merge").GetBoolean());Assert.Equal("squash",request.Body.GetProperty("do").GetString());
 }
 [Fact]public async Task PullWithoutChecksCanStillBeViewed(){await using var f=new CodeFactory();var x=await Setup(f);f.Git.CheckState=null;using var owner=f.Client("owner");var r=await owner.GetAsync($"{Api}/projects/{x.Project.Id}/code/pulls/1");Assert.Equal(HttpStatusCode.OK,r.StatusCode);var body=await r.Content.ReadFromJsonAsync<JsonElement>();Assert.Empty(body.GetProperty("checks").EnumerateArray());}
 [Fact]public async Task FileWritesUseTaskBranchAndBlobConflictAndReviewsRejectNewerHead(){await using var f=new CodeFactory();var x=await Setup(f);using var owner=f.Client("owner");var url=$"{Api}/projects/{x.Project.Id}/code/files";var body=new{path="README.md",content="changed",message="测试修改",taskId=x.Task.Id,sha=new string('c',40),branch="main"};
  Assert.Equal(HttpStatusCode.OK,(await owner.PostAsJsonAsync(url,body)).StatusCode);var request=Assert.Single(f.Git.Writes,w=>w.Path.Contains("/contents/"));Assert.Equal(Gitea.Login(FakeIdentity.Owner),request.Sudo);Assert.Equal("task/"+x.Task.Id,request.Body.GetProperty("new_branch").GetString());Assert.Equal(body.sha,request.Body.GetProperty("sha").GetString());
  f.Git.RejectWrite=true;Assert.Equal(HttpStatusCode.Conflict,(await owner.PostAsJsonAsync(url,body)).StatusCode);
  Assert.Equal(HttpStatusCode.Conflict,(await owner.PostAsJsonAsync($"{Api}/projects/{x.Project.Id}/code/pulls/1/reviews",new{body="旧版本意见",headSha=new string('a',40),@event="APPROVED"})).StatusCode);Assert.DoesNotContain(f.Git.Writes,w=>w.Path.EndsWith("/reviews"));
 }
}
