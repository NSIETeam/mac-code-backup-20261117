using System.Globalization;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

public class GradeCalendarTemplate : Entity
{
    public string Title { get; set; } = "";
    public string StepsJson { get; set; } = "[]";
}

public class GradeCalendarOwner : Entity
{
    public string Grade { get; set; } = "";
    public Guid StaffId { get; set; }
}

public class GradeCalendarEvent : Entity
{
    public string Grade { get; set; } = "";
    public Guid TemplateId { get; set; }
    public string Anchor { get; set; } = "";
    public int StepIndex { get; set; }
    public string Date { get; set; } = "";
    public string Action { get; set; } = "";
    public bool Done { get; set; }
}

public partial class Data
{
    public DbSet<GradeCalendarTemplate> GradeCalendarTemplates => Set<GradeCalendarTemplate>();
    public DbSet<GradeCalendarOwner> GradeCalendarOwners => Set<GradeCalendarOwner>();
    public DbSet<GradeCalendarEvent> GradeCalendarEvents => Set<GradeCalendarEvent>();
}

public record GradeCalendarStep(int Offset, string Action);
public record GradeCalendarTemplateInput(string Title, GradeCalendarStep[] Steps);
public record GradeCalendarApply(Guid TemplateId, string Anchor);
public record GradeCalendarEventUpdate(bool? Done = null, string? Date = null);

public static class GradeOperationsCalendar
{
    static readonly string[] Grades = ["小一","小二","小三","小四","小五","小六","初一","初二","初三","高一","高二","高三"];

    public static void MapGradeOperationsCalendar(this WebApplication app)
    {
        var group = app.MapGroup("/api/grade-calendar");
        group.MapGet("", async (Data db, Access access) =>
        {
            var actor = access.User;
            var grades = actor.Role == "admin" ? Grades : await db.GradeCalendarOwners
                .Where(o => o.StaffId == actor.Id).Select(o => o.Grade).ToArrayAsync();
            var templates = grades.Length == 0 ? [] : (await db.GradeCalendarTemplates.AsNoTracking()
                .OrderBy(t => t.Title).ToArrayAsync()).Select(t => new { t.Id, t.Title, steps = Json.Read<GradeCalendarStep[]>(t.StepsJson) }).ToArray();
            var owners = actor.Role == "admin" ? await (from owner in db.GradeCalendarOwners
                join staff in db.Staff on owner.StaffId equals staff.Id
                where staff.Enabled select new { owner.Grade, owner.StaffId, staff.Name }).ToArrayAsync() : [];
            return new { grades, templates, owners, canManage = actor.Role == "admin" };
        });
        group.MapPost("/templates", async (GradeCalendarTemplateInput input, Data db, Access access) =>
        {
            access.Admin();
            var title = input.Title?.Trim() ?? "";
            Guard.That(title.Length is > 0 and <= 100 && input.Steps is { Length: > 0 and <= 50 }
                && input.Steps.All(s => s != null && s.Offset is >= -180 and <= 365 && s.Action?.Trim().Length is > 0 and <= 240), "请填写模板名和有效的执行节点");
            var template = new GradeCalendarTemplate { Title = title, StepsJson = Json.Write(input.Steps.Select(s => s with { Action = s.Action.Trim() }).ToArray()) };
            db.GradeCalendarTemplates.Add(template);
            db.Audits.Add(new Audit { StaffId = access.User.Id, TargetId = template.Id, Action = "grade-calendar.template.created" });
            await db.SaveChangesAsync();
            return Results.Ok(new { template.Id });
        });
        group.MapPut("/{grade}/owners/{staffId:guid}", async (string grade, Guid staffId, Data db, Access access) =>
        {
            access.Admin();
            CheckGrade(grade);
            Guard.That(await db.Staff.AnyAsync(s => s.Id == staffId && s.Enabled), "请选择在用员工");
            if (await db.GradeCalendarOwners.AnyAsync(o => o.Grade == grade && o.StaffId == staffId)) return Results.NoContent();
            var owner = new GradeCalendarOwner { Grade = grade, StaffId = staffId };
            db.GradeCalendarOwners.Add(owner);
            db.Audits.Add(new Audit { StaffId = access.User.Id, TargetId = owner.Id, Action = "grade-calendar.owner.granted" });
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException)
            {
                if (!await db.GradeCalendarOwners.AsNoTracking().AnyAsync(o => o.Grade == grade && o.StaffId == staffId)) throw;
            }
            return Results.NoContent();
        });
        group.MapDelete("/{grade}/owners/{staffId:guid}", async (string grade, Guid staffId, Data db, Access access) =>
        {
            access.Admin();
            CheckGrade(grade);
            await using var tx = await db.Database.BeginTransactionAsync();
            var owner = await db.GradeCalendarOwners.SingleOrDefaultAsync(o => o.Grade == grade && o.StaffId == staffId);
            if (owner == null) return Results.NoContent();
            if (await db.GradeCalendarOwners.Where(o => o.Id == owner.Id).ExecuteDeleteAsync() > 0)
            {
                db.Audits.Add(new Audit { StaffId = access.User.Id, TargetId = owner.Id, Action = "grade-calendar.owner.revoked" });
                await db.SaveChangesAsync();
            }
            await tx.CommitAsync();
            return Results.NoContent();
        });
        group.MapGet("/{grade}/events", async (string grade, string? month, Data db, Access access) =>
        {
            await CheckAccess(grade, db, access);
            Guard.That(month != null && DateOnly.TryParseExact(month + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _), "月份格式无效");
            return await db.GradeCalendarEvents.AsNoTracking().Where(e => e.Grade == grade && e.Date.StartsWith(month! + "-"))
                .OrderBy(e => e.Date).ThenBy(e => e.StepIndex).ThenBy(e => e.Id).ToArrayAsync();
        });
        group.MapPost("/{grade}/events", async (string grade, GradeCalendarApply input, Data db, Access access) =>
        {
            await CheckAccess(grade, db, access);
            var anchor = ParseDate(input.Anchor);
            var template = await db.GradeCalendarTemplates.AsNoTracking().SingleOrDefaultAsync(t => t.Id == input.TemplateId)
                ?? throw new ApiException(404, "模板不存在");
            var existing = db.GradeCalendarEvents.Where(e => e.Grade == grade && e.TemplateId == template.Id && e.Anchor == input.Anchor);
            if (await existing.AnyAsync()) return Results.Ok(new { added = 0 });
            var steps = Json.Read<GradeCalendarStep[]>(template.StepsJson);
            Guard.That(steps.All(step => anchor.AddDays(step.Offset).Year is >= 2000 and <= 2099), "模板生成日期超出可用范围");
            var events = steps.Select((step, index) => new GradeCalendarEvent
            {
                Grade = grade, TemplateId = template.Id, Anchor = input.Anchor, StepIndex = index,
                Date = anchor.AddDays(step.Offset).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture), Action = step.Action
            }).ToArray();
            db.GradeCalendarEvents.AddRange(events);
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException)
            {
                if (!await existing.AsNoTracking().AnyAsync()) throw;
                return Results.Ok(new { added = 0 });
            }
            return Results.Ok(new { added = events.Length });
        });
        group.MapPut("/{grade}/events/{id:guid}", async (string grade, Guid id, GradeCalendarEventUpdate input, Data db, Access access) =>
        {
            await CheckAccess(grade, db, access);
            Guard.That(input.Done.HasValue || input.Date != null, "请提供待修改的日期或完成状态");
            if (input.Date != null) _ = ParseDate(input.Date);
            var changed = await db.GradeCalendarEvents.Where(e => e.Id == id && e.Grade == grade)
                .ExecuteUpdateAsync(set => set.SetProperty(e => e.Done, e => input.Done ?? e.Done).SetProperty(e => e.Date, e => input.Date ?? e.Date));
            Guard.That(changed == 1, "日历事项不存在", 404);
            return Results.NoContent();
        });
    }

    static void CheckGrade(string grade) => Guard.That(Grades.Contains(grade), "年级无效", 404);

    static async Task CheckAccess(string grade, Data db, Access access)
    {
        CheckGrade(grade);
        var actor = access.User;
        Guard.That(actor.Role == "admin" || await db.GradeCalendarOwners.AnyAsync(o => o.Grade == grade && o.StaffId == actor.Id), "当前员工未获此年级的运营权限", 403);
    }

    static DateOnly ParseDate(string? text)
    {
        Guard.That(DateOnly.TryParseExact(text, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)
            && date.Year is >= 2000 and <= 2099, "请选择 2000–2099 年的有效日期");
        return date;
    }
}
