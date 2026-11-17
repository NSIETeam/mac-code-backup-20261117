using System.Xml.Linq;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using UglyToad.PdfPig.Writer;
using Xunit;

public class PdfFixedRecoveryTests
{
    const string BlackPng = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAADElEQVR4nGNgIB0AAAA0AAF2Xq7DAAAAAElFTkSuQmCC";
    static readonly XNamespace W = WordDocuments.W;
    static readonly XNamespace WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static readonly XNamespace A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static ContentBlock[] Blocks(Question question) => Json.Read<ContentBlock[]>(question.BlocksJson);
    static Issue[] Issues(Question question) => Json.Read<Issue[]>(question.IssuesJson);

    static string ForbidOcr(Factory factory, IConfiguration config)
    {
        config["Documents:ParsingStrategy"] = "fixed";
        var flag = Path.Combine(factory.Root, "ocr-was-called");
        var executable = Path.Combine(factory.Root, "forbidden-ocr.sh");
        // A failing executable records attempts, unlike a nonexistent executable
        // whose failure could be swallowed by a recovery branch.
        File.WriteAllText(executable, "#!/bin/sh\nprintf called > '" + flag.Replace("'", "'\\''") + "'\nexit 91\n");
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(executable, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        config["Documents:OcrPython"] = executable;
        config["Documents:Tesseract"] = executable;
        return flag;
    }

    static string Pdf(Factory factory, PdfDocumentBuilder document, string name)
    {
        var path = Path.Combine(factory.Root, name + ".pdf");
        File.WriteAllBytes(path, document.Build());
        return path;
    }

    [Fact]
    public async Task NativeColumnsUseFixedRecoveryWithoutLaunchingAnyOcr()
    {
        await using var factory = new Factory(); using var scope = factory.Services.CreateScope();
        var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var flag = ForbidOcr(factory, scope.ServiceProvider.GetRequiredService<IConfiguration>());
        var document = new PdfDocumentBuilder(); var font = document.AddStandard14Font(Standard14Font.Helvetica);
        var page = document.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        page.AddText("1. Left first question.", 12, new PdfPoint(45, 750), font);
        page.DrawRectangle(new PdfPoint(45, 570), 180, 140);
        page.AddText("2. Left second question.", 12, new PdfPoint(45, 420), font);
        page.AddText("3. Right first question.", 12, new PdfPoint(330, 700), font);
        page.AddText("4. Right second question.", 12, new PdfPoint(330, 350), font);
        using var mode = QuestionRecognition.Use("legacy");
        var normalized = await word.NormalizePdfHandout(Pdf(factory, document, "native-columns"), factory.Root, default);
        var questions = await word.Parse(normalized, Guid.NewGuid(), default);
        Assert.Equal(new[] { "1.", "2.", "3.", "4." }, questions.Select(q => q.Label));
        Assert.All(questions, q => Assert.Single(WordDocuments.ImageReferences(Blocks(q).Single().Xml)));
        Assert.False(File.Exists(flag), "A fixed parsing fallback launched an OCR executable.");
    }

    [Fact]
    public async Task ScanWithoutTextIsRetainedForManualRegionsWithoutOcr()
    {
        await using var factory = new Factory(); using var scope = factory.Services.CreateScope();
        var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var flag = ForbidOcr(factory, scope.ServiceProvider.GetRequiredService<IConfiguration>());
        var document = new PdfDocumentBuilder(); var page = document.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        page.AddPng(Convert.FromBase64String(BlackPng), new PdfRectangle(0, 0, 595, 842));
        var source = Pdf(factory, document, "scan"); var original = File.ReadAllBytes(source);
        using var mode = QuestionRecognition.Use("named");
        var normalized = await word.NormalizePdfHandout(source, factory.Root, default);
        var question = Assert.Single(await word.Parse(normalized, Guid.NewGuid(), default));
        Assert.Contains(Issues(question), issue => issue.Code == "pdf-page-review" && issue.Blocking);
        var block = Assert.Single(Blocks(question));
        Assert.Single(WordDocuments.ImageReferences(block.Xml));
        Assert.Equal("retained-page", WordDocuments.PdfOrigin(XElement.Parse(block.Xml))!.Engine);
        Assert.Equal(original, File.ReadAllBytes(source));
        Assert.False(File.Exists(flag), "A scanned page launched an OCR executable.");
    }

    [Fact]
    public async Task PictureHeadingIsRetainedWithoutSmallRegionOcr()
    {
        await using var factory = new Factory(); using var scope = factory.Services.CreateScope();
        var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var flag = ForbidOcr(factory, scope.ServiceProvider.GetRequiredService<IConfiguration>());
        var document = new PdfDocumentBuilder(); var font = document.AddStandard14Font(Standard14Font.Helvetica);
        var page = document.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        page.AddPng(Convert.FromBase64String(BlackPng), new PdfRectangle(150, 742, 270, 762));
        page.AddText("1. Question below an image heading.", 12, new PdfPoint(45, 650), font);
        page.AddText("2. Another complete question.", 12, new PdfPoint(45, 450), font);
        using var mode = QuestionRecognition.Use("legacy");
        var normalized = await word.NormalizePdfHandout(Pdf(factory, document, "picture-heading"), factory.Root, default);
        var questions = await word.Parse(normalized, Guid.NewGuid(), default);
        Assert.Contains(questions.SelectMany(Blocks), block => WordDocuments.PdfOrigin(XElement.Parse(block.Xml)) is { } origin && origin.Bounds[1] < .12);
        Assert.False(File.Exists(flag), "A picture heading launched small-region OCR.");
    }

    [Fact]
    public async Task UnknownInkInTopMarginCannotDisappearFromFixedCrops()
    {
        await using var factory = new Factory(); using var scope = factory.Services.CreateScope();
        var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var flag = ForbidOcr(factory, scope.ServiceProvider.GetRequiredService<IConfiguration>());
        var document = new PdfDocumentBuilder(); var font = document.AddStandard14Font(Standard14Font.Helvetica);
        var page = document.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        // This source image lies entirely inside the old blanket 74pt top margin.
        page.AddPng(Convert.FromBase64String(BlackPng), new PdfRectangle(30, 812, 90, 832));
        page.AddText("1. Body question with a detached source image.", 12, new PdfPoint(45, 650), font);
        using var mode = QuestionRecognition.Use("legacy");
        var normalized = await word.NormalizePdfHandout(Pdf(factory, document, "top-margin-ink"), factory.Root, default);
        var questions = await word.Parse(normalized, Guid.NewGuid(), default);
        Assert.Contains(questions.SelectMany(Blocks), block => WordDocuments.PdfOrigin(XElement.Parse(block.Xml)) is { } origin && origin.Bounds[0] <= 30 / 595d && origin.Bounds[1] <= 10 / 842d && origin.Bounds[2] >= 90 / 595d && origin.Bounds[3] >= 30 / 842d);
        Assert.Contains(questions.SelectMany(Issues), issue => issue.Blocking && issue.Code is "pdf-orphan-prefix" or "pdf-page-review");
        Assert.False(File.Exists(flag));
    }

    static XElement Raster(string text, params string[] checks)
    {
        var origin = new PdfProvenance(1, "poppler-regions", [0, 0, 1, .3], checks);
        return new XElement(W + "p", new XElement(W + "r", new XElement(W + "t", text)),
            new XElement(W + "r", new XElement(W + "drawing", new XElement(WP + "inline",
                new XElement(WP + "extent", new XAttribute("cx", 4800000), new XAttribute("cy", 1600000)),
                new XElement(WP + "docPr", new XAttribute("id", 1), new XAttribute("name", "SiyuePDF1"), new XAttribute("descr", "continuation"), new XAttribute("title", "siyue-pdf:" + Json.Write(origin))),
                new XElement(A + "blip", new XAttribute(WordDocuments.R + "embed", "sourceImage"))))));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task NamedParsingRetainsUnassignedPdfPixelsAndTheirBlockingReview(bool explicitOrphan)
    {
        await using var factory = new Factory(); using var scope = factory.Services.CreateScope();
        var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(factory.Root, "unassigned-pdf.docx");
        using (var doc = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document)) {
            var main = doc.AddMainDocumentPart(); var image = main.AddImagePart(ImagePartType.Png, "sourceImage");
            using (var bytes = new MemoryStream(Convert.FromBase64String(BlackPng))) image.FeedData(bytes);
            var region = Raster("未确定归属的条件及图形", explicitOrphan ? ["orphan-prefix"] : []);
            var example = new XElement(W + "p", new XElement(W + "r", new XElement(W + "t", "例1 后续独立题目")));
            main.Document = new Document(new Body(new XElement(W + "body", region, example).ToString(SaveOptions.DisableFormatting))); main.Document.Save();
        }
        using var mode = QuestionRecognition.Use("named");
        var questions = await word.Parse(path, Guid.NewGuid(), default);
        Assert.Equal(2, questions.Count); Assert.Equal("例1", questions[1].Label);
        Assert.Contains("未确定归属", string.Concat(Blocks(questions[0]).Select(block=>block.Text)));
        Assert.Single(WordDocuments.ImageReferences(Blocks(questions[0]).Single().Xml));
        Assert.Contains(Issues(questions[0]), issue => issue.Code == "pdf-orphan-prefix" && issue.Blocking);
        WordDocuments.RefreshPdfContentIssues(questions[0]);
        Assert.Contains(Issues(questions[0]), issue => issue.Code == "pdf-orphan-prefix" && issue.Blocking);
    }
}
