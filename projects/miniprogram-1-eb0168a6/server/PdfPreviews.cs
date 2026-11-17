using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
using System.Text;
namespace Siyue;

public record PdfPreviewTarget(string Kind, Guid Id, string Key, string Title, Guid? ClassId, Guid? CourseId);
public record PdfPreviewPage(string Key, int Number, int Width, int Height);
public record PdfPreviewManifest(string SourceHash, PdfPreviewPage[] Pages);
public class PdfPreviews(Data db, Access access, IObjects objects, Files files)
{
    public static Guid JobId(string kind, Guid id, string key) => new(SHA256.HashData(Encoding.UTF8.GetBytes($"pdf-preview-v1:{kind}:{id}:{key}"))[..16]);
    public static string Prefix(string kind, Guid id, string key) => $"pdf-previews/{JobId(kind, id, key):N}";
    public static string SharedPrefix(string sourceHash)
    {
        Guard.That(sourceHash.Length == 64 && sourceHash.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f'), "预览源文件校验值无效", 422);
        // Include the renderer contract in the namespace; old grants and manifests remain usable.
        return "pdf-previews/source-v2-1600/" + sourceHash;
    }
    public static async Task<string> SourceHash(IObjects objects, string key, CancellationToken ct)
    {
        await using var source = await objects.Get(key, ct);
        return Convert.ToHexStringLower(await SHA256.HashDataAsync(source, ct));
    }
    public static async Task<PdfPreviewManifest?> ReadManifest(IObjects objects, string key, string sourceHash, CancellationToken ct)
    {
        try {
            await using var stream = await objects.Get(key, ct); using var reader = new StreamReader(stream);
            var manifest = Json.Read<PdfPreviewManifest>(await reader.ReadToEndAsync(ct));
            var prefix = SharedPrefix(sourceHash);
            // A manifest is published only after every page. Never grant arbitrary object keys
            // or accept a legacy manifest without a source-byte fingerprint.
            return manifest?.SourceHash == sourceHash && manifest.Pages is { Length: > 0 and <= 300 } &&
                manifest.Pages.Select((p, index) => p != null && p.Number == index + 1 && p.Key == $"{prefix}/page-{index + 1}.png" && p.Width is > 0 and <= 2000 && p.Height is > 0 and <= 2000).All(x => x)
                ? manifest : null;
        } catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException or System.Text.Json.JsonException ||
            e is Amazon.S3.AmazonS3Exception s && s.StatusCode == System.Net.HttpStatusCode.NotFound) { return null; }
    }
    public static async Task WriteManifest(IObjects objects, string key, object manifest, CancellationToken ct)
    {
        await using var stream = new MemoryStream(Encoding.UTF8.GetBytes(Json.Write(manifest)));
        await objects.Put(key, stream, "application/json", ct);
    }
    public async Task<PdfPreviewTarget> Target(string kind, Guid id)
    {
        if (kind == "export") {
            var d = await access.Document(id); Guard.That(d.State == "ready", "文档尚未生成完成", 409);
            return new(kind, id, d.PdfKey, (d.Kind == "file" ? d.SourceFileName : d.StudentName + "的错题本"), d.ClassId, d.CourseId);
        }
        Guard.That(kind == "handout", "预览类型无效");
        var h = await access.Handout(id); var l = await access.Lecture(h.LectureId);
        Guard.That(access.User.Role == "admin" || h.State == "published" || l.ClassId.HasValue, "讲义尚未发布", 403);
        Guard.That(h.SourcePdfKey.Length > 0, "原讲义尚未转换完成", 409);
        return new(kind, id, h.SourcePdfKey, h.FileName + " · v" + h.Version, l.ClassId, l.CourseId);
    }
    public async Task<object> Read(string kind, Guid id, bool start, CancellationToken ct)
    {
        // A reflow can still be producing its PDF when the internal viewer opens.
        if (kind == "export") {
            var document = await access.Document(id);
            if (document.State != "ready") return new { title = (document.Kind == "file" ? document.SourceFileName : document.StudentName + "的错题本"), state = document.State == "failed" ? "failed" : "processing", phase = "export", error = document.Error, pages = Array.Empty<object>() };
        }
        // Resolve permissions on every poll and renew image grants only for the current teacher.
        var target = await Target(kind, id); var jobId = JobId(kind, id, target.Key);
        var job = await db.Work.AsNoTracking().SingleOrDefaultAsync(x => x.Id == jobId, ct);
        if (start && job == null) {
            var created = new WorkItem { Id = jobId, Kind = kind + "-preview", EntityId = id };
            db.Work.Add(created);
            try { await db.SaveChangesAsync(ct); job = created; }
            catch (DbUpdateException) {
                db.Entry(created).State = EntityState.Detached;
                job = await db.Work.AsNoTracking().SingleOrDefaultAsync(x => x.Id == jobId, ct);
                if (job == null) throw;
            }
        }
        if (start && job?.State == "failed") {
            await db.Work.Where(x => x.Id == jobId && x.State == "failed").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "queued").SetProperty(x => x.Error, (string?)null), ct);
            job = await db.Work.AsNoTracking().SingleAsync(x => x.Id == jobId, ct);
        }
        var pages = new List<object>(); var ready = false;
        if (job?.State == "done") {
            var hash = await SourceHash(objects, target.Key, ct);
            var manifest = await ReadManifest(objects, Prefix(kind, id, target.Key) + "/source.json", hash, ct);
            if (manifest == null) {
                // Legacy manifests remain on disk, but cannot prove which source bytes they
                // depict. Rebuild once; the same job also invalidates overwritten source keys.
                await db.Work.Where(x => x.Id == jobId && x.State == "done").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "queued")
                    .SetProperty(x => x.Claim, (string?)null).SetProperty(x => x.LeaseUntil, (DateTimeOffset?)null).SetProperty(x => x.Error, (string?)null), ct);
                job = await db.Work.AsNoTracking().SingleAsync(x => x.Id == jobId, ct);
            }
            ready = manifest != null;
            foreach (var p in manifest?.Pages ?? []) {
                var url = await files.Grant(p.Key, "image/png", $"预览第{p.Number}页.png", access.User.Id, classId: target.ClassId, courseId: target.CourseId);
                pages.Add(new { p.Number, p.Width, p.Height, url });
            }
        }
        return new { title = target.Title, state = ready ? "ready" : job?.State == "failed" ? "failed" : "processing", error = job?.Error, pages };
    }
}
public static class PdfPreviewEndpoints
{
    public static void MapPdfPreviews(this WebApplication app)
    {
        app.MapPost("/api/previews/{kind}/{id:guid}", (string kind, Guid id, PdfPreviews previews, CancellationToken ct) => previews.Read(kind, id, true, ct));
        app.MapGet("/api/previews/{kind}/{id:guid}", (string kind, Guid id, PdfPreviews previews, CancellationToken ct) => previews.Read(kind, id, false, ct));
    }
}
