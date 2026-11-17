using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Writer;

namespace Siyue;

public record BookPageSplitInput(int Revision, string SourceVersion, int[] Cuts, int StartSequence = 1, string[]? Titles = null);
public record BookPageSource(string OriginalKey, string OriginalHash, string PdfKey, string PdfHash, string SourceVersion, int PageCount);
public record BookPageSplitPlan(int InputRevision, string SourceVersion, int[] Cuts, int StartSequence, string[] Titles, BookSection[] Sections);

/// <summary>Original-PDF page boundaries. No OCR, model calls, raster copies or schema changes.</summary>
public static class BookPageSplits
{
    const long MaxPdfBytes = 52_428_800;
    const long MaxSplitBytes = 188_743_680;
    const int MaxMetadata = 524_288; // Up to 200 titles (120 characters), duplicated in the immutable plan.
    static readonly SemaphoreSlim PreparationGate = new(1, 1);
    public static bool IsPageMode(BookImport b) => b.OriginalKey.Contains("/pages/original.", StringComparison.Ordinal) || b.NormalizedKey.EndsWith("/page-reference.docx", StringComparison.Ordinal);
    public static Guid PreviewJobId(BookImport b) => new(SHA256.HashData(Encoding.UTF8.GetBytes($"book-page-preview-v1:{b.Id}:{b.OriginalKey}"))[..16]);
    static string SourceKey(Guid id) => $"books/{id}/page-preview.json";
    static string PlanKey(Guid id, int revision) => $"books/{id}/page-split-{revision}.json";
    static string DefaultTitle(BookImport b) { var title = Path.GetFileNameWithoutExtension(b.FileName).Trim(); return title.Length == 0 ? "讲义" : title[..Math.Min(title.Length, 100)]; }
    static bool HashValid(string hash) => hash.Length == 64 && hash.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    static bool Missing(Exception e) => e is FileNotFoundException or DirectoryNotFoundException || e is Amazon.S3.AmazonS3Exception { StatusCode: System.Net.HttpStatusCode.NotFound };
    static async Task<T?> Read<T>(IObjects objects, string key, CancellationToken ct)
    {
        try {
            await using var stream = await objects.Get(key, ct); var bytes = new byte[MaxMetadata + 1]; var size = 0;
            while (size < bytes.Length) { var n = await stream.ReadAsync(bytes.AsMemory(size), ct); if (n == 0) break; size += n; }
            return size == 0 || size > MaxMetadata ? default : JsonSerializer.Deserialize<T>(bytes.AsSpan(0, size), Json.Options);
        } catch (Exception e) when (!ct.IsCancellationRequested && (Missing(e) || e is JsonException)) { return default; }
    }
    static async Task Write<T>(IObjects objects, string key, T value, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value, Json.Options); Guard.That(bytes.Length <= MaxMetadata, "讲次元数据超过限制");
        await using var stream = new MemoryStream(bytes); await objects.Put(key, stream, "application/json", ct);
    }
    static async Task<BookImport> Authorized(Guid id, Data db, Access access)
    {
        var book = await db.BookImports.FindAsync(id) ?? throw new ApiException(404, "讲义不存在");
        await access.Class(book.ClassId, book.CourseId); return book;
    }
    static bool HasChildren(BookImport book) => Json.Read<BookSection[]>(book.SectionsJson).Any(s => s.HandoutId.HasValue);
    static bool ValidSource(BookImport b, BookPageSource? s) => s != null && s.OriginalKey == b.OriginalKey &&
        HashValid(s.OriginalHash) && HashValid(s.PdfHash) && HashValid(s.SourceVersion) && s.PageCount is > 0 and <= 500 &&
        (s.PdfKey == b.OriginalKey && Path.GetExtension(b.FileName).Equals(".pdf", StringComparison.OrdinalIgnoreCase) ||
         s.PdfKey == $"book-page-sources/v1/{s.OriginalHash}/{s.PdfHash}.pdf") && s.SourceVersion == Version(s.OriginalHash, s.PdfHash, s.PageCount);
    static string Version(string originalHash, string pdfHash, int pages) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes($"book-pages-v1:{originalHash}:{pdfHash}:{pages}")));
    public static async Task<BookPageSource?> Source(BookImport b, IObjects objects, CancellationToken ct)
    {
        var source = await Read<BookPageSource>(objects, SourceKey(b.Id), ct); return ValidSource(b, source) ? source : null;
    }
    static async Task<string?> LockReason(BookImport b, Data db, CancellationToken ct)
    {
        if (HasChildren(b) || b.State is "split" or "splitting") return "这份讲义已建立讲次，当前仅供查看，不会替换已发布或使用中的资料。";
        if (await db.Work.AnyAsync(w => w.EntityId == b.Id && w.State == "running", ct)) return "讲义正在处理，请等待当前任务完成后再分讲。";
        if (b.State is not ("queued" or "review" or "failed")) return "讲义当前状态不能重新分讲，请等待处理完成。";
        return null;
    }
    public static async Task<object> Preview(Guid id, bool start, Data db, Access access, IObjects objects, Files files, CancellationToken ct)
    {
        var b = await Authorized(id, db, access); var source = await Source(b, objects, ct); var jobId = PreviewJobId(b);
        var job = await db.Work.AsNoTracking().SingleOrDefaultAsync(w => w.Id == jobId, ct);
        if (start && source == null) {
            if (job == null) {
                var created = new WorkItem { Id = jobId, Kind = "book-page-preview", EntityId = id }; db.Work.Add(created);
                try { await db.SaveChangesAsync(ct); job = created; }
                catch (DbUpdateException) { db.Entry(created).State = EntityState.Detached; job = await db.Work.AsNoTracking().SingleOrDefaultAsync(w => w.Id == jobId, ct); if (job == null) throw; }
            } else if (job.State is "failed" or "done") {
                await db.Work.Where(w => w.Id == jobId && (w.State == "failed" || w.State == "done")).ExecuteUpdateAsync(s => s.SetProperty(w => w.State, "queued").SetProperty(w => w.Error, (string?)null).SetProperty(w => w.Claim, (string?)null).SetProperty(w => w.LeaseUntil, (DateTimeOffset?)null), ct);
                job = await db.Work.AsNoTracking().SingleAsync(w => w.Id == jobId, ct);
            }
        }
        var reason = await LockReason(b, db, ct); var plan = await Read<BookPageSplitPlan>(objects, PlanKey(id, b.Revision), ct);
        var hasChildren = HasChildren(b); var busy = !hasChildren && await db.Work.AnyAsync(w => w.EntityId == id && w.State == "running", ct);
        var state = source != null ? "ready" : job?.State == "failed" ? "failed" : job?.State == "running" ? "processing" : job?.State == "queued" ? "queued" : "idle";
        var estimates = await QueueEstimates.Batch(db, objects, [id], ct);
        return new { state, title = b.FileName, pageCount = source?.PageCount, sourceVersion = source?.SourceVersion,
            sourceUrl = source == null ? null : await files.Grant(source.PdfKey, "application/pdf", Path.GetFileNameWithoutExtension(b.FileName) + ".pdf", access.User.Id, classId:b.ClassId, courseId:b.CourseId),
            revision = b.Revision, hasPlan = plan != null, hasChildren, busy, defaultTitle = DefaultTitle(b), canSplit = source != null && reason == null, lockReason = reason, error = source == null ? job?.Error : null,
            cuts = plan?.Cuts ?? [], startSequence = plan?.StartSequence ?? 1, titles = plan?.Titles ?? [], queue = estimates.GetValueOrDefault(id) };
    }
    public static void MapBookPageSplits(this WebApplication app)
    {
        app.MapGet("/api/books/{id:guid}/page-preview", (Guid id, Data db, Access access, IObjects objects, Files files, CancellationToken ct) => Preview(id, false, db, access, objects, files, ct));
        app.MapPost("/api/books/{id:guid}/page-preview", (Guid id, Data db, Access access, IObjects objects, Files files, CancellationToken ct) => Preview(id, true, db, access, objects, files, ct));
        app.MapPost("/api/books/{id:guid}/page-split", async (Guid id, BookPageSplitInput input, Data db, Access access, IObjects objects, CancellationToken ct) => {
            var b = await Authorized(id, db, access); return await Confirm(b, input, access.User.Id, db, objects, ct);
        });
    }
    public static BookSection[] Ranges(int pageCount, BookPageSplitInput input, string defaultTitle = "讲义")
    {
        Guard.That(pageCount is > 0 and <= 500 && input.Cuts != null && input.Cuts.Length < 200, "讲义最多500页，一次最多分成200讲");
        Guard.That(input.StartSequence > 0 && input.StartSequence <= int.MaxValue - input.Cuts.Length, "起始讲次无效");
        Guard.That(input.Cuts.Select((cut, i) => cut > 0 && cut < pageCount && (i == 0 || cut > input.Cuts[i - 1])).All(x => x), "分割线必须按页码递增，不能重复、位于首尾外或超出原稿");
        Guard.That(input.Titles == null || input.Titles.Length == 0 || input.Titles.Length == input.Cuts.Length + 1, "讲次标题数与分段数不一致");
        var boundaries = new[] { 0 }.Concat(input.Cuts).Append(pageCount).ToArray();
        return Enumerable.Range(0, boundaries.Length - 1).Select(i => {
            var title = input.Titles is { Length: > 0 } ? input.Titles[i]?.Trim() : null;
            title = string.IsNullOrWhiteSpace(title) ? Guard.Name(defaultTitle) : Guard.Name(title);
            return new BookSection(boundaries[i], boundaries[i + 1], input.StartSequence + i, title);
        }).ToArray();
    }
    static void WritePageReferences(string path, int pages)
    {
        var w = WordDocuments.W; var sy = AiBookOutline.SourceNamespace;
        using var doc = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document);
        var body = new XElement(w + "body", Enumerable.Range(1, pages).Select(page => new XElement(w + "p",
            new XAttribute(sy + "page", page), new XAttribute(sy + "top", "0"), new XAttribute(sy + "bottom", "1"), new XElement(w + "r", new XElement(w + "t", $"原稿第{page}页")))));
        doc.AddMainDocumentPart().Document = new Document(new XElement(w + "document", new XAttribute(XNamespace.Xmlns + "w", w), new XAttribute(XNamespace.Xmlns + "sy", sy), body).ToString(SaveOptions.DisableFormatting));
    }
    public static async Task FailedPreview(Guid id, string message, Data db, CancellationToken ct)
    {
        var b = await db.BookImports.FindAsync([id], ct);
        if (b != null && IsPageMode(b) && !HasChildren(b) && b.State is not ("split" or "splitting")) {
            b.State = "failed"; b.Error = message; await db.SaveChangesAsync(ct);
        }
    }
    public static async Task Prepare(Guid id, Data db, WordDocuments word, IObjects objects, string dir, CancellationToken ct)
    {
        var b = await db.BookImports.FindAsync([id], ct) ?? throw new ApiException(404, "讲义不存在");
        await PreparationGate.WaitAsync(ct);
        try {
            var original = await word.Materialize(b.OriginalKey, dir, "original" + Path.GetExtension(b.FileName).ToLowerInvariant(), ct);
            Guard.That(new FileInfo(original).Length is > 0 and <= MaxPdfBytes, "原稿超过50MB，请分册上传", 413);
            if (Path.GetExtension(original) == ".docx") WordDocuments.CheckPackage(original);
            string hash; await using (var bytes = File.OpenRead(original)) hash = Convert.ToHexStringLower(await SHA256.HashDataAsync(bytes, ct));
            var cacheKey = $"book-page-sources/v1/{hash}/source.json";
            var cached = await Read<BookPageSource>(objects, cacheKey, ct); string pdfPath, key;
            if (Path.GetExtension(original) == ".pdf") { pdfPath = original; key = b.OriginalKey; }
            else if (cached != null && cached.OriginalHash == hash && HashValid(cached.PdfHash) && cached.PdfKey == $"book-page-sources/v1/{hash}/{cached.PdfHash}.pdf") {
                try { pdfPath = await word.Materialize(cached.PdfKey, dir, "source.pdf", ct); key = cached.PdfKey; }
                catch (Exception e) when (Missing(e)) { pdfPath = await word.Convert(original, "pdf", dir, ct); key = ""; }
            } else { pdfPath = await word.Convert(original, "pdf", dir, ct); key = ""; }
            Guard.That(new FileInfo(pdfPath).Length is > 0 and <= MaxPdfBytes, "转换后的PDF超过50MB，请分册上传", 413);
            string pdfHash; await using (var bytes = File.OpenRead(pdfPath)) pdfHash = Convert.ToHexStringLower(await SHA256.HashDataAsync(bytes, ct));
            if (cached != null && key == cached.PdfKey && cached.PdfHash != pdfHash) throw new ApiException(409, "原稿缓存校验失败，请重新生成预览");
            using var pdf = PdfDocument.Open(pdfPath); Guard.That(pdf.NumberOfPages is > 0 and <= 500, "原稿须为1至500页", 422);
            if (key.Length == 0) { key = $"book-page-sources/v1/{hash}/{pdfHash}.pdf"; await using var bytes = File.OpenRead(pdfPath); await objects.Put(key, bytes, "application/pdf", ct); }
            var source = new BookPageSource(b.OriginalKey, hash, key, pdfHash, Version(hash, pdfHash, pdf.NumberOfPages), pdf.NumberOfPages);
            await Write(objects, cacheKey, source, ct);
            if (IsPageMode(b) && !HasChildren(b) && b.State is not ("split" or "splitting")) {
                var refs = Path.Combine(dir, "page-reference.docx"); WritePageReferences(refs, source.PageCount);
                b.NormalizedKey = $"books/{id}/page-reference.docx"; await using (var bytes = File.OpenRead(refs)) await objects.Put(b.NormalizedKey, bytes, WordDocuments.DocxType, ct);
                b.BlockCount = source.PageCount; b.State = "review"; b.Error = null; await db.SaveChangesAsync(ct);
            }
            // Publish only after the complete PDF and optional reference document are durable.
            await Write(objects, SourceKey(id), source, ct);
        } finally { PreparationGate.Release(); }
    }
    public static async Task<object> Confirm(BookImport b, BookPageSplitInput input, Guid staffId, Data db, IObjects objects, CancellationToken ct)
    {
        var source = await Source(b, objects, ct) ?? throw new ApiException(409, "请先等待原稿预览准备完成");
        Guard.That(input.SourceVersion == source.SourceVersion, "原稿版本已变化，请刷新预览后重新划分", 409);
        var ranges = Ranges(source.PageCount, input, DefaultTitle(b)); var titles = ranges.Select(s => s.Title).ToArray();
        var prior = await Read<BookPageSplitPlan>(objects, PlanKey(b.Id, b.Revision), ct);
        if (prior != null && HasChildren(b) && prior.InputRevision == input.Revision && prior.SourceVersion == input.SourceVersion &&
            prior.StartSequence == input.StartSequence && prior.Cuts.SequenceEqual(input.Cuts) && prior.Titles.SequenceEqual(titles))
            return new { bookId = b.Id, revision = b.Revision, state = b.State, sections = Json.Read<BookSection[]>(b.SectionsJson), lectureCount = ranges.Length };
        Guard.That(b.Revision == input.Revision, "讲义已被修改，请刷新后重试", 409);
        var initialLock = await LockReason(b, db, ct); Guard.That(initialLock == null, initialLock ?? "当前讲义不能重新划分", 409);
        Guard.That(b.State is "queued" or "review" or "failed", "请等待当前处理结束后再分讲", 409);
        var dir = Path.Combine(Path.GetTempPath(), "siyue-page-plan-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(dir);
        try {
            var refs = Path.Combine(dir, "page-reference.docx"); WritePageReferences(refs, source.PageCount);
            var normalizedKey = $"books/{b.Id}/page-reference/{Guid.NewGuid():N}/page-reference.docx";
            await using (var bytes = File.OpenRead(refs)) await objects.Put(normalizedKey, bytes, WordDocuments.DocxType, ct);
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            if (db.Database.IsNpgsql()) {
                await db.Database.ExecuteSqlInterpolatedAsync($"SELECT 1 FROM \"BookImports\" WHERE \"Id\"={b.Id} FOR UPDATE", ct);
                await db.Database.ExecuteSqlInterpolatedAsync($"SELECT 1 FROM \"Work\" WHERE \"EntityId\"={b.Id} AND \"State\" IN ('queued','running','waiting') FOR UPDATE", ct);
            }
            await db.Entry(b).ReloadAsync(ct);
            Guard.That(b.Revision == input.Revision && ValidSource(b, source), "讲义已被修改，请刷新后重试", 409);
            var reason = await LockReason(b, db, ct); Guard.That(reason == null, reason ?? "当前讲义不能重新划分", 409);
            Guard.That(b.State is "queued" or "review" or "failed", "请等待当前处理结束后再分讲", 409);
            await db.Work.Where(w => w.EntityId == b.Id && w.State == "queued" && (w.Kind == "book" || w.Kind == "book-auto")).ExecuteUpdateAsync(s => s.SetProperty(w => w.State, "superseded"), ct);
            await db.Classes.Where(c => c.Id == b.ClassId).ExecuteUpdateAsync(s => s.SetProperty(c => c.Name, c => c.Name), ct);
            for (var i = 0; i < ranges.Length; i++) {
                var range = ranges[i]; var candidates = await db.Lectures.Where(l => l.ClassId == b.ClassId && l.CourseId == b.CourseId && l.Sequence == range.Sequence).ToListAsync(ct);
                var matches = candidates.Where(l => BookStructure.TitleKey(l.Title) == BookStructure.TitleKey(range.Title)).ToArray();
                Guard.That(matches.Length == 0, $"已有第{range.Sequence}讲同名讲次，请调整起始讲次或标题，避免覆盖其他教材");
                var lecture = new Lecture { ClassId = b.ClassId, CourseId = b.CourseId, Sequence = range.Sequence, Title = range.Title };
                db.Lectures.Add(lecture);
                var handout = new Handout { LectureId = lecture.Id, RecognitionMode = b.RecognitionMode,
                    Version = (await db.Handouts.Where(h => h.LectureId == lecture.Id).MaxAsync(h => (int?)h.Version, ct) ?? 0) + 1,
                    FileName = $"第{range.Sequence}讲-{range.Title}.pdf" };
                handout.OriginalKey = $"handouts/{handout.Id}/original.pdf"; handout.SourcePdfKey = handout.OriginalKey;
                db.Handouts.Add(handout); db.Work.Add(new WorkItem { Kind = "import", EntityId = handout.Id, State = "waiting" }); ranges[i] = range with { HandoutId = handout.Id };
            }
            var plan = new BookPageSplitPlan(input.Revision, input.SourceVersion, input.Cuts, input.StartSequence, titles, ranges);
            await Write(objects, PlanKey(b.Id, b.Revision + 1), plan, ct);
            b.NormalizedKey = normalizedKey; b.BlockCount = source.PageCount; b.SectionsJson = Json.Write(ranges); b.State = "splitting"; b.Error = null; b.Revision++;
            db.Work.Add(new WorkItem { Kind = "book-split", EntityId = b.Id }); db.Audits.Add(new Audit { StaffId = staffId, Action = "book.page-split", TargetId = b.Id });
            await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
            return new { bookId = b.Id, revision = b.Revision, state = b.State, sections = ranges, lectureCount = ranges.Length };
        } finally { Directory.Delete(dir, true); }
    }
    public static async Task<bool> Split(Guid id, Data db, WordDocuments word, IObjects objects, string dir, CancellationToken ct)
    {
        var b = await db.BookImports.FindAsync([id], ct) ?? throw new ApiException(404, "讲义不存在");
        if (!IsPageMode(b)) return false;
        if (b.State == "split") return true;
        var source = await Source(b, objects, ct) ?? throw new ApiException(409, "原稿预览元数据缺失，请重新打开原稿");
        var plan = await Read<BookPageSplitPlan>(objects, PlanKey(id, b.Revision), ct) ?? throw new ApiException(409, "缺少已确认的页码划分");
        Guard.That(plan.SourceVersion == source.SourceVersion, "原稿版本已变化，请重新核对", 409);
        var sections = Json.Read<BookSection[]>(b.SectionsJson);
        Guard.That(Json.Write(plan.Sections) == Json.Write(sections) && sections.All(s => s.HandoutId.HasValue), "页码划分与讲次记录不一致", 409);
        var sourcePath = await word.Materialize(source.PdfKey, dir, "source.pdf", ct);
        await using (var bytes = File.OpenRead(sourcePath)) Guard.That(Convert.ToHexStringLower(await SHA256.HashDataAsync(bytes, ct)) == source.PdfHash, "原稿内容校验失败，未拆分讲次", 409);
        using var pdf = PdfDocument.Open(sourcePath); Guard.That(pdf.NumberOfPages == source.PageCount, "原稿页数发生变化", 409);
        // Stage each subset once and enforce the whole-book storage budget before publishing any child.
        // Retries stage all ranges again for the same exact budget, but never replace completed children.
        long totalBytes = 0; var paths = new Dictionary<Guid, string>();
        foreach (var section in sections) {
            var output = new PdfDocumentBuilder();
            for (var page = section.Start + 1; page <= section.End; page++) { ct.ThrowIfCancellationRequested(); output.AddPage(pdf, page); }
            var bytes = output.Build(); totalBytes += bytes.LongLength;
            Guard.That(bytes.LongLength <= MaxPdfBytes && totalBytes <= MaxSplitBytes, "分讲文件总量超过180MB或单讲超过50MB，请将原稿分册后重新上传", 413);
            var path = Path.Combine(dir, section.HandoutId!.Value + ".pdf"); await File.WriteAllBytesAsync(path, bytes, ct); paths.Add(section.HandoutId.Value, path);
        }
        var failures = new List<string>();
        foreach (var section in sections) {
            var hid = section.HandoutId!.Value;
            if (!await db.Work.AnyAsync(w => w.EntityId == hid && w.Kind == "import" && w.State == "waiting", ct)) continue;
            var h = await db.Handouts.FindAsync([hid], ct) ?? throw new ApiException(404, "讲次不存在");
            try {
                await using var stream = File.OpenRead(paths[hid]);
                await objects.Put(h.OriginalKey, stream, "application/pdf", ct); h.SourcePdfKey = h.OriginalKey; h.Error = null; await db.SaveChangesAsync(ct);
                await db.Work.Where(w => w.EntityId == hid && w.Kind == "import" && w.State == "waiting").ExecuteUpdateAsync(s => s.SetProperty(w => w.State, "queued"), ct);
            } catch (Exception e) when (!ct.IsCancellationRequested && e is not (OperationCanceledException or OutOfMemoryException)) { h.Error = e is ApiException ? e.Message : "分讲PDF生成失败，请重试"; await db.SaveChangesAsync(ct); failures.Add($"第{section.Sequence}讲"); }
        }
        b.State = failures.Count == 0 ? "split" : "failed"; b.Error = failures.Count == 0 ? null : string.Join("、", failures) + "未完成，请重试；已完成讲次保留。"; await db.SaveChangesAsync(ct); return true;
    }
}
