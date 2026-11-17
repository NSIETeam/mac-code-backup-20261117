using Microsoft.EntityFrameworkCore;

namespace Siyue;

public static class DocumentRecovery
{
    public static void MapDocumentRecovery(this WebApplication app)
    {
        app.MapPost("/api/handouts/{id:guid}/retry", async (Guid id, Data db, Access access) => {
            var handout = await access.Handout(id);
            await access.WriteLecture(handout.LectureId);
            Guard.That(handout.State != "published", "已发布讲义不可重新拆题，请上传新版本", 409);
            if (handout.State is "queued" or "processing" || (handout.State == "draft" && handout.Error == null)) return Results.Accepted();
            var previewOnly = handout.State == "draft" && handout.Error != null;
            Guard.That(handout.State == "failed" || previewOnly, "当前讲义无需重新处理", 409);
            await using var transaction = await db.Database.BeginTransactionAsync();
            // Updating the entity first serializes simultaneous retries. The queue insert commits with it.
            var changed = previewOnly
                ? await db.Handouts.Where(h => h.Id == id && h.State == "draft" && h.Error != null && h.Revision == handout.Revision)
                    .ExecuteUpdateAsync(s => s.SetProperty(h => h.Error, (string?)null))
                : await db.Handouts.Where(h => h.Id == id && h.State == "failed")
                    .ExecuteUpdateAsync(s => s.SetProperty(h => h.State, "queued").SetProperty(h => h.Error, (string?)null));
            if (changed == 1) {
                db.Work.Add(new WorkItem { EntityId = id, Kind = previewOnly ? "previews" : "import" });
                db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "handout.retry", TargetId = id });
                await db.SaveChangesAsync();
            }
            await transaction.CommitAsync();
            return Results.Accepted();
        });

        app.MapPost("/api/exports/{id:guid}/retry", async (Guid id, Data db, Access access) => {
            var document = await access.Document(id);
            // A repeated request after recovery never regenerates a ready document or starts printing.
            if (document.State is "queued" or "processing" or "ready") return Results.Accepted();
            Guard.That(document.State == "failed", "当前文档无需重新处理", 409);
            var kind = document.Kind switch { "booklet" => "export", "file" => "file", "report" or "variants" => "support", _ => "" };
            Guard.That(kind.Length > 0, "此类文档不支持重新处理", 409);
            await using var transaction = await db.Database.BeginTransactionAsync();
            var changed = await db.Documents.Where(d => d.Id == id && d.State == "failed")
                .ExecuteUpdateAsync(s => s.SetProperty(d => d.State, "queued").SetProperty(d => d.Error, (string?)null));
            if (changed == 1) {
                db.Work.Add(new WorkItem { EntityId = id, Kind = kind });
                db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "export.retry", TargetId = id });
                await db.SaveChangesAsync();
            }
            await transaction.CommitAsync();
            return Results.Accepted();
        });
    }
}
