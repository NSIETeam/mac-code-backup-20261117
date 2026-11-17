using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.EntityFrameworkCore;
using Siyue;
using Xunit;

public sealed partial class ResearchTests
{
    [Fact]
    public async Task XkwSettingsAreAdminOnlyEncryptedAndRevisionProtected()
    {
        await using var f = new ResearchFactory(); using var admin = await f.LoginOwner("xkw-admin");
        using (var scope=f.Services.CreateScope()) {var db=scope.ServiceProvider.GetRequiredService<Data>();db.Staff.Add(new Staff {Name="普通教师",Role="teacher",OpenId="development:xkw-teacher"});await db.SaveChangesAsync();}
        using var teacher = await f.Login("xkw-teacher");
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync("/api/admin/xkw")).StatusCode);
        var initial = await admin.GetFromJsonAsync<JsonElement>("/api/admin/xkw");
        var revision = initial.GetProperty("revision").GetInt32();
        var input = new XkwConnectionInput(revision, "app-one", "test-secret-never-return", true,
            new() { ["Courses:初中数学"]="2", ["Knowledge:初中数学/方程"]="77" });
        var saved = await admin.PutAsJsonAsync("/api/admin/xkw", input);
        Assert.Equal(HttpStatusCode.OK, saved.StatusCode);
        Assert.DoesNotContain(input.Secret!, await saved.Content.ReadAsStringAsync());
        var settings = f.Services.GetRequiredService<XkwConnectionSettings>();
        Assert.Equal(input.Secret, settings.Resolve()["Research:Xkw:Secret"]);
        var file = Path.Combine(f.Services.GetRequiredService<ResearchStore>().Root, "xkw-connection.protected.json");
        Assert.DoesNotContain(input.Secret!, await File.ReadAllTextAsync(file));
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PutAsJsonAsync("/api/admin/xkw", input)).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await admin.PutAsJsonAsync("/api/admin/xkw", input with { Revision=revision+1, AppId="different-app", Secret=null })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsJsonAsync("/api/admin/xkw", input with { Revision=revision+1, Secret=null })).StatusCode);
        Assert.Equal(input.Secret, settings.Resolve()["Research:Xkw:Secret"]);
        Assert.Equal(HttpStatusCode.BadRequest, (await admin.PutAsJsonAsync("/api/admin/xkw", input with { Revision=revision+2, Profile=new() { ["Secret"]="override" } })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsJsonAsync("/api/admin/xkw", input with { Revision=revision+2, Enabled=false, ClearSecret=true })).StatusCode);
        Assert.Equal("", settings.Resolve()["Research:Xkw:Secret"]);
        Assert.Contains("https://open.xkw.com", await admin.GetStringAsync("/research/xkw.html"));
    }

    [Fact]
    public async Task XkwCandidateAppendsOnceAndMustBeReviewedBeforeExport()
    {
        await using var f = new ResearchFactory(); using var c = await f.LoginOwner("xkw-author");
        var draft = await Factory.Post(c,"/api/research/drafts",Input()); var id=draft.GetProperty("id").GetGuid();
        draft=await Edit(c,id,draft,"add",item:new ResearchItem {Stem="一元一次方程",Knowledge=["一元一次方程"]});
        var item=draft.GetProperty("items")[0].GetProperty("id").GetGuid();
        var search=await Factory.Post(c,$"/api/research/drafts/{id}/search",new ResearchSearch(item,["一元一次方程"],"山东"));
        var token=search.GetProperty("items")[0].GetProperty("id").GetString();
        draft=await Edit(c,id,draft,"append-candidate",item,candidate:token);
        Assert.Equal(2,draft.GetProperty("items").GetArrayLength());
        Assert.Equal("一元一次方程",draft.GetProperty("items")[0].GetProperty("stem").GetString());
        Assert.Equal("学科网",draft.GetProperty("items")[1].GetProperty("source").GetString());
        Assert.False(draft.GetProperty("items")[1].GetProperty("reviewed").GetBoolean());
        Assert.Equal(HttpStatusCode.Conflict,(await c.PostAsJsonAsync($"/api/research/drafts/{id}/edit",new ResearchEdit(draft.GetProperty("revision").GetInt32(),"append-candidate",item,CandidateId:token))).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict,(await c.GetAsync($"/api/research/drafts/{id}/export?format=docx")).StatusCode);
        draft=await Edit(c,id,draft,"undo");Assert.Single(draft.GetProperty("items").EnumerateArray());
    }

    [Fact]
    public async Task WrongQuestionDraftUsesReviewedRecordsAndKeepsSourceRelationships()
    {
        await using var f=new ResearchFactory();using var c=await f.LoginOwner("wrong-source-admin");
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
        var staff=await db.Staff.SingleAsync();var campus=new Campus{Name="测试校区"};var course=new Course{Name="数学"};
        var cls=new SchoolClass{Name="初中班",CampusId=campus.Id};var student=new Student{ClassId=cls.Id,Name="学生甲",Code="01"};
        var lecture=new Lecture{CourseId=course.Id,Title="方程",Sequence=1};
        var handout=new Handout{LectureId=lecture.Id,State="published",NormalizedKey="xkw-test/source.docx",Version=1};
        var question=new Question{HandoutId=handout.Id,Confirmed=true,Label="1",BlocksJson=Json.Write(new[]{new ContentBlock("<p />","一元一次方程 2x=6","text")})};
        var session=new ClassSession{ClassId=cls.Id,CourseId=course.Id,HandoutId=handout.Id,StaffId=staff.Id,LessonDate="2026-11-14"};
        var mark=new ClassSessionMark{ClassSessionId=session.Id,StudentId=student.Id,Reviewed=true,RecordedBy=staff.Id,WrongIdsJson=Json.Write(new[]{question.Id,question.Id})};
        db.AddRange(campus,course,cls,student,lecture,handout,question,session,mark);await db.SaveChangesAsync();
        using var content=new MemoryStream([1,2,3,4]);await scope.ServiceProvider.GetRequiredService<IObjects>().Put(handout.NormalizedKey,content,WordDocuments.DocxType);
        var input=new ResearchWrongQuestions.Input(session.Id,"数学","初中","七年级","山东");
        var draft=await Factory.Post(c,"/api/research/from-session",input);Assert.Single(draft.GetProperty("items").EnumerateArray());
        var item=draft.GetProperty("items")[0];Assert.Equal(question.Id.ToString(),item.GetProperty("sourceId").GetString());Assert.True(item.GetProperty("original").GetBoolean());
        var dir=scope.ServiceProvider.GetRequiredService<ResearchStore>().Dir(staff.Id,draft.GetProperty("id").GetGuid());Assert.Equal(new byte[]{1,2,3,4},await File.ReadAllBytesAsync(Path.Combine(dir,"source.docx")));
        Assert.Equal(HttpStatusCode.BadRequest,(await c.PostAsJsonAsync("/api/research/from-session",input with {QuestionIds=[Guid.NewGuid()]})).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,(await c.PostAsJsonAsync("/api/research/from-session",input with {StudentId=Guid.NewGuid()})).StatusCode);
        mark.Reviewed=false;await db.SaveChangesAsync();
        Assert.Equal(HttpStatusCode.Conflict,(await c.PostAsJsonAsync("/api/research/from-session",input)).StatusCode);
        Assert.Single(await db.Questions.ToArrayAsync());Assert.Equal(Json.Write(new[]{question.Id,question.Id}),mark.WrongIdsJson);
    }
}
