using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using System.Xml.Linq;
using Xunit;

public class WordSplittingTests
{
    static readonly XNamespace W = WordDocuments.W;
    static XElement P(string text, XElement? properties = null) => new(W + "p", properties, new XElement(W + "r", new XElement(W + "t", text)));
    static XElement Num(int id, int level = 0) => new(W + "pPr", new XElement(W + "numPr", new XElement(W + "ilvl", new XAttribute(W + "val", level)), new XElement(W + "numId", new XAttribute(W + "val", id))));
    static string Create(Factory f, IEnumerable<XElement> blocks, string? numbering = null, string? styles = null) {
        var path = Path.Combine(f.Root, Guid.NewGuid()+".docx");
        using var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document);
        var main = doc.AddMainDocumentPart(); main.Document = new Document(new XElement(W + "document", new XElement(W + "body", blocks)).ToString());
        if (numbering != null) main.AddNewPart<NumberingDefinitionsPart>().Numbering = new Numbering(numbering);
        if (styles != null) main.AddNewPart<StyleDefinitionsPart>().Styles = new Styles(styles);
        return path;
    }
    static string Numbering(string extra = "") => $"<w:numbering xmlns:w='{W}'><w:abstractNum w:abstractNumId='1'><w:lvl w:ilvl='0'><w:start w:val='1'/><w:numFmt w:val='decimal'/><w:lvlText w:val='%1.'/></w:lvl><w:lvl w:ilvl='1'><w:start w:val='1'/><w:numFmt w:val='decimal'/><w:lvlText w:val='%2.'/></w:lvl></w:abstractNum><w:num w:numId='1'><w:abstractNumId w:val='1'/>{extra}</w:num></w:numbering>";
    [Fact]
    public async Task NumberingInstanceCanOverrideFormatAndStart() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var path = Create(f, [P("第一题", Num(1)), P("第二题", Num(1))], Numbering("<w:lvlOverride w:ilvl='0'><w:lvl w:ilvl='0'><w:start w:val='5'/><w:numFmt w:val='decimal'/><w:lvlText w:val='练%1.'/></w:lvl></w:lvlOverride>"));
        var qs = await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(path, Guid.NewGuid(), default);
        Assert.Equal(new[] {"练5.", "练6."}, qs.Select(q => q.Label));
    }
    [Fact]
    public async Task NumberingPropertiesMergeWithInheritedStyle() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        XElement Props() => new(W + "pPr", new XElement(W + "pStyle", new XAttribute(W + "val", "Question")), new XElement(W + "numPr", new XElement(W + "ilvl", new XAttribute(W + "val", 0))));
        var styles = $"<w:styles xmlns:w='{W}'><w:style w:type='paragraph' w:styleId='Base'><w:pPr><w:numPr><w:numId w:val='1'/></w:numPr></w:pPr></w:style><w:style w:type='paragraph' w:styleId='Question'><w:basedOn w:val='Base'/></w:style></w:styles>";
        var qs = await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(Create(f, [P("第一题", Props()), P("第二题", Props())], Numbering(), styles), Guid.NewGuid(), default);
        Assert.Equal(new[] {"1.", "2."}, qs.Select(q => q.Label));
    }
    [Fact]
    public async Task ContentControlKeepsIndividualQuestionsAndTableStructure() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var table = new XElement(W + "tbl", new XElement(W + "tr", new XElement(W + "tc", P("1. 表内不是新题"))));
        var control = new XElement(W + "sdt", new XElement(W + "sdtPr"), new XElement(W + "sdtContent", P("1. 第一题"), table, P("2. 第二题")));
        var qs = await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(Create(f, [control]), Guid.NewGuid(), default);
        Assert.Equal(new[] {"1.", "2."}, qs.Select(q => q.Label));
        Assert.Contains(Json.Read<ContentBlock[]>(qs[0].BlocksJson), b => b.Kind == "table" && b.Text.Contains("表内不是新题"));
    }
    [Fact]
    public async Task NestedListsAndDecimalValuesStayInsideParent() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var qs = await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(Create(f, [P("第一题",Num(1)),P("小问",Num(1,1)),P("3.14是小数"),P("（2）第二小问"),P("第二题",Num(1))], Numbering()), Guid.NewGuid(), default);
        Assert.Equal(2, qs.Count); var text = string.Concat(Json.Read<ContentBlock[]>(qs[0].BlocksJson).Select(b => b.Text)); Assert.Contains("小问",text); Assert.Contains("3.14",text);
    }
    [Fact]
    public async Task UnsupportedTopLevelObjectCannotDisappear() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope();
        var qs = await scope.ServiceProvider.GetRequiredService<WordDocuments>().Parse(Create(f,[P("1. 第一题"), new XElement(W + "altChunk", new XAttribute(WordDocuments.R + "id", "unresolved")), P("2. 第二题")]), Guid.NewGuid(), default);
        Assert.Contains(Json.Read<Issue[]>(qs[0].IssuesJson), i => i.Code == "unsupported-object" && i.Blocking);
    }
}
