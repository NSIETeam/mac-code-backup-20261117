using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.EntityFrameworkCore;
using Siyue;
using Xunit;

public partial class AcademicBetaTests {
 static object CollectionBody(Guid classId,string key="collection-test")=>new {classIds=new[]{classId},title="每周练习",instructions="完成练习后拍照提交",dueOn=DateTime.UtcNow.AddDays(2).ToString("yyyy-MM-dd"),requestKey=key};
 static async Task<(Guid id,string path)> ApprovedCollection(HttpClient author,HttpClient reviewer,Guid classId,string key="collection-test") {
  var rows=await Factory.Post(author,Api+"/collections",CollectionBody(classId,key));var id=rows[0].GetProperty("id").GetGuid();
  await Factory.Post(reviewer,$"{Api}/collections/{id}/approve",new{revision=0,confirmed=true});var detail=await Get(author,$"{Api}/collections/{id}");
  var share=detail.GetProperty("sharePath").GetString()!;return(id,"/academic-pilot/collect/"+share.Split("?t=")[1]);
 }
 static MultipartFormDataContent Photo(string receipt,int revision,string key,string name="本班学生甲",bool valid=true) {
  var form=new MultipartFormDataContent();foreach(var (k,v) in new[]{("receipt",receipt),("revision",revision.ToString()),("requestKey",key),("name",name)})form.Add(new StringContent(v),k);
  var bytes=valid?Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jYXkAAAAASUVORK5CYII="):new byte[30];
  form.Add(new ByteArrayContent(bytes),"photo","work.png");return form;
 }
 [Fact] public async Task Collection_gates_review_scope_and_deduplicates_drafts() {
  using var f=new BetaFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var outsider=await f.Login("beta-outsider");using var foreign=await f.Login("beta-foreign");
  Assert.Equal(HttpStatusCode.NotFound,(await outsider.GetAsync(Api+"/collections")).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await foreign.PostAsJsonAsync(Api+"/collections",CollectionBody(x.ClassId))).StatusCode);
  var rows=await Factory.Post(author,Api+"/collections",CollectionBody(x.ClassId));var id=rows[0].GetProperty("id").GetGuid();
  Assert.Equal(id,(await Factory.Post(author,Api+"/collections",CollectionBody(x.ClassId)))[0].GetProperty("id").GetGuid());
  Assert.Equal(JsonValueKind.Null,(await Get(author,$"{Api}/collections/{id}")).GetProperty("sharePath").ValueKind);
  Assert.Equal(HttpStatusCode.Forbidden,(await author.PostAsJsonAsync($"{Api}/collections/{id}/approve",new{revision=0,confirmed=true})).StatusCode);
  Assert.Equal(HttpStatusCode.Conflict,(await reviewer.PostAsJsonAsync($"{Api}/collections/{id}/approve",new{revision=0,confirmed=false})).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await foreign.GetAsync($"{Api}/collections/{id}")).StatusCode);
 }
 [Fact] public async Task Collection_parent_photos_retry_append_private_receipts_and_teacher_confirmation() {
  using var f=new BetaFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var parent=f.CreateClient();using var foreign=await f.Login("beta-foreign");
  var before=await OriginalBusinessSnapshot(f);var (id,path)=await ApprovedCollection(author,reviewer,x.ClassId);
  var metadata=await Get(parent,path);Assert.False(metadata.GetProperty("parentPortalAvailable").GetBoolean());Assert.False(metadata.TryGetProperty("students",out _));Assert.False(metadata.TryGetProperty("submissions",out _));
  var receipt=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString()!;
  using(var body=Photo(receipt,0,"one")){Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",body)).StatusCode);}
  using(var body=Photo(receipt,0,"one")){Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",body)).StatusCode);}
  using(var body=Photo(receipt,0,"two")){Assert.Equal(HttpStatusCode.Conflict,(await parent.PostAsync(path+"/submit",body)).StatusCode);}
  using(var body=Photo(receipt,1,"two")){Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",body)).StatusCode);}
  var mine=await Factory.Post(parent,path+"/mine",new{receipt});Assert.Equal(2,mine.GetProperty("photoCount").GetInt32());
  var otherReceipt=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString();var other=await Factory.Post(parent,path+"/mine",new{receipt=otherReceipt});Assert.Equal("empty",other.GetProperty("state").GetString());
  var detail=await Get(author,$"{Api}/collections/{id}");var sub=detail.GetProperty("submissions")[0];Assert.Equal(JsonValueKind.Null,sub.GetProperty("studentId").ValueKind);
  Assert.All(detail.GetProperty("students").EnumerateArray(),s=>Assert.False(s.GetProperty("submitted").GetBoolean()));
  var photoId=sub.GetProperty("photos")[0].GetGuid();Assert.Equal(HttpStatusCode.Unauthorized,(await parent.GetAsync($"{Api}/collection-photos/{photoId}")).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await foreign.GetAsync($"{Api}/collection-photos/{photoId}")).StatusCode);
  Assert.Equal(HttpStatusCode.OK,(await author.GetAsync($"{Api}/collection-photos/{photoId}")).StatusCode);
  await Put(author,$"{Api}/collection-submissions/{sub.GetProperty("id").GetGuid()}/student",new{revision=2,studentId=x.StudentId});
  detail=await Get(author,$"{Api}/collections/{id}");Assert.True(detail.GetProperty("students").EnumerateArray().Single(s=>s.GetProperty("id").GetGuid()==x.StudentId).GetProperty("submitted").GetBoolean());
  await Factory.Post(reviewer,$"{Api}/collections/{id}/close",new{revision=1});Assert.Equal(HttpStatusCode.Gone,(await parent.GetAsync(path)).StatusCode);
  using(var body=Photo(receipt,3,"three")){Assert.Equal(HttpStatusCode.Gone,(await parent.PostAsync(path+"/submit",body)).StatusCode);}
  Assert.Equal(before,await OriginalBusinessSnapshot(f));
 }
 [Fact] public async Task Collection_rejects_wrong_task_receipt_invalid_photo_name_change_and_revoked_author() {
  using var f=new BetaFactory();var x=await Seed(f);using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");using var parent=f.CreateClient();
  var (_,path)=await ApprovedCollection(author,reviewer,x.ClassId);var (_,other)=await ApprovedCollection(author,reviewer,x.ClassId,"other");var receipt=(await Factory.Post(parent,path+"/begin",new{})).GetProperty("receipt").GetString()!;
  Assert.Equal(HttpStatusCode.Forbidden,(await parent.PostAsJsonAsync(other+"/mine",new{receipt})).StatusCode);
  using(var body=Photo(receipt,0,"bad",valid:false))Assert.Equal(HttpStatusCode.BadRequest,(await parent.PostAsync(path+"/submit",body)).StatusCode);
  using(var body=Photo(receipt,0,"one"))Assert.Equal(HttpStatusCode.OK,(await parent.PostAsync(path+"/submit",body)).StatusCode);
  using(var body=Photo(receipt,1,"two","另一个名字"))Assert.Equal(HttpStatusCode.Conflict,(await parent.PostAsync(path+"/submit",body)).StatusCode);
  using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();await db.Assignments.Where(a=>a.StaffId==f.AuthorId).ExecuteDeleteAsync();Assert.Equal(HttpStatusCode.Gone,(await parent.GetAsync(path)).StatusCode);
 }
 [Fact] public async Task Collection_multi_class_creation_is_atomic_and_isolated() {
  using var f=new BetaFactory();var x=await Seed(f);using var author=await f.Login("beta-author");
  using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var foreignClass=await db.Students.Where(s=>s.Id==x.ForeignStudentId).Select(s=>s.ClassId).SingleAsync();
  var request=new{classIds=new[]{x.ClassId,foreignClass},title="通用作业",instructions="拍照",dueOn=DateTime.UtcNow.AddDays(1).ToString("yyyy-MM-dd"),requestKey="multi"};
  Assert.Equal(HttpStatusCode.Forbidden,(await author.PostAsJsonAsync(Api+"/collections",request)).StatusCode);Assert.Empty(await db.AcademicCollections.ToArrayAsync());
  db.Assignments.Add(new(){StaffId=f.AuthorId,ClassId=foreignClass,CourseId=x.CourseId});await db.SaveChangesAsync();
  var created=await Factory.Post(author,Api+"/collections",request);Assert.Equal(2,created.GetArrayLength());Assert.Equal(created[0].GetProperty("batchId").GetGuid(),created[1].GetProperty("batchId").GetGuid());Assert.NotEqual(created[0].GetProperty("id").GetGuid(),created[1].GetProperty("id").GetGuid());
 }
 [Fact] public async Task Collection_pdf_attachment_is_revisioned_and_requires_an_approved_ticket(){
  using var f=new BetaFactory();var x=await Seed(f);
  using var author=await f.Login("beta-author");using var reviewer=await f.Login("beta-reviewer");
  using var outsider=await f.Login("beta-foreign");using var parent=f.CreateClient();
  var draft=(await Factory.Post(author,Api+"/collections",CollectionBody(x.ClassId,"pdf-attachment")))[0];
  var path=Api+"/collections/"+draft.GetProperty("id").GetGuid();
  static MultipartFormDataContent Pdf(bool valid=true){
   var form=new MultipartFormDataContent();
   form.Add(new StringContent("0"),"revision");
   form.Add(new StringContent("fixture.pdf"),"name");
   form.Add(new ByteArrayContent(System.Text.Encoding.ASCII.GetBytes(valid?"%PDF-1.7 fixture":"fake payload")),"file","wx-tmp-file");
   return form;
  }
  using(var wrong=Pdf(false))Assert.Equal(HttpStatusCode.BadRequest,(await author.PostAsync(path+"/attachment",wrong)).StatusCode);
  using(var forbidden=Pdf())Assert.Equal(HttpStatusCode.Forbidden,(await outsider.PostAsync(path+"/attachment",forbidden)).StatusCode);
  using(var pdf=Pdf())Assert.Equal(HttpStatusCode.OK,(await author.PostAsync(path+"/attachment",pdf)).StatusCode);
  using(var retry=Pdf())Assert.Equal(HttpStatusCode.OK,(await author.PostAsync(path+"/attachment",retry)).StatusCode);
  var detail=await Get(author,path);
  Assert.Equal("fixture.pdf",detail.GetProperty("attachmentName").GetString());
  Assert.Equal(JsonValueKind.Null,detail.GetProperty("sharePath").ValueKind);
  await Factory.Post(reviewer,path+"/approve",new{revision=1,confirmed=true});
  var approved=await Get(author,path);
  var ticket=approved.GetProperty("sharePath").GetString()!.Split("?t=")[1];
  var collect="/academic-pilot/collect/"+ticket;
  Assert.Equal("fixture.pdf",(await Get(parent,collect)).GetProperty("attachmentName").GetString());
  Assert.Equal(HttpStatusCode.Unauthorized,(await parent.GetAsync(path+"/attachment")).StatusCode);
  using var file=await parent.GetAsync(collect+"/attachment");
  Assert.Equal(HttpStatusCode.OK,file.StatusCode);
  Assert.Equal("application/pdf",file.Content.Headers.ContentType!.MediaType);
  await Factory.Post(reviewer,path+"/close",new{revision=2});
  Assert.Equal(HttpStatusCode.Gone,(await parent.GetAsync(collect+"/attachment")).StatusCode);
 }
}
