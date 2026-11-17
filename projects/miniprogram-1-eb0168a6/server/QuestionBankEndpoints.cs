using Microsoft.EntityFrameworkCore;
namespace Siyue;

public static class QuestionBankEndpoints
{
    public sealed record CommunityVisibility(bool Active);
    internal static BankQuery Validate(BankQuery input)
    {
        Guard.That(LocalQuestionBank.Subjects.Contains(input.Subject), "请选择学科");
        Guard.That(LocalQuestionBank.Stages.Contains(input.Stage), "请选择实际学段");
        Guard.That(input.Topic is { Length: > 0 and <= 10000 } && !string.IsNullOrWhiteSpace(input.Topic), "请输入题目类型或知识点，最多10000字");
        Guard.That(input.Type != null && (input.Type.Length == 0 || LocalQuestionBank.Types.Contains(input.Type)), "请选择有效题型");
        Guard.That(input.Limit is >= 1 and <= 20, "每次返回1至20道题");
        Guard.That(input.SourceText is { Length: <= 10000 }, "原题文字最多10000字");
        Guard.That(input.ExcludeIds == null || input.ExcludeIds.Length <= 100 && input.ExcludeIds.All(id => id is { Length: > 0 and <= 200 }), "最多排除100道题");
        var topic = input.Topic.Trim();
        var detectedType = LocalQuestionBank.Types.FirstOrDefault(t => topic.EndsWith(t, StringComparison.Ordinal)) ?? (topic.EndsWith("大题", StringComparison.Ordinal) ? "解答题" : "");
        Guard.That(input.Type.Length == 0 || detectedType.Length == 0 || input.Type == detectedType, "输入的题型与筛选题型不一致，请调整后重试");
        var type = input.Type.Length > 0 ? input.Type : detectedType;
        Guard.That(!LocalQuestionBank.Types.Append("大题").Contains(topic), "请补充具体知识点，例如“椭圆离心率填空题”");
        return input with { Topic = topic, Type = type };
    }
    public static void MapQuestionBank(this WebApplication app)
    {
        app.MapGet("/api/question-bank", (Access access, IQuestionBank bank, CommunityQuestionStore uploads) => { _ = access.User; var status=bank.Availability();return new {status.Available,status.Count,status.Coverage,status.Method,uploadsEnabled=uploads.Enabled,uploadBatchLimit=100}; });
        app.MapGet("/api/question-bank/knowledge", (string subject, string? query, Access access, IQuestionBank bank) => {
            _ = access.User; Guard.That(LocalQuestionBank.Subjects.Contains(subject), "请选择学科");
            Guard.That(query == null || query.Length <= 100, "知识点搜索最多100字");
            return bank.Knowledge(subject, query?.Trim() ?? "");
        });
        app.MapPost("/api/question-bank/recommendations", (BankQuery input, Access access, IQuestionBank bank) => {
            _ = access.User; return Results.Ok(bank.Search(Validate(input)));
        });
        app.MapPost("/api/question-bank/uploads", (CommunityUploadInput input, Access access, IQuestionBank bank, CommunityQuestionStore uploads) => {
            return Results.Ok(uploads.Upload(access.User.Id,input,bank));
        });
        app.MapGet("/api/question-bank/intakes",(Access a,QuestionBankIntake intake)=>intake.Mine(a.User.Id));
        app.MapPost("/api/question-bank/intakes",async(HttpRequest request,Access a,QuestionBankIntake intake,CancellationToken ct)=>Results.Ok(await intake.Queue(a.User.Id,request,ct))).DisableAntiforgery();
        app.MapGet("/api/question-bank/intakes/{id:guid}",(Guid id,Access a,QuestionBankIntake intake)=>intake.View(a.User.Id,id));
        app.MapPost("/api/question-bank/intakes/{id:guid}/publish",(Guid id,BankIntakePublish input,Access a,QuestionBankIntake intake,IQuestionBank bank)=>intake.Publish(a.User.Id,id,input,bank));
        app.MapGet("/api/question-bank/intakes/{id:guid}/assets/{name}",(Guid id,string name,Access a,QuestionBankIntake intake,HttpContext context)=>{var asset=intake.FileFor(a.User.Id,id,name);context.Response.Headers["X-Content-Type-Options"]="nosniff";context.Response.Headers.CacheControl="no-store";return Results.File(asset.Path,asset.Type,asset.Name);});
        app.MapGet("/api/question-bank/uploads/mine", (int? page, Access access, CommunityQuestionStore uploads) => uploads.Mine(access.User.Id,page??1));
        app.MapPut("/api/question-bank/uploads/{id}", (string id, CommunityVisibility input, Access access, CommunityQuestionStore uploads) => new {revision=uploads.SetActive(access.User.Id,id,input.Active)});
        app.MapGet("/question-bank/{**asset}", IResult (string? asset,IWebHostEnvironment env,HttpContext ctx)=>{
            if(asset==null && !(ctx.Request.Path.Value??"").EndsWith("/",StringComparison.Ordinal))return Results.Redirect("question-bank/");
            asset=string.IsNullOrEmpty(asset)?"index.html":asset;
            Guard.That(asset is "index.html" or "app.mjs" or "style.css" or "siyue-ai-logo.png" || System.Text.RegularExpressions.Regex.IsMatch(asset,@"\Amath/(?:katex\.min\.(?:js|css)|contrib/auto-render\.min\.js|LICENSE\.txt|fonts/KaTeX_[A-Za-z0-9_-]+\.woff2)\z"),"资源不存在",404);
            ctx.Response.Headers.ContentSecurityPolicy="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'";
            ctx.Response.Headers["X-Content-Type-Options"]="nosniff";ctx.Response.Headers["Referrer-Policy"]="no-referrer";
            ctx.Response.Headers.CacheControl=asset=="index.html"?"no-store":"public,max-age=3600";
            var type=asset.EndsWith(".png")?"image/png":asset.EndsWith(".html")?"text/html; charset=utf-8":asset.EndsWith(".mjs")||asset.EndsWith(".js")?"text/javascript; charset=utf-8":asset.EndsWith(".css")?"text/css; charset=utf-8":asset.EndsWith(".woff2")?"font/woff2":"text/plain; charset=utf-8";
            var file=asset.StartsWith("math/",StringComparison.Ordinal)?Path.Combine(env.ContentRootPath,"AcademicPilotAssets",asset):Path.Combine(env.ContentRootPath,"QuestionBankAssets",asset);
            Guard.That(File.Exists(file),"资源不存在",404);return Results.File(file,type);
        });
        // A source read reuses existing class/course/student permissions. It never changes a mark.
        app.MapGet("/api/question-bank/source", async (Guid sessionId, Guid studentId, Guid questionId, Access access, Data db) => {
            var session = await db.ClassSessions.AsNoTracking().SingleOrDefaultAsync(s => s.Id == sessionId) ?? throw new ApiException(404, "课次不存在");
            await access.Class(session.ClassId, session.CourseId);
            Guard.That(await db.Students.AnyAsync(s => s.Id == studentId && s.ClassId == session.ClassId), "学生不属于当前班级", 403);
            var mark = await db.ClassSessionMarks.AsNoTracking().SingleOrDefaultAsync(m => m.ClassSessionId == sessionId && m.StudentId == studentId);
            Guard.That(mark != null && mark.Reviewed, "请先完成该学生的错题登记", 409);
            Guard.That(Json.Read<Guid[]>(mark.WrongIdsJson).Contains(questionId), "只能读取该学生本课次已登记的错题", 409);
            var question = await db.Questions.AsNoTracking().SingleOrDefaultAsync(q => q.Id == questionId && q.HandoutId == session.HandoutId) ?? throw new ApiException(409, "原错题已变化，请重新核对");
            var courseName = await db.Courses.Where(c => c.Id == session.CourseId).Select(c => c.Name).SingleAsync();
            var subjects = LocalQuestionBank.Subjects.Where(courseName.Contains).ToArray();
            var text = string.Join("\n", Json.Read<ContentBlock[]>(question.BlocksJson).Where(b => b.Kind is not ("image" or "drawing") && !string.IsNullOrWhiteSpace(b.Text)).Select(b => b.Text));
            return Results.Ok(new { questionId, question.Label, text, suggestedSubject = subjects.Length == 1 ? subjects[0] : "", markRevision = mark.Revision });
        });
    }
}
