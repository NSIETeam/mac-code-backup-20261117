using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.EntityFrameworkCore;
using Siyue;
using Xunit;

public class QuestionBankTopicTests
{
    sealed class DedicatedFactory : Factory
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) {
            base.ConfigureWebHost(builder);
            builder.ConfigureAppConfiguration((_, c) => c.AddInMemoryCollection(new Dictionary<string,string?> { ["QuestionBank:Dedicated"]="true",["RunWorker"]="true" }));
        }
    }
    sealed class Env : IWebHostEnvironment
    {
        public string ApplicationName { get; set; } = "tests";
        public string EnvironmentName { get; set; } = "Testing";
        public string ContentRootPath { get; set; } = AppContext.BaseDirectory;
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
        public string WebRootPath { get; set; } = "";
        public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    }
    static readonly Lazy<LocalQuestionBank> Actual = new(() => new(new ConfigurationBuilder().Build(), new Env(), NullLogger<LocalQuestionBank>.Instance));

    [Fact]
    public void ExpandedCatalogHasRealCountsAndDoesNotIncludeResearchOrStaging()
    {
        var available = Actual.Value.Availability(); Assert.True(available.Available); Assert.Equal(121153, available.Count); Assert.Equal(9, available.Coverage.Length);
    }
    [Fact]
    public void TopicAliasesReturnCheckedGeometryWithNativeDrawingAndFreshBatches()
    {
        var bank = Actual.Value; var query = new BankQuery("数学", "高中", "点线距离", "填空题");
        var first = bank.Search(query); Assert.Equal("matched", first.Status); Assert.Equal(5, first.Returned);
        Assert.All(first.Matches, m => { Assert.Contains("数学.analytic.line.point-line", m.Question.KnowledgeIds); Assert.Equal("填空题", m.Question.Type); Assert.NotEmpty(m.Question.Answer); if (m.Question.SourceKind == "original") { Assert.NotNull(m.Question.Diagram); Assert.All(m.Question.Diagram!.Shapes, s => Assert.All(s.Values, v => Assert.True(double.IsFinite(v)))); } });
        Assert.Contains(first.Matches, m => m.Question.Diagram != null);
        var next = bank.Search(query with { ExcludeIds = first.Matches.Select(m => m.Question.Id).ToArray() });
        Assert.Equal(5, next.Returned); Assert.Empty(first.Matches.Select(m => m.Question.Id).Intersect(next.Matches.Select(m => m.Question.Id)));
        Assert.Equal(first.Matches.Select(m => m.Question.Id), bank.Search(query).Matches.Select(m => m.Question.Id));
    }
    [Fact]
    public void ParentKnowledgeUsesDescendantsAndBroadQueriesPreferDifferentFamilies()
    {
        var result = Actual.Value.Search(new("数学", "高中", "立体几何")); Assert.Equal(5, result.Returned);
        Assert.All(result.Matches, m => Assert.Contains(m.Question.KnowledgeIds, id => id.StartsWith("数学.solid.")));
        var templates = result.Matches.Where(m => m.Question.TemplateId.Length > 0).Select(m => m.Question.TemplateId).ToArray(); Assert.Equal(templates.Distinct().Count(), templates.Length);
    }
    [Fact]
    public void ReadingQuestionsReturnCompleteMaterialAndAttributionWithoutInventingAnalysis()
    {
        var result = Actual.Value.Search(new("语文", "高中", "凋凌")); Assert.NotEmpty(result.Matches);
        var question = result.Matches.First(m => m.Question.Id.StartsWith("ncr-")).Question;
        Assert.Contains("隆冬之际", question.MaterialText); Assert.True(question.MaterialText.Length > 100); Assert.Contains("CC BY-SA", question.Attribution); Assert.NotEmpty(question.LicenseUrl); Assert.True(string.IsNullOrWhiteSpace(question.Analysis));
    }
    [Theory]
    [InlineData("生物", "光合作用")]
    [InlineData("物理", "串联电路")]
    [InlineData("化学", "物质的量浓度")]
    [InlineData("地理", "地方时")]
    public void OtherSubjectsUseSpecificTopicAndReturnAnswers(string subject, string topic)
    {
        var result = Actual.Value.Search(new(subject, "高中", topic)); Assert.Equal(5, result.Returned);
        Assert.All(result.Matches, m => { Assert.Equal(subject, m.Question.Subject); Assert.Equal("高中", m.Question.Stage); Assert.NotEmpty(m.Question.Answer); });
    }
    [Fact]
    public void UnknownTopicAndLowerStageReturnAnHonestEmptyResult()
    {
        Assert.Equal("no_match", Actual.Value.Search(new("数学", "高中", "量子场论重整化" )).Status);
        Assert.Equal(0, Actual.Value.Search(new("数学", "小学", "点线距离")).Returned);
        Assert.Equal(0, Actual.Value.Search(new("数学", "高中", "一元一次方程")).Matches.Count(m => m.Question.TemplateId.Contains("quadratic")));
    }
    [Fact]
    public async Task TopicApiRequiresEmployeeSessionAndValidatesBoundsAndTypeSuffix()
    {
        await using var f = new Factory(); using var anonymous = f.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync("/api/question-bank")).StatusCode);
        using var client = await f.Login("topic-admin", true);
        var before = await client.GetFromJsonAsync<JsonElement>("/api/question-bank"); Assert.Equal(121153, before.GetProperty("count").GetInt32());
        var result = await Factory.Post(client, "/api/question-bank/recommendations", new {subject="数学",stage="高中",topic="点线距离填空题"});
        Assert.Equal("填空题", result.GetProperty("type").GetString()); Assert.Equal(5, result.GetProperty("returned").GetInt32());
        foreach (var body in new object[] {
            new {subject="数学",stage="高中",topic="选择题"}, new {subject="数学",stage="高中",topic="  "},
            new {subject="数学",stage="高中",topic="椭圆",limit=21}, new {subject="数学",stage="高中",topic="椭圆",type="作业题"},
            new {subject="未知",stage="高中",topic="椭圆"}, new {subject="数学",stage="未知",topic="椭圆"},
            new {subject="数学",stage="高中",topic="椭圆",excludeIds=Enumerable.Repeat("q",101).ToArray()}
        }) Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync("/api/question-bank/recommendations", body)).StatusCode);
    }
    [Fact]
    public void DamagedExpandedShardFailsClosedAndNeverFallsBackToUncheckedContent()
    {
        var root = Path.Combine(Path.GetTempPath(), "bank-integrity-" + Guid.NewGuid());
        Directory.CreateDirectory(Path.Combine(root, "expanded/questions"));
        try {
            File.WriteAllText(Path.Combine(root,"knowledge-rules.json"), "{}");
            object Part(string file, string content, int count = 0) { File.WriteAllText(Path.Combine(root,"expanded",file), content); return new { file, count, sha256 = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(content))) }; }
            var knowledge = Part("knowledge.json", "{\"nodes\":[]}");
            var part = Part("questions/math-000.json", "[]", 0);
            File.WriteAllText(Path.Combine(root,"expanded/manifest.json"), JsonSerializer.Serialize(new { schema="siyue.server-bank/v2", count=1, subjects=new[]{new{subject="数学",stage="高中",count=1,parts=new[]{part}}},materials=Array.Empty<object>(),knowledge }));
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> { ["QuestionBank:Directory"]=root }).Build();
            Assert.False(new LocalQuestionBank(config,new Env(),NullLogger<LocalQuestionBank>.Instance).Availability().Available);
            var row = JsonSerializer.Serialize(new[]{new BankQuestion{Id="q",Subject="数学",Stage="高中",Type="填空题",Stem="求三角形面积",Answer="1",Tags=["三角形面积"],Source="测试自编",License="自编"}});
            part = Part("questions/math-000.json",row,1);
            File.WriteAllText(Path.Combine(root,"expanded/manifest.json"), JsonSerializer.Serialize(new { schema="siyue.server-bank/v2", count=1, subjects=new[]{new{subject="数学",stage="高中",count=1,parts=new[]{part}}},materials=Array.Empty<object>(),knowledge }));
            var valid = new LocalQuestionBank(config,new Env(),NullLogger<LocalQuestionBank>.Instance);Assert.Equal(1,valid.Search(new("数学","高中","三角形面积")).Returned);
            File.WriteAllText(Path.Combine(root,"expanded/questions/math-000.json"),row.Replace("\"1\"","\"999\""));
            var damaged = new LocalQuestionBank(config,new Env(),NullLogger<LocalQuestionBank>.Instance);Assert.Equal("unavailable",damaged.Search(new("数学","高中","三角形面积")).Status);
        } finally { Directory.Delete(root,true); }
    }
    [Fact]
    public async Task DedicatedServiceHasNoLoginPrintingOrBusinessMutationSurface()
    {
        await using var f = new DedicatedFactory(); using var client = f.CreateClient();
        using (var scope = f.Services.CreateScope()) await scope.ServiceProvider.GetRequiredService<Data>().Database.EnsureCreatedAsync();
        Assert.Equal(HttpStatusCode.OK,(await client.GetAsync("/health")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,(await client.GetAsync("/api/question-bank")).StatusCode);
        foreach (var route in new[]{"/api/auth/development","/api/exports","/api/wrong-questions","/api/academic-pilot/messages"}) Assert.Equal(HttpStatusCode.NotFound,(await client.PostAsJsonAsync(route,new{})).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,(await client.GetAsync("/api/workspace")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,(await client.GetAsync("/workbench/")).StatusCode);
        Assert.Equal("false",f.Services.GetRequiredService<IConfiguration>()["RunWorker"]);
    }
}

public partial class AcademicBetaTests
{
    [Fact]
    public async Task MiniappSourceLookupEnforcesStudentClassAndRegisteredWrongQuestion()
    {
        await using var f = new BetaFactory(); var x = await Seed(f); using var client = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        string Path(Guid session, Guid student, Guid question) => $"/api/question-bank/source?sessionId={session}&studentId={student}&questionId={question}";
        var source = await Get(client, Path(x.SessionId, x.StudentId, x.QuestionId)); Assert.Equal(x.QuestionId, source.GetProperty("questionId").GetGuid()); Assert.Contains("1/2", source.GetProperty("text").GetString());
        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync(Path(x.ForeignSessionId,x.ForeignStudentId,x.QuestionId))).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync(Path(x.SessionId,x.ForeignStudentId,x.QuestionId))).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await client.GetAsync(Path(x.SessionId,x.UnreviewedStudentId,x.QuestionId))).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await client.GetAsync(Path(x.SessionId,x.StudentId,x.OtherQuestionId))).StatusCode);
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }
}
