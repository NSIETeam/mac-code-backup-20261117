using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig.Writer;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using Xunit;

public class FormulaFailureIsolationTests
{
    [Theory]
    [InlineData("server-error")]
    [InlineData("malformed")]
    [InlineData("timeout")]
    [InlineData("success")]
    public async Task OptionalRecognitionNeverChangesSourcePicturesOrLosesQuestions(string mode)
    {
        await using var f=new FailureFactory(mode);using var admin=await f.Login("formula-isolation",true);
        using var scope=f.Services.CreateScope();var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();
        var builder=new PdfDocumentBuilder();var font=builder.AddStandard14Font(Standard14Font.Helvetica);
        for(var i=1;i<=3;i++)builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText($"{i}. Calculate x squared + 2.",12,new PdfPoint(50,750),font);
        var input=Path.Combine(f.Root,"input.pdf");await File.WriteAllBytesAsync(input,builder.Build());
        var normalized=await word.NormalizePdfHandout(input,f.Root,default);
        string[] Hashes(string path){using var doc=WordprocessingDocument.Open(path,false);return doc.MainDocumentPart!.ImageParts.Select(p=>{using var s=p.GetStream();return Convert.ToHexString(SHA256.HashData(s));}).Order().ToArray();}
        var original=Hashes(normalized);
        var response=await admin.PutAsJsonAsync("/api/admin/formula-service",new FormulaSettingsInput(0,true,"https://formula.invalid/api/latex_ocr","test-secret"));response.EnsureSuccessStatusCode();
        normalized=await word.NormalizePdfHandout(input,f.Root,default);
        var questions=await word.Parse(normalized,Guid.NewGuid(),default);
        Assert.Equal(new[]{"1.","2.","3."},questions.Select(q=>q.Label));Assert.Equal(original,Hashes(normalized));
        var evidence=questions.SelectMany(q=>Json.Read<ContentBlock[]>(q.BlocksJson)).Select(b=>FormulaRecognition.Evidence(XElement.Parse(b.Xml))).Where(x=>x!=null).ToArray();
        // Fixed PDF parsing preserves source pictures and never invokes the optional
        // external formula recognizer, even when an obsolete setting is enabled.
        Assert.Empty(evidence);Assert.Equal(0,f.Handler.Calls);
        Assert.All(questions,q=>Assert.DoesNotContain(Json.Read<Issue[]>(q.IssuesJson),i=>i.Code=="pdf-formula-review"));
    }
    class FailureFactory(string mode):Factory {
        public FailingHandler Handler {get;}=new(mode);
        protected override void ConfigureWebHost(IWebHostBuilder builder){base.ConfigureWebHost(builder);builder.ConfigureServices(s=>s.AddHttpClient("learning-ai").ConfigurePrimaryHttpMessageHandler(()=>Handler));}
    }
    public class FailingHandler(string mode):HttpMessageHandler {
        public int Calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct){Calls++;if(mode=="timeout")throw new TaskCanceledException();
            return Task.FromResult(mode switch {
                "server-error"=>new HttpResponseMessage(HttpStatusCode.ServiceUnavailable),
                "malformed"=>new HttpResponseMessage(HttpStatusCode.OK){Content=new StringContent("not-json")},
                _=>new HttpResponseMessage(HttpStatusCode.OK){Content=JsonContent.Create(new{status=true,res=new{latex="x^{2}+2",conf=.98}})}
            });
        }
    }
}
