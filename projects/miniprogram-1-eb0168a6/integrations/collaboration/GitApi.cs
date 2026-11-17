using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Siyue.Collaboration;
public record RepositoryInput(string Name,string DeploymentKey,Guid? ExistingId=null);
public record AcceptInput(Guid BuildId,bool Passed,string Notes);
public record ReleaseInput(Guid BuildId,string Notes,string DatabaseChanges="none",string RecoveryPlan="");
public static class GitApi {
 public static void Map(WebApplication app){
  var api=app.MapGroup("/api/collaboration");
  api.MapGet("/repositories",async(HttpContext c,CollaborationDb db)=>{Check.Admin(Check.Actor(c));return await db.Repositories.OrderBy(r=>r.Name).ToArrayAsync();});
  api.MapPost("/projects/{id:guid}/repository",async(Guid id,RepositoryInput input,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);Check.Admin(actor);var p=await access.Owner(id,actor);Check.That(p.RepositoryId==null,"项目已绑定代码库，请勿覆盖",409);
   Repository repo;if(input.ExistingId is Guid existing){repo=await db.Repositories.FindAsync(existing)??throw new Fault(404,"代码库不存在");Check.That(!p.Confidential&&!await db.Projects.AnyAsync(x=>x.RepositoryId==existing&&x.Confidential),"保密项目使用独立代码库");}
   else{Check.That(Regex.IsMatch(input.Name??"",@"^[a-z][a-z0-9-]{2,47}$")&&Regex.IsMatch(input.DeploymentKey??"",@"^[a-z][a-z0-9-]{2,47}$"),"代码库名与部署标识应使用3–48位小写字母、数字或连字符");repo=new(){Name=input.Name!,FullName=(app.Configuration["Gitea:Owner"]??"siyue-projects")+"/"+input.Name,DeploymentKey=input.DeploymentKey!};db.Repositories.Add(repo);await db.SaveChangesAsync();await git.Provision(repo);}
   p.RepositoryId=repo.Id;p.Revision++;await db.SaveChangesAsync();await git.SyncProject(p);access.Audit(actor,id,"repository.bind",repo.FullName);await db.SaveChangesAsync();return repo;
  });
  api.MapGet("/projects/{id:guid}/development",async(Guid id,Guid? repositoryId,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var p=await access.Read(id,Check.Actor(c));Repository? repo=p.RepositoryId.HasValue?await CodeApi.Resolve(id,repositoryId,c,db,access):null;
   var parent=repo?.ParentRepositoryId is Guid parentId?await db.Repositories.FindAsync(parentId):null;
   return new{repository=repo,parentCodeUrl=parent==null?null:git.PublicUrl+"/"+parent.FullName,parentName=parent?.Name,codeUrl=repo==null?null:git.PublicUrl+"/"+repo.FullName,builds=await db.Builds.Where(b=>b.RepositoryId==(repo==null?null:(Guid?)repo.Id)).OrderByDescending(b=>b.CreatedAt).Take(60).ToArrayAsync(),changes=await db.Changes.Where(b=>b.RepositoryId==(repo==null?null:(Guid?)repo.Id)).OrderByDescending(b=>b.UpdatedAt).Take(60).ToArrayAsync(),acceptances=await db.Acceptances.Where(a=>a.ProjectId==id).OrderByDescending(a=>a.CreatedAt).Take(60).ToArrayAsync(),releases=await db.Releases.Where(r=>r.ProjectId==id).OrderByDescending(r=>r.CreatedAt).Take(60).ToArrayAsync(),p.TestingBuildId,p.LockedBuildId,p.ProductionBuildId};
  });
  api.MapPost("/projects/{id:guid}/builds/{buildId:guid}/retry",async(Guid id,Guid buildId,HttpContext c,CollaborationDb db,ProjectAccess access,Delivery delivery)=>{
   var actor=Check.Actor(c);var build=await db.Builds.FindAsync(buildId)??throw new Fault(404,"检查记录不存在");var repo=await CodeApi.Resolve(id,build.RepositoryId,c,db,access);Check.That(repo.AutomationEnabled&&build.State is "failed" or "passed","当前检查不需要重试",409);
   await delivery.Call("/retry",new{id=buildId,key=repo.DeploymentKey,kind=build.State=="failed"?"build":"test"});if(build.State=="failed"){build.State="queued";build.ArtifactDigest="";build.FinishedAt=null;}build.Summary="已请求重试";access.Audit(actor,id,"build.retry",build.Id.ToString());await db.SaveChangesAsync();return Results.NoContent();
  });
  api.MapGet("/projects/{id:guid}/builds/{buildId:guid}/log",async(Guid id,Guid buildId,HttpContext c,CollaborationDb db,ProjectAccess access,Delivery delivery)=>{
   var build=await db.Builds.FindAsync(buildId)??throw new Fault(404,"检查记录不存在");var repo=await CodeApi.Resolve(id,build.RepositoryId,c,db,access);return await delivery.Call("/logs",new{id=buildId,key=repo.DeploymentKey});
  });
  api.MapGet("/projects/{id:guid}/tasks/{taskId:guid}/agent",async(Guid id,Guid taskId,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var p=await access.Read(id,Check.Actor(c));var task=await db.Tasks.SingleOrDefaultAsync(t=>t.Id==taskId&&t.ProjectId==id)??throw new Fault(404,"任务不存在");var repo=p.RepositoryId.HasValue?await db.Repositories.FindAsync(p.RepositoryId):null;Check.That(repo!=null,"请先绑定代码库",409);
   var parent=repo.ParentRepositoryId.HasValue?await db.Repositories.FindAsync(repo.ParentRepositoryId):null;var context=parent==null?"":$"主仓库：{git.PublicUrl}/{parent.FullName}.git\n模块位置：{repo.ModulePath}\n请递归克隆主仓库，在对应模块目录建立开发分支；在主仓库执行 python3 tools/sync-modules.py 后进行整体测试，避免编辑生成的兼容路径。\n";
   return new{text=$"平台操作说明：https://www.siyuejn.com/miniapp/collaboration/agent/\n项目：{p.Name}\n任务：{task.Id}\n目标：{task.Title}\n需求：{task.Description}\n验收标准：{task.Criteria}\n代码库：{git.PublicUrl}/{repo.FullName}.git\n基准分支：{repo.MainBranch}\n开发分支：task/{task.Id}\n{context}测试命令：{repo.TestCommand}\n请先读取项目的 AGENTS.md 与 README，使用本人的 Git 凭证开发；不要索取、保存或提交账号密码。修改后运行测试，提交到上述任务分支，并创建指向主分支的修改申请，在正文写明任务编号、改动说明、测试结果和 Agent 名称。提交信息使用 Agent: <名称> 标记。不得直接推送主分支、绕过检查或修改生产部署。遇到冲突先同步主分支，不能强制覆盖同事代码。"};
  });
  api.MapPost("/projects/{id:guid}/testing/lock",async(Guid id,AcceptInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);await using var tx=await db.Database.BeginTransactionAsync();var p=await CollaborationApi.LockProject(db,id);await access.Owner(id,actor);Check.That(p.TestingBuildId==input.BuildId,"测试版本已变化，请刷新",409);p.LockedBuildId=input.BuildId;p.Revision++;access.Audit(actor,id,"testing.lock",input.BuildId.ToString());await db.SaveChangesAsync();await tx.CommitAsync();return Results.NoContent();
  });
  api.MapPost("/projects/{id:guid}/testing/unlock",async(Guid id,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);await using var tx=await db.Database.BeginTransactionAsync();var p=await CollaborationApi.LockProject(db,id);await access.Owner(id,actor);p.LockedBuildId=null;p.Revision++;access.Audit(actor,id,"testing.unlock");await db.SaveChangesAsync();await tx.CommitAsync();return Results.NoContent();
  });
  api.MapPost("/projects/{id:guid}/acceptances",async(Guid id,AcceptInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);await using var tx=await db.Database.BeginTransactionAsync();var p=await CollaborationApi.LockProject(db,id);await access.Owner(id,actor);
   Check.That(p.TestingBuildId==input.BuildId&&p.LockedBuildId==input.BuildId,"请先锁定当前测试版本再验收",409);var build=await db.Builds.FindAsync(input.BuildId)??throw new Fault(404,"版本不存在");Check.That(build.RepositoryId==p.RepositoryId&&build.State=="tested"&&!string.IsNullOrEmpty(build.ArtifactDigest),"版本尚未通过测试部署",409);
   var row=new Acceptance{ProjectId=id,BuildId=build.Id,StaffId=actor.Id,Sha=build.Sha,ArtifactDigest=build.ArtifactDigest,Passed=input.Passed,Notes=Check.Text(input.Notes,1,4000)};db.Acceptances.Add(row);
   foreach(var member in await db.Members.Where(m=>m.ProjectId==id).ToArrayAsync())access.Notify(member.StaffId,id,build.Id,input.Passed?"accepted":"rejected",input.Passed?"负责人已验收，等待申请正式发布":"测试验收退回，请查看反馈","accept:"+row.Id+":"+member.StaffId);
   access.Audit(actor,id,input.Passed?"acceptance.pass":"acceptance.reject",build.Sha);await db.SaveChangesAsync();await tx.CommitAsync();return row;
  });
  api.MapPost("/projects/{id:guid}/releases",async(Guid id,ReleaseInput input,HttpContext c,CollaborationDb db,ProjectAccess access,IIdentityDirectory identity)=>{
   var actor=Check.Actor(c);await access.Owner(id,actor);await RequireAccepted(db,id,input.BuildId);Check.That(input.DatabaseChanges=="none"||!string.IsNullOrWhiteSpace(input.RecoveryPlan),"数据库变更必须填写备份及恢复方案");
   var pending=await db.Releases.SingleOrDefaultAsync(r=>r.ProjectId==id&&r.BuildId==input.BuildId&&(r.State=="requested"||r.State=="queued"||r.State=="publishing"));if(pending!=null)return pending;
   var row=new Release{ProjectId=id,BuildId=input.BuildId,RequestedBy=actor.Id,Notes=Check.Text(input.Notes,1),DatabaseChanges=Check.Text(input.DatabaseChanges,1),RecoveryPlan=Check.Text(input.RecoveryPlan)};db.Releases.Add(row);
   foreach(var admin in (await identity.People()).Where(p=>p.Enabled&&p.Role=="admin"))access.Notify(admin.Id,id,row.Id,"release_requested","新版本已申请正式发布，请核对验收记录","release-request:"+row.Id+":"+admin.Id);
   access.Audit(actor,id,"release.request",input.BuildId.ToString());await db.SaveChangesAsync();return row;
  });
  api.MapPost("/projects/{id:guid}/releases/{releaseId:guid}/publish",async(Guid id,Guid releaseId,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);Check.Admin(actor);await using var tx=await db.Database.BeginTransactionAsync();await CollaborationApi.LockProject(db,id);await access.Owner(id,actor);var row=await db.Releases.SingleOrDefaultAsync(r=>r.Id==releaseId&&r.ProjectId==id)??throw new Fault(404,"发布申请不存在");
   Check.That(row.State=="requested","该申请已被处理",409);await RequireAccepted(db,id,row.BuildId);Check.That(row.DatabaseChanges=="none","数据库迁移需先由管理员完成独立演练和备份；本入口仅发布无迁移的构建产物",409);
   row.State="queued";row.PublishedBy=actor.Id;access.Audit(actor,id,"release.approve",releaseId.ToString());await db.SaveChangesAsync();await tx.CommitAsync();return row;
  });
  api.MapPost("/projects/{id:guid}/rollback",async(Guid id,ReleaseInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);Check.Admin(actor);await access.Owner(id,actor);Check.That(await db.Releases.AnyAsync(r=>r.ProjectId==id&&r.BuildId==input.BuildId&&r.State=="published"&&r.DatabaseChanges=="none"),"只能回退到本项目成功发布且无数据库迁移的版本",409);
   var row=new Release{ProjectId=id,BuildId=input.BuildId,RequestedBy=actor.Id,PublishedBy=actor.Id,State="queued",IsRollback=true,Notes=Check.Text(input.Notes,1)};db.Releases.Add(row);access.Audit(actor,id,"release.rollback",input.BuildId.ToString());await db.SaveChangesAsync();return row;
  });
  app.MapPost("/api/collaboration/hooks/gitea",async(HttpContext c,CollaborationDb db,IConfiguration config)=>{
   var secret=config["Gitea:WebhookSecret"];Check.That(!string.IsNullOrEmpty(secret),"事件入口未配置",503);Check.That(c.Request.ContentLength is null or <=262144,"事件过大",413);
   using var stream=new MemoryStream();var buffer=new byte[8192];int n;while((n=await c.Request.Body.ReadAsync(buffer))>0){Check.That(stream.Length+n<=262144,"事件过大",413);await stream.WriteAsync(buffer.AsMemory(0,n));}var bytes=stream.ToArray();
   var expected=Convert.ToHexStringLower(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret),bytes));Check.That(Check.Same(expected,c.Request.Headers["X-Gitea-Signature"].ToString()),"事件签名无效",401);
   var delivery=c.Request.Headers["X-Gitea-Delivery"].ToString();var kind=c.Request.Headers["X-Gitea-Event"].ToString();Check.That(delivery.Length is >0 and <=100&&new[]{"push","pull_request","ping"}.Contains(kind),"事件类型无效");
   if(await db.GitEvents.AnyAsync(x=>x.Delivery==delivery))return Results.Ok(new{duplicate=true});using var json=JsonDocument.Parse(bytes);
   db.GitEvents.Add(new(){Delivery=delivery,Kind=kind,Payload=Encoding.UTF8.GetString(bytes)});await db.SaveChangesAsync();return Results.Accepted();
  });
 }
 public static async Task RequireAccepted(CollaborationDb db,Guid projectId,Guid buildId){
  var project=await db.Projects.FindAsync(projectId)??throw new Fault(404,"项目不存在");var build=await db.Builds.FindAsync(buildId)??throw new Fault(404,"版本不存在");
  Check.That(build.RepositoryId==project.RepositoryId&&build.State=="tested"&&project.TestingBuildId==buildId,"只能发布当前已测试的版本",409);
  var linked=await db.Projects.Where(p=>p.RepositoryId==project.RepositoryId).ToArrayAsync();
  foreach(var p in linked){var acceptance=await db.Acceptances.Where(a=>a.ProjectId==p.Id&&a.BuildId==buildId).OrderByDescending(a=>a.CreatedAt).FirstOrDefaultAsync();Check.That(acceptance is {Passed:true}&&acceptance.Sha==build.Sha&&acceptance.ArtifactDigest==build.ArtifactDigest,$"项目「{p.Name}」尚未验收此版本",409);}
 }
}
