using System.Xml.Linq;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig.Writer;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using Xunit;

public class PdfStructuredLayoutTests
{
    [Fact] public async Task DecorativeMarginsDoNotBreakRealContinuation() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var path=Path.Combine(f.Root,"margin-continuation.docx");Directory.CreateDirectory(f.Root);
        using(var doc=DocumentFormat.OpenXml.Packaging.WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)) {
            var main=doc.AddMainDocumentPart();var w=WordDocuments.W;
            var body=new XElement(w+"body",new XElement(w+"p",new XElement(w+"r",new XElement(w+"t","1. First condition."))),
                new XElement(w+"p",new XElement(w+"pPr",new XElement(w+"pStyle",new XAttribute(w+"val","SiyuePdfMargin"))),new XElement(w+"r",new XElement(w+"t","2026 教研室"))),
                new XElement(w+"p",new XElement(w+"r",new XElement(w+"t","Remaining condition: AB=5."))));
            main.Document=new DocumentFormat.OpenXml.Wordprocessing.Document(new DocumentFormat.OpenXml.Wordprocessing.Body(body.ToString()));main.Document.Save();
        }
        var q=Assert.Single(await word.Parse(path,Guid.NewGuid(),default));var text=string.Join("",Json.Read<ContentBlock[]>(q.BlocksJson).Select(b=>b.Text));
        Assert.Contains("AB=5",text);Assert.DoesNotContain("教研室",text);
    }
    [Fact] public async Task UnknownPagePrefixCannotSilentlyJoinPreviousQuestion() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var b=new PdfDocumentBuilder();var font=b.AddStandard14Font(Standard14Font.Helvetica);
        b.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText("1. Previous question.",12,new PdfPoint(50,300),font);
        var p=b.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        p.AddText("Unidentified material must be reviewed separately.",12,new PdfPoint(50,750),font);
        p.AddText("2. A reliably numbered question.",12,new PdfPoint(330,400),font);
        var path=Path.Combine(f.Root,"unknown-prefix.pdf");File.WriteAllBytes(path,b.Build());
        var normalized=await word.NormalizePdfHandout(path,f.Root,default);var q=await word.Parse(normalized,Guid.NewGuid(),default);
        Assert.DoesNotContain("Unidentified",string.Concat(Json.Read<ContentBlock[]>(q[0].BlocksJson).Select(x=>x.Text)));
        Assert.Contains(q,x=>Json.Read<Issue[]>(x.IssuesJson).Any(i=>i.Code=="pdf-orphan-prefix"&&i.Blocking));
        Assert.Contains(q,x=>x.Label=="2.");
    }
    [Fact] public async Task StaggeredColumnsKeepColumnOrderAndDiagramOwnership() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var b=new PdfDocumentBuilder();var font=b.AddStandard14Font(Standard14Font.Helvetica);var p=b.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        p.AddText("1. Left first question.",12,new PdfPoint(45,750),font);
        p.DrawRectangle(new PdfPoint(45,570),180,140);
        p.AddText("2. Left second question.",12,new PdfPoint(45,420),font);
        p.AddText("3. Right first question.",12,new PdfPoint(330,700),font);
        p.AddText("4. Right second question.",12,new PdfPoint(330,350),font);
        var path=Path.Combine(f.Root,"staggered.pdf");File.WriteAllBytes(path,b.Build());
        var normalized=await word.NormalizePdfHandout(path,f.Root,default);var questions=await word.Parse(normalized,Guid.NewGuid(),default);
        Assert.Equal(new[]{"1.","2.","3.","4."},questions.Select(q=>q.Label));
        var origins=questions.Select(q=>WordDocuments.PdfOrigin(XElement.Parse(Json.Read<ContentBlock[]>(q.BlocksJson).Single().Xml))!).ToArray();
        Assert.All(origins.Take(2),o=>Assert.True(o.Bounds[2]<.56));
        Assert.All(origins.Skip(2),o=>Assert.True(o.Bounds[0]>.5));
        Assert.True(origins[0].Bounds[3]>(842-570)/842d);
        Assert.DoesNotContain("Right",Json.Read<ContentBlock[]>(questions[0].BlocksJson).Single().Text);
    }
    [Fact] public async Task ParenthesizedIndependentNumbersAreProposedButSubpartsStayTogether() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var b=new PdfDocumentBuilder();var font=b.AddStandard14Font(Standard14Font.Helvetica);var p=b.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        p.AddText("(1) Calculate the first expression.",12,new PdfPoint(50,750),font);
        p.AddText("(2) Calculate the second expression.",12,new PdfPoint(50,500),font);
        var path=Path.Combine(f.Root,"nonstandard.pdf");File.WriteAllBytes(path,b.Build());
        var normalized=await word.NormalizePdfHandout(path,f.Root,default);var q=await word.Parse(normalized,Guid.NewGuid(),default);
        Assert.Equal(new[]{"(1)","(2)"},q.Select(x=>x.Label));
        Assert.All(q,x=>Assert.Contains(Json.Read<Issue[]>(x.IssuesJson),i=>i.Code=="pdf-contextual-label"&&i.Blocking));
    }
    [Fact] public async Task ExplicitContinuationAndFollowingQuestionKeepTheirOwnContent() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var b=new PdfDocumentBuilder();var font=b.AddStandard14Font(Standard14Font.Helvetica);
        var p=b.AddPage(UglyToad.PdfPig.Content.PageSize.A4);p.AddText("1. Prove the result using these conditions.",12,new PdfPoint(50,300),font);
        p=b.AddPage(UglyToad.PdfPig.Content.PageSize.A4);p.AddText("1. (continued) The remaining condition is AB=5.",12,new PdfPoint(50,750),font);
        p.DrawRectangle(new PdfPoint(50,560),160,140);
        p.AddText("2. An independent question.",12,new PdfPoint(50,420),font);
        var path=Path.Combine(f.Root,"continuation.pdf");File.WriteAllBytes(path,b.Build());
        for(var attempt=0;attempt<2;attempt++) {
            var normalized=await word.NormalizePdfHandout(path,f.Root,default);var q=await word.Parse(normalized,Guid.NewGuid(),default);
            Assert.Equal(new[]{"1.","2."},q.Select(x=>x.Label));
            Assert.Contains("AB=5",string.Concat(Json.Read<ContentBlock[]>(q[0].BlocksJson).Select(x=>x.Text)));
            Assert.DoesNotContain("independent",string.Concat(Json.Read<ContentBlock[]>(q[0].BlocksJson).Select(x=>x.Text)));
            Assert.Contains(Json.Read<Issue[]>(q[0].IssuesJson),i=>i.Code=="pdf-cross-page");
            Assert.Equal(new[]{1,2},Json.Read<ContentBlock[]>(q[0].BlocksJson).Select(x=>WordDocuments.PdfOrigin(XElement.Parse(x.Xml))!.Page).Distinct());
        }
    }
    [Fact] public async Task ContinuationSubpartsAreNotPromotedToNewQuestions() {
        await using var f=new Factory();using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var b=new PdfDocumentBuilder();var font=b.AddStandard14Font(Standard14Font.Helvetica);
        b.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText("1. Solve all parts below.",12,new PdfPoint(50,300),font);
        var p=b.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        p.AddText("(1) Calculate this result.",12,new PdfPoint(50,750),font);
        p.AddText("(2) Explain your method.",12,new PdfPoint(50,500),font);
        var path=Path.Combine(f.Root,"subparts.pdf");File.WriteAllBytes(path,b.Build());
        var normalized=await word.NormalizePdfHandout(path,f.Root,default);var q=Assert.Single(await word.Parse(normalized,Guid.NewGuid(),default));
        Assert.Equal("1.",q.Label);Assert.Contains("(2)",string.Concat(Json.Read<ContentBlock[]>(q.BlocksJson).Select(x=>x.Text)));
    }
}
