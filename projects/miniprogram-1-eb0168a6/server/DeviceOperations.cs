using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
namespace Siyue;
public class DeviceOperation : Entity
{
    public Guid DeviceId { get; set; } public Guid? PrinterId { get; set; } public Guid StaffId { get; set; }
    public string Action { get; set; } = ""; public int? JobId { get; set; } public string State { get; set; } = "queued";
    public string RequestKey { get; set; } = ""; public string? ClaimTokenHash { get; set; } public string? ResultProtected { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now; public DateTimeOffset ExpiresAt { get; set; } = Clock.Now.AddMinutes(5);
    public DateTimeOffset? CompletedAt { get; set; }
}
public record OperationInput(string Action, Guid? PrinterId = null, int? JobId = null, bool Acknowledge = false, string RequestKey = "");
public record OperationReport(string ClaimToken, string State, JsonElement Result);
public static class DeviceOperations
{
    public static readonly string[] Actions = ["diagnose", "enable-print-log", "start-spooler", "restart-spooler", "resume-printer", "cancel-job", "desktop-enable", "desktop-disable", "install-update"];
    public static void MapDeviceOperations(this WebApplication app, RouteGroupBuilder agent)
    {
        app.MapAgentUpdates(agent);
        app.MapGet("/api/admin/devices/{id:guid}/overview", async (Guid id, Data db, Access access) => {
            access.Admin(); var d = await db.Devices.FindAsync(id) ?? throw new ApiException(404,"设备不存在");
            return Results.Ok(new {device=new {d.Id,d.Name,d.AgentVersion,d.LastSeen,d.Enabled,d.MonitorVersion,d.MonitorLastSeen,d.MonitorError},printers=await db.Printers.Where(p=>p.DeviceId==id).Select(p=>new {p.Id,p.Name,p.Status,p.StatusDetail,p.StatusAt,p.JobCount,p.Driver,p.Port,p.DeviceReachable,p.DeviceCheckedAt}).ToListAsync(),executions=await db.PrintJobs.Where(j=>j.DeviceId==id && j.ExecutionJson!=null).OrderByDescending(j=>j.CreatedAt).Take(20).Select(j=>new {j.Id,j.ExecutionJson,j.ExecutionAt}).ToListAsync()});
        });
        app.MapPost("/api/admin/devices/{id:guid}/operations", async (Guid id, OperationInput r, Data db, Access access, IConfiguration config) => {
            access.Admin(); Guard.That(Actions.Contains(r.Action), "不支持的远程操作"); Guard.That(r.RequestKey.Length is >= 8 and <= 100, "请求标识无效");
            var device = await db.Devices.FindAsync(id) ?? throw new ApiException(404, "设备不存在");
            Guard.That(device.Enabled, "设备已停用", 409);
            Guard.That(Version.TryParse(device.AgentVersion, out var version) && version >= new Version(2,1,0), "请先在此电脑安装 2.1.0 或更新版本的助手", 409);
            Guard.That(device.LastSeen > Clock.Now.AddSeconds(-90), "设备不在线，请等待连接恢复", 409);
            Guard.That(r.Action == "diagnose" || r.Acknowledge, "请确认此操作会影响远程电脑", 400);
            if (r.PrinterId.HasValue) Guard.That(await db.Printers.AnyAsync(p => p.Id == r.PrinterId && p.DeviceId == id), "打印机不属于此设备");
            Guard.That(r.Action is not ("resume-printer" or "cancel-job") || r.PrinterId.HasValue, "请选择打印机");
            Guard.That(r.Action != "cancel-job" || r.JobId is > 0, "请输入 Windows 打印任务编号");
            var existing = await db.DeviceOperations.SingleOrDefaultAsync(x => x.DeviceId == id && x.RequestKey == r.RequestKey);
            if (existing != null) { Guard.That(existing.Action == r.Action && existing.PrinterId == r.PrinterId && existing.JobId == r.JobId, "此请求标识已用于其他操作", 409); return Results.Ok(new { existing.Id, existing.State }); }
            if(r.Action == "install-update") { var update = AgentUpdates.Read(config,id) ?? throw new ApiException(409,"尚未发布可用更新包"); Guard.That(System.Version.Parse(update.Version) > version, "终端已是当前版本或版本更高",409); }
            Guard.That(await db.DeviceOperations.CountAsync(x => x.DeviceId == id && x.State == "queued" && x.ExpiresAt > Clock.Now) < 5, "已有多条待执行操作，请等待回执", 409);
            var op = new DeviceOperation { DeviceId = id, PrinterId = r.PrinterId, StaffId = access.User.Id, Action = r.Action, JobId = r.JobId, RequestKey = r.RequestKey };
            db.Add(op); db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "device.operation." + r.Action, TargetId = op.Id }); await db.SaveChangesAsync(); return Results.Ok(new { op.Id, op.State });
        });
        app.MapGet("/api/admin/devices/{id:guid}/operations", async (Guid id, Data db, Access access, IDataProtectionProvider crypto) => {
            access.Admin(); await Expire(db, id); var protector = crypto.CreateProtector("siyue-device-operations-v1");
            var rows = await db.DeviceOperations.Where(x => x.DeviceId == id).OrderByDescending(x => x.CreatedAt).Take(30).ToListAsync();
            return rows.Select(x => new { x.Id, x.Action, x.PrinterId, x.JobId, x.State, x.CreatedAt, x.CompletedAt,
                result = VisibleResult(x, protector) });
        });
        app.MapPost("/api/admin/operations/{id:guid}/desktop-access", async (Guid id, Data db, Access access, IDataProtectionProvider crypto) => {
            access.Admin(); var op = await db.DeviceOperations.FindAsync(id) ?? throw new ApiException(404, "操作不存在");
            Guard.That(op.Action == "desktop-enable" && op.State == "succeeded", "远程桌面尚未准备好", 409);
            Guard.That(await db.Devices.AnyAsync(x => x.Id == op.DeviceId && x.Enabled), "设备已停用", 409);
            Guard.That(!await db.DeviceOperations.AnyAsync(x => x.DeviceId == op.DeviceId && (x.Action == "desktop-enable" || x.Action == "desktop-disable") && x.CreatedAt > op.CreatedAt && (x.State != "queued" && x.State != "expired")), "连接信息已更新，请重新读取最新操作", 409);
            db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "device.desktop.credentials.read", TargetId = id }); await db.SaveChangesAsync();
            return Results.Ok(ReadResult(op.ResultProtected, crypto.CreateProtector("siyue-device-operations-v1")));
        });
        agent.MapPost("/operations/claim", async (Data db, HttpContext context, IConfiguration config) => {
            var device = (Device)context.Items["device"]!;
            if (PrintAgentDispatch.HasExpired(context)) await Expire(db, device.Id);
            if (await db.DeviceOperations.AnyAsync(x => x.DeviceId == device.Id && x.State == "running")) return Results.NoContent();
            var op = await db.DeviceOperations.Where(x => x.DeviceId == device.Id && x.State == "queued" && x.ExpiresAt > Clock.Now).OrderBy(x => x.CreatedAt).FirstOrDefaultAsync();
            if (op == null) return Results.NoContent();
            var token = Secrets.New(); var won = await db.DeviceOperations.Where(x => x.Id == op.Id && x.State == "queued").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "running").SetProperty(x => x.ClaimTokenHash, Secrets.Hash(token)).SetProperty(x => x.ExpiresAt, Clock.Now.AddMinutes(10)));
            if (won != 1) return Results.NoContent();
            var printer = op.PrinterId.HasValue ? await db.Printers.FindAsync(op.PrinterId.Value) : null;
            return Results.Ok(new { op.Id, op.Action, queueName = printer?.QueueName, op.JobId, claimToken = token, desktopServer = config["RemoteDesktop:Server"], desktopKey = config["RemoteDesktop:Key"] });
        }).WithMetadata(new PrintAgentPoll(PrintAgentQueue.Operations));
        agent.MapPost("/operations/{id:guid}/report", async (Guid id, OperationReport r, Data db, HttpContext context, IDataProtectionProvider crypto) => {
            var device = (Device)context.Items["device"]!; Guard.That(r.State is "succeeded" or "failed" or "uncertain", "操作状态无效");
            Guard.That(r.Result.ValueKind == JsonValueKind.Object && r.Result.GetRawText().Length <= 65536, "诊断报告过大或格式无效");
            var hash = Secrets.Hash(r.ClaimToken); var op = await db.DeviceOperations.SingleOrDefaultAsync(x => x.Id == id && x.DeviceId == device.Id && x.ClaimTokenHash == hash) ?? throw new ApiException(409, "操作凭证无效");
            if (op.CompletedAt != null) { Guard.That(op.State == r.State, "操作已结束", 409); return Results.NoContent(); }
            Guard.That(op.State is "running" or "uncertain", "操作未领取", 409);
            op.State = r.State; op.ResultProtected = crypto.CreateProtector("siyue-device-operations-v1").Protect(r.Result.GetRawText()); op.CompletedAt = Clock.Now; await db.SaveChangesAsync(); return Results.NoContent();
        });
    }
    static JsonElement? VisibleResult(DeviceOperation op,IDataProtector protector) {
        var result=ReadResult(op.ResultProtected,protector);
        if(op.Action!="desktop-enable")return result;
        if(result.HasValue&&result.Value.TryGetProperty("message",out var message)&&message.ValueKind==JsonValueKind.String)
            return JsonSerializer.SerializeToElement(new {message=message.GetString()});
        return null;
    }
    static JsonElement? ReadResult(string? value, IDataProtector protector) => value == null ? null : JsonSerializer.Deserialize<JsonElement>(protector.Unprotect(value));
    static async Task Expire(Data db, Guid deviceId) {
        await db.DeviceOperations.Where(x => x.DeviceId == deviceId && x.State == "queued" && x.ExpiresAt < Clock.Now).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "expired"));
        // Never re-run a repair after a lost response: it may already have changed the machine.
        await db.DeviceOperations.Where(x => x.DeviceId == deviceId && x.State == "running" && x.ExpiresAt < Clock.Now).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "uncertain"));
    }
}
