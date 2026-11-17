using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Writer;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using Xunit;

public class PdfHandoutTests
{
    [Theory]
    [InlineData("【1】 数列", true)]
    [InlineData("测1 计算", true)]
    [InlineData("【测 ２】 比较大小", true)]
    [InlineData("【练 1-2】 分解因式", true)]
    [InlineData("【练 ２－３】 求值", true)]
    [InlineData("【例 2】 阅读材料", true)]
    [InlineData("第 12 题：计算", true)]
    [InlineData("题３ 证明", true)]
    [InlineData("习题 2：求值", true)]
    [InlineData("（1）解释原因", false)]
    [InlineData("模块一 等差数列 例1", false)]
    [InlineData("【题型 1：二次六项式】", false)]
    public void BracketedAndCompoundLabelsAreTopLevelOnly(string text, bool expected) => Assert.Equal(expected, WordDocuments.IsQuestionStart(text));

    [Fact] public async Task FigureInWhitespaceMovesBoundaryWithoutClippingOrMergingQuestions() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var builder=new PdfDocumentBuilder();var page=builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);var font=builder.AddStandard14Font(Standard14Font.Helvetica);
        page.AddText("1. Find the area of the figure below.",12,new PdfPoint(50,750),font);
        page.DrawRectangle(new PdfPoint(50,510),180,200);
        page.AddText("2. A separate question.",12,new PdfPoint(50,450),font);
        var source=Path.Combine(f.Root,"figure-gap.pdf");File.WriteAllBytes(source,builder.Build());
        var normalized=await word.NormalizePdfHandout(source,f.Root,default);
        var questions=await word.Parse(normalized,Guid.NewGuid(),default);
        Assert.Equal(new[]{"1.","2."},questions.Select(q=>q.Label));
        var first=XElement.Parse(Json.Read<ContentBlock[]>(questions[0].BlocksJson).Single().Xml);
        var extent=first.Descendants().Single(x=>x.Name.LocalName=="extent");
        Assert.True((long)extent.Attribute("cy")! > 240*12700, "The full diagram below question 1 must remain in its crop");
    }
    static readonly XNamespace W = WordDocuments.W;
    static readonly string Qa = Path.GetFullPath(".local/acceptance/pdf-handouts");
    static XElement P(string text) => new(W + "p", new XElement(W + "r", new XElement(W + "t", text)));
    static async Task<string> Source(Factory f, WordDocuments word) {
        var path = Path.Combine(f.Root, "两讲公式图片.docx");
        File.Copy(Path.Combine(AppContext.BaseDirectory,"fixtures","booklet-layout-source.docx"),path);
        using (var pack = WordprocessingDocument.Open(path,true)) {
            var main=pack.MainDocumentPart!; var root=XElement.Parse(main.Document!.OuterXml);var body=root.Element(W+"body")!;
            var questions=body.Element(W+"tbl")!.Elements(W+"tr").Select(r=>r.Elements(W+"tc").Last().Elements(W+"p").TakeWhile(p=>WordDocuments.Block(p).Text!="解题过程").Select(p=>new XElement(p)).ToArray()).ToArray();
            var section=new XElement(body.Element(W+"sectPr")!);body.RemoveNodes();body.Add(P("第1讲 方程与图形"));body.Add(questions[0]);body.Add(questions[1]);body.Add(questions[2]);
            body.Add(new XElement(W+"p",new XElement(W+"r",new XElement(W+"br",new XAttribute(W+"type","page")))));
            body.Add(P("第2讲 长题训练"),P("例1 请阅读下列条件并解答。"));
            for(var i=1;i<=70;i++)body.Add(P($"条件{i:D2}：保留题目条件与图形，检查跨页内容是否完整。"));
            body.Add(P("练1 计算 2+3。"),section);main.Document=new Document(root.ToString());main.Document.Save();
        }
        var pdf=await word.Convert(path,"pdf",f.Root,default);Directory.CreateDirectory(Qa);File.Copy(pdf,Path.Combine(Qa,"source.pdf"),true);return pdf;
    }
    [Fact] public async Task PdfBookSplitsLecturesPreservesVisualsAndExportsBooklet() {
        await using var f=new Factory();using var admin=await f.Login("pdf-admin",true);using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var campus=new Campus{Name="测试"};var cl=new SchoolClass{Name="测试班",CampusId=campus.Id};var course=new Course{Name="数学"};db.AddRange(campus,cl,course);await db.SaveChangesAsync();
        var pdf=await Source(f,word);
        using(var probe=PdfDocument.Open(pdf)) File.WriteAllText(Path.Combine(Qa,"geometry.json"),Json.Write(probe.GetPages().Select(p=>new {p.Number,p.Height,letters=p.Letters.TakeLast(5).Select(l=>new{l.Value,l.BoundingBox,l.StartBaseLine,l.Width}),paths=p.Paths.Select(x=>x.GetBoundingRectangle())})));

        using var form=new MultipartFormDataContent();form.Add(new StreamContent(File.OpenRead(pdf)),"file","两讲合订.PDF");
        using var response=await admin.PostAsync($"/api/classes/{cl.Id}/books?courseId={course.Id}",form);Assert.Equal(HttpStatusCode.Accepted,response.StatusCode);
        var id=(await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();await f.Run("book",id);
        var detail=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");var sections=detail.GetProperty("sections").Deserialize<BookSection[]>(Json.Options)!;
        Assert.Equal(2,sections.Length);Assert.All(sections,s=>Assert.Equal("lesson",s.Kind));Assert.Equal(new[]{1,2},sections.Select(s=>s.Sequence));
        await Factory.Post(admin,$"/api/books/{id}/confirm",new BookReview(0,sections));await f.Run("book-split",id);
        var handouts=await db.Handouts.OrderBy(h=>h.CreatedAt).ToArrayAsync();Assert.Equal(2,handouts.Length);
        var snapshots=new List<QuestionSnapshot>();
        foreach(var h in handouts) {
            await f.Run("import",h.Id);db.ChangeTracker.Clear();var questions=await db.Questions.Where(q=>q.HandoutId==h.Id).OrderBy(q=>q.Order).ToArrayAsync();
            Assert.Contains(questions,q=>q.Label=="例1");Assert.All(questions,q=>{Assert.NotEmpty(Json.Read<string[]>(q.PreviewsJson));Assert.Contains(Json.Read<Issue[]>(q.IssuesJson),i=>i.Code=="pdf-layout"&&!i.Blocking);});
            foreach(var q in questions)snapshots.Add(new(q.Id,h.Id,q.Label,"第1—2讲",q.BlocksJson,q.HalfPage));
        }
        Assert.Equal(5,snapshots.Count);
        foreach(var q in snapshots.Where(q=>!Json.Read<ContentBlock[]>(q.BlocksJson).Any(b=>b.Text.Contains("条件")))) {
            var blocks=Json.Read<ContentBlock[]>(q.BlocksJson);var extents=blocks.SelectMany(b=>XElement.Parse(b.Xml).Descendants().Where(x=>x.Name.LocalName=="extent"));
            Assert.All(extents,e=>Assert.InRange((long)e.Attribute("cy")!,1,2_000_000));
        }
        Assert.Contains("条件70",System.Text.RegularExpressions.Regex.Replace(string.Concat(snapshots.SelectMany(s=>Json.Read<ContentBlock[]>(s.BlocksJson)).Select(b=>b.Text)), @"\s+", ""));
        var export=new ExportDocument{StudentName="PDF 验收学生",SnapshotJson=Json.Write(snapshots)};
        var (docx,booklet)=await word.BookletPdf(export,f.Root,default);File.Copy(booklet,Path.Combine(Qa,"booklet.pdf"),true);File.Copy(docx,Path.Combine(Qa,"booklet.docx"),true);
        using var rendered=PdfDocument.Open(booklet);Assert.All(rendered.GetPages(),p=>Assert.InRange(p.Width,590,600));Assert.True(rendered.GetPages().Sum(p=>p.GetImages().Count())>=5);
        using var output=WordprocessingDocument.Open(docx,false);var paragraphs=output.MainDocumentPart!.Document!.Descendants<Paragraph>();Assert.DoesNotContain(paragraphs,p=>p.Elements<Run>().Any(r=>r.RunProperties?.Vanish==null && r.InnerText=="例1 "));
        // Original PDF download retains its MIME and remains scoped to the upload's class.
        var grant=await admin.GetAsync(detail.GetProperty("sourceUrl").GetString());Assert.Equal("application/pdf",grant.Content.Headers.ContentType?.MediaType);
        using var outsider=await f.Login("pdf-outsider");Assert.False((await outsider.GetAsync($"/api/books/{id}")).IsSuccessStatusCode);
    }
    static string Simple(Factory f, bool scan=false, bool columns=false) {
        var builder=new PdfDocumentBuilder();var page=builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);var font=builder.AddStandard14Font(Standard14Font.Helvetica);
        if(!scan){page.AddText("1. Calculate 2 + 2.",12,new PdfPoint(50,750),font);page.AddText("(1) Explain the answer.",12,new PdfPoint(65,720),font);page.AddText("2. Calculate 3 + 3.",12,new PdfPoint(columns?330:50,columns?750:650),font);}
        else page.DrawRectangle(new PdfPoint(50,600),400,100);
        var path=Path.Combine(f.Root,"source.pdf");File.WriteAllBytes(path,builder.Build());return path;
    }
    [Fact] public async Task NumericPdfDirectHandoutSupportsPreviewAndPreservesSource() {
        await using var f=new Factory();using var admin=await f.Login("pdf-direct",true);using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
        var campus=new Campus{Name="校区"};var cl=new SchoolClass{Name="班级",CampusId=campus.Id};var course=new Course{Name="课程"};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id,Sequence=1,Title="练习"};db.AddRange(campus,cl,course,lecture);await db.SaveChangesAsync();
        var path=Simple(f);using var form=new MultipartFormDataContent();form.Add(new StringContent("legacy"),"recognitionMode");form.Add(new StreamContent(File.OpenRead(path)),"file","练习.pdf");var r=await admin.PostAsync($"/api/lectures/{lecture.Id}/handouts",form);Assert.Equal(HttpStatusCode.Accepted,r.StatusCode);
        var id=(await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();await f.Run("import",id);var h=await db.Handouts.FindAsync(id);Assert.Equal("draft",h!.State);var q=await db.Questions.Where(x=>x.HandoutId==id).ToArrayAsync();Assert.Equal(2,q.Length);Assert.Contains(q,x=>x.Label=="1.");
        await using var stream=await scope.ServiceProvider.GetRequiredService<IObjects>().Get(h.SourcePdfKey);using var bytes=new MemoryStream();await stream.CopyToAsync(bytes);Assert.Equal(File.ReadAllBytes(path),bytes.ToArray());
    }
    [Fact] public async Task ClearColumnsAreSplitInReadingOrderAndRequireSourceReview() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path=await word.NormalizePdfHandout(Simple(f,columns:true),f.Root,default);
        var questions=await word.Parse(path,Guid.NewGuid(),default);
        Assert.Equal(new[]{"1.","2."},questions.Select(q=>q.Label));
        Assert.All(questions,q=>Assert.Contains(Json.Read<Issue[]>(q.IssuesJson), i=>i.Blocking && i.Code=="pdf-source-review"));
        Assert.All(questions,q=>Assert.NotEmpty(WordDocuments.ImageReferences(Json.Read<ContentBlock[]>(q.BlocksJson)[0].Xml)));
    }
    [Fact] public async Task ScansAndDamagedFilesGiveActionableErrors() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var scan=Simple(f,true);var normalized=await word.NormalizePdfHandout(scan,f.Root,default);
        var question=Assert.Single(await word.Parse(normalized,Guid.NewGuid(),default));
        Assert.Contains(Json.Read<Issue[]>(question.IssuesJson), i=>i.Blocking && i.Message.Contains("文字层"));
        File.WriteAllText(scan,"not a PDF");var e=await Assert.ThrowsAsync<ApiException>(()=>word.NormalizePdfHandout(scan,f.Root,default));Assert.Contains("损坏",e.Message);
    }
    [Fact] public async Task FailedMiddlePageRetainsLaterQuestionsAndDoesNotDuplicateEarlierCrops() {
        await using var f=new Factory(); using var scope=f.Services.CreateScope(); var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var builder=new PdfDocumentBuilder(); var font=builder.AddStandard14Font(Standard14Font.Helvetica);
        builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText("1. Calculate 2+2.",12,new PdfPoint(50,750),font);
        var bad=builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        bad.AddText("2. Bad boundary.",12,new PdfPoint(50,750),font);
        bad.DrawRectangle(new PdfPoint(40,600),500,180);
        bad.AddText("3. Intersecting figure.",12,new PdfPoint(50,690),font);
        builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText("4. Calculate 4+4.",12,new PdfPoint(50,750),font);
        var input=Path.Combine(f.Root,"partial.pdf"); File.WriteAllBytes(input,builder.Build());
        for(var attempt=0;attempt<2;attempt++) {
            var result=await word.NormalizePdfHandout(input,f.Root,default);
            var questions=await word.Parse(result,Guid.NewGuid(),default);
            Assert.Equal(3,questions.Count); Assert.Equal("1.",questions[0].Label);Assert.Equal("4.",questions[2].Label);
            Assert.Equal("2.",questions[1].Label);
            Assert.Contains(Json.Read<Issue[]>(questions[1].IssuesJson),i=>i.Code=="pdf-merged-region"&&i.Blocking);
            Assert.Contains("3. Intersecting",Json.Read<ContentBlock[]>(questions[1].BlocksJson).Single().Text);
            using var doc=WordprocessingDocument.Open(result,false);Assert.Equal(3,doc.MainDocumentPart!.ImageParts.Count());
        }
    }
    [Fact] public async Task LetterheadOnlyPageDoesNotStopSubsequentQuestions() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var builder=new PdfDocumentBuilder();var font=builder.AddStandard14Font(Standard14Font.Helvetica);
        var empty=builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);empty.AddText("Siyue",12,new PdfPoint(50,800),font);empty.DrawLine(new PdfPoint(50,780),new PdfPoint(550,780));
        builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText("1. Calculate 2+2.",12,new PdfPoint(50,750),font);
        var input=Path.Combine(f.Root,"letterhead.pdf");File.WriteAllBytes(input,builder.Build());
        var result=await word.NormalizePdfHandout(input,f.Root,default);var q=Assert.Single(await word.Parse(result,Guid.NewGuid(),default));
        Assert.Equal("1.",q.Label);Assert.DoesNotContain(Json.Read<Issue[]>(q.IssuesJson),i=>i.Blocking);
    }

}
