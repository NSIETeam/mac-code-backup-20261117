using System.Globalization;
using System.Text.RegularExpressions;
using UglyToad.PdfPig.Content;

namespace Siyue;

public partial class WordDocuments
{
    static bool IsPdfMargin(PdfLine line, double height) =>
        ((line.Bottom<74 || line.Top>height-65) && Regex.IsMatch(line.Text,@"^\s*[一二三四五六七八九十0-9]+年级\s*[·•.．－-]\s*(?:奥数|数学)\s*$")) ||
        (line.Top > height - 65 && Regex.IsMatch(line.Text, @"^\s*(?:[-—·•]?\s*\d+\s*[-—·•]?|\d+\s*/\s*\d+|第\s*\d+\s*页(?:\s*共\s*\d+\s*页)?)\s*$")) ||
        (line.Bottom < 74 && Regex.IsMatch(line.Text, @"(?:20\d{2}.*(?:春|夏|秋|冬|小升初)|教育教研室|教研.*编)"));

    static bool IsPdfContext(string text) => Regex.IsMatch(text,
        @"^\s*(?:【(?:模块[一二三四五六七八九十0-9]+|题型\s*\d+[^】]*|基础训练|限时训练|课堂练习|课后作业)】.*|限时训练|模块[一二三四五六七八九十0-9]+\s+.*)$");

    // Some PDFs draw headings and question labels as pictures, while their body is selectable.
    // OCR is restricted to these small structural regions. All visible math stays in the original raster.
    async Task AddPdfImageMarkers(string input, Page page, List<PdfLine> lines, string dir, string? knownTitle, CancellationToken ct, bool strict = true)
    {
        var images = page.GetImages().Select(i => i.BoundingBox).ToArray();
        var titleImages = images.Where(b => page.Height - b.Top is > 70 and < 118 && b.Left > page.Width * .18 && b.Right < page.Width * .9 && b.Height <= 40).ToArray();
        if (titleImages.Length > 0) {
            var left = titleImages.Min(b=>b.Left); var right = titleImages.Max(b=>b.Right);
            var top = page.Height-titleImages.Max(b=>b.Top); var bottom = page.Height-titleImages.Min(b=>b.Bottom);
            if(PdfParsingPolicy.IsFixed(config) && knownTitle==null) {
                if(strict)throw new ApiException(422,$"PDF 第 {page.Number} 页标题为图片，已保留原图供核对讲次");
            } else {
            var title = knownTitle ?? Regex.Replace(await OcrPdfRegion(input,page.Number,left,top,right,bottom,dir,false,ct), @"\s+", "");
            title = Regex.Replace(title, "[“”‘’]", "");
            if (BookStructure.IsHeading(title) || IsPdfContext(title)) lines.Add(new(title,left,right,top,bottom));
            else if(strict) throw new ApiException(422,$"PDF 第 {page.Number} 页图片标题未能可靠识别，请将标题改为可复制文字后重试");
            }
        }
        var markers = images.Where(b => b.Left >= page.Width * .06 && b.Right <= page.Width * .15 && b.Width < 30 && b.Height is > 7 and < 35 && page.Height-b.Top > 74 && b.Bottom > 65).OrderByDescending(b=>b.Top).ToArray();
        var groups = new List<List<UglyToad.PdfPig.Core.PdfRectangle>>();
        foreach (var box in markers) {
            var group = groups.LastOrDefault(g=>Math.Abs(g[0].Top-box.Top)<7);
            if(group==null) groups.Add([box]); else group.Add(box);
        }
        if (PdfParsingPolicy.IsFixed(config) || QuestionRecognition.NamedOnly) return; // Fixed parsing never infers markers from image OCR.
        foreach(var group in groups) {
            // A bracketed label consists of adjacent, separate glyph pictures, not a geometry diagram.
            if(group.Count < 2) continue;
            var left=group.Min(b=>b.Left);var right=group.Max(b=>b.Right);
            if(right-left>35) continue;
            var top=page.Height-group.Max(b=>b.Top);var bottom=page.Height-group.Min(b=>b.Bottom);
            var near=lines.Where(l=>l.Left>=right-2 && l.Top < bottom && l.Bottom > top).OrderBy(l=>l.Top).FirstOrDefault();
            if(near!=null && IsQuestionStart(near.Text)) continue;
            var value=Regex.Replace(await OcrPdfRegion(input,page.Number,left,top,right,bottom,dir,true,ct), @"\s+", "");
            if(!Regex.IsMatch(value,@"^[0-9]{1,3}$")) { if(strict)throw new ApiException(422,$"PDF 第 {page.Number} 页图片题号未识别，请核对该页题号");continue; }
            if (near == null) lines.Add(new("【"+value+"】",left,right,top,bottom));
            else { lines.Remove(near);lines.Add(near with {Text="【"+value+"】 "+near.Text,Left=left,Top=Math.Min(top,near.Top),Bottom=Math.Max(bottom,near.Bottom)}); }
        }
    }

    async Task<string> OcrPdfRegion(string input,int page,double left,double top,double right,double bottom,string dir,bool number,CancellationToken ct)
    {
        Guard.That(!PdfParsingPolicy.IsFixed(config),"固定规则不识别图片文字，请在原图上框选校对",422);
        var prefix=Path.Combine(dir,"pdf-marker-"+Guid.NewGuid().ToString("N"));
        const double scale=5;
        var x=Math.Max(0,(int)Math.Floor((left-3)*scale));var y=Math.Max(0,(int)Math.Floor((top-3)*scale));
        var w=(int)Math.Ceiling((right+3)*scale)-x;var h=(int)Math.Ceiling((bottom+3)*scale)-y;
        try {
            await Run(config["Documents:PdfToPpm"]??"pdftoppm",["-f",page.ToString(),"-l",page.ToString(),"-singlefile","-r","360","-x",x.ToString(),"-y",y.ToString(),"-W",w.ToString(),"-H",h.ToString(),"-png",input,prefix],ct);
            var args=new List<string>{prefix+".png",prefix,"-l",number?"eng":"chi_sim+eng","--psm","7"};
            if(number)args.AddRange(["-c","tessedit_char_whitelist=0123456789"]);
            args.Add("tsv");
            await Run(config["Documents:Tesseract"]??"tesseract",args,ct);
            return string.Join(" ",File.ReadLines(prefix+".tsv").Skip(1).Select(l=>l.Split('\t')).Where(c=>c.Length>=12 && c[0]=="5").Select(c=>c[11]));
        } finally { foreach(var ext in new[]{".png",".tsv"}) if(File.Exists(prefix+ext)) File.Delete(prefix+ext); }
    }
}
