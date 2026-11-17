using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Siyue;
using Xunit;
public partial class AcademicBetaTests {
 static async Task<(Guid Collection,string Path,string Receipt,Guid Job)> MobileDraft(WorkflowFactory f,HttpClient author,HttpClient reviewer,Guid classId,bool uncertain=false){
  var(id,path)=await ApprovedCollection(author,reviewer,classId);await Put(reviewer,Api+"/collections/"+id+"/learning-policy",new{revision=0,referenceAnswer="1.3x+5=20，x=5。2.2+2=4。",subject="数学",stage="初中",enabled=true});using var p=f.CreateClient();var receipt=(await Factory.Post(p,path+"/begin",new{})).GetProperty("receipt").GetString()!;using(var photo=Photo(receipt,0,"first"))Assert.Equal(HttpStatusCode.OK,(await p.PostAsync(path+"/submit",photo)).StatusCode);f.Grader.Uncertain=uncertain;await f.Tick();var j=(await Get(author,Api+"/collections/"+id+"/learning")).GetProperty("submissions")[0].GetProperty("job");return(id,path,receipt,j.GetProperty("id").GetGuid());
 }
 static async Task<string[]> CheckSheet(HttpClient c,Guid job,JsonElement sheet){var list=new List<string>();foreach(var label in sheet.GetProperty("questionOrder").EnumerateArray()){var r=await Factory.Post(c,Api+"/grading-jobs/"+job+"/question-checks",new{revision=sheet.GetProperty("revision").GetInt32(),reviewToken=sheet.GetProperty("reviewToken").GetString(),label=label.GetString(),confirmed=true});list.Add(r.GetProperty("receipt").GetString()!);}return list.ToArray();}
 [Fact] public async Task Mobile_review_requires_all_unique_question_checks_and_is_retry_safe(){
  using var f=new WorkflowFactory();var x=await Seed(f);using var a=await f.Login("beta-author");using var r=await f.Login("beta-reviewer");var before=await OriginalBusinessSnapshot(f);var d=await MobileDraft(f,a,r,x.ClassId);var route=Api+"/grading-jobs/"+d.Job;var sheet=await Get(r,route+"/review-sheet");var rev=sheet.GetProperty("revision").GetInt32();var ticket=sheet.GetProperty("reviewToken").GetString();
  Assert.Equal(HttpStatusCode.Forbidden,(await a.GetAsync(route+"/review-sheet")).StatusCode);
  Assert.Equal(HttpStatusCode.Conflict,(await r.PostAsJsonAsync(route+"/review-checked",new{revision=rev,reviewToken=ticket,receipts=Array.Empty<string>(),confirmed=true})).StatusCode);
  var receipts=await CheckSheet(r,d.Job,sheet);
  Assert.Equal(HttpStatusCode.Conflict,(await r.PostAsJsonAsync(route+"/review-checked",new{revision=rev,reviewToken=ticket,receipts=new[]{receipts[0],receipts[0]},confirmed=true})).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await r.PostAsJsonAsync(route+"/review-checked",new{revision=rev,reviewToken=ticket,receipts=new[]{receipts[0],"forged"},confirmed=true})).StatusCode);
  var fresh=await Get(r,route+"/review-sheet");Assert.Equal(HttpStatusCode.Conflict,(await r.PostAsJsonAsync(route+"/review-checked",new{revision=rev,reviewToken=fresh.GetProperty("reviewToken").GetString(),receipts,confirmed=true})).StatusCode);
  var body=new{revision=rev,reviewToken=ticket,receipts,confirmed=true};var approved=await Factory.Post(r,route+"/review-checked",body);Assert.False(approved.GetProperty("alreadyReviewed").GetBoolean());var retry=await Factory.Post(r,route+"/review-checked",body);Assert.True(retry.GetProperty("alreadyReviewed").GetBoolean());Assert.Equal(approved.GetProperty("revision").GetInt32(),retry.GetProperty("revision").GetInt32());Assert.Equal(before,await OriginalBusinessSnapshot(f));
 }
 [Fact] public async Task Mobile_review_prioritizes_uncertainty_and_invalidates_checks_after_edit_or_supplement(){
  using var f=new WorkflowFactory();var x=await Seed(f);using var a=await f.Login("beta-author");using var r=await f.Login("beta-reviewer");using var p=f.CreateClient();var d=await MobileDraft(f,a,r,x.ClassId,true);var route=Api+"/grading-jobs/"+d.Job;var sheet=await Get(r,route+"/review-sheet");var rev=sheet.GetProperty("revision").GetInt32();var ticket=sheet.GetProperty("reviewToken").GetString();Assert.Equal("1",sheet.GetProperty("questionOrder")[0].GetString());
  Assert.Equal(HttpStatusCode.Conflict,(await r.PostAsJsonAsync(route+"/question-checks",new{revision=rev,reviewToken=ticket,label="1",confirmed=true})).StatusCode);
  var corrected=sheet.GetProperty("result").Deserialize<HomeworkGradeResult>(Json.Options)!;corrected=corrected with{Questions=corrected.Questions.Select(q=>q with{Verdict="correct"}).ToArray()};await Put(r,route,new{revision=rev,result=corrected});Assert.Equal(HttpStatusCode.Conflict,(await r.PostAsJsonAsync(route+"/question-checks",new{revision=rev,reviewToken=ticket,label="2",confirmed=true})).StatusCode);
  sheet=await Get(r,route+"/review-sheet");var receipts=await CheckSheet(r,d.Job,sheet);using(var photo=Photo(d.Receipt,1,"supplement"))Assert.Equal(HttpStatusCode.OK,(await p.PostAsync(d.Path+"/submit",photo)).StatusCode);
  Assert.Equal(HttpStatusCode.Conflict,(await r.PostAsJsonAsync(route+"/review-checked",new{revision=sheet.GetProperty("revision").GetInt32(),reviewToken=sheet.GetProperty("reviewToken").GetString(),receipts,confirmed=true})).StatusCode);
  Assert.Equal(HttpStatusCode.Unauthorized,(await p.GetAsync(route+"/review-sheet")).StatusCode);using var outsider=await f.Login("beta-outsider");Assert.Equal(HttpStatusCode.NotFound,(await outsider.GetAsync(route+"/review-sheet")).StatusCode);
 }
}
