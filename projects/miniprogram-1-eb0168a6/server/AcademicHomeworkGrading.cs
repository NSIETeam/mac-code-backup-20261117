using Microsoft.EntityFrameworkCore;
namespace Siyue;
// Extension contract: providers receive immutable photo IDs and teacher references.
// OCR uncertainty and per-question confidence must remain visible to the reviewer.
public record HomeworkGradeInput(Guid SubmissionId,string SourceHash,Guid[] PhotoIds,string ReferenceAnswer);
public record HomeworkGradeQuestion(string Label,string RecognizedAnswer,string Verdict,decimal? Score,decimal? MaxScore,decimal Confidence,string Explanation,string[] KnowledgePoints,string Stem="");
public record HomeworkGradeResult(string Provider,string Model,HomeworkGradeQuestion[] Questions,string[] Warnings);
public interface IAcademicHomeworkGrader {bool Available {get;} Task<HomeworkGradeResult> Grade(HomeworkGradeInput input,CancellationToken cancellationToken);}
public class UnconfiguredHomeworkGrader : IAcademicHomeworkGrader {
 public bool Available=>false;
 public Task<HomeworkGradeResult> Grade(HomeworkGradeInput input,CancellationToken cancellationToken)=>throw new ApiException(503,"尚未接入自动批改服务");
}
public class AcademicGradingJob : Entity {
 public Guid SubmissionId {get;set;} public string SourceHash {get;set;}="";public string ReferenceAnswer {get;set;}="";
 public DateTimeOffset? AttemptedAt {get;set;} public string Error {get;set;}="";
 public string State {get;set;}="awaiting_provider";public string ResultJson {get;set;}="{}";public int Revision {get;set;}
 public Guid CreatedBy {get;set;} public Guid? ReviewedBy {get;set;} public DateTimeOffset CreatedAt {get;set;}=Clock.Now;
}
public partial class Data {public DbSet<AcademicGradingJob> AcademicGradingJobs=>Set<AcademicGradingJob>();}
public record GradingRequest(string ReferenceAnswer);
public record GradingEditRequest(int Revision,HomeworkGradeResult Result);
public static class AcademicHomeworkGrading {
 public static void Configure(ModelBuilder b){b.Entity<AcademicGradingJob>().HasIndex(x=>new{x.SubmissionId,x.SourceHash}).IsUnique();b.Entity<AcademicGradingJob>().Property(x=>x.Revision).IsConcurrencyToken();b.Entity<AcademicGradingJob>().HasOne<AcademicSubmission>().WithMany().HasForeignKey(x=>x.SubmissionId).OnDelete(DeleteBehavior.Restrict);}
 static async Task<AcademicSubmission> Scope(Guid id,Data db,Access a){var s=await db.AcademicSubmissions.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"作业不存在");await AcademicHomeworkCollection.Scope(s.CollectionId,db,a);return s;}
 internal static async Task<string> Source(Guid id,string reference,Data db){var photos=await db.AcademicSubmissionPhotos.AsNoTracking().Where(x=>x.SubmissionId==id).OrderBy(x=>x.Position).Select(x=>x.Id).ToArrayAsync();var answers=await db.AcademicSubmissions.Where(x=>x.Id==id).Select(x=>x.AnswersJson).SingleAsync();return Secrets.Hash(answers=="[]"?Json.Write(new{id,photos,reference}):Json.Write(new{id,photos,reference,answers}));}
 public static void Map(RouteGroupBuilder group){
  group.MapGet("/grading-contract",(IAcademicHomeworkGrader g)=>new{version=1,available=g.Available,requiresHumanReview=true,input=new[]{"submissionId","sourceHash","photoIds","referenceAnswer"},output=new[]{"provider","model","questions[label,recognizedAnswer,verdict,score,maxScore,confidence,explanation,knowledgePoints]","warnings"}});
  group.MapGet("/collection-submissions/{id:guid}/grading",async(Guid id,Data db,Access a)=>{await Scope(id,db,a);return await db.AcademicGradingJobs.AsNoTracking().Where(x=>x.SubmissionId==id).OrderByDescending(x=>x.CreatedAt).Select(x=>new{x.Id,x.State,x.Revision,x.ReferenceAnswer,x.ResultJson}).ToArrayAsync();});
  group.MapPost("/collection-submissions/{id:guid}/grading",async(Guid id,GradingRequest r,Data db,Access a,IAcademicHomeworkGrader grader,HttpContext ctx)=>{
   var sub=await Scope(id,db,a);Guard.That(r.ReferenceAnswer is {Length:>0 and <=16000},"请提供题目及参考答案，不能只凭照片猜测评分依据");var hash=await Source(id,r.ReferenceAnswer,db);
   var job=await db.AcademicGradingJobs.SingleOrDefaultAsync(x=>x.SubmissionId==id&&x.SourceHash==hash);if(job!=null&&(job.State is not ("awaiting_provider" or "failed")||!grader.Available))return Results.Ok(new{job.Id,job.State,job.Revision});
   if(job==null){job=new(){SubmissionId=id,SourceHash=hash,ReferenceAnswer=r.ReferenceAnswer,CreatedBy=a.User.Id};db.AcademicGradingJobs.Add(job);}if(grader.Available){job.State="processing";job.AttemptedAt=DateTimeOffset.UtcNow;job.Error="";job.Revision++;}await db.SaveChangesAsync();
   if(grader.Available){var ids=await db.AcademicSubmissionPhotos.Where(x=>x.SubmissionId==id).OrderBy(x=>x.Position).Select(x=>x.Id).ToArrayAsync();
    try{var result=await grader.Grade(new(id,hash,ids,r.ReferenceAnswer),ctx.RequestAborted);Validate(result);job.ResultJson=Json.Write(result);job.State="needs_review";}
    catch(OperationCanceledException){job.State="failed";job.Error="批改请求中断，请重试";}
    catch(Exception){job.State="failed";job.Error="批改暂未完成，请重试";}job.Revision++;await db.SaveChangesAsync();}
   return Results.Ok(new{job.Id,job.State,job.Revision});
  });
  group.MapPut("/grading-jobs/{id:guid}",async(Guid id,GradingEditRequest r,Data db,Access a)=>{
   var job=await db.AcademicGradingJobs.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"批改记录不存在");await Scope(job.SubmissionId,db,a);AcademicPilot.Revision(r.Revision,job.Revision);Guard.That(job.State is "needs_review" or "reviewed","批改结果尚未生成",409);Validate(r.Result);Guard.That(await AcademicHomeworkWorkflow.Current(job,db),"照片已补交，请重新批改",409);job.ResultJson=Json.Write(r.Result);job.State="needs_review";job.ReviewedBy=null;job.Revision++;await db.SaveChangesAsync();return Results.Ok(new{job.Revision});
  });
  group.MapPost("/grading-jobs/{id:guid}/review",async(Guid id,AcademicApprovalInput r,Data db,Access a,HttpContext ctx)=>{
   await AcademicPilot.Gate(ctx,true);
   var job=await db.AcademicGradingJobs.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"批改记录不存在");await Scope(job.SubmissionId,db,a);AcademicPilot.Revision(r.Revision,job.Revision);Guard.That(r.Confirmed&&job.State=="needs_review","请先核对批改结果",409);Guard.That(await AcademicHomeworkWorkflow.Current(job,db),"照片已补交，请重新批改",409);
   Guard.That(Json.Read<HomeworkGradeResult>(job.ResultJson).Questions.All(x=>x.Verdict!="uncertain"),"请先人工核对并修正无法判断的题目",409);await AcademicTrainingRetesting.ValidateReview(job,db);job.State="reviewed";job.ReviewedBy=a.User.Id;job.Revision++;AcademicParentPortal.Audit(db,a,id,"grading.reviewed",new{job.SourceHash,job.Revision});await db.SaveChangesAsync();return Results.Ok(new{job.Revision});
  });
 }
 internal static void Validate(HomeworkGradeResult result){Guard.That(result.Provider is {Length:>0 and <=120}&&result.Model is {Length:>0 and <=120}&&result.Questions is {Length:>0 and <=100}&&result.Warnings is {Length:<=100},"批改服务返回格式无效",502);Guard.That(result.Questions.Select(q=>q.Label).Distinct().Count()==result.Questions.Length&&result.Warnings.All(w=>w is {Length:<=2000}),"批改题号重复或提示过长",502);foreach(var q in result.Questions)Guard.That(q.Label is {Length:>0 and <=100}&&q.RecognizedAnswer is {Length:<=8000}&&q.Explanation is {Length:<=8000}&&q.Confidence is >=0 and <=1&&q.Verdict is "correct" or "incorrect" or "uncertain"&&q.Score is null or >=0&&q.MaxScore is null or >=0&&(q.Score==null||q.MaxScore!=null&&q.Score<=q.MaxScore)&&q.Stem is {Length:<=16000}&&q.KnowledgePoints is {Length:<=30}&&q.KnowledgePoints.All(k=>k is {Length:>0 and <=100}),"批改服务返回格式无效",502);}
}
