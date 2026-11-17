using Microsoft.EntityFrameworkCore;
using Siyue.Printing;
namespace Siyue;
public record PrintInput(Guid[] DocumentIds, Guid PrinterId, int Copies, bool Duplex, string RequestKey, bool WaitForDocuments = false);
public record ReprintInput(string RequestKey, bool AcknowledgeUncertain = false);
public record BindDeviceInput(string Code, string Name);
public record QueueInfo(string QueueName, bool Duplex, string Status = "unknown", long RawStatus = 0, int JobCount = 0, string Detail = "", string Driver = "", string Port = "", bool? DeviceReachable = null, DateTimeOffset? DeviceCheckedAt = null);
public record HeartbeatInput(QueueInfo[] Printers, bool Complete = true, string? Error = null, string AgentVersion = "", string MachineName = "", string? MonitorVersion = null, string? MonitorError = null, ExecutionSnapshot[]? Executions = null);
public record AgentReport(string ClaimToken, string State, string? Detail = null);
public record PrinterUpdate(string Name, bool Enabled);
public static class PrintingEndpoints
{
    public static void MapPrinting(this WebApplication app)
    {
        app.MapGet("/api/printers", async (Data db, Access a) => {
            _ = a.User; // Shared output devices do not grant access to another teacher's classes.
            return await (from p in db.Printers join d in db.Devices on p.DeviceId equals d.Id where p.Enabled && !p.Suppressed && d.Enabled && !db.GuangzhouDevices.Any(g => g.Id == d.Id && !g.Enrolled) select new { p.Id, p.Name, p.CampusId, p.Duplex, d.LastSeen, p.Status, p.StatusDetail, p.StatusAt, p.JobCount, p.QueueName, p.DeviceReachable, p.DeviceCheckedAt, deviceName = d.Name,
                serverQueuedCount = db.PrintJobs.Count(j => j.PrinterId == p.Id && (j.State == "queued" || j.State == "preparing")),
                serverActiveCount = db.PrintJobs.Count(j => j.PrinterId == p.Id && (j.State == "claimed" || j.State == "submitting")),
                loadObservedAt = Clock.Now }).ToListAsync();
        });
        app.MapPost("/api/print-jobs", async (PrintInput r, Data db, Access a, IObjects objects) => await Create(r, null, db, a, objects));
        app.MapGet("/api/print-jobs", async (Data db, Access a) => {
            var u = a.User; var scopes = db.Assignments.Where(x => x.StaffId == u.Id);
            return await (from j in db.PrintJobs join d in db.Documents on j.DocumentId equals d.Id join p in db.Printers on j.PrinterId equals p.Id join device in db.Devices on p.DeviceId equals device.Id
                where u.Role == "admin" || d.Kind == "file" && !d.ClassId.HasValue && d.StaffId == u.Id || scopes.Any(x => x.ClassId == d.ClassId && x.CourseId == d.CourseId)
                orderby j.CreatedAt descending select new { j.Id, j.BatchId, j.DocumentId, d.StudentName, printerName = p.Name, j.State, j.Copies, j.Duplex, j.Detail, j.ReprintOf, j.CreatedAt, device.LastSeen, j.ExecutionJson, j.ExecutionAt, device.MonitorLastSeen, device.MonitorError, p.Status, p.StatusDetail, p.DeviceReachable, p.DeviceCheckedAt }).Take(200).ToListAsync();
        });
        app.MapPost("/api/print-jobs/{id:guid}/reprint", async (Guid id, ReprintInput r, Data db, Access a, IObjects objects) => {
            var j = await db.PrintJobs.FindAsync(id) ?? throw new ApiException(404, "打印任务不存在"); await a.Document(j.DocumentId);
            var execution = MonitorTelemetry.Read(j.ExecutionJson);
            Guard.That(execution == null || !ExecutionTelemetry.Active(execution) || j.ExecutionAt < Clock.Now.AddSeconds(-90), "电脑仍在处理这个打印任务，请等待结果后再补打", 409);
            Guard.That(j.State is "submitted" or "failed" or "uncertain", "原任务仍在处理中，请勿补打", 409);
            Guard.That(j.State != "uncertain" || r.AcknowledgeUncertain, "原任务可能已经出纸，请核实后确认补打", 409);
            return await Create(new PrintInput([j.DocumentId], j.PrinterId, j.Copies, j.Duplex, r.RequestKey), id, db, a, objects);
        });
        app.MapPost("/api/admin/device-bindings", async (CatalogInput r, Data db, Access a) => {
            a.Admin(); Guard.That(await db.Campuses.AnyAsync(x => x.Id == r.CampusId), "校区不存在");
            var code = Secrets.New()[..16]; db.Bindings.Add(new DeviceBinding { CampusId = r.CampusId, CodeHash = Secrets.Hash(code), ExpiresAt = Clock.Now.AddMinutes(30) }); await db.SaveChangesAsync(); return new { code, expiresInMinutes = 30 };
        });
        app.MapPut("/api/admin/devices/{id:guid}", async (Guid id, CatalogInput r, Data db, Access a) => {
            a.Admin(); await using var tx = await db.Database.BeginTransactionAsync();
            var d = await db.Devices.FindAsync(id) ?? throw new ApiException(404, "设备不存在");
            if (!string.IsNullOrWhiteSpace(r.Name)) d.Name = Guard.Name(r.Name);
            if (r.CampusId != Guid.Empty && r.CampusId != d.CampusId) {
                Guard.That(await db.Campuses.AnyAsync(c => c.Id == r.CampusId), "校区不存在", 404);
                Guard.That(!await db.PrintJobs.AnyAsync(j => j.DeviceId == id && (j.State == "preparing" || j.State == "queued" || j.State == "claimed" || j.State == "submitting" || j.State == "uncertain")), "请先处理完该设备的打印任务，再调整校区", 409);
                var printers = await db.Printers.Where(p => p.DeviceId == id).ToListAsync();
                var names = printers.Select(p => p.Name).ToArray();
                Guard.That(!await db.Printers.AnyAsync(p => p.DeviceId != id && p.CampusId == r.CampusId && names.Contains(p.Name)), "目标校区存在同名打印机，请先修改名称", 409);
                d.CampusId = r.CampusId; foreach (var printer in printers) printer.CampusId = r.CampusId;
            }
            d.Enabled = r.Enabled; db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "device.edit", TargetId = id });
            await db.SaveChangesAsync(); await tx.CommitAsync(); return Results.NoContent();
        });
        app.MapDelete("/api/admin/printers/{id:guid}", CatalogRemoval.Printer);
        app.MapPut("/api/admin/printers/{id:guid}", async (Guid id, PrinterUpdate r, Data db, Access a) => {
            a.Admin(); var p = await db.Printers.SingleOrDefaultAsync(p => p.Id == id && !p.Suppressed) ?? throw new ApiException(404, "打印机不存在或已删除"); var name = Guard.Name(r.Name);
            Guard.That(!await db.Printers.AnyAsync(other => other.Id != id && other.CampusId == p.CampusId && other.Name == name), "本校区已有同名打印机", 409);
            p.Name = name; p.Enabled = r.Enabled; db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "printer.edit", TargetId = id }); await db.SaveChangesAsync(); return Results.NoContent();
        });
        app.MapPost("/agent/bind", async (BindDeviceInput r, Data db) => {
            var hash = Secrets.Hash(r.Code.Trim()); await using var tx = await db.Database.BeginTransactionAsync();
            var binding = await db.Bindings.SingleOrDefaultAsync(x => x.CodeHash == hash && !x.Used && x.ExpiresAt > Clock.Now) ?? throw new ApiException(403, "绑定码无效或已过期");
            Guard.That(await db.Campuses.AnyAsync(c => c.Id == binding.CampusId), "校区已删除", 403);
            Guard.That(await db.Bindings.Where(x => x.Id == binding.Id && !x.Used).ExecuteUpdateAsync(s => s.SetProperty(x => x.Used, true)) == 1, "绑定码已使用", 409);
            var token = Secrets.New(); var device = new Device { CampusId = binding.CampusId, Name = Guard.Name(r.Name), TokenHash = Secrets.Hash(token), LastSeen = Clock.Now };
            db.Devices.Add(device); await db.SaveChangesAsync(); await tx.CommitAsync(); return new { device.Id, device.CampusId, token };
        }).RequireRateLimiting("auth");
        var agent = app.MapGroup("/agent").AddEndpointFilter(PrintAgentDispatch.Authenticate);
        app.MapDeviceOperations(agent);
        agent.MapGet("/installation-profile", async (Data db, HttpContext ctx) => {
            var d = DeviceFrom(ctx); var campus = await db.Campuses.FindAsync(d.CampusId);
            var printers = await db.Printers.Where(p => p.DeviceId == d.Id).Select(p => new { p.QueueName, p.Name, p.Enabled, p.Driver }).ToListAsync();
            return Results.Ok(new { campusName = campus?.Name ?? "", printers = printers
                .Where(p => !InstallationProfile.IsMicrosoftPdfPrinter(p.QueueName, p.Driver))
                .Select(p => new { p.QueueName, p.Name, p.Enabled }) });
        });
        agent.MapPost("/installation-profile", async (InstallationProfile input, Data db, HttpContext ctx) => {
            InstallationProfile profile; try { profile = input.Validate(); } catch (ArgumentException e) { throw new ApiException(400, e.Message); }
            var d = DeviceFrom(ctx); var campus = await db.Campuses.FindAsync(d.CampusId);
            Guard.That(campus?.Name.Trim() == profile.CampusName, "所属校区与管理员分配的校区不一致", 409);
            var printers = await db.Printers.Where(p => p.DeviceId == d.Id).ToListAsync();
            var printer = printers.SingleOrDefault(p => p.QueueName.Equals(profile.QueueName, StringComparison.OrdinalIgnoreCase)) ?? throw new ApiException(404, "本机尚未上报所选打印队列");
            Guard.That(!InstallationProfile.IsMicrosoftPdfPrinter(printer.QueueName, printer.Driver), "请选择实体打印机");
            var registration = await db.Pairings.SingleOrDefaultAsync(p => p.DeviceId == d.Id && p.Automatic);
            var initial = registration?.RequestedProfileJson == null ? null : System.Text.Json.JsonSerializer.Deserialize<InstallationProfile>(registration.RequestedProfileJson);
            // A delayed first sync or reinstallation must not undo an administrator's rename.
            if (printer.Name != profile.PrinterAlias && profile != initial) {
                printer.Name = profile.PrinterAlias;
                db.Audits.Add(new Audit { Action = "device.printer-alias", TargetId = printer.Id });
                await db.SaveChangesAsync();
            }
            return Results.Ok(new { printer.Id, printer.Name, printer.Enabled, campusName = campus!.Name });
        });
        agent.MapPost("/heartbeat", async (HeartbeatInput r, Data db, HttpContext ctx) => {
            Guard.That(r.Printers.Length <= 100 && r.AgentVersion.Length <= 50 && r.MachineName.Length <= 120 && (r.Error?.Length ?? 0) <= 500, "状态数据过长");
            Guard.That(r.Printers.Select(x => x.QueueName).Distinct(StringComparer.OrdinalIgnoreCase).Count() == r.Printers.Length, "打印队列重复");
            var d = DeviceFrom(ctx); var now = Clock.Now; d.LastSeen = now; d.AgentVersion = r.AgentVersion; d.MachineName = r.MachineName; d.ProbeError = r.Error;
            await MonitorTelemetry.Update(db,d,r,now);
            var known = await db.Printers.Where(x => x.DeviceId == d.Id).ToListAsync();
            foreach (var queue in r.Printers) {
                if (InstallationProfile.IsMicrosoftPdfPrinter(queue.QueueName, queue.Driver)) {
                    var existing = known.SingleOrDefault(x => x.QueueName.Equals(queue.QueueName, StringComparison.OrdinalIgnoreCase));
                    if (existing != null) {
                        existing.Enabled = false;
                        existing.Suppressed = true;
                    }
                    continue;
                }
                Guard.That(queue.QueueName.Length is > 0 and <= 200 && queue.Detail.Length <= 500 && queue.Driver.Length <= 200 && queue.Port.Length <= 500 && queue.JobCount >= 0 && queue.RawStatus is >= 0 and <= uint.MaxValue, "无效的打印队列状态");
                Guard.That(queue.Status is "unknown" or "ready" or "busy" or "warning" or "offline" or "paused" or "error", "打印机状态无效");
                var p = known.SingleOrDefault(x => x.QueueName.Equals(queue.QueueName, StringComparison.OrdinalIgnoreCase));
                if (p == null) { p = new Printer { DeviceId = d.Id, CampusId = d.CampusId, QueueName = queue.QueueName, Name = queue.QueueName, Enabled = false }; db.Printers.Add(p); }
                p.Duplex = queue.Duplex; p.Status = queue.Status; p.StatusDetail = queue.Detail; p.StatusAt = now; p.JobCount = queue.JobCount; p.RawStatus = queue.RawStatus; p.Driver = queue.Driver; p.Port = queue.Port;
                if (queue.DeviceCheckedAt != null) { Guard.That(queue.DeviceCheckedAt <= now.AddMinutes(2), "网络检查时间无效"); p.DeviceReachable = queue.DeviceReachable; p.DeviceCheckedAt = queue.DeviceCheckedAt; }
            }
            foreach (var p in known.Where(p => !r.Printers.Any(q => q.QueueName.Equals(p.QueueName, StringComparison.OrdinalIgnoreCase)))) {
                p.Status = r.Complete ? "missing" : "unknown"; p.StatusDetail = r.Complete ? "控制电脑已找不到此打印队列" : "打印机状态读取失败，等待重试"; p.StatusAt = now; p.JobCount = 0;
            }
            await db.SaveChangesAsync(); return Results.Ok(new { serverTime = Clock.Now });
        });
        agent.MapPost("/claim", async (Data db, Files files, HttpContext ctx) => {
            var device = DeviceFrom(ctx); var now = Clock.Now;
            if (PrintAgentDispatch.HasExpired(ctx)) await PrintAgentDispatch.RecoverLeases(db, now, device.Id, ctx.RequestAborted);
            var requireFresh = !string.IsNullOrEmpty(device.AgentVersion); var freshAfter = now.AddSeconds(-45);
            var candidates = await (from job in db.PrintJobs join p in db.Printers on job.PrinterId equals p.Id
                where job.DeviceId == device.Id && job.State == "queued" && p.DeviceId == device.Id && p.Enabled && !p.Suppressed && p.Status != "missing" && p.Status != "offline" && p.Status != "paused" && p.Status != "error"
                    && (p.DeviceReachable != false || p.DeviceCheckedAt < freshAfter)
                    && (!requireFresh || p.Status != "unknown" && p.StatusAt > freshAfter)
                orderby job.CreatedAt select job).Take(10).ToListAsync();
            foreach (var j in candidates) {
                var printer = await db.Printers.SingleOrDefaultAsync(x => x.Id == j.PrinterId && x.DeviceId == device.Id && x.Enabled);
                if (printer == null || printer.Status is "missing" or "offline" or "paused" or "error") continue;
                if (!string.IsNullOrEmpty(device.AgentVersion) && (printer.Status == "unknown" || printer.StatusAt == null || printer.StatusAt < now.AddSeconds(-45))) continue;
                var token = Secrets.New(); var until = now.AddMinutes(5);
                var won = await db.PrintJobs.Where(x => x.Id == j.Id && x.State == "queued").ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "claimed").SetProperty(x => x.ClaimToken, token).SetProperty(x => x.LeaseUntil, until));
                if (won != 1) continue;
                var doc = await db.Documents.FindAsync(j.DocumentId) ?? throw new ApiException(409, "文档缺失");
                var url = await files.Grant(doc.PdfKey, "application/pdf", j.Id + ".pdf", deviceId: device.Id);
                return Results.Ok(new { j.Id, claimToken = token, leaseUntil = until, printer.QueueName, j.Copies, j.Duplex, pdfUrl = url, sha256 = doc.PdfHash });
            }
            return Results.NoContent();
        }).WithMetadata(new PrintAgentPoll(PrintAgentQueue.Jobs));
        agent.MapPost("/jobs/{id:guid}/begin", async (Guid id, AgentReport r, Data db, HttpContext ctx) => {
            var d = DeviceFrom(ctx); var now = Clock.Now;
            var changed = await db.PrintJobs.Where(x => x.Id == id && x.DeviceId == d.Id && x.ClaimToken == r.ClaimToken && x.State == "claimed" && x.LeaseUntil > now).ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "submitting").SetProperty(x => x.LeaseUntil, now.AddMinutes(10)));
            Guard.That(changed == 1, "任务认领已失效；禁止执行打印", 409); return Results.NoContent();
        });
        agent.MapPost("/jobs/{id:guid}/report", async (Guid id, AgentReport r, Data db, HttpContext ctx) => {
            var d = DeviceFrom(ctx); Guard.That(r.State is "submitted" or "uncertain" or "failed", "状态无效");
            var j = await db.PrintJobs.SingleOrDefaultAsync(x => x.Id == id && x.DeviceId == d.Id && x.ClaimToken == r.ClaimToken) ?? throw new ApiException(409, "任务认领已失效");
            if (j.State == r.State) return Results.NoContent();
            Guard.That(j.State is "claimed" or "submitting" or "uncertain", "任务状态不能回退", 409);
            Guard.That(r.State != "submitted" || j.State != "claimed", "尚未获准提交打印", 409);
            j.State = r.State; j.Detail = r.Detail?[..Math.Min(r.Detail.Length, 500)]; j.LeaseUntil = null; await db.SaveChangesAsync(); return Results.NoContent();
        });
    }
    static Device DeviceFrom(HttpContext ctx) => (Device)ctx.Items["device"]!;
    public static Task Expire(Data db, DateTimeOffset now) => PrintAgentDispatch.RecoverLeases(db, now);
    static async Task<IResult> Create(PrintInput r, Guid? reprintOf, Data db, Access a, IObjects objects)
    {
        Guard.That(r.DocumentIds.Length is > 0 and <= 100 && r.DocumentIds.Distinct().Count() == r.DocumentIds.Length, "请选择 1–100 份不同的学生文档");
        Guard.That(r.Copies is >= 1 and <= 99 && r.RequestKey.Length is >= 8 and <= 128, "打印份数或请求标识无效");
        await using var tx = await db.Database.BeginTransactionAsync();
        Guard.That(await db.Printers.Where(p => p.Id == r.PrinterId && p.Enabled && !p.Suppressed)
            .ExecuteUpdateAsync(s => s.SetProperty(p => p.Enabled, true)) == 1, "打印机尚未启用或已删除");
        var printer = await db.Printers.SingleOrDefaultAsync(x => x.Id == r.PrinterId && x.Enabled && !x.Suppressed) ?? throw new ApiException(400, "打印机尚未启用");
        Guard.That(await db.Devices.AnyAsync(x => x.Id == printer.DeviceId && x.Enabled), "打印设备已停用"); Guard.That(!r.Duplex || printer.Duplex, "该打印机不支持双面");
        Guard.That(!await db.GuangzhouDevices.AnyAsync(g => g.Id == printer.DeviceId && !g.Enrolled), "打印设备尚未绑定控制电脑，请选择已连接的打印机", 409);
        var hash = Secrets.Hash(Json.Write(new { documents = r.DocumentIds.Order().ToArray(), r.PrinterId, r.Copies, r.Duplex, reprintOf }));
        var documents = new List<ExportDocument>();
        foreach (var id in r.DocumentIds) documents.Add(await a.Document(id));
        var existing = await db.PrintJobs.Where(x => x.StaffId == a.User.Id && x.RequestKey == r.RequestKey).OrderBy(x => x.DocumentId).ToListAsync();
        if (existing.Count > 0) { Guard.That(existing.All(x => x.RequestHash == hash), "请求标识已用于不同打印内容", 409); return Results.Ok(existing); }
        await PrintExceptions.RequireAvailable(printer,db,reprintOf);
        Guard.That(documents.All(d => d.State == "ready" || r.WaitForDocuments && d.State is "queued" or "processing"), "文档未生成或已失败，请先重新生成", 409);
        var batch = Guid.NewGuid(); var jobs = r.DocumentIds.Order().Select(id => new PrintJob { BatchId = batch, StaffId = a.User.Id, DocumentId = id, PrinterId = printer.Id, DeviceId = printer.DeviceId, RequestKey = r.RequestKey, RequestHash = hash, Copies = r.Copies, Duplex = r.Duplex, ReprintOf = reprintOf }).ToArray();
        if (r.WaitForDocuments) foreach (var job in jobs) {
            job.State = "preparing"; job.Detail = "后台生成文档，完成后自动打印";
            db.Work.Add(new WorkItem { Kind = "print-prepare", EntityId = job.Id });
        }
        else await GuangzhouPrinting.Prepare(db, objects, jobs);
        db.PrintRequests.Add(new PrintRequest { Id = batch, StaffId = a.User.Id, RequestKey = r.RequestKey, RequestHash = hash });
        db.PrintJobs.AddRange(jobs); db.Audits.Add(new Audit { StaffId = a.User.Id, Action = reprintOf.HasValue ? "print.reprint" : "print.submit", TargetId = batch });
        try { await db.SaveChangesAsync(); } catch (DbUpdateException) { db.ChangeTracker.Clear(); var retry = await db.PrintJobs.Where(x => x.StaffId == a.User.Id && x.RequestKey == r.RequestKey).OrderBy(x => x.DocumentId).ToListAsync(); Guard.That(retry.Count == jobs.Length && retry.All(x => x.RequestHash == hash), "并发提交冲突，请刷新", 409); return Results.Ok(retry); }
        await tx.CommitAsync();
        return Results.Ok(jobs);
    }

    public static async Task PreparePending(Guid id, Data db, IObjects objects, CancellationToken ct)
    {
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        // Lock the row across preparation so an expired work lease cannot produce two payloads
        // or restore a task which a device has already claimed.
        var locked = await db.PrintJobs.Where(x => x.Id == id && x.State == "preparing")
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "preparing"), ct);
        if (locked != 1) return;
        var job = await db.PrintJobs.SingleAsync(x => x.Id == id, ct);
        if (job.State != "preparing") return; // Safe replay after a worker restart; never reset a claimed job.
        var document = await db.Documents.SingleAsync(x => x.Id == job.DocumentId, ct);
        Guard.That(document.State == "ready", "该学生文档生成失败，请重新生成后再打印", 409);
        var printer = await db.Printers.SingleAsync(x => x.Id == job.PrinterId, ct);
        Guard.That(printer.Enabled && !printer.Suppressed && printer.DeviceId == job.DeviceId && await db.Devices.AnyAsync(d => d.Id == job.DeviceId && d.Enabled, ct), "打印设备已停用，请重新选择设备", 409);
        Guard.That(!job.Duplex || printer.Duplex, "打印机已不支持双面，请重新选择", 409);
        // The work lease owns this preparation. Payload + queue state commit together.
        await GuangzhouPrinting.Prepare(db, objects, [job]);
        job.State = "queued"; job.Detail = null;
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
    }
}
