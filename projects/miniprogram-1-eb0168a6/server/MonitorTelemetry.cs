using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using Siyue.Printing;
namespace Siyue;
public static class MonitorTelemetry
{
    static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);
    public static ExecutionSnapshot? Read(string? json) { try { return json == null ? null : JsonSerializer.Deserialize<ExecutionSnapshot>(json, Options); } catch { return null; } }
    public static async Task Update(Data db, Device device, HeartbeatInput input, DateTimeOffset now)
    {
        if (input.MonitorVersion == null) return; // Old agents remain compatible.
        Guard.That(input.MonitorVersion.Length <= 40 && (input.MonitorError?.Length ?? 0) <= 500, "监控状态过长");
        var records = input.Executions ?? [];
        Guard.That(records.Length <= 100 && records.Select(x=>x.JobId).Distinct().Count()==records.Length, "执行记录过多或重复");
        var ids = records.Select(x=>x.JobId).ToArray();
        var jobs = await db.PrintJobs.Where(j=>ids.Contains(j.Id)).ToListAsync();
        Guard.That(jobs.All(j=>j.DeviceId==device.Id), "不能上报其他电脑的任务", 403);
        foreach (var x in records) {
            Guard.That(x.QueueName.Length is > 0 and <= 200 && x.Detail.Length <= 500 && ExecutionTelemetry.Phases.Contains(x.Phase) &&
                (x.WindowsJobIds?.Length ?? 0) <= 16 && x.PagesPrinted is >= 0 and <= 100000 && (x.ProcessId == null || x.ProcessId > 0) &&
                x.StartedAt <= x.UpdatedAt && x.UpdatedAt <= now.AddMinutes(2), "执行证据无效");
            Guard.That(x.Phase != "system_completed" || x.ExitCode == 0 && x.ProcessId != null && x.SystemCompletedAt != null, "缺少系统完成证据");
            var job = jobs.FirstOrDefault(j=>j.Id==x.JobId); if (job == null) continue; // Removed historical jobs must not break device heartbeats.
            Guard.That(job.State is not ("queued" or "claimed"), "尚未获准执行的任务不能上报运行证据", 409);
            var printer = await db.Printers.FindAsync(job.PrinterId);
            Guard.That(printer?.QueueName.Equals(x.QueueName,StringComparison.OrdinalIgnoreCase)==true, "打印队列与任务不匹配");
            var old = Read(job.ExecutionJson);
            if (old != null && (x.UpdatedAt <= old.UpdatedAt || old.Phase == "system_completed" && x.Phase != "system_completed")) continue;
            job.ExecutionJson=JsonSerializer.Serialize(x,Options); job.ExecutionAt=now;
        }
        device.MonitorVersion=input.MonitorVersion; device.MonitorLastSeen=now; device.MonitorError=input.MonitorError;
    }
}
