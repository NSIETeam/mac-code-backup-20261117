using System.Text.RegularExpressions;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using UglyToad.PdfPig;
using DW = DocumentFormat.OpenXml.Drawing.Wordprocessing;

namespace Siyue;

public partial class WordDocuments
{
    static readonly Regex ContextualPdfLabel = new(@"^\s*(?<label>[（(]\s*(?<n>\d{1,3})\s*[)）]|(?<n>\d{1,3})\s*[)）]|(?<circle>[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳]))\s*", RegexOptions.Compiled);
    static int ContextualNumber(Match m) => m.Groups["n"].Success ? int.Parse(m.Groups["n"].Value.Normalize(System.Text.NormalizationForm.FormKC)) : m.Groups["circle"].Value[0] - '①' + 1;
    static bool HasContextualPdfLabels(PdfOcrLine[] lines) {
        if (QuestionRecognition.NamedOnly) return false;
        var candidates=lines.Select(l=>(Line:l,Match:ContextualPdfLabel.Match(l.Text))).Where(x=>x.Match.Success).ToArray();
        return candidates.Length>=2 && candidates.Zip(candidates.Skip(1)).Any(p=>
            Math.Abs(p.First.Line.Left-p.Second.Line.Left)<20 && ContextualNumber(p.Second.Match)==ContextualNumber(p.First.Match)+1);
    }
    static string? PreviousPdfLabel(Body body,int page) {
        var paragraphs=body.Elements<Paragraph>().Where(p=>p.ParagraphProperties?.ParagraphStyleId?.Val?.Value!="SiyuePdfMargin").Reverse().ToArray();
        if(paragraphs.Length==0)return null;
        var last=XElement.Parse(paragraphs[0].OuterXml);var origin=PdfOrigin(last);
        if(IsPdfReview(last)||origin==null||origin.Page!=page-1)return null;
        foreach(var p in paragraphs) {
            var xml=XElement.Parse(p.OuterXml);
            if(IsPdfReview(xml)||!IsPdfRaster(xml))return null;
            if(xml.Descendants(WP+"docPr").Any(x=>(string?)x.Attribute("descr")=="question"))
                return PdfOrigin(xml)?.Label ?? Start.Match(Block(xml).Text).Groups["label"].Value;
        }
        return null;
    }
    static bool ExplicitPdfContinuation(string text,string? previous) {
        if(string.IsNullOrEmpty(previous))return false;
        var match=Start.Match(text);
        return match.Success && Regex.Replace(match.Groups["label"].Value,@"\s+","")==Regex.Replace(previous,@"\s+","") &&
            Regex.IsMatch(text[match.Length..],@"^\s*[（(【\[]?\s*(?:续|接上页|承上页|continued\b)",RegexOptions.IgnoreCase);
    }
    sealed record PdfLayoutAnchor(int Index,string Label,int Number,bool Weak,double Left,double Right,double Top,double Bottom,bool Context=false);
    sealed record PdfPlannedRegion(int[] Anchors,int[] Bounds,string[] Checks);
    sealed record PdfRegionPlan(PdfPlannedRegion[] Regions,int Columns,int MissingPixels,string? Error);

    async Task<int> StructuredPdfPage(string input,int page,string dir,MainDocumentPart main,Body body,uint startId,string[] headings,PdfOcrPage data,string engine,CancellationToken ct) {
        Guard.That(data.Lines.Length>0,"未识别到可靠文字",422);
        var previous=PreviousPdfLabel(body,page);
        var lines=data.Lines.OrderBy(l=>l.Top).ThenBy(l=>l.Left).ToArray();
        var anchors=new List<PdfLayoutAnchor>();
        bool ReferenceCell(PdfOcrLine l,Match m) => m.Success &&
            Regex.IsMatch(Regex.Replace(l.Text,@"\s+",""),@"^(?:例|练|题)[0-9０-９一二三四五六七八九十百]+(?:[-－—~～至、,，](?:例|练|题)?[0-9０-９一二三四五六七八九十百]+)*$") && l.Left>data.Width*.55 &&
            lines.Any(x=>x.Left<l.Left&&Math.Abs((x.Top+x.Bottom-l.Top-l.Bottom)/2)<20&&Regex.IsMatch(x.Text,@"^模块[一二三四五六七八九十0-9]+$")) &&
            lines.Any(x=>x.Top>l.Bottom&&IsPdfContext(x.Text));
        var references=new HashSet<int>();
        for(var i=0;i<lines.Length;i++) {
            var l=lines[i];var m=Start.Match(l.Text);
            if(ReferenceCell(l,m)){references.Add(i);continue;}
            if(m.Success && !ExplicitPdfContinuation(l.Text,previous))anchors.Add(new(i,m.Groups["label"].Value.Trim(),0,false,l.Left,l.Right,l.Top,l.Bottom));
        }
        // Parenthesized/circled numbers can be subparts. Only propose them when no
        // explicit question markers exist, and require review before publishing.
        if(anchors.Count==0 && previous==null && HasContextualPdfLabels(lines)) {
            foreach(var (l,i) in lines.Select((l,i)=>(l,i))) {
                var m=ContextualPdfLabel.Match(l.Text);
                if(m.Success)anchors.Add(new(i,m.Groups["label"].Value,ContextualNumber(m),true,l.Left,l.Right,l.Top,l.Bottom));
            }
        }
        var questionEdge=anchors.Select(a=>a.Left).DefaultIfEmpty(data.Width*.08).Min();
        for(var i=0;i<lines.Length;i++) {
            var l=lines[i];
            if(references.Contains(i)||IsPdfContext(l.Text)||BookStructure.IsHeading(l.Text)||IsPdfMargin(new PdfLine(l.Text,l.Left,l.Right,l.Top/data.Height*842,l.Bottom/data.Height*842),842))
                anchors.Add(new(i,l.Text,0,false,questionEdge,l.Right,l.Top,l.Bottom,true));
        }
        Guard.That(anchors.Any(a=>!a.Context) || previous!=null,"未识别到可靠题号或上一页衔接",422);
        Guard.That(anchors.All(a=>lines[a.Index].Confidence>=70),"题号识别置信度不足，请核对原稿",422);
        var png=await RenderAuditPage(input,page,dir,ct);
        var graphics=new List<double[]>();
        try {
            using var pdf=PdfDocument.Open(input);var pg=pdf.GetPage(page);
            var boxes=pg.GetImages().Select(i=>i.BoundingBox).Where(b=>b.Width*b.Height<pg.Width*pg.Height*.8)
                .Concat(pg.Paths.Where(p=>(p.IsFilled||p.IsStroked)&&!IsPdfPageBackground(p,pg)).Select(p=>p.GetBoundingRectangle()).Where(b=>b.HasValue).Select(b=>b!.Value));
            foreach(var b in boxes.Where(b=>b.Height>3 && b.Width>3))
                graphics.Add([b.Left/pg.Width*data.Width,(pg.Height-b.Top)/pg.Height*data.Height,b.Right/pg.Width*data.Width,(pg.Height-b.Bottom)/pg.Height*data.Height]);
        } catch(Exception e) when(RecoverablePdfPageError(e,ct)) { /* Pixel coverage still protects source ink; source review remains required. */ }
        var planPath=Path.Combine(dir,"structured-plan.json");var resultPath=Path.Combine(dir,"structured-result.json");
        await File.WriteAllTextAsync(planPath,Json.Write(new{anchors,graphics,allowContinuation=previous!=null}),ct);
        await Run(config["Documents:Python"]??"python3",[PixelScript,"layout",png,planPath,resultPath],ct);
        var plan=Json.Read<PdfRegionPlan>(await File.ReadAllTextAsync(resultPath,ct));
        Guard.That(plan.Error==null && plan.MissingPixels==0 && plan.Regions.Length>0,plan.Error??"存在未归属内容，请对照原稿校对",422);
        var titles=headings.Length>0?headings:lines.Where(l=>BookStructure.IsHeading(l.Text)&&l.Bottom<=anchors.Select(a=>a.Top).DefaultIfEmpty(data.Height).Min()).Select(l=>l.Text).ToArray();
        foreach(var title in titles.Where(t=>!lines.Any(l=>l.Text==t && plan.Regions.Any(r=>r.Anchors.Length==0 && l.Top<r.Bounds[3] && l.Bottom>r.Bounds[1]))))
            body.Append(new Paragraph(new Run(new Text(title))));
        var count=0;
        foreach(var region in plan.Regions) {
            var box=region.Bounds;var members=region.Anchors.Select(n=>anchors.Single(a=>a.Index==n)).ToArray();
            var regionLines=lines.Where(l=>l.Left<box[2]&&l.Right>box[0]&&l.Top<box[3]&&l.Bottom>box[1]).ToArray();
            var checks=region.Checks.Concat(new[]{"source-review"}).ToList();
            var isHeading=members.Length==0&&titles.Any(t=>regionLines.Any(l=>l.Text==t));
            var contextOnly=members.Length>0&&members.All(a=>a.Context)&&box[3]<=members.Max(a=>a.Bottom)+data.Height*.015;
            if(members.Length>0&&members.All(a=>a.Context)&&!contextOnly)checks.Add("orphan-prefix");
            // A letterhead above a recognized section is source context, never a
            // continuation of the previous page's question. Unknown prefixes stay reviewable.
            var headerPrefix=members.Length==0 && box[3]<data.Height*.16 &&
                anchors.Any(a=>a.Context&&a.Top>=box[3]&&a.Top<data.Height*.18);
            if(members.Any(a=>a.Weak))checks.Add("contextual-label");
            if(members.Length==0 && !isHeading && !headerPrefix &&
                !(previous!=null && regionLines.Any(l=>ExplicitPdfContinuation(l.Text,previous)||Regex.IsMatch(l.Text,@"^\s*[（(][0-9]+[）)]"))))checks.Add("orphan-prefix");
            var label=members.FirstOrDefault(a=>!a.Context)?.Label;
            var text=string.Join("\n",regionLines.Select(l=>l.Text));
            if(label!=null) {
                var first=members.First(a=>!a.Context);var canonical=first.Weak?"【"+first.Number+"】":label;
                text=canonical+" "+text;count++;
            }
            var prefix=Path.Combine(dir,$"structured-{page}-{count}-{startId}");
            await Run(config["Documents:PdfToPpm"]??"pdftoppm",["-f",page.ToString(),"-l",page.ToString(),"-singlefile","-r","180","-x",box[0].ToString(),"-y",box[1].ToString(),"-W",(box[2]-box[0]).ToString(),"-H",(box[3]-box[1]).ToString(),"-png",input,prefix],ct);
            var part=main.AddImagePart(ImagePartType.Png);using(var f=File.OpenRead(prefix+".png"))part.FeedData(f);File.Delete(prefix+".png");
            var width=(box[2]-box[0])*5080d;var scale=Math.Min(1,4_800_000/width);
            var drawing=PdfDrawing(main.GetIdOfPart(part),++startId,(long)(width*scale),(long)((box[3]-box[1])*5080*scale),label!=null);
            drawing.Descendants<DW.DocProperties>().Single().Title="siyue-pdf:"+Json.Write(new PdfProvenance(page,engine+"-regions",[box[0]/(double)data.Width,box[1]/(double)data.Height,box[2]/(double)data.Width,box[3]/(double)data.Height],checks.ToArray(),label));
            var props=new ParagraphProperties(new SpacingBetweenLines{After="0",Line="240",LineRule=LineSpacingRuleValues.Auto});
            if(isHeading){props.Append(new ParagraphStyleId{Val="SiyuePdfHeading"});text=titles.First(t=>regionLines.Any(l=>l.Text==t));}
            else if(contextOnly||headerPrefix){
                var margin=headerPrefix||members.All(a=>IsPdfMargin(new PdfLine(a.Label,a.Left,a.Right,a.Top/data.Height*842,a.Bottom/data.Height*842),842));
                props.Append(new ParagraphStyleId{Val=margin?"SiyuePdfMargin":members.Any(a=>BookStructure.IsHeading(a.Label))?"SiyuePdfHeading":"SiyuePdfContext"});text=contextOnly?members[0].Label:text;
                if(contextOnly&&members.Any(a=>BookStructure.IsHeading(a.Label))) {
                    int LectureNumber(string value)=>BookStructure.Number(Regex.Match(value,@"第\s*([0-9０-９一二三四五六七八九十百]+)\s*讲").Groups[1].Value);
                    var n=LectureNumber(text);text=headings.FirstOrDefault(h=>n>0&&LectureNumber(h)==n)??text;
                }
            }
            body.Append(new Paragraph(props,new Run(new RunProperties(new Vanish()),new Text(text)),new Run(drawing)));
        }
        return count;
    }
}
