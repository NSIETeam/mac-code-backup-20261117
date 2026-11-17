using System.Diagnostics;
using System.Drawing.Printing;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using System.ServiceProcess;
using Siyue.Printing;

namespace Siyue.Agent;
public record Configuration(string Server, string Token, Guid DeviceId, string? ConnectIp = null);
static class Program
{
    public const string ServiceName = "SiyueCampusAgent";
    public static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "SiyuePrinter");
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public static Configuration? ReadConfig() { var path = Path.Combine(Root, "identity.dat"); return File.Exists(path) ? JsonSerializer.Deserialize<Configuration>(ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.LocalMachine), Json) : null; }
    public static void SaveConfig(Configuration config) { var path = Path.Combine(Root, "identity.dat"); var temporary = path + ".tmp"; File.WriteAllBytes(temporary, ProtectedData.Protect(JsonSerializer.SerializeToUtf8Bytes(config, Json), null, DataProtectionScope.LocalMachine)); File.Move(temporary, path, true); }
    [STAThread] static void Main(string[] args)
    {
        Directory.CreateDirectory(Root);
        if (args.Contains("--service")) { ServiceBase.Run(new ResidentService()); return; }
        using var mutex = new Mutex(true, "Global\\SiyueCampusAgentSettings", out var first); if (!first) return;
        ApplicationConfiguration.Initialize();
        if (!ServiceController.GetServices().Any(s => s.ServiceName == ServiceName)) {
            var installer = Path.Combine(AppContext.BaseDirectory, "Install.ps1");
            if (!File.Exists(installer)) { MessageBox.Show("请完整解压安装包，然后运行安装程序。", "思跃校区打印助手"); return; }
            var info = new ProcessStartInfo("powershell.exe") { UseShellExecute = true }; foreach (var arg in new[] { "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", installer }) info.ArgumentList.Add(arg); Process.Start(info); return;
        }
        Application.Run(new ManagementForm());
    }
}
sealed class SetupForm : Form
{
    readonly TextBox server = new() { PlaceholderText = "https://你的系统域名", Width = 350 };
    readonly TextBox connectIp = new() { PlaceholderText = "可选：指定公网 IP，留空使用域名解析", Width = 350 };
    readonly TextBox code = new() { PlaceholderText = "也可输入小程序生成的设备绑定码", Width = 350 };
    readonly TextBox name = new() { Text = Environment.MachineName, Width = 350 };
    readonly Label status = new() { AutoSize = true, MaximumSize = new Size(350, 110) };
    readonly CancellationTokenSource stop = new();
    public Configuration? Result { get; private set; }
    public SetupForm(Configuration? current = null)
    {
        server.Text = current?.Server ?? "https://www.siyuejn.com/miniapp/"; connectIp.Text = current?.ConnectIp ?? "";
        Text = "思跃 · 连接校区打印助手"; Width = 450; Height = 620; StartPosition = FormStartPosition.CenterScreen;
        var layout = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, Padding = new Padding(25), WrapContents = false };
        layout.Controls.AddRange([new Label { Text = "服务器地址", AutoSize = true }, server, new Label { Text = "连接公网 IP（可选，保留 HTTPS 域名校验）", AutoSize = true }, connectIp, new Label { Text = "设备名称", AutoSize = true }, name]);
        var connect = new Button { Text = "获取连接码，在小程序一键连接", Width = 350, Height = 40 }; layout.Controls.Add(connect); layout.Controls.Add(status);
        layout.Controls.Add(code); var bind = new Button { Text = "使用旧绑定码连接", Width = 350, Height = 35 }; layout.Controls.Add(bind); Controls.Add(layout);
        connect.Click += async (_, _) => {
            connect.Enabled = false; bind.Enabled = false;
            try {
                var endpoint = new ServerEndpoint(server.Text, connectIp.Text); using var http = endpoint.CreateClient(); server.Enabled = false; connectIp.Enabled = false; name.Enabled = false;
                using var response = await http.PostAsJsonAsync("agent/pairings", new { name = name.Text.Trim() }, stop.Token); response.EnsureSuccessStatusCode();
                var pairing = await response.Content.ReadFromJsonAsync<JsonElement>(stop.Token);
                var connectionCode = pairing.GetProperty("code").GetString()!;
                status.Text = "连接码：" + connectionCode + "（10分钟有效）\n请在小程序「机构管理 → 校区设备」中选择校区，输入此码并点击「一键连接」。连接后在小程序启用打印机。";
                http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", pairing.GetProperty("pollToken").GetString());
                while (!stop.IsCancellationRequested) {
                    await Task.Delay(2000, stop.Token); using var poll = await http.GetAsync("agent/pairings/status", stop.Token);
                    if (poll.StatusCode == HttpStatusCode.Gone) throw new Exception("连接码已过期，请重新获取"); poll.EnsureSuccessStatusCode();
                    var result = await poll.Content.ReadFromJsonAsync<JsonElement>(stop.Token);
                    if (result.GetProperty("state").GetString() != "connected") continue;
                    Result = new Configuration(endpoint.BaseUri.AbsoluteUri, pairing.GetProperty("deviceToken").GetString()!, result.GetProperty("id").GetGuid(), endpoint.ConnectAddress?.ToString()); DialogResult = DialogResult.OK; Close(); return;
                }
            } catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
            catch (Exception e) { status.Text = "连接失败：" + e.Message; }
            finally { if (!IsDisposed) { connect.Enabled = true; bind.Enabled = true; server.Enabled = true; connectIp.Enabled = true; name.Enabled = true; } }
        };
        bind.Click += async (_, _) => { bind.Enabled = false; connect.Enabled = false; try {
            var endpoint = new ServerEndpoint(server.Text, connectIp.Text); using var http = endpoint.CreateClient(); server.Enabled = false; connectIp.Enabled = false; name.Enabled = false; using var response = await http.PostAsJsonAsync("agent/bind", new { code = code.Text.Trim(), name = name.Text.Trim() }, stop.Token);
            if (!response.IsSuccessStatusCode) throw new Exception("绑定失败，请检查地址、绑定码和校区配置");
            var r = await response.Content.ReadFromJsonAsync<JsonElement>(stop.Token); Result = new Configuration(endpoint.BaseUri.AbsoluteUri, r.GetProperty("token").GetString()!, r.GetProperty("id").GetGuid(), endpoint.ConnectAddress?.ToString()); DialogResult = DialogResult.OK; Close();
        } catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
        catch (Exception e) { status.Text = e.Message; } finally { if (!IsDisposed) { bind.Enabled = true; connect.Enabled = true; server.Enabled = true; connectIp.Enabled = true; name.Enabled = true; } } };
        FormClosed += (_, _) => stop.Cancel();
    }
}
sealed class HttpTransport : ITransport, IDisposable
{
    readonly Configuration config; readonly HttpClient http; readonly ServerEndpoint endpoint; readonly string workDirectory; readonly ExecutionMonitor? monitor;
    public HttpTransport(Configuration c, ExecutionMonitor? monitor = null) { this.monitor = monitor; config = c; endpoint = new ServerEndpoint(c.Server, c.ConnectIp); http = endpoint.CreateClient(); http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", c.Token); workDirectory = Path.Combine(Program.Root, endpoint.Scope(c.DeviceId)); Directory.CreateDirectory(workDirectory); }
    public async Task Heartbeat(ProbeResult probe, CancellationToken ct) { using var r = await http.PostAsJsonAsync("agent/heartbeat", new { probe.Printers, probe.Complete, probe.Error, agentVersion = typeof(Program).Assembly.GetName().Version!.ToString(3), machineName = Environment.MachineName, monitorVersion = ExecutionTelemetry.Version, monitorError = monitor?.Error, executions = monitor?.Snapshot() ?? [] }, ct); r.EnsureSuccessStatusCode(); }
    public async Task<Claim?> Claim(CancellationToken ct) { using var r = await http.PostAsJsonAsync("agent/claim", new { }, ct); if (r.StatusCode == HttpStatusCode.NoContent) return null; r.EnsureSuccessStatusCode(); return await r.Content.ReadFromJsonAsync<Claim>(Program.Json, ct); }
    public async Task Begin(Claim job, CancellationToken ct) { using var r = await http.PostAsJsonAsync($"agent/jobs/{job.Id}/begin", new { job.ClaimToken, state = "submitting" }, ct); r.EnsureSuccessStatusCode(); }
    public async Task Report(Claim job, Outcome outcome, CancellationToken ct) { using var r = await http.PostAsJsonAsync($"agent/jobs/{job.Id}/report", new { job.ClaimToken, outcome.State, outcome.Detail }, ct); if (r.StatusCode != HttpStatusCode.Conflict) r.EnsureSuccessStatusCode(); }
    public async Task<string> Download(Claim job, CancellationToken ct)
    {
        var uri = new Uri(job.PdfUrl); if (!endpoint.AllowsFile(uri)) throw new PrintCheckException("文件地址与当前服务器不匹配");
        var path = Path.Combine(workDirectory, job.Id + ".pdf");
        try { using var response = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct); response.EnsureSuccessStatusCode();
            await using (var input = await response.Content.ReadAsStreamAsync(ct)) await using (var output = File.Create(path)) { var buffer = new byte[65536]; long total = 0; int n; while ((n = await input.ReadAsync(buffer, ct)) > 0) { total += n; if (total > 209_715_200) throw new Exception("文档过大"); await output.WriteAsync(buffer.AsMemory(0, n), ct); } }
            await using var f = File.OpenRead(path); if (!Convert.ToHexStringLower(await SHA256.HashDataAsync(f, ct)).Equals(job.Sha256, StringComparison.OrdinalIgnoreCase)) throw new PrintCheckException("下载文件的 SHA-256 与服务器不一致"); return path;
        } catch { try { File.Delete(path); } catch { } throw; }
    }
    public void Dispose() => http.Dispose();
}
sealed class NativePrintExecutor(ExecutionMonitor? monitor = null) : IExecutor
{
    readonly string executable = Path.Combine(AppContext.BaseDirectory, "vendor", "NativePrint.exe");
    public async Task Validate(Claim job, CancellationToken ct) {
        if (job.Copies is < 1 or > 99) throw new PrintCheckException("打印份数无效");
        if (!PrinterSettings.InstalledPrinters.Cast<string>().Contains(job.QueueName)) throw new PrintCheckException("系统服务账户找不到指定打印队列");
        if (job.Duplex && !new PrinterSettings { PrinterName = job.QueueName }.CanDuplex) throw new PrintCheckException("打印机不支持双面");
        foreach (var component in new[] { ("NativePrint.exe", "native-print.sha256"), ("pdfium.dll", "pdfium.sha256") }) {
            var path = Path.Combine(AppContext.BaseDirectory, "vendor", component.Item1);
            var manifest = Path.Combine(AppContext.BaseDirectory, "vendor", component.Item2);
            if (!File.Exists(path) || !File.Exists(manifest)) throw new PrintCheckException("原生打印组件或校验清单缺失，请检查隔离记录或修复安装");
            var hash = (await File.ReadAllTextAsync(manifest, ct)).Trim().Split(' ')[0]; await using var file = File.OpenRead(path);
            if (hash.Length != 64 || !Convert.ToHexStringLower(await SHA256.HashDataAsync(file, ct)).Equals(hash, StringComparison.OrdinalIgnoreCase)) throw new PrintCheckException("原生打印组件 SHA-256 校验失败，未运行组件");
        }
    }
    public async Task<Outcome> Execute(Claim job, string file, CancellationToken ct)
    {
        monitor?.Preparing(job);
        var evidence = new SpoolerEvidence(); using var observeStop = new CancellationTokenSource();
        var watch = Task.Run(() => evidence.Watch(job.QueueName, job.Id, observeStop.Token));
        try {
        var info = new ProcessStartInfo(executable) { UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in new[] { "-new-window", "-silent", "-print-to", job.QueueName, "-print-settings", $"paper=A4,shrink,{(job.Duplex ? "duplexlong" : "simplex")},{job.Copies}x", file }) info.ArgumentList.Add(a);
        Process? p; try { p = Process.Start(info); if (p == null) throw new IOException("无法启动打印组件"); } catch (Exception e) { monitor?.LaunchFailed(job.Id,e.HResult); return new Outcome("failed", "无法启动打印组件，未提交打印；系统错误码 0x"+e.HResult.ToString("X8")+"，请检查安全软件拦截和组件文件"); }
        monitor?.Started(job.Id,p.Id);
        using (p) { using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromMinutes(3)); try { await p.WaitForExitAsync(timeout.Token); monitor?.Exited(job.Id,p.ExitCode); } catch { try { p.Kill(true); } catch { } monitor?.Interrupted(job.Id); return new Outcome("uncertain", "打印组件中断或超时，请现场核实"); }
            await Task.Delay(1500, ct); observeStop.Cancel(); await watch.WaitAsync(TimeSpan.FromSeconds(3), ct);
            monitor?.ObservedJobs(job.Id,evidence.JobIds);
            return PrintEvidence.Evaluate(p.ExitCode, evidence.Observed, evidence.Flags, evidence.JobIds, evidence.Failed); }
        } finally { observeStop.Cancel(); }
    }
}
