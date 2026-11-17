namespace Siyue;

public static class WorkerEntityGate
{
    static readonly HashSet<Guid> Active = [];
    public static IDisposable? TryEnter(Guid id)
    {
        lock (Active) { return Active.Add(id) ? new Lease(id) : null; }
    }
    sealed class Lease(Guid id) : IDisposable
    {
        bool disposed;
        public void Dispose() { lock (Active) { if (!disposed) { Active.Remove(id); disposed = true; } } }
    }
}

// Admission limits apply before claiming a job. An admitted job keeps its lease and
// finishes normally even if pressure rises; no task is killed to free a lane.
public static class WorkerScheduler
{
    public static async Task Run(int concurrency, Func<CancellationToken, Task<bool>> tick,
        Func<int, string?> blocked, Action<string?> report, CancellationToken ct, TimeSpan? idle = null)
    {
        var delay = idle ?? TimeSpan.FromSeconds(2);
        using var gate = new SemaphoreSlim(1, 1);
        var active = 0; string? lastReason = null;
        long nextAdmission = 0;
        async Task Lane()
        {
            while (!ct.IsCancellationRequested) {
                var admitted = false;
                await gate.WaitAsync(ct);
                try {
                    var reason = blocked(active);
                    if (reason != lastReason) { report(reason); lastReason = reason; }
                    if (reason == null && Environment.TickCount64 >= nextAdmission) { active++; admitted = true; nextAdmission = Environment.TickCount64 + 250; }
                } finally { gate.Release(); }
                var worked = false;
                if (admitted) {
                    try { worked = await tick(ct); }
                    finally { await gate.WaitAsync(CancellationToken.None); try { active--; } finally { gate.Release(); } }
                }
                // Stagger admissions so a newly started conversion can become visible
                // in the memory sample before another job is taken.
                await Task.Delay(worked ? TimeSpan.FromMilliseconds(250) : delay, ct);
            }
        }
        try { await Task.WhenAll(Enumerable.Range(0, Math.Clamp(concurrency, 1, 2)).Select(_ => Lane())); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
    }
}

public record WorkerResources(long AvailableMb, long UsedMb, long LimitMb, long FreeDiskMb);

public sealed class WorkerAdmission(IConfiguration config)
{
    const long Mb = 1024 * 1024;
    public static string? Check(WorkerResources r, int active, long reserveMb = 1024, long extraMb = 384,
        long headroomMb = 384, long freeDiskMb = 4096)
    {
        if (r.AvailableMb < reserveMb + active * extraMb) return "host-memory";
        if (r.LimitMb > 0 && r.LimitMb - r.UsedMb < headroomMb + active * 256) return "worker-memory";
        if (r.FreeDiskMb < freeDiskMb) return "disk-space";
        return null;
    }
    public string? Blocked(int active)
    {
        if (config["Worker:DrainFile"] is string drain && File.Exists(drain)) return "draining";
        if (!OperatingSystem.IsLinux()) return null;
        try {
            var disk = new DriveInfo(Path.GetTempPath()).AvailableFreeSpace / Mb;
            return Check(ReadResources("/proc/meminfo", "/sys/fs/cgroup", disk), active,
                config.GetValue("Worker:ReserveMemoryMb", 1024), config.GetValue("Worker:ExtraTaskMemoryMb", 384),
                config.GetValue("Worker:HeadroomMb", 384), config.GetValue("Worker:FreeDiskMb", 4096));
        } catch (Exception e) when (e is IOException or UnauthorizedAccessException or FormatException or KeyNotFoundException) {
            return "resource-probe-unavailable";
        }
    }
    public static WorkerResources ReadResources(string memoryInfoPath, string cgroupRoot, long freeDiskMb)
    {
        var available = ReadCounters(memoryInfoPath)["MemAvailable:"] / 1024;
        var v2 = File.Exists(Path.Combine(cgroupRoot, "memory.current"));
        var root = v2 ? cgroupRoot : Path.Combine(cgroupRoot, "memory");
        // Some v1 runtimes mount the memory controller directly at the cgroup root.
        if (!v2 && !File.Exists(Path.Combine(root, "memory.usage_in_bytes"))) root = cgroupRoot;
        var current = long.Parse(File.ReadAllText(Path.Combine(root, v2 ? "memory.current" : "memory.usage_in_bytes")));
        var maximum = File.ReadAllText(Path.Combine(root, v2 ? "memory.max" : "memory.limit_in_bytes")).Trim();
        var limitBytes = v2 && maximum == "max" ? 0 : long.Parse(maximum);
        if (current < 0 || limitBytes < 0 || available < 0) throw new FormatException("Invalid resource counter");
        // v1 represents an unlimited memory cgroup as LONG_MAX rounded to a page.
        if (!v2 && limitBytes >= long.MaxValue / 2) limitBytes = 0;
        var counters = ReadCounters(Path.Combine(root, "memory.stat"));
        // v1 usage includes descendants; subtract only their reclaimable clean cache.
        var inactiveFile = !v2 && counters.TryGetValue("total_inactive_file", out var total)
            ? total : counters.GetValueOrDefault("inactive_file");
        if (inactiveFile < 0) throw new FormatException("Invalid cache counter");
        return new(available, Math.Max(0, current - inactiveFile) / Mb, limitBytes / Mb, freeDiskMb);
    }
    static Dictionary<string, long> ReadCounters(string path) => File.ReadLines(path)
        .Select(x => x.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries))
        .Where(x => x.Length >= 2 && long.TryParse(x[1], out _)).ToDictionary(x => x[0], x => long.Parse(x[1]));
}
