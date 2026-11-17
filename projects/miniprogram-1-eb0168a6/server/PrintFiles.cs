using Microsoft.EntityFrameworkCore;
namespace Siyue;
public static class PrintFiles
{
    public static void MapPrintFiles(this WebApplication app)
    {
        app.MapPost("/api/print-files", async (HttpRequest req, Data db, Access a, IObjects objects) => { var d = await Save(req, db, a, objects); return Results.Accepted(value: new { d.Id, d.BatchId, d.State }); }).DisableAntiforgery();
    }
    public static async Task<ExportDocument> Save(HttpRequest req, Data db, Access a, IObjects objects) {
            var form = await req.ReadFormAsync();
            Guid? Optional(string key) { if (string.IsNullOrEmpty(form[key])) return null; Guard.That(Guid.TryParse(form[key], out var id), "班级、课程或讲次无效"); return id; }
            var classId = Optional("classId"); var courseId = Optional("courseId"); var lectureId = Optional("lectureId");
            var materialKind = form["materialKind"].ToString();
            if (classId.HasValue) {
                Guard.That(courseId.HasValue && lectureId.HasValue && materialKind is "homework" or "handout", "请选择对应课程讲次和资料类型");
                await a.Class(classId.Value, courseId); var lecture = await a.Lecture(lectureId.Value);
                Guard.That(lecture.CourseId == courseId && (!lecture.ClassId.HasValue || lecture.ClassId == classId), "资料讲次不属于本班课程", 403);
            } else { Guard.That(!courseId.HasValue && !lectureId.HasValue && materialKind.Length == 0, "请选择完整的班级课程讲次"); }
            var file = form.Files.GetFile("file"); var name = Path.GetFileName(form["fileName"].ToString());
            if (string.IsNullOrWhiteSpace(name)) name = Path.GetFileName(file?.FileName ?? "");
            name = Guard.Name(name); var ext = Path.GetExtension(name).ToLowerInvariant();
            Guard.That(file != null && file.Length is > 0 and <= 52_428_800 && ext is ".pdf" or ".doc" or ".docx" or ".png" or ".jpg" or ".jpeg", "请选择不超过50MB的 PDF、Word、PNG 或 JPG 文件");
            var d = new ExportDocument { Kind = "file", BatchId = Guid.NewGuid(), StaffId = a.User.Id, ClassId = classId, CourseId = courseId, LectureId = lectureId, MaterialKind = materialKind, StudentName = name, SourceFileName = name };
            d.SourceKey = $"print-files/{d.Id}/original{ext}";
            await using var stream = file!.OpenReadStream(); await objects.Put(d.SourceKey, stream, "application/octet-stream");
            db.Documents.Add(d); db.Work.Add(new WorkItem { Kind = "file", EntityId = d.Id });
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = classId.HasValue ? "material.upload." + materialKind : "file.upload", TargetId = d.Id });
            await db.SaveChangesAsync(); return d;
    }
}
