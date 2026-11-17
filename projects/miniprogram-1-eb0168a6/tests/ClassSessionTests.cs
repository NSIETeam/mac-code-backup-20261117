using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Xml.Linq;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Siyue;
using UglyToad.PdfPig;
using Xunit;

public class ClassSessionTests {
 [Fact] public async Task SessionMarksAreDurableScopedVersionedAndExportSelectionIsImmutable(){
  await using var f=new Factory();using var admin=await f.Login("session-admin",true);using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
  var campus=new Campus{Name="校区"};var course=new Course{Name="数学"};var cl=new SchoolClass{Name="课次验收班",CampusId=campus.Id};var student=new Student{Name="验收学生",Code="01",ClassId=cl.Id};var otherStudent=new Student{Name="另一学生",Code="02",ClassId=cl.Id};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id,Sequence=1,Title="方程"};var h=new Handout{LectureId=lecture.Id,State="published",NormalizedKey="test/source.docx"};
  var w=WordDocuments.W;var q=new Question{HandoutId=h.Id,Label="例1",Order=1,Confirmed=true,BlocksJson=Json.Write(new[]{WordDocuments.Block(new XElement(w+"p",new XElement(w+"r",new XElement(w+"t","例1 解方程 x + 3 = 5。"))))})};var q2=new Question{HandoutId=h.Id,Label="练1",Order=2,Confirmed=true,BlocksJson=q.BlocksJson};db.AddRange(campus,course,cl,student,otherStudent,lecture,h,q,q2);await db.SaveChangesAsync();
  var input=new{classId=cl.Id,handoutId=h.Id,lessonDate="2026-10-27",requestKey="lesson-a"};var first=await Factory.Post(admin,"/api/class-sessions",input);var id=first.GetProperty("id").GetGuid();Assert.Equal(id,(await Factory.Post(admin,"/api/class-sessions",input)).GetProperty("id").GetGuid());
  var path=$"/api/class-sessions/{id}/students/{student.Id}";
  var save=new{revision=0,questionIds=new[]{q.Id,q2.Id},notes=new Dictionary<Guid,string>{{q.Id,"漏掉移项变号"}}};
  var response=await admin.PutAsJsonAsync(path,save);Assert.Equal(HttpStatusCode.OK,response.StatusCode);var immediate=await response.Content.ReadFromJsonAsync<JsonElement>();Assert.Equal(1,immediate.GetProperty("revision").GetInt32());Assert.True(immediate.GetProperty("reviewed").GetBoolean());Assert.Equal(2,immediate.GetProperty("questionIds").GetArrayLength());
  Assert.Equal(HttpStatusCode.OK,(await admin.PutAsJsonAsync(path,save)).StatusCode);
  Assert.Equal(HttpStatusCode.Conflict,(await admin.PutAsJsonAsync(path,new{revision=0,questionIds=Array.Empty<Guid>()})).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await admin.PutAsJsonAsync(path,new{revision=1,questionIds=new[]{Guid.NewGuid()}})).StatusCode);
  var saved=await admin.GetFromJsonAsync<JsonElement>($"/api/class-sessions/{id}");var marks=saved.GetProperty("marks").EnumerateArray().Single(m=>m.GetProperty("studentId").GetGuid()==student.Id);Assert.Equal(2,marks.GetProperty("questionIds").GetArrayLength());Assert.Equal("漏掉移项变号",marks.GetProperty("notes").GetProperty(q.Id.ToString()).GetString());
  var another=await Factory.Post(admin,"/api/class-sessions",new{classId=cl.Id,handoutId=h.Id,lessonDate="2026-11-03",requestKey="lesson-b"});var second=await admin.GetFromJsonAsync<JsonElement>("/api/class-sessions/"+another.GetProperty("id").GetGuid());Assert.All(second.GetProperty("marks").EnumerateArray(),m=>Assert.Empty(m.GetProperty("questionIds").EnumerateArray()));
  using var stranger=await f.Login("session-stranger");var staff=await Factory.Post(admin,"/api/admin/catalog/staff",new{name="其他教师"});var invite=await Factory.Post(admin,$"/api/admin/staff/{staff.GetProperty("id").GetGuid()}/invite",new{});await Factory.Post(stranger,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});
  Assert.Equal(HttpStatusCode.Forbidden,(await stranger.GetAsync($"/api/class-sessions/{id}")).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await stranger.PutAsJsonAsync(path,save)).StatusCode);Assert.Empty((await stranger.GetFromJsonAsync<JsonElement>("/api/class-sessions")).EnumerateArray());
  var exportBody=new{studentIds=new[]{student.Id},questionIds=new[]{q.Id},layout="analysis",requestKey="export-a"};var export=await Factory.Post(admin,$"/api/class-sessions/{id}/exports",exportBody);var docId=export.GetProperty("documents")[0].GetProperty("id").GetGuid();Assert.Equal(docId,(await Factory.Post(admin,$"/api/class-sessions/{id}/exports",exportBody)).GetProperty("documents")[0].GetProperty("id").GetGuid());
  // A cached mini-program Page key may recur for another selection. Keep each
  // immutable batch distinct, and retry each selection without extra work items.
  var changedBody=new{studentIds=new[]{student.Id},layout="compilation",requestKey="export-a"};
  var changed=await Factory.Post(admin,$"/api/class-sessions/{id}/exports",changedBody);
  Assert.NotEqual(export.GetProperty("batchId").GetGuid(),changed.GetProperty("batchId").GetGuid());
  Assert.Equal(changed.GetProperty("batchId").GetGuid(),(await Factory.Post(admin,$"/api/class-sessions/{id}/exports",changedBody)).GetProperty("batchId").GetGuid());
  await admin.PutAsJsonAsync($"/api/class-sessions/{id}/students/{otherStudent.Id}",new{revision=0,questionIds=new[]{q2.Id}});
  var otherBody=new{studentIds=new[]{otherStudent.Id},questionIds=new[]{q2.Id},layout="analysis",requestKey="export-a"};
  var other=await Factory.Post(admin,$"/api/class-sessions/{id}/exports",otherBody);
  Assert.NotEqual(docId,other.GetProperty("documents")[0].GetProperty("id").GetGuid());
  var retries=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>Factory.Post(admin,$"/api/class-sessions/{id}/exports",otherBody)));
  Assert.All(retries,r=>Assert.Equal(other.GetProperty("batchId").GetGuid(),r.GetProperty("batchId").GetGuid()));
  // Already-created pre-fix batches must still be returned, not regenerated.
  var legacyId=new Guid(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"session-export:{(await db.Documents.AsNoTracking().SingleAsync(x=>x.Id==docId)).StaffId}:{id}:export-a"))[..16]);
  await db.Documents.Where(x=>x.Id==docId).ExecuteUpdateAsync(x=>x.SetProperty(d=>d.BatchId,legacyId));
  Assert.Equal(docId,(await Factory.Post(admin,$"/api/class-sessions/{id}/exports",exportBody)).GetProperty("documents")[0].GetProperty("id").GetGuid());
  Assert.Equal(3,await db.Work.CountAsync(x=>x.Kind=="export"));
  Assert.Equal(HttpStatusCode.Forbidden,(await stranger.PostAsJsonAsync($"/api/class-sessions/{id}/exports",otherBody)).StatusCode);
  var pure=await Factory.Post(admin,$"/api/class-sessions/{id}/exports",new{studentIds=new[]{student.Id},questionIds=new[]{q.Id},layout="compilation",requestKey="export-b"});
  db.ChangeTracker.Clear();var doc=await db.Documents.SingleAsync(x=>x.Id==docId);var item=Assert.Single(Json.Read<QuestionSnapshot[]>(doc.SnapshotJson));Assert.Equal("漏掉移项变号",item.Note);Assert.Equal(q.Id,item.Id);
  await admin.PutAsJsonAsync(path,new{revision=1,questionIds=Array.Empty<Guid>()});Assert.Single(Json.Read<QuestionSnapshot[]>(doc.SnapshotJson));Assert.True(await db.LearningEvents.AnyAsync(e=>e.StudentId==student.Id&&e.QuestionId==q.Id&&e.State=="wrong"));
  var source=Path.Combine(f.Root,"source.docx");using(var pack=WordprocessingDocument.Create(source,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)){pack.AddMainDocumentPart().Document=new Document(new Body(new Paragraph(new Run(new Text("源讲义")))));}
  await using(var inputFile=File.OpenRead(source))await scope.ServiceProvider.GetRequiredService<IObjects>().Put(h.NormalizedKey,inputFile,WordDocuments.DocxType);
  var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();var output=Path.GetFullPath(".local/acceptance/classroom-v11");Directory.CreateDirectory(output);
  foreach(var exportId in new[]{docId,pure.GetProperty("documents")[0].GetProperty("id").GetGuid()}){
   var d=await db.Documents.SingleAsync(x=>x.Id==exportId);var dir=Path.Combine(f.Root,d.Layout);Directory.CreateDirectory(dir);var (docx,pdf)=await word.BookletPdf(d,dir,default);File.Copy(pdf,Path.Combine(output,d.Layout+".pdf"),true);File.Copy(docx,Path.Combine(output,d.Layout+".docx"),true);
   using var rendered=PdfDocument.Open(pdf);Assert.Equal(1,rendered.NumberOfPages);var text=rendered.GetPage(1).Text;Assert.Contains("验收学生个性化错题",text);Assert.Contains("第1讲",text);Assert.Contains("2026.10.27",text);Assert.Contains("例1",text);
   if(d.Layout=="analysis"){foreach(var label in new[]{"一级错误（低级）","二级错误（必须掌握）","三级错误","正确思路：","卡点（哪一步不会）：","套路总结："})Assert.Contains(label,text);Assert.Contains("漏掉移项变号",text);}else{Assert.DoesNotContain("一级错误",text);Assert.DoesNotContain("漏掉移项变号",text);Assert.DoesNotContain("套路总结",text);}
  }
 }
 [Fact] public async Task DraftInterferenceRemovalIsScopedAndPublishedHandoutsStayLocked(){
  await using var f=new Factory();using var admin=await f.Login("review-admin",true);using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
  var campus=new Campus{Name="校区"};var course=new Course{Name="数学"};var cl=new SchoolClass{Name="校对班",CampusId=campus.Id};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id,Sequence=1,Title="讲义"};var handout=new Handout{LectureId=lecture.Id,State="draft"};
  var w=WordDocuments.W;ContentBlock Block(string text)=>WordDocuments.Block(new XElement(w+"p",new XElement(w+"r",new XElement(w+"t",text))));
  var question=new Question{HandoutId=handout.Id,Label="例1",BlocksJson=Json.Write(new[]{Block("装订线"),Block("例1 原题完整内容")})};db.AddRange(campus,course,cl,lecture,handout,question);await db.SaveChangesAsync();
  await Factory.Post(admin,$"/api/handouts/{handout.Id}/edit",new{revision=0,questionId=question.Id,action="remove-block",blockIndex=0});db.ChangeTracker.Clear();var saved=await db.Questions.SingleAsync(q=>q.Id==question.Id);Assert.Equal("例1 原题完整内容",Assert.Single(Json.Read<ContentBlock[]>(saved.BlocksJson)).Text);Assert.False(saved.Confirmed);
  var h=await db.Handouts.FindAsync(handout.Id);h!.State="published";await db.SaveChangesAsync();Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"/api/handouts/{handout.Id}/edit",new{revision=1,questionId=question.Id,action="remove-block",blockIndex=0})).StatusCode);
 }

}
