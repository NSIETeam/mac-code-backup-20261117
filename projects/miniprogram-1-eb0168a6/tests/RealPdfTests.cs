using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig;
using Xunit;

public sealed class RealPdfFactAttribute : FactAttribute
{
    public RealPdfFactAttribute() { if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("SIYUE_REAL_PDF"))) Skip="Set SIYUE_REAL_PDF to an explicitly supplied acceptance source"; }
}
public class RealPdfTests
{
    [RealPdfFact]
    public async Task ProvidedTrainingPageKeepsThreeQuestionsSeparateFromWhiteBackground() {
        var source=Environment.GetEnvironmentVariable("SIYUE_REAL_PDF");
        if(Environment.GetEnvironmentVariable("SIYUE_TRAINING_PAGE") is not string pageValue)return;
        var page=int.Parse(pageValue);var output=Path.GetFullPath(Environment.GetEnvironmentVariable("SIYUE_REAL_PDF_OUTPUT") ?? ".local/acceptance/pdf-trim");Directory.CreateDirectory(output);
        using var pdf=PdfDocument.Open(source!);var builder=new UglyToad.PdfPig.Writer.PdfDocumentBuilder();builder.AddPage(pdf,page);
        await using var f=new Factory();Directory.CreateDirectory(f.Root);var input=Path.Combine(f.Root,"training-page.pdf");File.WriteAllBytes(input,builder.Build());
        using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var normalized=await word.NormalizePdfHandout(input,f.Root,default);var questions=await word.Parse(normalized,Guid.NewGuid(),default);
        var training=questions.Where(q=>q.Label.StartsWith("测")).ToArray();Assert.Equal(new[]{"测1","测2","测3"},training.Select(q=>q.Label));
        foreach(var q in training) {
            Assert.DoesNotContain(Json.Read<Issue[]>(q.IssuesJson),i=>i.Code=="pdf-merged-region");
            var blocks=Json.Read<ContentBlock[]>(q.BlocksJson);Assert.DoesNotContain("五年级",string.Join("",blocks.Select(b=>b.Text)));
            Assert.DoesNotContain("教研室",string.Join("",blocks.Select(b=>b.Text)));
            using var doc=DocumentFormat.OpenXml.Packaging.WordprocessingDocument.Open(normalized,false);
            foreach(var (block,index) in blocks.Select((b,i)=>(b,i))) {
                var relationship=Assert.Single(WordDocuments.ImageReferences(block.Xml));
                using var image=doc.MainDocumentPart!.GetPartById(relationship).GetStream();using var dest=File.Create(Path.Combine(output,q.Label+"-"+index+".png"));image.CopyTo(dest);
            }
        }
        File.WriteAllText(Path.Combine(output,"training-questions.json"),Json.Write(questions.Select(q=>new{q.Label,blocks=Json.Read<ContentBlock[]>(q.BlocksJson).Select(b=>b.Text),issues=Json.Read<Issue[]>(q.IssuesJson)})));
    }
    [RealPdfFact]
    public async Task ProvidedPdfIsNormalizedWithOriginalPictures()
    {
        var source = Environment.GetEnvironmentVariable("SIYUE_REAL_PDF");
        if (string.IsNullOrEmpty(source)) return;
        var evidence = Path.GetFullPath(Environment.GetEnvironmentVariable("SIYUE_REAL_PDF_OUTPUT") ?? ".local/acceptance/pdf-real");
        Directory.CreateDirectory(evidence);
        using (var pdf = PdfDocument.Open(source))
            File.WriteAllText(Path.Combine(evidence, "geometry.json"), Json.Write(pdf.GetPages().Select(p => new {
                p.Number, p.Width, p.Height, text=p.Text,
                images=p.GetImages().Select(i=>i.BoundingBox),
                letters=p.Letters.Select(l=>new {l.Value,l.StartBaseLine,l.EndBaseLine,l.BoundingBox,l.FontSize}),
                paths=p.Paths.Select(x=>x.GetBoundingRectangle())
            })));
        await using var f=new Factory();
        using var scope=f.Services.CreateScope();
        var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var result=await word.NormalizePdfHandout(source,evidence,default);
        var blocks=BookStructure.Read(result);
        var sections=BookStructure.DetectFile(blocks,Path.GetFileName(source),1);
        File.WriteAllText(Path.Combine(evidence,"sections.json"),Json.Write(sections));
        File.WriteAllText(Path.Combine(evidence,"blocks.txt"),string.Join("\n\n",blocks.Select((x,i)=>i+": "+WordDocuments.Block(x).Text)));
        Assert.Contains(sections,s=>s.Kind=="lesson");
        if (Environment.GetEnvironmentVariable("SIYUE_EXPECT_LECTURES") is string expected)
            Assert.Equal(expected.Split(',').Select(int.Parse),sections.Where(s=>s.Kind=="lesson").Select(s=>s.Sequence));
        var questions=new List<object>(); var counts=new List<int>();
        foreach(var section in sections.Where(s=>s.Kind=="lesson")) {
            var slice=Path.Combine(evidence,"lecture-"+section.Sequence+".docx");
            BookStructure.Slice(result,slice,section.Start,section.End);
            var parsed=await word.Parse(slice,Guid.NewGuid(),default);
            counts.Add(parsed.Count);
            Assert.DoesNotContain(parsed,q=>q.Label=="待定题号");
            questions.Add(new {section.Sequence,section.Title,questions=parsed.Select(q=>new {q.Label,q.Family,blocks=Json.Read<ContentBlock[]>(q.BlocksJson).Select(b=>b.Text),issues=Json.Read<Issue[]>(q.IssuesJson)})});
        }
        File.WriteAllText(Path.Combine(evidence,"questions.json"),Json.Write(questions));
        if (Environment.GetEnvironmentVariable("SIYUE_EXPECT_QUESTIONS") is string expectedCounts)
            Assert.Equal(expectedCounts.Split(',').Select(int.Parse),counts);
    }
}
