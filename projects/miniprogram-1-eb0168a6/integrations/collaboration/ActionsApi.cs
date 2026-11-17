using Microsoft.EntityFrameworkCore;
namespace Siyue.Collaboration;
public record ActionInput(string Title,string? Body=null,Guid? TaskId=null,string Kind="update",DateOnly? PlannedOn=null,string? DateLabel=null,string State="planned",Guid? RepositoryId=null,string? CodeRef=null,long Revision=0,string? RequestId=null);
public record ReminderInput(Guid[] StaffIds,string RequestId,string? Message=null);
public static class ActionsApi {
 public static void Map(WebApplication app){
  var api=app.MapGroup("/api/collaboration").RequireRateLimiting("collaboration");
  api.MapPost("/projects/{id:guid}/actions",async(Guid id,ActionInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);var p=await access.Read(id,actor);Check.That(Guid.TryParse(input.RequestId,out _),"提交标识无效");
   var old=await db.TaskActions.SingleOrDefaultAsync(a=>a.ProjectId==id&&a.RequestId==input.RequestId);if(old!=null){Check.That(old.AuthorId==actor.Id,"提交标识重复",409);return old;}
   var row=new TaskAction{ProjectId=id,AuthorId=actor.Id,RequestId=input.RequestId!};await Set(row,input,p,db,access);db.TaskActions.Add(row);access.Audit(actor,id,"action.create",row.Id.ToString());await db.SaveChangesAsync();return row;
  });
  api.MapPut("/projects/{id:guid}/actions/{actionId:guid}",async(Guid id,Guid actionId,ActionInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);var p=await access.Read(id,actor);var row=await db.TaskActions.SingleOrDefaultAsync(a=>a.Id==actionId&&a.ProjectId==id)??throw new Fault(404,"节点不存在");Check.That(row.Revision==input.Revision,"节点已被修改，请刷新后再保存",409);
   Check.That(row.TaskId==input.TaskId,"已有节点不能移到其他任务");await Set(row,input,p,db,access);row.Revision++;access.Audit(actor,id,"action.update",row.Id.ToString());await db.SaveChangesAsync();return row;
  });
  api.MapPost("/projects/{id:guid}/actions/{actionId:guid}/remind",async(Guid id,Guid actionId,ReminderInput input,HttpContext c,CollaborationDb db,ProjectAccess access)=>{
   var actor=Check.Actor(c);await access.Read(id,actor);var action=await db.TaskActions.SingleOrDefaultAsync(a=>a.Id==actionId&&a.ProjectId==id)??throw new Fault(404,"节点不存在");Check.That(Guid.TryParse(input.RequestId,out _),"提交标识无效");var ids=input.StaffIds.Distinct().ToArray();Check.That(ids.Length is >0 and <=50,"请选择需要提醒的协作者");await access.RequireMembers(id,ids);
   foreach(var staff in ids){var key=$"remind:{actionId}:{actor.Id}:{input.RequestId}:{staff}";if(!await db.Notifications.AnyAsync(n=>n.StaffId==staff&&n.EventKey==key))access.Notify(staff,id,actionId,"action_reminder",$"{actor.Name} 提醒你查看节点「{action.Title}」：{Check.Text(input.Message,0,500)}",key);}
   access.Audit(actor,id,"action.remind",actionId.ToString());await db.SaveChangesAsync();return Results.NoContent();
  });
 }
 static async Task Set(TaskAction row,ActionInput input,Project project,CollaborationDb db,ProjectAccess access){
  Check.That(new[]{"update","code"}.Contains(input.Kind)&&CollaborationApi.States.Contains(input.State),"节点类型或状态无效");
  if(input.TaskId.HasValue)Check.That(await db.Tasks.AnyAsync(t=>t.Id==input.TaskId&&t.ProjectId==project.Id),"任务不存在",404);
  if(input.Kind=="code")Check.That(project.RepositoryId.HasValue&&input.RepositoryId==project.RepositoryId,"请选择本项目关联的代码库");
  var code=Check.Text(input.CodeRef,0,250);Check.That(!code.Contains("..")&&!code.Contains(':')&&!code.Contains('?')&&!code.Contains('#')&&!code.Any(char.IsWhiteSpace)&&!code.StartsWith('/'),"代码位置请填写提交编号或分支名");
  row.TaskId=input.TaskId;row.Title=Check.Text(input.Title,1,160);row.Body=Check.Text(input.Body,0,8000);row.Kind=input.Kind;row.PlannedOn=input.PlannedOn;row.DateLabel=input.PlannedOn.HasValue?"":Check.Text(input.DateLabel,0,80);row.State=input.State;row.RepositoryId=input.Kind=="code"?input.RepositoryId:null;row.CodeRef=input.Kind=="code"?code:"";row.UpdatedAt=DateTime.UtcNow;
  var mentions=CollaborationApi.Mentions(row.Title,row.Body);await access.RequirePeople(mentions);
  foreach(var person in mentions.Where(x=>x!=row.AuthorId))access.Notify(person,row.ProjectId,row.Id,"action_mention","在项目节点中提及你",$"action:{row.Id}:{input.Revision+1}:{person}");
 }
}
