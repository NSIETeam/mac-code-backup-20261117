using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Siyue.Printing;
namespace Siyue.Agent;
public record Destination(string Server, string? ConnectIp, string Name);
public record PendingEnrollment(Destination Destination, string EnrollmentKey);
static class AutomaticEnrollment
{
    static string PendingPath => Path.Combine(Program.Root, "enrollment.dat");
    static string DestinationPath => Path.Combine(Program.Root, "destination.json");
    public static Destination ReadDestination() => File.Exists(DestinationPath) ? JsonSerializer.Deserialize<Destination>(File.ReadAllText(DestinationPath), Program.Json)! : new("https://www.siyuejn.com/miniapp/", null, Environment.MachineName);
    public static void ChangeDestination(Destination destination)
    {
        // Caller has already stopped the service, so no job can be claimed during the switch.
        new ServerEndpoint(destination.Server, destination.ConnectIp);
        var path = Path.Combine(Program.Root, "identity.dat"); if (File.Exists(path)) File.Move(path, path + ".previous-" + DateTime.UtcNow.Ticks);
        File.WriteAllText(DestinationPath + ".tmp", JsonSerializer.Serialize(destination, Program.Json)); File.Move(DestinationPath + ".tmp", DestinationPath, true);
        File.Delete(PendingPath);
    }
    public static async Task Connect(CancellationToken ct)
    {
        PendingEnrollment pending;
        if (File.Exists(PendingPath)) pending = JsonSerializer.Deserialize<PendingEnrollment>(ProtectedData.Unprotect(File.ReadAllBytes(PendingPath), null, DataProtectionScope.LocalMachine), Program.Json)!;
        else { pending = new(ReadDestination(), Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32))); var bytes = ProtectedData.Protect(JsonSerializer.SerializeToUtf8Bytes(pending, Program.Json), null, DataProtectionScope.LocalMachine); File.WriteAllBytes(PendingPath + ".tmp", bytes); File.Move(PendingPath + ".tmp", PendingPath, true); }
        var endpoint = new ServerEndpoint(pending.Destination.Server, pending.Destination.ConnectIp); using var http = endpoint.CreateClient();
        using var response = await http.PostAsJsonAsync("agent/enrollments", new { pending.Destination.Name, pending.EnrollmentKey }, ct); response.EnsureSuccessStatusCode();
        var registration = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.GetProperty("pollToken").GetString());
        while (!ct.IsCancellationRequested) {
            using var poll = await http.GetAsync("agent/pairings/status", ct);
            if (poll.StatusCode == HttpStatusCode.Gone) return;
            poll.EnsureSuccessStatusCode(); var result = await poll.Content.ReadFromJsonAsync<JsonElement>(ct);
            if (result.GetProperty("state").GetString() == "connected") {
                Program.SaveConfig(new(endpoint.BaseUri.AbsoluteUri, registration.GetProperty("deviceToken").GetString()!, result.GetProperty("id").GetGuid(), endpoint.ConnectAddress?.ToString())); return;
            }
            AgentRuntime.WriteStatus("已自动登记，等待管理员在小程序中分配校区", null, new { name = pending.Destination.Name, server = endpoint.BaseUri.AbsoluteUri });
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
        Text = "服务端与设备设置"; Width = 530; Height = 390; StartPosition = FormStartPosition.CenterParent;
        var server = new TextBox { Width = 460, Text = current?.Server ?? destination.Server };
        var ip = new TextBox { Width = 460, Text = current?.ConnectIp ?? destination.ConnectIp ?? "", PlaceholderText = "留空使用域名解析，可修改为服务器公网 IP" };
        var name = new TextBox { Width = 460, Text = destination.Name };
        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(20) };
        var save = new Button { Text = "保存并自动登记到此服务端", Width = 460, Height = 42 };
        panel.Controls.AddRange([new Label { Text = "小程序服务端 HTTPS 地址（含路径）", AutoSize = true }, server, new Label { Text = "连接 IP（可选，域名证书校验仍开启）", AutoSize = true }, ip, new Label { Text = "此控制电脑的名称", AutoSize = true }, name, new Label { Text = "保存后需在小程序重新分配校区；已有任务不会转移。", AutoSize = true }, save]); Controls.Add(panel);
        save.Click += (_, _) => { try { var endpoint = new ServerEndpoint(server.Text, ip.Text); if (name.Text.Trim().Length is < 1 or > 120) throw new Exception("设备名称需要 1–120 字"); Result = new(endpoint.BaseUri.AbsoluteUri, endpoint.ConnectAddress?.ToString(), name.Text.Trim()); DialogResult = DialogResult.OK; Close(); } catch (Exception e) { MessageBox.Show(e.Message, Text); } };
    }
}
