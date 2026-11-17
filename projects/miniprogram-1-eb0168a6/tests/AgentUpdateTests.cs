using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;
public class AgentUpdateTests
{
 [Fact] public async Task UpdateRequiresDeviceAuthenticationAndRetriesKeepOriginalOperationAfterUpgrade()
 {
  await using var f=new Factory();var dir=Path.Combine(f.Root,"updates");Directory.CreateDirectory(dir);
  await File.WriteAllBytesAsync(Path.Combine(dir,"setup.exe"),[1,2,3]);
  await File.WriteAllTextAsync(Path.Combine(dir,"release.json"),JsonSerializer.Serialize(new AgentUpdateManifest("2.1.1",new string('a',64),"setup.exe",3)));
  using var app=f.WithWebHostBuilder(b=>b.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{{"Updates:Directory",dir}})));
  using var admin=await f.Login("update-admin",true); // Same fixture database; create a client bound to the configured application.
  using var manager=app.CreateClient();manager.DefaultRequestHeaders.Authorization=admin.DefaultRequestHeaders.Authorization;
  using var agent=app.CreateClient();
  Assert.Equal(HttpStatusCode.Unauthorized,(await agent.GetAsync("/agent/updates/package")).StatusCode);
  var token=Secrets.New();var d=new Device{Name="Update test",TokenHash=Secrets.Hash(token),AgentVersion="2.1.0",LastSeen=Clock.Now};
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.Add(d);await db.SaveChangesAsync();}
  agent.DefaultRequestHeaders.Authorization=new("Bearer",token);
  Assert.Equal(new byte[]{1,2,3},await agent.GetByteArrayAsync("/agent/updates/package"));
  var path=$"/api/admin/devices/{d.Id}/operations";var request=new{action="install-update",acknowledge=true,requestKey="update-once-test"};
  var op=await Factory.Post(manager,path,request);
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();var device=await db.Devices.FindAsync(d.Id);device!.AgentVersion="2.1.1";await db.SaveChangesAsync();}
  var duplicate=await Factory.Post(manager,path,request);Assert.Equal(op.GetProperty("id").GetGuid(),duplicate.GetProperty("id").GetGuid());
  Assert.Equal(HttpStatusCode.Conflict,(await manager.PostAsJsonAsync(path,new{action="install-update",acknowledge=true,requestKey="already-updated"})).StatusCode);
 }
 [Fact] public async Task ScopedPatchIsBoundToAuthenticatedDeviceAndDoesNotChangeOtherDevices()
 {
  await using var f=new Factory();var dir=Path.Combine(f.Root,"scoped-updates");Directory.CreateDirectory(dir);
  var chosen=new Device{Name="Chosen",TokenHash=Secrets.Hash("chosen-token"),AgentVersion="2.1.1",LastSeen=Clock.Now};
  var other=new Device{Name="Other",TokenHash=Secrets.Hash("other-token"),AgentVersion="2.1.1",LastSeen=Clock.Now};
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(chosen,other);await db.SaveChangesAsync();}
  void Package(string folder,string version,byte[] data){Directory.CreateDirectory(folder);File.WriteAllBytes(Path.Combine(folder,"setup.exe"),data);File.WriteAllText(Path.Combine(folder,"release.json"),JsonSerializer.Serialize(new AgentUpdateManifest(version,new string('a',64),"setup.exe",data.Length)));}
  Package(dir,"2.1.3",[1]);Package(Path.Combine(dir,"devices",chosen.Id.ToString()),"2.1.4",[2,3]);
  using var app=f.WithWebHostBuilder(b=>b.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{{"Updates:Directory",dir}})));
  using var a=app.CreateClient();a.DefaultRequestHeaders.Authorization=new("Bearer","chosen-token");
  using var b=app.CreateClient();b.DefaultRequestHeaders.Authorization=new("Bearer","other-token");
  Assert.Equal(new byte[]{2,3},await a.GetByteArrayAsync("/agent/updates/package"));
  Assert.Equal(new byte[]{1},await b.GetByteArrayAsync("/agent/updates/package?deviceId="+chosen.Id));
  Assert.Equal("2.1.4",(await a.GetFromJsonAsync<AgentUpdateManifest>("/agent/updates/current"))!.Version);
  Assert.Equal("2.1.3",(await b.GetFromJsonAsync<AgentUpdateManifest>("/agent/updates/current?deviceId="+chosen.Id))!.Version);
 }
 [Fact] public async Task InstallerDownloadIsAdminOnlySupportsRangesAndRevokesWithAdmin()
 {
  await using var f=new Factory();var dir=Path.Combine(f.Root,"installer");Directory.CreateDirectory(dir);
  await File.WriteAllBytesAsync(Path.Combine(dir,"setup.exe"),[1,2,3,4]);
  await File.WriteAllTextAsync(Path.Combine(dir,"release.json"),JsonSerializer.Serialize(new AgentUpdateManifest("2.1.5",new string('b',64),"setup.exe",4)));
  using var app=f.WithWebHostBuilder(b=>b.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{{"Updates:Directory",dir}})));
  using var admin=await f.Login("installer-admin",true);using var manager=app.CreateClient();manager.DefaultRequestHeaders.Authorization=admin.DefaultRequestHeaders.Authorization;
  using var browser=app.CreateClient();Assert.Equal(HttpStatusCode.Unauthorized,(await browser.PostAsJsonAsync("/api/admin/installer-download",new{})).StatusCode);
  var m=await Factory.Post(manager,"/api/admin/installer-download",new{});var url=m.GetProperty("url").GetString()!;
  Assert.Equal(new byte[]{1,2,3,4},await browser.GetByteArrayAsync(url));
  using var request=new HttpRequestMessage(HttpMethod.Get,url);request.Headers.Range=new(1,2);using var range=await browser.SendAsync(request);Assert.Equal(HttpStatusCode.PartialContent,range.StatusCode);Assert.Equal(new byte[]{2,3},await range.Content.ReadAsByteArrayAsync());
  Assert.Equal(HttpStatusCode.NotFound,(await browser.GetAsync(url.Replace("/installer-download/","/files/"))).StatusCode);
  using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();var staff=db.Staff.Single(x=>x.OpenId=="development:installer-admin");staff.Enabled=false;await db.SaveChangesAsync();}
  Assert.Equal(HttpStatusCode.Forbidden,(await browser.GetAsync(url)).StatusCode);
 }
 [Theory][InlineData("../outside.exe",3)][InlineData("setup.exe",4)][InlineData("",3)]
 public void RejectsUnavailableOrUnsafePackage(string name,long size)
 {
  var dir=Path.Combine(Path.GetTempPath(),Guid.NewGuid().ToString());Directory.CreateDirectory(dir);
  try {File.WriteAllBytes(Path.Combine(dir,"setup.exe"),[1,2,3]);File.WriteAllText(Path.Combine(dir,"release.json"),JsonSerializer.Serialize(new AgentUpdateManifest("2.1.1",new string('a',64),name,size)));var config=new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?>{{"Updates:Directory",dir}}).Build();Assert.Throws<ApiException>(()=>AgentUpdates.Read(config));}
  finally {Directory.Delete(dir,true);}
 }
}
