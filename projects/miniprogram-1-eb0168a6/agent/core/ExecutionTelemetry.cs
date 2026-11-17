using System.Text.Json;
namespace Siyue.Printing;

// This record deliberately contains no document contents, URLs, tokens or user names.
public record ExecutionSnapshot(Guid JobId, string QueueName, DateTimeOffset StartedAt,
    DateTimeOffset UpdatedAt, string Phase = "preparing", int? ProcessId = null,
    int? ExitCode = null, DateTimeOffset? ProcessExitedAt = null, uint[]? WindowsJobIds = null,
    bool InQueue = false, uint QueueFlags = 0, int PagesPrinted = 0,
    DateTimeOffset? SystemCompletedAt = null, string Detail = "等待启动打印组件");

public static class ExecutionTelemetry
{
    public const string Version = "1.0.0";
    public static readonly string[] Phases = ["preparing", "process_started", "queued", "printing", "blocked", "awaiting_result", "system_completed", "failed", "uncertain"];
    public static uint PrinterToJobFlags(uint flags) =>
        ((flags & 1)!=0?1u:0u) | ((flags & (2u|8u|4096u))!=0?2u:0u) |
        ((flags & 16)!=0?64u:0u) | ((flags & 128)!=0?32u:0u) |
        ((flags & (524288u|4194304u))!=0?512u:0u);
    public static bool Active(ExecutionSnapshot x) => x.Phase is not ("system_completed" or "failed" or "uncertain");
    public static bool MatchesCompletion(ExecutionSnapshot x,string queue,uint windowsId,Guid? namedJob,DateTimeOffset at) =>
        queue.Equals(x.QueueName,StringComparison.OrdinalIgnoreCase) && at>=x.StartedAt &&
        (namedJob==x.JobId || windowsId>0 && (x.WindowsJobIds??[]).Contains(windowsId));
    public static ExecutionSnapshot Evaluate(ExecutionSnapshot x, DateTimeOffset now)
    {
        if (x.Phase == "failed" && x.ProcessId == null) return x;
        string phase, detail;
        if (x.ExitCode is not null and not 0) { phase = "uncertain"; detail = "打印进程异常退出，可能已部分提交；禁止自动重打"; }
        else if (x.ExitCode == 0 && x.SystemCompletedAt != null) { phase = "system_completed"; detail = "Windows 打印服务报告完成；实体出纸仍需现场或打印机作业日志确认"; }
        else if (x.InQueue && (x.QueueFlags & (1u | 2u | 4u | 32u | 64u | 512u)) != 0) { phase = "blocked"; detail = "Windows 作业报告暂停、脱机、缺纸或其他异常，请检查打印队列"; }
        else if (x.InQueue) { phase = (x.QueueFlags & 16) != 0 ? "printing" : "queued"; detail = phase == "printing" ? "Windows 正在处理该打印作业" : "已找到与本次任务对应的 Windows 作业"; }
        else if (x.ProcessExitedAt != null && now - x.ProcessExitedAt > TimeSpan.FromMinutes(5)) { phase = "uncertain"; detail = "打印进程已退出，但未取得对应的系统完成记录；请核实，禁止自动重打"; }
        else if (x.ProcessExitedAt == null && now - x.StartedAt > TimeSpan.FromMinutes(10)) { phase = "uncertain"; detail = "未取得打印进程结束证据，可能在执行时中断；请核实，禁止自动重打"; }
        else if (x.ExitCode == 0) { phase = "awaiting_result"; detail = "打印进程正常退出，继续等待 Windows 队列或完成记录"; }
        else if (x.ProcessId != null) { phase = "process_started"; detail = "已实际启动打印进程，正在等待驱动接收任务"; }
        else { phase = "preparing"; detail = "正在准备打印组件"; }
        return x with { Phase = phase, Detail = detail, UpdatedAt = now };
    }
}

public sealed class ExecutionStore
{
    readonly object gate = new(); readonly string directory;
    readonly Dictionary<Guid, ExecutionSnapshot> jobs = [];
    static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public ExecutionStore(string directory)
    {
        this.directory = directory; Directory.CreateDirectory(directory);
        foreach (var p in Directory.EnumerateFiles(directory, "*.json").OrderByDescending(File.GetLastWriteTimeUtc).Take(200))
            try { if (new FileInfo(p).Length > 16384) continue; var x = JsonSerializer.Deserialize<ExecutionSnapshot>(File.ReadAllText(p), Json); if (x != null && x.StartedAt > DateTimeOffset.UtcNow.AddDays(-2)) jobs[x.JobId] = x; } catch { }
    }
    public void Start(Guid id, string queue)
    { lock (gate) { if (!jobs.ContainsKey(id)) Save(new(id, queue, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow)); } }
    public void Change(Guid id, Func<ExecutionSnapshot, ExecutionSnapshot> update)
    { lock (gate) { if (jobs.TryGetValue(id, out var x)) Save(update(x)); } }
    public ExecutionSnapshot[] Snapshot()
    { lock (gate) return jobs.Values.OrderByDescending(x => x.StartedAt).Take(100).ToArray(); }
    void Save(ExecutionSnapshot x)
    {
        jobs[x.JobId] = x;
        var path = Path.Combine(directory, x.JobId + ".json");
        // Atomic receipt survives a lost HTTP response or a service restart.
        File.WriteAllText(path + ".tmp", JsonSerializer.Serialize(x, Json)); File.Move(path + ".tmp", path, true);
        foreach (var old in jobs.Values.OrderByDescending(j => j.StartedAt).Skip(200).ToArray()) { jobs.Remove(old.JobId); try { File.Delete(Path.Combine(directory, old.JobId + ".json")); } catch { } }
    }
}
