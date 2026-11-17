using Microsoft.EntityFrameworkCore;
namespace Siyue;

public record PrinterHealth(Guid PrinterId, Guid CampusId, string Name, string Code, string Message, DateTimeOffset? LastSeen, int Waiting, int NeedsReview);
public static class PrintHealth
{
    // Evidence is derived from current identities and heartbeats, never from a display name.
    public static async Task<PrinterHealth[]> Inspect(Data db, CancellationToken ct = default)
    {
        var printers=await db.Printers.AsNoTracking().Where(p=>!p.Suppressed).ToArrayAsync(ct);
        var devices=await db.Devices.AsNoTracking().ToDictionaryAsync(d=>d.Id,ct);
        var protocols=await db.GuangzhouDevices.AsNoTracking().ToDictionaryAsync(d=>d.Id,ct);
        var campuses=await db.Campuses.Select(c=>c.Id).ToArrayAsync(ct);
        var jobs=await db.PrintJobs.AsNoTracking().Where(j=>j.State=="queued"||j.State=="preparing"||j.State=="uncertain"||j.State=="submitting").ToArrayAsync(ct);
        var now=Clock.Now;
        return printers.Select(p=>{
            devices.TryGetValue(p.DeviceId,out var d);protocols.TryGetValue(p.DeviceId,out var g);
            var pending=jobs.Where(j=>j.PrinterId==p.Id).ToArray();
            var code="ready";var message=g!=null?"助手在线；实际出纸以现场核实为准":"控制电脑在线，驱动未报告异常";
            if(!p.Enabled||d?.Enabled!=true){code="disabled";message="设备已停用，不会自动重新启用";}
            else if(g is {Enrolled:false}){code="unbound";message="尚未绑定控制电脑，不可下单；请在打印助手完成绑定";}
            else if(!campuses.Contains(d.CampusId)||p.CampusId!=d.CampusId){code="campus_mismatch";message="校区关联异常，等待自动校验";}
            else if(d.LastSeen==null||d.LastSeen<now.AddSeconds(-90)){code="offline";message="控制电脑离线；请开启电脑和打印助手，重连后原排队任务自动继续";}
            else if(g?.Availability=="attention"||pending.Any(j=>j.State=="uncertain")){code="needs_review";message="已有任务结果待核实，不会自动重打，请在控制电脑核实";}
            else if(g?.Availability=="paused"&&pending.Any(j=>j.State=="submitting")){code="awaiting_receipt";message="任务已交给打印机，助手正等待完成回执；不会自动重打";}
            else if(p.StatusAt==null||p.StatusAt<now.AddSeconds(-45)){code="stale";message="电脑在线，等待最新打印机状态";}
            else if(p.Status is "missing" or "offline" or "paused" or "error"||g?.Availability=="paused"||p.DeviceReachable==false&&p.DeviceCheckedAt>now.AddSeconds(-45)){code="printer_unavailable";message="打印机不可用，请检查连接、纸张及暂停状态；恢复后原任务继续";}
            else if(p.Status is not ("ready" or "busy")){code="unknown";message="等待打印助手确认队列状态";}
            return new PrinterHealth(p.Id,p.CampusId,p.Name,code,message,d?.LastSeen,pending.Count(j=>j.State is "queued" or "preparing"),pending.Count(j=>j.State=="uncertain"));
        }).ToArray();
    }

    public static async Task<int> Recover(Data db, CancellationToken ct = default)
    {
        var count=0;
        // Campus merges can leave an old printer display association behind. The authenticated
        // controller remains the authority; do not bind by name or restore deleted printers.
        var mismatches=await (from p in db.Printers.AsNoTracking() join d in db.Devices on p.DeviceId equals d.Id
            where p.Enabled&&!p.Suppressed&&d.Enabled&&p.CampusId!=d.CampusId&&db.Campuses.Any(c=>c.Id==d.CampusId)
            select new {p.Id,p.DeviceId,Old=p.CampusId,Target=d.CampusId}).Take(50).ToArrayAsync(ct);
        foreach(var p in mismatches){
            await using var tx=await db.Database.BeginTransactionAsync(ct);
            var changed=await db.Printers.Where(x=>x.Id==p.Id&&x.Enabled&&!x.Suppressed&&x.DeviceId==p.DeviceId&&x.CampusId==p.Old&&db.Devices.Any(d=>d.Id==x.DeviceId&&d.Enabled&&d.CampusId==p.Target)&&db.Campuses.Any(c=>c.Id==p.Target))
                .ExecuteUpdateAsync(s=>s.SetProperty(x=>x.CampusId,p.Target),ct);
            if(changed==1){db.Audits.Add(new Audit{Action="printer.auto-repair-campus",TargetId=p.Id});await db.SaveChangesAsync(ct);count++;}
            await tx.CommitAsync(ct);db.ChangeTracker.Clear();
        }
        var cutoff=Clock.Now.AddSeconds(-15);
        var ids=await db.PrintJobs.AsNoTracking().Where(j=>j.CreatedAt<cutoff&&(j.State=="preparing"||j.State=="queued")&&j.ClaimToken==null&&j.LeaseUntil==null&&j.ExecutionJson==null&&j.ExecutionAt==null)
            .Where(j=>j.State=="preparing"&&!db.Work.Any(w=>w.Kind=="print-prepare"&&w.EntityId==j.Id&&(w.State=="queued"||w.State=="running"))||j.State=="queued"&&db.GuangzhouDevices.Any(g=>g.Id==j.DeviceId&&g.Enrolled)&&!db.GuangzhouJobs.Any(g=>g.Id==j.Id))
            .OrderBy(j=>j.CreatedAt).Select(j=>j.Id).Take(50).ToArrayAsync(ct);
        foreach(var id in ids){
            await using var tx=await db.Database.BeginTransactionAsync(ct);
            // A conditional write locks the ORIGINAL job against claims and other recovery workers.
            var locked=await db.PrintJobs.Where(j=>j.Id==id&&(j.State=="preparing"||j.State=="queued")&&j.ClaimToken==null&&j.LeaseUntil==null&&j.ExecutionJson==null&&j.ExecutionAt==null)
                .ExecuteUpdateAsync(s=>s.SetProperty(j=>j.Detail,j=>j.Detail),ct);
            if(locked==0)continue;
            var job=await db.PrintJobs.SingleAsync(j=>j.Id==id,ct);
            if(await db.GuangzhouJobs.AnyAsync(g=>g.Id==id&&(g.State!="queued"||g.ExecutionKey!=null||g.EvidenceJson!=null||g.ResolutionJson!=null),ct))continue;
            if(!await db.Printers.AnyAsync(p=>p.Id==job.PrinterId&&p.DeviceId==job.DeviceId&&p.Enabled&&!p.Suppressed&&db.Devices.Any(d=>d.Id==p.DeviceId&&d.Enabled&&d.CampusId==p.CampusId)&&db.Campuses.Any(c=>c.Id==p.CampusId),ct))continue;
            if(await db.GuangzhouDevices.AnyAsync(g=>g.Id==job.DeviceId&&!g.Enrolled,ct))continue;
            if(await db.Work.AnyAsync(w=>w.Kind=="print-prepare"&&w.EntityId==id&&(w.State=="queued"||w.State=="running"),ct))continue;
            if(job.State=="queued"&&await db.GuangzhouJobs.AnyAsync(g=>g.Id==id,ct))continue;
            // At most three automatic preparation attempts; never retry an execution/uncertain job.
            var attempts=await db.Work.Where(w=>w.EntityId==id&&w.Kind=="print-prepare").SumAsync(w=>(int?)w.Attempts,ct)??0;
            if(attempts>=3)continue;
            job.State="preparing";job.Detail="正在自动恢复打印准备，保留原任务，不会重复提交打印";
            db.Work.Add(new WorkItem{Kind="print-prepare",EntityId=id});
            db.Audits.Add(new Audit{Action="print.auto-repair-preparation",TargetId=id});
            await db.SaveChangesAsync(ct);await tx.CommitAsync(ct);count++;db.ChangeTracker.Clear();
        }
        return count;
    }
}

// Independent of long document conversions; bounded batches and database locks allow many workers.
public class PrintHealthLoop(IServiceScopeFactory scopes, ILogger<PrintHealthLoop> log):BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        while(!stop.IsCancellationRequested){
            try{await using var scope=scopes.CreateAsyncScope();await PrintHealth.Recover(scope.ServiceProvider.GetRequiredService<Data>(),stop);}
            catch(OperationCanceledException)when(stop.IsCancellationRequested){break;}
            catch(Exception e){log.LogError(e,"Printer automatic health check failed; existing tasks retained");}
            await Task.Delay(TimeSpan.FromSeconds(30),stop);
        }
    }
}
