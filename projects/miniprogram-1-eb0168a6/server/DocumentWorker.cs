using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
namespace Siyue;
public class DocumentWorker(Data db, WordDocuments word, IObjects objects, ILogger<DocumentWorker> log, IHttpClientFactory clients, LearningModelSettings modelSettings, IConfiguration config)
{
    public async Task<bool> Tick(CancellationToken ct)
    {
        var now = Clock.Now; await PrintingEndpoints.Expire(db, now);
        // Document work has no printing side effects, so expired leases can safely be reclaimed.
        await db.Work.Where(x => x.State == "running" && x.LeaseUntil < now).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "queued"), ct);
        var candidates = await QueueEstimates.Executable(db, now).Take(10).ToListAsync(ct);
        foreach (var work in candidates) {
            using var entityLease = WorkerEntityGate.TryEnter(work.EntityId);
            if (entityLease == null) continue;
            var token = Secrets.New(); var won = await db.Work.Where(x => x.Id == work.Id && x.State == "queued").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "running").SetProperty(x => x.Claim, token).SetProperty(x => x.LeaseUntil, now.AddMinutes(5)).SetProperty(x => x.Attempts, x => x.Attempts + 1), ct);
            if (won != 1) continue; db.ChangeTracker.Clear();
            using var lease = CancellationTokenSource.CreateLinkedTokenSource(ct); var renew = Renew(work.Id, token, lease.Token);
            var completed = false; var previousProgress = word.HandoutProgressCallback;
            word.HandoutProgressCallback = async (progress, progressCt) => {
                try { if (previousProgress != null) await previousProgress(progress, progressCt); }
                finally { await QueueEstimates.Progress(objects, work.Id, token, progress, progressCt); }
            };
            try {
                await QueueEstimates.Started(objects, work, token, ct, Math.Clamp(config.GetValue("Worker:Concurrency", 1), 1, 2));
                await Process(work.Kind, work.EntityId, ct);
                completed = await db.Work.Where(x => x.Id == work.Id && x.Claim == token).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "done").SetProperty(x => x.Error, (string?)null), ct) == 1;
            } catch (Exception e) when (!ct.IsCancellationRequested) {
                // Only preparation has no printer side effects. Retry transient I/O with the
                // same job ID, bounded attempts and backoff; never retry a printer execution.
                if(work.Kind=="print-prepare" && e is IOException or HttpRequestException &&
                    await db.Work.Where(x=>x.Id==work.Id).Select(x=>x.Attempts).SingleAsync(ct)<3){
                    db.ChangeTracker.Clear();
                    await db.Work.Where(x=>x.Id==work.Id&&x.Claim==token).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,"queued").SetProperty(x=>x.Claim,(string?)null).SetProperty(x=>x.LeaseUntil,Clock.Now.AddSeconds(30)).SetProperty(x=>x.Error,"文件连接暂时中断，自动重试打印准备"),ct);
                    return true;
                }
                log.LogError(e, "Document task {Id} failed", work.Id); db.ChangeTracker.Clear(); var message = e is ApiException ? e.Message : "文档处理失败，请检查文件或服务器转换环境";
                await db.Work.Where(x => x.Id == work.Id && x.Claim == token).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "failed").SetProperty(x => x.Error, message), ct);
                if (work.Kind is "export" or "file" or "support") await db.Documents.Where(x => x.Id == work.EntityId && x.State != "ready").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "failed").SetProperty(x => x.Error, message), ct);
                else if (work.Kind == "print-prepare") await db.PrintJobs.Where(x => x.Id == work.EntityId && x.State == "preparing").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "failed").SetProperty(x => x.Detail, message), ct);
                else if (work.Kind == "variants") await db.LearningArtifacts.Where(x => x.Id == work.EntityId && x.State != "approved").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "failed").SetProperty(x => x.Error, message), ct);
                else if (work.Kind == "book-page-preview") await BookPageSplits.FailedPreview(work.EntityId, message, db, ct);
                else if (work.Kind is "book" or "book-auto" or "book-split") await db.BookImports.Where(x => x.Id == work.EntityId && x.State != "split").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "failed").SetProperty(x => x.Error, message), ct);
                else if (work.Kind == "import") await db.Handouts.Where(x => x.Id == work.EntityId && x.State != "published").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, x => x.State == "draft" ? "draft" : "failed").SetProperty(x => x.Error, message), ct);
                else if (work.Kind == "previews") await db.Handouts.Where(x => x.Id == work.EntityId && x.State == "draft").ExecuteUpdateAsync(s => s.SetProperty(x => x.Error, message), ct);
            } finally {
                word.HandoutProgressCallback = previousProgress;
                lease.Cancel(); try { await renew; } catch (OperationCanceledException) { }
                await QueueEstimates.Finished(objects, work.Id, token, completed, ct);
            }
            return true;
        }
        await QueueEstimates.Idle(objects, ct, Math.Clamp(config.GetValue("Worker:Concurrency", 1), 1, 2)); return false;
    }
    async Task Renew(Guid id, string token, CancellationToken ct)
    {
        // This context is intentionally independent of the context doing document mutations.
        while (true) { await Task.Delay(TimeSpan.FromSeconds(45), ct); var connection = config.GetConnectionString("Database") ?? db.Database.GetDbConnection().ConnectionString; var options = new DbContextOptionsBuilder<Data>(); if (db.Database.IsSqlite()) options.UseSqlite(connection); else options.UseNpgsql(connection); await using var leaseDb = new Data(options.Options); var held = await leaseDb.Work.Where(x => x.Id == id && x.Claim == token && x.State == "running").ExecuteUpdateAsync(s => s.SetProperty(x => x.LeaseUntil, Clock.Now.AddMinutes(5)), ct); if (held == 1) await QueueEstimates.Heartbeat(objects, id, token, ct); }
    }
    public async Task Process(string kind, Guid id, CancellationToken ct)
    {
        var dir = Path.Combine(Path.GetTempPath(), "siyue-doc-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(dir);
        try {
            if (kind == "print-prepare") { await PrintingEndpoints.PreparePending(id, db, objects, ct); }
            else if (kind == "variants") { await LearningSupport.Generate(id, db, objects, await modelSettings.Resolve(), clients, ct); }
            else if (kind == "book-page-preview") { await BookPageSplits.Prepare(id, db, word, objects, dir, ct); }
            else if (kind == "book-split") { await BookImports.Split(id, db, word, objects, dir, ct); }
            else if (kind is "book" or "book-auto") { await BookImports.Analyze(id, db, word, objects, dir, ct, autoSplit: kind == "book-auto"); }
            else if (kind == "import") {
                var h = await db.Handouts.FindAsync([id], ct) ?? throw new InvalidOperationException(); if (h.State == "published") return;
                using var recognition = QuestionRecognition.Use(word.EffectiveRecognitionMode(h.RecognitionMode));
                if (h.State == "draft") { await GenerateQuestionPreviews(id, dir, ct); return; }
                h.State = "processing"; h.Error = null; await db.SaveChangesAsync(ct);
                var input = await word.Materialize(h.OriginalKey, dir, "original" + Path.GetExtension(h.FileName).ToLowerInvariant(), ct);
                if (Path.GetExtension(input) == ".docx") WordDocuments.CheckPackage(input);
                // Keep the original PDF available even when parsing cannot start.
                if (Path.GetExtension(input) == ".pdf" && h.SourcePdfKey.Length == 0) { h.SourcePdfKey = $"handouts/{id}/source.pdf"; await Save(input, h.SourcePdfKey, "application/pdf", ct); await db.SaveChangesAsync(ct); }
                word.SourcePdfInput = WordDocuments.ReadAiSourceReferences(input).Length>0 && h.SourcePdfKey.Length>0
                    ? await word.Materialize(h.SourcePdfKey,dir,"book-original.pdf",ct) : null;
                var normalized = await word.NormalizeHandout(input, dir, ct);
                WordDocuments.CheckPackage(normalized); h.NormalizedKey = $"handouts/{id}/normalized.docx"; await Save(normalized, h.NormalizedKey, WordDocuments.DocxType, ct);
                // Split crops refer to the full original book's pages; do not overwrite that
                // source with a reflowed PDF of this lecture's normalized document.
                if (h.SourcePdfKey.Length == 0) {
                    var pdf = word.NormalizedSourcePdf is string originalPdf && File.Exists(originalPdf) ? originalPdf
                        : Path.GetExtension(input) == ".pdf" ? input : await word.Convert(normalized, "pdf", dir, ct);
                    h.SourcePdfKey = $"handouts/{id}/source.pdf"; await Save(pdf, h.SourcePdfKey, "application/pdf", ct);
                }
                // Retry replaces only unpublished parsing results; imports cannot be referenced by teachers yet.
                List<Question> questions;
                await using (var parsing = await db.Database.BeginTransactionAsync(ct)) {
                var old = await db.Questions.Where(x => x.HandoutId == id).Select(x => x.Id).ToArrayAsync(ct); await db.QuestionAssets.Where(x => old.Contains(x.QuestionId)).ExecuteDeleteAsync(ct); await db.Questions.Where(x => x.HandoutId == id).ExecuteDeleteAsync(ct); await db.Assets.Where(x => x.HandoutId == id).ExecuteDeleteAsync(ct);
                questions = await word.Parse(normalized, id, ct); db.Questions.AddRange(questions); await db.SaveChangesAsync(ct);
                await parsing.CommitAsync(ct);
                }
                foreach (var q in questions) await WordDocuments.SyncAssets(db, q);
                await db.SaveChangesAsync(ct);
                // Parsing is durable before preview conversion. Recovery must not replace question IDs.
                h.State = "draft"; await db.SaveChangesAsync(ct);
                await GenerateQuestionPreviews(id, dir, ct);
            } else if (kind == "previews") {
                var h = await db.Handouts.FindAsync([id], ct); if (h?.State != "draft") return;
                await GenerateQuestionPreviews(id, dir, ct);
            } else if (kind == "export") {
                var d = await db.Documents.FindAsync([id], ct) ?? throw new InvalidOperationException(); if (d.State == "ready") return;
                d.State = "processing"; d.Error = null; await db.SaveChangesAsync(ct);
                var (docx, pdf) = await word.BookletPdf(d, dir, ct);
                d.TemplateVersion = WordDocuments.BookletTemplateVersion;
                d.DocxKey = $"exports/{id}/booklet.docx"; d.PdfKey = $"exports/{id}/booklet.pdf";
                await Save(docx, d.DocxKey, WordDocuments.DocxType, ct); await Save(pdf, d.PdfKey, "application/pdf", ct);
                await using var file = File.OpenRead(pdf); d.PdfHash = Convert.ToHexStringLower(await SHA256.HashDataAsync(file, ct)); d.State = "ready"; await db.SaveChangesAsync(ct);
            } else if (kind == "support") {
                var d = await db.Documents.FindAsync([id], ct) ?? throw new ApiException(404, "学习资料不存在"); if(d.State == "ready") return;
                d.State = "processing"; d.Error = null; await db.SaveChangesAsync(ct); var (docx,pdf) = await word.SupportPdf(d,dir,ct);
                d.DocxKey = $"exports/{id}/support.docx"; d.PdfKey = $"exports/{id}/support.pdf";
                await Save(docx,d.DocxKey,WordDocuments.DocxType,ct); await Save(pdf,d.PdfKey,"application/pdf",ct);
                await using var file=File.OpenRead(pdf); d.PdfHash=Convert.ToHexStringLower(await SHA256.HashDataAsync(file,ct));d.State="ready";await db.SaveChangesAsync(ct);
            } else if (kind == "file") {
                var d = await db.Documents.FindAsync([id], ct) ?? throw new ApiException(404, "文件不存在"); if (d.State == "ready") return;
                d.State = "processing"; d.Error = null; await db.SaveChangesAsync(ct);
                var (docx, pdf) = await word.PrintFilePdf(d, dir, ct);
                if (docx != null) { d.DocxKey = $"print-files/{id}/document.docx"; await Save(docx, d.DocxKey, WordDocuments.DocxType, ct); }
                d.PdfKey = $"print-files/{id}/document.pdf"; await Save(pdf, d.PdfKey, "application/pdf", ct);
                await using var file = File.OpenRead(pdf); d.PdfHash = Convert.ToHexStringLower(await SHA256.HashDataAsync(file, ct)); d.State = "ready"; await db.SaveChangesAsync(ct);
            } else if (kind is "export-preview" or "handout-preview") {
                string key;
                if (kind == "export-preview") {
                    var d = await db.Documents.FindAsync([id], ct) ?? throw new ApiException(404, "文档不存在");
                    Guard.That(d.State == "ready", "文档尚未生成完成", 409); key = d.PdfKey;
                } else {
                    var h = await db.Handouts.FindAsync([id], ct) ?? throw new ApiException(404, "讲义不存在");
                    Guard.That(h.SourcePdfKey.Length > 0, "原讲义尚未转换完成", 409); key = h.SourcePdfKey;
                }
                var pdf = await word.Materialize(key, dir, "preview.pdf", ct);
                var prefix = PdfPreviews.Prefix(kind == "export-preview" ? "export" : "handout", id, key);
                string sourceHash;
                await using (var source = File.OpenRead(pdf)) sourceHash = Convert.ToHexStringLower(await SHA256.HashDataAsync(source, ct));
                var sharedPrefix = PdfPreviews.SharedPrefix(sourceHash);
                var manifest = await PdfPreviews.ReadManifest(objects, sharedPrefix + "/manifest.json", sourceHash, ct);
                if (manifest == null) {
                    var pages = await word.RenderPdfPages(pdf, sharedPrefix, dir, ct);
                    manifest = new(sourceHash, pages);
                    // Publish the shared manifest last, after the entire PDF is rendered.
                    await PdfPreviews.WriteManifest(objects, sharedPrefix + "/manifest.json", manifest, ct);
                }
                // Retain the original array format for older readers and rollback. Only these
                // small references are per handout; all lectures share the source page images.
                await PdfPreviews.WriteManifest(objects, prefix + "/manifest.json", manifest.Pages, ct);
                await PdfPreviews.WriteManifest(objects, prefix + "/source.json", manifest, ct);
            } else throw new InvalidOperationException("Unknown task kind");
        } finally { try { Directory.Delete(dir, true); } catch (IOException) { } }
    }
    async Task GenerateQuestionPreviews(Guid id, string dir, CancellationToken ct)
    {
        var questions = await db.Questions.Where(x => x.HandoutId == id && x.PreviewsJson == "[]").OrderBy(x => x.Order).AsNoTracking().ToListAsync(ct);
        var processed = 0;
        if (word.HandoutProgressCallback != null) await word.HandoutProgressCallback(new("previews", 0, questions.Count, "正在生成题目预览"), ct);
        foreach (var q in questions) {
            ct.ThrowIfCancellationRequested();
            var issues = Json.Read<Issue[]>(q.IssuesJson).Where(i => i.Code != "preview-failed").ToList();
            try {
                var preview = await word.Preview(q, dir, ct);
                await db.Questions.Where(x => x.Id == q.Id && x.Revision == q.Revision && x.PreviewsJson == "[]").ExecuteUpdateAsync(s => s.SetProperty(x => x.PreviewsJson, Json.Write(preview.Keys)).SetProperty(x => x.HalfPage, preview.Half).SetProperty(x => x.IssuesJson, Json.Write(issues)), ct);
            } catch (Exception e) when (!ct.IsCancellationRequested && e is not (OperationCanceledException or OutOfMemoryException)) {
                log.LogWarning(e, "Question preview {Id} failed; continuing other questions", q.Id);
                issues.Add(new Issue("preview-failed", "本题预览未完成：" + (e is ApiException ? e.Message : "转换异常，请重试；原稿与其他题目已保留"), true));
                await db.Questions.Where(x => x.Id == q.Id && x.Revision == q.Revision && x.PreviewsJson == "[]").ExecuteUpdateAsync(s => s.SetProperty(x => x.IssuesJson, Json.Write(issues)), ct);
            }
            processed++;
            if (word.HandoutProgressCallback != null) await word.HandoutProgressCallback(new("previews", processed, questions.Count, "正在生成题目预览"), ct);
        }
        var remaining = await db.Questions.CountAsync(x => x.HandoutId == id && x.PreviewsJson == "[]", ct);
        await db.Handouts.Where(x => x.Id == id && x.State == "draft").ExecuteUpdateAsync(s => s.SetProperty(x => x.Error, remaining == 0 ? null : $"{remaining} 道题的预览未完成，其他题目已保留，可重试未完成预览。"), ct);
        if (remaining == 0) await HandoutReuse.Completed(db, objects, id, config, ct);
    }
    async Task Save(string path, string key, string type, CancellationToken ct) { await using var file = File.OpenRead(path); await objects.Put(key, file, type, ct); }
}
public class WorkerLoop(IServiceScopeFactory scopes, ILogger<WorkerLoop> log, IConfiguration config) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var admission = new WorkerAdmission(config);
        async Task RecordAdmission(bool allowed)
        {
            try { await using var scope = scopes.CreateAsyncScope(); await QueueEstimates.Admission(scope.ServiceProvider.GetRequiredService<IObjects>(), Math.Clamp(config.GetValue("Worker:Concurrency", 1), 1, 2), allowed, stoppingToken); }
            catch (Exception e) when (e is not OutOfMemoryException) { log.LogDebug("Optional queue admission telemetry unavailable"); }
        }
        log.LogInformation("Document worker concurrency {Concurrency}", Math.Clamp(config.GetValue("Worker:Concurrency", 1), 1, 2));
        log.LogInformation("Document parsing strategy {Strategy}", PdfParsingPolicy.IsFixed(config) ? "fixed-no-model-no-ocr" : "model-assisted");
        await RecordAdmission(admission.Blocked(0) == null);
        await WorkerScheduler.Run(config.GetValue("Worker:Concurrency", 1), async ct => {
            try { await using var scope = scopes.CreateAsyncScope(); return await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception e) { log.LogError(e, "Worker unavailable"); return false; }
        }, admission.Blocked, reason => {
            _ = RecordAdmission(reason == null);
            if (reason == null) log.LogInformation("Document worker admission resumed");
            else log.LogWarning("Document worker admission paused: {Reason}; active jobs continue", reason);
        }, stoppingToken);
    }
}
