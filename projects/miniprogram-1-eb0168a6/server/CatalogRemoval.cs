using Microsoft.EntityFrameworkCore;
namespace Siyue;

public static class CatalogRemoval
{
    static readonly string[] PendingStates = ["preparing", "queued", "claimed", "submitting", "uncertain"];

    public static async Task<IResult> Printer(Guid id, Data db, Access access)
    {
        access.Admin();
        await using var tx = await db.Database.BeginTransactionAsync();
        // The same row lock is held by print submission until its jobs are committed.
        var changed = await db.Printers.Where(p => p.Id == id && !p.Suppressed)
            .ExecuteUpdateAsync(s => s.SetProperty(p => p.Enabled, false).SetProperty(p => p.Suppressed, true));
        Guard.That(changed == 1, "打印机不存在或已删除", 404);
        Guard.That(!await db.PrintJobs.AnyAsync(j => j.PrinterId == id && PendingStates.Contains(j.State)),
            "此打印机还有未完成或结果待核实的任务，请处理完打印记录后再删除", 409);
        db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "printer.delete", TargetId = id });
        await db.SaveChangesAsync(); await tx.CommitAsync();
        return Results.NoContent();
    }

    public static async Task<IResult> Class(Guid id, Data db, Access access)
    {
        access.Admin();
        await using var tx = await db.Database.BeginTransactionAsync();
        var changed = await db.Classes.Where(c => c.Id == id)
            .ExecuteUpdateAsync(s => s.SetProperty(c => c.Archived, true));
        Guard.That(changed == 1, "班级不存在或已归档", 404);
        db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "class.archive", TargetId = id });
        await db.SaveChangesAsync();
        await tx.CommitAsync();
        return Results.NoContent();
    }

    public static async Task<IResult> Staff(Guid id, Data db, Access access)
    {
        access.Admin();
        Guard.That(id != access.User.Id, "不能移除当前登录账号", 409);
        await using var tx = await db.Database.BeginTransactionAsync();
        var changed = await db.Staff.Where(u => u.Id == id)
            .ExecuteUpdateAsync(s => s.SetProperty(u => u.Enabled, false).SetProperty(u => u.Archived, true));
        Guard.That(changed == 1, "账号不存在或已移除", 404);
        await db.Invites.Where(i => i.StaffId == id && !i.Used)
            .ExecuteUpdateAsync(s => s.SetProperty(i => i.Used, true));
        db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "staff.archive", TargetId = id });
        await db.SaveChangesAsync();
        await tx.CommitAsync();
        return Results.NoContent();
    }

    public static async Task<IResult> Campus(Guid id, Data db, Access access)
    {
        access.Admin();
        await using var tx = await db.Database.BeginTransactionAsync();
        var changed = await db.Campuses.Where(c => c.Id == id).ExecuteUpdateAsync(s => s.SetProperty(c => c.Archived, true));
        Guard.That(changed == 1, "校区不存在或已删除", 404);
        Guard.That(!await db.Classes.AnyAsync(c => c.CampusId == id), "此校区仍有关联班级，请先在班级设置中调整所属校区", 409);
        Guard.That(!await db.Printers.AnyAsync(p => p.CampusId == id && !p.Suppressed), "此校区仍有打印设备，请先删除或迁移设备", 409);
        Guard.That(!await db.PrintJobs.AnyAsync(j => PendingStates.Contains(j.State) && db.Devices.Any(d => d.Id == j.DeviceId && d.CampusId == id)),
            "此校区还有未完成或结果待核实的打印任务，请先处理打印记录", 409);
        await db.Devices.Where(d => d.CampusId == id).ExecuteUpdateAsync(s => s.SetProperty(d => d.Enabled, false));
        await db.Bindings.Where(b => b.CampusId == id && !b.Used).ExecuteUpdateAsync(s => s.SetProperty(b => b.Used, true));
        db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "campus.delete", TargetId = id });
        await db.SaveChangesAsync(); await tx.CommitAsync();
        return Results.NoContent();
    }
}
