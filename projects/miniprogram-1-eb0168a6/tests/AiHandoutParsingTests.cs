using System.Xml.Linq;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class AiHandoutParsingTests
{
    static readonly XNamespace W = WordDocuments.W;
    static readonly XNamespace WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static readonly XNamespace A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static XElement Region(int page, int index, string? label, string text = "", string description = "question", string? continuation = null, params string[] extraChecks) {
        var checks = new List<string> { "ai-verified", "ai-id:" + AiHandoutLayout.QuestionId(page, index) };
        if (continuation != null) checks.Add("ai-continuation:" + continuation);
        checks.AddRange(extraChecks);
        var origin = new PdfProvenance(page, "ai-layout", [0, 0, 1, 1], checks.ToArray(), label);
        return new XElement(W + "p", new XElement(W + "r", new XElement(W + "t", text)),
            new XElement(W + "r", new XElement(W + "drawing", new XElement(WP + "inline",
                new XElement(WP + "extent", new XAttribute("cx", 4800000), new XAttribute("cy", 1600000)),
                new XElement(WP + "docPr", new XAttribute("id", page * 100 + index), new XAttribute("name", "SiyuePDF" + page + "-" + index), new XAttribute("descr", description), new XAttribute("title", "siyue-pdf:" + Json.Write(origin))),
                new XElement(A + "blip", new XAttribute(WordDocuments.R + "embed", "sourceImage"))))));
    }
    static XElement Context(string style, string text) => new(W + "p", new XElement(W + "pPr", new XElement(W + "pStyle", new XAttribute(W + "val", style))), new XElement(W + "r", new XElement(W + "t", text)));
    static void Write(string path, params XElement[] paragraphs) {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        using var doc = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document); var main = doc.AddMainDocumentPart();
        var image = main.AddImagePart(ImagePartType.Png, "sourceImage"); using (var bytes = new MemoryStream(Convert.FromBase64String(PdfModelAssist.ProbeImage))) image.FeedData(bytes);
        main.Document = new Document(new Body(new XElement(W + "body", paragraphs).ToString(SaveOptions.DisableFormatting))); main.Document.Save();
    }
    static Issue[] Issues(Question q) => Json.Read<Issue[]>(q.IssuesJson);
    static ContentBlock[] Blocks(Question q) => Json.Read<ContentBlock[]>(q.BlocksJson);

    [Fact] public async Task ExplicitAiBoundariesPreserveBareLabelsAndDoNotSplitSubpartsAgain() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "ai-named.docx");
        Write(path, Region(6, 0, "【1】", "【1】原题\n例2 只是题中引用\n（1）小问和图仍是一道"), Region(6, 1, null, "无题号原题"), Region(6, 2, "练12-1", "练12-1"));
        using var mode = QuestionRecognition.Use("named"); var qs = await word.Parse(path, Guid.NewGuid(), default);
        Assert.Equal(new[] { "【1】", "题2", "练12-1" }, qs.Select(q => q.Label));
        Assert.All(qs, q => { Assert.Equal("ai", q.Family); Assert.False(q.Confirmed); Assert.DoesNotContain(Issues(q), i => i.Blocking); });
        Assert.Contains("（1）", Blocks(qs[0]).Single().Text);
    }
    [Fact] public async Task VerifiedCrossPageReferenceMergesOnlyItsAdjacentRootQuestion() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "ai-cross-page.docx");
        Write(path, Region(11, 0, "例15", "题干"), Region(12, 0, "例15", "A B C D选项", "continuation", "p11-r1"), Region(12, 1, "例17", "下一题"));
        var qs = await word.Parse(path, Guid.NewGuid(), default); Assert.Equal(2, qs.Count); Assert.Equal(2, Blocks(qs[0]).Length);
        Assert.Contains("选项", Blocks(qs[0])[1].Text); Assert.DoesNotContain("下一题", qs[0].BlocksJson);
        Assert.Contains(Issues(qs[0]), i => i.Code == "pdf-cross-page" && !i.Blocking);
        Assert.All(qs, q => Assert.DoesNotContain(Issues(q), i => i.Blocking));
    }
    [Fact] public async Task WrongOrNonAdjacentContinuationCannotBeSilentlyMerged() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "ai-wrong-continuation.docx");
        Write(path, Region(7, 0, "练4", "上一题"), Region(8, 0, "练4", "未明归属", "continuation", "p7-r2"));
        var qs = await word.Parse(path, Guid.NewGuid(), default); Assert.Equal(2, qs.Count); Assert.Single(Blocks(qs[0]));
        Assert.Contains(Issues(qs[1]), i => i.Code == "pdf-ai-continuation" && i.Blocking);
        Write(path, Region(7, 0, "练4", "上一题"), Context("SiyuePdfContext", "知识说明"), Region(8, 0, "练4", "未明归属", "continuation", "p7-r1"));
        qs = await word.Parse(path, Guid.NewGuid(), default); Assert.Equal(2, qs.Count);
        Assert.Contains(Issues(qs[1]), i => i.Code == "pdf-ai-continuation" && i.Blocking);
    }
    [Fact] public async Task ImageOnlyAiQuestionAndFrontMatterAreHandledWithoutTextOcr() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "ai-picture-only.docx");
        Write(path, Context("SiyueAiFrontMatter", "例99 封面上的文字"), Region(6, 0, null));
        using var mode = QuestionRecognition.Use("named"); var q = Assert.Single(await word.Parse(path, Guid.NewGuid(), default));
        Assert.Equal("题1", q.Label); Assert.Empty(Blocks(q).Single().Text); Assert.Single(WordDocuments.ImageReferences(Blocks(q)[0].Xml));
        Assert.DoesNotContain(Issues(q), i => i.Blocking); Assert.False(q.Confirmed);
    }
    [Fact] public async Task LowConfidenceAndOrphanRegionsStayVisibleAndBlocking() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "ai-review.docx");
        Write(path, Region(6, 0, "1", "低置信", extraChecks: ["ai-review"]), Region(6, 1, "2", "归属不明", extraChecks: ["orphan-prefix"]));
        using var mode = QuestionRecognition.Use("named"); var qs = await word.Parse(path, Guid.NewGuid(), default); Assert.Equal(2, qs.Count);
        Assert.Contains(Issues(qs[0]), i => i.Code == "pdf-ai-review" && i.Blocking);
        Assert.Contains(Issues(qs[1]), i => i.Code == "pdf-orphan-prefix" && i.Blocking);
        WordDocuments.RefreshPdfContentIssues(qs[0]); Assert.Contains(Issues(qs[0]), i => i.Code == "pdf-ai-review" && i.Blocking);
    }
    [Fact] public void VerifiedAiDoesNotCreateGenericFormulaOrRegexBlockersButKeepsRealDisagreement() {
        var xml = Region(6, 0, "1", "1.条件\n2.第二个条件"); var prop = xml.Descendants(WP + "docPr").Single();
        prop.SetAttributeValue(XName.Get("formula", "urn:siyue:formula-review"), Json.Write(new FormulaEvidence("x", "x", .99, "x", false, "done")));
        var q = new Question { Label = "1", Family = "ai", BlocksJson = Json.Write(new[] { WordDocuments.Block(xml) }) };
        WordDocuments.RefreshPdfContentIssues(q); Assert.DoesNotContain(Issues(q), i => i.Blocking);
        prop.SetAttributeValue(XName.Get("formula", "urn:siyue:formula-review"), Json.Write(new FormulaEvidence("x", "x", .99, "y", true, "done")));
        q.BlocksJson = Json.Write(new[] { WordDocuments.Block(xml) }); WordDocuments.RefreshPdfContentIssues(q);
        Assert.Contains(Issues(q), i => i.Code == "pdf-formula-review" && i.Blocking);
    }
    [Fact] public void IntegrityRechecksIdentifiersAndDoesNotTrustVerifiedFlagAlone() {
        var root = Region(6, 0, "1", "题干"); var continuation = Region(7, 0, "1", "续题", "continuation", "p6-r2");
        var q = new Question { Label = "1", BlocksJson = Json.Write(new[] { WordDocuments.Block(root), WordDocuments.Block(continuation) }) };
        WordDocuments.RefreshPdfContentIssues(q); Assert.Contains(Issues(q), i => i.Code == "pdf-ai-continuation" && i.Blocking);
        var origin = WordDocuments.PdfOrigin(root)!; root.Descendants(WP + "docPr").Single().SetAttributeValue("title", "siyue-pdf:" + Json.Write(origin with { Checks = ["ai-verified", "ai-id:p5-r1"] }));
        q.BlocksJson = Json.Write(new[] { WordDocuments.Block(root) }); WordDocuments.RefreshPdfContentIssues(q);
        Assert.Contains(Issues(q), i => i.Code == "pdf-ai-identity" && i.Blocking);
    }
    [Fact] public async Task LegacyRasterNumberGapStillRequiresReview() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "legacy-unchanged.docx"); var first = Region(1, 0, "1.", "1. 原题"); var third = Region(1, 1, "3.", "3. 跳号");
        foreach (var xml in new[] { first, third }) { var origin = WordDocuments.PdfOrigin(xml)!; xml.Descendants(WP + "docPr").Single().SetAttributeValue("title", "siyue-pdf:" + Json.Write(origin with { Engine = "native", Checks = [] })); }
        Write(path, first, third); using var mode = QuestionRecognition.Use("legacy"); var qs = await word.Parse(path, Guid.NewGuid(), default);
        Assert.Equal(2, qs.Count); Assert.Contains(Issues(qs[1]), i => i.Code == "pdf-number-gap" && i.Blocking);
    }
    [Fact] public async Task ImportPreservesExistingFullBookSourcePdfAndOriginalPageNumbers() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var cfg = scope.ServiceProvider.GetRequiredService<IConfiguration>(); cfg["Documents:Soffice"] = "missing-converter-proves-existing-source-is-kept";
        var course = new Course { Name = "测试课程" }; var lecture = new Lecture { CourseId = course.Id, Sequence = 1, Title = "测试讲" };
        var handout = new Handout { LectureId = lecture.Id, State = "queued", RecognitionMode = "named", FileName = "split.docx", OriginalKey = "fixture/split.docx", SourcePdfKey = "fixture/full-book.pdf" };
        db.AddRange(course, lecture, handout); await db.SaveChangesAsync();
        var path = Path.Combine(f.Root, "split.docx"); Write(path, Region(6, 0, "1", "来源第6页"));
        await using (var file = File.OpenRead(path)) await objects.Put(handout.OriginalKey, file, WordDocuments.DocxType);
        var sourceBytes = System.Text.Encoding.UTF8.GetBytes("original-full-book-sentinel"); using (var source = new MemoryStream(sourceBytes)) await objects.Put(handout.SourcePdfKey, source, "application/pdf");
        await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Process("import", handout.Id, default);
        db.ChangeTracker.Clear(); var saved = (await db.Handouts.FindAsync(handout.Id))!;
        Assert.Equal("fixture/full-book.pdf", saved.SourcePdfKey); Assert.Equal("draft", saved.State);
        await using var stored = await objects.Get(saved.SourcePdfKey); using var bytes = new MemoryStream(); await stored.CopyToAsync(bytes); Assert.Equal(sourceBytes, bytes.ToArray());
        var q = db.Questions.Single(x => x.HandoutId == handout.Id); Assert.Equal(6, WordDocuments.PdfOrigin(XElement.Parse(Blocks(q).Single().Xml))!.Page); Assert.False(q.Confirmed);
    }
    [Theory]
    [InlineData(0, 1, 4800000)]
    [InlineData(.25, .75, 4800000)]
    [InlineData(.1, .4, 300000)]
    public async Task CompilationRestoresOnlyVisibleSourceCropWidth(double left, double right, long maxWidth) {
        await using var f = new Factory(); var input = Path.Combine(f.Root, "source-crop.docx");
        var original = Region(6, 0, "1", "题图"); Write(input, original);
        var block = PdfRegions.Crop(WordDocuments.Block(original), new(left, .1, right, .6, "1"));
        using var source = WordprocessingDocument.Open(input, false);
        using var stream = new MemoryStream(); using var target = WordprocessingDocument.Create(stream, WordprocessingDocumentType.Document);
        var targetMain = target.AddMainDocumentPart();
        var compose = typeof(WordDocuments).GetMethod("ComposeBlocks", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!;
        var result = (List<XElement>)compose.Invoke(null, [targetMain, source.MainDocumentPart!, new[] { block }, "c", "1", 360, 0, maxWidth, true])!;
        var xml = Assert.Single(result, WordDocuments.IsPdfRaster); var extent = Assert.Single(xml.Descendants(WP + "extent"));
        var png = Convert.FromBase64String(PdfModelAssist.ProbeImage); var pixelWidth = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(png.AsSpan(16, 4));
        var expectedWidth = Math.Min(maxWidth, (long)Math.Round(pixelWidth * 5080d * (right - left)));
        Assert.Equal(expectedWidth, (long)extent.Attribute("cx")!);
        var before = XElement.Parse(block.Xml).Descendants(WP + "extent").Single();
        Assert.Equal((long)before.Attribute("cy")! * expectedWidth / (long)before.Attribute("cx")!, (long)extent.Attribute("cy")!);
        Assert.Equal(new[] { left, .1, right, .6 }, PdfRegions.Bounds(xml));
    }
}
