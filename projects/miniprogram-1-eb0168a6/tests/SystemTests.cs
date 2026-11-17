using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Siyue.Printing;
using Xunit;
using System.Xml.Linq;
using UglyToad.PdfPig;

public class Factory : WebApplicationFactory<Program>
{
    public readonly string Root = Path.GetFullPath(Path.Combine(".local", "test-" + Guid.NewGuid().ToString("N")));
    protected override void ConfigureWebHost(IWebHostBuilder builder) {
        Directory.CreateDirectory(Root); builder.UseEnvironment("Testing"); builder.ConfigureAppConfiguration((_, c) => c.AddInMemoryCollection(new Dictionary<string, string?> {
            ["Database:Provider"] = "sqlite", ["Database:Initialize"] = "true", ["ConnectionStrings:Database"] = "Data Source=" + Path.Combine(Root, "test.db"), ["Storage:Provider"] = "local", ["Storage:Directory"] = Path.Combine(Root, "files"),
            ["PublicUrl"] = "http://localhost", ["BootstrapCode"] = "test-bootstrap-secret", ["DevelopmentAuthKey"] = "test-development-secret", ["RunWorker"] = "false",
            ["Documents:Python"] = Environment.GetEnvironmentVariable("SIYUE_PYTHON") ?? "python3", ["Documents:PdfToText"] = Environment.GetEnvironmentVariable("SIYUE_PDFTOTEXT") ?? "pdftotext", ["Documents:Soffice"] = Environment.GetEnvironmentVariable("SIYUE_SOFFICE") ?? "soffice", ["Documents:PdfToPpm"] = Environment.GetEnvironmentVariable("SIYUE_PDFTOPPM") ?? "pdftoppm", ["Documents:PdfDiagnosticsDirectory"] = Environment.GetEnvironmentVariable("SIYUE_REAL_PDF_OUTPUT"), ["Documents:Tesseract"] = Environment.GetEnvironmentVariable("SIYUE_TESSERACT") ?? "tesseract", ["Documents:DiagnosticsDirectory"] = Path.GetFullPath(".local/acceptance/debug"), ["Documents:FontDirectory"] = Environment.GetEnvironmentVariable("SIYUE_FONT_DIR") ?? (OperatingSystem.IsMacOS() ? "/System/Library/Fonts/Supplemental" : "/usr/share/fonts")
        }));
    }
    public async Task<HttpClient> Login(string openId, bool bootstrap = false) {
        var client = CreateClient(); var result = await Post(client, "/api/auth/development", new { openId, key = "test-development-secret" }); client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", result.GetProperty("token").GetString());
        if (bootstrap) await Post(client, "/api/auth/bootstrap", new { code = "test-bootstrap-secret", name = "验收管理员" }); return client;
    }
    public static async Task<JsonElement> Post(HttpClient client, string url, object value) { var r = await client.PostAsJsonAsync(url, value); Assert.True(r.IsSuccessStatusCode, await r.Content.ReadAsStringAsync()); return r.StatusCode == HttpStatusCode.NoContent ? default : await r.Content.ReadFromJsonAsync<JsonElement>(); }
    public async Task Run(string kind, Guid id) { using var scope = Services.CreateScope(); await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Process(kind, id, CancellationToken.None); }
}
public class SystemTests
{
    [Fact]
    public async Task WordImportExportPermissionsAndPrinterIdempotency()
    {
        await using var f = new Factory(); using var admin = await f.Login("admin", true); Assert.Equal(HttpStatusCode.NotFound, (await admin.PostAsJsonAsync("/api/auth/preview", new {})).StatusCode);
        var campus = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "测试校区" }); var campusId = campus.GetProperty("id").GetGuid();
        var campus2 = await Factory.Post(admin, "/api/admin/catalog/campuses", new { name = "其他校区" });
        var cl = await Factory.Post(admin, "/api/admin/catalog/classes", new { name = "初一一班", campusId }); var classId = cl.GetProperty("id").GetGuid();
        var other = await Factory.Post(admin, "/api/admin/catalog/classes", new { name = "其他班级", campusId = campus2.GetProperty("id").GetGuid() }); var otherId = other.GetProperty("id").GetGuid();
        var course = await Factory.Post(admin, "/api/admin/catalog/courses", new { name = "数学" }); var courseId = course.GetProperty("id").GetGuid();
        var course2 = await Factory.Post(admin, "/api/admin/catalog/courses", new { name = "语文" });
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "测试教师" }); var staffId = staff.GetProperty("id").GetGuid();
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staffId}/invite", new { }); using var teacher = await f.Login("teacher");
        await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        using var stranger = await f.Login("stranger"); var reused = await stranger.PostAsJsonAsync("/api/auth/bind", new { code = invite.GetProperty("code").GetString() }); Assert.Equal(HttpStatusCode.BadRequest, reused.StatusCode);
        var assignment = await Factory.Post(admin, "/api/admin/catalog/assignments", new { staffId, classId, courseId });
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/classes/{otherId}?courseId={courseId}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/classes/{classId}?courseId={course2.GetProperty("id").GetGuid()}")).StatusCode);
        var student = await Factory.Post(admin, "/api/admin/catalog/students", new { name = "验收学生甲", code = "001", classId }); var studentId = student.GetProperty("id").GetGuid();
        var student2 = await Factory.Post(admin, "/api/admin/catalog/students", new { name = "验收学生乙", code = "002", classId }); var student2Id = student2.GetProperty("id").GetGuid();
        var lecture = await Factory.Post(admin, "/api/lectures", new { classId, courseId, title = "方程与应用", sequence = 1 }); var lectureId = lecture.GetProperty("id").GetGuid();
        var fixture = Path.Combine(f.Root, "验收讲义.docx"); Fixture(fixture);
        using var form = new MultipartFormDataContent(); form.Add(new StreamContent(File.OpenRead(fixture)), "file", "验收讲义.docx"); form.Add(new StringContent("legacy"), "recognitionMode"); var upload = await admin.PostAsync($"/api/lectures/{lectureId}/handouts", form); Assert.Equal(HttpStatusCode.Accepted, upload.StatusCode); var hid = (await upload.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
        await f.Run("import", hid);
        var detail = await admin.GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}"); var questions = detail.GetProperty("questions").EnumerateArray().ToArray(); Assert.Equal(4, questions.Length);
        Assert.Contains(questions, q => q.GetProperty("label").GetString()!.StartsWith("例1")); Assert.Contains(questions, q => q.GetProperty("label").GetString()!.StartsWith("练1"));
        Assert.All(questions, q => Assert.NotEmpty(q.GetProperty("previews").EnumerateArray()));
        var qids = questions.Select(q => q.GetProperty("id").GetGuid()).ToArray();
        // Word uploads now follow the fixed PDF-layout pipeline. Review the known synthetic cross-page fixture before publishing.
        await Factory.Post(admin, $"/api/handouts/{hid}/verify-selected", new { revision = detail.GetProperty("handout").GetProperty("revision").GetInt32(), questionIds = qids });
        foreach (var qid in qids) { detail = await admin.GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}"); await Factory.Post(admin, $"/api/handouts/{hid}/edit", new { action = "confirm", revision = detail.GetProperty("handout").GetProperty("revision").GetInt32(), questionId = qid }); }
        detail = await admin.GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}"); await Factory.Post(admin, $"/api/handouts/{hid}/publish", new { revision = detail.GetProperty("handout").GetProperty("revision").GetInt32() });
        var immutable = await admin.PostAsJsonAsync($"/api/handouts/{hid}/edit", new { action = "label", revision = 0, questionId = qids[0], label = "例99" }); Assert.Equal(HttpStatusCode.Conflict, immutable.StatusCode);
        var wrong = new { handoutId = hid, studentIds = new[] { studentId, student2Id }, questionIds = qids, mode = "add" };
        Assert.True((await teacher.PutAsJsonAsync("/api/wrong-questions", wrong)).IsSuccessStatusCode); Assert.True((await teacher.PutAsJsonAsync("/api/wrong-questions", wrong)).IsSuccessStatusCode);
        var batch = await Factory.Post(teacher, "/api/exports", new { classId, courseId, studentIds = new[] { studentId, student2Id }, lectureIds = new[] { lectureId } });
        var docs = batch.GetProperty("documents").EnumerateArray().Select(x => x.GetProperty("id").GetGuid()).ToArray(); Assert.Equal(2, docs.Length); foreach (var id in docs) await f.Run("export", id);
        var download = await teacher.GetFromJsonAsync<JsonElement>($"/api/exports/{docs[0]}/download?format=pdf"); var pdfUrl = download.GetProperty("url").GetString()!;
        var pdfBytes = await teacher.GetByteArrayAsync(pdfUrl); using var pdf = PdfDocument.Open(pdfBytes); Assert.True(pdf.NumberOfPages >= 3); Assert.Contains(batch.GetProperty("documents").EnumerateArray().First(x => x.GetProperty("id").GetGuid() == docs[0]).GetProperty("studentName").GetString()!, string.Concat(pdf.GetPages().Select(x => x.Text)));
        Assert.Contains("步骤说明", string.Join("\n",questions.SelectMany(q=>q.GetProperty("blocks").EnumerateArray()).Select(b=>b.GetProperty("text").GetString()))); Assert.DoesNotContain("SYEND", string.Concat(pdf.GetPages().Select(x => x.Text)));
        Directory.CreateDirectory(Path.GetFullPath(".local/acceptance")); await File.WriteAllBytesAsync(Path.GetFullPath(".local/acceptance/错题本验收.pdf"), pdfBytes);
        var docxDownload = await teacher.GetFromJsonAsync<JsonElement>($"/api/exports/{docs[0]}/download?format=docx"); var docx = await teacher.GetByteArrayAsync(docxDownload.GetProperty("url").GetString()!); await File.WriteAllBytesAsync(Path.GetFullPath(".local/acceptance/错题本验收.docx"), docx);
        using (var m = new MemoryStream(docx)) using (var pack = WordprocessingDocument.Open(m, false)) { Assert.NotEmpty(pack.MainDocumentPart!.ImageParts); /* Formulas remain in source PDF images, not regenerated math XML. */ }
        var previewPath = $"/api/previews/export/{docs[0]}";
        await Task.WhenAll(Factory.Post(teacher, previewPath, new {}), Factory.Post(teacher, previewPath, new {}));
        using (var scope = f.Services.CreateScope()) { var db = scope.ServiceProvider.GetRequiredService<Data>(); Assert.Equal(1, await db.Work.CountAsync(x => x.Kind == "export-preview" && x.EntityId == docs[0])); }
        for (var attempt = 0; attempt < 10; attempt++) {
            var state = await teacher.GetFromJsonAsync<JsonElement>(previewPath);
            if (state.GetProperty("state").GetString() == "ready") break;
            using var scope = f.Services.CreateScope(); Assert.True(await scope.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(CancellationToken.None));
        }
        var preview = await teacher.GetFromJsonAsync<JsonElement>(previewPath); Assert.Equal("ready", preview.GetProperty("state").GetString());
        var previewPages = preview.GetProperty("pages").EnumerateArray().ToArray(); Assert.Equal(pdf.NumberOfPages, previewPages.Length);
        Assert.Equal(Enumerable.Range(1, pdf.NumberOfPages), previewPages.Select(x => x.GetProperty("number").GetInt32()));
        var previewUrl = previewPages[0].GetProperty("url").GetString()!;
        var pageBytes = await teacher.GetByteArrayAsync(previewUrl); Assert.Equal(new byte[] {137,80,78,71,13,10,26,10}, pageBytes[..8]);
        Assert.Equal(1600, System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(pageBytes.AsSpan(20,4)));
        Assert.Equal(pdfBytes, await teacher.GetByteArrayAsync(pdfUrl)); // Preview rendering cannot rewrite the printed PDF.
        Assert.Equal("processing", (await Factory.Post(teacher, $"/api/previews/handout/{hid}", new {})).GetProperty("state").GetString());
        // Existing print documents remain immutable when an older template is reflowed.
        using (var scope = f.Services.CreateScope()) await scope.ServiceProvider.GetRequiredService<Data>().Documents.Where(x => x.Id == docs[0]).ExecuteUpdateAsync(s => s.SetProperty(x => x.TemplateVersion, "v1"));
        var reflow = await Factory.Post(teacher, $"/api/exports/{docs[0]}/reflow", new {}); var reflowId = reflow.GetProperty("id").GetGuid(); Assert.NotEqual(docs[0], reflowId);
        Assert.Equal(reflowId, (await Factory.Post(teacher, $"/api/exports/{docs[0]}/reflow", new {})).GetProperty("id").GetGuid());
        var pendingPreview = await Factory.Post(teacher, $"/api/previews/export/{reflowId}", new {}); Assert.Equal("export", pendingPreview.GetProperty("phase").GetString());
        await f.Run("export", reflowId);
        var latestDocuments = await teacher.GetFromJsonAsync<JsonElement>($"/api/exports?batchId={batch.GetProperty("batchId").GetGuid()}"); Assert.Equal(2, latestDocuments.GetArrayLength());
        Assert.DoesNotContain(latestDocuments.EnumerateArray(), d => d.GetProperty("id").GetGuid() == docs[0]);
        var reflowDownload = await teacher.GetFromJsonAsync<JsonElement>($"/api/exports/{reflowId}/download?format=pdf");
        using (var reflowPdf = PdfDocument.Open(await teacher.GetByteArrayAsync(reflowDownload.GetProperty("url").GetString()!))) Assert.Single(System.Text.RegularExpressions.Regex.Matches(string.Concat(reflowPdf.GetPages().Select(p => p.Text)), "日期"));
        Assert.Equal(pdfBytes, await teacher.GetByteArrayAsync(pdfUrl));
        var binding = await Factory.Post(admin, "/api/admin/device-bindings", new { campusId }); using var device = f.CreateClient(); var identity = await Factory.Post(device, "/agent/bind", new { code = binding.GetProperty("code").GetString(), name = "模拟校区电脑" }); device.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", identity.GetProperty("token").GetString());
        await Factory.Post(device, "/agent/heartbeat", new { printers = new[] { new { queueName = "验收队列", duplex = true } } });
        var catalog = await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog"); var printerId = catalog.GetProperty("printers")[0].GetProperty("id").GetGuid(); Assert.True((await admin.PutAsJsonAsync($"/api/admin/printers/{printerId}", new { name = "验收打印机", enabled = true })).IsSuccessStatusCode);
        var printInput = new { documentIds = docs, printerId, copies = 1, duplex = true, requestKey = "test-print-request" };
        var jobs = await Factory.Post(teacher, "/api/print-jobs", printInput); var again = await Factory.Post(teacher, "/api/print-jobs", printInput); Assert.Equal(jobs[0].GetProperty("id").GetGuid(), again[0].GetProperty("id").GetGuid());
        var conflict = await teacher.PostAsJsonAsync("/api/print-jobs", new { documentIds = docs, printerId, copies = 2, duplex = true, requestKey = "test-print-request" }); Assert.Equal(HttpStatusCode.Conflict, conflict.StatusCode);
        var claim = await Factory.Post(device, "/agent/claim", new { }); var jobId = claim.GetProperty("id").GetGuid(); var claimToken = claim.GetProperty("claimToken").GetString();
        await Factory.Post(device, $"/agent/jobs/{jobId}/begin", new { claimToken, state = "submitting" });
        using (var scope = f.Services.CreateScope()) { var db = scope.ServiceProvider.GetRequiredService<Data>(); await db.PrintJobs.Where(x => x.Id == jobId).ExecuteUpdateAsync(s => s.SetProperty(x => x.LeaseUntil, Clock.Now.AddMinutes(-1))); await PrintingEndpoints.Expire(db, DateTimeOffset.UtcNow); Assert.Equal("uncertain", (await db.PrintJobs.FindAsync(jobId))!.State); Assert.Equal(8, await db.WrongQuestions.CountAsync()); }
        var next = await Factory.Post(device, "/agent/claim", new { }); Assert.NotEqual(jobId, next.GetProperty("id").GetGuid());
        var unsafeReprint = await teacher.PostAsJsonAsync($"/api/print-jobs/{jobId}/reprint", new { requestKey = "test-reprint-request" }); Assert.Equal(HttpStatusCode.Conflict, unsafeReprint.StatusCode);
        await Factory.Post(teacher, $"/api/print-jobs/{jobId}/reprint", new { requestKey = "test-reprint-request", acknowledgeUncertain = true });
        Assert.True((await admin.DeleteAsync("/api/admin/assignments/" + assignment.GetProperty("id").GetGuid())).IsSuccessStatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync(pdfUrl)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync(previewPath)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.PostAsJsonAsync($"/api/exports/{docs[0]}/reflow", new {})).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync(previewUrl)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/exports/{docs[0]}/download?format=pdf")).StatusCode);
    }
    [Fact]
    public async Task LegacyDocActuallyConvertsAndKeepsQuestionBoundaries()
    {
        await using var f = new Factory(); _ = f.Services; using var scope = f.Services.CreateScope(); var word = scope.ServiceProvider.GetRequiredService<WordDocuments>(); var path = Path.Combine(f.Root, "legacy-source.docx"); Fixture(path);
        var doc = await word.Convert(path, "doc:MS Word 97", f.Root, CancellationToken.None); var converted = await word.Convert(doc, "docx", f.Root, CancellationToken.None);
        var questions = await word.Parse(converted, Guid.NewGuid(), CancellationToken.None); Assert.Equal(4, questions.Count);
    }
    internal static void Fixture(string path)
    {
        using var doc = WordprocessingDocument.Create(path, DocumentFormat.OpenXml.WordprocessingDocumentType.Document); var main = doc.AddMainDocumentPart(); var w = WordDocuments.W; XNamespace m = "http://schemas.openxmlformats.org/officeDocument/2006/math"; XNamespace r = WordDocuments.R;
        XElement P(string t) => new(w + "p", new XElement(w + "r", new XElement(w + "t", t)));
        var formula = XElement.Parse($"<w:p xmlns:w='{w}' xmlns:m='{m}'><m:oMath><m:f><m:num><m:r><m:t>x+2</m:t></m:r></m:num><m:den><m:r><m:t>5</m:t></m:r></m:den></m:f></m:oMath></w:p>");
        var body = new XElement(w + "body", P("第一讲 方程与应用"), P("例1. 求解下列方程，并写出计算过程。"), formula, P("（1）先化简，再求值。"), P("练1. 如图，求矩形的面积。"));
        var image = main.AddImagePart(ImagePartType.Png); image.FeedData(new MemoryStream(Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXRcAAAAASUVORK5CYII="))); var rid = main.GetIdOfPart(image);
        body.Add(XElement.Parse($"<w:p xmlns:w='{w}' xmlns:wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing' xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main' xmlns:pic='http://schemas.openxmlformats.org/drawingml/2006/picture' xmlns:r='{r}'><w:r><w:drawing><wp:inline><wp:extent cx='1400000' cy='500000'/><wp:docPr id='1' name='图形'/><a:graphic><a:graphicData uri='http://schemas.openxmlformats.org/drawingml/2006/picture'><pic:pic><pic:nvPicPr><pic:cNvPr id='0' name='图形'/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed='{rid}'/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x='0' y='0'/><a:ext cx='1400000' cy='500000'/></a:xfrm><a:prstGeom prst='rect'><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"));
        var numbering = main.AddNewPart<NumberingDefinitionsPart>(); numbering.Numbering = new Numbering($"<w:numbering xmlns:w='{w}'><w:abstractNum w:abstractNumId='1'><w:lvl w:ilvl='0'><w:start w:val='1'/><w:numFmt w:val='decimal'/><w:lvlText w:val='%1.'/></w:lvl><w:lvl w:ilvl='1'><w:start w:val='1'/><w:numFmt w:val='decimal'/><w:lvlText w:val='（%2）'/></w:lvl></w:abstractNum><w:num w:numId='1'><w:abstractNumId w:val='1'/></w:num></w:numbering>");
        var auto = P("小组同学完成植树任务，请列方程。"); auto.AddFirst(new XElement(w + "pPr", new XElement(w + "numPr", new XElement(w + "ilvl", new XAttribute(w + "val", 0)), new XElement(w + "numId", new XAttribute(w + "val", 1))))); body.Add(auto);
        var nested = P("每人种树数量相同，求人数。"); nested.AddFirst(new XElement(w + "pPr", new XElement(w + "numPr", new XElement(w + "ilvl", new XAttribute(w + "val", 1)), new XElement(w + "numId", new XAttribute(w + "val", 1))))); body.Add(nested);
        body.Add(P("2. 综合应用：请阅读以下材料并完成所有问题。")); for (var i = 0; i < 65; i++) body.Add(P("步骤说明：分析条件并列出等式，保留所有计算步骤，验证最终结果是否符合题意。"));
        body.Add(new XElement(w + "tbl", new XElement(w + "tr", new XElement(w + "tc", P("项目")), new XElement(w + "tc", P("数量"))), new XElement(w + "tr", new XElement(w + "tc", P("甲")), new XElement(w + "tc", P("12")))));
        body.Add(new XElement(w + "sectPr", new XElement(w + "pgSz", new XAttribute(w + "w", 11906), new XAttribute(w + "h", 16838)))); using var s = main.GetStream(); new XElement(w + "document", body).Save(s);
    }
}
