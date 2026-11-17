using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
namespace Siyue;

public record MobileQuestionCheck(int Revision,string ReviewToken,string Label,bool Confirmed);
public record MobileReviewApproval(int Revision,string ReviewToken,string[] Receipts,bool Confirmed);
public static class AcademicMobileReview {
 record Evidence(string Kind,Guid ActorId,Guid JobId,int Revision,string SourceHash,string ResultHash,Guid Session,string Label,long ExpiresAt);
 static IDataProtector Protector(IDataProtectionProvider p)=>p.CreateProtector("siyue.academic.mobile-review.v1");
 static string Pack(IDataProtectionProvider p,Evidence value)=>Protector(p).Protect(Json.Write(value));
 static Evidence Read(IDataProtectionProvider p,string token){
  try{Guard.That(token is {Length:>0 and <=12000},"复核凭证无效",403);return Json.Read<Evidence>(Protector(p).Unprotect(token));}catch{throw new ApiException(403,"复核凭证无效，请重新打开答卷");}
 }
 static void Verify(Evidence e,string kind,Guid actor,AcademicGradingJob j,int revision){
  Guard.That(e.Kind==kind&&e.ActorId==actor&&e.JobId==j.Id,"复核凭证不属于当前账号或答卷",403);
  Guard.That(e.ExpiresAt>=Clock.Now.ToUnixTimeMilliseconds(),"复核已超时，请重新打开答卷",410);
  Guard.That(e.Revision==revision&&e.SourceHash==j.SourceHash&&e.ResultHash==Secrets.Hash(j.ResultJson),"答卷已变化，请重新核对",409);
 }
 static async Task<AcademicGradingJob> Scope(Guid id,Data db,Access a,HttpContext ctx){
  await AcademicPilot.Gate(ctx,true);var j=await db.AcademicGradingJobs.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"批改不存在");var s=await db.AcademicSubmissions.SingleAsync(x=>x.Id==j.SubmissionId);await AcademicHomeworkCollection.Scope(s.CollectionId,db,a);
  Guard.That(await AcademicHomeworkWorkflow.Current(j,db),"学生补交或评分依据已变化，请重新打开最新答卷",409);return j;
 }
 public static void Map(RouteGroupBuilder group){
  group.MapGet("/grading-jobs/{id:guid}/review-sheet",async(Guid id,Data db,Access a,HttpContext ctx,IDataProtectionProvider p)=>{
   var j=await Scope(id,db,a,ctx);Guard.That(j.State=="needs_review","这份答卷已处理或尚未生成批改",409);var sub=await db.AcademicSubmissions.SingleAsync(x=>x.Id==j.SubmissionId);var result=Json.Read<HomeworkGradeResult>(j.ResultJson);
   var evidence=new Evidence("sheet",a.User.Id,j.Id,j.Revision,j.SourceHash,Secrets.Hash(j.ResultJson),Guid.NewGuid(),"",Clock.Now.AddMinutes(45).ToUnixTimeMilliseconds());
   return new{j.Id,j.Revision,sub.Name,sub.StudentId,reviewToken=Pack(p,evidence),j.ReferenceAnswer,result,
    questionOrder=result.Questions.OrderBy(q=>q.Verdict=="uncertain"?0:q.Verdict=="incorrect"?1:2).Select(q=>q.Label).ToArray(),
    photos=await db.AcademicSubmissionPhotos.AsNoTracking().Where(x=>x.SubmissionId==sub.Id).OrderBy(x=>x.Position).Select(x=>x.Id).ToArrayAsync(),submittedAnswers=Json.Read<OnlineTestAnswer[]>(sub.AnswersJson)};
  });
  group.MapPost("/grading-jobs/{id:guid}/question-checks",async(Guid id,MobileQuestionCheck input,Data db,Access a,HttpContext ctx,IDataProtectionProvider p)=>{
   var j=await Scope(id,db,a,ctx);AcademicPilot.Revision(input.Revision,j.Revision);Guard.That(j.State=="needs_review"&&input.Confirmed,"请查看并确认本题",409);var sheet=Read(p,input.ReviewToken);Verify(sheet,"sheet",a.User.Id,j,input.Revision);
   var q=Json.Read<HomeworkGradeResult>(j.ResultJson).Questions.SingleOrDefault(q=>q.Label==input.Label);Guard.That(q!=null,"题号不属于本次答卷",409);Guard.That(q!.Verdict is "correct" or "incorrect","不确定的题须先人工修正并保存",409);
   return Results.Ok(new{label=q.Label,receipt=Pack(p,sheet with{Kind="question",Label=q.Label})});
  });
  group.MapPost("/grading-jobs/{id:guid}/review-checked",async(Guid id,MobileReviewApproval input,Data db,Access a,HttpContext ctx,IDataProtectionProvider p)=>{
   var j=await Scope(id,db,a,ctx);var retry=j.State=="reviewed"&&j.ReviewedBy==a.User.Id&&j.Revision==input.Revision+1;
   Guard.That(input.Confirmed&&(retry||j.State=="needs_review"&&j.Revision==input.Revision),"答卷已变化或未确认，请重新核对",409);
   var sheet=Read(p,input.ReviewToken);Verify(sheet,"sheet",a.User.Id,j,input.Revision);var questions=Json.Read<HomeworkGradeResult>(j.ResultJson).Questions;
   Guard.That(input.Receipts!=null&&input.Receipts.Length==questions.Length,"请逐题查看并确认，不能跳过未核对题目",409);
   var checks=input.Receipts!.Select(x=>Read(p,x)).ToArray();foreach(var check in checks){Verify(check,"question",a.User.Id,j,input.Revision);Guard.That(check.Session==sheet.Session,"请使用本次打开答卷后的确认记录",409);}
   Guard.That(checks.Select(x=>x.Label).Distinct().Count()==questions.Length&&checks.Select(x=>x.Label).Order().SequenceEqual(questions.Select(x=>x.Label).Order()),"请核对全部题目，不能重复确认代替其他题",409);
   Guard.That(questions.All(q=>q.Verdict is "correct" or "incorrect"),"仍有不确定的题，请先人工修正",409);await AcademicTrainingRetesting.ValidateReview(j,db);
   if(retry)return Results.Ok(new{j.Revision,j.State,alreadyReviewed=true});
   j.State="reviewed";j.ReviewedBy=a.User.Id;j.Revision++;AcademicParentPortal.Audit(db,a,id,"grading.reviewed",new{j.SourceHash,j.Revision,mode="mobile_sequential",labels=checks.Select(x=>x.Label).ToArray()});await db.SaveChangesAsync();
   return Results.Ok(new{j.Revision,j.State,alreadyReviewed=false});
  });
 }
}
