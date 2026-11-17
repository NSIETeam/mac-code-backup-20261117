using Siyue.Printing;
using Xunit;
public class PrintEngineTests
{
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
