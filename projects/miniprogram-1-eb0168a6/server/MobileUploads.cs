using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Primitives;
namespace Siyue;
public record MobileUploadInput(string Mode = "print", Guid? ClassId = null, Guid? CourseId = null, Guid? LectureId = null, string MaterialKind = "", string? RecognitionMode = null);
public static class MobileUploads
{
    public static void MapMobileUploads(this WebApplication app)
    {
        app.MapPost("/api/mobile-uploads", async (MobileUploadInput input, HttpContext context, Access a, Data db, IConfiguration config) => {
            Guard.That(input.Mode is "print" or "handout" or "roster" or "book", "上传入口无效");
            if (input.Mode == "book") { Guard.That(input.ClassId.HasValue && input.CourseId.HasValue, "请选择班级和课程"); await a.Class(input.ClassId!.Value, input.CourseId); }
            else if (input.Mode == "handout") { Guard.That(input.LectureId.HasValue, "请选择讲次"); await a.WriteLecture(input.LectureId.Value); }
            else if (input.Mode == "roster") { Guard.That(input.ClassId.HasValue, "请选择班级"); await a.Class(input.ClassId.Value); }
            else if (input.ClassId.HasValue) {
                Guard.That(input.CourseId.HasValue && input.LectureId.HasValue && input.MaterialKind is "homework" or "handout", "请选择完整的班级课程讲次");
                await a.Class(input.ClassId.Value, input.CourseId); var lecture = await a.Lecture(input.LectureId.Value);
                Guard.That(lecture.CourseId == input.CourseId && (!lecture.ClassId.HasValue || lecture.ClassId == input.ClassId), "资料讲次不属于本班课程", 403);
            } else Guard.That(!input.CourseId.HasValue && !input.LectureId.HasValue && input.MaterialKind.Length == 0, "请选择完整的班级课程讲次");
            var materialKind = input.Mode is "book" or "handout" ? QuestionRecognition.Validate(input.RecognitionMode) : input.MaterialKind;
            var token = Secrets.New(); var sessionHash = Secrets.Hash(context.Request.Headers.Authorization.ToString()[7..]);
            var t = new MobileUpload { StaffId = a.User.Id, SessionHash = sessionHash, TokenHash = Secrets.Hash(token), Mode = input.Mode, ClassId = input.ClassId, CourseId = input.CourseId, LectureId = input.LectureId, MaterialKind = materialKind, ExpiresAt = Clock.Now.AddMinutes(10) };
            db.MobileUploads.Add(t); await db.SaveChangesAsync();
            return new { t.Id, url = (config["PublicUrl"] ?? "http://127.0.0.1:5080").TrimEnd('/') + "/mobile-upload/" + token };
        });
        app.MapGet("/mobile-upload/{token}", async (string token, Data db, HttpContext context) => {
            var ticket = await Ticket(token, db, context);
            context.Response.Headers["Referrer-Policy"] = "no-referrer";
            context.Response.Headers["X-Content-Type-Options"] = "nosniff";
            context.Response.Headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self' 'unsafe-inline' https://res.wx.qq.com; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'";
            await using var stream = typeof(MobileUploads).Assembly.GetManifestResourceStream("Siyue.Server.MobileUploadPage.html")!;
            using var reader = new StreamReader(stream); var html = await reader.ReadToEndAsync();
            return Results.Content(html.Replace("__MODE__", ticket.Mode), "text/html; charset=utf-8");
        });
        app.MapPost("/mobile-upload/{token}/file", async (string token, Data db, HttpContext context, Access a, IObjects objects) => {
            var ticket = await Ticket(token, db, context);
            if (ticket.State == "done") return Results.Ok(new { url = ticket.ResultUrl, state = "done" });
            var request = context.Request; var form = await request.ReadFormAsync();
            var fields = new Dictionary<string,StringValues>();
            if (ticket.ClassId.HasValue) fields["classId"] = ticket.ClassId.Value.ToString();
            if (ticket.CourseId.HasValue) fields["courseId"] = ticket.CourseId.Value.ToString();
            if (ticket.LectureId.HasValue) fields["lectureId"] = ticket.LectureId.Value.ToString();
            if (ticket.Mode is "book" or "handout") fields["recognitionMode"] = QuestionRecognition.Validate(ticket.MaterialKind);
            else if (ticket.MaterialKind.Length > 0) fields["materialKind"] = ticket.MaterialKind;
            // Never trust scope identifiers supplied by the embedded page.
            fields["commit"] = form["commit"] == "true" ? "true" : "false";
            request.Form = new FormCollection(fields, form.Files);
            if (ticket.Mode == "roster" && fields["commit"] != "true") return await CatalogEndpoints.ImportRoster("students", request, db, a, ticket.ClassId);
            await using var tx = await db.Database.BeginTransactionAsync();
            var won = await db.MobileUploads.Where(x => x.Id == ticket.Id && x.State == "pending").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "uploading"));
            if (won != 1) { await tx.RollbackAsync(); db.ChangeTracker.Clear(); var current = await db.MobileUploads.SingleAsync(x => x.Id == ticket.Id); Guard.That(current.State == "done", "文件正在上传，请稍后重试", 409); return Results.Ok(new { url = current.ResultUrl, state = "done" }); }
            string target;
            if (ticket.Mode == "book") { var b = await BookImports.Upload(ticket.ClassId!.Value, ticket.CourseId!.Value, request, db, a, objects); target = "/pages/book-import/book-import?id=" + b.Id; }
            else if (ticket.Mode == "handout") { var h = await LearningEndpoints.UploadHandout(ticket.LectureId!.Value, request, db, a, objects); target = "/pages/review/review?id=" + h.Id; }
            else if (ticket.Mode == "roster") { await CatalogEndpoints.ImportRoster("students", request, db, a, ticket.ClassId, transactionOwned: true); target = "/pages/roster/roster?classId=" + ticket.ClassId; }
            else { var d = await PrintFiles.Save(request, db, a, objects); target = "/pages/preview/preview?kind=export&id=" + d.Id; }
            ticket.State = "done"; ticket.ResultUrl = target; await db.SaveChangesAsync(); await tx.CommitAsync();
            return Results.Ok(new { url = target, state = "done" });
        }).DisableAntiforgery();
    }
    static async Task<MobileUpload> Ticket(string token, Data db, HttpContext context)
    {
        Guard.That(token.Length == 64 && token.All(Uri.IsHexDigit), "上传链接无效", 404);
        var hash = Secrets.Hash(token); var t = await db.MobileUploads.SingleOrDefaultAsync(x => x.TokenHash == hash && x.ExpiresAt > Clock.Now) ?? throw new ApiException(410, "上传入口已过期，请返回小程序重新选择");
        var staff = await db.Staff.SingleOrDefaultAsync(x => x.Id == t.StaffId && x.Enabled) ?? throw new ApiException(403, "账号已停用");
        Guard.That(await db.Sessions.AnyAsync(x => x.TokenHash == t.SessionHash && x.StaffId == t.StaffId && x.ExpiresAt > Clock.Now), "登录已过期，请返回小程序重新登录", 401);
        context.Items["actor"] = new Actor(staff.Id, staff.Name, staff.Role); return t;
    }
}
