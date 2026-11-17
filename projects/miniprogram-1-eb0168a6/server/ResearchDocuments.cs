using DocumentFormat.OpenXml.Packaging;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;

namespace Siyue;

public partial class WordDocuments
{
    // Original blocks are copied with their image/formula relationships. Edited questions reflow.
    public async Task<string> ResearchDocx(ResearchDraft draft, string sourcePath, string dir, bool teacher, CancellationToken ct)
    {
        ResearchTemplates.Validate(draft.Template); var t = draft.Template;
        var path = Path.Combine(dir, "handout.docx");
        using var source = File.Exists(sourcePath) ? WordprocessingDocument.Open(sourcePath, false) : null;
        using var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document);
        var main = doc.AddMainDocumentPart(); var body = new XElement(W + "body");
        var width = t.Paper == "B5" ? 10318 : 11906; var height = t.Paper == "B5" ? 14570 : 16838; var margin = (int)Math.Round(t.MarginMm * 56.693);
        var line = (int)(t.FontSize * 20 * 1.5); var size = (t.FontSize * 2).ToString();
        void Lines(string text, string? fontSize = null) { foreach (var value in text.Replace("\r", "").Split('\n')) body.Add(P(value, fontSize ?? size, line: line, after: 100)); }
        var header = main.AddNewPart<HeaderPart>(); ResearchWrite(header, new XElement(W + "hdr", P(t.Header, "18", align: "right")));
        var footer = main.AddNewPart<FooterPart>(); var foot = P(t.Footer + "    ·    ", "18", align: "center");
        foot.Add(new XElement(W + "fldSimple", new XAttribute(W + "instr", "PAGE"), new XElement(W + "r", new XElement(W + "t", "1")))); ResearchWrite(footer, new XElement(W + "ftr", foot));
        if (t.Cover) { body.Add(Spacer(1800)); body.Add(P(draft.Title, "44", align: "center", line: 700)); body.Add(P(draft.Stage + " · " + draft.Grade + " · " + draft.Subject, "26", align: "center", line: 600)); body.Add(P(teacher ? "教师参考版" : "学生讲义", "24", align: "center")); body.Add(Break()); }
        body.Add(P(draft.Title, "32", align: "center", line: 520, after: 180));
        body.Add(P(draft.Grade + " · " + draft.Subject + " · " + (teacher ? "教师参考版" : "学生讲义"), "20", align: "center", after: 220));
        if (!teacher) Lines("姓名：____________    日期：____________");
        var sections = t.Sections.Concat(draft.Items.Select(q => q.Section)).Distinct(); var number = 0;
        foreach (var section in sections) {
            var items = draft.Items.Where(q => q.Section == section).ToArray(); var note = draft.Notes.GetValueOrDefault(section, "");
            if (items.Length == 0 && note.Length == 0) continue;
            var heading = P(section, "26", border: true, line: 480, after: 160); heading.Element(W + "pPr")!.Add(new XElement(W + "keepNext")); body.Add(heading);
            if (note.Length > 0) Lines(note);
            foreach (var item in items) {
                ct.ThrowIfCancellationRequested(); number++;
                var label = P($"第 {number} 题" + (item.Type.Length > 0 ? " · " + item.Type : ""), size, line: line); label.Element(W + "pPr")!.Add(new XElement(W + "keepNext")); body.Add(label);
                if (item.Blocks.Length > 0) {
                    Guard.That(source != null, "原稿素材缺失，请重新上传讲义", 422);
                    body.Add(ComposeBlocks(main, source!.MainDocumentPart!, item.Blocks, "r" + number, item.Label,
                        line: line, maxImageWidth: (width - margin * 2) * 635L));
                } else body.Add(await ResearchContent(main, item.Stem, dir, "stem" + number, size, line, (width - margin * 2) * 635L, ct));
                if (teacher) {
                    Lines("参考答案：" + (item.Answer.Length == 0 ? "尚未录入，请教师补充" : ""));
                    if (item.Answer.Length > 0) body.Add(await ResearchContent(main, item.Answer, dir, "answer" + number, size, line, (width - margin * 2) * 635L, ct));
                    if (item.Analysis.Length > 0) { Lines("解析："); body.Add(await ResearchContent(main, item.Analysis, dir, "analysis" + number, size, line, (width - margin * 2) * 635L, ct)); }
                    Lines("知识点：" + string.Join("、", item.Knowledge) + "    来源：" + item.Source, "18");
                } else for (var j = 0; j < (item.AnswerLines < 0 ? t.AnswerLines : item.AnswerLines); j++) body.Add(Spacer(line));
                body.Add(Spacer(160));
            }
        }
        var sect = Section(width); sect.Element(W + "pgSz")!.SetAttributeValue(W + "h", height);
        foreach (var side in new[] { "top", "bottom", "left", "right" }) sect.Element(W + "pgMar")!.SetAttributeValue(W + side, margin);
        sect.AddFirst(new XElement(W + "headerReference", new XAttribute(W + "type", "default"), new XAttribute(R + "id", main.GetIdOfPart(header))),
            new XElement(W + "footerReference", new XAttribute(W + "type", "default"), new XAttribute(R + "id", main.GetIdOfPart(footer))));
        body.Add(sect); Write(main, new XElement(W + "document", new XAttribute(XNamespace.Xmlns + "w", W), new XElement(W + "body", body.Elements())));
        return path;
    }
    static void ResearchWrite(OpenXmlPart part, XElement root) { using var stream = part.GetStream(FileMode.Create, FileAccess.Write); root.Save(stream); }
    async Task<List<XElement>> ResearchContent(MainDocumentPart target, string input, string dir, string key, string size, int line, long maxWidth, CancellationToken ct)
    {
        if (!Regex.IsMatch(input, @"<[a-zA-Z/]|\$|\\\(|\\\[")) return input.Replace("\r", "").Split('\n').Select(s => P(s, size, line: line)).ToList();
        Guard.That(!Regex.IsMatch(input, @"<svg\b", RegexOptions.IgnoreCase), "题目包含暂不支持的内嵌 SVG，请改用 PNG 图片后导出", 422);
        var inputPath = Path.Combine(dir, key + "-math.json"); var output = Path.Combine(dir, key + "-parsed.json");
        await File.WriteAllTextAsync(inputPath, Json.Write(input), ct);
        await Run(config["Documents:Node"] ?? "node", [Path.Combine(AppContext.BaseDirectory, "research_math.mjs"), inputPath, output], ct);
        var parsed = Json.Read<ResearchMathInput>(await File.ReadAllTextAsync(output, ct));
        var images = new Dictionary<string,string>();
        foreach (var url in ResearchMedia.Sources(parsed.Html)) {
            var image = await ResearchMedia.Fetch(url, modelClients, ct); var path = Path.Combine(dir, key + "-image-" + images.Count + (image.Type == "image/png" ? ".png" : ".jpg"));
            await File.WriteAllBytesAsync(path, image.Bytes, ct); images.Add(url, path);
        }
        var html = Path.Combine(dir, key + ".html");
        await File.WriteAllTextAsync(html, "<!doctype html><html><head><meta charset=\"utf-8\"><style>body{font-family:serif;font-size:" + (int.Parse(size)/2) + "pt;}table{border-collapse:collapse}td,th{border:1px solid #999;padding:5px}p{margin:0 0 6pt}img{max-width:100%}</style></head><body>" + ResearchMedia.SafeHtml(parsed.Html, images) + "</body></html>", ct);
        var converted = await Convert(html, "docx:Office Open XML Text", dir, ct);
        using var doc = WordprocessingDocument.Open(converted, false);
        var blocks = doc.MainDocumentPart!.Document!.Body!.ChildElements.Where(e => e.LocalName != "sectPr").Select(e => Block(XElement.Parse(e.OuterXml))).ToArray();
        var content = ComposeBlocks(target, doc.MainDocumentPart, blocks, key, "", line: line, maxImageWidth: maxWidth);
        foreach (var run in content.SelectMany(e => e.Descendants(W + "r"))) {
            var properties = run.Element(W + "rPr"); if (properties == null) { properties = new XElement(W + "rPr"); run.AddFirst(properties); }
            properties.Elements(W + "sz").Remove(); properties.Add(new XElement(W + "sz", new XAttribute(W + "val", size)));
        }
        foreach (var pair in parsed.Math) {
            var math = ResearchMath.ToOfficeMath(pair.Value);
            var matches = content.SelectMany(e => e.Descendants(W + "t")).Where(e => e.Value.Contains(pair.Key)).ToArray();
            Guard.That(matches.Length > 0, "公式排版占位符丢失，请核对题目后重试", 422);
            foreach (var text in matches) {
                var run = text.Parent!; var parts = text.Value.Split(pair.Key); var replacements = new List<XElement>();
                for (var i=0; i<parts.Length; i++) { if (parts[i].Length > 0) replacements.Add(new XElement(W+"r",new XElement(W+"t",new XAttribute(XNamespace.Xml+"space","preserve"),parts[i]))); if(i<parts.Length-1)replacements.Add(new XElement(math)); }
                run.ReplaceWith(replacements);
            }
        }
        return content;
    }
}

public record ResearchMathInput(string Html, Dictionary<string,string> Math);
