using Microsoft.EntityFrameworkCore;
using System.Globalization;
namespace Siyue;

// Read-only projection: no second task state to drift from homework/review facts.
public record TeacherTodo(string Id,string Kind,int Priority,Guid CollectionId,string ClassName,string Title,string DueOn,string StudentName,string Summary,string Action,string Path,int Count,int Uncertain=0,bool CanRemind=false);
public static class AcademicTeacherTodos {
 const int PageSize=20;
 static string Collect(Guid id)=>"/pages/homework-collect/homework-collect?id="+id;
 static string Learning(Guid id,Guid? sub=null)=>"/pages/homework-review/homework-review?id="+id+(sub.HasValue?"&submissionId="+sub:"");
 internal static async Task<(CollectionRoster[] Missing,int Pending)> Missing(AcademicCollection c,Data db){
  var live=await db.Students.AsNoTracking().Where(s=>s.ClassId==c.ClassId).Select(s=>s.Id).ToArrayAsync();
  var roster=Json.Read<CollectionRoster[]>(c.RosterJson).Where(s=>live.Contains(s.Id)).DistinctBy(s=>s.Id).ToArray();
  var online=await db.AcademicOnlineTests.AnyAsync(t=>t.Id==c.Id);
  var subs=await db.AcademicSubmissions.AsNoTracking().Where(s=>s.CollectionId==c.Id&&(!online||s.AnswersJson!="[]")).ToArrayAsync();
  var ids=subs.Where(s=>s.StudentId.HasValue&&roster.Any(x=>x.Id==s.StudentId)).Select(s=>s.StudentId!.Value).ToHashSet();
  return(roster.Where(s=>!ids.Contains(s.Id)).ToArray(),subs.Count(s=>!s.StudentId.HasValue||!roster.Any(x=>x.Id==s.StudentId)));
 }
 static bool OpenForSubmission(AcademicCollection c)=>c.State=="approved"&&DateOnly.Parse(c.DueOn,CultureInfo.InvariantCulture).AddDays(30)>=DateOnly.FromDateTime(Clock.Now.UtcDateTime);
 public static void Map(RouteGroupBuilder group){
  group.MapGet("/teacher-todos",async(Guid? classId,int? page,Data db,Access a,HttpContext ctx)=>{
   var n=page??0;Guard.That(n>=0&&n<=10000,"页码无效");if(classId.HasValue)await a.Class(classId.Value);
   var uid=a.User.Id;var admin=a.User.Role=="admin";var canReview=await AcademicPilot.Listed(ctx.RequestServices.GetRequiredService<IConfiguration>(),db,uid,"Reviewer");
   var collections=await db.AcademicCollections.AsNoTracking().Where(c=>c.State!="closed"&&(!classId.HasValue||c.ClassId==classId)&&db.Classes.Any(x=>x.Id==c.ClassId&&!x.Archived)&&(admin||db.Assignments.Any(x=>x.StaffId==uid&&x.ClassId==c.ClassId))).OrderByDescending(c=>c.CreatedAt).ThenBy(c=>c.Id).Skip(n*PageSize).Take(PageSize+1).ToArrayAsync();
   var rows=new List<TeacherTodo>();
   foreach(var c in collections.Take(PageSize)){
    var name=(await db.Classes.AsNoTracking().SingleAsync(x=>x.Id==c.ClassId)).Name;
    void Add(string suffix,string kind,int priority,string student,string summary,string action,string path,int count=1,int uncertain=0,bool remind=false)=>rows.Add(new(c.Id+":"+suffix,kind,priority,c.Id,name,c.Title,c.DueOn,student,summary,action,path,count,uncertain,remind));
    if(c.State=="draft"){Add("approval","approval",2,"","作业内容待教务确认",canReview?"核对作业":"查看作业",Collect(c.Id));continue;}
    if(!await AcademicTrainingRetesting.CollectionCurrent(c.Id,db))continue;
    var (missing,pending)=await Missing(c,db);
    if(pending>0)Add("identity","identity",0,"",$"{pending}份提交需核对学生身份","核对学生",Collect(c.Id),pending);
    if(missing.Length>0&&OpenForSubmission(c)){
     var overdue=string.CompareOrdinal(c.DueOn,Clock.Now.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd",CultureInfo.InvariantCulture))<0;
     Add("missing","missing",overdue?3:5,"",pending>0?$"{missing.Length}人尚未确认交齐，请先核对身份":$"{missing.Length}人未交"+(overdue?" · 已过截止日期":""),"查看名单",Collect(c.Id),missing.Length,remind:pending==0);
    }
    var online=await db.AcademicOnlineTests.AnyAsync(t=>t.Id==c.Id);
    var subs=await db.AcademicSubmissions.AsNoTracking().Where(s=>s.CollectionId==c.Id&&(!online||s.AnswersJson!="[]")).OrderByDescending(s=>s.SubmittedAt).ThenByDescending(s=>s.Revision).ThenBy(s=>s.Id).ToArrayAsync();
    var roster=Json.Read<CollectionRoster[]>(c.RosterJson).Select(s=>s.Id).ToHashSet();
    var liveIds=await db.Students.Where(s=>s.ClassId==c.ClassId).Select(s=>s.Id).ToArrayAsync();
    var eligible=subs.Where(s=>s.StudentId.HasValue&&roster.Contains(s.StudentId.Value)&&liveIds.Contains(s.StudentId.Value)).DistinctBy(s=>s.StudentId).ToArray();
    var ids=eligible.Select(s=>s.Id).ToArray();var jobs=await db.AcademicGradingJobs.AsNoTracking().Where(j=>ids.Contains(j.SubmissionId)).OrderByDescending(j=>j.CreatedAt).ThenBy(j=>j.Id).ToArrayAsync();
    var isRetest=await db.AcademicTrainingRetests.AnyAsync(t=>t.CollectionId==c.Id);
    foreach(var sub in eligible){
     var j=jobs.FirstOrDefault(j=>j.SubmissionId==sub.Id);var path=Learning(c.Id,sub.Id);
     if(j==null){Add(sub.Id.ToString(),"grading",4,sub.Name,"尚未设置或生成自动批改","查看批改设置",path);continue;}
     if(!await AcademicHomeworkWorkflow.Current(j,db)){Add(j.Id.ToString(),"grading",4,sub.Name,"答卷或评分依据已更新，旧结果不再使用","查看最新批改",path);continue;}
     if(j.State=="needs_review"){
      var u=Json.Read<HomeworkGradeResult>(j.ResultJson).Questions.Count(q=>q.Verdict=="uncertain");
      Add(j.Id.ToString(),"review",u>0?0:1,sub.Name,u>0?$"{u}题不确定，优先核对":"答卷待逐题复核",canReview?"开始复核":"查看待复核答卷",canReview?"/pages/homework-batch-review/homework-batch-review?id="+c.Id+"&jobId="+j.Id:path,uncertain:u);continue;
     }
     if(j.State is "failed" or "awaiting_provider"){Add(j.Id.ToString(),"grading",2,sub.Name,j.State=="failed"?"批改失败，需检查后重试":"批改模型尚未配置","处理批改",path);continue;}
     if(j.State!="reviewed")continue;
     var wrong=Json.Read<HomeworkGradeResult>(j.ResultJson).Questions.Count(q=>q.Verdict=="incorrect");if(wrong==0)continue;
     var plans=await db.AcademicHomeworkTrainings.AsNoTracking().Where(t=>t.JobId==j.Id).ToArrayAsync();var current=new List<AcademicHomeworkTraining>();foreach(var t in plans)if(await AcademicTrainingRetesting.PlanCurrent(t,db))current.Add(t);
     if(current.Count==0)Add(j.Id.ToString(),"practice",3,sub.Name,isRetest?$"复测仍有{wrong}道错题，需继续强化":$"{wrong}道已复核错题，待安排强化","安排强化训练",path,wrong);
     else foreach(var t in current){
      if(t.State=="draft")Add(t.Id.ToString(),"training",2,sub.Name,"强化训练草稿待教务确认","核对训练",path);
      else if(t.State=="approved"&&!await db.AcademicTrainingRetests.AnyAsync(x=>x.Id==t.Id))Add(t.Id.ToString(),"training",2,sub.Name,"训练已审核，待生成答题入口","查看训练",path);
     }
    }
   }
   return Results.Ok(new{rows=rows.OrderBy(x=>x.Priority).ThenByDescending(x=>x.Uncertain).ThenBy(x=>x.DueOn).ThenBy(x=>x.Id).ToArray(),hasMore=collections.Length>PageSize,nextPage=n+1,checkedCollections=Math.Min(PageSize,collections.Length),canReview});
  });
  group.MapGet("/collections/{id:guid}/reminder-preview",async(Guid id,Data db,Access a)=>{
   var c=await AcademicHomeworkCollection.Scope(id,db,a);Guard.That(OpenForSubmission(c)&&await AcademicTrainingRetesting.CollectionCurrent(id,db),"这份作业已关闭、过期或依据已更新，不能生成催交草稿",409);
   var (missing,pending)=await Missing(c,db);Guard.That(pending==0,"还有提交尚未核对学生身份，请先核对后再生成催交草稿",409);Guard.That(missing.Length>0,"已经全部交齐，无需催交",409);
   var name=(await db.Classes.SingleAsync(x=>x.Id==c.ClassId)).Name;
   // No child names in group text, no outbound capability or fake approved state.
   return Results.Ok(new{state="draft",requiresAcademicReview=true,missing=missing.Select(x=>new{x.Id,x.Name}),body=$"各位家长好，{name}的《{c.Title}》作业还有同学尚未提交，截止日期为{c.DueOn}。请尚未提交的家庭从原作业卡片进入并提交。如已提交或需要协助，请联系老师核对。谢谢配合。",generatedAt=Clock.Now,deliveryMode="none"});
  });
 }
}
