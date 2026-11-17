using System.Text.Json;
using System.Threading.Channels;
using Microsoft.EntityFrameworkCore;
namespace Siyue;

public sealed record TeachingJobInput(Guid ClassId,string Kind,Guid RequestId,JsonElement Input);
public sealed record TeachingReview(bool Reviewed);
public sealed record TeachingJob {
    public Guid Id {get;init;} public Guid Owner {get;init;} public Guid ClassId {get;init;}
    public string Kind {get;init;}=""; public string Fingerprint {get;init;}="";
    public JsonElement Input {get;init;} public JsonElement? Output {get;init;}
    public string State {get;init;}="queued"; public string Error {get;init;}="";
    public string Model {get;init;}=""; public DateTimeOffset CreatedAt {get;init;}=Clock.Now;
    public DateTimeOffset UpdatedAt {get;init;}=Clock.Now; public DateTimeOffset? ReviewedAt {get;init;}
    public int Revision {get;init;}
}
public sealed class TeachingJobStore(IConfiguration config,TeachingAdmission admission,ILogger<TeachingJobStore> log)
{
    readonly SemaphoreSlim gate=new(1);readonly Channel<(Guid Owner,Guid Id)> queue=Channel.CreateUnbounded<(Guid,Guid)>();
    readonly Dictionary<(Guid,Guid),CancellationTokenSource> running=[];
    string Root=>Path.GetFullPath(config["TeachingTools:Directory"]??Path.Combine(config["Research:Directory"]??".local/research","teaching-jobs"));
    string FileFor(Guid owner,Guid id)=>Path.Combine(Root,owner.ToString("N"),id.ToString("N")+".json");
    public ChannelReader<(Guid Owner,Guid Id)> Queue=>queue.Reader;
    async Task Write(TeachingJob job) {
        var path=FileFor(job.Owner,job.Id);Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try{await File.WriteAllTextAsync(temp,Json.Write(job));File.Move(temp,path,true);}finally{if(File.Exists(temp))File.Delete(temp);}
    }
    public async Task<TeachingJob> Load(Guid owner,Guid id) {
        var path=FileFor(owner,id);Guard.That(File.Exists(path),"任务不存在或无权访问",404);
        var job=Json.Read<TeachingJob>(await File.ReadAllTextAsync(path));Guard.That(job.Owner==owner&&job.Id==id,"任务不存在",404);return job;
    }
    public async Task<TeachingJob[]> List(Guid owner) {
        var dir=Path.Combine(Root,owner.ToString("N"));if(!Directory.Exists(dir))return [];
        var list=new List<TeachingJob>();foreach(var path in Directory.EnumerateFiles(dir,"*.json")) {
            try {var job=Json.Read<TeachingJob>(await File.ReadAllTextAsync(path));if(job.Owner==owner)list.Add(job);}
            catch(JsonException){log.LogError("Invalid teaching record {File}; retained for recovery",Path.GetFileName(path));}
        }
        return list.OrderByDescending(x=>x.CreatedAt).ToArray();
    }
    public async Task<TeachingJob> Create(Guid owner,TeachingJobInput request) {
        await gate.WaitAsync();try {
            var fingerprint=Secrets.Hash(Json.Write(new{request.ClassId,request.Kind,request.Input}));
            if(File.Exists(FileFor(owner,request.RequestId))) {var old=await Load(owner,request.RequestId);Guard.That(old.Fingerprint==fingerprint,"请求标识已用于其他内容，请刷新后重试",409);return old;}
            var previous=await List(owner);
            Guard.That(previous.Length<500,"已保存500份教学记录，请联系管理员归档后继续",409);
            Guard.That(previous.Count(x=>x.State is "queued" or "running")<3,"已有3个生成任务，请等待或取消后再试",429);
            admission.Admit(owner,request.Kind);
            var job=new TeachingJob{Id=request.RequestId,Owner=owner,ClassId=request.ClassId,Kind=request.Kind,Input=request.Input.Clone(),Fingerprint=fingerprint};
            await Write(job);queue.Writer.TryWrite((owner,job.Id));return job;
        }finally{gate.Release();}
    }
    public async Task Initialize() {
        await gate.WaitAsync();try {
            if(!Directory.Exists(Root))return;
            foreach(var path in Directory.EnumerateFiles(Root,"*.json",SearchOption.AllDirectories)) {
                TeachingJob job;try{job=Json.Read<TeachingJob>(await File.ReadAllTextAsync(path));}
                catch(JsonException){log.LogError("Invalid teaching record {File}; retained for recovery",Path.GetFileName(path));continue;}
                if(Path.GetFullPath(path)!=FileFor(job.Owner,job.Id)){log.LogError("Teaching record path mismatch {File}",Path.GetFileName(path));continue;}
                if(job.State=="running")await Write(job with{State="failed",Error="服务重启中断了生成，输入已保留；请点击重试",UpdatedAt=Clock.Now,Revision=job.Revision+1});
                else if(job.State=="queued")queue.Writer.TryWrite((job.Owner,job.Id));
            }
        }finally{gate.Release();}
    }
    public async Task<TeachingJob?> Begin(Guid owner,Guid id,CancellationTokenSource cancel) {
        await gate.WaitAsync();try {var job=await Load(owner,id);if(job.State!="queued")return null;running[(owner,id)]=cancel;job=job with{State="running",UpdatedAt=Clock.Now,Revision=job.Revision+1};await Write(job);return job;}finally{gate.Release();}
    }
    public async Task Finish(Guid owner,Guid id,string state,JsonElement? output,string error,string model="") {
        await gate.WaitAsync();try {var job=await Load(owner,id);running.Remove((owner,id));if(job.State=="cancelled")return;await Write(job with{State=state,Output=output,Error=error,Model=model,UpdatedAt=Clock.Now,Revision=job.Revision+1});}finally{gate.Release();}
    }
    public async Task<TeachingJob> Cancel(Guid owner,Guid id) {
        await gate.WaitAsync();try {var job=await Load(owner,id);if(job.State is "queued" or "running") {if(running.TryGetValue((owner,id),out var cancel))cancel.Cancel();job=job with{State="cancelled",Error="已取消，输入仍保留",UpdatedAt=Clock.Now,Revision=job.Revision+1};await Write(job);}return job;}finally{gate.Release();}
    }
    public async Task<TeachingJob> Review(Guid owner,Guid id,bool reviewed) {
        await gate.WaitAsync();try {var job=await Load(owner,id);Guard.That(job.State=="succeeded","请等待生成完成",409);job=job with{ReviewedAt=reviewed?Clock.Now:null,Revision=job.Revision+1,UpdatedAt=Clock.Now};await Write(job);return job;}finally{gate.Release();}
    }
}
public static class TeachingJobs
{
    public static async Task Validate(TeachingJobInput r,Data db,Access access,CancellationToken ct) {
        Guard.That(r.RequestId!=Guid.Empty&&r.Kind is "lesson" or "essays" or "analysis"&&r.Input.ValueKind==JsonValueKind.Object&&r.Input.GetRawText().Length<12000,"生成请求无效");
        await access.Class(r.ClassId);
        if(r.Kind=="lesson") {var input=r.Input.Deserialize<EnglishTeachingTools.LessonInput>(Json.Options)!;Guard.That(input.ClassId==r.ClassId,"班级不一致");EnglishTeachingTools.Validate(input);}
        else if(r.Kind=="essays") {var input=r.Input.Deserialize<EnglishTeachingTools.EssayInput>(Json.Options)!;Guard.That(input.ClassId==r.ClassId,"班级不一致");EnglishTeachingTools.Validate(input);}
        else {
            Guard.That(r.Input.TryGetProperty("sessionId",out var value)&&value.TryGetGuid(out _),"请选择课次");
            var id=r.Input.GetProperty("sessionId").GetGuid();var session=await db.ClassSessions.AsNoTracking().SingleOrDefaultAsync(s=>s.Id==id,ct)??throw new ApiException(404,"课次不存在");
            Guard.That(session.ClassId==r.ClassId,"课次与班级不一致",403);await access.Class(session.ClassId,session.CourseId);
        }
    }
    public static void Map(RouteGroupBuilder group) {
        group.MapPost("/jobs",async(TeachingJobInput r,Data db,Access access,TeachingJobStore store,HttpContext context,CancellationToken ct)=>{
            try{await Validate(r,db,access,ct);}catch(JsonException){throw new ApiException(400,"生成请求字段不完整，请刷新后重试");}
            context.Items["teachingOperation"]="teaching."+r.Kind+".generate";
            var job=await store.Create(access.User.Id,r);return Results.Accepted(value:job);
        });
        group.MapGet("/jobs",async(Guid? classId,Data db,Access access,TeachingJobStore store)=>{
            if(classId.HasValue)await access.Class(classId.Value);
            var items=await store.List(access.User.Id);var visible=new List<object>();
            foreach(var j in items.Where(x=>classId==null||x.ClassId==classId).Take(100)) {
                try{await access.Class(j.ClassId);}catch(ApiException e)when(e.Status is 403 or 404){continue;}
                visible.Add(new{j.Id,j.ClassId,j.Kind,j.State,j.Error,j.CreatedAt,j.UpdatedAt,j.ReviewedAt});
            }
            return Results.Ok(visible);
        });
        group.MapGet("/jobs/{id:guid}",async(Guid id,Data db,Access access,TeachingJobStore store,CancellationToken ct)=>{
            var job=await store.Load(access.User.Id,id);await Validate(new(job.ClassId,job.Kind,job.Id,job.Input),db,access,ct);return Results.Ok(job);
        });
        group.MapPost("/jobs/{id:guid}/cancel",async(Guid id,Access access,TeachingJobStore store)=>{var job=await store.Load(access.User.Id,id);await access.Class(job.ClassId);return Results.Ok(await store.Cancel(access.User.Id,id));});
        group.MapPost("/jobs/{id:guid}/review",async(Guid id,TeachingReview r,Data db,Access access,TeachingJobStore store,CancellationToken ct)=>{var job=await store.Load(access.User.Id,id);await Validate(new(job.ClassId,job.Kind,job.Id,job.Input),db,access,ct);return Results.Ok(await store.Review(access.User.Id,id,r.Reviewed));});
        group.MapGet("/jobs/{id:guid}/word",async(Guid id,string audience,Data db,Access access,TeachingJobStore store,CancellationToken ct)=>{
            var job=await store.Load(access.User.Id,id);await Validate(new(job.ClassId,job.Kind,job.Id,job.Input),db,access,ct);
            Guard.That(job.Kind=="lesson"&&job.State=="succeeded"&&job.Output.HasValue,"备课尚未生成",409);
            Guard.That(audience is "student" or "teacher","请选择导出版本");
            Guard.That(audience!="student"||job.ReviewedAt.HasValue,"请先核对讲义、题目和答案，再确认复核后导出学生版",409);
            var lesson=job.Output!.Value.Deserialize<EnglishTeachingTools.Lesson>(Json.Options)!;var input=job.Input.Deserialize<EnglishTeachingTools.LessonInput>(Json.Options)!;
            return Results.File(WordDocuments.EnglishLesson(input.Unit,lesson,audience),WordDocuments.DocxType,$"英语备课-{(audience=="student"?"学生版":"教师版")}-{id.ToString("N")[..8]}.docx");
        });
    }
}
public sealed class TeachingJobWorker(TeachingJobStore store,IServiceScopeFactory scopes,IConfiguration config,ILogger<TeachingJobWorker> log):BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stop) {
        if(!config.GetValue("TeachingTools:WorkerEnabled",config.GetValue("Research:WorkerEnabled",false)))return;
        await store.Initialize();await Task.WhenAll(Loop(stop),Loop(stop));
    }
    async Task Loop(CancellationToken stop) {
        await foreach(var key in store.Queue.ReadAllAsync(stop)) {
            using var cancel=CancellationTokenSource.CreateLinkedTokenSource(stop);
            var job=await store.Begin(key.Owner,key.Id,cancel);if(job==null)continue;
            string model="";
            try {
                await using var scope=scopes.CreateAsyncScope();var services=scope.ServiceProvider;var db=services.GetRequiredService<Data>();
                var staff=await db.Staff.AsNoTracking().SingleOrDefaultAsync(s=>s.Id==job.Owner&&s.Enabled,cancel.Token)??throw new ApiException(403,"账号已停用，任务停止");
                Guard.That(!await db.PasswordAccounts.AnyAsync(p=>p.StaffId==staff.Id&&p.MustChangePassword,cancel.Token),"账号需修改密码，任务停止",428);
                var contexts=services.GetRequiredService<IHttpContextAccessor>();var old=contexts.HttpContext;
                try {
                    contexts.HttpContext=new DefaultHttpContext{RequestServices=services};contexts.HttpContext.Items["actor"]=new Actor(staff.Id,staff.Name,staff.Role);
                    var access=services.GetRequiredService<Access>();await TeachingJobs.Validate(new(job.ClassId,job.Kind,job.Id,job.Input),db,access,cancel.Token);
                    var settings=services.GetRequiredService<LearningModelSettings>();var clients=services.GetRequiredService<IHttpClientFactory>();model=(await settings.Resolve())["LearningAi:Model"]??"";
                    object output=job.Kind switch {
                        "lesson"=>await EnglishTeachingTools.MakeLesson(job.Input.Deserialize<EnglishTeachingTools.LessonInput>(Json.Options)!,settings,clients,cancel.Token),
                        "essays"=>await EnglishTeachingTools.MakeEssays(job.Input.Deserialize<EnglishTeachingTools.EssayInput>(Json.Options)!,settings,clients,cancel.Token),
                        _=>await ResearchClassAnalysis.DraftLessonPlan(job.Input.GetProperty("sessionId").GetGuid(),db,access,services.GetRequiredService<IObjects>(),settings,clients,cancel.Token)
                    };
                    // Recheck identity and class access after the provider call.
                    Guard.That(await db.Staff.AsNoTracking().AnyAsync(s=>s.Id==job.Owner&&s.Enabled,cancel.Token),"账号已停用，任务停止",403);
                    Guard.That(!await db.PasswordAccounts.AnyAsync(p=>p.StaffId==job.Owner&&p.MustChangePassword,cancel.Token),"账号需修改密码，任务停止",428);
                    await TeachingJobs.Validate(new(job.ClassId,job.Kind,job.Id,job.Input),db,access,cancel.Token);
                    await store.Finish(job.Owner,job.Id,"succeeded",JsonSerializer.SerializeToElement(output,Json.Options),"",model);
                    try{db.Audits.Add(new Audit{StaffId=job.Owner,TargetId=job.Id,Action="teaching."+job.Kind+".completed"});await db.SaveChangesAsync(cancel.Token);}catch(Exception){log.LogWarning("Teaching task {Job} saved but activity logging failed",job.Id);}
                }finally{contexts.HttpContext=old;}
            }catch(OperationCanceledException){await store.Finish(job.Owner,job.Id,"failed",null,stop.IsCancellationRequested?"服务维护中断了生成，输入已保留，请重试":"已取消",model);}
            catch(Exception e) {
                log.LogWarning("Teaching task {Job} failed ({Type})",job.Id,e.GetType().Name);
                await store.Finish(job.Owner,job.Id,"failed",null,e is ApiException?e.Message:"生成暂时失败，输入和以前的成果已保留，请重试",model);
                try{await using var scope=scopes.CreateAsyncScope();var db=scope.ServiceProvider.GetRequiredService<Data>();db.Audits.Add(new Audit{StaffId=job.Owner,TargetId=job.Id,Action="teaching."+job.Kind+".failed"});await db.SaveChangesAsync();}catch(Exception){/* The durable job itself retains the failure. */}
            }
        }
    }
}
