using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class LearningEvolutionTests
{
    static readonly XNamespace W=WordDocuments.W;
    static XElement P(string text)=>new(W+"p",new XElement(W+"r",new XElement(W+"t",text)));
    static async Task<(Student student,Course course,Handout handout,Question question)> Seed(Factory f)
    {
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var campus=new Campus{Name="验收校区"};var cl=new SchoolClass{Name="验收班",CampusId=campus.Id};var course=new Course{Name="数学"};var student=new Student{Name="学生甲",ClassId=cl.Id,Code="001"};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id,Sequence=1,Title="一元一次方程"};var handout=new Handout{LectureId=lecture.Id,State="published",Version=1};var q=new Question{HandoutId=handout.Id,Label="例1",Order=1,BlocksJson=Json.Write(new[]{WordDocuments.Block(P("例1 解方程 2x+3=7。"))})};db.AddRange(campus,cl,course,student,lecture,handout,q);await db.SaveChangesAsync();return(student,course,handout,q);
    }
    static async Task<HttpClient> Teacher(Factory f,HttpClient admin,Guid classId,Guid courseId)
    {
        var s=await Factory.Post(admin,"/api/admin/catalog/staff",new{name="任课教师"});var sid=s.GetProperty("id").GetGuid();var invite=await Factory.Post(admin,$"/api/admin/staff/{sid}/invite",new{});var client=await f.Login("teacher-history");await Factory.Post(client,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});await Factory.Post(admin,"/api/admin/catalog/assignments",new{staffId=sid,classId,courseId});return client;
    }
    [Fact]
    public async Task EightLectureBookKeepsImagesMathTablesAndExcludesTocFromHeadingDetection()
    {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var path=Path.Combine(f.Root,"合订八讲.docx");SystemTests.Fixture(path);
        using(var doc=WordprocessingDocument.Open(path,true)){
            var main=doc.MainDocumentPart!;var source=XElement.Parse(main.Document!.Body!.OuterXml).Elements().Where(x=>x.Name!=W+"sectPr").Skip(1).ToArray();var blocks=new List<XElement>{P("八讲合订讲义"),P("目录")};for(var n=1;n<=8;n++)blocks.Add(P($"第{n}讲 方程……{n*10}"));for(var n=1;n<=8;n++){blocks.Add(P($"第{n}讲 方程{n}"));blocks.AddRange(source.Select(x=>new XElement(x)));}blocks.Add(P("参考答案"));blocks.Add(P("第一讲"));blocks.Add(P("例1：x=2"));main.Document.Body=new Body(new XElement(W+"body",blocks).ToString());main.Document.Save();
        }
        var all=BookStructure.Read(path);var sections=BookStructure.Detect(all);Assert.Equal(8,sections.Count(s=>s.Kind=="lesson"));Assert.Equal("unassigned",sections[0].Kind);Assert.Equal("reference",sections[^1].Kind);
        foreach(var section in sections.Where(s=>s.Kind=="lesson")){
            var slice=Path.Combine(f.Root,$"第{section.Sequence}讲.docx");BookStructure.Slice(path,slice,section.Start,section.End);var qs=await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(slice,Guid.NewGuid(),default);Assert.Equal(4,qs.Count);Assert.Contains(qs,q=>q.BlocksJson.Contains("oMath"));Assert.Contains(qs,q=>WordDocuments.ImageReferences(Json.Read<ContentBlock[]>(q.BlocksJson).FirstOrDefault(b=>b.Xml.Contains("blip"))?.Xml??"<p/>").Length>0);Assert.Contains(qs,q=>Json.Read<ContentBlock[]>(q.BlocksJson).Any(b=>b.Kind=="table"));Assert.DoesNotContain(qs,q=>q.BlocksJson.Contains("参考答案"));Assert.True(qs[^1].BlocksJson.Length>5000);
        }
    }
    [Fact]
    public async Task BracketedFullWidthLabelsAndExplicitTableRowsSplitWithoutBreakingSubquestions()
    {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var path=Path.Combine(f.Root,"题号.docx");using(var doc=WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)){var main=doc.AddMainDocumentPart();main.Document=new Document(new XElement(W+"document",new XElement(W+"body",P("【例题１】解方程"),P("（1）一个小问"),P("3.14 是小数"),new XElement(W+"tbl",new XElement(W+"tr",new XElement(W+"tc",P("练习1 求值"))),new XElement(W+"tr",new XElement(W+"tc",P("练习2 求面积")))),P("２．综合题"))).ToString());}
        var qs=await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(path,Guid.NewGuid(),default);Assert.Equal(4,qs.Count);Assert.Contains("小问",string.Concat(Json.Read<ContentBlock[]>(qs[0].BlocksJson).Select(b=>b.Text)));Assert.Contains("3.14",string.Concat(Json.Read<ContentBlock[]>(qs[0].BlocksJson).Select(b=>b.Text)));Assert.StartsWith("【例题１】",qs[0].Label);Assert.Equal("table",Json.Read<ContentBlock[]>(qs[1].BlocksJson)[0].Kind);
    }
    [Fact]
    public async Task UploadedBookAutomaticallyQueuesRecognizedLecturesForItsClass()
    {
        await using var f = new Factory();
        using var admin = await f.Login("admin-auto-book", true);
        var (student, course, _, _) = await Seed(f);
        var path = Path.Combine(f.Root, "auto.docx");
        using (var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document))
        {
            doc.AddMainDocumentPart().Document = new Document(new XElement(W + "document",
                new XElement(W + "body", P("封面"), P("第三讲 方程"), P("例1. 求值"),
                    P("【讲４】几何"), P("练1. 求面积"))).ToString());
        }
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent("合订讲义.docx"), "originalName");
        form.Add(new StreamContent(File.OpenRead(path)), "file", "wx-temp");
        var response = await admin.PostAsync($"/api/classes/{student.ClassId}/books?courseId={course.Id}", form);
        Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
        var id = (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
        using (var scope = f.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            Assert.Equal("book-auto", (await db.Work.SingleAsync(w => w.EntityId == id)).Kind);
        }
        await f.Run("book-auto", id);
        await f.Run("book-auto", id);
        await f.Run("book-split", id);
        var detail = await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");
        Assert.Equal("split", detail.GetProperty("book").GetProperty("state").GetString());
        using var check = f.Services.CreateScope();
        var data = check.ServiceProvider.GetRequiredService<Data>();
        var lectures = await data.Lectures.Where(l => l.Sequence >= 3).OrderBy(l => l.Sequence).ToListAsync();
        Assert.Equal(new[] { 3, 4 }, lectures.Select(l => l.Sequence));
        Assert.All(lectures, l => { Assert.Equal(student.ClassId, l.ClassId); Assert.Equal(course.Id, l.CourseId); });
        Assert.Equal(2, await data.Work.CountAsync(w => w.Kind == "import" && w.State == "queued"));
    }

    [Fact]
    public async Task BookConfirmationRequiresCompleteCoverageAndCreatesVersionsIdempotently()
    {
        await using var f=new Factory();using var admin=await f.Login("admin-book",true);var(s,c,h,q)=await Seed(f);var path=Path.Combine(f.Root,"book.docx");using(var doc=WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)){doc.AddMainDocumentPart().Document=new Document(new XElement(W+"document",new XElement(W+"body",P("封面"),P("第一讲 一元一次方程"),P("例1. x+1=2"),P("【讲２】几何"),P("练1. 求面积"))).ToString());}
        using var form=new MultipartFormDataContent();form.Add(new StringContent("两讲合订.docx"),"originalName");form.Add(new StreamContent(File.OpenRead(path)),"file","wx-tmp");var response=await admin.PostAsync($"/api/classes/{s.ClassId}/books?courseId={c.Id}",form);Assert.Equal(HttpStatusCode.Accepted,response.StatusCode);var id=(await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();await f.Run("book",id);var detail=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");var sections=detail.GetProperty("sections").Deserialize<BookSection[]>(Json.Options)!;
        Assert.Equal("两讲合订.docx",detail.GetProperty("book").GetProperty("fileName").GetString());
        Assert.Equal(new[]{1,2},sections.Where(x=>x.Kind=="lesson").Select(x=>x.Sequence));
        Assert.Equal(HttpStatusCode.BadRequest,(await admin.PostAsJsonAsync($"/api/books/{id}/confirm",new BookReview(0,sections))).StatusCode);
        sections[0]=sections[0] with{Kind="exclude",HandoutId=h.Id};var invalid=sections.ToArray();invalid[1]=invalid[1] with{Start=2};Assert.Equal(HttpStatusCode.BadRequest,(await admin.PostAsJsonAsync($"/api/books/{id}/confirm",new BookReview(0,invalid))).StatusCode);
        await Factory.Post(admin,$"/api/books/{id}/confirm",new BookReview(0,sections));await Factory.Post(admin,$"/api/books/{id}/confirm",new BookReview(0,sections));using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(2,await db.Lectures.CountAsync());Assert.Equal(3,await db.Handouts.CountAsync());Assert.Equal("published",(await db.Handouts.FindAsync(h.Id))!.State);Assert.Equal(2,await db.Work.CountAsync(w=>w.Kind=="import"));
        Assert.Null(Json.Read<BookSection[]>((await db.BookImports.FindAsync(id))!.SectionsJson)[0].HandoutId);Assert.Equal(2,await db.Work.CountAsync(w=>w.Kind=="import"&&w.State=="waiting"));await f.Run("book-split",id);await f.Run("book-split",id);Assert.Equal(2,await db.Work.CountAsync(w=>w.Kind=="import"&&w.State=="queued"));
        foreach(var imported in await db.Handouts.Where(x=>x.Id!=h.Id).Select(x=>x.Id).ToListAsync()) { await f.Run("import",imported); Assert.Equal("draft",(await admin.GetFromJsonAsync<JsonElement>($"/api/handouts/{imported}?classId={s.ClassId}")).GetProperty("handout").GetProperty("state").GetString()); }
        var others=new Course{Name="其他课程"};db.Courses.Add(others);await db.SaveChangesAsync();using var teacher=await Teacher(f,admin,s.ClassId,others.Id);Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"/api/books/{id}")).StatusCode);
    }
    [Fact]
    public async Task CorrectionsKeepHistoryRetestsAreIdempotentAndOtherCoursesCannotReadOrExport()
    {
        await using var f=new Factory();using var admin=await f.Login("admin-history",true);var(s,c,h,q)=await Seed(f);using var teacher=await Teacher(f,admin,s.ClassId,c.Id);
        async Task Mark(string state){var result=await teacher.PutAsJsonAsync("/api/wrong-questions",new WrongInput(h.Id,[s.Id],state=="wrong"?[q.Id]:[],ClassId:s.ClassId,Marks:new(){[q.Id]=state}));Assert.True(result.IsSuccessStatusCode,await result.Content.ReadAsStringAsync());}
        await Mark("wrong");await Mark("wrong");await Mark("correct");var history=await teacher.GetFromJsonAsync<JsonElement>($"/api/students/{s.Id}/history?courseId={c.Id}");var item=Assert.Single(history.GetProperty("items").EnumerateArray());Assert.Equal("correct",item.GetProperty("state").GetString());Assert.Equal(2,item.GetProperty("events").GetArrayLength());Assert.Equal(0,item.GetProperty("retests").GetInt32());
        var retest=new RetestInput(c.Id,q.Id,"wrong","retry-1");await Factory.Post(teacher,$"/api/students/{s.Id}/retests",retest);await Factory.Post(teacher,$"/api/students/{s.Id}/retests",retest);Assert.Equal(HttpStatusCode.Conflict,(await teacher.PostAsJsonAsync($"/api/students/{s.Id}/retests",retest with{State="correct"})).StatusCode);
        var exported=await Factory.Post(teacher,$"/api/students/{s.Id}/history/export",new HistoryExportInput(c.Id,[q.Id]));using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Empty(await db.WrongQuestions.ToListAsync());Assert.Equal(3,await db.LearningEvents.CountAsync());var d=await db.Documents.FindAsync(exported.GetProperty("id").GetGuid());Assert.Equal(q.Id,Assert.Single(Json.Read<QuestionSnapshot[]>(d!.SnapshotJson)).Id);
        var other=new Course{Name="无权课程"};db.Courses.Add(other);await db.SaveChangesAsync();Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"/api/students/{s.Id}/history?courseId={other.Id}")).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync($"/api/students/{s.Id}/history/export",new HistoryExportInput(c.Id,[Guid.NewGuid()]))).StatusCode);
        var report=await Factory.Post(teacher,$"/api/students/{s.Id}/reports",new ReportInput(c.Id));var reportId=report.GetProperty("id").GetGuid();var facts=(await teacher.GetFromJsonAsync<JsonElement>($"/api/learning-support/{reportId}")).GetProperty("payload");Assert.Equal(1,facts.GetProperty("wrongQuestions").GetInt32());Assert.Equal(1,facts.GetProperty("retestedQuestions").GetInt32());Assert.Equal(0,facts.GetProperty("latestRetestCorrect").GetInt32());var reportDoc=await Factory.Post(teacher,$"/api/learning-support/{reportId}/approve",new PublishInput(0));await f.Run("support",reportDoc.GetProperty("id").GetGuid());Assert.Equal(HttpStatusCode.Conflict,(await teacher.PutAsJsonAsync($"/api/learning-support/{reportId}",new SupportReview(1,TeacherNote:"覆盖"))).StatusCode);
    }
    [Fact]
    public async Task LegacyWrongLinkIsCapturedBeforeClearingAndUnmarkedIsNotCorrect()
    {
        await using var f=new Factory();using var admin=await f.Login("legacy",true);var(s,c,h,q)=await Seed(f);using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.WrongQuestions.Add(new WrongQuestion{StudentId=s.Id,QuestionId=q.Id});await db.SaveChangesAsync();}
        Assert.True((await admin.PutAsJsonAsync("/api/wrong-questions",new WrongInput(h.Id,[s.Id],[],ClassId:s.ClassId))).IsSuccessStatusCode);var history=await admin.GetFromJsonAsync<JsonElement>($"/api/students/{s.Id}/history?courseId={c.Id}");var item=Assert.Single(history.GetProperty("items").EnumerateArray());Assert.Equal("unmarked",item.GetProperty("state").GetString());Assert.Equal(2,item.GetProperty("events").GetArrayLength());
    }
    [Fact]
    public async Task AiDraftMustBeReviewedAndStudentDocumentOmitsAnswers()
    {
        await using var f=new AiFactory();using var admin=await f.Login("ai",true);var(s,c,h,q)=await Seed(f);await admin.PutAsJsonAsync("/api/wrong-questions",new WrongInput(h.Id,[s.Id],[q.Id],ClassId:s.ClassId));var result=await Factory.Post(admin,$"/api/students/{s.Id}/variants",new VariantInput(c.Id,[q.Id],1,"ai-1"));var id=result.GetProperty("id").GetGuid();Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"/api/learning-support/{id}/approve",new PublishInput(0))).StatusCode);await f.Run("variants",id);var detail=await admin.GetFromJsonAsync<JsonElement>($"/api/learning-support/{id}");Assert.Equal("draft",detail.GetProperty("state").GetString());Assert.Equal(1,detail.GetProperty("payload").GetProperty("items").GetArrayLength());var doc=await Factory.Post(admin,$"/api/learning-support/{id}/approve",new PublishInput(0));var did=doc.GetProperty("id").GetGuid();await f.Run("support",did);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var d=await db.Documents.FindAsync(did);Assert.Equal("ready",d!.State);await using var file=await scope.ServiceProvider.GetRequiredService<IObjects>().Get(d.DocxKey);using var word=WordprocessingDocument.Open(file,false);Assert.Contains("3x+2=11",word.MainDocumentPart!.Document!.InnerText);Assert.DoesNotContain("答案不可出现在学生页",word.MainDocumentPart.Document!.InnerText);
    }
    [Fact]
    public async Task OldDocBookConversionKeepsLectureBoundaries()
    {
        await using var f=new Factory();using var admin=await f.Login("old-doc",true);var(s,c,_,_)=await Seed(f);using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var source=Path.Combine(f.Root,"two-lectures.docx");using(var doc=WordprocessingDocument.Create(source,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)){doc.AddMainDocumentPart().Document=new Document(new XElement(W+"document",new XElement(W+"body",P("第一讲 方程"),P("例1. 解方程 x+2=5。"),P("第二讲 几何"),P("练1. 求正方形面积。"))).ToString());}
        var converted=await word.Convert(source,"doc",f.Root,default);using var form=new MultipartFormDataContent();form.Add(new StreamContent(File.OpenRead(converted)),"file","两讲合订.doc");var response=await admin.PostAsync($"/api/classes/{s.ClassId}/books?courseId={c.Id}",form);Assert.Equal(HttpStatusCode.Accepted,response.StatusCode);var id=(await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();await f.Run("book",id);var detail=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");Assert.Equal(2,detail.GetProperty("sections").EnumerateArray().Count(x=>x.GetProperty("kind").GetString()=="lesson"));Assert.EndsWith(".doc",detail.GetProperty("book").GetProperty("originalKey").GetString());Assert.EndsWith(".docx",detail.GetProperty("book").GetProperty("normalizedKey").GetString());
    }
    [Fact]
    public async Task DevelopmentUpgradePreservesExistingStudentAndWrongRecords()
    {
        await using var f=new Factory();using var admin=await f.Login("upgrade",true);var(s,c,h,q)=await Seed(f);using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();db.WrongQuestions.Add(new WrongQuestion{StudentId=s.Id,QuestionId=q.Id});await db.SaveChangesAsync();
        await db.Database.ExecuteSqlRawAsync("DROP TABLE LearningEvents; DROP TABLE LearningArtifacts; DROP TABLE BookImports;");await DevelopmentSchema.Upgrade(db);Assert.Equal(s.Id,(await db.Students.SingleAsync()).Id);Assert.Single(await db.WrongQuestions.ToListAsync());Assert.Empty(await db.LearningEvents.ToListAsync());Assert.Empty(await db.LearningArtifacts.ToListAsync());Assert.Empty(await db.BookImports.ToListAsync());
    }
    [Fact]
    public async Task HistoryPagesAndSearchCoverWholeArchiveWithoutGrantingEveryPreview()
    {
        await using var f=new Factory();using var admin=await f.Login("history-pages",true);var(student,course,handout,first)=await Seed(f);Guid lastId;
        using(var scope=f.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();db.WrongQuestions.Add(new WrongQuestion{StudentId=student.Id,QuestionId=first.Id});
            var more=Enumerable.Range(2,50).Select(n=>new Question{HandoutId=handout.Id,Order=n,Label="练"+n,BlocksJson=Json.Write(new[]{WordDocuments.Block(P(n==51?"最后一道独有关键词":"计算题"))}),PreviewsJson=Json.Write(new[]{"test/preview.png"})}).ToArray();lastId=more[^1].Id;db.Questions.AddRange(more);db.WrongQuestions.AddRange(more.Select(q=>new WrongQuestion{StudentId=student.Id,QuestionId=q.Id}));await db.SaveChangesAsync();
            await scope.ServiceProvider.GetRequiredService<IObjects>().Put("test/preview.png",new MemoryStream(Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXRcAAAAASUVORK5CYII=")),"image/png");
        }
        var url=$"/api/students/{student.Id}/history?courseId={course.Id}";var page1=await admin.GetFromJsonAsync<JsonElement>(url);Assert.Equal(51,page1.GetProperty("total").GetInt32());Assert.Equal(50,page1.GetProperty("items").GetArrayLength());Assert.True(page1.GetProperty("hasMore").GetBoolean());var page2=await admin.GetFromJsonAsync<JsonElement>(url+"&page=2");Assert.Equal(lastId,Assert.Single(page2.GetProperty("items").EnumerateArray()).GetProperty("questionId").GetGuid());
        var filtered=await admin.GetFromJsonAsync<JsonElement>(url+"&query="+Uri.EscapeDataString("独有关键词"));Assert.Equal(lastId,Assert.Single(filtered.GetProperty("items").EnumerateArray()).GetProperty("questionId").GetGuid());
        using(var scope=f.Services.CreateScope())Assert.Empty(await scope.ServiceProvider.GetRequiredService<Data>().Grants.ToListAsync());
        var detail=await admin.GetFromJsonAsync<JsonElement>($"/api/students/{student.Id}/history/{lastId}?courseId={course.Id}");Assert.Single(detail.GetProperty("previews").EnumerateArray());using(var scope=f.Services.CreateScope())Assert.Single(await scope.ServiceProvider.GetRequiredService<Data>().Grants.ToListAsync());
        Assert.Equal(HttpStatusCode.NotFound,(await admin.GetAsync($"/api/students/{student.Id}/history/{Guid.NewGuid()}?courseId={course.Id}")).StatusCode);
    }
    [Fact]
    public void PerLectureAnswersDoNotSwallowLaterLessonsAndAppendixAnswersRemainReferences()
    {
        var sections=BookStructure.Detect(new[]{P("第一讲 方程"),P("例1. x=1"),P("【参考答案】"),P("1. 答案"),P("第二讲 几何"),P("例1. 求面积"),P("第二讲：答案"),P("1. 答案"),P("第三讲 应用"),P("例1. 应用题"),P("参考答案"),P("第一讲"),P("例1. 答案"),P("第二讲"),P("例1. 答案")});
        Assert.Equal(new[]{1,2,3},sections.Where(x=>x.Kind=="lesson").Select(x=>x.Sequence));Assert.All(sections.Where(x=>x.Start>=10),x=>Assert.Equal("reference",x.Kind));
    }
    [Fact]
    public async Task AutoNumberedLectureTitlesAndContinuousQuestionsKeepTheirOriginalNumbersAfterSlicing()
    {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var path=Path.Combine(f.Root,"automatic-book.docx");
        XElement Numbered(string text,int id,int depth=0)=>new(W+"p",new XElement(W+"pPr",new XElement(W+"numPr",new XElement(W+"ilvl",new XAttribute(W+"val",depth)),new XElement(W+"numId",new XAttribute(W+"val",id)))),new XElement(W+"r",new XElement(W+"t",text)));
        using(var doc=WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)){
            var main=doc.AddMainDocumentPart();main.Document=new Document(new XElement(W+"document",new XElement(W+"body",Numbered("方程",1),Numbered("第一道",2),Numbered("第一题小问",2,1),Numbered("几何",1),Numbered("第二道",2),Numbered("第二题小问",2,1))).ToString());
            main.AddNewPart<NumberingDefinitionsPart>().Numbering=new Numbering($"<w:numbering xmlns:w='{W}'><w:abstractNum w:abstractNumId='1'><w:lvl w:ilvl='0'><w:start w:val='1'/><w:numFmt w:val='chineseCounting'/><w:lvlText w:val='第%1讲'/></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId='2'><w:lvl w:ilvl='0'><w:start w:val='1'/><w:numFmt w:val='decimal'/><w:lvlText w:val='%1.'/></w:lvl><w:lvl w:ilvl='1'><w:start w:val='1'/><w:numFmt w:val='decimal'/><w:lvlText w:val='%2.'/></w:lvl></w:abstractNum><w:num w:numId='1'><w:abstractNumId w:val='1'/></w:num><w:num w:numId='2'><w:abstractNumId w:val='2'/></w:num></w:numbering>");
        }
        BookNumbering.Normalize(path);BookNumbering.Normalize(path);var sections=BookStructure.Detect(BookStructure.Read(path));Assert.Equal(2,sections.Count(s=>s.Kind=="lesson"));
        var second=sections.Single(s=>s.Sequence==2);var slice=Path.Combine(f.Root,"second.docx");BookStructure.Slice(path,slice,second.Start,second.End);var parsed=await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(slice,Guid.NewGuid(),default);Assert.Equal("2.",Assert.Single(parsed).Label);Assert.Contains("1. 第二题小问",string.Join("\n",Json.Read<ContentBlock[]>(parsed[0].BlocksJson).Select(b=>b.Text)));
    }
    class AiFactory:Factory
    {
        protected override void ConfigureWebHost(IWebHostBuilder b){base.ConfigureWebHost(b);b.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{["LearningAi:Endpoint"]="https://generation.invalid/variants",["LearningAi:Protocol"]="structured"}));b.ConfigureServices(s=>s.AddHttpClient("learning-ai").ConfigurePrimaryHttpMessageHandler(()=>new Stub()));}
    }
    class Stub:HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage r,CancellationToken ct){var data=await r.Content!.ReadFromJsonAsync<JsonElement>(ct);var id=data.GetProperty("sources")[0].GetProperty("sourceQuestionId").GetGuid();Assert.False(data.TryGetProperty("studentName",out _));return new(HttpStatusCode.OK){Content=JsonContent.Create(new{items=new[]{new VariantQuestion(id,"解方程 3x+2=11。","答案不可出现在学生页：x=3","两边减2，再除以3。","一元一次方程","等式两边同变形","同等")}})};}
    }
}
