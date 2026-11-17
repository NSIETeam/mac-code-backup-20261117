using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

public sealed class AcademicHomeworkAttachment : Entity
{
    public string Name { get; set; } = "";
    public byte[] Bytes { get; set; } = [];
    public int Size { get; set; }
}

public partial class Data
{
    public DbSet<AcademicHomeworkAttachment> AcademicHomeworkAttachments => Set<AcademicHomeworkAttachment>();
}

public static class AcademicHomeworkAttachments
{
    const int MaxFile = 3 * 1024 * 1024;
    const int MaxTotal = 64 * 1024 * 1024;

    internal static void Configure(ModelBuilder model) =>
        model.Entity<AcademicHomeworkAttachment>().HasOne<AcademicCollection>().WithOne()
            .HasForeignKey<AcademicHomeworkAttachment>(x => x.Id).OnDelete(DeleteBehavior.Cascade);

    internal static async Task<string?> Name(Guid id, Data db) =>
        await db.AcademicHomeworkAttachments.AsNoTracking().Where(x => x.Id == id).Select(x => x.Name).SingleOrDefaultAsync();

    static async Task<IResult> Download(Guid id, Data db, HttpContext ctx)
    {
        var attachment = await db.AcademicHomeworkAttachments.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id)
            ?? throw new ApiException(404, "作业资料不存在");
        ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
        ctx.Response.Headers.CacheControl = "private, no-store";
        return Results.File(attachment.Bytes, "application/pdf", attachment.Name);
    }

    internal static void Map(RouteGroupBuilder staff, RouteGroupBuilder parent)
    {
        staff.MapPost("/collections/{id:guid}/attachment", async (Guid id, HttpRequest request, Data db, Access access, CancellationToken ct) =>
        {
            var collection = await AcademicHomeworkCollection.Scope(id, db, access);
            Guard.That(collection.State == "draft" && (collection.CreatedBy == access.User.Id || access.User.Role == "admin"),
                "仅草稿创建者或管理员可上传资料", 403);
            Guard.That(request.HasFormContentType && request.ContentLength is > 0 and <= MaxFile + 16384,
                "请选择 3 MiB 以内的 PDF");
            var form = await request.ReadFormAsync(ct);
            Guard.That(int.TryParse(form["revision"], out var revision), "缺少作业版本");
            var file = form.Files.GetFile("file");
            Guard.That(form.Files.Count == 1 && file is { Length: > 5 and <= MaxFile }, "请选择 3 MiB 以内的 PDF");
            var originalName = form["name"].ToString();
            var name = Path.GetFileName((string.IsNullOrWhiteSpace(originalName) ? file!.FileName : originalName).Replace('\\', '/')).Trim();
            Guard.That(name.Length is > 4 and <= 120 && name.EndsWith(".pdf", StringComparison.OrdinalIgnoreCase)
                && !name.Any(char.IsControl), "PDF 文件名无效");
            using var buffer = new MemoryStream((int)file!.Length);
            await file.CopyToAsync(buffer, ct);
            var bytes = buffer.ToArray();
            Guard.That(bytes.AsSpan().StartsWith("%PDF-"u8), "文件不是 PDF");

            await using var tx = await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.Serializable, ct);
            await db.Entry(collection).ReloadAsync(ct);
            Guard.That(collection.State == "draft", "作业已确认，不能修改资料", 409);
            var old = await db.AcademicHomeworkAttachments.SingleOrDefaultAsync(x => x.Id == id, ct);
            if (old != null && old.Name == name && old.Bytes.AsSpan().SequenceEqual(bytes))
                return Results.Ok(new { name, collection.Revision });
            AcademicPilot.Revision(revision, collection.Revision);
            var total = await db.AcademicHomeworkAttachments.SumAsync(x => (long)x.Size, ct);
            Guard.That(total - (old?.Size ?? 0) + bytes.Length <= MaxTotal, "作业资料容量已满，请联系管理员", 409);
            if (old == null)
            {
                old = new AcademicHomeworkAttachment { Id = id };
                db.AcademicHomeworkAttachments.Add(old);
            }
            old.Name = name;
            old.Bytes = bytes;
            old.Size = bytes.Length;
            collection.Revision++;
            AcademicParentPortal.Audit(db, access, id, "collection.attachment.saved", new { size = bytes.Length, collection.Revision });
            try
            {
                await db.SaveChangesAsync(ct);
                await tx.CommitAsync(ct);
            }
            catch (Npgsql.PostgresException error) when (error.SqlState is "40001" or "40P01")
            {
                throw new ApiException(409, "作业资料已变化，请刷新后重试");
            }
            return Results.Ok(new { name, collection.Revision });
        }).DisableAntiforgery();

        staff.MapGet("/collections/{id:guid}/attachment", async (Guid id, Data db, Access access, HttpContext ctx) =>
        {
            await AcademicHomeworkCollection.Scope(id, db, access);
            return await Download(id, db, ctx);
        });

        parent.MapGet("/attachment", async (string ticket, Data db, IDataProtectionProvider p, IConfiguration c, HttpContext ctx) =>
        {
            var row = await AcademicHomeworkCollection.Public(ticket, db, p, c);
            return await Download(row.Id, db, ctx);
        });
    }
}
