namespace Siyue;
public static class InstallerDownloads
{
    public static void MapInstallerDownloads(this WebApplication app)
    {
        app.MapPost("/api/admin/installer-download", async (Access a, Data db, IConfiguration config) => {
            a.Admin(); var manifest = AgentUpdates.Read(config) ?? throw new ApiException(404, "安装包尚未发布，请联系管理员");
            var token = Secrets.New(); db.Grants.Add(new FileGrant { TokenHash = Secrets.Hash(token), ObjectKey = "installer:" + manifest.Sha256, ContentType = "application/octet-stream", FileName = manifest.FileName, StaffId = a.User.Id, ExpiresAt = Clock.Now.AddHours(1) }); await db.SaveChangesAsync();
            return new { manifest.Version, manifest.FileName, manifest.Size, manifest.Sha256, expiresInMinutes = 60, url = (config["PublicUrl"] ?? "http://127.0.0.1:5080").TrimEnd('/') + "/installer-download/" + token };
        });
        app.MapGet("/installer-download/{token}", async (string token, Data db, IConfiguration config) => {
            var hash = Secrets.Hash(token); var grant = await Microsoft.EntityFrameworkCore.EntityFrameworkQueryableExtensions.SingleOrDefaultAsync(db.Grants, x => x.TokenHash == hash && x.ExpiresAt > Clock.Now && x.ObjectKey.StartsWith("installer:")) ?? throw new ApiException(404, "下载链接已过期，请重新获取");
            Guard.That(await Microsoft.EntityFrameworkCore.EntityFrameworkQueryableExtensions.AnyAsync(db.Staff, x => x.Id == grant.StaffId && x.Enabled && x.Role == "admin"), "管理员权限已失效", 403);
            var manifest = AgentUpdates.Read(config) ?? throw new ApiException(404, "暂无安装包");
            Guard.That(grant.ObjectKey == "installer:" + manifest.Sha256 && grant.FileName == manifest.FileName, "安装包版本已更新，请重新获取下载链接", 410);
            return Results.File(Path.Combine(AgentUpdates.Root(config)!, manifest.FileName), "application/octet-stream", manifest.FileName, enableRangeProcessing: true);
        });
    }
}
