using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using System.ServiceProcess;
using Siyue.Printing;
namespace Siyue.Agent;
public record Destination(string Server, string? ConnectIp, string Name, InstallationProfile? Profile = null);
public record PendingEnrollment(Destination Destination, string EnrollmentKey);
static class AutomaticEnrollment
{
    static string PendingPath => Path.Combine(Program.Root, "enrollment.dat");
    static string DestinationPath => Path.Combine(Program.Root, "destination.json");
    public static Destination ReadDestination() => File.Exists(DestinationPath) ? JsonSerializer.Deserialize<Destination>(File.ReadAllText(DestinationPath), Program.Json)! : new("https://www.siyuejn.com/miniapp/", null, Environment.MachineName);
    public static bool Initialized { get { try { return ReadDestination().Profile?.Validate() != null; } catch { return false; } } }
    public static void ChangeDestination(Destination destination)
    {
        // Caller stops the service before changing identity or the local profile.
        var endpoint = new ServerEndpoint(destination.Server, destination.ConnectIp);
        destination = destination with { Profile = destination.Profile?.Validate() ?? throw new ArgumentException("请填写所属校区与打印机别名") };
        var current = Program.ReadConfig();
        var sameServer = current != null && new ServerEndpoint(current.Server, current.ConnectIp).BaseUri == endpoint.BaseUri;
        var path = Path.Combine(Program.Root, "identity.dat");
        if (current != null && !sameServer) File.Move(path, path + ".previous-" + DateTime.UtcNow.Ticks);
        File.WriteAllText(DestinationPath + ".tmp", JsonSerializer.Serialize(destination, Program.Json)); File.Move(DestinationPath + ".tmp", DestinationPath, true);
        if (sameServer) Program.SaveConfig(current! with { Server = endpoint.BaseUri.AbsoluteUri, ConnectIp = endpoint.ConnectAddress?.ToString() });
        // Preserve the idempotency key while editing an unapproved registration on the same server.
        if (File.Exists(PendingPath)) {
            var old = JsonSerializer.Deserialize<PendingEnrollment>(ProtectedData.Unprotect(File.ReadAllBytes(PendingPath), null, DataProtectionScope.LocalMachine), Program.Json)!;
            if (new ServerEndpoint(old.Destination.Server, old.Destination.ConnectIp).BaseUri == endpoint.BaseUri) SavePending(old with { Destination = destination });
            else File.Delete(PendingPath);
        }
    }
    static void SavePending(PendingEnrollment pending)
    {
        var bytes = ProtectedData.Protect(JsonSerializer.SerializeToUtf8Bytes(pending, Program.Json), null, DataProtectionScope.LocalMachine);
        File.WriteAllBytes(PendingPath + ".tmp", bytes); File.Move(PendingPath + ".tmp", PendingPath, true);
    }
    public static async Task Connect(CancellationToken ct)
    {
        if (!Initialized) { AgentRuntime.WriteStatus("等待初始化：请打开打印助手，填写所属校区与打印机别名", null, null); await Task.Delay(5000, ct); return; }
        PendingEnrollment pending;
        if (File.Exists(PendingPath)) pending = JsonSerializer.Deserialize<PendingEnrollment>(ProtectedData.Unprotect(File.ReadAllBytes(PendingPath), null, DataProtectionScope.LocalMachine), Program.Json)!;
        else { pending = new(ReadDestination(), Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32))); SavePending(pending); }
        var endpoint = new ServerEndpoint(pending.Destination.Server, pending.Destination.ConnectIp); using var http = endpoint.CreateClient();
        using var response = await http.PostAsJsonAsync("agent/enrollments", new { pending.Destination.Name, pending.EnrollmentKey, pending.Destination.Profile }, ct);
        if (!response.IsSuccessStatusCode) {
            var detail = "自动接入未完成，请检查校区和打印机名称";
            try { var error = await response.Content.ReadFromJsonAsync<JsonElement>(ct); if (error.TryGetProperty("message", out var message)) detail = message.GetString() ?? detail; } catch (JsonException) { }
            AgentRuntime.WriteStatus(detail, null, null); await Task.Delay(10000, ct); return;
        }
        var registration = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.GetProperty("pollToken").GetString());
        while (!ct.IsCancellationRequested) {
            using var poll = await http.GetAsync("agent/pairings/status", ct);
            if (poll.StatusCode == HttpStatusCode.Gone) return;
            poll.EnsureSuccessStatusCode(); var result = await poll.Content.ReadFromJsonAsync<JsonElement>(ct);
            if (result.GetProperty("state").GetString() == "connected") {
                Program.SaveConfig(new(endpoint.BaseUri.AbsoluteUri, registration.GetProperty("deviceToken").GetString()!, result.GetProperty("id").GetGuid(), endpoint.ConnectAddress?.ToString())); return;
            }
            AgentRuntime.WriteStatus("已登记到“" + pending.Destination.Profile!.CampusName + "”，正在连接打印服务", null, new { name = pending.Destination.Name, server = endpoint.BaseUri.AbsoluteUri, pending.Destination.Profile });
            await Task.Delay(5000, ct);
        }
    }
}
sealed class DestinationForm : Form
{
    public Destination? Result { get; private set; }
    public DestinationForm(Configuration? current)
    {
        var destination = AutomaticEnrollment.ReadDestination();
        Text = "校区与打印机初始化"; Width = 600; Height = 730; MinimumSize = new Size(580, 660); StartPosition = FormStartPosition.CenterParent;
        Font = new Font("Microsoft YaHei UI", 10); BackColor = Color.White;
        var campus = new TextBox { Name = "CampusName", AccessibleName = "所属校区", Width = 510, Text = destination.Profile?.CampusName ?? "", PlaceholderText = "填写小程序中的校区名称" };
        var queue = new ComboBox { Name = "PrinterQueue", AccessibleName = "本机打印队列", Width = 510, DropDownStyle = ComboBoxStyle.DropDownList };
        var alias = new TextBox { Name = "PrinterAlias", AccessibleName = "打印机别名", Width = 510, Text = destination.Profile?.PrinterAlias ?? "", PlaceholderText = "例如：银座晶都12B东侧打印机" };
        var server = new TextBox { Name = "Server", AccessibleName = "服务端地址", Width = 510, Text = current?.Server ?? destination.Server };
        var ip = new TextBox { Name = "ConnectIp", AccessibleName = "服务器公网IP", Width = 510, Text = current?.ConnectIp ?? destination.ConnectIp ?? "", PlaceholderText = "留空使用域名解析" };
        var status = new Label { Name = "ValidationMessage", AutoSize = true, MaximumSize = new Size(510, 0), ForeColor = Color.FromArgb(170, 55, 30), Margin = new Padding(0, 10, 0, 8) };
        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoScroll = true, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(28, 20, 24, 20) };
        var save = new Button { Name = "SaveProfile", AccessibleName = "保存并连接", Text = "保存并连接", Width = 510, Height = 44, BackColor = Color.FromArgb(29, 29, 31), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        Label Title(string text) => new() { Text = text, AutoSize = true, Margin = new Padding(0, 14, 0, 6) };
        panel.Controls.AddRange([new Label { Text = "让老师按校区和位置找到这台打印机", AutoSize = true, Font = new Font(Font, FontStyle.Bold) }, Title("所属校区 *"), campus, Title("本机打印队列 *"), queue, Title("打印机别名 *"), alias, Title("服务端地址"), server, Title("服务器公网 IP（可选）"), ip, new Label { Text = "填写并保存后自动接入校区，所选打印机立即启用。\n修改别名会保留当前设备身份；管理员可修改名称、校区或停用设备。", AutoSize = true, MaximumSize = new Size(510, 0), Margin = new Padding(0, 16, 0, 0) }, status, save]); Controls.Add(panel);
        async Task LoadQueues()
        {
            try {
                var probe = await Task.Run(WindowsPrinters.Read);
                if (IsDisposed) return;
                queue.Items.Clear(); queue.Items.AddRange(probe.Printers.Select(p => (object)p.QueueName).ToArray());
                queue.SelectedItem = destination.Profile?.QueueName;
                if (queue.SelectedIndex < 0 && queue.Items.Count == 1) queue.SelectedIndex = 0;
                if (queue.Items.Count == 0) status.Text = "未发现本机打印队列，请先安装打印机驱动，再重新打开此窗口。";
                if (current != null && destination.Profile == null) {
                    using var http = new ServerEndpoint(current.Server, current.ConnectIp).CreateClient(); http.Timeout = TimeSpan.FromSeconds(8);
                    http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", current.Token);
                    var existing = await http.GetFromJsonAsync<JsonElement>("agent/installation-profile");
                    if (IsDisposed) return;
                    if (string.IsNullOrWhiteSpace(campus.Text)) campus.Text = existing.GetProperty("campusName").GetString();
                    var choices = existing.GetProperty("printers").EnumerateArray().Where(p => p.GetProperty("enabled").GetBoolean()).ToArray();
                    if (choices.Length == 1 && queue.SelectedIndex < 0) queue.SelectedItem = choices[0].GetProperty("queueName").GetString();
                    var selected = existing.GetProperty("printers").EnumerateArray().FirstOrDefault(p => p.GetProperty("queueName").GetString() == queue.SelectedItem?.ToString());
                    if (selected.ValueKind == JsonValueKind.Object && string.IsNullOrWhiteSpace(alias.Text) && selected.GetProperty("name").GetString() != selected.GetProperty("queueName").GetString()) alias.Text = selected.GetProperty("name").GetString();
                }
            } catch (Exception) { if (!IsDisposed) status.Text = "部分信息未能自动读取，请填写校区、选择打印队列并设置别名。"; }
        }
        Shown += async (_, _) => await LoadQueues();
        save.Click += async (_, _) => {
            try {
                var profile = new InstallationProfile(campus.Text, queue.SelectedItem?.ToString() ?? "", alias.Text).Validate();
                var endpoint = new ServerEndpoint(server.Text, ip.Text);
                var next = new Destination(endpoint.BaseUri.AbsoluteUri, endpoint.ConnectAddress?.ToString(), destination.Name, profile);
                save.Enabled = false; panel.Enabled = false; status.Text = "正在保存并重新连接…";
                await Task.Run(() => {
                    using var service = new ServiceController(Program.ServiceName);
                    if (service.Status != ServiceControllerStatus.Stopped) { service.Stop(); service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(65)); }
                    try {
                        if (current != null && new ServerEndpoint(current.Server, current.ConnectIp).BaseUri != endpoint.BaseUri) RemoteDesktop.StopManaged(CancellationToken.None).GetAwaiter().GetResult();
                        AutomaticEnrollment.ChangeDestination(next);
                    } finally { service.Refresh(); if (service.Status == ServiceControllerStatus.Stopped) service.Start(); }
                    service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                });
                Result = next; DialogResult = DialogResult.OK; Close();
            } catch (Exception e) { status.Text = "未完成：" + e.Message; }
            finally { if (!IsDisposed) { panel.Enabled = true; save.Enabled = true; } }
        };
    }
}
