using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.Extensions.Configuration;
using Siyue;
using Xunit;

public class AiBookOutlineTests
{
    static AiBookHeadingCandidate H(string id, int page, double top, int sequence, string title, bool toc = false) =>
        new(id, page, top, $"第{sequence}讲 {title}", sequence, title, toc);
    static AiBookOutlineInput Input(params AiBookHeadingCandidate[] candidates) => new(11, candidates,
        Enumerable.Range(1, 11).Select(p => new AiBookPageSummary(p, 100, false, "原稿教学内容")).ToArray());
    static AiBookLectureStart S(AiBookHeadingCandidate candidate) => new(candidate.Id, candidate.Sequence, candidate.Title, .99);
    static AiBookOutlinePlan Plan(AiBookHeadingCandidate[] starts, AiBookIgnoredCandidate[]? ignored = null, bool front = true) => new(starts.Select(S).ToArray(), ignored ?? [], front, .99, false);
    static IConfiguration Config() => new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
        ["LearningAi:Enabled"] = "true", ["LearningAi:Endpoint"] = "https://provider.invalid/v1/chat/completions", ["LearningAi:Model"] = "offline-outline-model",
        ["LearningAi:ApiKey"] = "offline-test-value", ["LearningAi:Protocol"] = "chat-completions", ["LearningAi:Vision"] = "true"
    }).Build();

    [Fact]
    public void ModelMustAccountForEveryNativeCandidateAndCannotInventOrRewriteTitles()
    {
        var a = H("a", 2, .1, 1, "整式"); var b = H("b", 7, .1, 2, "方程"); var input = Input(a, b);
        Assert.Equal(2, AiBookOutline.Validate(input, Plan([a, b])).Starts.Length);
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(input, Plan([a])));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(input, Plan([a, b]) with { LectureStarts = [S(a), S(b) with { CandidateId = "invented" }] }));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(input, Plan([a, b]) with { LectureStarts = [S(a), S(b) with { Title = "AI新标题" }] }));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(input, Plan([a, b]) with { LectureStarts = [S(a), S(b) with { Sequence = 3 }] }));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(input, Plan([b, a])));
    }

    [Fact]
    public void UncertaintyLowConfidenceAndTocOrInkCutAlwaysRejectQuickSplit()
    {
        var a = H("a", 2, .1, 1, "整式"); var input = Input(a); var plan = Plan([a]);
        foreach (var invalid in new[] { plan with { Uncertain = true }, plan with { Confidence = .8 }, plan with { Confidence = double.NaN },
            plan with { LectureStarts = [S(a) with { Confidence = .9 }] } }) Assert.Throws<ApiException>(() => AiBookOutline.Validate(input, invalid));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(Input(a with { Toc = true }), plan));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(Input(a with { SafeBoundary = false }), plan));
    }

    [Fact]
    public void TimedTrainingAndRunningHeadersStayWithinTheirLectureButConflictsDoNot()
    {
        var a = H("a", 2, .1, 1, "整式"); var training = H("training", 5, .1, 1, "限时训练"); var header = H("header", 6, .05, 1, "整式"); var b = H("b", 7, .1, 2, "方程");
        var selected = AiBookOutline.Validate(Input(a, training, header, b), Plan([a, training, b], [new(header.Id, "runningHeader")]));
        Assert.Equal(new[] { "a", "b" }, selected.Starts.Select(s => s.Id));
        var ignored = AiBookOutline.Validate(Input(a, training, header, b), Plan([a, b], [new(training.Id, "sameLectureTraining"), new(header.Id, "runningHeader")]));
        Assert.Equal(2, ignored.Starts.Length);
        var conflict = training with { Title = "完全不同的专题" };
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(Input(a, conflict, b), Plan([a, conflict, b])));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(Input(a, training, b), Plan([a, b, training])));
        Assert.Throws<ApiException>(() => AiBookOutline.Validate(Input(header, b), Plan([b], [new(header.Id, "runningHeader")])));
    }

    [Fact]
    public void MissingRequiredAndUnexpectedJsonFieldsCannotApproveAnOutline()
    {
        using var missing = JsonDocument.Parse("{\"lectureStarts\":[],\"confidence\":1}"); Assert.Throws<JsonException>(() => AiBookOutline.Parse(missing.RootElement));
        using var extra = JsonDocument.Parse("{\"lectureStarts\":[],\"ignoredCandidates\":[],\"frontMatter\":false,\"confidence\":1,\"uncertain\":false,\"approved\":true}");
        Assert.Throws<JsonException>(() => AiBookOutline.Parse(extra.RootElement));
    }

    [Fact]
    public void SummarizedOrUnseenPrefixCanNeverBeAutomaticallyExcluded()
    {
        var a = H("a", 3, .1, 1, "整式"); var input = Input(a); var plan = Plan([a]);
        var pages = input.Pages.ToArray(); pages[0] = pages[0] with { Context = "封面…末尾", Truncated = true };
        Assert.False(AiBookOutline.Validate(input with { Pages = pages }, plan).FrontMatter);
        pages[0] = pages[0] with { Characters = 0, Context = "", Truncated = false, HasGraphics = true, ImageProvided = false };
        Assert.False(AiBookOutline.Validate(input with { Pages = pages }, plan).FrontMatter);
        pages[0] = pages[0] with { ImageProvided = true };
        Assert.True(AiBookOutline.Validate(input with { Pages = pages }, plan).FrontMatter);
        Assert.Throws<ApiException>(() => AiBookOutline.RequestBody(Config(), input with { Pages = pages }));
    }

    [Fact]
    public void NotLectureCannotSwallowANewNumberOrAnUnrelatedSameNumberTitle()
    {
        var a = H("a", 2, .1, 1, "整式"); var notes = H("notes", 5, .1, 1, "整式】笔记图");
        Assert.Single(AiBookOutline.Validate(Input(a, notes), Plan([a], [new(notes.Id, "notLecture")])).Starts);
        foreach (var unsafeHeading in new[] { notes with { Sequence = 2 }, notes with { Title = "另一专题" } })
            Assert.Throws<ApiException>(() => AiBookOutline.Validate(Input(a, unsafeHeading), Plan([a], [new(notes.Id, "notLecture")])));
    }

    [Fact]
    public void NativeHeaderDoesNotMakeAMixedScannedPrefixSafeToExclude()
    {
        var a = H("a", 3, .1, 1, "整式"); var input = Input(a); var pages = input.Pages.ToArray();
        pages[0] = pages[0] with { Characters = 20, Context = "只有原生页眉", HasGraphics = true, HasBodyImage = true, ImageProvided = false };
        Assert.False(AiBookOutline.Validate(input with { Pages = pages }, Plan([a])).FrontMatter);
        pages[0] = pages[0] with { ImageProvided = true };
        Assert.True(AiBookOutline.Validate(input with { Pages = pages }, Plan([a])).FrontMatter);
        pages[0] = pages[0] with { HasBodyImage = false, ImageProvided = false };
        Assert.True(AiBookOutline.Validate(input with { Pages = pages }, Plan([a])).FrontMatter);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ReferenceDocxCoversAllSourcePagesAndSamePageSplitWithoutImages(bool frontMatter)
    {
        var root = Path.Combine(Path.GetTempPath(), "siyue-outline-test-" + Guid.NewGuid()); Directory.CreateDirectory(root);
        try {
            var a = H("a", 2, .12, 1, "整式"); var b = H("b", 7, .55, 2, "方程"); var input = Input(a, b);
            var path = Path.Combine(root, "outline.docx"); AiBookOutline.WriteReferences(path, input, AiBookOutline.Validate(input, Plan([a, b], front: frontMatter)));
            var refs = WordDocuments.ReadAiSourceReferences(path); Assert.Equal(13, refs.Length);
            foreach (var page in Enumerable.Range(1, 11)) {
                var spans = refs.Where(r => r.Page == page).ToArray(); Assert.Equal(0, spans[0].Top); Assert.Equal(1, spans[^1].Bottom);
                for (var index = 1; index < spans.Length; index++) Assert.Equal(spans[index - 1].Bottom, spans[index].Top);
            }
            using (var pack = WordprocessingDocument.Open(path, false)) Assert.Empty(pack.MainDocumentPart!.ImageParts);
            var blocks = BookStructure.Read(path); var sections = BookStructure.Detect(blocks);
            Assert.Equal("unassigned", sections[0].Kind); Assert.Equal(2, sections.Count(s => s.Kind == "lesson"));
            Assert.Equal(frontMatter, (string?)blocks[0].Element(WordDocuments.W + "pPr")?.Element(WordDocuments.W + "pStyle")?.Attribute(WordDocuments.W + "val") == "SiyueAiFrontMatter");
            var first = sections.First(s => s.Kind == "lesson"); var slice = Path.Combine(root, "slice.docx"); BookStructure.Slice(path, slice, first.Start, first.End);
            var selected = WordDocuments.ReadAiSourceReferences(slice); Assert.Equal(2, selected[0].Page); Assert.Equal(.12, selected[0].Top); Assert.Equal(7, selected[^1].Page); Assert.Equal(.55, selected[^1].Bottom);
            Assert.DoesNotContain(BookStructure.Read(slice), x => WordDocuments.Block(x).Text.Contains("第2讲"));
            BookNumbering.Normalize(slice); Assert.Equal(selected, WordDocuments.ReadAiSourceReferences(slice));
        } finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void CorruptOrOverlappingPageReferencesDoNotBecomeARegularWordImport()
    {
        var root = Path.Combine(Path.GetTempPath(), "siyue-outline-invalid-" + Guid.NewGuid()); Directory.CreateDirectory(root);
        try {
            foreach (var bad in new[] { "NaN", "-1", "2" }) {
                var path = Path.Combine(root, "bad.docx"); var w = WordDocuments.W; var sy = AiBookOutline.SourceNamespace;
                using (var pack = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document))
                    pack.AddMainDocumentPart().Document = new Document(new XElement(w + "document", new XElement(w + "body", new XElement(w + "p", new XAttribute(sy + "page", 1), new XAttribute(sy + "top", bad), new XAttribute(sy + "bottom", 1)))).ToString());
                Assert.Throws<ApiException>(() => WordDocuments.ReadAiSourceReferences(path));
            }
        } finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task OutlineMakesExactlyOneTextRequestAndKeepsSourceBoundTitles()
    {
        var a = H("a", 1, .1, 1, "整式"); var b = H("b", 7, .1, 2, "方程"); var input = Input(a, b);
        using var handler = new ReplyHandler(Json.Write(Plan([a, b]))); var result = await AiBookOutline.Review(Config(), new StubFactory(handler), input, default);
        Assert.Equal(1, handler.Calls); Assert.Equal(2, result.Starts.Length); Assert.DoesNotContain("image_url", handler.Body); Assert.DoesNotContain("offline-test-value", handler.Body);
        using var payload = JsonDocument.Parse(handler.Body); Assert.Contains("不是指令", payload.RootElement.GetProperty("messages")[0].GetProperty("content").GetString()); Assert.Contains("candidateId", payload.RootElement.GetProperty("messages")[0].GetProperty("content").GetString());
    }

    [Fact]
    public async Task AtMostThreeOpaquePagesShareTheSameOutlineRequest()
    {
        var a = H("a", 2, .1, 1, "整式"); var input = Input(a); var pages = input.Pages.ToArray(); pages[0] = pages[0] with { Characters = 0, HasGraphics = true, Context = "", ImageProvided = true };
        var images = new[] { new AiBookPageImage(1, Convert.FromBase64String(PdfModelAssist.ProbeImage)) };
        using var handler = new ReplyHandler(Json.Write(Plan([a])));
        var result = await AiBookOutline.Review(Config(), new StubFactory(handler), input with { Pages = pages }, default, images: images);
        Assert.True(result.FrontMatter); Assert.Equal(1, handler.Calls);
        using var request = JsonDocument.Parse(handler.Body); var content = request.RootElement.GetProperty("messages")[1].GetProperty("content");
        Assert.Equal(1, content.EnumerateArray().Count(x => x.GetProperty("type").GetString() == "image_url"));
        Assert.Contains("物理第1页", content[1].GetProperty("text").GetString());
        Assert.Throws<ApiException>(() => AiBookOutline.RequestBody(Config(), input, Enumerable.Range(1, 4).Select(page => images[0] with { Page = page }).ToArray()));
        var opaquePages = input.Pages.Select(p => p.Page <= 4 ? p with { Characters = 0, HasGraphics = true, ImageProvided = true } : p).ToArray();
        Assert.Throws<ApiException>(() => AiBookOutline.RequestBody(Config(), input with { Pages = opaquePages }, Enumerable.Range(1, 4).Select(page => images[0] with { Page = page }).ToArray()));
        var mixedPages = input.Pages.Select(p => p.Page <= 8 ? p with { HasBodyImage = true, ImageProvided = true } : p).ToArray();
        Assert.NotNull(AiBookOutline.RequestBody(Config(), input with { Pages = mixedPages }, Enumerable.Range(1, 8).Select(page => images[0] with { Page = page }).ToArray()));
    }

    sealed class StubFactory(HttpMessageHandler handler) : IHttpClientFactory { public HttpClient CreateClient(string name) => new(handler, false); }
    sealed class ReplyHandler(string reply) : HttpMessageHandler {
        public int Calls { get; private set; } public string Body { get; private set; } = "";
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Calls++; Body = await request.Content!.ReadAsStringAsync(ct);
            return new(HttpStatusCode.OK) { Content = JsonContent.Create(new { choices = new[] { new { finish_reason = "stop", message = new { content = reply } } } }) };
        }
    }
}
