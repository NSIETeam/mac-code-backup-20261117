using Microsoft.Data.Sqlite;
using System.Text.Json;
namespace Siyue.Printing;
public record Claim(Guid Id, string ClaimToken, DateTimeOffset LeaseUntil, string QueueName, int Copies, bool Duplex, string PdfUrl, string Sha256);
public record Outcome(string State, string Detail);
public sealed class PrintCheckException(string message) : Exception(message);
public interface ITransport { Task<Claim?> Claim(CancellationToken ct); Task Begin(Claim job, CancellationToken ct); Task Report(Claim job, Outcome outcome, CancellationToken ct); Task<string> Download(Claim job, CancellationToken ct); }
public interface IExecutor { Task Validate(Claim job, CancellationToken ct); Task<Outcome> Execute(Claim job, string file, CancellationToken ct); }
public record Entry(Claim Job, string Phase, string Detail, bool Pending);
public class Journal : IDisposable
{
    readonly SqliteConnection db;
    public Journal(string path) { Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!); db = new SqliteConnection("Data Source=" + path); db.Open(); using var c = db.CreateCommand(); c.CommandText = "PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, claim TEXT NOT NULL, phase TEXT NOT NULL, detail TEXT NOT NULL, pending INTEGER NOT NULL)"; c.ExecuteNonQuery(); }
    public void Save(Claim job, string phase, string detail = "", bool pending = true) { using var c = db.CreateCommand(); c.CommandText = "INSERT INTO jobs VALUES($id,$claim,$phase,$detail,$pending) ON CONFLICT(id) DO UPDATE SET claim=$claim,phase=$phase,detail=$detail,pending=$pending"; c.Parameters.AddWithValue("$id", job.Id.ToString()); c.Parameters.AddWithValue("$claim", JsonSerializer.Serialize(job)); c.Parameters.AddWithValue("$phase", phase); c.Parameters.AddWithValue("$detail", detail); c.Parameters.AddWithValue("$pending", pending ? 1 : 0); c.ExecuteNonQuery(); }
    public Entry? Find(Guid id) => Entries().FirstOrDefault(x => x.Job.Id == id);
    public List<Entry> Entries() { using var c = db.CreateCommand(); c.CommandText = "SELECT claim,phase,detail,pending FROM jobs"; using var r = c.ExecuteReader(); var result = new List<Entry>(); while (r.Read()) result.Add(new Entry(JsonSerializer.Deserialize<Claim>(r.GetString(0))!, r.GetString(1), r.GetString(2), r.GetInt32(3) == 1)); return result; }
    public void Dispose() => db.Dispose();
}
public class PrintEngine(Journal journal, ITransport transport, IExecutor executor)
{
    public async Task Recover(CancellationToken ct)
    {
        foreach (var entry in journal.Entries().Where(x => x.Pending)) {
            var state = entry.Phase is "prepared" ? "failed" : entry.Phase is "submitting" ? "uncertain" : entry.Phase;
            var detail = entry.Phase is "prepared" ? "助手重启，任务尚未执行打印，可核实后补打" : entry.Phase is "submitting" ? "助手在提交过程中中断，请现场核实" : entry.Detail;
            journal.Save(entry.Job, state, detail); await Report(entry.Job, new Outcome(state, detail), ct);
        }
    }
    public async Task<bool> Tick(CancellationToken ct)
    {
        await Recover(ct); var job = await transport.Claim(ct); if (job == null) return false;
        var old = journal.Find(job.Id);
        if (old != null) { var state = old.Phase == "failed" ? "failed" : "uncertain"; var detail = "本地已有此任务记录，未重复执行打印；请核实原任务"; journal.Save(job, state, detail); await Report(job, new Outcome(state, detail), ct); return true; }
        journal.Save(job, "prepared"); string? file = null; var began = false; var stage = "打印组件检查";
        try {
            await executor.Validate(job, ct); stage = "下载并校验文件"; file = await transport.Download(job, ct);
            stage = "确认打印授权"; await transport.Begin(job, ct); journal.Save(job, "submitting"); began = true;
            var result = await executor.Execute(job, file, ct); journal.Save(job, result.State, result.Detail);
            // A lost receipt must retain the known outcome, never run the executor again.
            try { await Report(job, result, ct); } catch { }
        } catch (Exception e) {
            // Only explicitly safe check messages are exposed. Framework exceptions can contain
            // download credentials, file paths or document names, so report their type/code instead.
            var reason = e is PrintCheckException ? e.Message : e is HttpRequestException h && h.StatusCode.HasValue ? "HTTP " + (int)h.StatusCode.Value : e.GetType().Name + "，错误码 0x" + e.HResult.ToString("X8");
            var outcome = new Outcome(began ? "uncertain" : "failed", began ? "打印过程异常，请现场核实；系统不会自动重打" : stage + "失败：" + reason + "；尚未执行打印");
            journal.Save(job, outcome.State, outcome.Detail); try { await Report(job, outcome, CancellationToken.None); } catch { }
        } finally { if (file != null) try { File.Delete(file); } catch (IOException) { } }
        return true;
    }
    async Task Report(Claim job, Outcome result, CancellationToken ct) { await transport.Report(job, result, ct); journal.Save(job, result.State, result.Detail, false); }
}
