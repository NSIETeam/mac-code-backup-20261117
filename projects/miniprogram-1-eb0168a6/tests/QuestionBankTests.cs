using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Logging.Abstractions;
using Siyue;
using Xunit;

public sealed class QuestionBankTests : IDisposable
{
    readonly string directory = Path.Combine(Path.GetTempPath(), "siyue-question-test-" + Guid.NewGuid());
    sealed class Env : IWebHostEnvironment
    {
        public string ApplicationName { get; set; } = "tests";
        public string EnvironmentName { get; set; } = "Testing";
        public string ContentRootPath { get; set; } = "";
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
        public string WebRootPath { get; set; } = "";
        public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    }
    static BankQuestion Q(string id, string stem = "求一元二次方程的判别式", string stage = "初中", string subject = "数学", string[]? issues = null) => new() {
        Id = id, Stem = stem, Stage = stage, Subject = subject, Tags = ["一元二次方程"], Answer = "参考答案", Type = "解答题", Source = "自编测试题", License = "自编", Issues = issues ?? []
    };
    LocalQuestionBank Bank(params BankQuestion[] questions)
    {
        Directory.CreateDirectory(directory);
        File.WriteAllText(Path.Combine(directory, "questions.json"), JsonSerializer.Serialize(questions));
        File.WriteAllText(Path.Combine(directory, "knowledge-rules.json"), "{}");
        return new(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> { ["QuestionBank:Directory"] = directory }).Build(), new Env(), NullLogger<LocalQuestionBank>.Instance);
    }
    [Fact]
    public void MatchesRespectStageSubjectQualityExclusionsAndDeduplication()
    {
        var bank = Bank(Q("good"), Q("duplicate"), Q("wrong-stage", stage: "高中"), Q("wrong-subject", subject: "物理"), Q("flagged", issues: ["缺图"]), Q("no-answer") with { Answer = "" });
        var matches = bank.Recommend("数学", "初中", "根据判别式求一元二次方程的根", ["一元二次方程"], 5, new HashSet<string>());
        Assert.Single(matches); Assert.Equal("good", matches[0].Question.Id);
        Assert.Empty(bank.Recommend("数学", "初中", "根据判别式求一元二次方程的根", ["一元二次方程"], 5, new HashSet<string> { LocalQuestionBank.Fingerprint("数学", "求一元二次方程的判别式") }));
        Assert.Empty(bank.Recommend("数学", "初中", "例1 求一元二次方程的判别式", [], 3, new HashSet<string>()));
    }
    [Fact]
    public void SubjectAndBoilerplateAloneCannotProduceRecommendations()
    {
        var bank = Bank(Q("unrelated", "已知三角形的周长求面积") with { Tags = [] });
        Assert.Empty(bank.Recommend("数学", "初中", "已知下列说法正确的是", [], 3, new HashSet<string>()));
        Assert.Empty(bank.Recommend("数学", "初中", "", [], 3, new HashSet<string>()));
    }
    [Fact]
    public void RelevantTextCanMatchWithoutTagsAndRankingIsDeterministic()
    {
        var bank = Bank(Q("b", "求一元二次方程判别式的符号及根的个数") with { Tags = [] }, Q("a", "求一元二次方程判别式的符号及根的个数与关系") with { Tags = [] });
        var first = bank.Recommend("数学", "初中", "利用一元二次方程判别式判断根的个数", [], 3, new HashSet<string>());
        Assert.NotEmpty(first);
        Assert.Equal(first.Select(x => x.Question.Id), bank.Recommend("数学", "初中", "利用一元二次方程判别式判断根的个数", [], 3, new HashSet<string>()).Select(x => x.Question.Id));
    }
    [Fact]
    public void MissingOrInvalidCatalogHasExplicitUnavailableState()
    {
        var bank = Bank(); Assert.False(bank.Availability().Available);
        File.WriteAllText(Path.Combine(directory,"questions.json"), "invalid");
        bank = new(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> { ["QuestionBank:Directory"] = directory }).Build(), new Env(), NullLogger<LocalQuestionBank>.Instance);
        Assert.False(bank.Availability().Available);
    }
    public void Dispose() { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
}
