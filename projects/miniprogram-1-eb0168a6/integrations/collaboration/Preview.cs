using Microsoft.AspNetCore.DataProtection;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Siyue.Collaboration;
public sealed record PreviewTicket(Guid ProjectId,Guid RepositoryId,Guid BuildId,string Digest,string SessionHash,long ExpiresAt);
public static class Preview {
 public static void Map(WebApplication app){
  app.MapGet("/api/collaboration/projects/{id:guid}/preview",async(Guid id,Guid? buildId,Guid? repositoryId,HttpContext c,ProjectAccess access,CollaborationDb db,Delivery delivery,IDataProtectionProvider keys)=>{
   var p=await access.Read(id,Check.Actor(c));var repo=await CodeApi.Resolve(id,repositoryId,c,db,access);
   var bid=buildId??(repo.Id==p.RepositoryId?p.TestingBuildId:null);Check.That(bid.HasValue,"尚无可体验的测试版本",409);
   var build=await db.Builds.FindAsync(bid);Check.That(build!=null&&build.RepositoryId==repo.Id&&build.State is "tested" or "previewed","此提交尚未通过检查及测试部署",409);
   var ticket=new PreviewTicket(id,repo.Id,build.Id,build.ArtifactDigest,(string)c.Items["sessionHash"]!,DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeSeconds());
   var encoded=keys.CreateProtector("siyue-preview-v2").Protect(JsonSerializer.Serialize(ticket));
   var info=await delivery.Call("/describe",new{key=repo.DeploymentKey});var entry=info.TryGetProperty("entry",out var path)?path.GetString():"index.html";
   return new{url=c.Request.PathBase+"/collaboration/test/"+id+"/"+encoded+"/"+entry,buildId=build.Id,build.Sha,build.ArtifactDigest,notes=info.TryGetProperty("notes",out var notes)?notes.GetString():"隔离测试环境，数据不进入正式业务。"};
  });
  app.MapMethods("/collaboration/test/{id:guid}/{ticket}/{**path}",["GET","POST","PUT","PATCH","DELETE","OPTIONS"],async(Guid id,string ticket,string? path,HttpContext c,IDataProtectionProvider keys,IIdentityDirectory identity,ProjectAccess access,CollaborationDb db,Delivery delivery)=>{
   PreviewTicket? grant;try{grant=JsonSerializer.Deserialize<PreviewTicket>(keys.CreateProtector("siyue-preview-v2").Unprotect(ticket));}catch{throw new Fault(401,"测试入口已失效，请从项目页面重新打开");}
   Check.That(grant!=null&&grant.ProjectId==id&&grant.ExpiresAt>DateTimeOffset.UtcNow.ToUnixTimeSeconds(),"测试入口已过期，请从项目页面重新打开",401);
   var actor=await identity.Session(grant.SessionHash);Check.That(actor!=null,"登录已失效",401);await access.Read(id,actor);
   c.Items["actor"]=actor; // Resolve uses the authenticated actor item set by identity middleware below.
   var project=await db.Projects.FindAsync(id);var primary=await db.Repositories.FindAsync(project!.RepositoryId);var repo=await db.Repositories.FindAsync(grant.RepositoryId)??throw new Fault(404,"代码库不存在");
   Check.That(primary!=null&&(repo.ParentRepositoryId??repo.Id)==(primary.ParentRepositoryId??primary.Id),"代码库关联已变更",403);
   var build=await db.Builds.FindAsync(grant.BuildId);Check.That(build!=null&&build.RepositoryId==repo.Id&&build.ArtifactDigest==grant.Digest&&build.State is "tested" or "previewed","测试版本已失效",409);
   var testRoot=$"{c.Request.Scheme}://{c.Request.Host}{c.Request.PathBase}/collaboration/test/{id}/{ticket}/";
   c.Response.Headers.ContentSecurityPolicy=$"sandbox allow-scripts allow-forms; default-src 'none'; script-src {testRoot} 'unsafe-inline'; style-src {testRoot} 'unsafe-inline'; img-src {testRoot} data: blob:; connect-src {testRoot}; font-src {testRoot} data:; form-action {testRoot}; base-uri 'none'; frame-ancestors 'self'";
   c.Response.Headers["Access-Control-Allow-Origin"]="null";c.Response.Headers["Access-Control-Allow-Methods"]="GET, POST, PUT, PATCH, DELETE, OPTIONS";c.Response.Headers["Access-Control-Allow-Headers"]="Content-Type, Authorization, X-Workbench-Token, X-CSRF-Token, Idempotency-Key, X-Material-Name";c.Response.Headers["Cache-Control"]="no-store";c.Response.Headers["Referrer-Policy"]="no-referrer";
   if(c.Request.Method=="OPTIONS")return Results.NoContent();
   using var bytes=new MemoryStream();var buffer=new byte[8192];int n;while((n=await c.Request.Body.ReadAsync(buffer))>0){Check.That(bytes.Length+n<=4*1024*1024,"测试请求过大",413);bytes.Write(buffer,0,n);}
   var result=await delivery.Call("/preview",new{key=repo.DeploymentKey,buildId=grant.BuildId,digest=grant.Digest,viewer=ticket,path=(path??"")+c.Request.QueryString,method=c.Request.Method,body=Convert.ToBase64String(bytes.ToArray()),type=c.Request.ContentType??"application/json",authorization=c.Request.Headers.Authorization.ToString(),workbenchToken=c.Request.Headers["X-Workbench-Token"].ToString(),csrfToken=c.Request.Headers["X-CSRF-Token"].ToString(),idempotencyKey=c.Request.Headers["Idempotency-Key"].ToString(),materialName=c.Request.Headers["X-Material-Name"].ToString(),prefix=new Uri(testRoot).AbsolutePath});
   c.Response.StatusCode=result.GetProperty("status").GetInt32();var data=Convert.FromBase64String(result.GetProperty("body").GetString()??"");var type=result.GetProperty("type").GetString()??"application/octet-stream";
   if(type.StartsWith("text/html")){
    var source=Encoding.UTF8.GetString(data);source=Regex.Replace(source,"(?<attr>(?:src|href|action)\\s*=\\s*[\"'])/(?!/)(?:miniapp/)?",m=>m.Groups["attr"].Value+testRoot,RegexOptions.IgnoreCase);
    var script=await File.ReadAllTextAsync(Path.Combine(app.Environment.WebRootPath,"collaboration","preview-bridge.js"));script=script.Replace("__SIYUE_PREVIEW_CONFIG__",JsonSerializer.Serialize(new{root=testRoot,storage=result.TryGetProperty("storage",out var storage)?storage:(JsonElement?)null,hint=result.TryGetProperty("hint",out var hint)?hint.GetString():""}));
    var injection="<script>"+script+"</script>";source=Regex.IsMatch(source,"<head(?:\\s[^>]*)?>",RegexOptions.IgnoreCase)?new Regex("<head(?:\\s[^>]*)?>",RegexOptions.IgnoreCase).Replace(source,m=>m.Value+injection,1):injection+source;data=Encoding.UTF8.GetBytes(source);
   }
   return Results.Bytes(data,type);
  });
 }
}
