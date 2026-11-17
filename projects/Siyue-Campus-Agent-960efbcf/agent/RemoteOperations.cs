using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Siyue.Printing;
namespace Siyue.Agent;
record OperationClaim(Guid Id, string Action, string? QueueName, int? JobId, string ClaimToken, string? DesktopServer, string? DesktopKey);
record OperationOutcome(string State, JsonElement Result);
sealed class RemoteOperations(Configuration config, SemaphoreSlim gate) : IDisposable
{
    readonly HttpClient http = Create(config);
    readonly string directory = Path.Combine(Program.Root, new ServerEndpoint(config.Server, config.ConnectIp).Scope(config.DeviceId), "operations");
    static HttpClient Create(Configuration c) { var h = new ServerEndpoint(c.Server, c.ConnectIp).CreateClient(); h.DefaultRequestHeaders.Authorization = new("Bearer", c.Token); return h; }
    record Pending(OperationClaim Claim, OperationOutcome? Outcome);
    public async Task Run(CancellationToken ct) {
        Directory.CreateDirectory(directory);
        while (!ct.IsCancellationRequested) {
            try {
                foreach(var updating in Directory.GetFiles(directory,"*.updating")) if(File.GetLastWriteTimeUtc(updating)<DateTime.UtcNow.AddMinutes(-15)) File.Move(updating,Path.ChangeExtension(updating,"pending"),true);
                // The protected machine directory contains only our operations. No command is executed twice.
                foreach (var file in Directory.GetFiles(directory, "*.pending")) {
                    var pending = Load(file);
                    var outcome = pending.Outcome ?? Result("uncertain", "电脑在执行操作时中断；没有自动重做，请先诊断当前状态");
                    await Report(pending.Claim, outcome, ct); File.Delete(file);
                }
                if(Directory.GetFiles(directory,"*.updating").Length>0){await Task.Delay(15000,ct);continue;}
                using var response = await http.PostAsJsonAsync("agent/operations/claim", new {}, ct);
                if (response.StatusCode != HttpStatusCode.NoContent) {
                    response.EnsureSuccessStatusCode(); var command = (await response.Content.ReadFromJsonAsync<OperationClaim>(Program.Json, ct))!;
                    var path = Path.Combine(directory, command.Id + ".pending");
                    Save(path, new Pending(command, null));
                    await gate.WaitAsync(ct);
                    OperationOutcome outcome;
                    try { outcome = command.Action=="install-update" ? await AgentUpdate.Stage(http,command,path,ct) : await Execute(command, ct); }
                    catch (Exception e) { outcome = Result("uncertain", "远程操作未获得完整结果：" + e.GetType().Name + "，错误码 0x" + e.HResult.ToString("X8") + (e.InnerException is System.ComponentModel.Win32Exception w ? "，Windows 错误 " + w.NativeErrorCode : "") + "；请先诊断再决定下一步"); }
                    finally { gate.Release(); }
                    if(outcome.State=="deferred"){await Task.Delay(15000,ct);continue;}
                    Save(path, new Pending(command, outcome)); await Report(command, outcome, ct); File.Delete(path);
                    // Last local report is useful even when the server becomes unreachable. Never write desktop credentials here.
                    if (!command.Action.StartsWith("desktop-")) File.WriteAllText(Path.Combine(Program.Root,"last-diagnostic.json"), outcome.Result.GetRawText());
                }
            } catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch { /* Retain the receipt and retry reporting, not executing. */ }
            await Task.Delay(15000, ct);
        }
    }
    void Save(string path, Pending pending) {
        // Desktop passwords never remain as plaintext in the journal.
        var json = JsonSerializer.Serialize(pending, Program.Json);
        var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(json), null, DataProtectionScope.LocalMachine);
        File.WriteAllBytes(path + ".tmp", bytes); File.Move(path + ".tmp", path, true);
    }
    Pending Load(string file) => JsonSerializer.Deserialize<Pending>(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(file),null,DataProtectionScope.LocalMachine)),Program.Json)!;
    async Task Report(OperationClaim c, OperationOutcome o, CancellationToken ct) { using var r = await http.PostAsJsonAsync($"agent/operations/{c.Id}/report", new { c.ClaimToken, o.State, o.Result }, ct); if (r.StatusCode != HttpStatusCode.Conflict) r.EnsureSuccessStatusCode(); }
    async Task<OperationOutcome> Execute(OperationClaim c, CancellationToken ct) {
        if (c.Action is "desktop-enable" or "desktop-disable") return await RemoteDesktop.Execute(c, ct);
        if (!new[] {"diagnose","enable-print-log","start-spooler","restart-spooler","resume-printer","cancel-job"}.Contains(c.Action)) return Result("failed", "此版本不支持该操作");
        var input = Path.Combine(directory, c.Id + ".input.json"); await File.WriteAllTextAsync(input,JsonSerializer.Serialize(new { c.Action,c.QueueName,c.JobId },Program.Json),ct);
        try {
            var (code, output) = await ProcessRunner.Run("powershell.exe", ["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",Path.Combine(AppContext.BaseDirectory,"operations","PrinterOperations.ps1"),"-InputFile",input],TimeSpan.FromSeconds(60),ct);
            var resultLine=output.Split('\n',StringSplitOptions.RemoveEmptyEntries).LastOrDefault(x=>x.TrimStart().StartsWith("{")) ?? throw new IOException("诊断未返回结构化结果");
            var result=JsonSerializer.Deserialize<OperationOutcome>(resultLine, Program.Json); return result ?? Result("failed","没有获得诊断结果");
        } finally {File.Delete(input);}
    }
    public static OperationOutcome Result(string state,string message) => new(state,JsonSerializer.SerializeToElement(new {message}));
    public void Dispose()=>http.Dispose();
}
static class ProcessRunner
{
    public static async Task<(int Code,string Output)> Run(string executable,string[] args,TimeSpan limit,CancellationToken ct) {
        var info=new ProcessStartInfo(executable) { UseShellExecute=false, CreateNoWindow=true, RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8 };
        foreach(var arg in args)info.ArgumentList.Add(arg);
        using var p=Process.Start(info) ?? throw new IOException("组件无法启动");
        var output=new StringBuilder(); var errors=new StringBuilder();
        var readOutput=Read(p.StandardOutput,output);var readErrors=Read(p.StandardError,errors);
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(limit);
        try {await p.WaitForExitAsync(timeout.Token);} catch {try{p.Kill(true);}catch{}throw;}
        // Installers may leave inherited pipes open in a resident child. Once the invoked
        // process exits, collect its final output without waiting for that child's lifetime.
        await Task.WhenAny(Task.WhenAll(readOutput,readErrors),Task.Delay(500,ct));
        string snapshot;lock(output)snapshot=output.ToString();
        p.StandardOutput.Close();p.StandardError.Close();
        return (p.ExitCode,snapshot);
        static async Task Read(StreamReader reader,StringBuilder result) {
            try {var b=new char[4096];int n;while((n=await reader.ReadAsync(b))>0)lock(result)if(result.Length<65536)result.Append(b,0,Math.Min(n,65536-result.Length));}
            catch(ObjectDisposedException){}catch(IOException){}
        }
    }
}
