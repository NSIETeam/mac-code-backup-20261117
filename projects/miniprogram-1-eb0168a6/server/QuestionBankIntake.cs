using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Data.Sqlite;
namespace Siyue;

public sealed record BankIntakeDraft(string Key, CommunityQuestionInput Question, string[] Images);
public sealed record BankIntakePublish(string RequestId, BankIntakeDraft[] Drafts);
public sealed record BankIntakeJob(Guid Id, Guid Owner, string FileName, string Extension, string Subject, string Stage,
    string State, string Message, DateTimeOffset CreatedAt, BankIntakeDraft[] Drafts, CommunityUploadResult? Result = null);

// Original files, review drafts and image evidence live only in the question bank's own volume.
// No document-import, printing or student record is created by this service.
public sealed class QuestionBankIntake(IConfiguration config, IWebHostEnvironment env, CommunityQuestionStore contributions)
{
    static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);
    static readonly SemaphoreSlim UploadGate = new(1);
    public const long MaxFileBytes = 20 * 1024 * 1024;
    public string Root => Path.Combine(Path.GetFullPath(config["QuestionBank:UploadsDirectory"] ?? Path.Combine(env.ContentRootPath,".local","question-bank-uploads")),"intakes");
    string Dir(Guid id) => Path.Combine(Root,id.ToString("N"));
    static readonly string[] Extensions = [".png",".jpg",".jpeg",".webp",".bmp",".tif",".tiff",".pdf",".docx",".doc",".txt",".md"];
    static readonly Regex Asset = new(@"\A(?:page-\d{1,3}\.png|source\.(?:png|jpg|jpeg|webp|bmp|tif|tiff|pdf|docx|doc|txt|md))\z");
    void Enabled() => Guard.That(contributions.Enabled,"文件上传暂不可用",503);
    SqliteConnection Open() {
        Directory.CreateDirectory(Root);var db=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=Path.Combine(Root,"intakes.db"),DefaultTimeout=10}.ToString());db.Open();
        using var schema=db.CreateCommand();schema.CommandText="PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS Intakes(Id TEXT PRIMARY KEY,Owner TEXT NOT NULL,State TEXT NOT NULL,CreatedAt TEXT NOT NULL,StatusJson TEXT NOT NULL,JobJson TEXT NOT NULL);CREATE INDEX IF NOT EXISTS IX_Intakes_Owner ON Intakes(Owner,CreatedAt DESC);CREATE INDEX IF NOT EXISTS IX_Intakes_State ON Intakes(State,CreatedAt);";schema.ExecuteNonQuery();return db;
    }
    BankIntakeJob Read(Guid id) {using var db=Open();using var cmd=db.CreateCommand();cmd.CommandText="SELECT JobJson FROM Intakes WHERE Id=$id";cmd.Parameters.AddWithValue("$id",id.ToString());var text=cmd.ExecuteScalar() as string;Guard.That(text!=null,"上传记录不存在",404);return JsonSerializer.Deserialize<BankIntakeJob>(text!,Options)!;}
    void Save(BankIntakeJob job) {
        using var db=Open();using var cmd=db.CreateCommand();cmd.CommandText="INSERT INTO Intakes VALUES($id,$owner,$state,$at,$status,$job) ON CONFLICT(Id) DO UPDATE SET State=$state,StatusJson=$status,JobJson=$job";
        cmd.Parameters.AddWithValue("$id",job.Id.ToString());cmd.Parameters.AddWithValue("$owner",job.Owner.ToString());cmd.Parameters.AddWithValue("$state",job.State);cmd.Parameters.AddWithValue("$at",job.CreatedAt.ToString("O"));cmd.Parameters.AddWithValue("$status",JsonSerializer.Serialize(job with{Drafts=[]},Options));cmd.Parameters.AddWithValue("$job",JsonSerializer.Serialize(job,Options));cmd.ExecuteNonQuery();
    }
    internal BankIntakeJob[] Jobs(bool pending=false,Guid? owner=null) {
        if(!Directory.Exists(Root))return [];using var db=Open();using var cmd=db.CreateCommand();
        cmd.CommandText="SELECT StatusJson FROM Intakes"+(owner.HasValue?" WHERE Owner=$owner":pending?" WHERE State IN ('queued','processing')":"")+" ORDER BY CreatedAt"+(owner.HasValue?" DESC LIMIT 30":pending?" LIMIT 10":" LIMIT 5001");if(owner.HasValue)cmd.Parameters.AddWithValue("$owner",owner.Value.ToString());using var reader=cmd.ExecuteReader();var rows=new List<BankIntakeJob>();while(reader.Read())rows.Add(JsonSerializer.Deserialize<BankIntakeJob>(reader.GetString(0),Options)!);return rows.ToArray();
    }
    public object View(Guid owner,Guid id) {Enabled();var job=Read(id);Guard.That(job.Owner==owner,"只能查看自己的上传资料",403);return Public(job);}
    public object[] Mine(Guid owner) {Enabled();return Jobs(owner:owner).Select(Public).ToArray();}
    static object Public(BankIntakeJob j)=>new {j.Id,j.FileName,j.State,j.Message,j.CreatedAt,j.Drafts,j.Result,source=$"question-bank/intakes/{j.Id}/assets/source{j.Extension}"};

    public async Task<object> Queue(Guid owner,HttpRequest request,CancellationToken ct) {
        Enabled();Guard.That(request.HasFormContentType,"请选择图片或资料文件");var form=await request.ReadFormAsync(ct);var file=form.Files.GetFile("file");
        var ext=Path.GetExtension(file?.FileName??"").ToLowerInvariant();
        Guard.That(file!=null && file.Length is >0 and <=MaxFileBytes && Extensions.Contains(ext),"支持20MB以内的图片、PDF、Word、TXT或Markdown文件");
        var subject=form["subject"].FirstOrDefault()??"数学";var stage=form["stage"].FirstOrDefault()??"高中";
        Guard.That(LocalQuestionBank.Subjects.Contains(subject)&&LocalQuestionBank.Stages.Contains(stage),"请选择有效的学科和学段");
        await UploadGate.WaitAsync(ct);
        var id=Guid.NewGuid();var dir=Dir(id);
        try {
            Directory.CreateDirectory(Root);var jobs=Jobs().ToArray();
            Guard.That(jobs.Length<5000 && jobs.Count(j=>j.Owner==owner&&j.State is "queued" or "processing")<10,"待识别文件较多，请等当前任务完成后再上传",429);
            var all=Directory.EnumerateFiles(Root,"*",SearchOption.AllDirectories).Select(p=>new FileInfo(p)).ToArray();
            Guard.That(all.Sum(f=>f.Length)+file!.Length<=2L*1024*1024*1024,"题库资料存储已满，请联系管理员扩容",409);
            var owned=jobs.Where(j=>j.Owner==owner).Select(j=>Dir(j.Id)+Path.DirectorySeparatorChar).ToArray();
            Guard.That(all.Where(f=>owned.Any(d=>f.FullName.StartsWith(d,StringComparison.Ordinal))).Sum(f=>f.Length)+file.Length<=200L*1024*1024,"个人上传资料已达200MB，请联系管理员整理",409);
            Directory.CreateDirectory(dir);await using(var output=File.Create(Path.Combine(dir,"source"+ext)))await file.CopyToAsync(output,ct);
            var path=Path.Combine(dir,"source"+ext);var header=new byte[Math.Min(12,(int)file.Length)];await using(var source=File.OpenRead(path))await source.ReadExactlyAsync(header,ct);
            var valid=ext switch { ".pdf"=>header.AsSpan().StartsWith("%PDF-"u8),".docx"=>header.AsSpan().StartsWith("PK"u8),".doc"=>header.AsSpan().StartsWith(new byte[]{0xd0,0xcf,0x11,0xe0}),".png"=>header.AsSpan().StartsWith(new byte[]{137,80,78,71}),".jpg" or ".jpeg"=>header.AsSpan().StartsWith(new byte[]{255,216,255}),_=>true};
            Guard.That(valid,"文件内容与扩展名不符，请重新选择文件");if(ext==".docx")WordDocuments.CheckPackage(path);
            var name=Path.GetFileName(file.FileName.Replace('\\','/'));Guard.That(name.Length<=250,"文件名过长");
            var job=new BankIntakeJob(id,owner,name,ext,subject,stage,"queued","已上传，等待自动识别",DateTimeOffset.UtcNow,[]);Save(job);return Public(job);
        } catch {if(Directory.Exists(dir))Directory.Delete(dir,true);throw;} finally {UploadGate.Release();}
    }
    public (string Path,string Type,string Name) FileFor(Guid owner,Guid id,string name) {
        Enabled();Guard.That(Asset.IsMatch(name),"文件不存在",404);var job=Read(id);var uri=$"question-bank/intakes/{id}/assets/{name}";
        Guard.That(job.Owner==owner||contributions.Snapshot().Questions.Any(q=>q.Images.Contains(uri)),"无权读取此文件",403);
        var path=Path.Combine(Dir(id),name);Guard.That(File.Exists(path),"文件不存在",404);
        var type=Path.GetExtension(name).ToLowerInvariant() switch {".png"=>"image/png",".jpg" or ".jpeg"=>"image/jpeg",".webp"=>"image/webp",".bmp"=>"image/bmp",".tif" or ".tiff"=>"image/tiff",".pdf"=>"application/pdf",".txt" or ".md"=>"text/plain; charset=utf-8",_=>"application/octet-stream"};
        return(path,type,name.StartsWith("source")?job.FileName:name);
    }
    public object Publish(Guid owner,Guid id,BankIntakePublish input,IQuestionBank bank) {
        Enabled();var job=Read(id);Guard.That(job.Owner==owner,"只能确认自己的上传题目",403);
        using var lease=new FileStream(Path.Combine(Dir(id),"publish.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);Guard.That(job.State is "review" or "published","文件尚未识别完成",409);
        Guard.That(input.RequestId is {Length:>0 and <=80} && input.Drafts is {Length:>0 and <=100},"请选择1至100道题确认入库");
        Guard.That(input.Drafts.All(d=>d!=null&&d.Question!=null&&d.Images!=null),"识别结果格式无效");
        Guard.That(input.Drafts.Select(d=>d.Key).Distinct().Count()==input.Drafts.Length,"题目重复，请刷新后重试");
        var images=new List<string[]>();var keys=new List<string>();
        foreach(var draft in input.Drafts) {
            var original=job.Drafts.SingleOrDefault(d=>d.Key==draft.Key);Guard.That(original!=null,"题目不属于此上传文件");
            Guard.That(draft.Images!=null && draft.Images.SequenceEqual(original!.Images),"原图引用已变化，请刷新后重试");
            images.Add(original.Images);using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            foreach(var image in original.Images){var name=image.Split('/')[^1];Guard.That(Asset.IsMatch(name),"原图引用无效");hash.AppendData(System.IO.File.ReadAllBytes(Path.Combine(Dir(id),name)));}
            keys.Add(original.Images.Length>0?Convert.ToHexStringLower(hash.GetHashAndReset()):"");
        }
        var result=contributions.UploadIntake(owner,new("intake:"+id.ToString("N")+":"+input.RequestId,input.Drafts.Select(d=>d.Question).ToArray()),bank,images.ToArray(),keys.ToArray());
        Save(job with {State="published",Message=$"已入库{result.Added}道，跳过重复{result.Duplicates}道",Result=result});return result;
    }
    public static BankIntakeDraft[] Split(string text,string subject,string stage,string source,string[] images) {
        text=text.Replace("\r\n","\n").Replace('\r','\n').Trim();Guard.That(text.Length<=500000,"资料文字过多，请拆分文件后上传");
        var chunks=Regex.Split(text,@"(?m)(?=^\s*(?:\d{1,3}[．、.]\s*|[【\[]?(?:例题?|练习?|测试?)\s*\d+\s*[】\]]?[.．、:：]?))").Where(t=>!string.IsNullOrWhiteSpace(t)).ToList();
        // A title immediately before numbered questions belongs to the first question, not an extra question.
        if(chunks.Count>1&&!Regex.IsMatch(chunks[0],@"^\s*(?:\d{1,3}[．、.]|[【\[]?(?:例|练|测))")){chunks[1]=chunks[0]+"\n"+chunks[1];chunks.RemoveAt(0);}
        if(chunks.Count==0)chunks.Add("");Guard.That(chunks.Count<=100,"单个文件识别超过100题，请分批上传");
        return chunks.Select(chunk=>{
            var answerMarker=Regex.Match(chunk,@"(?:[【\[]\s*(?:参考)?答案\s*[】\]]|(?:参考)?答案\s*[:：])");
            var analysisMarker=Regex.Match(chunk,@"(?:[【\[]\s*(?:解析|详解)\s*[】\]]|解析\s*[:：])");
            var end=new[]{answerMarker.Success?answerMarker.Index:chunk.Length,analysisMarker.Success?analysisMarker.Index:chunk.Length}.Min();
            var stem=chunk[..end].Trim();var answer=answerMarker.Success?chunk[(answerMarker.Index+answerMarker.Length)..(analysisMarker.Success&&analysisMarker.Index>answerMarker.Index?analysisMarker.Index:chunk.Length)].Trim():"";
            var analysis=analysisMarker.Success?chunk[(analysisMarker.Index+analysisMarker.Length)..].Trim():"";
            var type=Regex.IsMatch(stem,@"[ABCD][.．、]")?"选择题":Regex.IsMatch(stem,@"_{2,}|填空|[（(]\s*[）)]")?"填空题":"解答题";
            return new BankIntakeDraft(Guid.NewGuid().ToString("N"),new(subject,stage,type,stem,answer,source,"上传者提供，限校内教学使用；来源待教研核对",analysis),images);
        }).ToArray();
    }
    async Task Run(string executable,IEnumerable<string> args,CancellationToken ct) {
        var info=new ProcessStartInfo(executable){RedirectStandardOutput=true,RedirectStandardError=true,UseShellExecute=false,CreateNoWindow=true};foreach(var arg in args)info.ArgumentList.Add(arg);
        using var process=System.Diagnostics.Process.Start(info)??throw new ApiException(503,"识别工具暂不可用");var output=process.StandardOutput.ReadToEndAsync(ct);var error=process.StandardError.ReadToEndAsync(ct);
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(TimeSpan.FromSeconds(120));
        try {await process.WaitForExitAsync(timeout.Token);}catch {try{process.Kill(true);}catch{}ct.ThrowIfCancellationRequested();throw new ApiException(422,"识别耗时较长，请拆分文件后重试");}
        await Task.WhenAll(output,error);Guard.That(process.ExitCode==0,"无法读取文件，请检查是否损坏、加密或图片不清晰",422);
    }
    async Task<string> Ocr(string path,string dir,string name,CancellationToken ct) {
        var prefix=Path.Combine(dir,name);
        try {
            await Run(config["Documents:OcrPython"]??"/opt/ocr/bin/python",[Path.Combine(env.ContentRootPath,"pdf_pixels.py"),"rapid",path,prefix+"-rapid.tsv",prefix+"-rapid.json"],ct);
            var page=Json.Read<PdfOcrPage>(await System.IO.File.ReadAllTextAsync(prefix+"-rapid.json",ct));
            if(page.Lines.Length>0)return string.Join("\n",page.Lines.Select(l=>l.Text));
        } catch(Exception e)when(!ct.IsCancellationRequested&&e is not OutOfMemoryException) { }
        await Run(config["Documents:Tesseract"]??"tesseract",[path,prefix,"-l",config["Documents:OcrLanguages"]??"chi_sim+eng","--psm","3"],ct);return await System.IO.File.ReadAllTextAsync(prefix+".txt",ct);
    }
    static readonly SemaphoreSlim ExamPhotoGate = new(1);

    internal async Task<string> ReadPrivatePhoto(IFormFile photo, CancellationToken cancellation)
    {
        Guard.That(photo.Length is > 0 and <= 8 * 1024 * 1024, "请上传 8MB 以内的试卷照片");
        Guard.That(await ExamPhotoGate.WaitAsync(0, cancellation), "已有照片正在识别，请稍后重试", 429);
        string? directory = null;
        try
        {
            directory = Directory.CreateTempSubdirectory("siyue-exam-").FullName;
            var source = Path.Combine(directory, "input");
            var normalized = Path.Combine(directory, "exam.png");
            await using (var output = System.IO.File.Create(source))
                await photo.CopyToAsync(output, cancellation);
            await Run(config["Documents:ImagePython"] ?? "python3",
                [Path.Combine(env.ContentRootPath, "question_bank_media.py"), source, normalized], cancellation);
            return await Ocr(normalized, directory, "exam", cancellation);
        }
        finally
        {
            try { if (directory != null) Directory.Delete(directory, recursive: true); }
            finally { ExamPhotoGate.Release(); }
        }
    }

    public async Task Process(Guid id,CancellationToken ct) {
        FileStream lease;try{lease=new(Path.Combine(Dir(id),"processing.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);}catch(IOException){return;}
        using(lease){var job=Read(id);if(job.State is not ("queued" or "processing"))return;Save(job with{State="processing",Message="正在识别并整理题目，原文件已保存"});
            try {
                var dir=Dir(id);var source=Path.Combine(dir,"source"+job.Extension);var pages=new List<(string Text,string[] Images)>();
                if(job.Extension is ".txt" or ".md"){Guard.That(new FileInfo(source).Length<=1024*1024,"文字文件最大1MB，请拆分后上传");pages.Add((await System.IO.File.ReadAllTextAsync(source,Encoding.UTF8,ct),[]));}
                else if(job.Extension is ".pdf" or ".docx" or ".doc") {
                    var pdf=source;
                    if(job.Extension!=".pdf") {
                        var converted=Path.Combine(dir,"converted");Directory.CreateDirectory(converted);var profile=Path.Combine(dir,"office-profile");
                        await Run(config["Documents:Soffice"]??"soffice",["-env:UserInstallation="+new Uri(profile).AbsoluteUri,"--headless","--nologo","--nodefault","--norestore","--convert-to","pdf","--outdir",converted,source],ct);
                        pdf=Path.Combine(converted,"source.pdf");Guard.That(System.IO.File.Exists(pdf),"Word转换失败，请检查源文件",422);
                    }
                    int count;using(var doc=UglyToad.PdfPig.PdfDocument.Open(pdf))count=doc.NumberOfPages;Guard.That(count is >0 and <=25,"单份资料最多25页，请拆分后上传",422);
                    for(var page=1;page<=count;page++) {
                        var prefix=Path.Combine(dir,"page-"+page);var textFile=prefix+".txt";var n=page.ToString(System.Globalization.CultureInfo.InvariantCulture);
                        await Run(config["Documents:PdfToText"]??"pdftotext",["-f",n,"-l",n,"-layout","-enc","UTF-8",pdf,textFile],ct);
                        await Run(config["Documents:PdfToPpm"]??"pdftoppm",["-f",n,"-l",n,"-scale-to","1400","-singlefile","-png",pdf,prefix],ct);
                        var text=await System.IO.File.ReadAllTextAsync(textFile,ct);if(Regex.Replace(text,@"\s","").Length<30)text=await Ocr(prefix+".png",dir,"ocr-"+page,ct);
                        pages.Add((text,[$"question-bank/intakes/{id}/assets/page-{page}.png"]));
                        Guard.That(Directory.EnumerateFiles(dir,"*",SearchOption.AllDirectories).Sum(p=>new FileInfo(p).Length)<=80L*1024*1024,"资料展开后过大，请拆分后上传",422);
                    }
                } else {var normalized=Path.Combine(dir,"page-1.png");await Run(config["Documents:ImagePython"]??"python3",[Path.Combine(env.ContentRootPath,"question_bank_media.py"),source,normalized],ct);pages.Add((await Ocr(normalized,dir,"ocr",ct),[$"question-bank/intakes/{id}/assets/page-1.png"]));}
                var drafts=pages.SelectMany(p=>Split(p.Text,job.Subject,job.Stage,job.FileName,p.Images)).ToArray();Guard.That(drafts.Length is >0 and <=100,"单份资料最多100题，请拆分后上传",422);
                Save(job with{State="review",Message=$"已整理{drafts.Length}道，请确认识别结果后入库"+(drafts.Any(d=>string.IsNullOrWhiteSpace(d.Question.Answer))?"；缺少答案的题目可展开补齐":""),Drafts=drafts});
            } catch(OperationCanceledException)when(ct.IsCancellationRequested){Save(job with{State="queued",Message="等待继续识别"});throw;}
            catch(Exception e)when(e is not OutOfMemoryException){Save(job with{State="failed",Message=e is ApiException?e.Message:"文件识别失败，可重新上传或换成PDF/清晰图片"});}
        }
    }
}

public sealed class QuestionBankIntakeWorker(QuestionBankIntake intake,IConfiguration config,ILogger<QuestionBankIntakeWorker> logger):BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken) {
        if(!config.GetValue<bool>("QuestionBank:IntakeWorkerEnabled"))return;
        while(!stoppingToken.IsCancellationRequested){
            foreach(var job in intake.Jobs(pending:true)){
                try{await intake.Process(job.Id,stoppingToken);}catch(OperationCanceledException)when(stoppingToken.IsCancellationRequested){return;}catch(Exception e){logger.LogWarning("Question intake worker deferred ({Type})",e.GetType().Name);}
            }
            await Task.Delay(1000,stoppingToken);
        }
    }
}
