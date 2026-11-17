using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class QuestionRecognitionTests
{
    [Theory]
    [InlineData("例1 求值", true)]
    [InlineData("练习题２ 比较", true)]
    [InlineData("【测 3】（2023 秋）", true)]
    [InlineData("练1-2 计算", true)]
    [InlineData("1. 比例的项：", false)]
    [InlineData("2023. 秋季试题", false)]
    [InlineData("（2023 秋·越秀区校级期中）", false)]
    [InlineData("【2023】", false)]
    [InlineData("（1）分式", false)]
    public void NamedModeDoesNotTreatConceptNumbersOrYearsAsQuestions(string text, bool expected) {
        using var mode = QuestionRecognition.Use("named");
        Assert.Equal(expected, WordDocuments.IsQuestionStart(text));
    }
    [Fact]
    public void IntegrityCountsNamedMarkersButNotYearOrNumberedConceptLines() {
        var w = WordDocuments.W;
        var wp = System.Xml.Linq.XNamespace.Get("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing");
        var origin = new PdfProvenance(1, "native", new double[] { 0, 0, 1, 1 }, Array.Empty<string>(), "测3");
        var xml = new System.Xml.Linq.XElement(w + "p", new System.Xml.Linq.XElement(wp + "docPr", new System.Xml.Linq.XAttribute("title", "siyue-pdf:" + Json.Write(origin))));
        var q = new Question { Label = "测3" };
        void SetText(string text) { q.BlocksJson = Json.Write(new[] { new ContentBlock(xml.ToString(), text, "paragraph") }); WordDocuments.RefreshPdfContentIssues(q); }
        SetText("测3 （2023 秋）\n2023. 年份\n1. 题内条件");
        Assert.DoesNotContain(Json.Read<Issue[]>(q.IssuesJson), i => i.Code == "pdf-multiple-labels");
        SetText("测3 （2023 秋）\n测4 下一道题");
        Assert.Contains(Json.Read<Issue[]>(q.IssuesJson), i => i.Code == "pdf-multiple-labels" && i.Blocking);
    }
    [Fact]
    public async Task ModesAreIsolatedAcrossConcurrentImportsAndRestored() {
        Assert.Equal("legacy", QuestionRecognition.Mode);
        await Task.WhenAll(Enumerable.Range(0, 20).Select(async i => {
            var selected = i % 2 == 0 ? "named" : "legacy";
            using var mode = QuestionRecognition.Use(selected);
            await Task.Yield(); Assert.Equal(selected, QuestionRecognition.Mode);
            Assert.Equal(selected == "legacy", WordDocuments.IsQuestionStart("1. 定义"));
            using (QuestionRecognition.Use("named")) Assert.False(WordDocuments.IsQuestionStart("2023. 年"));
            Assert.Equal(selected, QuestionRecognition.Mode);
        }));
        Assert.Equal("legacy", QuestionRecognition.Mode);
    }
    [Fact]
    public async Task ParseKeepsOnlyNamedQuestionsAndPreservesSubpartsAndYears() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var word = scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path = Path.Combine(f.Root, "named.docx");
        Directory.CreateDirectory(f.Root);
        using (var pack = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document)) {
            var main = pack.AddMainDocumentPart();
            main.Document = new Document(new Body(new[] {
                "1. 比例的项：", "概念部分", "例1 计算", "（1）第一个小问", "练1 解答", "测3 （2023 秋·越秀区校级期中）", "2023 是题目给定的年份"
            }.Select(t => new Paragraph(new Run(new Text(t)))))); main.Document.Save();
        }
        using var mode = QuestionRecognition.Use("named");
        var qs = await word.Parse(path, Guid.NewGuid(), default);
        Assert.Equal(new[] { "例1", "练1", "测3" }, qs.Select(q => q.Label));
        Assert.Contains("第一个小问", string.Join("", Json.Read<ContentBlock[]>(qs[0].BlocksJson).Select(b => b.Text)));
        Assert.DoesNotContain("概念部分", string.Join("", qs.SelectMany(q => Json.Read<ContentBlock[]>(q.BlocksJson)).Select(b => b.Text)));
        Assert.Contains("2023", qs[2].BlocksJson);
        Assert.DoesNotContain(qs.SelectMany(q => Json.Read<Issue[]>(q.IssuesJson)), i => i.Code == "pdf-multiple-labels");
    }
}
