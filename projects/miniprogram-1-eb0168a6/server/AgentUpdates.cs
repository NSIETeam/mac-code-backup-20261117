using System.Security.Cryptography;
using System.Text.Json;
namespace Siyue;
public record AgentUpdateManifest(string Version,string Sha256,string FileName,long Size);
public static class AgentUpdates
{
    public static string? Root(IConfiguration config, Guid? deviceId = null) {
        var root=config["Updates:Directory"];
        if(root!=null && deviceId.HasValue){var scoped=Path.Combine(root,"devices",deviceId.Value.ToString());if(File.Exists(Path.Combine(scoped,"release.json")))return scoped;}
        return root;
    }
    public static AgentUpdateManifest? Read(IConfiguration config, Guid? deviceId = null) {
        var root=Root(config,deviceId);if(root==null||!File.Exists(Path.Combine(root,"release.json")))return null;
        AgentUpdateManifest? m; try { m=JsonSerializer.Deserialize<AgentUpdateManifest>(File.ReadAllText(Path.Combine(root,"release.json")),Json.Options); } catch(JsonException) { throw new ApiException(503,"更新包清单无效"); }
        if(m==null||!System.Version.TryParse(m.Version,out _)||string.IsNullOrEmpty(m.Sha256)||m.Sha256.Length!=64||!m.Sha256.All(Uri.IsHexDigit)||string.IsNullOrEmpty(m.FileName)||Path.GetFileName(m.FileName)!=m.FileName||!m.FileName.EndsWith(".exe")||m.Size<=0||m.Size>314572800)throw new ApiException(503,"更新包清单无效");
        var file=new FileInfo(Path.Combine(root,m.FileName));if(!file.Exists||file.Length!=m.Size)throw new ApiException(503,"更新安装包尚未就绪");return m;
    }
    public static void MapAgentUpdates(this WebApplication app,RouteGroupBuilder agent) {
        app.MapGet("/api/admin/agent-update",(Access a,IConfiguration c,Guid? deviceId)=>{a.Admin();return Results.Ok(Read(c,deviceId));});
        agent.MapGet("/updates/current",(IConfiguration c,HttpContext ctx)=>Results.Ok(Read(c,((Device)ctx.Items["device"]!).Id)));
        agent.MapGet("/updates/package",(IConfiguration c,HttpContext ctx)=>{var id=((Device)ctx.Items["device"]!).Id;var m=Read(c,id)??throw new ApiException(404,"暂无更新");return Results.File(Path.Combine(Root(c,id)!,m.FileName),"application/octet-stream",m.FileName,enableRangeProcessing:true);});
    }
}
