using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class DocumentRecoveryTests
{
    [Fact]
    public async Task OpenApiContractIncludesRecoveryWithoutOptionalGuidSchemaFailure()
    {
        await using var f = new Factory(); using var client = f.CreateClient();
        var response = await client.GetAsync("/openapi/v1.json"); Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var schema = await response.Content.ReadFromJsonAsync<System.Text.Json.JsonElement>(); var paths = schema.GetProperty("paths");
        Assert.True(paths.TryGetProperty("/api/handouts/{id}/retry", out _)); Assert.True(paths.TryGetProperty("/api/exports/{id}/retry", out _));
        Assert.True(paths.TryGetProperty("/api/admin/catalog/{kind}", out _));
        using var admin = await f.Login("catalog-defaults", true);
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "默认身份验收" });
        Assert.Equal("teacher", staff.GetProperty("role").GetString()); Assert.True(staff.GetProperty("enabled").GetBoolean());
    }
    static async Task<(Handout handout, ExportDocument document)> Seed(Factory f, string state = "failed")
    {
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "恢复验收校区" }; var cl = new SchoolClass { Name = "恢复验收班", CampusId = campus.Id };
        var course = new Course { Name = "数学" }; var lecture = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 1 };
        var handout = new Handout { LectureId = lecture.Id, State = state, Error = "模拟转换中断", OriginalKey = "recovery/source.docx", FileName = "恢复验收.docx" };
        var document = new ExportDocument { ClassId = cl.Id, CourseId = course.Id, Kind = "file", State = state, Error = "模拟转换中断", SourceKey = handout.OriginalKey, SourceFileName = handout.FileName };
        db.AddRange(campus, cl, course, lecture, handout, document); await db.SaveChangesAsync(); return (handout, document);
    }
    static async Task Tick(Factory f) { using var scope = f.Services.CreateScope(); Assert.True(await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(default)); }
    static async Task RestoreSource(Factory f)
    {
        using var scope = f.Services.CreateScope(); using var bytes = new MemoryStream();
        using (var doc = WordprocessingDocument.Create(bytes, DocumentFormat.OpenXml.WordprocessingDocumentType.Document, true))
            doc.AddMainDocumentPart().Document = new Document(new Body(new Paragraph(new Run(new Text("第一讲 方程"))), new Paragraph(new Run(new Text("例1. 解方程 x+2=5。")))));
        bytes.Position = 0; await scope.ServiceProvider.GetRequiredService<IObjects>().Put("recovery/source.docx", bytes, WordDocuments.DocxType);
    }
    [Fact]
    public async Task ConcurrentRetriesQueueOnceAndPreserveDocumentIdentityWithoutPrinting()
    {
        await using var f = new Factory(); using var admin = await f.Login("retry-admin", true); var (h, d) = await Seed(f);
        foreach (var path in new[] { $"/api/handouts/{h.Id}/retry", $"/api/exports/{d.Id}/retry" }) {
            var responses = await Task.WhenAll(Enumerable.Range(0, 4).Select(_ => admin.PostAsJsonAsync(path, new {})));
            Assert.All(responses, r => Assert.Equal(HttpStatusCode.Accepted, r.StatusCode));
        }
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        Assert.Equal(2, await db.Work.CountAsync()); Assert.Single(await db.Handouts.ToListAsync()); Assert.Single(await db.Documents.ToListAsync());
        Assert.Empty(await db.PrintJobs.ToListAsync()); Assert.Equal(2, await db.Audits.CountAsync(a => a.Action.EndsWith(".retry")));
        Assert.Equal("queued", (await db.Handouts.FindAsync(h.Id))!.State); Assert.Null((await db.Documents.FindAsync(d.Id))!.Error);
    }
    [Fact]
    public async Task FailedImportCanRecoverAfterStorageReturnsAndPublishedVersionCannotBeRetried()
    {
        await using var f = new Factory(); using var admin = await f.Login("import-admin", true); var (h, _) = await Seed(f);
        Assert.Equal(HttpStatusCode.Accepted, (await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/retry", new {})).StatusCode);
        await Tick(f); // Object storage is missing the source: failure must be explicit, not a draft with missing questions.
        using (var scope = f.Services.CreateScope()) Assert.Equal("failed", (await scope.ServiceProvider.GetRequiredService<Data>().Handouts.FindAsync(h.Id))!.State);
        await RestoreSource(f);
        Assert.Equal(HttpStatusCode.Accepted, (await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/retry", new {})).StatusCode); await Tick(f);
        Guid questionId;
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); var recovered = (await db.Handouts.FindAsync(h.Id))!;
            Assert.Equal("draft", recovered.State); Assert.Null(recovered.Error);
            var question = Assert.Single(await db.Questions.ToListAsync()); questionId = question.Id; Assert.StartsWith("例1", question.Label); Assert.NotEqual("[]", question.PreviewsJson);
            recovered.State = "published"; await db.SaveChangesAsync();
        }
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/retry", new {})).StatusCode);
        using (var scope = f.Services.CreateScope()) Assert.Equal(questionId, (await scope.ServiceProvider.GetRequiredService<Data>().Questions.SingleAsync()).Id);
    }
    [Fact]
    public async Task ExpiredWorkerLeaseRecoversOriginalDocumentAndReadyRetryDoesNotPrintOrRegenerate()
    {
        await using var f = new Factory(); using var admin = await f.Login("worker-admin", true); var (_, d) = await Seed(f, "processing"); await RestoreSource(f);
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); db.Work.Add(new WorkItem { EntityId = d.Id, Kind = "file", State = "running", Claim = "crashed-worker", Attempts = 1, LeaseUntil = Clock.Now.AddMinutes(-1) }); await db.SaveChangesAsync();
        }
        await Tick(f);
        Assert.Equal(HttpStatusCode.Accepted, (await admin.PostAsJsonAsync($"/api/exports/{d.Id}/retry", new {})).StatusCode);
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); var result = (await db.Documents.FindAsync(d.Id))!;
            Assert.Equal("ready", result.State); Assert.NotEmpty(result.PdfHash); var work = Assert.Single(await db.Work.ToListAsync()); Assert.Equal("done", work.State); Assert.Equal(2, work.Attempts); Assert.Empty(await db.PrintJobs.ToListAsync());
        }
    }
    [Fact]
    public async Task PreviewRecoveryKeepsQuestionIdentityAndRepeatedRequestIsHarmless()
    {
        await using var f = new Factory(); using var admin = await f.Login("preview-admin", true); var (h, _) = await Seed(f, "draft");
        var q = new Question { HandoutId = h.Id, Label = "例1", Revision = 3 };
        using (var scope = f.Services.CreateScope()) { var db = scope.ServiceProvider.GetRequiredService<Data>(); db.Questions.Add(q); await db.SaveChangesAsync(); }
        for (var i = 0; i < 2; i++) Assert.Equal(HttpStatusCode.Accepted, (await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/retry", new {})).StatusCode);
        using (var scope = f.Services.CreateScope()) { var db = scope.ServiceProvider.GetRequiredService<Data>(); Assert.Equal("previews", (await db.Work.SingleAsync()).Kind); Assert.Equal(q.Id, (await db.Questions.SingleAsync()).Id); Assert.Equal(3, (await db.Questions.SingleAsync()).Revision); }
    }
    [Fact]
    public async Task TeachersCannotRetryOtherClassesOrSharedUnpublishedHandouts()
    {
        await using var f = new Factory(); using var admin = await f.Login("permission-admin", true); var (h, d) = await Seed(f);
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "其他老师" }); var sid = staff.GetProperty("id").GetGuid();
        var invite = await Factory.Post(admin, $"/api/admin/staff/{sid}/invite", new {}); using var teacher = await f.Login("other-teacher"); await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        foreach (var path in new[] { $"/api/handouts/{h.Id}/retry", $"/api/exports/{d.Id}/retry" }) Assert.Equal(HttpStatusCode.Forbidden, (await teacher.PostAsJsonAsync(path, new {})).StatusCode);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var lecture = (await db.Lectures.FindAsync(h.LectureId))!;
        db.Assignments.Add(new Assignment { StaffId = sid, ClassId = d.ClassId!.Value, CourseId = d.CourseId!.Value }); lecture.ClassId = null; await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.PostAsJsonAsync($"/api/handouts/{h.Id}/retry", new {})).StatusCode); Assert.Empty(await db.Work.ToListAsync());
    }
    [Fact] public async Task OneQuestionPreviewFailureDoesNotHideOtherQuestionsAndRetryKeepsTheirKeys() {
        await using var f=new Factory();using var admin=await f.Login("partial-preview",true);var (h,_)=await Seed(f,"draft");await RestoreSource(f);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
        var stored=(await db.Handouts.FindAsync(h.Id))!;stored.NormalizedKey=stored.OriginalKey;
        var w=WordDocuments.W;var block=WordDocuments.Block(new System.Xml.Linq.XElement(w+"p",new System.Xml.Linq.XElement(w+"r",new System.Xml.Linq.XElement(w+"t","例1 计算 2+2。"))));
        var good=new Question{HandoutId=h.Id,Order=1,Label="例1",BlocksJson=Json.Write(new[]{block})};
        var bad=new Question{HandoutId=h.Id,Order=2,Label="例2",BlocksJson=Json.Write(new[]{block with {Xml="<invalid"}})};
        var last=new Question{HandoutId=h.Id,Order=3,Label="例3",BlocksJson=good.BlocksJson};
        db.AddRange(good,bad,last);await db.SaveChangesAsync();await f.Run("previews",h.Id);db.ChangeTracker.Clear();
        var before=await db.Questions.OrderBy(q=>q.Order).ToArrayAsync();Assert.NotEqual("[]",before[0].PreviewsJson);Assert.Equal("[]",before[1].PreviewsJson);Assert.NotEqual("[]",before[2].PreviewsJson);
        Assert.Contains(Json.Read<Issue[]>(before[1].IssuesJson),i=>i.Code=="preview-failed"&&i.Blocking);
        Assert.Equal("draft",(await db.Handouts.FindAsync(h.Id))!.State);
        Assert.Equal(3,(await admin.GetFromJsonAsync<JsonElement>($"/api/handouts/{h.Id}")).GetProperty("questions").GetArrayLength());
        await Factory.Post(admin,$"/api/handouts/{h.Id}/confirm-all",new{revision=0});
        Assert.False((await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/publish",new{revision=1})).IsSuccessStatusCode);
        before[1].BlocksJson=good.BlocksJson;await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.Accepted,(await admin.PostAsJsonAsync($"/api/handouts/{h.Id}/retry",new{})).StatusCode);await f.Run("previews",h.Id);db.ChangeTracker.Clear();
        var after=await db.Questions.OrderBy(q=>q.Order).ToArrayAsync();Assert.Equal(before.Select(q=>q.Id),after.Select(q=>q.Id));
        Assert.Equal(before[0].PreviewsJson,after[0].PreviewsJson);Assert.Equal(before[2].PreviewsJson,after[2].PreviewsJson);
        Assert.NotEqual("[]",after[1].PreviewsJson);Assert.DoesNotContain(Json.Read<Issue[]>(after[1].IssuesJson),i=>i.Code=="preview-failed");
        Assert.Null((await db.Handouts.FindAsync(h.Id))!.Error);
    }
    [Fact] public async Task FailedBookSectionDoesNotPreventLaterSectionsAndRetryOnlyQueuesMissingSlice() {
        await using var f=new Factory();var (seed,_)=await Seed(f);await RestoreSource(f);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
        var hands=Enumerable.Range(0,3).Select(i=>new Handout{LectureId=seed.LectureId,Version=i+2,OriginalKey="slice/"+Guid.NewGuid()+".docx"}).ToArray();
        var sections=new[]{new BookSection(0,1,1,"第一段",HandoutId:hands[0].Id),new BookSection(9,12,2,"坏范围",HandoutId:hands[1].Id),new BookSection(1,2,3,"第三段",HandoutId:hands[2].Id)};
        var lecture=(await db.Lectures.FindAsync(seed.LectureId))!;
        var staff=new Staff{Name="分讲测试",Role="admin"};db.Add(staff);
        var b=new BookImport{StaffId=staff.Id,ClassId=lecture.ClassId!.Value,CourseId=lecture.CourseId,State="splitting",NormalizedKey="recovery/source.docx",SectionsJson=Json.Write(sections)};
        db.AddRange(hands);db.Add(b);db.AddRange(hands.Select(h=>new WorkItem{Kind="import",EntityId=h.Id,State="waiting"}));await db.SaveChangesAsync();
        await f.Run("book-split",b.Id);db.ChangeTracker.Clear();
        Assert.Equal("failed",(await db.BookImports.FindAsync(b.Id))!.State);
        Assert.Equal(2,await db.Work.CountAsync(w=>w.State=="queued"));Assert.Equal(1,await db.Work.CountAsync(w=>w.State=="waiting"));
        sections[1]=sections[1] with {Start=0,End=1};(await db.BookImports.FindAsync(b.Id))!.SectionsJson=Json.Write(sections);await db.SaveChangesAsync();
        await f.Run("book-split",b.Id);db.ChangeTracker.Clear();Assert.Equal("split",(await db.BookImports.FindAsync(b.Id))!.State);
        Assert.Equal(3,await db.Work.CountAsync(w=>w.State=="queued"));Assert.Equal(3,await db.Work.CountAsync());
    }

}
