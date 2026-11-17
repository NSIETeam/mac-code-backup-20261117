using Microsoft.EntityFrameworkCore;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Siyue.Collaboration;
public record PullInput(string Title,string Body,string Head,Guid? TaskId=null);
public record ReviewInput(string Body,string HeadSha,string Event="COMMENT");
public record MergeInput(string HeadSha);
public record FileInput(string Path,string Content,string Message,Guid TaskId,string? Sha=null,string? Branch=null);
public static class CodeApi {
 public static async Task<Repository> Resolve(Guid id,Guid? repositoryId,HttpContext c,CollaborationDb db,ProjectAccess access){
  var p=await access.Read(id,Check.Actor(c));var primary=p.RepositoryId.HasValue?await db.Repositories.FindAsync(p.RepositoryId):null;Check.That(primary!=null,"尚未关联代码库",409);
  if(repositoryId==null||repositoryId==primary.Id)return primary;
  Check.That(!AgentApi.IsAgent(c),"此仓库不在该 Agent 项目授权范围内",403);
  var selected=await db.Repositories.FindAsync(repositoryId);Check.That(selected!=null&&(selected.ParentRepositoryId??selected.Id)==(primary.ParentRepositoryId??primary.Id),"代码库不属于本项目应用",403);return selected;
 }
 public static string Ref(string? value,string fallback){var v=string.IsNullOrWhiteSpace(value)?fallback:value.Trim();Check.That(v.Length<=250&&!v.Contains("..")&&!v.Contains("@{")&&!v.Any(char.IsWhiteSpace)&&!v.StartsWith('/')&&!v.Any(x=>"\\:?#".Contains(x)),"分支或版本格式无效");return v;}
 static string Path(string value,bool empty=false){Check.That(value!=null&&value.Length<=1000&&!value.StartsWith('/')&&!value.Contains('\\')&&(empty&&value==""||!value.Split('/').Any(x=>x is "." or ".." or "")),"文件路径无效");return string.Join('/',value.Split('/').Select(Uri.EscapeDataString));}
 static IEnumerable<JsonElement> Items(JsonElement x)=>x.ValueKind==JsonValueKind.Array?x.EnumerateArray().ToArray():[];
 static string S(JsonElement x,string key)=>x.ValueKind==JsonValueKind.Object&&x.TryGetProperty(key,out var v)&&v.ValueKind==JsonValueKind.String?v.GetString()??"":"";
 static object Person(JsonElement x)=>new{login=S(x,"login"),name=S(x,"full_name")};
 static object Pull(JsonElement p)=>new{number=p.GetProperty("number").GetInt64(),title=S(p,"title"),body=S(p,"body"),state=p.GetProperty("merged").GetBoolean()?"merged":S(p,"state"),head=S(p.GetProperty("head"),"ref"),sha=S(p.GetProperty("head"),"sha"),@base=S(p.GetProperty("base"),"ref"),author=Person(p.GetProperty("user")),updatedAt=S(p,"updated_at"),mergeable=p.TryGetProperty("mergeable",out var m)&&m.ValueKind==JsonValueKind.True};
 static void Number(long number)=>Check.That(number>0,"申请编号无效");
 public static void Map(WebApplication app){
  var api=app.MapGroup("/api/collaboration/projects/{id:guid}/code").RequireRateLimiting("collaboration");
  api.MapGet("/catalog",async(Guid id,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var repo=await Resolve(id,null,c,db,access);var family=repo.ParentRepositoryId??repo.Id;
   return new{primaryId=repo.Id,repositories=await db.Repositories.Where(r=>r.Id==family||r.ParentRepositoryId==family).OrderBy(r=>r.ParentRepositoryId!=null).ThenBy(r=>r.Name).Select(r=>new{r.Id,r.Name,r.FullName,r.ModulePath,r.PrototypeAuthor,r.ParentRepositoryId,r.MainBranch,r.AutomationEnabled}).ToArrayAsync()};
  });
  api.MapGet("/branches",async(Guid id,Guid? repositoryId,int? page,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{var r=await Resolve(id,repositoryId,c,db,access);var n=Math.Clamp(page??1,1,10000);var data=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/branches?limit=50&page={n}");return new{page=n,hasMore=data.GetArrayLength()==50,items=data.EnumerateArray().Select(x=>new{name=S(x,"name"),sha=S(x.GetProperty("commit"),"id")})};});
  api.MapGet("/tree",async(Guid id,Guid? repositoryId,string? reference,string? path,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var r=await Resolve(id,repositoryId,c,db,access);var branch=Ref(reference,r.MainBranch);var files=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/contents/{Path(path??"",true)}?ref={Uri.EscapeDataString(branch)}");Check.That(files.ValueKind==JsonValueKind.Array,"请选择文件夹");
   var family=r.ParentRepositoryId??r.Id;var modules=await db.Repositories.Where(x=>x.ParentRepositoryId==family).ToArrayAsync();
   return files.EnumerateArray().Select(f=>new{name=S(f,"name"),path=S(f,"path"),type=S(f,"type"),size=f.GetProperty("size").GetInt64(),moduleRepositoryId=modules.FirstOrDefault(x=>x.ModulePath==S(f,"path")&&r.Id==family)?.Id,sha=S(f,"sha")}).OrderBy(f=>f.type=="dir"?0:1).ThenBy(f=>f.name);
  });
  api.MapGet("/file",async(Guid id,Guid? repositoryId,string? reference,string path,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var r=await Resolve(id,repositoryId,c,db,access);var branch=Ref(reference,r.MainBranch);var data=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/contents/{Path(path)}?ref={Uri.EscapeDataString(branch)}");
   Check.That(data.ValueKind==JsonValueKind.Object&&S(data,"type")=="file","请选择普通文件");Check.That(data.GetProperty("size").GetInt64()<=524288,"文件超过512KB，请使用本地 Agent 查看",413);
   Check.That(S(data,"encoding")=="base64"&&data.TryGetProperty("content",out _),"此文件暂不支持在线查看",415);var bytes=Convert.FromBase64String(S(data,"content"));Check.That(!bytes.Contains((byte)0),"二进制文件请使用本地 Agent 查看",415);
   string text;try{text=new UTF8Encoding(false,true).GetString(bytes);}catch(DecoderFallbackException){throw new Fault(415,"仅支持在线查看 UTF-8 文本");}
   return new{path,sha=S(data,"sha"),branch,content=text,prototypeAuthor=r.PrototypeAuthor};
  });
  api.MapGet("/commits",async(Guid id,Guid? repositoryId,string? reference,string? path,int? page,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var r=await Resolve(id,repositoryId,c,db,access);var n=Math.Clamp(page??1,1,10000);if(!string.IsNullOrEmpty(path))Path(path);var data=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/commits?sha={Uri.EscapeDataString(Ref(reference,r.MainBranch))}&path={Uri.EscapeDataString(path??"")}&limit=20&page={n}&stat=false&files=false");
   return new{page=n,hasMore=data.GetArrayLength()==20,items=data.EnumerateArray().Select(x=>new{sha=S(x,"sha"),message=S(x.GetProperty("commit"),"message"),author=S(x.GetProperty("commit").GetProperty("author"),"name"),date=S(x.GetProperty("commit").GetProperty("author"),"date")})};
  });
  api.MapGet("/commits/{sha}",async(Guid id,string sha,Guid? repositoryId,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var r=await Resolve(id,repositoryId,c,db,access);Check.That(Regex.IsMatch(sha,"^[a-fA-F0-9]{40,64}$"),"提交编号无效");var value=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/git/commits/{sha}");var diff=await git.Diff($"repos/{r.FullName}/git/commits/{sha}.diff");
   return new{sha,message=S(value.GetProperty("commit"),"message"),author=S(value.GetProperty("commit").GetProperty("author"),"name"),date=S(value.GetProperty("commit").GetProperty("author"),"date"),diff=diff.Text,truncated=diff.Truncated};
  });
  api.MapGet("/pulls",async(Guid id,Guid? repositoryId,string? state,int? page,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{var r=await Resolve(id,repositoryId,c,db,access);Check.That(state==null||state is "open" or "closed" or "all","申请状态无效");var n=Math.Clamp(page??1,1,10000);var data=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/pulls?state={state??"all"}&sort=recentupdate&limit=20&page={n}");return new{page=n,hasMore=data.GetArrayLength()==20,items=data.EnumerateArray().Select(Pull)};});
  api.MapGet("/pulls/{number:long}",async(Guid id,long number,Guid? repositoryId,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var r=await Resolve(id,repositoryId,c,db,access);Number(number);var p=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/pulls/{number}");var sha=S(p.GetProperty("head"),"sha");var diff=await git.Diff($"repos/{r.FullName}/pulls/{number}.diff");
   var reviews=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/pulls/{number}/reviews?limit=50");var checks=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/commits/{sha}/status");
   return new{pull=Pull(p),diff=diff.Text,truncated=diff.Truncated,reviews=Items(reviews).Select(x=>new{body=S(x,"body"),state=S(x,"state"),author=Person(x.GetProperty("user")),sha=S(x,"commit_id"),date=S(x,"submitted_at")}),checks=Items(checks.GetProperty("statuses")).Select(x=>new{context=S(x,"context"),state=S(x,"status"),description=S(x,"description")}),r.AutomationEnabled};
  });
  api.MapPost("/pulls",async(Guid id,Guid? repositoryId,PullInput input,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);var r=await Resolve(id,repositoryId,c,db,access);var head=Ref(input.Head,"");Check.That(!string.IsNullOrEmpty(head)&&head!=r.MainBranch,"请选择开发分支");
   if(input.TaskId is Guid task){var t=await db.Tasks.SingleOrDefaultAsync(x=>x.Id==task&&x.ProjectId==id)??throw new Fault(404,"任务不存在");var p=await db.Projects.FindAsync(id);Check.That(p!.RepositoryId==r.Id&&head=="task/"+t.Id,"请选择此任务对应的开发分支");}
   var row=await git.UserCall(actor,HttpMethod.Post,$"repos/{r.FullName}/pulls",new{title=Check.Text(input.Title,1,200),body=Check.Text(input.Body,0,8000)+(input.TaskId.HasValue?"\n\n任务："+input.TaskId:""),head,@base=r.MainBranch});access.Audit(actor,id,"code.pull.create",r.FullName+"#"+row.GetProperty("number"));await db.SaveChangesAsync();return Pull(row);
  });
  api.MapPost("/pulls/{number:long}/reviews",async(Guid id,long number,Guid? repositoryId,ReviewInput input,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);var r=await Resolve(id,repositoryId,c,db,access);Number(number);Check.That(input.Event is "COMMENT" or "APPROVED" or "REQUEST_CHANGES","审核类型无效");var p=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/pulls/{number}");Check.That(S(p.GetProperty("head"),"sha")==input.HeadSha,"申请已有新提交，请刷新后审核",409);
   await git.UserCall(actor,HttpMethod.Post,$"repos/{r.FullName}/pulls/{number}/reviews",new{body=Check.Text(input.Body,1,8000),commit_id=input.HeadSha,@event=input.Event});access.Audit(actor,id,"code.pull.review",r.FullName+"#"+number+":"+input.Event);await db.SaveChangesAsync();return Results.NoContent();
  });
  api.MapPost("/pulls/{number:long}/merge",async(Guid id,long number,Guid? repositoryId,MergeInput input,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);await access.Owner(id,actor);var r=await Resolve(id,repositoryId,c,db,access);Number(number);Check.That(r.AutomationEnabled,"此仓库尚未启用自动检查，暂不能合并",409);
   var p=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/pulls/{number}");Check.That(S(p.GetProperty("head"),"sha")==input.HeadSha,"申请已有新提交，请刷新后再合并",409);Check.That(S(p.GetProperty("base"),"ref")==r.MainBranch,"只能合并到受保护主分支");
   var checks=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/commits/{input.HeadSha}/status");Check.That(Items(checks.GetProperty("statuses")).Any(x=>S(x,"context")=="siyue/ci"&&S(x,"status")=="success"),"当前提交的自动检查尚未通过",409);
   await git.UserCall(actor,HttpMethod.Post,$"repos/{r.FullName}/pulls/{number}/merge",new{@do="squash",head_commit_id=input.HeadSha,force_merge=false,merge_when_checks_succeed=false,delete_branch_after_merge=false});access.Audit(actor,id,"code.pull.merge",r.FullName+"#"+number+":"+input.HeadSha);await db.SaveChangesAsync();return Results.NoContent();
  });
  api.MapPost("/files",async(Guid id,FileInput input,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);var r=await Resolve(id,null,c,db,access);var task=await db.Tasks.SingleOrDefaultAsync(x=>x.Id==input.TaskId&&x.ProjectId==id)??throw new Fault(404,"任务不存在");var path=Path(input.Path);Check.That(input.Content!=null&&Encoding.UTF8.GetByteCount(input.Content)<=524288,"在线文件修改限512KB",413);
   var branch="task/"+task.Id;Check.That(input.Branch==null||input.Branch==r.MainBranch||input.Branch==branch,"只能从主分支或此任务分支提交修改");
   var existing=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/branches/{Uri.EscapeDataString(branch)}",missing:true);var fresh=existing.ValueKind==JsonValueKind.Undefined;
   if(!fresh&&input.Sha==null){var found=await git.Call(HttpMethod.Get,$"repos/{r.FullName}/contents/{path}?ref={Uri.EscapeDataString(branch)}",missing:true);Check.That(found.ValueKind==JsonValueKind.Undefined,"任务分支已存在此文件，请先打开该分支中的文件再修改",409);}
   var result=await git.UserCall(actor,input.Sha==null?HttpMethod.Post:HttpMethod.Put,$"repos/{r.FullName}/contents/{path}",new{content=Convert.ToBase64String(Encoding.UTF8.GetBytes(input.Content)),message=Check.Text(input.Message,1,500)+"\n\nTask: "+task.Id,sha=input.Sha,branch=fresh?r.MainBranch:branch,new_branch=fresh?branch:null});
   access.Audit(actor,id,"code.file.commit",r.FullName+":"+branch+":"+input.Path);await db.SaveChangesAsync();return new{branch,sha=S(result.GetProperty("commit"),"sha")};
  });
 }
}
