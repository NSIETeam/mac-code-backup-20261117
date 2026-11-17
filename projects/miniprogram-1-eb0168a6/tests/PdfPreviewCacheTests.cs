using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using UglyToad.PdfPig.Writer;
using Xunit;

public class PdfPreviewCacheTests
{
    sealed record Fixture(Handout[] Handouts, Guid ClassA, Guid ClassB, Guid TeacherA, Guid TeacherB, byte[] Pdf);
    static byte[] Pdf(string text) {
        var builder = new PdfDocumentBuilder(); var font = builder.AddStandard14Font(Standard14Font.Helvetica);
        builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4).AddText(text, 16, new PdfPoint(60, 720), font);
        return builder.Build();
    }
    static async Task<Fixture> Seed(Factory f, int count = 1) {
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var campus = new Campus { Name = "预览缓存验收" }; var course = new Course { Name = "数学" };
        var a = new SchoolClass { Name = "甲班", CampusId = campus.Id }; var b = new SchoolClass { Name = "乙班", CampusId = campus.Id };
        var teacherA = new Staff { Name = "甲教师", OpenId = "development:pdf-cache-a" }; var teacherB = new Staff { Name = "乙教师", OpenId = "development:pdf-cache-b" };
        db.AddRange(campus, course, a, b, teacherA, teacherB, new Assignment { ClassId = a.Id, CourseId = course.Id, StaffId = teacherA.Id }, new Assignment { ClassId = b.Id, CourseId = course.Id, StaffId = teacherB.Id });
        var handouts = new List<Handout>(); var pdf = Pdf("ORIGINAL SOURCE QUESTION");
        for (var i = 0; i < count; i++) {
            var lecture = new Lecture { ClassId = i % 2 == 0 ? a.Id : b.Id, CourseId = course.Id, Sequence = i + 1, Title = "讲次" + (i + 1) };
            // Identical bytes must share pages even if they have distinct source object keys.
            var handout = new Handout { LectureId = lecture.Id, State = "published", FileName = "讲义.pdf", SourcePdfKey = i < count / 2 ? "books/shared/source.pdf" : $"books/copy-{i}/source.pdf", Version = 1 };
            db.AddRange(lecture, handout); handouts.Add(handout);
            await objects.Put(handout.SourcePdfKey, new MemoryStream(pdf), "application/pdf");
        }
        await db.SaveChangesAsync(); return new(handouts.ToArray(), a.Id, b.Id, teacherA.Id, teacherB.Id, pdf);
    }
    static string PathFor(Handout h) => $"/api/previews/handout/{h.Id}";
    static async Task Tick(Factory f) { using var scope = f.Services.CreateScope(); Assert.True(await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(default)); }
    static async Task<JsonElement> Ready(HttpClient client, Handout h) {
        var result = await client.GetFromJsonAsync<JsonElement>(PathFor(h)); Assert.Equal("ready", result.GetProperty("state").GetString()); Assert.Single(result.GetProperty("pages").EnumerateArray()); return result;
    }
    static string Url(JsonElement result) => result.GetProperty("pages")[0].GetProperty("url").GetString()!;

    [Fact]
    public async Task SixteenLecturesShareOneRenderedSourceButKeepTeacherGrantsScoped() {
        await using var f = new Factory(); var fixture = await Seed(f, 16); using var a = await f.Login("pdf-cache-a"); using var b = await f.Login("pdf-cache-b");
        await Factory.Post(a, PathFor(fixture.Handouts[0]), new { }); await Tick(f); var first = await Ready(a, fixture.Handouts[0]);
        // A second renderer invocation now fails, proving reuse is not just overwriting files.
        f.Services.GetRequiredService<IConfiguration>()["Documents:PdfToPpm"] = "unavailable-after-first-preview";
        JsonElement other = default;
        for (var i = 1; i < fixture.Handouts.Length; i++) {
            var client = i % 2 == 0 ? a : b; var handout = fixture.Handouts[i];
            await Factory.Post(client, PathFor(handout), new { key = "client-must-not-select-an-object" }); await Tick(f);
            var result = await Ready(client, handout); if (i == 1) other = result;
        }
        var sharedRoot = System.IO.Path.Combine(f.Root, "files", "pdf-previews"); Assert.Single(Directory.GetFiles(sharedRoot, "*.png", SearchOption.AllDirectories));
        Assert.Equal(await a.GetByteArrayAsync(Url(first)), await b.GetByteArrayAsync(Url(other)));
        Assert.Equal(HttpStatusCode.Forbidden, (await a.GetAsync(PathFor(fixture.Handouts[1]))).StatusCode);
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); var grants = await db.Grants.Where(g => g.ContentType == "image/png").ToArrayAsync();
            Assert.Single(grants.Select(g => g.ObjectKey).Distinct()); Assert.Contains(grants, g => g.StaffId == fixture.TeacherA && g.ClassId == fixture.ClassA); Assert.Contains(grants, g => g.StaffId == fixture.TeacherB && g.ClassId == fixture.ClassB);
            await db.Assignments.Where(x => x.StaffId == fixture.TeacherA).ExecuteDeleteAsync();
        }
        Assert.Equal(HttpStatusCode.Forbidden, (await a.GetAsync(Url(first))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await b.GetAsync(Url(other))).StatusCode);
    }

    [Fact]
    public async Task ReplacingBytesAtTheSameSourceKeyInvalidatesOnlyThatSourceSnapshot() {
        await using var f = new Factory(); var fixture = await Seed(f); var h = fixture.Handouts[0]; using var a = await f.Login("pdf-cache-a");
        await Factory.Post(a, PathFor(h), new { }); await Tick(f); var old = await Ready(a, h); var oldPng = await a.GetByteArrayAsync(Url(old));
        var replacement = Pdf("REPLACED SOURCE QUESTION");
        using (var scope = f.Services.CreateScope()) await scope.ServiceProvider.GetRequiredService<IObjects>().Put(h.SourcePdfKey, new MemoryStream(replacement), "application/pdf");
        var stale = await a.GetFromJsonAsync<JsonElement>(PathFor(h)); Assert.Equal("processing", stale.GetProperty("state").GetString()); Assert.Empty(stale.GetProperty("pages").EnumerateArray());
        await Tick(f); var current = await Ready(a, h); Assert.NotEqual(oldPng, await a.GetByteArrayAsync(Url(current)));
        using var final = f.Services.CreateScope(); var db = final.ServiceProvider.GetRequiredService<Data>(); var objects = final.ServiceProvider.GetRequiredService<IObjects>();
        Assert.Single(await db.Work.ToArrayAsync()); var hash = Convert.ToHexStringLower(SHA256.HashData(replacement));
        var manifest = await PdfPreviews.ReadManifest(objects, PdfPreviews.Prefix("handout", h.Id, h.SourcePdfKey) + "/source.json", hash, default); Assert.NotNull(manifest);
        Assert.Equal(2, Directory.GetFiles(System.IO.Path.Combine(f.Root, "files", "pdf-previews"), "*.png", SearchOption.AllDirectories).Length);
        // Old image grants stay byte-stable until their ordinary expiry; no file is overwritten.
        Assert.Equal(oldPng, await a.GetByteArrayAsync(Url(old)));
    }

    [Fact]
    public async Task LegacyArrayManifestRebuildsOnceAndRemainsReadableByOldClients() {
        await using var f = new Factory(); var fixture = await Seed(f); var h = fixture.Handouts[0]; using var a = await f.Login("pdf-cache-a");
        var prefix = PdfPreviews.Prefix("handout", h.Id, h.SourcePdfKey); var oldImage = new byte[] { 1, 2, 3, 4 };
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
            db.Work.Add(new WorkItem { Id = PdfPreviews.JobId("handout", h.Id, h.SourcePdfKey), Kind = "handout-preview", EntityId = h.Id, State = "done" }); await db.SaveChangesAsync();
            await objects.Put(prefix + "/page-1.png", new MemoryStream(oldImage), "image/png");
            await PdfPreviews.WriteManifest(objects, prefix + "/manifest.json", new[] { new PdfPreviewPage(prefix + "/page-1.png", 1, 1, 1) }, default);
        }
        var legacy = await a.GetFromJsonAsync<JsonElement>(PathFor(h)); Assert.Equal("processing", legacy.GetProperty("state").GetString()); Assert.Empty(legacy.GetProperty("pages").EnumerateArray());
        await Tick(f); await Ready(a, h);
        using var final = f.Services.CreateScope(); var storage = final.ServiceProvider.GetRequiredService<IObjects>();
        await using var stream = await storage.Get(prefix + "/manifest.json"); using var reader = new StreamReader(stream);
        var compatible = Json.Read<PdfPreviewPage[]>(await reader.ReadToEndAsync()); Assert.Single(compatible); Assert.Contains("source-v2-1600", compatible[0].Key);
        await using var old = await storage.Get(prefix + "/page-1.png"); using var copy = new MemoryStream(); await old.CopyToAsync(copy); Assert.Equal(oldImage, copy.ToArray());
    }

    [Fact]
    public async Task ManifestCannotGrantUnrelatedObjectKeysOrAnotherSourcesPages() {
        await using var f = new Factory(); using var scope = f.Services.CreateScope(); var objects = scope.ServiceProvider.GetRequiredService<IObjects>();
        var hash = new string('a', 64); var key = PdfPreviews.SharedPrefix(hash) + "/manifest.json";
        await PdfPreviews.WriteManifest(objects, key, new PdfPreviewManifest(hash, [new("private/other.png", 1, 100, 100)]), default);
        Assert.Null(await PdfPreviews.ReadManifest(objects, key, hash, default));
        await PdfPreviews.WriteManifest(objects, key, new PdfPreviewManifest(new string('b', 64), [new(PdfPreviews.SharedPrefix(hash) + "/page-1.png", 1, 100, 100)]), default);
        Assert.Null(await PdfPreviews.ReadManifest(objects, key, hash, default));
    }
}
