using Microsoft.EntityFrameworkCore;

namespace Siyue;

public static class ResearchEndpoints
{
    public static void MapResearch(this WebApplication app)
    {
        // ASP.NET routes match both /research and /research/; resolve each relative redirect correctly.
        app.MapGet("/research", (HttpRequest request) => Results.Redirect(request.Path.Value!.EndsWith('/') ? "index.html" : "research/index.html"));
        app.MapGet("/research/math/{**path}", (string path, IWebHostEnvironment env) => {
            Guard.That(System.Text.RegularExpressions.Regex.IsMatch(path, @"\A(?:katex\.min\.(?:js|css)|fonts/[a-zA-Z0-9_-]+\.(?:woff2?|ttf))\z"), "资源不存在", 404);
            var file = Path.Combine(env.ContentRootPath, "AcademicPilotAssets", "math", path);
            Guard.That(File.Exists(file), "资源不存在", 404);
            return Results.File(file, path.EndsWith(".js") ? "text/javascript" : path.EndsWith(".css") ? "text/css" : path.EndsWith(".woff2") ? "font/woff2" : "application/octet-stream");
        });
        var api = app.MapGroup("/api/research").AddEndpointFilter(async (context, next) => {
            var services = context.HttpContext.RequestServices;
            var user = services.GetRequiredService<Access>().User;
            var config = services.GetRequiredService<IConfiguration>();
            var owner = config["Research:OwnerStaffId"];
            if (!string.IsNullOrWhiteSpace(owner))
                return Guid.TryParse(owner, out var id) && user.Id == id ? await next(context) : Results.NotFound();

            // The launcher uses a separate SQLite database for the loopback demo identity.
            if (config.GetValue<bool>("Research:LocalPreview") &&
                config.GetValue<bool>("DevelopmentPreview") && config["Database:Provider"] == "sqlite" &&
                services.GetRequiredService<IWebHostEnvironment>().IsDevelopment() &&
                context.HttpContext.Connection.RemoteIpAddress is { } ip && System.Net.IPAddress.IsLoopback(ip) &&
                context.HttpContext.Request.Host.Host is "localhost" or "127.0.0.1" or "[::1]" &&
                await services.GetRequiredService<Data>().Staff.AnyAsync(
                    s => s.Id == user.Id && s.OpenId == "local-preview", context.HttpContext.RequestAborted))
                return await next(context);

            return Results.NotFound();
        });
        app.MapClassAnalysis(api);
        ResearchWrongQuestions.Map(api);
        api.MapGet("/access", () => Results.NoContent());
        api.MapGet("/status", (Access access, IResearchQuestions questions) => { _ = access.User; return questions.Status(); });
        api.MapGet("/drafts", async (Access access, ResearchStore store, CancellationToken ct) =>
            (await store.List(access.User.Id, ct)).Select(d => new { d.Id, d.Title, d.Subject, d.Grade, d.Region, d.State, d.UpdatedAt, count = d.Items.Length }));
        api.MapPost("/drafts", async (ResearchCreate input, Access access, ResearchStore store, CancellationToken ct) => ResearchImport.View(await store.Create(access.User.Id, input, null, ct)));
        api.MapPost("/upload", async (HttpRequest request, Access access, ResearchStore store, CancellationToken ct) => {
            var form = await request.ReadFormAsync(ct); var file = form.Files.GetFile("file") ?? throw new ApiException(400, "请选择讲义文件");
            var input = new ResearchCreate(form["title"].ToString(), form["subject"].ToString(), form["stage"].ToString(), form["grade"].ToString(), form["region"].ToString(), form["templateId"].ToString());
            return Results.Accepted(value: ResearchImport.View(await store.Create(access.User.Id, input, file, ct)));
        }).DisableAntiforgery();
        api.MapGet("/drafts/{id:guid}", async (Guid id, Access access, ResearchStore store, CancellationToken ct) => ResearchImport.View(await store.Load(access.User.Id, id, ct)));
        api.MapPost("/drafts/{id:guid}/edit", async (Guid id, ResearchEdit input, Access access, ResearchStore store, IResearchQuestions questions, CancellationToken ct) => {
            var candidate = input.Action is "replace" or "append-candidate" ? questions.Candidate(access.User.Id, id, input.CandidateId ?? "") : null;
            return ResearchImport.View(await store.Change(access.User.Id, id, input.Revision, d => ResearchStore.Edit(d, input, candidate), ct));
        });
        api.MapPost("/drafts/{id:guid}/retry", async (Guid id, Access access, ResearchStore store, CancellationToken ct) =>
            ResearchImport.View(await store.Change(access.User.Id, id, null, d => { Guard.That(d.State == "failed", "当前讲义无需重试", 409); return d with { State = "queued", Message = "等待重新拆题" }; }, ct)));
        api.MapPost("/drafts/{id:guid}/search", async (Guid id, ResearchSearch input, Access access, ResearchStore store, IResearchQuestions questions, CancellationToken ct) => {
            var d = await store.Load(access.User.Id, id, ct); Guard.That(d.State == "ready" && d.Items.Any(q => q.Id == input.ItemId), "请选择已拆分的题目");
            return await questions.Search(d, input, ct);
        });
        api.MapPost("/drafts/{id:guid}/analyze/{itemId:guid}", async (Guid id, Guid itemId, Access access, ResearchStore store, IQuestionBank bank, LearningModelSettings settings, IHttpClientFactory clients, CancellationToken ct) => {
            var d = await store.Load(access.User.Id, id, ct); Guard.That(d.State == "ready", "请等待拆题完成", 409);
            var q = d.Items.SingleOrDefault(q => q.Id == itemId) ?? throw new ApiException(404, "题目不存在");
            var result = await ResearchKnowledge.Analyze(d, q, bank, settings, clients, ct);
            return ResearchImport.View(await store.Change(access.User.Id, id, d.Revision, x => x with { Items = x.Items.Select(q => q.Id == itemId ? q with { Knowledge = result.Knowledge, KnowledgeMethod = result.Method, Reviewed = false } : q).ToArray() }, ct));
        });
        api.MapGet("/knowledge", (string subject, string? q, Access access, IQuestionBank bank) => { _ = access.User; Guard.That(LocalQuestionBank.Subjects.Contains(subject) && (q?.Length ?? 0) <= 100, "学科或关键词无效"); return bank.Knowledge(subject, q ?? ""); });
        api.MapGet("/templates", async (Access access, ResearchStore store, CancellationToken ct) => await store.Templates(access.User.Id, ct));
        api.MapPost("/templates", async (ResearchTemplate input, Access access, ResearchStore store, CancellationToken ct) => await store.SaveTemplate(access.User.Id, input, ct));
        api.MapGet("/drafts/{id:guid}/original", async (Guid id, Access access, ResearchStore store, CancellationToken ct) => {
            var d = await store.Load(access.User.Id, id, ct); Guard.That(d.FileName.Length > 0, "此讲义没有上传原稿", 404);
            var ext = Path.GetExtension(d.FileName).ToLowerInvariant(); return Results.File(Path.Combine(store.Dir(d.Owner, id), "original" + ext), ext == ".pdf" ? "application/pdf" : WordDocuments.DocxType, d.FileName);
        });
        api.MapGet("/drafts/{id:guid}/images/{relationship}", async (Guid id, string relationship, Access access, ResearchStore store, CancellationToken ct) => {
            var d = await store.Load(access.User.Id, id, ct); Guard.That(d.State == "ready", "原稿尚未处理完成", 409);
            var image = ResearchImport.Image(Path.Combine(store.Dir(d.Owner, id), "source.docx"), relationship); return Results.File(image.Bytes, image.Type);
        });
        api.MapGet("/drafts/{id:guid}/word-preview/{itemId:guid}/{key}/{page:int?}", async (
            Guid id, Guid itemId, string key, int? page, Access access, ResearchStore store, WordDocuments words, CancellationToken ct) => {
            var draft = await store.Load(access.User.Id, id, ct);
            var item = draft.Items.SingleOrDefault(q => q.Id == itemId) ?? throw new ApiException(404, "题目不存在");
            Guard.That(draft.State == "ready" && key == ResearchImport.WordPreviewKey(draft, item) && (page is null or > 0), "题目预览不存在", 404);
            var dir = await ResearchImport.PrepareWordPreview(store.Dir(draft.Owner, id), item, key, words, ct);
            if (page == null) return Results.Ok(new { pages = Directory.GetFiles(dir, "*.png").Length });
            var file = Path.Combine(dir, page + ".png");
            Guard.That(File.Exists(file), "预览页不存在", 404);
            return Results.File(file, "image/png");
        });
        api.MapGet("/drafts/{id:guid}/media", async (Guid id, Guid? itemId, string? candidateId, int index, string field, Access access, ResearchStore store, IResearchQuestions questions, IHttpClientFactory clients, CancellationToken ct) => {
            var d = await store.Load(access.User.Id, id, ct);
            var item = candidateId != null ? questions.Candidate(access.User.Id, id, candidateId) : d.Items.SingleOrDefault(q => q.Id == itemId) ?? throw new ApiException(404, "题目不存在");
            var html = field switch { "stem" => item.Stem, "answer" => item.Answer, "analysis" => item.Analysis, _ => throw new ApiException(400, "图片字段无效") };
            var urls = ResearchMedia.Sources(html); Guard.That(index >= 0 && index < urls.Length, "图片不存在", 404);
            var image = await ResearchMedia.Fetch(urls[index], clients, ct); return Results.File(image.Bytes, image.Type);
        });
        api.MapGet("/drafts/{id:guid}/export", async (Guid id, string? format, string? version, bool? preview, Access access, ResearchStore store, WordDocuments words, CancellationToken ct) => {
            var d = await store.Load(access.User.Id, id, ct); format ??= "pdf"; version ??= "student";
            Guard.That(format is "pdf" or "docx" && version is "student" or "teacher", "请选择有效的导出格式");
            Guard.That(d.State == "ready" && d.Items.Length > 0, "请先完成讲义内容", 409);
            Guard.That(preview == true || d.Items.All(x => x.Reviewed), "请先逐题确认图文完整性和答案；可先查看排版预览", 409);
            var dir = Path.Combine(store.Dir(d.Owner, id), "exports", d.Revision + "-" + version); Directory.CreateDirectory(dir);
            await using var gate = await ResearchStore.Lock(dir, ".export.lock", ct);
            var docx = await words.ResearchDocx(d, Path.Combine(store.Dir(d.Owner, id), "source.docx"), dir, version == "teacher", ct);
            var file = format == "pdf" ? await words.Convert(docx, "pdf", dir, ct) : docx;
            return Results.File(await File.ReadAllBytesAsync(file, ct), format == "pdf" ? "application/pdf" : WordDocuments.DocxType,
                d.Title + (version == "teacher" ? "-教师参考版" : "-学生讲义") + "." + format);
        });
    }
}
