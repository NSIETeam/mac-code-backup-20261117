using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using System.Xml.Linq;
using UglyToad.PdfPig;
namespace Siyue;

public partial class WordDocuments
{
    // A4 house standard. Page fill is applied only AFTER pagination and sparse-tail removal.
    public const int BookletBottomMarginTwips = 850; // 15 mm, rounded to a twip.
    public const int BookletAnswerRatio = 2;
    const int BookletSideMargin = 794, BookletWidth = 10318, AnalysisWidth = 2438, QuestionWidth = 7880;
    const int CellPadding = 135, AnswerLine = 315, EndMarkerHeight = 20;
    const string MarkerPrefix = "SYLAYOUT";
    sealed record BookletItem(QuestionSnapshot Question, string Source, int Height, int Answer);
    sealed record EndPosition(int Index, int Page, double Bottom);

    public async Task<string> Booklet(ExportDocument export, string dir, CancellationToken ct, int compactLevel = 0)
        => (await BookletPdf(export, dir, ct)).Docx;

    public async Task<(string Docx, string Pdf)> BookletPdf(ExportDocument export, string dir, CancellationToken ct)
    {
        var snapshots = Json.Read<QuestionSnapshot[]>(export.SnapshotJson);
        Guard.That(snapshots.Length > 0, "没有可生成的错题");
        var sourcePaths = new Dictionary<Guid, string>();
        foreach (var id in snapshots.Select(q => q.HandoutId).Distinct()) {
            var handout = await db.Handouts.IgnoreQueryFilters().SingleAsync(x => x.Id == id, ct);
            sourcePaths[id] = await Materialize(handout.NormalizedKey, dir, "source-" + id + ".docx", ct);
        }
        if (export.Layout == "compilation") return await CompilationPdf(export, snapshots, sourcePaths, dir, ct);
        var heights = await MeasureBookletQuestions(snapshots, sourcePaths, dir, ct);
        var items = snapshots.Select((q, i) => new BookletItem(q, sourcePaths[q.HandoutId], heights[i].Height, heights[i].AnswerBasis * BookletAnswerRatio)).ToArray();
        var metadata = await BookletMetadata(export, snapshots, ct);
        // Historical selections and classroom exports share the measured page planner.
        return await ClassroomBookletPdf(items, metadata, dir, ct);
    }

    async Task<(int Height, int AnswerBasis)[]> MeasureBookletQuestions(QuestionSnapshot[] items, Dictionary<Guid, string> sources, string dir, CancellationToken ct)
    {
        var path = Path.Combine(dir, "booklet-measure.docx");
        using (var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document)) {
            var main = doc.AddMainDocumentPart(); var body = new XElement(W + "body");
            for (var i = 0; i < items.Length; i++) {
                if (i > 0) body.Add(Break());
                body.Add(LayoutMarker("START" + i));
                using var source = WordprocessingDocument.Open(sources[items[i].HandoutId], false);
                body.Add(ComposeBlocks(main, source.MainDocumentPart!, Json.Read<ContentBlock[]>(items[i].BlocksJson), "m" + i, items[i].Label, 360, 0, compactBookletFlow: true));
                body.Add(LayoutMarker("STOP" + i));
            }
            // Match the final right-cell text width exactly, including both cell margins.
            body.Add(BookletSection(QuestionWidth - 2 * CellPadding + 2 * BookletSideMargin));
            Write(main, new XElement(W + "document", new XAttribute(XNamespace.Xmlns + "w", W), body));
        }
        var pdfPath = await Convert(path, "pdf", dir, ct);
        using var pdf = PdfDocument.Open(pdfPath);
        var marks = pdf.GetPages().SelectMany(p => p.GetWords().Where(w => w.Text.StartsWith(MarkerPrefix)).Select(w => new { w.Text, Page = p.Number, Y = p.Height - w.BoundingBox.Bottom })).ToArray();
        var usableHeight = (16838 - BookletSideMargin - BookletBottomMarginTwips) / 20d;
        return items.Select((q, i) => {
            var start = marks.SingleOrDefault(x => x.Text == MarkerPrefix + "START" + i);
            var end = marks.SingleOrDefault(x => x.Text == MarkerPrefix + "STOP" + i);
            Guard.That(start != null && end != null, "题目高度测量失败，请检查讲义内容");
            var height = (end!.Page - start!.Page) * usableHeight + end.Y - start.Y - 1;
            var flowHeight = Math.Max(AnswerLine, (int)Math.Ceiling(height * 20));
            var answerBasis = flowHeight;
            var blocks = Json.Read<ContentBlock[]>(q.BlocksJson);
            if (blocks.Length == 1) {
                var xml = XElement.Parse(blocks[0].Xml);
                var extents = xml.Descendants(WP + "extent").ToArray();
                if (IsPdfRaster(xml) && extents.Length == 1) {
                    var cx = (long?)extents[0].Attribute("cx") ?? 0;
                    var cy = (long?)extents[0].Attribute("cy") ?? 0;
                    // LibreOffice versions add different inline-image baseline gaps.
                    // Keep those in the row's flow height, but do not multiply blank
                    // baseline padding when allocating 2x prompt-height writing space.
                    if (cx > 0 && cy > 0) answerBasis = Math.Min(flowHeight,
                        Math.Max(AnswerLine, (int)Math.Ceiling(cy * Math.Min(1d, 4_800_000d / cx) / 635d)));
                }
            }
            return (Height: flowHeight, AnswerBasis: answerBasis);
        }).ToArray();
    }

    async Task<string[]> BookletMetadata(ExportDocument export, QuestionSnapshot[] snapshots, CancellationToken ct)
    {
        var ids = snapshots.Select(q => q.HandoutId).Distinct().ToArray();
        var lectures = await (from h in db.Handouts.IgnoreQueryFilters() join l in db.Lectures.IgnoreQueryFilters() on h.LectureId equals l.Id
                              where ids.Contains(h.Id) select new { l.CourseId, l.Sequence, l.Title }).Distinct().ToArrayAsync(ct);
        var sequence = lectures.Select(l => l.Sequence).Distinct().Order().ToArray();
        var lectureText = sequence.Length > 0 ? string.Join("、", sequence.Select(n => $"第{n}讲")) : "未指定";
        var date = (export.CreatedAt == default ? Clock.Now : export.CreatedAt).ToOffset(TimeSpan.FromHours(8)).ToString("yyyy.MM.dd");
        if (export.ClassSessionId.HasValue) {
            var session = await db.ClassSessions.SingleAsync(x => x.Id == export.ClassSessionId, ct);
            date = session.LessonDate.Replace('-', '.');
            var currentSequence = await (from h in db.Handouts.IgnoreQueryFilters() join l in db.Lectures.IgnoreQueryFilters() on h.LectureId equals l.Id
                                         where h.Id == session.HandoutId select (int?)l.Sequence).SingleOrDefaultAsync(ct);
            if (currentSequence.HasValue) lectureText = $"第{currentSequence.Value}讲";
        }
        return [export.StudentName.Trim() + "个性化错题", lectureText, "日期：" + date];
    }

    async Task<(string Docx, string Pdf)> CompilationPdf(ExportDocument export, QuestionSnapshot[] items, Dictionary<Guid,string> sources, string dir, CancellationToken ct)
    {
        var metadata = await BookletMetadata(export, items, ct);
        string WriteCompilation(string suffix, int lastLines) {
            var path=Path.Combine(dir,"compilation-"+suffix+".docx");
            using var doc=WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document);
            var main=doc.AddMainDocumentPart();var body=new XElement(W+"body");
            body.Add(ClassroomHeading(metadata));
            for(var i=0;i<items.Length;i++) {
                using var source=WordprocessingDocument.Open(sources[items[i].HandoutId],false);
                var blocks=ComposeBlocks(main,source.MainDocumentPart!,Json.Read<ContentBlock[]>(items[i].BlocksJson),"c"+i,items[i].Label,360,0,BookletWidth * 635L,true);
                body.Add(blocks);
                // Three consistent lines for reworking, no analysis column or decorative rules.
                for(var line=0;line<(i==items.Length-1?lastLines:3);line++)body.Add(Spacer(AnswerLine));
            }
            body.Add(BookletSection(11906));Write(main,new XElement(W+"document",new XAttribute(XNamespace.Xmlns+"w",W),new XAttribute(XNamespace.Xmlns+"r",R),body));return path;
        }
        var docx=WriteCompilation("full",3);var pdf=await Convert(docx,"pdf",dir,ct);
        if(PageCount(pdf)>1&&SparseTail(pdf)) {
            var trimmed=WriteCompilation("trim",0);var candidate=await Convert(trimmed,"pdf",dir,ct);
            if(PageCount(candidate)<PageCount(pdf))return(trimmed,candidate);
        }
        return(docx,pdf);
    }

    static string WriteBooklet(BookletItem[] items, string[] metadata, int[] extras, string dir, string suffix, bool markers, bool classroom = false)
    {
        var path = Path.Combine(dir, "booklet-" + suffix + ".docx");
        using var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document);
        var main = doc.AddMainDocumentPart(); var body = new XElement(W + "body");
        foreach (var text in metadata) { var p = P(text, line: AnswerLine, after: 0); p.Element(W + "pPr")!.Element(W + "spacing")!.SetAttributeValue(W + "lineRule", "exact"); body.Add(p); }
        body.Add(Spacer(160));
        var table = BookletTable();
        for (var i = 0; i < items.Length; i++) {
            var item = items[i]; using var source = WordprocessingDocument.Open(item.Source, false);
            var blocks = ComposeBlocks(main, source.MainDocumentPart!, Json.Read<ContentBlock[]>(item.Question.BlocksJson), "q" + i, item.Question.Label, 360, 0);
            var left = AnalysisColumn(item.Height + item.Answer + extras[i], classroom, classroom ? item.Question.Note : null);
            var answerLabel = P("正解：", line: 360, after: 0); answerLabel.Element(W + "pPr")!.Element(W + "spacing")!.SetAttributeValue(W + "before", 80);
            var right = new List<XElement>(blocks) { answerLabel };
            for (var remaining = item.Answer + extras[i]; remaining > 0; remaining -= AnswerLine) right.Add(Spacer(Math.Min(AnswerLine, remaining)));
            right.Add(LayoutMarker("END" + i, markers));
            var props = new XElement(W + "trPr");
            // Whole questions stay together when the complete card fits one page. Oversized
            // cards are allowed to continue; no exact row height can clip their content.
            if (item.Height + item.Answer + extras[i] + 800 < 16838 - BookletSideMargin - BookletBottomMarginTwips - (i == 0 ? AnswerLine * 4 + 160 : 0)) props.Add(new XElement(W + "cantSplit"));
            table.Add(new XElement(W + "tr", props, Cell(AnalysisWidth, left), Cell(QuestionWidth, right)));
        }
        body.Add(table);
        var footer = main.AddNewPart<FooterPart>();
        footer.Footer = new Footer(Pack(new XElement(W + "p", new XElement(W + "pPr", new XElement(W + "jc", new XAttribute(W + "val", "center")),
            new XElement(W + "spacing", new XAttribute(W + "line", 240), new XAttribute(W + "lineRule", "exact"), new XAttribute(W + "after", 0))),
            new XElement(W + "fldSimple", new XAttribute(W + "instr", "PAGE"), new XElement(W + "r", new XElement(W + "rPr", Fonts(), new XElement(W + "sz", new XAttribute(W + "val", "18"))), new XElement(W + "t", "1"))))));
        var section = BookletSection(11906); section.AddFirst(new XElement(W + "footerReference", new XAttribute(W + "type", "default"), new XAttribute(R + "id", main.GetIdOfPart(footer)))); body.Add(section);
        Write(main, new XElement(W + "document", new XAttribute(XNamespace.Xmlns + "w", W), new XAttribute(XNamespace.Xmlns + "r", R), body)); return path;
    }

    static XElement BookletTable() => new(W + "tbl", new XElement(W + "tblPr",
        new XElement(W + "tblW", new XAttribute(W + "w", BookletWidth), new XAttribute(W + "type", "dxa")), new XElement(W + "tblLayout", new XAttribute(W + "type", "fixed")),
        new XElement(W + "tblBorders", new[] { "top", "left", "bottom", "right", "insideH", "insideV" }.Select(edge => new XElement(W + edge, new XAttribute(W + "val", "single"), new XAttribute(W + "sz", edge == "insideV" ? 9 : 7), new XAttribute(W + "color", "222222")))),
        new XElement(W + "tblCellMar", new[] { ("top", 100), ("bottom", 110), ("left", CellPadding), ("right", CellPadding) }.Select(x => new XElement(W + x.Item1, new XAttribute(W + "w", x.Item2), new XAttribute(W + "type", "dxa"))))),
        new XElement(W + "tblGrid", new XElement(W + "gridCol", new XAttribute(W + "w", AnalysisWidth)), new XElement(W + "gridCol", new XAttribute(W + "w", QuestionWidth))));

    static List<XElement> AnalysisColumn(int contentHeight, bool classroom, string? note = null)
    {
        // The three levels and writing fields are identical in every analysis export.
        // Allocate writing lines only after reserving the labels and teacher note.
        var result = new List<XElement>();
        var analysisHeight = 0;
        foreach (var (level, causes) in new[] {
            ("一级错误（低级）", new[] { "计算错误", "审题错误", "时间不够" }),
            ("二级错误（必须掌握）", new[] { "思路错误", "概念模糊" }),
            ("三级错误", new[] { "太难了" }) }) {
            result.Add(AnalysisText(level, "19", 380));
            foreach (var pair in causes.Chunk(2)) {
                var row = AnalysisText("□ " + pair[0], "18", 340);
                if (pair.Length == 2) {
                    row.Element(W + "pPr")!.Add(new XElement(W + "tabs", new XElement(W + "tab", new XAttribute(W + "val", "left"), new XAttribute(W + "pos", 1060))));
                    row.Add(new XElement(W + "r", new XElement(W + "tab")));
                    row.Add(P("□ " + pair[1], size: "18").Element(W + "r"));
                }
                result.Add(row);
            }
            result.Add(Spacer(180));
            analysisHeight += 380 + ((causes.Length + 1) / 2) * 340 + 180;
        }
        var noteHeight = 0;
        if (!string.IsNullOrWhiteSpace(note)) {
            // Teacher notes belong with the analysis, never in the answer area.
            result.Add(P("错因：" + note, size: "18", line: 300, after: 0));
            noteHeight = (int)Math.Ceiling((note.Length + 3) / 12d) * 300;
        }
        result.Add(Spacer(120));
        var writingBudget = contentHeight - analysisHeight - 120 - noteHeight - 3 * 380;
        var lines = Math.Max(1, writingBudget / (3 * AnswerLine));
        var fieldGap = 80 + Math.Max(0, (writingBudget - lines * 3 * AnswerLine) / 3);
        foreach (var label in new[] { "正确思路：", "卡点（哪一步不会）：", "套路总结：" }) {
            result.Add(AnalysisText(label, "19", 300));
            for (var i = 0; i < lines; i++) result.Add(AnalysisLine());
            result.Add(Spacer(fieldGap));
        }
        return result;
    }
    static XElement AnalysisText(string text, string size, int height)
    {
        var p = P(text, size: size, line: height, after: 0);
        p.Element(W + "pPr")!.Element(W + "spacing")!.SetAttributeValue(W + "lineRule", "exact");
        return p;
    }
    static XElement AnalysisLine()
    {
        var p = Spacer(AnswerLine);
        p.Element(W + "pPr")!.Add(new XElement(W + "pBdr", new[] { "bottom", "between" }.Select(side => new XElement(W + side,
            new XAttribute(W + "val", "single"), new XAttribute(W + "sz", 3), new XAttribute(W + "space", 0), new XAttribute(W + "color", "B8B8B8")))));
        return p;
    }
    static XElement LayoutMarker(string label, bool visible = true)
    {
        var p = Spacer(EndMarkerHeight); p.Element(W + "r")!.Element(W + "t")!.Value = visible ? MarkerPrefix + label : " "; return p;
    }
    static EndPosition[] ReadEnds(string path, int count)
    {
        using var pdf = PdfDocument.Open(path);
        var ends = pdf.GetPages().SelectMany(p => p.GetWords().Where(w => w.Text.StartsWith(MarkerPrefix + "END")).Select(w =>
            new EndPosition(int.Parse(w.Text[(MarkerPrefix.Length + 3)..]), p.Number, w.BoundingBox.Bottom))).ToArray();
        Guard.That(ends.Length == count && ends.Select(e => e.Index).Distinct().Count() == count, "分页高度校验失败"); return ends;
    }
    static XElement BookletSection(int width) => new(W + "sectPr", new XElement(W + "pgSz", new XAttribute(W + "w", width), new XAttribute(W + "h", 16838)),
        new XElement(W + "pgMar", new XAttribute(W + "top", BookletSideMargin), new XAttribute(W + "bottom", BookletBottomMarginTwips), new XAttribute(W + "left", BookletSideMargin), new XAttribute(W + "right", BookletSideMargin), new XAttribute(W + "header", 340), new XAttribute(W + "footer", 397)));
}
