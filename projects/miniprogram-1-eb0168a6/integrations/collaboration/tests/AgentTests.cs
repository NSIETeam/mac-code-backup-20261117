using System.Net;
using System.Net.Http.Json;
using System.Net.Http.Headers;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.DependencyInjection;
using Siyue.Collaboration;
using Xunit;
public class AgentFactory:AppFactory{
 protected override void ConfigureWebHost(IWebHostBuilder builder){base.ConfigureWebHost(builder);builder.ConfigureServices(s=>s.AddHttpClient("agent-loopback").ConfigurePrimaryHttpMessageHandler(()=>new Forward(()=>Server.CreateClient())));}
 sealed class Forward(Func<HttpClient> client):HttpMessageHandler{
  protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct){using var c=client();var copy=new HttpRequestMessage(request.Method,request.RequestUri!.PathAndQuery);foreach(var h in request.Headers)copy.Headers.TryAddWithoutValidation(h.Key,h.Value);if(request.Content!=null){copy.Content=new ByteArrayContent(await request.Content.ReadAsByteArrayAsync(ct));copy.Content.Headers.ContentType=request.Content.Headers.ContentType;}return await c.SendAsync(copy,ct);}
 }
}
public class AgentTests{
 const string Api="/api/collaboration";
 static async Task<JsonElement> Post(HttpClient c,string path,object value){var r=await c.PostAsJsonAsync(Api+path,value);Assert.True(r.IsSuccessStatusCode,await r.Content.ReadAsStringAsync());return r.StatusCode==HttpStatusCode.NoContent?default:await r.Content.ReadFromJsonAsync<JsonElement>();}
 static async Task<Guid> Project(HttpClient c)=> (await Post(c,"/projects",new{name="实验室测试",ownerTeam="AI创新实验室"})).GetProperty("id").GetGuid();
 static async Task<JsonElement> Grant(HttpClient c,Guid id,string scope="read")=>await Post(c,"/agent/grants",new{name="Workbuddy",projectIds=new[]{id},scope,hours=1});
 static HttpClient Agent(AppFactory f,JsonElement grant)=>f.Client(grant.GetProperty("token").GetString()!);
 [Fact]public async Task GrantsEnforceProjectScopeWriteScopeAndForbidPrivilegeEscalation(){
  await using var f=new AppFactory();using var admin=f.Client("admin");var p=await Project(admin);var other=await Project(admin);var grant=await Grant(admin,p);using var reader=Agent(f,grant);
  var projects=await reader.GetFromJsonAsync<JsonElement>(Api+"/projects");Assert.Single(projects.EnumerateArray());Assert.Equal(p,projects[0].GetProperty("id").GetGuid());
  Assert.Equal(HttpStatusCode.Forbidden,(await reader.GetAsync(Api+"/projects/"+other)).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await reader.PostAsJsonAsync(Api+$"/projects/{p}/actions",new{title="读权限写入",requestId=Guid.NewGuid().ToString()})).StatusCode);
  using var writer=Agent(f,await Grant(admin,p,"contribute"));var req=Guid.NewGuid().ToString();var action=await Post(writer,$"/projects/{p}/actions",new{title="真实开发进展",requestId=req,state="in_progress"});
  Assert.Equal(action.GetProperty("id").GetGuid(),(await Post(writer,$"/projects/{p}/actions",new{title="重试",requestId=req})).GetProperty("id").GetGuid());
  foreach(var path in new[]{"/agent/grants","/session",$"/projects/{p}/members",$"/projects/{p}/acceptances",$"/projects/{p}/releases/{Guid.NewGuid()}/publish",$"/projects/{p}/rollback"})Assert.Equal(HttpStatusCode.Forbidden,(await writer.PostAsJsonAsync(Api+path,new{})).StatusCode);
  Assert.Equal(HttpStatusCode.Forbidden,(await writer.GetAsync(Api+"/agent/grants")).StatusCode);
  Assert.Equal(HttpStatusCode.NoContent,(await admin.DeleteAsync(Api+"/agent/grants/"+grant.GetProperty("id").GetGuid())).StatusCode);Assert.Equal(HttpStatusCode.Unauthorized,(await reader.GetAsync(Api+"/projects")).StatusCode);
 }
 [Fact]public async Task GrantsFollowMemberRemovalDisablementExpiryAndOriginalSession(){
  await using var f=new AppFactory();using var owner=f.Client("owner");using var peer=f.Client("peer");var p=await Project(owner);await Post(owner,$"/projects/{p}/members",new{staffId=FakeIdentity.Peer});
  var grant=await Grant(peer,p);using var agent=Agent(f,grant);Assert.Equal(HttpStatusCode.OK,(await agent.GetAsync(Api+$"/projects/{p}")).StatusCode);
  await owner.DeleteAsync(Api+$"/projects/{p}/members/{FakeIdentity.Peer}");Assert.Equal(HttpStatusCode.Forbidden,(await agent.GetAsync(Api+$"/projects/{p}")).StatusCode);Assert.Empty((await agent.GetFromJsonAsync<JsonElement>(Api+"/projects")).EnumerateArray());
  await Post(owner,$"/projects/{p}/members",new{staffId=FakeIdentity.Peer});f.Identity.Users["peer"]=f.Identity.Users["peer"] with{Enabled=false};Assert.Equal(HttpStatusCode.Unauthorized,(await agent.GetAsync(Api+"/projects")).StatusCode);
  f.Identity.Users["peer"]=f.Identity.Users["peer"] with{Enabled=true};await f.WithDb(async db=>{(await db.AgentGrants.SingleAsync())!.ExpiresAt=DateTime.UtcNow.AddMinutes(-1);await db.SaveChangesAsync();});Assert.Equal(HttpStatusCode.Unauthorized,(await agent.GetAsync(Api+"/projects")).StatusCode);
  using var fresh=Agent(f,await Grant(peer,p));f.Identity.Users.Remove("peer");Assert.Equal(HttpStatusCode.Unauthorized,(await fresh.GetAsync(Api+"/agent/context")).StatusCode);
 }
 [Fact]public async Task McpExecutesSameScopedApisAndPreservesWriteDeduplication(){
  await using var f=new AgentFactory();using var owner=f.Client("owner");var p=await Project(owner);using var agent=Agent(f,await Grant(owner,p,"contribute"));
  var init=await Post(agent,"/agent/mcp",new{jsonrpc="2.0",id=1,method="initialize",@params=new{protocolVersion="2025-06-18"}});Assert.Equal("2025-06-18",init.GetProperty("result").GetProperty("protocolVersion").GetString());
  var list=await Post(agent,"/agent/mcp",new{jsonrpc="2.0",id=2,method="tools/list"});Assert.Equal(9,list.GetProperty("result").GetProperty("tools").GetArrayLength());
  var read=await Post(agent,"/agent/mcp",new{jsonrpc="2.0",id=3,method="tools/call",@params=new{name="siyue_projects",arguments=new{}}});Assert.False(read.GetProperty("result").GetProperty("isError").GetBoolean());Assert.Contains(p.ToString(),read.GetRawText());
  var req=Guid.NewGuid().ToString();var call=new{jsonrpc="2.0",id=4,method="tools/call",@params=new{name="siyue_record_action",arguments=new{projectId=p,title="MCP真实进展",requestId=req}}};await Post(agent,"/agent/mcp",call);await Post(agent,"/agent/mcp",call);await f.WithDb(async db=>Assert.Single(await db.TaskActions.ToArrayAsync()));
  agent.DefaultRequestHeaders.Add("Origin","https://untrusted.example");Assert.Equal(HttpStatusCode.Forbidden,(await agent.PostAsJsonAsync(Api+"/agent/mcp",call)).StatusCode);agent.DefaultRequestHeaders.Remove("Origin");
  Assert.Equal(HttpStatusCode.MethodNotAllowed,(await agent.GetAsync(Api+"/agent/mcp")).StatusCode);
  agent.DefaultRequestHeaders.Add("MCP-Protocol-Version","not-supported");Assert.Equal(HttpStatusCode.BadRequest,(await agent.PostAsJsonAsync(Api+"/agent/mcp",call)).StatusCode);
 }
}
