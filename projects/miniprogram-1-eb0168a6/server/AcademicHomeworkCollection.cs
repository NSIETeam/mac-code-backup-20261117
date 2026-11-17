using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using System.Globalization;
using System.Security.Cryptography;
namespace Siyue;

public class AcademicCollection : Entity {
 public Guid BatchId {get;set;} public Guid ClassId {get;set;} public Guid CreatedBy {get;set;}
 public string Title {get;set;}=""; public string Instructions {get;set;}=""; public string DueOn {get;set;}="";
 public string State {get;set;}="draft"; public int Revision {get;set;} public string RosterJson {get;set;}="[]";
 public Guid? ApprovedBy {get;set;} public DateTimeOffset? ApprovedAt {get;set;} public DateTimeOffset CreatedAt {get;set;}=Clock.Now;
 public string RequestKey {get;set;}="";
}
public class AcademicSubmission : Entity {
 public Guid CollectionId {get;set;} public string Name {get;set;}=""; public Guid? StudentId {get;set;}
 public string AnswersJson {get;set;}="[]"; public string AnswersRequestKey {get;set;}="";
 public string? ParentKey {get;set;} public Guid? ParentBindingId {get;set;} public DateTimeOffset? FirstSubmittedAt {get;set;}
 public int Revision {get;set;} public string RequestKey {get;set;}=""; public string ContentHash {get;set;}="";
 public DateTimeOffset SubmittedAt {get;set;}=Clock.Now; public Guid? CheckedBy {get;set;}
}
public class AcademicSubmissionPhoto : Entity {
 public Guid SubmissionId {get;set;} public int Position {get;set;} public string ContentType {get;set;}="image/jpeg";
 public byte[] Bytes {get;set;}=[];
}
public partial class Data {
 public DbSet<AcademicCollection> AcademicCollections=>Set<AcademicCollection>();
 public DbSet<AcademicSubmission> AcademicSubmissions=>Set<AcademicSubmission>();
 public DbSet<AcademicSubmissionPhoto> AcademicSubmissionPhotos=>Set<AcademicSubmissionPhoto>();
}
public record CollectionInput(Guid[] ClassIds,string Title,string Instructions,string DueOn,string RequestKey);
public record CollectionCheckInput(int Revision,Guid StudentId);
public record CollectionRoster(Guid Id,string Name);
public static class AcademicHomeworkCollection {
 const long MaxBytes=12*1024*1024;
 public static void Configure(ModelBuilder b) {
  AcademicHomeworkAttachments.Configure(b);
  b.Entity<AcademicCollection>().HasIndex(x=>new{x.CreatedBy,x.RequestKey,x.ClassId}).IsUnique();
  b.Entity<AcademicCollection>().Property(x=>x.Revision).IsConcurrencyToken();
  b.Entity<AcademicCollection>().HasOne<SchoolClass>().WithMany().HasForeignKey(x=>x.ClassId).OnDelete(DeleteBehavior.Restrict);
  b.Entity<AcademicSubmission>().HasOne<AcademicCollection>().WithMany().HasForeignKey(x=>x.CollectionId).OnDelete(DeleteBehavior.Restrict);
  b.Entity<AcademicSubmission>().Property(x=>x.Revision).IsConcurrencyToken();
  b.Entity<AcademicSubmission>().HasIndex(x=>new{x.CollectionId,x.StudentId});
  b.Entity<AcademicSubmissionPhoto>().HasOne<AcademicSubmission>().WithMany().HasForeignKey(x=>x.SubmissionId).OnDelete(DeleteBehavior.Cascade);
 }
 static IDataProtector Protector(IDataProtectionProvider p)=>p.CreateProtector("siyue.academic.homework.v1");
 static string Pack(IDataProtectionProvider p,object data)=>Protector(p).Protect(Json.Write(data));
 record Ticket(string Kind,Guid CollectionId,int Revision,Guid SubmissionId);
 static Ticket Read(IDataProtectionProvider p,string token,string kind) {
  try {var t=Json.Read<Ticket>(Protector(p).Unprotect(token));Guard.That(t.Kind==kind,"入口无效",404);return t;}
  catch {throw new ApiException(404,"入口无效，请重新打开群里的作业卡片");}
 }
 internal static async Task<AcademicCollection> Scope(Guid id,Data db,Access a) {
  var row=await db.AcademicCollections.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"作业不存在");await a.Class(row.ClassId);return row;
 }
 internal static async Task<AcademicCollection> Public(string ticket,Data db,IDataProtectionProvider p,IConfiguration c) {
  Guard.That(c.GetValue<bool>("AcademicPilot:Enabled"),"入口不存在",404);
  var t=Read(p,ticket,"share");var row=await db.AcademicCollections.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==t.CollectionId);
  Guard.That(row!=null&&row.State=="approved"&&row.Revision==t.Revision,"这份作业已关闭或更新，请联系老师",410);
  var creator=await db.Staff.SingleOrDefaultAsync(x=>x.Id==row!.CreatedBy&&x.Enabled);
  Guard.That(creator!=null&&await AcademicPilot.Listed(c,db,creator.Id,"Allowed")&&await db.Classes.AnyAsync(x=>x.Id==row!.ClassId)&&(creator.Role=="admin"||await db.Assignments.AnyAsync(x=>x.StaffId==creator.Id&&x.ClassId==row!.ClassId)),"作业已关闭",410);
  Guard.That(DateOnly.Parse(row!.DueOn,CultureInfo.InvariantCulture).AddDays(30)>=DateOnly.FromDateTime(Clock.Now.UtcDateTime),"收作业入口已过期",410);
  Guard.That(await AcademicTrainingRetesting.CollectionCurrent(row!.Id,db),"原错题或训练依据已更新，请联系老师重新生成训练",410);
  return row!;
 }
 internal static string Share(AcademicCollection row,IDataProtectionProvider p)=>Pack(p,new Ticket("share",row.Id,row.Revision,Guid.Empty));
 internal static string Receipt(AcademicCollection row,Guid submissionId,IDataProtectionProvider p)=>Pack(p,new Ticket("receipt",row.Id,row.Revision,submissionId));
 static object Summary(AcademicCollection x)=>new{x.Id,x.BatchId,x.ClassId,x.Title,x.Instructions,x.DueOn,x.State,x.Revision,x.ApprovedAt};
 static void Audit(Data db,Access a,Guid id,string action)=>db.AcademicOperationEvents.Add(new(){EntityId=id,ActorId=a.User.Id,Action=action});
 public static void Map(WebApplication app,RouteGroupBuilder group) {
  group.MapGet("/collections",async(Guid? classId,Data db,Access a)=>{
   if(classId.HasValue)await a.Class(classId.Value);var uid=a.User.Id;var admin=a.User.Role=="admin";
   var rows=await db.AcademicCollections.AsNoTracking().Where(x=>(!classId.HasValue||x.ClassId==classId)&&db.Classes.Any(c=>c.Id==x.ClassId)&&(admin||db.Assignments.Any(y=>y.ClassId==x.ClassId&&y.StaffId==uid))).OrderByDescending(x=>x.CreatedAt).Take(200).ToArrayAsync();return rows.Select(Summary);
  });
  group.MapPost("/collections",async(CollectionInput r,Data db,Access a)=>{
   Guard.That(r.ClassIds is {Length:>0 and <=30}&&r.ClassIds.Distinct().Count()==r.ClassIds.Length,"请选择班级");
   Guard.That(r.Title is {Length:>0 and <=120}&&!string.IsNullOrWhiteSpace(r.Title)&&r.Instructions is {Length:>0 and <=4000}&&!string.IsNullOrWhiteSpace(r.Instructions),"请填写作业名称和要求");
   Guard.That(DateOnly.TryParseExact(r.DueOn,"yyyy-MM-dd",CultureInfo.InvariantCulture,DateTimeStyles.None,out var due)&&due>=DateOnly.FromDateTime(Clock.Now.ToOffset(TimeSpan.FromHours(8)).DateTime)&&due<=DateOnly.FromDateTime(Clock.Now.UtcDateTime).AddYears(1),"请选择有效的截止日期");
   Guard.That(r.RequestKey is {Length:>0 and <=100},"缺少提交标识");foreach(var id in r.ClassIds)await a.Class(id);
   var old=await db.AcademicCollections.Where(x=>x.CreatedBy==a.User.Id&&x.RequestKey==r.RequestKey).ToArrayAsync();
   if(old.Length>0){Guard.That(old.Length==r.ClassIds.Length&&old.All(x=>r.ClassIds.Contains(x.ClassId)&&x.Title==r.Title.Trim()&&x.Instructions==r.Instructions.Trim()&&x.DueOn==r.DueOn),"提交标识已用于其他作业",409);return Results.Ok(old.Select(Summary));}
   var batch=Guid.NewGuid();var rows=r.ClassIds.Select(id=>new AcademicCollection{BatchId=batch,ClassId=id,CreatedBy=a.User.Id,Title=r.Title.Trim(),Instructions=r.Instructions.Trim(),DueOn=r.DueOn,RequestKey=r.RequestKey}).ToArray();
   db.AcademicCollections.AddRange(rows);foreach(var row in rows)Audit(db,a,row.Id,"collection.drafted");await db.SaveChangesAsync();return Results.Ok(rows.Select(Summary));
  });
  group.MapGet("/collections/{id:guid}",async(Guid id,Data db,Access a,IDataProtectionProvider p)=>{
   var row=await Scope(id,db,a);var students=row.RosterJson=="[]"?await db.Students.Where(x=>x.ClassId==row.ClassId).Select(x=>new CollectionRoster(x.Id,x.Name)).ToArrayAsync():Json.Read<CollectionRoster[]>(row.RosterJson);
   var submissions=await db.AcademicSubmissions.AsNoTracking().Where(x=>x.CollectionId==id).OrderByDescending(x=>x.SubmittedAt).ToArrayAsync();
   var onlineTest=await db.AcademicOnlineTests.AnyAsync(x=>x.Id==id);
   var receivedIds=submissions.Where(x=>(!onlineTest||x.AnswersJson!="[]")&&x.StudentId.HasValue&&students.Any(s=>s.Id==x.StudentId)).Select(x=>x.StudentId!.Value).Distinct().ToArray();
   var deadline=DateTimeOffset.ParseExact(row.DueOn+"T23:59:59+08:00","yyyy-MM-dd'T'HH:mm:sszzz",CultureInfo.InvariantCulture);
   var late=receivedIds.Count(student=>submissions.Where(x=>x.StudentId==student).Min(x=>x.FirstSubmittedAt??x.SubmittedAt)>deadline);
   var jobs=await db.AcademicGradingJobs.AsNoTracking().Where(j=>submissions.Select(s=>s.Id).Contains(j.SubmissionId)).ToArrayAsync();
   var reviewed=new HashSet<Guid>();foreach(var job in jobs.Where(j=>j.State=="reviewed")){var sub=submissions.Single(s=>s.Id==job.SubmissionId);if(sub.StudentId.HasValue&&await AcademicHomeworkWorkflow.Current(job,db))reviewed.Add(sub.StudentId.Value);}
   var statistics=new{expected=students.Length,received=receivedIds.Length,missing=students.Length-receivedIds.Length,pendingIdentity=submissions.Count(x=>!x.StudentId.HasValue),late,onTime=receivedIds.Length-late,reviewed=reviewed.Count,awaitingGrading=receivedIds.Count(x=>!reviewed.Contains(x)),submissionCount=submissions.Length};
   return new{attachmentName=await AcademicHomeworkAttachments.Name(id,db),onlineTest=(await db.AcademicOnlineTests.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==id)) is {} test?Json.Read<OnlineTestQuestion[]>(test.QuestionsJson):null,statistics,collection=Summary(row),className=(await db.Classes.SingleAsync(x=>x.Id==row.ClassId)).Name,sharePath=row.State=="approved"?"/pages/homework-submit/homework-submit?t="+Uri.EscapeDataString(Share(row,p)):null,
    students=students.Select(s=>new{s.Id,s.Name,submitted=receivedIds.Contains(s.Id)}),submissions=submissions.Select(x=>new{x.Id,x.Name,x.StudentId,x.Revision,x.SubmittedAt,photos=db.AcademicSubmissionPhotos.AsNoTracking().Where(y=>y.SubmissionId==x.Id).OrderBy(y=>y.Position).Select(y=>y.Id).ToArray()})};
  });
  group.MapPost("/collections/{id:guid}/approve",async(Guid id,AcademicApprovalInput input,Data db,Access a,HttpContext ctx)=>{
   await AcademicPilot.Gate(ctx,true);var row=await Scope(id,db,a);AcademicPilot.Revision(input.Revision,row.Revision);Guard.That(input.Confirmed&&row.State=="draft","请核对内容后确认",409);
   var roster=await db.Students.Where(x=>x.ClassId==row.ClassId).Select(x=>new CollectionRoster(x.Id,x.Name)).ToArrayAsync();Guard.That(roster.Length>0,"请先完善学生名单");
   row.RosterJson=Json.Write(roster);row.State="approved";row.ApprovedBy=a.User.Id;row.ApprovedAt=Clock.Now;row.Revision++;Audit(db,a,id,"collection.approved");await db.SaveChangesAsync();return Results.Ok(Summary(row));
  });
  group.MapPost("/collections/{id:guid}/close",async(Guid id,AcademicRevisionInput input,Data db,Access a,HttpContext ctx)=>{
   await AcademicPilot.Gate(ctx,true);var row=await Scope(id,db,a);AcademicPilot.Revision(input.Revision,row.Revision);row.State="closed";row.Revision++;Audit(db,a,id,"collection.closed");await db.SaveChangesAsync();return Results.Ok(Summary(row));
  });
  group.MapPut("/collection-submissions/{id:guid}/student",async(Guid id,CollectionCheckInput input,Data db,Access a)=>{
   var sub=await db.AcademicSubmissions.SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"提交不存在");var row=await Scope(sub.CollectionId,db,a);AcademicPilot.Revision(input.Revision,sub.Revision);
   Guard.That(Json.Read<CollectionRoster[]>(row.RosterJson).Any(x=>x.Id==input.StudentId)&&await db.Students.AnyAsync(x=>x.Id==input.StudentId&&x.ClassId==row.ClassId),"学生不属于本次作业名单",403);
   Guard.That(!sub.ParentBindingId.HasValue||sub.StudentId==input.StudentId,"已绑定家长的作业不能改归其他孩子",409);
   sub.StudentId=input.StudentId;sub.CheckedBy=a.User.Id;sub.Revision++;Audit(db,a,sub.Id,"collection.student_confirmed");await db.SaveChangesAsync();return Results.Ok(new{sub.Revision});
  });
  group.MapGet("/collection-photos/{id:guid}",async(Guid id,Data db,Access a,HttpContext ctx)=>{
   var photo=await db.AcademicSubmissionPhotos.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==id)??throw new ApiException(404,"照片不存在");var sub=await db.AcademicSubmissions.AsNoTracking().SingleAsync(x=>x.Id==photo.SubmissionId);await Scope(sub.CollectionId,db,a);ctx.Response.Headers["X-Content-Type-Options"]="nosniff";return Results.File(photo.Bytes,photo.ContentType);
  });
  var parent=app.MapGroup("/academic-pilot/collect/{ticket}").RequireRateLimiting("homework-parent").AddEndpointFilter(async (context,next)=>{
   try{return await next(context);}catch(Npgsql.PostgresException e) when(e.SqlState is "40001" or "40P01"){throw new ApiException(409,"记录刚刚更新，请重试提交");}catch(Microsoft.Data.Sqlite.SqliteException e) when(e.SqliteErrorCode is 5 or 6){throw new ApiException(409,"记录刚刚更新，请重试提交");}
  });
  AcademicHomeworkAttachments.Map(group,parent);
  parent.MapGet("",async(string ticket,Data db,IDataProtectionProvider p,IConfiguration c)=>{
   var row=await Public(ticket,db,p,c);return new{attachmentName=await AcademicHomeworkAttachments.Name(row.Id,db),parentPortalAvailable=c.GetValue<bool>("AcademicPilot:ParentBindingEnabled")&&!string.IsNullOrWhiteSpace(c["WeChat:Secret"]),row.Id,row.ClassId,className=(await db.Classes.SingleAsync(x=>x.Id==row.ClassId)).Name,row.Title,row.Instructions,row.DueOn,questions=(await db.AcademicOnlineTests.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==row.Id)) is {} test?Json.Read<OnlineTestQuestion[]>(test.QuestionsJson):null};
  });
  parent.MapPost("/begin",async(string ticket,Data db,IDataProtectionProvider p,IConfiguration c)=>{
   var row=await Public(ticket,db,p,c);return new{receipt=Pack(p,new Ticket("receipt",row.Id,row.Revision,Guid.NewGuid()))};
  });
  parent.MapPost("/submit",async(string ticket,Data db,IDataProtectionProvider p,IConfiguration c,HttpContext ctx)=>{
   var row=await Public(ticket,db,p,c);Guard.That(ctx.Request.ContentLength is >0 and <=MaxBytes,"照片总大小不能超过12MB");
   var form=await ctx.Request.ReadFormAsync();var receipt=Read(p,form["receipt"].ToString(),"receipt");Guard.That(receipt.CollectionId==row.Id&&receipt.Revision==row.Revision,"提交凭证不属于这份作业",403);
   var name=form["name"].ToString().Trim();var key=form["requestKey"].ToString();Guard.That(name.Length is >0 and <=60&&key.Length is >0 and <=100,"请填写孩子姓名");
   Guard.That(form.Files.Count is >0 and <=9,"请上传1–9张作业照片");var photos=new List<AcademicSubmissionPhoto>();
   foreach(var file in form.Files){Guard.That(file.Length is >0 and <=3*1024*1024,"每张照片不能超过3MB");await using var ms=new MemoryStream();await file.CopyToAsync(ms);var bytes=ms.ToArray();var jpeg=bytes.Length>20&&bytes[0]==255&&bytes[1]==216&&bytes[2]==255;var png=bytes.Length>24&&bytes.Take(8).SequenceEqual(new byte[]{137,80,78,71,13,10,26,10});Guard.That(jpeg||png,"只支持JPEG或PNG照片");photos.Add(new(){SubmissionId=receipt.SubmissionId,Position=photos.Count,ContentType=jpeg?"image/jpeg":"image/png",Bytes=bytes});}
   var hash=Secrets.Hash(name+":"+string.Join(":",photos.Select(x=>Convert.ToHexString(SHA256.HashData(x.Bytes)))));
   await using var tx=await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.Serializable);
   var live=await db.AcademicCollections.AsNoTracking().SingleAsync(x=>x.Id==row.Id);Guard.That(live.State=="approved"&&live.Revision==row.Revision,"作业已关闭",410);
   var sub=await db.AcademicSubmissions.SingleOrDefaultAsync(x=>x.Id==receipt.SubmissionId);
   AcademicParentBinding? parentBinding=null;string? parentKey=null;
   if(!string.IsNullOrEmpty(form["bindingId"])){
    Guard.That(Guid.TryParse(form["bindingId"],out var bindingId),"家长绑定无效");parentKey=await AcademicParentPortal.Identity(ctx,db);parentBinding=await AcademicParentPortal.Binding(bindingId,parentKey,db,c);
    Guard.That(parentBinding.ClassId==row.ClassId&&Json.Read<CollectionRoster[]>(row.RosterJson).Any(x=>x.Id==parentBinding.StudentId),"孩子不属于本次作业名单",403);
    var child=await db.Students.SingleAsync(x=>x.Id==parentBinding.StudentId);Guard.That(name==child.Name,"孩子姓名已变化，请重新打开作业",409);
   }
   Guard.That(sub?.ParentKey==null||sub.ParentKey==parentKey&&sub.ParentBindingId==parentBinding?.Id,"请使用原家长身份补交",403);
   Guard.That(parentBinding==null||sub?.StudentId==null||sub.StudentId==parentBinding.StudentId,"本份作业已归属其他孩子",409);
   if(sub!=null&&sub.RequestKey==key){Guard.That(sub.ContentHash==hash,"请刷新提交标识后重试",409);return Results.Ok(new{sub.Revision,state="submitted"});}
   var expected=int.TryParse(form["revision"],out var v)?v:-1;AcademicPilot.Revision(expected,sub?.Revision??0);
   Guard.That(await db.AcademicSubmissions.CountAsync(x=>x.CollectionId==row.Id)<500||sub!=null,"本次收作业已达上限，请联系老师",429);
   if(sub==null){sub=new(){Id=receipt.SubmissionId,CollectionId=row.Id};db.AcademicSubmissions.Add(sub);}else Guard.That(sub.Name==name,"已提交后的姓名请联系老师核对",409);
   var oldPhotos=await db.AcademicSubmissionPhotos.Where(x=>x.SubmissionId==sub.Id).Select(x=>new{x.Position,size=x.Bytes.Length}).ToArrayAsync();
   Guard.That(oldPhotos.Length+photos.Count<=9&&oldPhotos.Sum(x=>(long)x.size)+photos.Sum(x=>(long)x.Bytes.Length)<=MaxBytes,"本次作业最多9张照片、总计12MB");
   foreach(var photo in photos)photo.Position+=oldPhotos.Length;
   if(sub.Name!=name){sub.StudentId=null;sub.CheckedBy=null;}sub.FirstSubmittedAt??=Clock.Now;if(parentBinding!=null){sub.ParentKey=parentKey;sub.ParentBindingId=parentBinding.Id;sub.StudentId=parentBinding.StudentId;sub.CheckedBy=parentBinding.VerifiedBy;}sub.Name=name;sub.RequestKey=key;sub.ContentHash=hash;sub.Revision++;sub.SubmittedAt=Clock.Now;
   db.AcademicSubmissionPhotos.AddRange(photos);await db.SaveChangesAsync();await AcademicHomeworkWorkflow.Enqueue(sub.Id,db);await tx.CommitAsync();return Results.Ok(new{sub.Revision,state="submitted"});
  }).DisableAntiforgery();
  parent.MapPost("/answers",async(string ticket,OnlineAnswersInput r,Data db,IDataProtectionProvider p,IConfiguration c,HttpContext ctx)=>{
   var row=await Public(ticket,db,p,c);var receipt=Read(p,r.Receipt,"receipt");Guard.That(receipt.CollectionId==row.Id&&receipt.Revision==row.Revision,"提交凭证无效",403);
   var test=await db.AcademicOnlineTests.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==row.Id)??throw new ApiException(404,"这份作业不是在线测验");var questions=Json.Read<OnlineTestQuestion[]>(test.QuestionsJson);
   Guard.That(r.Answers!=null&&r.Answers.Length==questions.Length&&r.Answers.Select(x=>x.Label).Distinct().Count()==questions.Length&&r.Answers.All(a=>questions.Any(q=>q.Label==a.Label)&&a.Answer is {Length:<=2000})&&r.Answers.Sum(a=>a.Answer.Length)<=16000,"请按题号填写答案，单题不超过2000字");
   Guard.That(r.Answers!.Any(a=>!string.IsNullOrWhiteSpace(a.Answer))||await db.AcademicSubmissionPhotos.AnyAsync(x=>x.SubmissionId==receipt.SubmissionId),"请填写答案或上传解题照片");
   var name=(r.Name??"").Trim();Guard.That(name.Length is >0 and <=60&&r.RequestKey is {Length:>0 and <=100},"请填写孩子姓名");var json=Json.Write(questions.Select(q=>r.Answers!.Single(a=>a.Label==q.Label)).ToArray());
   await using var tx=await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.Serializable);var live=await db.AcademicCollections.AsNoTracking().SingleAsync(x=>x.Id==row.Id);Guard.That(live.State=="approved"&&live.Revision==row.Revision,"测验已关闭",410);
   var sub=await db.AcademicSubmissions.SingleOrDefaultAsync(x=>x.Id==receipt.SubmissionId);AcademicParentBinding? binding=null;string? parentKey=null;
   if(r.BindingId.HasValue){parentKey=await AcademicParentPortal.Identity(ctx,db);binding=await AcademicParentPortal.Binding(r.BindingId.Value,parentKey,db,c);Guard.That(binding.ClassId==row.ClassId&&Json.Read<CollectionRoster[]>(row.RosterJson).Any(x=>x.Id==binding.StudentId),"孩子不属于本次测验",403);Guard.That(name==(await db.Students.SingleAsync(x=>x.Id==binding.StudentId)).Name,"孩子姓名已变化",409);}
   Guard.That(sub?.ParentKey==null||sub.ParentKey==parentKey&&sub.ParentBindingId==binding?.Id,"请使用原家长身份提交",403);Guard.That(binding==null||sub?.StudentId==null||sub.StudentId==binding.StudentId,"本份答卷已归属其他孩子",409);
   if(sub!=null&&sub.AnswersRequestKey==r.RequestKey){Guard.That(sub.AnswersJson==json&&sub.Name==name,"请刷新提交标识后重试",409);return Results.Ok(new{sub.Revision,state="submitted"});}
   AcademicPilot.Revision(r.Revision,sub?.Revision??0);Guard.That(await db.AcademicSubmissions.CountAsync(x=>x.CollectionId==row.Id)<500||sub!=null,"本次测验已达上限",429);
   if(sub==null){sub=new(){Id=receipt.SubmissionId,CollectionId=row.Id,Name=name};db.AcademicSubmissions.Add(sub);}else Guard.That(sub.Name==name,"已提交后的姓名请联系老师核对",409);
   if(binding!=null){sub.ParentKey=parentKey;sub.ParentBindingId=binding.Id;sub.StudentId=binding.StudentId;sub.CheckedBy=binding.VerifiedBy;}sub.AnswersJson=json;sub.AnswersRequestKey=r.RequestKey;sub.FirstSubmittedAt??=Clock.Now;sub.SubmittedAt=Clock.Now;sub.Revision++;
   await db.SaveChangesAsync();await AcademicHomeworkWorkflow.Enqueue(sub.Id,db);await tx.CommitAsync();return Results.Ok(new{sub.Revision,state="submitted"});
  });
  parent.MapPost("/mine",async(string ticket,AcademicReceiptInput r,Data db,IDataProtectionProvider p,IConfiguration c)=>{
   var row=await Public(ticket,db,p,c);var t=Read(p,r.Receipt,"receipt");Guard.That(t.CollectionId==row.Id&&t.Revision==row.Revision,"提交凭证无效",403);var sub=await db.AcademicSubmissions.AsNoTracking().SingleOrDefaultAsync(x=>x.Id==t.SubmissionId);
   return new{answers=sub==null?Array.Empty<OnlineTestAnswer>():Json.Read<OnlineTestAnswer[]>(sub.AnswersJson),name=sub?.Name,revision=sub?.Revision??0,submittedAt=sub?.SubmittedAt,state=sub==null?"empty":"submitted",photoCount=sub==null?0:await db.AcademicSubmissionPhotos.CountAsync(x=>x.SubmissionId==sub.Id)};
  });
 }
}
public record AcademicReceiptInput(string Receipt);
