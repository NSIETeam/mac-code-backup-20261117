using Microsoft.EntityFrameworkCore;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
namespace Siyue;
// Property defaults keep optional catalog fields compatible with both JSON binding and OpenAPI schema export.
public record CatalogInput
{
    public string Name { get; init; } = "";
    public bool? CanSelectAnyCampus { get; init; }
    public string? City { get; init; }
    public Guid CampusId { get; init; }
    public Guid ClassId { get; init; }
    public Guid CourseId { get; init; }
    public Guid StaffId { get; init; }
    public string Code { get; init; } = "";
    public string Role { get; init; } = "teacher";
    public bool Enabled { get; init; } = true;
}
public record ExcelRow(int Row, string Name, string Code);
public static class CatalogEndpoints
{
    public static void MapCatalog(this WebApplication app)
    {
        app.MapGet("/api/workspace", async (Access access, Data db) => {
            var u = access.User; var anyCampus = u.Role is "admin" or "teacher"; var assignments = await db.Assignments.Where(x => (u.Role == "admin" || x.StaffId == u.Id) && db.Classes.Any(c => c.Id == x.ClassId) && db.Courses.Any(c => c.Id == x.CourseId)).ToListAsync();
            var ids = assignments.Select(x => x.ClassId).ToArray(); var classes = await db.Classes.Where(x => u.Role == "admin" || x.OwnerStaffId == u.Id || ids.Contains(x.Id)).ToListAsync();
            var campuses = classes.Select(x => x.CampusId).ToArray(); var courses = assignments.Select(x => x.CourseId).ToArray();
            return new { user = u, classes, assignments, campuses = await db.Campuses.Where(x => anyCampus || campuses.Contains(x.Id)).ToListAsync(),
                printCampuses = await db.Campuses.Where(c => db.Printers.Any(p => p.CampusId == c.Id && p.Enabled && !p.Suppressed && db.Devices.Any(d => d.Id == p.DeviceId && d.Enabled && !db.GuangzhouDevices.Any(g => g.Id == d.Id && !g.Enrolled)))).OrderBy(c => c.Name).ToListAsync(),
                courses = await db.Courses.ToListAsync() };
        });
        var admin = app.MapGroup("/api/admin").AddEndpointFilter(async (context, next) => { context.HttpContext.RequestServices.GetRequiredService<Access>().Admin(); return await next(context); });
        admin.MapGet("/catalog", async (Data db) => new {
            campuses = await db.Campuses.ToListAsync(), courses = await db.Courses.ToListAsync(), classes = await db.Classes.ToListAsync(), assignments = await db.Assignments.Where(x => db.Classes.Any(c => c.Id == x.ClassId) && db.Courses.Any(c => c.Id == x.CourseId)).ToListAsync(),
            students = await db.Students.Where(x => db.Classes.Any(c => c.Id == x.ClassId)).ToListAsync(), staff = await db.Staff.Select(x => new { x.Id, x.Name, x.Code, x.Role, x.Enabled, canSelectAnyCampus = x.Role == "teacher" || x.Role == "admin" || x.CanSelectAnyCampus, bound = x.OpenId != null, account = db.PasswordAccounts.Where(p => p.StaffId == x.Id).Select(p => p.Account).FirstOrDefault() }).ToListAsync(),
            devices = await db.Devices.Where(x => x.Enabled || db.Campuses.Any(c => c.Id == x.CampusId)).Select(x => new { x.Id, x.Name, x.CampusId, x.Enabled, x.LastSeen, x.AgentVersion, x.MachineName, x.ProbeError }).ToListAsync(), printers = await db.Printers.Where(x => !x.Suppressed).ToListAsync(), printerHealth = await PrintHealth.Inspect(db)
        });
        admin.MapPost("/catalog/{kind}", (string kind, CatalogInput input, Data db, Access a) => Save(kind, null, input, db, a));
        admin.MapPut("/catalog/{kind}/{id:guid}", (string kind, Guid id, CatalogInput input, Data db, Access a) => Save(kind, id, input, db, a));
        admin.MapDelete("/catalog/campuses/{id:guid}", CatalogRemoval.Campus);
        admin.MapDelete("/catalog/classes/{id:guid}", CatalogRemoval.Class);
        admin.MapDelete("/catalog/staff/{id:guid}", CatalogRemoval.Staff);
        admin.MapDelete("/assignments/{id:guid}", async (Guid id, Data db, Access a) => {
            var n = await db.Assignments.Where(x => x.Id == id).ExecuteDeleteAsync(); Guard.That(n == 1, "任课关系不存在", 404);
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "assignment.delete", TargetId = id }); await db.SaveChangesAsync(); return Results.NoContent();
        });
        admin.MapPost("/staff/{id:guid}/invite", async (Guid id, Data db) => {
            var u = await db.Staff.FindAsync(id) ?? throw new ApiException(404, "教师不存在"); Guard.That(u.Enabled && u.OpenId == null, "该身份已绑定或停用");
            await db.Invites.Where(x => x.StaffId == id && !x.Used).ExecuteUpdateAsync(s => s.SetProperty(x => x.Used, true));
            var code = Secrets.New()[..16]; db.Invites.Add(new Invite { StaffId = id, CodeHash = Secrets.Hash(code), ExpiresAt = Clock.Now.AddDays(2) }); await db.SaveChangesAsync(); return new { code, expiresInHours = 48 };
        });
        admin.MapPost("/roster/{kind}/import", (string kind, HttpRequest req, Data db, Access a) => ImportRoster(kind, req, db, a)).DisableAntiforgery();

    }
    public static async Task<IResult> ImportRoster(string kind, HttpRequest req, Data db, Access a, Guid? forcedClass = null, bool transactionOwned = false) {
            Guard.That(kind is "students" or "staff", "仅支持学生或教师名单"); var form = await req.ReadFormAsync(); var file = form.Files.GetFile("file");
            Guard.That(file != null && Path.GetExtension(file.FileName).Equals(".xlsx", StringComparison.OrdinalIgnoreCase) && file.Length <= 5_242_880, "请选择不超过 5MB 的 xlsx 文件");
            var classId = Guid.TryParse(form["classId"], out var cid) ? cid : Guid.Empty;
            if (forcedClass.HasValue) { Guard.That(classId == Guid.Empty || classId == forcedClass, "名单班级不匹配", 403); classId = forcedClass.Value; }
            if (kind == "students") await a.Class(classId); else a.Admin();
            await using var m = new MemoryStream(); await file!.CopyToAsync(m); m.Position = 0; var rows = ReadRoster(m);
            Guard.That(rows.Count is > 0 and <= 1000, "名单需要 1–1000 行");
            Guard.That(rows.All(x => x.Name.Length is > 0 and <= 120 && x.Code.Length <= 120 && (kind == "students" || x.Code.Length > 0)), "姓名不能为空且不能超过120字；教师名单需填写工号");
            Guard.That(rows.Where(x => x.Code.Length > 0).Select(x => x.Code).Distinct().Count() == rows.Count(x => x.Code.Length > 0), "名单存在重复学号/工号");
            if (kind == "students") { var unnamedCodes = rows.Where(x => x.Code.Length == 0).Select(x => x.Name).ToArray();
                Guard.That(unnamedCodes.Distinct().Count() == unnamedCodes.Length, "名单中有重复姓名，请核对后再导入");
                Guard.That(!await db.Students.AnyAsync(x => x.ClassId == classId && unnamedCodes.Contains(x.Name)), "名单中有本班已有的同名学生，请移除重复行；确为同名学生可单独添加");
                var codes = rows.Where(x => x.Code.Length > 0).Select(x => x.Code).ToArray(); Guard.That(!await db.Students.AnyAsync(x => x.ClassId == classId && codes.Contains(x.Code)), "名单中存在本班已录入的学号，请移除重复行"); }
            if (kind == "staff") { var codes = rows.Select(x => x.Code).ToArray(); Guard.That(!await db.Staff.AnyAsync(x => x.Code != null && codes.Contains(x.Code)), "名单中存在已录入的工号"); }
            if (form["commit"] != "true") return Results.Ok(new { rows, committed = false });
            await using var tx = transactionOwned ? null : await db.Database.BeginTransactionAsync();
            foreach (var row in rows) { if (kind == "students") db.Students.Add(new Student { ClassId = classId, Name = row.Name, Code = string.IsNullOrWhiteSpace(row.Code) ? "auto-" + Guid.NewGuid().ToString("N") : row.Code }); else db.Staff.Add(new Staff { Name = row.Name, Code = row.Code }); }
            db.Audits.Add(new Audit { StaffId = a.User.Id, Action = "roster.import." + kind, TargetId = classId }); await db.SaveChangesAsync(); if (tx != null) await tx.CommitAsync(); return Results.Ok(new { rows, committed = true });
    }
    static async Task<IResult> Save(string kind, Guid? id, CatalogInput i, Data db, Access a)
    {
        Entity entity;
        switch (kind)
        {
            case "campuses": var campus = id.HasValue ? await db.Campuses.FindAsync(id) ?? throw new ApiException(404, "校区不存在") : new Campus(); campus.Name = Guard.Name(i.Name); if (i.City != null) campus.City = Guard.Name(i.City); entity = campus; break;
            case "courses": var course = id.HasValue ? await db.Courses.FindAsync(id) ?? throw new ApiException(404, "课程不存在") : new Course(); course.Name = Guard.Name(i.Name); entity = course; break;
            case "classes": Guard.That(await db.Campuses.AnyAsync(x => x.Id == i.CampusId), "校区不存在"); var cl = id.HasValue ? await db.Classes.FindAsync(id) ?? throw new ApiException(404, "班级不存在") : new SchoolClass(); cl.Name = Guard.Name(i.Name); cl.CampusId = i.CampusId; entity = cl; break;
            case "students": Guard.That(await db.Classes.AnyAsync(x => x.Id == i.ClassId), "班级不存在"); var student = id.HasValue ? await db.Students.FindAsync(id) ?? throw new ApiException(404, "学生不存在") : new Student();
                Guard.That(!id.HasValue || student.ClassId == i.ClassId || !await db.WrongQuestions.AnyAsync(x => x.StudentId == id), "已有错题记录的学生暂不支持直接转班"); student.Name = Guard.Name(i.Name); student.Code = string.IsNullOrWhiteSpace(i.Code) ? (id.HasValue ? student.Code : "auto-" + student.Id.ToString("N")) : Guard.Name(i.Code); student.ClassId = i.ClassId; entity = student; break;
            case "staff": Guard.That(i.Role is "admin" or "teacher", "身份无效"); var user = id.HasValue ? await db.Staff.FindAsync(id) ?? throw new ApiException(404, "教师不存在") : new Staff();
                Guard.That(user.Id != a.User.Id || (i.Enabled && i.Role == "admin"), "不能停用或降级当前管理员"); user.Code = string.IsNullOrWhiteSpace(i.Code) ? null : Guard.Name(i.Code); user.Name = Guard.Name(i.Name); user.Role = i.Role; user.Enabled = i.Enabled; if(i.CanSelectAnyCampus.HasValue)user.CanSelectAnyCampus=i.CanSelectAnyCampus.Value; entity = user; break;
            case "assignments": Guard.That(!id.HasValue, "请删除旧任课关系后重新分配"); Guard.That(await db.Staff.AnyAsync(x => x.Id == i.StaffId && x.Enabled) && await db.Classes.AnyAsync(x => x.Id == i.ClassId) && await db.Courses.AnyAsync(x => x.Id == i.CourseId), "请选择有效的教师、班级和课程"); entity = new Assignment { StaffId = i.StaffId, ClassId = i.ClassId, CourseId = i.CourseId }; break;
            default: throw new ApiException(404, "不支持的管理项目");
        }
        if (!id.HasValue) db.Add(entity); db.Audits.Add(new Audit { StaffId = a.User.Id, Action = kind + (id.HasValue ? ".update" : ".create"), TargetId = entity.Id }); await db.SaveChangesAsync(); return Results.Ok(entity);
    }
    static List<ExcelRow> ReadRoster(Stream stream)
    {
        using var book = SpreadsheetDocument.Open(stream, false); var wb = book.WorkbookPart ?? throw new ApiException(400, "Excel 格式无效");
        var sheet = (wb.Workbook ?? throw new ApiException(400, "Excel 工作簿无效")).Sheets?.Elements<Sheet>().FirstOrDefault() ?? throw new ApiException(400, "Excel 没有工作表");
        var part = (WorksheetPart)wb.GetPartById(sheet.Id!); var strings = wb.SharedStringTablePart?.SharedStringTable;
        string Value(Cell cell) => cell.DataType?.Value == CellValues.SharedString ? strings?.Elements<SharedStringItem>().ElementAt(int.Parse(cell.CellValue!.Text)).InnerText ?? "" : cell.InnerText;
        var result = new List<ExcelRow>();
        foreach (var row in (part.Worksheet ?? throw new ApiException(400, "Excel 工作表无效")).Descendants<Row>().Skip(1).Take(1002)) {
            var cells = row.Elements<Cell>().ToDictionary(x => new string((x.CellReference?.Value ?? "").TakeWhile(char.IsLetter).ToArray()), x => Value(x).Trim());
            var name = cells.GetValueOrDefault("A", ""); var code = cells.GetValueOrDefault("B", ""); if (name.Length + code.Length == 0) continue;
            result.Add(new ExcelRow((int)(row.RowIndex?.Value ?? 0), name, code));
        }
        return result;
    }
}
