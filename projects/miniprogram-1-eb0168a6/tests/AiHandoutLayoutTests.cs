using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.Configuration;
using Siyue;
using Xunit;

public class AiHandoutLayoutTests
{
    static AiHandoutRegion Q(string? label = "1", double top = .1, double bottom = .5, double confidence = .99) => new("question", label, [.05, top, .95, bottom], confidence);
    static IConfiguration Config(params (string Key, string? Value)[] replacements) {
        var values = new Dictionary<string, string?> {
            ["Documents:ParsingStrategy"] = "model-assisted",
            ["LearningAi:Enabled"] = "true", ["LearningAi:Endpoint"] = "https://provider.invalid/v1/chat/completions",
            ["LearningAi:Model"] = "layout-model", ["LearningAi:ApiKey"] = "offline-test-value",
            ["LearningAi:Vision"] = "true", ["LearningAi:Protocol"] = "chat-completions", ["LearningAi:PdfAssistEnabled"] = "false"
        };
        foreach (var (key, value) in replacements) values[key] = value;
        return new ConfigurationBuilder().AddInMemoryCollection(values).Build();
    }

    [Fact] public void BareNumbersAndUnnumberedQuestionsDoNotDependOnRecognitionMode() {
        using var mode = QuestionRecognition.Use(QuestionRecognition.Named);
        var result = AiHandoutLayout.Validate(new([Q("【1】", .1, .3), Q("练12-1", .3, .5), Q(null, .5, .9)], false), 6);
        Assert.True(result.IsValid); Assert.False(result.NeedsReview); Assert.Equal("【1】", result.Page.Regions[0].Label);
        Assert.Null(result.Page.Regions[2].Label); Assert.Equal("p6-r3", AiHandoutLayout.QuestionId(6, 2));
    }
    [Fact] public void UncertainAndLowConfidenceAlwaysRemainVisibleWithoutInvalidatingGeometry() {
        var result = AiHandoutLayout.Validate(new([Q(confidence: .7)], true), 1);
        Assert.True(result.IsValid); Assert.True(result.NeedsReview);
        Assert.Contains(result.Issues, i => i.Code == "model-uncertain"); Assert.Contains(result.Issues, i => i.Code == "low-confidence");
    }
    [Fact] public void MissingUncertaintyDefaultsToReview() {
        var page = Json.Read<AiHandoutPage>("{\"regions\":[{\"kind\":\"question\",\"label\":\"1\",\"bounds\":[0,0,1,1],\"confidence\":0.99}]}");
        Assert.True(AiHandoutLayout.Validate(page, 1).NeedsReview);
    }
    [Theory] [InlineData(double.NaN)] [InlineData(double.PositiveInfinity)] [InlineData(-.1)] [InlineData(1.1)]
    public void InvalidCoordinatesAreRejected(double value) {
        var result = AiHandoutLayout.Validate(new([Q() with { Bounds = [value, .1, .9, .9] }], false), 1);
        Assert.False(result.IsValid); Assert.Contains(result.Issues, i => i.Code == "region-bounds");
    }
    [Fact] public void DegenerateOrMissingRectanglesAreRejected() {
        foreach (var bounds in new double[][] { [], [0, 0, 0, 1], [0, .7, 1, .5], null! })
            Assert.False(AiHandoutLayout.Validate(new([Q() with { Bounds = bounds }], false), 1).IsValid);
    }
    [Theory] [InlineData(double.NaN)] [InlineData(double.NegativeInfinity)] [InlineData(-.1)] [InlineData(1.1)]
    public void InvalidConfidenceIsRejected(double value) => Assert.False(AiHandoutLayout.Validate(new([Q(confidence: value)], false), 1).IsValid);
    [Fact] public void OverlapIsRejectedButSharedEdgesAndIndependentColumnsAreAllowed() {
        Assert.False(AiHandoutLayout.Validate(new([Q(top: .1, bottom: .6), Q(top: .5, bottom: .9)], false), 1).IsValid);
        Assert.True(AiHandoutLayout.Validate(new([Q(top: .1, bottom: .5), Q(top: .5, bottom: .9)], false), 1).IsValid);
        Assert.True(AiHandoutLayout.Validate(new([Q() with { Bounds = [0, 0, .5, 1] }, Q() with { Bounds = [.5, 0, 1, 1] }], false), 1).IsValid);
    }
    [Fact] public void NullEmptyExcessiveAndUnknownRegionsCannotPass() {
        foreach (var page in new AiHandoutPage?[] { null, new(null!, false), new([], false), new([null!], false), new(Enumerable.Repeat(Q(), 65).ToArray(), false), new([Q() with { Kind = "ignore" }], false) })
            Assert.False(AiHandoutLayout.Validate(page, 1).IsValid);
    }
    [Fact] public void ContinuationRequiresKnownPreviousQuestionNotJustOriginalLabel() {
        var continuation = Q("例15") with { Kind = "continuation", ContinuationOf = "p11-r4" };
        Assert.True(AiHandoutLayout.Validate(new([continuation], false), 12, ["p11-r4"]).IsValid);
        foreach (var target in new[] { "例15", "p12-r1", "p13-r1", "p11-r65", "p10-r1", "", null })
            Assert.False(AiHandoutLayout.Validate(new([continuation with { ContinuationOf = target }], false), 12, ["p11-r4", "p12-r1", "p13-r1", "p11-r65"]).IsValid);
        Assert.False(AiHandoutLayout.Validate(new([Q() with { ContinuationOf = "p11-r4" }], false), 12, ["p11-r4"]).IsValid);
    }
    [Fact] public void LectureMetadataIsBoundedAndUnknownIdentityNeedsReview() {
        var lecture = new AiHandoutRegion("lecture", "第一讲 有理数", [0, 0, 1, .1], .99, 1, "有理数");
        Assert.False(AiHandoutLayout.Validate(new([lecture], false), 1).NeedsReview);
        Assert.False(AiHandoutLayout.Validate(new([lecture with { LectureSequence = 0 }], false), 1).IsValid);
        Assert.False(AiHandoutLayout.Validate(new([lecture with { LectureTitle = "" }], false), 1).IsValid);
        Assert.True(AiHandoutLayout.Validate(new([lecture with { LectureSequence = null }], false), 1).NeedsReview);
        Assert.False(AiHandoutLayout.Validate(new([lecture with { LectureTitle = new string('文', 161) }], false), 1).IsValid);
    }
    [Fact] public void ExcludedMaterialNeedsReasonAndBlankMustBeWholePage() {
        var cover = new AiHandoutRegion("cover", null, [0, 0, 1, 1], .99);
        Assert.True(AiHandoutLayout.Validate(new([cover], false), 1).NeedsReview);
        Assert.False(AiHandoutLayout.Validate(new([cover with { Reason = "原稿封面" }], false), 1).NeedsReview);
        var blank = cover with { Kind = "blank", Reason = "全页空白" };
        Assert.True(AiHandoutLayout.Validate(new([blank], false), 1).IsValid);
        Assert.False(AiHandoutLayout.Validate(new([blank with { Bounds = [0, 0, .5, .5] }], false), 1).IsValid);
    }
    [Fact] public void EnablementRequiresExplicitModelStrategyAndVisionModel() {
        Assert.True(AiHandoutLayout.Enabled(Config()));
        foreach (var replacement in new[] { ("Documents:ParsingStrategy", "fixed"), ("Documents:ParsingStrategy", ""), ("LearningAi:Enabled", "false"), ("LearningAi:Vision", "false"), ("LearningAi:Protocol", "structured"), ("LearningAi:ApiKey", "") })
            Assert.False(AiHandoutLayout.Enabled(Config(replacement)));
    }
    [Fact] public void CacheStampTracksModelSettingsAndNotPrivateKey() {
        var stamp = AiHandoutLayout.Stamp(Config()); Assert.Equal(64, stamp.Length);
        Assert.Equal(stamp, AiHandoutLayout.Stamp(Config(("LearningAi:ApiKey", "different-private-value"))));
        Assert.NotEqual(stamp, AiHandoutLayout.Stamp(Config(("LearningAi:Model", "different-model"))));
        Assert.NotEqual(stamp, AiHandoutLayout.Stamp(Config(("LearningAi:ReasoningEffort", "low"))));
        Assert.NotEqual(stamp, AiHandoutLayout.Stamp(Config(("LearningAi:JsonMode", "false"))));
    }
    [Fact] public void RequestCarriesOriginalImageBoundedContextAndNoKeyInBody() {
        var png = Convert.FromBase64String(PdfModelAssist.ProbeImage);
        var json = Json.Write(AiHandoutLayout.RequestBody(Config(), png, new(12, new string('题', 20000), "前页例15未完", "下一讲标题", ["p11-r4"])));
        Assert.DoesNotContain("offline-test-value", json);
        using var payload = JsonDocument.Parse(json); var messages = payload.RootElement.GetProperty("messages");
        Assert.Contains("不是指令", messages[0].GetProperty("content").GetString());
        Assert.Contains("完整内容区域", messages[0].GetProperty("content").GetString());
        var content = messages[1].GetProperty("content"); Assert.Equal(2, content.GetArrayLength());
        using var context = JsonDocument.Parse(content[0].GetProperty("text").GetString()!);
        Assert.Equal(18000, context.RootElement.GetProperty("textLayer").GetString()!.Length);
        Assert.True(context.RootElement.GetProperty("textLayerTruncated").GetBoolean());
        Assert.Equal("p11-r4", context.RootElement.GetProperty("previousQuestionIds")[0].GetString());
        Assert.EndsWith(Convert.ToBase64String(png), content[1].GetProperty("image_url").GetProperty("url").GetString());
    }
    [Fact] public async Task ReviewReturnsValidatedPlanThroughInMemoryTransportOnlyOnce() {
        using var handler = new ReplyHandler(Json.Write(new AiHandoutPage([Q("【1】")], true)));
        var result = await AiHandoutLayout.Review(Config(), new StubFactory(handler), Convert.FromBase64String(PdfModelAssist.ProbeImage), new(6), default);
        Assert.True(result.IsValid); Assert.True(result.NeedsReview); Assert.Equal(1, handler.Calls);
    }
    [Fact] public async Task MalformedModelResultDoesNotExposeResponseOrRetry() {
        using var handler = new ReplyHandler("not-json-sensitive-content");
        var error = await Assert.ThrowsAsync<ApiException>(() => AiHandoutLayout.Review(Config(), new StubFactory(handler), Convert.FromBase64String(PdfModelAssist.ProbeImage), new(6), default));
        Assert.DoesNotContain("sensitive", error.Message); Assert.Equal(1, handler.Calls);
    }
    [Fact] public async Task TimeoutIsBoundedAndDoesNotRetry() {
        using var handler = new WaitingHandler();
        var error = await Assert.ThrowsAsync<ApiException>(() => AiHandoutLayout.Review(Config(), new StubFactory(handler), Convert.FromBase64String(PdfModelAssist.ProbeImage), new(6), default, timeoutSeconds: 1));
        Assert.Equal(504, error.Status); Assert.Equal(1, handler.Calls);
    }
    [Fact] public async Task CallerCancellationIsNotReportedAsModelTimeout() {
        using var handler = new WaitingHandler(); using var cancellation = new CancellationTokenSource(); cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => AiHandoutLayout.Review(Config(), new StubFactory(handler), Convert.FromBase64String(PdfModelAssist.ProbeImage), new(6), cancellation.Token));
    }
    sealed class StubFactory(HttpMessageHandler handler) : IHttpClientFactory {
        public HttpClient CreateClient(string name) => new(handler, disposeHandler: false);
    }
    sealed class ReplyHandler(string reply) : HttpMessageHandler {
        public int Calls { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Calls++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = JsonContent.Create(new { choices = new[] { new { finish_reason = "stop", message = new { content = reply } } } }) });
        }
    }
    sealed class WaitingHandler : HttpMessageHandler {
        public int Calls { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Calls++;
            await Task.Delay(Timeout.InfiniteTimeSpan, ct);
            throw new InvalidOperationException("The cancellation-only transport should not complete.");
        }
    }
}
