using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public partial class AcademicBetaTests {
 static JsonElement[] TodoRows(JsonElement page)=>page.GetProperty("rows").EnumerateArray().ToArray();
 [Fact] public async Task Teacher_todos_follow_identity_review_training_and_staleness_without_business_writes(){
  using var f=new WorkflowFactory();var x=await Seed(f);using var a=await f.Login("beta-author");using var r=await f.Login("beta-reviewer");var before=await OriginalBusinessSnapshot(f);var d=await MobileDraft(f,a,r,x.ClassId,true);
  var rows=TodoRows(await Get(r,Api+"/teacher-todos"));Assert.Contains(rows,t=>t.GetProperty("kind").GetString()=="identity");Assert.DoesNotContain(rows,t=>t.GetProperty("kind").GetString()=="review");Assert.False(rows.Single(t=>t.GetProperty("kind").GetString()=="missing").GetProperty("canRemind").GetBoolean());
  Assert.Equal(HttpStatusCode.Conflict,(await r.GetAsync(Api+"/collections/"+d.Collection+"/reminder-preview")).StatusCode);
  var learning=await Get(r,Api+"/collections/"+d.Collection+"/learning");var sub=learning.GetProperty("submissions")[0];var sid=sub.GetProperty("id").GetGuid();await Put(r,Api+"/collection-submissions/"+sid+"/student",new{revision=1,studentId=x.StudentId});
  rows=TodoRows(await Get(r,Api+"/teacher-todos"));var review=rows.Single(t=>t.GetProperty("kind").GetString()=="review");Assert.Equal(1,review.GetProperty("uncertain").GetInt32());Assert.Contains("homework-batch-review",review.GetProperty("path").GetString());Assert.Equal(review,rows[0]);
  var teacherRows=TodoRows(await Get(a,Api+"/teacher-todos"));Assert.Contains("homework-review",teacherRows.Single(t=>t.GetProperty("kind").GetString()=="review").GetProperty("path").GetString());
  var job=sub.GetProperty("job");var result=job.GetProperty("result").Deserialize<HomeworkGradeResult>(Json.Options)!;result=result with{Questions=result.Questions.Select(q=>q.Label=="1"?q with{Verdict="incorrect"}:q).ToArray()};await Put(r,Api+"/grading-jobs/"+d.Job,new{revision=2,result});await Factory.Post(r,Api+"/grading-jobs/"+d.Job+"/review",new{revision=3,confirmed=true});
  rows=TodoRows(await Get(r,Api+"/teacher-todos"));Assert.DoesNotContain(rows,t=>t.GetProperty("kind").GetString()=="review");Assert.Contains(rows,t=>t.GetProperty("kind").GetString()=="practice"&&t.GetProperty("path").GetString()!.Contains(sid.ToString()));
  var plan=await Factory.Post(r,Api+"/grading-jobs/"+d.Job+"/training",new{perError=2,maxQuestions=4});rows=TodoRows(await Get(r,Api+"/teacher-todos"));Assert.Contains(rows,t=>t.GetProperty("kind").GetString()=="training");Assert.DoesNotContain(rows,t=>t.GetProperty("kind").GetString()=="practice");
  await Factory.Post(r,Api+"/homework-trainings/"+plan.GetProperty("id").GetGuid()+"/review",new{revision=0,confirmed=true});
  rows=TodoRows(await Get(r,Api+"/teacher-todos"));Assert.DoesNotContain(rows,t=>t.GetProperty("kind").GetString()=="training");Assert.Equal(2,rows.Count(t=>t.GetProperty("kind").GetString()=="missing"));
  using(var p=f.CreateClient()){using var photo=Photo(d.Receipt,2,"supplement");Assert.Equal(HttpStatusCode.OK,(await p.PostAsync(d.Path+"/submit",photo)).StatusCode);}
  rows=TodoRows(await Get(r,Api+"/teacher-todos"));Assert.DoesNotContain(rows,t=>t.GetProperty("kind").GetString() is "training" or "practice");Assert.Single(rows,t=>t.GetProperty("kind").GetString()=="missing");Assert.Equal(before,await OriginalBusinessSnapshot(f));
 }
 [Fact] public async Task Reminder_preview_rechecks_latest_receipts_deduplicates_students_and_never_sends(){
  using var f=new BetaFactory();var x=await Seed(f);using var a=await f.Login("beta-author");using var r=await f.Login("beta-reviewer");var(id,_)=await ApprovedCollection(a,r,x.ClassId);
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AcademicSubmissions.AddRange(new AcademicSubmission{CollectionId=id,StudentId=x.StudentId,Name="本班学生甲"},new AcademicSubmission{CollectionId=id,StudentId=x.StudentId,Name="本班学生甲"});await db.SaveChangesAsync();}
  var before=await OriginalBusinessSnapshot(f);int events;using(var scope=f.Services.CreateScope())events=await scope.ServiceProvider.GetRequiredService<Data>().AcademicOperationEvents.CountAsync();
  var preview=await Get(a,Api+"/collections/"+id+"/reminder-preview");Assert.Equal(3,preview.GetProperty("missing").GetArrayLength());Assert.Equal("draft",preview.GetProperty("state").GetString());Assert.True(preview.GetProperty("requiresAcademicReview").GetBoolean());Assert.Equal("none",preview.GetProperty("deliveryMode").GetString());Assert.DoesNotContain("本班学生",preview.GetProperty("body").GetString());
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(events,await db.AcademicOperationEvents.CountAsync());foreach(var sid in new[]{x.SecondStudentId,x.ThirdStudentId,x.UnreviewedStudentId})db.AcademicSubmissions.Add(new(){CollectionId=id,StudentId=sid,Name="已交学生"});await db.SaveChangesAsync();}
  Assert.Equal(HttpStatusCode.Conflict,(await a.GetAsync(Api+"/collections/"+id+"/reminder-preview")).StatusCode);Assert.DoesNotContain(TodoRows(await Get(a,Api+"/teacher-todos")),t=>t.GetProperty("kind").GetString()=="missing");
  await Factory.Post(r,Api+"/collections/"+id+"/close",new{revision=1});Assert.Empty(TodoRows(await Get(a,Api+"/teacher-todos")));Assert.Equal(before,await OriginalBusinessSnapshot(f));
 }
 [Fact] public async Task Teacher_todos_paginate_without_hiding_older_work_and_enforce_class_and_beta_scope(){
  using var f=new BetaFactory();var x=await Seed(f);using var a=await f.Login("beta-author");using var foreign=await f.Login("beta-foreign");using var outsider=await f.Login("beta-outsider");using var anonymous=f.CreateClient();
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();for(var i=0;i<23;i++)db.AcademicCollections.Add(new(){ClassId=x.ClassId,CreatedBy=f.AuthorId,Title="待确认"+i,DueOn="2026-11-04",RequestKey="todo-"+i});await db.SaveChangesAsync();}
  var p1=await Get(a,Api+"/teacher-todos?page=0");var p2=await Get(a,Api+"/teacher-todos?page=1");Assert.True(p1.GetProperty("hasMore").GetBoolean());Assert.False(p2.GetProperty("hasMore").GetBoolean());Assert.Equal(20,TodoRows(p1).Length);Assert.Equal(3,TodoRows(p2).Length);Assert.Equal(23,TodoRows(p1).Concat(TodoRows(p2)).Select(t=>t.GetProperty("id").GetString()).Distinct().Count());
  Assert.Empty(TodoRows(await Get(foreign,Api+"/teacher-todos")));Assert.Equal(HttpStatusCode.Forbidden,(await foreign.GetAsync(Api+"/teacher-todos?classId="+x.ClassId)).StatusCode);Assert.Equal(HttpStatusCode.NotFound,(await outsider.GetAsync(Api+"/teacher-todos")).StatusCode);Assert.Equal(HttpStatusCode.Unauthorized,(await anonymous.GetAsync(Api+"/teacher-todos")).StatusCode);Assert.Equal(HttpStatusCode.BadRequest,(await a.GetAsync(Api+"/teacher-todos?page=-1")).StatusCode);
 }
}
