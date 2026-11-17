using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Siyue.Collaboration;

public record ProjectInput(string Name,string? Goal,string? Approach,string? Department,string? Summary,Guid? OwnerId,bool Confidential=false,long Revision=0,string Status="planned",string? OwnerTeam=null);
public record TaskInput(string Title,Guid? OwnerId,string? Description=null,string Kind="task",Guid? MilestoneId=null,Guid[]? Collaborators=null,string State="planned",DateOnly? StartsOn=null,DateOnly? DueOn=null,string? Criteria=null,string? Blocker=null,Guid? NeedsStaffId=null,long Revision=0);
public record ProgressInput(string Body,string? Next,Guid? TaskId,string RequestId,Guid? ActionId=null,Guid? ParentId=null);
public record MemberInput(Guid StaffId);
public record RevisionInput(long Revision);
public static class CollaborationApi {
 public static readonly string[] States=["planned","in_progress","blocked","review","done"];
 public static Guid[] Mentions(params string[] texts)=>Regex.Matches(string.Join('\n',texts),@"@\[[^\]\r\n]{1,100}\]\(staff:([0-9a-fA-F-]{36})\)").Select(x=>Guid.TryParse(x.Groups[1].Value,out var id)?id:Guid.Empty).Where(x=>x!=Guid.Empty).Distinct().ToArray();
 static void NotifyMentions(ProjectAccess a,Guid[] ids,Guid project,Guid? target,Person actor,string key){foreach(var id in ids.Where(id=>id!=actor.Id))a.Notify(id,project,target,"mention",$"{actor.Name} 在协作项目中提及你",key+":"+id);}
 static IQueryable<Notification> RecentNotifications(CollaborationDb db,Guid staffId)=>db.Notifications.AsNoTracking()
    .Where(n=>n.StaffId==staffId).OrderByDescending(n=>n.CreatedAt).Take(200);
 public static async Task<Project> LockProject(CollaborationDb db,Guid id) {
  // Row locks serialize membership, repository and deployment changes across service instances.
  if(db.Database.IsNpgsql())return await db.Projects.FromSqlInterpolated($"SELECT * FROM \"Projects\" WHERE \"Id\"={id} FOR UPDATE").SingleAsync();
  return await db.Projects.SingleAsync(p=>p.Id==id);
 }
 public static void Map(WebApplication app){
  var api=app.MapGroup("/api/collaboration").RequireRateLimiting("collaboration");
  api.MapGet("/me",(HttpContext c,IConfiguration config)=>new{user=Check.Actor(c),codeUrl=config["Gitea:PublicUrl"],version="20261115-inline"});
  api.MapGet("/people",async(HttpContext c,IIdentityDirectory identity,CollaborationDb db,ProjectAccess access,Guid? projectId)=>{
   if(projectId.HasValue)await access.Read(projectId.Value,Check.Actor(c));
   var people=(await identity.People(c.RequestAborted)).Where(x=>x.Enabled).ToArray();var profiles=await db.People.ToDictionaryAsync(x=>x.StaffId,x=>x.Department);
   var members=projectId.HasValue?await db.Members.Where(x=>x.ProjectId==projectId).Select(x=>x.StaffId).ToArrayAsync():[];
   return people.Select(x=>new{x.Id,x.Name,x.Account,department=profiles.GetValueOrDefault(x.Id,"待补充"),member=members.Contains(x.Id)}).OrderByDescending(x=>x.member).ThenBy(x=>x.Name);
  });
  api.MapPut("/people/{id:guid}/department",async(Guid id,Dictionary<string,string> input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   Check.Admin(Check.Actor(c));await access.RequirePeople([id]);var p=await db.People.SingleOrDefaultAsync(x=>x.StaffId==id);if(p==null){p=new(){StaffId=id};db.People.Add(p);}p.Department=Check.Text(input.GetValueOrDefault("department"),0,100);await db.SaveChangesAsync();return Results.NoContent();
  });
  api.MapGet("/projects",async(HttpContext c,CollaborationDb db)=>{
   var actor=Check.Actor(c);var memberships=await db.Members.Where(m=>m.StaffId==actor.Id).Select(m=>m.ProjectId).ToArrayAsync();
   var rows=await db.Projects.AsNoTracking().Where(p=>!p.Confidential||actor.Role=="admin"||p.OwnerId==actor.Id||memberships.Contains(p.Id)).OrderByDescending(x=>x.UpdatedAt).ToArrayAsync();
   if(AgentApi.IsAgent(c))rows=rows.Where(p=>AgentApi.Scope(c).Contains(p.Id)&&(actor.Role=="admin"||p.OwnerId==actor.Id||memberships.Contains(p.Id))).ToArray();
   var repos=await db.Repositories.AsNoTracking().ToDictionaryAsync(r=>r.Id);
   var projectIds=rows.Select(p=>p.Id).ToArray();var tasks=await db.Tasks.AsNoTracking().Where(t=>projectIds.Contains(t.ProjectId)&&t.Kind=="milestone").ToArrayAsync();
   return rows.Select(p=>new{p.Id,p.Name,p.Goal,p.Department,p.OwnerId,p.OwnerTeam,p.Confidential,p.Status,p.Summary,p.Revision,p.UpdatedAt,repository=(actor.Role=="admin"||p.OwnerId==actor.Id||memberships.Contains(p.Id))&&p.RepositoryId is Guid rid&&repos.TryGetValue(rid,out var repo)?new{repo.Id,repo.FullName,repo.ParentRepositoryId,repo.ModulePath,repo.PrototypeAuthor,familyNames=repos.Values.Where(r=>r.Id==(repo.ParentRepositoryId??repo.Id)||r.ParentRepositoryId==(repo.ParentRepositoryId??repo.Id)).Select(r=>r.FullName).ToArray()}:null,member=actor.Role=="admin"||p.OwnerId==actor.Id||memberships.Contains(p.Id),completed=tasks.Count(t=>t.ProjectId==p.Id&&t.State=="done"),total=tasks.Count(t=>t.ProjectId==p.Id),nextTitle=tasks.Where(t=>t.ProjectId==p.Id&&t.State!="done").OrderBy(t=>t.DueOn??DateOnly.MaxValue).ThenBy(t=>t.State=="blocked"?0:t.State=="in_progress"?1:t.State=="review"?2:3).ThenBy(t=>t.Title).Select(t=>t.Title).FirstOrDefault(),nextDue=tasks.Where(t=>t.ProjectId==p.Id&&t.State!="done"&&t.DueOn.HasValue).OrderBy(t=>t.DueOn).Select(t=>t.DueOn).FirstOrDefault()});
  });
  api.MapPost("/projects",async(ProjectInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);var owner=input.OwnerId??actor.Id;if(owner!=actor.Id)Check.Admin(actor);await access.RequirePeople([owner]);
   var p=new Project{Name=Check.Text(input.Name,1,120),Goal=Check.Text(input.Goal),Approach=Check.Text(input.Approach),Department=Check.Text(input.Department,0,100),Summary=Check.Text(input.Summary),OwnerId=owner,OwnerTeam=Check.Text(input.OwnerTeam??"AI创新实验室",0,80),Confidential=input.Confidential};
   var mentioned=Mentions(p.Goal,p.Approach,p.Summary);await access.RequirePeople(mentioned);
   db.Projects.Add(p);db.Members.Add(new(){ProjectId=p.Id,StaffId=owner,Role="owner"});NotifyMentions(access,mentioned,p.Id,null,actor,"project:"+p.Id+":1");access.Audit(actor,p.Id,"project.create");await db.SaveChangesAsync();return p;
  });
  api.MapGet("/projects/{id:guid}",async(Guid id,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);var p=await access.Read(id,actor);var members=await db.Members.Where(m=>m.ProjectId==id).ToArrayAsync();var tasks=await db.Tasks.Where(t=>t.ProjectId==id).OrderBy(t=>t.DueOn).ThenBy(t=>t.Title).ToArrayAsync();
   return new{project=p,members,tasks,actions=await db.TaskActions.Where(x=>x.ProjectId==id).OrderBy(x=>x.PlannedOn).ThenBy(x=>x.CreatedAt).ToArrayAsync(),progress=await db.Progress.Where(x=>x.ProjectId==id).OrderByDescending(x=>x.CreatedAt).Take(300).ToArrayAsync(),attachments=await db.Attachments.Where(x=>x.ProjectId==id).OrderByDescending(x=>x.CreatedAt).ToArrayAsync(),canManage=actor.Role=="admin"||p.OwnerId==actor.Id};
  });
  api.MapPut("/projects/{id:guid}",async(Guid id,ProjectInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);var p=await access.Owner(id,actor);Check.That(p.Revision==input.Revision,"项目已被修改，请刷新后再保存",409);
   Check.That(new[]{"planned","active","blocked","completed","archived"}.Contains(input.Status),"项目状态无效");
   Check.That(!input.Confidential||p.RepositoryId==null||!await db.Projects.AnyAsync(x=>x.Id!=id&&x.RepositoryId==p.RepositoryId),"共用代码库的项目不能改为保密，请先使用独立代码库",409);
   // Ownership transfer is intentionally separate from content editing.
   Check.That(input.OwnerId==null||input.OwnerId==p.OwnerId,"请通过成员管理移交负责人");
   if(input.OwnerTeam!=null)p.OwnerTeam=Check.Text(input.OwnerTeam??"AI创新实验室",0,80);p.Name=Check.Text(input.Name,1,120);p.Goal=Check.Text(input.Goal);p.Approach=Check.Text(input.Approach);p.Department=Check.Text(input.Department,0,100);p.Summary=Check.Text(input.Summary);p.Confidential=input.Confidential;p.Status=input.Status;p.UpdatedAt=DateTime.UtcNow;p.Revision++;
   var mentioned=Mentions(p.Goal,p.Approach,p.Summary);await access.RequirePeople(mentioned);NotifyMentions(access,mentioned,p.Id,null,actor,$"project:{id}:{p.Revision}");access.Audit(actor,id,"project.update");await db.SaveChangesAsync();return p;
  });
  api.MapPost("/projects/{id:guid}/join-requests",async(Guid id,HttpContext c,CollaborationDb db,ProjectAccess access,IIdentityDirectory identity)=>{
   var actor=Check.Actor(c);var p=await access.Read(id,actor,false);var key=$"join:{id}:{actor.Id}";
   var recipients=p.OwnerId.HasValue?new[]{p.OwnerId.Value}:(await identity.People()).Where(x=>x.Enabled&&x.Role=="admin").Select(x=>x.Id).ToArray();
   foreach(var recipient in recipients){var eventKey=key+":"+recipient;if(!await db.Notifications.AnyAsync(n=>n.EventKey==eventKey))access.Notify(recipient,id,actor.Id,"join",$"{actor.Name} 申请加入项目：{p.Name}",eventKey);}await db.SaveChangesAsync();return Results.NoContent();
  });
  api.MapPost("/projects/{id:guid}/members",async(Guid id,MemberInput input,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);await using var tx=await db.Database.BeginTransactionAsync();var p=await LockProject(db,id);await access.Read(id,actor);if(p.Confidential)await access.Owner(id,actor);await access.RequirePeople([input.StaffId]);
   if(!await db.Members.AnyAsync(m=>m.ProjectId==id&&m.StaffId==input.StaffId)){
    var member=new Member{ProjectId=id,StaffId=input.StaffId};db.Members.Add(member);access.Notify(input.StaffId,id,null,"joined",$"{actor.Name} 邀请你加入项目",$"joined:{member.Id}");p.Revision++;p.UpdatedAt=DateTime.UtcNow;access.Audit(actor,id,"member.add",input.StaffId.ToString());await db.SaveChangesAsync();
   }await tx.CommitAsync();await git.SyncProject(p);return Results.NoContent();
  });
  api.MapDelete("/projects/{id:guid}/members/{staffId:guid}",async(Guid id,Guid staffId,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);await using var tx=await db.Database.BeginTransactionAsync();var p=await LockProject(db,id);await access.Owner(id,actor);Check.That(p.OwnerId!=staffId,"请先移交负责人");
   var member=await db.Members.SingleOrDefaultAsync(x=>x.ProjectId==id&&x.StaffId==staffId);if(member!=null){db.Members.Remove(member);p.Revision++;access.Audit(actor,id,"member.remove",staffId.ToString());await db.SaveChangesAsync();}
   // Revoke native repository permission before completing removal. Failures roll back the business change.
   await git.SyncProject(p);await tx.CommitAsync();return Results.NoContent();
  });
  api.MapPost("/projects/{id:guid}/owner",async(Guid id,MemberInput input,HttpContext c,CollaborationDb db,ProjectAccess access,Gitea git)=>{
   var actor=Check.Actor(c);await using var tx=await db.Database.BeginTransactionAsync();var p=await LockProject(db,id);await access.Owner(id,actor);await access.RequireMembers(id,[input.StaffId]);
   var old=await db.Members.SingleOrDefaultAsync(m=>m.ProjectId==id&&m.StaffId==p.OwnerId);if(old!=null)old.Role="contributor";var next=await db.Members.SingleAsync(m=>m.ProjectId==id&&m.StaffId==input.StaffId);next.Role="owner";p.OwnerId=input.StaffId;p.Revision++;
   access.Audit(actor,id,"owner.transfer",input.StaffId.ToString());await db.SaveChangesAsync();await git.SyncProject(p);await tx.CommitAsync();return Results.NoContent();
  });
  api.MapPost("/projects/{id:guid}/tasks",async(Guid id,TaskInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);await access.Read(id,actor);var t=new WorkTask{ProjectId=id};await SetTask(t,input,access,db);db.Tasks.Add(t);await TaskNotifications(t,actor,access);access.Audit(actor,id,"task.create",t.Id.ToString());await db.SaveChangesAsync();return t;
  });
  api.MapPut("/projects/{id:guid}/tasks/{taskId:guid}",async(Guid id,Guid taskId,TaskInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);await access.Read(id,actor);var t=await db.Tasks.SingleOrDefaultAsync(t=>t.ProjectId==id&&t.Id==taskId)??throw new Fault(404,"任务不存在");Check.That(t.Revision==input.Revision,"任务已被他人修改，请刷新后再保存",409);
   await SetTask(t,input,access,db);t.Revision++;await TaskNotifications(t,actor,access);access.Audit(actor,id,"task.update",t.Id.ToString());await db.SaveChangesAsync();return t;
  });
  api.MapPost("/projects/{id:guid}/progress",async(Guid id,ProgressInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);await access.Read(id,actor);Check.That(Guid.TryParse(input.RequestId,out _),"提交标识无效");var previous=await db.Progress.SingleOrDefaultAsync(x=>x.AuthorId==actor.Id&&x.RequestId==input.RequestId);
   if(previous!=null){Check.That(previous.ProjectId==id,"提交标识重复",409);return previous;}
   if(input.TaskId.HasValue)Check.That(await db.Tasks.AnyAsync(t=>t.Id==input.TaskId&&t.ProjectId==id),"任务不存在",404);
   TaskAction? action=null;Progress? parent=null;
   if(input.ActionId.HasValue){action=await db.TaskActions.SingleOrDefaultAsync(a=>a.Id==input.ActionId&&a.ProjectId==id)??throw new Fault(404,"节点动作不存在");Check.That(input.TaskId==null||input.TaskId==action.TaskId,"评论任务与节点不一致");}
   if(input.ParentId.HasValue){parent=await db.Progress.SingleOrDefaultAsync(x=>x.Id==input.ParentId&&x.ProjectId==id)??throw new Fault(404,"回复对象不存在");Check.That(input.ActionId.HasValue&&parent.ActionId==input.ActionId,"回复必须属于同一个节点");}
   var body=Check.Text(input.Body,1,8000);var next=Check.Text(input.Next);var mentions=Mentions(body,next);await access.RequirePeople(mentions);
   var row=new Progress{ProjectId=id,TaskId=action?.TaskId??input.TaskId,ActionId=input.ActionId,ParentId=input.ParentId,AuthorId=actor.Id,Body=body,Next=next,Mentions=JsonSerializer.Serialize(mentions),RequestId=input.RequestId};db.Progress.Add(row);NotifyMentions(access,mentions,id,input.ActionId??input.TaskId??row.Id,actor,"progress:"+row.Id);
   if(parent!=null&&parent.AuthorId!=actor.Id&&!mentions.Contains(parent.AuthorId))access.Notify(parent.AuthorId,id,input.ActionId,"action_reply",$"{actor.Name} 回复了你的节点评论","reply:"+row.Id);
   if(action!=null&&action.AuthorId!=actor.Id&&action.AuthorId!=parent?.AuthorId&&!mentions.Contains(action.AuthorId))access.Notify(action.AuthorId,id,action.Id,"action_comment",$"{actor.Name} 评论了节点「{action.Title}」","comment:"+row.Id);access.Audit(actor,id,"progress.create",row.Id.ToString());await db.SaveChangesAsync();return row;
  });
  api.MapGet("/notifications",async(HttpContext c,CollaborationDb db)=>{
   var actor=Check.Actor(c);
   var notes=RecentNotifications(db,actor.Id);
   return await (from note in notes
     join project in db.Projects.AsNoTracking() on note.ProjectId equals project.Id into matching
     from project in matching.DefaultIfEmpty()
     orderby note.CreatedAt descending
     select new {
      note.Id,note.StaffId,note.ProjectId,note.TargetId,note.Kind,note.Text,note.EventKey,note.CreatedAt,note.ReadAt,
      projectName=project!=null && (!project.Confidential || actor.Role=="admin" || project.OwnerId==actor.Id ||
          db.Members.Any(m=>m.ProjectId==project.Id&&m.StaffId==actor.Id)) ? project.Name : null
     }).ToArrayAsync(c.RequestAborted);
  });
  api.MapGet("/notifications/unread-count",async(HttpContext c,CollaborationDb db)=>new {
      count=await RecentNotifications(db,Check.Actor(c).Id).CountAsync(n=>n.ReadAt==null,c.RequestAborted)
  });
  api.MapPost("/notifications/{id:guid}/read",async(Guid id,HttpContext c,CollaborationDb db)=>{var actor=Check.Actor(c);await db.Notifications.Where(n=>n.Id==id&&n.StaffId==actor.Id&&n.ReadAt==null).ExecuteUpdateAsync(x=>x.SetProperty(n=>n.ReadAt,DateTime.UtcNow));return Results.NoContent();});
  api.MapGet("/todos",async(HttpContext c,CollaborationDb db)=>{var actor=Check.Actor(c);var ids=await db.Members.Where(m=>m.StaffId==actor.Id).Select(m=>m.ProjectId).ToArrayAsync();return await db.Tasks.Where(t=>(ids.Contains(t.ProjectId)||actor.Role=="admin")&&t.State!="done"&&(t.OwnerId==actor.Id||t.NeedsStaffId==actor.Id)).OrderBy(t=>t.DueOn).ToArrayAsync();});
  api.MapPost("/projects/{id:guid}/attachments",async(Guid id,HttpContext c,CollaborationDb db,ProjectAccess access,IConfiguration config)=>{
   var actor=Check.Actor(c);await access.Read(id,actor);Check.That(c.Request.HasFormContentType,"请选择附件");var form=await c.Request.ReadFormAsync();var f=form.Files.GetFile("file");Check.That(f!=null&&f.Length is >0 and <=10*1024*1024,"附件应小于10MB");
   Guid? taskId=Guid.TryParse(form["taskId"],out var tid)?tid:null;Guid? actionId=Guid.TryParse(form["actionId"],out var aid)?aid:null;
   if(actionId.HasValue){var action=await db.TaskActions.SingleOrDefaultAsync(a=>a.Id==actionId&&a.ProjectId==id)??throw new Fault(404,"节点动作不存在");Check.That(taskId==null||taskId==action.TaskId,"附件任务与节点不一致");taskId=action.TaskId;}if(taskId.HasValue)Check.That(await db.Tasks.AnyAsync(t=>t.Id==taskId&&t.ProjectId==id),"任务不存在",404);
   var name=Path.GetFileName(f.FileName);Check.That(new[]{".png",".jpg",".jpeg",".webp",".pdf",".docx",".xlsx",".csv",".txt",".md",".zip"}.Contains(Path.GetExtension(name).ToLowerInvariant()),"不支持此附件类型");
   using var ms=new MemoryStream();await f.CopyToAsync(ms);var bytes=ms.ToArray();var row=new Attachment{ProjectId=id,TaskId=taskId,ActionId=actionId,AuthorId=actor.Id,Name=Check.Text(name,1,200),Hash=Check.Hash(bytes),Size=bytes.Length};var root=config["Attachments:Directory"]??".local/attachments";Directory.CreateDirectory(root);await File.WriteAllBytesAsync(Path.Combine(root,row.Id.ToString("N")),bytes);db.Attachments.Add(row);await db.SaveChangesAsync();return row;
  }).DisableAntiforgery();
  api.MapGet("/projects/{id:guid}/attachments/{fileId:guid}",async(Guid id,Guid fileId,HttpContext c,CollaborationDb db,ProjectAccess access,IConfiguration config)=>{
   await access.Read(id,Check.Actor(c));var row=await db.Attachments.SingleOrDefaultAsync(a=>a.Id==fileId&&a.ProjectId==id)??throw new Fault(404,"附件不存在");return Results.File(Path.GetFullPath(Path.Combine(config["Attachments:Directory"]??".local/attachments",row.Id.ToString("N"))),"application/octet-stream",row.Name);
  });
  api.MapGet("/projects/{id:guid}/audit",async(Guid id,HttpContext c,CollaborationDb db,ProjectAccess access)=>{await access.Owner(id,Check.Actor(c));return await db.Audits.Where(a=>a.ProjectId==id).OrderByDescending(a=>a.CreatedAt).Take(200).ToArrayAsync();});
 }
 static async Task SetTask(WorkTask t,TaskInput i,ProjectAccess access,CollaborationDb db){
  Check.That(States.Contains(i.State)&&new[]{"task","milestone"}.Contains(i.Kind),"任务类型或状态无效");Check.That(!i.StartsOn.HasValue||!i.DueOn.HasValue||i.StartsOn<=i.DueOn,"截止日期不能早于开始日期");
  var collaborators=(i.Collaborators??[]).Distinct().ToArray();Check.That(collaborators.Length<=50,"协作者过多");await access.RequireMembers(t.ProjectId,i.OwnerId.HasValue?collaborators.Append(i.OwnerId.Value):collaborators);
  if(i.MilestoneId.HasValue)Check.That(i.MilestoneId!=t.Id&&await db.Tasks.AnyAsync(m=>m.Id==i.MilestoneId&&m.ProjectId==t.ProjectId&&m.Kind=="milestone"),"节点无效");
  if(i.NeedsStaffId.HasValue)await access.RequireMembers(t.ProjectId,[i.NeedsStaffId.Value]);if(i.State=="blocked")Check.That(!string.IsNullOrWhiteSpace(i.Blocker)&&i.NeedsStaffId.HasValue,"阻塞任务需填写原因和需要协助的人");
  t.Title=Check.Text(i.Title,1,160);t.Description=Check.Text(i.Description);t.OwnerId=i.OwnerId;t.Kind=i.Kind;t.MilestoneId=i.MilestoneId;t.Collaborators=JsonSerializer.Serialize(collaborators);t.State=i.State;t.StartsOn=i.StartsOn;t.DueOn=i.DueOn;t.Criteria=Check.Text(i.Criteria);t.Blocker=Check.Text(i.Blocker);t.NeedsStaffId=i.NeedsStaffId;t.CompletedAt=i.State=="done"?t.CompletedAt??DateTime.UtcNow:null;t.UpdatedAt=DateTime.UtcNow;
  await access.RequirePeople(Mentions(t.Description,t.Criteria,t.Blocker));
 }
 static Task TaskNotifications(WorkTask t,Person actor,ProjectAccess access){
  if(t.OwnerId.HasValue)access.Notify(t.OwnerId.Value,t.ProjectId,t.Id,"assigned",$"任务更新：{t.Title}",$"task:{t.Id}:{t.Revision}:owner");
  if(t.State=="blocked"&&t.NeedsStaffId.HasValue)access.Notify(t.NeedsStaffId.Value,t.ProjectId,t.Id,"blocked",$"需要你协助：{t.Title}",$"task:{t.Id}:{t.Revision}:blocked");
  NotifyMentions(access,Mentions(t.Description,t.Criteria,t.Blocker),t.ProjectId,t.Id,actor,$"task:{t.Id}:{t.Revision}:mention");return Task.CompletedTask;
 }
}
