using Microsoft.EntityFrameworkCore;
using System.Xml.Linq;
namespace Siyue;
public record LectureInput(Guid? ClassId, Guid CourseId, string Title, int Sequence, string? RequestId = null);
public record EditInput(string Action, int Revision, Guid QuestionId, Guid? OtherQuestionId = null, int BlockIndex = 0, string? Label = null, string? RelationshipId = null, int Occurrence = 0);
public record PublishInput(int Revision);
public record VerifySelectionInput(int Revision, Guid[] QuestionIds);
public record AttendanceInput(Guid StudentId, Guid ClassId, bool Absent);
public record WrongInput(Guid HandoutId, Guid[] StudentIds, Guid[] QuestionIds, string Mode = "replace", Guid? ClassId = null, Dictionary<Guid,string>? Marks = null);
public record ExportInput(Guid ClassId, Guid CourseId, Guid[] StudentIds, Guid[] LectureIds, Guid[]? HandoutIds = null);
public record ExportSummary(Guid Id, Guid BatchId, Guid? StudentId, string StudentName, string State, string? Error, DateTimeOffset CreatedAt, string TemplateVersion, bool NeedsReflow, string Kind, bool CanDownloadWord);
public static class LearningEndpoints
{
    static ExportSummary Summary(ExportDocument d) => new(d.Id, d.BatchId, d.StudentId, d.StudentName, d.State, d.Error, d.CreatedAt, d.TemplateVersion, d.Kind == "booklet" && d.TemplateVersion != WordDocuments.BookletTemplateVersion, d.Kind, d.Kind == "booklet" || d.DocxKey.Length > 0);
    public record Registration(Guid StudentId, Guid LectureId);
    public static async Task<Registration[]> Registrations(Data db, Guid[] students, Guid[] lectures) {
        var saved = await db.WrongSettings.Where(x => students.Contains(x.StudentId) && lectures.Contains(x.LectureId)).Select(x => new Registration(x.StudentId, x.LectureId)).ToArrayAsync();
        var legacy = await (from w in db.WrongQuestions join q in db.Questions on w.QuestionId equals q.Id join h in db.Handouts on q.HandoutId equals h.Id where students.Contains(w.StudentId) && lectures.Contains(h.LectureId) select new Registration(w.StudentId, h.LectureId)).Distinct().ToArrayAsync();
        return saved.Concat(legacy).Distinct().ToArray();
    }
    public static async Task<Handout> UploadHandout(Guid id, HttpRequest req, Data db, Access a, IObjects objects) {
            await a.WriteLecture(id); var form = await req.ReadFormAsync(); var f = form.Files.GetFile("file"); var ext = Path.GetExtension(f?.FileName ?? "").ToLowerInvariant();
            Guard.That(f != null && f.Length is > 0 and <= 52_428_800 && ext is ".doc" or ".docx" or ".pdf", "请选择不超过 50MB 的 Word 或 PDF 讲义");
            var h = new Handout { RecognitionMode = QuestionRecognition.Validate(form["recognitionMode"].FirstOrDefault()), LectureId = id, Version = (await db.Handouts.Where(x => x.LectureId == id).MaxAsync(x => (int?)x.Version) ?? 0) + 1, FileName = Path.GetFileName(f!.FileName) };
            string hash; await using (var input = f.OpenReadStream()) hash = await HandoutReuse.Hash(input, req.HttpContext.RequestAborted);
            var reused = await HandoutReuse.TryHandout(db, objects, h, a.User.Id, hash, HandoutReuse.Force(req), req.HttpContext.RequestAborted);
            if (reused != null) { req.HttpContext.Items["HandoutReuse"] = reused; return h; }
            h.OriginalKey = $"handouts/{h.Id}/original{ext}"; await using var stream = f.OpenReadStream(); await objects.Put(h.OriginalKey, stream, "application/octet-stream");
            await HandoutReuse.RememberUpload(objects, "handout", h.Id, h.OriginalKey, h.FileName, hash, req.HttpContext.RequestAborted);
            db.Handouts.Add(h); db.Work.Add(new WorkItem { Kind = "import", EntityId = h.Id }); db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "handout.upload", TargetId = h.Id }); await db.SaveChangesAsync(); return h;
    }
    public static void MapLearning(this WebApplication app)
    {
        app.MapGet("/api/classes/{id:guid}", async (Guid id, Guid? courseId, Data db, Access a, IObjects objects, CancellationToken ct) => {
            await a.Class(id, courseId);
            var schoolClass = await db.Classes.FindAsync(id);
            var availableCourses = await db.Courses.Where(c => (a.User.Role == "admin" && !schoolClass!.OwnerStaffId.HasValue || db.Assignments.Any(ass => ass.ClassId == id && (a.User.Role == "admin" || ass.StaffId == a.User.Id) && ass.CourseId == c.Id)) && (!courseId.HasValue || c.Id == courseId.Value)).OrderBy(c => c.Name).ToListAsync();
            var courseIds = availableCourses.Select(c => c.Id).ToArray();
            var lectures = await db.Lectures.Where(x => (x.ClassId == id || x.ClassId == null) && courseIds.Contains(x.CourseId)).OrderBy(x => x.Sequence).ToListAsync(); var ids = lectures.Select(x => x.Id).ToArray();
            var teachers = await (from ass in db.Assignments join staff in db.Staff on ass.StaffId equals staff.Id where ass.ClassId == id && courseIds.Contains(ass.CourseId) && staff.Enabled && (a.User.Role == "admin" || staff.Id == a.User.Id) select new { staff.Id, staff.Name }).Distinct().ToListAsync();
            var registrations = await Registrations(db, await db.Students.Where(s => s.ClassId == id).Select(s => s.Id).ToArrayAsync(), ids);
            var materials = await db.Documents.Where(d => d.Kind == "file" && d.ClassId == id && d.CourseId.HasValue && courseIds.Contains(d.CourseId.Value)).OrderByDescending(d => d.CreatedAt).Select(d => new { d.Id, d.LectureId, d.SourceFileName, d.State, d.MaterialKind, d.BatchId }).ToListAsync();
            var classHandouts = await db.Handouts.Where(x => ids.Contains(x.LectureId) && (a.User.Role == "admin" || x.State == "published" || db.Lectures.Any(l => l.Id == x.LectureId && l.ClassId == id))).Select(x => new { x.Id, x.LectureId, x.Version, x.State, x.FileName, x.Error }).ToListAsync(ct);
            var estimates = await QueueEstimates.Batch(db, objects, classHandouts.Select(x => x.Id), ct);
            return new { teachers, registrations, materials, campusName = await (from cl in db.Classes join campus in db.Campuses on cl.CampusId equals campus.Id where cl.Id == id select campus.Name).FirstOrDefaultAsync(), schoolClass = await db.Classes.FindAsync(id), courses = availableCourses, course = availableCourses.FirstOrDefault(), students = await db.Students.Where(x => x.ClassId == id).OrderBy(x => x.Code).ToListAsync(), lectures,
                handouts = classHandouts.Select(x => new { x.Id, x.LectureId, x.Version, x.State, x.FileName, x.Error, queue = estimates.GetValueOrDefault(x.Id) }) };
        });
        app.MapGet("/api/courses/{id:guid}/lectures", async (Guid id, Data db, Access a) => {
            a.Admin(); await a.Course(id); var lectures = await db.Lectures.Where(x => x.CourseId == id && x.ClassId == null).OrderBy(x => x.Sequence).ToListAsync(); var ids = lectures.Select(x => x.Id).ToArray();
            return new { schoolClass = new { name = "课程共享讲义" }, course = await db.Courses.FindAsync(id), students = Array.Empty<Student>(), lectures, handouts = await db.Handouts.Where(x => ids.Contains(x.LectureId)).Select(x => new { x.Id, x.LectureId, x.Version, x.State, x.FileName }).ToListAsync() };
        });
        app.MapPut("/api/lectures/{id:guid}/attendance", async (Guid id, AttendanceInput r, Data db, Access a) => {
            var lecture = await a.Lecture(id); await a.LectureClass(lecture, r.ClassId);
            Guard.That(await db.Students.AnyAsync(x => x.Id == r.StudentId && x.ClassId == r.ClassId), "学生不属于当前班级", 403);
            var status = await db.Attendances.SingleOrDefaultAsync(x => x.StudentId == r.StudentId && x.LectureId == id);
            if (status == null) { status = new LectureAttendance { StudentId = r.StudentId, LectureId = id }; db.Attendances.Add(status); }
            status.Absent = r.Absent; status.RecordedBy = a.User.Id; status.UpdatedAt = Clock.Now;
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "lecture.attendance", TargetId = r.StudentId }); await db.SaveChangesAsync(); return Results.NoContent();
        });
        app.MapLessonPlans();
        app.MapPost("/api/lectures", async (LectureInput r, Data db, Access a) => {
            if (!r.ClassId.HasValue) a.Admin(); if (r.ClassId.HasValue) await a.Class(r.ClassId.Value, r.CourseId); else await a.Course(r.CourseId); Guard.That(await db.Courses.AnyAsync(x => x.Id == r.CourseId) && r.Sequence > 0, "课程或讲次无效");
            var title = Guard.Name(r.Title); var id = Guid.NewGuid();
            if (!string.IsNullOrWhiteSpace(r.RequestId)) {
                Guard.That(r.RequestId.Length <= 120, "请求标识无效"); id = new Guid(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"lecture:{a.User.Id}:{r.RequestId}"))[..16]);
                var existing = await db.Lectures.FindAsync(id);
                if (existing != null) { Guard.That(existing.ClassId == r.ClassId && existing.CourseId == r.CourseId && existing.Sequence == r.Sequence && existing.Title == title, "本次讲次已添加，请返回查看", 409); return existing; }
            }
            var lecture = new Lecture { Id = id, ClassId = r.ClassId, CourseId = r.CourseId, Title = title, Sequence = r.Sequence }; db.Lectures.Add(lecture); db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "lecture.create", TargetId = lecture.Id }); await db.SaveChangesAsync(); return lecture;
        });
        app.MapPost("/api/lectures/{id:guid}/share", async (Guid id, Data db, Access a) => {
            a.Admin(); var lecture = await a.Lecture(id); lecture.ClassId = null;
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "lecture.share", TargetId = id }); await db.SaveChangesAsync(); return lecture;
        });
        app.MapPost("/api/lectures/{id:guid}/handouts", async (Guid id, HttpRequest req, Data db, Access a, IObjects objects) => {
            var h = await UploadHandout(id, req, db, a, objects);
            var response = System.Text.Json.JsonSerializer.SerializeToNode(h, Json.Options)!;
            response["reuse"] = System.Text.Json.JsonSerializer.SerializeToNode(req.HttpContext.Items["HandoutReuse"] as HandoutReuseSummary, Json.Options);
            return Results.Accepted(value: response);
        }).DisableAntiforgery();

        app.MapGet("/api/handouts/{id:guid}", async (Guid id, Guid? classId, Data db, Access a, Files files, IObjects objects, CancellationToken ct) => {
            var h = await a.Handout(id); var readableLecture = await a.Lecture(h.LectureId); Guard.That(a.User.Role == "admin" || h.State == "published" || readableLecture.ClassId.HasValue, "讲义尚未发布", 403); var l = await a.Lecture(h.LectureId); var scopeClass = await a.LectureClass(l, classId);
            var questions = await db.Questions.Where(x => x.HandoutId == id).OrderBy(x => x.Order).ToListAsync(); var cards = new List<object>();
            foreach (var q in questions) {
                var issues = new List<Issue>(); var blocks = new List<object>(); var previews = new List<string>(); var formulaReviews = new List<object>();
                try {
                    issues.AddRange(Json.Read<Issue[]>(q.IssuesJson));
                    foreach (var (block, index) in Json.Read<ContentBlock[]>(q.BlocksJson).Select((b,i)=>(b,i))) {
                        string[] images;
                        try { images = WordDocuments.ImageReferences(block.Xml); var evidence = FormulaRecognition.Evidence(XElement.Parse(block.Xml)); if(evidence!=null)formulaReviews.Add(evidence); }
                        catch (System.Xml.XmlException) { images = []; issues.Add(new Issue("content-unreadable", "本题有异常内容，原稿和其他题目已保留，请修正原稿后重新上传", true)); }
                        blocks.Add(new { index, block.Text, block.Kind, images, canFrame = images.Length==1 && WordDocuments.IsPdfRaster(XElement.Parse(block.Xml)) });
                    }
                    foreach (var key in Json.Read<string[]>(q.PreviewsJson)) previews.Add(await files.Grant(key, "image/png", "题目预览.png", a.User.Id, classId: scopeClass, courseId: l.CourseId));
                } catch (System.Text.Json.JsonException) { issues.Add(new Issue("content-unreadable", "本题数据异常，请修正原稿后重新上传；其他题目可继续查看", true)); }
                cards.Add(new { q.Id, q.Label, q.Family, q.Order, confirmed = q.Confirmed && !issues.Any(i=>i.Blocking), q.Revision, q.HalfPage, blocks, formulaReviews, issues = issues.Distinct(), previews });
            }
            var students = await db.Students.Where(x => x.ClassId == scopeClass).OrderBy(x => x.Code).ToListAsync(); var sids = students.Select(x => x.Id).ToArray(); var qids = questions.Select(x => x.Id).ToArray();
            var wrong = await db.WrongQuestions.Where(x => sids.Contains(x.StudentId) && qids.Contains(x.QuestionId)).Select(x => new { x.StudentId, x.QuestionId }).ToListAsync();
            var absentStudentIds = await db.Attendances.Where(x => x.LectureId == l.Id && sids.Contains(x.StudentId) && x.Absent).Select(x => x.StudentId).ToArrayAsync();
            var estimates = await QueueEstimates.Batch(db, objects, [h.Id], ct);
            return new { absentStudentIds, queue = estimates.GetValueOrDefault(h.Id), handout = new { h.Id, h.LectureId, h.Version, h.RecognitionMode, h.State, h.Error, h.Revision, h.FileName }, lecture = l, classId = scopeClass, questions = cards, students, wrong, completedStudentIds = (await Registrations(db, sids, [l.Id])).Select(x => x.StudentId).Distinct().ToArray(), marks = (await db.WrongSettings.Where(x => sids.Contains(x.StudentId) && x.HandoutId == id).ToListAsync()).Select(x => new { x.StudentId, states = Json.Read<Dictionary<Guid,string>>(x.MarksJson) }),
                sourceUrl = h.SourcePdfKey.Length > 0 ? await files.Grant(h.SourcePdfKey, "application/pdf", "原讲义.pdf", a.User.Id, classId: scopeClass, courseId: l.CourseId) : null };
        });
        app.MapPost("/api/handouts/{id:guid}/edit", async (Guid id, EditInput r, Data db, Access a) => {
            var h = await a.Handout(id); await a.WriteLecture(h.LectureId); Guard.That(h.State == "draft", "仅可校对已处理完的未发布讲义", 409);
            await using var tx = await db.Database.BeginTransactionAsync();
            Guard.That(await db.Handouts.Where(x => x.Id == id && x.State == "draft" && x.Revision == r.Revision).ExecuteUpdateAsync(s => s.SetProperty(x => x.Revision, r.Revision + 1)) == 1, "讲义已被修改，请刷新", 409);
            var list = await db.Questions.Where(x => x.HandoutId == id).OrderBy(x => x.Order).ToListAsync(); var q = list.SingleOrDefault(x => x.Id == r.QuestionId) ?? throw new ApiException(404, "题目不存在");
            using var recognition = QuestionRecognition.Use(h.RecognitionMode);
            var changed = new List<Question>(); var blocks = Json.Read<List<ContentBlock>>(q.BlocksJson);
            switch (r.Action) {
                case "confirm": Guard.That(WordDocuments.ReadableBlocks(q) && !Json.Read<Issue[]>(q.IssuesJson).Any(x => x.Blocking), "请先修复原稿中的不支持内容"); Guard.That(Json.Read<string[]>(q.PreviewsJson).Length > 0, "题目预览尚未生成，请稍后确认", 409); q.Confirmed = true; break;
                case "verify-integrity": {
                    Guard.That(WordDocuments.ReadableBlocks(q) && Json.Read<string[]>(q.PreviewsJson).Length>0,"请先生成完整预览",409);
                    var original=Json.Read<Issue[]>(q.IssuesJson);
                    Guard.That(original.Any(i=>WordDocuments.PdfReviewableIssues.Contains(i.Code)),"本题没有待核对提示",409);
                    Guard.That(!original.Any(i=>i.Blocking&&!WordDocuments.PdfReviewableIssues.Contains(i.Code)),"请先框选拆题或修复无法读取的内容",409);
                    q.IssuesJson=Json.Write(original.Where(i=>!WordDocuments.PdfReviewableIssues.Contains(i.Code)));
                    q.Confirmed=true;break;
                }
                case "remove-question": {
                    await db.QuestionAssets.Where(x => x.QuestionId == q.Id).ExecuteDeleteAsync();
                    db.Questions.Remove(q); list.Remove(q); break;
                }
                case "label": q.Label = Guard.Name(r.Label ?? ""); changed.Add(q); break;
                case "merge": {
                    var other = list.SingleOrDefault(x => x.Id == r.OtherQuestionId) ?? throw new ApiException(400, "请选择相邻题目"); Guard.That(Math.Abs(list.IndexOf(q) - list.IndexOf(other)) == 1, "只能合并相邻题目");
                    var first = q.Order < other.Order ? q : other; var second = q.Order < other.Order ? other : q;
                    first.BlocksJson = Json.Write(Json.Read<List<ContentBlock>>(first.BlocksJson).Concat(Json.Read<List<ContentBlock>>(second.BlocksJson))); first.IssuesJson = Json.Write(Json.Read<Issue[]>(first.IssuesJson).Concat(Json.Read<Issue[]>(second.IssuesJson)).Distinct());
                    await db.QuestionAssets.Where(x => x.QuestionId == second.Id).ExecuteDeleteAsync(); db.Questions.Remove(second); list.Remove(second); changed.Add(first); break;
                }
                case "split": {
                    Guard.That(r.BlockIndex > 0 && r.BlockIndex < blocks.Count, "请选择题目中间的段落作为下一题起点");
                    var next = new Question { HandoutId = id, Label = Guard.Name(r.Label ?? "待定题号"), BlocksJson = Json.Write(blocks.Skip(r.BlockIndex)), IssuesJson = q.IssuesJson };
                    q.BlocksJson = Json.Write(blocks.Take(r.BlockIndex)); list.Insert(list.IndexOf(q) + 1, next); db.Questions.Add(next); changed.AddRange([q, next]); break;
                }
                case "remove-block": {
                    Guard.That(r.BlockIndex >= 0 && r.BlockIndex < blocks.Count, "请选择需要排除的干扰段落");
                    Guard.That(!WordDocuments.IsPdfRaster(XElement.Parse(blocks[r.BlockIndex].Xml)), "PDF 内容块可能包含整页题目，请框选要保留的正文来排除页眉页脚，不能整块排除");
                    if(blocks.Count==1) {
                        Guard.That(list.Count>1,"不能排除本讲唯一题目，请替换原稿");
                        await db.QuestionAssets.Where(x=>x.QuestionId==q.Id).ExecuteDeleteAsync();db.Questions.Remove(q);list.Remove(q);
                    } else { blocks.RemoveAt(r.BlockIndex);q.BlocksJson=Json.Write(blocks);changed.Add(q); }
                    break;
                }
                case "move-image": {
                    var other = list.SingleOrDefault(x => x.Id == r.OtherQuestionId && x.Id != q.Id) ?? throw new ApiException(400, "请选择另一道题"); Guard.That(r.BlockIndex >= 0 && r.BlockIndex < blocks.Count && r.RelationshipId != null, "图片位置无效");
                    var xml = XElement.Parse(blocks[r.BlockIndex].Xml); Guard.That(!WordDocuments.IsPdfRaster(xml), "PDF 题目按原版面整块保留，请合并相邻题或修改源文件后重新上传"); var refs = WordDocuments.ImageNodes(xml).Where(x => (x.Attribute(WordDocuments.R + "embed")?.Value ?? x.Attribute(WordDocuments.R + "id")?.Value) == r.RelationshipId).ToArray(); Guard.That(r.Occurrence >= 0 && r.Occurrence < refs.Length, "图片引用不存在");
                    var drawing = refs[r.Occurrence].Ancestors().FirstOrDefault(x => x.Name == WordDocuments.W + "drawing" || x.Name == WordDocuments.W + "pict") ?? throw new ApiException(400, "此图片需通过修改原稿调整");
                    var copy = new XElement(drawing); drawing.Remove(); blocks[r.BlockIndex] = WordDocuments.Block(xml); q.BlocksJson = Json.Write(blocks);
                    var target = Json.Read<List<ContentBlock>>(other.BlocksJson); target.Add(WordDocuments.Block(new XElement(WordDocuments.W + "p", new XElement(WordDocuments.W + "r", copy)))); other.BlocksJson = Json.Write(target); changed.AddRange([q, other]); break;
                }
                default: throw new ApiException(400, "校对操作无效");
            }
            for (var i = 0; i < list.Count; i++) list[i].Order = i + 1;
            foreach (var item in changed.DistinctBy(x => x.Id)) { item.Confirmed = false; item.Revision++; item.PreviewsJson = "[]"; await WordDocuments.SyncAssets(db, item); }
            if (changed.Count > 0) db.Work.Add(new WorkItem { Kind = "previews", EntityId = id });
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "handout." + r.Action, TargetId = id }); await db.SaveChangesAsync(); await tx.CommitAsync(); return new { revision = r.Revision + 1 };
        });
        app.MapPost("/api/handouts/{id:guid}/verify-selected", async (Guid id, VerifySelectionInput r, Data db, Access a) => {
            var h = await a.Handout(id); await a.WriteLecture(h.LectureId);
            Guard.That(h.State == "draft", "仅可校对未发布讲义", 409);
            Guard.That(r.QuestionIds is { Length: > 0 and <= 1000 } && r.QuestionIds.Distinct().Count() == r.QuestionIds.Length, "请选择需要确认的题目");
            await using var tx = await db.Database.BeginTransactionAsync();
            Guard.That(await db.Handouts.Where(x => x.Id == id && x.State == "draft" && x.Revision == r.Revision)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.Revision, r.Revision + 1)) == 1, "讲义已被修改，请刷新", 409);
            var selected = await db.Questions.Where(x => x.HandoutId == id && r.QuestionIds.Contains(x.Id)).ToListAsync();
            Guard.That(selected.Count == r.QuestionIds.Length, "所选题目已变化，请刷新", 409);
            foreach (var q in selected) {
                var issues = Json.Read<Issue[]>(q.IssuesJson);
                Guard.That(WordDocuments.ReadableBlocks(q) && Json.Read<string[]>(q.PreviewsJson).Length > 0, "所选题目尚未生成完整预览", 409);
                Guard.That(!issues.Any(i => i.Blocking && !WordDocuments.PdfReviewableIssues.Contains(i.Code)), "所选题目仍需框选拆题或修复内容", 409);
                q.IssuesJson = Json.Write(issues.Where(i => !WordDocuments.PdfReviewableIssues.Contains(i.Code)));
                q.Confirmed = true;
            }
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "handout.verify-selected", TargetId = id });
            await db.SaveChangesAsync(); await tx.CommitAsync();
            return new { revision = r.Revision + 1, confirmed = selected.Count };
        });
        app.MapPost("/api/handouts/{id:guid}/confirm-all", async (Guid id, PublishInput r, Data db, Access a) => {
            var h = await a.Handout(id); await a.WriteLecture(h.LectureId);
            Guard.That(h.State == "draft", "仅可确认未发布讲义", 409);
            await using var tx = await db.Database.BeginTransactionAsync();
            Guard.That(await db.Handouts.Where(x => x.Id == id && x.State == "draft" && x.Revision == r.Revision)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.Revision, r.Revision + 1)) == 1, "讲义已被修改，请刷新", 409);
            var questions = await db.Questions.Where(x => x.HandoutId == id).ToListAsync();
            var eligible = questions.Where(q => !q.Confirmed && Json.Read<string[]>(q.PreviewsJson).Length > 0
                && WordDocuments.ReadableBlocks(q) && !Json.Read<Issue[]>(q.IssuesJson).Any(i => i.Blocking)).ToArray();
            foreach (var q in eligible) q.Confirmed = true;
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "handout.confirm-all", TargetId = id });
            await db.SaveChangesAsync(); await tx.CommitAsync();
            return new { revision = r.Revision + 1, confirmed = eligible.Length, remaining = questions.Count(q => !q.Confirmed) };
        });
        app.MapPost("/api/handouts/{id:guid}/publish", async (Guid id, PublishInput r, Data db, Access a, IObjects objects, IConfiguration config, CancellationToken ct) => {
            var publishing = await a.Handout(id); await a.WriteLecture(publishing.LectureId); await using var tx = await db.Database.BeginTransactionAsync(); var qs = await db.Questions.Where(x => x.HandoutId == id).ToListAsync();
            Guard.That(qs.Count > 0 && qs.All(x => x.Confirmed && WordDocuments.ReadableBlocks(x) && x.PreviewsJson != "[]" && !Json.Read<Issue[]>(x.IssuesJson).Any(i => i.Blocking)), "请确认全部题目并处理异常");
            Guard.That(await db.Handouts.Where(x => x.Id == id && x.State == "draft" && x.Revision == r.Revision).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "published").SetProperty(x => x.Revision, r.Revision + 1)) == 1, "讲义已变更或已发布，请刷新", 409);
            await ClassSessions.EnsurePublished(id, a.User.Id, db);
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "handout.publish", TargetId = id }); await db.SaveChangesAsync(); await tx.CommitAsync();
            await HandoutReuse.Completed(db, objects, id, config, ct); return Results.NoContent();
        });
        app.MapPut("/api/wrong-questions", async (WrongInput r, Data db, Access a) => {
            var h = await a.Handout(r.HandoutId, true); var l = await a.Lecture(h.LectureId); var scopeClass = await a.LectureClass(l, r.ClassId); Guard.That(scopeClass.HasValue, "请选择具体班级"); Guard.That(r.StudentIds.Length is > 0 and <= 100 && r.StudentIds.Distinct().Count() == r.StudentIds.Length, "请选择不同学生");
            Guard.That(await db.Students.CountAsync(x => r.StudentIds.Contains(x.Id) && x.ClassId == scopeClass) == r.StudentIds.Length, "存在其他班级的学生", 403);
            var ids = await db.Questions.Where(x => x.HandoutId == h.Id).Select(x => x.Id).ToArrayAsync(); Guard.That(r.QuestionIds.Distinct().All(ids.Contains), "题目不属于当前讲义", 403);
            Guard.That(r.Mode is "add" or "replace", "记录模式无效");
            if (r.Marks != null) {
                Guard.That(r.Mode == "replace" && r.StudentIds.Length == 1 && r.Marks.Keys.All(ids.Contains) && r.Marks.Values.All(x => x is "correct" or "wrong" or "unfinished"), "批改状态或学生范围无效");
                Guard.That(r.Marks.Where(x => x.Value == "wrong").Select(x => x.Key).ToHashSet().SetEquals(r.QuestionIds), "错题与批改状态不一致");
            }
            await using var tx = await db.Database.BeginTransactionAsync();
            await StudentHistory.Record(db, r, l, scopeClass.Value, a.User.Id);
            if (r.Mode == "replace") await db.WrongQuestions.Where(x => r.StudentIds.Contains(x.StudentId) && ids.Contains(x.QuestionId)).ExecuteDeleteAsync();
            var existing = await db.WrongQuestions.Where(x => r.StudentIds.Contains(x.StudentId) && ids.Contains(x.QuestionId)).ToListAsync();
            foreach (var student in r.StudentIds) foreach (var q in r.QuestionIds.Distinct()) if (!existing.Any(x => x.StudentId == student && x.QuestionId == q)) db.WrongQuestions.Add(new WrongQuestion { StudentId = student, QuestionId = q, RecordedBy = a.User.Id });
            foreach (var student in r.StudentIds) {
                var setting = await db.WrongSettings.SingleOrDefaultAsync(x => x.StudentId == student && x.LectureId == l.Id);
                if (setting == null) { setting = new WrongSetting { StudentId = student, LectureId = l.Id }; db.WrongSettings.Add(setting); }
                var marks = r.Mode == "add" && setting.HandoutId == h.Id ? Json.Read<Dictionary<Guid,string>>(setting.MarksJson) : new Dictionary<Guid,string>();
                if (r.Marks != null) marks = r.Marks;
                else foreach (var q in r.QuestionIds) marks[q] = "wrong";
                setting.MarksJson = Json.Write(marks);
                setting.HandoutId = h.Id; setting.RecordedBy = a.User.Id; setting.RecordedAt = Clock.Now;
            }
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "wrong-questions.save", TargetId = h.Id }); await db.SaveChangesAsync(); await tx.CommitAsync(); return Results.NoContent();
        });
        app.MapPost("/api/exports", async (ExportInput r, Data db, Access a) => {
            await a.Class(r.ClassId, r.CourseId); Guard.That(r.StudentIds.Length is > 0 and <= 100 && r.StudentIds.Distinct().Count() == r.StudentIds.Length, "请选择 1–100 名不同学生");
            var students = await db.Students.Where(x => r.StudentIds.Contains(x.Id) && x.ClassId == r.ClassId).ToListAsync(); Guard.That(students.Count == r.StudentIds.Length, "存在其他班级的学生", 403);
            if (r.LectureIds.Length > 0) Guard.That(await db.Lectures.CountAsync(x => r.LectureIds.Contains(x.Id) && (x.ClassId == r.ClassId || x.ClassId == null) && x.CourseId == r.CourseId) == r.LectureIds.Distinct().Count(), "讲次不属于当前班级课程", 403);
            if (r.HandoutIds != null) {
                Guard.That(r.HandoutIds.Length is > 0 and <= 100 && r.HandoutIds.Distinct().Count() == r.HandoutIds.Length, "讲义范围无效");
                var count = await (from h in db.Handouts join l in db.Lectures on h.LectureId equals l.Id where r.HandoutIds.Contains(h.Id) && (l.ClassId == r.ClassId || l.ClassId == null) && l.CourseId == r.CourseId select h.Id).CountAsync();
                Guard.That(count == r.HandoutIds.Length, "讲义不属于当前班级课程", 403);
            }
            var batchId = Guid.NewGuid(); var docs = new List<ExportDocument>(); var skipped = new List<string>();
            foreach (var s in students) {
                var items = await (from w in db.WrongQuestions join q in db.Questions on w.QuestionId equals q.Id join h in db.Handouts on q.HandoutId equals h.Id join l in db.Lectures on h.LectureId equals l.Id
                    where w.StudentId == s.Id && h.State == "published" && (r.HandoutIds == null || r.HandoutIds.Contains(h.Id)) && (l.ClassId == r.ClassId || l.ClassId == null) && l.CourseId == r.CourseId && (r.LectureIds.Length == 0 || r.LectureIds.Contains(l.Id)) orderby l.Sequence, h.Version, q.Order select new { q, l, h.Version }).ToListAsync();
                if (items.Count == 0) { skipped.Add(s.Name); continue; }
                var doc = new ExportDocument { BatchId = batchId, StaffId = a.User.Id, StudentId = s.Id, StudentName = s.Name, ClassId = r.ClassId, CourseId = r.CourseId,
                    SnapshotJson = Json.Write(items.Select(x => new QuestionSnapshot(x.q.Id, x.q.HandoutId, x.q.Label, $"第{x.l.Sequence}讲 · {x.l.Title} · v{x.Version}", x.q.BlocksJson, x.q.HalfPage))) };
                db.Documents.Add(doc); db.Work.Add(new WorkItem { Kind = "export", EntityId = doc.Id }); docs.Add(doc);
            }
            Guard.That(docs.Count > 0, "所选学生在这些讲次中尚未登记错题"); db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "export.create", TargetId = batchId }); await db.SaveChangesAsync(); return Results.Accepted(value: new { batchId, documents = docs.Select(x => new { x.Id, x.StudentName, x.State }), skipped });
        });
        app.MapGet("/api/exports", async (Guid? batchId, Guid? documentId, Data db, Access a) => {
            var u = a.User; var scope = db.Assignments.Where(x => x.StaffId == u.Id);
            var documents = await db.Documents.Where(x => (!batchId.HasValue || x.BatchId == batchId) && (!documentId.HasValue || x.Id == documentId) && (u.Role == "admin" || x.Kind == "file" && !x.ClassId.HasValue && x.StaffId == u.Id || scope.Any(s => s.ClassId == x.ClassId && s.CourseId == x.CourseId))).OrderByDescending(x => x.CreatedAt).Take(200).ToListAsync();
            return documents.GroupBy(x => (x.BatchId, x.StudentId)).Select(g => Summary(g.First())).ToArray();
        });
        app.MapPost("/api/exports/{id:guid}/reflow", async (Guid id, Data db, Access a) => {
            var source = await a.Document(id);
            if (source.Kind != "booklet" || source.TemplateVersion == WordDocuments.BookletTemplateVersion) return Summary(source);
            // A new immutable document preserves earlier downloads and already queued print jobs.
            var nextId = new Guid(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"booklet-reflow:{WordDocuments.BookletTemplateVersion}:{id}"))[..16]);
            var next = await db.Documents.FindAsync(nextId);
            if (next == null) {
                next = new ExportDocument { Id = nextId, BatchId = source.BatchId, StaffId = a.User.Id, StudentId = source.StudentId, StudentName = source.StudentName, ClassId = source.ClassId, CourseId = source.CourseId, SnapshotJson = source.SnapshotJson, Layout = source.Layout, ClassSessionId = source.ClassSessionId, MaterialKind = source.MaterialKind };
                db.Documents.Add(next); db.Work.Add(new WorkItem { Kind = "export", EntityId = next.Id });
                try { await db.SaveChangesAsync(); }
                catch (DbUpdateException) { db.ChangeTracker.Clear(); next = await db.Documents.FindAsync(nextId); if (next == null) throw; }
            }
            if (next.State == "failed") {
                next.State = "queued"; next.Error = null;
                var failed = await db.Work.Where(x => x.EntityId == nextId && x.Kind == "export" && x.State == "failed").ToListAsync();
                if (failed.Count == 0) db.Work.Add(new WorkItem { Kind = "export", EntityId = nextId });
                else foreach (var task in failed) { task.State = "queued"; task.Error = null; }
                await db.SaveChangesAsync();
            }
            return Summary(next);
        });
        app.MapGet("/api/exports/{id:guid}/download", async (Guid id, string format, Access a, Files files, Data db) => {
            var d = await a.Document(id); Guard.That(d.State == "ready", "文档尚未生成完成", 409); Guard.That(format is "pdf" or "docx", "下载格式无效"); Guard.That(format != "docx" || d.DocxKey.Length > 0, "此文件没有 Word 版本", 404);
            var fileName = await ExportFileNames.For(d, format, db);
            return new { fileName, url = await files.Grant(format == "pdf" ? d.PdfKey : d.DocxKey, format == "pdf" ? "application/pdf" : WordDocuments.DocxType, fileName, a.User.Id, classId: d.ClassId, courseId: d.CourseId) };
        });
    }
}
