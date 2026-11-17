using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using DW = DocumentFormat.OpenXml.Drawing.Wordprocessing;

namespace Siyue;
public record PdfInkAudit(int MissingPixels, int[]? MissingBounds, int CutInk = 0);
public record PdfOcrLine(string Text, double Left, double Right, double Top, double Bottom, double Confidence);
public record PdfOcrPage(int Width, int Height, PdfOcrLine[] Lines, int[][] Gaps);
public record PdfProvenance(int Page, string Engine, double[] Bounds, string[] Checks, string? Label = null);

public partial class WordDocuments
{
    public const string PdfEngineVersion = "poppler-fixed-regions-v14";
    static string PdfNumber(double n) => n.ToString(CultureInfo.InvariantCulture);
    string PixelScript => config["Documents:PixelScript"] ?? Path.Combine(AppContext.BaseDirectory, "pdf_pixels.py");
    async Task<string> RenderAuditPage(string input, int page, string dir, CancellationToken ct) {
        var info=new FileInfo(input);var stamp=System.Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(input+info.Length+info.LastWriteTimeUtc.Ticks)))[..12];
        var prefix = Path.Combine(dir, "pdf-audit-" + stamp + "-" + page);
        if (!File.Exists(prefix + ".png")) await Run(config["Documents:PdfToPpm"] ?? "pdftoppm", ["-f",page.ToString(),"-l",page.ToString(),"-singlefile","-r","180","-png",input,prefix],ct);
        return prefix + ".png";
    }
    async Task<PdfInkAudit> AuditPdfPixels(string input, int page, double width, double height, List<double[]> regions, string dir, CancellationToken ct) {
        var png = await RenderAuditPage(input,page,dir,ct);
        var plan = Path.Combine(dir,"audit-plan.json"); var result=Path.Combine(dir,"audit-result.json");
        var fixedStrategy=PdfParsingPolicy.IsFixed(config);
        var cuts=regions.Skip(fixedStrategy?0:2).SelectMany(r=>new[]{new[]{r[0],r[1],r[2]},new[]{r[0],r[3],r[2]}})
            .Where(r=>r[1]>(fixedStrategy?0:74)&&r[1]<height-(fixedStrategy?0:65)).ToArray();
        await File.WriteAllTextAsync(plan,Json.Write(new {width,height,regions,cuts}),ct);
        await Run(config["Documents:Python"] ?? "python3",[PixelScript,"audit",png,plan,result],ct);
        return Json.Read<PdfInkAudit>(await File.ReadAllTextAsync(result,ct));
    }
    static void TagPdf(Drawing drawing, int page, string engine, double[] bounds, params string[] checks) =>
        drawing.Descendants<DW.DocProperties>().Single().Title = "siyue-pdf:" + Json.Write(new PdfProvenance(page,engine,bounds,checks));
    public static PdfProvenance? PdfOrigin(XElement xml) {
        var value=(string?)xml.Descendants(WP+"docPr").FirstOrDefault()?.Attribute("title");
        if(value?.StartsWith("siyue-pdf:")!=true)return null;
        try{return Json.Read<PdfProvenance>(value[10..]);}catch(System.Text.Json.JsonException){return null;}
    }
    // Independent extraction engines only locate content. Visible source pixels remain authoritative.
    async Task<(PdfOcrPage Page,string Engine)> ReadRecoveryPage(string input,int page,string dir,CancellationToken ct,bool forceOcr=false) {
        Guard.That(!forceOcr || !PdfParsingPolicy.IsFixed(config), "固定规则无法读取图片文字，已保留原页供框选校对", 422);
        var png=await RenderAuditPage(input,page,dir,ct);var prefix=Path.Combine(dir,"recovery-"+page);
        var tsv=prefix+".tsv";var engine="poppler";
        try {
            Guard.That(!forceOcr,"改用 OCR",422);
            await Run(config["Documents:PdfToText"]??"pdftotext",["-f",page.ToString(),"-l",page.ToString(),"-bbox-layout",input,prefix+".html"],ct);
            var xml=XDocument.Load(prefix+".html");var pg=xml.Descendants().First(x=>x.Name.LocalName=="page");
            var w=double.Parse(pg.Attribute("width")!.Value,CultureInfo.InvariantCulture); var h=double.Parse(pg.Attribute("height")!.Value,CultureInfo.InvariantCulture);
            var bytes=await File.ReadAllBytesAsync(png,ct);var pw=System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16,4));var ph=System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(20,4));
            var text=new StringBuilder();var lineNo=0;
            foreach(var line in pg.Descendants().Where(x=>x.Name.LocalName=="line")) {
                lineNo++;foreach(var word in line.Elements().Where(x=>x.Name.LocalName=="word")) {
                    double A(string name)=>double.Parse(word.Attribute(name)!.Value,CultureInfo.InvariantCulture);
                    text.AppendLine($"5\t1\t1\t1\t{lineNo}\t1\t{(int)(A("xMin")/w*pw)}\t{(int)(A("yMin")/h*ph)}\t{(int)Math.Ceiling((A("xMax")-A("xMin"))/w*pw)}\t{(int)Math.Ceiling((A("yMax")-A("yMin"))/h*ph)}\t100\t{word.Value}");
                }
            }
            var plain=string.Concat(pg.Descendants().Where(x=>x.Name.LocalName=="word").Select(x=>x.Value));
            Guard.That(plain.Count(char.IsLetterOrDigit)>=12 && !plain.Contains('\ufffd') && !plain.Any(c=>c is >= '\ue000' and <= '\uf8ff'),"文字层不可用",422);
            await File.WriteAllTextAsync(tsv,text.ToString(),ct);
        } catch(Exception e) when(RecoverablePdfPageError(e,ct)) {
            // Fixed parsing uses only the PDF text layer and original source pixels.
            // A failed text layer must never silently launch a local OCR model.
            if(PdfParsingPolicy.IsFixed(config))throw new ApiException(422,"PDF 文字层不可用，已保留原页供框选校对");
            try {
                var rapidResult=prefix+"-rapid.json";
                await Run(config["Documents:OcrPython"]??"/opt/ocr/bin/python",[PixelScript,"rapid",png,prefix+"-rapid.tsv",rapidResult],ct);
                var rapid=Json.Read<PdfOcrPage>(await File.ReadAllTextAsync(rapidResult,ct));
                if(rapid.Lines.Any(l=>IsQuestionStart(l.Text)))return(rapid,"rapidocr-ppocrv4");
            } catch(Exception rapidError) when(RecoverablePdfPageError(rapidError,ct)) { /* Tesseract remains an independent fallback. */ }
            engine="tesseract";
            // OCR runs locally; separately enabled model assistance follows this stage.
            await Run(config["Documents:Tesseract"]??"tesseract",[png,prefix,"-l",config["Documents:OcrLanguages"]??"chi_sim+eng","--psm","3","tsv"],ct);
        }
        var result=prefix+".json";
        await Run(config["Documents:Python"]??"python3",[PixelScript,"ocr",png,tsv,result],ct);
        return (Json.Read<PdfOcrPage>(await File.ReadAllTextAsync(result,ct)),engine);
    }
    async Task<int> RecoverPdfPage(string input,int page,string dir,MainDocumentPart main,Body body,uint startId,string[] headings,CancellationToken ct) {
        PdfOcrPage data; string engine;
        try {(data,engine)=await ReadRecoveryPage(input,page,dir,ct);}
        catch(Exception e) when(RecoverablePdfPageError(e,ct)) {
            var png=await File.ReadAllBytesAsync(await RenderAuditPage(input,page,dir,ct),ct);
            data=new(System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(png.AsSpan(16,4)),System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(png.AsSpan(20,4)),[],[]);engine="retained-page";
        }
        if(!PdfParsingPolicy.IsFixed(config) && engine=="poppler" && !data.Lines.Any(l=>IsQuestionStart(l.Text)) && !HasContextualPdfLabels(data.Lines) && PreviousPdfLabel(body,page)==null)
            try {(data,engine)=await ReadRecoveryPage(input,page,dir,ct,true);} catch(Exception e) when(RecoverablePdfPageError(e,ct)) { /* Continue to optional model assistance. */ }
        if(!PdfParsingPolicy.IsFixed(config) && engine=="poppler") {
            // Selectable body text does not imply selectable decorative headings or labels.
            try {
                var (visual,_)=await ReadRecoveryPage(input,page,dir,ct,true);var combined=data.Lines.ToList();
                foreach(var l in visual.Lines.Where(l=>l.Confidence>=75&&(IsQuestionStart(l.Text)||IsPdfContext(l.Text)||BookStructure.IsHeading(l.Text))))
                    if(!combined.Any(x=>Math.Abs((x.Top+x.Bottom-l.Top-l.Bottom)/2)<Math.Max(12,l.Bottom-l.Top)&&Math.Abs(x.Left-l.Left)<data.Width*.06&&(IsQuestionStart(x.Text)||IsPdfContext(x.Text)||BookStructure.IsHeading(x.Text))))combined.Add(l);
                data=data with {Lines=combined.ToArray()};
            } catch(Exception e) when(RecoverablePdfPageError(e,ct)) { /* Native markers and pixel coverage still apply. */ }
        }
        try {data=await SupplementPdfStructure(input,page,dir,data,ct);}
        catch(Exception e) when(RecoverablePdfPageError(e,ct)) { /* Model or retained-page fallback can still inspect intact source pixels. */ }
        if(!PdfParsingPolicy.IsFixed(config))
            (data,engine)=await AssistPdfPage(input,page,dir,data,engine,PreviousPdfLabel(body,page),ct);
        return await StructuredPdfPage(input,page,dir,main,body,startId,headings,data,engine,ct);
    }

    // Formula OCR may omit a label while recognizing later labels. Recover structure
    // independently from the PDF glyphs and small label pictures; never rewrite math.
    async Task<PdfOcrPage> SupplementPdfStructure(string input,int page,string dir,PdfOcrPage data,CancellationToken ct) {
        using var pdf=UglyToad.PdfPig.PdfDocument.Open(input);var pg=pdf.GetPage(page);
        var native=PdfLines(pg);
        try {await AddPdfImageMarkers(input,pg,native,dir,null,ct,false);}
        catch(Exception e) when(RecoverablePdfPageError(e,ct)) { /* Keep both original page and available OCR if local marker recognition fails. */ }
        var combined=data.Lines.ToList();
        foreach(var l in native.Where(l=>IsQuestionStart(l.Text)||IsPdfContext(l.Text)||BookStructure.IsHeading(l.Text)||IsPdfMargin(l,pg.Height))) {
            var candidate=new PdfOcrLine(l.Text,l.Left/pg.Width*data.Width,l.Right/pg.Width*data.Width,l.Top/pg.Height*data.Height,l.Bottom/pg.Height*data.Height,100);
            var existing=combined.Where(x=>Math.Abs((x.Top+x.Bottom-candidate.Top-candidate.Bottom)/2)<Math.Max(12,candidate.Bottom-candidate.Top) && Math.Abs(x.Left-candidate.Left)<data.Width*.06).ToArray();
            if(existing.Any(x=>IsQuestionStart(x.Text)||IsPdfContext(x.Text)||BookStructure.IsHeading(x.Text)))continue;
            combined.Add(candidate);
        }
        return data with {Lines=combined.OrderBy(l=>l.Top).ThenBy(l=>l.Left).ToArray()};
    }
}
