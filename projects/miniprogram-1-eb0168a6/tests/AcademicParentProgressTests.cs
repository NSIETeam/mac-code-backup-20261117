using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public partial class AcademicBetaTests {
 class ParentFactory(bool grading=false, bool practice=false):BetaFactory {
  protected override void ConfigureWebHost(IWebHostBuilder b){base.ConfigureWebHost(b);b.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{["AcademicPilot:ParentBindingEnabled"]="true",["WeChat:AppId"]="test-app",["WeChat:Secret"]="test-only-not-real"}));b.ConfigureServices(s=>{s.AddSingleton<IAcademicParentIdentity>(new ParentIdentityStub());if(grading)s.AddSingleton<IAcademicHomeworkGrader>(new GraderStub());if(practice)s.AddSingleton<IQuestionBank>(new ParentPracticeBank());});}
 }
 sealed class ParentPracticeBank : IQuestionBank {
  public BankAvailability Availability()=>new(true,20,[],"fixture");
  public bool ContainsQuestion(BankQuestion question)=>true;
  public BankQueryResult Search(BankQuery query)=>throw new NotSupportedException();
  public BankKnowledgeNode[] Knowledge(string subject,string query)=>[];
  public QuestionMatch[] Recommend(string subject,string stage,string stem,string[] tags,int limit,ISet<string> excluded)
     => Enumerable.Range(1, Math.Min(limit, 20)).Select(n => new QuestionMatch(new BankQuestion
     {
      Id = $"practice-fixture-{n}", Subject = subject, Stage = stage,
      Stem = $"计算 1/4 + {n}/6", Answer = $"{3 + 2 * n}/12",
      Analysis = "先通分，再相加", Source = "验收样题", License = "验收",
      SourceKind = n == 1 ? "community" : ""
     }, 1, [])).ToArray();
 }
 class ParentIdentityStub:IAcademicParentIdentity {public Task<string> Exchange(string code,CancellationToken c){Guard.That(code is "parent-a" or "parent-b","微信凭证无效",401);return Task.FromResult(Secrets.Hash("test-parent:"+code));}}
 class GraderStub:IAcademicHomeworkGrader {public bool Available=>true;public Task<HomeworkGradeResult> Grade(HomeworkGradeInput input,CancellationToken c)=>Task.FromResult(new HomeworkGradeResult("test-provider","test-model",[new("1","1/2","incorrect",0,1,.9m,"需要通分",["通分"])],[]));}
 static async Task<HttpClient> ParentLogin(ParentFactory f,string code="parent-a"){var client=f.CreateClient();var r=await Factory.Post(client,"/academic-pilot/parent/wechat",new{code});client.DefaultRequestHeaders.Add("X-Parent-Token",r.GetProperty("token").GetString());return client;}
 static async Task<JsonElement> BindParent(HttpClient parent,HttpClient reviewer,Guid student,string path){var ticket=Uri.UnescapeDataString(path.Split('/').Last());var r=await Factory.Post(parent,"/academic-pilot/parent/bindings",new{ticket,childName=student.ToString()[..8],relationship="妈妈"});await Factory.Post(reviewer,Api+"/parent-bindings/"+r.GetProperty("id").GetGuid()+"/approve",new{revision=0,studentId=student,confirmed=true,checkCode=r.GetProperty("checkCode").GetString()});return r;}
 [Fact] public async Task Parent_binding_requires_private_check_and_only_exposes_verified_own_children(){
  using var f=new ParentFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var a=await ParentLogin(f);using var b=await ParentLogin(f,"parent-b");var(_,path)=await ApprovedCollection(author,reviewer,x.ClassId);
  var ticket=path.Split('/').Last();var request=await Factory.Post(a,"/academic-pilot/parent/bindings",new{ticket,childName="自报姓名",relationship="爸爸"});var id=request.GetProperty("id").GetGuid();
  Assert.Equal(HttpStatusCode.Forbidden,(await a.GetAsync("/academic-pilot/parent/children/"+id)).StatusCode);
  Assert.Equal(HttpStatusCode.Conflict,(await reviewer.PostAsJsonAsync(Api+"/parent-bindings/"+id+"/approve",new{revision=0,studentId=x.StudentId,confirmed=true,checkCode="wrong"})).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await author.PostAsJsonAsync(Api+"/parent-bindings/"+id+"/approve",new{revision=0,studentId=x.StudentId,confirmed=true,checkCode=request.GetProperty("checkCode").GetString()})).StatusCode);
  await Factory.Post(reviewer,Api+"/parent-bindings/"+id+"/approve",new{revision=0,studentId=x.StudentId,confirmed=true,checkCode=request.GetProperty("checkCode").GetString()});
  Assert.Equal("本班学生甲",(await Get(a,"/academic-pilot/parent/children/"+id)).GetProperty("name").GetString());
  Assert.Equal(HttpStatusCode.Forbidden,(await b.GetAsync("/academic-pilot/parent/children/"+id)).StatusCode);Assert.Empty((await Get(b,"/academic-pilot/parent/children")).EnumerateArray());
  await BindParent(a,reviewer,x.SecondStudentId,path);Assert.Equal(2,(await Get(a,"/academic-pilot/parent/children")).GetArrayLength());
  Assert.Equal(HttpStatusCode.Unauthorized,(await a.GetAsync(Api+"/collections")).StatusCode);Assert.Equal(HttpStatusCode.Unauthorized,(await author.GetAsync("/academic-pilot/parent/children")).StatusCode);
 }
 [Fact] public async Task Parent_stages_remain_private_until_review_and_edits_withdraw_visibility(){
  using var f=new ParentFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var a=await ParentLogin(f);var before=await OriginalBusinessSnapshot(f);var(_,path)=await ApprovedCollection(author,reviewer,x.ClassId);var binding=await BindParent(a,reviewer,x.StudentId,path);var child="/academic-pilot/parent/children/"+binding.GetProperty("id").GetGuid();
  var stage=await Factory.Post(author,Api+"/students/"+x.StudentId+"/stages",new{title="分数单元",from="2026-10-01",to="2026-11-02"});var sid=stage.GetProperty("id").GetGuid();Assert.Contains("错题记录1题次",stage.GetProperty("evidence").GetString());
  Assert.Empty((await Get(a,child)).GetProperty("stages").EnumerateArray());
  var edit=new{revision=0,problem="通分步骤遗漏",evidence="课堂练习出现通分遗漏",adjustment="下次课用分步练习并复查",result="",actionState="planned",nextReviewOn="2026-11-05"};await Put(author,Api+"/stages/"+sid,edit);
  Assert.Equal(HttpStatusCode.Forbidden,(await author.PostAsJsonAsync(Api+"/stages/"+sid+"/publish",new{revision=1,confirmed=true})).StatusCode);
  await Factory.Post(reviewer,Api+"/stages/"+sid+"/publish",new{revision=1,confirmed=true});Assert.Single((await Get(a,child)).GetProperty("stages").EnumerateArray());
  await Put(author,Api+"/stages/"+sid,new{revision=2,problem=edit.problem,evidence=edit.evidence,adjustment="补充复查安排",result="",actionState="in_progress",nextReviewOn="2026-11-06"});Assert.Empty((await Get(a,child)).GetProperty("stages").EnumerateArray());
  await Factory.Post(reviewer,Api+"/stages/"+sid+"/publish",new{revision=3,confirmed=true});await Factory.Post(reviewer,Api+"/stages/"+sid+"/withdraw",new{revision=4});Assert.Empty((await Get(a,child)).GetProperty("stages").EnumerateArray());Assert.Equal(before,await OriginalBusinessSnapshot(f));
 }
 [Fact]
 public async Task Parent_practice_is_reviewed_and_binding_scoped()
 {
  using var factory=new ParentFactory(practice:true);
  var fixture=await Seed(factory);
  using var author=await factory.Login("beta-author");
  using var reviewer=await factory.Login("beta-reviewer");
  using var parent=await ParentLogin(factory);
  using var stranger=await ParentLogin(factory,"parent-b");
  Guid courseId;
  using(var scope=factory.Services.CreateScope()){
   var db=scope.ServiceProvider.GetRequiredService<Data>();
   var session=await db.ClassSessions.SingleAsync(s=>s.ClassId==fixture.ClassId&&db.ClassSessionMarks.Any(m=>m.ClassSessionId==s.Id&&m.StudentId==fixture.StudentId&&m.Reviewed));
   courseId=session.CourseId;
   (await db.Classes.SingleAsync(c=>c.Id==fixture.ClassId)).Name="初一验收班";
   (await db.Courses.SingleAsync(c=>c.Id==courseId)).Name="初中数学";
   await db.SaveChangesAsync();
  }
  var (_,path)=await ApprovedCollection(author,reviewer,fixture.ClassId);
  var binding=await BindParent(parent,reviewer,fixture.StudentId,path);
  var child="/academic-pilot/parent/children/"+binding.GetProperty("id").GetGuid();
  var stage=await Factory.Post(author,Api+"/students/"+fixture.StudentId+"/stages",new{title="通分强化",from="2026-10-01",to="2026-11-02"});
  var stagePath=Api+"/stages/"+stage.GetProperty("id").GetGuid();
  var draft=await Factory.Post(author,stagePath+"/practice",new{revision=0,courseId});
  var draftQuestions=draft.GetProperty("practice").EnumerateArray().ToArray();
  Assert.Equal(15,draftQuestions.Length);
  Assert.Equal(Enumerable.Range(2,15).Select(n=>$"practice-fixture-{n}"),draftQuestions.Select(q=>q.GetProperty("id").GetString()));
  Assert.Empty((await Get(parent,child)).GetProperty("stages").EnumerateArray());
  await Put(author,stagePath,new{revision=1,problem="通分遗漏",evidence="课堂错题",adjustment="完成强化练习",result="",actionState="planned",nextReviewOn="2026-11-05"});
  await Factory.Post(reviewer,stagePath+"/publish",new{revision=2,confirmed=true});
  var visible=Assert.Single((await Get(parent,child)).GetProperty("stages").EnumerateArray());
  var publishedQuestions=visible.GetProperty("practice").EnumerateArray().ToArray();
  Assert.Equal(draftQuestions.Select(q=>q.GetRawText()),publishedQuestions.Select(q=>q.GetRawText()));
  Assert.Equal("35/12",publishedQuestions[^1].GetProperty("answer").GetString());
  Assert.Equal(HttpStatusCode.Forbidden,(await stranger.GetAsync(child)).StatusCode);
  await Factory.Post(reviewer,stagePath+"/withdraw",new{revision=3});
  Assert.Empty((await Get(parent,child)).GetProperty("stages").EnumerateArray());
 }
 [Fact] public async Task Bound_homework_survives_device_relogin_counts_one_student_and_revocation_blocks_upload(){
  using var f=new ParentFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var a=await ParentLogin(f);var(taskId,path)=await ApprovedCollection(author,reviewer,x.ClassId);var binding=await BindParent(a,reviewer,x.StudentId,path);var bindingId=binding.GetProperty("id").GetGuid();
  var opened=await Factory.Post(a,"/academic-pilot/parent/tasks/"+taskId+"/open",new{bindingId});var receipt=opened.GetProperty("receipt").GetString()!;var submit="/academic-pilot/collect/"+opened.GetProperty("ticket").GetString()+"/submit";
  using(var photo=Photo(receipt,0,"one")){photo.Add(new StringContent(bindingId.ToString()),"bindingId");Assert.Equal(HttpStatusCode.OK,(await a.PostAsync(submit,photo)).StatusCode);}
  using var secondDevice=await ParentLogin(f);var reopened=await Factory.Post(secondDevice,"/academic-pilot/parent/tasks/"+taskId+"/open",new{bindingId});var receipt2=reopened.GetProperty("receipt").GetString()!;
  var mine=await Factory.Post(secondDevice,"/academic-pilot/collect/"+opened.GetProperty("ticket").GetString()+"/mine",new{receipt=receipt2});Assert.Equal(1,mine.GetProperty("photoCount").GetInt32());
  using(var photo=Photo(receipt2,1,"two")){photo.Add(new StringContent(bindingId.ToString()),"bindingId");Assert.Equal(HttpStatusCode.OK,(await secondDevice.PostAsync(submit,photo)).StatusCode);}
  var stats=(await Get(author,Api+"/collections/"+taskId)).GetProperty("statistics");Assert.Equal(1,stats.GetProperty("received").GetInt32());Assert.Equal(3,stats.GetProperty("missing").GetInt32());Assert.Equal(0,stats.GetProperty("pendingIdentity").GetInt32());
  await Factory.Post(reviewer,Api+"/parent-bindings/"+bindingId+"/revoke",new{revision=1});Assert.Equal(HttpStatusCode.Forbidden,(await a.GetAsync("/academic-pilot/parent/children/"+bindingId)).StatusCode);
  using(var photo=Photo(receipt,2,"three")){photo.Add(new StringContent(bindingId.ToString()),"bindingId");Assert.Equal(HttpStatusCode.Forbidden,(await a.PostAsync(submit,photo)).StatusCode);}
 }
 [Fact] public async Task Grading_is_an_unconfigured_extension_and_AI_results_require_review_of_current_photos(){
  foreach(var available in new[]{false,true}){using var f=new ParentFactory(available);var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var parent=f.CreateClient();var(id,path)=await ApprovedCollection(author,reviewer,x.ClassId);var receipt=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString()!;using(var photo=Photo(receipt,0,"one"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",photo)).StatusCode);
   var sub=(await Get(author,Api+"/collections/"+id)).GetProperty("submissions")[0].GetProperty("id").GetGuid();var job=await Factory.Post(author,Api+"/collection-submissions/"+sub+"/grading",new{referenceAnswer="1. 1/2+1/3=5/6；通分分步评分"});Assert.Equal(available?"needs_review":"awaiting_provider",job.GetProperty("state").GetString());Assert.Equal(available,(await Get(author,Api+"/grading-contract")).GetProperty("available").GetBoolean());
   if(available){using(var photo=Photo(receipt,1,"two"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",photo)).StatusCode);Assert.Equal(HttpStatusCode.Conflict,(await reviewer.PostAsJsonAsync(Api+"/grading-jobs/"+job.GetProperty("id").GetGuid()+"/review",new{revision=job.GetProperty("revision").GetInt32(),confirmed=true})).StatusCode);}
  }
 }
 [Fact] public async Task Submission_statistics_use_first_receipt_time_and_deduplicate_student_identity(){
  using var f=new ParentFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");var(id,path)=await ApprovedCollection(author,reviewer,x.ClassId);
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();var task=await db.AcademicCollections.SingleAsync(z=>z.Id==id);var end=DateTimeOffset.Parse(task.DueOn+"T23:59:59+08:00");db.AcademicSubmissions.AddRange(new(){CollectionId=id,Name="甲",StudentId=x.StudentId,FirstSubmittedAt=end.AddMinutes(-1),SubmittedAt=end.AddDays(1)},new(){CollectionId=id,Name="甲补交",StudentId=x.StudentId,FirstSubmittedAt=end.AddDays(1)},new(){CollectionId=id,Name="乙",StudentId=x.SecondStudentId,FirstSubmittedAt=end.AddMinutes(1)},new(){CollectionId=id,Name="未核对"});await db.SaveChangesAsync();}
  var s=(await Get(author,Api+"/collections/"+id)).GetProperty("statistics");Assert.Equal(2,s.GetProperty("received").GetInt32());Assert.Equal(2,s.GetProperty("missing").GetInt32());Assert.Equal(1,s.GetProperty("late").GetInt32());Assert.Equal(1,s.GetProperty("pendingIdentity").GetInt32());Assert.Equal(4,s.GetProperty("submissionCount").GetInt32());
 }
 [Fact] public async Task Parent_login_without_configuration_fails_closed(){using var f=new BetaFactory();await Seed(f);using var c=f.CreateClient();Assert.Equal(HttpStatusCode.ServiceUnavailable,(await c.PostAsJsonAsync("/academic-pilot/parent/wechat",new{code="untrusted-openid"})).StatusCode);using var disabled=new BetaFactory(false);using var d=disabled.CreateClient();Assert.Equal(HttpStatusCode.NotFound,(await d.GetAsync("/academic-pilot/parent/status")).StatusCode);}
}
