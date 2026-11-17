using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using DocumentFormat.OpenXml.Spreadsheet;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using UglyToad.PdfPig;
using Xunit;

public class TeacherWorkspaceTests
{
    sealed record Setup(Guid ClassA, Guid ClassB, Guid Course, Guid TeacherA, Guid TeacherB, Guid Campus, HttpClient A, HttpClient B);
    static async Task<Setup> Prepare(Factory f, HttpClient admin) {
        var campus=(await Factory.Post(admin,"/api/admin/catalog/campuses",new {name="隔离验收校区"})).GetProperty("id").GetGuid();
        var course=(await Factory.Post(admin,"/api/admin/catalog/courses",new {name="数学"})).GetProperty("id").GetGuid();
        var clients=new List<HttpClient>();var classes=new List<Guid>();var teachers=new List<Guid>();
        for(var i=0;i<2;i++) {
            var cl=(await Factory.Post(admin,"/api/admin/catalog/classes",new {name="隔离班"+i,campusId=campus})).GetProperty("id").GetGuid();classes.Add(cl);
            var teacher=(await Factory.Post(admin,"/api/admin/catalog/staff",new {name="教师"+i})).GetProperty("id").GetGuid();teachers.Add(teacher);
            await Factory.Post(admin,"/api/admin/catalog/assignments",new {classId=cl,courseId=course,staffId=teacher});
            var invite=await Factory.Post(admin,$"/api/admin/staff/{teacher}/invite",new {});var client=await f.Login("workspace-teacher-"+i);clients.Add(client);
            await Factory.Post(client,"/api/auth/bind",new {code=invite.GetProperty("code").GetString()});
        }
        return new(classes[0],classes[1],course,teachers[0],teachers[1],campus,clients[0],clients[1]);
    }
    static MultipartFormDataContent Upload(byte[] bytes,string name,Dictionary<string,string>? fields=null) {
        var form=new MultipartFormDataContent();form.Add(new ByteArrayContent(bytes),"file",name);if(fields!=null)foreach(var pair in fields)form.Add(new StringContent(pair.Value),pair.Key);return form;
    }
    static byte[] Word() {
        using var stream = new MemoryStream();
        using (var doc = WordprocessingDocument.Create(stream, WordprocessingDocumentType.Document, true)) {
            var main = doc.AddMainDocumentPart();
            main.Document = new Document(new Body(
                new Paragraph(new DocumentFormat.OpenXml.Wordprocessing.Run(new DocumentFormat.OpenXml.Wordprocessing.Text("例1. 计算 2+3。"))),
                new SectionProperties(new PageSize { Width = 11906, Height = 16838 })));
            main.Document.Save();
        }
        return stream.ToArray();
    }
    static byte[] Roster() {
        using var stream=new MemoryStream();using(var doc=SpreadsheetDocument.Create(stream,SpreadsheetDocumentType.Workbook,true)){
            var wb=doc.AddWorkbookPart();wb.Workbook=new Workbook();var sheet=wb.AddNewPart<WorksheetPart>();var data=new SheetData();
            Row Row(uint n,string a,string b)=>new Row(new Cell{CellReference="A"+n,DataType=CellValues.InlineString,InlineString=new InlineString(new DocumentFormat.OpenXml.Spreadsheet.Text(a))},new Cell{CellReference="B"+n,DataType=CellValues.InlineString,InlineString=new InlineString(new DocumentFormat.OpenXml.Spreadsheet.Text(b))}){RowIndex=n};
            data.Append(Row(1,"姓名","学号"),Row(2,"导入学生","X001"));sheet.Worksheet=new Worksheet(data);wb.Workbook.Append(new Sheets(new Sheet{Id=wb.GetIdOfPart(sheet),SheetId=1,Name="名单"}));wb.Workbook.Save();
        }return stream.ToArray();
    }
    public static byte[] Pdf() {
        var objects=new[]{"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>","<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        var s=new StringBuilder("%PDF-1.4\n");var offsets=new List<int>{0};foreach(var obj in objects){offsets.Add(s.Length);s.Append($"{offsets.Count-1} 0 obj\n{obj}\nendobj\n");}
        const string content="BT /F1 14 Tf 72 760 Td (Private print document) Tj ET";offsets.Add(s.Length);s.Append($"5 0 obj\n<< /Length {content.Length} >>\nstream\n{content}\nendstream\nendobj\n");var x=s.Length;s.Append("xref\n0 6\n0000000000 65535 f \n");foreach(var offset in offsets.Skip(1))s.Append(offset.ToString("D10")+" 00000 n \n");s.Append($"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{x}\n%%EOF");return Encoding.ASCII.GetBytes(s.ToString());
    }
    [Fact]
    public async Task UploadPersistsAutoDefaultAndExplicitLegacyMode() {
        await using var f = new Factory(); using var admin = await f.Login("recognition-upload", true);
        var scope = await Prepare(f, admin); using var a = scope.A; using var b = scope.B;
        foreach (var mode in new[] { "", "legacy" }) {
            using var form = Upload(Pdf(), "讲义.pdf", mode == "" ? null : new() { ["recognitionMode"] = mode });
            var response = await a.PostAsync($"/api/classes/{scope.ClassA}/books?courseId={scope.Course}", form);
            Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
            var id = (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
            using var services = f.Services.CreateScope(); var db = services.ServiceProvider.GetRequiredService<Data>();
            Assert.Equal(mode == "" ? "auto" : mode, (await db.BookImports.FindAsync(id))!.RecognitionMode);
        }
        using var invalid = Upload(Pdf(), "讲义.pdf", new() { ["recognitionMode"] = "invalid" });
        Assert.Equal(HttpStatusCode.BadRequest, (await a.PostAsync($"/api/classes/{scope.ClassA}/books?courseId={scope.Course}", invalid)).StatusCode);
    }
    [Fact]
    public async Task ClassEditChecksScopeAndConcurrentChanges() {
        await using var f = new Factory(); using var admin = await f.Login("edit-class-admin", true);
        var scope = await Prepare(f, admin); using var a = scope.A; using var b = scope.B;
        var input = new { name = "新班名", campusId = scope.Campus, expectedName = "隔离班0", expectedCampusId = scope.Campus };
        Assert.Equal(HttpStatusCode.Forbidden, (await b.PutAsJsonAsync($"/api/classes/{scope.ClassA}", input)).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await a.PutAsJsonAsync($"/api/classes/{scope.ClassA}", input)).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await a.PutAsJsonAsync($"/api/classes/{scope.ClassA}", input)).StatusCode);
        var detail = await a.GetFromJsonAsync<JsonElement>($"/api/classes/{scope.ClassA}");
        Assert.Equal("新班名", detail.GetProperty("schoolClass").GetProperty("name").GetString());
    }
    [Fact]
    public async Task BatchReviewIsAtomicScopedAndAllowsRemovingLastDraftQuestion() {
        await using var f = new Factory(); using var admin = await f.Login("review-admin", true);
        var scope = await Prepare(f, admin); using var a = scope.A; using var b = scope.B;
        var lecture = await Factory.Post(a, "/api/lectures", new { classId = scope.ClassA, courseId = scope.Course, title = "批量校对", sequence = 1 });
        var h = new Handout { LectureId = lecture.GetProperty("id").GetGuid(), State = "draft" };
        var block = new ContentBlock("<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:r><w:t>例1 内容</w:t></w:r></w:p>", "例1 内容", "paragraph");
        var q1 = new Question { HandoutId = h.Id, Order = 1, Label = "例1", BlocksJson = Json.Write(new[] { block }), PreviewsJson = Json.Write(new[] { "preview.png" }), IssuesJson = Json.Write(new[] { new Issue("pdf-formula-review", "请核对", true) }) };
        var q2 = new Question { HandoutId = h.Id, Order = 2, Label = "练1", BlocksJson = q1.BlocksJson, PreviewsJson = q1.PreviewsJson, IssuesJson = Json.Write(new[] { new Issue("pdf-merged-region", "需要拆题", true) }) };
        using (var services = f.Services.CreateScope()) { var db = services.ServiceProvider.GetRequiredService<Data>(); db.Handouts.Add(h); db.Questions.AddRange(q1, q2); await db.SaveChangesAsync(); }
        var url = $"/api/handouts/{h.Id}/verify-selected";
        Assert.Equal(HttpStatusCode.Forbidden, (await b.PostAsJsonAsync(url, new { revision = 0, questionIds = new[] { q1.Id } })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await a.PostAsJsonAsync(url, new { revision = 0, questionIds = new[] { q1.Id, q2.Id } })).StatusCode);
        using (var services = f.Services.CreateScope()) { var db = services.ServiceProvider.GetRequiredService<Data>(); Assert.False((await db.Questions.FindAsync(q1.Id))!.Confirmed); Assert.Equal(0, (await db.Handouts.FindAsync(h.Id))!.Revision); }
        await Factory.Post(a, url, new { revision = 0, questionIds = new[] { q1.Id } });
        Assert.Equal(HttpStatusCode.Conflict, (await a.PostAsJsonAsync(url, new { revision = 0, questionIds = new[] { q1.Id } })).StatusCode);
        await Factory.Post(a, $"/api/handouts/{h.Id}/edit", new { revision = 1, questionId = q2.Id, action = "remove-question" });
        await Factory.Post(a, $"/api/handouts/{h.Id}/edit", new { revision = 2, questionId = q1.Id, action = "remove-question" });
        using (var services = f.Services.CreateScope()) { var db = services.ServiceProvider.GetRequiredService<Data>(); Assert.Empty(await db.Questions.Where(q => q.HandoutId == h.Id).ToListAsync()); Assert.Equal(3, (await db.Handouts.FindAsync(h.Id))!.Revision); }
    }
    [Fact]
    public async Task QuickClassCreationIsScopedAssignedAuditedAndRetryable() {
        await using var f = new Factory(); using var admin = await f.Login("quick-admin",true); var scope = await Prepare(f,admin); using var a=scope.A; using var b=scope.B;
        var input = new { name="新班级",campusId=scope.Campus,courseId=scope.Course,requestId="quick-class" };
        var created=await Factory.Post(a,"/api/classes",input); var id=created.GetProperty("id").GetGuid();
        Assert.Equal(id,(await Factory.Post(a,"/api/classes",input)).GetProperty("id").GetGuid());
        Assert.Equal(HttpStatusCode.Forbidden,(await b.GetAsync($"/api/classes/{id}")).StatusCode);
        var workspace=await a.GetFromJsonAsync<JsonElement>("/api/workspace"); Assert.Contains(workspace.GetProperty("assignments").EnumerateArray(),x=>x.GetProperty("classId").GetGuid()==id && x.GetProperty("staffId").GetGuid()==scope.TeacherA);
        var other=(await Factory.Post(admin,"/api/admin/catalog/campuses",new{name="其他校区"})).GetProperty("id").GetGuid();
        var cross=await Factory.Post(a,"/api/classes",new{name="跨校区自建班",campusId=other,courseId=scope.Course,requestId="cross-campus"});
        Assert.Equal(HttpStatusCode.Forbidden,(await b.GetAsync("/api/classes/"+cross.GetProperty("id").GetGuid()+"/students")).StatusCode);
        using var services=f.Services.CreateScope();var db=services.ServiceProvider.GetRequiredService<Data>();Assert.Single(await db.Audits.Where(x=>x.TargetId==id && x.Action=="classes.create").ToListAsync());
    }
    [Fact]
    public async Task NameOnlyStudentsKeepDistinctIdentityAndBatchRetryDoesNotDuplicate() {
        await using var f=new Factory();using var admin=await f.Login("names-admin",true);var scope=await Prepare(f,admin);using var a=scope.A;using var b=scope.B;
        var first=await Factory.Post(a,$"/api/classes/{scope.ClassA}/students",new{name="同名学生"});var second=await Factory.Post(a,$"/api/classes/{scope.ClassA}/students",new{name="同名学生"});
        Assert.NotEqual(first.GetProperty("id").GetGuid(),second.GetProperty("id").GetGuid());Assert.NotEqual(first.GetProperty("code").GetString(),second.GetProperty("code").GetString());
        var updated=await a.PutAsJsonAsync($"/api/classes/{scope.ClassA}/students/{first.GetProperty("id").GetGuid()}",new{name="修改姓名"});updated.EnsureSuccessStatusCode();Assert.Equal(first.GetProperty("code").GetString(),(await updated.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
        var input=new{names=new[]{"甲","乙"},requestId="names-batch"};var rows=await Factory.Post(a,$"/api/classes/{scope.ClassA}/students/batch",input);var retry=await Factory.Post(a,$"/api/classes/{scope.ClassA}/students/batch",input);Assert.Equal(rows[0].GetProperty("id").GetGuid(),retry[0].GetProperty("id").GetGuid());
        Assert.Equal(HttpStatusCode.Forbidden,(await b.PostAsJsonAsync($"/api/classes/{scope.ClassA}/students/batch",input)).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await a.PostAsJsonAsync($"/api/classes/{scope.ClassA}/students/batch",new{names=new[]{"丙"," "},requestId="invalid"})).StatusCode);
        Assert.Equal(4,(await a.GetFromJsonAsync<JsonElement>($"/api/classes/{scope.ClassA}/students")).GetArrayLength());
        var lectureInput=new{classId=scope.ClassA,courseId=scope.Course,title="第1讲",sequence=1,requestId="lecture-retry"};var lecture=await Factory.Post(a,"/api/lectures",lectureInput);Assert.Equal(lecture.GetProperty("id").GetGuid(),(await Factory.Post(a,"/api/lectures",lectureInput)).GetProperty("id").GetGuid());
    }
    [Fact]
    public async Task NameOnlySpreadsheetImportsWithoutCodesAndRejectsDuplicateReimport() {
        await using var f=new Factory();using var admin=await f.Login("names-excel-admin",true);var scope=await Prepare(f,admin);using var a=scope.A;using var b=scope.B;
        using var stream=new MemoryStream(Roster());using(var sheet=SpreadsheetDocument.Open(stream,true)){var part=sheet.WorkbookPart!.WorksheetParts.First();foreach(var cell in part.Worksheet.Descendants<Cell>().Where(c=>c.CellReference!.Value!.StartsWith("B")).ToArray())cell.Remove();part.Worksheet.Save();}
        var bytes=stream.ToArray();using(var preview=Upload(bytes,"名单.xlsx")){var response=await a.PostAsync($"/api/classes/{scope.ClassA}/students/import",preview);response.EnsureSuccessStatusCode();Assert.Single((await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("rows").EnumerateArray());}
        using(var commit=Upload(bytes,"名单.xlsx",new(){{"commit","true"}}))(await a.PostAsync($"/api/classes/{scope.ClassA}/students/import",commit)).EnsureSuccessStatusCode();
        using(var duplicate=Upload(bytes,"名单.xlsx",new(){{"commit","true"}}))Assert.Equal(HttpStatusCode.BadRequest,(await a.PostAsync($"/api/classes/{scope.ClassA}/students/import",duplicate)).StatusCode);
        var rows=await a.GetFromJsonAsync<JsonElement>($"/api/classes/{scope.ClassA}/students");Assert.Single(rows.EnumerateArray());Assert.False(string.IsNullOrEmpty(rows[0].GetProperty("code").GetString()));
    }
    [Fact]
    public async Task ArchivedTestCampusesHideTheirDisabledControlComputersButKeepRealDevicesVisible() {
        await using var f=new Factory();using var admin=await f.Login("archive-admin",true);
        var campus=new Campus{Name="银座晶都12B"};var archived=new Campus{Name="测试校区",Archived=true};
        var real=new Device{Name="真实控制电脑",CampusId=campus.Id,Enabled=false,TokenHash=Secrets.Hash("real")};
        var diagnostic=new Device{Name="演练电脑",CampusId=archived.Id,Enabled=false,TokenHash=Secrets.Hash("diagnostic")};
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();db.AddRange(campus,archived,real,diagnostic);await db.SaveChangesAsync();}
        var catalog=await admin.GetFromJsonAsync<JsonElement>("/api/admin/catalog");Assert.Single(catalog.GetProperty("campuses").EnumerateArray());Assert.Equal(real.Id,catalog.GetProperty("devices")[0].GetProperty("id").GetGuid());Assert.Single(catalog.GetProperty("devices").EnumerateArray());
    }
    [Fact]
    public async Task AbsenceIsLectureScopedEditableAndDoesNotMarkWrongQuestionsCompleted() {
        await using var f=new Factory();using var admin=await f.Login("attendance-admin",true);var s=await Prepare(f,admin);using var a=s.A;using var b=s.B;
        var student=(await Factory.Post(a,$"/api/classes/{s.ClassA}/students",new {name="请假学生",code="A01"})).GetProperty("id").GetGuid();
        var lecture=(await Factory.Post(a,"/api/lectures",new {classId=s.ClassA,courseId=s.Course,title="第1讲",sequence=1})).GetProperty("id").GetGuid();
        var request=new {studentId=student,classId=s.ClassA,absent=true};
        Assert.Equal(HttpStatusCode.Forbidden,(await b.PutAsJsonAsync($"/api/lectures/{lecture}/attendance",request)).StatusCode);
        Assert.True((await a.PutAsJsonAsync($"/api/lectures/{lecture}/attendance",request)).IsSuccessStatusCode);
        Assert.True((await a.PutAsJsonAsync($"/api/lectures/{lecture}/attendance",request)).IsSuccessStatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.True((await db.Attendances.SingleAsync()).Absent);Assert.Empty(await db.WrongSettings.ToListAsync());Assert.Empty(await db.WrongQuestions.ToListAsync());}
        Assert.True((await a.PutAsJsonAsync($"/api/lectures/{lecture}/attendance",new{studentId=student,classId=s.ClassA,absent=false})).IsSuccessStatusCode);
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.False((await db.Attendances.SingleAsync()).Absent);}
    }
    [Fact]
    public async Task ClassLectureListCombinesCoursesWithoutLeakingAnotherTeachersCourse() {
        await using var f=new Factory();using var admin=await f.Login("all-lectures-admin",true);var s=await Prepare(f,admin);using var a=s.A;using var b=s.B;
        var math=(await Factory.Post(a,"/api/lectures",new {classId=s.ClassA,courseId=s.Course,title="数学讲次",sequence=1})).GetProperty("id").GetGuid();
        var english=(await Factory.Post(admin,"/api/admin/catalog/courses",new {name="英语"})).GetProperty("id").GetGuid();
        await Factory.Post(admin,"/api/admin/catalog/assignments",new{classId=s.ClassA,courseId=english,staffId=s.TeacherB});
        var other=(await Factory.Post(admin,"/api/lectures",new {classId=s.ClassA,courseId=english,title="英语讲次",sequence=1})).GetProperty("id").GetGuid();
        var own=await a.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassA}");Assert.Single(own.GetProperty("lectures").EnumerateArray());Assert.Equal(math,own.GetProperty("lectures")[0].GetProperty("id").GetGuid());
        var all=await admin.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassA}");Assert.Equal(2,all.GetProperty("lectures").GetArrayLength());
        var theirs=await b.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassA}");Assert.Single(theirs.GetProperty("lectures").EnumerateArray());Assert.Equal(other,theirs.GetProperty("lectures")[0].GetProperty("id").GetGuid());
    }
    [Fact]
    public async Task TeacherRostersLecturesAndZeroQuestionRegistrationAreScopedAndAudited() {
        await using var f=new Factory();using var admin=await f.Login("workspace-admin",true);var s=await Prepare(f,admin);using var a=s.A;using var b=s.B;
        Assert.Equal(HttpStatusCode.Forbidden,(await a.GetAsync("/api/admin/catalog")).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await a.GetAsync("/api/admin/activity")).StatusCode);
        var workspace=await a.GetFromJsonAsync<JsonElement>("/api/workspace");Assert.Single(workspace.GetProperty("classes").EnumerateArray());Assert.All(workspace.GetProperty("assignments").EnumerateArray(),x=>Assert.Equal(s.TeacherA,x.GetProperty("staffId").GetGuid()));
        Assert.Equal(HttpStatusCode.Forbidden,(await a.PostAsJsonAsync("/api/lectures",new {classId=s.ClassB,courseId=s.Course,title="越权",sequence=1})).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,(await a.PostAsJsonAsync("/api/lectures",new {classId=(Guid?)null,courseId=s.Course,title="越权共享",sequence=1})).StatusCode);
        var student=(await Factory.Post(a,$"/api/classes/{s.ClassA}/students",new {name="学生甲",code="A01"})).GetProperty("id").GetGuid();
        Assert.Equal(HttpStatusCode.Forbidden,(await b.PutAsJsonAsync($"/api/classes/{s.ClassB}/students/{student}",new {name="越权",code="01"})).StatusCode);
        using(var import=Upload(Roster(),"名单.xlsx",new(){{"commit","true"}}))Assert.True((await a.PostAsync($"/api/classes/{s.ClassA}/students/import",import)).IsSuccessStatusCode);
        using(var import=Upload(Roster(),"名单.xlsx"))Assert.Equal(HttpStatusCode.Forbidden,(await b.PostAsync($"/api/classes/{s.ClassA}/students/import",import)).StatusCode);
        var lecture=(await Factory.Post(a,"/api/lectures",new {classId=s.ClassA,courseId=s.Course,title="第1讲",sequence=1})).GetProperty("id").GetGuid();
        Guid hid;using(var upload=Upload(Word(),"讲义.docx")){var res=await a.PostAsync($"/api/lectures/{lecture}/handouts",upload);Assert.Equal(HttpStatusCode.Accepted,res.StatusCode);hid=(await res.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();}
        await f.Run("import",hid);var detail=await a.GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}?classId={s.ClassA}");
        var question=detail.GetProperty("questions")[0].GetProperty("id").GetGuid();await Factory.Post(a,$"/api/handouts/{hid}/edit",new {action="confirm",revision=0,questionId=question});await Factory.Post(a,$"/api/handouts/{hid}/publish",new {revision=1});
        Assert.True((await a.PutAsJsonAsync("/api/wrong-questions",new {handoutId=hid,classId=s.ClassA,studentIds=new[]{student},questionIds=Array.Empty<Guid>()})).IsSuccessStatusCode);
        detail=await a.GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}?classId={s.ClassA}");Assert.Empty(detail.GetProperty("wrong").EnumerateArray());Assert.Equal(student,detail.GetProperty("completedStudentIds")[0].GetGuid());
        Assert.True((await a.PutAsJsonAsync("/api/wrong-questions",new {handoutId=hid,classId=s.ClassA,studentIds=new[]{student},questionIds=new[]{question}})).IsSuccessStatusCode);
        var correct = new Dictionary<Guid,string>{{question,"correct"}};
        Assert.Equal(HttpStatusCode.BadRequest,(await a.PutAsJsonAsync("/api/wrong-questions",new {handoutId=hid,classId=s.ClassA,studentIds=new[]{student},questionIds=new[]{question},marks=correct})).StatusCode);
        Assert.True((await a.PutAsJsonAsync("/api/wrong-questions",new {handoutId=hid,classId=s.ClassA,studentIds=new[]{student},questionIds=Array.Empty<Guid>(),marks=correct})).IsSuccessStatusCode);
        detail=await a.GetFromJsonAsync<JsonElement>($"/api/handouts/{hid}?classId={s.ClassA}");Assert.Equal("correct",detail.GetProperty("marks")[0].GetProperty("states").GetProperty(question.ToString()).GetString());Assert.Empty(detail.GetProperty("wrong").EnumerateArray());
        var classroom=await a.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassA}?courseId={s.Course}");Assert.Equal(student,classroom.GetProperty("registrations")[0].GetProperty("studentId").GetGuid());
        Assert.Equal(HttpStatusCode.Forbidden,(await b.GetAsync($"/api/handouts/{hid}?classId={s.ClassA}")).StatusCode);
        var log=await admin.GetFromJsonAsync<JsonElement>("/api/admin/activity?staffId="+s.TeacherA);Assert.Contains(log.GetProperty("items").EnumerateArray(),x=>x.GetProperty("action").GetString()=="wrong-questions.save");Assert.Contains(log.GetProperty("items").EnumerateArray(),x=>x.GetProperty("action").GetString()=="handout.upload");
    }
    [Fact]
    public async Task PrivateFilesKeepTheirOriginalPdfAndCannotBeListedPreviewedOrPrintedByAnotherTeacher() {
        await using var f=new Factory();using var admin=await f.Login("file-admin",true);var s=await Prepare(f,admin);using var a=s.A;using var b=s.B;
        Guid id;var pdf=Pdf();using(var form=Upload(pdf,"微信资料.pdf")){var res=await a.PostAsync("/api/print-files",form);Assert.Equal(HttpStatusCode.Accepted,res.StatusCode);id=(await res.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();}
        await f.Run("file",id);
        var docs=await b.GetFromJsonAsync<JsonElement>("/api/exports");Assert.Empty(docs.EnumerateArray());
        Assert.Equal(HttpStatusCode.Forbidden,(await b.PostAsJsonAsync($"/api/previews/export/{id}",new{})).StatusCode);Assert.Equal(HttpStatusCode.Forbidden,(await b.GetAsync($"/api/exports/{id}/download?format=pdf")).StatusCode);
        var link=await a.GetFromJsonAsync<JsonElement>($"/api/exports/{id}/download?format=pdf");Assert.Equal(pdf,await a.GetByteArrayAsync(link.GetProperty("url").GetString()));
        await Factory.Post(a,$"/api/previews/export/{id}",new{}); for(var i=0;i<3;i++) { using var work=f.Services.CreateScope(); await work.ServiceProvider.GetRequiredService<DocumentWorker>().Tick(CancellationToken.None); }var preview=await a.GetFromJsonAsync<JsonElement>($"/api/previews/export/{id}");Assert.Equal("微信资料.pdf",preview.GetProperty("title").GetString());Assert.Single(preview.GetProperty("pages").EnumerateArray());
        Guid printer;using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();var d=new Device{CampusId=s.Campus,Name="西侧",TokenHash=Secrets.Hash("test-device")};var p=new Printer{CampusId=s.Campus,DeviceId=d.Id,Name="西侧打印机",QueueName="test",Enabled=true};db.AddRange(d,p);await db.SaveChangesAsync();printer=p.Id;}
        Assert.Equal(HttpStatusCode.Forbidden,(await b.PostAsJsonAsync("/api/print-jobs",new{documentIds=new[]{id},printerId=printer,copies=1,duplex=false,requestKey="private-file-denied"})).StatusCode);
        var request=new{documentIds=new[]{id},printerId=printer,copies=1,duplex=false,requestKey="private-file-authorized"};var jobs=await Factory.Post(a,"/api/print-jobs",request);var retry=await Factory.Post(a,"/api/print-jobs",request);Assert.Equal(jobs[0].GetProperty("id").GetGuid(),retry[0].GetProperty("id").GetGuid());
        Assert.Empty((await b.GetFromJsonAsync<JsonElement>("/api/print-jobs")).EnumerateArray());
        var my=(await a.GetFromJsonAsync<JsonElement>("/api/exports"))[0];Assert.False(my.GetProperty("needsReflow").GetBoolean());Assert.False(my.GetProperty("canDownloadWord").GetBoolean());
    }
    [Theory]
    [InlineData("image.png")]
    [InlineData("source.docx")]
    public async Task ImageAndWordFilesBecomePreviewablePdfs(string name) {
        await using var f=new Factory();using var admin=await f.Login("convert-admin",true);
        var bytes=name.EndsWith("png")?Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXRcAAAAASUVORK5CYII="):Word();
        using var form=Upload(bytes,name);var res=await admin.PostAsync("/api/print-files",form);Assert.Equal(HttpStatusCode.Accepted,res.StatusCode);var id=(await res.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();await f.Run("file",id);
        var download=await admin.GetFromJsonAsync<JsonElement>($"/api/exports/{id}/download?format=pdf");using var pdf=PdfDocument.Open(await admin.GetByteArrayAsync(download.GetProperty("url").GetString()));Assert.Equal(1,pdf.NumberOfPages);Assert.InRange(pdf.GetPage(1).Width,590,600);
    }
    [Fact]
    public async Task PhoneFilePickerUsesScopedExpiringTicketsAndRetriesDoNotDuplicateUploads() {
        await using var f=new Factory();using var admin=await f.Login("mobile-admin",true);var s=await Prepare(f,admin);using var a=s.A;using var b=s.B;
        Assert.Equal(HttpStatusCode.Forbidden,(await b.PostAsJsonAsync("/api/mobile-uploads",new { mode="roster",classId=s.ClassA })).StatusCode);
        var ticket=await Factory.Post(a,"/api/mobile-uploads",new {mode="print"});var url=ticket.GetProperty("url").GetString()!;
        Assert.DoesNotContain(a.DefaultRequestHeaders.Authorization!.Parameter!,url);
        using var browser=f.CreateClient();var html=await browser.GetStringAsync(url);Assert.Contains("type=\"file\"",html);Assert.Contains("选择手机文件",html);
        string target;using(var form=Upload(Pdf(),"手机资料.pdf")){var res=await browser.PostAsync(url+"/file",form);Assert.True(res.IsSuccessStatusCode,await res.Content.ReadAsStringAsync());target=(await res.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("url").GetString()!;}
        using(var form=Upload(Pdf(),"重复.pdf")){var res=await browser.PostAsync(url+"/file",form);Assert.Equal(target,(await res.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("url").GetString());}
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Equal(1,await db.Documents.CountAsync(x=>x.StaffId==s.TeacherA));Assert.Equal(1,await db.Work.CountAsync(x=>x.Kind=="file"));}
        var revoked=await Factory.Post(a,"/api/mobile-uploads",new {mode="print"});await Factory.Post(a,"/api/logout",new{});Assert.Equal(HttpStatusCode.Unauthorized,(await browser.GetAsync(revoked.GetProperty("url").GetString())).StatusCode);
    }
    [Fact]
    public async Task PhoneRosterRequiresPreviewConfirmationAndKeepsTheServerSideClassScope() {
        await using var f=new Factory();using var admin=await f.Login("mobile-roster-admin",true);var s=await Prepare(f,admin);using var a=s.A;using var b=s.B;using var browser=f.CreateClient();
        var ticket=await Factory.Post(a,"/api/mobile-uploads",new {mode="roster",classId=s.ClassA});var url=ticket.GetProperty("url").GetString()!;
        using(var form=Upload(Roster(),"手机名单.xlsx",new(){{"classId",s.ClassB.ToString()}})){var res=await browser.PostAsync(url+"/file",form);var data=await res.Content.ReadFromJsonAsync<JsonElement>();Assert.False(data.GetProperty("committed").GetBoolean());}
        Assert.Empty((await a.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassA}/students")).EnumerateArray());
        using(var form=Upload(Roster(),"手机名单.xlsx",new(){{"commit","true"},{"classId",s.ClassB.ToString()}})){var res=await browser.PostAsync(url+"/file",form);Assert.True(res.IsSuccessStatusCode,await res.Content.ReadAsStringAsync());}
        using(var form=Upload(Roster(),"手机名单.xlsx",new(){{"commit","true"}}))Assert.True((await browser.PostAsync(url+"/file",form)).IsSuccessStatusCode);
        Assert.Single((await a.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassA}/students")).EnumerateArray());Assert.Empty((await b.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassB}/students")).EnumerateArray());
    }
    [Fact]
    public async Task HomeworkRequiresAnAssignedClassAndDoesNotModifySharedHandouts() {
        await using var f=new Factory();using var admin=await f.Login("homework-admin",true);var s=await Prepare(f,admin);using var a=s.A;using var b=s.B;
        var l=(await Factory.Post(admin,"/api/lectures",new {classId=(Guid?)null,courseId=s.Course,title="共享讲次",sequence=1})).GetProperty("id").GetGuid();
        var fields=new Dictionary<string,string>{{"classId",s.ClassA.ToString()},{"courseId",s.Course.ToString()},{"lectureId",l.ToString()},{"materialKind","homework"}};
        using(var form=Upload(Pdf(),"课后作业.pdf",fields))Assert.Equal(HttpStatusCode.Forbidden,(await b.PostAsync("/api/print-files",form)).StatusCode);
        Guid id;using(var form=Upload(Pdf(),"课后作业.pdf",fields)){var res=await a.PostAsync("/api/print-files",form);Assert.Equal(HttpStatusCode.Accepted,res.StatusCode);id=(await res.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();}
        var own=await a.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassA}?courseId={s.Course}");Assert.Equal(id,own.GetProperty("materials")[0].GetProperty("id").GetGuid());var other=await b.GetFromJsonAsync<JsonElement>($"/api/classes/{s.ClassB}?courseId={s.Course}");Assert.Empty(other.GetProperty("materials").EnumerateArray());
        using(var form=Upload(Word(),"越权共享.docx"))Assert.Equal(HttpStatusCode.Forbidden,(await a.PostAsync($"/api/lectures/{l}/handouts",form)).StatusCode);
    }
}
