using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
namespace Siyue;

public class AcademicHomeworkPolicy : Entity {
 public string ReferenceAnswer {get;set;}=""; public string Subject {get;set;}=""; public string Stage {get;set;}="";
 public bool Enabled {get;set;} public int Revision {get;set;} public Guid UpdatedBy {get;set;}
}
public class AcademicHomeworkTraining : Entity {
 public int PolicyRevision {get;set;} public Guid JobId {get;set;} public Guid StudentId {get;set;} public int JobRevision {get;set;}
 public string SourceHash {get;set;}=""; public string BodyJson {get;set;}="{}"; public string State {get;set;}="draft";
 public int Revision {get;set;} public Guid CreatedBy {get;set;} public Guid? ReviewedBy {get;set;}
}
public partial class Data {
 public DbSet<AcademicHomeworkPolicy> AcademicHomeworkPolicies=>Set<AcademicHomeworkPolicy>();
 public DbSet<AcademicHomeworkTraining> AcademicHomeworkTrainings=>Set<AcademicHomeworkTraining>();
}
public record HomeworkPolicyInput(int Revision,string ReferenceAnswer,string Subject,string Stage,bool Enabled);
public record HomeworkTrainingInput(int PerError=2,int MaxQuestions=12);
public record HomeworkExercise(string WrongLabel,string WrongStem,BankQuestion Question,string[] Reasons);
public record HomeworkTrainingBody(string Subject,string Stage,HomeworkExercise[] Exercises,string[] Notices);
public static class AcademicHomeworkWorkflow {
 public static void Configure(ModelBuilder b) {
  b.Entity<AcademicHomeworkPolicy>().HasOne<AcademicCollection>().WithOne().HasForeignKey<AcademicHomeworkPolicy>(x=>x.Id).OnDelete(DeleteBehavior.Restrict);
  b.Entity<AcademicHomeworkPolicy>().Property(x=>x.Revision).IsConcurrencyToken();
  b.Entity<AcademicHomeworkTraining>().HasOne<AcademicGradingJob>().WithMany().HasForeignKey(x=>x.JobId).OnDelete(DeleteBehavior.Restrict);
  b.Entity<AcademicHomeworkTraining>().Property(x=>x.Revision).IsConcurrencyToken();
  b.Entity<AcademicHomeworkTraining>().HasIndex(x=>new{x.JobId,x.SourceHash}).IsUnique();
 }
 internal static async Task<bool> OwnerAllowed(AcademicCollection row,Data db,IConfiguration c) {
  if(!c.GetValue<bool>("AcademicPilot:Enabled"))return false;
  var staff=await db.Staff.SingleOrDefaultAsync(x=>x.Id==row.CreatedBy&&x.Enabled&&!x.Archived);
  return staff!=null&&await AcademicPilot.Listed(c,db,staff.Id,"Allowed")&&await db.Classes.AnyAsync(x=>x.Id==row.ClassId&&!x.Archived)&&(staff.Role=="admin"||await db.Assignments.AnyAsync(x=>x.StaffId==staff.Id&&x.ClassId==row.ClassId));
 }
 // Called inside the same upload transaction, after photos are saved. No model call
 // holds up a parent upload. The database queue survives server restarts.
 internal static async Task Enqueue(Guid submissionId,Data db) {
  var sub=await db.AcademicSubmissions.SingleAsync(x=>x.Id==submissionId);
  var test=await db.AcademicOnlineTests.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==sub.CollectionId);
  if(sub.AnswersJson=="[]"&&test!=null)return;
  var policy=await db.AcademicHomeworkPolicies.SingleOrDefaultAsync(x=>x.Id==sub.CollectionId&&x.Enabled);
  if(policy==null)return;
  var hash=await AcademicHomeworkGrading.Source(sub.Id,policy.ReferenceAnswer,db);
  if(!await db.AcademicGradingJobs.AnyAsync(x=>x.SubmissionId==sub.Id&&x.SourceHash==hash)){
   var choices=test==null?null:AcademicOnlineTesting.GradeChoices(test,policy.ReferenceAnswer,sub.AnswersJson);
   db.AcademicGradingJobs.Add(new(){SubmissionId=sub.Id,SourceHash=hash,ReferenceAnswer=policy.ReferenceAnswer,CreatedBy=policy.UpdatedBy,
    State=choices==null?"queued":"needs_review",ResultJson=choices==null?"{}":Json.Write(choices)});
  }
  await db.SaveChangesAsync();
 }
 internal static async Task<bool> Current(AcademicGradingJob job,Data db) {
  if(job.SourceHash!=await AcademicHomeworkGrading.Source(job.SubmissionId,job.ReferenceAnswer,db))return false;
  var sub=await db.AcademicSubmissions.AsNoTracking().SingleAsync(x=>x.Id==job.SubmissionId);
  var policy=await db.AcademicHomeworkPolicies.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==sub.CollectionId);
  return (policy==null||policy.ReferenceAnswer==job.ReferenceAnswer)&&await AcademicTrainingRetesting.CollectionCurrent(sub.CollectionId,db);
 }
 static async Task<(AcademicGradingJob Job,AcademicSubmission Submission)> Job(Guid id,Data db,Access a) {
  var job=await db.AcademicGradingJobs.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"批改不存在");
  var sub=await db.AcademicSubmissions.SingleAsync(x=>x.Id==job.SubmissionId);await AcademicHomeworkCollection.Scope(sub.CollectionId,db,a);return(job,sub);
 }
 public static void Map(RouteGroupBuilder group) {
  AcademicOnlineTesting.Map(group);
  AcademicTrainingRetesting.Map(group);
  AcademicMobileReview.Map(group);
  AcademicTeacherTodos.Map(group);
  group.MapGet("/collections/{id:guid}/learning",async(Guid id,Data db,Access a,IAcademicHomeworkGrader grader,HomeworkQuestionBank bank,IDataProtectionProvider p)=>{
   await AcademicHomeworkCollection.Scope(id,db,a);
   var policy=await db.AcademicHomeworkPolicies.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==id);
   var subs=await db.AcademicSubmissions.AsNoTracking().Where(x=>x.CollectionId==id).ToArrayAsync();
   var ids=subs.Select(x=>x.Id).ToArray();var jobs=await db.AcademicGradingJobs.AsNoTracking().Where(x=>ids.Contains(x.SubmissionId)).OrderByDescending(x=>x.CreatedAt).ToArrayAsync();
   var rows=new List<object>();var wrong=new List<(Guid Student,string Label,string Stem,string[] Points)>();
   var validReviewed=0;var uncertain=0;
   foreach(var sub in subs){
    var job=jobs.FirstOrDefault(x=>x.SubmissionId==sub.Id);var current=job!=null&&await Current(job,db);
    HomeworkGradeResult? result=job?.State is "needs_review" or "reviewed"?Json.Read<HomeworkGradeResult>(job.ResultJson):null;
    if(current&&result!=null)uncertain+=result.Questions.Count(x=>x.Verdict=="uncertain");
    if(current&&job?.State=="reviewed"&&sub.StudentId.HasValue){
     // Class validation below uses the collection, never a self-reported name.
     var collection=await db.AcademicCollections.AsNoTracking().SingleAsync(x=>x.Id==id);
     if(await db.Students.AnyAsync(x=>x.Id==sub.StudentId&&x.ClassId==collection.ClassId)){validReviewed++;wrong.AddRange(result!.Questions.Where(x=>x.Verdict=="incorrect").Select(x=>(sub.StudentId.Value,x.Label,x.Stem,x.KnowledgePoints)));}
    }
    var plans=job==null?[]:await db.AcademicHomeworkTrainings.AsNoTracking().Where(x=>x.JobId==job.Id).ToArrayAsync();
    var trainingViews=new List<object>();foreach(var t in plans)trainingViews.Add(new{t.Id,state=current&&job!.State=="reviewed"&&t.JobRevision==job.Revision&&t.StudentId==sub.StudentId&&t.PolicyRevision==(policy?.Revision??0)?t.State:"stale",t.Revision,body=Json.Read<HomeworkTrainingBody>(t.BodyJson),retest=await AcademicTrainingRetesting.View(t,db,p)});
    rows.Add(new{sub.Id,sub.Name,sub.StudentId,photos=await db.AcademicSubmissionPhotos.AsNoTracking().Where(p=>p.SubmissionId==sub.Id).OrderBy(p=>p.Position).Select(p=>p.Id).ToArrayAsync(),job=job==null?null:new{job.Id,state=current?job.State:"stale",job.Revision,job.Error,result},trainings=trainingViews});
   }
   var retestLink=await db.AcademicTrainingRetests.AsNoTracking().SingleOrDefaultAsync(x=>x.CollectionId==id);var retestPlan=retestLink==null?null:await db.AcademicHomeworkTrainings.AsNoTracking().SingleAsync(x=>x.Id==retestLink.Id);
   return new{retest=retestPlan==null?null:await AcademicTrainingRetesting.View(retestPlan,db,p),policyLocked=retestLink!=null,policy=policy==null?new HomeworkPolicyInput(0,"","数学","初中",false):new(policy.Revision,policy.ReferenceAnswer,policy.Subject,policy.Stage,policy.Enabled),graderEnabled=grader.Available,bank=bank.Availability(),submissions=rows,
    statistics=new{reviewedSubmissions=validReviewed,wrongQuestions=wrong.Count,studentsWithErrors=wrong.Select(x=>x.Student).Distinct().Count(),uncertainQuestions=uncertain,
     knowledgePoints=wrong.SelectMany(x=>x.Points.Distinct().Select(p=>new{x.Student,Point=p})).GroupBy(x=>x.Point).Select(g=>new{point=g.Key,occurrences=g.Count(),students=g.Select(x=>x.Student).Distinct().Count()})},requiresAcademicReview=true};
  });
  group.MapPut("/collections/{id:guid}/learning-policy",async(Guid id,HomeworkPolicyInput input,Data db,Access a,HttpContext ctx)=>{
   var collection=await AcademicHomeworkCollection.Scope(id,db,a);await AcademicPilot.Gate(ctx,true);
   Guard.That(!await db.AcademicTrainingRetests.AnyAsync(x=>x.CollectionId==id),"强化训练使用审核时的固定题目和答案，请重新生成训练后调整",409);
   Guard.That(input.ReferenceAnswer is {Length:>0 and <=16000}&&!string.IsNullOrWhiteSpace(input.ReferenceAnswer),"请填写完整题目、参考答案及评分要求");
   Guard.That(LocalQuestionBank.Subjects.Contains(input.Subject)&&LocalQuestionBank.Stages.Contains(input.Stage),"请选择学科和学段");
   var policy=await db.AcademicHomeworkPolicies.SingleOrDefaultAsync(x=>x.Id==id);AcademicPilot.Revision(input.Revision,policy?.Revision??0);
   if(policy==null){policy=new(){Id=id};db.AcademicHomeworkPolicies.Add(policy);}
   policy.ReferenceAnswer=input.ReferenceAnswer.Trim();policy.Subject=input.Subject;policy.Stage=input.Stage;policy.Enabled=input.Enabled;policy.UpdatedBy=a.User.Id;policy.Revision++;
   AcademicParentPortal.Audit(db,a,id,"homework.grading_policy",new{policy.Revision,policy.Enabled});await db.SaveChangesAsync();
   if(policy.Enabled){var subs=await db.AcademicSubmissions.Where(x=>x.CollectionId==id).Select(x=>x.Id).ToArrayAsync();foreach(var sub in subs)await Enqueue(sub,db);}
   return Results.Ok(new{policy.Revision});
  });
  group.MapPost("/grading-jobs/{id:guid}/retry",async(Guid id,AcademicRevisionInput input,Data db,Access a)=>{
   var(job,sub)=await Job(id,db,a);AcademicPilot.Revision(input.Revision,job.Revision);Guard.That(job.State is "failed" or "awaiting_provider","当前任务无需重试",409);Guard.That(await Current(job,db),"照片或参考答案已更新，请使用最新任务",409);
   job.State="queued";job.Error="";job.Revision++;await db.SaveChangesAsync();return Results.Ok(new{job.Revision});
  });
  group.MapPost("/grading-jobs/{id:guid}/training",async(Guid id,HomeworkTrainingInput input,Data db,Access a,HomeworkQuestionBank bank)=>{
   var(job,sub)=await Job(id,db,a);Guard.That(job.State=="reviewed"&&await Current(job,db),"请先由教务复核最新批改结果",409);
   var collection=await AcademicHomeworkCollection.Scope(sub.CollectionId,db,a);Guard.That(sub.StudentId.HasValue&&await db.Students.AnyAsync(x=>x.Id==sub.StudentId&&x.ClassId==collection.ClassId),"请先核对学生身份",409);
   Guard.That(input.PerError is >=1 and <=3&&input.MaxQuestions is >=1 and <=30,"每道错题选1至3道，总题数1至30道");
   var policy=await db.AcademicHomeworkPolicies.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==sub.CollectionId)??throw new ApiException(409,"请先设置学科学段及参考答案");
   var result=Json.Read<HomeworkGradeResult>(job.ResultJson);var wrong=result.Questions.Where(x=>x.Verdict=="incorrect").ToArray();
   Guard.That(wrong.Length>0,"本次作业无已确认错题，无需强化训练",409);
   var hash=Secrets.Hash(Json.Write(new{job.Id,job.Revision,job.SourceHash,sub.StudentId,policyRevision=policy.Revision,input.PerError,input.MaxQuestions}));
   var old=await db.AcademicHomeworkTrainings.SingleOrDefaultAsync(x=>x.JobId==id&&x.SourceHash==hash);if(old!=null)return Results.Ok(new{old.Id,old.State,old.Revision,body=Json.Read<HomeworkTrainingBody>(old.BodyJson)});
   var excluded=result.Questions.Select(x=>LocalQuestionBank.Fingerprint(policy.Subject,x.Stem)).ToHashSet();
   var exercises=new List<HomeworkExercise>();var notices=new List<string>();
   static string[] Expressions(string text)=>System.Text.RegularExpressions.Regex.Matches(System.Text.RegularExpressions.Regex.Replace(text.Normalize(System.Text.NormalizationForm.FormKC),@"\s", "").Replace("−","-"),@"[0-9a-z][0-9a-z()+\-=/^*.]*",System.Text.RegularExpressions.RegexOptions.IgnoreCase).Select(m=>m.Value.ToLowerInvariant()).Where(x=>x.Length>=3&&x.Any(c=>"+-=/^*".Contains(c))).ToArray();
   var originalExpressions=policy.Subject=="数学"?result.Questions.SelectMany(x=>Expressions(x.Stem)).ToHashSet():new HashSet<string>();
   foreach(var q in wrong){
    if(exercises.Count>=input.MaxQuestions)break;
    if(q.Stem.Trim().Length<4){notices.Add($"第{q.Label}题原题文字不足，请在批改页补全后重新复核。");continue;}
    var matches=bank.Recommend(policy.Subject,policy.Stage,q.Stem,q.KnowledgePoints,Math.Min(30,input.PerError*5),excluded).Where(m=>m.Question.Diagram==null&&!Expressions(m.Question.Stem).Any(originalExpressions.Contains)).Take(Math.Min(input.PerError,input.MaxQuestions-exercises.Count)).ToArray();
    foreach(var match in matches){exercises.Add(new(q.Label,q.Stem,match.Question,match.Reasons));excluded.Add(LocalQuestionBank.Fingerprint(policy.Subject,match.Question.Stem));}
    if(matches.Length<input.PerError)notices.Add($"第{q.Label}题匹配到{matches.Length}道可直接显示的同类题，未用无关题或缺少图形的题补足。");
   }
   Guard.That(exercises.Count>0,"当前题库暂无可用同类题；请核对题干、知识点或补充题源",409);
   var body=new HomeworkTrainingBody(policy.Subject,policy.Stage,exercises.ToArray(),notices.ToArray());
   var plan=new AcademicHomeworkTraining{JobId=id,StudentId=sub.StudentId!.Value,JobRevision=job.Revision,PolicyRevision=policy.Revision,SourceHash=hash,BodyJson=Json.Write(body),CreatedBy=a.User.Id};db.AcademicHomeworkTrainings.Add(plan);await db.SaveChangesAsync();
   return Results.Ok(new{plan.Id,plan.State,plan.Revision,body});
  });
  group.MapPost("/homework-trainings/{id:guid}/review",async(Guid id,AcademicApprovalInput input,Data db,Access a,HttpContext ctx,IDataProtectionProvider p)=>{
   await AcademicPilot.Gate(ctx,true);await using var tx=await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.Serializable);var plan=await db.AcademicHomeworkTrainings.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"训练不存在");var(job,sub)=await Job(plan.JobId,db,a);
   var policy=await db.AcademicHomeworkPolicies.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==sub.CollectionId);
   AcademicPilot.Revision(input.Revision,plan.Revision);Guard.That(input.Confirmed&&plan.State=="draft"&&job.State=="reviewed"&&plan.JobRevision==job.Revision&&plan.StudentId==sub.StudentId&&plan.PolicyRevision==(policy?.Revision??0)&&await Current(job,db),"批改或学生归属已更新，请重新生成训练",409);
   plan.State="approved";plan.ReviewedBy=a.User.Id;plan.Revision++;AcademicParentPortal.Audit(db,a,id,"homework.training_reviewed",new{plan.Revision});await AcademicTrainingRetesting.Ensure(plan,db,a);await db.SaveChangesAsync();await tx.CommitAsync();return Results.Ok(new{plan.Revision,plan.State,deliveryMode="manual",retest=await AcademicTrainingRetesting.View(plan,db,p)});
  });
 }
}

public sealed class HomeworkQueueProcessor(Data db,IConfiguration config,IAcademicHomeworkGrader grader) {
 public async Task<int> Tick(CancellationToken ct=default) {
  if(!config.GetValue<bool>("AcademicPilot:Enabled"))return 0;
  var cutoff=Clock.Now.AddMinutes(-5);
  // A crashed/terminated model request becomes explicitly retryable, never stuck.
  await db.AcademicGradingJobs.Where(x=>x.State=="processing"&&x.AttemptedAt!=null&&x.AttemptedAt<cutoff).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,"failed").SetProperty(x=>x.Error,"上次批改中断，请重试").SetProperty(x=>x.Revision,x=>x.Revision+1),ct);
  var queued=await db.AcademicGradingJobs.AsNoTracking().Where(x=>x.State=="queued").OrderBy(x=>x.CreatedAt).Take(10).ToArrayAsync(ct);
  foreach(var job in queued){
   var sub=await db.AcademicSubmissions.AsNoTracking().SingleAsync(x=>x.Id==job.SubmissionId,ct);var row=await db.AcademicCollections.AsNoTracking().SingleAsync(x=>x.Id==sub.CollectionId,ct);
   var policy=await db.AcademicHomeworkPolicies.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==row.Id,ct);
   if(!await AcademicHomeworkWorkflow.OwnerAllowed(row,db,config)||policy?.Enabled!=true||!await AcademicHomeworkWorkflow.Current(job,db)){
    await db.AcademicGradingJobs.Where(x=>x.Id==job.Id&&x.Revision==job.Revision).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,"stale").SetProperty(x=>x.Revision,x=>x.Revision+1),ct);continue;
   }
   if(!grader.Available){await db.AcademicGradingJobs.Where(x=>x.Id==job.Id&&x.Revision==job.Revision).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,"awaiting_provider").SetProperty(x=>x.Error,"自动批改模型尚未启用").SetProperty(x=>x.Revision,x=>x.Revision+1),ct);continue;}
   var won=await db.AcademicGradingJobs.Where(x=>x.Id==job.Id&&x.State=="queued"&&x.Revision==job.Revision).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,"processing").SetProperty(x=>x.AttemptedAt,Clock.Now).SetProperty(x=>x.Revision,x=>x.Revision+1),ct);if(won!=1)continue;
   string state="needs_review",error="",json="{}";
   try{
    var photos=await db.AcademicSubmissionPhotos.Where(x=>x.SubmissionId==job.SubmissionId).OrderBy(x=>x.Position).Select(x=>x.Id).ToArrayAsync(ct);
    using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(TimeSpan.FromSeconds(130));
    var result=await grader.Grade(new(job.SubmissionId,job.SourceHash,photos,job.ReferenceAnswer),timeout.Token);AcademicHomeworkGrading.Validate(result);json=Json.Write(result);
    if(!await AcademicHomeworkWorkflow.Current(job,db))state="stale";
   }catch(OperationCanceledException) when(ct.IsCancellationRequested){throw;}
   catch(ApiException e){state=e.Status==503?"awaiting_provider":"failed";error=e.Message;}
   catch(Exception){state="failed";error="批改未完成，请核对模型设置后重试";}
   await db.AcademicGradingJobs.Where(x=>x.Id==job.Id&&x.State=="processing"&&x.Revision==job.Revision+1).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,state).SetProperty(x=>x.Error,error).SetProperty(x=>x.ResultJson,json).SetProperty(x=>x.Revision,x=>x.Revision+1),ct);
   return 1; // Bound work per tick; use a new scope for the next claim.
  }
  return 0;
 }
}
public sealed class HomeworkGradingLoop(IServiceScopeFactory scopes,ILogger<HomeworkGradingLoop> log):BackgroundService {
 protected override async Task ExecuteAsync(CancellationToken ct){while(!ct.IsCancellationRequested){try{using var scope=scopes.CreateScope();await scope.ServiceProvider.GetRequiredService<HomeworkQueueProcessor>().Tick(ct);}catch(OperationCanceledException)when(ct.IsCancellationRequested){break;}catch(Exception e){log.LogWarning("Homework queue tick failed ({Type})",e.GetType().Name);}try{await Task.Delay(TimeSpan.FromSeconds(3),ct);}catch(OperationCanceledException){break;}}}
}
