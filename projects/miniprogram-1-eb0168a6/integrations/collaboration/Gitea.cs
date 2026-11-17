using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
namespace Siyue.Collaboration;

public sealed class Gitea(IHttpClientFactory clients,IConfiguration config,CollaborationDb db,IIdentityDirectory identity){
 public bool Enabled=>!string.IsNullOrEmpty(config["Gitea:Token"]);
 public static string Login(Guid id)=>"staff_"+id.ToString("N");
 public static string Email(Guid id)=>id.ToString("N")+"@users.siyuejn.com";
 public string PublicUrl=>(config["Gitea:PublicUrl"]??"").TrimEnd('/');
 public async Task<JsonElement> Call(HttpMethod method,string path,object? input=null,bool missing=false){
  Check.That(Enabled,"代码服务尚未配置",503);var client=clients.CreateClient("gitea");using var req=new HttpRequestMessage(method,(config["Gitea:InternalUrl"]??"http://127.0.0.1:5300").TrimEnd('/')+"/api/v1/"+path);
  req.Headers.Authorization=new AuthenticationHeaderValue("token",config["Gitea:Token"]);if(input!=null)req.Content=JsonContent.Create(input);using var response=await client.SendAsync(req);
  if(missing&&response.StatusCode==HttpStatusCode.NotFound)return default;
  if(!response.IsSuccessStatusCode)throw new Fault(502,$"代码服务操作失败（{(int)response.StatusCode}），请稍后重试");
  return response.StatusCode==HttpStatusCode.NoContent?default:await response.Content.ReadFromJsonAsync<JsonElement>();
 }
 // User-initiated repository operations are attributed to the verified platform identity.
 public async Task<JsonElement> UserCall(Person actor,HttpMethod method,string path,object input){
  var login=await EnsureUser(actor);using var req=Request(method,path,input);req.Headers.Add("Sudo",login);
  using var response=await clients.CreateClient("gitea").SendAsync(req);EnsureResponse(response);
  if(response.StatusCode==HttpStatusCode.NoContent||response.Content.Headers.ContentLength==0)return default;
  var text=await response.Content.ReadAsStringAsync();return string.IsNullOrWhiteSpace(text)?default:JsonSerializer.Deserialize<JsonElement>(text);
 }
 HttpRequestMessage Request(HttpMethod method,string path,object? input=null){Check.That(Enabled,"代码服务尚未配置",503);var req=new HttpRequestMessage(method,(config["Gitea:InternalUrl"]??"http://127.0.0.1:5300").TrimEnd('/')+"/api/v1/"+path);req.Headers.Authorization=new AuthenticationHeaderValue("token",config["Gitea:Token"]);if(input!=null)req.Content=JsonContent.Create(input);return req;}
 static void EnsureResponse(HttpResponseMessage r){if(r.IsSuccessStatusCode)return;var code=(int)r.StatusCode;throw new Fault(code is 401 or 403?403:code==404?404:code is 405 or 409 or 422?409:502,code is 401 or 403?"代码权限尚未就绪或此操作未获授权":code==404?"代码记录不存在":code is 405 or 409 or 422?"代码状态已变化，或分支检查尚未通过；请刷新后核对":"代码服务暂时不可用");}
 public async Task<(string Text,bool Truncated)> Diff(string path){
  using var req=Request(HttpMethod.Get,path);using var response=await clients.CreateClient("gitea").SendAsync(req,HttpCompletionOption.ResponseHeadersRead);EnsureResponse(response);
  using var reader=new StreamReader(await response.Content.ReadAsStreamAsync());var buffer=new char[524289];var count=await reader.ReadBlockAsync(buffer);return (new string(buffer,0,Math.Min(count,524288)),count>524288);
 }
 public async Task<string> EnsureUser(Person person){
  var name=Login(person.Id);if(!Enabled)return name;
  var remote=await Call(HttpMethod.Get,"users/"+name,missing:true);var source=config.GetValue<int>("Gitea:AuthSourceId");Check.That(source>0,"代码单点登录尚未配置",503);
  if(remote.ValueKind==JsonValueKind.Undefined)remote=await Call(HttpMethod.Post,"admin/users",new{username=name,email=Email(person.Id),full_name=person.Name,login_name=person.Id.ToString(),source_id=source,must_change_password=false,send_notify=false,restricted=true});
  await Call(HttpMethod.Patch,"admin/users/"+name,new{login_name=person.Id.ToString(),source_id=source,full_name=person.Name,email=Email(person.Id),prohibit_login=!person.Enabled,restricted=true,allow_create_organization=false,allow_git_hook=false,max_repo_creation=0});
  var row=await db.GitIdentities.SingleOrDefaultAsync(x=>x.StaffId==person.Id);if(row==null){row=new(){StaffId=person.Id,Login=name,GiteaId=remote.GetProperty("id").GetInt64()};db.GitIdentities.Add(row);}row.SyncedAt=DateTime.UtcNow;await db.SaveChangesAsync();return name;
 }
 public async Task Provision(Repository repository){
  var owner=config["Gitea:Owner"]??"siyue-projects";var name=repository.Name;
  var org=await Call(HttpMethod.Get,"orgs/"+owner,missing:true);if(org.ValueKind==JsonValueKind.Undefined)await Call(HttpMethod.Post,"orgs",new{username=owner,full_name="思跃项目协作",visibility="private"});
  var remote=await Call(HttpMethod.Get,$"repos/{owner}/{name}",missing:true);
  if(remote.ValueKind==JsonValueKind.Undefined)remote=await Call(HttpMethod.Post,$"orgs/{owner}/repos",new{name,description="思跃项目协作代码库",@private=true,auto_init=true,default_branch="main",trust_model="committer"});
  repository.FullName=owner+"/"+name;repository.GiteaId=remote.GetProperty("id").GetInt64();repository.Enabled=true;
  await Call(HttpMethod.Patch,"repos/"+repository.FullName,new{website="https://www.siyuejn.com/miniapp/collaboration/#projects?repository="+Uri.EscapeDataString(repository.FullName),has_issues=true,has_pull_requests=true,has_actions=false,allow_merge_commits=false,allow_rebase=false,allow_rebase_explicit=false,allow_squash_merge=true,default_delete_branch_after_merge=false});
  var hooks=await Call(HttpMethod.Get,$"repos/{repository.FullName}/hooks");var webhook=config["Gitea:WebhookUrl"]??throw new InvalidOperationException("Missing webhook URL");
  if(!hooks.EnumerateArray().Any(h=>h.GetProperty("config").GetProperty("url").GetString()==webhook))await Call(HttpMethod.Post,$"repos/{repository.FullName}/hooks",new{type="gitea",active=true,events=new[]{"push","pull_request"},config=new{url=webhook,content_type="json",secret=config["Gitea:WebhookSecret"],http_method="POST"}});
  repository.SyncState="pending";await db.SaveChangesAsync();
 }
 public async Task SyncProject(Project project){if(project.RepositoryId.HasValue&&Enabled){var repo=await db.Repositories.FindAsync(project.RepositoryId.Value);if(repo!=null)await SyncRepository(repo);}}
 public async Task SyncRepository(Repository repo,Person[]? people=null,HashSet<Guid>? syncedUsers=null){
  if(!Enabled||!repo.Enabled)return;
  var familyId=repo.ParentRepositoryId??repo.Id;var family=await db.Repositories.Where(r=>r.Id==familyId||r.ParentRepositoryId==familyId).Select(r=>r.Id).ToArrayAsync();var projects=await db.Projects.Where(p=>p.RepositoryId.HasValue&&family.Contains(p.RepositoryId.Value)).ToArrayAsync();var ids=projects.Select(x=>x.Id).ToArray();var memberIds=await db.Members.Where(m=>ids.Contains(m.ProjectId)).Select(m=>m.StaffId).ToArrayAsync();people??=await identity.People();
  var allowed=people.Where(p=>p.Enabled&&(p.Role=="admin"||memberIds.Contains(p.Id))).ToArray();var ownerIds=projects.Select(p=>p.OwnerId).ToArray();var reviewers=allowed.Where(p=>p.Role=="admin"||ownerIds.Contains(p.Id)).Select(p=>Login(p.Id)).ToArray();
  foreach(var p in allowed){
   if(syncedUsers?.Contains(p.Id)==true)continue;
   await EnsureUser(p);
   syncedUsers?.Add(p.Id);
  }
  var existing=await Call(HttpMethod.Get,$"repos/{repo.FullName}/collaborators");
  foreach(var row in existing.EnumerateArray()){var login=row.GetProperty("login").GetString()!;if(login.StartsWith("staff_")&&!allowed.Any(p=>Login(p.Id)==login))await Call(HttpMethod.Delete,$"repos/{repo.FullName}/collaborators/{login}");}
  foreach(var p in allowed)await Call(HttpMethod.Put,$"repos/{repo.FullName}/collaborators/{Login(p.Id)}",new{permission="write"});
  var protection=new{branch_name=repo.MainBranch,rule_name=repo.MainBranch,enable_push=false,enable_force_push=false,enable_merge_whitelist=true,merge_whitelist_usernames=reviewers,enable_status_check=true,status_check_contexts=new[]{"siyue/ci"},required_approvals=0,block_on_rejected_reviews=true,block_on_outdated_branch=true,block_admin_merge_override=true};
  var rule=await Call(HttpMethod.Get,$"repos/{repo.FullName}/branch_protections/{repo.MainBranch}",missing:true);
  if(!MatchesBranchProtection(rule,JsonSerializer.SerializeToElement(protection)))
   await Call(rule.ValueKind==JsonValueKind.Undefined?HttpMethod.Post:HttpMethod.Patch,$"repos/{repo.FullName}/branch_protections"+(rule.ValueKind==JsonValueKind.Undefined?"":"/"+repo.MainBranch),protection);
  repo.SyncState="ready";repo.SyncError="";await db.SaveChangesAsync();
 }
 internal static bool MatchesBranchProtection(JsonElement current,JsonElement desired){
  if(current.ValueKind!=JsonValueKind.Object)return false;
  foreach(var setting in desired.EnumerateObject()){
   if(!current.TryGetProperty(setting.Name,out var existing))return false;
   if(setting.Value.ValueKind==JsonValueKind.Array&&existing.ValueKind==JsonValueKind.Array){
    if(!existing.EnumerateArray().Select(x=>x.GetString()).Order(StringComparer.Ordinal)
        .SequenceEqual(setting.Value.EnumerateArray().Select(x=>x.GetString()).Order(StringComparer.Ordinal)))return false;
   }else if(!JsonElement.DeepEquals(existing,setting.Value))return false;
  }
  return true;
 }
 public async Task Status(Repository repo,Build b,string state){await Call(HttpMethod.Post,$"repos/{repo.FullName}/statuses/{b.Sha}",new{state,context="siyue/ci",description=b.Summary.Length>140?b.Summary[..140]:b.Summary,target_url=config["PublicUrl"]});}
}
