using Microsoft.EntityFrameworkCore;
namespace Siyue;
public record LessonPlanInput(Guid CourseId, string Title, string Content, Guid? StaffId = null, Guid? Id = null);
public static class LessonPlanEndpoints
{
    public static void MapLessonPlans(this WebApplication app)
    {
        app.MapGet("/api/classes/{id:guid}/plans", async (Guid id, Guid courseId, Data db, Access a) => {
            await a.Class(id, courseId); var uid = a.User.Id; var admin = a.User.Role == "admin";
            return await (from p in db.LessonPlans join teacher in db.Staff on p.StaffId equals teacher.Id where p.ClassId == id && p.CourseId == courseId && (admin || p.StaffId == uid) orderby p.UpdatedAt descending select new { p.Id, p.Title, p.Content, p.StaffId, p.WordFileName, teacherName = teacher.Name, p.UpdatedAt }).ToListAsync();
        });
        app.MapPost("/api/classes/{id:guid}/plans", (Guid id, LessonPlanInput r, Data db, Access a, IObjects objects) => Save(id, r, db, a, objects));
        app.MapPost("/api/classes/{id:guid}/plans/word", async (Guid id, HttpRequest req, Data db, Access a, IObjects objects) => {
            var form = await req.ReadFormAsync();
            Guid Required(string field) { Guard.That(Guid.TryParse(form[field], out var value), "请选择有效课程"); return value; }
            Guid? Optional(string field) { if (string.IsNullOrEmpty(form[field])) return null; Guard.That(Guid.TryParse(form[field], out var value), "教案或教师标识无效"); return value; }
            var file = form.Files.GetFile("file"); Guard.That(file != null, "请选择 Word 文件");
            return await Save(id, new LessonPlanInput(Required("courseId"), form["title"].ToString(), form["content"].ToString(), Optional("staffId"), Optional("id")), db, a, objects, file, form["fileName"].ToString());
        }).DisableAntiforgery();
        app.MapGet("/api/lesson-plans/{id:guid}/word", async (Guid id, Data db, Access a, Files files) => {
            var plan = await db.LessonPlans.FindAsync(id) ?? throw new ApiException(404, "教案不存在"); await a.Class(plan.ClassId, plan.CourseId);
            Guard.That(a.User.Role == "admin" || plan.StaffId == a.User.Id, "无权查看此教师的教案", 403); Guard.That(plan.WordKey.Length > 0, "此教案尚未上传 Word 文件", 404);
            var format = Path.GetExtension(plan.WordFileName).Equals(".doc", StringComparison.OrdinalIgnoreCase) ? "doc" : "docx";
            return new { url = await files.Grant(plan.WordKey, format == "doc" ? "application/msword" : WordDocuments.DocxType, plan.WordFileName, a.User.Id, classId: plan.ClassId, courseId: plan.CourseId), format };
        });
    }
    static async Task<LessonPlan> Save(Guid classId, LessonPlanInput r, Data db, Access a, IObjects objects, IFormFile? file = null, string? uploadName = null)
    {
        await a.Class(classId, r.CourseId); var teacherId = a.User.Role == "admin" ? r.StaffId ?? a.User.Id : a.User.Id;
        Guard.That(await db.Staff.AnyAsync(x => x.Id == teacherId && x.Enabled) && await db.Assignments.AnyAsync(x => x.ClassId == classId && x.CourseId == r.CourseId && x.StaffId == teacherId), "请先为教师分配本班课程");
        var plan = r.Id.HasValue ? await db.LessonPlans.FindAsync(r.Id) ?? throw new ApiException(404, "教案不存在") : new LessonPlan { ClassId = classId, CourseId = r.CourseId, StaffId = teacherId };
        Guard.That(plan.ClassId == classId && plan.CourseId == r.CourseId && plan.StaffId == teacherId, "无权修改此教案", 403);
        var content = r.Content.Trim(); Guard.That(content.Length <= 20000, "教案正文不能超过20000字"); Guard.That(content.Length > 0 || file != null || plan.WordKey.Length > 0, "请填写教案正文或上传 Word 文件");
        plan.Title = Guard.Name(r.Title); plan.Content = content;
        if (file != null) {
            var fileName = Guard.Name(Path.GetFileName(string.IsNullOrWhiteSpace(uploadName) ? file.FileName : uploadName));
            var ext = Path.GetExtension(fileName).ToLowerInvariant(); Guard.That(file.Length is > 0 and <= 52_428_800 && ext is ".doc" or ".docx", "请选择不超过50MB的 doc 或 docx 文件");
            var key = $"lesson-plans/{plan.Id}/{Guid.NewGuid():N}{ext}";
            await using var stream = file.OpenReadStream(); await objects.Put(key, stream, ext == ".doc" ? "application/msword" : WordDocuments.DocxType);
            plan.WordKey = key; plan.WordFileName = fileName;
        }
        plan.UpdatedAt = Clock.Now; if (!r.Id.HasValue) db.LessonPlans.Add(plan);
        db.Audits.Add(new Audit { StaffId = a.User.Id, Action = file == null ? "lesson-plan.save" : "lesson-plan.word.save", TargetId = plan.Id }); await db.SaveChangesAsync(); return plan;
    }
}
