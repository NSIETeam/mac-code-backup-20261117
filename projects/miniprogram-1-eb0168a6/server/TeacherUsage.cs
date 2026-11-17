using System.Globalization;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

public static class TeacherUsage
{
    const string Miniapp = "usage.miniapp.";
    const string Preview = "usage.miniapp-preview.";
    static readonly TimeSpan Beijing = TimeSpan.FromHours(8);
    static readonly Dictionary<(string Method, string Route), string> Operations = new()
    {
        [("GET", "/api/teaching-tools/sessions/{id:guid}")] = "teaching.analysis.view",
        [("POST", "/api/teaching-tools/sessions/{id:guid}/lesson-plan")] = "teaching.analysis.generate",
        [("PUT", "/api/teaching-tools/sessions/{id:guid}/date")] = "session.date",
        [("POST", "/api/teaching-tools/english/lesson")] = "teaching.lesson.generate",
        [("POST", "/api/teaching-tools/english/essays")] = "teaching.essays.generate",
        [("POST", "/api/teaching-tools/english/lesson/word")] = "teaching.lesson.word",
        [("POST", "/api/teaching-tools/jobs")] = "teaching.task.submit",
        [("POST", "/api/teaching-tools/jobs/{id:guid}/cancel")] = "teaching.task.cancel",
        [("POST", "/api/teaching-tools/jobs/{id:guid}/review")] = "teaching.task.review",
        [("GET", "/api/teaching-tools/jobs/{id:guid}/word")] = "teaching.lesson.word",
        [("GET", "/api/workspace")] = "workspace",
        [("GET", "/api/classes/{id:guid}")] = "class.view",
        [("POST", "/api/classes")] = "class.create",
        [("GET", "/api/class-sessions")] = "session.list",
        [("GET", "/api/class-sessions/{id:guid}")] = "session.view",
        [("POST", "/api/class-sessions")] = "session.create",
        [("PUT", "/api/class-sessions/{id:guid}/students/{studentId:guid}")] = "session.mark",
        [("POST", "/api/class-sessions/{id:guid}/exports")] = "session.export",
        [("GET", "/api/courses/{id:guid}/lectures")] = "lecture.list",
        [("POST", "/api/lectures")] = "lecture.create",
        [("PUT", "/api/lectures/{id:guid}/attendance")] = "lecture.attendance",
        [("GET", "/api/handouts/{id:guid}")] = "handout.view",
        [("POST", "/api/lectures/{id:guid}/handouts")] = "handout.upload",
        [("POST", "/api/handouts/{id:guid}/edit")] = "handout.edit",
        [("POST", "/api/handouts/{id:guid}/publish")] = "handout.publish",
        [("POST", "/api/handouts/{id:guid}/confirm-all")] = "handout.confirm",
        [("POST", "/api/handouts/{id:guid}/verify-selected")] = "handout.confirm",
        [("PUT", "/api/wrong-questions")] = "wrong.save",
        [("POST", "/api/exports")] = "export.create",
        [("GET", "/api/exports/{id:guid}/download")] = "export.download",
        [("GET", "/api/exports")] = "export.list",
        [("POST", "/api/print-files")] = "file.upload",
        [("POST", "/api/print-jobs")] = "print.submit",
        [("GET", "/api/print-jobs")] = "print.list",
        [("POST", "/api/print-jobs/{id:guid}/reprint")] = "print.reprint",
        [("GET", "/api/students/{id:guid}/history")] = "history.view",
        [("POST", "/api/students/{id:guid}/retests")] = "history.retest",
        [("POST", "/api/students/{id:guid}/history/export")] = "history.export",
        [("POST", "/api/classes/{classId:guid}/students")] = "student.create",
        [("POST", "/api/classes/{classId:guid}/students/batch")] = "student.create",
        [("PUT", "/api/classes/{classId:guid}/students/{id:guid}")] = "student.update",
        [("POST", "/api/classes/{classId:guid}/students/import")] = "student.import",
        [("POST", "/api/courses/{id:guid}/books")] = "book.upload",
        [("POST", "/api/classes/{classId:guid}/books")] = "book.upload",
        [("GET", "/api/books/{id:guid}")] = "book.view",
        [("POST", "/api/books/{id:guid}/page-split")] = "book.page-split",
        [("POST", "/api/handouts/{id:guid}/regions")] = "handout.regions",
        [("GET", "/api/material-question-bank")] = "bank.view",
        [("GET", "/api/material-question-bank/question")] = "bank.question",
        [("POST", "/api/material-question-bank/papers")] = "bank.paper",
        [("GET", "/api/question-bank")] = "bank.view",
        [("POST", "/api/question-bank/recommendations")] = "bank.recommend",
    };

    static readonly Dictionary<string, string> Labels = new()
    {
        ["teaching.analysis.view"] = "查看错题学情",
        ["teaching.analysis.generate"] = "提交错题讲评生成",
        ["teaching.lesson.generate"] = "提交英语备课生成",
        ["teaching.essays.generate"] = "提交英语作文批改",
        ["teaching.lesson.word"] = "导出英语Word",
        ["teaching.task.submit"] = "提交教学生成任务",
        ["teaching.task.cancel"] = "取消教学生成任务",
        ["teaching.task.review"] = "复核教学成果",
        ["session.date"] = "补充或修改测验日期",
        ["teaching.analysis.completed"] = "错题讲评生成完成",
        ["teaching.analysis.failed"] = "错题讲评生成失败",
        ["teaching.lesson.completed"] = "英语备课生成完成",
        ["teaching.lesson.failed"] = "英语备课生成失败",
        ["teaching.essays.completed"] = "英语作文批改生成完成",
        ["teaching.essays.failed"] = "英语作文批改生成失败",
        ["teaching.analysis.view.rejected"] = "查看错题学情未成功",
        ["teaching.analysis.generate.rejected"] = "提交错题讲评生成未成功",
        ["teaching.lesson.generate.rejected"] = "提交英语备课生成未成功",
        ["teaching.essays.generate.rejected"] = "提交英语作文批改未成功",
        ["teaching.lesson.word.rejected"] = "导出英语Word未成功",
        ["teaching.task.submit.rejected"] = "提交教学生成任务未成功",
        ["teaching.task.cancel.rejected"] = "取消教学生成任务未成功",
        ["teaching.task.review.rejected"] = "复核教学成果未成功",
        ["session.date.rejected"] = "补充或修改测验日期未成功",
        ["workspace"] = "打开工作台", ["class.view"] = "查看班级", ["class.create"] = "创建班级",
        ["session.list"] = "查看课次列表", ["session.view"] = "打开课次", ["session.create"] = "创建课次",
        ["session.mark"] = "登记课次批改", ["session.export"] = "生成课次错题本",
        ["lecture.list"] = "查看讲次列表", ["lecture.create"] = "创建讲次", ["lecture.attendance"] = "登记考勤",
        ["handout.view"] = "打开讲义", ["handout.upload"] = "上传讲义", ["handout.edit"] = "编辑讲义",
        ["handout.publish"] = "发布讲义", ["handout.confirm"] = "确认讲义题目", ["wrong.save"] = "保存错题",
        ["export.create"] = "生成错题本", ["export.list"] = "查看生成记录", ["export.download"] = "获取文档下载",
        ["file.upload"] = "上传打印文件", ["print.submit"] = "提交打印", ["print.list"] = "查看打印记录",
        ["print.reprint"] = "提交重打", ["history.view"] = "查看学生错题历史", ["history.retest"] = "记录复测",
        ["history.export"] = "导出学生错题历史", ["student.create"] = "添加学生", ["student.update"] = "修改学生资料",
        ["student.import"] = "导入学生名单", ["book.upload"] = "上传教材", ["book.view"] = "查看教材",
        ["bank.view"] = "打开题库", ["bank.recommend"] = "查找同类题",
        ["bank.question"] = "查看题库题目", ["bank.paper"] = "题库组卷",
        ["session.auto-create"] = "自动补齐课次", ["class.update"] = "修改班级", ["class.delete"] = "删除班级",
        ["lecture.update"] = "修改讲次", ["lecture.delete"] = "删除讲次", ["lecture.share"] = "共享讲次",
        ["handout.remove-question"] = "删除讲义题目", ["handout.verify-integrity"] = "核对讲义完整性",
        ["handout.label"] = "编辑题号", ["handout.label-confirm"] = "确认题号", ["handout.regions"] = "编辑 PDF 识别区域",
        ["handout.remove-block"] = "删除讲义内容块", ["handout.merge"] = "合并讲义题目",
        ["book.split"] = "拆分教材", ["book.page-split"] = "拆分教材页面", ["course.create"] = "创建课程",
    };

    static readonly Dictionary<string, string> ExistingActions = new()
    {
        ["classes.create"] = "class.create", ["classes.update"] = "class.update", ["classes.delete"] = "class.delete",
        ["class-session.create"] = "session.create", ["class-session.auto-create"] = "session.auto-create",
        ["class-session.mark"] = "session.mark", ["class-session.export"] = "session.export",
        ["wrong-questions.save"] = "wrong.save", ["handout.verify-selected"] = "handout.confirm",
        ["handout.confirm-all"] = "handout.confirm", ["roster.import.students"] = "student.import",
        ["student.retest"] = "history.retest", ["courses.create"] = "course.create",
    };

    // WeChat supplies the Referer for native mini-program requests. Match the
    // project's app ID, not a user-agent substring or a caller-defined header.
    static string? Client(HttpRequest request, IConfiguration config)
    {
        var appId = config["TeacherUsage:MiniappId"];
        if (string.IsNullOrEmpty(appId) ||
            !Uri.TryCreate(request.Headers.Referer.ToString(), UriKind.Absolute, out var referer) ||
            referer.Scheme != "https" || referer.Host != "servicewechat.com" || !referer.IsDefaultPort ||
            referer.UserInfo.Length != 0)
            return null;
        var parts = referer.AbsolutePath.Split('/');
        if (parts.Length != 4 || parts[1] != appId || parts[3] != "page-frame.html") return null;
        if (parts[2] is "0" or "devtools") return Preview;
        return int.TryParse(parts[2], NumberStyles.None, CultureInfo.InvariantCulture, out var version) && version > 0
            ? Miniapp : null;
    }

    public static void Track(HttpContext context, Staff staff)
    {
        if (staff.Role != "teacher" || context.GetEndpoint() is not RouteEndpoint route ||
            !Operations.TryGetValue((context.Request.Method, route.RoutePattern.RawText ?? ""), out var action))
            return;
        var client = Client(context.Request, context.RequestServices.GetRequiredService<IConfiguration>());
        if (client == null) return;

        var services = context.RequestServices.GetRequiredService<IServiceScopeFactory>();
        var logger = context.RequestServices.GetRequiredService<ILoggerFactory>().CreateLogger("TeacherUsage");
        var at = Clock.Now;
        context.Response.OnCompleted(async () =>
        {
            var operation=context.Items["teachingOperation"] as string??action;
            var success=context.Response.StatusCode is >=200 and <300;
            if(!success&&!operation.StartsWith("teaching.")&&operation!="session.date")return;
            if(!success)operation+=".rejected";
            var target=context.Request.RouteValues.TryGetValue("id",out var raw)&&Guid.TryParse(raw?.ToString(),out var id)?id:staff.Id;
            try
            {
                await using var scope = services.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<Data>();
                db.Audits.Add(new Audit { StaffId = staff.Id, Action = client + operation, TargetId = target, CreatedAt = at });
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                await db.SaveChangesAsync(timeout.Token);
            }
            catch (Exception error)
            {
                logger.LogWarning(error, "Could not record teacher activity");
            }
        });
    }

    static (string Source, string? Code) Classify(string action)
    {
        if (action.StartsWith(Miniapp, StringComparison.Ordinal)) return ("miniapp", action[Miniapp.Length..]);
        if (action.StartsWith(Preview, StringComparison.Ordinal)) return ("preview", action[Preview.Length..]);
        if (action.StartsWith("handout.regions.saved.", StringComparison.Ordinal)) return ("unattributed", "handout.regions");
        var code = ExistingActions.GetValueOrDefault(action, action);
        return ("unattributed", Labels.ContainsKey(code) ? code : null);
    }

    public static (string Source,string? Action) Describe(string action)
    {
        var (source,code)=Classify(action);
        return (source,code==null?null:Labels.GetValueOrDefault(code));
    }

    public static void MapTeacherUsage(this WebApplication app)
    {
        app.MapUsageStatistics();
        app.MapGet("/api/admin/teacher-usage", async (string start, string end, Data db, Access access,
            CancellationToken cancellation) =>
        {
            access.Admin();
            const string format = "yyyy-MM-dd";
            if (!DateOnly.TryParseExact(start, format, CultureInfo.InvariantCulture, DateTimeStyles.None, out var first) ||
                !DateOnly.TryParseExact(end, format, CultureInfo.InvariantCulture, DateTimeStyles.None, out var last) ||
                last < first || last.DayNumber - first.DayNumber >= 31 || first.Year < 2000 || last.Year > 2100)
                throw new ApiException(400, "请选择连续 1 至 31 天的日期范围");

            var since = new DateTimeOffset(first.ToDateTime(TimeOnly.MinValue), Beijing);
            var until = new DateTimeOffset(last.AddDays(1).ToDateTime(TimeOnly.MinValue), Beijing);
            var allowed = Labels.Keys.Concat(ExistingActions.Keys).ToArray();
            var rows = await (from audit in db.Audits.AsNoTracking()
                join staff in db.Staff.AsNoTracking() on audit.StaffId equals staff.Id
                where staff.Enabled && staff.Role == "teacher" && staff.Name != "test_teacher" &&
                    audit.CreatedAt >= since && audit.CreatedAt < until &&
                    (audit.Action.StartsWith(Miniapp) || audit.Action.StartsWith(Preview) ||
                     audit.Action.StartsWith("handout.regions.saved.") || allowed.Contains(audit.Action))
                select new { StaffId = staff.Id, audit.Action, audit.CreatedAt }).ToListAsync(cancellation);

            var items = rows.Select(row =>
            {
                var (source, code) = Classify(row.Action);
                return new { row.StaffId, At = row.CreatedAt.ToOffset(Beijing), Source = source, Code = code };
            }).Where(row => row.Code != null && Labels.ContainsKey(row.Code))
              .GroupBy(row => new { row.StaffId, Day = DateOnly.FromDateTime(row.At.DateTime), row.Source, row.Code })
              .Select(group => new
              {
                  group.Key.StaffId, group.Key.Day, group.Key.Source, Action = Labels[group.Key.Code!],
                  Count = group.Count(), FirstAt = group.Min(x => x.At), LastAt = group.Max(x => x.At)
              })
              .OrderByDescending(row => row.Day).ThenBy(row => row.Source).ThenBy(row => row.FirstAt);
            return Results.Ok(new { items });
        });
    }
}
