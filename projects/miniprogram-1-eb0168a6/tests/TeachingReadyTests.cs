using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Validation;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.EntityFrameworkCore;
using Siyue;
using Xunit;
using static Siyue.EnglishTeachingTools;

public class TeachingReadyTests
{
    static JsonElement Element(object value)=>JsonSerializer.SerializeToElement(value,Json.Options);
    static Lesson Sample()=>new("一般现在时：主语是第三人称单数时注意动词形式。","学习目标：能够描述日常活动。\n语法讲解：I walk to school. She walks to school.",
        Enumerable.Range(1,3).Select(i=>new Exercise($"基础第{i}题：She ___ (walk) to school every day.","walks", "主语She是第三人称单数。")).ToArray(),
        Enumerable.Range(1,3).Select(i=>new Exercise($"提升第{i}题：改正 He don't like English. 中的语法错误。","He doesn't like English.","第三人称单数的否定式使用doesn't。")).ToArray());
    static EssayResult Result(params Correction[] corrections)=>new([new(corrections,"请对照修改建议复核。","Invented facts at six thirty.","Independent model essay.")],[]);
    static TeachingJobStore Store(string dir,IConfiguration? c=null){c??=new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?>{{"TeachingTools:Directory",dir}}).Build();return new(c,new TeachingAdmission(c),NullLogger<TeachingJobStore>.Instance);}
    static TeachingJobInput Request(Guid cls,Guid? id=null)=>new(cls,"lesson",id??Guid.NewGuid(),Element(new LessonInput(cls,"Unit 1","一般现在时","能介绍日常生活")));
    [Fact] public void FactsAndNegationCannotBeRewrittenAndWordCountIsDeterministic(){
        const string original="I get up at seven. I walk to school. I do not like art.";
        var result=GroundEssays([original],Result(new("seven","six thirty","改善"),new("walk","take a bus","改善"),new("do not like","like","改善")));
        var r=Assert.Single(result.Essays);Assert.Equal(original,r.RevisedText);Assert.Equal(original,r.BasicExample);Assert.All(r.Corrections,c=>Assert.False(c.Applied));Assert.Equal(14,r.WordCount);Assert.StartsWith("独立范文",r.AdvancedExample);Assert.NotEmpty(r.ReviewWarnings);
    }
    [Fact] public void SafeGrammarEditsAreAnchoredAndAmbiguousOverlapsAreNotApplied(){
        var output=GroundEssays(["He play football. He go home. go go."],Result(new("He play","He plays","第三人称"),new("He play football","He plays football","重复建议"),new("go","goes","定位不唯一")));
        Assert.Equal("He plays football. He go home. go go.",output.Essays[0].RevisedText);
        Assert.Equal(1,output.Essays[0].Corrections.Count(c=>c.Applied));
        Assert.False(GrammarOnly("I like English","I like art"));Assert.False(GrammarOnly("I walked","I walk"));Assert.True(GrammarOnly("He don't","He doesn't"));
    }
    [Fact] public void ModelSharedClaimsRequireRepeatedAnchoredEvidence(){
        var a=Result(new Correction("He go","He goes","主谓一致")).Essays[0];var b=Result().Essays[0];
        Assert.Empty(GroundEssays(["He go home.","I like art."],new([a,b],["两人均不懂时态"])).SharedIssues);
        Assert.Single(GroundEssays(["He go home.","He go to school."],new([a,a],[])).SharedIssues);
    }
    [Fact] public void WordVersionsHaveExactlySixQuestionsAndStudentHasNoAnswers(){
        var lesson=Sample();Assert.False(DuplicatesExercises(lesson));Assert.True(DuplicatesExercises(lesson with{Handout=lesson.Handout+"\n基础练习\n"+lesson.Basic[0].Question}));
        foreach(var audience in new[]{"student","teacher"}){
            var bytes=WordDocuments.EnglishLesson("Unit 1 日常生活",lesson,audience);using var stream=new MemoryStream(bytes);using var doc=WordprocessingDocument.Open(stream,false);
            Assert.Empty(new OpenXmlValidator().Validate(doc));var text=doc.MainDocumentPart!.Document.InnerText;
            foreach(var q in lesson.Basic.Concat(lesson.Advanced))Assert.Equal(1,text.Split(q.Question).Length-1);
            if(audience=="student"){Assert.DoesNotContain("教师参考",text);Assert.DoesNotContain("主语She是第三人称单数",text);Assert.DoesNotContain("He doesn't like English.",text);}else Assert.Contains("教师参考板书与答案",text);
            var artifacts=Environment.GetEnvironmentVariable("TEACHING_TEST_OUTPUT");if(artifacts!=null){Directory.CreateDirectory(artifacts);File.WriteAllBytes(Path.Combine(artifacts,audience+".docx"),bytes);}
        }
    }
    [Fact] public async Task ConcurrentRetriesPersistExactlyOnceAndConflictsAreRejected(){
        var dir=Path.Combine(Path.GetTempPath(),"teaching-test-"+Guid.NewGuid());try{
            var store=Store(dir);var owner=Guid.NewGuid();var request=Request(Guid.NewGuid());var results=await Task.WhenAll(Enumerable.Range(0,12).Select(_=>store.Create(owner,request)));
            Assert.Single(await store.List(owner));Assert.All(results,r=>Assert.Equal(request.RequestId,r.Id));Assert.Single(await Store(dir).List(owner));Assert.Empty(await store.List(Guid.NewGuid()));
            var err=await Assert.ThrowsAsync<ApiException>(()=>store.Create(owner,request with{ClassId=Guid.NewGuid()}));Assert.Equal(409,err.Status);
        }finally{Directory.Delete(dir,true);}
    }
    [Fact] public async Task RestartRetainsInputAndCancellingSameIdNeverCancelsOtherOwner(){
        var dir=Path.Combine(Path.GetTempPath(),"teaching-test-"+Guid.NewGuid());try{
            var store=Store(dir);var a=Guid.NewGuid();var b=Guid.NewGuid();var req=Request(Guid.NewGuid());await store.Create(a,req);await store.Create(b,req);
            using var ca=new CancellationTokenSource();using var cb=new CancellationTokenSource();await store.Begin(a,req.RequestId,ca);await store.Begin(b,req.RequestId,cb);await store.Cancel(a,req.RequestId);
            Assert.True(ca.IsCancellationRequested);Assert.False(cb.IsCancellationRequested);await store.Finish(a,req.RequestId,"succeeded",Element(new{}),"");Assert.Equal("cancelled",(await store.Load(a,req.RequestId)).State);
            var restart=Store(dir);await restart.Initialize();Assert.Equal("failed",(await restart.Load(b,req.RequestId)).State);Assert.Equal(req.Input.GetRawText(),(await restart.Load(b,req.RequestId)).Input.GetRawText());
        }finally{Directory.Delete(dir,true);}
    }
    [Fact] public async Task ACorruptRecordCannotStopOtherSavedTasks(){
        var dir=Path.Combine(Path.GetTempPath(),"teaching-test-"+Guid.NewGuid());try{var owner=Guid.NewGuid();var store=Store(dir);var req=Request(Guid.NewGuid());await store.Create(owner,req);await File.WriteAllTextAsync(Path.Combine(dir,owner.ToString("N"),Guid.NewGuid().ToString("N")+".json"),"{");await Store(dir).Initialize();Assert.Single(await store.List(owner));}finally{Directory.Delete(dir,true);}
    }
    [Fact] public void LimitsAreSeparatePerToolAndHaveAGlobalBudget(){
        var c=new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?>{{"TeachingTools:DailyGlobalLimit","8"}}).Build();var limit=new TeachingAdmission(c);var owner=Guid.NewGuid();for(var i=0;i<6;i++)limit.Admit(owner,"lesson");Assert.Equal(429,Assert.Throws<ApiException>(()=>limit.Admit(owner,"lesson")).Status);limit.Admit(owner,"essays");limit.Admit(Guid.NewGuid(),"lesson");Assert.Equal(429,Assert.Throws<ApiException>(()=>limit.Admit(Guid.NewGuid(),"lesson")).Status);
    }
    [Fact] public async Task ApiEnforcesReviewOwnershipClassScopeAndInvalidInputsDoNotConsumeQuota(){
        await using var f=new Factory();using var admin=await f.Login("teaching-admin",true);
        f.Services.GetRequiredService<IConfiguration>()["TeachingTools:Directory"]=Path.Combine(f.Root,"teaching");
        var campus=await Factory.Post(admin,"/api/admin/catalog/campuses",new{name="验收校区"});var cl=await Factory.Post(admin,"/api/admin/catalog/classes",new{name="英语一班",campusId=campus.GetProperty("id").GetGuid()});var cls=cl.GetProperty("id").GetGuid();
        Guid owner;using(var scope=f.Services.CreateScope())owner=await scope.ServiceProvider.GetRequiredService<Data>().Staff.Where(x=>x.OpenId=="development:teaching-admin").Select(x=>x.Id).SingleAsync();
        var request=Request(cls);for(var i=0;i<7;i++){var bad=request with{Input=Element(new LessonInput(cls,"","",""))};Assert.Equal(HttpStatusCode.BadRequest,(await admin.PostAsJsonAsync("/api/teaching-tools/jobs",bad)).StatusCode);}
        var job=await Factory.Post(admin,"/api/teaching-tools/jobs",request);Assert.Equal("queued",job.GetProperty("state").GetString());await Factory.Post(admin,"/api/teaching-tools/jobs",request);
        var store=f.Services.GetRequiredService<TeachingJobStore>();await store.Finish(owner,request.RequestId,"succeeded",Element(Sample()),"");var word=$"/api/teaching-tools/jobs/{request.RequestId}/word?audience=student";
        Assert.Equal(HttpStatusCode.Conflict,(await admin.GetAsync(word)).StatusCode);await Factory.Post(admin,$"/api/teaching-tools/jobs/{request.RequestId}/review",new{reviewed=true});Assert.Equal(HttpStatusCode.OK,(await admin.GetAsync(word)).StatusCode);
        using var anon=f.CreateClient();Assert.Equal(HttpStatusCode.Unauthorized,(await anon.GetAsync(word)).StatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.Staff.Add(new Staff{Name="其他老师",OpenId="development:teaching-other"});await db.SaveChangesAsync();}
        using var other=await f.Login("teaching-other");Assert.Equal(HttpStatusCode.NotFound,(await other.GetAsync(word)).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await other.PostAsJsonAsync("/api/teaching-tools/jobs",Request(cls))).StatusCode);
        for(var i=1;i<6;i++){var r=Request(cls);await Factory.Post(admin,"/api/teaching-tools/jobs",r);await store.Cancel(owner,r.RequestId);}
        var blocked=await admin.PostAsJsonAsync("/api/teaching-tools/jobs",Request(cls));Assert.Equal(HttpStatusCode.TooManyRequests,blocked.StatusCode);Assert.NotNull(blocked.Headers.RetryAfter);Assert.Contains("请求较多",await blocked.Content.ReadAsStringAsync());
    }
}
