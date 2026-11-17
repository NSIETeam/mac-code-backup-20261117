using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class BookScaleTests
{
    static readonly XNamespace W = WordDocuments.W;
    static XElement P(string text) => new(W+"p",new XElement(W+"r",new XElement(W+"t",text)));
    static void Write(string path, IEnumerable<XElement> blocks) {
        using var d=WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document);
        d.AddMainDocumentPart().Document=new Document(new XElement(W+"document",new XElement(W+"body",blocks)).ToString());
    }
    static async Task<(Guid Class, Guid Course)> Seed(HttpClient admin) {
        var campus=await Factory.Post(admin,"/api/admin/catalog/campuses",new{name="测试校区"});
        var course=await Factory.Post(admin,"/api/admin/catalog/courses",new{name="数学"});
        var cl=await Factory.Post(admin,"/api/admin/catalog/classes",new{name="测试班级",campusId=campus.GetProperty("id").GetGuid()});
        return(cl.GetProperty("id").GetGuid(),course.GetProperty("id").GetGuid());
    }
    static async Task<Guid> Upload(Factory f, HttpClient admin, (Guid Class,Guid Course) ids, string path) {
        using var form=new MultipartFormDataContent();form.Add(new StreamContent(File.OpenRead(path)),"file",Path.GetFileName(path));
        var response=await admin.PostAsync($"/api/classes/{ids.Class}/books?courseId={ids.Course}",form);
        Assert.Equal(HttpStatusCode.Accepted,response.StatusCode);
        var id=(await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();await f.Run("book",id);return id;
    }
    static async Task<BookSection[]> Confirm(HttpClient admin, Guid id) {
        var d=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");
        var sections=d.GetProperty("sections").Deserialize<BookSection[]>(Json.Options)!;
        var result=await Factory.Post(admin,$"/api/books/{id}/confirm",new BookReview(0,sections));
        return result.GetProperty("sections").Deserialize<BookSection[]>(Json.Options)!;
    }
    [Theory]
    [InlineData("第１课时：方程",1)] [InlineData("【第十二节】方程",12)]
    [InlineData("讲次：35 方程",35)] [InlineData("第1001讲 方程",1001)] [InlineData("第一千零一讲 方程",1001)]
    public void DifferentLectureHeadingFormsAreRecognized(string heading,int sequence) {
        var section=Assert.Single(BookStructure.Detect([P(heading),P("例1 求值")]));
        Assert.Equal("lesson",section.Kind);Assert.Equal(sequence,section.Sequence);Assert.Equal("方程",section.Title);
    }
    [Fact] public void SubsectionsAndRunningHeadingsDoNotFragmentLectures() {
        var sections=BookStructure.Detect([P("第1讲 方程"),P("第一节 基础"),P("例1 求值"),P("第1讲 方程"),P("练1 应用"),P("第2讲 几何"),P("第一节 基础"),P("例1 求面积")]);
        Assert.Equal(2,sections.Length);Assert.Equal(5,sections[0].End);Assert.Equal(8,sections[1].End);
    }
    [Fact] public async Task SixtyUploadedFilesRemainAccessibleAndSingleHandoutsUseFilename() {
        await using var f=new Factory();using var admin=await f.Login("scale-many",true);var ids=await Seed(admin);var uploaded=new HashSet<Guid>();
        for(var n=1;n<=60;n++){
            var path=Path.Combine(f.Root,$"第{n}讲 课程{n}.docx");Write(path,[P("例1 计算 1+1"),P("练1 计算 2+2")]);
            var id=await Upload(f,admin,ids,path);uploaded.Add(id);
            var d=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");var section=Assert.Single(d.GetProperty("sections").EnumerateArray());
            Assert.Equal(n,section.GetProperty("sequence").GetInt32());Assert.Equal("lesson",section.GetProperty("kind").GetString());
        }
        var a=await admin.GetFromJsonAsync<JsonElement>($"/api/classes/{ids.Class}/books?courseId={ids.Course}");
        var b=await admin.GetFromJsonAsync<JsonElement>($"/api/classes/{ids.Class}/books?courseId={ids.Course}&offset=50");
        Assert.Equal(50,a.GetArrayLength());Assert.Equal(10,b.GetArrayLength());
        Assert.True(uploaded.SetEquals(a.EnumerateArray().Concat(b.EnumerateArray()).Select(x=>x.GetProperty("id").GetGuid())));
    }
    [Fact] public async Task SixHundredLecturesCanBeConfirmedAndBodyPagesCoverEveryParagraph() {
        await using var f=new Factory();using var admin=await f.Login("scale-large",true);var ids=await Seed(admin);
        var path=Path.Combine(f.Root,"六百讲.docx");Write(path,Enumerable.Range(1,600).SelectMany(n=>new[]{P($"第{n}讲 课程{n}"),P($"例1 第{n}讲独有题目")}));
        var id=await Upload(f,admin,ids,path);var d=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");
        Assert.Equal(600,d.GetProperty("sections").GetArrayLength());Assert.Equal(50,d.GetProperty("blocks").GetArrayLength());
        var seen=new List<int>();var offset=0;
        do {d=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}?blockOffset={offset}");seen.AddRange(d.GetProperty("blocks").EnumerateArray().Select(x=>x.GetProperty("index").GetInt32()));offset=d.GetProperty("blockNextOffset").GetInt32();}while(d.GetProperty("blockHasMore").GetBoolean());
        Assert.Equal(Enumerable.Range(0,1200),seen);
        var sections=await Confirm(admin,id);await Confirm(admin,id);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
        Assert.Equal(600,await db.Lectures.CountAsync());Assert.Equal(600,await db.Handouts.CountAsync());Assert.Equal(600,await db.Work.CountAsync(x=>x.Kind=="import"&&x.State=="waiting"));
        await f.Run("book-split",id);await f.Run("book-split",id);
        Assert.Equal(600,await db.Work.CountAsync(x=>x.Kind=="import"&&x.State=="queued"));
        // Check every generated slice, including identical question numbers across lectures.
        var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        foreach(var section in sections){
            var h=await db.Handouts.FindAsync(section.HandoutId!.Value);
            var slice=await word.Materialize(h!.OriginalKey,f.Root,"slice.docx",default);
            var question=Assert.Single(await word.Parse(slice,section.HandoutId!.Value,default));Assert.Contains($"第{section.Sequence}讲独有题目",string.Concat(Json.Read<ContentBlock[]>(question.BlocksJson).Select(b=>b.Text)));
        }
    }
    [Fact] public async Task RunningHeadingInsideLongQuestionDoesNotCreateAnotherQuestion() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var path=Path.Combine(f.Root,"跨页题.docx");
        Write(path,[P("第1讲 方程"),P("例1 长题第一部分"),P("第1讲 方程"),P("长题续页的第二部分"),P("练1 第二题")]);
        var questions=await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(path,Guid.NewGuid(),default);
        Assert.Equal(2,questions.Count);Assert.Contains("长题续页的第二部分",string.Concat(Json.Read<ContentBlock[]>(questions[0].BlocksJson).Select(b=>b.Text)));
    }
    [Fact] public async Task DifferentBooksWithSameNumbersPreserveSeparateLecturesAndSameTitleCreatesVersion() {
        await using var f=new Factory();using var admin=await f.Login("scale-versions",true);var ids=await Seed(admin);var path=Path.Combine(f.Root,"book.docx");var handouts=new List<Guid>();
        foreach(var title in new[]{"方程","几何","方程"}){
            Write(path,[P("第1讲 "+title),P("例1 "+title+"题目")]);var id=await Upload(f,admin,ids,path);handouts.Add((await Confirm(admin,id))[0].HandoutId!.Value);
        }
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(2,await db.Lectures.CountAsync());
        var a=await db.Handouts.FindAsync(handouts[0]);var b=await db.Handouts.FindAsync(handouts[1]);var c=await db.Handouts.FindAsync(handouts[2]);
        Assert.NotEqual(a!.LectureId,b!.LectureId);Assert.Equal(a.LectureId,c!.LectureId);Assert.Equal(1,a.Version);Assert.Equal(2,c.Version);
    }
}
