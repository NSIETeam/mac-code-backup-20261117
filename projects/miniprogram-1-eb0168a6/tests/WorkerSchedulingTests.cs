using Siyue;
using Xunit;

public class WorkerSchedulingTests
{
    [Fact]
    public void Same_document_cannot_run_twice_and_finishing_releases_it()
    {
        var id = Guid.NewGuid(); var first = WorkerEntityGate.TryEnter(id);
        Assert.NotNull(first); Assert.Null(WorkerEntityGate.TryEnter(id));
        using var other = WorkerEntityGate.TryEnter(Guid.NewGuid()); Assert.NotNull(other);
        first!.Dispose(); using var again = WorkerEntityGate.TryEnter(id); Assert.NotNull(again);
        first.Dispose(); Assert.Null(WorkerEntityGate.TryEnter(id));
    }

    [Fact]
    public async Task Two_jobs_overlap_without_exceeding_the_cap()
    {
        using var cancel = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var active = 0; var peak = 0; var done = 0;
        await WorkerScheduler.Run(20, async ct => {
            var n = Interlocked.Increment(ref active);
            int old; do { old = peak; if (old >= n) break; } while (Interlocked.CompareExchange(ref peak, n, old) != old);
            try { await Task.Delay(400, ct); if (Interlocked.Increment(ref done) >= 6) cancel.Cancel(); return true; }
            finally { Interlocked.Decrement(ref active); }
        }, _ => null, _ => { }, cancel.Token, TimeSpan.FromMilliseconds(10));
        Assert.Equal(2, peak); Assert.True(done >= 6); Assert.Equal(0, active);
    }

    [Fact]
    public async Task Pressure_blocks_new_work_then_recovers_without_cancelling_running_work()
    {
        using var cancel = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var pressure = 1; var started = 0;
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var finish = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var completed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var task = WorkerScheduler.Run(2, async ct => {
            Interlocked.Increment(ref started); Volatile.Write(ref pressure, 1); entered.TrySetResult();
            await finish.Task.WaitAsync(ct); completed.TrySetResult(); return true;
        }, _ => Volatile.Read(ref pressure) == 1 ? "host-memory" : null, _ => { }, cancel.Token, TimeSpan.FromMilliseconds(10));
        await Task.Delay(60); Assert.Equal(0, started);
        Volatile.Write(ref pressure, 0); await entered.Task.WaitAsync(cancel.Token);
        await Task.Delay(60); Assert.Equal(1, started); Assert.False(completed.Task.IsCompleted);
        finish.SetResult(); await completed.Task.WaitAsync(cancel.Token); cancel.Cancel(); await task;
    }

    [Theory]
    [InlineData(900, 200, 1536, 10000, 0, "host-memory")]
    [InlineData(1200, 200, 1536, 10000, 0, null)]
    [InlineData(1200, 200, 1536, 10000, 1, "host-memory")]
    [InlineData(2000, 1200, 1536, 10000, 0, "worker-memory")]
    [InlineData(2000, 950, 1536, 10000, 1, "worker-memory")]
    [InlineData(2000, 300, 1536, 3000, 0, "disk-space")]
    [InlineData(2000, 300, 1536, 10000, 1, null)]
    public void Resource_headroom_and_disk_space_bound_admission(long host, long used, long limit, long disk, int active, string? reason)
        => Assert.Equal(reason, WorkerAdmission.Check(new(host, used, limit, disk), active));

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, false)]
    [InlineData(false, true)]
    public void Both_cgroup_layouts_apply_the_same_memory_headroom(bool v2, bool flat)
    {
        var root = Path.Combine(Path.GetTempPath(), "worker-cgroup-" + Guid.NewGuid().ToString("N"));
        try {
            var group = !v2 && !flat ? Path.Combine(root, "memory") : root; Directory.CreateDirectory(group);
            File.WriteAllText(Path.Combine(root, "meminfo"), "MemAvailable: 2097152 kB\n");
            File.WriteAllText(Path.Combine(group, v2 ? "memory.current" : "memory.usage_in_bytes"), (500L * 1024 * 1024).ToString());
            File.WriteAllText(Path.Combine(group, v2 ? "memory.max" : "memory.limit_in_bytes"), (768L * 1024 * 1024).ToString());
            File.WriteAllText(Path.Combine(group, "memory.stat"), v2 ? "inactive_file 209715200\n" : "inactive_file 0\ntotal_inactive_file 209715200\n");
            var sample = WorkerAdmission.ReadResources(Path.Combine(root, "meminfo"), root, 10000);
            Assert.Equal(new WorkerResources(2048, 300, 768, 10000), sample);
            Assert.Null(WorkerAdmission.Check(sample, 0)); Assert.Equal("worker-memory", WorkerAdmission.Check(sample, 1));
        } finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    [Theory]
    [InlineData(true, "max")]
    [InlineData(false, "9223372036854771712")]
    public void Unlimited_containers_still_obey_host_memory_admission(bool v2, string limit)
    {
        var root = Path.Combine(Path.GetTempPath(), "worker-cgroup-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        try {
            File.WriteAllText(Path.Combine(root, "meminfo"), "MemAvailable: 524288 kB\n");
            File.WriteAllText(Path.Combine(root, v2 ? "memory.current" : "memory.usage_in_bytes"), "104857600");
            File.WriteAllText(Path.Combine(root, v2 ? "memory.max" : "memory.limit_in_bytes"), limit);
            File.WriteAllText(Path.Combine(root, "memory.stat"), "inactive_file 0\n");
            var sample = WorkerAdmission.ReadResources(Path.Combine(root, "meminfo"), root, 10000);
            Assert.Equal(0, sample.LimitMb); Assert.Equal("host-memory", WorkerAdmission.Check(sample, 0));
            File.WriteAllText(Path.Combine(root, v2 ? "memory.current" : "memory.usage_in_bytes"), "invalid");
            Assert.Throws<FormatException>(() => WorkerAdmission.ReadResources(Path.Combine(root, "meminfo"), root, 10000));
        } finally { Directory.Delete(root, true); }
    }
}
