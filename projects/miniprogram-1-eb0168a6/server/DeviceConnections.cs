using Microsoft.EntityFrameworkCore;
using Siyue.Printing;
using System.Text.Json;
namespace Siyue;
public record PairingInput(string Name);
public record ConnectDeviceInput(Guid CampusId, string Code = "", Guid? PairingId = null);
public record EnrollmentInput(string Name, string EnrollmentKey, InstallationProfile? Profile = null);
public static class DeviceConnections
{
    static InstallationProfile? ReadProfile(DevicePairing pairing) => pairing.RequestedProfileJson == null ? null : JsonSerializer.Deserialize<InstallationProfile>(pairing.RequestedProfileJson);
    public static void MapDeviceConnections(this WebApplication app)
    {
        app.MapPost("/agent/enrollments", async (EnrollmentInput r, Data db) => {
            Guard.That(r.EnrollmentKey.Length == 64 && r.EnrollmentKey.All(Uri.IsHexDigit), "登记凭证格式无效");
            await using var tx = await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.Serializable);
            var pollToken = Secrets.Hash("poll:" + r.EnrollmentKey); var deviceToken = Secrets.Hash("device:" + r.EnrollmentKey); var hash = Secrets.Hash(pollToken);
            var pairing = await db.Pairings.SingleOrDefaultAsync(x => x.PollHash == hash);
            if (pairing == null) { pairing = new DevicePairing { Automatic = true, Name = Guard.Name(r.Name), PollHash = hash, DeviceTokenHash = Secrets.Hash(deviceToken), CodeHash = Secrets.Hash(Secrets.New()) }; db.Pairings.Add(pairing); }
            if (pairing.DeviceId == null) {
                pairing.Name = Guard.Name(r.Name);
                if (r.Profile != null) {
                    InstallationProfile profile;
                    try { profile = r.Profile.Validate(); } catch (ArgumentException e) { throw new ApiException(400, e.Message); }
                    pairing.RequestedProfileJson = JsonSerializer.Serialize(profile);
                }
            }
            if (pairing.DeviceId == null && ReadProfile(pairing) is { } requested) {
                var campuses = await db.Campuses.Where(c => c.Name == requested.CampusName).ToListAsync();
                Guard.That(campuses.Count <= 1, "校区名称重复，请管理员先调整", 409);
                var campus = campuses.SingleOrDefault();
                if (campus == null) {
                    Guard.That(!await db.Campuses.IgnoreQueryFilters().AnyAsync(c => c.Name == requested.CampusName), "该校区已停用，请联系管理员", 409);
                    campus = new Campus { Name = requested.CampusName }; db.Campuses.Add(campus);
                }
                Guard.That(!await db.Printers.AnyAsync(p => p.CampusId == campus.Id && p.Name == requested.PrinterAlias), "本校区已有同名打印机，请填写不同的位置名称", 409);
                var device = new Device { Name = requested.PrinterAlias, CampusId = campus.Id, TokenHash = pairing.DeviceTokenHash };
                var printer = new Printer { DeviceId = device.Id, CampusId = campus.Id, QueueName = requested.QueueName, Name = requested.PrinterAlias, Enabled = true };
                db.Devices.Add(device); db.Printers.Add(printer); pairing.DeviceId = device.Id;
                db.Audits.Add(new Audit { Action = "device.auto-connect", TargetId = device.Id });
            }
            pairing.LastSeen = Clock.Now; pairing.ExpiresAt = Clock.Now.AddDays(7);
            await db.SaveChangesAsync(); await tx.CommitAsync(); return new { pairing.Id, pollToken, deviceToken };
        }).RequireRateLimiting("auth");
        app.MapGet("/api/admin/device-enrollments", async (Data db, Access a) => {
            a.Admin(); var pending = await db.Pairings.Where(x => x.Automatic && x.DeviceId == null && x.ExpiresAt > Clock.Now).OrderByDescending(x => x.LastSeen).Take(100).ToListAsync();
            return pending.Select(x => { var profile = ReadProfile(x); return new { x.Id, name = profile == null ? x.Name : profile.CampusName + " · " + profile.PrinterAlias, deviceName = x.Name, x.LastSeen, profile }; });
        });
        app.MapPost("/agent/pairings", async (PairingInput r, Data db) => {
            var code = Secrets.New()[..12].ToUpperInvariant(); var pollToken = Secrets.New(); var deviceToken = Secrets.New();
            db.Pairings.Add(new DevicePairing { Name = Guard.Name(r.Name), CodeHash = Secrets.Hash(code), PollHash = Secrets.Hash(pollToken), DeviceTokenHash = Secrets.Hash(deviceToken), ExpiresAt = Clock.Now.AddMinutes(10) });
            await db.SaveChangesAsync(); return new { code, pollToken, deviceToken, expiresInMinutes = 10 };
        }).RequireRateLimiting("auth");
        app.MapGet("/agent/pairings/status", async (HttpRequest req, Data db) => {
            var raw = req.Headers.Authorization.ToString(); Guard.That(raw.StartsWith("Bearer "), "连接凭证无效", 401);
            var hash = Secrets.Hash(raw[7..]); var pairing = await db.Pairings.SingleOrDefaultAsync(x => x.PollHash == hash && x.ExpiresAt > Clock.Now) ?? throw new ApiException(410, "连接码已过期，请重新获取");
            if (pairing.Automatic) { pairing.LastSeen = Clock.Now; await db.SaveChangesAsync(); }
            if (!pairing.DeviceId.HasValue) return Results.Ok(new { state = "pending" });
            var device = await db.Devices.FindAsync(pairing.DeviceId) ?? throw new ApiException(404, "设备不存在"); Guard.That(device.Enabled, "设备已停用", 403);
            return Results.Ok(new { state = "connected", device.Id, device.CampusId });
        });
        app.MapPost("/api/admin/device-connections", async (ConnectDeviceInput r, Data db, Access a) => {
            a.Admin(); Guard.That(await db.Campuses.AnyAsync(x => x.Id == r.CampusId), "请选择有效校区");
            var hash = Secrets.Hash(r.Code.Trim().ToUpperInvariant()); await using var tx = await db.Database.BeginTransactionAsync();
            var pairing = await db.Pairings.SingleOrDefaultAsync(x => (r.PairingId.HasValue ? x.Id == r.PairingId && x.Automatic : x.CodeHash == hash) && x.ExpiresAt > Clock.Now) ?? throw new ApiException(400, "连接码无效或已过期");
            if (pairing.DeviceId.HasValue) { var existing = await db.Devices.FindAsync(pairing.DeviceId); Guard.That(existing?.CampusId == r.CampusId, "该设备已经连接到其他校区", 409); return Results.Ok(new { id = pairing.DeviceId, state = "connected" }); }
            var requested = ReadProfile(pairing);
            if (requested != null) { var campus = await db.Campuses.FindAsync(r.CampusId); Guard.That(campus!.Name.Trim() == requested.CampusName, "请选择电脑初始化时填写的所属校区；校区不存在时请先创建", 409); }
            var device = new Device { Name = pairing.Name, CampusId = r.CampusId, TokenHash = pairing.DeviceTokenHash };
            Guard.That(await db.Pairings.Where(x => x.Id == pairing.Id && x.DeviceId == null).ExecuteUpdateAsync(s => s.SetProperty(x => x.DeviceId, device.Id)) == 1, "设备已连接，请刷新", 409);
            db.Devices.Add(device); db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "device.connect", TargetId = device.Id }); await db.SaveChangesAsync(); await tx.CommitAsync();
            return Results.Ok(new { id = (Guid?)device.Id, state = "connected" });
        });
    }
}
