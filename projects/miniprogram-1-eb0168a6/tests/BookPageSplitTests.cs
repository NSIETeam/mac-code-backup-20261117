using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using UglyToad.PdfPig.Writer;
using Xunit;

public class BookPageSplitTests
{
    static byte[] Pdf(int pages = 4) {
        var pdf = new PdfDocumentBuilder(); var font = pdf.AddStandard14Font(Standard14Font.Helvetica);
        for (var n=1;n<=pages;n++) { var page=pdf.AddPage(UglyToad.PdfPig.Content.PageSize.A4); page.AddText($"1. Page {n} calculate {n} + 2.",14,new PdfPoint(50,740),font); page.DrawRectangle(new PdfPoint(50,600),120+n*5,60); }
        return pdf.Build();
    }
    static byte[] Word() { using var bytes=new MemoryStream(); using(var d=WordprocessingDocument.Create(bytes,WordprocessingDocumentType.Document,true)) {
        d.AddMainDocumentPart().Document=new Document(new Body(new Paragraph(new Run(new Text("First lecture"))),new Paragraph(new Run(new Break{Type=BreakValues.Page})),new Paragraph(new Run(new Text("Second lecture"))))); }
        return bytes.ToArray(); }
    static async Task<(SchoolClass Class, Course Course)> Seed(Factory f) {
        using var s=f.Services.CreateScope(); var db=s.ServiceProvider.GetRequiredService<Data>(); var campus=new Campus{Name="页拆校区"}; var cl=new SchoolClass{Name="页拆班级",CampusId=campus.Id};var course=new Course{Name="数学"};db.AddRange(campus,cl,course);await db.SaveChangesAsync();return(cl,course);
    }
    static async Task<Guid> Upload(HttpClient client,Guid cl,Guid course,byte[] bytes,string? mode="pages",string file="页拆讲义.pdf") {
        using var form=new MultipartFormDataContent();form.Add(new ByteArrayContent(bytes),"file",file);form.Add(new StringContent("legacy"),"recognitionMode");if(mode!=null)form.Add(new StringContent(mode),"splitMode");
        var response=await client.PostAsync($"/api/classes/{cl}/books?courseId={course}",form);Assert.Equal(HttpStatusCode.Accepted,response.StatusCode);return(await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }
    static async Task<JsonElement> Ready(Factory f,HttpClient client,Guid id) { await f.Run("book-page-preview",id);return await client.GetFromJsonAsync<JsonElement>($"/api/books/{id}/page-preview"); }
    static BookPageSplitInput Input(JsonElement ready,params int[] cuts)=>new(ready.GetProperty("revision").GetInt32(),ready.GetProperty("sourceVersion").GetString()!,cuts);
    static async Task<byte[]> Bytes(IObjects objects,string key){await using var input=await objects.Get(key);using var output=new MemoryStream();await input.CopyToAsync(output);return output.ToArray();}
    sealed class FailObjects(IObjects inner,Func<string,bool> fail):IObjects {
        public Task<Stream> Get(string key,CancellationToken ct=default)=>inner.Get(key,ct);
        public Task Put(string key,Stream data,string type,CancellationToken ct=default)=>fail(key)?throw new IOException("injected storage failure"):inner.Put(key,data,type,ct);
    }

    [Theory]
    [InlineData("0")][InlineData("4")][InlineData("2,2")][InlineData("3,1")][InlineData("-1")]
    public void InvalidCutsNeverGuessOrDropPages(string values)=>Assert.Throws<ApiException>(()=>BookPageSplits.Ranges(4,new(0,"",values.Split(',').Select(int.Parse).ToArray())));
    [Fact] public void RangeLimitsAndZeroCutsCoverExactlyAllPages() {
        Assert.Equal(new[]{(0,1),(1,3),(3,4)},BookPageSplits.Ranges(4,new(0,"",[1,3])).Select(x=>(x.Start,x.End)));
        Assert.Equal((0,4),BookPageSplits.Ranges(4,new(0,"",[])).Select(x=>(x.Start,x.End)).Single());
        Assert.Throws<ApiException>(()=>BookPageSplits.Ranges(501,new(0,"",[])));Assert.Throws<ApiException>(()=>BookPageSplits.Ranges(500,new(0,"",Enumerable.Range(1,200).ToArray())));
        Assert.Throws<ApiException>(()=>BookPageSplits.Ranges(4,new(0,"",[1],int.MaxValue)));Assert.Throws<ApiException>(()=>BookPageSplits.Ranges(4,new(0,"",[1],1,["one"])));
        Assert.Throws<ApiException>(()=>BookPageSplits.Ranges(4,new(0,"",[],1,[new string('x',121)])));
    }
    [Fact] public async Task PdfUploadPreviewAndPhysicalSplitsPreserveEveryPageAndQueueOnlyAfterDurableFiles() {
        await using var f=new Factory();using var admin=await f.Login("page-split",true);var seed=await Seed(f);var original=Pdf();var id=await Upload(admin,seed.Class.Id,seed.Course.Id,original);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal("book-page-preview",(await db.Work.SingleAsync()).Kind);Assert.Empty(await db.Lectures.ToArrayAsync());}
        var ready=await Ready(f,admin,id);Assert.Equal("ready",ready.GetProperty("state").GetString());Assert.Equal(4,ready.GetProperty("pageCount").GetInt32());Assert.True(ready.GetProperty("canSplit").GetBoolean());Assert.False(ready.GetProperty("hasPlan").GetBoolean());
        Assert.Equal(original,await admin.GetByteArrayAsync(ready.GetProperty("sourceUrl").GetString()));
        var input=Input(ready,1,3);var split=await Factory.Post(admin,$"/api/books/{id}/page-split",input);Assert.Equal(3,split.GetProperty("lectureCount").GetInt32());
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal(3,await db.Work.CountAsync(w=>w.Kind=="import"&&w.State=="waiting"));var b=(await db.BookImports.FindAsync(id))!;var o=s.ServiceProvider.GetRequiredService<IObjects>();var path=Path.Combine(f.Root,"refs.docx");await File.WriteAllBytesAsync(path,await Bytes(o,b.NormalizedKey));WordDocuments.CheckPackage(path);Assert.Equal(4,BookStructure.Read(path).Length);}
        await f.Run("book-split",id);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();var b=(await db.BookImports.FindAsync(id))!;Assert.Equal("split",b.State);var sections=Json.Read<BookSection[]>(b.SectionsJson);var objects=s.ServiceProvider.GetRequiredService<IObjects>();using var source=PdfDocument.Open(original);long total=0;
            foreach(var section in sections){var h=(await db.Handouts.FindAsync(section.HandoutId))!;var bytes=await Bytes(objects,h.OriginalKey);total+=bytes.Length;using var pdf=PdfDocument.Open(bytes);Assert.Equal(section.End-section.Start,pdf.NumberOfPages);Assert.Equal(h.OriginalKey,h.SourcePdfKey);for(var p=1;p<=pdf.NumberOfPages;p++)Assert.Equal(source.GetPage(section.Start+p).Text,pdf.GetPage(p).Text);}
            Assert.True(total<188_743_680);Assert.Equal(original,await Bytes(objects,b.OriginalKey));Assert.Equal(3,await db.Work.CountAsync(w=>w.Kind=="import"&&w.State=="queued"));Assert.Empty(await db.ClassSessions.ToArrayAsync());
            Assert.DoesNotContain(Directory.EnumerateFiles(Path.Combine(f.Root,"files"),"*",SearchOption.AllDirectories),p=>p.EndsWith(".png"));
        }
        var repeat=await Factory.Post(admin,$"/api/books/{id}/page-split",input);Assert.Equal(split.GetProperty("sections").GetRawText(),repeat.GetProperty("sections").GetRawText());
        var readOnly=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}/page-preview");Assert.False(readOnly.GetProperty("canSplit").GetBoolean());Assert.True(readOnly.GetProperty("hasPlan").GetBoolean());Assert.True(readOnly.GetProperty("hasChildren").GetBoolean());
    }
    static string TestSourceDirectory([System.Runtime.CompilerServices.CallerFilePath] string file="")=>Path.GetDirectoryName(file)!;
    [Fact] public async Task ChineseDiagramFixtureIsCopiedAsExactOrderedPhysicalPages() {
        await using var f=new Factory();using var admin=await f.Login("page-diagram",true);var seed=await Seed(f);
        var original=await File.ReadAllBytesAsync(Path.Combine(TestSourceDirectory(),"fixtures","page-split-diagrams.pdf"));var id=await Upload(admin,seed.Class.Id,seed.Course.Id,original);
        var ready=await Ready(f,admin,id);Assert.Equal(4,ready.GetProperty("pageCount").GetInt32());await Factory.Post(admin,$"/api/books/{id}/page-split",Input(ready,2));await f.Run("book-split",id);
        var output=Path.GetFullPath(".local/acceptance/pdf-split-smoke");Directory.CreateDirectory(output);await File.WriteAllBytesAsync(Path.Combine(output,"source.pdf"),original);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var book=(await db.BookImports.FindAsync(id))!;using var source=PdfDocument.Open(original);var sections=Json.Read<BookSection[]>(book.SectionsJson);var index=0;long total=0;
        foreach(var section in sections){var h=(await db.Handouts.FindAsync(section.HandoutId))!;var bytes=await Bytes(scope.ServiceProvider.GetRequiredService<IObjects>(),h.OriginalKey);total+=bytes.Length;using var part=PdfDocument.Open(bytes);Assert.Equal(2,part.NumberOfPages);for(var p=1;p<=2;p++){Assert.Equal(source.GetPage(section.Start+p).Text,part.GetPage(p).Text);Assert.Equal(source.GetPage(section.Start+p).Paths.Count,part.GetPage(p).Paths.Count);}await File.WriteAllBytesAsync(Path.Combine(output,$"part{++index}.pdf"),bytes);}
        await File.WriteAllTextAsync(Path.Combine(output,"summary.json"),Json.Write(new{sourceBytes=original.Length,partBytes=total,sourcePages=4,partPages=new[]{2,2},textAndVectorPathsEqual=true}));
    }
    [Fact] public async Task ConcurrentConfirmationCreatesEachLectureOnceAndDoesNotReplaceAnOlderLecture() {
        await using var f=new Factory();using var admin=await f.Login("page-race",true);var seed=await Seed(f);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf());var ready=await Ready(f,admin,id);var input=Input(ready,2);
        var responses=await Task.WhenAll(admin.PostAsJsonAsync($"/api/books/{id}/page-split",input),admin.PostAsJsonAsync($"/api/books/{id}/page-split",input));Assert.Contains(responses,r=>r.IsSuccessStatusCode);Assert.All(responses,r=>Assert.True(r.IsSuccessStatusCode||r.StatusCode==HttpStatusCode.Conflict, r.StatusCode+":"+r.Content.ReadAsStringAsync().Result));
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal(2,await db.Lectures.CountAsync());Assert.Equal(2,await db.Handouts.CountAsync());Assert.Equal(1,await db.Work.CountAsync(w=>w.Kind=="book-split"));}
        var other=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf(3));var otherReady=await Ready(f,admin,other);Assert.Equal(HttpStatusCode.BadRequest,(await admin.PostAsJsonAsync($"/api/books/{other}/page-split",Input(otherReady,1))).StatusCode);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal(2,await db.Lectures.CountAsync());Assert.Equal(2,await db.Handouts.CountAsync());Assert.Equal("review",(await db.BookImports.FindAsync(other))!.State);}
    }
    [Theory][InlineData("queued")][InlineData("review")][InlineData("failed")][InlineData("split")][InlineData("processing")]
    public async Task LegacyBooksPreviewOriginalPdfWithoutMutatingTheirBusinessState(string state) {
        await using var f=new Factory();using var admin=await f.Login("page-old",true);var seed=await Seed(f);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf(),null);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();await db.BookImports.Where(x=>x.Id==id).ExecuteUpdateAsync(x=>x.SetProperty(b=>b.State,state));}
        await Factory.Post(admin,$"/api/books/{id}/page-preview",new{});await Factory.Post(admin,$"/api/books/{id}/page-preview",new{});var preview=await Ready(f,admin,id);
        Assert.Equal("ready",preview.GetProperty("state").GetString());Assert.False(preview.GetProperty("hasPlan").GetBoolean());Assert.Equal(state is "queued" or "review" or "failed",preview.GetProperty("canSplit").GetBoolean());
        using var scope=f.Services.CreateScope();var db2=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(state,(await db2.BookImports.FindAsync(id))!.State);Assert.Equal(1,await db2.Work.CountAsync(w=>w.Kind=="book-page-preview"));Assert.Empty(await db2.Handouts.ToArrayAsync());
    }
    [Fact] public async Task ActiveJobsLockTemporarilyAndQueuedAutoIsOnlySupersededOnConfirmedManualSplit() {
        await using var f=new Factory();using var admin=await f.Login("page-active",true);var seed=await Seed(f);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf(),null);var ready=await Ready(f,admin,id);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal("queued",(await db.Work.SingleAsync()).State);await db.Work.ExecuteUpdateAsync(x=>x.SetProperty(w=>w.State,"running"));}
        var busy=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}/page-preview");Assert.True(busy.GetProperty("busy").GetBoolean());Assert.False(busy.GetProperty("canSplit").GetBoolean());Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"/api/books/{id}/page-split",Input(ready,2))).StatusCode);
        using(var s=f.Services.CreateScope())await s.ServiceProvider.GetRequiredService<Data>().Work.ExecuteUpdateAsync(x=>x.SetProperty(w=>w.State,"queued"));
        await Factory.Post(admin,$"/api/books/{id}/page-split",Input(ready,2));using(var s=f.Services.CreateScope())Assert.Equal("superseded",(await s.ServiceProvider.GetRequiredService<Data>().Work.SingleAsync(w=>w.Kind=="book-auto")).State);
    }
    [Fact] public async Task StaleSourceVersionsAndChangedOriginalBytesCannotProduceChildren() {
        await using var f=new Factory();using var admin=await f.Login("page-version",true);var seed=await Seed(f);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf());var ready=await Ready(f,admin,id);
        Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"/api/books/{id}/page-split",Input(ready,2)with{SourceVersion=new string('0',64)})).StatusCode);
        await Factory.Post(admin,$"/api/books/{id}/page-split",Input(ready,2));
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();var b=(await db.BookImports.FindAsync(id))!;using var bytes=new MemoryStream(Pdf(2));await s.ServiceProvider.GetRequiredService<IObjects>().Put(b.OriginalKey,bytes,"application/pdf");}
        var error=await Assert.ThrowsAsync<ApiException>(()=>f.Run("book-split",id));Assert.Equal(409,error.Status);
        using var scope=f.Services.CreateScope();var db2=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(2,await db2.Work.CountAsync(w=>w.Kind=="import"&&w.State=="waiting"));Assert.False(Directory.Exists(Path.Combine(f.Root,"files","handouts")));
    }
    [Fact] public async Task FailedConfirmationRollsBackDatabaseAndFailedChildCanRetryWithoutLosingSourceOrCompletedChild() {
        await using var f=new Factory();using var admin=await f.Login("page-retry",true);var seed=await Seed(f);var original=Pdf();var id=await Upload(admin,seed.Class.Id,seed.Course.Id,original);var ready=await Ready(f,admin,id);var input=Input(ready,2);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();var b=(await db.BookImports.FindAsync(id))!;var objects=s.ServiceProvider.GetRequiredService<IObjects>();await Assert.ThrowsAsync<IOException>(()=>BookPageSplits.Confirm(b,input,Guid.Empty,db,new FailObjects(objects,k=>k.Contains("/page-split-")),default));}
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Empty(await db.Handouts.ToArrayAsync());Assert.Empty(await db.Lectures.ToArrayAsync());Assert.Equal(ready.GetProperty("revision").GetInt32(),(await db.BookImports.FindAsync(id))!.Revision);}
        await Factory.Post(admin,$"/api/books/{id}/page-split",input);Guid first,second;byte[] firstBytes;
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();var b=(await db.BookImports.FindAsync(id))!;var sections=Json.Read<BookSection[]>(b.SectionsJson);first=sections[0].HandoutId!.Value;second=sections[1].HandoutId!.Value;var o=s.ServiceProvider.GetRequiredService<IObjects>();var path=Path.Combine(f.Root,"split");Directory.CreateDirectory(path);await BookPageSplits.Split(id,db,s.ServiceProvider.GetRequiredService<WordDocuments>(),new FailObjects(o,k=>k.Contains(second.ToString())),path,default);Assert.Equal("failed",b.State);firstBytes=await Bytes(o,(await db.Handouts.FindAsync(first))!.OriginalKey);Assert.Equal(original,await Bytes(o,b.OriginalKey));}
        await f.Run("book-split",id);using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal("split",(await db.BookImports.FindAsync(id))!.State);Assert.Equal(firstBytes,await Bytes(s.ServiceProvider.GetRequiredService<IObjects>(),(await db.Handouts.FindAsync(first))!.OriginalKey));Assert.Equal(2,await db.Work.CountAsync(w=>w.Kind=="import"&&w.State=="queued"));}
    }
    [Fact] public async Task NewPreviewFailureAndRetryStayInPageModeAndOldBookFailureDoesNotRewriteBusinessState() {
        await using var f=new Factory();using var admin=await f.Login("page-fail",true);var seed=await Seed(f);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,[1,2,3]);
        using(var s=f.Services.CreateScope())await s.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(default);
        using(var s=f.Services.CreateScope())Assert.Equal("failed",(await s.ServiceProvider.GetRequiredService<Data>().BookImports.FindAsync(id))!.State);
        Assert.Equal(HttpStatusCode.Accepted,(await admin.PostAsJsonAsync($"/api/books/{id}/retry",new{})).StatusCode);using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Equal("book-page-preview",(await db.Work.SingleAsync()).Kind);Assert.Equal("queued",(await db.Work.SingleAsync()).State);Assert.Null((await db.Work.SingleAsync()).LeaseUntil);}
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.Single(await QueueEstimates.Executable(db,Clock.Now).ToArrayAsync());await db.Work.ExecuteUpdateAsync(x=>x.SetProperty(w=>w.State,"failed").SetProperty(w=>w.LeaseUntil,Clock.Now.AddMinutes(5)));}
        await Factory.Post(admin,$"/api/books/{id}/page-preview",new{});
        using(var s=f.Services.CreateScope())Assert.Single(await QueueEstimates.Executable(s.ServiceProvider.GetRequiredService<Data>(),Clock.Now).ToArrayAsync());
        var old=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf(),null);using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();await BookPageSplits.FailedPreview(old,"bad",db,default);Assert.Equal("queued",(await db.BookImports.FindAsync(old))!.State);}
    }
    [Fact] public async Task TeacherScopeAppliesToPreviewSplitAndIssuedSourceGrantAfterAssignmentRevocation() {
        await using var f=new Factory();using var admin=await f.Login("page-auth",true);var seed=await Seed(f);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf());var ready=await Ready(f,admin,id);
        using var teacher=await f.Login("page-teacher");var staff=await Factory.Post(admin,"/api/admin/catalog/staff",new{name="本班教师"});var staffId=staff.GetProperty("id").GetGuid();var invite=await Factory.Post(admin,$"/api/admin/staff/{staffId}/invite",new{});await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});
        foreach(var method in new[]{"get","post","split"}){var response=method=="get"?await teacher.GetAsync($"/api/books/{id}/page-preview"):method=="post"?await teacher.PostAsJsonAsync($"/api/books/{id}/page-preview",new{}):await teacher.PostAsJsonAsync($"/api/books/{id}/page-split",Input(ready,2));Assert.Equal(HttpStatusCode.Forbidden,response.StatusCode);}
        await Factory.Post(admin,"/api/admin/catalog/assignments",new{staffId,classId=seed.Class.Id,courseId=seed.Course.Id});var allowed=await teacher.GetFromJsonAsync<JsonElement>($"/api/books/{id}/page-preview");var url=allowed.GetProperty("sourceUrl").GetString()!;Assert.True((await teacher.GetAsync(url)).IsSuccessStatusCode);
        using(var s=f.Services.CreateScope())await s.ServiceProvider.GetRequiredService<Data>().Assignments.Where(a=>a.StaffId==staffId).ExecuteDeleteAsync();Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync(url)).StatusCode);
    }
    [Fact] public async Task LegacyConfirmAndAutoTasksCannotParsePageReferenceDocuments() {
        await using var f=new Factory();using var admin=await f.Login("page-old-job",true);var seed=await Seed(f);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,Pdf());
        await f.Run("book-auto",id);var ready=await admin.GetFromJsonAsync<JsonElement>($"/api/books/{id}/page-preview");Assert.Equal("ready",ready.GetProperty("state").GetString());
        Assert.Equal(HttpStatusCode.Conflict,(await admin.PostAsJsonAsync($"/api/books/{id}/confirm",new BookReview(0,[]))).StatusCode);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Empty(await db.Handouts.ToArrayAsync());Assert.Empty(await db.Questions.ToArrayAsync());Assert.Empty(await db.Lectures.ToArrayAsync());
    }
    [Fact] public async Task WordConvertsOnceAndCrossBookPreviewUsesImmutableSharedPdfCache() {
        await using var f=new Factory();using var admin=await f.Login("page-word",true);var seed=await Seed(f);var word=Word();var first=await Upload(admin,seed.Class.Id,seed.Course.Id,word,file:"word-a.docx");var ready=await Ready(f,admin,first);Assert.Equal(2,ready.GetProperty("pageCount").GetInt32());
        f.Services.GetRequiredService<IConfiguration>()["Documents:Soffice"]="must-not-convert-a-second-time";
        var second=await Upload(admin,seed.Class.Id,seed.Course.Id,word,file:"word-b.docx");var reused=await Ready(f,admin,second);Assert.Equal(ready.GetProperty("sourceVersion").GetString(),reused.GetProperty("sourceVersion").GetString());Assert.Equal(await admin.GetByteArrayAsync(ready.GetProperty("sourceUrl").GetString()),await admin.GetByteArrayAsync(reused.GetProperty("sourceUrl").GetString()));
    }
    [Fact] public async Task PageSplitImportRegistersReusableResultsAndCrossClassCloneKeepsReadOnlyOriginalPreview() {
        await using var f=new Factory();using var admin=await f.Login("page-e2e",true);var seed=await Seed(f);var pdf=Pdf(2);var id=await Upload(admin,seed.Class.Id,seed.Course.Id,pdf);var ready=await Ready(f,admin,id);await Factory.Post(admin,$"/api/books/{id}/page-split",Input(ready,1));await f.Run("book-split",id);
        Guid[] children;using(var s=f.Services.CreateScope())children=Json.Read<BookSection[]>((await s.ServiceProvider.GetRequiredService<Data>().BookImports.FindAsync(id))!.SectionsJson).Select(x=>x.HandoutId!.Value).ToArray();
        foreach(var child in children)await f.Run("import",child);
        Guid target;using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();Assert.All(await db.Handouts.ToArrayAsync(),h=>Assert.Equal("draft",h.State));Assert.All(await db.Questions.ToArrayAsync(),q=>{Assert.False(q.Confirmed);Assert.NotEmpty(Json.Read<string[]>(q.PreviewsJson));});var cl=new SchoolClass{Name="复用目标班",CampusId=seed.Class.CampusId};db.Classes.Add(cl);await db.SaveChangesAsync();target=cl.Id;}
        var reused=await Upload(admin,target,seed.Course.Id,pdf);Assert.NotEqual(id,reused);
        using(var s=f.Services.CreateScope()){var db=s.ServiceProvider.GetRequiredService<Data>();var b=(await db.BookImports.FindAsync(reused))!;Assert.Equal("split",b.State);Assert.False(await db.Work.AnyAsync(w=>w.EntityId==reused));Assert.Equal(4,await db.Handouts.CountAsync());Assert.NotNull(await HandoutReuse.Summary(s.ServiceProvider.GetRequiredService<IObjects>(),"book",reused));}
        await Factory.Post(admin,$"/api/books/{reused}/page-preview",new{});var preview=await Ready(f,admin,reused);Assert.Equal("ready",preview.GetProperty("state").GetString());Assert.True(preview.GetProperty("hasChildren").GetBoolean());Assert.False(preview.GetProperty("canSplit").GetBoolean());Assert.Equal(pdf,await admin.GetByteArrayAsync(preview.GetProperty("sourceUrl").GetString()));
    }
}
