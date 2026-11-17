using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using System.Globalization;
using System.Text.RegularExpressions;
namespace Siyue;

// A reviewed training is an immutable, single-student online test. The link
// preserves the exact exercise-to-original-error mapping used for mastery.
public class AcademicTrainingRetest : Entity {
 public Guid CollectionId {get;set;}
 public DateTimeOffset CreatedAt {get;set;}=Clock.Now;
}
public partial class Data { public DbSet<AcademicTrainingRetest> AcademicTrainingRetests=>Set<AcademicTrainingRetest>(); }
public record RetestSkill(string WrongLabel,string WrongStem,string[] KnowledgePoints,int Total,int Correct,int Incorrect,string State);
public record RetestView(Guid CollectionId,string? SharePath,string State,int Submitted,int PendingIdentity,int Total,int Correct,int Incorrect,int Passed,int NeedsPractice,RetestSkill[] Skills,DateTimeOffset? SubmittedAt);
public static class AcademicTrainingRetesting {
 public static void Configure(ModelBuilder b){
  b.Entity<AcademicTrainingRetest>().HasOne<AcademicHomeworkTraining>().WithOne().HasForeignKey<AcademicTrainingRetest>(x=>x.Id).OnDelete(DeleteBehavior.Restrict);
  b.Entity<AcademicTrainingRetest>().HasOne<AcademicCollection>().WithOne().HasForeignKey<AcademicTrainingRetest>(x=>x.CollectionId).OnDelete(DeleteBehavior.Restrict);
 }
 internal static string Label(string label){var m=Regex.Match(label.Trim(),@"^(?:第\s*)?(\d+)\s*(?:题)?[.、．。]?$",RegexOptions.CultureInvariant);return m.Success?int.Parse(m.Groups[1].Value,CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture):label.Trim();}
 internal static async Task<bool> PlanCurrent(AcademicHomeworkTraining plan,Data db){
  var job=await db.AcademicGradingJobs.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==plan.JobId);if(job==null||job.State!="reviewed"||job.Revision!=plan.JobRevision)return false;
  var sub=await db.AcademicSubmissions.AsNoTracking().SingleAsync(x=>x.Id==job.SubmissionId);if(sub.StudentId!=plan.StudentId)return false;
  var collection=await db.AcademicCollections.AsNoTracking().SingleAsync(x=>x.Id==sub.CollectionId);
  var policy=await db.AcademicHomeworkPolicies.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==collection.Id);
  return policy?.Revision==plan.PolicyRevision&&await db.Students.AnyAsync(x=>x.Id==plan.StudentId&&x.ClassId==collection.ClassId)&&await AcademicHomeworkWorkflow.Current(job,db);
 }
 internal static async Task<bool> CollectionCurrent(Guid id,Data db){
  var link=await db.AcademicTrainingRetests.AsNoTracking().SingleOrDefaultAsync(x=>x.CollectionId==id);if(link==null)return true;
  var plan=await db.AcademicHomeworkTrainings.AsNoTracking().SingleAsync(x=>x.Id==link.Id);return plan.State=="approved"&&await PlanCurrent(plan,db);
 }
 internal static async Task<AcademicTrainingRetest> Ensure(AcademicHomeworkTraining plan,Data db,Access a){
  Guard.That(plan.State=="approved"&&await PlanCurrent(plan,db),"原批改或学生归属已更新，请重新生成并审核训练",409);
  var old=await db.AcademicTrainingRetests.SingleOrDefaultAsync(x=>x.Id==plan.Id);if(old!=null)return old;
  var job=await db.AcademicGradingJobs.SingleAsync(x=>x.Id==plan.JobId);var sub=await db.AcademicSubmissions.SingleAsync(x=>x.Id==job.SubmissionId);var source=await AcademicHomeworkCollection.Scope(sub.CollectionId,db,a);var student=await db.Students.SingleAsync(x=>x.Id==plan.StudentId);
  var body=Json.Read<HomeworkTrainingBody>(plan.BodyJson);
  Guard.That(body.Exercises is {Length:>0 and <=30}&&body.Exercises.All(x=>!string.IsNullOrWhiteSpace(x.Question.Answer)&&x.Question.Diagram==null),"训练题目或参考答案不完整，请重新生成",409);
  var questions=body.Exercises.Select((x,i)=>new OnlineTestQuestion((i+1).ToString(CultureInfo.InvariantCulture),string.Join("\n",new[]{x.Question.MaterialText,x.Question.Stem}.Where(s=>!string.IsNullOrWhiteSpace(s))))).ToArray();
  var reference=string.Join("\n\n",body.Exercises.Select((x,i)=>$"第{i+1}题：{questions[i].Stem}\n答案：{x.Question.Answer}\n解析：{x.Question.Analysis}\n知识点：{string.Join("、",x.Question.Tags)}"));
  Guard.That(reference.Length<=16000&&questions.All(q=>q.Stem.Length<=16000),"训练篇幅过长，请减少训练题数后重新生成",409);
  var collection=new AcademicCollection{BatchId=Guid.NewGuid(),ClassId=source.ClassId,CreatedBy=a.User.Id,Title="错题强化训练",Instructions="逐题填写答案，可附解题过程照片。提交后由老师复核。",DueOn=Clock.Now.ToOffset(TimeSpan.FromHours(8)).AddDays(7).ToString("yyyy-MM-dd",CultureInfo.InvariantCulture),State="approved",Revision=1,ApprovedBy=plan.ReviewedBy,ApprovedAt=Clock.Now,RosterJson=Json.Write(new[]{new CollectionRoster(student.Id,student.Name)}),RequestKey="training-retest:"+plan.Id};
  db.AcademicCollections.Add(collection);db.AcademicOnlineTests.Add(new(){Id=collection.Id,QuestionsJson=Json.Write(questions)});
  db.AcademicHomeworkPolicies.Add(new(){Id=collection.Id,ReferenceAnswer=reference,Subject=body.Subject,Stage=body.Stage,Enabled=true,Revision=1,UpdatedBy=a.User.Id});
  var link=new AcademicTrainingRetest{Id=plan.Id,CollectionId=collection.Id};db.AcademicTrainingRetests.Add(link);AcademicParentPortal.Audit(db,a,plan.Id,"training.retest_created",new{collection.Id,plan.StudentId});await db.SaveChangesAsync();return link;
 }
 internal static HomeworkGradeResult Align(HomeworkGradeResult result,OnlineTestQuestion[] expected){
  var warnings=result.Warnings.ToList();var questions=expected.Select(e=>{
   var found=result.Questions.Where(q=>Label(q.Label)==Label(e.Label)).ToArray();
   if(found.Length==1)return found[0] with{Label=e.Label,Stem=e.Stem};
   warnings.Add($"第{e.Label}题未得到唯一批改结果，须人工核对。");return new HomeworkGradeQuestion(e.Label,"","uncertain",null,null,0,"请对照学生答案或过程照片补全本题判定",[],e.Stem);
  }).ToArray();
  if(result.Questions.Any(q=>!expected.Any(e=>Label(e.Label)==Label(q.Label))))warnings.Add("模型返回了训练范围外的题号，未计入本次训练。");
  return result with{Questions=questions,Warnings=warnings.Take(100).ToArray()};
 }
 internal static async Task ValidateReview(AcademicGradingJob job,Data db){
  var sub=await db.AcademicSubmissions.AsNoTracking().SingleAsync(x=>x.Id==job.SubmissionId);
  var link=await db.AcademicTrainingRetests.AsNoTracking().SingleOrDefaultAsync(x=>x.CollectionId==sub.CollectionId);if(link==null)return;
  var test=await db.AcademicOnlineTests.SingleAsync(x=>x.Id==sub.CollectionId);var expected=Json.Read<OnlineTestQuestion[]>(test.QuestionsJson).Select(q=>Label(q.Label)).Order().ToArray();
  var actual=Json.Read<HomeworkGradeResult>(job.ResultJson).Questions.Select(q=>Label(q.Label)).Order().ToArray();
  Guard.That(expected.SequenceEqual(actual),"批改题号或题数与训练不一致，请补全后再复核",409);
 }
 internal static async Task<RetestView?> View(AcademicHomeworkTraining plan,Data db,IDataProtectionProvider p){
  var link=await db.AcademicTrainingRetests.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==plan.Id);if(link==null)return null;
  var collection=await db.AcademicCollections.AsNoTracking().SingleAsync(x=>x.Id==link.CollectionId);var valid=await CollectionCurrent(collection.Id,db);
  var body=Json.Read<HomeworkTrainingBody>(plan.BodyJson);var subs=await db.AcademicSubmissions.AsNoTracking().Where(x=>x.CollectionId==collection.Id&&x.AnswersJson!="[]").OrderByDescending(x=>x.SubmittedAt).ThenByDescending(x=>x.Revision).ToArrayAsync();
  var sub=subs.FirstOrDefault(x=>x.StudentId==plan.StudentId);var state=!valid?"stale":sub==null?subs.Length>0?"pending_identity":"not_submitted":"pending_review";HomeworkGradeResult? result=null;
  if(valid&&sub!=null){
   var job=await db.AcademicGradingJobs.AsNoTracking().Where(x=>x.SubmissionId==sub.Id).OrderByDescending(x=>x.CreatedAt).FirstOrDefaultAsync();
   if(job!=null&&await AcademicHomeworkWorkflow.Current(job,db)){
    state=job.State=="reviewed"?"reviewed":job.State;
    if(job.State=="reviewed"){
     var r=Json.Read<HomeworkGradeResult>(job.ResultJson);var labels=Enumerable.Range(1,body.Exercises.Length).Select(x=>x.ToString(CultureInfo.InvariantCulture)).Order();
     if(labels.SequenceEqual(r.Questions.Select(q=>Label(q.Label)).Order())&&r.Questions.All(q=>q.Verdict is "correct" or "incorrect"))result=r;else state="pending_review";
    }
   }
  }
  var original=await db.AcademicGradingJobs.AsNoTracking().SingleAsync(x=>x.Id==plan.JobId);var originalQuestions=Json.Read<HomeworkGradeResult>(original.ResultJson).Questions;
  var skills=body.Exercises.Select((x,i)=>new{Exercise=x,Label=(i+1).ToString(CultureInfo.InvariantCulture)}).GroupBy(x=>x.Exercise.WrongLabel).Select(g=>{
   var correct=result==null?0:g.Count(x=>result.Questions.Single(q=>Label(q.Label)==x.Label).Verdict=="correct");var incorrect=result==null?0:g.Count()-correct;
   return new RetestSkill(g.Key,g.First().Exercise.WrongStem,originalQuestions.FirstOrDefault(q=>q.Label==g.Key)?.KnowledgePoints??[],g.Count(),correct,incorrect,result==null?"pending":incorrect==0?"passed":"needs_practice");
  }).ToArray();
  return new(collection.Id,valid&&collection.State=="approved"?"/pages/homework-submit/homework-submit?t="+Uri.EscapeDataString(AcademicHomeworkCollection.Share(collection,p)):null,state,subs.Length,subs.Count(x=>!x.StudentId.HasValue),body.Exercises.Length,skills.Sum(x=>x.Correct),skills.Sum(x=>x.Incorrect),skills.Count(x=>x.State=="passed"),skills.Count(x=>x.State=="needs_practice"),skills,sub?.SubmittedAt);
 }
 public static void Map(RouteGroupBuilder group){
  // Compatibility for training drafts approved before this workflow existed.
  group.MapPost("/homework-trainings/{id:guid}/retest",async(Guid id,AcademicApprovalInput input,Data db,Access a,HttpContext ctx,IDataProtectionProvider p)=>{
   await AcademicPilot.Gate(ctx,true);await using var tx=await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.Serializable);
   var plan=await db.AcademicHomeworkTrainings.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"训练不存在");AcademicPilot.Revision(input.Revision,plan.Revision);Guard.That(input.Confirmed,"请先确认训练",409);
   var job=await db.AcademicGradingJobs.SingleAsync(x=>x.Id==plan.JobId);var sub=await db.AcademicSubmissions.SingleAsync(x=>x.Id==job.SubmissionId);await AcademicHomeworkCollection.Scope(sub.CollectionId,db,a);
   await Ensure(plan,db,a);await tx.CommitAsync();return Results.Ok(await View(plan,db,p));
  });
 }
}
