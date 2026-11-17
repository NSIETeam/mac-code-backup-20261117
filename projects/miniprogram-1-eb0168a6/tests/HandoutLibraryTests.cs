using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class HandoutLibraryTests
{
    sealed record Fixture(Staff Old, Staff Current, SchoolClass Class, SchoolClass ArchivedClass, Course Course,
        BookImport Book, BookImport Repeated, Handout Second, Handout Tenth, Handout OldVersion, Handout NewVersion, Handout Shared, Handout Archived);

    static async Task<Fixture> Seed(Data db)
    {
        var old = new Staff { Name = "曾任教师", Archived = true, Enabled = false };
        var current = new Staff { Name = "当前教师" }; var campus = new Campus { Name = "校区" };
        var cl = new SchoolClass { Name = "初一数学", CampusId = campus.Id, OwnerStaffId = current.Id };
        var archived = new SchoolClass { Name = "历史班级", CampusId = campus.Id, Archived = true };
        var course = new Course { Name = "数学" }; var archivedCourse = new Course { Name = "历史课程", Archived = true };
        var l2 = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 2, Title = "第二讲" };
        var l10 = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 10, Title = "第十讲" };
        var solo = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 11, Title = "独立讲义" };
        var shared = new Lecture { ClassId = null, CourseId = course.Id, Sequence = 12, Title = "共享" };
        var archivedLecture = new Lecture { ClassId = archived.Id, CourseId = archivedCourse.Id, Sequence = 13, Title = "历史" };
        var h2 = new Handout { LectureId = l2.Id, State = "draft", FileName = "第2讲.pdf", Version = 1 };
        var h10 = new Handout { LectureId = l10.Id, State = "published", FileName = "第10讲.pdf", Version = 1 };
        var v1 = new Handout { LectureId = solo.Id, State = "published", FileName = "独立旧版.pdf", Version = 1 };
        var v2 = new Handout { LectureId = solo.Id, State = "failed", FileName = "独立新版.pdf", Version = 2 };
        var hs = new Handout { LectureId = shared.Id, FileName = "<img src=x onerror=alert(1)>_100%.pdf", Version = 1 };
        var ha = new Handout { LectureId = archivedLecture.Id, State = "draft", FileName = "历史.pdf", OriginalKey = "library/history.pdf", Version = 1 };
        var book = new BookImport { StaffId = old.Id, ClassId = cl.Id, CourseId = course.Id, State = "split", FileName = "合订原稿.pdf", OriginalKey = "library/book.pdf" };
        book.SectionsJson = Json.Write(new[] { new BookSection(0,1,10,"第十讲", HandoutId:h10.Id), new BookSection(1,2,2,"第二讲", HandoutId:h2.Id), new BookSection(2,3,2,"重复引用", HandoutId:h2.Id) });
        var repeated = new BookImport { StaffId = current.Id, ClassId = cl.Id, CourseId = course.Id, State = "split", FileName = "重复原稿.pdf", SectionsJson = Json.Write(new[]{new BookSection(0,1,2,"第二讲", HandoutId:h2.Id)}) };
        db.AddRange(old,current,campus,cl,archived,course,archivedCourse,l2,l10,solo,shared,archivedLecture,h2,h10,v1,v2,hs,ha,book,repeated,
            new Assignment { StaffId=current.Id, ClassId=cl.Id, CourseId=course.Id },
            new Audit { Action="handout.upload", TargetId=v1.Id, StaffId=old.Id, CreatedAt=Clock.Now.AddDays(-2) },
            new Audit { Action="handout.upload", TargetId=v1.Id, StaffId=current.Id, CreatedAt=Clock.Now.AddDays(-1) },
            new Audit { Action="handout.upload", TargetId=hs.Id, StaffId=current.Id },
            new Question { HandoutId=h2.Id, Order=1, Confirmed=true, BlocksJson="NEVER_RETURN_QUESTION_BODY" },
            new Question { HandoutId=h2.Id, Order=2, Confirmed=false },
            new Question { HandoutId=h10.Id, Order=1, Confirmed=true });
        await db.SaveChangesAsync(); return new(old,current,cl,archived,course,book,repeated,h2,h10,v1,v2,hs,ha);
    }

    [Fact]
    public async Task ParentsDeduplicateChildrenKeepVersionsSharedAndArchivedWithoutGuessingUploader()
    {
        await using var f = new Factory(); using var admin = await f.Login("library-admin", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var x = await Seed(db);
        var response = await admin.GetAsync("/api/handout-library"); var raw = await response.Content.ReadAsStringAsync();
        Assert.Equal(HttpStatusCode.OK,response.StatusCode); Assert.DoesNotContain("NEVER_RETURN_QUESTION_BODY",raw);
        Assert.DoesNotContain("library/book.pdf",raw); Assert.DoesNotContain("originalKey",raw);
        var page = (await response.Content.ReadFromJsonAsync<HandoutLibrary.Page>())!;
        Assert.Equal(6,page.Pagination.Total); Assert.Equal(2,page.Stats.Books); Assert.Equal(4,page.Stats.StandaloneHandouts);
        Assert.Equal(5,page.Stats.Lectures); Assert.Equal(2,page.Stats.Teachers); Assert.Equal(2,page.Stats.Classes);
        Assert.Equal(3,page.Stats.Review); Assert.Equal(1,page.Stats.Failed); Assert.Equal(1,page.Stats.Published); Assert.Equal(1,page.Stats.Processing); Assert.Equal(2,page.Stats.Unattributed);
        Assert.DoesNotContain(page.Items,i=>i.Id==x.Second.Id||i.Id==x.Tenth.Id);
        var book = Assert.Single(page.Items,i=>i.Id==x.Book.Id); Assert.Equal(2,book.HandoutCount); Assert.Equal(2,book.LectureCount); Assert.Equal(3,book.QuestionCount); Assert.Equal(2,book.ConfirmedCount);
        var old = Assert.Single(page.Items,i=>i.Id==x.OldVersion.Id); Assert.Equal(x.Old.Id,old.StaffId); Assert.True(old.StaffArchived);
        var latest = Assert.Single(page.Items,i=>i.Id==x.NewVersion.Id); Assert.Null(latest.StaffId); Assert.False(latest.SourceRecorded); Assert.Equal("来源未记录",latest.StaffName);
        var shared = Assert.Single(page.Items,i=>i.Id==x.Shared.Id); Assert.True(shared.Shared); Assert.Null(shared.ClassId); Assert.DoesNotContain("classId",shared.OpenUrl); Assert.Equal(x.Shared.FileName,shared.FileName);
        var archived = Assert.Single(page.Items,i=>i.Id==x.Archived.Id); Assert.True(archived.ClassArchived); Assert.True(archived.CourseArchived); Assert.False(archived.CanOpen); Assert.Null(archived.OpenUrl);
        Assert.Contains(page.Filters.Teachers,i=>i.Id==x.Old.Id&&i.Archived&&!i.Enabled); Assert.Contains(page.Filters.Classes,i=>i.Id==x.ArchivedClass.Id&&i.Archived);
        Assert.Equal(0,await db.Grants.CountAsync()); Assert.Equal(0,await db.Work.CountAsync());
    }

    [Fact]
    public async Task DetailSortsNumericLecturesAndOriginalGrantsKeepScopeAndCurrentAdminAuthorization()
    {
        await using var f = new Factory(); using var admin = await f.Login("library-detail", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var x = await Seed(db);
        var objects = scope.ServiceProvider.GetRequiredService<IObjects>(); await objects.Put(x.Book.OriginalKey,new MemoryStream(Encoding.UTF8.GetBytes("original")),"application/pdf");
        var detail = (await admin.GetFromJsonAsync<HandoutLibrary.Detail>($"/api/handout-library/book/{x.Book.Id}"))!;
        Assert.Equal(new[]{2,10},detail.Children.Select(c=>c.Sequence)); Assert.All(detail.Children,c=>Assert.Contains("&bookId="+x.Book.Id,c.OpenUrl));
        Assert.Equal(detail.OriginalUrl,detail.SourcePdfUrl); Assert.NotNull(detail.OriginalUrl);
        var grant = await db.Grants.SingleAsync(); Assert.Equal(x.Class.Id,grant.ClassId); Assert.Equal(x.Course.Id,grant.CourseId); Assert.NotEqual(x.Old.Id,grant.StaffId);
        var download = await admin.GetAsync(new Uri(detail.OriginalUrl!).PathAndQuery); Assert.Equal(HttpStatusCode.OK,download.StatusCode); Assert.Equal("original",await download.Content.ReadAsStringAsync());
        var archived = (await admin.GetFromJsonAsync<HandoutLibrary.Detail>($"/api/handout-library/handout/{x.Archived.Id}"))!;
        Assert.NotNull(archived.OriginalUrl); Assert.False(archived.Item.CanOpen); Assert.False(Assert.Single(archived.Children).CanOpen);
        Assert.Equal(HttpStatusCode.NotFound,(await admin.GetAsync($"/api/handout-library/handout/{x.Second.Id}")).StatusCode);
        var actor = await db.Staff.SingleAsync(s=>s.Id==grant.StaffId); actor.Role="teacher"; await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.Forbidden,(await admin.GetAsync(new Uri(detail.OriginalUrl!).PathAndQuery)).StatusCode);
    }

    [Fact]
    public async Task PaginationFiltersAndSqlEscapingAreServerSideAndFilterOptionsRemainGlobal()
    {
        await using var f = new Factory(); using var admin = await f.Login("library-filter", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var x = await Seed(db);
        var first = (await admin.GetFromJsonAsync<HandoutLibrary.Page>("/api/handout-library?pageSize=2&page=1"))!;
        var second = (await admin.GetFromJsonAsync<HandoutLibrary.Page>("/api/handout-library?pageSize=2&page=2"))!;
        Assert.Equal(2,first.Items.Length); Assert.Equal(3,first.Pagination.TotalPages); Assert.Empty(first.Items.Select(i=>i.Id).Intersect(second.Items.Select(i=>i.Id)));
        var filtered = (await admin.GetFromJsonAsync<HandoutLibrary.Page>($"/api/handout-library?staffId={x.Old.Id}&classId={x.Class.Id}&courseId={x.Course.Id}&status=published"))!;
        Assert.Equal(x.OldVersion.Id,Assert.Single(filtered.Items).Id); Assert.Equal(1,filtered.Stats.Uploads); Assert.Equal(1,filtered.Stats.Lectures); Assert.Equal(2,filtered.Filters.Teachers.Length);
        var literal = (await admin.GetFromJsonAsync<HandoutLibrary.Page>("/api/handout-library?q="+Uri.EscapeDataString("_100%")))!;
        Assert.Equal(x.Shared.Id,Assert.Single(literal.Items).Id);
        var injection = (await admin.GetFromJsonAsync<HandoutLibrary.Page>("/api/handout-library?q="+Uri.EscapeDataString("' OR 1=1 --")))!; Assert.Empty(injection.Items);
        foreach(var query in new[]{"pageSize=31","page=0","status=made-up","q="+new string('a',121)}) Assert.Equal(HttpStatusCode.BadRequest,(await admin.GetAsync("/api/handout-library?"+query)).StatusCode);
    }

    [Fact]
    public async Task MalformedLegacyRelationsDoNotFailAndTextContainingIdsIsNotATypedRelationship()
    {
        await using var f = new Factory(); using var admin = await f.Login("library-json", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var x = await Seed(db);
        foreach(var json in new[]{"not-json", "{}", "[1,null,\"x\"]", Json.Write(new[]{new {title=x.NewVersion.Id.ToString()}})})
            db.BookImports.Add(new BookImport { ClassId=x.Class.Id, CourseId=x.Course.Id, StaffId=x.Current.Id, FileName="旧记录", SectionsJson=json });
        await db.SaveChangesAsync();
        var page = (await admin.GetFromJsonAsync<HandoutLibrary.Page>("/api/handout-library"))!;
        Assert.Equal(10,page.Pagination.Total); Assert.Contains(page.Items,i=>i.Id==x.NewVersion.Id); Assert.Equal(5,page.Stats.Lectures);
    }

    [Fact]
    public async Task OrdinaryTeachersCannotListExportOrInspectEvenTheirOwnUploads()
    {
        await using var f = new Factory(); using var admin = await f.Login("library-owner-admin", true);
        using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>(); var x = await Seed(db);
        using var teacher = await f.Login("library-teacher"); var invite = await Factory.Post(admin,$"/api/admin/staff/{x.Current.Id}/invite",new{});
        await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});
        foreach(var path in new[]{"/api/handout-library","/api/handout-library/export.csv",$"/api/handout-library/book/{x.Repeated.Id}",$"/api/handout-library/handout/{x.Shared.Id}"}) {
            var response=await teacher.GetAsync(path); Assert.Equal(HttpStatusCode.Forbidden,response.StatusCode); Assert.DoesNotContain(x.Repeated.FileName,await response.Content.ReadAsStringAsync());
        }
    }

    [Fact]
    public async Task CsvExportProtectsFormulaCellsAndDoesNotCreateJobsOrFileGrants()
    {
        await using var f = new Factory(); using var admin = await f.Login("library-csv", true);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var x=await Seed(db);
        x.Book.FileName="=HYPERLINK(\"https://example.invalid\")";await db.SaveChangesAsync();
        var response=await admin.GetAsync("/api/handout-library/export.csv?staffId="+x.Old.Id);
        Assert.Equal(HttpStatusCode.OK,response.StatusCode);Assert.Equal("text/csv",response.Content.Headers.ContentType!.MediaType);
        var bytes=await response.Content.ReadAsByteArrayAsync();Assert.Equal(new byte[]{239,187,191},bytes.Take(3));
        var csv=Encoding.UTF8.GetString(bytes);Assert.Contains("\"'=HYPERLINK(\"\"https://example.invalid\"\")\"",csv);
        Assert.DoesNotContain("独立新版",csv);Assert.Equal(0,await db.Work.CountAsync());Assert.Equal(0,await db.Grants.CountAsync());
    }

    [Fact]
    public async Task ListAndDetailsReadActualReuseMetadataWithoutExposingPrivateCacheFields()
    {
        await using var f=new Factory();using var admin=await f.Login("library-reuse",true);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();var x=await Seed(db);
        var objects=scope.ServiceProvider.GetRequiredService<IObjects>();
        var summary=new HandoutReuseSummary("reused","book",2,3,"named");
        await objects.Put($"system/handout-reuse/v1/reuse/book/{x.Book.Id}.json",new MemoryStream(Encoding.UTF8.GetBytes(Json.Write(summary))),"application/json");
        var page=(await admin.GetFromJsonAsync<HandoutLibrary.Page>("/api/handout-library"))!;
        Assert.Equal(summary,Assert.Single(page.Items,i=>i.Id==x.Book.Id).Reuse);Assert.Null(Assert.Single(page.Items,i=>i.Id==x.Repeated.Id).Reuse);
        var detail=(await admin.GetFromJsonAsync<HandoutLibrary.Detail>($"/api/handout-library/book/{x.Book.Id}"))!;Assert.Equal(summary,detail.Item.Reuse);
        Assert.Null(detail.Item.ContentFingerprint);
    }

    [Theory]
    [InlineData("=1+1")][InlineData("+SUM(A1:A2)")][InlineData("-1+1")][InlineData("@SUM(1)")]
    [InlineData("  =1")][InlineData("\ttext")][InlineData("\rtext")][InlineData("\ntext")][InlineData("\uFEFF =1")]
    public void CsvCellPreventsExecutablePrefixes(string value) => Assert.StartsWith("\"'",HandoutLibrary.CsvCell(value));
}
