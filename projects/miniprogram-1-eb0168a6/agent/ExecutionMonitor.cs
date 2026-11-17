using System.Diagnostics.Eventing.Reader;
using System.Collections.Concurrent;
using Siyue.Printing;
namespace Siyue.Agent;

// Runs alongside printing; a slow printer or a lost receipt never causes a second print.
sealed class ExecutionMonitor : IDisposable
{
    readonly ExecutionStore store;
    readonly ConcurrentDictionary<string,(uint Flags,DateTimeOffset At)> printerFlags=new(StringComparer.OrdinalIgnoreCase);
    public void ObservePrinters(ProbeResult probe) {
        foreach(var p in probe.Printers)printerFlags[p.QueueName]=((uint)p.RawStatus,DateTimeOffset.UtcNow);
    }
    record Completion(string Queue,uint Id,Guid? NamedJob,DateTimeOffset At);
    readonly object eventGate=new();readonly List<Completion> completed=[];
    EventLogWatcher? events; public string? Error { get; private set; }
    public ExecutionMonitor(string path) => store = new(path);
    public ExecutionSnapshot[] Snapshot() => store.Snapshot();
    void Change(Guid id, Func<ExecutionSnapshot, ExecutionSnapshot> update)
    { try { store.Change(id, update); } catch { Error = "任务执行回执写入失败，请检查磁盘和权限"; } }
    public void Preparing(Claim job) { try { store.Start(job.Id, job.QueueName); } catch { Error = "任务执行回执写入失败，请检查磁盘和权限"; } }
    public void Started(Guid id, int pid) => Change(id, x => ExecutionTelemetry.Evaluate(x with { ProcessId = pid }, DateTimeOffset.UtcNow));
    public void LaunchFailed(Guid id, int code) => Change(id, x => x with { Phase = "failed", UpdatedAt = DateTimeOffset.UtcNow, Detail = "打印进程未能启动，系统错误码 0x" + code.ToString("X8") + "；检查组件文件、权限或安全软件拦截记录" });
    public void Exited(Guid id, int code) => Change(id, x => ExecutionTelemetry.Evaluate(x with { ExitCode = code, ProcessExitedAt = DateTimeOffset.UtcNow }, DateTimeOffset.UtcNow));
    ExecutionSnapshot MatchCompletion(ExecutionSnapshot x)
    {
        Completion? match;lock(eventGate)match=completed.LastOrDefault(e=>ExecutionTelemetry.MatchesCompletion(x,e.Queue,e.Id,e.NamedJob,e.At));
        return match==null?x:ExecutionTelemetry.Evaluate(x with {SystemCompletedAt=match.At,WindowsJobIds=(x.WindowsJobIds??[]).Concat(match.Id>0?new[]{match.Id}:[]).Distinct().Take(16).ToArray()},DateTimeOffset.UtcNow);
    }
    public void ObservedJobs(Guid id, string ids) => Change(id, x => MatchCompletion(x with { WindowsJobIds = (x.WindowsJobIds ?? []).Concat(ids.Split(',').Select(s=>uint.TryParse(s,out var n)?n:0).Where(n=>n>0)).Distinct().Take(16).ToArray(), UpdatedAt=DateTimeOffset.UtcNow }));
    public void Interrupted(Guid id) => Change(id, x => x with { Phase = "uncertain", ExitCode = -1, ProcessExitedAt = DateTimeOffset.UtcNow, UpdatedAt = DateTimeOffset.UtcNow, Detail = "打印进程中断或超时；可能已提交部分内容，禁止自动重打" });
    public async Task Run(CancellationToken ct)
    {
        try {
            var query = new EventLogQuery("Microsoft-Windows-PrintService/Operational", PathType.LogName,
                "*[System[(EventID=307) and TimeCreated[timediff(@SystemTime) <= 172800000]]]");
            // Replay retained events after restart, then subscribe to new events (no event-log polling).
            events = new EventLogWatcher(query, null, true);
            events.EventRecordWritten += (_, e) => {
                if (e.EventException != null) { Error = "无法读取 Windows 打印完成事件，完成状态将保持待核实"; return; }
                using var record = e.EventRecord; if (record == null) return;
                try {
                    var values = record.Properties.Select(p => p.Value?.ToString() ?? "").ToArray();
                    var at = new DateTimeOffset(record.TimeCreated?.ToUniversalTime() ?? DateTime.UtcNow);
                    var snapshots=store.Snapshot();var number=values.Length>0 && uint.TryParse(values[0],out var n)?n:0;
                    foreach(var queue in snapshots.Select(x=>x.QueueName).Distinct(StringComparer.OrdinalIgnoreCase)) {
                        if(!values.Any(v=>v.Equals(queue,StringComparison.OrdinalIgnoreCase)))continue;
                        var named=snapshots.FirstOrDefault(x=>values.Any(v=>v.Contains(x.JobId.ToString(),StringComparison.OrdinalIgnoreCase)))?.JobId;
                        lock(eventGate){completed.Add(new(queue,number,named,at));if(completed.Count>512)completed.RemoveRange(0,completed.Count-512);}
                    }
                    foreach(var x in snapshots)Change(x.JobId,MatchCompletion);
                } catch { Error = "打印完成事件读取失败，未确认结果"; }
            };
            events.Enabled = true;
        } catch { Error = "Windows 打印详细日志不可用，无法确认系统完成；请启用日志"; }
        while (!ct.IsCancellationRequested) {
            foreach (var x in store.Snapshot().Where(x => ExecutionTelemetry.Active(x) && x.StartedAt > DateTimeOffset.UtcNow.AddDays(-1))) {
                try {
                    var evidence = SpoolerEvidence.ReadJobs(x.QueueName, x.JobId);
                    var inherited=printerFlags.TryGetValue(x.QueueName,out var printer) && DateTimeOffset.UtcNow-printer.At<TimeSpan.FromSeconds(15) ? ExecutionTelemetry.PrinterToJobFlags(printer.Flags) : 0u;
                    Change(x.JobId, j => MatchCompletion(ExecutionTelemetry.Evaluate(j with {
                        InQueue = evidence.Length > 0, QueueFlags = evidence.Aggregate(inherited, (flags, v) => flags | v.Flags),
                        WindowsJobIds = (j.WindowsJobIds ?? []).Concat(evidence.Select(v => v.Id)).Distinct().Take(16).ToArray(),
                        PagesPrinted = Math.Max(j.PagesPrinted, evidence.Sum(v => (int)Math.Min(v.PagesPrinted, 100000)))
                    }, DateTimeOffset.UtcNow)));
                } catch { Error = "暂时无法读取打印队列，现有任务不会自动重打"; }
            }
            await Task.Delay(2000, ct);
        }
    }
    public void Dispose() { events?.Dispose(); }
}
