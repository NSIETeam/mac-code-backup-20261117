using System.Xml.Linq;
using UglyToad.PdfPig;
namespace Siyue;

public partial class WordDocuments
{
    // Used identically by the height probe and final analysis-layout output.
    // Source snapshots, images, formulae, tables and internal answer gaps are untouched.
    static void NormalizeBookletFlow(List<XElement> blocks)
    {
        static bool EmptyEdge(XElement p) => p.Name == W + "p"
            && !p.Descendants(W + "t").Any(t => !string.IsNullOrWhiteSpace(t.Value))
            && !p.Descendants(W + "pBdr").Any() && !p.Descendants(W + "numPr").Any()
            && p.Elements().Where(n => n.Name != W + "pPr").SelectMany(n => n.DescendantsAndSelf())
                .All(n => n.Name.Namespace == W && (n.Name.LocalName is "r" or "rPr" or "t" or "tab" or "br" or "lastRenderedPageBreak"
                    || n.Ancestors(W + "rPr").Any()));
        while (blocks.Count > 1 && EmptyEdge(blocks[0])) blocks.RemoveAt(0);
        while (blocks.Count > 1 && EmptyEdge(blocks[^1])) blocks.RemoveAt(blocks.Count - 1);
        foreach (var block in blocks) {
            foreach (var br in block.Descendants(W + "br").Where(n => (string?)n.Attribute(W + "type") is "page" or "column").ToArray()) br.Remove();
            foreach (var marker in block.Descendants(W + "lastRenderedPageBreak").ToArray()) marker.Remove();
            foreach (var p in block.DescendantsAndSelf(W + "p")) {
                var props = p.Element(W + "pPr");
                if (props == null) { props = new XElement(W + "pPr"); p.AddFirst(props); }
                // Removing these elements merely exposes inherited Word style values.
                // Explicit off overrides inherited page-break and keep-with-next chains.
                foreach (var name in new[] { "pageBreakBefore", "keepNext", "keepLines", "widowControl" }) {
                    props.Elements(W + name).Remove();
                    props.Add(new XElement(W + name, new XAttribute(W + "val", "0")));
                }
                var spacing = props.Element(W + "spacing");
                if (spacing == null) { spacing = new XElement(W + "spacing"); props.Add(spacing); }
                spacing.SetAttributeValue(W + "before", "0");
                spacing.SetAttributeValue(W + "beforeAutospacing", "0");
                spacing.SetAttributeValue(W + "afterAutospacing", "0");
            }
        }
    }

    static void VerifyClassroomFrames(string pdfPath, IReadOnlyList<ClassroomSheet> planned)
    {
        using var pdf = PdfDocument.Open(pdfPath);
        Guard.That(pdf.NumberOfPages == planned.Count, "短题合页校验失败：生成页数与排版计划不一致，请重新生成");
        for (var i = 0; i < planned.Count; i++) {
            // The full-width outer border and row separators are outside the narrow
            // question column, so source diagrams/table rules do not count as frames.
            var horizontal = pdf.GetPage(i + 1).Paths.SelectMany(p => p).SelectMany(p => p.Commands)
                .Select(c => c.GetBoundingRectangle()).Where(b => b.HasValue && b.Value.Width > 500 && b.Value.Width < 530 && b.Value.Left > 30 && b.Value.Height < 1 && b.Value.Bottom > 20)
                .Select(b => b!.Value.Bottom).Order().ToArray();
            var borders = new List<double>();
            foreach (var y in horizontal) if (borders.Count == 0 || y - borders[^1] > 2) borders.Add(y);
            Guard.That(borders.Count == planned[i].Items.Length + 1,
                "短题合页校验失败：题框数量与计划不一致，文件未发布，请重新生成");
        }
    }
}
