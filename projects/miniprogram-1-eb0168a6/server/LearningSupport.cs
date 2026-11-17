using System.Text.Json;
using Microsoft.EntityFrameworkCore;
namespace Siyue;
public class LearningArtifact : Entity
{
    public Guid StudentId { get; set; } public Guid ClassId { get; set; } public Guid CourseId { get; set; } public Guid StaffId { get; set; }
    public string Kind { get; set; } = "variants"; public string State { get; set; } = "queued"; public string RequestKey { get; set; } = "";
    public string PayloadJson { get; set; } = "{}"; public string? Error { get; set; } public int Revision { get; set; } public Guid? DocumentId { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public record VariantQuestion(Guid SourceQuestionId, string Question, string Answer, string Explanation, string KnowledgePoint, string Method, string Difficulty);
public record VariantPayload(Guid[] SourceQuestionIds, int PerQuestion, VariantQuestion[] Items);
public record VariantInput(Guid CourseId, Guid[] QuestionIds, int PerQuestion, string RequestKey);
public record SupportReview(int Revision, VariantQuestion[]? Items = null, string? TeacherNote = null);
public record ReportInput(Guid CourseId, DateTimeOffset? From = null, DateTimeOffset? To = null, string Stage = "阶段学习反馈");
public record ReportFacts(string StudentName, string CourseName, string Stage, DateTimeOffset? From, DateTimeOffset? To, int MarkedQuestions, int WrongQuestions, int RetestedQuestions, int LatestRetestCorrect, int FailedRetestEvents, string[] LectureSummaries, string[] Evidence, string TeacherNote, string[]? KnowledgeSummaries = null, string[]? Suggestions = null);
public static class LearningSupport
{
    public static void Validate(VariantQuestion[] items, Guid[] sources, int expected)
    {
        Guard.That(items != null && sources.Length > 0 && items.Length == expected && items.All(x => x != null && sources.Contains(x.SourceQuestionId) && !string.IsNullOrWhiteSpace(x.Question) && !string.IsNullOrWhiteSpace(x.Answer) && !string.IsNullOrWhiteSpace(x.Explanation) && !string.IsNullOrWhiteSpace(x.KnowledgePoint) && !string.IsNullOrWhiteSpace(x.Method) && x.Difficulty is "基础" or "同等" or "提高" && x.Question.Length <= 8000 && x.Answer.Length <= 4000 && x.Explanation.Length <= 8000 && x.KnowledgePoint.Length <= 200 && x.Method.Length <= 1000), "变式题内容不完整、数量异常或原题关联错误", 422);
        Guard.That(items.Select(x=>x.Question.Trim()).Distinct().Count()==items.Length,"生成的变式题存在重复，请重新生成或校订",422);
        Guard.That(sources.All(id => items.Count(x=>x.SourceQuestionId==id)==expected/sources.Length), "每道原题必须有对应的变式题",422);
    }
    static async Task<LearningArtifact> Scope(Guid id, Data db, Access a) { var x=await db.LearningArtifacts.FindAsync(id) ?? throw new ApiException(404,"学习资料不存在");await a.Class(x.ClassId,x.CourseId);var student=await StudentHistory.StudentScope(x.StudentId,x.CourseId,db,a);Guard.That(student.ClassId==x.ClassId,"学生已调整班级，请联系管理员处理历史资料",403);return x; }
    public static void MapLearningSupport(this WebApplication app)
    {
        app.MapGet("/api/students/{id:guid}/learning-support",async(Guid id,Guid courseId,Data db,Access a,LearningModelSettings modelSettings)=>{
            var config=await modelSettings.Resolve();
            await StudentHistory.StudentScope(id,courseId,db,a);
            return new {generationAvailable=VariantGenerator.Configured(config),items=await db.LearningArtifacts.Where(x=>x.StudentId==id&&x.CourseId==courseId).OrderByDescending(x=>x.CreatedAt).Take(100).Select(x=>new{x.Id,x.Kind,x.State,x.Error,x.CreatedAt,x.DocumentId}).ToListAsync()};
        });
        app.MapPost("/api/students/{id:guid}/variants",async(Guid id,VariantInput input,Data db,Access a,LearningModelSettings modelSettings)=>{
            var config=await modelSettings.Resolve();
            var student=await StudentHistory.StudentScope(id,input.CourseId,db,a);Guard.That(input.QuestionIds.Length is >0 and <=10 && input.QuestionIds.Distinct().Count()==input.QuestionIds.Length && input.PerQuestion is >0 and <=3 && input.RequestKey.Length is >0 and <=100,"请选择 1–10 道原题，每题生成 1–3 道变式");
            var history=await StudentHistory.Items(student,input.CourseId,db,a);Guard.That(input.QuestionIds.All(q=>history.Any(x=>x.QuestionId==q)),"原题不属于该学生的历史错题",403);
            var old=await db.LearningArtifacts.SingleOrDefaultAsync(x=>x.StudentId==id&&x.RequestKey==input.RequestKey);
            if(old!=null){var p=Json.Read<VariantPayload>(old.PayloadJson);Guard.That(old.Kind=="variants"&&old.CourseId==input.CourseId&&p.PerQuestion==input.PerQuestion&&p.SourceQuestionIds.Order().SequenceEqual(input.QuestionIds.Order()),"请求标识已用于其他生成任务",409);return Results.Accepted(value:new{old.Id,old.State});}
            Guard.That(VariantGenerator.Configured(config),"尚未配置变式生成服务，请管理员配置后使用；历史汇编与学情报告不受影响",503);
            var artifact=new LearningArtifact {StudentId=id,ClassId=student.ClassId,CourseId=input.CourseId,StaffId=a.User.Id,RequestKey=input.RequestKey,PayloadJson=Json.Write(new VariantPayload(input.QuestionIds,input.PerQuestion,[]))};db.LearningArtifacts.Add(artifact);db.Work.Add(new WorkItem{Kind="variants",EntityId=artifact.Id});db.Audits.Add(new Audit {StaffId=a.User.Id,Action="variants.generate",TargetId=artifact.Id});await db.SaveChangesAsync();return Results.Accepted(value:new{artifact.Id,artifact.State});
        });
        app.MapPost("/api/students/{id:guid}/reports",async(Guid id,ReportInput input,Data db,Access a)=>{
            var student=await StudentHistory.StudentScope(id,input.CourseId,db,a);Guard.That(input.Stage.Length is >0 and <=100 && (!input.From.HasValue || !input.To.HasValue || input.From<=input.To),"阶段或日期范围无效");
            var all=await StudentHistory.Items(student,input.CourseId,db,a);var history=all.Where(x=>x.Events.Any(e=>e.State=="wrong"&&(!input.From.HasValue||e.RecordedAt>=input.From)&&(!input.To.HasValue||e.RecordedAt<=input.To))).ToArray();
            var events=await db.LearningEvents.Where(x=>x.StudentId==id&&x.ClassId==student.ClassId&&x.CourseId==input.CourseId&&(!input.From.HasValue||x.RecordedAt>=input.From)&&(!input.To.HasValue||x.RecordedAt<=input.To)).ToListAsync();
            var retests=all.SelectMany(x=>x.Events).Where(e=>e.Kind=="retest"&&(!input.From.HasValue||e.RecordedAt>=input.From)&&(!input.To.HasValue||e.RecordedAt<=input.To)).GroupBy(e=>e.QuestionId).ToArray();
            var facts=new ReportFacts(student.Name,(await db.Courses.FindAsync(input.CourseId))!.Name,input.Stage.Trim(),input.From,input.To,events.Where(e=>e.Kind!="retest" && e.State is "correct" or "wrong" or "unfinished").Select(e=>e.QuestionId).Union(history.Select(h=>h.QuestionId)).Distinct().Count(),history.Length,retests.Length,retests.Count(g=>g.OrderByDescending(e=>e.RecordedAt).First().State=="correct"),retests.Sum(g=>g.Count(e=>e.State=="wrong")),history.GroupBy(x=>new{x.Sequence,x.Title}).Select(g=>$"第{g.Key.Sequence}讲 · {g.Key.Title}：记录错题 {g.Count()} 道").ToArray(),history.Select(x=>$"第{x.Sequence}讲 {x.Label}（v{x.Version}）：期间错误记录 {x.Events.Count(e=>e.State=="wrong"&&(!input.From.HasValue||e.RecordedAt>=input.From)&&(!input.To.HasValue||e.RecordedAt<=input.To))} 次").ToArray(),"");
            var reviewed = await db.LearningArtifacts.Where(x=>x.StudentId==id && x.CourseId==input.CourseId && x.ClassId==student.ClassId && x.Kind=="variants" && x.State=="approved").Select(x=>x.PayloadJson).ToListAsync();
            var historyIds=history.Select(x=>x.QuestionId).ToHashSet();
            var knowledge=reviewed.SelectMany(p=>Json.Read<VariantPayload>(p).Items).Where(x=>historyIds.Contains(x.SourceQuestionId)).GroupBy(x=>x.KnowledgePoint).Select(g=>$"{g.Key}：关联历史错题 {g.Select(x=>x.SourceQuestionId).Distinct().Count()} 道（教师已审核）").ToArray();
            var testedIds=retests.Select(g=>g.Key).ToHashSet(); var untested=history.Count(x=>!testedIds.Contains(x.QuestionId));
            facts=facts with {KnowledgeSummaries=knowledge,Suggestions=new[]{ $"本阶段有 {untested} 道错题尚无复测记录，建议先安排独立重做，再记录结果。", $"本阶段最后一次复测仍错误的题目有 {retests.Count(g=>g.OrderByDescending(e=>e.RecordedAt).First().State=="wrong")} 道，建议教师结合原题分析错误原因后安排对应练习。" }};
            var artifact=new LearningArtifact {StudentId=id,ClassId=student.ClassId,CourseId=input.CourseId,StaffId=a.User.Id,Kind="report",State="draft",RequestKey=Guid.NewGuid().ToString("N"),PayloadJson=Json.Write(facts)};db.LearningArtifacts.Add(artifact);db.Audits.Add(new Audit{StaffId=a.User.Id,Action="report.create",TargetId=artifact.Id});await db.SaveChangesAsync();return new{artifact.Id,artifact.State};
        });
        app.MapGet("/api/learning-support/{id:guid}",async(Guid id,Data db,Access a,Files files)=>{
            var x=await Scope(id,db,a);var sourceQuestions=Array.Empty<object>();
            if(x.Kind=="variants") {var student=await StudentHistory.StudentScope(x.StudentId,x.CourseId,db,a);var ids=Json.Read<VariantPayload>(x.PayloadJson).SourceQuestionIds;sourceQuestions=(await StudentHistory.Items(student,x.CourseId,db,a,files,ids)).Where(q=>ids.Contains(q.QuestionId)).Select(q=>(object)new{q.QuestionId,q.Label,q.Sequence,q.Version,q.Text,q.Previews}).ToArray();}
            return new{x.Id,x.StudentId,x.CourseId,x.Kind,x.State,x.Error,x.Revision,x.DocumentId,sourceQuestions,payload=JsonSerializer.Deserialize<JsonElement>(x.PayloadJson)};
        });
        app.MapPost("/api/learning-support/{id:guid}/retry",async(Guid id,Data db,Access a)=>{
            var x=await Scope(id,db,a);Guard.That(x.Kind=="variants","此资料无需重新生成",409);await using var tx=await db.Database.BeginTransactionAsync();Guard.That(await db.LearningArtifacts.Where(y=>y.Id==id&&y.State=="failed").ExecuteUpdateAsync(s=>s.SetProperty(y=>y.State,"queued").SetProperty(y=>y.Error,(string?)null))==1,"当前任务无需重试",409);db.Work.Add(new WorkItem{Kind="variants",EntityId=id});await db.SaveChangesAsync();await tx.CommitAsync();return Results.Accepted();
        });
        app.MapPut("/api/learning-support/{id:guid}",async(Guid id,SupportReview input,Data db,Access a)=>{
            var x=await Scope(id,db,a);Guard.That(x.State=="draft","仅可编辑待审核资料",409);string payload;
            if(x.Kind=="variants"){var p=Json.Read<VariantPayload>(x.PayloadJson);Guard.That(input.Items!=null,"请填写变式题");Validate(input.Items!,p.SourceQuestionIds,p.SourceQuestionIds.Length*p.PerQuestion);payload=Json.Write(p with {Items=input.Items!});}
            else{Guard.That(input.TeacherNote!=null&&input.TeacherNote.Length<=8000,"教师反馈最长 8000 字");payload=Json.Write(Json.Read<ReportFacts>(x.PayloadJson) with {TeacherNote=input.TeacherNote!});}
            Guard.That(await db.LearningArtifacts.Where(a=>a.Id==id&&a.State=="draft"&&a.Revision==input.Revision).ExecuteUpdateAsync(s=>s.SetProperty(a=>a.PayloadJson,payload).SetProperty(a=>a.Revision,input.Revision+1))==1,"资料已变更，请刷新",409);db.Audits.Add(new Audit{StaffId=a.User.Id,Action=x.Kind+".edit",TargetId=id});await db.SaveChangesAsync();return new{revision=input.Revision+1};
        });
        app.MapPost("/api/learning-support/{id:guid}/approve",async(Guid id,PublishInput input,Data db,Access a)=>{
            var x=await Scope(id,db,a);if(x.State=="approved")return Results.Ok(new{id=x.DocumentId});
            Guard.That(x.State=="draft","资料尚未生成完成",409);await using var tx=await db.Database.BeginTransactionAsync();Guard.That(await db.LearningArtifacts.Where(a=>a.Id==id&&a.State=="draft"&&a.Revision==input.Revision).ExecuteUpdateAsync(s=>s.SetProperty(a=>a.State,"approved").SetProperty(a=>a.Revision,input.Revision+1))==1,"资料已变更，请刷新",409);
            var student=await db.Students.FindAsync(x.StudentId);var doc=new ExportDocument{BatchId=Guid.NewGuid(),StudentId=x.StudentId,ClassId=x.ClassId,CourseId=x.CourseId,StaffId=a.User.Id,StudentName=student!.Name,Kind=x.Kind,SnapshotJson=x.PayloadJson,SourceFileName=x.Kind=="report"?"学情报告":"变式训练"};db.Documents.Add(doc);db.Work.Add(new WorkItem{Kind="support",EntityId=doc.Id});x.State="approved";x.Revision=input.Revision+1;x.DocumentId=doc.Id;db.Audits.Add(new Audit{StaffId=a.User.Id,Action=x.Kind+".approve",TargetId=id});await db.SaveChangesAsync();await tx.CommitAsync();return Results.Ok(new{id=doc.Id});
        });
    }
    public static async Task Generate(Guid id,Data db,IObjects objects,IConfiguration config,IHttpClientFactory clients,CancellationToken ct)
    {
        var artifact=await db.LearningArtifacts.FindAsync([id],ct)??throw new ApiException(404,"资料不存在");if(artifact.State is "draft" or "approved")return;
        artifact.State="processing";artifact.Error=null;await db.SaveChangesAsync(ct);var payload=Json.Read<VariantPayload>(artifact.PayloadJson);
        var questions=await db.Questions.Where(q=>payload.SourceQuestionIds.Contains(q.Id)).ToListAsync(ct);var sources=new List<VariantSource>(); long imageBytes=0;
        foreach(var q in questions){var images=new List<string>();foreach(var key in Json.Read<string[]>(q.PreviewsJson)){await using var stream=await objects.Get(key,ct);using var memory=new MemoryStream();await stream.CopyToAsync(memory,ct);imageBytes+=memory.Length;Guard.That(memory.Length<=4*1024*1024 && imageBytes<=12*1024*1024,"原题图片过大，请减少单次生成题数",422);images.Add(Convert.ToBase64String(memory.ToArray()));}sources.Add(new(q.Id,Json.Read<ContentBlock[]>(q.BlocksJson),images.ToArray()));}
        var result=await VariantGenerator.Generate(config,clients,payload.PerQuestion,sources.ToArray(),ct);
        Validate(result,payload.SourceQuestionIds,payload.SourceQuestionIds.Length*payload.PerQuestion);
        artifact.PayloadJson=Json.Write(payload with{Items=result});artifact.State="draft";await db.SaveChangesAsync(ct);
    }
    public record VariantResponse(VariantQuestion[] Items);
}
