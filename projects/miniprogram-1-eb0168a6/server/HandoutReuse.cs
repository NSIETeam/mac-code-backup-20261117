using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

public record HandoutReuseSummary(string State, string SourceKind, int ReusedLectures, int ReusedQuestions, string SourceRecognitionMode);
public record HandoutReuseIndexResult(int Total, int Cached, int Invalid, int Missing, int Errors, int DistinctOriginals);
public record ReusedBook(BookImport Book, HandoutReuseSummary Reuse);

// Content-addressed hints only. The database snapshot is revalidated before every clone.
// Existing-result validation is deliberately distinct from an assertion about parser execution.
public static class HandoutReuse
{
    const string Prefix = "system/handout-reuse/v1";
    const long MaxOriginal = 52_428_800;
    const int MaxMetadata = 32_768, MaxHandouts = 200, MaxQuestions = 20_000;
    const long MaxSnapshotCharacters = 8L * 1024 * 1024;
    static readonly SemaphoreSlim IndexWrites = new(1, 1);
    sealed record Entry(string Kind, Guid Id, string OriginalKey, string Hash, string Format, string RecognitionMode, string Profile, string Snapshot, DateTimeOffset At);
    sealed record Fingerprint(string Hash, string OriginalKey, string Format);
    sealed record Snapshot(BookImport? Book, BookSection[] Sections, Lecture[] Lectures, Handout[] Handouts, Question[] Questions, Asset[] Assets, QuestionAsset[] Links);
    static string Format(string name) => Path.GetExtension(name).ToLowerInvariant();
    static bool HashValid(string value) => value.Length == 64 && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    static bool Missing(Exception e) => e is FileNotFoundException or DirectoryNotFoundException || e is Amazon.S3.AmazonS3Exception { StatusCode: System.Net.HttpStatusCode.NotFound } || e is ApiException { Status: 404 };
    static string Stamp(Snapshot value) => Convert.ToHexStringLower(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(value, Json.Options)));
    static string IndexKey(string hash, string format) => $"{Prefix}/content/{hash}{format}.json";
    public static bool Force(HttpRequest req) => string.Equals(req.Form["forceReparse"].FirstOrDefault(), "true", StringComparison.OrdinalIgnoreCase);

    public static async Task<string> Hash(Stream source, CancellationToken ct = default)
    {
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256); var buffer = new byte[65_536]; long total = 0;
        while (true) { var n = await source.ReadAsync(buffer, ct); if (n == 0) break; total += n; Guard.That(total <= MaxOriginal, "讲义不能超过 50MB"); sha.AppendData(buffer, 0, n); }
        Guard.That(total > 0, "讲义为空"); return Convert.ToHexStringLower(sha.GetHashAndReset());
    }
    static async Task<T?> Read<T>(IObjects objects, string key, CancellationToken ct)
    {
        try { await using var source = await objects.Get(key, ct); var bytes = new byte[MaxMetadata + 1]; var n = 0;
            while (n < bytes.Length) { var got = await source.ReadAsync(bytes.AsMemory(n), ct); if (got == 0) break; n += got; }
            return n == 0 || n > MaxMetadata ? default : JsonSerializer.Deserialize<T>(bytes.AsSpan(0, n), Json.Options);
        } catch (Exception e) when (!ct.IsCancellationRequested && (Missing(e) || e is JsonException)) { return default; }
    }
    static async Task Write<T>(IObjects objects, string key, T value, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value, Json.Options); Guard.That(bytes.Length <= MaxMetadata, "复用元数据超出限制");
        await using var stream = new MemoryStream(bytes); await objects.Put(key, stream, "application/json", ct);
    }
    public static async Task RememberUpload(IObjects objects, string kind, Guid id, string key, string name, string hash, CancellationToken ct = default)
    {
        try { await Write(objects, $"{Prefix}/fingerprints/{kind}/{id}.json", new Fingerprint(hash, key, Format(name)), ct); }
        catch (Exception e) when (!ct.IsCancellationRequested && e is not OutOfMemoryException) { /* An optional fingerprint cannot fail an upload. */ }
    }
    public static Task<HandoutReuseSummary?> Summary(IObjects objects, string kind, Guid id, CancellationToken ct = default) =>
        kind is "book" or "handout" ? Read<HandoutReuseSummary>(objects, $"{Prefix}/reuse/{kind}/{id}.json", ct) : Task.FromResult<HandoutReuseSummary?>(null);
    public static Task<IReadOnlyDictionary<Guid, HandoutReuseSummary>> Summaries(IObjects objects, string kind, IEnumerable<Guid> ids, CancellationToken ct = default) =>
        Summaries(objects, ids.Select(id => (Kind: kind, Id: id)), ct);
    public static async Task<IReadOnlyDictionary<Guid, HandoutReuseSummary>> Summaries(IObjects objects, IEnumerable<(string Kind, Guid Id)> items, CancellationToken ct = default)
    {
        var selected = items.Where(x => x.Id != Guid.Empty && x.Kind is "book" or "handout").Distinct().Take(30).ToArray();
        var result = new System.Collections.Concurrent.ConcurrentDictionary<Guid, HandoutReuseSummary>();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(2)); using var gate = new SemaphoreSlim(4);
        await Task.WhenAll(selected.Select(async item => {
            var entered = false;
            try { await gate.WaitAsync(timeout.Token); entered = true; var value = await Summary(objects, item.Kind, item.Id, timeout.Token); if (value?.State == "reused") result[item.Id] = value; }
            catch (Exception e) when (!ct.IsCancellationRequested && e is not OutOfMemoryException) { }
            finally { if (entered) gate.Release(); }
        }));
        return result;
    }
    static async Task RecordSummary(IObjects objects, string kind, Guid id, HandoutReuseSummary value, CancellationToken ct)
    {
        try { await Write(objects, $"{Prefix}/reuse/{kind}/{id}.json", value, ct); }
        catch (Exception e) when (!ct.IsCancellationRequested && e is not OutOfMemoryException) { /* A successful clone is already durable. */ }
    }
    static async Task<Entry[]> Candidates(IObjects objects, string hash, string format, string kind, CancellationToken ct)
    {
        if (!HashValid(hash) || format is not (".pdf" or ".doc" or ".docx")) return [];
        return (await Read<Entry[]>(objects, IndexKey(hash, format), ct) ?? []).Where(e => e.Kind == kind && e.Hash == hash && e.Format == format && e.Id != Guid.Empty &&
            e.Profile == "validated-existing-result-v1" && e.RecognitionMode is "legacy" or "named" or "auto").Take(16).ToArray();
    }
    static async Task<Snapshot?> Load(Data db, string kind, Guid id, CancellationToken ct)
    {
        BookImport? book = null; BookSection[] sections = []; Guid[] ids;
        if (kind == "book") {
            book = await db.BookImports.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct);
            if (book == null || book.State != "split" || !string.IsNullOrEmpty(book.Error) || book.BlockCount is <= 0 or > 20000 || book.NormalizedKey.Length == 0) return null;
            try { sections = Json.Read<BookSection[]>(book.SectionsJson); } catch (JsonException) { return null; }
            if (sections.Length is 0 or > 1000 || sections[0].Start != 0 || sections[^1].End != book.BlockCount || sections.Where((s,i) => s.End <= s.Start || i > 0 && s.Start != sections[i-1].End || s.Kind is not ("lesson" or "reference" or "exclude") || s.Kind == "lesson" && (s.HandoutId == null || s.Sequence <= 0 || string.IsNullOrWhiteSpace(s.Title))).Any()) return null;
            ids = sections.Where(s => s.HandoutId.HasValue).Select(s => s.HandoutId!.Value).ToArray();
            if (ids.Length == 0 || ids.Length > MaxHandouts || ids.Distinct().Count() != ids.Length) return null;
        } else ids = [id];
        var hs = await db.Handouts.AsNoTracking().Where(h => ids.Contains(h.Id)).OrderBy(h => h.Id).ToArrayAsync(ct);
        if (hs.Length != ids.Length || hs.Any(h => h.State is not ("draft" or "published") || !string.IsNullOrEmpty(h.Error) || h.NormalizedKey.Length == 0 || h.OriginalKey.Length == 0 || h.SourcePdfKey.Length == 0)) return null;
        var lids = hs.Select(h => h.LectureId).Distinct().ToArray(); var ls = await db.Lectures.AsNoTracking().Where(l => lids.Contains(l.Id)).OrderBy(l => l.Id).ToArrayAsync(ct);
        if (ls.Length != lids.Length || book != null && ls.Any(l => l.ClassId != book.ClassId || l.CourseId != book.CourseId)) return null;
        // Run this aggregate in the database before materializing question bodies.
        // The API has a small memory budget; large books use the normal worker path.
        var characters = await db.Questions.Where(q => ids.Contains(q.HandoutId))
            .SumAsync(q => (long)q.BlocksJson.Length + q.IssuesJson.Length + q.PreviewsJson.Length, ct);
        if (characters > MaxSnapshotCharacters) return null;
        var qs = await db.Questions.AsNoTracking().Where(q => ids.Contains(q.HandoutId)).OrderBy(q => q.HandoutId).ThenBy(q => q.Order).ThenBy(q => q.Id).Take(MaxQuestions + 1).ToArrayAsync(ct);
        if (qs.Length > MaxQuestions || hs.Any(h => !qs.Any(q => q.HandoutId == h.Id))) return null;
        var assets = await db.Assets.AsNoTracking().Where(a => ids.Contains(a.HandoutId)).OrderBy(a => a.Id).Take(30_001).ToArrayAsync(ct);
        if (assets.Length > 30_000 || assets.Any(a => a.ObjectKey.Length == 0) || assets.GroupBy(a => (a.HandoutId, a.RelationshipId)).Any(g => g.Count() != 1)) return null;
        try {
            foreach (var q in qs) {
                var blocks = Json.Read<ContentBlock[]>(q.BlocksJson); var previews = Json.Read<string[]>(q.PreviewsJson); _ = Json.Read<Issue[]>(q.IssuesJson);
                if (blocks.Length == 0 || previews.Length == 0 || previews.Any(string.IsNullOrWhiteSpace)) return null;
                foreach (var block in blocks) { _ = XElement.Parse(block.Xml); foreach (var relation in WordDocuments.ImageReferences(block.Xml)) if (!assets.Any(a => a.HandoutId == q.HandoutId && a.RelationshipId == relation)) return null; }
            }
        } catch (Exception e) when (e is JsonException or System.Xml.XmlException or InvalidOperationException or NullReferenceException) { return null; }
        var qids = qs.Select(q => q.Id).ToArray(); var links = await db.QuestionAssets.AsNoTracking().Where(a => qids.Contains(a.QuestionId)).OrderBy(a => a.Id).Take(100_001).ToArrayAsync(ct);
        if (links.Length > 100_000 || links.Any(l => !assets.Any(a => a.Id == l.AssetId && qs.Any(q => q.Id == l.QuestionId && q.HandoutId == a.HandoutId)))) return null;
        foreach (var q in qs) {
            var blocks = Json.Read<ContentBlock[]>(q.BlocksJson); var expected = new List<(Guid Asset, int Block, int Occurrence)>();
            for (var i = 0; i < blocks.Length; i++) { var occurrence = 0; foreach (var relation in WordDocuments.ImageReferences(blocks[i].Xml)) expected.Add((assets.Single(a => a.HandoutId == q.HandoutId && a.RelationshipId == relation).Id, i, occurrence++)); }
            var actual = links.Where(l => l.QuestionId == q.Id).Select(l => (Asset: l.AssetId, Block: l.BlockIndex, Occurrence: l.Occurrence)).ToArray();
            if (actual.Length != expected.Count || actual.Distinct().Count() != actual.Length || expected.Except(actual).Any()) return null;
        }
        return new(book, sections, ls, hs, qs, assets, links);
    }
    static async Task FilesExist(Snapshot source, IObjects objects, CancellationToken ct)
    {
        var keys = source.Handouts.SelectMany(h => new[] { h.OriginalKey, h.NormalizedKey, h.SourcePdfKey }).Concat(source.Assets.Select(a => a.ObjectKey))
            .Concat(source.Questions.SelectMany(q => Json.Read<string[]>(q.PreviewsJson))).Concat(source.Book == null ? [] : new[] { source.Book.OriginalKey, source.Book.NormalizedKey }).Distinct();
        foreach (var key in keys) { ct.ThrowIfCancellationRequested(); await using var stream = await objects.Get(key, ct); if (await stream.ReadAsync(new byte[1], ct) != 1) throw new FileNotFoundException("Empty cached document object"); }
    }
    static async Task LockSource(Data db, Snapshot source, CancellationToken ct)
    {
        if (!db.Database.IsNpgsql()) return;
        if (source.Book != null) await db.Database.ExecuteSqlInterpolatedAsync($"SELECT 1 FROM \"BookImports\" WHERE \"Id\"={source.Book.Id} FOR SHARE", ct);
        var ids = source.Handouts.Select(x => (object)x.Id).ToArray();
        var placeholders = string.Join(',', Enumerable.Range(0, ids.Length).Select(i => "{" + i + "}"));
        await db.Database.ExecuteSqlInterpolatedAsync(System.Runtime.CompilerServices.FormattableStringFactory.Create("SELECT 1 FROM \"Handouts\" WHERE \"Id\" IN (" + placeholders + ") ORDER BY \"Id\" FOR SHARE", ids), ct);
    }
    static async Task AddIndex(IObjects objects, Entry entry, CancellationToken ct)
    {
        await IndexWrites.WaitAsync(ct);
        try { var key = IndexKey(entry.Hash, entry.Format); var old = await Read<Entry[]>(objects, key, ct) ?? [];
            await Write(objects, key, old.Where(e => e.Kind != entry.Kind || e.Id != entry.Id).Prepend(entry).Take(16).ToArray(), ct);
        } finally { IndexWrites.Release(); }
    }
    static void Clone(Data db, Snapshot source, Handout sourceHandout, Handout target)
    {
        target.RecognitionMode = sourceHandout.RecognitionMode; target.NormalizedKey = sourceHandout.NormalizedKey; target.SourcePdfKey = sourceHandout.SourcePdfKey; target.State = "draft"; target.Error = null; target.Revision = 0;
        var amap = source.Assets.Where(a => a.HandoutId == sourceHandout.Id).ToDictionary(a => a.Id, a => new Asset { HandoutId = target.Id, RelationshipId = a.RelationshipId, ObjectKey = a.ObjectKey, ContentType = a.ContentType });
        var qmap = source.Questions.Where(q => q.HandoutId == sourceHandout.Id).ToDictionary(q => q.Id, q => new Question { HandoutId = target.Id, Order = q.Order, Label = q.Label, Family = q.Family, BlocksJson = q.BlocksJson, IssuesJson = q.IssuesJson, PreviewsJson = q.PreviewsJson, HalfPage = q.HalfPage, Confirmed = false, Revision = 0 });
        db.Handouts.Add(target); db.Assets.AddRange(amap.Values); db.Questions.AddRange(qmap.Values);
        db.QuestionAssets.AddRange(source.Links.Where(l => qmap.ContainsKey(l.QuestionId)).Select(l => new QuestionAsset { QuestionId = qmap[l.QuestionId].Id, AssetId = amap[l.AssetId].Id, BlockIndex = l.BlockIndex, Occurrence = l.Occurrence, Width = l.Width, Height = l.Height }));
    }
    public static async Task<ReusedBook?> TryBook(Data db, IObjects objects, BookImport target, string hash, bool force, CancellationToken ct = default)
    {
        if (force) return null;
        var candidates = await Candidates(objects, hash, Format(target.FileName), "book", ct);
        var candidateIds = candidates.Select(e => e.Id).ToArray();
        var localIds = await db.BookImports.AsNoTracking().Where(b => candidateIds.Contains(b.Id) && b.ClassId == target.ClassId && b.CourseId == target.CourseId).Select(b => b.Id).ToArrayAsync(ct);
        // A recently cloned other-class record must not hide the original target-class record.
        foreach (var entry in candidates.OrderByDescending(e => localIds.Contains(e.Id))) {
            var source = await Load(db, "book", entry.Id, ct);
            if (source?.Book == null || source.Book.OriginalKey != entry.OriginalKey || source.Book.RecognitionMode != entry.RecognitionMode || Stamp(source) != entry.Snapshot) continue;
            try { await FilesExist(source, objects, ct); } catch (Exception e) when (Missing(e)) { continue; }
            var summary = new HandoutReuseSummary("reused", "book", source.Handouts.Length, source.Questions.Length, source.Book.RecognitionMode);
            if (source.Book.ClassId == target.ClassId && source.Book.CourseId == target.CourseId) return new(source.Book, summary);
            await using var tx = await db.Database.BeginTransactionAsync(ct); await LockSource(db, source, ct);
            var fresh = await Load(db, "book", entry.Id, ct); if (fresh == null || Stamp(fresh) != entry.Snapshot) { await tx.RollbackAsync(ct); continue; }
            await db.Classes.Where(c => c.Id == target.ClassId).ExecuteUpdateAsync(s => s.SetProperty(c => c.Name, c => c.Name), ct);
            // Serialize parallel uploads to this class. The first clone may have
            // committed while this request was waiting for the class allocation lock.
            var originalKeys = candidates.Select(e => e.OriginalKey).Distinct().ToArray();
            var existingIds = await db.BookImports.AsNoTracking().Where(b => b.ClassId == target.ClassId && b.CourseId == target.CourseId && b.State == "split" && originalKeys.Contains(b.OriginalKey)).OrderByDescending(b => b.CreatedAt).Select(b => b.Id).Take(16).ToArrayAsync(ct);
            foreach (var existingId in existingIds) {
                var existing = await Load(db, "book", existingId, ct); if (existing?.Book == null) continue;
                try { await FilesExist(existing, objects, ct); } catch (Exception e) when (Missing(e)) { continue; }
                await tx.RollbackAsync(ct);
                return new(existing.Book, new("reused", "book", existing.Handouts.Length, existing.Questions.Length, existing.Book.RecognitionMode));
            }
            target.OriginalKey = source.Book.OriginalKey; target.NormalizedKey = source.Book.NormalizedKey; target.RecognitionMode = source.Book.RecognitionMode; target.BlockCount = source.Book.BlockCount; target.State = "split"; target.Error = null;
            var sections = source.Sections.ToArray();
            for (var i = 0; i < sections.Length; i++) {
                var section = sections[i]; if (!section.HandoutId.HasValue) continue;
                var sh = source.Handouts.Single(h => h.Id == section.HandoutId.Value);
                var matches = await db.Lectures.Where(l => l.ClassId == target.ClassId && l.CourseId == target.CourseId && l.Sequence == section.Sequence).ToListAsync(ct);
                var same = matches.Where(l => BookStructure.TitleKey(l.Title) == BookStructure.TitleKey(section.Title)).ToArray();
                Guard.That(same.Length <= 1, "目标班级存在重复讲次，请先整理", 409);
                var lecture = same.SingleOrDefault() ?? new Lecture { ClassId = target.ClassId, CourseId = target.CourseId, Sequence = section.Sequence, Title = section.Title };
                if (same.Length == 0) db.Lectures.Add(lecture);
                var h = new Handout { LectureId = lecture.Id, Version = (await db.Handouts.Where(x => x.LectureId == lecture.Id).MaxAsync(x => (int?)x.Version, ct) ?? 0) + 1, FileName = sh.FileName, OriginalKey = sh.OriginalKey };
                Clone(db, source, sh, h); sections[i] = section with { HandoutId = h.Id };
            }
            target.SectionsJson = Json.Write(sections); db.BookImports.Add(target); db.Audits.Add(new Audit { StaffId = target.StaffId, Action = "book.reuse", TargetId = target.Id });
            await db.SaveChangesAsync(ct); await tx.CommitAsync(ct); await RecordSummary(objects, "book", target.Id, summary, ct);
            await RememberUpload(objects, "book", target.Id, target.OriginalKey, target.FileName, hash, ct);
            try { var cloned = await Load(db, "book", target.Id, ct); if (cloned != null) await AddIndex(objects, entry with { Id = target.Id, OriginalKey = target.OriginalKey, Snapshot = Stamp(cloned), At = Clock.Now }, ct); }
            catch (Exception e) when (!ct.IsCancellationRequested && e is not OutOfMemoryException) { }
            return new(target, summary);
        }
        return null;
    }
    public static async Task<HandoutReuseSummary?> TryHandout(Data db, IObjects objects, Handout target, Guid staffId, string hash, bool force, CancellationToken ct = default)
    {
        if (force) return null;
        foreach (var entry in await Candidates(objects, hash, Format(target.FileName), "handout", ct)) {
            var source = await Load(db, "handout", entry.Id, ct);
            if (source == null || source.Handouts[0].OriginalKey != entry.OriginalKey || Stamp(source) != entry.Snapshot) continue;
            try { await FilesExist(source, objects, ct); } catch (Exception e) when (Missing(e)) { continue; }
            await using var tx = await db.Database.BeginTransactionAsync(ct); await LockSource(db, source, ct);
            var fresh = await Load(db, "handout", entry.Id, ct); if (fresh == null || Stamp(fresh) != entry.Snapshot) { await tx.RollbackAsync(ct); continue; }
            target.OriginalKey = source.Handouts[0].OriginalKey; Clone(db, source, source.Handouts[0], target);
            db.Audits.Add(new Audit { StaffId = staffId, Action = "handout.reuse", TargetId = target.Id }); await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
            var summary = new HandoutReuseSummary("reused", "handout", 1, source.Questions.Length, source.Handouts[0].RecognitionMode);
            await RecordSummary(objects, "handout", target.Id, summary, ct);
            try { var cloned = await Load(db, "handout", target.Id, ct); if (cloned != null) await AddIndex(objects, entry with { Id = target.Id, OriginalKey = target.OriginalKey, Snapshot = Stamp(cloned), At = Clock.Now }, ct); }
            catch (Exception e) when (!ct.IsCancellationRequested && e is not OutOfMemoryException) { }
            return summary;
        }
        return null;
    }
    static async Task Register(Data db, IObjects objects, string kind, Guid id, string profile, Dictionary<string, string>? hashes, CancellationToken ct)
    {
        var source = await Load(db, kind, id, ct); if (source == null) throw new InvalidDataException("Incomplete parsed result");
        await FilesExist(source, objects, ct); var key = source.Book?.OriginalKey ?? source.Handouts[0].OriginalKey; var name = source.Book?.FileName ?? source.Handouts[0].FileName;
        var fp = await Read<Fingerprint>(objects, $"{Prefix}/fingerprints/{kind}/{id}.json", ct); string hash;
        if (fp != null && fp.OriginalKey == key && fp.Format == Format(name) && HashValid(fp.Hash)) hash = fp.Hash;
        else if (hashes != null && hashes.TryGetValue(key, out var known)) hash = known;
        else { await using var stream = await objects.Get(key, ct); hash = await Hash(stream, ct); if (hashes != null) hashes[key] = hash; }
        await RememberUpload(objects, kind, id, key, name, hash, ct);
        await AddIndex(objects, new(kind, id, key, hash, Format(name), source.Book?.RecognitionMode ?? source.Handouts[0].RecognitionMode, profile, Stamp(source), Clock.Now), ct);
    }
    public static async Task Completed(Data db, IObjects objects, Guid handoutId, IConfiguration config, CancellationToken ct = default)
    {
        try {
            // This hook also runs when historical work is published. It attests to
            // a compatible result snapshot, never to which parser produced it.
            const string profile = "validated-existing-result-v1";
            var marker = handoutId.ToString(); var parents = await db.BookImports.AsNoTracking().Where(b => b.State == "split" && b.SectionsJson.Contains(marker)).Select(b => b.Id).Take(8).ToArrayAsync(ct);
            if (parents.Length == 0) await Register(db, objects, "handout", handoutId, profile, null, ct);
            else foreach (var id in parents) { try { await Register(db, objects, "book", id, profile, null, ct); } catch (InvalidDataException) { } }
        } catch (Exception e) when (!ct.IsCancellationRequested && e is not OutOfMemoryException) { /* Optional cache must not fail a completed import. */ }
    }
    public static async Task<HandoutReuseIndexResult> IndexExisting(Data db, IObjects objects, CancellationToken ct = default, int limit = 500)
    {
        limit = Math.Clamp(limit, 1, 500); var books = await db.BookImports.AsNoTracking().OrderBy(b => b.CreatedAt).ThenBy(b => b.Id).Take(limit).ToArrayAsync(ct);
        var referenced = new HashSet<Guid>(); foreach (var b in await db.BookImports.AsNoTracking().Select(b => b.SectionsJson).ToArrayAsync(ct)) { try { foreach (var s in Json.Read<BookSection[]>(b)) if (s.HandoutId.HasValue) referenced.Add(s.HandoutId.Value); } catch (JsonException) { } }
        var singles = await db.Handouts.AsNoTracking().Where(h => !referenced.Contains(h.Id)).OrderBy(h => h.CreatedAt).ThenBy(h => h.Id).Take(Math.Max(0, limit - books.Length)).ToArrayAsync(ct);
        var work = books.Select(b => (Kind: "book", b.Id, b.OriginalKey, b.FileName)).Concat(singles.Select(h => (Kind: "handout", h.Id, h.OriginalKey, h.FileName))).ToArray(); var hashes = new Dictionary<string, string>(); int cached = 0, invalid = 0, missing = 0, errors = 0;
        foreach (var item in work) { ct.ThrowIfCancellationRequested(); try {
                if (!hashes.TryGetValue(item.OriginalKey, out var hash)) { await using var stream = await objects.Get(item.OriginalKey, ct); hash = await Hash(stream, ct); hashes[item.OriginalKey] = hash; }
                await RememberUpload(objects, item.Kind, item.Id, item.OriginalKey, item.FileName, hash, ct);
                await Register(db, objects, item.Kind, item.Id, "validated-existing-result-v1", hashes, ct); cached++; }
            catch (InvalidDataException) { invalid++; } catch (Exception e) when (Missing(e)) { missing++; } catch (Exception e) when (!ct.IsCancellationRequested && e is not OutOfMemoryException) { errors++; } }
        return new(work.Length, cached, invalid, missing, errors, hashes.Count);
    }
}
