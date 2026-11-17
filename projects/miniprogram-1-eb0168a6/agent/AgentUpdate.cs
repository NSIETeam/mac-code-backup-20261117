using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
namespace Siyue.Agent;
record UpdateManifest(string Version,string Sha256,string FileName,long Size);
static class AgentUpdate
{
    public static async Task<OperationOutcome> Stage(HttpClient http,OperationClaim c,string pending,CancellationToken ct) {
        var manifest=await http.GetFromJsonAsync<UpdateManifest>("agent/updates/current",Program.Json,ct);
        if(manifest==null||!Version.TryParse(manifest.Version,out var version)||version<=typeof(Program).Assembly.GetName().Version||manifest.Size<=0||manifest.Size>314572800||string.IsNullOrEmpty(manifest.Sha256)||manifest.Sha256.Length!=64||!manifest.Sha256.All(Uri.IsHexDigit))return RemoteOperations.Result("failed","没有更新版本，或安装包清单无效");
        var root=Path.Combine(Program.Root,"updates",c.Id.ToString());Directory.CreateDirectory(root);
        var installer=Path.Combine(root,"setup.exe");
        using(var downloadTimeout=CancellationTokenSource.CreateLinkedTokenSource(ct)) {
            downloadTimeout.CancelAfter(TimeSpan.FromMinutes(5));
            using var response=await http.GetAsync("agent/updates/package",HttpCompletionOption.ResponseHeadersRead,downloadTimeout.Token);response.EnsureSuccessStatusCode();
            await using var input=await response.Content.ReadAsStreamAsync(downloadTimeout.Token);await using var output=File.Create(installer);var buffer=new byte[65536];long length=0;int n;
            while((n=await input.ReadAsync(buffer,downloadTimeout.Token))>0){length+=n;if(length>manifest.Size)throw new IOException("更新包大小不符");await output.WriteAsync(buffer.AsMemory(0,n),downloadTimeout.Token);}
            if(length!=manifest.Size)throw new IOException("更新包未完整下载");
        }
        await using(var file=File.OpenRead(installer)){if(!Convert.ToHexStringLower(await SHA256.HashDataAsync(file,ct)).Equals(manifest.Sha256,StringComparison.OrdinalIgnoreCase))return RemoteOperations.Result("failed","更新包校验失败，未运行安装");}
        // Copy the helper out of Program Files so the installer can replace the application safely.
        var helper=Path.Combine(root,"ApplyUpdate.ps1");File.Copy(Path.Combine(AppContext.BaseDirectory,"operations","ApplyUpdate.ps1"),helper,true);
        var updating=Path.ChangeExtension(pending,"updating");File.Move(pending,updating,true);
        var request=Path.Combine(root,"request.json");await File.WriteAllTextAsync(request,JsonSerializer.Serialize(new{installer,version=manifest.Version,sha256=manifest.Sha256,updating,pending},Program.Json),ct);
        try {
            var info=new System.Diagnostics.ProcessStartInfo("powershell.exe"){UseShellExecute=false,CreateNoWindow=true};foreach(var a in new[]{"-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",helper,"-Request",request})info.ArgumentList.Add(a);
            using var process=System.Diagnostics.Process.Start(info)??throw new IOException("无法启动更新程序");
        }catch{File.Move(updating,pending,true);throw;}
        return RemoteOperations.Result("deferred","安装程序已启动，等待新版本实际启动后回报");
    }
}
