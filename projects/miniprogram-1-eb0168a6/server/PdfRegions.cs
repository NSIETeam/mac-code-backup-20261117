using System.Xml.Linq;
using Microsoft.EntityFrameworkCore;
namespace Siyue;
public record PdfRegion(double Left,double Top,double Right,double Bottom,string Label);
public record PdfRegionEdit(int Revision,Guid QuestionId,int BlockIndex,PdfRegion[] Regions,bool ConfirmedCoverage=false,string? UnassignedReason=null,string Mode="split");
public static class PdfRegions
{
    static readonly XNamespace A="http://schemas.openxmlformats.org/drawingml/2006/main";
    static readonly XNamespace WP="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static readonly XNamespace W=WordDocuments.W;
    public static double[] Bounds(XElement xml) {
        var crop=xml.Descendants(A+"srcRect").SingleOrDefault();
        double Get(string name)=>(double?)crop?.Attribute(name)/100000??0;
        return [Get("l"),Get("t"),1-Get("r"),1-Get("b")];
    }
    public static void Validate(PdfRegionEdit r,double[] bounds) {
        Guard.That(r.Mode is "split" or "trim","框选操作无效");
        if(r.Mode=="trim")Guard.That(r.ConfirmedCoverage,"请先对照原稿确认题目和图片完整");
        Guard.That(r.Regions is {Length:>0 and <=60},"请框选 1–60 道题");
        Guard.That(r.Mode!="trim" || r.Regions.Length==1,"排除页眉页脚时请框选一个完整正文区域，多道题请使用框选拆题");
        foreach(var x in r.Regions) {
            Guard.Name(x.Label);
            Guard.That(new[]{x.Left,x.Top,x.Right,x.Bottom}.All(double.IsFinite) && x.Left>=bounds[0]-1e-7 && x.Top>=bounds[1]-1e-7 && x.Right<=bounds[2]+1e-7 && x.Bottom<=bounds[3]+1e-7 && x.Right-x.Left>=.002 && x.Bottom-x.Top>=.002,"选框必须位于当前内容范围内，且不能过小");
        }
        for(var i=0;i<r.Regions.Length;i++)for(var j=0;j<i;j++) {
            var a=r.Regions[i];var b=r.Regions[j];
            Guard.That(Math.Min(a.Right,b.Right)-Math.Max(a.Left,b.Left)<=1e-7 || Math.Min(a.Bottom,b.Bottom)-Math.Max(a.Top,b.Top)<=1e-7,"题目选框不能重叠，请调整边界");
        }
        // Manual split regions are authoritative; margins and gaps need not be assigned.
        if(r.Mode=="trim") {
        var total=(bounds[2]-bounds[0])*(bounds[3]-bounds[1]);var covered=r.Regions.Sum(x=>(x.Right-x.Left)*(x.Bottom-x.Top));
        Guard.That(total-covered<=1e-6 || (r.UnassignedReason?.Trim().Length is >=4 and <=300),"有未归属区域，请补全选框，或填写排除原因（仅限页眉、页脚或空白等非题目内容）");
        }
    }
    public static ContentBlock Crop(ContentBlock block,PdfRegion region) {
        var xml=XElement.Parse(block.Xml);var original=Bounds(xml);
        Guard.That(WordDocuments.IsPdfRaster(xml) && WordDocuments.ImageReferences(block.Xml).Length==1,"当前内容不支持框选");
        var blip=xml.Descendants(A+"blip").Single();var parent=blip.Parent!;
        parent.Elements(A+"srcRect").Remove();blip.AddAfterSelf(new XElement(A+"srcRect",new XAttribute("l",(int)Math.Round(region.Left*100000)),new XAttribute("t",(int)Math.Round(region.Top*100000)),new XAttribute("r",(int)Math.Round((1-region.Right)*100000)),new XAttribute("b",(int)Math.Round((1-region.Bottom)*100000))));
        var extent=xml.Descendants(WP+"extent").Single();
        var cx=(long)Math.Round((long)extent.Attribute("cx")!*(region.Right-region.Left)/(original[2]-original[0]));
        var cy=(long)Math.Round((long)extent.Attribute("cy")!*(region.Bottom-region.Top)/(original[3]-original[1]));
        foreach(var size in xml.Descendants().Where(x=>x.Name==WP+"extent"||x.Name==A+"ext")){size.SetAttributeValue("cx",cx);size.SetAttributeValue("cy",cy);}
        xml.Element(W+"pPr")?.Elements(W+"pStyle").Remove();
        foreach(var run in xml.Elements(W+"r").Where(x=>!x.Descendants(W+"drawing").Any()).ToArray())run.Remove();
        var hidden=new XElement(W+"r",new XElement(W+"rPr",new XElement(W+"vanish")),new XElement(W+"t",region.Label+" "));
        var pp=xml.Element(W+"pPr");if(pp!=null)pp.AddAfterSelf(hidden);else xml.AddFirst(hidden);
        var props=xml.Descendants(WP+"docPr").Single();props.SetAttributeValue("descr","question");
        var origin=WordDocuments.PdfOrigin(xml);if(origin!=null)props.SetAttributeValue("title","siyue-pdf:"+Json.Write(origin with{Engine="manual",Checks=[],Label=region.Label}));
        return WordDocuments.Block(xml);
    }
    public static void MapPdfRegions(this WebApplication app) {
        app.MapGet("/api/handouts/{id:guid}/regions/{questionId:guid}/{blockIndex:int}",async(Guid id,Guid questionId,int blockIndex,Data db,Access a,Files files)=>{
            var h=await a.Handout(id);await a.WriteLecture(h.LectureId);Guard.That(h.State=="draft","已发布讲义不能修改",409);
            var q=await db.Questions.SingleOrDefaultAsync(x=>x.Id==questionId&&x.HandoutId==id)??throw new ApiException(404,"题目不存在");
            var blocks=Json.Read<ContentBlock[]>(q.BlocksJson);Guard.That(blockIndex>=0&&blockIndex<blocks.Length,"内容位置无效");
            var xml=XElement.Parse(blocks[blockIndex].Xml);var refs=WordDocuments.ImageReferences(blocks[blockIndex].Xml);
            Guard.That(WordDocuments.IsPdfRaster(xml)&&refs.Length==1,"当前内容不支持框选");
            var asset=await db.Assets.SingleAsync(x=>x.HandoutId==id&&x.RelationshipId==refs[0]);var lecture=await a.Lecture(h.LectureId);
            return new {revision=h.Revision,url=await files.Grant(asset.ObjectKey,asset.ContentType,"原题图片.png",a.User.Id,classId:lecture.ClassId,courseId:lecture.CourseId),bounds=Bounds(xml),origin=WordDocuments.PdfOrigin(xml)};
        });
        app.MapPost("/api/handouts/{id:guid}/regions",async(Guid id,PdfRegionEdit r,Data db,Access a,IObjects objects,CancellationToken ct)=>{
            var h=await a.Handout(id);await a.WriteLecture(h.LectureId);Guard.That(h.State=="draft","已发布讲义不能修改",409);
            await using var tx=await db.Database.BeginTransactionAsync();
            Guard.That(await db.Handouts.Where(x=>x.Id==id&&x.State=="draft"&&x.Revision==r.Revision).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.Revision,r.Revision+1))==1,"讲义已被修改，请刷新",409);
            var questions=await db.Questions.Where(x=>x.HandoutId==id).OrderBy(x=>x.Order).ToListAsync();
            var q=questions.SingleOrDefault(x=>x.Id==r.QuestionId)??throw new ApiException(404,"题目不存在");
            var blocks=Json.Read<ContentBlock[]>(q.BlocksJson);Guard.That(r.BlockIndex>=0&&r.BlockIndex<blocks.Length,"内容位置无效");
            var source=blocks[r.BlockIndex];Validate(r,Bounds(XElement.Parse(source.Xml)));
            var replacements=new List<Question>();
            if(r.Mode=="trim") {
                var unresolved=Json.Read<Issue[]>(q.IssuesJson).Where(i=>i.Code is "pdf-merged-region" or "pdf-multiple-labels" or "pdf-page-review").ToArray();
                blocks[r.BlockIndex]=Crop(source,r.Regions[0] with{Label=q.Label});
                q.BlocksJson=Json.Write(blocks);
                WordDocuments.RefreshPdfContentIssues(q);
                q.IssuesJson=Json.Write(Json.Read<Issue[]>(q.IssuesJson).Concat(unresolved).Distinct());
                replacements.Add(q);
            } else {
            if(r.BlockIndex>0)replacements.Add(new Question{HandoutId=id,Label=q.Label,Family=q.Family,BlocksJson=Json.Write(blocks.Take(r.BlockIndex)),IssuesJson=q.IssuesJson});
            foreach(var region in r.Regions)replacements.Add(new Question{HandoutId=id,Label=Guard.Name(region.Label),Family=q.Family,BlocksJson=Json.Write(new[]{Crop(source,region)}),IssuesJson=Json.Write(new[]{new Issue("pdf-manual","已人工框选，请查看生成预览后确认。",false)})});
            if(r.BlockIndex+1<blocks.Length) {
                var last=replacements[^1];last.BlocksJson=Json.Write(Json.Read<ContentBlock[]>(last.BlocksJson).Concat(blocks.Skip(r.BlockIndex+1)));
                // Keep unresolved issues for the other source blocks; never clear unrelated pages.
                last.IssuesJson=q.IssuesJson;
            }
            var at=questions.IndexOf(q);var first=replacements[0];q.Label=first.Label;q.Family=first.Family;q.BlocksJson=first.BlocksJson;q.IssuesJson=first.IssuesJson;
            replacements[0]=q;questions.RemoveAt(at);questions.InsertRange(at,replacements);
            }
            foreach(var item in replacements){if(item.Id!=q.Id)db.Questions.Add(item);item.Confirmed=false;item.Revision++;item.PreviewsJson="[]";}
            for(var i=0;i<questions.Count;i++)questions[i].Order=i+1;
            await db.SaveChangesAsync();foreach(var item in replacements)await WordDocuments.SyncAssets(db,item);
            db.Work.Add(new WorkItem{Kind="previews",EntityId=id});
            await using var receipt=new MemoryStream(System.Text.Encoding.UTF8.GetBytes(Json.Write(new{staffId=a.User.Id,request=r,source=source.Xml})));
            await objects.Put($"handouts/{id}/region-edits/{r.Revision+1}.json",receipt,"application/json",ct);
            db.Audits.Add(new Audit{StaffId=a.User.Id,Action="handout.regions",TargetId=id});
            await db.SaveChangesAsync();await tx.CommitAsync();return new{revision=r.Revision+1,questions=replacements.Count};
        });
    }
}
