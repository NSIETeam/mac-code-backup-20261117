using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public sealed class QuestionBankIntakeTests
{
    sealed class IntakeFactory(bool dedicated=false):Factory {
        protected override void ConfigureWebHost(IWebHostBuilder builder) {
            base.ConfigureWebHost(builder);
            var stub=Path.Combine(Root,"ocr-fixture");Directory.CreateDirectory(Root);
            File.WriteAllText(stub,"#!/bin/sh\nprintf '1. 如图，点到直线距离为多少？\\n【答案】5\\n【解析】代入距离公式。\\n' > \"$2.txt\"\n");
            if(!OperatingSystem.IsWindows())File.SetUnixFileMode(stub,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
            builder.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{
                ["QuestionBank:UploadsEnabled"]="true",["QuestionBank:UploadsDirectory"]=Path.Combine(Root,"community"),
                ["QuestionBank:Dedicated"]=dedicated.ToString(),["QuestionBank:IntakeWorkerEnabled"]="false",["Documents:Tesseract"]=stub,["Documents:OcrPython"]="/nonexistent/rapid-ocr-test-fallback",["Documents:ImagePython"]=Environment.GetEnvironmentVariable("SIYUE_IMAGE_PYTHON")??Environment.GetEnvironmentVariable("SIYUE_PYTHON")??"python3"
            }));
        }
    }
    static readonly byte[] Png=Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9h0AAAAASUVORK5CYII=");
    static async Task<HttpResponseMessage> Queue(HttpClient client,string name,byte[] bytes) {
        using var form=new MultipartFormDataContent();form.Add(new ByteArrayContent(bytes),"file",name);form.Add(new StringContent("数学"),"subject");form.Add(new StringContent("高中"),"stage");return await client.PostAsync("/api/question-bank/intakes",form);
    }
    static async Task<(HttpClient Client,Guid Id)> Teacher(Factory f,HttpClient admin,string name) {
        var staff=await Factory.Post(admin,"/api/admin/catalog/staff",new{name});var id=staff.GetProperty("id").GetGuid();var invite=await Factory.Post(admin,$"/api/admin/staff/{id}/invite",new{});var teacher=await f.Login(name);await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});return(teacher,id);
    }
    static async Task<Guid> Upload(HttpClient client,string name,byte[] bytes) {using var response=await Queue(client,name,bytes);response.EnsureSuccessStatusCode();return(await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();}
    [Fact]
    public void NumberedQuestionsAndAnswerAnalysisAreSeparatedWithoutInventingMissingAnswers() {
        var rows=QuestionBankIntake.Split("数学资料\n1. 计算3+4。\n【答案】7\n【解析】相加。\n2．求点到直线距离。","数学","高中","自编资料",[]);
        Assert.Equal(2,rows.Length);Assert.Equal("7",rows[0].Question.Answer);Assert.Equal("相加。",rows[0].Question.Analysis);Assert.DoesNotContain("【答案】",rows[0].Question.Stem);Assert.Empty(rows[1].Question.Answer);
        Assert.DoesNotContain(rows,r=>r.Question.Stem=="数学资料");
    }
    [Fact]
    public async Task TextUploadPersistsDraftsAndOrdinaryTeacherCanConfirmAndSearchThem() {
        await using var f=new IntakeFactory();using var admin=await f.Login("intake-admin",true);var(teacher,_)=await Teacher(f,admin,"上传教师");using(teacher){
            var id=await Upload(teacher,"原创题目.txt",Encoding.UTF8.GetBytes("1. 青岚专项：首项2，公差3，求第5项。\n【答案】14\n【解析】2+4×3=14。"));
            await f.Services.GetRequiredService<QuestionBankIntake>().Process(id,CancellationToken.None);
            var job=await teacher.GetFromJsonAsync<JsonElement>($"/api/question-bank/intakes/{id}");Assert.Equal("review",job.GetProperty("state").GetString());var drafts=job.GetProperty("drafts");Assert.Equal(1,drafts.GetArrayLength());
            var body=new{requestId="confirmed-once",drafts};var first=await Factory.Post(teacher,$"/api/question-bank/intakes/{id}/publish",body);var retry=await Factory.Post(teacher,$"/api/question-bank/intakes/{id}/publish",body);Assert.Equal(first.GetRawText(),retry.GetRawText());
            var result=await Factory.Post(admin,"/api/question-bank/recommendations",new{subject="数学",stage="高中",topic="青岚专项"});Assert.Equal("14",result.GetProperty("matches")[0].GetProperty("question").GetProperty("answer").GetString());
            var jobs=await teacher.GetFromJsonAsync<JsonElement[]>("/api/question-bank/intakes");Assert.Single(jobs!);Assert.Empty(jobs![0].GetProperty("drafts").EnumerateArray());
        }
    }
    [Fact]
    public async Task ImageEvidenceIsPrivateUntilPublicationAndStopsSharingAfterWithdrawal() {
        await using var f=new IntakeFactory();using var admin=await f.Login("photo-admin",true);var(teacher,_)=await Teacher(f,admin,"原图教师");using(teacher){
            var id=await Upload(teacher,"题目照片.png",Png);var url=$"/api/question-bank/intakes/{id}/assets/page-1.png";
            Assert.Equal(HttpStatusCode.Forbidden,(await admin.GetAsync(url)).StatusCode);
            await f.Services.GetRequiredService<QuestionBankIntake>().Process(id,CancellationToken.None);
            var job=await teacher.GetFromJsonAsync<JsonElement>($"/api/question-bank/intakes/{id}");var drafts=job.GetProperty("drafts");Assert.Single(drafts[0].GetProperty("images").EnumerateArray());
            Assert.Equal(HttpStatusCode.Forbidden,(await admin.PostAsJsonAsync($"/api/question-bank/intakes/{id}/publish",new{requestId="foreign",drafts})).StatusCode);
            var result=await Factory.Post(teacher,$"/api/question-bank/intakes/{id}/publish",new{requestId="photo-publish",drafts});
            Assert.Equal(HttpStatusCode.OK,(await admin.GetAsync(url)).StatusCode);
            var rows=await teacher.GetFromJsonAsync<JsonElement[]>("/api/question-bank/uploads/mine");Assert.Single(rows![0].GetProperty("question").GetProperty("images").EnumerateArray());
            await teacher.PutAsJsonAsync("/api/question-bank/uploads/"+result.GetProperty("ids")[0].GetString(),new{active=false});Assert.Equal(HttpStatusCode.Forbidden,(await admin.GetAsync(url)).StatusCode);Assert.Equal(HttpStatusCode.OK,(await teacher.GetAsync(url)).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden,(await admin.GetAsync($"/api/question-bank/intakes/{id}")).StatusCode);
        }
    }
    [Fact]
    public async Task UnsupportedAndSpoofedFilesAreRejectedAndMissingAnswersCannotBePublished() {
        await using var f=new IntakeFactory();using var client=await f.Login("validation-admin",true);
        Assert.Equal(HttpStatusCode.BadRequest,(await Queue(client,"payload.json",Encoding.UTF8.GetBytes("{}"))).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await Queue(client,"fake.pdf",Encoding.UTF8.GetBytes("not a PDF"))).StatusCode);
        var id=await Upload(client,"待补答案.txt",Encoding.UTF8.GetBytes("1. 这个题目尚未提供答案。"));await f.Services.GetRequiredService<QuestionBankIntake>().Process(id,CancellationToken.None);
        var job=await client.GetFromJsonAsync<JsonElement>($"/api/question-bank/intakes/{id}");Assert.Equal(HttpStatusCode.BadRequest,(await client.PostAsJsonAsync($"/api/question-bank/intakes/{id}/publish",new{requestId="invalid",drafts=job.GetProperty("drafts")})).StatusCode);
        Assert.Empty((await client.GetFromJsonAsync<JsonElement[]>("/api/question-bank/uploads/mine"))!);
    }
    [Fact]
    public async Task LearnerListsAndRecommendationsEnforceTheAssignedClassAndCourse() {
        await using var f=new IntakeFactory();using var admin=await f.Login("learner-admin",true);var(teacher,teacherId)=await Teacher(f,admin,"任课教师");using(teacher){
            var campus=new Campus{Name="校区"};var course=new Course{Name="高中数学"};var schoolClass=new SchoolClass{Name="高中一班",CampusId=campus.Id};var other=new SchoolClass{Name="其他班",CampusId=campus.Id};var student=new Student{Name="学员甲",ClassId=schoolClass.Id};var foreign=new Student{Name="学员乙",ClassId=other.Id};
            using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(campus,course,schoolClass,other,student,foreign,new Assignment{StaffId=teacherId,ClassId=schoolClass.Id,CourseId=course.Id});await db.SaveChangesAsync();}
            var groups=await teacher.GetFromJsonAsync<JsonElement[]>("/api/question-bank/learner-groups");Assert.Single(groups!);
            var list=await teacher.GetFromJsonAsync<JsonElement[]>($"/api/question-bank/learners?classId={schoolClass.Id}&courseId={course.Id}");Assert.Single(list!);Assert.Equal(student.Id,list![0].GetProperty("id").GetGuid());
            Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"/api/question-bank/learners?classId={other.Id}&courseId={course.Id}")).StatusCode);
            var body=new BankLearnerQuery(schoolClass.Id,course.Id,[student.Id],"数学","高中","点线距离","填空题");var result=await Factory.Post(teacher,"/api/question-bank/learners/recommend",body);Assert.Equal(5,result.GetProperty("groups")[0].GetProperty("returned").GetInt32());
            Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync("/api/question-bank/learners/recommend",body with{StudentIds=[foreign.Id]})).StatusCode);
            Assert.Equal(HttpStatusCode.BadRequest,(await teacher.PostAsJsonAsync("/api/question-bank/learners/recommend",body with{Subject="化学"})).StatusCode);
            using var check=f.Services.CreateScope();Assert.Equal(0,await check.ServiceProvider.GetRequiredService<Data>().ClassSessionMarks.CountAsync());
        }
    }
    [Fact]
    public async Task DirectLearnerDirectoryIncludesOnlyAuthorizedSubjectsAndKeepsClassStage() {
        await using var f=new IntakeFactory();using var admin=await f.Login("direct-directory-admin",true);var(teacher,teacherId)=await Teacher(f,admin,"目录教师");using(teacher){
            var campus=new Campus{Name="测试校区"};var cl=new SchoolClass{Name="初一一班",CampusId=campus.Id};var other=new SchoolClass{Name="高一外班",CampusId=campus.Id};var math=new Course{Name="高中数学"};var physics=new Course{Name="物理"};var foreignCourse=new Course{Name="化学"};var student=new Student{Name="可见学员",ClassId=cl.Id};var foreign=new Student{Name="不可见学员",ClassId=other.Id};
            using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(campus,cl,other,math,physics,foreignCourse,student,foreign,new Assignment{ClassId=cl.Id,CourseId=math.Id,StaffId=teacherId},new Assignment{ClassId=cl.Id,CourseId=physics.Id,StaffId=teacherId});await db.SaveChangesAsync();}
            var rows=await teacher.GetFromJsonAsync<JsonElement[]>("/api/question-bank/learners");Assert.Single(rows!);var options=rows![0].GetProperty("courses").EnumerateArray().ToArray();Assert.Equal(2,options.Length);Assert.All(options,c=>Assert.Equal("初中",c.GetProperty("stage").GetString()));Assert.DoesNotContain(options,c=>c.GetProperty("subject").GetString()=="化学");
            Assert.Empty((await teacher.GetFromJsonAsync<JsonElement[]>("/api/question-bank/learners?query=不可见"))!);
            Assert.Equal(HttpStatusCode.BadRequest,(await teacher.GetAsync($"/api/question-bank/learners?classId={cl.Id}")).StatusCode);
            var auto=await Factory.Post(teacher,"/api/question-bank/learners/recommend",new BankLearnerQuery(cl.Id,math.Id,[student.Id]));Assert.Equal("初中",auto.GetProperty("stage").GetString());Assert.Equal(0,auto.GetProperty("groups")[0].GetProperty("returned").GetInt32());
            Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync("/api/question-bank/learners/recommend",new BankLearnerQuery(cl.Id,math.Id,[student.Id],CourseIds:[foreignCourse.Id]))).StatusCode);
        }
    }
    [Fact]
    public async Task AutomaticLearnerRecommendationsUseReviewedErrorsWithoutTopicAndNeverInventMissingEvidence() {
        await using var f=new IntakeFactory();using var admin=await f.Login("direct-automatic-admin",true);var(teacher,teacherId)=await Teacher(f,admin,"推荐教师");using(teacher){
            var campus=new Campus{Name="校区"};var cl=new SchoolClass{Name="高一一班",CampusId=campus.Id};var course=new Course{Name="数学"};var student=new Student{Name="学员甲",ClassId=cl.Id};var lecture=new Lecture{CourseId=course.Id,Title="直线与圆"};var handout=new Handout{LectureId=lecture.Id};
            var bank=f.Services.GetRequiredService<IQuestionBank>();var source=bank.Search(new("数学","高中","点线距离","填空题",10)).Matches.First().Question;
            var q=new Question{HandoutId=handout.Id,Label="例1",BlocksJson=Json.Write(new[]{new ContentBlock("",source.Stem,"text")})};var session=new ClassSession{ClassId=cl.Id,CourseId=course.Id,StaffId=teacherId,HandoutId=handout.Id,LessonDate="2026-11-03"};var mark=new ClassSessionMark{ClassSessionId=session.Id,StudentId=student.Id,WrongIdsJson=Json.Write(new[]{q.Id}),Reviewed=false};
            using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(campus,cl,course,student,lecture,handout,q,session,mark,new Assignment{ClassId=cl.Id,CourseId=course.Id,StaffId=teacherId});await db.SaveChangesAsync();}
            var input=new BankLearnerQuery(cl.Id,course.Id,[student.Id]);var pending=await Factory.Post(teacher,"/api/question-bank/learners/recommend",input);Assert.Equal("no_reviewed_errors",pending.GetProperty("groups")[0].GetProperty("status").GetString());
            using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();(await db.ClassSessionMarks.SingleAsync(m=>m.Id==mark.Id)).Reviewed=true;await db.SaveChangesAsync();}
            var result=await Factory.Post(teacher,"/api/question-bank/learners/recommend",input);var group=result.GetProperty("groups")[0];Assert.True(group.GetProperty("returned").GetInt32()>0);var matches=group.GetProperty("matches").EnumerateArray().ToArray();Assert.All(matches,m=>{var question=m.GetProperty("question");Assert.Equal("数学",question.GetProperty("subject").GetString());Assert.Equal("高中",question.GetProperty("stage").GetString());Assert.NotEqual(LocalQuestionBank.Fingerprint("数学",source.Stem),LocalQuestionBank.Fingerprint("数学",question.GetProperty("stem").GetString()!));});
            using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();var unchanged=await db.ClassSessionMarks.SingleAsync(m=>m.Id==mark.Id);Assert.True(unchanged.Reviewed);Assert.Equal(mark.WrongIdsJson,unchanged.WrongIdsJson);(await db.Questions.SingleAsync(x=>x.Id==q.Id)).BlocksJson=Json.Write(new[]{new ContentBlock("","","image")});await db.SaveChangesAsync();}
            var imageOnly=await Factory.Post(teacher,"/api/question-bank/learners/recommend",input);Assert.Equal("needs_text",imageOnly.GetProperty("groups")[0].GetProperty("status").GetString());
            using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();(await db.Classes.SingleAsync(x=>x.Id==cl.Id)).Name="临时班";await db.SaveChangesAsync();}
            var missingStage=await Factory.Post(teacher,"/api/question-bank/learners/recommend",input with{Stage="高中"});Assert.Equal("stage_missing",missingStage.GetProperty("groups")[0].GetProperty("status").GetString());
        }
    }
    [Fact]
    public async Task DedicatedSidecarAllowsNewBankFeaturesButStillDeniesLegacyBusinessWrites() {
        await using var f=new IntakeFactory(true);using var client=f.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized,(await client.GetAsync("/api/question-bank/learner-groups")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,(await Queue(client,"private.txt",Encoding.UTF8.GetBytes("private"))).StatusCode);
        const string token="intake-dedicated-fixture";var staff=new Staff{Name="验收",OpenId="intake-fixture",Role="teacher"};
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();await db.Database.EnsureCreatedAsync();db.AddRange(staff,new Session{StaffId=staff.Id,OpenId=staff.OpenId,TokenHash=Secrets.Hash(token),ExpiresAt=DateTimeOffset.UtcNow.AddMinutes(5)});await db.SaveChangesAsync();}
        client.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",token);
        var id=await Upload(client,"说明.txt",Encoding.UTF8.GetBytes("1. 测试。\n【答案】1"+new string(' ',300000)));Assert.Equal(HttpStatusCode.OK,(await client.GetAsync($"/api/question-bank/intakes/{id}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,(await client.PostAsJsonAsync("/api/classes",new{})).StatusCode);
    }
}
