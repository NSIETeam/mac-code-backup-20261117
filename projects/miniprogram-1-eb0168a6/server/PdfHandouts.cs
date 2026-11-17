using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Content;
using DR = DocumentFormat.OpenXml.Drawing;
using DW = DocumentFormat.OpenXml.Drawing.Wordprocessing;
using PIC = DocumentFormat.OpenXml.Drawing.Pictures;

namespace Siyue;

public partial class WordDocuments
{
    const string PdfRasterName = "SiyuePDF";
    public async Task<string> NormalizeHandout(string input, string dir, CancellationToken ct)
    {
        NormalizedSourcePdf = null;
        using var recognition = QuestionRecognition.Use(EffectiveRecognitionMode(QuestionRecognition.Mode));
        // Existing reviewed source regions remain authoritative when the parsing strategy changes.
        if (HasAiLayout(input)) return input;
        var references = ReadAiSourceReferences(input);
        if (PdfParsingPolicy.IsFixed(config) && references.Length > 0)
            return await NormalizeFixedReferenceHandout(input, references, dir, ct);
        if (!PdfParsingPolicy.IsFixed(config) && (QuestionRecognition.Mode == QuestionRecognition.Auto || references.Length > 0)) {
            var automatic = await NormalizeAiHandout(input, dir, ct);
            if (automatic != null) return automatic;
        }
        return Path.GetExtension(input).ToLowerInvariant() switch {
            ".pdf" => await NormalizePdfHandout(input, dir, ct),
            ".doc" => await Convert(input, "docx", dir, ct),
            ".docx" => input,
            _ => throw new ApiException(422, "请选择 Word 或 PDF 讲义")
        };
    }

    sealed record PdfLine(string Text, double Left, double Right, double Top, double Bottom);
    static bool IsPdfPageBackground(UglyToad.PdfPig.Graphics.PdfPath path,Page page) {
        var box=path.GetBoundingRectangle();
        if(!path.IsFilled || path.IsStroked || box==null || path.FillColor==null ||
            box.Value.Left>page.Width*.01 || box.Value.Bottom>page.Height*.01 ||
            box.Value.Right<page.Width*.99 || box.Value.Top<page.Height*.99)return false;
        try { var (r,g,b)=path.FillColor.ToRGBValues();return r>=.99 && g>=.99 && b>=.99; }
        catch(NotSupportedException) { return false; }
    }
    static List<PdfLine> PdfLines(Page page)
    {
        // Content stream order is not reading order. Cluster baselines before sorting horizontally.
        var rows = new List<List<Letter>>();
        foreach (var letter in page.Letters.Where(l => !string.IsNullOrWhiteSpace(l.Value)).OrderByDescending(l => l.StartBaseLine.Y)) {
            var row = rows.LastOrDefault(r => Math.Abs(r[0].StartBaseLine.Y - letter.StartBaseLine.Y) <= Math.Max(2, Math.Min(r[0].PointSize, letter.PointSize) * .35));
            if (row == null) rows.Add([letter]); else row.Add(letter);
        }
        var raw = rows.Select(row => {
            var letters = row.OrderBy(l => l.StartBaseLine.X).ToArray(); var text = new StringBuilder(); Letter? previous = null;
            foreach (var l in letters) {
                if (previous != null && l.StartBaseLine.X - (previous.StartBaseLine.X + previous.Width) > Math.Max(1.5, l.PointSize * .25)) text.Append(l.StartBaseLine.X - (previous.StartBaseLine.X + previous.Width) > Math.Max(24, l.PointSize * 3) ? '\t' : ' ');
                text.Append(l.Value); previous = l;
            }
            var value = string.Concat(text.ToString().EnumerateRunes().Select(r => r.ToString())).Trim(); var marker = Start.Match(value);
            if (marker.Success) value = Regex.Replace(marker.Groups["label"].Value, @"\s+", "") + " " + value[marker.Length..];
            var boxes = letters.Select(l => l.BoundingBox.Height > l.PointSize * 1.8 || l.BoundingBox.Width > Math.Max(l.Width * 3, l.PointSize * 2)
                ? new UglyToad.PdfPig.Core.PdfRectangle(l.StartBaseLine.X, l.StartBaseLine.Y - l.PointSize * .2, l.StartBaseLine.X + l.Width, l.StartBaseLine.Y + l.PointSize)
                : l.BoundingBox).ToArray();
            return new PdfLine(value, boxes.Min(b => b.Left), boxes.Max(b => b.Right), page.Height - boxes.Max(b => b.Top), page.Height - boxes.Min(b => b.Bottom));
        }).OrderBy(l => l.Top).ToList();
        // Fractions and superscripts occupy overlapping baselines. Keep their complete ink with the question line.
        var groups = new List<List<PdfLine>>();
        foreach (var line in raw) {
            if (groups.Count == 0 || line.Top >= groups[^1].Max(l => l.Bottom) - .5) groups.Add([line]);
            else groups[^1].Add(line);
        }
        return groups.Select(g => new PdfLine(string.Join(" ",g.OrderBy(l=>l.Left).ThenBy(l=>l.Top).Select(l=>l.Text)),g.Min(l=>l.Left),g.Max(l=>l.Right),g.Min(l=>l.Top),g.Max(l=>l.Bottom))).ToList();
    }

    public async Task<string> NormalizePdfHandout(string input, string dir, CancellationToken ct)
    {
        await InitPdfModelAssist();
        PdfDocument pdf;
        try { pdf = PdfDocument.Open(input); }
        catch (Exception e) when (e is not (OperationCanceledException or OutOfMemoryException)) {
            try {
                var repaired=Path.Combine(dir,"poppler-repaired.pdf");
                await Run(config["Documents:PdfToCairo"]??"pdftocairo",["-pdf",input,repaired],ct);
                pdf=PdfDocument.Open(repaired);input=repaired;
            } catch(Exception recovery) when(recovery is not (OperationCanceledException or OutOfMemoryException)) {
                throw new ApiException(422,"无法读取 PDF，后备引擎也未能打开；请解除密码保护并检查文件是否损坏后重新上传");
            }
        }
        using (pdf) {
            Guard.That(pdf.NumberOfPages is > 0 and <= 500, "PDF 讲义须为 1–500 页，超过时请分册上传", 422);
            await using var sourceHash=File.OpenRead(input);
            var fingerprint=System.Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(sourceHash));
            var modelStamp = pdfModelConfig!=null && PdfModelAssist.Enabled(pdfModelConfig)
                ? "-model-"+System.Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(Json.Write(new{endpoint=pdfModelConfig["LearningAi:Endpoint"],model=pdfModelConfig["LearningAi:Model"],effort=pdfModelConfig["LearningAi:ReasoningEffort"]}))))[..12] : "";
            var cachePrefix=$"pdf-checkpoints/{PdfEngineVersion}-{QuestionRecognition.Mode}{modelStamp}/{fingerprint}";
            var output = Path.Combine(dir, "pdf-normalized.docx");
            using var doc = WordprocessingDocument.Create(output, WordprocessingDocumentType.Document);
            var main = doc.AddMainDocumentPart(); main.Document = new Document(new Body()); var body = main.Document.Body!;
            uint imageId = 0; long totalBytes = 0; var questionCount = 0; var reviewPages = 0;
            // Explicit printed-page references in the source TOC are more reliable than OCR of decorative titles.
            var tocLines = new List<PdfLine>();
            for (var n = 1; n <= Math.Min(5, pdf.NumberOfPages); n++) {
                ct.ThrowIfCancellationRequested();
                try { tocLines.AddRange(PdfLines(pdf.GetPage(n))); }
                catch (Exception e) when (RecoverablePdfPageError(e, ct)) { /* This page is reported in the main pass. */ }
            }
            var toc = tocLines.Select(l => Regex.Match(l.Text, @"^(?<title>第[一二三四五六七八九十0-9０-９]+讲.*?)\s*[.·…]{2,}\s*(?<page>\d+)\s*$"))
                .Where(m => m.Success).GroupBy(m => int.Parse(m.Groups["page"].Value)).Where(g => g.Count() == 1)
                .ToDictionary(g => g.Key,g => g.Single().Groups["title"].Value.Trim());
            for (var pageNumber = 1; pageNumber <= pdf.NumberOfPages; pageNumber++) {
                ct.ThrowIfCancellationRequested();
                var blockCheckpoint = body.ChildElements.Count;
                var partCheckpoint = main.Parts.Select(x => x.RelationshipId).ToHashSet();
                var countCheckpoint = questionCount; var bytesCheckpoint = totalBytes;
                var cacheKey=$"{cachePrefix}/{pageNumber}.docx";
                string? knownTitle=null;
                var restored=await LoadPdfCheckpoint(cacheKey,dir,main,body,ct);
                if(restored.HasValue){questionCount+=restored.Value;imageId+=(uint)(main.Parts.Count()-partCheckpoint.Count);totalBytes+=main.Parts.Where(x=>!partCheckpoint.Contains(x.RelationshipId)).Sum(x=>{using var stream=x.OpenXmlPart.GetStream();return stream.Length;});Guard.That(totalBytes<=180_000_000&&imageId<=4500,"PDF 内容过多，请分册上传",413);continue;}
                try {
                var page = pdf.GetPage(pageNumber);
                Guard.That(page.Width is > 0 and <= 1440 && page.Height is > 0 and <= 1440, $"PDF 第 {page.Number} 页尺寸过大，请使用 A4 或 A3 页面", 422);
                // Capture TOC-based lecture identity before any page validation can fail.
                // Decorative headings are often images, including on pages with broken fonts.
                var pageLabel = PdfLines(page).Select(l => l.Top > page.Height - 65 ? Regex.Match(l.Text, @"^\s*(?<page>\d+)\s*/\s*\d+\s*$") : Match.Empty).FirstOrDefault(m => m.Success);
                knownTitle = pageLabel != null && int.TryParse(pageLabel.Groups["page"].Value,out var printedPage) ? toc.GetValueOrDefault(printedPage) : null;
                var lines = PdfLines(page).Where(l => !IsPdfMargin(l, page.Height)).ToList();
                // Fixed parsing never treats entire page-edge strips as disposable. Only
                // explicit margin text is excluded; other ink must be retained or reviewed.
                var coverage = PdfParsingPolicy.IsFixed(config)
                    ? PdfLines(page).Where(l=>IsPdfMargin(l,page.Height)).Select(l=>new[]{l.Left,l.Top,l.Right,l.Bottom}).ToList()
                    : new List<double[]> { new[]{0d,0d,page.Width,74d}, new[]{0d,page.Height-65,page.Width,page.Height} };
                var graphics = page.GetImages().Select(im => im.BoundingBox).ToArray();
                var paths = page.Paths.Where(p => (p.IsFilled || p.IsStroked) && !IsPdfPageBackground(p,page)).Select(p => p.GetBoundingRectangle()).Where(b => b.HasValue).Select(b => b!.Value).ToArray();
                if (lines.Count == 0 && graphics.Length == 0 && paths.Length == 0) continue;
                // An explicitly titled organization introduction is front matter, not
                // a failed question page. Keep its complete image in the source package.
                if(pageNumber<=3 && !lines.Any(l=>IsQuestionStart(l.Text)) &&
                    lines.Any(l=>l.Top<page.Height*.25 && Regex.IsMatch(Regex.Replace(l.Text,@"\s+",""),@"^.{0,18}(?:教育|机构|学校|公司|品牌)简介$"))) {
                    var png=await RenderAuditPage(input,pageNumber,dir,ct);var bytes=await File.ReadAllBytesAsync(png,ct);totalBytes+=bytes.Length;
                    Guard.That(totalBytes<=180_000_000 && ++imageId<=4500,"PDF 内容过多，请分册上传",413);
                    var part=main.AddImagePart(ImagePartType.Png);using(var stream=new MemoryStream(bytes))part.FeedData(stream);
                    var drawing=PdfDrawing(main.GetIdOfPart(part),imageId,4_800_000,(long)(4_800_000*page.Height/page.Width),false);
                    TagPdf(drawing,pageNumber,"front-matter",[0,0,1,1]);
                    body.Append(new Paragraph(new ParagraphProperties(new ParagraphStyleId{Val="SiyuePdfContext"}),new Run(new RunProperties(new Vanish()),new Text(string.Join("\n",lines.Select(l=>l.Text)))),new Run(drawing)));
                    await CachePdfPage(cacheKey,dir,main,body,blockCheckpoint,ct);continue;
                }
                // Letterhead-only separator sheets are not scanned question pages.
                bool Interior(UglyToad.PdfPig.Core.PdfRectangle b) => page.Height - b.Bottom > 74 && page.Height - b.Top < page.Height - 65 && b.Height > 3;
                if (!lines.Any(l=>IsQuestionStart(l.Text)) && !lines.Any(l => l.Bottom > 74 && l.Top < page.Height - 65) && !graphics.Any(Interior) && !paths.Any(Interior)) continue;
                // A table of contents can have a large enclosing frame. It is a single retained
                // non-question block; cropping each entry would cut through that frame.
                if (!lines.Any(l => IsQuestionStart(l.Text)) && (lines.Count(l => Regex.IsMatch(l.Text, @"[.·…]{2,}\s*\d+\s*$")) >= 2 || lines.Any(l => Regex.IsMatch(Regex.Replace(l.Text, @"\s+", ""), @"^目录[：:·]?$")) && lines.Count(l => BookStructure.IsHeading(l.Text)) >= 2)) {
                    body.Append(new Paragraph(new Run(new Text(string.Join("\n", lines.Select(l => l.Text))) { Space = SpaceProcessingModeValues.Preserve })));
                    continue;
                }
                var chars = string.Concat(lines.Select(l => l.Text));
                Guard.That(chars.Count(c=>c=='\ufffd')<5 && !(chars.Count(c=>c is >= '\ue000' and <= '\uf8ff')>chars.Length/4 && !lines.Any(l=>IsQuestionStart(l.Text))), "PDF 字体编码异常，需要后备识别",422);
                Guard.That(!(chars.Length < 20 && graphics.Any(b => b.Width * b.Height > page.Width * page.Height * .4)), $"PDF 第 {page.Number} 页扫描图片未能可靠自动拆题，请在原图上框选题目", 422);
                Guard.That(chars.Any(char.IsLetterOrDigit) && (lines.Any(l => IsQuestionStart(l.Text)) || chars.Count(char.IsLetter) >= 20), $"PDF 第 {page.Number} 页文字层或字体编码无法可靠识别，请在原图上框选题目", 422);
                Guard.That(page.Letters.All(l => Math.Abs(l.StartBaseLine.Y - l.EndBaseLine.Y) < 2), $"PDF 第 {page.Number} 页含旋转文字，请先转为横向文字排版或上传 Word", 422);
                // Two question markers on one visual line indicate a multi-column layout.
                Guard.That(lines.Any(l => IsQuestionStart(l.Text)) || !HasContextualPdfLabels(lines.Select(l => new PdfOcrLine(l.Text,l.Left,l.Right,l.Top,l.Bottom,100)).ToArray()),
                    "题号格式需要结合位置和序列校验",422);
                Guard.That(!lines.Any(l => l.Text.Split('\t').Skip(1).Any(IsQuestionStart) ||
                    IsQuestionStart(l.Text) && l.Left > page.Width * .42), $"PDF 第 {page.Number} 页需要按栏重新识别阅读顺序", 422);
                await AddPdfImageMarkers(input, page, lines, dir, knownTitle, ct);
                lines = lines.OrderBy(l => l.Top).ToList();
                Guard.That(lines.Any(l=>IsQuestionStart(l.Text)||BookStructure.IsHeading(l.Text)||IsPdfContext(l.Text)) || PreviousPdfLabel(body,pageNumber)!=null,
                    "正文题号无法可靠识别，已保留原页供框选校对",422);
                var left = lines.Min(l => l.Left);
                bool Heading(PdfLine l) => BookStructure.IsHeading(l.Text) || Regex.IsMatch(l.Text, @"^(参考答案|答案与解析|答案解析|参考答案与解析|附录)[：: ]*$");
                var previousLabel = PreviousPdfLabel(body, pageNumber);
                bool Question(PdfLine l) => IsQuestionStart(l.Text) && l.Left <= left + 24 && !ExplicitPdfContinuation(l.Text, previousLabel);
                bool Context(PdfLine l) => IsPdfContext(l.Text);
                var starts = new SortedSet<int> { 0 };
                for (var i = 0; i < lines.Count; i++) if (Heading(lines[i]) || Question(lines[i]) || Context(lines[i])) { starts.Add(i); if ((Heading(lines[i]) || Context(lines[i])) && i + 1 < lines.Count) starts.Add(i + 1); }
                questionCount += lines.Count(Question);
                // Long text is split only in a whitespace gap, never through an embedded image.
                var bodyGraphics = graphics.Where(b => page.Height - b.Bottom > 74).ToArray();
                var images = bodyGraphics.Concat(paths.Where(b => b.Width < page.Width * .9 && b.Height < page.Height * .9)).ToArray();
                var contentPaths = paths.Where(b => page.Height - b.Bottom > 74 && b.Width < page.Width * .98 && b.Height < page.Height * .98).ToArray();
                var inkTop = Math.Max(0, Math.Min(lines.Min(l => l.Top), bodyGraphics.Concat(contentPaths).Select(b => page.Height - b.Top).DefaultIfEmpty(page.Height).Min()) - 3);
                var inkBottom = Math.Min(page.Height, Math.Max(lines.Max(l => l.Bottom), bodyGraphics.Concat(contentPaths).Select(b => page.Height - b.Bottom).DefaultIfEmpty(0).Max()) + 3);
                var initial = starts.Append(lines.Count).ToArray();
                for (var s = 0; s + 1 < initial.Length; s++) {
                    var anchor = initial[s];
                    for (var i = anchor + 1; i < initial[s + 1]; i++) {
                        var cut = (lines[i - 1].Bottom + lines[i].Top) / 2;
                        if (lines[i].Top - lines[anchor].Top < 240 || lines[i].Top - lines[i - 1].Bottom < 4 || images.Any(b => page.Height - b.Top < cut && page.Height - b.Bottom > cut)) continue;
                        starts.Add(i); anchor = i;
                    }
                }
                double Boundary(int index) {
                    var low = lines[index - 1].Bottom; var high = lines[index].Top;
                    var cut = (low + high) / 2;
                    for (var step = 0; step <= images.Length; step++) {
                        var crossed = images.Where(b => page.Height - b.Top + 2 < cut && page.Height - b.Bottom - 2 > cut).ToArray();
                        if (crossed.Length == 0) return cut;
                        var after = crossed.Max(b => page.Height - b.Bottom) + 3;
                        if (after < high - 1) { cut = after; continue; }
                        var before = crossed.Min(b => page.Height - b.Top) - 3;
                        if (before > low + 1) { cut = before; continue; }
                        return cut; // No safe gap: the existing guard requires manual correction.
                    }
                    return cut;
                }
                var cuts = starts.Append(lines.Count).ToArray();
                if (config["Documents:PdfDiagnosticsDirectory"] is string diagnostic) { Directory.CreateDirectory(diagnostic); File.WriteAllText(Path.Combine(diagnostic, $"page-{page.Number}.json"), Json.Write(new {lines,cuts,images})); }
                for (var s = 0; s + 1 < cuts.Length; s++) {
                    var begin = cuts[s]; var end = cuts[s + 1];
                    var text = string.Join("\n", lines.Skip(begin).Take(end - begin).Select(l => l.Text));
                    var top = begin == 0 ? inkTop : Math.Max(0, Boundary(begin));
                    var bottom = end == lines.Count ? inkBottom : Boundary(end);
                    if (end == begin + 1 && (Heading(lines[begin]) || Context(lines[begin]))) {
                        if (Heading(lines[begin])) Guard.That(!images.Any(b => page.Height - b.Top + 2 < top && page.Height - b.Bottom - 2 > top || page.Height - b.Top + 2 < bottom && page.Height - b.Bottom - 2 > bottom), $"PDF 第 {page.Number} 页讲次标题与图形交叠，请调整源文件或上传 Word", 422);
                        // Retain decorative title/context bands as source pixels, too. They are
                        // lecture metadata, not questions, but must not look like missing diagrams.
                        coverage.Add([0,top,page.Width,bottom]);
                        var titlePrefix=Path.Combine(dir,"pdf-title-"+pageNumber+"-"+begin);
                        await Run(config["Documents:PdfToPpm"]??"pdftoppm",["-f",pageNumber.ToString(),"-l",pageNumber.ToString(),"-singlefile","-r","180","-y",((int)Math.Floor(top*2.5)).ToString(),"-H",((int)Math.Ceiling(bottom*2.5)-(int)Math.Floor(top*2.5)).ToString(),"-png",input,titlePrefix],ct);
                        var titlePart=main.AddImagePart(ImagePartType.Png);using(var stream=File.OpenRead(titlePrefix+".png")){totalBytes+=stream.Length;titlePart.FeedData(stream);}File.Delete(titlePrefix+".png");
                        Guard.That(totalBytes<=180_000_000 && ++imageId<=4500,"PDF 内容过多，请分册上传",413);
                        var titleDrawing=PdfDrawing(main.GetIdOfPart(titlePart),imageId,4_800_000,(long)(4_800_000*(bottom-top)/page.Width),false);
                        body.Append(new Paragraph(new ParagraphProperties(new ParagraphStyleId{Val=Context(lines[begin])?"SiyuePdfContext":"SiyuePdfHeading"}),new Run(new RunProperties(new Vanish()),new Text(text)),new Run(titleDrawing)));continue;
                    }
                    Guard.That(!images.Any(b => page.Height - b.Top + 2 < top && page.Height - b.Bottom - 2 > top || page.Height - b.Top + 2 < bottom && page.Height - b.Bottom - 2 > bottom), $"PDF 第 {page.Number} 页题目边界穿过图片，请调整源文件排版后上传", 422);
                    var bandLines = lines.Skip(begin).Take(end - begin).ToArray();
                    var bandGraphics = bodyGraphics.Concat(contentPaths).Where(b => page.Height - b.Top < bottom && page.Height - b.Bottom > top).ToArray();
                    top = Math.Max(top, Math.Min(bandLines.Min(l => l.Top), bandGraphics.Select(b => page.Height - b.Top).DefaultIfEmpty(bottom).Min()) - 3);
                    bottom = Math.Min(bottom, Math.Max(bandLines.Max(l => l.Bottom), bandGraphics.Select(b => page.Height - b.Bottom).DefaultIfEmpty(top).Max()) + 3);
                    var cropLeft = Math.Max(0, Math.Min(bandLines.Min(l => l.Left), bandGraphics.Select(b => b.Left).DefaultIfEmpty(page.Width).Min()) - 3);
                    var cropRight = Math.Min(page.Width, Math.Max(bandLines.Max(l => l.Right), bandGraphics.Select(b => b.Right).DefaultIfEmpty(0).Max()) + 3);
                    coverage.Add([cropLeft,top,cropRight,bottom]);
                    Guard.That(++imageId <= 4500, "PDF 内容过多，请按册分开上传", 413);
                    var prefix = Path.Combine(dir, "pdf-band-" + imageId);
                    const double dpi = 180; var y = (int)Math.Floor(top * dpi / 72); var height = (int)Math.Ceiling(bottom * dpi / 72) - y;
                    await Run(config["Documents:PdfToPpm"] ?? "pdftoppm", ["-f", page.Number.ToString(), "-l", page.Number.ToString(), "-singlefile", "-r", dpi.ToString(CultureInfo.InvariantCulture), "-x", ((int)Math.Floor(cropLeft * dpi / 72)).ToString(), "-W", ((int)Math.Ceiling((cropRight - cropLeft) * dpi / 72)).ToString(), "-y", y.ToString(), "-H", height.ToString(), "-png", input, prefix], ct);
                    var path = prefix + ".png"; totalBytes += new FileInfo(path).Length;
                    Guard.That(totalBytes <= 180_000_000, "PDF 图片数据过大，请按册分开上传", 413);
                    var part = main.AddImagePart(ImagePartType.Png); using (var stream = File.OpenRead(path)) part.FeedData(stream); File.Delete(path);
                    var scale = Math.Min(1d, 4_800_000d / ((cropRight - cropLeft) * 12700));
                    var cx = (long)((cropRight - cropLeft) * 12700 * scale); var cy = (long)((bottom - top) * 12700 * scale);
                    var drawing = PdfDrawing(main.GetIdOfPart(part), imageId, cx, cy, Question(lines[begin]));
                    TagPdf(drawing,page.Number,"pdfpig-poppler",[cropLeft/page.Width,top/page.Height,cropRight/page.Width,bottom/page.Height]);
                    // Hidden text supplies search and segmentation; the picture is the authoritative visible content.
                    body.Append(new Paragraph(new ParagraphProperties(new SpacingBetweenLines { After = "0", Line = "240", LineRule = LineSpacingRuleValues.Auto }),
                        new Run(new RunProperties(new Vanish()), new Text(text) { Space = SpaceProcessingModeValues.Preserve }),
                        new Run(drawing)));
                }
                var audit = await AuditPdfPixels(input,pageNumber,page.Width,page.Height,coverage,dir,ct);
                await using(var report=new MemoryStream(System.Text.Encoding.UTF8.GetBytes(Json.Write(new{page=pageNumber,audit,coverage}))))await objects.Put(cacheKey+".audit.json",report,"application/json",ct);
                Guard.That(audit.MissingPixels<=24 && audit.CutInk<=4,$"PDF 第 {pageNumber} 页有未归属的文字或图形，需要人工框选",422);
                await CachePdfPage(cacheKey,dir,main,body,blockCheckpoint,ct);
                } catch (Exception e) when (RecoverablePdfPageError(e, ct)) {
                    var recoveredHeadings=body.ChildElements.Skip(blockCheckpoint).OfType<Paragraph>().Select(p=>p.InnerText).Where(BookStructure.IsHeading).ToArray();
                    if(recoveredHeadings.Length==0 && knownTitle!=null)recoveredHeadings=[knownTitle];
                    // A page is atomic: do not retain a half-cropped question followed by a duplicate full page.
                    while (body.ChildElements.Count > blockCheckpoint) body.LastChild!.Remove();
                    foreach (var part in main.Parts.Where(x => !partCheckpoint.Contains(x.RelationshipId)).ToArray()) main.DeletePart(part.RelationshipId);
                    questionCount = countCheckpoint; totalBytes = bytesCheckpoint;
                    try {
                        var recovered = await RecoverPdfPage(input,pageNumber,dir,main,body,imageId,recoveredHeadings,ct);
                        imageId += (uint)(main.Parts.Count()-partCheckpoint.Count); questionCount += recovered;
                        totalBytes += main.Parts.Where(x=>!partCheckpoint.Contains(x.RelationshipId)).Sum(x=>{using var stream=x.OpenXmlPart.GetStream();return stream.Length;});
                        Guard.That(totalBytes<=180_000_000 && imageId<=4500,"PDF 内容过多，请分册上传",413);
                        await CachePdfPage(cacheKey,dir,main,body,blockCheckpoint,ct);
                        continue;
                    } catch(Exception recoveryError) when(RecoverablePdfPageError(recoveryError,ct)) {
                        await using(var report=new MemoryStream(System.Text.Encoding.UTF8.GetBytes(Json.Write(new{page=pageNumber,primary=e.Message,recovery=recoveryError.Message}))))
                            await objects.Put(cacheKey+".recovery-error.json",report,"application/json",ct);
                        while(body.ChildElements.Count>blockCheckpoint)body.LastChild!.Remove();
                        foreach(var part in main.Parts.Where(x=>!partCheckpoint.Contains(x.RelationshipId)).ToArray())main.DeletePart(part.RelationshipId);
                        questionCount=countCheckpoint;totalBytes=bytesCheckpoint;
                    }
                    foreach(var title in recoveredHeadings)body.Append(new Paragraph(new Run(new Text(title))));
                    reviewPages++;
                    var reason = e is ApiException ? e.Message : "文字或图形结构无法可靠解析";
                    var paragraph = new Paragraph(new ParagraphProperties(new ParagraphStyleId { Val = PdfReviewStyle }),
                        new Run(new Text($"第 {pageNumber} 页待校对：{reason}。原页已保留；如为封面或目录请在讲次划分中单独排除，正文请使用“框选拆题”调整范围。")));
                    var prefix = Path.Combine(dir, "pdf-review-" + pageNumber);
                    try {
                        await Run(config["Documents:PdfToPpm"] ?? "pdftoppm", ["-f", pageNumber.ToString(), "-l", pageNumber.ToString(), "-singlefile", "-r", "180", "-png", input, prefix], ct);
                        var bytes = await File.ReadAllBytesAsync(prefix + ".png", ct);
                        totalBytes += bytes.Length;
                        Guard.That(totalBytes <= 180_000_000 && ++imageId <= 4500, "PDF 内容过多，请按册分开上传", 413);
                        var width = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16, 4));
                        var height = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(20, 4));
                        var part = main.AddImagePart(ImagePartType.Png); using var stream = new MemoryStream(bytes); part.FeedData(stream);
                        var drawing=PdfDrawing(main.GetIdOfPart(part), imageId, 4_800_000, (long)(4_800_000d * height / width), false);
                        TagPdf(drawing,pageNumber,"retained-page",[0,0,1,1],"manual-regions");
                        paragraph.Append(new Run(drawing));
                    } catch (Exception renderError) when (RecoverablePdfPageError(renderError, ct)) {
                        paragraph.Append(new Run(new Text(" 此页预览也未能生成，请通过下载原稿查看该页。")));
                    } finally { if (File.Exists(prefix + ".png")) File.Delete(prefix + ".png"); }
                    body.Append(paragraph);
                }
            }
            Guard.That(questionCount > 0 || reviewPages > 0, "PDF 未识别到例1、练1或1.等题号，请检查文字能否复制及题号格式，或上传原始 Word", 422);
            body.Append(new SectionProperties(new DocumentFormat.OpenXml.Wordprocessing.PageSize { Width = 11906, Height = 16838 }, new PageMargin { Top = 720, Bottom = 720, Left = 720, Right = 720 }));
            await AnnotatePdfFormulae(main,dir,ct);
            main.Document.Save(); return output;
        }
    }
    public const string PdfReviewStyle = "SiyuePdfPageReview";
    public static bool IsPdfReview(System.Xml.Linq.XElement block) => (string?)block.Element(W + "pPr")?.Element(W + "pStyle")?.Attribute(W + "val") == PdfReviewStyle;
    static bool RecoverablePdfPageError(Exception e, CancellationToken ct) => !ct.IsCancellationRequested && e is not (OperationCanceledException or OutOfMemoryException or IOException or UnauthorizedAccessException) && (e is not ApiException api || api.Status == 422);
    static Drawing PdfDrawing(string relationship, uint id, long cx, long cy, bool startsQuestion) => new(new DW.Inline(
        new DW.Extent { Cx = cx, Cy = cy }, new DW.DocProperties { Id = id, Name = PdfRasterName + id, Description = startsQuestion ? "question" : "continuation" },
        new DR.Graphic(new DR.GraphicData(new PIC.Picture(
            new PIC.NonVisualPictureProperties(new PIC.NonVisualDrawingProperties { Id = id, Name = "PDF 原题" }, new PIC.NonVisualPictureDrawingProperties()),
            new PIC.BlipFill(new DR.Blip { Embed = relationship }, new DR.Stretch(new DR.FillRectangle())),
            new PIC.ShapeProperties(new DR.Transform2D(new DR.Offset { X = 0, Y = 0 }, new DR.Extents { Cx = cx, Cy = cy }), new DR.PresetGeometry(new DR.AdjustValueList()) { Preset = DR.ShapeTypeValues.Rectangle }))) { Uri = "http://schemas.openxmlformats.org/drawingml/2006/picture" })) { DistanceFromTop = 0, DistanceFromBottom = 0, DistanceFromLeft = 0, DistanceFromRight = 0 });
    public static bool IsPdfRaster(System.Xml.Linq.XElement block) => block.Descendants(WP + "docPr").Any(x => ((string?)x.Attribute("name"))?.StartsWith(PdfRasterName, StringComparison.Ordinal) == true);
}
