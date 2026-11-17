using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Writer;
namespace Siyue;

// Adapter for the handed-over Windows assistant, protocol v2 revision 3.
// Its durable execution identity and receipt semantics are retained unchanged.
public class GuangzhouDevice : Entity {
    public bool Legacy { get; set; } public string IdentityCampusName { get; set; } = "";
    public Guid QueueId { get; set; } = Guid.NewGuid(); public long CampusNumber { get; set; }
    public string BindingHash { get; set; } = ""; public DateTimeOffset ExpiresAt { get; set; }
    public bool Enrolled { get; set; } public string Availability { get; set; } = "paused"; public int Revision { get; set; }
}
public class GuangzhouJob : Entity {
    public string State { get; set; } = "queued"; public string PdfKey { get; set; } = ""; public string Hash { get; set; } = "";
    public long Bytes { get; set; } public int Pages { get; set; } public Guid? ExecutionKey { get; set; }
    public string? EvidenceJson { get; set; } public string? ResolutionJson { get; set; }
}
public record GuangzhouBinding(Guid CampusId, string Name);
public record GuangzhouEnroll(string Code);
public record GuangzhouHeartbeat(string Availability);
public record GuangzhouReceipt([property:JsonPropertyName("queue_id")] Guid QueueId,
    [property:JsonPropertyName("pdf_sha256")] string Hash, [property:JsonPropertyName("pdf_bytes")] long Bytes,
    string State, [property:JsonPropertyName("execution_key")] Guid? ExecutionKey = null,
    JsonElement? Evidence = null, [property:JsonPropertyName("not_executed")] bool NotExecuted = false);
public record GuangzhouResolution([property:JsonPropertyName("queue_id")] Guid QueueId,
    [property:JsonPropertyName("pdf_sha256")] string Hash, [property:JsonPropertyName("pdf_bytes")] long Bytes,
    [property:JsonPropertyName("execution_key")] Guid ExecutionKey, string Conclusion, string Source,
    string Operator, string Note, [property:JsonPropertyName("spooler_check")] string SpoolerCheck);
public static class GuangzhouPrinting {
    const long MaxBytes = 128 * 1024 * 1024;
    static readonly object Parameters = new { paper = "A4", color = "monochrome", duplex = "long-edge", driver_copies = 1 };
    sealed class ClaimPoll;
    static IQueryable<PrintJob> ActiveQueue(Data db) => db.PrintJobs.Where(j =>
        j.State == "queued" || j.State == "claimed" || j.State == "submitting" || j.State == "uncertain");

    static async ValueTask<object?> Authenticate(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        var http = context.HttpContext;
        var raw = http.Request.Headers["X-Print-Agent-Token"].ToString().Split('.', 2);
        if (raw.Length != 2 || !Guid.TryParse(raw[0], out var id)) throw new ApiException(401, "设备未认证");
        var db = http.RequestServices.GetRequiredService<Data>();
        var poll = http.GetEndpoint()?.Metadata.GetMetadata<ClaimPoll>() != null;
        var active = ActiveQueue(db);
        var identity = await (from device in db.Devices
            where device.Id == id && device.Enabled
            join protocol in db.GuangzhouDevices on device.Id equals protocol.Id into protocols
            from protocol in protocols.DefaultIfEmpty()
            select new {
                Device = device, Protocol = protocol,
                CampusExists = db.Campuses.Any(c => c.Id == device.CampusId),
                HasHead = poll && active.Any(j => j.DeviceId == device.Id)
            }).SingleOrDefaultAsync(http.RequestAborted) ?? throw new ApiException(401, "设备凭证无效");
        var d = identity.Device;
        var g = identity.Protocol;
        Guard.That(g is { Enrolled: true }, "设备未绑定", 401);
        Guard.That(identity.CampusExists, "校区已停用", 403);
        if (g!.Legacy) {
            await GuangzhouLegacy.Verify(http, d, g);
            if (http.Request.RouteValues.TryGetValue("id", out var value) && Guid.TryParse(value?.ToString(), out var job)
                && !await db.GuangzhouJobs.AnyAsync(x => x.Id == job, http.RequestAborted)) return await GuangzhouLegacy.Forward(http);
        } else {
            Guard.That(d.TokenHash == Secrets.Hash(raw[1]), "设备凭证无效", 401);
            if (poll && g.Availability != "ready") return Results.Ok(new { job = (object?)null, reason = "paused" });
            if (poll && !identity.HasHead) return Results.Ok(new { job = (object?)null, reason = "empty" });
        }
        http.Items["gz-device"] = d;
        http.Items["gz-protocol"] = g;
        return await next(context);
    }

    public static void MapGuangzhouPrinting(this WebApplication app) {
        app.MapPost("/api/admin/guangzhou-bindings", async (GuangzhouBinding r, Access a, Data db) => {
            a.Admin(); Guard.That(await db.Campuses.AnyAsync(x=>x.Id==r.CampusId), "校区不存在",404);
            var name = Guard.Name(r.Name);
            var code = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            var pending = await db.Printers.SingleOrDefaultAsync(x=>x.CampusId==r.CampusId && x.Name==name && !x.Suppressed);
            if(pending!=null){
                var existing=await db.GuangzhouDevices.SingleOrDefaultAsync(x=>x.Id==pending.DeviceId&&!x.Enrolled);
                Guard.That(existing!=null,"本校区已有同名或已绑定打印机，不能接管旧设备",409);
                Guard.That(await db.Devices.AnyAsync(d=>d.Id==pending.DeviceId&&d.Enabled),"设备已停用，请先在设备管理中核实",409);
                Guard.That(await db.GuangzhouDevices.Where(x=>x.Id==pending.DeviceId&&!x.Enrolled).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.BindingHash,Secrets.Hash(code)).SetProperty(x=>x.ExpiresAt,Clock.Now.AddMinutes(10)))==1,"设备已经绑定，不能重新签发",409);
                db.Audits.Add(new Audit{StaffId=a.User.Id,Action="device.guangzhou-binding.renew",TargetId=pending.DeviceId});await db.SaveChangesAsync();
                return new {code,expiresInMinutes=10,deviceId=pending.DeviceId,queueId=existing!.QueueId};
            }
            var device = new Device {CampusId=r.CampusId,Name=name,AgentVersion="Guangzhou-v2"};
            var campusNumber = await (from existing in db.GuangzhouDevices join d in db.Devices on existing.Id equals d.Id where d.CampusId==r.CampusId select (long?)existing.CampusNumber).FirstOrDefaultAsync() ?? RandomNumberGenerator.GetInt32(1,int.MaxValue);
            var g = new GuangzhouDevice {Id=device.Id,CampusNumber=campusNumber,BindingHash=Secrets.Hash(code),ExpiresAt=Clock.Now.AddMinutes(10)};
            db.Devices.Add(device); db.GuangzhouDevices.Add(g);
            db.Printers.Add(new Printer {DeviceId=device.Id,CampusId=r.CampusId,Name=name,QueueName=name,Duplex=true,StatusDetail="等待广州交接版助手绑定"});
            db.Audits.Add(new Audit {StaffId=a.User.Id,Action="device.guangzhou-binding",TargetId=device.Id});await db.SaveChangesAsync();
            return new {code,expiresInMinutes=10,deviceId=device.Id,queueId=g.QueueId};
        });
        app.MapPost("/api/v2/agent/enroll",async (GuangzhouEnroll r,Data db)=>{
            Guard.That(System.Text.RegularExpressions.Regex.IsMatch(r.Code,"^[0-9a-f]{32}$"),"绑定码无效",401);
            await using var tx=await db.Database.BeginTransactionAsync();var hash=Secrets.Hash(r.Code);
            var g=await db.GuangzhouDevices.SingleOrDefaultAsync(x=>x.BindingHash==hash && !x.Enrolled && x.ExpiresAt>Clock.Now) ?? throw new ApiException(401,"绑定码已使用或过期");
            Guard.That(await db.GuangzhouDevices.Where(x=>x.Id==g.Id&&!x.Enrolled).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.Enrolled,true))==1,"绑定码已使用",409);
            var d=await db.Devices.SingleAsync(x=>x.Id==g.Id); Guard.That(d.Enabled,"设备已停用",403);
            var campus=await db.Campuses.SingleOrDefaultAsync(x=>x.Id==d.CampusId)??throw new ApiException(403,"校区已停用");
            var secret=Secrets.New(); d.TokenHash=Secrets.Hash(secret);await db.SaveChangesAsync();await tx.CommitAsync();
            return new {credential=d.Id+"."+secret,id=d.Id,campus_id=g.CampusNumber,campus_name=campus.Name,queue_id=g.QueueId,protocol_version=2};
        }).RequireRateLimiting("auth");
        var api=app.MapGroup("/api/v2/agent").AddEndpointFilter(Authenticate);
        api.MapGet("/identity",async(HttpContext h,Data db)=>{var(d,g)=Identity(h);var c=await db.Campuses.SingleAsync(x=>x.Id==d.CampusId);return new{id=d.Id,queue_id=g.QueueId,campus_id=g.CampusNumber,campus_name=g.Legacy?g.IdentityCampusName:c.Name,protocol_version=2,protocol_revision=3};});
        api.MapPost("/heartbeat",async(GuangzhouHeartbeat r,HttpContext h,Data db)=>{
            Guard.That(r.Availability is "ready" or "paused" or "attention","状态无效");var(d,g)=Identity(h);
            if(g.Legacy)await GuangzhouLegacy.Call(h,"POST","/api/v2/agent/heartbeat",Json.Write(new{availability=r.Availability}));
            d.LastSeen=Clock.Now;g.Availability=r.Availability;
            foreach(var p in await db.Printers.Where(x=>x.DeviceId==d.Id).ToListAsync()){p.Status=r.Availability=="ready"?"ready":"paused";p.StatusAt=Clock.Now;p.StatusDetail=r.Availability=="attention"?"结果待核实":r.Availability=="paused"?"助手已暂停":"助手在线，驱动状态由广州助手核查";}
            await db.SaveChangesAsync();return new{ok=true};
        });
        api.MapPost("/claim",async(HttpContext h,Data db)=>{
            var(d,g)=Identity(h);await using var tx=await db.Database.BeginTransactionAsync();await Lock(db,g.Id);
            // The device row serializes head selection across processes; no in-memory mutex.
            var availability=await db.GuangzhouDevices.AsNoTracking().Where(x=>x.Id==g.Id).Select(x=>x.Availability).SingleAsync();
            if(availability!="ready")return Results.Ok(new{job=(object?)null,reason="paused"});
            var j=await ActiveQueue(db).Where(x=>x.DeviceId==d.Id).OrderBy(x=>x.CreatedAt).ThenBy(x=>x.Id).FirstOrDefaultAsync();
            if(g.Legacy && (j==null||j.State=="queued")){
                var old=await GuangzhouLegacy.Call(h,"POST","/api/v2/agent/claim","{}");
                using var reply=JsonDocument.Parse(old.Body);
                if(reply.RootElement.TryGetProperty("job",out var oldJob)&&oldJob.ValueKind!=JsonValueKind.Null){await tx.CommitAsync();return old;}
                // A generating or unresolved old head must not be bypassed by a new-platform task.
                if(!reply.RootElement.TryGetProperty("reason",out var reason)||reason.GetString()!="empty"){await tx.CommitAsync();return old;}
            }
            if(j==null)return Results.Ok(new{job=(object?)null,reason="empty"});
            Guard.That(await db.Printers.AnyAsync(p=>p.Id==j.PrinterId&&p.DeviceId==d.Id&&p.Enabled&&!p.Suppressed),"打印机已停用",409);
            var v=await db.GuangzhouJobs.SingleOrDefaultAsync(x=>x.Id==j.Id);
            // Missing preparation is recoverable only before the assistant has ever claimed it.
            // Do not turn an unknown execution into another printable task.
            if(v==null)return Results.Ok(new{job=(object?)null,reason=j.State=="queued"?"preparing":"needs_review"});
            bool first=v.State=="queued";
            if(first){v.State="claimed";j.State="claimed";j.LeaseUntil=null;}
            d.LastSeen=Clock.Now;await db.SaveChangesAsync();await tx.CommitAsync();return Results.Ok(new{job=Output(j,v,g,!first)});
        }).WithMetadata(new ClaimPoll());
        api.MapGet("/jobs/{id:guid}",async(Guid id,HttpContext h,Data db)=>{var(d,g)=Identity(h);var(j,v)=await Job(db,d.Id,id);return Output(j,v,g,true);});
        api.MapGet("/jobs/{id:guid}/pdf",async(Guid id,HttpContext h,Data db,IObjects objects)=>{
            var(d,g)=Identity(h);var(_,v)=await Job(db,d.Id,id);var bytes=await Read(objects,v.PdfKey);
            Guard.That(bytes.LongLength==v.Bytes && Digest(bytes)==v.Hash,"文件校验失败，禁止打印",409);return Results.File(bytes,"application/pdf");
        });
        api.MapPost("/jobs/{id:guid}/status",async(Guid id,GuangzhouReceipt r,HttpContext h,Data db)=>{
            var(d,g)=Identity(h);await using var tx=await db.Database.BeginTransactionAsync();await Lock(db,g.Id);var(j,v)=await Job(db,d.Id,id);
            Check(v,g,r.QueueId,r.Hash,r.Bytes,r.ExecutionKey);Guard.That(r.State is "dispatching" or "spooling" or "submitted" or "uncertain" or "failed","状态无效");
            Guard.That(r.State=="failed"||r.ExecutionKey is not null && r.ExecutionKey!=Guid.Empty,"缺少执行标识",409);
            if(r.Evidence!=null)ValidateEvidence(r.Evidence.Value,r.State);
            if(r.State is "spooling" or "submitted"){
                Guard.That(r.Evidence!=null,"缺少Windows执行证据",409);ValidateEvidence(r.Evidence!.Value,r.State);
                if(v.EvidenceJson!=null){var old=JsonDocument.Parse(v.EvidenceJson).RootElement;foreach(var k in new[]{"job_id","document","printer","user","machine"})Guard.That(old.GetProperty(k).ToString()==r.Evidence.Value.GetProperty(k).ToString(),"Windows作业关联冲突",409);}
            }
            if(v.State==r.State){Guard.That(v.ExecutionKey==r.ExecutionKey,"执行标识冲突",409);return Results.Ok(new{state=v.State,transition_applied=false});}
            var allowed=(v.State,r.State) is ("claimed","dispatching") or ("dispatching","spooling") or ("spooling","submitted") or ("claimed","uncertain") or ("dispatching","uncertain") or ("spooling","uncertain");
            Guard.That(allowed||v.State=="claimed"&&r.State=="failed"&&r.NotExecuted,"状态不允许回退或自动重打",409);
            v.State=r.State;v.ExecutionKey??=r.ExecutionKey;if(r.Evidence!=null)v.EvidenceJson=r.Evidence.Value.GetRawText();
            j.State=r.State is "dispatching" or "spooling"?"submitting":r.State;j.Detail=r.State=="submitted"?"广州助手已确认Windows原作业结束；不代表现场确认出纸":r.State=="uncertain"?"结果待核实，广州队列暂停":"广州助手："+r.State;
            db.Audits.Add(new Audit{Action="print.guangzhou."+r.State,TargetId=j.Id});await db.SaveChangesAsync();await tx.CommitAsync();return Results.Ok(new{state=v.State,transition_applied=true});
        });
        api.MapPost("/jobs/{id:guid}/resolve",async(Guid id,GuangzhouResolution r,HttpContext h,Data db)=>{
            var(d,g)=Identity(h);await using var tx=await db.Database.BeginTransactionAsync();await Lock(db,g.Id);var(j,v)=await Job(db,d.Id,id);
            Check(v,g,r.QueueId,r.Hash,r.Bytes,r.ExecutionKey);
            Guard.That(r.ExecutionKey!=Guid.Empty&&r.Conclusion is "confirmed_output" or "closed_without_reprint"&&r.Source=="onsite_operator"&&r.Operator.Length is >0 and <=80&&r.Note.Length is >=3 and <=512&&r.SpoolerCheck is "terminal" or "missing_checked","核实信息不完整");
            var value=Json.Write(r);
            if(v.State=="resolved"){Guard.That(v.ResolutionJson==value,"已登记不同核实结论",409);return Results.Ok(new{state="resolved",transition_applied=false,resolution=JsonDocument.Parse(value).RootElement});}
            Guard.That(v.State is "uncertain" or "dispatching" or "spooling" || v.State=="claimed"&&v.ExecutionKey==null&&r.Conclusion=="closed_without_reprint","此状态不允许人工收尾",409);
            v.State="resolved";v.ExecutionKey??=r.ExecutionKey;v.ResolutionJson=value;j.State=r.Conclusion=="confirmed_output"?"submitted":"failed";j.Detail=r.Conclusion=="confirmed_output"?"现场人员确认已出纸":"现场收尾，未自动重印，实际出纸结果未知";
            db.Audits.Add(new Audit{Action="print.guangzhou.resolved",TargetId=j.Id});await db.SaveChangesAsync();await tx.CommitAsync();return Results.Ok(new{state="resolved",transition_applied=true,resolution=JsonDocument.Parse(value).RootElement});
        });
    }
    static (Device,GuangzhouDevice) Identity(HttpContext h)=>((Device)h.Items["gz-device"]!,(GuangzhouDevice)h.Items["gz-protocol"]!);
    static Task<int> Lock(Data db,Guid id)=>db.GuangzhouDevices.Where(x=>x.Id==id).ExecuteUpdateAsync(s=>s.SetProperty(x=>x.Revision,x=>x.Revision+1));
    static async Task<(PrintJob,GuangzhouJob)> Job(Data db,Guid device,Guid id){var j=await db.PrintJobs.SingleOrDefaultAsync(x=>x.Id==id&&x.DeviceId==device&&x.State!="queued")??throw new ApiException(404,"设备无权访问此任务");return(j,await db.GuangzhouJobs.SingleAsync(x=>x.Id==id));}
    static object Output(PrintJob j,GuangzhouJob v,GuangzhouDevice g,bool reconcile)=>new{id=j.Id,queue_id=g.QueueId,protocol_version=2,state=v.State,pdf_sha256=v.Hash,pdf_bytes=v.Bytes,pages=v.Pages,parameters=Parameters,pdf_url=$"/api/v2/agent/jobs/{j.Id}/pdf",reconcile_only=reconcile,execution_key=v.ExecutionKey,execution=v.EvidenceJson==null?(JsonElement?)null:JsonDocument.Parse(v.EvidenceJson).RootElement,resolution=v.ResolutionJson==null?(JsonElement?)null:JsonDocument.Parse(v.ResolutionJson).RootElement,created_at=j.CreatedAt};
    static void Check(GuangzhouJob v,GuangzhouDevice g,Guid queue,string hash,long bytes,Guid? key){Guard.That(queue==g.QueueId&&hash==v.Hash&&bytes==v.Bytes,"任务来源或文件不一致",409);Guard.That(v.ExecutionKey==null||v.ExecutionKey==key,"执行标识冲突",409);}
    static void ValidateEvidence(JsonElement e,string state){
        Guard.That(e.ValueKind==JsonValueKind.Object&&e.GetRawText().Length<=4096,"Windows证据格式无效",409);
        var keys=new[]{"job_id","document","printer","user","machine","terminal"};Guard.That(e.EnumerateObject().Select(p=>p.Name).Order().SequenceEqual(keys.Order()),"Windows证据字段无效",409);
        Guard.That(e.GetProperty("job_id").TryGetUInt32(out var id)&&id>0,"Windows作业ID无效",409);
        foreach(var k in keys.Skip(1).Take(4))Guard.That(e.GetProperty(k).ValueKind==JsonValueKind.String&&e.GetProperty(k).GetString()!.Length is >0 and <=256,"Windows关联字段无效",409);
        var terminal=e.GetProperty("terminal").ToString();Guard.That(e.GetProperty("document").GetString()!.StartsWith("SiyueV2-")&&terminal is "none" or "printed" or "complete"&& (state!="submitted"||terminal!="none"),"缺少Windows原作业终态",409);
    }
    static string Digest(byte[] bytes)=>Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    static async Task<byte[]> Read(IObjects objects,string key){await using var s=await objects.Get(key);using var m=new MemoryStream();var buffer=new byte[81920];int n;while((n=await s.ReadAsync(buffer))>0){Guard.That(m.Length+n<=MaxBytes,"广州打印文件超过128MB");m.Write(buffer,0,n);}return m.ToArray();}
    public static async Task Prepare(Data db,IObjects objects,PrintJob[] jobs){
        if(!await db.GuangzhouDevices.AnyAsync(x=>x.Id==jobs[0].DeviceId))return;
        foreach(var j in jobs){var doc=await db.Documents.SingleAsync(x=>x.Id==j.DocumentId);var raw=await Read(objects,doc.PdfKey);Guard.That(Digest(raw).Equals(doc.PdfHash,StringComparison.OrdinalIgnoreCase),"原文档校验失败",409);
            var result=Expand(raw,j.Copies,j.Duplex);var key="guangzhou-print/"+j.Id+".pdf";using var stream=new MemoryStream(result.Bytes);await objects.Put(key,stream,"application/pdf");
            db.GuangzhouJobs.Add(new GuangzhouJob{Id=j.Id,PdfKey=key,Hash=Digest(result.Bytes),Bytes=result.Bytes.Length,Pages=result.Pages});}
    }
    public static (byte[] Bytes,int Pages) Expand(byte[] raw,int copies,bool duplex){
        Guard.That(copies is >=1 and <=99,"份数无效");using var pdf=PdfDocument.Open(raw);var per=duplex?pdf.NumberOfPages+(pdf.NumberOfPages%2):pdf.NumberOfPages*2;
        Guard.That(pdf.NumberOfPages>0&&per*copies<=200,"广州交接版单任务最多200面（含份数及补白），请分批打印");
        var builder=new PdfDocumentBuilder();for(var copy=0;copy<copies;copy++){for(var page=1;page<=pdf.NumberOfPages;page++){builder.AddPage(pdf,page);if(!duplex)builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);}if(duplex&&pdf.NumberOfPages%2==1)builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);}
        var result=builder.Build();Guard.That(result.LongLength<=MaxBytes,"广州打印文件超过128MB");return(result,per*copies);
    }
}
