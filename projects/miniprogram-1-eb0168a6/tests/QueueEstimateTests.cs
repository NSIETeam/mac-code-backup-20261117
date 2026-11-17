using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class QueueEstimateTests
{
    sealed class Objects : IObjects
    {
        public byte[]? Bytes; public bool Broken;
        public async Task Put(string key, Stream stream, string type, CancellationToken ct = default) { if (Broken) throw new IOException("metrics unavailable"); using var memory = new MemoryStream(); await stream.CopyToAsync(memory, ct); Bytes = memory.ToArray(); }
        public Task<Stream> Get(string key, CancellationToken ct = default) => Broken ? throw new IOException("metrics unavailable") : Bytes == null ? throw new FileNotFoundException() : Task.FromResult<Stream>(new MemoryStream(Bytes));
        public void Set(object value) => Bytes = JsonSerializer.SerializeToUtf8Bytes(value, Json.Options);
    }
    sealed class Db : IAsyncDisposable
    {
        public SqliteConnection Connection = new("Data Source=:memory:"); public Data Data = null!; public Objects Objects = new();
        public static async Task<Db> Create() { var x = new Db(); await x.Connection.OpenAsync(); x.Data = new(new DbContextOptionsBuilder<Data>().UseSqlite(x.Connection).Options); await x.Data.Database.EnsureCreatedAsync(); return x; }
        public async ValueTask DisposeAsync() { await Data.DisposeAsync(); await Connection.DisposeAsync(); }
    }
    static Guid Id(int n) => Guid.Parse($"00000000-0000-0000-0000-{n:D12}");
    static WorkItem Work(int n, string kind = "import", string state = "queued") => new() { Id = Id(n), EntityId = Guid.NewGuid(), Kind = kind, State = state };
    static string Hash(string value) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    static void Metrics(Objects objects, WorkItem? work = null, int current = 0, int total = 0, int elapsed = 0, int heartbeatAge = 0,
        Dictionary<string, object[]>? samples = null, string? claim = null, int progressAge = 0)
    {
        var now = Clock.Now; var progressAt = now.AddSeconds(-progressAge); var start = progressAt.AddSeconds(-elapsed);
        objects.Set(new { version = 1, heartbeatAt = now.AddSeconds(-heartbeatAge), samples = samples ?? new(), active = work == null ? null : (object)new {
            workId = work.Id, kind = work.Kind, claimHash = Hash(claim ?? work.Claim!), startedAt = start, stageStartedAt = start, progressAt, stage = "recognizing", current, total
        } });
    }
    static Dictionary<string, object[]> History(params (string Kind, double Seconds)[] rows) => rows.ToDictionary(x => x.Kind, x => new object[] { new { seconds = x.Seconds, at = Clock.Now.AddMinutes(-1) } });

    [Fact]
    public async Task UsesRealWorkerOrderAndExcludesDependenciesBackoffAndWaiting()
    {
        await using var f = await Db.Create(); var a = Work(1); var target = Work(2); var prep = Work(9, "print-prepare");
        var blocked = Work(3, "print-prepare"); var backoff = Work(4); backoff.LeaseUntil = Clock.Now.AddMinutes(1); var waiting = Work(5, state: "waiting");
        var ready = new ExportDocument { State = "ready" }; var processing = new ExportDocument { State = "processing" };
        f.Data.AddRange(a, target, prep, blocked, backoff, waiting, ready, processing,
            new PrintJob { Id = prep.EntityId, DocumentId = ready.Id, RequestKey = "ready" }, new PrintJob { Id = blocked.EntityId, DocumentId = processing.Id, RequestKey = "blocked" });
        await f.Data.SaveChangesAsync(); Metrics(f.Objects, samples: History(("import", 120), ("print-prepare", 20)));
        Assert.Equal(new[] { prep.Id, a.Id, target.Id }, await QueueEstimates.Executable(f.Data, Clock.Now).Select(w => w.Id).ToArrayAsync());
        var estimates = await QueueEstimates.Batch(f.Data, f.Objects, new[] { target.EntityId, prep.EntityId, blocked.EntityId, backoff.EntityId, waiting.EntityId });
        Assert.Equal(2, estimates[target.EntityId].Ahead); Assert.Equal(150, estimates[target.EntityId].WaitSeconds); Assert.Equal(270, estimates[target.EntityId].RemainingSeconds); Assert.Equal("observed", estimates[target.EntityId].Basis);
        Assert.Equal(0, estimates[prep.EntityId].Ahead); Assert.Equal(0, estimates[prep.EntityId].WaitSeconds);
        Assert.Equal("waiting", estimates[blocked.EntityId].State); Assert.Null(estimates[blocked.EntityId].WaitSeconds);
        Assert.Equal("retrying", estimates[backoff.EntityId].State); Assert.Null(estimates[backoff.EntityId].WaitSeconds);
        Assert.Equal("waiting", estimates[waiting.EntityId].State); Assert.Null(estimates[waiting.EntityId].RemainingSeconds);
    }

    [Fact]
    public async Task ActualProgressChangesRemainingButReadTimeDoesNotInventCountdown()
    {
        await using var f = await Db.Create(); var active = Work(1, state: "running"); active.Claim = "lease"; active.LeaseUntil = Clock.Now.AddMinutes(5); var next = Work(2);
        f.Data.AddRange(active, next); await f.Data.SaveChangesAsync(); Metrics(f.Objects, active, 2, 4, 60, samples: History(("import", 120)));
        var first = await QueueEstimates.Batch(f.Data, f.Objects, new[] { active.EntityId, next.EntityId });
        Assert.Equal(60, first[active.EntityId].RemainingSeconds); Assert.Equal("observed", first[active.EntityId].Basis); Assert.Equal(0, first[active.EntityId].WaitSeconds);
        Assert.Equal(60, first[next.EntityId].WaitSeconds); Assert.Equal(180, first[next.EntityId].RemainingSeconds); Assert.Equal(1, first[next.EntityId].Ahead);
        var again = await QueueEstimates.Batch(f.Data, f.Objects, new[] { active.EntityId }); Assert.Equal(first[active.EntityId].RemainingSeconds, again[active.EntityId].RemainingSeconds);
        await QueueEstimates.Progress(f.Objects, active.Id, "lease", new("recognizing", 3, 4, "private filename must never be stored"));
        var advanced = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { active.EntityId }))[active.EntityId];
        Assert.Equal(30, advanced.RemainingSeconds); Assert.Equal(3, advanced.Current); Assert.Equal(4, advanced.Total);
        Assert.DoesNotContain("private filename", Encoding.UTF8.GetString(f.Objects.Bytes!));
    }

    [Fact]
    public async Task NoHistoryIsExplicitlyInitialAndMissingMetricsHasNoEta()
    {
        await using var f = await Db.Create(); var work = Work(1); f.Data.Add(work); await f.Data.SaveChangesAsync(); Metrics(f.Objects);
        var initial = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId }))[work.EntityId];
        Assert.Equal("initial", initial.Basis); Assert.Equal(0, initial.WaitSeconds); Assert.Equal(180, initial.RemainingSeconds);
        f.Objects.Bytes = null;
        var missing = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId }))[work.EntityId];
        Assert.Equal("unknown", missing.Basis); Assert.Null(missing.WaitSeconds); Assert.Null(missing.RemainingSeconds); Assert.False(missing.Delayed);
    }

    [Theory]
    [InlineData("failed")]
    [InlineData("done")]
    [InlineData("waiting")]
    public async Task TerminalAndDependentWorkNeverGetsACountdown(string state)
    {
        await using var f = await Db.Create(); var work = Work(1, state: state); f.Data.Add(work); await f.Data.SaveChangesAsync(); Metrics(f.Objects, samples: History(("import", 30)));
        var estimate = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId }))[work.EntityId];
        Assert.Equal(state == "waiting" ? state : "unknown", estimate.State); Assert.Null(estimate.Ahead); Assert.Null(estimate.WaitSeconds); Assert.Null(estimate.RemainingSeconds);
    }

    [Fact]
    public async Task CompletedPagesAreFinishingNotAutomaticallyDelayedOrDone()
    {
        await using var f = await Db.Create(); var work = Work(1, state: "running"); work.Claim = "lease"; work.LeaseUntil = Clock.Now.AddMinutes(5);
        f.Data.Add(work); await f.Data.SaveChangesAsync(); Metrics(f.Objects, work, 10, 10, 600, samples: History(("import", 60)));
        var result = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId }))[work.EntityId];
        Assert.Equal("running", result.State); Assert.False(result.Delayed); Assert.Null(result.RemainingSeconds); Assert.Equal(10, result.Current);
    }

    [Fact]
    public async Task ProgressFirstObservedMidStageUsesOnlyNewlyProcessedUnits()
    {
        await using var f = await Db.Create(); var work = Work(1, state: "running"); work.Claim = "lease"; work.LeaseUntil = Clock.Now.AddMinutes(5); f.Data.Add(work); await f.Data.SaveChangesAsync();
        var now = Clock.Now;
        f.Objects.Set(new { version = 1, heartbeatAt = now, samples = new { }, active = new { workId = work.Id, kind = work.Kind, claimHash = Hash("lease"), startedAt = now.AddMinutes(-2), progressAt = now, stageStartedAt = now.AddSeconds(-30), stage = "recognizing", current = 3, total = 10, stageInitialCurrent = 3 } });
        await QueueEstimates.Progress(f.Objects, work.Id, "lease", new("recognizing", 4, 10, ""));
        var result = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId }))[work.EntityId];
        Assert.Equal("observed", result.Basis); Assert.InRange(result.RemainingSeconds!.Value, 180L, 210L);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task MixedHistoricalOutcomesDoNotOverrideBusinessState(bool doneFirst)
    {
        await using var f = await Db.Create(); var a = Work(1, state: doneFirst ? "done" : "failed"); var b = Work(2, "previews", doneFirst ? "failed" : "done"); b.EntityId = a.EntityId;
        f.Data.AddRange(a, b); await f.Data.SaveChangesAsync(); Metrics(f.Objects);
        var result = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { a.EntityId }))[a.EntityId]; Assert.Equal("unknown", result.State); Assert.Null(result.RemainingSeconds);
    }

    [Theory]
    [InlineData("expired-lease")]
    [InlineData("stale-heartbeat")]
    [InlineData("silent-progress")]
    public async Task StaleOrExpiredWorkCannotGiveFalseCompletionTimes(string condition)
    {
        await using var f = await Db.Create(); var work = Work(1, state: "running"); work.Claim = "lease"; work.LeaseUntil = Clock.Now.AddMinutes(condition == "expired-lease" ? -1 : 5);
        var next = Work(2); f.Data.AddRange(work, next); await f.Data.SaveChangesAsync();
        Metrics(f.Objects, work, 2, 10, 60, heartbeatAge: condition == "stale-heartbeat" ? 300 : 0, progressAge: condition == "silent-progress" ? 500 : 0);
        var result = await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId, next.EntityId });
        Assert.True(result[work.EntityId].Delayed); Assert.Null(result[work.EntityId].RemainingSeconds); Assert.Null(result[next.EntityId].WaitSeconds);
    }

    [Fact]
    public async Task AReclaimedLeaseCannotReuseOldProgressOrFinishAnotherAttempt()
    {
        await using var f = await Db.Create(); var work = Work(1, state: "running"); work.Claim = "new-claim"; work.LeaseUntil = Clock.Now.AddMinutes(5); f.Data.Add(work); await f.Data.SaveChangesAsync();
        Metrics(f.Objects, work, 5, 10, 100, claim: "old-claim");
        var stale = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId }))[work.EntityId]; Assert.Null(stale.RemainingSeconds); Assert.Null(stale.Current);
        await QueueEstimates.Started(f.Objects, work, "new-claim"); await QueueEstimates.Finished(f.Objects, work.Id, "old-claim", true);
        using var saved = JsonDocument.Parse(f.Objects.Bytes!); Assert.Equal(Hash("new-claim"), saved.RootElement.GetProperty("actives").GetProperty(work.Id.ToString()).GetProperty("claimHash").GetString());
        Assert.Empty(saved.RootElement.GetProperty("samples").EnumerateObject());
    }

    [Fact]
    public async Task SamplesAreBoundedAndFailuresDoNotTrainTheEstimator()
    {
        var objects = new Objects(); var work = Work(1);
        for (var i = 0; i < 26; i++) { await QueueEstimates.Started(objects, work, "lease"); await Task.Yield(); await QueueEstimates.Finished(objects, work.Id, "lease", true); }
        using (var saved = JsonDocument.Parse(objects.Bytes!)) Assert.Equal(20, saved.RootElement.GetProperty("samples").GetProperty("import").GetArrayLength());
        await QueueEstimates.Started(objects, work, "lease"); await QueueEstimates.Finished(objects, work.Id, "lease", false);
        using var after = JsonDocument.Parse(objects.Bytes!); Assert.Equal(20, after.RootElement.GetProperty("samples").GetProperty("import").GetArrayLength()); Assert.Empty(after.RootElement.GetProperty("actives").EnumerateObject());
        Assert.True(objects.Bytes!.Length < 65536); Assert.DoesNotContain("\"lease\"", Encoding.UTF8.GetString(objects.Bytes));
    }

    [Fact]
    public async Task OnlyRequestedEntitiesAreReturnedAndTelemetryNeverDisclosesOtherIdentities()
    {
        await using var f = await Db.Create(); var other = Work(1); var owned = Work(2); other.Error = "private student and file"; f.Data.AddRange(other, owned); await f.Data.SaveChangesAsync(); Metrics(f.Objects);
        var result = await QueueEstimates.Batch(f.Data, f.Objects, new[] { owned.EntityId }); Assert.Single(result); Assert.True(result.ContainsKey(owned.EntityId)); Assert.Equal(1, result[owned.EntityId].Ahead);
        var json = Json.Write(result); Assert.DoesNotContain(other.EntityId.ToString(), json); Assert.DoesNotContain(other.Id.ToString(), json); Assert.DoesNotContain(other.Error, json);
    }

    [Fact]
    public async Task QueueEndpointKeepsClassAuthorizationAndHidesOtherQueuedFiles()
    {
        await using var f = new Factory(); using var admin = await f.Login("queue-admin", true);
        var campus = (await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "队列验收" })).GetProperty("id").GetGuid();
        var course = (await Factory.Post(admin, "/api/admin/catalog/courses", new { name = "数学" })).GetProperty("id").GetGuid();
        var cl = (await Factory.Post(admin, "/api/admin/catalog/classes", new { name = "本班", campusId = campus })).GetProperty("id").GetGuid();
        var other = (await Factory.Post(admin, "/api/admin/catalog/classes", new { name = "另一班", campusId = campus })).GetProperty("id").GetGuid();
        var staff = (await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "队列教师" })).GetProperty("id").GetGuid();
        await Factory.Post(admin, "/api/admin/catalog/assignments", new { classId = cl, courseId = course, staffId = staff });
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staff}/invite", new { }); using var teacher = await f.Login("queue-teacher");
        await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        var ownBook = new BookImport { StaffId = staff, ClassId = cl, CourseId = course, FileName = "本班讲义.pdf", OriginalKey = "own.pdf" };
        var otherBook = new BookImport { StaffId = staff, ClassId = other, CourseId = course, FileName = "不可见资料.pdf", OriginalKey = "other.pdf" };
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); db.AddRange(ownBook, otherBook, new WorkItem { Id = Id(1), Kind = "book-auto", EntityId = otherBook.Id }, new WorkItem { Id = Id(2), Kind = "book-auto", EntityId = ownBook.Id }); await db.SaveChangesAsync();
        }
        var ownResponse = await teacher.GetAsync($"/api/books/{ownBook.Id}"); Assert.Equal(HttpStatusCode.OK, ownResponse.StatusCode);
        var body = await ownResponse.Content.ReadAsStringAsync(); using var json = JsonDocument.Parse(body);
        Assert.Equal(1, json.RootElement.GetProperty("queue").GetProperty("ahead").GetInt32());
        Assert.DoesNotContain(otherBook.Id.ToString(), body); Assert.DoesNotContain(otherBook.FileName, body);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/books/{otherBook.Id}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/classes/{other}/books?courseId={course}")).StatusCode);
    }

    [Theory]
    [InlineData("bad-json")]
    [InlineData("oversized")]
    [InlineData("unavailable")]
    public async Task InvalidTelemetryDegradesWithoutFailingTheApi(string kind)
    {
        await using var f = await Db.Create(); var work = Work(1); f.Data.Add(work); await f.Data.SaveChangesAsync();
        f.Objects.Bytes = Encoding.UTF8.GetBytes(kind == "oversized" ? new string('x', 65537) : "{broken"); f.Objects.Broken = kind == "unavailable";
        var result = await QueueEstimates.Batch(f.Data, f.Objects, new[] { work.EntityId }); Assert.Equal("queued", result[work.EntityId].State); Assert.Null(result[work.EntityId].WaitSeconds);
    }

    static void MultiMetrics(Objects objects, int capacity, params (WorkItem Work, int Current, int Total, int Elapsed, int HeartbeatAge)[] rows)
    {
        var now = Clock.Now;
        objects.Set(new { version = 2, capacity, heartbeatAt = now, samples = History(("import", 120)), actives = rows.ToDictionary(x => x.Work.Id,
            x => new { workId = x.Work.Id, kind = x.Work.Kind, claimHash = Hash(x.Work.Claim!), startedAt = now.AddSeconds(-x.Elapsed),
                stageStartedAt = now.AddSeconds(-x.Elapsed), progressAt = now, lastSeen = now.AddSeconds(-x.HeartbeatAge), stage = "recognizing", current = x.Current, total = x.Total }) });
    }
    static WorkItem Running(int n) { var item = Work(n, state: "running"); item.Claim = "lease-" + n; item.LeaseUntil = Clock.Now.AddMinutes(5); return item; }

    [Fact]
    public async Task ParallelTasksKeepIndependentProgressAndIdleCannotClearEither()
    {
        var objects = new Objects(); var a = Running(1); var b = Running(2);
        await Task.WhenAll(QueueEstimates.Started(objects, a, a.Claim!, capacity: 2), QueueEstimates.Started(objects, b, b.Claim!, capacity: 2));
        await Task.WhenAll(QueueEstimates.Progress(objects, a.Id, a.Claim!, new("recognizing", 2, 10, "private A")),
            QueueEstimates.Progress(objects, b.Id, b.Claim!, new("splitting", 3, 6, "private B")));
        await QueueEstimates.Idle(objects, capacity: 2);
        using (var saved = JsonDocument.Parse(objects.Bytes!)) {
            Assert.Equal(2, saved.RootElement.GetProperty("capacity").GetInt32());
            var actives = saved.RootElement.GetProperty("actives"); Assert.Equal(2, actives.EnumerateObject().Count());
            Assert.Equal(2, actives.GetProperty(a.Id.ToString()).GetProperty("current").GetInt32());
            Assert.Equal(3, actives.GetProperty(b.Id.ToString()).GetProperty("current").GetInt32());
        }
        await QueueEstimates.Finished(objects, a.Id, "wrong-old-claim", true);
        await QueueEstimates.Finished(objects, a.Id, a.Claim!, true);
        await QueueEstimates.Heartbeat(objects, b.Id, b.Claim!);
        using var after = JsonDocument.Parse(objects.Bytes!); var remaining = after.RootElement.GetProperty("actives");
        Assert.Single(remaining.EnumerateObject()); Assert.Equal(3, remaining.GetProperty(b.Id.ToString()).GetProperty("current").GetInt32());
        Assert.True(objects.Bytes!.Length < 65536); Assert.DoesNotContain("private", Encoding.UTF8.GetString(objects.Bytes));
    }

    [Fact]
    public async Task TwoBusyLanesAssignQueuedWorkToEarliestFinishingLane()
    {
        await using var f = await Db.Create(); var a = Running(1); var b = Running(2); var first = Work(3); var second = Work(4); var third = Work(5);
        f.Data.AddRange(a, b, first, second, third); await f.Data.SaveChangesAsync(); MultiMetrics(f.Objects, 2, (a, 2, 4, 60, 0), (b, 1, 3, 60, 0));
        var estimates = await QueueEstimates.Batch(f.Data, f.Objects, new[] { a.EntityId, b.EntityId, first.EntityId, second.EntityId, third.EntityId });
        Assert.Equal(60, estimates[a.EntityId].RemainingSeconds); Assert.Equal(120, estimates[b.EntityId].RemainingSeconds);
        Assert.Equal(60, estimates[first.EntityId].WaitSeconds); Assert.Equal(180, estimates[first.EntityId].RemainingSeconds); Assert.Equal(2, estimates[first.EntityId].Ahead);
        Assert.Equal(120, estimates[second.EntityId].WaitSeconds); Assert.Equal(240, estimates[second.EntityId].RemainingSeconds); Assert.Equal(3, estimates[second.EntityId].Ahead);
        Assert.Equal(180, estimates[third.EntityId].WaitSeconds); Assert.Equal(300, estimates[third.EntityId].RemainingSeconds); Assert.Equal(4, estimates[third.EntityId].Ahead);
        Assert.All(estimates.Values, x => Assert.Equal("observed", x.Basis));
    }

    [Fact]
    public async Task UnknownBusyLaneDoesNotInventPositiveFutureMinimum()
    {
        await using var f = await Db.Create(); var a = Running(1); var b = Running(2); var next = Work(3);
        f.Data.AddRange(a, b, next); await f.Data.SaveChangesAsync(); MultiMetrics(f.Objects, 2, (a, 2, 4, 60, 0));
        var results = await QueueEstimates.Batch(f.Data, f.Objects, new[] { a.EntityId, b.EntityId, next.EntityId });
        Assert.Equal(60, results[a.EntityId].RemainingSeconds); Assert.Null(results[b.EntityId].RemainingSeconds);
        Assert.Equal(2, results[next.EntityId].Ahead); Assert.Null(results[next.EntityId].WaitSeconds); Assert.Null(results[next.EntityId].RemainingSeconds); Assert.Equal("unknown", results[next.EntityId].Basis);
    }

    [Fact]
    public async Task OneKnownFreeLaneCanStartOneJobEvenWhenOtherLaneIsUnknown()
    {
        await using var f = await Db.Create(); var a = Running(1); var first = Work(2); var second = Work(3);
        f.Data.AddRange(a, first, second); await f.Data.SaveChangesAsync(); MultiMetrics(f.Objects, 2);
        var results = await QueueEstimates.Batch(f.Data, f.Objects, new[] { first.EntityId, second.EntityId });
        Assert.Equal(0, results[first.EntityId].WaitSeconds); Assert.Equal(120, results[first.EntityId].RemainingSeconds);
        Assert.Null(results[second.EntityId].WaitSeconds); Assert.Null(results[second.EntityId].RemainingSeconds);
    }

    [Fact]
    public async Task AnotherLaneHeartbeatCannotMakeAnOldActiveSampleFresh()
    {
        await using var f = await Db.Create(); var stale = Running(1); var fresh = Running(2); var next = Work(3);
        f.Data.AddRange(stale, fresh, next); await f.Data.SaveChangesAsync(); MultiMetrics(f.Objects, 2, (stale, 2, 10, 300, 200), (fresh, 2, 4, 60, 0));
        await QueueEstimates.Heartbeat(f.Objects, fresh.Id, fresh.Claim!);
        var results = await QueueEstimates.Batch(f.Data, f.Objects, new[] { stale.EntityId, fresh.EntityId, next.EntityId });
        Assert.True(results[stale.EntityId].Delayed); Assert.Null(results[stale.EntityId].RemainingSeconds); Assert.Equal(60, results[fresh.EntityId].RemainingSeconds);
        Assert.Null(results[next.EntityId].WaitSeconds); Assert.True(results[next.EntityId].Delayed);
    }

    [Fact]
    public async Task SameEntityGateDoesNotReserveAFreeLaneWhileOtherWorkCanStart()
    {
        await using var f = await Db.Create(); var active = Running(1); var same = Work(2); same.EntityId = active.EntityId; var other = Work(3);
        f.Data.AddRange(active, same, other); await f.Data.SaveChangesAsync(); MultiMetrics(f.Objects, 2, (active, 2, 4, 60, 0));
        var estimate = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { other.EntityId }))[other.EntityId];
        Assert.Equal(0, estimate.WaitSeconds); Assert.Equal(1, estimate.Ahead); Assert.Equal(120, estimate.RemainingSeconds);
    }

    [Fact]
    public async Task WorkerPublishesConfiguredCapacityInsteadOfApiDefault()
    {
        await using var f = await Db.Create(); var document = new ExportDocument { State = "ready" }; var work = Work(1, "export"); work.EntityId = document.Id;
        f.Data.AddRange(document, work); await f.Data.SaveChangesAsync(); var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Worker:Concurrency"] = "2" }).Build();
        var protection = new EphemeralDataProtectionProvider(); var settings = new LearningModelSettings(f.Data, config, protection); var clients = new NoClients();
        var word = new WordDocuments(config, f.Objects, f.Data, settings, clients, new FormulaSettings(f.Data, protection));
        var worker = new DocumentWorker(f.Data, word, f.Objects, NullLogger<DocumentWorker>.Instance, clients, settings, config); Assert.True(await worker.Tick(default));
        using var saved = JsonDocument.Parse(f.Objects.Bytes!); Assert.Equal(2, saved.RootElement.GetProperty("capacity").GetInt32());
        var one = Work(2); var two = Work(3); f.Data.AddRange(one, two); await f.Data.SaveChangesAsync();
        var results = await QueueEstimates.Batch(f.Data, f.Objects, new[] { one.EntityId, two.EntityId }); Assert.Equal(0, results[one.EntityId].WaitSeconds); Assert.Equal(0, results[two.EntityId].WaitSeconds);
    }

    [Fact]
    public async Task ResourceAdmissionPauseDoesNotPromiseAnOtherwiseFreeLane()
    {
        await using var f = await Db.Create(); var active = Running(1); var waiting = Work(2);
        f.Data.AddRange(active, waiting); await f.Data.SaveChangesAsync(); MultiMetrics(f.Objects, 2, (active, 2, 4, 60, 0));
        await QueueEstimates.Admission(f.Objects, 2, false);
        var paused = await QueueEstimates.Batch(f.Data, f.Objects, new[] { active.EntityId, waiting.EntityId });
        Assert.Equal(60, paused[active.EntityId].RemainingSeconds); Assert.Null(paused[waiting.EntityId].WaitSeconds); Assert.True(paused[waiting.EntityId].Delayed);
        await QueueEstimates.Admission(f.Objects, 2, true);
        var resumed = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { waiting.EntityId }))[waiting.EntityId]; Assert.Equal(0, resumed.WaitSeconds); Assert.False(resumed.Delayed);
    }

    [Fact]
    public async Task QueuedJobsForSameEntityUseSerialLanesWhileOtherEntityCanStart()
    {
        await using var f = await Db.Create(); var first = Work(1); var same = Work(2); same.EntityId = first.EntityId; var other = Work(3);
        f.Data.AddRange(first, same, other); await f.Data.SaveChangesAsync(); MultiMetrics(f.Objects, 2);
        var result = (await QueueEstimates.Batch(f.Data, f.Objects, new[] { other.EntityId }))[other.EntityId];
        Assert.Equal(0, result.WaitSeconds); Assert.Equal(1, result.Ahead); Assert.Equal(120, result.RemainingSeconds);
    }

    sealed class NoClients : IHttpClientFactory { public HttpClient CreateClient(string name) => throw new InvalidOperationException("No model calls permitted"); }
    [Fact]
    public async Task WorkerCompletesBusinessWorkWhenTelemetryStorageFailsAndRestoresCallback()
    {
        await using var f = await Db.Create(); f.Objects.Broken = true; var document = new ExportDocument { State = "ready" }; var work = Work(1, "export"); work.EntityId = document.Id;
        f.Data.AddRange(document, work); await f.Data.SaveChangesAsync(); var config = new ConfigurationBuilder().Build(); var protection = new EphemeralDataProtectionProvider(); var settings = new LearningModelSettings(f.Data, config, protection); var clients = new NoClients();
        var word = new WordDocuments(config, f.Objects, f.Data, settings, clients, new FormulaSettings(f.Data, protection));
        Func<HandoutProgress, CancellationToken, Task> callback = (_, _) => Task.CompletedTask; word.HandoutProgressCallback = callback;
        var worker = new DocumentWorker(f.Data, word, f.Objects, NullLogger<DocumentWorker>.Instance, clients, settings, config);
        Assert.True(await worker.Tick(default)); Assert.Equal("done", await f.Data.Work.Where(x => x.Id == work.Id).Select(x => x.State).SingleAsync()); Assert.Same(callback, word.HandoutProgressCallback);
    }
}
