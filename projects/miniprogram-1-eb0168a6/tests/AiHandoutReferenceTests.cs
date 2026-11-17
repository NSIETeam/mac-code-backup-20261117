using System.Globalization;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using UglyToad.PdfPig.Writer;
using Xunit;

public class AiHandoutReferenceTests
{
    static void ReferenceDocx(string path, int page = 2, double top = .35, double bottom = .55) {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!); var w = WordDocuments.W; var sy = AiBookOutline.SourceNamespace;
        var body = new XElement(w + "body",
            new XElement(w + "p", new XElement(w + "pPr", new XElement(w + "pStyle", new XAttribute(w + "val", "SiyuePdfHeading"))), new XElement(w + "r", new XElement(w + "t", "第3讲 窗口验证"))),
            new XElement(w + "p", new XAttribute(sy + "page", page), new XAttribute(sy + "top", top.ToString("R", CultureInfo.InvariantCulture)), new XAttribute(sy + "bottom", bottom.ToString("R", CultureInfo.InvariantCulture)), new XElement(w + "r", new XElement(w + "t", $"原稿第{page}页"))));
        using var doc = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document);
        doc.AddMainDocumentPart().Document = new Document(new XElement(w + "document", body).ToString());
    }
    static byte[] SourcePdf() {
        var builder = new PdfDocumentBuilder(); var font = builder.AddStandard14Font(Standard14Font.Helvetica);
        builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText("OTHER PAGE", 12, new PdfPoint(50, 750), font);
        var page = builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        page.AddText("OUTSIDE TOP", 12, new PdfPoint(50, 750), font);
        page.AddText("1. CHOSEN QUESTION", 12, new PdfPoint(50, 500), font);
        page.AddText("INCLUDED SECOND LINE", 12, new PdfPoint(50, 460), font);
        page.AddText("OUTSIDE BOTTOM", 12, new PdfPoint(50, 100), font);
        builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText("UNSELECTED LAST PAGE", 12, new PdfPoint(50, 750), font);
        return builder.Build();
    }

    [Theory] [InlineData(false)] [InlineData(true)]
    public async Task ReferenceWindowUsesOriginalPageAndRealSourcePixelsThroughWorkerAndPreview(bool invalidModelPlan) {
        await using var f = new ReferenceFactory(invalidModelPlan); using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var course = new Course { Name = "本地范围验证" }; var lecture = new Lecture { CourseId = course.Id, Sequence = 3, Title = "窗口验证" };
        var handout = new Handout { LectureId = lecture.Id, State = "queued", RecognitionMode = QuestionRecognition.Auto, FileName = "reference.docx", OriginalKey = "reference/selected.docx", SourcePdfKey = "reference/original.pdf" };
        db.AddRange(course, lecture, handout); await db.SaveChangesAsync();
        var input = Path.Combine(f.Root, "reference.docx"); ReferenceDocx(input);
        var pdf = SourcePdf(); using (var stream = new MemoryStream(pdf)) await objects.Put(handout.SourcePdfKey, stream, "application/pdf");
        await using (var stream = File.OpenRead(input)) await objects.Put(handout.OriginalKey, stream, WordDocuments.DocxType);
        await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Process("import", handout.Id, default);
        Assert.Equal(1, f.Model.Calls); Assert.Equal(2, f.Model.LastPage);
        Assert.Contains("CHOSEN QUESTION", f.Model.TextLayer); Assert.Contains("INCLUDED SECOND LINE", f.Model.TextLayer);
        Assert.DoesNotContain("OUTSIDE", f.Model.TextLayer); Assert.DoesNotContain("OTHER PAGE", f.Model.TextLayer);
        Assert.InRange(f.Model.ImageHeight, 400u, 440u); Assert.InRange(f.Model.ImageWidth, 1400u, 1600u);
        db.ChangeTracker.Clear(); var saved = (await db.Handouts.FindAsync(handout.Id))!;
        Assert.Equal("reference/original.pdf", saved.SourcePdfKey); Assert.Equal("draft", saved.State); Assert.Null(saved.Error);
        var question = Assert.Single(db.Questions.Where(q => q.HandoutId == handout.Id)); Assert.False(question.Confirmed);
        var block = Assert.Single(Json.Read<ContentBlock[]>(question.BlocksJson)); var xml = XElement.Parse(block.Xml); var origin = WordDocuments.PdfOrigin(xml)!;
        Assert.Equal(2, origin.Page); Assert.Equal(.35, origin.Bounds[1], 5); Assert.Equal(.55, origin.Bounds[3], 5);
        Assert.Equal(.35, PdfRegions.Bounds(xml)[1], 5); Assert.Equal(.55, PdfRegions.Bounds(xml)[3], 5);
        if (invalidModelPlan) Assert.Contains(Json.Read<Issue[]>(question.IssuesJson), issue => issue.Code == "pdf-page-review" && issue.Blocking);
        else {
            Assert.Equal("1", question.Label); Assert.DoesNotContain(Json.Read<Issue[]>(question.IssuesJson), issue => issue.Blocking);
            Assert.Contains("CHOSEN QUESTION", block.Text); Assert.DoesNotContain("OUTSIDE", block.Text);
        }
        var key = Assert.Single(Json.Read<string[]>(question.PreviewsJson)); await using var preview = await objects.Get(key); using var bytes = new MemoryStream(); await preview.CopyToAsync(bytes);
        var png = bytes.ToArray(); Assert.True(png.Length > 100); var height = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(png.AsSpan(20, 4));
        Assert.InRange(height, 200u, 400u); // An uncropped full page would be about 1300 px tall.
        await using var original = await objects.Get(saved.SourcePdfKey); using var copy = new MemoryStream(); await original.CopyToAsync(copy); Assert.Equal(pdf, copy.ToArray());
        if (Environment.GetEnvironmentVariable("SIYUE_AI_REFERENCE_OUTPUT") is string output) {
            Directory.CreateDirectory(output); var prefix = invalidModelPlan ? "retained-window" : "question-window";
            await File.WriteAllBytesAsync(Path.Combine(output, prefix + ".png"), png); await File.WriteAllBytesAsync(Path.Combine(output, "source.pdf"), pdf);
            await File.WriteAllTextAsync(Path.Combine(output, prefix + ".json"), Json.Write(new { origin, question.Label, issues = Json.Read<Issue[]>(question.IssuesJson), modelPage = f.Model.LastPage, modelImageWidth = f.Model.ImageWidth, modelImageHeight = f.Model.ImageHeight }));
        }
    }
    [Fact] public async Task ModelStrategyReferencesWithoutEnabledModelFailInsteadOfImportingPlaceholderText() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        scope.ServiceProvider.GetRequiredService<IConfiguration>()["Documents:ParsingStrategy"] = "model-assisted";
        scope.ServiceProvider.GetRequiredService<IConfiguration>()["LearningAi:Enabled"] = "false";
        var path = Path.Combine(f.Root, "disabled-reference.docx"); ReferenceDocx(path); using var mode = QuestionRecognition.Use(QuestionRecognition.Auto);
        var error = await Assert.ThrowsAsync<ApiException>(() => word.NormalizeHandout(path, f.Root, default)); Assert.Equal(503, error.Status);
    }
    [Fact] public async Task ReferencesCannotChooseAnOriginalFileFromTheirOwnXml() {
        await using var f = new ReferenceFactory(false); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "missing-reference-source.docx"); ReferenceDocx(path); using var mode = QuestionRecognition.Use(QuestionRecognition.Auto);
        var error = await Assert.ThrowsAsync<ApiException>(() => word.NormalizeHandout(path, f.Root, default)); Assert.Equal(422, error.Status); Assert.Equal(0, f.Model.Calls);
    }
    [Theory] [InlineData(QuestionRecognition.Auto)] [InlineData(QuestionRecognition.Named)] [InlineData(QuestionRecognition.Legacy)]
    public async Task FixedStrategyRetainsReferenceWindowWithNoModelRequests(string recognitionMode) {
        await using var f=new ReferenceFactory(false,true);using var scope=f.Services.CreateScope();
        var db=scope.ServiceProvider.GetRequiredService<Data>();var objects=scope.ServiceProvider.GetRequiredService<IObjects>();
        // Neither legacy protected model setting may be read in fixed parsing mode.
        db.LearningModelConfigurations.Add(new(){Id=LearningModelSettings.ConfigurationId,ProtectedJson="invalid-protected-model-settings"});
        db.LearningModelConfigurations.Add(new(){Id=FormulaSettings.Id,ProtectedJson="invalid-protected-formula-settings"});
        var course=new Course{Name="固定规则验证"};var lecture=new Lecture{CourseId=course.Id,Sequence=3,Title="窗口验证"};
        var handout=new Handout{LectureId=lecture.Id,State="queued",RecognitionMode=recognitionMode,FileName="reference.docx",OriginalKey="fixed/selected.docx",SourcePdfKey="fixed/original.pdf"};
        db.AddRange(course,lecture,handout);await db.SaveChangesAsync();
        var input=Path.Combine(f.Root,"fixed-reference.docx");ReferenceDocx(input);var pdf=SourcePdf();
        using(var stream=new MemoryStream(pdf))await objects.Put(handout.SourcePdfKey,stream,"application/pdf");
        await using(var stream=File.OpenRead(input))await objects.Put(handout.OriginalKey,stream,WordDocuments.DocxType);
        await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Process("import",handout.Id,default);
        Assert.Equal(0,f.Model.Calls);db.ChangeTracker.Clear();var saved=(await db.Handouts.FindAsync(handout.Id))!;
        Assert.Equal("draft",saved.State);Assert.Equal("fixed/original.pdf",saved.SourcePdfKey);Assert.Null(saved.Error);
        var question=Assert.Single(db.Questions.Where(q=>q.HandoutId==handout.Id));Assert.False(question.Confirmed);
        var block=Assert.Single(Json.Read<ContentBlock[]>(question.BlocksJson));var xml=XElement.Parse(block.Xml);var origin=WordDocuments.PdfOrigin(xml)!;
        Assert.Equal("fixed-reference",origin.Engine);Assert.Equal(2,origin.Page);
        Assert.Equal(.35,origin.Bounds[1],5);Assert.Equal(.55,origin.Bounds[3],5);
        Assert.Equal(.35,PdfRegions.Bounds(xml)[1],5);Assert.Equal(.55,PdfRegions.Bounds(xml)[3],5);
        Assert.Contains(Json.Read<Issue[]>(question.IssuesJson),i=>i.Code=="pdf-page-review"&&i.Blocking);
        Assert.DoesNotContain("原稿第2页",block.Text);Assert.DoesNotContain("OUTSIDE",block.Text);
        var key=Assert.Single(Json.Read<string[]>(question.PreviewsJson));await using var preview=await objects.Get(key);using var bytes=new MemoryStream();await preview.CopyToAsync(bytes);
        Assert.InRange(System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(bytes.ToArray().AsSpan(20,4)),200u,400u);
        await using var original=await objects.Get(saved.SourcePdfKey);using var copy=new MemoryStream();await original.CopyToAsync(copy);Assert.Equal(pdf,copy.ToArray());
    }
    [Fact] public async Task FixedReferencesRequireTrustedOriginalSource() {
        await using var f=new ReferenceFactory(false,true);using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path=Path.Combine(f.Root,"fixed-no-source.docx");ReferenceDocx(path);using var mode=QuestionRecognition.Use(QuestionRecognition.Auto);
        var error=await Assert.ThrowsAsync<ApiException>(()=>word.NormalizeHandout(path,f.Root,default));Assert.Equal(422,error.Status);Assert.Equal(0,f.Model.Calls);
    }
    sealed class ReferenceFactory(bool invalid,bool fixedParsing=false) : Factory {
        public LayoutReply Model { get; } = new(invalid);
        protected override void ConfigureWebHost(IWebHostBuilder builder) {
            base.ConfigureWebHost(builder);
            builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(new Dictionary<string, string?> {
                ["Documents:ParsingStrategy"] = fixedParsing?"fixed":"model-assisted",
                ["LearningAi:Enabled"] = "true", ["LearningAi:Vision"] = "true", ["LearningAi:Protocol"] = "chat-completions",
                ["LearningAi:Model"] = "offline-layout-fixture", ["LearningAi:ApiKey"] = "offline-fixture-value", ["LearningAi:Endpoint"] = "https://offline.invalid/v1/chat/completions",
                ["Documents:Soffice"] = "unavailable-to-prove-pdf-source-path", ["Documents:AiHandoutMaxPages"] = "5"
            }));
            builder.ConfigureServices(services => services.AddHttpClient("learning-ai").ConfigurePrimaryHttpMessageHandler(() => Model));
        }
    }
    sealed class LayoutReply(bool invalid) : HttpMessageHandler {
        public int Calls { get; private set; } public int LastPage { get; private set; } public string TextLayer { get; private set; } = "";
        public uint ImageWidth { get; private set; } public uint ImageHeight { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Calls++; var body = await request.Content!.ReadFromJsonAsync<JsonElement>(ct); var content = body.GetProperty("messages")[1].GetProperty("content");
            using var context = JsonDocument.Parse(content[0].GetProperty("text").GetString()!); LastPage = context.RootElement.GetProperty("pageNumber").GetInt32(); TextLayer = context.RootElement.GetProperty("textLayer").GetString()!;
            var uri = content[1].GetProperty("image_url").GetProperty("url").GetString()!; var png = Convert.FromBase64String(uri[(uri.IndexOf(',') + 1)..]);
            ImageWidth = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(png.AsSpan(16, 4)); ImageHeight = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(png.AsSpan(20, 4));
            var plan = new AiHandoutPage([new("question", "1", invalid ? [-1, 0, 1, 1] : [0, 0, 1, 1], .99)], false);
            return new(HttpStatusCode.OK) { Content = JsonContent.Create(new { choices = new[] { new { finish_reason = "stop", message = new { content = Json.Write(plan) } } } }) };
        }
    }
}
