using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Configuration;
using Siyue;
using Xunit;

public class VariantGeneratorTests
{
    static IConfiguration Config(params (string,string)[] extra) => new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> {
        ["LearningAi:Endpoint"]="https://model.example.test/v1/chat/completions", ["LearningAi:Model"]="configured-vision-model", ["LearningAi:ApiKey"]="test-only-api-key"
    }).AddInMemoryCollection(extra.Select(x=>new KeyValuePair<string,string?>("LearningAi:"+x.Item1,x.Item2))).Build();
    static readonly Guid SourceId=Guid.NewGuid();
    static VariantSource Source(bool diagram=false) => new(SourceId,[new ContentBlock(diagram ? "<p xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main'><a:blip r:embed='rId1'/></p>" : "<p>例1：求解 x+1=2</p>","例1：求解 x+1=2","paragraph")],["cG5n"]);
    static string Payload()=>Json.Write(new {items=new[]{new VariantQuestion(SourceId,"解方程 3x+2=11。","x=3","先减2，再除以3。","一元一次方程","等式两边同变形","同等")}});
    static byte[] Response(string reason="stop",object? refusal=null,string? text=null)=>Encoding.UTF8.GetBytes(Json.Write(new{choices=new[]{new{finish_reason=reason,message=new{content=text??Payload(),refusal}}}}));
    [Fact]
    public void ConfigRequiresHttpsModelAndCredentialWithoutUrlCredentials()
    {
        Assert.True(VariantGenerator.Configured(Config()));
        Assert.False(VariantGenerator.Configured(Config(("Model",""))));Assert.False(VariantGenerator.Configured(Config(("ApiKey",""))));
        Assert.False(VariantGenerator.Configured(Config(("Endpoint","http://model.example.test/completions"))));
        Assert.False(VariantGenerator.Configured(Config(("Endpoint","https://user:pass@model.example.test/completions"))));
    }
    [Fact]
    public void ChatRequestIncludesSourceIdentityMathAndImagesButNoStudentIdentity()
    {
        var body=JsonSerializer.SerializeToElement(VariantGenerator.RequestBody(Config(),2,[Source()]),Json.Options);
        Assert.Equal("configured-vision-model",body.GetProperty("model").GetString());Assert.Equal("json_object",body.GetProperty("response_format").GetProperty("type").GetString());
        var messages=body.GetProperty("messages");Assert.Equal("system",messages[0].GetProperty("role").GetString());var content=messages[1].GetProperty("content");
        var source=JsonDocument.Parse(content[0].GetProperty("text").GetString()!).RootElement;Assert.Equal(SourceId,source.GetProperty("sourceQuestionId").GetGuid());Assert.Equal(2,source.GetProperty("perQuestion").GetInt32());Assert.Contains("x+1=2",source.GetProperty("blocks")[0].GetProperty("xml").GetString());Assert.Equal("data:image/png;base64,cG5n",content[1].GetProperty("image_url").GetProperty("url").GetString());Assert.False(source.TryGetProperty("studentName",out _));
    }
    [Fact]
    public void TextModelCannotSilentlyDiscardDiagramConditions()
    {
        var error=Assert.Throws<ApiException>(()=>VariantGenerator.RequestBody(Config(("Vision","false")),1,[Source(true)]));Assert.Equal(422,error.Status);
        var text=JsonSerializer.SerializeToElement(VariantGenerator.RequestBody(Config(("Vision","false")),1,[Source()]),Json.Options);Assert.Single(text.GetProperty("messages")[1].GetProperty("content").EnumerateArray());
    }
    [Theory]
    [InlineData("chat-completions")]
    [InlineData("structured")]
    public void DamagedExtractionUsesOnlyImagesInBothProtocols(string protocol)
    {
        var source=Source() with {Blocks=[new ContentBlock("<p>3�+1</p>","3�+1","paragraph")]};
        var body=JsonSerializer.Serialize(VariantGenerator.RequestBody(Config(("Protocol",protocol)),1,[source]),Json.Options);
        Assert.DoesNotContain("3�+1",body);Assert.DoesNotContain("3\\uFFFD+1",body);
        Assert.Contains("image-reference",body);Assert.Contains("cG5n",body);
        Assert.Throws<ApiException>(()=>VariantGenerator.RequestBody(Config(("Protocol",protocol),("Vision","false")),1,[source]));
        Assert.Throws<ApiException>(()=>VariantGenerator.RequestBody(Config(("Protocol",protocol)),1,[source with {PreviewPngBase64=[]}]));
    }
    [Fact]
    public void PdfMathWithoutReplacementCharactersStillRequiresImageGrounding()
    {
        var xml="<p xmlns='http://schemas.openxmlformats.org/wordprocessingml/2006/main' xmlns:wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'><r><drawing><wp:inline><wp:docPr id='1' name='SiyuePDF1' descr='question'/></wp:inline></drawing></r></p>";
        var source=Source() with {Blocks=[new ContentBlock(xml,"wrong-order-x2","paragraph")]};
        var body=JsonSerializer.Serialize(VariantGenerator.RequestBody(Config(),1,[source]),Json.Options);
        Assert.DoesNotContain("wrong-order-x2",body);Assert.Contains("image-reference",body);
    }
    [Fact]
    public void CompleteJsonIsAcceptedButTruncationRefusalAndUnstructuredContentAreRejected()
    {
        Assert.Equal(SourceId,Assert.Single(VariantGenerator.Parse(Config(),Response())).SourceQuestionId);
        Assert.Single(VariantGenerator.Parse(Config(),Response(text:"```json\n"+Payload()+"\n```")));
        Assert.Throws<ApiException>(()=>VariantGenerator.Parse(Config(),Response(reason:"length")));
        Assert.Throws<ApiException>(()=>VariantGenerator.Parse(Config(),Response(refusal:"cannot produce this")));
        Assert.Throws<ApiException>(()=>VariantGenerator.Parse(Config(),Response(text:"这是结果："+Payload())));
        Assert.Throws<ApiException>(()=>VariantGenerator.Parse(Config(),Encoding.UTF8.GetBytes("{}")));
    }
    [Fact]
    public async Task ChatTransportSendsBearerAndParsesProviderEnvelope()
    {
        var handler=new Handler(async r=>{Assert.Equal("Bearer",r.Headers.Authorization?.Scheme);Assert.Equal("test-only-api-key",r.Headers.Authorization?.Parameter);var body=await r.Content!.ReadAsStringAsync();Assert.Contains("messages",body);return new(HttpStatusCode.OK){Content=new ByteArrayContent(Response())};});
        var items=await VariantGenerator.Generate(Config(),new Clients(handler),1,[Source()],default);Assert.Single(items);LearningSupport.Validate(items,[SourceId],1);
    }
    [Fact]
    public async Task ExcessiveProviderResponseFailsWithoutProducingDraft()
    {
        var handler=new Handler(_=>Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK){Content=new ByteArrayContent(new byte[1_048_577])}));var error=await Assert.ThrowsAsync<ApiException>(()=>VariantGenerator.Generate(Config(),new Clients(handler),1,[Source()],default));Assert.Equal(502,error.Status);
    }
    sealed class Handler(Func<HttpRequestMessage,Task<HttpResponseMessage>> send):HttpMessageHandler {protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)=>send(request);}
    sealed class Clients(HttpMessageHandler handler):IHttpClientFactory {public HttpClient CreateClient(string name)=>new(handler);}
}
