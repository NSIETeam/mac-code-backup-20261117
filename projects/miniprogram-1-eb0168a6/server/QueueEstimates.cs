using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

public record QueueEstimate(string State, int? Ahead, long? WaitSeconds, long? RemainingSeconds,
    string Basis, string? Stage, int? Current, int? Total, DateTimeOffset? UpdatedAt, bool Delayed);

/// <summary>Optional bounded worker telemetry. Callers must authorize every requested entity first.</summary>
public static class QueueEstimates
{
    public const string MetricsKey = "system/document-queue-metrics-v1.json";
    const int MaxBytes = 65_536, MaxQueue = 10_000, MaxActive = 4;
    static readonly SemaphoreSlim Writes = new(1, 1);
    static long nextIdleHeartbeat;
    static readonly Dictionary<string, int> InitialSeconds = new() {
        ["print-prepare"] = 15, ["book-auto"] = 60, ["book"] = 180, ["book-split"] = 15, ["book-page-preview"] = 10,
        ["import"] = 180, ["previews"] = 90, ["export"] = 90, ["file"] = 60,
        ["support"] = 60, ["variants"] = 120, ["export-preview"] = 60, ["handout-preview"] = 90
    };
    static readonly HashSet<string> Stages = ["processing", "recognizing", "splitting", "outline", "outline-fallback", "fallback", "previews"];
    sealed record Sample(double Seconds, DateTimeOffset At);
    sealed record Active(Guid WorkId, string Kind, string ClaimHash, DateTimeOffset StartedAt,
        DateTimeOffset ProgressAt, DateTimeOffset StageStartedAt, string Stage, int Current, int Total, int StageInitialCurrent = 0, DateTimeOffset LastSeen = default);
    sealed class Metrics
    {
        public int Version { get; set; } = 2;
        public int Capacity { get; set; } = 1;
        public bool AdmissionAllowed { get; set; } = true;
        public DateTimeOffset AdmissionAt { get; set; }
        public DateTimeOffset HeartbeatAt { get; set; }
        public Active? Active { get; set; } // Read-only compatibility with the former single-worker format.
        public Dictionary<Guid, Active> Actives { get; set; } = new();
        public Dictionary<string, List<Sample>> Samples { get; set; } = new();
    }
    sealed record Item(Guid Id, Guid EntityId, string Kind, string State, string? Claim, DateTimeOffset? LeaseUntil, int Attempts);
    sealed record Duration(double? Seconds, string Basis, bool Delayed = false);

    // The worker and readers share this exact predicate and database Guid ordering.
    public static IOrderedQueryable<WorkItem> Executable(Data db, DateTimeOffset now) => db.Work
        .Where(x => x.State == "queued" && (x.LeaseUntil == null || x.LeaseUntil <= now) &&
            (x.Kind != "print-prepare" || db.PrintJobs.Any(j => j.Id == x.EntityId &&
                db.Documents.Any(d => d.Id == j.DocumentId && (d.State == "ready" || d.State == "failed")))))
        .OrderBy(x => x.Kind == "print-prepare" ? 0 : 1).ThenBy(x => x.Id);

    static IQueryable<Item> Items(IQueryable<WorkItem> source) => source.Select(x => new Item(x.Id, x.EntityId, x.Kind, x.State, x.Claim, x.LeaseUntil, x.Attempts));
    static string ClaimHash(string claim) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(claim)));
    static bool Matches(Active? active, Guid id, string? claim) => active != null && active.WorkId == id &&
        !string.IsNullOrEmpty(claim) && active.ClaimHash == ClaimHash(claim);

    static Active? Find(Metrics? value, Guid id) => value?.Actives.GetValueOrDefault(id);
    static Metrics Clean(Metrics value, DateTimeOffset now)
    {
        if (value.Version is not (1 or 2)) return new();
        value.Actives ??= new();
        if (value.Version == 1 && value.Active != null) {
            value.Actives[value.Active.WorkId] = value.Active with { LastSeen = value.HeartbeatAt };
            value.Capacity = 1;
        }
        value.Version = 2; value.Active = null; value.Capacity = Math.Clamp(value.Capacity, 1, 2);
        value.Samples ??= new();
        value.Samples = value.Samples.Where(x => InitialSeconds.ContainsKey(x.Key) && x.Value != null)
            .ToDictionary(x => x.Key, x => x.Value.Where(s => s != null && double.IsFinite(s.Seconds) && s.Seconds is > 0 and <= 86400 &&
                s.At <= now.AddMinutes(1) && s.At >= now.AddDays(-30)).OrderBy(s => s.At).TakeLast(20).ToList());
        value.Actives = value.Actives.Where(pair => {
            var a = pair.Value;
            return a != null && pair.Key != Guid.Empty && pair.Key == a.WorkId && InitialSeconds.ContainsKey(a.Kind) && Stages.Contains(a.Stage) && a.ClaimHash?.Length == 64 &&
                a.StartedAt <= now.AddMinutes(1) && a.ProgressAt >= a.StartedAt && a.ProgressAt <= now.AddMinutes(1) &&
                a.StageStartedAt >= a.StartedAt && a.StageStartedAt <= a.ProgressAt && a.Current >= 0 && a.Total >= a.Current && a.Total <= 100_000 &&
                a.StageInitialCurrent >= 0 && a.StageInitialCurrent <= a.Current && a.LastSeen >= a.StartedAt && a.LastSeen <= now.AddMinutes(1);
        }).OrderByDescending(x => x.Value.LastSeen).Take(MaxActive).ToDictionary(x => x.Key, x => x.Value);
        return value;
    }
    static async Task<Metrics?> Read(IObjects objects, CancellationToken ct)
    {
        try {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(2));
            await using var stream = await objects.Get(MetricsKey, timeout.Token);
            var bytes = new byte[MaxBytes + 1]; var length = 0;
            while (length < bytes.Length) { var got = await stream.ReadAsync(bytes.AsMemory(length), timeout.Token); if (got == 0) break; length += got; }
            if (length == 0 || length > MaxBytes) return null;
            var data = JsonSerializer.Deserialize<Metrics>(bytes.AsSpan(0, length), Json.Options);
            return data == null || data.Version is not (1 or 2) || data.Version == 2 && data.Capacity is not (1 or 2) || data.HeartbeatAt == default || data.HeartbeatAt > Clock.Now.AddMinutes(1) ? null : Clean(data, Clock.Now);
        } catch (Exception e) when (e is not OutOfMemoryException) { return null; }
    }
    static async Task Update(IObjects objects, Action<Metrics, DateTimeOffset> change, CancellationToken ct)
    {
        var entered = false;
        try {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(2));
            await Writes.WaitAsync(timeout.Token); entered = true;
            var now = Clock.Now; var data = await Read(objects, timeout.Token) ?? new(); change(data, now); Clean(data, now);
            var bytes = JsonSerializer.SerializeToUtf8Bytes(data, Json.Options); if (bytes.Length > MaxBytes) return;
            await using var stream = new MemoryStream(bytes); await objects.Put(MetricsKey, stream, "application/json", timeout.Token);
        } catch (Exception e) when (e is not OutOfMemoryException) { /* Telemetry must never fail document work. */ }
        finally { if (entered) Writes.Release(); }
    }
    public static Task Started(IObjects objects, WorkItem work, string claim, CancellationToken ct = default, int capacity = 1) => Update(objects, (data, now) => {
        data.HeartbeatAt = now; data.Capacity = Math.Clamp(capacity, 1, 2);
        if (InitialSeconds.ContainsKey(work.Kind)) data.Actives[work.Id] = new(work.Id, work.Kind, ClaimHash(claim), now, now, now, "processing", 0, 0, 0, now);
    }, ct);
    public static Task Progress(IObjects objects, Guid workId, string claim, HandoutProgress progress, CancellationToken ct = default) => Update(objects, (data, now) => {
        var a = Find(data, workId);
        if (!Matches(a, workId, claim) || !Stages.Contains(progress.Stage) || progress.Current < 0 || progress.Total < progress.Current || progress.Total > 100_000) return;
        var reset = a!.Stage != progress.Stage || a.Total != progress.Total || progress.Current < a.Current;
        data.Actives[workId] = a with { ProgressAt = now, LastSeen = now, StageStartedAt = reset ? now : a.StageStartedAt,
            StageInitialCurrent = reset ? progress.Current : a.StageInitialCurrent, Stage = progress.Stage, Current = progress.Current, Total = progress.Total };
        data.HeartbeatAt = now;
    }, ct);
    public static Task Heartbeat(IObjects objects, Guid workId, string claim, CancellationToken ct = default) => Update(objects, (data, now) => {
        var a = Find(data, workId);
        if (Matches(a, workId, claim)) { data.Actives[workId] = a! with { LastSeen = now }; data.HeartbeatAt = now; }
    }, ct);
    public static Task Finished(IObjects objects, Guid workId, string claim, bool succeeded, CancellationToken ct = default) => Update(objects, (data, now) => {
        var a = Find(data, workId); if (!Matches(a, workId, claim)) return;
        var seconds = (now - a!.StartedAt).TotalSeconds;
        if (succeeded && seconds is > 0 and <= 86400) {
            if (!data.Samples.TryGetValue(a.Kind, out var samples)) data.Samples[a.Kind] = samples = [];
            samples.Add(new(seconds, now)); if (samples.Count > 20) samples.RemoveRange(0, samples.Count - 20);
        }
        data.Actives.Remove(workId); data.HeartbeatAt = now;
    }, ct);
    public static Task Idle(IObjects objects, CancellationToken ct = default, int capacity = 1)
    {
        var ticks = Clock.Now.UtcTicks; var next = Interlocked.Read(ref nextIdleHeartbeat);
        if (ticks < next || Interlocked.CompareExchange(ref nextIdleHeartbeat, ticks + TimeSpan.FromSeconds(30).Ticks, next) != next) return Task.CompletedTask;
        return Update(objects, (data, now) => { data.Capacity = Math.Clamp(capacity, 1, 2); data.HeartbeatAt = now; }, ct);
    }

    public static Task Admission(IObjects objects, int capacity, bool allowed, CancellationToken ct = default)
    {
        var observedAt = Clock.Now;
        return Update(objects, (data, now) => {
            if (data.AdmissionAt > observedAt) return;
            data.Capacity = Math.Clamp(capacity, 1, 2); data.AdmissionAllowed = allowed;
            data.AdmissionAt = observedAt; data.HeartbeatAt = now;
        }, ct);
    }

    static Duration Typical(Metrics? metrics, string kind)
    {
        if (metrics?.Samples.TryGetValue(kind, out var samples) == true && samples.Count > 0) {
            var sorted = samples.Select(x => x.Seconds).Order().ToArray();
            return new(sorted.Length % 2 == 1 ? sorted[sorted.Length / 2] : (sorted[sorted.Length / 2 - 1] + sorted[sorted.Length / 2]) / 2, "observed");
        }
        return InitialSeconds.TryGetValue(kind, out var seconds) ? new(seconds, "initial") : new(null, "unknown");
    }
    static bool Fresh(Metrics? metrics, DateTimeOffset now) => metrics != null && metrics.HeartbeatAt >= now.AddSeconds(-100) && metrics.HeartbeatAt <= now.AddMinutes(1);
    static Duration Remaining(Item item, Metrics? metrics, DateTimeOffset now)
    {
        if (item.LeaseUntil == null || item.LeaseUntil <= now) return new(null, "unknown", true);
        var a = Find(metrics, item.Id);
        if (!Fresh(metrics, now) || !Matches(a, item.Id, item.Claim)) return new(null, "unknown", metrics != null && !Fresh(metrics, now));
        if (a!.LastSeen < now.AddSeconds(-100)) return new(null, "unknown", true); var typical = Typical(metrics, item.Kind);
        var silenceLimit = item.Kind is "book-auto" or "book-split" ? 180 : item.Kind is "import" or "previews" ? 360 : 900;
        if (now - a.ProgressAt > TimeSpan.FromSeconds(silenceLimit)) return new(null, "unknown", true);
        if (a.Total > 0 && a.Current == a.Total) return new(null, "unknown"); // Completed pages can still need parsing or publishing assets.
        if (a.Current > a.StageInitialCurrent && a.Current < a.Total) {
            var elapsed = (a.ProgressAt - a.StageStartedAt).TotalSeconds;
            if (elapsed > 0) return new(elapsed / (a.Current - a.StageInitialCurrent) * (a.Total - a.Current), "observed");
        }
        if (typical.Seconds == null) return typical;
        // Freeze the estimate between observed events, rather than inventing a per-second countdown.
        var left = typical.Seconds.Value - (a.ProgressAt - a.StartedAt).TotalSeconds;
        if (left <= 0 || (now - a.StartedAt).TotalSeconds > Math.Max(silenceLimit, typical.Seconds.Value * 2)) return new(null, typical.Basis, true);
        return new(left, typical.Basis);
    }
    static string Combine(string a, string b) => a == "unknown" || b == "unknown" ? "unknown" : a == "initial" || b == "initial" ? "initial" : "observed";
    static long? Rounded(double? seconds) => seconds == null || !double.IsFinite(seconds.Value) || seconds > 604800 ? null :
        seconds <= 0 ? 0 : (long)(Math.Ceiling(seconds.Value / (seconds > 300 ? 60 : 30)) * (seconds > 300 ? 60 : 30));

    public static async Task<Dictionary<Guid, QueueEstimate>> Batch(Data db, IObjects objects, IEnumerable<Guid> entityIds, CancellationToken ct = default)
    {
        var ids = entityIds.Where(id => id != Guid.Empty).Distinct().Take(500).ToArray(); var result = new Dictionary<Guid, QueueEstimate>(); if (ids.Length == 0) return result;
        var now = Clock.Now; var metrics = await Read(objects, ct);
        var queue = await Items(Executable(db, now).AsNoTracking()).Take(MaxQueue + 1).ToArrayAsync(ct);
        var active = await Items(db.Work.AsNoTracking().Where(w => w.State == "running")).Take(MaxActive + 1).ToArrayAsync(ct);
        var requested = await Items(db.Work.AsNoTracking().Where(w => ids.Contains(w.EntityId))).ToArrayAsync(ct);
        var positions = new Dictionary<Guid, (int Ahead, double? Wait, string Basis, bool Delayed)>();
        var activeIds = active.Select(x => x.Id).ToHashSet();
        var queued = queue.Take(MaxQueue).Where(x => !activeIds.Contains(x.Id)).ToArray();
        var ahead = active.Count(w => w.LeaseUntil > now);
        var reliable = Fresh(metrics, now) && metrics!.AdmissionAllowed && active.Length <= metrics.Capacity;
        var uncertain = metrics != null && !Fresh(metrics, now) || Fresh(metrics, now) && (!metrics!.AdmissionAllowed || active.Length > metrics.Capacity);
        foreach (var item in queued) positions[item.Id] = (ahead++, null, "unknown", uncertain);
        if (reliable) {
            var slots = active.Select(x => Remaining(x, metrics, now)).ToList();
            while (slots.Count < metrics!.Capacity) slots.Add(new(0, "observed"));
            var entityEnds = active.GroupBy(x => x.EntityId).ToDictionary(g => g.Key,
                g => g.Count() == 1 ? Remaining(g.First(), metrics, now) : new Duration(null, "unknown", true));
            var pending = new LinkedList<Item>(queued); var assigned = active.Count(w => w.LeaseUntil > now);
            while (pending.Count > 0) {
                var known = slots.Select((value, index) => (value, index)).Where(x => x.value.Seconds.HasValue).OrderBy(x => x.value.Seconds).ToArray();
                if (known.Length == 0) break;
                var slot = known[0]; var ready = slot.value;
                // A free lane is known to be available now. A positive future minimum is
                // unknowable if another lane has no valid remaining-duration estimate.
                if (ready.Seconds > 0 && known.Length != slots.Count) break;
                LinkedListNode<Item>? chosen = null; var examined = new List<Duration>(); var node = pending.First;
                for (var i = 0; node != null && i < 10; i++, node = node.Next) {
                    if (!entityEnds.TryGetValue(node.Value.EntityId, out var end) || end.Seconds <= ready.Seconds) { chosen = node; break; }
                    examined.Add(end);
                }
                if (chosen == null) {
                    var next = examined.Where(x => x.Seconds.HasValue).OrderBy(x => x.Seconds).FirstOrDefault();
                    if (next == null) break;
                    slots[slot.index] = new(next.Seconds, Combine(ready.Basis, next.Basis), ready.Delayed || next.Delayed);
                    continue;
                }
                var item = chosen.Value; pending.Remove(chosen);
                positions[item.Id] = (assigned++, ready.Seconds, ready.Basis, ready.Delayed);
                var duration = Typical(metrics, item.Kind);
                var finish = new Duration(duration.Seconds.HasValue ? ready.Seconds + duration.Seconds : null,
                    Combine(ready.Basis, duration.Basis), ready.Delayed || duration.Delayed);
                slots[slot.index] = finish; entityEnds[item.EntityId] = finish;
            }
            if (slots.Any(x => x.Delayed)) foreach (var item in pending) {
                var position = positions[item.Id]; positions[item.Id] = (position.Ahead, null, "unknown", true);
            }
        }
        foreach (var id in ids) {
            var own = requested.Where(w => w.EntityId == id).ToArray();
            var item = own.FirstOrDefault(w => w.State == "running") ?? queue.FirstOrDefault(w => w.EntityId == id) ??
                own.FirstOrDefault(w => w.State == "queued") ?? own.FirstOrDefault(w => w.State == "waiting") ?? own.FirstOrDefault(w => w.State == "done") ?? own.FirstOrDefault();
            if (item == null) { result[id] = new("unknown", null, null, null, "unknown", null, null, null, now, false); continue; }
            if (item.State == "running") {
                var remaining = Remaining(item, metrics, now);
                var observed = Find(metrics, item.Id); var progress = Matches(observed, item.Id, item.Claim) ? observed : null;
                result[id] = new("running", 0, 0, Rounded(remaining.Seconds), remaining.Basis, progress?.Stage,
                    progress?.Total > 0 ? progress.Current : null, progress?.Total > 0 ? progress.Total : null, now, remaining.Delayed); continue;
            }
            if (item.State == "queued" && positions.TryGetValue(item.Id, out var position)) {
                var duration = Typical(metrics, item.Kind); var completion = position.Wait.HasValue && duration.Seconds.HasValue ? position.Wait + duration.Seconds : null;
                result[id] = new("queued", position.Ahead, Rounded(position.Wait), Rounded(completion), Combine(position.Basis, duration.Basis), null, null, null, now, position.Delayed); continue;
            }
            // Old import/previews attempts may disagree. Only the entity's own state is authoritative after work ends.
            var state = item.State == "queued" ? item.LeaseUntil > now ? "retrying" : "waiting" : item.State is "done" or "failed" ? "unknown" : item.State;
            result[id] = new(state, null, null, null, "unknown", null, null, null, now, state == "retrying" || item.State == "queued" && queue.Length > MaxQueue);
        }
        return result;
    }
}
