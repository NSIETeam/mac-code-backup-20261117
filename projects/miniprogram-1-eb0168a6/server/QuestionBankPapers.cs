using System.Collections.Concurrent;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
namespace Siyue;

public sealed record BankPaperInput(Guid ClassId,Guid[] CourseIds,Guid? StudentId=null,int QuestionCount=10,string Title="");
public sealed record BankPaperStudent(Guid StudentId,string Name,string Status,string Message,string[] QuestionIds);
public sealed record BankPaperDraft(Guid Id,Guid Owner,Guid ClassId,Guid[] CourseIds,string ClassName,string Subject,string Stage,string Title,int RequestedCount,DateTimeOffset CreatedAt,BankPaperStudent[] Papers,Dictionary<string,BankQuestion> Questions);
public sealed class QuestionBankPapers(IConfiguration config,IWebHostEnvironment env,ILogger<QuestionBankPapers> logger)
{
    readonly SemaphoreSlim gate=new(1,1);
    readonly ConcurrentDictionary<Guid,SemaphoreSlim> exportLocks=new();
    static readonly JsonSerializerOptions JsonOptions=new(JsonSerializerDefaults.Web);
    public string Root=>Path.Combine(Path.GetFullPath(config["QuestionBank:UploadsDirectory"]??Path.Combine(env.ContentRootPath,".local","question-bank-uploads")),"papers");
    string Dir(Guid id)=>Path.Combine(Root,id.ToString("N"));
    public static void Map(WebApplication app){
        app.MapPost("/api/question-bank/papers",async(BankPaperInput input,Access access,Data db,IQuestionBank bank,QuestionBankPapers papers)=>await papers.Create(input,access,db,bank));
        app.MapGet("/api/question-bank/papers/{id:guid}",async(Guid id,int? index,Access access,QuestionBankPapers papers)=>papers.View(await papers.Load(id,access),index??0));
        app.MapGet("/api/question-bank/papers/{id:guid}/export",async(Guid id,Guid? studentId,string? format,string? version,Access access,QuestionBankPapers papers,QuestionBankIntake intake,WordDocuments words,CancellationToken ct)=>{
            var draft=await papers.Load(id,access);var teacher=version=="teacher";Guard.That(version is null or "teacher" or "student","请选择学生卷或教师答案卷");format??="pdf";Guard.That(format is "pdf" or "docx","支持 PDF 或 Word 格式");
            var selected=draft.Papers.Where(p=>!studentId.HasValue||p.StudentId==studentId).ToArray();Guard.That(selected.Length>0,"学员不属于本次试卷",404);var available=selected.Where(p=>p.QuestionIds.Length>0).ToArray();Guard.That(available.Length>0,"当前没有可以导出的试卷，请先补充错题依据",409);
            var needed=available.SelectMany(p=>p.QuestionIds).Distinct().Select(q=>draft.Questions[q]).ToArray();var images=VerifyImages(draft.Owner,needed,intake);
            var semaphore=papers.exportLocks.GetOrAdd(id,_=>new(1,1));await semaphore.WaitAsync(ct);
            try{
                var prefix="numbered-"+(teacher?"teacher":"student")+"-"+(studentId?.ToString("N")??"all");var dir=Path.Combine(papers.Dir(id),prefix);Directory.CreateDirectory(dir);
                var docx=Path.Combine(dir,"paper.docx");if(!File.Exists(docx))await words.BankPaperDocx(draft,available,teacher,images,dir,ct);
                var path=docx;if(format=="pdf"){path=Path.Combine(dir,"paper.pdf");if(!File.Exists(path)){var converted=await words.Convert(docx,"pdf",dir,ct);File.Move(converted,path,true);}}
                return Results.File(path,format=="pdf"?"application/pdf":WordDocuments.DocxType,(studentId.HasValue?$"卷号{Array.FindIndex(draft.Papers,p=>p.StudentId==studentId)+1:D2}":draft.ClassName)+"-"+draft.Subject+(teacher?"-教师答案":"-个性试卷")+"."+format);
            }finally{semaphore.Release();}
        });
    }
    public async Task<object> Create(BankPaperInput input,Access access,Data db,IQuestionBank bank){
        Guard.That(input.CourseIds is {Length:>0 and <=20}&&input.CourseIds.Distinct().Count()==input.CourseIds.Length,"请选择有效科目");Guard.That(input.QuestionCount is >=5 and <=20,"每份试卷支持5至20题");Guard.That(input.Title is {Length:<=120},"试卷标题最多120字");
        foreach(var course in input.CourseIds)await access.Class(input.ClassId,course);
        var students=await db.Students.AsNoTracking().Where(s=>s.ClassId==input.ClassId&&(!input.StudentId.HasValue||s.Id==input.StudentId)).OrderBy(s=>s.Name).ThenBy(s=>s.Id).Take(201).Select(s=>s.Id).ToArrayAsync();Guard.That(students.Length is >0 and <=200,"请核对班级名单，单次最多200人");
        var batch=await QuestionBankLearners.Select(new(input.ClassId,input.CourseIds[0],students,CourseIds:input.CourseIds),access,db,bank,input.QuestionCount,200);
        Guard.That(LocalQuestionBank.Subjects.Contains(batch.Subject)&&LocalQuestionBank.Stages.Contains(batch.Stage),"请先完善班级课程的学科与学段资料");
        var questions=batch.Groups.SelectMany(g=>g.Matches).Select(m=>m.Question).GroupBy(q=>q.Id).ToDictionary(g=>g.Key,g=>g.First());
        var title=string.IsNullOrWhiteSpace(input.Title)?batch.ClassName+" · "+batch.Subject+"个性训练":input.Title.Trim();
        var draft=new BankPaperDraft(Guid.NewGuid(),access.User.Id,input.ClassId,input.CourseIds,batch.ClassName,batch.Subject,batch.Stage,title,input.QuestionCount,Clock.Now,batch.Groups.Select(g=>new BankPaperStudent(g.StudentId,g.StudentName,g.Returned==0?g.Status:g.Returned<input.QuestionCount?"partial":"ready",g.Returned>0&&g.Returned<input.QuestionCount?$"计划{input.QuestionCount}题，当前匹配{g.Returned}题，未用无关题补足。":g.Message,g.Matches.Select(m=>m.Question.Id).ToArray())).ToArray(),questions);
        var bytes=JsonSerializer.SerializeToUtf8Bytes(draft,JsonOptions);Guard.That(bytes.Length<=20*1024*1024,"试卷内容较多，请减少题量后重试");
        await gate.WaitAsync();try{
            Directory.CreateDirectory(Root);
            var now = DateTime.UtcNow;
            var files = Directory.EnumerateDirectories(Root)
                .Where(dir => Guid.TryParseExact(Path.GetFileName(dir), "N", out _) && !File.GetAttributes(dir).HasFlag(FileAttributes.ReparsePoint))
                .Select(dir => new FileInfo(Path.Combine(dir, "draft.json")))
                .Where(file => file.Exists && !file.Attributes.HasFlag(FileAttributes.ReparsePoint)).ToArray();
            var activeCount = files.Count(file => file.LastWriteTimeUtc > now.AddHours(-24));
            // Drafts expire after 24 hours. Allow another day for an export already in progress.
            foreach (var expired in files.Where(file => file.LastWriteTimeUtc < now.AddHours(-48)).Take(20))
            {
                if (Guid.TryParseExact(expired.Directory!.Name, "N", out var expiredId))
                {
                    try
                    {
                        Directory.Delete(expired.DirectoryName!, recursive: true);
                        exportLocks.TryRemove(expiredId, out _);
                    }
                    catch (Exception error) when (error is IOException or UnauthorizedAccessException)
                    {
                        logger.LogWarning("Expired paper cleanup deferred ({ErrorType})", error.GetType().Name);
                    }
                }
            }
            Guard.That(activeCount < 500,"当前试卷草稿较多，请稍后重试",429);
            var dir=Dir(draft.Id);Directory.CreateDirectory(dir);await File.WriteAllBytesAsync(Path.Combine(dir,"draft.json"),bytes);
        }finally{gate.Release();}
        return View(draft,0);
    }
    public async Task<BankPaperDraft> Load(Guid id,Access access){
        var file=Path.Combine(Dir(id),"draft.json");Guard.That(File.Exists(file),"试卷草稿不存在",404);var d=JsonSerializer.Deserialize<BankPaperDraft>(await File.ReadAllBytesAsync(file),JsonOptions)!;
        Guard.That(d.Owner==access.User.Id,"只能查看自己生成的试卷",403);Guard.That(d.CreatedAt>Clock.Now.AddHours(-24),"试卷预览已过期，请重新生成",410);foreach(var c in d.CourseIds)await access.Class(d.ClassId,c);return d;
    }
    public object View(BankPaperDraft d,int index){
        Guard.That(index>=0&&index<d.Papers.Length,"试卷不存在",404);var p=d.Papers[index];return new{id=d.Id,d.Title,d.ClassName,d.Subject,d.Stage,d.RequestedCount,d.CreatedAt,generated=d.Papers.Count(p=>p.QuestionIds.Length>0),total=d.Papers.Length,requiresTeacherReview=true,papers=d.Papers.Select(p=>new{p.StudentId,p.Name,p.Status,p.Message,count=p.QuestionIds.Length}),index,studentId=p.StudentId,name=p.Name,status=p.Status,message=p.Message,questions=p.QuestionIds.Select(q=>d.Questions[q])};
    }
    internal static Dictionary<string,string> VerifyImages(Guid owner,IEnumerable<BankQuestion> questions,QuestionBankIntake intake){
        var result=new Dictionary<string,string>();foreach(var q in questions)foreach(var path in q.Images){var m=System.Text.RegularExpressions.Regex.Match(path,@"\Aquestion-bank/intakes/([a-f0-9-]{36})/assets/(page-\d{1,3}\.png|source\.(?:png|jpg|jpeg|webp|bmp|tif|tiff))\z");Guard.That(m.Success,"题目插图地址无效，请先核对题目",422);result[path]=intake.FileFor(owner,Guid.Parse(m.Groups[1].Value),m.Groups[2].Value).Path;}return result;
    }
}
