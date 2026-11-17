using System.ComponentModel;
using System.Diagnostics;
using System.Drawing.Printing;
using System.Runtime.InteropServices;
using System.ServiceProcess;
using System.Text.Json;
using Siyue.Printing;
namespace Siyue.Agent;

sealed class ResidentService : ServiceBase
{
    CancellationTokenSource? stop; Task? runner;
    public ResidentService() { ServiceName = Program.ServiceName; CanShutdown = true; }
    protected override void OnStart(string[] args) { stop = new(); runner = Task.Run(() => Run(stop.Token)); }
    async Task Run(CancellationToken ct)
    {
        try {
            while (!ct.IsCancellationRequested) {
                try {
                    var config = Program.ReadConfig();
                    if (config != null) { using var runtime = new AgentRuntime(config); await runtime.Run(ct); }
                    else await AutomaticEnrollment.Connect(ct);
                } catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
                catch (Exception e) { AgentRuntime.WriteStatus("助手正在重试：" + e.GetType().Name, null, null); }
                await Task.Delay(5000, ct);
            }
        } catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
    }
    protected override void OnStop() { RequestAdditionalTime(60000); stop?.Cancel(); if (runner != null && !runner.Wait(TimeSpan.FromSeconds(55))) Environment.FailFast("打印助手停止超时，保留任务日志供重启核实"); stop?.Dispose(); }
    protected override void OnShutdown() => OnStop();
}

sealed class AgentRuntime : IDisposable
{
    readonly HttpTransport transport; readonly Journal journal; readonly PrintEngine engine; readonly Configuration config; readonly ExecutionMonitor monitor;
    readonly SemaphoreSlim operationGate = new(1,1);
    Task<ProbeResult>? probeTask; DateTimeOffset? lastContact; volatile ProbeResult? latest;
    public AgentRuntime(Configuration c) { config = c; var dir = Path.Combine(Program.Root, new ServerEndpoint(c.Server, c.ConnectIp).Scope(c.DeviceId)); Directory.CreateDirectory(dir); monitor = new(Path.Combine(dir,"execution")); transport = new(c,monitor); journal = new(Path.Combine(dir, "jobs.db")); engine = new(journal, transport, new NativePrintExecutor(monitor)); }
    public async Task Run(CancellationToken ct)
    {
        // The two loops never wait for each other: printing may take minutes, heartbeats must continue.
        using var operations = new RemoteOperations(config, operationGate);
        await Task.WhenAll(Task.Run(() => Heartbeats(ct), ct), Task.Run(() => Jobs(ct), ct), Task.Run(() => monitor.Run(ct),ct), operations.Run(ct));
    }
    async Task Heartbeats(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested) {
            try {
                probeTask ??= Task.Run(WindowsPrinters.Read);
                ProbeResult probe;
                try { probe = await probeTask.WaitAsync(TimeSpan.FromSeconds(4), ct); probeTask = null; }
                catch (System.TimeoutException) { probe = new([], false, "Windows 打印服务响应超时，正在重试"); }
                catch (Exception) when (!ct.IsCancellationRequested) { probeTask = null; probe = new([], false, "读取打印机失败，请检查 Windows 打印服务"); }
                try { probe = PrinterConnectionMonitor.Enrich(probe); } catch { /* Queue telemetry remains usable if a port cannot be inspected. */ }
                monitor.ObservePrinters(probe);
                latest = probe;
                await transport.Heartbeat(probe, ct); lastContact = DateTimeOffset.UtcNow;
                WriteStatus("已连接服务端", config, new { lastContact, probe });
            } catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (HttpRequestException e) when (e.StatusCode == System.Net.HttpStatusCode.Unauthorized) {
                latest = null; try { await RemoteDesktop.StopManaged(ct); } catch { }
                WriteStatus("设备授权已失效，请管理员检查；远程桌面已请求关闭",config,null);
            }
            catch (Exception e) { latest = null; WriteStatus("连接中断，自动重连：" + e.GetType().Name, config, new { lastContact }); }
            await Task.Delay(5000, ct);
        }
    }
    async Task Jobs(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested) {
            try { await operationGate.WaitAsync(ct); try { var operationsDir=Path.Combine(Program.Root,new ServerEndpoint(config.Server,config.ConnectIp).Scope(config.DeviceId),"operations"); if(!Directory.Exists(operationsDir)||!Directory.EnumerateFiles(operationsDir,"*.updating").Any()) { if (latest?.Complete == true) await engine.Tick(ct); else await engine.Recover(ct); } } finally { operationGate.Release(); } }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch { /* Keep the journal and retry without duplicating a submitted job. */ }
            await Task.Delay(3000, ct);
        }
    }
    public static void WriteStatus(string message, Configuration? config, object? detail)
    {
        try { var path = Path.Combine(Program.Root, "status.json"); File.WriteAllText(path + ".tmp", JsonSerializer.Serialize(new { message, server = config?.Server, connectIp = config?.ConnectIp, updatedAt = DateTimeOffset.UtcNow, detail }, Program.Json)); File.Move(path + ".tmp", path, true); } catch { }
    }
    public void Dispose() { monitor.Dispose(); transport.Dispose(); journal.Dispose(); }
}

static class WindowsPrinters
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct PrinterInfo {
        public string? ServerName, PrinterName, ShareName, PortName, DriverName, Comment, Location;
        public IntPtr DevMode; public string? SeparatorFile, PrintProcessor, Datatype, Parameters; public IntPtr SecurityDescriptor;
        public uint Attributes, Priority, DefaultPriority, StartTime, UntilTime, Status, Jobs, AveragePages;
    }
    [DllImport("winspool.drv", EntryPoint = "EnumPrintersW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)] static extern bool EnumPrinters(uint flags, string? name, uint level, IntPtr buffer, uint size, out uint needed, out uint returned);
    public static ProbeResult Read()
    {
        // Machine-installed queues are visible to the SYSTEM service; user-only network connections are not.
        var ok = EnumPrinters(2 | 4, null, 2, IntPtr.Zero, 0, out var needed, out _);
        if (needed == 0) { if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error()); return new([], true); }
        if (needed > 32 * 1024 * 1024) throw new InvalidOperationException("打印队列信息过大");
        var buffer = Marshal.AllocHGlobal((int)needed);
        try {
            if (!EnumPrinters(2 | 4, null, 2, buffer, needed, out _, out var count)) throw new Win32Exception(Marshal.GetLastWin32Error());
            if (count > 100) throw new InvalidOperationException("打印队列超过上限");
            var list = new List<PrinterTelemetry>(); var size = Marshal.SizeOf<PrinterInfo>();
            for (var i = 0; i < count; i++) {
                var p = Marshal.PtrToStructure<PrinterInfo>(IntPtr.Add(buffer, i * size)); if (string.IsNullOrWhiteSpace(p.PrinterName)) continue;
                if (InstallationProfile.IsMicrosoftPdfPrinter(p.PrinterName, p.DriverName)) continue;
                var (state, detail) = SpoolerStatus.Describe(p.Status); bool duplex; try { duplex = new PrinterSettings { PrinterName = p.PrinterName }.CanDuplex; } catch { duplex = false; }
                list.Add(new(p.PrinterName, duplex, state, p.Status, (int)Math.Min(p.Jobs, int.MaxValue), detail, p.DriverName ?? "", p.PortName ?? ""));
            }
            return new(list.ToArray(), true);
        } finally { Marshal.FreeHGlobal(buffer); }
    }
}

sealed class ManagementForm : Form
{
    readonly TextBox status = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Width = 550, Height = 220 };
    readonly System.Windows.Forms.Timer timer = new() { Interval = 3000 };
    readonly Button configure = new() { Text = "连接校区 / 修改服务端地址与 IP", Width = 550, Height = 42 };
    public ManagementForm()
    {
        Text = "思跃 · 校区打印助手设置"; Width = 620; Height = 610; StartPosition = FormStartPosition.CenterScreen;
        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, Padding = new Padding(20), WrapContents = false };
        panel.Controls.Add(new Label { Text = "后台服务开机启动，关闭此窗口后仍接收打印任务。", AutoSize = true }); panel.Controls.Add(status); panel.Controls.Add(configure);
        panel.Controls.Add(new Label { Text = "启动后自动登记。在小程序为待分配电脑选择校区即可。", AutoSize = true });
        var restart = new Button { Text = "重新启动后台服务", Width = 550, Height = 35 }; panel.Controls.Add(restart); Controls.Add(panel);
        var diagnose = new Button { Text = "本机诊断并查看报告", Width = 550, Height = 35 }; panel.Controls.Add(diagnose);
        diagnose.Click += async (_, _) => { diagnose.Enabled=false;try {
            var input=Path.Combine(Program.Root,"local-diagnostic-input.json");File.WriteAllText(input,"{\"action\":\"diagnose\"}");
            var (_,output)=await ProcessRunner.Run("powershell.exe",["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",Path.Combine(AppContext.BaseDirectory,"operations","PrinterOperations.ps1"),"-InputFile",input],TimeSpan.FromSeconds(60),CancellationToken.None);
            File.Delete(input);var report=Path.Combine(Program.Root,"local-diagnostic.json");File.WriteAllText(report,output);Process.Start(new ProcessStartInfo("notepad.exe",report){UseShellExecute=true});
        } catch(Exception e){MessageBox.Show("诊断未完成："+e.GetType().Name,Text);}finally{diagnose.Enabled=true;} };
        var stopDesktop = new Button { Text = "关闭本机远程桌面", Width = 550, Height = 35 }; panel.Controls.Add(stopDesktop);
        stopDesktop.Click += async (_, _) => { stopDesktop.Enabled=false;try{var r=await RemoteDesktop.Execute(new(Guid.NewGuid(),"desktop-disable",null,null,"",null,null),CancellationToken.None);MessageBox.Show(r.Result.GetProperty("message").GetString(),Text);}catch(Exception e){MessageBox.Show("关闭未完成："+e.GetType().Name,Text);}finally{stopDesktop.Enabled=true;} };
        panel.Controls.Add(new Label {Text="远程桌面由管理员启用；本机可随时关闭。",AutoSize=true});
        configure.Click += async (_, _) => { try {
            var current = Program.ReadConfig(); using var setup = new DestinationForm(current);
            if (setup.ShowDialog() != DialogResult.OK || setup.Result == null) return;
            configure.Enabled = false; restart.Enabled = false;
            await Task.Run(() => { using var service = new ServiceController(Program.ServiceName); if (service.Status != ServiceControllerStatus.Stopped) { service.Stop(); service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(65)); } RemoteDesktop.StopManaged(CancellationToken.None).GetAwaiter().GetResult(); AutomaticEnrollment.ChangeDestination(setup.Result); service.Start(); service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30)); });
            MessageBox.Show("已保存并启动。电脑会自动登记，请在小程序“待分配电脑”中选择校区。", Text);
        } catch (Exception e) { MessageBox.Show("未完成切换，请确认已运行安装程序。\n" + e.Message, Text); } finally { configure.Enabled = true; restart.Enabled = true; RefreshStatus(); } };
        restart.Click += async (_, _) => { restart.Enabled = false; configure.Enabled = false; try { await Task.Run(() => { using var service = new ServiceController(Program.ServiceName); if (service.Status != ServiceControllerStatus.Stopped) { service.Stop(); service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(65)); } service.Start(); }); } catch (Exception e) { MessageBox.Show(e.Message, Text); } finally { restart.Enabled = true; configure.Enabled = true; RefreshStatus(); } };
        timer.Tick += (_, _) => RefreshStatus(); timer.Start(); RefreshStatus(); FormClosed += (_, _) => timer.Dispose();
    }
    void RefreshStatus()
    {
        try {
            using var service = new ServiceController(Program.ServiceName); var config = Program.ReadConfig();
            var text = "后台服务：" + service.Status + "\r\n服务器：" + (config?.Server ?? AutomaticEnrollment.ReadDestination().Server) + "\r\n连接 IP：" + (config?.ConnectIp ?? "自动解析") + "\r\n";
            var path = Path.Combine(Program.Root, "status.json");
            if (File.Exists(path)) { using var doc = JsonDocument.Parse(File.ReadAllText(path)); var root = doc.RootElement; text += root.GetProperty("message").GetString() + "\r\n更新时间：" + root.GetProperty("updatedAt").GetDateTimeOffset().ToLocalTime() + "\r\n";
                if (root.TryGetProperty("detail", out var detail) && detail.ValueKind == JsonValueKind.Object && detail.TryGetProperty("probe", out var probe)) {
                    if (probe.TryGetProperty("error", out var error)) text += error.GetString() + "\r\n";
                    foreach (var p in probe.GetProperty("printers").EnumerateArray()) text += p.GetProperty("queueName").GetString() + "：" + p.GetProperty("detail").GetString() + "，队列任务 " + p.GetProperty("jobCount").GetInt32() + "\r\n";
                }
            }
            status.Text = text;
        } catch { status.Text = "请先运行“安装校区助手.cmd”。状态暂不可读取，稍后自动刷新。"; }
    }
}
