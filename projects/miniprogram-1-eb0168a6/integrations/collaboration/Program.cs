using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.DataProtection;
using Siyue.Collaboration;
using System.Security.Cryptography.X509Certificates;
using OpenIddict.Abstractions;
using OpenIddict.Server.AspNetCore;
using System.Threading.RateLimiting;

var builder=WebApplication.CreateBuilder(args);
builder.Logging.AddFilter("Microsoft.EntityFrameworkCore.Database.Command",LogLevel.Warning);
builder.Logging.AddFilter("Microsoft.AspNetCore.Hosting.Diagnostics",LogLevel.Warning);
builder.Logging.AddFilter("Microsoft.AspNetCore.Routing.EndpointMiddleware",LogLevel.Warning);
if(builder.Configuration["CollaborationConfig"] is string configurationFile)builder.Configuration.AddJsonFile(configurationFile,optional:false,reloadOnChange:false).AddEnvironmentVariables();
builder.WebHost.ConfigureKestrel(o=>o.Limits.MaxRequestBodySize=12*1024*1024);
builder.Services.AddDbContext<CollaborationDb>(o=>{
 if(builder.Environment.IsEnvironment("Testing"))o.UseSqlite(builder.Configuration.GetConnectionString("Collaboration")??"Data Source=collaboration-test.db");
 else o.UseNpgsql(builder.Configuration.GetConnectionString("Collaboration"));o.UseOpenIddict();
});
builder.Services.AddScoped<IIdentityDirectory,IdentityDirectory>();
if(builder.Environment.IsEnvironment("Testing")&&builder.Configuration.GetValue<bool>("Preview:Isolated")){
 if((builder.Configuration["Preview:SessionToken"]??"").Length<32||!string.IsNullOrEmpty(builder.Configuration["Gitea:Token"])||!string.IsNullOrEmpty(builder.Configuration["Delivery:Secret"])||!string.IsNullOrEmpty(builder.Configuration.GetConnectionString("Identity")))throw new InvalidOperationException("隔离预览不得配置正式身份、代码或发布凭证");
 builder.Services.AddScoped<IIdentityDirectory,PreviewIdentity>();
}
builder.Services.AddScoped<ProjectAccess>();
builder.Services.AddHttpClient("gitea",c=>c.Timeout=TimeSpan.FromSeconds(20)).ConfigurePrimaryHttpMessageHandler(()=>new HttpClientHandler{AllowAutoRedirect=false,UseProxy=false}).RemoveAllLoggers();
builder.Services.AddHttpClient("deployer",c=>c.Timeout=TimeSpan.FromSeconds(30)).ConfigurePrimaryHttpMessageHandler(()=>new HttpClientHandler{AllowAutoRedirect=false,UseProxy=false}).RemoveAllLoggers();
builder.Services.AddHttpClient("agent-loopback",c=>c.Timeout=TimeSpan.FromSeconds(30)).ConfigurePrimaryHttpMessageHandler(()=>new HttpClientHandler{AllowAutoRedirect=false,UseProxy=false}).RemoveAllLoggers();
builder.Services.AddScoped<Gitea>();builder.Services.AddScoped<Delivery>();
builder.Services.AddAuthentication("collaboration-login").AddCookie("collaboration-login",o=>{o.Cookie.Name="siyue_collaboration";o.Cookie.HttpOnly=true;o.Cookie.SecurePolicy=CookieSecurePolicy.Always;o.Cookie.SameSite=SameSiteMode.Lax;o.ExpireTimeSpan=TimeSpan.FromHours(8);o.SlidingExpiration=false;});
if(builder.Configuration["Keys:Directory"] is string keys)builder.Services.AddDataProtection().PersistKeysToFileSystem(new DirectoryInfo(keys)).SetApplicationName("siyue-collaboration");
builder.Services.AddOpenIddict().AddCore(o=>o.UseEntityFrameworkCore().UseDbContext<CollaborationDb>()).AddServer(o=>{
 o.SetIssuer(new Uri(builder.Configuration["Oidc:Issuer"]??"https://localhost/api/collaboration/oidc/"));
 var endpoint=(builder.Configuration["PathBase"]??"")+"/api/collaboration/oidc/";
 o.SetAuthorizationEndpointUris(endpoint+"authorize").SetTokenEndpointUris(endpoint+"token").SetUserInfoEndpointUris(endpoint+"userinfo");
 o.SetConfigurationEndpointUris(endpoint+".well-known/openid-configuration").SetJsonWebKeySetEndpointUris(endpoint+"jwks");
 // Gitea is a confidential server client with an exact callback and client secret.
 // Its OIDC client does not send PKCE; OpenIddict still validates it when supplied.
 o.AllowAuthorizationCodeFlow();o.RegisterScopes("openid","profile","email");o.DisableAccessTokenEncryption();
 if(builder.Configuration["Oidc:Certificate"] is string cert){var x=X509CertificateLoader.LoadPkcs12FromFile(cert,builder.Configuration["Oidc:CertificatePassword"]);o.AddSigningCertificate(x);o.AddEncryptionCertificate(x);}
 else if(builder.Environment.IsEnvironment("Testing")||builder.Environment.IsDevelopment()){o.AddEphemeralSigningKey().AddEphemeralEncryptionKey();}
 else throw new InvalidOperationException("OIDC signing certificate is required");
 o.UseAspNetCore().EnableAuthorizationEndpointPassthrough().EnableTokenEndpointPassthrough().EnableUserInfoEndpointPassthrough();
});
builder.Services.AddRateLimiter(o=>{o.RejectionStatusCode=429;o.AddPolicy("collaboration",c=>RateLimitPartition.GetFixedWindowLimiter((c.Items["actor"] as Person)?.Id.ToString()??c.Connection.RemoteIpAddress?.ToString()??"unknown",_=>new(){PermitLimit=300,Window=TimeSpan.FromMinutes(1),QueueLimit=0}));});
if(!builder.Environment.IsEnvironment("Testing"))builder.Services.AddHostedService<CollaborationWorker>();
var app=builder.Build();
if(app.Configuration["PathBase"] is string prefix&&!string.IsNullOrEmpty(prefix))app.UsePathBase(prefix);
// Only the loopback reverse proxy can set the external scheme. Never trust arbitrary forwarded identities.
app.Use(async(c,next)=>{if(c.Connection.RemoteIpAddress is {} ip&&System.Net.IPAddress.IsLoopback(ip)&&c.Request.Headers["X-Forwarded-Proto"]=="https")c.Request.Scheme="https";await next();});
app.Use(async(c,next)=>{
 c.Response.Headers.CacheControl="no-store";c.Response.Headers["X-Content-Type-Options"]="nosniff";c.Response.Headers["Referrer-Policy"]="no-referrer";
 c.Response.Headers.ContentSecurityPolicy="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'";
 try{await next();}catch(Fault e){c.Response.StatusCode=e.Status;await c.Response.WriteAsJsonAsync(new{message=e.Message});}
 catch(DbUpdateException){c.Response.StatusCode=409;await c.Response.WriteAsJsonAsync(new{message="记录已被更新或重复提交，请刷新后重试"});}
 catch(BadHttpRequestException e){c.Response.StatusCode=e.StatusCode;await c.Response.WriteAsJsonAsync(new{message="请求格式无效或文件过大"});}
 catch(Exception e){app.Logger.LogError(e,"Collaboration request {Trace}",c.TraceIdentifier);if(!c.Response.HasStarted){c.Response.StatusCode=500;await c.Response.WriteAsJsonAsync(new{message="服务暂时不可用，请稍后重试",traceId=c.TraceIdentifier});}}
});
app.UseRouting();app.UseAuthentication();
app.Use(async(c,next)=>{
 var path=c.Request.Path.Value??"";
 if(path.StartsWith("/api/collaboration")&&!path.StartsWith("/api/collaboration/oidc/")&&path!="/api/collaboration/hooks/gitea"){
  var header=c.Request.Headers.Authorization.ToString();
  // The shared inbox can use the existing AI Lab session in tabs without a workbench token.
  // Cookie authentication is restricted to inbox operations with a same-origin custom header.
  const string inbox="/api/collaboration/notifications";
  var inboxRequest=c.Request.Headers["X-Siyue-Inbox"]=="1" &&
      (HttpMethods.IsGet(c.Request.Method) && (path==inbox||path==inbox+"/unread-count") ||
       HttpMethods.IsPost(c.Request.Method) && path.StartsWith(inbox+"/",StringComparison.Ordinal) &&
       path.EndsWith("/read",StringComparison.Ordinal));
  if(header.Length==0&&inboxRequest&&c.Request.Cookies.TryGetValue("siyue_print_session",out var session))
      header="Bearer "+session;
  Check.That(header.StartsWith("Bearer ")&&header.Length is >7 and <1024,"请先登录教学工作台",401);
  if(header[7..].StartsWith("syagent_")){await AgentApi.Authenticate(c,header[7..]);await next();return;}
  var hash=Check.Hash(header[7..]);var person=await c.RequestServices.GetRequiredService<IIdentityDirectory>().Session(hash,c.RequestAborted);
  Check.That(person!=null,"登录已过期或账号已停用",401);c.Items["actor"]=person;c.Items["sessionHash"]=hash;
 }await next();
});
app.UseRateLimiter();app.UseDefaultFiles();var contentTypes=new Microsoft.AspNetCore.StaticFiles.FileExtensionContentTypeProvider();contentTypes.Mappings[".md"]="text/plain; charset=utf-8";app.UseStaticFiles(new StaticFileOptions{ContentTypeProvider=contentTypes});
app.MapGet("/health",async(CollaborationDb db)=>{await db.Database.ExecuteSqlRawAsync("SELECT 1");return new{status="ok",application="siyue-collaboration",version="20261115-ci"};});
CollaborationApi.Map(app);ActionsApi.Map(app);AgentApi.Map(app);AgentMcp.Map(app);GitApi.Map(app);CodeApi.Map(app);LoginApi.Map(app);Preview.Map(app);
await using(var scope=app.Services.CreateAsyncScope()){
 var db=scope.ServiceProvider.GetRequiredService<CollaborationDb>();await db.Database.EnsureCreatedAsync();
 if(!app.Environment.IsEnvironment("Testing"))await LoginApi.Register(scope.ServiceProvider,app.Configuration);
}
await app.RunAsync();
public partial class Program{}
