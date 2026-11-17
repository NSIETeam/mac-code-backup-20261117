using System.Security.Claims;
using Microsoft.AspNetCore;
using Microsoft.AspNetCore.Authentication;
using OpenIddict.Abstractions;
using OpenIddict.Server.AspNetCore;
using static OpenIddict.Abstractions.OpenIddictConstants;
namespace Siyue.Collaboration;

public static class LoginApi {
 public static async Task Register(IServiceProvider services,IConfiguration config){
  var manager=services.GetRequiredService<IOpenIddictApplicationManager>();var client=config["Oidc:ClientId"]??"siyue-gitea";
  var descriptor=new OpenIddictApplicationDescriptor{ClientId=client,ClientType=ClientTypes.Confidential,ApplicationType=ApplicationTypes.Web,ClientSecret=config["Oidc:ClientSecret"]??throw new InvalidOperationException("OIDC client secret is required"),DisplayName="思跃代码平台",ConsentType=ConsentTypes.Implicit};
  descriptor.RedirectUris.Add(new Uri(config["Oidc:RedirectUri"]??throw new InvalidOperationException("Gitea callback is required")));
  foreach(var permission in new[]{Permissions.Endpoints.Authorization,Permissions.Endpoints.Token,Permissions.GrantTypes.AuthorizationCode,Permissions.ResponseTypes.Code,Permissions.Scopes.Email,Permissions.Scopes.Profile})descriptor.Permissions.Add(permission);
  var existing=await manager.FindByClientIdAsync(client);if(existing==null)await manager.CreateAsync(descriptor);else await manager.UpdateAsync(existing,descriptor);
 }
 public static void Map(WebApplication app){
  app.MapPost("/api/collaboration/session",async(HttpContext c)=>{
   var actor=Check.Actor(c);var claims=new ClaimsIdentity("collaboration-login");claims.AddClaim(new(ClaimTypes.NameIdentifier,actor.Id.ToString()));claims.AddClaim(new("session_hash",(string)c.Items["sessionHash"]!));
   await c.SignInAsync("collaboration-login",new ClaimsPrincipal(claims));return Results.NoContent();
  });
  app.MapPost("/api/collaboration/logout",async(HttpContext c)=>{await c.SignOutAsync("collaboration-login");return Results.NoContent();});
  app.MapMethods("/api/collaboration/oidc/authorize",["GET","POST"],async(HttpContext c,IIdentityDirectory identity,Gitea git)=>{
   var auth=await c.AuthenticateAsync("collaboration-login");var hash=auth.Principal?.FindFirstValue("session_hash");var actor=hash==null?null:await identity.Session(hash);
   if(actor==null){var target=c.Request.PathBase+c.Request.Path+c.Request.QueryString;return Results.Redirect(c.Request.PathBase+"/collaboration/?authorize="+Uri.EscapeDataString(target));}
   await git.EnsureUser(actor);
   var request=c.GetOpenIddictServerRequest()??throw new Fault(400,"登录请求无效");var claims=new ClaimsIdentity(OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
   claims.SetClaim(Claims.Subject,actor.Id.ToString());claims.SetClaim(Claims.Name,actor.Name);claims.SetClaim(Claims.PreferredUsername,Gitea.Login(actor.Id));claims.SetClaim(Claims.Email,Gitea.Email(actor.Id));claims.SetClaim(Claims.EmailVerified,true);claims.SetClaim("session_hash",hash);
   var principal=new ClaimsPrincipal(claims);principal.SetScopes(request.GetScopes().Intersect(new[]{Scopes.OpenId,Scopes.Profile,Scopes.Email}));
   principal.SetDestinations(claim=>claim.Type=="session_hash"?new[]{Destinations.AccessToken}:new[]{Destinations.AccessToken,Destinations.IdentityToken});
   return Results.SignIn(principal,authenticationScheme:OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
  });
  app.MapPost("/api/collaboration/oidc/token",async(HttpContext c,IIdentityDirectory identity)=>{
   var result=await c.AuthenticateAsync(OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);Check.That(result.Principal!=null,"授权无效",401);
   var hash=result.Principal.FindFirstValue("session_hash");Check.That(hash!=null&&await identity.Session(hash)!=null,"会话已失效",401);
   return Results.SignIn(result.Principal,authenticationScheme:OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
  });
  app.MapMethods("/api/collaboration/oidc/userinfo",["GET","POST"],async(HttpContext c,IIdentityDirectory identity)=>{
   var result=await c.AuthenticateAsync(OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);var hash=result.Principal?.FindFirstValue("session_hash");var actor=hash==null?null:await identity.Session(hash);Check.That(actor!=null,"会话已失效",401);
   return Results.Ok(new{sub=actor.Id.ToString(),name=actor.Name,preferred_username=Gitea.Login(actor.Id),email=Gitea.Email(actor.Id),email_verified=true});
  });
 }
}
