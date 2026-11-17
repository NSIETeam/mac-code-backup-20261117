using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public partial class AcademicBetaTests {
 class WorkflowFactory:BetaFactory {
  public readonly WorkflowGrader Grader=new();
  protected override void ConfigureWebHost(IWebHostBuilder b){base.ConfigureWebHost(b);b.ConfigureServices(s=>s.AddSingleton<IAcademicHomeworkGrader>(Grader));}
  public async Task Tick(){using var s=Services.CreateScope();await s.ServiceProvider.GetRequiredService<HomeworkQueueProcessor>().Tick();}
 }
 class WorkflowGrader:IAcademicHomeworkGrader {
  public bool Available=>true; public int Calls;public bool Uncertain;public HomeworkGradeResult? Next;
  public Task<HomeworkGradeResult> Grade(HomeworkGradeInput input,CancellationToken ct){Calls++;return Task.FromResult(Next??new HomeworkGradeResult("test","fixture",[new("1","x=2",Uncertain?"uncertain":"incorrect",0,1,.95m,"移项时符号错误",["一元一次方程"],"解一元一次方程：3x+5=20，求x的值。"),new("2","4","correct",1,1,.99m,"计算正确",["加法"],"计算2+2。")],[]));}
 }
 [Fact] public async Task Automatic_homework_to_reviewed_errors_and_training_is_isolated_and_stale_safe(){
  using var f=new WorkflowFactory();var x=await Seed(f);using var teacher=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var parent=f.CreateClient();var before=await OriginalBusinessSnapshot(f);
  var(id,path)=await ApprovedCollection(teacher,reviewer,x.ClassId);var policy=new{revision=0,referenceAnswer="1. 解方程3x+5=20，x=5。2. 2+2=4。",subject="数学",stage="初中",enabled=true};
  Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PutAsJsonAsync(Api+"/collections/"+id+"/learning-policy",policy)).StatusCode);
  await Put(reviewer,Api+"/collections/"+id+"/learning-policy",policy);
  var receipt=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString()!;
  using(var photo=Photo(receipt,0,"first"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",photo)).StatusCode);
  var read=Api+"/collections/"+id+"/learning";var learning=await Get(teacher,read);var row=learning.GetProperty("submissions")[0];Assert.Equal("queued",row.GetProperty("job").GetProperty("state").GetString());
  await f.Tick();await f.Tick();Assert.Equal(1,f.Grader.Calls);
  learning=await Get(teacher,read);row=learning.GetProperty("submissions")[0];var job=row.GetProperty("job");var jobId=job.GetProperty("id").GetGuid();Assert.Equal("needs_review",job.GetProperty("state").GetString());Assert.Equal(0,learning.GetProperty("statistics").GetProperty("wrongQuestions").GetInt32());
  Assert.Equal(HttpStatusCode.Conflict,(await teacher.PostAsJsonAsync(Api+"/grading-jobs/"+jobId+"/training",new{})).StatusCode);
  var approval=new{revision=job.GetProperty("revision").GetInt32(),confirmed=true};Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync(Api+"/grading-jobs/"+jobId+"/review",approval)).StatusCode);
  await Factory.Post(reviewer,Api+"/grading-jobs/"+jobId+"/review",approval);
  Assert.Equal(0,(await Get(teacher,read)).GetProperty("statistics").GetProperty("wrongQuestions").GetInt32());
  await Put(teacher,Api+"/collection-submissions/"+row.GetProperty("id").GetGuid()+"/student",new{revision=1,studentId=x.StudentId});
  learning=await Get(teacher,read);Assert.Equal(1,learning.GetProperty("statistics").GetProperty("wrongQuestions").GetInt32());
  var plan=await Factory.Post(teacher,Api+"/grading-jobs/"+jobId+"/training",new{perError=2,maxQuestions=4});Assert.Equal("draft",plan.GetProperty("state").GetString());Assert.NotEmpty(plan.GetProperty("body").GetProperty("exercises").EnumerateArray());
  var again=await Factory.Post(teacher,Api+"/grading-jobs/"+jobId+"/training",new{perError=2,maxQuestions=4});Assert.Equal(plan.GetProperty("id").GetGuid(),again.GetProperty("id").GetGuid());
  var trainingId=plan.GetProperty("id").GetGuid();Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync(Api+"/homework-trainings/"+trainingId+"/review",new{revision=0,confirmed=true})).StatusCode);
  await Factory.Post(reviewer,Api+"/homework-trainings/"+trainingId+"/review",new{revision=0,confirmed=true});
  using(var photo=Photo(receipt,2,"supplement"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",photo)).StatusCode);
  learning=await Get(teacher,read);Assert.Equal(0,learning.GetProperty("statistics").GetProperty("wrongQuestions").GetInt32());
  Assert.Equal(HttpStatusCode.Conflict,(await teacher.PostAsJsonAsync(Api+"/grading-jobs/"+jobId+"/training",new{})).StatusCode);
  await f.Tick();Assert.Equal(2,f.Grader.Calls);Assert.Equal(before,await OriginalBusinessSnapshot(f));
  using var outsider=await f.Login("beta-outsider");Assert.Equal(HttpStatusCode.NotFound,(await outsider.GetAsync(read)).StatusCode);Assert.Equal(HttpStatusCode.Unauthorized,(await parent.GetAsync(read)).StatusCode);
 }
 [Fact] public async Task Uncertain_grading_cannot_be_reviewed_and_edits_revoke_the_review(){
  using var f=new WorkflowFactory();f.Grader.Uncertain=true;var x=await Seed(f);using var teacher=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var parent=f.CreateClient();var(id,path)=await ApprovedCollection(teacher,reviewer,x.ClassId);
  await Put(reviewer,Api+"/collections/"+id+"/learning-policy",new{revision=0,referenceAnswer="解方程，x=5",subject="数学",stage="初中",enabled=true});var receipt=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString()!;using(var photo=Photo(receipt,0,"first"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",photo)).StatusCode);await f.Tick();
  var row=(await Get(teacher,Api+"/collections/"+id+"/learning")).GetProperty("submissions")[0];var job=row.GetProperty("job");var jobId=job.GetProperty("id").GetGuid();Assert.Equal(HttpStatusCode.Conflict,(await reviewer.PostAsJsonAsync(Api+"/grading-jobs/"+jobId+"/review",new{revision=2,confirmed=true})).StatusCode);
  var result=job.GetProperty("result").Deserialize<HomeworkGradeResult>(Json.Options)!;result=result with{Questions=result.Questions.Select(q=>q with{Verdict="incorrect"}).ToArray()};await Put(reviewer,Api+"/grading-jobs/"+jobId,new{revision=2,result});await Factory.Post(reviewer,Api+"/grading-jobs/"+jobId+"/review",new{revision=3,confirmed=true});
  await Put(reviewer,Api+"/grading-jobs/"+jobId,new{revision=4,result});Assert.Equal("needs_review",(await Get(teacher,Api+"/collections/"+id+"/learning")).GetProperty("submissions")[0].GetProperty("job").GetProperty("state").GetString());
 }
}
