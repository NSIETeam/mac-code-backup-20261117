using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using System.Xml.Linq;
using UglyToad.PdfPig;

namespace Siyue;

public partial class WordDocuments
{
    const int ClassroomPageHeight = 16838 - BookletSideMargin - BookletBottomMarginTwips;
    // Fixed cell padding and the 正解 label consume space, but optional answer
    // whitespace (including the former 2x rule) never decides pagination.
    const int ClassroomQuestionChrome = 700;
    sealed record ClassroomSheet(BookletItem[] Items, int Height);

    static bool ClassroomFitsHalf(int promptHeight, int pageHeight)
        => promptHeight <= pageHeight / 2 - ClassroomQuestionChrome;

    static bool ClassroomPairFits(BookletItem first, BookletItem second, int height)
    {
        // The renderer adds cell margins outside the row minimum. Reserve them
        // for the threshold, independently of the rendered-border page fill.
        var promptPageHeight = height - 2 * (100 + 110);
        return ClassroomFitsHalf(first.Height, promptPageHeight) && ClassroomFitsHalf(second.Height, promptPageHeight);
    }

    static int[] ClassroomRowHeights(ClassroomSheet sheet)
        => sheet.Items.Length == 1 ? [sheet.Height] : [sheet.Height / 2, sheet.Height / 2];

    async Task<int> MeasureClassroomHeading(string[] metadata, string dir, CancellationToken ct)
    {
        var path = Path.Combine(dir, "booklet-heading-measure.docx");
        using (var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document)) {
            var main = doc.AddMainDocumentPart();
            var body = new XElement(W + "body", LayoutMarker("HEADERSTART"), ClassroomHeading(metadata), LayoutMarker("HEADERSTOP"), BookletSection(11906));
            Write(main, new XElement(W + "document", new XAttribute(XNamespace.Xmlns + "w", W), body));
        }
        using var pdf = PdfDocument.Open(await Convert(path, "pdf", dir, ct));
        Guard.That(pdf.NumberOfPages == 1, "讲义页眉信息过长，请缩短课程标题后重试");
        var marks = pdf.GetPage(1).GetWords().Where(w => w.Text.StartsWith(MarkerPrefix + "HEADER")).ToArray();
        var start = marks.Single(w => w.Text == MarkerPrefix + "HEADERSTART");
        var stop = marks.Single(w => w.Text == MarkerPrefix + "HEADERSTOP");
        return (int)Math.Ceiling((start.BoundingBox.Bottom - stop.BoundingBox.Bottom) * 20);
    }

    // Preserve question order; two prompts fitting half a page share equal rows.
    // A longer prompt receives its own page, even beside a very short neighbour.
    async Task<(string Docx, string Pdf)> ClassroomBookletPdf(BookletItem[] items, string[] metadata, string dir, CancellationToken ct)
    {
        var headerHeight = await MeasureClassroomHeading(metadata, dir, ct);
        var height = ClassroomPageHeight - headerHeight - 160;
        Guard.That(height >= 11000, "讲义页眉信息过长，请缩短课程标题后重试");
        var sheets = new List<ClassroomSheet>();
        for (var i = 0; i < items.Length;) {
            var count = i + 1 < items.Length &&
                ClassroomPairFits(items[i], items[i + 1], height) ? 2 : 1;
            sheets.Add(new(items.Skip(i).Take(count).ToArray(), height)); i += count;
        }
        var path = WriteClassroomBooklet(sheets, metadata, dir, 0);
        var pdf = await Convert(path, "pdf", dir, ct);
        var appliedInset = 0;
        // Renderer rounding and paragraph borders vary slightly across installations.
        // Back off row minimums only; never change question text, images or font size.
        var oversized = sheets.Any(s => s.Items.Any(i => i.Height + 1000 > s.Height));
        for (var attempt = 1; !oversized && PageCount(pdf) > sheets.Count && attempt <= 3; attempt++) {
            path = WriteClassroomBooklet(sheets, metadata, dir, attempt * 100);
            pdf = await Convert(path, "pdf", dir, ct);
            appliedInset = attempt * 100;
        }
        Guard.That(oversized || PageCount(pdf) == sheets.Count, "题框分页校验未通过，请重新生成");
        if (!oversized) {
            // Fill to the agreed 15 mm margin using the real rendered bottom border,
            // not an estimate of how Word interprets table and paragraph spacing.
            var filled = sheets.ToArray();
            using (var rendered = PdfDocument.Open(pdf)) {
                for (var i = 0; i < filled.Length; i++) {
                    var borders = rendered.GetPage(i + 1).Paths.SelectMany(p => p).SelectMany(p => p.Commands)
                        .Select(c => c.GetBoundingRectangle()).Where(b => b.HasValue && b.Value.Width > 500 && b.Value.Height < 1 && b.Value.Bottom > 20)
                        .Select(b => b!.Value.Bottom).ToArray();
                    Guard.That(borders.Length > 0, "题框页边距校验失败");
                    var delta = (int)Math.Floor((borders.Min() - BookletBottomMarginTwips / 20d - 1.5) * 20);
                    filled[i] = filled[i] with { Height = filled[i].Height - appliedInset * filled[i].Items.Length + delta };
                }
            }
            var filledPath = WriteClassroomBooklet(filled.ToList(), metadata, dir, 0, "filled");
            var filledPdf = await Convert(filledPath, "pdf", dir, ct);
            if (PageCount(filledPdf) == sheets.Count) { VerifyClassroomFrames(filledPdf, sheets); return (filledPath, filledPdf); }
        }
        if (!oversized) VerifyClassroomFrames(pdf, sheets);
        return (path, pdf);
    }

    static IEnumerable<XElement> ClassroomHeading(string[] metadata)
    {
        var heading = P(metadata[0], size: "36", line: 520, after: 0);
        // Mixed font sizes share one baseline. Measure real wrapping for unusually
        // long names or historical lecture selections instead of clipping text.
        heading.Element(W + "pPr")!.Element(W + "spacing")!.SetAttributeValue(W + "lineRule", "atLeast");
        heading.Add(P("    " + string.Join("    ", metadata.Skip(1)), size: "18").Element(W + "r"));
        yield return heading;
        yield return Spacer(280);
    }

    static string WriteClassroomBooklet(List<ClassroomSheet> sheets, string[] metadata, string dir, int inset, string suffix = "base")
    {
        var path = Path.Combine(dir, $"classroom-balanced-{suffix}-{inset}.docx");
        using var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document);
        var main = doc.AddMainDocumentPart(); var body = new XElement(W + "body");
        var index = 0;
        foreach (var sheet in sheets) {
            if (index > 0) {
                var nextPage = Spacer(20);
                nextPage.Element(W + "pPr")!.AddFirst(new XElement(W + "pageBreakBefore"));
                body.Add(nextPage);
            }
            body.Add(ClassroomHeading(metadata));
            var table = BookletTable();
            var rowHeights = ClassroomRowHeights(sheet);
            for (var row = 0; row < sheet.Items.Length; row++) {
                var item = sheet.Items[row];
                var target = rowHeights[row] - inset;
                using var source = WordprocessingDocument.Open(item.Source, false);
                var content = ComposeBlocks(main, source.MainDocumentPart!, Json.Read<ContentBlock[]>(item.Question.BlocksJson), "balanced" + index++, item.Question.Label, 360, 0, compactBookletFlow: true);
                content.Add(P("正解：", line: 360, after: 0));
                var fits = item.Height + 1000 <= target;
                var props = new XElement(W + "trPr", new XElement(W + "trHeight", new XAttribute(W + "val", target), new XAttribute(W + "hRule", "atLeast")));
                if (fits) props.Add(new XElement(W + "cantSplit"));
                table.Add(new XElement(W + "tr", props,
                    // Leave breathing room under the handwriting rules: their discrete
                    // line count must never become the minimum height of a full-page row.
                    Cell(AnalysisWidth, AnalysisColumn(target - 250, true, item.Question.Note)), Cell(QuestionWidth, content)));
            }
            body.Add(table);
        }
        // Word requires a paragraph after the final table. Make its height explicit
        // so the renderer cannot add a normal text line and create a blank page.
        body.Add(Spacer(20));
        var footer = main.AddNewPart<FooterPart>();
        footer.Footer = new Footer(Pack(new XElement(W + "p", new XElement(W + "pPr", new XElement(W + "jc", new XAttribute(W + "val", "center")),
            new XElement(W + "spacing", new XAttribute(W + "line", 240), new XAttribute(W + "lineRule", "exact"), new XAttribute(W + "before", 0), new XAttribute(W + "after", 0))),
            new XElement(W + "fldSimple", new XAttribute(W + "instr", "PAGE"), new XElement(W + "r", new XElement(W + "rPr", Fonts(), new XElement(W + "sz", new XAttribute(W + "val", "18"))), new XElement(W + "t", "1"))))));
        var section = BookletSection(11906);
        section.AddFirst(new XElement(W + "footerReference", new XAttribute(W + "type", "default"), new XAttribute(R + "id", main.GetIdOfPart(footer))));
        body.Add(section);
        Write(main, new XElement(W + "document", new XAttribute(XNamespace.Xmlns + "w", W), new XAttribute(XNamespace.Xmlns + "r", R), body));
        return path;
    }
}
