using Siyue.Printing;
using Microsoft.Data.Sqlite;
using Xunit;
public class PrintEngineTests
{
    [Fact] public void ExistingJournalSchemaIsMigratedWithoutLosingJobs()
    {
        var path=Path.Combine(Path.GetTempPath(),Guid.NewGuid()+".db");var job=new Transport().Job;
        using(var db=new SqliteConnection("Data Source="+path)){db.Open();using var c=db.CreateCommand();c.CommandText="CREATE TABLE jobs (id TEXT PRIMARY KEY, claim TEXT NOT NULL, phase TEXT NOT NULL, detail TEXT NOT NULL, pending INTEGER NOT NULL); INSERT INTO jobs VALUES($id,$claim,'submitting','',1)";c.Parameters.AddWithValue("$id",job.Id.ToString());c.Parameters.AddWithValue("$claim",System.Text.Json.JsonSerializer.Serialize(job));c.ExecuteNonQuery();}
        using var journal=new Journal(path);var entry=Assert.Single(journal.Entries());Assert.Equal(job.Id,entry.Job.Id);Assert.True(entry.Pending);journal.Save(job,"uncertain","migrated",false);Assert.Equal("migrated",Assert.Single(journal.Entries()).Detail);
    }
    [Fact] public void JournalPruningNeverDeletesPendingOrRecentEntries()
    {
        using var journal=new Journal(Path.Combine(Path.GetTempPath(),Guid.NewGuid()+".db"));var now=DateTimeOffset.UtcNow;
        var oldDone=new Transport().Job with {Id=Guid.NewGuid()};var oldPending=oldDone with {Id=Guid.NewGuid()};var recent=oldDone with {Id=Guid.NewGuid()};
        journal.Save(oldDone,"failed","old",false,now.AddDays(-120));journal.Save(oldPending,"submitting","pending",true,now.AddDays(-120));journal.Save(recent,"submitted","recent",false,now);
        journal.Prune(now.AddDays(-90),100);
        var entries=journal.Entries();Assert.DoesNotContain(entries,x=>x.Job.Id==oldDone.Id);Assert.Contains(entries,x=>x.Job.Id==oldPending.Id&&x.Pending);Assert.Contains(entries,x=>x.Job.Id==recent.Id);
    }
    [Fact] public async Task LostReceiptAndRestartNeverExecuteTwice()
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db"); using var journal = new Journal(path); var t = new Transport { LoseReceipt = true }; var e = new Executor(); var engine = new PrintEngine(journal, t, e);
        await engine.Tick(CancellationToken.None); Assert.Equal(1, e.Calls); Assert.True(journal.Entries()[0].Pending);
        t.LoseReceipt = false; await new PrintEngine(journal, t, e).Recover(CancellationToken.None); Assert.Equal(1, e.Calls); Assert.False(journal.Entries()[0].Pending);
    }
    [Fact] public async Task CrashAtSubmittingBecomesUncertainWithoutRunningPrinter()
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db"); using var journal = new Journal(path); var t = new Transport(); var e = new Executor(); journal.Save(t.Job, "submitting");
        await new PrintEngine(journal, t, e).Recover(CancellationToken.None); Assert.Equal(0, e.Calls); Assert.Equal("uncertain", journal.Entries()[0].Phase);
    }
    [Theory]
    [InlineData("validate", "打印组件检查")]
    [InlineData("download", "下载并校验文件")]
    [InlineData("begin", "确认打印授权")]
    public async Task FailedPreflightReportsStageWithoutLeakingExceptionContent(string phase,string expected)
    {
        using var journal = new Journal(Path.Combine(Path.GetTempPath(),Guid.NewGuid()+".db"));
        var failure = new HttpRequestException("https://secret.example/files/credential?private=document",null,System.Net.HttpStatusCode.Forbidden);
        var t = new Transport { DownloadError = phase=="download" ? failure : null, BeginError = phase=="begin" ? failure : null };
        var e = new Executor { ValidationError = phase=="validate" ? failure : null };
        await new PrintEngine(journal,t,e).Tick(CancellationToken.None);
        var entry=Assert.Single(journal.Entries());Assert.Equal("failed",entry.Phase);Assert.Contains(expected,entry.Detail);Assert.Contains("HTTP 403",entry.Detail);
        Assert.DoesNotContain("credential",entry.Detail);Assert.DoesNotContain("secret.example",entry.Detail);Assert.Equal(0,e.Calls);
        await new PrintEngine(journal,t,e).Recover(CancellationToken.None);Assert.Equal(0,e.Calls);
    }
    [Fact] public async Task SafeComponentFailureIsVisibleWithoutSubmittingPrint()
    {
        using var journal = new Journal(Path.Combine(Path.GetTempPath(),Guid.NewGuid()+".db"));
        var t=new Transport();var e=new Executor { ValidationError=new PrintCheckException("打印组件缺失") };
        await new PrintEngine(journal,t,e).Tick(CancellationToken.None);
        Assert.Contains("打印组件缺失",Assert.Single(journal.Entries()).Detail);Assert.Equal(0,e.Calls);
    }
    sealed class Executor : IExecutor { public int Calls; public Exception? ValidationError; public Task Validate(Claim j, CancellationToken c) => ValidationError==null ? Task.CompletedTask : Task.FromException(ValidationError); public Task<Outcome> Execute(Claim j, string f, CancellationToken c) { Calls++; return Task.FromResult(new Outcome("submitted", "submitted")); } }
    sealed class Transport : ITransport {
        public Claim Job = new(Guid.NewGuid(), "lease", DateTimeOffset.UtcNow.AddMinutes(5), "queue", 1, false, "http://localhost/file", "hash"); public bool LoseReceipt; bool claimed;
        public Task<Claim?> Claim(CancellationToken c) { var next = claimed ? null : Job; claimed = true; return Task.FromResult(next); }
        public Exception? DownloadError,BeginError;
        public Task Begin(Claim j, CancellationToken c) => BeginError==null ? Task.CompletedTask : Task.FromException(BeginError);
        public Task Report(Claim j, Outcome o, CancellationToken c) => LoseReceipt ? Task.FromException(new IOException("offline")) : Task.CompletedTask;
        public Task<string> Download(Claim j, CancellationToken c) { if(DownloadError!=null)return Task.FromException<string>(DownloadError);var p = Path.GetTempFileName(); return Task.FromResult(p); }
    }
}
