using System.Net;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using UglyToad.PdfPig.Writer;
using Xunit;

public class PdfFixedModelGuardTests
{
    static IConfiguration Settings(string? strategy=null) => new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> {
        ["Documents:ParsingStrategy"]=strategy,["LearningAi:Enabled"]="true",["LearningAi:Vision"]="true",["LearningAi:PdfAssistEnabled"]="true",
        ["LearningAi:Endpoint"]="https://offline.invalid/v1/chat/completions",["LearningAi:Model"]="offline-fixture",["LearningAi:ApiKey"]="offline-fixture-key"
    }).Build();

    [Theory] [InlineData(null)] [InlineData("fixed")]
    public async Task FixedPolicyRejectsEveryExternalPdfCallBeforeCreatingHttpClient(string? strategy) {
        var config=Settings(strategy);var clients=new NoRequests();
        Assert.False(AiHandoutLayout.Enabled(config));Assert.False(PdfModelAssist.Enabled(config));
        var image=Convert.FromBase64String(PdfModelAssist.ProbeImage);
        Assert.Equal(409,(await Assert.ThrowsAsync<ApiException>(()=>AiHandoutLayout.Review(config,clients,image,new(1),default))).Status);
        Assert.Equal(409,(await Assert.ThrowsAsync<ApiException>(()=>AiBookOutline.Review(config,clients,new(1,[],[]),default))).Status);
        Assert.Equal(409,(await Assert.ThrowsAsync<ApiException>(()=>PdfModelAssist.Review(config,clients,image,new(1,1,[],[]),null,default))).Status);
        Assert.Equal(409,(await Assert.ThrowsAsync<ApiException>(()=>FormulaRecognition.ReadWithModel(config,clients,image,"",null,default))).Status);
        Assert.Equal(0,clients.Calls);
    }

    [Fact]
    public async Task NativeFixedImportDoesNotReadEnabledModelOrFormulaServices() {
        await using var f=new FixedFactory();using var scope=f.Services.CreateScope();
        var db=scope.ServiceProvider.GetRequiredService<Data>();var protection=scope.ServiceProvider.GetRequiredService<IDataProtectionProvider>();
        var model=new ModelConfigurationValue(true,"https://offline.invalid/v1/chat/completions","offline-fixture","chat-completions",true,true,"offline-fixture-key",true);
        var formula=new FormulaSettingsValue(true,"https://offline.invalid/formula","offline-fixture-key");
        db.LearningModelConfigurations.Add(new(){Id=LearningModelSettings.ConfigurationId,ProtectedJson=protection.CreateProtector("Siyue.LearningModelConfiguration.v1").Protect(Json.Write(model))});
        db.LearningModelConfigurations.Add(new(){Id=FormulaSettings.Id,ProtectedJson=protection.CreateProtector("Siyue.SimpleTex.v1").Protect(Json.Write(formula))});
        await db.SaveChangesAsync();
        var source=Path.Combine(f.Root,"fixed-native.pdf");var builder=new PdfDocumentBuilder();var font=builder.AddStandard14Font(Standard14Font.Helvetica);
        var page=builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);page.AddText("1. Preserve the original question and all formula pixels.",12,new PdfPoint(50,700),font);
        File.WriteAllBytes(source,builder.Build());
        var word=scope.ServiceProvider.GetRequiredService<WordDocuments>();using var recognition=QuestionRecognition.Use(QuestionRecognition.Legacy);
        var normalized=await word.NormalizeHandout(source,f.Root,default);Assert.True(File.Exists(normalized));
        Assert.NotEmpty(await word.Parse(normalized,Guid.NewGuid(),default));Assert.Equal(0,f.Http.Calls);
        Assert.Null(await word.TryNormalizeBookOutline("does-not-exist.pdf",f.Root,default));Assert.Equal(0,f.Http.Calls);
    }

    sealed class NoRequests : IHttpClientFactory {
        public int Calls {get;private set;}
        public HttpClient CreateClient(string name){Calls++;throw new InvalidOperationException("Fixed PDF parsing must never create an external client.");}
    }
    sealed class BlockingHandler : HttpMessageHandler {
        public int Calls {get;private set;}
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken cancellationToken){Calls++;return Task.FromResult(new HttpResponseMessage(HttpStatusCode.ServiceUnavailable));}
    }
    sealed class FixedFactory : Factory {
        public BlockingHandler Http {get;}=new();
        protected override void ConfigureWebHost(IWebHostBuilder builder){
            base.ConfigureWebHost(builder);
            builder.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{["Documents:ParsingStrategy"]="fixed"}));
            builder.ConfigureServices(s=>s.AddHttpClient("learning-ai").ConfigurePrimaryHttpMessageHandler(()=>Http));
        }
    }
}
