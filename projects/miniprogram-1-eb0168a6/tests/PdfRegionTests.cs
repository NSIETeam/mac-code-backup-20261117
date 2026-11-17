using System.Net;
using System.Net.Http.Json;
using System.Xml.Linq;
using System.Text.Json;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig.Writer;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using Xunit;

public class PdfRegionTests
{
    [Theory]
    [InlineData("初三上册自招-秋季第4讲.pdf",4)]
    [InlineData("数学 第十二讲 练习.pdf",12)]
    public void SingleLectureNumberCanAppearAtEndOfFilename(string filename,int expected) {
        var block=new XElement(WordDocuments.W+"p",new XElement(WordDocuments.W+"r",new XElement(WordDocuments.W+"t","例1 计算。")));
        Assert.Equal(expected,Assert.Single(BookStructure.DetectFile([block],filename,1)).Sequence);
        Assert.DoesNotContain(BookStructure.DetectFile([block],"第一讲与第八讲合订.pdf",1),x=>x.Kind=="lesson");
    }
    [Fact] public void ManualSplitsAllowGapsWithoutCoverageConfirmationAndRejectInvalidBoxes() {
        var q=Guid.NewGuid();var all=new double[]{0,0,1,1};
        PdfRegionEdit Input(params PdfRegion[] r)=>new(0,q,0,r,true);
        PdfRegions.Validate(Input(new PdfRegion(0,0,1,.5,"例1"),new(0,.5,1,1,"例2")),all);
        PdfRegions.Validate(Input(new PdfRegion(.1,.1,.8,.4,"例1")) with{ConfirmedCoverage=false},all);
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(0,0,1,.7,"例1"),new(0,.5,1,1,"例2")),all));
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(double.NaN,0,1,1,"例1")),all));
        PdfRegions.Validate(Input(new PdfRegion(0,0,1,1,"例1")) with{ConfirmedCoverage=false},all);
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(0,0,1,1,"")),all));
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(-.1,0,1,1,"例1")),all));
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(0,0,1,.5,"例1")) with{Mode="trim"},all));
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(0,0,1,1,"例1")) with{Mode="trim",ConfirmedCoverage=false},all));
        PdfRegions.Validate(Input(new PdfRegion(0,0,1,.5,"例1")) with{UnassignedReason="下半页只有空白"},all);
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(0,0,1,.5,"例1"),new(0,.5,1,1,"例2")) with{Mode="trim"},all));
        Assert.Throws<ApiException>(()=>PdfRegions.Validate(Input(new PdfRegion(0,0,1,1,"例1")) with{Mode="delete"},all));
    }
    [Fact] public async Task PdfInterferenceTrimPreservesCrossPageQuestionAndRejectsWholeBlockRemoval() {
        await using var f=new Factory();using var admin=await f.Login("trim-admin",true);using var scope=f.Services.CreateScope();
        var db=scope.ServiceProvider.GetRequiredService<Data>();
        var campus=new Campus{Name="裁边验收"};var cl=new SchoolClass{Name="裁边班",CampusId=campus.Id};var course=new Course{Name="数学"};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id,Sequence=1,Title="裁边"};
        var handout=new Handout{LectureId=lecture.Id,State="draft"};
        XNamespace wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",drawing="http://schemas.openxmlformats.org/drawingml/2006/main";
        ContentBlock Raster(int page) {
            var origin=Json.Write(new PdfProvenance(page,"test",[0,0,1,1],["source-review"]));
            var inline=new XElement(wp+"inline",
                new XElement(wp+"extent",new XAttribute("cx",1000000),new XAttribute("cy",2000000)),
                new XElement(wp+"docPr",new XAttribute("id",page),new XAttribute("name","SiyuePDF"+page),new XAttribute("title","siyue-pdf:"+origin)),
                new XElement(drawing+"blip",new XAttribute(WordDocuments.R+"embed","image"+page)));
            return WordDocuments.Block(new XElement(WordDocuments.W+"p",
                new XElement(WordDocuments.W+"r",new XElement(WordDocuments.W+"t","例1 正文与页脚")),
                new XElement(WordDocuments.W+"r",new XElement(WordDocuments.W+"drawing",inline))));
        }
        var blocks=new[]{Raster(1),Raster(2)};var question=new Question{HandoutId=handout.Id,Label="例1",Family="例",Order=1,Confirmed=true,BlocksJson=Json.Write(blocks),PreviewsJson="[\"old-preview\"]",IssuesJson=Json.Write(new[]{new Issue("pdf-cross-page","跨页",true),new Issue("pdf-source-review","后备识别",true)})};
        var other=new Question{HandoutId=handout.Id,Label="例2",Order=2,BlocksJson=Json.Write(new[]{Raster(3)})};
        db.AddRange(campus,cl,course,lecture,handout,question,other);await db.SaveChangesAsync();
        var path=$"/api/handouts/{handout.Id}";
        foreach(var index in new[]{0,1})Assert.Equal(HttpStatusCode.BadRequest,(await admin.PostAsJsonAsync(path+"/edit",new{revision=0,questionId=question.Id,action="remove-block",blockIndex=index})).StatusCode);
        db.ChangeTracker.Clear();Assert.Equal(0,(await db.Handouts.FindAsync(handout.Id))!.Revision);Assert.Equal(Json.Write(blocks),(await db.Questions.FindAsync(question.Id))!.BlocksJson);
        var input=new PdfRegionEdit(0,question.Id,1,[new(0,.1,1,.9,"ignored-label")],true,"排除页眉页脚与空白","trim");
        await Factory.Post(admin,path+"/regions",input);
        Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync(path+"/regions",input)).StatusCode);
        db.ChangeTracker.Clear();var saved=(await db.Questions.FindAsync(question.Id))!;var kept=Json.Read<ContentBlock[]>(saved.BlocksJson);
        Assert.Equal(2,await db.Questions.CountAsync(q=>q.HandoutId==handout.Id));Assert.Equal("例1",saved.Label);Assert.Equal(2,kept.Length);Assert.Equal(blocks[0],kept[0]);Assert.Equal(other.BlocksJson,(await db.Questions.FindAsync(other.Id))!.BlocksJson);
        Assert.Equal(new double[]{0,.1,1,.9},PdfRegions.Bounds(XElement.Parse(kept[1].Xml)));Assert.Equal("image2",Assert.Single(WordDocuments.ImageReferences(kept[1].Xml)));Assert.Empty(Json.Read<string[]>(saved.PreviewsJson));Assert.False(saved.Confirmed);
        Assert.Contains(Json.Read<Issue[]>(saved.IssuesJson),i=>i.Code=="pdf-cross-page");Assert.Contains(Json.Read<Issue[]>(saved.IssuesJson),i=>i.Code=="pdf-source-review");
        await Factory.Post(admin,path+"/regions",input with{Revision=1,Regions=[new(0,.2,1,.8,"例1")]});
        db.ChangeTracker.Clear();kept=Json.Read<ContentBlock[]>((await db.Questions.FindAsync(question.Id))!.BlocksJson);Assert.Equal(new double[]{0,.2,1,.8},PdfRegions.Bounds(XElement.Parse(kept[1].Xml)));Assert.Equal(blocks[0],kept[0]);
    }
    [Fact] public async Task ManualRegionsKeepSourceImagePreserveQuestionIdentityAndRejectStaleOrForeignEdits() {
        await using var f=new Factory();using var admin=await f.Login("frame-admin",true);using var scope=f.Services.CreateScope();
        var db=scope.ServiceProvider.GetRequiredService<Data>();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var campus=new Campus{Name="框选验收"};var cl=new SchoolClass{Name="框选班",CampusId=campus.Id};var course=new Course{Name="数学"};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id,Sequence=1,Title="框选"};db.AddRange(campus,cl,course,lecture,new Staff{Name="其他教师",OpenId="development:frame-outsider",Role="teacher",Enabled=true});await db.SaveChangesAsync();using var outsider=await f.Login("frame-outsider");
        var pdf=new PdfDocumentBuilder();var page=pdf.AddPage(UglyToad.PdfPig.Content.PageSize.A4);var font=pdf.AddStandard14Font(Standard14Font.Helvetica);page.AddText("1. Two parts.",12,new PdfPoint(50,750),font);page.AddText("A diagram below.",12,new PdfPoint(50,550),font);page.DrawRectangle(new PdfPoint(50,470),180,50);
        var path=Path.Combine(f.Root,"regions.pdf");File.WriteAllBytes(path,pdf.Build());using var form=new MultipartFormDataContent();form.Add(new StringContent("legacy"),"recognitionMode");form.Add(new StreamContent(File.OpenRead(path)),"file","regions.pdf");var upload=await admin.PostAsync($"/api/lectures/{lecture.Id}/handouts",form);upload.EnsureSuccessStatusCode();var id=(await upload.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();await f.Run("import",id);
        var before=await db.Questions.SingleAsync(x=>x.HandoutId==id);var oldId=before.Id;var count=await db.Assets.CountAsync(x=>x.HandoutId==id);
        var source=await admin.GetFromJsonAsync<JsonElement>($"/api/handouts/{id}/regions/{before.Id}/0");Assert.NotEmpty(source.GetProperty("url").GetString()!);
        Assert.Equal(HttpStatusCode.Forbidden,(await outsider.GetAsync($"/api/handouts/{id}/regions/{before.Id}/0")).StatusCode);
        var input=new PdfRegionEdit(0,before.Id,0,[new(.05,.05,.95,.3,"例1"),new(.05,.35,.95,.6,"例2")]);
        await Factory.Post(admin,$"/api/handouts/{id}/regions",new{input.Revision,input.QuestionId,input.BlockIndex,input.Regions});
        Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"/api/handouts/{id}/regions",input)).StatusCode);
        db.ChangeTracker.Clear();var qs=await db.Questions.Where(x=>x.HandoutId==id).OrderBy(x=>x.Order).ToArrayAsync();Assert.Equal(2,qs.Length);Assert.Equal(oldId,qs[0].Id);Assert.Equal(count,await db.Assets.CountAsync(x=>x.HandoutId==id));
        Assert.All(qs,q=>Assert.False(q.Confirmed));Assert.Equal(new double[]{.05,.05,.95,.3},PdfRegions.Bounds(XElement.Parse(Json.Read<ContentBlock[]>(qs[0].BlocksJson)[0].Xml)).Select(x=>Math.Round(x,6)));
        await f.Run("previews",id);db.ChangeTracker.Clear();qs=await db.Questions.Where(x=>x.HandoutId==id).ToArrayAsync();Assert.All(qs,q=>Assert.NotEmpty(Json.Read<string[]>(q.PreviewsJson)));
        await Factory.Post(admin,$"/api/handouts/{id}/confirm-all",new PublishInput(1));await Factory.Post(admin,$"/api/handouts/{id}/publish",new PublishInput(2));
        Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"/api/handouts/{id}/regions",input with{Revision=3})).StatusCode);
    }
    [Fact] public async Task FullPageScanIsNeverMistakenForAnEmptyLetterhead() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var pdf=new PdfDocumentBuilder();var page=pdf.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        page.AddPng(Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAADElEQVR4nGNgIB0AAAA0AAF2Xq7DAAAAAElFTkSuQmCC"),new PdfRectangle(0,0,595,842));
        var path=Path.Combine(f.Root,"scan.pdf");File.WriteAllBytes(path,pdf.Build());
        var output=await word.NormalizePdfHandout(path,f.Root,default);var q=Assert.Single(await word.Parse(output,Guid.NewGuid(),default));
        Assert.Contains(Json.Read<Issue[]>(q.IssuesJson),i=>i.Code=="pdf-page-review"&&i.Blocking);
        Assert.NotEmpty(WordDocuments.ImageReferences(Json.Read<ContentBlock[]>(q.BlocksJson)[0].Xml));
    }
    [Fact] public async Task PdfGapRequiresIndividualReviewAndCachedPagesAreReusable() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var pdf=new PdfDocumentBuilder();var page=pdf.AddPage(UglyToad.PdfPig.Content.PageSize.A4);var font=pdf.AddStandard14Font(Standard14Font.Helvetica);page.AddText("1. Start.",12,new PdfPoint(50,750),font);page.AddText("3. Gap.",12,new PdfPoint(50,650),font);
        var path=Path.Combine(f.Root,"gap.pdf");File.WriteAllBytes(path,pdf.Build());
        var output=await word.NormalizePdfHandout(path,f.Root,default);var qs=await word.Parse(output,Guid.NewGuid(),default);
        Assert.Equal(2,qs.Count);Assert.Contains(Json.Read<Issue[]>(qs[1].IssuesJson),x=>x.Code=="pdf-number-gap"&&x.Blocking);
        Assert.NotEmpty(Directory.GetFiles(Path.Combine(f.Root,"files","pdf-checkpoints"),"*.pointer",SearchOption.AllDirectories));
        scope.ServiceProvider.GetRequiredService<Microsoft.Extensions.Configuration.IConfiguration>()["Documents:PdfToPpm"]="missing-renderer-to-prove-checkpoint-reuse";
        var second=await word.NormalizePdfHandout(path,f.Root,default);var again=await word.Parse(second,Guid.NewGuid(),default);Assert.Equal(qs.Select(x=>x.Label),again.Select(x=>x.Label));
    }
}
