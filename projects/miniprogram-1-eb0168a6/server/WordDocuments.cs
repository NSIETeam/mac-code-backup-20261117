using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using System.Diagnostics;
using System.IO.Compression;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using UglyToad.PdfPig;
namespace Siyue;

public partial class WordDocuments(IConfiguration config, IObjects objects, Data db, LearningModelSettings modelSettings, IHttpClientFactory modelClients, FormulaSettings formulaSettings)
{
    public const string BookletTemplateVersion = "v16-half-page-verified";
    public const string DocxType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static readonly XNamespace W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    public static readonly XNamespace R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static readonly XNamespace A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static readonly XNamespace WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static Regex Start => QuestionRecognition.NamedOnly ? QuestionRecognition.NamedStart : LegacyStart;
    static readonly Regex LegacyStart = new(@"^\s*(?<label>[【\[]?\s*(?<family>例(?:题)?|练(?:习|习题)?|测(?:试题|试)?|习题|题)\s*[0-9０-９一二三四五六七八九十百]+(?:\s*[-－﹣—]\s*[0-9０-９]+)*\s*[】\]]?\s*[.．、:：]?|第\s*[0-9０-９一二三四五六七八九十百]+\s*题[.．、:：]?|[【\[]\s*[0-9０-９]{1,3}\s*[】\]]|[0-9０-９]{1,3}\s*[.．、](?![0-9０-９]))\s*", RegexOptions.Compiled);
    public static bool IsQuestionStart(string text) => Start.IsMatch(text);
    public static ContentBlock Block(XElement xml) => new(xml.ToString(SaveOptions.DisableFormatting), string.Concat(xml.Descendants().Where(x => x.Name.LocalName == "t").Select(x => x.Value)), xml.Name == W + "tbl" ? "table" : "paragraph");
    public static IEnumerable<XElement> ImageNodes(XElement xml) => xml.Descendants().Where(x => x.Name == A + "blip" || x.Name.LocalName == "imagedata");
    public static bool ReadableBlocks(Question q) {
        try { var blocks = Json.Read<ContentBlock[]>(q.BlocksJson); return blocks.Length > 0 && blocks.All(b => XElement.Parse(b.Xml) != null); }
        catch (Exception e) when (e is System.Text.Json.JsonException or System.Xml.XmlException or ArgumentException) { return false; }
    }
    public static string[] ImageReferences(string xml) => ImageNodes(XElement.Parse(xml)).Select(x => x.Attribute(R + "embed")?.Value ?? x.Attribute(R + "id")?.Value ?? "").Where(x => x.Length > 0).ToArray();
    public static async Task SyncAssets(Data db, Question q)
    {
        await db.QuestionAssets.Where(x => x.QuestionId == q.Id).ExecuteDeleteAsync();
        var assets = await db.Assets.Where(x => x.HandoutId == q.HandoutId).ToDictionaryAsync(x => x.RelationshipId); var blocks = Json.Read<ContentBlock[]>(q.BlocksJson);
        for (var i = 0; i < blocks.Length; i++) {
            var occurrence = 0;
            foreach (var node in ImageNodes(XElement.Parse(blocks[i].Xml))) {
                var rid = node.Attribute(R + "embed")?.Value ?? node.Attribute(R + "id")?.Value ?? ""; if (!assets.TryGetValue(rid, out var asset)) continue;
                var size = node.Ancestors().SelectMany(x => x.Elements(WP + "extent")).FirstOrDefault();
                db.QuestionAssets.Add(new QuestionAsset { QuestionId = q.Id, AssetId = asset.Id, BlockIndex = i, Occurrence = occurrence++, Width = (long?)size?.Attribute("cx") ?? 0, Height = (long?)size?.Attribute("cy") ?? 0 });
            }
        }
    }
    public static void CheckPackage(string path)
    {
        using var zip = ZipFile.OpenRead(path);
        ValidateArchive(zip);
    }
    static void ValidateArchive(ZipArchive zip)
    {
        Guard.That(zip.Entries.Count <= 5000 && zip.Entries.Sum(x => x.Length) <= 209_715_200, "Word 解压后过大或结构过于复杂");
        Guard.That(zip.Entries.Select(x => x.FullName).Distinct(StringComparer.OrdinalIgnoreCase).Count() == zip.Entries.Count, "Word 包含重复的文件部件");
        Guard.That(zip.Entries.All(x => !x.FullName.Contains("vbaProject", StringComparison.OrdinalIgnoreCase)), "不接受带宏的 Word 文件");
    }
    public async Task<string> Materialize(string key, string dir, string name, CancellationToken ct)
    { var path = Path.Combine(dir, name); await using var input = await objects.Get(key, ct); await using var output = File.Create(path); await input.CopyToAsync(output, ct); return path; }
    public async Task<string> Convert(string input, string format, string dir, CancellationToken ct)
    {
        var outDir = Path.Combine(dir, Guid.NewGuid().ToString("N")); Directory.CreateDirectory(outDir);
        input = PrepareWordCopy(input, outDir);
        var profile = Path.Combine(outDir, "profile"); Directory.CreateDirectory(profile);
        await Run(config["Documents:Soffice"] ?? "soffice", ["-env:UserInstallation=" + new Uri(profile).AbsoluteUri, "--headless", "--nologo", "--nodefault", "--norestore", "--convert-to", format, "--outdir", outDir, input], ct);
        var output = Path.Combine(outDir, Path.GetFileNameWithoutExtension(input) + "." + format.Split(':')[0]);
        Guard.That(File.Exists(output) && new FileInfo(output).Length > 0, "Word 转换失败，请检查是否加密或文件损坏"); return PrepareWordCopy(output, outDir);
    }
    public async Task Run(string executable, IEnumerable<string> args, CancellationToken ct)
    {
        var info = new ProcessStartInfo(executable) { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
        foreach (var arg in args) info.ArgumentList.Add(arg);
        string? fontConfig = null;
        if (config["Documents:FontDirectory"] is string fonts) {
            info.Environment["SAL_FONTPATH"] = fonts;
            fontConfig = Path.Combine(Path.GetTempPath(), "siyue-fonts-" + Guid.NewGuid().ToString("N") + ".conf");
            new XElement("fontconfig", new XElement("dir", fonts), new XElement("cachedir", Path.Combine(Path.GetTempPath(), "siyue-font-cache"))).Save(fontConfig);
            info.Environment["FONTCONFIG_FILE"] = fontConfig;
        }
        using var p = Process.Start(info) ?? throw new InvalidOperationException("无法启动文档处理程序");
        var stdout = p.StandardOutput.ReadToEndAsync(ct); var stderr = p.StandardError.ReadToEndAsync(ct);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(config.GetValue("Documents:TimeoutSeconds", 120)));
        try { await p.WaitForExitAsync(timeout.Token); } catch { try { p.Kill(true); } catch { } ct.ThrowIfCancellationRequested(); throw new ApiException(422, "文档处理超时或已取消，请检查文件后重试"); }
        await Task.WhenAll(stdout, stderr); if (fontConfig != null) File.Delete(fontConfig); Guard.That(p.ExitCode == 0, "文档处理失败，请检查源文件及转换环境", 422);
    }
    public async Task<List<Question>> Parse(string path, Guid handoutId, CancellationToken ct, bool persistAssets = true)
    {
        CheckPackage(path); using var doc = WordprocessingDocument.Open(path, false); var main = doc.MainDocumentPart ?? throw new ApiException(422, "Word 缺少正文");
        var root = XElement.Parse((main.Document ?? throw new ApiException(422, "Word 正文无效")).OuterXml); var body = root.Element(W + "body") ?? throw new ApiException(422, "Word 缺少正文");
        var numbering = main.NumberingDefinitionsPart?.Numbering != null ? XElement.Parse(main.NumberingDefinitionsPart.Numbering.OuterXml) : new XElement(W + "numbering");
        var styles = main.StyleDefinitionsPart?.Styles != null ? XElement.Parse(main.StyleDefinitionsPart.Styles.OuterXml) : new XElement(W + "styles");
        foreach (var pair in main.Parts.Where(x => persistAssets && x.OpenXmlPart is ImagePart)) {
            var asset = new Asset { HandoutId = handoutId, RelationshipId = pair.RelationshipId, ObjectKey = $"handouts/{handoutId}/assets/{Guid.NewGuid():N}", ContentType = pair.OpenXmlPart.ContentType };
            await using var source = pair.OpenXmlPart.GetStream(); await objects.Put(asset.ObjectKey, source, asset.ContentType, ct); db.Assets.Add(asset);
        }
        var counters = new Dictionary<(string, int), int>(); var output = new List<Question>(); var blocks = new List<ContentBlock>(); var issues = new List<Issue>(); string label = "待定题号", family = "unclassified", section = "";
        void Flush() {
            if (QuestionRecognition.NamedOnly && family == "unclassified" && !issues.Any(i => i.Blocking)) {
                // Word concept paragraphs may be excluded in named mode, but an
                // unassigned PDF crop can contain a diagram or part of a question.
                if(blocks.Any(b=>{var xml=XElement.Parse(b.Xml);return IsPdfRaster(xml)&&PdfOrigin(xml)!=null;})) {
                    label="待核对归属";family="pdf-orphan";
                    // Store the uncertainty on the crop as well, so integrity
                    // rechecks cannot accidentally clear the blocking review.
                    blocks=blocks.Select(b=>{
                        var xml=XElement.Parse(b.Xml);var origin=PdfOrigin(xml);
                        if(!IsPdfRaster(xml)||origin==null)return b;
                        xml.Descendants(WP+"docPr").First().SetAttributeValue("title","siyue-pdf:"+Json.Write(origin with{Checks=origin.Checks.Append("orphan-prefix").Distinct().ToArray()}));
                        return Block(xml);
                    }).ToList();
                } else {blocks.Clear();issues.Clear();return;}
            }
            if (blocks.Count == 0 || (!issues.Any(i => i.Blocking) && blocks.All(b => b.Text.Trim().Length == 0 && ImageReferences(b.Xml).Length == 0 && !b.Xml.Contains("oMath")))) { blocks.Clear(); issues.Clear(); return; }
            output.Add(new Question { HandoutId = handoutId, Label = label, Family = family == "ai" ? "ai" : section + family, Order = output.Count + 1, BlocksJson = Json.Write(blocks), IssuesJson = Json.Write(issues.Distinct()) }); blocks = []; issues = [];
        }
        // Block content controls wrap paragraphs, but tables must stay whole.
        IEnumerable<XElement> BodyBlocks(IEnumerable<XElement> elements) {
            foreach (var element in elements) {
                if (element.Name == W + "sdt" && element.Element(W + "sdtContent") is XElement content) {
                    foreach (var child in BodyBlocks(content.Elements())) yield return child;
                } else if (element.Name == W + "tbl") {
                    // Only explicit example/exercise markers split table rows; numeric data cells stay intact.
                    var rows = element.Elements(W + "tr").ToArray(); var groups = new List<List<XElement>>();
                    foreach (var row in rows) {
                        var first = row.Descendants(W + "p").FirstOrDefault(); var m = Start.Match(first == null ? "" : Block(first).Text.Trim());
                        if (groups.Count == 0 || m.Success && m.Groups["family"].Success) groups.Add([]);
                        groups[^1].Add(row);
                    }
                    if (groups.Count > 1 && !element.Descendants(W + "vMerge").Any()) {
                        foreach (var group in groups) yield return new XElement(W + "tbl", element.Elements().Where(x => x.Name != W + "tr"), group);
                    } else yield return element;
                } else if (element.Name != W + "sectPr") yield return element;
            }
        }
        foreach (var element in BodyBlocks(body.Elements())) {
            var xml = new XElement(element); var blockIssues = new List<Issue>();
            if(xml.Element(W+"pPr")?.Element(W+"pStyle")?.Attribute(W+"val")?.Value is "SiyuePdfMargin" or "SiyueAiFrontMatter")continue;
            if (IsPdfReview(xml)) {
                if (blocks.Count > 0) issues.Add(new Issue("pdf-page-boundary", "下一页需要校对，请对照原稿检查本题是否跨页", false));
                Flush(); label = Regex.Match(Block(xml).Text, @"^第\s*\d+\s*页待校对").Value;
                if (label.Length == 0) label = "PDF 页面待校对";
                family = "pdf-review"; blocks.Add(Block(xml));
                issues.Add(new Issue("pdf-page-review", Block(xml).Text, true));
                continue;
            }
            if (xml.Element(W + "pPr")?.Element(W + "pStyle")?.Attribute(W + "val")?.Value == "SiyuePdfHeading") { Flush(); section=Block(xml).Text.Trim()+":";label="待定题号";family="unclassified";continue; }
            if (xml.Element(W + "pPr")?.Element(W + "pStyle")?.Attribute(W + "val")?.Value == "SiyuePdfContext") { Flush(); section = Block(xml).Text.Trim() + ":"; label = "待定题号"; family = "unclassified"; continue; }
            if(PdfOrigin(xml)?.Checks.Contains("orphan-prefix")==true && !IsAiLayout(PdfOrigin(xml))) { Flush();label="待核对归属";family="pdf-orphan";blocks.Add(Block(xml));Flush();label="待定题号";family="unclassified";continue; }
            if (xml.DescendantsAndSelf().Any(x => x.Name.LocalName is "object" or "OLEObject" or "txbxContent" or "altChunk")) blockIssues.Add(new Issue("unsupported-object", "存在旧式公式、文本框或嵌入对象，请修改原稿后替换", true));
            foreach (var anchor in xml.Descendants(WP + "anchor").ToArray()) {
                anchor.ReplaceWith(new XElement(WP + "inline", anchor.Elements().Where(x => x.Name.LocalName is "extent" or "effectExtent" or "docPr" or "cNvGraphicFramePr" or "graphic").Select(x => new XElement(x))));
                blockIssues.Add(new Issue("floating-image", "浮动图片已转为行内，请核对图片归属和顺序", false));
            }
            if (IsPdfRaster(xml)) blockIssues.Add(new Issue("pdf-layout", "PDF 原版面题目，请核对讲次、题号、图文归属和跨页内容；复杂排版请修改源文件", false));
            // AI boundaries carry whole source regions, independently of printed numbering.
            var aiOrigin = PdfOrigin(xml);
            if (IsPdfRaster(xml) && IsAiLayout(aiOrigin)) {
                var description = (string?)xml.Descendants(WP + "docPr").FirstOrDefault()?.Attribute("descr");
                if (description == "question") {
                    Flush(); label = string.IsNullOrWhiteSpace(aiOrigin!.Label) ? $"题{output.Count + 1}" : aiOrigin.Label.Trim(); family = "ai";
                } else if (description == "continuation") {
                    var first = blocks.Count == 0 ? null : XElement.Parse(blocks[0].Xml);
                    var aiRoot = first == null ? null : PdfOrigin(first);
                    var last = blocks.Count == 0 ? null : PdfOrigin(XElement.Parse(blocks[^1].Xml));
                    var target = AiCheckValue(aiOrigin!, "ai-continuation:");
                    var canContinue = family == "ai" && IsAiLayout(aiRoot) && IsAiLayout(last)
                        && first!.Descendants(WP + "docPr").Any(x => (string?)x.Attribute("descr") == "question")
                        && AiRegionId(aiRoot!) is string rootId && target == rootId
                        && aiOrigin!.Page > aiRoot!.Page && aiOrigin.Page >= last!.Page;
                    if (!canContinue) {
                        Flush(); label = string.IsNullOrWhiteSpace(aiOrigin!.Label) ? $"续题{output.Count + 1}（待核对）" : aiOrigin.Label.Trim(); family = "ai";
                        blockIssues.Add(new Issue("pdf-ai-continuation", "续题与紧邻上一题的关联不一致，已独立保留；请对照原稿调整归属。", true));
                    }
                } else {
                    Flush(); label = string.IsNullOrWhiteSpace(aiOrigin!.Label) ? $"题{output.Count + 1}（待核对）" : aiOrigin.Label.Trim(); family = "ai";
                    blockIssues.Add(new Issue("pdf-ai-boundary", "AI题目区域缺少明确的起题或续题标记，已保留原图待校对。", true));
                }
                blocks.Add(Block(xml)); issues.AddRange(blockIssues); continue;
            }
            var text = Block(xml).Text.Trim(); var nestedList = false;
            if (xml.Name == W + "p") {
                var np = xml.Element(W + "pPr")?.Element(W + "numPr"); var style = xml.Element(W + "pPr")?.Element(W + "pStyle")?.Attribute(W + "val")?.Value; var visited = new HashSet<string>();
                var numId = np?.Element(W + "numId")?.Attribute(W + "val")?.Value; var inheritedLevel = (int?)np?.Element(W + "ilvl")?.Attribute(W + "val");
                while (style != null && visited.Add(style) && (numId == null || inheritedLevel == null)) {
                    var st = styles.Elements(W + "style").FirstOrDefault(x => (string?)x.Attribute(W + "styleId") == style); var inherited = st?.Element(W + "pPr")?.Element(W + "numPr");
                    numId ??= inherited?.Element(W + "numId")?.Attribute(W + "val")?.Value;
                    inheritedLevel ??= (int?)inherited?.Element(W + "ilvl")?.Attribute(W + "val"); style = st?.Element(W + "basedOn")?.Attribute(W + "val")?.Value;
                }
                var level = inheritedLevel ?? 0; nestedList = level > 0;
                if (numId != null && numId != "0") {
                    var num = numbering.Elements(W + "num").FirstOrDefault(x => (string?)x.Attribute(W + "numId") == numId); var absId = num?.Element(W + "abstractNumId")?.Attribute(W + "val")?.Value;
                    var lvl = numbering.Elements(W + "abstractNum").FirstOrDefault(x => (string?)x.Attribute(W + "abstractNumId") == absId)?.Elements(W + "lvl").FirstOrDefault(x => (int?)x.Attribute(W + "ilvl") == level);
                    var levelOverride = num?.Elements(W + "lvlOverride").FirstOrDefault(x => (int?)x.Attribute(W + "ilvl") == level);
                    var overrideDefinition = levelOverride?.Element(W + "lvl");
                    var format = (overrideDefinition?.Element(W + "lvlText") ?? lvl?.Element(W + "lvlText"))?.Attribute(W + "val")?.Value;
                    if (format != null && (overrideDefinition?.Element(W + "numFmt") ?? lvl?.Element(W + "numFmt"))?.Attribute(W + "val")?.Value == "decimal") {
                        var start = (int?)levelOverride?.Element(W + "startOverride")?.Attribute(W + "val") ?? (int?)(overrideDefinition?.Element(W + "start") ?? lvl?.Element(W + "start"))?.Attribute(W + "val") ?? 1;
                        var n = counters.GetValueOrDefault((numId, level), start - 1) + 1; counters[(numId, level)] = n;
                        foreach (var key in counters.Keys.Where(k => k.Item1 == numId && k.Item2 > level).ToArray()) counters.Remove(key);
                        var rendered = Regex.Replace(format, @"%([1-9])", m => counters.GetValueOrDefault((numId, int.Parse(m.Groups[1].Value) - 1), 1).ToString());
                        // Nested list items remain inside their parent question.
                        if (level == 0 && Start.IsMatch(rendered)) { var pp = xml.Element(W + "pPr"); pp?.Element(W + "numPr")?.Remove(); if (pp == null) { pp = new XElement(W + "pPr"); xml.AddFirst(pp); } pp.Add(new XElement(W + "numPr", new XElement(W + "numId", new XAttribute(W + "val", 0)))); pp.AddAfterSelf(new XElement(W + "r", new XElement(W + "t", rendered + " "))); text = rendered + " " + text; }
                    }
                }
            }
            var firstParagraph = xml.Descendants(W + "p").FirstOrDefault();
            var tableStart = firstParagraph == null ? Match.Empty : Start.Match(Block(firstParagraph).Text.Trim());
            var pdfContinuation = IsPdfRaster(xml) && xml.Descendants(WP + "docPr").Any(x => (string?)x.Attribute("descr") == "continuation");
            var match = xml.Name == W + "p" ? (nestedList || pdfContinuation ? Match.Empty : Start.Match(text)) : tableStart.Success && tableStart.Groups["family"].Success ? tableStart : Match.Empty;
            if (xml.Name == W + "tbl" && xml.Descendants(W + "p").Count(p => { var m = Start.Match(Block(p).Text.Trim()); return m.Success && m.Groups["family"].Success; }) > 1)
                blockIssues.Add(new Issue("table-question-boundary", "合并单元格内包含多道题，请整理原稿中的题目边界后重新上传", true));
            if (match.Success) { Flush(); label = PdfOrigin(xml)?.Label ?? match.Groups["label"].Value.Trim(); family = match.Groups["family"].Success ? match.Groups["family"].Value : "number"; }
            else if (xml.Name == W + "p" && !blockIssues.Any(i => i.Blocking) && ImageReferences(xml.ToString()).Length == 0 && !xml.ToString().Contains("oMath") && BookStructure.IsHeading(text)) { if (BookStructure.TitleKey(section) == BookStructure.TitleKey(text + ":")) continue; Flush(); section = text + ":"; label = "待定题号"; family = "unclassified"; continue; }
            if (family == "unclassified" && text.Length > 0 && !issues.Any(x => x.Code == "unclassified")) issues.Add(new Issue("unclassified", "这段内容没有识别到题号，请修改题号或与相邻题合并", false));
            blocks.Add(Block(xml)); issues.AddRange(blockIssues);
        }
        Flush(); Guard.That(output.Count > 0 && output.Any(q => Json.Read<ContentBlock[]>(q.BlocksJson).Any(b => b.Text.Trim().Length > 0 || IsAiLayout(PdfOrigin(XElement.Parse(b.Xml))) && ImageReferences(b.Xml).Length > 0)), "没有可拆分的正文；扫描图片式 Word 暂不支持", 422);
        foreach (var group in output.GroupBy(x => (x.Family, x.Label)).Where(g => g.Count() > 1)) foreach (var q in group) q.IssuesJson = Json.Write(Json.Read<Issue[]>(q.IssuesJson).Append(new Issue("duplicate-label", "本节题号重复，请校对题号", false)));
        AddPdfIntegrityIssues(output);
        return output;
    }
    public async Task<(string[] Keys, bool Half)> Preview(Question q, string dir, CancellationToken ct)
    {
        var source = await db.Handouts.FindAsync([q.HandoutId], ct) ?? throw new InvalidOperationException();
        if (await TryAiPreview(q, source.LectureId, dir, ct) is { } aiPreview) return aiPreview;
        // A batch can contain hundreds of questions from the same large package.
        // Reuse its read-only source within this task's private directory.
        var sourceStamp = System.Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(source.NormalizedKey)));
        var input = Path.Combine(dir, "preview-source-" + sourceStamp + ".docx");
        if (!File.Exists(input)) {
            var staged = await Materialize(source.NormalizedKey, dir, "preview-source-" + sourceStamp + ".partial", ct);
            File.Move(staged, input, true);
        }
        var (pages, half) = await RenderQuestionPreview(input, q.Id, null, Json.Read<ContentBlock[]>(q.BlocksJson), dir, ct);
        var keys = new List<string>();
        for (var p = 0; p < pages.Length; p++) {
            var key = $"questions/{q.Id}/{q.Revision}-{Guid.NewGuid():N}-{p + 1}.png";
            await using var png = File.OpenRead(pages[p]);
            await objects.Put(key, png, "image/png", ct);
            keys.Add(key);
        }
        return (keys.ToArray(), half);
    }
    public async Task<(string[] Pages, bool Half)> RenderQuestionPreview(string input, Guid id, string? label, ContentBlock[] blocks, string dir, CancellationToken ct)
    {
        var probe = Path.Combine(dir, id + ".docx");
        var marker = "SYEND" + id.ToString("N");
        using (var pack = WordprocessingDocument.Open(input, false)) {
            using var target = WordprocessingDocument.Create(probe, DocumentFormat.OpenXml.WordprocessingDocumentType.Document);
            var main = target.AddMainDocumentPart(); var content = ComposeBlocks(main, pack.MainDocumentPart!, blocks, "p", label);
            content.Add(P(marker, size: "16")); content.Add(Section(9300)); Write(main, new XElement(W + "document", new XAttribute(XNamespace.Xmlns + "w", W), new XElement(W + "body", content)));
        }
        var pdfPath = await Convert(probe, "pdf", dir, ct); using var pdf = PdfDocument.Open(pdfPath); var last = pdf.GetPage(pdf.NumberOfPages);
        var end = last.GetWords().FirstOrDefault(w => w.Text.Contains("SYEND"));
        if (end == null && config["Documents:DiagnosticsDirectory"] is string debugDirectory) { Directory.CreateDirectory(debugDirectory); File.Copy(probe, Path.Combine(debugDirectory, "probe.docx"), true); File.Copy(pdfPath, Path.Combine(debugDirectory, "probe.pdf"), true); File.WriteAllText(Path.Combine(debugDirectory, "probe.txt"), string.Join("\n", pdf.GetPages().Select(p => p.Text))); }
        Guard.That(end != null, "题目预览排版测量失败", 422); var endTop = last.Height - end!.BoundingBox.Top;
        var pages = new List<string>();
        for (var p = 1; p <= pdf.NumberOfPages; p++) {
            var prefix = Path.Combine(dir, $"q-{id}-{p}"); var args = new List<string> { "-f", p.ToString(), "-l", p.ToString(), "-singlefile", "-r", "110", "-png" };
            if (p == pdf.NumberOfPages) { args.AddRange(["-H", Math.Max(1, (int)((endTop - 2) * 110 / 72)).ToString()]); }
            args.AddRange([pdfPath, prefix]); await Run(config["Documents:PdfToPpm"] ?? "pdftoppm", args, ct);
            pages.Add(prefix + ".png");
        }
        return (pages.ToArray(), pdf.NumberOfPages == 1 && endTop - 34 < 175);
    }
    public async Task<PdfPreviewPage[]> RenderPdfPages(string path, string prefix, string dir, CancellationToken ct)
    {
        using var pdf = PdfDocument.Open(path);
        Guard.That(pdf.NumberOfPages is > 0 and <= 300, "文档页数超出预览范围", 422);
        var pages = new List<PdfPreviewPage>();
        for (var number = 1; number <= pdf.NumberOfPages; number++) {
            ct.ThrowIfCancellationRequested();
            var page = pdf.GetPage(number); var scale = 1600d / Math.Max(page.Width, page.Height);
            var output = Path.Combine(dir, $"preview-page-{number}");
            await Run(config["Documents:PdfToPpm"] ?? "pdftoppm", ["-f", number.ToString(), "-l", number.ToString(), "-singlefile", "-scale-to", "1600", "-png", path, output], ct);
            var key = $"{prefix}/page-{number}.png";
            await using var image = File.OpenRead(output + ".png"); await objects.Put(key, image, "image/png", ct);
            pages.Add(new(key, number, (int)Math.Ceiling(page.Width * scale), (int)Math.Ceiling(page.Height * scale)));
        }
        return pages.ToArray();
    }
    static List<XElement> ComposeBlocks(MainDocumentPart target, MainDocumentPart source, ContentBlock[] blocks, string prefix, string? label, int line = 360, int after = 60, long maxImageWidth = 4_800_000, bool restorePdfScale = false, bool compactBookletFlow = false)
    {
        var styleMap = new Dictionary<string, string>(); var numMap = new Dictionary<string, string>(); var relMap = new Dictionary<string, string>();
        var stylePart = target.StyleDefinitionsPart ?? target.AddNewPart<StyleDefinitionsPart>(); var styles = stylePart.Styles != null ? XElement.Parse(stylePart.Styles.OuterXml) : new XElement(W + "styles", new XElement(W + "docDefaults", new XElement(W + "rPrDefault", new XElement(W + "rPr", Fonts(), new XElement(W + "sz", new XAttribute(W + "val", "21"))))));
        if (source.StyleDefinitionsPart?.Styles != null) {
            var incoming = XElement.Parse(source.StyleDefinitionsPart.Styles.OuterXml); foreach (var s in incoming.Elements(W + "style")) { var id = (string?)s.Attribute(W + "styleId"); if (id != null) styleMap[id] = prefix + id; }
            foreach (var s in incoming.Elements(W + "style")) { var copy = new XElement(s); copy.Attribute(W + "default")?.Remove(); copy.SetAttributeValue(W + "styleId", styleMap[(string)copy.Attribute(W + "styleId")!]); RemapStyles(copy, styleMap); styles.Add(copy); }
        }
        stylePart.Styles = new Styles(styles.ToString(SaveOptions.DisableFormatting));
        if (source.NumberingDefinitionsPart?.Numbering != null) {
            var np = target.NumberingDefinitionsPart ?? target.AddNewPart<NumberingDefinitionsPart>(); var nums = np.Numbering != null ? XElement.Parse(np.Numbering.OuterXml) : new XElement(W + "numbering"); var incoming = XElement.Parse(source.NumberingDefinitionsPart.Numbering.OuterXml); var offset = nums.Elements().Select(x => (int?)x.Attribute(W + (x.Name.LocalName == "num" ? "numId" : "abstractNumId")) ?? 0).DefaultIfEmpty().Max() + 1;
            foreach (var n in incoming.Elements(W + "abstractNum")) { var copy = new XElement(n); copy.SetAttributeValue(W + "abstractNumId", (int)copy.Attribute(W + "abstractNumId")! + offset); RemapStyles(copy, styleMap); nums.AddFirst(copy); }
            foreach (var n in incoming.Elements(W + "num")) { var copy = new XElement(n); var old = (string)copy.Attribute(W + "numId")!; var newId = (int.Parse(old) + offset).ToString(); numMap[old] = newId; copy.SetAttributeValue(W + "numId", newId); var abs = copy.Element(W + "abstractNumId")!; abs.SetAttributeValue(W + "val", (int)abs.Attribute(W + "val")! + offset); nums.Add(copy); }
            np.Numbering = new Numbering(nums.ToString(SaveOptions.DisableFormatting));
        }
        var output = blocks.Select(b => XElement.Parse(b.Xml)).ToList();
        foreach (var xml in output) {
            RemapStyles(xml, styleMap);
            foreach (var n in xml.Descendants(W + "numId")) { var value = (string?)n.Attribute(W + "val"); if (value != null && numMap.TryGetValue(value, out var id)) n.SetAttributeValue(W + "val", id); }
            foreach (var attribute in xml.DescendantsAndSelf().Attributes().Where(x => x.Name.Namespace == R).ToArray()) {
                var rid = attribute.Value;
                if (!relMap.TryGetValue(rid, out var mapped)) {
                    var pair = source.Parts.FirstOrDefault(x => x.RelationshipId == rid);
                    if (pair.OpenXmlPart != null) mapped = target.GetIdOfPart(target.AddPart(pair.OpenXmlPart));
                    else { var hyperlink = source.HyperlinkRelationships.FirstOrDefault(x => x.Id == rid); if (hyperlink != null) mapped = target.AddHyperlinkRelationship(hyperlink.Uri, true).Id; }
                    if (mapped != null) relMap[rid] = mapped;
                }
                if (mapped != null) attribute.Value = mapped;
            }
            foreach (var prop in xml.Descendants(W + "sectPr").ToArray()) prop.Remove();
            foreach (var b in xml.Descendants(W + "br").Where(x => (string?)x.Attribute(W + "type") == "page").ToArray()) b.Remove();
            foreach (var paragraph in xml.DescendantsAndSelf(W + "p")) { var pp = paragraph.Element(W + "pPr"); if (pp == null) { pp = new XElement(W + "pPr"); paragraph.AddFirst(pp); } pp.Elements(W + "spacing").Remove(); pp.Elements(W + "pageBreakBefore").Remove(); pp.Elements(W + "keepNext").Remove(); pp.Elements(W + "keepLines").Remove(); pp.Elements(W + "snapToGrid").Remove(); pp.Add(new XElement(W + "snapToGrid", new XAttribute(W + "val", "0"))); pp.Add(new XElement(W + "spacing", new XAttribute(W + "line", IsPdfRaster(paragraph) ? 240 : line), new XAttribute(W + "lineRule", "auto"), new XAttribute(W + "after", after))); }
            foreach (var run in xml.Descendants(W + "r")) { var rp = run.Element(W + "rPr"); if (rp == null) { rp = new XElement(W + "rPr"); run.AddFirst(rp); } rp.Elements(W + "rFonts").Remove(); rp.Elements(W + "sz").Remove(); rp.Add(Fonts(), new XElement(W + "sz", new XAttribute(W + "val", "21"))); }
            foreach (var extent in xml.Descendants(WP + "extent")) {
                var cx = (long?)extent.Attribute("cx") ?? 0; var cy = (long?)extent.Attribute("cy") ?? 0;
                if (cx <= 0 || cy <= 0) continue;
                var width = Math.Min(cx, maxImageWidth);
                // PDF crops are rendered at 180 dpi. Recover their original physical
                // width after removing the analysis column; keep small diagrams small.
                if (restorePdfScale && IsPdfRaster(xml)) {
                    var rid = (string?)extent.Parent?.Descendants(A + "blip").FirstOrDefault()?.Attribute(R + "embed");
                    if (rid != null && target.GetPartById(rid) is ImagePart image && image.ContentType == "image/png") {
                        using var stream = image.GetStream(); var header = new byte[24];
                        if (stream.Read(header) == header.Length && header[0] == 137 && header[1] == 80 && header[2] == 78 && header[3] == 71) {
                            var pixels = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(16, 4));
                            // AI/manual crops can reference an entire page image plus srcRect.
                            // Restore the visible crop's physical width, not the hidden full page.
                            var crop = extent.Parent?.Descendants(A + "srcRect").FirstOrDefault();
                            var visible = 1 - ((double?)crop?.Attribute("l") ?? 0) / 100000d - ((double?)crop?.Attribute("r") ?? 0) / 100000d;
                            if (double.IsFinite(visible) && visible is > 0 and <= 1)
                                width = Math.Min(maxImageWidth, Math.Max(1L, (long)Math.Round(pixels * 5080d * visible)));
                        }
                    }
                }
                extent.SetAttributeValue("cx", width); extent.SetAttributeValue("cy", cy * width / cx);
                foreach (var size in extent.Parent?.Descendants(A + "ext") ?? []) { size.SetAttributeValue("cx", width); size.SetAttributeValue("cy", cy * width / cx); }
            }
        }
        // Review images show the source. The editable number is displayed by
        // the client; document exports still supply a label to compose below.
        if (label == null) return output;
        if (compactBookletFlow) NormalizeBookletFlow(output);
        var first = output.FirstOrDefault(x => x.Name == W + "p");
        if (first != null && IsPdfRaster(first) && Start.Match(Block(first).Text).Groups["label"].Value.Trim() != label) output.Insert(0, P("题号：" + label));
        if (first != null && !IsPdfRaster(first)) {
            var firstProps = first.Element(W + "pPr"); firstProps?.Elements(W + "numPr").Remove(); firstProps?.Add(new XElement(W + "numPr", new XElement(W + "numId", new XAttribute(W + "val", 0))));
            var match = Start.Match(Block(first).Text); if (match.Success) { var remaining = match.Length; foreach (var text in first.Descendants(W + "t")) { var length = Math.Min(remaining, text.Value.Length); text.Value = text.Value[length..]; remaining -= length; if (remaining == 0) break; } }
            var run = new XElement(W + "r", new XElement(W + "rPr", Fonts(), new XElement(W + "sz", new XAttribute(W + "val", "21"))), new XElement(W + "t", new XAttribute(XNamespace.Xml + "space", "preserve"), label + " ")); var pp = first.Element(W + "pPr"); if (pp != null) pp.AddAfterSelf(run); else first.AddFirst(run);
        }
        return output;
    }
    static void RemapStyles(XElement xml, Dictionary<string, string> map) { foreach (var x in xml.Descendants().Where(x => x.Name.Namespace == W && x.Name.LocalName is "pStyle" or "rStyle" or "tblStyle" or "basedOn" or "next" or "link")) { var id = (string?)x.Attribute(W + "val"); if (id != null && map.TryGetValue(id, out var n)) x.SetAttributeValue(W + "val", n); } }
    static int PageCount(string path) { using var pdf = PdfDocument.Open(path); return pdf.NumberOfPages; }
    public static bool SparseTail(string path)
    {
        using var pdf = PdfDocument.Open(path); if (pdf.NumberOfPages < 2) return false;
        var page = pdf.GetPage(pdf.NumberOfPages); var inkHeight = 0d;
        // The left column is writing space, not a reason to preserve a mostly empty extra page.
        var columnStart = 34 + (page.Width - 68) / 4;
        var lines = page.GetWords().Where(w => w.BoundingBox.Left >= columnStart && w.BoundingBox.Top < page.Height - 34 && w.BoundingBox.Bottom > 34).GroupBy(w => Math.Round(w.BoundingBox.Bottom / 3));
        foreach (var line in lines) {
            var text = string.Concat(line.OrderBy(w => w.BoundingBox.Left).Select(w => w.Text)).Trim();
            if (text.Length == 0 || text.StartsWith("原题") || text == "正解" || text.All(c => char.IsWhiteSpace(c) || c is '_' or '＿')) continue;
            inkHeight += line.Max(w => w.BoundingBox.Top) - line.Min(w => w.BoundingBox.Bottom);
        }
        inkHeight += page.GetImages().Where(i => i.BoundingBox.Right > columnStart).Sum(i => i.BoundingBox.Height);
        return inkHeight < (page.Height - 68) * .4;
    }
    static XElement Cell(int width, IEnumerable<XElement> content) {
        var elements = content.ToList(); if (elements.LastOrDefault()?.Name != W + "p") elements.Add(Spacer());
        return new XElement(W + "tc", new XElement(W + "tcPr", new XElement(W + "tcW", new XAttribute(W + "w", width), new XAttribute(W + "type", "dxa")), new XElement(W + "vAlign", new XAttribute(W + "val", "top"))), elements);
    }
    static XElement Spacer(int height = 20) => new(W + "p", new XElement(W + "pPr", new XElement(W + "spacing", new XAttribute(W + "line", height), new XAttribute(W + "lineRule", "exact"), new XAttribute(W + "before", "0"), new XAttribute(W + "after", "0"))), new XElement(W + "r", new XElement(W + "rPr", new XElement(W + "sz", new XAttribute(W + "val", "2"))), new XElement(W + "t", " ")));
    static XElement Fonts() => new(W + "rFonts", new XAttribute(W + "ascii", "Source Han Serif SC"), new XAttribute(W + "hAnsi", "Source Han Serif SC"), new XAttribute(W + "eastAsia", "Source Han Serif SC"));
    static XElement P(string text, string size = "21", string color = "222222", string align = "left", bool border = false, int line = 360, int after = 50) => new(W + "p", new XElement(W + "pPr", new XElement(W + "jc", new XAttribute(W + "val", align)), new XElement(W + "spacing", new XAttribute(W + "line", line), new XAttribute(W + "after", after)), border ? new XElement(W + "pBdr", new XElement(W + "top", new XAttribute(W + "val", "single"), new XAttribute(W + "sz", "8"), new XAttribute(W + "color", "1755AB"))) : null), new XElement(W + "r", new XElement(W + "rPr", Fonts(), new XElement(W + "sz", new XAttribute(W + "val", size)), new XElement(W + "color", new XAttribute(W + "val", color))), new XElement(W + "t", new XAttribute(XNamespace.Xml + "space", "preserve"), text)));
    static XElement Break() => new(W + "p", new XElement(W + "pPr", new XElement(W + "spacing", new XAttribute(W + "line", 20), new XAttribute(W + "lineRule", "exact"), new XAttribute(W + "after", 0))), new XElement(W + "r", new XElement(W + "br", new XAttribute(W + "type", "page"))));
    static XElement Section(int width) => new(W + "sectPr", new XElement(W + "pgSz", new XAttribute(W + "w", width), new XAttribute(W + "h", 16838)), new XElement(W + "pgMar", new XAttribute(W + "top", 680), new XAttribute(W + "bottom", 680), new XAttribute(W + "left", 680), new XAttribute(W + "right", 680), new XAttribute(W + "header", 200), new XAttribute(W + "footer", 200)));
    static Paragraph Pack(XElement xml) => new(xml.ToString(SaveOptions.DisableFormatting));
    static void Write(MainDocumentPart part, XElement root) { using var stream = part.GetStream(FileMode.Create, FileAccess.Write); root.Save(stream); }
}
