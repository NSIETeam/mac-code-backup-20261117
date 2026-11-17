using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public sealed class CommunityQuestionTests
{
    sealed class CommunityFactory : Factory
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) {
            base.ConfigureWebHost(builder);
            builder.ConfigureAppConfiguration((_,config)=>config.AddInMemoryCollection(new Dictionary<string,string?>{
                ["QuestionBank:UploadsEnabled"]="true",["QuestionBank:UploadsDirectory"]=Path.Combine(Root,"community")
            }));
        }
    }
    static object Q(string topic="蓝桥测试代数",string answer="15")=>new{subject="数学",stage="高中",type="填空题",stem=topic+"：设 a=3，b=5，求 a·b。",answer,analysis="3×5=15",source="教研测试自编",license="本人原创，允许校内教学共享",tags=new[]{topic}};
    static async Task<HttpClient> Teacher(Factory f,HttpClient admin,string name)
    {
        var staff=await Factory.Post(admin,"/api/admin/catalog/staff",new{name});var id=staff.GetProperty("id").GetGuid();
        var invite=await Factory.Post(admin,$"/api/admin/staff/{id}/invite",new{});var teacher=await f.Login(name);
        await Factory.Post(teacher,"/api/auth/bind",new{code=invite.GetProperty("code").GetString()});return teacher;
    }
    [Fact]
    public async Task SharedPortalHasACanonicalTrailingSlashAndNoAnonymousUploadAccess()
    {
        await using var f=new CommunityFactory();using var client=f.CreateClient();
        var page=await client.GetAsync("/question-bank/");Assert.Equal(HttpStatusCode.OK,page.StatusCode);Assert.Contains("员工登录",await page.Content.ReadAsStringAsync());
        Assert.Equal(HttpStatusCode.OK,(await client.GetAsync("/question-bank/app.mjs")).StatusCode);
        var logo=await client.GetAsync("/question-bank/siyue-ai-logo.png");Assert.Equal(HttpStatusCode.OK,logo.StatusCode);Assert.Equal("image/png",logo.Content.Headers.ContentType?.MediaType);
        Assert.Equal(HttpStatusCode.OK,(await client.GetAsync("/question-bank/math/katex.min.css")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,(await client.GetAsync("/question-bank/unknown-file.json")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,(await client.GetAsync("/api/question-bank/uploads/mine")).StatusCode);
    }
    [Fact]
    public async Task EveryLoggedInTeacherCanPublishAndOtherStaffCanSearchImmediately()
    {
        await using var f=new CommunityFactory();using var admin=await f.Login("community-admin",true);using var teacher=await Teacher(f,admin,"普通上传教师");
        var original=(await admin.GetFromJsonAsync<JsonElement>("/api/question-bank")).GetProperty("count").GetInt32();
        var upload=await Factory.Post(teacher,"/api/question-bank/uploads",new{requestId="first-upload",questions=new[]{Q()}});Assert.Equal(1,upload.GetProperty("added").GetInt32());
        var id=upload.GetProperty("ids")[0].GetString();
        var result=await Factory.Post(admin,"/api/question-bank/recommendations",new{subject="数学",stage="高中",topic="蓝桥测试代数"});var q=result.GetProperty("matches")[0].GetProperty("question");Assert.Equal(id,q.GetProperty("id").GetString());Assert.Equal("community",q.GetProperty("sourceKind").GetString());Assert.False(q.GetProperty("reviewed").GetBoolean());
        Assert.Equal(original+1,(await admin.GetFromJsonAsync<JsonElement>("/api/question-bank")).GetProperty("count").GetInt32());
        using var scope=f.Services.CreateScope();Assert.Equal("teacher",(await scope.ServiceProvider.GetRequiredService<Data>().Staff.FindAsync((await teacher.GetFromJsonAsync<JsonElement>("/api/me")).GetProperty("id").GetGuid()))!.Role);
    }
    [Fact]
    public async Task UploadRetryIsIdempotentAndDuplicateContentIsSharedOnlyOnce()
    {
        await using var f=new CommunityFactory();using var admin=await f.Login("retry-admin",true);using var teacher=await Teacher(f,admin,"重复上传教师");
        var body=new{requestId="stable-id",questions=new[]{Q()}};var first=await Factory.Post(teacher,"/api/question-bank/uploads",body);var retry=await Factory.Post(teacher,"/api/question-bank/uploads",body);Assert.Equal(first.GetRawText(),retry.GetRawText());
        var duplicate=await Factory.Post(admin,"/api/question-bank/uploads",new{requestId="other-owner",questions=new[]{Q()}});Assert.Equal(0,duplicate.GetProperty("added").GetInt32());Assert.Equal(1,duplicate.GetProperty("duplicates").GetInt32());
        Assert.Equal(HttpStatusCode.Conflict,(await teacher.PostAsJsonAsync("/api/question-bank/uploads",new{requestId="stable-id",questions=new[]{Q(answer:"999")}})).StatusCode);
        Assert.Single((await teacher.GetFromJsonAsync<JsonElement[]>("/api/question-bank/uploads/mine"))!);Assert.Empty((await admin.GetFromJsonAsync<JsonElement[]>("/api/question-bank/uploads/mine"))!);
    }
    [Fact]
    public async Task OnlyOwnerCanWithdrawAndRestoreAndSearchCacheFollowsTheRevision()
    {
        await using var f=new CommunityFactory();using var admin=await f.Login("withdraw-admin",true);using var teacher=await Teacher(f,admin,"撤回教师");var query=new{subject="数学",stage="高中",topic="可撤回方程测试"};
        var empty=await Factory.Post(admin,"/api/question-bank/recommendations",query);Assert.Equal(0,empty.GetProperty("returned").GetInt32());
        var upload=await Factory.Post(teacher,"/api/question-bank/uploads",new{requestId="withdraw-test",questions=new[]{Q("可撤回方程测试")}});var id=upload.GetProperty("ids")[0].GetString();
        Assert.Equal(1,(await Factory.Post(admin,"/api/question-bank/recommendations",query)).GetProperty("returned").GetInt32());
        Assert.Equal(HttpStatusCode.Forbidden,(await admin.PutAsJsonAsync("/api/question-bank/uploads/"+id,new{active=false})).StatusCode);
        Assert.Equal(HttpStatusCode.OK,(await teacher.PutAsJsonAsync("/api/question-bank/uploads/"+id,new{active=false})).StatusCode);
        Assert.Equal(0,(await Factory.Post(admin,"/api/question-bank/recommendations",query)).GetProperty("returned").GetInt32());
        Assert.Equal(HttpStatusCode.OK,(await teacher.PutAsJsonAsync("/api/question-bank/uploads/"+id,new{active=true})).StatusCode);
        Assert.Equal(1,(await Factory.Post(admin,"/api/question-bank/recommendations",query)).GetProperty("returned").GetInt32());
    }
    [Fact]
    public async Task AWholeBatchIsValidatedBeforePublishingAndAnonymousUploadsAreDenied()
    {
        await using var f=new CommunityFactory();using var client=f.CreateClient();Assert.Equal(HttpStatusCode.Unauthorized,(await client.PostAsJsonAsync("/api/question-bank/uploads",new{requestId="anonymous",questions=new[]{Q()}})).StatusCode);
        using var admin=await f.Login("validation-admin",true);
        Assert.Equal(HttpStatusCode.BadRequest,(await admin.PostAsJsonAsync("/api/question-bank/uploads",new{requestId="partial",questions=new[]{Q(),new{subject="数学",stage="高中",type="填空题",stem="缺答案的题",answer="",analysis="",source="本人原创",license="校内共享",tags=new[]{"测试"}}}})).StatusCode);
        Assert.Empty((await admin.GetFromJsonAsync<JsonElement[]>("/api/question-bank/uploads/mine"))!);
        Assert.Equal(HttpStatusCode.BadRequest,(await admin.PostAsJsonAsync("/api/question-bank/uploads",new{requestId="big-batch",questions=Enumerable.Repeat(Q(),101)})).StatusCode);
    }
    [Fact]
    public async Task BuiltInDuplicatesAreNotAddedAndReadingMaterialSurvivesStorage()
    {
        await using var f=new CommunityFactory();using var admin=await f.Login("material-admin",true);var built=await Factory.Post(admin,"/api/question-bank/recommendations",new{subject="数学",stage="高中",topic="点线距离",type="填空题",limit=1});var q=built.GetProperty("matches")[0].GetProperty("question");
        var duplicate=await Factory.Post(admin,"/api/question-bank/uploads",new{requestId="built-duplicate",questions=new[]{new{subject="数学",stage="高中",type="填空题",stem=q.GetProperty("stem").GetString(),answer=q.GetProperty("answer").GetString(),source="测试复制",license="原有授权",tags=Array.Empty<string>()}}});Assert.Equal(0,duplicate.GetProperty("added").GetInt32());
        var upload=await Factory.Post(admin,"/api/question-bank/uploads",new{requestId="reading",questions=new[]{new{subject="语文",stage="高中",type="选择题",stem="青岚阅读专项测试：作者的主要观点是什么？A.甲 B.乙 C.丙 D.丁",answer="A",materialText="这里是必须随题目保留的完整阅读材料。",source="本人原创",license="校内教学共享",tags=new[]{"青岚阅读专项测试"}}}});
        var result=await Factory.Post(admin,"/api/question-bank/recommendations",new{subject="语文",stage="高中",topic="青岚阅读专项测试"});Assert.Equal("这里是必须随题目保留的完整阅读材料。",result.GetProperty("matches")[0].GetProperty("question").GetProperty("materialText").GetString());Assert.Equal(1,upload.GetProperty("added").GetInt32());
    }
    [Fact]
    public async Task ContributionsSurviveProviderRestartWithoutTouchingBusinessTables()
    {
        await using var f=new CommunityFactory();using var admin=await f.Login("restart-admin",true);await Factory.Post(admin,"/api/question-bank/uploads",new{requestId="restart",questions=new[]{Q()}});
        var config=f.Services.GetRequiredService<IConfiguration>();var env=f.Services.GetRequiredService<IWebHostEnvironment>();var reopened=new CommunityQuestionStore(config,env);Assert.Single(reopened.Snapshot().Questions);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();Assert.Empty(db.Questions);Assert.Empty(db.PrintJobs);Assert.Empty(db.Documents);
    }
}
