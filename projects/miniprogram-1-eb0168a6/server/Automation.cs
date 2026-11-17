using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Routing;
using System.Threading.RateLimiting;
namespace Siyue;

public class AutomationCredential : Entity
{
    public Guid StaffId { get; set; }
    public string Name { get; set; } = "";
    [System.Text.Json.Serialization.JsonIgnore] public string TokenHash { get; set; } = "";
    public string ScopesJson { get; set; } = "[]";
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
    public DateTimeOffset ExpiresAt { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }
}
// A single durable record arbitrates an entire print request, including disjoint document sets.
public class PrintRequest : Entity
{
    public Guid StaffId { get; set; }
    public string RequestKey { get; set; } = "";
    public string RequestHash { get; set; } = "";
}
public record AutomationCredentialInput(string Name, string[] Scopes, int ExpiresInDays = 30);
public record AutomationTaskStatus(Guid Id, string Kind, string State, bool Terminal, bool NeedsReview, int? Revision, string? Error, int PollAfterSeconds);

public sealed class AutomationAdmission : IDisposable
{
    readonly ConcurrencyLimiter global;
    readonly PartitionedRateLimiter<Guid> owners;
    readonly PartitionedRateLimiter<Guid> rates;
    readonly ConcurrencyLimiter uploads;
    public int UploadConcurrency { get; }
    public int OwnerRequestsPerSecond { get; }
    public int OwnerBurst { get; }
    public int GlobalConcurrency { get; }
    public int OwnerConcurrency { get; }
    public AutomationAdmission(IConfiguration config)
    {
        GlobalConcurrency = Math.Clamp(config.GetValue("Automation:GlobalConcurrency", 64), 1, 512);
        OwnerConcurrency = Math.Clamp(config.GetValue("Automation:OwnerConcurrency", 8), 1, 64);
        UploadConcurrency = Math.Clamp(config.GetValue("Automation:UploadConcurrency", 2), 1, 16);
        OwnerRequestsPerSecond = Math.Clamp(config.GetValue("Automation:OwnerRequestsPerSecond", 5), 1, 100);
        OwnerBurst = Math.Clamp(config.GetValue("Automation:OwnerBurst", 30), 1, 500);
        uploads = new(new() { PermitLimit = UploadConcurrency, QueueLimit = 0 });
        rates = PartitionedRateLimiter.Create<Guid, Guid>(id => RateLimitPartition.GetTokenBucketLimiter(id, _ => new() { TokenLimit = OwnerBurst, TokensPerPeriod = OwnerRequestsPerSecond, ReplenishmentPeriod = TimeSpan.FromSeconds(1), AutoReplenishment = true, QueueLimit = 0 }));
        global = new(new() { PermitLimit = GlobalConcurrency, QueueLimit = 0 });
        // Partition by teacher, not by credential, so issuing more keys doesn't multiply capacity.
        owners = PartitionedRateLimiter.Create<Guid, Guid>(id => RateLimitPartition.GetConcurrencyLimiter(id,
            _ => new() { PermitLimit = OwnerConcurrency, QueueLimit = 0 }));
    }
    public RateLimitLease Global() => global.AttemptAcquire();
    public RateLimitLease Owner(Guid id) => owners.AttemptAcquire(id);
    public RateLimitLease Rate(Guid id) => rates.AttemptAcquire(id);
    public RateLimitLease Upload() => uploads.AttemptAcquire();
    public void Dispose() { global.Dispose(); owners.Dispose(); rates.Dispose(); uploads.Dispose(); }
}

public static class Automation
{
    public static bool UsesCredential(HttpContext ctx) => ctx.Request.Headers.Authorization.ToString().StartsWith("Bearer sya_", StringComparison.Ordinal);
    public static async Task<Actor> Authenticate(HttpContext ctx, Data db, string raw)
    {
        var hash = Secrets.Hash(raw);
        var key = await db.AutomationCredentials.AsNoTracking().SingleOrDefaultAsync(x => x.TokenHash == hash && x.RevokedAt == null && x.ExpiresAt > Clock.Now, ctx.RequestAborted);
        Guard.That(key != null, "Agent 凭证无效、已过期或已撤销", 401);
        var staff = await db.Staff.AsNoTracking().SingleOrDefaultAsync(x => x.Id == key!.StaffId && x.Enabled, ctx.RequestAborted);
        Guard.That(staff != null, "账号已停用", 401);
        var operation = AutomationCatalog.Find(ctx.Request.Method, (ctx.GetEndpoint() as RouteEndpoint)?.RoutePattern.RawText);
        Guard.That(operation != null, "此接口未向外部 Agent 开放", 403);
        Guard.That(Json.Read<string[]>(key!.ScopesJson).Contains(operation!.Scope), "Agent 凭证缺少操作权限：" + operation.Scope, 403);
        Guard.That(!operation.Admin || staff!.Role == "admin", "需要管理员权限", 403);
        ctx.Items["automationCredential"] = key;
        ctx.Items["automationOperation"] = operation.Id;
        return new(staff!.Id, staff.Name, staff.Role);
    }
    public static async Task RejectBusy(HttpContext ctx)
    {
        ctx.Response.StatusCode = 429;
        ctx.Response.Headers.RetryAfter = "2";
        await ctx.Response.WriteAsJsonAsync(new { code = "RATE_LIMITED", message = "并发请求过多，请按 Retry-After 退避重试", traceId = ctx.TraceIdentifier });
    }
    public static void MapAutomation(this WebApplication app)
    {
        // Credential lifecycle is session-only: Authenticate intentionally denies these routes to agent keys.
        app.MapPost("/api/automation/credentials", async (AutomationCredentialInput r, Data db, Access access) => {
            Guard.That(r.ExpiresInDays is >=1 and <=90, "凭证有效期为 1–90 天");
            Guard.That(r.Scopes is { Length: >0 } && r.Scopes.All(AutomationCatalog.Scopes.Contains), "请选择有效的操作权限");
            Guard.That(access.User.Role == "admin" || !r.Scopes.Contains("organization:write"), "教师不能授予组织管理权限", 403);
            var raw = "sya_" + Secrets.New();
            var key = new AutomationCredential { StaffId = access.User.Id, Name = Guard.Name(r.Name), TokenHash = Secrets.Hash(raw), ScopesJson = Json.Write(r.Scopes.Distinct().Order().ToArray()), ExpiresAt = Clock.Now.AddDays(r.ExpiresInDays) };
            db.AutomationCredentials.Add(key);
            db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "automation.credential.create", TargetId = key.Id });
            await db.SaveChangesAsync();
            return Results.Created($"/api/automation/credentials/{key.Id}", new { key.Id, key.Name, token = raw, scopes = Json.Read<string[]>(key.ScopesJson), key.ExpiresAt });
        });
        app.MapGet("/api/automation/credentials", async (Data db, Access access) => {
            var rows = await db.AutomationCredentials.AsNoTracking().Where(x => x.StaffId == access.User.Id).OrderByDescending(x => x.CreatedAt).ToListAsync();
            return rows.Select(x => new { x.Id, x.Name, scopes = Json.Read<string[]>(x.ScopesJson), x.CreatedAt, x.ExpiresAt, x.RevokedAt });
        });
        app.MapDelete("/api/automation/credentials/{id:guid}", async (Guid id, Data db, Access access) => {
            var key = await db.AutomationCredentials.SingleOrDefaultAsync(x => x.Id == id && x.StaffId == access.User.Id) ?? throw new ApiException(404, "凭证不存在");
            if (key.RevokedAt == null) { key.RevokedAt = Clock.Now; db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "automation.credential.revoke", TargetId = id }); await db.SaveChangesAsync(); }
            return Results.NoContent();
        });
        app.MapGet("/api/automation/v1/capabilities", (Access access, HttpContext ctx, AutomationAdmission limits) => {
            var credential = ctx.Items["automationCredential"] as AutomationCredential;
            var scopes = credential != null ? Json.Read<string[]>(credential.ScopesJson) : AutomationCatalog.Scopes;
            return new {
                version = "1.0.0", user = access.User, scopes,
                openApiPath = "/openapi/agent-v1.json",
                limits = new { perInstanceConcurrency = limits.GlobalConcurrency, perOwnerConcurrency = limits.OwnerConcurrency, uploadConcurrency = limits.UploadConcurrency, ownerRequestsPerSecond = limits.OwnerRequestsPerSecond, ownerBurst = limits.OwnerBurst, recommendedClientConcurrency = 4, recommendedUploadConcurrency = 2, maxExportStudents = 100, maxPrintDocuments = 100, pollSeconds = 2 },
                operations = AutomationCatalog.Operations.Where(x => scopes.Contains(x.Scope) && (!x.Admin || access.User.Role == "admin")),
                unsupported = new[] { "Agent 凭证不能管理凭证、读取/修改模型密钥、操作打印助手内部接口。", "未标记 idempotent 的写操作不支持自动重试。", "submitted 表示已提交打印机，不保证出纸；uncertain 需人工核实。" }
            };
        });
        app.MapGet("/api/automation/v1/classes", async (string? query, int? page, int? pageSize, Data db, Access a, CancellationToken ct) => {
            var number = page ?? 1; var size = pageSize ?? 50;
            Guard.That(number is >=1 and <=100000 && size is >=1 and <=100 && (query?.Length ?? 0) <=120, "分页或搜索参数无效");
            var term = (query ?? "").Trim(); var user = a.User;
            var visible = db.Classes.AsNoTracking().Where(x => (user.Role == "admin" || db.Assignments.Any(s => s.StaffId == user.Id && s.ClassId == x.Id)) && (term == "" || x.Name.Contains(term)));
            var rows = await visible.OrderBy(x => x.Name).ThenBy(x => x.Id).Skip((number-1)*size).Take(size+1).Select(x => new {x.Id,x.Name,x.CampusId}).ToArrayAsync(ct);
            var ids = rows.Take(size).Select(x=>x.Id).ToArray();
            var assignments = await db.Assignments.AsNoTracking().Where(x => ids.Contains(x.ClassId) && (user.Role == "admin" || x.StaffId == user.Id)).Select(x => new {x.ClassId,x.CourseId}).Distinct().ToArrayAsync(ct);
            return new { items=rows.Take(size).Select(x=>new{x.Id,x.Name,x.CampusId,courseIds=assignments.Where(s=>s.ClassId==x.Id).Select(s=>s.CourseId)}),page=number,pageSize=size,hasMore=rows.Length>size };
        });
        app.MapGet("/api/automation/v1/sessions/{id:guid}", async (Guid id, Data db, Access a, CancellationToken ct) => {
            var session = await db.ClassSessions.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct) ?? throw new ApiException(404, "课次不存在");
            await a.Class(session.ClassId, session.CourseId);
            var marks = await db.ClassSessionMarks.AsNoTracking().Where(x => x.ClassSessionId == id).ToArrayAsync(ct);
            var ids = marks.Select(x => x.StudentId).ToArray();
            var students = await db.Students.AsNoTracking().Where(x => ids.Contains(x.Id) && x.ClassId == session.ClassId).OrderBy(x => x.Code).Select(x => new { x.Id, x.Name }).ToArrayAsync(ct);
            var questions = await db.Questions.AsNoTracking().Where(x => x.HandoutId == session.HandoutId).OrderBy(x => x.Order).Select(x => new { x.Id, x.Label, x.Order }).ToArrayAsync(ct);
            return new { session, students, questions, marks = marks.Select(x => new { x.StudentId, x.Revision, x.Reviewed, questionIds = Json.Read<Guid[]>(x.WrongIdsJson), notes = Json.Read<Dictionary<Guid,string>>(x.NotesJson) }) };
        });
        app.MapGet("/api/automation/v1/tasks/{kind}/{id:guid}", TaskStatus);
    }
    static async Task<AutomationTaskStatus> TaskStatus(string kind, Guid id, Data db, Access a, HttpContext ctx, CancellationToken ct)
    {
        string state; string? error; int? revision = null; bool review;
        switch (kind) {
            case "export": var d = await a.Document(id); state = d.State; error = d.Error; review = false; break;
            case "handout": var h = await a.Handout(id); var lecture = await a.Lecture(h.LectureId); Guard.That(a.User.Role == "admin" || h.State == "published" || lecture.ClassId.HasValue, "讲义尚未发布", 403); state = h.State; error = h.Error; revision = h.Revision; review = state == "draft"; break;
            case "book": var b = await db.BookImports.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct) ?? throw new ApiException(404, "导入任务不存在"); await a.Class(b.ClassId,b.CourseId); state = b.State; error = b.Error; revision = b.Revision; review = state == "review"; break;
            case "support": var s = await db.LearningArtifacts.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct) ?? throw new ApiException(404, "学习资料不存在"); await a.Class(s.ClassId,s.CourseId); state = s.State; error = s.Error; revision = s.Revision; review = state == "draft"; break;
            case "print": var j = await db.PrintJobs.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct) ?? throw new ApiException(404, "打印任务不存在"); await a.Document(j.DocumentId); state = j.State; error = j.Detail; review = state == "uncertain"; break;
            default: throw new ApiException(400, "kind 必须为 book、handout、export、print 或 support");
        }
        var terminal = review || state is "ready" or "published" or "split" or "approved" or "failed" or "submitted" or "uncertain";
        ctx.Response.Headers.RetryAfter = terminal ? "0" : "2";
        return new(id,kind,state,terminal,review,revision,error,terminal ? 0 : 2);
    }
}
