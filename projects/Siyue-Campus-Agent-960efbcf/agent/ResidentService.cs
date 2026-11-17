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
    CancellationTokenSource? stop;
    Task? runner;
    readonly HostPower hostPower = new();

    public ResidentService()
    {
        ServiceName = Program.ServiceName;
        CanShutdown = true;
        CanHandlePowerEvent = true;
    }

    protected override void OnStart(string[] args)
    {
        stop = new();
        var cancellation = stop.Token;
        runner = Task.WhenAll(Task.Run(() => Run(cancellation)), hostPower.Run(cancellation));
    }

    protected override bool OnPowerEvent(PowerBroadcastStatus status)
    {
        if (status is PowerBroadcastStatus.ResumeAutomatic or PowerBroadcastStatus.ResumeSuspend)
            hostPower.RefreshAfterResume();
        return true;
    }
    async Task Run(CancellationToken ct)
    {
        try {
            while (!ct.IsCancellationRequested) {
                try {
                    var config = Program.ReadConfig();
                    if (config != null) { using var runtime = new AgentRuntime(config); await runtime.Run(ct); }
                    else await AutomaticEnrollment.Connect(ct);
                } catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
                catch (Exception e) { AgentRuntime.WriteStatus(FailureMessages.Connection(e), null, null); }
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
                await transport.Heartbeat(probe, ct); latest = probe; lastContact = DateTimeOffset.UtcNow;
                WriteStatus("已连接服务端", config, new { lastContact, probe });
            } catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (HttpRequestException e) when (e.StatusCode == System.Net.HttpStatusCode.Unauthorized) {
                latest = null; try { await RemoteDesktop.StopManaged(ct); } catch { }
                WriteStatus("设备授权已失效，请管理员检查；远程桌面已请求关闭",config,null);
            }
            catch (InvalidOperationException e) { latest = null; WriteStatus(e.Message, config, new { lastContact }); }
            catch (Exception e) { latest = null; WriteStatus(FailureMessages.Connection(e), config, new { lastContact }); }
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
                var (state, detail) = SpoolerStatus.Describe(p.Status); bool duplex; try { duplex = new PrinterSettings { PrinterName = p.PrinterName }.CanDuplex; } catch { duplex = false; }
                list.Add(new(p.PrinterName, duplex, state, p.Status, (int)Math.Min(p.Jobs, int.MaxValue), detail, p.DriverName ?? "", p.PortName ?? ""));
            }
            return new(list.ToArray(), true);
        } finally { Marshal.FreeHGlobal(buffer); }
    }
}

sealed class ManagementForm : Form
{
    readonly System.Windows.Forms.Timer timer = new() { Interval = 3000 };
    readonly Label stateTitle = new() { Text = "正在检查…", AutoSize = true, Font = new Font("Microsoft YaHei UI", 20, FontStyle.Bold), ForeColor = Color.FromArgb(31, 41, 55) };
    readonly Label stateDetail = new() { Text = "请稍候", AutoSize = true, MaximumSize = new Size(620, 0), Font = new Font("Microsoft YaHei UI", 10), ForeColor = Color.FromArgb(75, 85, 99) };
    readonly Label lastContact = CardValue("—");
    readonly Label queueCount = CardValue("0");
    readonly Label printerCount = CardValue("0");
    readonly FlowLayoutPanel printers = new() { AutoSize = true, FlowDirection = FlowDirection.TopDown, WrapContents = false, Margin = new Padding(0, 8, 0, 0) };
    readonly Label issue = new() { Text = "暂未发现问题", AutoSize = true, MaximumSize = new Size(600, 0), ForeColor = Color.FromArgb(75, 85, 99), Font = new Font("Microsoft YaHei UI", 10) };
    readonly Button repair = PrimaryButton("一键恢复连接");
    readonly Button refresh = SecondaryButton("立即刷新");
    readonly Button advanced = SecondaryButton("高级维护");

    public ManagementForm()
    {
        Text = "思跃打印助手"; Width = 720; Height = 680; MinimumSize = new Size(680, 600); StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Microsoft YaHei UI", 9); BackColor = Color.FromArgb(246, 248, 251);
        var root = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoScroll = true, FlowDirection = FlowDirection.TopDown, Padding = new Padding(34, 28, 34, 24), WrapContents = false };
        root.Controls.Add(new Label { Text = "思跃打印助手", AutoSize = true, Font = new Font("Microsoft YaHei UI", 12, FontStyle.Bold), ForeColor = Color.FromArgb(37, 99, 235), Margin = new Padding(0, 0, 0, 18) });
        var hero = Card(640, 112); hero.Controls.Add(stateTitle); hero.Controls.Add(stateDetail); root.Controls.Add(hero);
        var metrics = new FlowLayoutPanel { Width = 640, Height = 112, FlowDirection = FlowDirection.LeftToRight, Margin = new Padding(0, 14, 0, 0) };
        metrics.Controls.Add(Metric("最近联机", lastContact)); metrics.Controls.Add(Metric("待打印", queueCount)); metrics.Controls.Add(Metric("打印机", printerCount)); root.Controls.Add(metrics);
        root.Controls.Add(Section("打印机", printers)); root.Controls.Add(Section("需要处理", issue));
        var actions = new FlowLayoutPanel { Width = 640, Height = 50, FlowDirection = FlowDirection.LeftToRight, Margin = new Padding(0, 12, 0, 0) };
        actions.Controls.Add(repair); actions.Controls.Add(refresh); actions.Controls.Add(advanced); root.Controls.Add(actions);
        root.Controls.Add(new Label { Text = "关闭窗口不影响后台接收打印任务", AutoSize = true, ForeColor = Color.FromArgb(107, 114, 128), Margin = new Padding(0, 16, 0, 0) });
        Controls.Add(root);
        repair.Click += async (_, _) => await RestartService();
        refresh.Click += (_, _) => RefreshStatus();
        advanced.Click += (_, _) => ShowAdvanced();
        timer.Tick += (_, _) => RefreshStatus(); timer.Start(); RefreshStatus(); FormClosed += (_, _) => timer.Dispose();
    }

    static Label CardValue(string text) => new() { Text = text, AutoSize = true, Font = new Font("Microsoft YaHei UI", 18, FontStyle.Bold), ForeColor = Color.FromArgb(17, 24, 39) };
    static Button PrimaryButton(string text) => new() { Text = text, Width = 178, Height = 42, FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(37, 99, 235), ForeColor = Color.White, Font = new Font("Microsoft YaHei UI", 9, FontStyle.Bold), Cursor = Cursors.Hand };
    static Button SecondaryButton(string text) => new() { Text = text, Width = 138, Height = 42, FlatStyle = FlatStyle.Flat, BackColor = Color.White, ForeColor = Color.FromArgb(55, 65, 81), Cursor = Cursors.Hand };
    static FlowLayoutPanel Card(int width, int height) => new() { Width = width, Height = height, BackColor = Color.White, Padding = new Padding(20, 16, 20, 12), FlowDirection = FlowDirection.TopDown, WrapContents = false, Margin = new Padding(0) };
    static Control Metric(string title, Label value) { var c = Card(194, 100); c.Margin = new Padding(0, 0, 12, 0); c.Controls.Add(new Label { Text = title, AutoSize = true, ForeColor = Color.FromArgb(107, 114, 128) }); c.Controls.Add(value); return c; }
    static Control Section(string title, Control content) { var c = Card(640, 128); c.Margin = new Padding(0, 14, 0, 0); c.Controls.Add(new Label { Text = title, AutoSize = true, Font = new Font("Microsoft YaHei UI", 10, FontStyle.Bold), ForeColor = Color.FromArgb(31, 41, 55) }); c.Controls.Add(content); return c; }

    async Task RestartService()
    {
        repair.Enabled = false; stateTitle.Text = "正在恢复…"; stateDetail.Text = "重新连接服务并保留未完成任务";
        try { await Task.Run(() => { using var service = new ServiceController(Program.ServiceName); if (service.Status != ServiceControllerStatus.Stopped) { service.Stop(); service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(65)); } service.Start(); service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30)); }); }
        catch (Exception e) { MessageBox.Show("恢复未完成：" + e.Message, Text); }
        finally { repair.Enabled = true; RefreshStatus(); }
    }

    void ShowAdvanced()
    {
        using var menu = new Form { Text = "高级维护", Width = 430, Height = 330, StartPosition = FormStartPosition.CenterParent, Font = Font, BackColor = Color.White, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false };
        var p = new FlowLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(24), FlowDirection = FlowDirection.TopDown, WrapContents = false };
        var configure = SecondaryButton("校区与打印机设置"); configure.Width = 350;
        var diagnose = SecondaryButton("生成诊断报告"); diagnose.Width = 350;
        var stopDesktop = SecondaryButton("关闭远程协助"); stopDesktop.Width = 350;
        p.Controls.Add(new Label { Text = "仅在管理员指导下使用", AutoSize = true, Font = new Font(Font, FontStyle.Bold), Margin = new Padding(0, 0, 0, 14) }); p.Controls.Add(configure); p.Controls.Add(diagnose); p.Controls.Add(stopDesktop); menu.Controls.Add(p);
        configure.Click += (_, _) => { using var setup = new DestinationForm(Program.ReadConfig()); setup.ShowDialog(menu); };
        diagnose.Click += async (_, _) => { diagnose.Enabled=false; try { var input=Path.Combine(Program.Root,"local-diagnostic-input.json"); File.WriteAllText(input,"{\"action\":\"diagnose\"}"); var (_,output)=await ProcessRunner.Run("powershell.exe",["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",Path.Combine(AppContext.BaseDirectory,"operations","PrinterOperations.ps1"),"-InputFile",input],TimeSpan.FromSeconds(60),CancellationToken.None); File.Delete(input); var report=Path.Combine(Program.Root,"local-diagnostic.json"); File.WriteAllText(report,output); Process.Start(new ProcessStartInfo("notepad.exe",report){UseShellExecute=true}); } catch(Exception e){MessageBox.Show("诊断未完成："+e.GetType().Name,Text);} finally{diagnose.Enabled=true;} };
        stopDesktop.Click += async (_, _) => { stopDesktop.Enabled=false; try { var r=await RemoteDesktop.Execute(new(Guid.NewGuid(),"desktop-disable",null,null,"",null,null),CancellationToken.None); MessageBox.Show(r.Result.GetProperty("message").GetString(),Text); } catch(Exception e){MessageBox.Show("关闭未完成："+e.GetType().Name,Text);} finally{stopDesktop.Enabled=true;} };
        menu.ShowDialog(this);
    }

    void RefreshStatus()
    {
        try {
            using var service = new ServiceController(Program.ServiceName);
            var running = service.Status == ServiceControllerStatus.Running; var connected = false; var totalJobs = 0; var totalPrinters = 0; var problem = "暂未发现问题"; DateTimeOffset? updated = null;
            printers.Controls.Clear();
            var path = Path.Combine(Program.Root, "status.json");
            if (File.Exists(path)) { using var doc = JsonDocument.Parse(File.ReadAllText(path)); var root = doc.RootElement; var message = root.GetProperty("message").GetString() ?? ""; connected = message == "已连接服务端"; updated = root.GetProperty("updatedAt").GetDateTimeOffset().ToLocalTime(); if(!connected) problem = message;
                if (connected && (DateTimeOffset.Now - updated.Value > TimeSpan.FromSeconds(30) || updated.Value > DateTimeOffset.Now.AddSeconds(5))) { connected = false; problem = "状态已过期，正在等待新的连接确认"; }
                if (root.TryGetProperty("detail", out var detail) && detail.ValueKind == JsonValueKind.Object && detail.TryGetProperty("probe", out var probe)) {
                    if (probe.TryGetProperty("complete", out var complete) && !complete.GetBoolean()) problem = "打印机状态尚未完整读取";
                    if (probe.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.String) problem = error.GetString() ?? problem;
                    foreach (var item in probe.GetProperty("printers").EnumerateArray()) { totalPrinters++; var jobs=item.GetProperty("jobCount").GetInt32(); totalJobs+=jobs; var name=item.GetProperty("queueName").GetString(); var selectedProfile=AutomaticEnrollment.ReadDestination().Profile; if(selectedProfile?.QueueName==name) name=selectedProfile.PrinterAlias; var detailText=item.GetProperty("detail").GetString(); if (item.TryGetProperty("status", out var ps) && ps.GetString() is "offline" or "paused" or "error" or "warning") problem = $"{name}：{detailText}"; if (item.TryGetProperty("deviceReachable", out var reach) && reach.ValueKind == JsonValueKind.False) problem = $"{name}：无法连接打印机"; printers.Controls.Add(new Label { Text=$"{name}   {detailText}"+(jobs>0?$"   待处理 {jobs}":""), AutoSize=true, MaximumSize=new Size(580,0), ForeColor=Color.FromArgb(55,65,81), Margin=new Padding(0,4,0,0) }); }
                }
            }
            if (connected && totalPrinters == 0 && problem == "暂未发现问题") problem = "尚未发现可用打印机";
            var healthy = running && connected && problem == "暂未发现问题";
            stateTitle.Text = running && connected ? healthy ? "已连接总控" : "打印机需要检查" : running ? "正在重新连接" : "打印助手未运行";
            stateTitle.ForeColor = healthy ? Color.FromArgb(22, 163, 74) : Color.FromArgb(220, 38, 38);
            stateDetail.Text = healthy ? "打印任务会自动处理；是否完成请查看任务和打印机状态" : problem;
            lastContact.Text = updated.HasValue ? updated.Value.ToString("HH:mm:ss") : "—"; queueCount.Text = totalJobs.ToString(); printerCount.Text = totalPrinters.ToString(); issue.Text = problem;
            if (totalPrinters == 0) printers.Controls.Add(new Label { Text="尚未读取到打印机", AutoSize=true, ForeColor=Color.FromArgb(107,114,128), Margin=new Padding(0,4,0,0) });
            repair.Visible = !running || !connected;
        } catch {
            stateTitle.Text="尚未安装"; stateTitle.ForeColor=Color.FromArgb(220,38,38); stateDetail.Text="请先安装打印助手，再由小程序分配校区"; lastContact.Text="—"; queueCount.Text="0"; issue.Text="后台服务尚不可用"; repair.Visible=false; printers.Controls.Clear();
            try { var local=WindowsPrinters.Read(); printerCount.Text=local.Printers.Length.ToString(); foreach(var item in local.Printers) printers.Controls.Add(new Label { Text=$"{item.QueueName}   {item.Detail}"+(item.JobCount>0?$"   待处理 {item.JobCount}":""), AutoSize=true, MaximumSize=new Size(580,0), ForeColor=Color.FromArgb(55,65,81), Margin=new Padding(0,4,0,0) }); }
            catch { printerCount.Text="0"; printers.Controls.Add(new Label { Text="暂时无法读取本机打印机", AutoSize=true, ForeColor=Color.FromArgb(107,114,128) }); }
        }
    }
}
