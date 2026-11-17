using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Siyue;
using Xunit;
public partial class AcademicBetaTests {
 [Fact] public async Task Online_test_requires_approval_hides_reference_and_accepts_text_with_optional_photos(){
  using var f=new WorkflowFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var parent=f.CreateClient();var before=await OriginalBusinessSnapshot(f);
  var rows=await Factory.Post(author,Api+"/collections",CollectionBody(x.ClassId,"online-test"));var id=rows[0].GetProperty("id").GetGuid();var config=new{questions=new[]{new{label="1",stem="解方程3x+5=20"},new{label="2",stem="2+2"}},referenceAnswer="SECRET-TEACHER-REFERENCE x=5，4",subject="数学",stage="初中"};
  await Put(author,Api+"/collections/"+id+"/online-test",config);Assert.Equal(JsonValueKind.Null,(await Get(author,Api+"/collections/"+id)).GetProperty("sharePath").ValueKind);
  await Factory.Post(reviewer,Api+"/collections/"+id+"/approve",new{revision=1,confirmed=true});var d=await Get(author,Api+"/collections/"+id);var path="/academic-pilot/collect/"+d.GetProperty("sharePath").GetString()!.Split("?t=")[1];
  var visible=await Get(parent,path);Assert.Equal(2,visible.GetProperty("questions").GetArrayLength());Assert.DoesNotContain("SECRET-TEACHER-REFERENCE",visible.ToString());
  Assert.Equal(HttpStatusCode.Conflict,(await author.PutAsJsonAsync(Api+"/collections/"+id+"/online-test",config)).StatusCode);
  var receipt=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString()!;
  var answer=new{receipt,revision=0,name="本班学生甲",requestKey="online-1",answers=new[]{new{label="1",answer="x=2"},new{label="2",answer="4"}}};
  await Factory.Post(parent,path+"/answers",answer);await Factory.Post(parent,path+"/answers",answer);await f.Tick();Assert.Equal(1,f.Grader.Calls);
  var mine=await Factory.Post(parent,path+"/mine",new{receipt});Assert.Equal(0,mine.GetProperty("photoCount").GetInt32());Assert.Equal(2,mine.GetProperty("answers").GetArrayLength());
  var read=Api+"/collections/"+id+"/learning";var sub=(await Get(author,read)).GetProperty("submissions")[0];Assert.Equal("needs_review",sub.GetProperty("job").GetProperty("state").GetString());
  using(var photo=Photo(receipt,1,"process-photo"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",photo)).StatusCode);await f.Tick();Assert.Equal(2,f.Grader.Calls);
  var second=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString()!;Assert.Equal(0,(await Factory.Post(parent,path+"/mine",new{receipt=second})).GetProperty("answers").GetArrayLength());
  using(var photo=Photo(second,0,"photo-only"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",photo)).StatusCode);
  var photoOnly=await Get(author,Api+"/collections/"+id);
  var photoSubmission=photoOnly.GetProperty("submissions").EnumerateArray().Single(s=>s.GetProperty("revision").GetInt32()==1);
  await Put(author,Api+"/collection-submissions/"+photoSubmission.GetProperty("id").GetGuid()+"/student",new{revision=1,studentId=x.StudentId});
  var incomplete=await Get(author,Api+"/collections/"+id);
  Assert.Equal(0,incomplete.GetProperty("statistics").GetProperty("received").GetInt32());
  Assert.False(incomplete.GetProperty("students").EnumerateArray().Single(s=>s.GetProperty("id").GetGuid()==x.StudentId).GetProperty("submitted").GetBoolean());
  await Factory.Post(parent,path+"/answers",new{receipt,revision=2,name="本班学生甲",requestKey="online-2",answers=new[]{new{label="1",answer="x=5"},new{label="2",answer="4"}}});await f.Tick();Assert.Equal(3,f.Grader.Calls);
  Assert.Equal(HttpStatusCode.Conflict,(await parent.PostAsJsonAsync(path+"/answers",answer)).StatusCode);
  await Factory.Post(reviewer,Api+"/collections/"+id+"/close",new{revision=2});Assert.Equal(HttpStatusCode.Gone,(await parent.PostAsJsonAsync(path+"/answers",answer)).StatusCode);Assert.Equal(before,await OriginalBusinessSnapshot(f));
 }
 [Fact] public async Task Online_test_single_choice_grades_without_a_model_and_keeps_review_required(){
  using var f=new WorkflowFactory();var x=await Seed(f);
  using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var parent=f.CreateClient();
  var rows=await Factory.Post(author,Api+"/collections",CollectionBody(x.ClassId,"choice-rules"));
  var id=rows[0].GetProperty("id").GetGuid();
  var path=Api+"/collections/"+id;
  var questions=new[]{new{label="1",stem="第一个选择题"},new{label="2",stem="第二个选择题"},new{label="3",stem="第三个选择题"}};
  var invalid=new{questions,referenceAnswer="选择题答案\n1: A",subject="数学",stage="初中"};
  Assert.Equal(HttpStatusCode.BadRequest,(await author.PutAsJsonAsync(path+"/online-test",invalid)).StatusCode);
  await Put(author,path+"/online-test",new{questions,referenceAnswer="选择题答案\n1: A\n2：B\n3: C",subject="数学",stage="初中"});
  await Factory.Post(reviewer,path+"/approve",new{revision=1,confirmed=true});
  var share=(await Get(author,path)).GetProperty("sharePath").GetString()!;
  var collect="/academic-pilot/collect/"+share.Split("?t=")[1];
  var receipt=(await Factory.Post(parent,collect+"/begin",new{})).GetProperty("receipt").GetString()!;
  var input=new{receipt,revision=0,name="本班学生甲",requestKey="choice-1",answers=new[]{new{label="1",answer="ａ"},new{label="2",answer="D"},new{label="3",answer=""}}};
  var response=await Factory.Post(parent,collect+"/answers",input);
  Assert.False(response.TryGetProperty("score",out _));
  await Factory.Post(parent,collect+"/answers",input);
  var sub=(await Get(author,path+"/learning")).GetProperty("submissions")[0];
  var job=sub.GetProperty("job");
  Assert.Equal("needs_review",job.GetProperty("state").GetString());
  var jobs=await Get(author,Api+"/collection-submissions/"+sub.GetProperty("id").GetGuid()+"/grading");
  var result=Json.Read<HomeworkGradeResult>(Assert.Single(jobs.EnumerateArray()).GetProperty("resultJson").GetString()!);
  Assert.Equal("single-choice",result.Model);
  Assert.Equal(new[]{"correct","incorrect","uncertain"},result.Questions.Select(q=>q.Verdict));
  Assert.Equal(new decimal?[]{1,0,null},result.Questions.Select(q=>q.Score));
  Assert.Equal(0,f.Grader.Calls);
 }
}
