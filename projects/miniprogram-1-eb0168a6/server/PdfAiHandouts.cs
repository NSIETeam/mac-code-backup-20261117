using System.Security.Cryptography;
using System.Text;
using System.Xml.Linq;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using UglyToad.PdfPig;

namespace Siyue;
public record HandoutProgress(string Stage,int Current,int Total,string Message);
public record AiPixelPlan(AiHandoutRegion[] Regions,int MissingPixels,int CutInk,int[]? MissingBounds);
public record AiPreviewResult(string[] Paths,bool Half);

public partial class WordDocuments
{
    public Func<HandoutProgress,CancellationToken,Task>? HandoutProgressCallback {get;set;}
    public string? NormalizedSourcePdf {get;private set;}
    public string? SourcePdfInput {get;set;}
    string AiImageScript => config["Documents:AiImageScript"] ?? Path.Combine(AppContext.BaseDirectory,"ai_handout_images.py");
    Task HandoutProgress(string stage,int current,int total,string message,CancellationToken ct) =>
        HandoutProgressCallback?.Invoke(new(stage,current,total,message),ct) ?? Task.CompletedTask;
    static string? AiLectureTitle(int? sequence,string? title) {
        if(title==null)return null;
        var match=System.Text.RegularExpressions.Regex.Match(title,@"^\s*第\s*([0-9０-９一二三四五六七八九十百]+)\s*(?:讲|课时|课)\s*[：:、.．—-]?\s*(.*)$");
        return match.Success && sequence==BookStructure.Number(match.Groups[1].Value)?match.Groups[2].Value.Trim():title.Trim();
    }

    // Already segmented DOCX slices must retain their authoritative boundaries and source pixels.
    static bool HasAiLayout(string path) {
        if(!Path.GetExtension(path).Equals(".docx",StringComparison.OrdinalIgnoreCase))return false;
        CheckPackage(path);using var pack=WordprocessingDocument.Open(path,false);
        return pack.MainDocumentPart?.Document?.Body?.Descendants<DocumentFormat.OpenXml.Drawing.Wordprocessing.DocProperties>()
            .Any(p=>p.Title?.Value?.Contains("\"engine\":\"ai-layout\"",StringComparison.Ordinal)==true)==true;
    }
    async Task<string?> NormalizeAiHandout(string input,string dir,CancellationToken ct) {
        if(HasAiLayout(input))return input;
        var references=ReadAiSourceReferences(input);
        if(PdfParsingPolicy.IsFixed(config))
            return references.Length>0?await NormalizeFixedReferenceHandout(input,references,dir,ct):null;
        var settings=await modelSettings.Resolve();
        if(!AiHandoutLayout.Enabled(settings)) {
            Guard.That(references.Length==0,"讲次已拆出，AI识题服务暂未启用，请恢复模型配置后重试。",503);
            await HandoutProgress("fallback",0,0,"当前未配置可用的视觉模型，先按题号规则识别，结果需要核对。",ct);
            return null;
        }
        Guard.That(references.Length==0 || SourcePdfInput!=null,"讲次缺少原讲义PDF，请重新拆讲",422);
        var pdfInput=references.Length>0?SourcePdfInput!:Path.GetExtension(input).Equals(".pdf",StringComparison.OrdinalIgnoreCase)?input:await Convert(input,"pdf",dir,ct);
        NormalizedSourcePdf=pdfInput;
        using var pdf=PdfDocument.Open(pdfInput);
        Guard.That(pdf.NumberOfPages is >0 and <=500,"讲义最多500页，请按册上传",413);
        var selected=references.Length>0?references:Enumerable.Range(1,pdf.NumberOfPages).Select(n=>new AiSourcePageReference(n,0,1)).ToArray();
        Guard.That(selected.All(r=>r.Page>=1&&r.Page<=pdf.NumberOfPages&&r.Top>=0&&r.Bottom<=1&&r.Top<r.Bottom),"讲次页码范围无效",422);
        var total=selected.Length;
        string fingerprint;await using(var original=File.OpenRead(pdfInput))fingerprint=System.Convert.ToHexString(await SHA256.HashDataAsync(original,ct));
        var output=Path.Combine(dir,"ai-normalized.docx");
        using var pack=WordprocessingDocument.Create(output,WordprocessingDocumentType.Document);
        var main=pack.AddMainDocumentPart();var body=new Body();main.Document=new Document(body);
        string[] previousIds=[];string previousSummary=references.Length>0?string.Join(" ",BookStructure.Read(input).Select(x=>Block(x).Text).Where(BookStructure.IsHeading)):"";int? lectureSequence=null;string? lectureTitle=null;
        uint imageId=0;long bytes=0;var errors=0;var reviewed=0;var calls=0;var referenceSection=false;
        var budget=Math.Clamp(config.GetValue("Documents:AiHandoutMaxPages",500),1,500);
        for(var pageIndex=0;pageIndex<total;pageIndex++) {
            var window=selected[pageIndex];var number=window.Page;
            ct.ThrowIfCancellationRequested();var page=pdf.GetPage(number);
            Guard.That(page.Width is >0 and <=3600 && page.Height is >0 and <=3600 && page.Width*page.Height*6.25<=40_000_000,"讲义页面尺寸过大，请压缩大尺寸扫描页后上传",413);
            await HandoutProgress("recognizing",pageIndex,total,$"AI正在识别本讲第 {pageIndex+1}/{total} 页（原稿第 {number} 页）；{reviewed} 页有疑点。",ct);
            var pageLines=PdfLines(page);
            var visibleLines=pageLines.Where(l=>l.Top/page.Height>=window.Top-.002&&l.Bottom/page.Height<=window.Bottom+.002).ToArray();
            var coordinateText=string.Join("\n",visibleLines.Select(l=>System.Text.Json.JsonSerializer.Serialize(new{text=l.Text,bounds=new[]{Math.Round(l.Left/page.Width,4),Math.Round((l.Top/page.Height-window.Top)/(window.Bottom-window.Top),4),Math.Round(l.Right/page.Width,4),Math.Round((l.Bottom/page.Height-window.Top)/(window.Bottom-window.Top),4)}},AiHandoutLayout.PromptJson)));
            var nextWindow=pageIndex+1<total?selected[pageIndex+1]:null;
            string? nextText=null;
            if(nextWindow!=null) {var nextPage=pdf.GetPage(nextWindow.Page);nextText=string.Join("\n",PdfLines(nextPage).Where(l=>l.Top/nextPage.Height>=nextWindow.Top-.002&&l.Bottom/nextPage.Height<=nextWindow.Bottom+.002).Select(l=>l.Text));}
            var context=new AiHandoutPageContext(number,coordinateText,previousSummary,nextText,previousIds);
            var contextHash=System.Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Json.Write(new{context,window}))));
            var cache=$"pdf-cache/ai-layout-v2/{fingerprint}/{AiHandoutLayout.Stamp(settings)}/{number}-{contextHash}.json";
            AiHandoutValidation? validation=null;string? failure=null;
            var sourcePng=await RenderAuditPage(pdfInput,number,dir,ct);var png=sourcePng;
            if(window.Top>0 || window.Bottom<1) {
                png=Path.Combine(dir,$"ai-window-{number}.png");var cropPlan=Path.Combine(dir,$"ai-window-{number}.json");
                await File.WriteAllTextAsync(cropPlan,Json.Write(new{top=window.Top,bottom=window.Bottom}),ct);
                await Run(config["Documents:Python"]??"python3",[AiImageScript,"crop",sourcePng,cropPlan,png],ct);
            }
            try {
                try {await using var cached=await objects.Get(cache,ct);using var reader=new StreamReader(cached);
                    validation=AiHandoutLayout.Validate(Json.Read<AiHandoutPage>(await reader.ReadToEndAsync(ct)),number,previousIds);
                    if(!validation.IsValid)validation=null;
                } catch(Exception e) when(e is FileNotFoundException or DirectoryNotFoundException or System.Text.Json.JsonException || e is Amazon.S3.AmazonS3Exception s && s.StatusCode==System.Net.HttpStatusCode.NotFound) { }
                if(validation==null) {
                    Guard.That(calls<budget && errors<2,"AI处理暂未完成，原页已保留；请稍后重试或校对该页。",422);
                    var resized=Path.Combine(dir,$"ai-model-{number}.png");var empty=Path.Combine(dir,"ai-empty.json");await File.WriteAllTextAsync(empty,"{}",ct);
                    await Run(config["Documents:Python"]??"python3",[AiImageScript,"resize",png,empty,resized],ct);
                    calls++;
                    var modelImage=await File.ReadAllBytesAsync(resized,ct);
                    try {validation=await AiHandoutLayout.Review(settings,modelClients,modelImage,context,ct,
                        Math.Clamp(settings.GetValue("LearningAi:TimeoutSeconds",60),15,120));}
                    catch(ApiException e) when(e.Status==422) {
                        // One bounded retry for malformed model output. The page budget counts
                        // pages, so a retry must not silently skip a later source page.
                        validation=await AiHandoutLayout.Review(settings,modelClients,modelImage,context,ct,
                            Math.Clamp(settings.GetValue("LearningAi:TimeoutSeconds",60),15,120));
                    }
                    errors=0;File.Delete(resized);
                }
                Guard.That(validation.Issues.All(x=>!x.InvalidatesPlan || x.Code=="region-overlap"),"AI版面边界或跨页关系尚不明确，原页已保留。",422);
                var regions=validation.Page.Regions.ToList();
                // Exact text-layer page numbers are harmless margins; repair only their tiny
                // measured glyph box, never a broad strip which could conceal a question.
                foreach(var footer in visibleLines.Where(l=>System.Text.RegularExpressions.Regex.IsMatch(l.Text.Trim(),@"^\d{1,4}$") && l.Top/page.Height>.90 && l.Left/page.Width>.35 && l.Right/page.Width<.65)) {
                    double[] fb=[Math.Max(0,footer.Left/page.Width-.002),Math.Max(0,(footer.Top/page.Height-window.Top)/(window.Bottom-window.Top)-.002),Math.Min(1,footer.Right/page.Width+.002),Math.Min(1,(footer.Bottom/page.Height-window.Top)/(window.Bottom-window.Top)+.002)];
                    if(regions.Any(r=>r.Bounds[0]<=fb[0]&&r.Bounds[1]<=fb[1]&&r.Bounds[2]>=fb[2]&&r.Bounds[3]>=fb[3]))continue;
                    var at=regions.FindIndex(r=>r.Kind=="margin"&&r.Bounds[1]>.88&&Math.Abs((r.Bounds[0]+r.Bounds[2]-fb[0]-fb[2])/2)<.04);
                    if(at>=0){var rb=regions[at].Bounds;regions[at]=regions[at] with{Bounds=[Math.Min(rb[0],fb[0]),Math.Min(rb[1],fb[1]),Math.Max(rb[2],fb[2]),Math.Max(rb[3],fb[3])]};}
                    else if(regions.Count<AiHandoutLayout.MaxRegions)regions.Add(new("margin","",fb,.99,Reason:"原文字层居中页脚编号"));
                }
                validation=AiHandoutLayout.Validate(validation.Page with{Regions=regions.ToArray()},number,previousIds);
                var plan=Path.Combine(dir,$"ai-plan-{number}.json");var result=Path.Combine(dir,$"ai-audit-{number}.json");
                await File.WriteAllTextAsync(plan,Json.Write(validation.Page),ct);
                await Run(config["Documents:Python"]??"python3",[AiImageScript,"layout",png,plan,result],ct);
                var pixels=Json.Read<AiPixelPlan>(await File.ReadAllTextAsync(result,ct));
                var checkedPlan=AiHandoutLayout.Validate(validation.Page with{Regions=pixels.Regions},number,previousIds);
                Guard.That(checkedPlan.IsValid && pixels.MissingPixels<=24 && pixels.CutInk<=4,
                    "AI选框存在遗漏或切到文字、图形，原页已保留供核对。",422);
                if(!checkedPlan.NeedsReview) {
                    await using var data=new MemoryStream(Encoding.UTF8.GetBytes(Json.Write(validation.Page)));
                    await objects.Put(cache,data,"application/json",ct);
                }
                validation=checkedPlan;
            } catch(Exception e) when(RecoverablePdfPageError(e,ct) || e is HttpRequestException) {
                errors++;failure=e is ApiException?e.Message:"本页AI服务暂不可用，原页已完整保留。";
            }
            if(failure!=null || validation==null) {
                reviewed++;
                // Reliable native lecture headings remain available even on a page requiring manual crops.
                var nativeHeadings=visibleLines.Where(l=>BookStructure.IsHeading(l.Text)).ToArray();
                // A failed page may contain several lectures; never move all its titles before one full-page image.
                if(nativeHeadings.Length==1 && nativeHeadings[0].Top/page.Height<.2)
                    body.Append(new Paragraph(new ParagraphProperties(new ParagraphStyleId{Val="SiyuePdfHeading"}),new Run(new Text(nativeHeadings[0].Text))));
                var part=main.AddImagePart(ImagePartType.Png);using(var stream=File.OpenRead(sourcePng)){bytes+=stream.Length;part.FeedData(stream);}
                var drawing=PdfDrawing(main.GetIdOfPart(part),++imageId,4_800_000,(long)(4_800_000*page.Height/page.Width),false);
                TagPdf(drawing,number,"ai-layout",[0,0,1,1],"ai-review","ai-lecture-review");
                var retained=XElement.Parse(new Paragraph(new Run(drawing)).OuterXml);
                if(window.Top>0||window.Bottom<1) {
                    retained=XElement.Parse(PdfRegions.Crop(Block(retained),new(0,window.Top,1,window.Bottom,$"第 {number} 页待校对")).Xml);
                    retained.Descendants(WP+"docPr").Single().SetAttributeValue("title","siyue-pdf:"+Json.Write(new PdfProvenance(number,"ai-layout",[0,window.Top,1,window.Bottom],["ai-review"],null)));
                }
                retained.AddFirst(new XElement(W+"pPr",new XElement(W+"pStyle",new XAttribute(W+"val",PdfReviewStyle))),new XElement(W+"r",new XElement(W+"t",$"第 {number} 页待校对：{failure}")));
                body.Append(new Paragraph(retained.ToString(SaveOptions.DisableFormatting)));
                previousIds=[];previousSummary="上一页未可靠识别，不能猜测跨页归属。";
            } else {
                if(validation.NeedsReview)reviewed++;
                var part=main.AddImagePart(ImagePartType.Png);using(var stream=File.OpenRead(sourcePng)){bytes+=stream.Length;part.FeedData(stream);}
                var currentIds=new List<string>();var descriptions=new List<string>();
                for(var i=0;i<validation.Page.Regions.Length;i++) {
                    var originalRegion=validation.Page.Regions[i];var region=originalRegion with{LectureTitle=AiLectureTitle(originalRegion.LectureSequence,originalRegion.LectureTitle)};
                    var raw=region.Bounds;double[] b=[raw[0],window.Top+raw[1]*(window.Bottom-window.Top),raw[2],window.Top+raw[3]*(window.Bottom-window.Top)];
                    var lectureIssue=region.Kind=="lecture" && (region.Confidence<AiHandoutLayout.ReviewConfidence || !region.LectureSequence.HasValue || string.IsNullOrWhiteSpace(region.LectureTitle));
                    lectureIssue |= region.Kind=="lecture" && region.LectureSequence==lectureSequence && lectureTitle!=null && BookStructure.TitleKey(region.LectureTitle??"")!=BookStructure.TitleKey(lectureTitle);
                    lectureIssue |= region.Kind is "question" or "continuation" && lectureSequence.HasValue && region.LectureSequence.HasValue && region.LectureSequence!=lectureSequence;
                    if(region.Kind=="lecture" && !lectureIssue && region.LectureSequence.HasValue && !string.IsNullOrWhiteSpace(region.LectureTitle)) {
                        // A repeated heading, including timed practice from the same lecture, is context only.
                        if(lectureSequence!=region.LectureSequence || referenceSection) {lectureSequence=region.LectureSequence;lectureTitle=region.LectureTitle;referenceSection=false;
                            body.Append(new Paragraph(new ParagraphProperties(new ParagraphStyleId{Val="SiyuePdfHeading"}),new Run(new Text($"第{lectureSequence}讲 {lectureTitle}"))));}
                    }
                    var id=AiHandoutLayout.QuestionId(number,i);
                    var checks=new List<string>{"ai-verified","ai-id:"+id};
                    if(lectureIssue)checks.AddRange(["ai-review","ai-lecture-review"]);
                    if(validation.Issues.Any(x=>x.RegionIndex==i || x.RegionIndex==null && region.Kind!="margin"))checks.Add("ai-review");
                    if(region.ContinuationOf!=null)checks.Add("ai-continuation:"+region.ContinuationOf);
                    if(region.Kind=="question")currentIds.Add(id);
                    if(region.Kind=="continuation")currentIds.Add(region.ContinuationOf!);
                    var drawing=PdfDrawing(main.GetIdOfPart(part),++imageId,4_800_000,(long)(4_800_000*page.Height/page.Width),region.Kind=="question");
                    TagPdf(drawing,number,"ai-layout",[0,0,1,1],checks.ToArray());
                    var xml=XElement.Parse(new Paragraph(new Run(drawing)).OuterXml);
                    xml=XElement.Parse(PdfRegions.Crop(Block(xml),new(b[0],b[1],b[2],b[3],region.Label??"")).Xml);
                    var props=xml.Descendants(WP+"docPr").Single();props.SetAttributeValue("title","siyue-pdf:"+Json.Write(new PdfProvenance(number,"ai-layout",b,checks.ToArray(),region.Label)));
                    props.SetAttributeValue("descr",region.Kind=="question"?"question":region.Kind=="continuation"?"continuation":region.Kind);
                    var text=string.Join("\n",pageLines.Where(l=>l.Top/page.Height>=b[1]-.003&&l.Bottom/page.Height<=b[3]+.003&&l.Left/page.Width>=b[0]-.003&&l.Right/page.Width<=b[2]+.003).Select(l=>l.Text));
                    // Non-question source text is retained visibly as pixels, not reinterpreted as headings.
                    if(region.Kind is "question" or "continuation")xml.AddFirst(new XElement(W+"r",new XElement(W+"rPr",new XElement(W+"vanish")),new XElement(W+"t",text)));
                    else {var style=checks.Contains("ai-review")?PdfReviewStyle:region.Kind is "cover" or "contents" or "blank"?"SiyueAiFrontMatter":region.Kind=="margin"?"SiyuePdfMargin":"SiyuePdfContext";
                        xml.AddFirst(new XElement(W+"pPr",new XElement(W+"pStyle",new XAttribute(W+"val",style))));
                        if(region.Kind=="answer" && !referenceSection){body.Append(new Paragraph(new Run(new Text("参考答案"))));referenceSection=true;}}
                    body.Append(new Paragraph(xml.ToString(SaveOptions.DisableFormatting)));
                    descriptions.Add($"{region.Kind} {region.Label} id={id}");
                }
                previousIds=currentIds.Distinct().TakeLast(8).ToArray();
                previousSummary=$"当前第{lectureSequence}讲 {lectureTitle}\n"+string.Join("\n",descriptions);
            }
            Guard.That(bytes<=180_000_000 && imageId<=4500,"讲义图片过多，请按册上传",413);
            if(config["Documents:PdfDiagnosticsDirectory"] is string diagnostic) {Directory.CreateDirectory(diagnostic);
                await File.WriteAllTextAsync(Path.Combine(diagnostic,$"ai-page-{number}.json"),Json.Write(new{page=number,validation,failure}),ct);}
            File.Delete(png);
            if(sourcePng!=png)File.Delete(sourcePng);
            await HandoutProgress("recognizing",pageIndex+1,total,$"已识别本讲 {pageIndex+1}/{total} 页，{reviewed} 页待核对。",ct);
        }
        main.Document.Save();
        await HandoutProgress("splitting",total,total,$"原稿识别完成，正在归并讲次；{reviewed} 页待核对。",ct);
        return output;
    }

    // Older split books contain references, not the lecture's actual Word content. Retain each
    // exact original-page window for manual regions instead of parsing placeholder text.
    async Task<string> NormalizeFixedReferenceHandout(string input,AiSourcePageReference[] references,string dir,CancellationToken ct) {
        Guard.That(SourcePdfInput!=null,"讲次缺少原讲义PDF，请重新拆讲",422);
        using var pdf=PdfDocument.Open(SourcePdfInput!);
        Guard.That(pdf.NumberOfPages is >0 and <=500,"讲义最多500页，请按册上传",413);
        Guard.That(references.All(r=>r.Page>=1&&r.Page<=pdf.NumberOfPages&&double.IsFinite(r.Top)&&double.IsFinite(r.Bottom)&&r.Top>=0&&r.Bottom<=1&&r.Top<r.Bottom),"讲次页码范围无效",422);
        NormalizedSourcePdf=SourcePdfInput;
        var output=Path.Combine(dir,"fixed-reference-normalized.docx");
        using var pack=WordprocessingDocument.Create(output,WordprocessingDocumentType.Document);
        var main=pack.AddMainDocumentPart();var body=new Body();main.Document=new Document(body);
        var referenceIndex=0;uint imageId=0;long bytes=0;
        foreach(var block in BookStructure.Read(input)) {
            ct.ThrowIfCancellationRequested();
            if(!block.Attributes().Any(a=>a.Name.Namespace==AiBookOutline.SourceNamespace)) {
                // Keep only validated lecture headings; reference placeholder prose is never a question.
                if(BookStructure.IsHeading(Block(block).Text))
                    body.Append(new Paragraph(new ParagraphProperties(new ParagraphStyleId{Val="SiyuePdfHeading"}),new Run(new Text(Block(block).Text))));
                continue;
            }
            var window=references[referenceIndex++];var page=pdf.GetPage(window.Page);
            Guard.That(page.Width is >0 and <=1440&&page.Height is >0 and <=1440,"讲义页面尺寸过大，请压缩大尺寸扫描页后上传",413);
            await HandoutProgress("fixed",referenceIndex-1,references.Length,$"正在保留第 {window.Page} 页原稿范围，供框选校对。",ct);
            var sourcePng=await RenderAuditPage(SourcePdfInput!,window.Page,dir,ct);
            var part=main.AddImagePart(ImagePartType.Png);
            using(var stream=File.OpenRead(sourcePng)){bytes+=stream.Length;Guard.That(bytes<=180_000_000&&++imageId<=4500,"讲义图片过多，请按册上传",413);part.FeedData(stream);}
            var drawing=PdfDrawing(main.GetIdOfPart(part),imageId,4_800_000,(long)(4_800_000*page.Height/page.Width),false);
            TagPdf(drawing,window.Page,"fixed-reference",[0,0,1,1],"manual-regions");
            var retained=XElement.Parse(new Paragraph(new Run(drawing)).OuterXml);
            if(window.Top>0||window.Bottom<1)
                retained=XElement.Parse(PdfRegions.Crop(Block(retained),new(0,window.Top,1,window.Bottom,"待框选校对")).Xml);
            // Crop changes provenance to manual; this region still requires explicit staff review.
            var props=retained.Descendants(WP+"docPr").Single();
            props.SetAttributeValue("title","siyue-pdf:"+Json.Write(new PdfProvenance(window.Page,"fixed-reference",[0,window.Top,1,window.Bottom],["manual-regions"])));
            props.SetAttributeValue("descr","continuation");
            foreach(var run in retained.Elements(W+"r").Where(r=>!r.Descendants(W+"drawing").Any()).ToArray())run.Remove();
            retained.AddFirst(new XElement(W+"pPr",new XElement(W+"pStyle",new XAttribute(W+"val",PdfReviewStyle))),
                new XElement(W+"r",new XElement(W+"t",$"第 {window.Page} 页待校对：已按原稿讲次范围保留，请使用框选拆题确认题目。")));
            body.Append(new Paragraph(retained.ToString(SaveOptions.DisableFormatting)));
        }
        main.Document.Save();
        await HandoutProgress("fixed",references.Length,references.Length,"原稿讲次范围已完整保留，请框选题目后校对。",ct);
        return output;
    }

    async Task<(string[] Keys,bool Half)?> TryAiPreview(Question q,Guid lectureId,string dir,CancellationToken ct) {
        var blocks=Json.Read<ContentBlock[]>(q.BlocksJson);
        if(blocks.Length==0 || blocks.Any(b=>PdfOrigin(XElement.Parse(b.Xml))?.Engine is not ("ai-layout" or "fixed-reference") || ImageReferences(b.Xml).Length!=1))return null;
        var items=new List<object>();
        foreach(var block in blocks) {
            var xml=XElement.Parse(block.Xml);var rid=ImageReferences(block.Xml).Single();
            var asset=await db.Assets.SingleAsync(a=>a.HandoutId==q.HandoutId&&a.RelationshipId==rid,ct);
            var path=Path.Combine(dir,"ai-preview-asset-"+asset.Id+".png");
            if(!File.Exists(path))await Materialize(asset.ObjectKey,dir,Path.GetFileName(path),ct);
            items.Add(new{path,bounds=PdfRegions.Bounds(xml)});
        }
        var plan=Path.Combine(dir,$"ai-preview-{q.Id}.json");var output=Path.Combine(dir,$"ai-preview-{q.Id}-result.json");
        await File.WriteAllTextAsync(plan,Json.Write(new{items,prefix=Path.Combine(dir,$"ai-preview-{q.Id}")}),ct);
        await Run(config["Documents:Python"]??"python3",[AiImageScript,"preview","-",plan,output],ct);
        var result=Json.Read<AiPreviewResult>(await File.ReadAllTextAsync(output,ct));var keys=new List<string>();
        foreach(var path in result.Paths) {var key=$"questions/{q.Id}/{q.Revision}-{Guid.NewGuid():N}.png";
            await using var stream=File.OpenRead(path);await objects.Put(key,stream,"image/png",ct);keys.Add(key);}
        return(keys.ToArray(),result.Half);
    }
}
