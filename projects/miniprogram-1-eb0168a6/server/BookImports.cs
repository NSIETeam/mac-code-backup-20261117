using System.Text.RegularExpressions;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
namespace Siyue;

public class BookImport : Entity
{
    public string RecognitionMode { get; set; } = QuestionRecognition.Legacy;
    public Guid StaffId { get; set; } public Guid ClassId { get; set; } public Guid CourseId { get; set; }
    public string FileName { get; set; } = ""; public string OriginalKey { get; set; } = ""; public string NormalizedKey { get; set; } = "";
    public string State { get; set; } = "queued"; public string? Error { get; set; } public int Revision { get; set; }
    public string SectionsJson { get; set; } = "[]"; public int BlockCount { get; set; } public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public record BookSection(int Start, int End, int Sequence, string Title, string Kind = "lesson", Guid? HandoutId = null);
public record BookReview(int Revision, BookSection[] Sections);
public static class BookStructure
{
    static readonly XNamespace W = WordDocuments.W;
    const string Digits = "[0-9０-９一二三四五六七八九十百千零〇两]+";
    static readonly Regex Heading = new(@"^\s*[【\[（(]?\s*(?:第\s*(?<n>" + Digits + @")\s*(?<unit>讲|课时|课|节)|讲次\s*[：:]?\s*(?<n>" + Digits + @")|(?:讲|课时)\s*(?<n>" + Digits + @")(?=$|[\s：:、.．—\-】\]）)]))\s*[】\]）)]?\s*(?<title>.*)$");
    public static XElement[] Blocks(XElement body) => body.Elements().Where(x => x.Name != W + "sectPr").SelectMany(x => x.Name == W + "sdt" && x.Element(W + "sdtContent") is XElement content ? Blocks(content) : new[] { new XElement(x) }).ToArray();
    public static int Number(string value)
    {
        value = value.Normalize(System.Text.NormalizationForm.FormKC); if (int.TryParse(value, out var n)) return n;
        long total = 0; var digit = 0;
        foreach (var c in value) {
            var unit = c switch { '十' => 10, '百' => 100, '千' => 1000, _ => 0 };
            if (unit > 0) { total += (digit == 0 ? 1 : digit) * unit; digit = 0; }
            else { digit = c == '两' ? 2 : c == '〇' ? 0 : "零一二三四五六七八九".IndexOf(c); if (digit < 0) return 0; }
        }
        return total + digit <= int.MaxValue ? (int)(total + digit) : 0;
    }
    public static bool IsHeading(string text) => text.Length <= 120 && Heading.IsMatch(text);
    public static BookSection? HeadingCandidate(string text) {
        if (text.Length > 120) return null;
        var match = Heading.Match(text); if (!match.Success) return null;
        var sequence = Number(match.Groups["n"].Value); if (sequence < 1) return null;
        var title = match.Groups["title"].Value.Trim().TrimStart('：', ':', '、', ' ', '—', '-', '.', '．');
        return new(0, 0, sequence, string.IsNullOrWhiteSpace(title) ? "讲义" : title);
    }
    public static string TitleKey(string title) => Regex.Replace(title.Normalize(System.Text.NormalizationForm.FormKC).Trim(), @"\s+", " ");
    static bool Toc(XElement x, string text) => x.Descendants(W + "pStyle").Any(p => Regex.IsMatch((string?)p.Attribute(W + "val") ?? "", "^(TOC|目录)", RegexOptions.IgnoreCase)) || x.Descendants(W + "hyperlink").Any(p => ((string?)p.Attribute(W + "anchor"))?.StartsWith("_Toc") == true) || Regex.IsMatch(text, @"[.…·]{2,}\s*\d+\s*$");
    public static BookSection[] Detect(XElement[] blocks)
    {
        var texts = blocks.Select(x => WordDocuments.Block(x).Text.Trim()).ToArray();
        // A section inside a lecture is not a new lecture. Use 节 only for books without 讲/课 headings.
        var primary = blocks.Select((x, i) => !Toc(x, texts[i]) && texts[i].Length <= 120 ? Heading.Match(texts[i]) : Match.Empty).Any(m => m.Success && m.Groups["unit"].Value != "节");
        var starts = new List<BookSection>(); var answers = false; var lastLesson = 0;
        for (var i = 0; i < blocks.Length; i++) {
            var x = blocks[i]; var text = texts[i];
            if (Toc(x, text) || text.Length > 120) continue;
            if (Regex.IsMatch(text, @"^[【\[]?(参考答案|答案与解析|答案解析|参考答案与解析|附录)[】\]]?[：:\s]*$")) { answers = true; starts.Add(new(i, 0, 0, text, "reference")); continue; }
            var m = Heading.Match(text); if (!m.Success || (primary && m.Groups["unit"].Value == "节")) continue;
            var seq = Number(m.Groups["n"].Value); if (seq < 1) continue;
            var title = m.Groups["title"].Value.Trim().TrimStart('：', ':', '、', ' ', '—', '-', '.', '．');
            var referenceTitle = Regex.IsMatch(title, @"^(参考答案|答案与解析|答案解析|答案)[：:\s]*$");
            if (referenceTitle) answers = true;
            else if (answers && lastLesson > 0 && seq > lastLesson) answers = false;
            if (!answers) lastLesson = Math.Max(lastLesson, seq);
            title = string.IsNullOrWhiteSpace(title) ? "讲义" : title;
            var previous = starts.LastOrDefault();
            // Repeated running headings stay inside the original range; no paragraphs are discarded.
            if (!answers && previous is { Kind: "lesson" } && previous.Sequence == seq && TitleKey(previous.Title) == TitleKey(title)) continue;
            starts.Add(new(i, 0, seq, title, answers ? "reference" : "lesson"));
        }
        if (starts.Count == 0 || starts[0].Start > 0) starts.Insert(0, new(0, 0, 0, "封面、目录或待分配内容", "unassigned"));
        return starts.Select((s, i) => s with { End = i + 1 < starts.Count ? starts[i + 1].Start : blocks.Length }).ToArray();
    }
    public static BookSection[] DetectFile(XElement[] blocks, string fileName, int nextSequence)
    {
        var result = Detect(blocks);
        if (result.Any(s => s.Kind == "lesson" || s.Kind == "reference") || blocks.Any(x => WordDocuments.PdfOrigin(x)?.Checks.Contains("ai-lecture-review") == true) ||
            !blocks.Any(x => WordDocuments.IsQuestionStart(WordDocuments.Block(x).Text.Trim()) ||
                (WordDocuments.PdfOrigin(x)?.Engine == "ai-layout" && x.Descendants(XName.Get("docPr", "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing")).Any(p => (string?)p.Attribute("descr") == "question")))) return result;
        // A single handout need not repeat its lecture number inside the Word body.
        var name = Path.GetFileNameWithoutExtension(fileName);
        var match = Heading.Match(name);
        var embeddedNumbers=Regex.Matches(name,@"第\s*(?<n>"+Digits+@")\s*(?:讲|课时|课|节)").Select(m=>Number(m.Groups["n"].Value)).Where(n=>n>0).Distinct().ToArray();
        if(embeddedNumbers.Length>1)return result; // A multi-lecture filename is not a single-lecture fallback.
        var sequence = match.Success ? Number(match.Groups["n"].Value) : embeddedNumbers.Length==1 ? embeddedNumbers[0] : nextSequence;
        var title = match.Success ? match.Groups["title"].Value.Trim().TrimStart('：', ':', '、', ' ') : name;
        return [new(0, blocks.Length, sequence, string.IsNullOrWhiteSpace(title) ? "讲义" : title[..Math.Min(120, title.Length)])];
    }
    public static XElement[] Read(string path) { WordDocuments.CheckPackage(path); using var doc = WordprocessingDocument.Open(path, false); var body = doc.MainDocumentPart?.Document?.Body ?? throw new ApiException(422,"Word 缺少正文"); return Blocks(XElement.Parse(body.OuterXml)); }
    public static void Slice(string source, string output, int start, int end)
    {
        File.Copy(source, output, true); using var doc = WordprocessingDocument.Open(output, true); var main = doc.MainDocumentPart!;
        var original = XElement.Parse((main.Document?.Body ?? throw new ApiException(422,"Word 缺少正文")).OuterXml); var blocks = Blocks(original); Guard.That(start >= 0 && end > start && end <= blocks.Length, "讲次段落范围无效");
        var body = new XElement(W + "body", blocks.Skip(start).Take(end - start), original.Element(W + "sectPr"));
        main.Document.Body = new Body(body.ToString(SaveOptions.DisableFormatting)); main.Document.Save();
        // A bound book can contain hundreds of images; a slice should not carry every other lecture's images.
        var used = XElement.Parse(main.Document.OuterXml).DescendantsAndSelf().Attributes().Where(a=>a.Name.Namespace == WordDocuments.R).Select(a=>a.Value).ToHashSet();
        foreach(var part in main.Parts.Where(p=>p.OpenXmlPart is ImagePart && !used.Contains(p.RelationshipId)).ToArray()) main.DeletePart(part.RelationshipId);
    }
}
public static class BookImports
{
    static bool MissingObject(Exception e) => e is FileNotFoundException or DirectoryNotFoundException ||
        e is Amazon.S3.AmazonS3Exception { StatusCode: System.Net.HttpStatusCode.NotFound } ||
        e is ApiException { Status: 404 };

    static async Task<HandoutProgress?> ReadProgress(Guid id, IObjects objects, CancellationToken ct)
    {
        try {
            await using var stream = await objects.Get($"books/{id}/progress.json", ct);
            // Progress is optional metadata, never an unbounded document download.
            var buffer = new byte[16_385]; var length = 0;
            while (length < buffer.Length) { var read = await stream.ReadAsync(buffer.AsMemory(length), ct); if (read == 0) break; length += read; }
            if (length == buffer.Length) return null;
            return System.Text.Json.JsonSerializer.Deserialize<HandoutProgress>(buffer.AsSpan(0, length), Json.Options);
        }
        catch (Exception e) when (!ct.IsCancellationRequested && (MissingObject(e) || e is System.Text.Json.JsonException)) { return null; }
    }

    static async Task<string> SourcePdfKey(Guid id, IObjects objects, CancellationToken ct)
    {
        var key = $"books/{id}/source.pdf";
        try { await using var source = await objects.Get(key, ct); return key; }
        catch (Exception e) when (!ct.IsCancellationRequested && MissingObject(e)) { return ""; }
    }

    public static async Task<BookImport> Upload(Guid classId, Guid? courseId, HttpRequest req, Data db, Access a, IObjects objects)
    {
        await a.Class(classId, courseId); var form = await req.ReadFormAsync(); var file = form.Files.GetFile("file"); var suppliedName = form["originalName"].FirstOrDefault();
        var name = Path.GetFileName((suppliedName ?? file?.FileName ?? "").Replace('\\', '/')).Trim();
        var ext = Path.GetExtension(name).ToLowerInvariant();
        Guard.That(file != null && file.Length is > 0 and <= 52_428_800 && name.Length <= 255 && !name.Any(char.IsControl) && ext is ".doc" or ".docx" or ".pdf", "请选择不超过 50MB 的 Word 或 PDF 讲义");
        var mode = QuestionRecognition.Validate(form["recognitionMode"].FirstOrDefault());
        var splitMode = form["splitMode"].FirstOrDefault(); Guard.That(string.IsNullOrEmpty(splitMode) || splitMode is "pages" or "blocks", "分讲方式无效");
        var pages = splitMode == "pages";
        var resolvedCourseId = courseId ?? await ClassHandoutCourse(classId, db, a);
        var book = new BookImport { RecognitionMode = mode, StaffId = a.User.Id, ClassId = classId, CourseId = resolvedCourseId, FileName = name };
        string hash; await using (var input = file!.OpenReadStream()) hash = await HandoutReuse.Hash(input, req.HttpContext.RequestAborted);
        var reused = await HandoutReuse.TryBook(db, objects, book, hash, HandoutReuse.Force(req), req.HttpContext.RequestAborted);
        if (reused != null) { req.HttpContext.Items["HandoutReuse"] = reused.Reuse; return reused.Book; }
        book.OriginalKey = $"books/{book.Id}/{(pages ? "pages/" : "")}original{ext}"; await using var stream = file!.OpenReadStream(); await objects.Put(book.OriginalKey, stream, "application/octet-stream");
        await HandoutReuse.RememberUpload(objects, "book", book.Id, book.OriginalKey, name, hash, req.HttpContext.RequestAborted);
        db.BookImports.Add(book); db.Work.Add(new WorkItem { Id = pages ? BookPageSplits.PreviewJobId(book) : Guid.NewGuid(), Kind = pages ? "book-page-preview" : "book-auto", EntityId = book.Id }); db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "book.upload", TargetId = book.Id }); await db.SaveChangesAsync(); return book;
    }
    // A technical grouping for class-owned handouts, not an inferred academic subject.
    static readonly Guid ClassHandoutCourseId = new("1740d5dc-1a24-4852-b31b-dc58a9d1d32a");
    static async Task<Guid> ClassHandoutCourse(Guid classId, Data db, Access a)
    {
        var actor = a.User; Guard.That(actor.Role is "admin" or "teacher", "当前身份不能上传班级讲义", 403);
        var courses = await db.Assignments.Where(x => x.ClassId == classId && (actor.Role == "admin" || x.StaffId == actor.Id) && db.Courses.Any(c => c.Id == x.CourseId)).Select(x => x.CourseId).Distinct().ToListAsync();
        if (courses.Count == 1) return courses[0];
        var course = await db.Courses.FindAsync(ClassHandoutCourseId);
        if (course == null) {
            Guard.That(!await db.Courses.IgnoreQueryFilters().AnyAsync(x => x.Id == ClassHandoutCourseId), "班级讲义归档已停用，请联系管理员", 409);
            course = new Course { Id = ClassHandoutCourseId, Name = "班级讲义" }; db.Courses.Add(course);
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException) { db.Entry(course).State = EntityState.Detached; if (!await db.Courses.AnyAsync(x => x.Id == ClassHandoutCourseId)) throw; }
        }
        if (!await db.Assignments.AnyAsync(x => x.ClassId == classId && x.CourseId == ClassHandoutCourseId && x.StaffId == actor.Id)) {
            var id = new Guid(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"class-course:{actor.Id}:{classId}:{ClassHandoutCourseId}"))[..16]);
            var assignment = new Assignment { Id = id, ClassId = classId, CourseId = ClassHandoutCourseId, StaffId = actor.Id }; db.Assignments.Add(assignment);
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException) { db.Entry(assignment).State = EntityState.Detached; if (!await db.Assignments.AnyAsync(x => x.Id == id)) throw; }
        }
        return ClassHandoutCourseId;
    }
    static async Task<BookImport> AccessBook(Guid id, Data db, Access a) { var book = await db.BookImports.FindAsync(id) ?? throw new ApiException(404, "合订讲义不存在"); await a.Class(book.ClassId, book.CourseId); return book; }
    public static void MapBookImports(this WebApplication app)
    {
        app.MapPost("/api/classes/{classId:guid}/books", async (Guid classId, Guid? courseId, HttpRequest req, Data db, Access a, IObjects objects) => { var b = await Upload(classId, courseId, req, db, a, objects); return Results.Accepted(value: new { b.Id, b.State, reuse = req.HttpContext.Items["HandoutReuse"] as HandoutReuseSummary }); }).DisableAntiforgery();
        app.MapGet("/api/classes/{classId:guid}/books", async (Guid classId, Guid courseId, int? offset, Data db, Access a, IObjects objects, CancellationToken ct) => {
            await a.Class(classId, courseId); Guard.That((offset ?? 0) >= 0, "分页位置无效");
            var books = await db.BookImports.Where(x => x.ClassId == classId && x.CourseId == courseId).OrderByDescending(x => x.CreatedAt).ThenBy(x => x.Id).Skip(offset ?? 0).Take(50).Select(x => new { x.Id, x.FileName, x.State, x.Error, x.CreatedAt }).ToListAsync(ct);
            var estimates = await QueueEstimates.Batch(db, objects, books.Select(x => x.Id), ct);
            return books.Select(x => new { x.Id, x.FileName, x.State, x.Error, x.CreatedAt, queue = estimates.GetValueOrDefault(x.Id) });
        });
        app.MapGet("/api/books/{id:guid}", async (Guid id, int? blockOffset, Data db, Access a, WordDocuments word, IObjects objects, Files files, CancellationToken ct) => {
            var b = await AccessBook(id, db, a); var sections = Json.Read<BookSection[]>(b.SectionsJson); var ids = sections.Where(s => s.HandoutId.HasValue).Select(s => s.HandoutId!.Value).ToArray();
            var offset = blockOffset ?? 0; Guard.That(offset >= 0 && offset <= b.BlockCount, "正文分页位置无效");
            var blocks = Array.Empty<object>(); var reviewBlocks = Array.Empty<object>(); string? blockError = null; var dir = Path.Combine(Path.GetTempPath(), "siyue-book-" + Guid.NewGuid()); Directory.CreateDirectory(dir);
            try { if (b.NormalizedKey.Length > 0 && b.State == "review") { var path = await word.Materialize(b.NormalizedKey, dir, "book.docx", ct); var all = BookStructure.Read(path); reviewBlocks = all.Select((x,i)=>(x,i)).Where(p=>WordDocuments.IsPdfReview(p.x)).Select(p=>(object)new {index=p.i,text=WordDocuments.Block(p.x).Text}).ToArray(); blocks = all.Skip(offset).Take(50).Select((x,i) => { var text = WordDocuments.Block(x).Text; return (object)new { index = offset + i, text = text.Length <= 2000 ? text : text[..2000] + "…（完整内容请打开原稿）", images = WordDocuments.ImageReferences(x.ToString()).Length, needsReview = WordDocuments.IsPdfReview(x) }; }).ToArray(); } }
            catch (Exception e) when (!ct.IsCancellationRequested && e is not (OperationCanceledException or OutOfMemoryException)) { blockError = "正文段落暂时无法加载，请刷新重试；已识别讲次和原稿仍保留。"; }
            finally { Directory.Delete(dir, true); }
            var handouts = await db.Handouts.Where(x => ids.Contains(x.Id)).Select(x => new { x.Id, x.LectureId, x.State, x.Error }).ToListAsync(ct);
            var questions = await db.Questions.Where(x => ids.Contains(x.HandoutId)).Select(x => new { x.HandoutId, x.Confirmed, x.IssuesJson, x.PreviewsJson }).ToListAsync(ct);
            var counts = questions.GroupBy(x => x.HandoutId).ToDictionary(g => g.Key, g => new {
                questionCount = g.Count(), reviewCount = g.Count(q => !q.Confirmed && Json.Read<Issue[]>(q.IssuesJson).Any(issue => issue.Blocking)),
                previewCount = g.Count(q => Json.Read<string[]>(q.PreviewsJson).Length > 0)
            });
            var estimates = await QueueEstimates.Batch(db, objects, handouts.Select(h => h.Id).Append(b.Id), ct);
            return new { splitMode = BookPageSplits.IsPageMode(b) ? "pages" : "blocks", book = new { b.Id, b.StaffId, b.ClassId, b.CourseId, b.RecognitionMode, b.FileName, b.State, b.Error, b.Revision, b.BlockCount, b.CreatedAt, b.OriginalKey, b.NormalizedKey }, queue = estimates.GetValueOrDefault(b.Id), sections, blocks, reviewBlocks, blockError, progress = await ReadProgress(id, objects, ct), blockHasMore = b.State == "review" && offset + blocks.Length < b.BlockCount, blockNextOffset = offset + blocks.Length, handouts = handouts.Select(h => { counts.TryGetValue(h.Id, out var count); return new { h.Id, h.LectureId, h.State, h.Error, queue = estimates.GetValueOrDefault(h.Id), questionCount = count?.questionCount ?? 0, reviewCount = count?.reviewCount ?? 0, previewCount = count?.previewCount ?? 0 }; }), sourceUrl = await files.Grant(b.OriginalKey, Path.GetExtension(b.FileName).Equals(".pdf", StringComparison.OrdinalIgnoreCase) ? "application/pdf" : "application/octet-stream", b.FileName, a.User.Id, classId: b.ClassId, courseId: b.CourseId) };
        });
        app.MapPost("/api/books/{id:guid}/retry", async(Guid id, Data db, Access a)=>{
            var book = await AccessBook(id,db,a); var split = Json.Read<BookSection[]>(book.SectionsJson).Any(s=>s.HandoutId.HasValue); await using var tx=await db.Database.BeginTransactionAsync();
            Guard.That(await db.BookImports.Where(x=>x.Id==id && x.State=="failed").ExecuteUpdateAsync(s=>s.SetProperty(x=>x.State,split ? "splitting" : "queued").SetProperty(x=>x.Error,(string?)null))==1,"当前任务无需重试",409);
            var auto = await db.Work.AnyAsync(w => w.EntityId == id && w.Kind == "book-auto");
            if (!split && BookPageSplits.IsPageMode(book)) {
                var previewId = BookPageSplits.PreviewJobId(book); var preview = await db.Work.FindAsync(previewId);
                if (preview == null) db.Work.Add(new WorkItem { Id = previewId, Kind = "book-page-preview", EntityId = id });
                else { Guard.That(preview.State is "failed" or "done", "预览任务正在处理", 409); preview.State = "queued"; preview.Error = null; preview.Claim = null; preview.LeaseUntil = null; }
            } else db.Work.Add(new WorkItem {Kind=split ? "book-split" : auto ? "book-auto" : "book",EntityId=id});await db.SaveChangesAsync();await tx.CommitAsync();return Results.Accepted();
        });
        app.MapPost("/api/books/{id:guid}/confirm", async (Guid id, BookReview input, Data db, Access a, CancellationToken ct) => {
            var b = await AccessBook(id, db, a); Guard.That(!BookPageSplits.IsPageMode(b), "请在原稿预览中按页确认分讲", 409); if (b.State is "split" or "splitting") return Results.Ok(new { sections = Json.Read<BookSection[]>(b.SectionsJson) });
            Guard.That(b.State == "review", "讲义尚未识别完成", 409);
            var sections = await QueueSplit(b, input.Revision, input.Sections, a.User.Id, db, ct);
            return Results.Ok(new { sections });
        });
    }

    static async Task<BookSection[]> QueueSplit(BookImport b, int revision, BookSection[] inputSections, Guid staffId, Data db, CancellationToken ct)
    {
        var sections = inputSections.Select(s=>s with {HandoutId=null}).OrderBy(x => x.Start).ToArray();
        Guard.That(sections.Length > 0 && sections.Length <= b.BlockCount && sections[0].Start == 0 && sections[^1].End == b.BlockCount && sections.Select((s,i) => s.End > s.Start && (i == 0 || s.Start == sections[i-1].End)).All(x=>x), "所有段落必须连续归属，不能遗漏或重叠");
        Guard.That(sections.All(s => s.Kind is "lesson" or "reference" or "exclude"), "请将待分配内容归入讲次，或确认属于封面目录后排除");
        var lessons = sections.Where(s => s.Kind == "lesson").ToArray(); Guard.That(lessons.Length > 0 && lessons.All(s => s.Sequence > 0 && !string.IsNullOrWhiteSpace(s.Title) && s.Title.Length <= 120) && lessons.Select(s=>(s.Sequence, BookStructure.TitleKey(s.Title))).Distinct().Count() == lessons.Length, "请填写有效讲次序号和标题；同号同标题的连续内容应合并");
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        Guard.That(await db.BookImports.Where(x => x.Id == b.Id && x.State == "review" && x.Revision == revision).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "splitting").SetProperty(x => x.Revision, revision + 1), ct) == 1, "讲次划分已变更，请刷新", 409);
        // Serialize lecture/version allocation within the class, without holding a DB transaction during file conversion.
        await db.Classes.Where(c=>c.Id==b.ClassId).ExecuteUpdateAsync(s=>s.SetProperty(c=>c.Name,c=>c.Name),ct);
        for (var i=0; i<sections.Length; i++) {
            var s = sections[i]; if (s.Kind != "lesson") continue;
            var candidates = await db.Lectures.Where(l => l.ClassId == b.ClassId && l.CourseId == b.CourseId && l.Sequence == s.Sequence).ToListAsync(ct);
            var matches = candidates.Where(l => BookStructure.TitleKey(l.Title) == BookStructure.TitleKey(s.Title)).ToList();
            Guard.That(matches.Count <= 1, $"第{s.Sequence}讲有多个同号同标题讲次，请先整理班级讲次", 409);
            var lecture = matches.SingleOrDefault(); if (lecture == null) { lecture = new Lecture { ClassId = b.ClassId, CourseId = b.CourseId, Sequence = s.Sequence, Title = s.Title.Trim() }; db.Lectures.Add(lecture); }
            var h = new Handout { RecognitionMode = b.RecognitionMode, LectureId = lecture.Id, FileName = $"第{s.Sequence}讲-{Path.GetFileNameWithoutExtension(b.FileName)}.docx", Version = (await db.Handouts.Where(x=>x.LectureId == lecture.Id).MaxAsync(x=>(int?)x.Version, ct) ?? 0) + 1 };
            h.OriginalKey = $"handouts/{h.Id}/original.docx";
            db.Handouts.Add(h); db.Work.Add(new WorkItem { Kind = "import", EntityId = h.Id, State = "waiting" }); sections[i] = s with { HandoutId = h.Id };
        }
        b.State = "splitting"; b.Revision = revision + 1; b.SectionsJson = Json.Write(sections);
        db.Work.Add(new WorkItem {Kind="book-split",EntityId=b.Id});
        db.Audits.Add(new Audit { StaffId=staffId, Action="book.split",TargetId=b.Id }); await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
        return sections;
    }

    public static async Task Split(Guid id, Data db, WordDocuments word, IObjects objects, string dir, CancellationToken ct)
    {
        if (await BookPageSplits.Split(id, db, word, objects, dir, ct)) return;
        var book=await db.BookImports.FindAsync([id],ct)??throw new ApiException(404,"合订讲义不存在");if(book.State=="split")return;
        var sections=Json.Read<BookSection[]>(book.SectionsJson);Guard.That(sections.Any(s=>s.HandoutId.HasValue),"请先确认讲次划分",409);
        book.State="splitting";book.Error=null;await db.SaveChangesAsync(ct);
        var path=await word.Materialize(book.NormalizedKey,dir,"book.docx",ct);
        var sourcePdfKey = await SourcePdfKey(id, objects, ct);
        var failures = new List<string>();
        foreach(var section in sections.Where(s=>s.HandoutId.HasValue)) {
            var hid=section.HandoutId!.Value;
            if(!await db.Work.AnyAsync(w=>w.Kind=="import"&&w.EntityId==hid&&w.State=="waiting",ct))continue;
            var h=await db.Handouts.FindAsync([hid],ct)??throw new ApiException(404,"分讲讲义不存在");
            var target=Path.Combine(dir,hid+".docx");
            try {
                BookStructure.Slice(path,target,section.Start,section.End);
                await using(var stream=File.OpenRead(target))await objects.Put(h.OriginalKey,stream,WordDocuments.DocxType,ct);
                if (sourcePdfKey.Length > 0) h.SourcePdfKey = sourcePdfKey;
                h.Error = null; await db.SaveChangesAsync(ct);
                // Make the import claimable only after its complete source file is durable. Retry skips completed slices.
                await db.Work.Where(w=>w.Kind=="import"&&w.EntityId==hid&&w.State=="waiting").ExecuteUpdateAsync(s=>s.SetProperty(w=>w.State,"queued"),ct);
            } catch (Exception e) when (!ct.IsCancellationRequested && e is not (OperationCanceledException or OutOfMemoryException)) {
                var message = e is ApiException ? e.Message : "分讲文件生成失败，请重试";
                h.Error = message; await db.SaveChangesAsync(ct); failures.Add($"第{section.Sequence}讲：{message}");
            } finally { if(File.Exists(target))File.Delete(target); }
        }
        book.State = failures.Count == 0 ? "split" : "failed";
        book.Error = failures.Count == 0 ? null : "部分讲次未完成，其他讲次可继续校对。" + string.Join("；", failures);
        await db.SaveChangesAsync(ct);
    }
    public static async Task Analyze(Guid id, Data db, WordDocuments word, IObjects objects, string dir, CancellationToken ct, bool autoSplit = false)
    {
        var book = await db.BookImports.FindAsync([id], ct) ?? throw new ApiException(404, "合订讲义不存在");
        // Old cached clients/jobs must never parse the page-reference document as lesson content.
        if (BookPageSplits.IsPageMode(book)) { if (book.State is not ("review" or "splitting" or "split")) await BookPageSplits.Prepare(id, db, word, objects, dir, ct); return; }
        if (book.State is "review" or "splitting" or "split") return;
        using var recognition = QuestionRecognition.Use(word.EffectiveRecognitionMode(book.RecognitionMode));
        book.State = "processing"; book.Error = null; await db.SaveChangesAsync(ct);
        var source = await word.Materialize(book.OriginalKey, dir, "original" + Path.GetExtension(book.FileName).ToLowerInvariant(), ct);
        var previousCallback = word.HandoutProgressCallback;
        string? recognitionNotice = null; string path;
        word.HandoutProgressCallback = async (HandoutProgress progress, CancellationToken progressCt) => {
            if (previousCallback != null) await previousCallback(progress, progressCt);
            if (progress.Stage is "fallback" or "outline-fallback") recognitionNotice = progress.Message;
            await using var json = new MemoryStream(System.Text.Encoding.UTF8.GetBytes(Json.Write(progress)));
            await objects.Put($"books/{id}/progress.json", json, "application/json", progressCt);
        };
        try { path = (book.RecognitionMode == QuestionRecognition.Auto ? await word.TryNormalizeBookOutline(source, dir, ct) : null)
                ?? await word.NormalizeHandout(source, dir, ct); }
        finally { word.HandoutProgressCallback = previousCallback; }
        if (!string.IsNullOrEmpty(word.NormalizedSourcePdf)) {
            await using var pdf = File.OpenRead(word.NormalizedSourcePdf);
            await objects.Put($"books/{id}/source.pdf", pdf, "application/pdf", ct);
        }
        BookNumbering.Normalize(path);
        var blocks = BookStructure.Read(path); Guard.That(blocks.Length is > 0 and <= 20000, "讲义为空或段落数超出范围");
        book.NormalizedKey = $"books/{id}/normalized.docx"; await using var stream = File.OpenRead(path); await objects.Put(book.NormalizedKey, stream, WordDocuments.DocxType, ct);
        var lastSequence = await db.Lectures.Where(l => l.ClassId == book.ClassId && l.CourseId == book.CourseId).MaxAsync(l => (int?)l.Sequence, ct) ?? 0;
        Guard.That(lastSequence < int.MaxValue, "讲次编号已超出范围，请先整理讲次");
        var sections = BookStructure.DetectFile(blocks, book.FileName, lastSequence + 1);
        book.BlockCount = blocks.Length;
        book.SectionsJson = Json.Write(sections);
        book.State = "review";
        var reviewPages = blocks.Count(WordDocuments.IsPdfReview);
        var reviewNotice = reviewPages == 0 ? null : $"有 {reviewPages} 处需要校对，其他内容已继续识别。请查看异常内容和原稿；正文可在拆题页使用“框选拆题”校对。";
        book.Error = string.Join(" ", new[] { recognitionNotice, reviewNotice }.Where(x => !string.IsNullOrWhiteSpace(x)));
        if (book.Error.Length == 0) book.Error = null;
        await db.SaveChangesAsync(ct);

        if (autoSplit && sections.Length > 1 && sections[0].Kind == "unassigned" &&
            blocks[..sections[0].End].All(block =>
                (book.RecognitionMode == QuestionRecognition.Auto && (string?)block.Element(WordDocuments.W + "pPr")?.Element(WordDocuments.W + "pStyle")?.Attribute(WordDocuments.W + "val") == "SiyueAiFrontMatter" && WordDocuments.PdfOrigin(block)?.Checks.Contains("ai-review") != true) ||
                (WordDocuments.PdfOrigin(block) == null && WordDocuments.Block(block).Text.Trim() is "" or "封面" or "目录" &&
                WordDocuments.ImageReferences(block.ToString()).Length == 0 &&
                !block.Descendants().Any(element => element.Name.LocalName is "oMath" or "tbl"))))
            sections[0] = sections[0] with { Kind = "exclude" };

        // Keep unknown lecture context for placement; AI question doubts remain in each draft for review.
        var lessons = sections.Where(section => section.Kind == "lesson").ToArray();
        if (!autoSplit || (reviewPages != 0 && (book.RecognitionMode != QuestionRecognition.Auto || blocks.Where(WordDocuments.IsPdfReview).Any(x => WordDocuments.PdfOrigin(x)?.Engine != "ai-layout"))) ||
            blocks.Any(x => WordDocuments.PdfOrigin(x)?.Checks.Contains("ai-lecture-review") == true) || sections.Any(section => section.Kind == "unassigned") ||
            lessons.Length == 0 || lessons.Any(section => section.Title.Length > 120) ||
            lessons.Select(section => (section.Sequence, BookStructure.TitleKey(section.Title))).Distinct().Count() != lessons.Length)
            return;
        try
        {
            await QueueSplit(book, book.Revision, sections, book.StaffId, db, ct);
        }
        catch (ApiException e) when (e.Status == 409)
        {
            db.ChangeTracker.Clear();
        }
    }
}
