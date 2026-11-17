using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class BookImportProgressTests
{
    static readonly XNamespace W = WordDocuments.W;
    static readonly XNamespace WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static XElement P(string text, string? style = null) => new(W + "p", style == null ? null : new XElement(W + "pPr", new XElement(W + "pStyle", new XAttribute(W + "val", style))), new XElement(W + "r", new XElement(W + "t", text)));
    static XElement AiBlock(string description, string[] checks, string? style = null)
    {
        var block = P("", style);
        block.Add(new XElement(W + "r", new XElement(W + "drawing", new XElement(WP + "inline",
            new XElement(WP + "docPr", new XAttribute("id", 1), new XAttribute("name", "SiyuePDF1"), new XAttribute("descr", description),
                new XAttribute("title", "siyue-pdf:" + Json.Write(new PdfProvenance(1, "ai-layout", [0, 0, 1, 1], checks))))))));
        return block;
    }
    static byte[] Word(params XElement[] blocks)
    {
        using var bytes = new MemoryStream();
        using (var doc = WordprocessingDocument.Create(bytes, DocumentFormat.OpenXml.WordprocessingDocumentType.Document))
            doc.AddMainDocumentPart().Document = new Document(new XElement(W + "document", new XElement(W + "body", blocks)).ToString());
        return bytes.ToArray();
    }
    static async Task<(SchoolClass Class, Course Course, Lecture Lecture)> Seed(Factory factory)
    {
        using var scope = factory.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "进度验收校区" }; var schoolClass = new SchoolClass { Name = "进度验收班", CampusId = campus.Id };
        var course = new Course { Name = "数学" }; var lecture = new Lecture { ClassId = schoolClass.Id, CourseId = course.Id, Sequence = 1, Title = "方程" };
        db.AddRange(campus, schoolClass, course, lecture); await db.SaveChangesAsync(); return (schoolClass, course, lecture);
    }
    static async Task Put(IObjects objects, string key, byte[] bytes, string type = "application/octet-stream")
    {
        await using var data = new MemoryStream(bytes); await objects.Put(key, data, type);
    }

    [Fact]
    public async Task DetailCountsQuestionsNotImagesAndTreatsProgressAsOptional()
    {
        await using var f = new Factory(); using var admin = await f.Login("book-progress", true); var seed = await Seed(f);
        Guid id; Guid handoutId; Guid emptyId;
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var h = new Handout { LectureId = seed.Lecture.Id, State = "draft" }; var empty = new Handout { LectureId = seed.Lecture.Id, State = "failed", Version = 2 };
            var unrelated = new Handout { LectureId = seed.Lecture.Id, State = "draft", Version = 3 };
            var b = new BookImport { ClassId = seed.Class.Id, CourseId = seed.Course.Id, State = "split", OriginalKey = "book-original.docx", FileName = "合订.docx", BlockCount = 4 };
            b.SectionsJson = Json.Write(new[] { new BookSection(0, 2, 1, "方程", HandoutId: h.Id), new BookSection(2, 4, 2, "几何", HandoutId: empty.Id) });
            Question Q(bool confirmed, Issue[] issues, string[] previews) => new() { HandoutId = h.Id, Confirmed = confirmed, IssuesJson = Json.Write(issues), PreviewsJson = Json.Write(previews) };
            db.AddRange(h, empty, unrelated, b,
                Q(false, [new("blocking", "待核对", true)], ["one.png", "two.png"]),
                Q(false, [new("source", "原图请核对", false)], ["three.png"]),
                Q(true, [new("old-blocking", "已确认疑点", true)], []), Q(false, [], []),
                new Question { HandoutId = unrelated.Id, IssuesJson = Json.Write(new[] { new Issue("blocking", "不可计入", true) }) });
            await db.SaveChangesAsync(); id = b.Id; handoutId = h.Id; emptyId = empty.Id;
        }
        var detail = await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");
        Assert.True(!detail.TryGetProperty("progress", out var absent) || absent.ValueKind == JsonValueKind.Null);
        var handouts = detail.GetProperty("handouts").EnumerateArray().ToArray(); Assert.Equal(2, handouts.Length);
        var counts = handouts.Single(h => h.GetProperty("id").GetGuid() == handoutId);
        Assert.Equal(4, counts.GetProperty("questionCount").GetInt32()); Assert.Equal(1, counts.GetProperty("reviewCount").GetInt32()); Assert.Equal(2, counts.GetProperty("previewCount").GetInt32());
        Assert.Equal(0, handouts.Single(h => h.GetProperty("id").GetGuid() == emptyId).GetProperty("questionCount").GetInt32());
        using (var scope = f.Services.CreateScope()) {
            var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
            await Put(objects, $"books/{id}/progress.json", Encoding.UTF8.GetBytes(Json.Write(new HandoutProgress("recognizing", 3, 8, "正在识别第 3 页"))), "application/json");
            detail = await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");
            Assert.Equal(3, detail.GetProperty("progress").GetProperty("current").GetInt32()); Assert.Equal(8, detail.GetProperty("progress").GetProperty("total").GetInt32());
            foreach (var invalid in new[] { "{broken", new string('x', 20_000) }) {
                await Put(objects, $"books/{id}/progress.json", Encoding.UTF8.GetBytes(invalid));
                detail = await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}");
                Assert.True(!detail.TryGetProperty("progress", out var bad) || bad.ValueKind == JsonValueKind.Null);
                Assert.Equal(2, detail.GetProperty("handouts").GetArrayLength());
            }
        }
    }

    [Theory]
    [InlineData("book", null, "auto")]
    [InlineData("book", "named", "named")]
    [InlineData("book", "legacy", "legacy")]
    [InlineData("handout", null, "auto")]
    [InlineData("handout", "named", "named")]
    [InlineData("handout", "legacy", "legacy")]
    public async Task MobileTicketKeepsRecognitionChoiceAndIgnoresPostedOverride(string mode, string? selected, string expected)
    {
        await using var f = new Factory(); using var admin = await f.Login("mobile-recognition", true); var seed = await Seed(f);
        var ticket = await Factory.Post(admin, "/api/mobile-uploads", new MobileUploadInput(mode, seed.Class.Id, seed.Course.Id, seed.Lecture.Id, RecognitionMode: selected));
        using var form = new MultipartFormDataContent(); form.Add(new ByteArrayContent(Word(P("第一讲 方程"), P("例1 求值"))), "file", "讲义.docx");
        form.Add(new StringContent(expected == "legacy" ? "auto" : "legacy"), "recognitionMode");
        var response = await admin.PostAsync(new Uri(ticket.GetProperty("url").GetString()!).AbsolutePath + "/file", form);
        Assert.True(response.IsSuccessStatusCode, await response.Content.ReadAsStringAsync());
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        Assert.Equal(expected, (await db.MobileUploads.FindAsync(ticket.GetProperty("id").GetGuid()))!.MaterialKind);
        Assert.Equal(expected, mode == "book" ? (await db.BookImports.SingleAsync()).RecognitionMode : (await db.Handouts.SingleAsync()).RecognitionMode);
    }

    [Fact]
    public async Task MobileRejectsInvalidRecognitionWithoutChangingPrintMaterial()
    {
        await using var f = new Factory(); using var admin = await f.Login("mobile-mode-validation", true); var seed = await Seed(f);
        foreach (var mode in new[] { "book", "handout" }) {
            var response = await admin.PostAsJsonAsync("/api/mobile-uploads", new MobileUploadInput(mode, seed.Class.Id, seed.Course.Id, seed.Lecture.Id, RecognitionMode: "unknown"));
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        }
        var print = await Factory.Post(admin, "/api/mobile-uploads", new MobileUploadInput("print", seed.Class.Id, seed.Course.Id, seed.Lecture.Id, "homework", "unknown"));
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        Assert.Single(await db.MobileUploads.ToListAsync()); Assert.Equal("homework", (await db.MobileUploads.FindAsync(print.GetProperty("id").GetGuid()))!.MaterialKind);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task SplitRetainsSharedPdfBeforeImportIsQueued(bool withPdf)
    {
        await using var f = new Factory(); using var admin = await f.Login("book-pdf", true); var seed = await Seed(f);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var h = new Handout { LectureId = seed.Lecture.Id, OriginalKey = "split/original.docx" };
        var b = new BookImport { ClassId = seed.Class.Id, CourseId = seed.Course.Id, State = "splitting", NormalizedKey = "split/normalized.docx" };
        b.SectionsJson = Json.Write(new[] { new BookSection(0, 2, 1, "方程", HandoutId: h.Id) });
        db.AddRange(b, h, new WorkItem { Kind = "import", EntityId = h.Id, State = "waiting" }); await db.SaveChangesAsync();
        await Put(objects, b.NormalizedKey, Word(P("第一讲 方程"), P("例1 求值")));
        var pdf = TeacherWorkspaceTests.Pdf(); if (withPdf) await Put(objects, $"books/{b.Id}/source.pdf", pdf, "application/pdf");
        var dir = Path.Combine(f.Root, "split-test"); Directory.CreateDirectory(dir);
        await BookImports.Split(b.Id, db, scope.ServiceProvider.GetRequiredService<WordDocuments>(), objects, dir, default);
        db.ChangeTracker.Clear(); h = (await db.Handouts.FindAsync(h.Id))!;
        Assert.Equal(withPdf ? $"books/{b.Id}/source.pdf" : "", h.SourcePdfKey);
        Assert.Equal("queued", (await db.Work.SingleAsync(w => w.EntityId == h.Id)).State);
        Assert.Equal("split", (await db.BookImports.FindAsync(b.Id))!.State);
        if (withPdf) { await using var source = await objects.Get(h.SourcePdfKey); using var bytes = new MemoryStream(); await source.CopyToAsync(bytes); Assert.Equal(pdf, bytes.ToArray()); }
    }

    [Theory]
    [InlineData("SiyueAiFrontMatter", false, false, "splitting")]
    [InlineData("SiyuePdfContext", false, false, "review")]
    [InlineData("SiyueAiFrontMatter", true, false, "review")]
    [InlineData("SiyueAiFrontMatter", false, true, "review")]
    public async Task AutoSplitKeepsUnknownContextAndLectureDoubtsForReview(string prefixStyle, bool lectureDoubt, bool prefixDoubt, string expected)
    {
        await using var f = new Factory(); using var admin = await f.Login("book-ai-boundaries", true); var seed = await Seed(f);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var b = new BookImport { StaffId = (await db.Staff.SingleAsync(s => s.Role == "admin")).Id, ClassId = seed.Class.Id, CourseId = seed.Course.Id, FileName = "两讲.docx", RecognitionMode = "auto", OriginalKey = "ai/original.docx" };
        db.Add(b); await db.SaveChangesAsync();
        var checks = lectureDoubt ? new[] { "ai-review", "ai-lecture-review" } : new[] { "ai-review" };
        await Put(objects, b.OriginalKey, Word(AiBlock("cover", prefixDoubt ? ["ai-review"] : [], prefixStyle), P("第一讲 方程"), AiBlock("question", checks, WordDocuments.PdfReviewStyle), P("第二讲 几何"), AiBlock("question", [])));
        var dir = Path.Combine(f.Root, "analyze-test"); Directory.CreateDirectory(dir);
        await BookImports.Analyze(b.Id, db, scope.ServiceProvider.GetRequiredService<WordDocuments>(), objects, dir, default, autoSplit: true);
        Assert.Equal(expected, b.State); var sections = Json.Read<BookSection[]>(b.SectionsJson);
        Assert.Equal(expected == "splitting" ? "exclude" : "unassigned", sections[0].Kind);
        Assert.Equal(expected == "splitting" ? 2 : 0, await db.Work.CountAsync(w => w.Kind == "import"));
    }

    [Fact]
    public void SingleAiQuestionCanUseFileNameButContextAndUncertainLecturesCannot()
    {
        var question = BookStructure.DetectFile([AiBlock("question", [])], "第3讲 方程.docx", 4);
        Assert.Equal("lesson", Assert.Single(question).Kind); Assert.Equal(3, question[0].Sequence);
        Assert.Equal("unassigned", Assert.Single(BookStructure.DetectFile([AiBlock("context", [])], "第3讲 方程.docx", 4)).Kind);
        Assert.Equal("unassigned", Assert.Single(BookStructure.DetectFile([AiBlock("question", ["ai-lecture-review"])], "第3讲 方程.docx", 4)).Kind);
    }

    [Fact]
    public async Task UnconfiguredModelWritesHonestFallbackProgressAndRestoresCallback()
    {
        await using var f = new Factory(); using var admin = await f.Login("book-fallback-progress", true); var seed = await Seed(f);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        // This case intentionally exercises the opt-in model path; production now defaults to fixed parsing.
        scope.ServiceProvider.GetRequiredService<Microsoft.Extensions.Configuration.IConfiguration>()["Documents:ParsingStrategy"] = "model-assisted";
        var b = new BookImport { ClassId = seed.Class.Id, CourseId = seed.Course.Id, FileName = "讲义.docx", RecognitionMode = "auto", OriginalKey = "fallback/original.docx" };
        db.Add(b); await db.SaveChangesAsync(); await Put(objects, b.OriginalKey, Word(P("第一讲 方程"), P("例1 求值")));
        var word = scope.ServiceProvider.GetRequiredService<WordDocuments>(); Func<HandoutProgress, CancellationToken, Task> original = (_, _) => Task.CompletedTask; word.HandoutProgressCallback = original;
        var dir = Path.Combine(f.Root, "fallback-test"); Directory.CreateDirectory(dir);
        await BookImports.Analyze(b.Id, db, word, objects, dir, default);
        Assert.Same(original, word.HandoutProgressCallback); Assert.Contains("视觉模型", b.Error);
        await using var stored = await objects.Get($"books/{b.Id}/progress.json"); var progress = await JsonSerializer.DeserializeAsync<HandoutProgress>(stored, Json.Options);
        Assert.Equal("fallback", progress!.Stage); Assert.Equal(0, progress.Total); Assert.Contains("题号规则", progress.Message);
    }
}
