using System.Buffers.Binary;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using UglyToad.PdfPig;
using DR = DocumentFormat.OpenXml.Drawing;
using DW = DocumentFormat.OpenXml.Drawing.Wordprocessing;
using PIC = DocumentFormat.OpenXml.Drawing.Pictures;
namespace Siyue;
public partial class WordDocuments
{
    public async Task<(string? Docx, string Pdf)> PrintFilePdf(ExportDocument d, string dir, CancellationToken ct)
    {
        var ext = Path.GetExtension(d.SourceFileName).ToLowerInvariant();
        var input = await Materialize(d.SourceKey, dir, "source" + ext, ct);
        if (ext == ".pdf") {
            using var pdf = PdfDocument.Open(input); Guard.That(pdf.NumberOfPages is > 0 and <= 500, "PDF 页数应为 1–500 页"); return (null, input);
        }
        string docx;
        if (ext is ".jpg" or ".jpeg" or ".png") {
            var bytes = await File.ReadAllBytesAsync(input, ct); var (width, height, png) = ImageSize(bytes);
            Guard.That(width > 0 && height > 0 && (long)width * height <= 40_000_000, "图片过大或尺寸无效");
            docx = Path.Combine(dir, "image.docx");
            using (var doc = WordprocessingDocument.Create(docx, WordprocessingDocumentType.Document)) {
                var main = doc.AddMainDocumentPart(); var part = main.AddImagePart(png ? ImagePartType.Png : ImagePartType.Jpeg);
                using var stream = new MemoryStream(bytes); part.FeedData(stream);
                var scale = Math.Min(6_858_000d / width, 9_753_600d / height); long cx = (long)(width * scale), cy = (long)(height * scale);
                var drawing = new Drawing(new DW.Inline(new DW.Extent { Cx = cx, Cy = cy }, new DW.DocProperties { Id = 1, Name = "打印图片" },
                    new DR.Graphic(new DR.GraphicData(new PIC.Picture(new PIC.NonVisualPictureProperties(new PIC.NonVisualDrawingProperties { Id = 0, Name = "图片" }, new PIC.NonVisualPictureDrawingProperties()),
                        new PIC.BlipFill(new DR.Blip { Embed = main.GetIdOfPart(part) }, new DR.Stretch(new DR.FillRectangle())),
                        new PIC.ShapeProperties(new DR.Transform2D(new DR.Offset { X = 0, Y = 0 }, new DR.Extents { Cx = cx, Cy = cy }), new DR.PresetGeometry(new DR.AdjustValueList()) { Preset = DR.ShapeTypeValues.Rectangle }))) { Uri = "http://schemas.openxmlformats.org/drawingml/2006/picture" })) { DistanceFromTop = 0, DistanceFromBottom = 0, DistanceFromLeft = 0, DistanceFromRight = 0 });
                main.Document = new Document(new Body(new Paragraph(new ParagraphProperties(new Justification { Val = JustificationValues.Center }, new SpacingBetweenLines { After = "0" }), new Run(drawing)),
                    new SectionProperties(new PageSize { Width = 11906, Height = 16838 }, new PageMargin { Top = 720, Bottom = 720, Left = 720, Right = 720 })));
                main.Document.Save();
            }
        } else {
            docx = ext == ".doc" ? await Convert(input, "docx", dir, ct) : input; CheckPackage(docx);
        }
        var output = await Convert(docx, "pdf", dir, ct);
        using (var pdf = PdfDocument.Open(output)) Guard.That(pdf.NumberOfPages is > 0 and <= 500, "文件页数应为 1–500 页");
        return (ext is ".doc" or ".docx" ? docx : null, output);
    }
    static (int Width, int Height, bool Png) ImageSize(byte[] bytes)
    {
        if (bytes.Length >= 24 && bytes.AsSpan(0, 8).SequenceEqual(new byte[] {137,80,78,71,13,10,26,10})) return (BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16,4)), BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(20,4)), true);
        Guard.That(bytes.Length > 4 && bytes[0] == 0xff && bytes[1] == 0xd8, "图片格式无效，请选择 PNG 或 JPG");
        for (var offset = 2; offset + 8 < bytes.Length;) {
            Guard.That(bytes[offset++] == 0xff, "JPG 文件结构无效");
            while (offset < bytes.Length && bytes[offset] == 0xff) offset++;
            Guard.That(offset + 2 < bytes.Length, "JPG 文件不完整"); var marker = bytes[offset++];
            if (marker is 0xd8 or 0xd9 or 0x01 || marker is >= 0xd0 and <= 0xd7) continue;
            var length = BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(offset,2)); Guard.That(length >= 2 && offset + length <= bytes.Length, "JPG 文件不完整");
            if (marker is >= 0xc0 and <= 0xc3 or >= 0xc5 and <= 0xc7 or >= 0xc9 and <= 0xcb or >= 0xcd and <= 0xcf) return (BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(offset+5,2)), BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(offset+3,2)), false);
            offset += length;
        }
        throw new ApiException(400, "无法读取图片尺寸");
    }
}
