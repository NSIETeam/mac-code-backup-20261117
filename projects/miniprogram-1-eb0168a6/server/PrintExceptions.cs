using Microsoft.EntityFrameworkCore;
namespace Siyue;
public sealed record PrintExceptionAssignment(Guid StaffId,string Note,int DueHours=24);
public sealed record PrintExceptionCase(Guid JobId,Guid StaffId,string Note,DateTimeOffset DueAt,DateTimeOffset UpdatedAt,Guid UpdatedBy);
public sealed class PrintExceptionStore(IConfiguration config)
{
    readonly SemaphoreSlim gate=new(1);
    string Root=>Path.GetFullPath(Path.Combine(config["Research:Directory"]??".local/research","print-exceptions"));
    public async Task<PrintExceptionCase?> Read(Guid id){var path=Path.Combine(Root,id.ToString("N")+".json");return File.Exists(path)?Json.Read<PrintExceptionCase>(await File.ReadAllTextAsync(path)):null;}
    public async Task Save(PrintExceptionCase item){await gate.WaitAsync();try{Directory.CreateDirectory(Root);var path=Path.Combine(Root,item.JobId.ToString("N")+".json");var temp=path+".tmp";await File.WriteAllTextAsync(temp,Json.Write(item));File.Move(temp,path,true);}finally{gate.Release();}}
}
public static class PrintExceptions
{
    public static async Task RequireAvailable(Printer p,Data db,Guid? acknowledgedReprint=null){
        Guard.That(p.Status is not ("missing" or "offline" or "paused" or "error"),"打印机当前不可用，请选同校区其他正常打印机或联系管理员；未创建新任务",409);
        Guard.That(await db.Devices.AnyAsync(d=>d.Id==p.DeviceId&&d.Enabled&&d.LastSeen>Clock.Now.AddSeconds(-90)),"控制电脑已离线，请等待连接恢复或选择其他打印机；未创建新任务",409);
        Guard.That(!await db.PrintJobs.AnyAsync(j=>j.PrinterId==p.Id&&j.State=="uncertain"&&j.Id!=acknowledgedReprint),"该打印机有待现场核实的任务，请先处理或选择其他打印机，避免重复出纸",409);
    }
    public static void MapPrintExceptions(this WebApplication app){
        app.MapGet("/api/admin/print-exceptions",async(Data db,Access access,PrintExceptionStore store)=>{
            access.Admin();var now=Clock.Now;var health=await PrintHealth.Inspect(db);var campuses=await db.Campuses.AsNoTracking().ToDictionaryAsync(c=>c.Id,c=>c.Name);var staff=await db.Staff.AsNoTracking().Where(s=>s.Enabled).Select(s=>new{s.Id,s.Name}).ToArrayAsync();
            var jobs=await db.PrintJobs.AsNoTracking().Where(j=>j.State=="uncertain"||(j.State=="queued"||j.State=="preparing"||j.State=="submitting"||j.State=="claimed")&&j.CreatedAt<now.AddMinutes(-15)).OrderBy(j=>j.CreatedAt).Take(500).ToArrayAsync();
            var rows=new List<object>();foreach(var j in jobs){var h=health.FirstOrDefault(x=>x.PrinterId==j.PrinterId);var assignment=await store.Read(j.Id);rows.Add(new{j.Id,j.State,j.Detail,j.CreatedAt,j.DeviceId,j.PrinterId,printerName=h?.Name,campusName=h==null?"":campuses.GetValueOrDefault(h.CampusId,""),submittedBy=staff.FirstOrDefault(s=>s.Id==j.StaffId)?.Name??"历史账号",assignment,ageMinutes=(int)(now-j.CreatedAt).TotalMinutes,overdue=assignment==null||assignment.DueAt<now,alternatives=health.Where(x=>x.CampusId==h?.CampusId&&x.PrinterId!=j.PrinterId&&x.Code=="ready").Select(x=>new{x.PrinterId,x.Name}),instruction=j.State=="uncertain"?"先到现场核对出纸，再在控制电脑登记结果；禁止自动重打":"检查队列连接与原任务，恢复前不要反复下单"});}
            return Results.Ok(new{items=rows,staff,unavailable=health.Where(h=>h.Code!="ready").Select(h=>new{h.PrinterId,h.Name,h.Code,h.Message,campusName=campuses.GetValueOrDefault(h.CampusId,"")})});
        });
        app.MapPost("/api/admin/print-exceptions/{id:guid}/assign",async(Guid id,PrintExceptionAssignment r,Data db,Access access,PrintExceptionStore store)=>{
            access.Admin();Guard.That(r.Note is {Length:>=2 and <=1000}&&r.DueHours is >=1 and <=72,"请填写处理说明，并选择1至72小时内的处理期限");
            Guard.That(await db.Staff.AnyAsync(s=>s.Id==r.StaffId&&s.Enabled),"处理负责人不存在或已停用");
            Guard.That(await db.PrintJobs.AnyAsync(j=>j.Id==id&&new[]{"uncertain","queued","preparing","submitting","claimed"}.Contains(j.State)),"任务已结束，请刷新",409);
            var item=new PrintExceptionCase(id,r.StaffId,r.Note.Trim(),Clock.Now.AddHours(r.DueHours),Clock.Now,access.User.Id);await store.Save(item);db.Audits.Add(new Audit{StaffId=access.User.Id,TargetId=id,Action="print.exception.assign"});await db.SaveChangesAsync();return Results.Ok(item);
        });
    }
}
