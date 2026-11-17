using System.Security.Cryptography;
using System.ServiceProcess;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Siyue.Agent;
static class RemoteDesktop
{
    static readonly string Owned = Path.Combine(Program.Root,"rustdesk-owned.txt");
    static readonly string Installed = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"RustDesk","rustdesk.exe");
    public static async Task StopManaged(CancellationToken ct) {
        if(File.Exists(Owned)) await Execute(new(Guid.NewGuid(),"desktop-disable",null,null,"",null,null),ct);
    }
    public static async Task<OperationOutcome> Execute(OperationClaim command,CancellationToken ct) {
        if (command.Action=="desktop-disable") {
            if(!File.Exists(Owned))return RemoteOperations.Result("failed","远程桌面不是由本助手管理，未更改现有软件");
            using var s=new ServiceController("RustDesk"); if(s.Status!=ServiceControllerStatus.Stopped){s.Stop();s.WaitForStatus(ServiceControllerStatus.Stopped,TimeSpan.FromSeconds(30));}
            // Disable automatic start as well, so a reboot cannot silently undo the administrator's stop request.
            await ProcessRunner.Run("sc.exe",["config","RustDesk","start=","demand"],TimeSpan.FromSeconds(10),ct);
            return RemoteOperations.Result("succeeded","已停止远程桌面服务；再次启用时会更换连接密码");
        }
        if(string.IsNullOrWhiteSpace(command.DesktopServer)||string.IsNullOrWhiteSpace(command.DesktopKey))return RemoteOperations.Result("failed","服务端尚未配置远程桌面中转地址和公钥");
        if(!Regex.IsMatch(command.DesktopServer,@"^[a-zA-Z0-9.\-]+(:\d{1,5})?$"))return RemoteOperations.Result("failed","远程桌面服务器地址格式无效");
        var vendor=Path.Combine(AppContext.BaseDirectory,"vendor","RustDesk.exe");
        var expected=(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory,"vendor","rustdesk.sha256"),ct)).Trim();
        await using(var f=File.OpenRead(vendor)){if(Convert.ToHexStringLower(await SHA256.HashDataAsync(f,ct))!=expected)throw new IOException("远程桌面组件校验失败");}
        if(File.Exists(Installed)&&!File.Exists(Owned))return RemoteOperations.Result("failed","电脑已有独立安装的 RustDesk。为保留原配置，请现场确认后再接入管理");
        if(!File.Exists(Installed)) {
            File.WriteAllText(Owned,"Managed by Siyue campus assistant; official RustDesk 1.5.0");
            await ProcessRunner.Run(vendor,["--silent-install"],TimeSpan.FromSeconds(75),ct);
            for(var n=0;n<30&&!File.Exists(Installed);n++)await Task.Delay(1000,ct);
            if(!File.Exists(Installed))return RemoteOperations.Result("failed","远程桌面组件安装未完成");
        }
        var services=ServiceController.GetServices(); var exists=services.Any(s=>s.ServiceName.Equals("RustDesk",StringComparison.OrdinalIgnoreCase));foreach(var s in services)s.Dispose();
        if(!exists){await ProcessRunner.Run(Installed,["--install-service"],TimeSpan.FromSeconds(30),ct);await Task.Delay(3000,ct);}
        using(var service=new ServiceController("RustDesk")){if(service.Status!=ServiceControllerStatus.Running){service.Start();service.WaitForStatus(ServiceControllerStatus.Running,TimeSpan.FromSeconds(30));}}
        await ProcessRunner.Run("sc.exe",["config","RustDesk","start=","auto"],TimeSpan.FromSeconds(10),ct);
        var settings=JsonSerializer.SerializeToUtf8Bytes(new {host=command.DesktopServer,key=command.DesktopKey,api="",relay=""});
        var config=new string(Convert.ToBase64String(settings).TrimEnd('=').Replace('+','-').Replace('/','_').Reverse().ToArray());
        await ProcessRunner.Run(Installed,["--config",config],TimeSpan.FromSeconds(15),ct);
        var password=Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16));
        var set=await ProcessRunner.Run(Installed,["--password",password],TimeSpan.FromSeconds(20),ct);
        if(set.Code!=0||!set.Output.Contains("Done!",StringComparison.Ordinal))return RemoteOperations.Result("failed","远程桌面密码设置没有获得成功确认，请通过诊断检查组件服务");
        // RustDesk's Windows service stores its configuration under LocalService (official config::patch).
        using(var service=new ServiceController("RustDesk")) {
            service.Stop();service.WaitForStatus(ServiceControllerStatus.Stopped,TimeSpan.FromSeconds(30));
            try { SetFullAccess(); } finally { service.Start();service.WaitForStatus(ServiceControllerStatus.Running,TimeSpan.FromSeconds(30)); }
        }
        await Task.Delay(2000,ct);
        var id=(await ProcessRunner.Run(Installed,["--get-id"],TimeSpan.FromSeconds(15),ct)).Output.Trim();
        if(!Regex.IsMatch(id,@"^[a-zA-Z0-9_\-]{6,32}$"))return RemoteOperations.Result("failed","未获得有效的远程桌面设备编号");
        return new("succeeded",JsonSerializer.SerializeToElement(new {message="远程桌面已启用；需在控制端 RustDesk 配置同一服务器后连接",remoteId=id,password,server=command.DesktopServer,key=command.DesktopKey,config,component="RustDesk 1.5.0"}));
    }
    static void SetFullAccess() {
        var path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows),"ServiceProfiles","LocalService","AppData","Roaming","RustDesk","config","RustDesk2.toml");
        if(!File.Exists(path))throw new IOException("远程桌面服务配置尚未生成");
        var settings=new Dictionary<string,string> { ["access-mode"]="full",["enable-keyboard"]="Y",["enable-clipboard"]="Y",["enable-file-transfer"]="Y",["enable-remote-restart"]="Y",["approve-mode"]="password",["verification-method"]="use-permanent-password",["allow-auto-record-incoming"]="N" };
        var lines=File.ReadAllLines(path).ToList();var start=lines.FindIndex(x=>x.Trim()=="[options]");
        if(start<0){lines.Add("[options]");start=lines.Count-1;}
        var end=lines.FindIndex(start+1,x=>x.TrimStart().StartsWith("["));if(end<0)end=lines.Count;
        foreach(var pair in settings) {
            var index=lines.FindIndex(start+1,end-start-1,x=>Regex.IsMatch(x,@"^\s*"+Regex.Escape(pair.Key)+@"\s*="));
            var line=pair.Key+" = \""+pair.Value+"\"";
            if(index>=0)lines[index]=line;else{lines.Insert(end,line);end++;}
        }
        File.WriteAllLines(path,lines,new UTF8Encoding(false));
    }

}
