using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
namespace Siyue.Collaboration;
public sealed class Delivery(IHttpClientFactory clients,IConfiguration config){
 public bool Enabled=>!string.IsNullOrEmpty(config["Delivery:Secret"]);
 public async Task<JsonElement> Call(string path,object input){
  Check.That(Enabled,"构建与发布服务尚未配置",503);var body=JsonSerializer.Serialize(input);var stamp=DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString();
  var retryable=path is "/describe" or "/logs"||path=="/preview"&&JsonSerializer.SerializeToElement(input).TryGetProperty("method",out var method)&&method.GetString()=="GET";
  for(var attempt=0;;attempt++){
   using var request=new HttpRequestMessage(HttpMethod.Post,(config["Delivery:Url"]??"http://127.0.0.1:5302")+path){Content=new StringContent(body,Encoding.UTF8,"application/json")};request.Headers.Add("X-Siyue-Time",stamp);
   request.Headers.Add("X-Siyue-Signature",Convert.ToHexStringLower(HMACSHA256.HashData(Encoding.UTF8.GetBytes(config["Delivery:Secret"]!),Encoding.UTF8.GetBytes(stamp+"\n"+path+"\n"+body))));
   try{using var response=await clients.CreateClient("deployer").SendAsync(request);Check.That(response.IsSuccessStatusCode,"构建发布服务暂不可用",502);return await response.Content.ReadFromJsonAsync<JsonElement>();}
   catch(HttpRequestException)when(retryable&&attempt<2){await Task.Delay(100*(attempt+1));}
  }
 }
}
public sealed class CollaborationWorker(IServiceScopeFactory scopes,ILogger<CollaborationWorker> logger):BackgroundService {
 DateTime syncAt=DateTime.MinValue;
 protected override async Task ExecuteAsync(CancellationToken stop){
  while(!stop.IsCancellationRequested){try{await Tick(stop);}catch(OperationCanceledException)when(stop.IsCancellationRequested){break;}catch(Exception e){logger.LogWarning(e,"Collaboration queue retry");}await Task.Delay(TimeSpan.FromSeconds(2),stop);}
 }
 async Task Tick(CancellationToken ct){
  await using var scope=scopes.CreateAsyncScope();var db=scope.ServiceProvider.GetRequiredService<CollaborationDb>();var git=scope.ServiceProvider.GetRequiredService<Gitea>();var delivery=scope.ServiceProvider.GetRequiredService<Delivery>();var people=scope.ServiceProvider.GetRequiredService<IIdentityDirectory>();
  if(!git.Enabled)return;
  await db.Database.OpenConnectionAsync(ct);var connection=db.Database.GetDbConnection();await using var cmd=connection.CreateCommand();cmd.CommandText="SELECT pg_try_advisory_lock(71981114)";if(!(bool)(await cmd.ExecuteScalarAsync(ct))!)return;
  try{
   if(DateTime.UtcNow-syncAt>TimeSpan.FromSeconds(30)){
    await ReconcileRepositories(db,git,people,ct);
    syncAt=DateTime.UtcNow;
   }
   foreach(var ev in await db.GitEvents.Where(e=>e.State=="pending"&&(e.NextAttemptAt==null||e.NextAttemptAt<=DateTime.UtcNow)).OrderBy(e=>e.CreatedAt).Take(10).ToArrayAsync(ct)){
    try{await ProcessEvent(db,ev);ev.State="processed";}catch(Exception){ev.Attempts++;ev.NextAttemptAt=DateTime.UtcNow.AddSeconds(Math.Min(300,10*Math.Pow(2,Math.Min(ev.Attempts,5))));}await db.SaveChangesAsync(ct);
   }
   if(!delivery.Enabled)return;
   var pending=await db.Builds.Where(b=>b.State=="queued"||b.State=="running").OrderBy(b=>b.State=="running"?0:b.Kind=="check"?1:2).ThenBy(b=>b.CreatedAt).FirstOrDefaultAsync(ct);
   if(pending!=null){var repo=await db.Repositories.FindAsync(pending.RepositoryId);if(repo is {Enabled:true}){
    var result=await delivery.Call("/build",new{id=pending.Id,key=repo.DeploymentKey,sha=pending.Sha,kind=pending.Kind});var state=result.GetProperty("state").GetString()!;
    pending.Summary=result.TryGetProperty("summary",out var summary)?summary.GetString()??"":"";
    if(state=="succeeded"){pending.State="passed";pending.ArtifactDigest=result.GetProperty("digest").GetString()!;pending.FinishedAt=DateTime.UtcNow;await git.Status(repo,pending,"success");}
    else if(state=="failed"){pending.State="failed";pending.FinishedAt=DateTime.UtcNow;await git.Status(repo,pending,"failure");}
    else{pending.State=state=="queued"?"queued":"running";await git.Status(repo,pending,"pending");}await db.SaveChangesAsync(ct);
   }}
   foreach(var repo in await db.Repositories.Where(r=>r.Enabled && db.Builds.Any(b=>
       b.RepositoryId==r.Id && b.State=="passed" && (b.Kind!="main" || b.Sha==r.HeadSha))).ToArrayAsync(ct)){
    await using var transaction=await db.Database.BeginTransactionAsync(ct);
    var projects=await db.Projects.FromSqlInterpolated($"SELECT * FROM \"Projects\" WHERE \"RepositoryId\"={repo.Id} ORDER BY \"Id\" FOR UPDATE").ToArrayAsync(ct);
    var candidate=await db.Builds.Where(b=>b.RepositoryId==repo.Id&&b.State=="passed"&&(b.Kind!="main"||b.Sha==repo.HeadSha)).OrderByDescending(b=>b.CreatedAt).FirstOrDefaultAsync(ct);
    if(candidate!=null&&(candidate.Kind!="main"||projects.All(p=>p.LockedBuildId==null))){
     var result=await delivery.Call("/test",new{id=candidate.Id,key=repo.DeploymentKey,digest=candidate.ArtifactDigest,kind=candidate.Kind});
     if(result.GetProperty("state").GetString()=="succeeded"){
      candidate.State=candidate.Kind=="main"?"tested":"previewed";candidate.Summary=candidate.Kind=="main"?"测试环境已更新，等待负责人验收":"开发分支检查通过，可预览此提交；合并后重新验收";foreach(var p in candidate.Kind=="main"?projects:[]){p.TestingBuildId=candidate.Id;p.Revision++;if(p.OwnerId.HasValue)db.Notifications.Add(new(){StaffId=p.OwnerId.Value,ProjectId=p.Id,TargetId=candidate.Id,Kind="test_ready",Text="新测试版本已部署，请锁定版本后验收",EventKey=$"test:{candidate.Id}:{p.Id}"});}
     }else candidate.Summary=result.GetProperty("summary").GetString()??"测试部署未完成";
     await db.SaveChangesAsync(ct);
    }await transaction.CommitAsync(ct);
   }
   var release=await db.Releases.Where(r=>r.State=="queued"||r.State=="publishing").OrderBy(r=>r.CreatedAt).FirstOrDefaultAsync(ct);
   if(release!=null){
    var project=await db.Projects.FindAsync(release.ProjectId);var build=await db.Builds.FindAsync(release.BuildId);var repo=await db.Repositories.FindAsync(build!.RepositoryId);
    try{
     Check.That((await people.People(ct)).Any(p=>p.Id==release.PublishedBy&&p.Enabled&&p.Role=="admin"),"发布管理员已停用",403);
     if(!release.IsRollback)await GitApi.RequireAccepted(db,project!.Id,build.Id);
    }catch(Fault failure){release.State="failed";release.Result=failure.Message;release.FinishedAt=DateTime.UtcNow;await db.SaveChangesAsync(ct);return;}
    release.State="publishing";await db.SaveChangesAsync(ct);
    var result=await delivery.Call("/publish",new{id=release.Id,buildId=build.Id,key=repo!.DeploymentKey,digest=build.ArtifactDigest,rollback=release.IsRollback});var state=result.GetProperty("state").GetString();
    release.Result=result.GetProperty("summary").GetString()??"";
    if(state is "succeeded" or "failed"){
     release.State=state=="succeeded"?"published":"failed";release.FinishedAt=DateTime.UtcNow;
     if(state=="succeeded")foreach(var linked in await db.Projects.Where(p=>p.RepositoryId==repo.Id).ToArrayAsync(ct)){linked.ProductionBuildId=build.Id;linked.Revision++;}
     foreach(var m in await db.Members.Where(m=>m.ProjectId==project!.Id).ToArrayAsync(ct))db.Notifications.Add(new(){StaffId=m.StaffId,ProjectId=project!.Id,TargetId=release.Id,Kind=state=="succeeded"?"published":"publish_failed",Text=state=="succeeded"?"管理员已发布新版本":"正式发布失败，请查看发布记录",EventKey=$"release:{release.Id}:{m.StaffId}"});
    }await db.SaveChangesAsync(ct);
   }
  }finally{cmd.CommandText="SELECT pg_advisory_unlock(71981114)";await cmd.ExecuteScalarAsync(CancellationToken.None);}
 }
 static async Task ReconcileRepositories(CollaborationDb db,Gitea git,IIdentityDirectory directory,CancellationToken ct){
  var people=await directory.People(ct);
  var syncedUsers=new HashSet<Guid>();
  foreach(var repository in await db.Repositories.Where(r=>r.Enabled).ToArrayAsync(ct)){
   try{
    await git.SyncRepository(repository,people,syncedUsers);
    var branch=await git.Call(HttpMethod.Get,$"repos/{repository.FullName}/branches/{repository.MainBranch}");
    var sha=branch.GetProperty("commit").GetProperty("id").GetString()!;
    repository.HeadSha=sha;
    await Queue(db,repository,sha,repository.MainBranch,"main","reconciler");
    // Recover open changes that arrived while webhooks were unavailable.
    for(var page=1;page<=20;page++){
     var pulls=await git.Call(HttpMethod.Get,$"repos/{repository.FullName}/pulls?state=open&limit=50&page={page}");
     foreach(var pr in pulls.EnumerateArray())await ProcessEvent(db,new GitEvent{
      Kind="pull_request",Payload=JsonSerializer.Serialize(new{repository=new{full_name=repository.FullName},pull_request=pr,sender=pr.GetProperty("user")})
     });
     if(pulls.GetArrayLength()<50)break;
    }
   }catch(OperationCanceledException)when(ct.IsCancellationRequested){throw;}
   catch(Exception){repository.SyncState="retrying";repository.SyncError="代码权限或状态同步失败，正在重试";}
  }
  var disabled=(await directory.People(ct)).Where(p=>!p.Enabled).ToDictionary(p=>p.Id);
  if(disabled.Count>0){
   var ids=disabled.Keys.ToArray();
   var known=await db.GitIdentities.Where(u=>ids.Contains(u.StaffId)).Select(u=>u.StaffId).ToArrayAsync(ct);
   foreach(var id in known)await git.EnsureUser(disabled[id]);
  }
  await db.SaveChangesAsync(ct);
 }
 public static async Task Queue(CollaborationDb db,Repository repo,string sha,string branch,string kind,string actor){
  if(!repo.AutomationEnabled)return;
  if(!System.Text.RegularExpressions.Regex.IsMatch(sha,@"^[a-f0-9]{40,64}$"))return;
  foreach(var old in await db.Builds.Where(b=>b.RepositoryId==repo.Id&&b.Branch==branch&&b.Kind==kind&&b.Sha!=sha&&b.State=="queued").ToArrayAsync()){old.State="superseded";old.Summary="已有更新的提交，后续检查使用新版本";old.FinishedAt=DateTime.UtcNow;}
  if(!await db.Builds.AnyAsync(b=>b.RepositoryId==repo.Id&&b.Sha==sha&&b.Kind==kind)){db.Builds.Add(new(){RepositoryId=repo.Id,Sha=sha,Branch=branch,Kind=kind,Actor=actor});await db.SaveChangesAsync();}
 }
 public static async Task RecordCodeAction(CollaborationDb db,Repository repo,string sha,string branch,string sender){
  if(!branch.StartsWith("task/")||!Guid.TryParse(branch[5..],out var taskId)||!System.Text.RegularExpressions.Regex.IsMatch(sha,@"^[a-f0-9]{40,64}$"))return;
  var task=await db.Tasks.FindAsync(taskId);if(task==null||!await db.Projects.AnyAsync(p=>p.Id==task.ProjectId&&p.RepositoryId==repo.Id))return;
  var key=$"git-push:{repo.Id}:{sha}:{taskId}";if(await db.TaskActions.AnyAsync(a=>a.ProjectId==task.ProjectId&&a.RequestId==key))return;
  var author=sender.StartsWith("staff_")&&Guid.TryParseExact(sender[6..],"N",out var id)?id:Guid.Empty;
  db.TaskActions.Add(new(){ProjectId=task.ProjectId,TaskId=taskId,AuthorId=author,Title="提交代码 · "+sha[..10],Body=$"代码平台已收到 {sender} 推送到 {branch} 的提交。提交代码不等于验收通过或正式上线。",Kind="code",PlannedOn=DateOnly.FromDateTime(DateTime.UtcNow.AddHours(8)),State="done",RepositoryId=repo.Id,CodeRef=sha,RequestId=key});await db.SaveChangesAsync();
 }
 public static async Task ProcessEvent(CollaborationDb db,GitEvent ev){
  if(ev.Kind=="ping")return;using var document=JsonDocument.Parse(ev.Payload);var body=document.RootElement;var name=body.GetProperty("repository").GetProperty("full_name").GetString();var repo=await db.Repositories.SingleOrDefaultAsync(r=>r.FullName==name&&r.Enabled);if(repo==null)return;
  var sender=body.TryGetProperty("sender",out var user)&&user.TryGetProperty("login",out var login)?login.GetString()??"":"";
  if(ev.Kind=="push"){
   var branch=body.GetProperty("ref").GetString()??"";if(!branch.StartsWith("refs/heads/"))return;branch=branch[11..];var sha=body.GetProperty("after").GetString()??"";if(sha.All(c=>c=='0'))return;
   await RecordCodeAction(db,repo,sha,branch,sender);
   await Queue(db,repo,sha,branch,branch==repo.MainBranch?"main":"check",sender);
  }else if(ev.Kind=="pull_request"){
   var pr=body.GetProperty("pull_request");var number=pr.GetProperty("number").GetInt64();var row=await db.Changes.SingleOrDefaultAsync(x=>x.RepositoryId==repo.Id&&x.Number==number);if(row==null){row=new(){RepositoryId=repo.Id,Number=number};db.Changes.Add(row);}
   row.Title=pr.GetProperty("title").GetString()??"";row.State=pr.TryGetProperty("merged",out var merged)&&merged.ValueKind==JsonValueKind.True?"merged":pr.GetProperty("state").GetString()??"open";row.Sha=pr.GetProperty("head").GetProperty("sha").GetString()??"";row.Branch=pr.GetProperty("head").GetProperty("ref").GetString()??"";row.Actor=sender;row.Url=pr.GetProperty("html_url").GetString()??"";row.UpdatedAt=DateTime.UtcNow;
   if(row.Branch.StartsWith("task/")&&Guid.TryParse(row.Branch[5..],out var task)){var projectIds=await db.Projects.Where(p=>p.RepositoryId==repo.Id).Select(p=>p.Id).ToArrayAsync();if(await db.Tasks.AnyAsync(t=>t.Id==task&&projectIds.Contains(t.ProjectId)))row.TaskId=task;}
   await db.SaveChangesAsync();if(row.State=="open")await Queue(db,repo,row.Sha,row.Branch,"check",sender);
  }
 }
}
