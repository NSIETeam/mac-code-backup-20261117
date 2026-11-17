using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using Siyue;
// A dependency-free Docker probe; it checks the API and its database connection.
if(args.Contains("--healthcheck")) {
    try {
        var raw=Environment.GetEnvironmentVariable("ASPNETCORE_URLS")?.Split(';')[0]??"http://127.0.0.1:5082";
        var target=new UriBuilder(raw.Replace("0.0.0.0","127.0.0.1").Replace("*","127.0.0.1").Replace("+","127.0.0.1")){Path="/health"};
        using var client=new HttpClient(new HttpClientHandler{UseProxy=false}){Timeout=TimeSpan.FromSeconds(4)};
        using var response=await client.GetAsync(target.Uri);Environment.ExitCode=response.IsSuccessStatusCode?0:1;
    }catch{Environment.ExitCode=1;}
    return;
}
var indexHandoutReuse = args.Contains("--index-handout-reuse");
var indexingLimit = 500;
if (indexHandoutReuse) {
    var limits = args.Where(x => x.StartsWith("--limit=", StringComparison.Ordinal)).ToArray();
    if (args.Contains("--migrate") || args.Contains("--worker") || args.Contains("--build-question-bank-index") || limits.Length > 1 ||
        limits.Length == 1 && (!int.TryParse(limits[0][8..], out indexingLimit) || indexingLimit is < 1 or > 500)) {
        Console.WriteLine(Json.Write(new { error = "索引只能单独运行；上限使用 --limit=1 至 --limit=500" }));
        Environment.ExitCode = 2; return;
    }
}
var builder = WebApplication.CreateBuilder(args);
if (builder.Configuration["Research:ConfigurationFile"] is string researchConfig && researchConfig.Length > 0)
    builder.Configuration.AddJsonFile(Path.GetFullPath(researchConfig), optional: false, reloadOnChange: false).AddEnvironmentVariables();
if (indexHandoutReuse) builder.Logging.ClearProviders();
if (args.Contains("--build-question-bank-index")) {
    new LocalQuestionBank(builder.Configuration,builder.Environment,Microsoft.Extensions.Logging.Abstractions.NullLogger<LocalQuestionBank>.Instance).BuildSearchIndexes();
    return;
}
if(builder.Configuration.GetValue<bool>("AcademicPilot:IsolatedHomeworkLab")){builder.Configuration["RunWorker"]="false";builder.Configuration["Database:Initialize"]="false";}
var miniappFeaturesOnly = builder.Configuration.GetValue<bool>("MiniappFeatures:Dedicated");
if (miniappFeaturesOnly) {
    if (args.Contains("--migrate") || args.Contains("--worker")) throw new InvalidOperationException("小程序配套服务不允许运行迁移或打印任务");
    builder.Configuration["RunWorker"] = "false";
    builder.Configuration["Database:Initialize"] = "false";
    builder.Configuration["AcademicPilot:HomeworkWorkerEnabled"] = "false";
    builder.Configuration["QuestionBank:IntakeWorkerEnabled"] = "false";
}
var questionBankOnly = builder.Configuration.GetValue<bool>("QuestionBank:Dedicated");
if (questionBankOnly) {
    if (args.Contains("--migrate") || args.Contains("--worker")) throw new InvalidOperationException("独立题库服务不允许运行迁移或打印任务");
    builder.Configuration["RunWorker"] = "false";
    builder.Configuration["Database:Initialize"] = "false";
    builder.Configuration["AcademicPilot:HomeworkWorkerEnabled"]="false";
}
// The headless macOS converter does not automatically see the desktop font catalog.
// Use the same bundled CJK and math fonts as the Linux deployment for local previews.
var bundledFonts = Path.GetFullPath(Path.Combine(builder.Environment.ContentRootPath, "..", "deploy", "fonts"));
if (OperatingSystem.IsMacOS() && string.IsNullOrEmpty(builder.Configuration["Documents:FontDirectory"]) && Directory.Exists(bundledFonts))
    builder.Configuration["Documents:FontDirectory"] = bundledFonts;
// Allow a complete 50 MiB Word file plus multipart headers through the HTTP layer.
builder.WebHost.ConfigureKestrel(o => o.Limits.MaxRequestBodySize = questionBankOnly ? 262_144 : miniappFeaturesOnly ? 9 * 1024 * 1024 : 54_525_952);
builder.Services.AddDbContext<Data>(o =>
{
    if (builder.Configuration["Database:Provider"] == "sqlite") {
        if (!builder.Environment.IsDevelopment() && !builder.Environment.IsEnvironment("Testing")) throw new InvalidOperationException("SQLite 仅用于本地开发与测试");
        var sqlite = new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder(builder.Configuration.GetConnectionString("Database") ?? "Data Source=.local/siyue.db");
        if (sqlite.DataSource != ":memory:") { sqlite.DataSource = Path.GetFullPath(sqlite.DataSource); Directory.CreateDirectory(Path.GetDirectoryName(sqlite.DataSource)!); }
        o.UseSqlite(sqlite.ToString());
    } else o.UseNpgsql(builder.Configuration.GetConnectionString("Database") ?? throw new InvalidOperationException("请配置 PostgreSQL 连接"));
});
builder.Services.AddHttpClient("guangzhou-legacy").ConfigurePrimaryHttpMessageHandler(()=>new HttpClientHandler {AllowAutoRedirect=false,UseProxy=false}).RemoveAllLoggers();
builder.Services.AddSingleton<AutomationAdmission>();
builder.Services.AddHttpClient("wechat-parent",c=>c.Timeout=TimeSpan.FromSeconds(15)).RemoveAllLoggers();
builder.Services.AddScoped<IAcademicParentIdentity,AcademicParentWechatIdentity>();
builder.Services.AddScoped<IAcademicHomeworkGrader,AcademicVisionGrader>();
builder.Services.AddSingleton<CommunityQuestionStore>();
builder.Services.AddSingleton<QuestionBankIntake>();
builder.Services.AddHostedService<QuestionBankIntakeWorker>();
builder.Services.AddSingleton<IQuestionBank, LocalQuestionBank>();
builder.Services.AddResponseCompression(o=>{o.EnableForHttps=true;});
builder.Services.AddSingleton<HomeworkQuestionBank>();
builder.Services.AddScoped<HomeworkQueueProcessor>();
if(builder.Configuration.GetValue<bool>("AcademicPilot:HomeworkWorkerEnabled")) builder.Services.AddHostedService<HomeworkGradingLoop>();
builder.Services.AddAutomationOpenApi();
builder.Services.AddTeachingToolLimits();
builder.Services.AddSingleton<TeachingJobStore>();
builder.Services.AddSingleton<PrintExceptionStore>();
builder.Services.AddHostedService<TeachingJobWorker>();
builder.Services.AddHttpContextAccessor(); builder.Services.AddHttpClient("wechat").RemoveAllLoggers(); builder.Services.AddHttpClient("learning-ai").ConfigurePrimaryHttpMessageHandler(() => ExternalModelClient.Handler()).RemoveAllLoggers(); builder.Services.AddOpenApi();
builder.Services.AddRateLimiter(o => o.AddPolicy("auth", ctx => System.Threading.RateLimiting.RateLimitPartition.GetFixedWindowLimiter(ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new System.Threading.RateLimiting.FixedWindowRateLimiterOptions { PermitLimit = 30, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 })));
builder.Services.AddRateLimiter(o => o.AddPolicy("homework-parent", ctx => System.Threading.RateLimiting.RateLimitPartition.GetFixedWindowLimiter(ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new System.Threading.RateLimiting.FixedWindowRateLimiterOptions { PermitLimit = 40, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 })));
builder.Services.AddSingleton<QuestionBankPapers>();
builder.Services.AddSingleton<ResearchStore>();
builder.Services.AddSingleton<UsageOrganizationStore>();
builder.Services.AddScoped<ResearchImport>();
builder.Services.AddSingleton<XkwConnectionSettings>();
builder.Services.AddSingleton<IResearchQuestions, ResearchXkw>();
builder.Services.AddHostedService<ResearchWorker>();
builder.Services.AddHttpClient("research-xkw", c => c.Timeout = TimeSpan.FromSeconds(25)).ConfigurePrimaryHttpMessageHandler(() => ExternalModelClient.Handler()).RemoveAllLoggers();
builder.Services.AddScoped<FormulaSettings>(); builder.Services.AddScoped<LearningModelSettings>(); builder.Services.AddScoped<Access>(); builder.Services.AddScoped<Files>(); builder.Services.AddScoped<WordDocuments>(); builder.Services.AddScoped<DocumentWorker>(); builder.Services.AddScoped<PdfPreviews>();
builder.Services.AddSingleton<IObjects>(s => s.GetRequiredService<IConfiguration>()["Storage:Provider"] == "local" ? new LocalObjects(s.GetRequiredService<IConfiguration>()) : new S3Objects(s.GetRequiredService<IConfiguration>()));
builder.Services.Configure<Microsoft.AspNetCore.Http.Features.FormOptions>(o => o.MultipartBodyLengthLimit = 52_428_800);
builder.Services.AddExceptionHandler(o => o.ExceptionHandler = async ctx => {
    var e = ctx.Features.Get<Microsoft.AspNetCore.Diagnostics.IExceptionHandlerFeature>()!.Error;
    ctx.Response.StatusCode = e is ApiException a ? a.Status : e is BadHttpRequestException bad ? bad.StatusCode : e is DbUpdateException ? 409 : 500;
    if (ctx.Response.StatusCode == 429) ctx.Response.Headers.RetryAfter = "300";
    var message = e is ApiException ? e.Message : e is BadHttpRequestException ? "请求格式无效" : e is DbUpdateException ? "数据已变更或存在重复记录，请刷新后重试" : "服务处理失败，请稍后重试";
    ctx.RequestServices.GetRequiredService<ILoggerFactory>().CreateLogger("Errors").LogError(e, "Request {Path} failed", ctx.GetEndpoint()?.DisplayName);
    var code = ctx.Response.StatusCode switch { 400 => "INVALID_ARGUMENT", 401 => "UNAUTHENTICATED", 403 => "FORBIDDEN", 404 => "NOT_FOUND", 409 => "CONFLICT", 428 => "PASSWORD_CHANGE_REQUIRED", 429 => "RATE_LIMITED", _ => "INTERNAL_ERROR" };
    await ctx.Response.WriteAsJsonAsync(new { message, code, traceId = ctx.TraceIdentifier });
});
builder.Services.AddProblemDetails();
builder.Services.ConfigureHttpJsonOptions(o => { o.SerializerOptions.RespectNullableAnnotations = true; o.SerializerOptions.RespectRequiredConstructorParameters = true; });
if (!indexHandoutReuse && (builder.Configuration.GetValue<bool>("RunWorker") || args.Contains("--worker"))) { builder.Services.AddHostedService<WorkerLoop>(); builder.Services.AddHostedService<PrintHealthLoop>(); }
// Only the loopback reverse proxy may supply the original client address.
builder.Services.Configure<Microsoft.AspNetCore.Builder.ForwardedHeadersOptions>(o => { o.ForwardedHeaders = Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor | Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto; if (System.Net.IPAddress.TryParse(builder.Configuration["TrustedProxy"], out var proxy)) o.KnownProxies.Add(proxy); });
var protection = builder.Services.AddDataProtection().SetApplicationName("SiyueMiniapp");
if (builder.Configuration["DataProtection:Directory"] is string keyPath) protection.PersistKeysToFileSystem(new DirectoryInfo(keyPath));
var app = builder.Build();
// Maintenance only: no HTTP host, hosted workers, schema initialization or migrations.
if (indexHandoutReuse) {
    using var cancelled = new CancellationTokenSource();
    ConsoleCancelEventHandler onCancel = (_, e) => { e.Cancel = true; cancelled.Cancel(); };
    Console.CancelKeyPress += onCancel;
    try {
        await using var scope = app.Services.CreateAsyncScope();
        var result = await HandoutReuse.IndexExisting(scope.ServiceProvider.GetRequiredService<Data>(), scope.ServiceProvider.GetRequiredService<IObjects>(), cancelled.Token, indexingLimit);
        Console.WriteLine(Json.Write(result));
    } catch (OperationCanceledException) { Console.WriteLine(Json.Write(new { cancelled = true })); Environment.ExitCode = 130; }
    catch { Console.WriteLine(Json.Write(new { error = "讲义缓存索引未完成，原有资料未改动" })); Environment.ExitCode = 1; }
    finally { Console.CancelKeyPress -= onCancel; await app.DisposeAsync(); }
    return;
}
app.UseResponseCompression(); app.UseForwardedHeaders(); app.UseExceptionHandler();
// An isolated homework acceptance instance never exposes legacy writes or printing.
if(app.Configuration.GetValue<bool>("AcademicPilot:IsolatedHomeworkLab"))app.Use(async(ctx,next)=>{
 var path=ctx.Request.Path.Value??"";
 var allowed=path=="/health"||path.StartsWith("/academic-pilot/collect/")||path.StartsWith("/api/academic-pilot/")
 ||new[]{"/academic-pilot/","/academic-pilot/index.html","/academic-pilot/app.js","/academic-pilot/style.css","/academic-pilot/homework-workflow.js"}.Contains(path)||path.StartsWith("/academic-pilot/math/")
 ||ctx.Request.Method=="GET"&&new[]{"/api/workspace","/api/me","/api/auth/status"}.Contains(path)
 ||ctx.Request.Method=="POST"&&new[]{"/api/auth/password","/api/logout"}.Contains(path);
 if(!allowed){ctx.Response.StatusCode=404;return;}ctx.Response.Headers["X-Robots-Tag"]="noindex, nofollow";await next(ctx);
});
// The optional recommendation sidecar exposes only read-only lookup paths, never legacy writes.
if (app.Configuration.GetValue<bool>("QuestionBank:Dedicated")) {
    app.Configuration["RunWorker"]="false";
    app.Configuration["Database:Initialize"]="false";
    app.Use(async (ctx, next) => {
    var path = ctx.Request.Path.Value ?? "";
    var allowed = ctx.Request.Method == "GET" && (path is "/health" or "/api/question-bank" or "/api/question-bank/knowledge" or "/api/question-bank/source" or "/api/question-bank/uploads/mine" or "/api/question-bank/intakes" or "/api/question-bank/learner-groups" or "/api/question-bank/learners" or "/question-bank")
        || ctx.Request.Method=="GET" && (path.StartsWith("/question-bank/",StringComparison.Ordinal)||System.Text.RegularExpressions.Regex.IsMatch(path,@"\A/api/question-bank/intakes/[a-f0-9-]{36}(?:/assets/[a-zA-Z0-9.-]+)?\z"))
        || ctx.Request.Method == "POST" && path is "/api/question-bank/recommendations" or "/api/question-bank/uploads" or "/api/question-bank/intakes" or "/api/question-bank/learners/recommend" or "/api/question-bank/papers"
        || ctx.Request.Method=="POST" && System.Text.RegularExpressions.Regex.IsMatch(path,@"\A/api/question-bank/intakes/[a-f0-9-]{36}/publish\z")
        || ctx.Request.Method == "PUT" && System.Text.RegularExpressions.Regex.IsMatch(path,@"\A/api/question-bank/uploads/community-[a-f0-9]{32}\z");
    allowed=allowed || ctx.Request.Method=="GET" && System.Text.RegularExpressions.Regex.IsMatch(path,@"\A/api/question-bank/papers/[a-f0-9-]{36}(?:/export)?\z");
    if((path=="/api/question-bank/uploads"||path.StartsWith("/api/question-bank/intakes",StringComparison.Ordinal))&&ctx.Request.Method=="POST"){
        var max=path=="/api/question-bank/intakes"?22*1024*1024:2097152;
        if(ctx.Request.ContentLength>max)throw new ApiException(413,"上传内容超过大小限制");
        var size=ctx.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>();if(size is{IsReadOnly:false})size.MaxRequestBodySize=max;
    }
    if (!allowed) { ctx.Response.StatusCode = 404; return; }
    await next(ctx);
    });
}
// A private, opt-in pilot: no changes to existing pages, APIs or print workers.
app.Use(async (ctx, next) => {
    if (AcademicPilot.IsPath(ctx.Request.Path) && !ctx.RequestServices.GetRequiredService<IConfiguration>().GetValue<bool>("AcademicPilot:Enabled")) {
        ctx.Response.StatusCode = 404; return;
    }
    await next(ctx);
});
app.Use(async (ctx, next) => {
    ctx.Response.Headers["X-Request-Id"] = ctx.TraceIdentifier;
    if (!Automation.UsesCredential(ctx)) { await next(ctx); return; }
    using var lease = ctx.RequestServices.GetRequiredService<AutomationAdmission>().Global();
    if (!lease.IsAcquired) { await Automation.RejectBusy(ctx); return; }
    await next(ctx);
});
app.Use(async (ctx, next) => {
    ctx.Response.Headers.CacheControl = "no-store";
    if (miniappFeaturesOnly && ctx.GetEndpoint() is null) {
        ctx.Response.StatusCode = 404; return;
    }
    if(ctx.Request.Path.StartsWithSegments("/api/v2/agent") && ctx.Request.Method=="POST")ctx.Request.EnableBuffering(16384,32768);
    if (ctx.Request.Path.StartsWithSegments("/api") && !ctx.Request.Path.StartsWithSegments("/api/auth") && !ctx.Request.Path.StartsWithSegments("/api/v2/agent")) {
        var raw = ctx.Request.Headers.Authorization.ToString(); Guard.That(raw.StartsWith("Bearer "), "请先登录", 401);
        var db = ctx.RequestServices.GetRequiredService<Data>(); var hash = Secrets.Hash(raw[7..]);
        if (Automation.UsesCredential(ctx)) {
            ctx.Items["actor"] = await Automation.Authenticate(ctx, db, raw[7..]);
            using var ownerLease = ctx.RequestServices.GetRequiredService<AutomationAdmission>().Owner(((Actor)ctx.Items["actor"]!).Id);
            if (!ownerLease.IsAcquired) { await Automation.RejectBusy(ctx); return; }
            using var rateLease = ctx.RequestServices.GetRequiredService<AutomationAdmission>().Rate(((Actor)ctx.Items["actor"]!).Id);
            if (!rateLease.IsAcquired) { await Automation.RejectBusy(ctx); return; }
            var uploads = ctx.Items["automationOperation"] as string is "upload_book" or "upload_handout" or "upload_print_file" or "import_roster";
            using var uploadLease = uploads ? ctx.RequestServices.GetRequiredService<AutomationAdmission>().Upload() : null;
            if (uploadLease is { IsAcquired: false }) { await Automation.RejectBusy(ctx); return; }
            var key = (AutomationCredential)ctx.Items["automationCredential"]!;
            var operation = ctx.Items["automationOperation"];
            // Structured fields only: never log credentials, request bodies or signed file URLs.
            ctx.Response.OnCompleted(() => {
                ctx.RequestServices.GetRequiredService<ILoggerFactory>().CreateLogger("Automation").LogInformation("Agent {CredentialId} staff {StaffId} operation {Operation} status {StatusCode} trace {TraceId}", key.Id, key.StaffId, operation, ctx.Response.StatusCode, ctx.TraceIdentifier);
                return Task.CompletedTask;
            });
            await next(ctx); return;
        }
        var s = await db.Sessions.SingleOrDefaultAsync(x => x.TokenHash == hash && x.ExpiresAt > Clock.Now);
        Guard.That(s?.StaffId != null, "登录已过期或尚未绑定身份", 401);
        var u = await db.Staff.SingleOrDefaultAsync(x => x.Id == s!.StaffId && x.Enabled); Guard.That(u != null, "账号已停用", 401);
        ctx.Items["actor"] = new Actor(u!.Id, u.Name, u.Role);
        if(ctx.Request.Path!="/api/account/password"&&ctx.Request.Path!="/api/logout"&&ctx.Request.Path!="/api/me")
            Guard.That(!await db.PasswordAccounts.AnyAsync(p=>p.StaffId==u.Id&&p.MustChangePassword),"首次登录或密码已重置，请先修改密码后再使用工作台",428);
        TeacherUsage.Track(ctx, u);
    }
    await next(ctx);
});
app.MapGet("/health", async (Data db) => { await db.Database.ExecuteSqlRawAsync("SELECT 1"); return Results.Ok(new { status = "ok", application = "siyue-miniapp" }); });
AcademicRollCall.MapEndpoints(app.MapGroup("/api/attendance"));
app.MapStudentFollowUps();
app.MapOutreachDiagnosis();
app.MapGradeOperationsCalendar();
if (miniappFeaturesOnly) {
    await app.RunAsync();
    return;
}
app.UseRateLimiter();
app.UseDefaultFiles();
app.UseStaticFiles(new StaticFileOptions { OnPrepareResponse = context => {
    context.Context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    context.Context.Response.Headers["Referrer-Policy"] = "no-referrer";
    context.Context.Response.Headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; frame-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'";
} });
app.MapGet("/files/{token}", (string token, Files f, CancellationToken ct) => f.Read(token, ct));
app.MapAcademicPilot();
app.MapQuestionBank();
QuestionBankLearners.Map(app);
QuestionBankPapers.Map(app);
app.MapResearch();
XkwConnectionSettings.MapEndpoints(app);
app.MapTeacherUsage();
app.MapOpenApi(); app.MapAutomation(); app.MapAuth(); app.MapCatalog(); app.MapLearning(); app.MapPdfRegions(); app.MapDocumentRecovery(); app.MapBookImports(); app.MapBookPageSplits(); app.MapHandoutLibrary(); app.MapStudentHistory(); app.MapLearningSupport(); app.MapLearningModelSettings(); app.MapFormulaSettings(); app.MapTeacherWorkspace(); app.MapPrintFiles(); app.MapMobileUploads(); app.MapInstallerDownloads(); app.MapPrinting();
app.MapPrintExceptions(); app.MapGuangzhouPrinting(); app.MapDeviceConnections(); app.MapPdfPreviews(); app.MapClassSessions();
if (builder.Configuration.GetValue<bool>("Database:Initialize")) { await using var scope = app.Services.CreateAsyncScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); await db.Database.EnsureCreatedAsync(); await DevelopmentSchema.Upgrade(db); }
if (args.Contains("--migrate")) { await using var scope = app.Services.CreateAsyncScope(); await scope.ServiceProvider.GetRequiredService<Data>().Database.MigrateAsync(); return; }
await app.RunAsync();
public partial class Program { }
