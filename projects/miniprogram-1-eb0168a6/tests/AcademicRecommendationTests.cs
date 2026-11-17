using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public partial class AcademicBetaTests
{
    [Fact]
    public async Task RecommendationsUseReviewedStudentErrorsAndNeverModifyBusinessRecords()
    {
        await using var f = new BetaFactory(); var x = await Seed(f);
        using var client = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        var path = $"{Api}/sessions/{x.SessionId}/recommendations/{x.StudentId}";
        var result = await Factory.Post(client, path, new { stage = "高中", questionId = x.QuestionId, supplementalText = "已知函数的导数，求函数的单调区间与极值。", tags = new[] { "导数" }, limitPerQuestion = 3 });
        Assert.Equal(x.StudentId, result.GetProperty("studentId").GetGuid());
        Assert.True(result.GetProperty("requiresTeacherReview").GetBoolean());
        var rows = result.GetProperty("questions").EnumerateArray().ToArray(); Assert.Single(rows);
        var matches = rows[0].GetProperty("matches").EnumerateArray().ToArray(); Assert.NotEmpty(matches); Assert.InRange(matches.Length, 1, 3);
        foreach (var match in matches) {
            var q = match.GetProperty("question"); Assert.Equal("高中", q.GetProperty("stage").GetString()); Assert.Equal("数学", q.GetProperty("subject").GetString());
            Assert.Equal(0, q.GetProperty("issues").GetArrayLength()); Assert.NotEmpty(q.GetProperty("source").GetString()!); Assert.NotEmpty(q.GetProperty("answer").GetString()!);
            Assert.NotEmpty(match.GetProperty("reasons").EnumerateArray());
        }
        Assert.Equal(before, await OriginalBusinessSnapshot(f));
    }

    [Fact]
    public async Task CatalogAndRecommendationsStayBehindPilotAndClassPermissions()
    {
        foreach (var enabled in new[] { false, true }) {
            await using var f = new BetaFactory(enabled); var x = await Seed(f);
            using var hidden = await f.Login(enabled ? "beta-outsider" : "beta-author");
            Assert.Equal(HttpStatusCode.NotFound, (await hidden.GetAsync(Api + "/question-bank")).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound, (await hidden.PostAsJsonAsync($"{Api}/sessions/{x.SessionId}/recommendations/{x.StudentId}", new { stage = "高中" })).StatusCode);
        }
        await using var allowed = new BetaFactory(); var y = await Seed(allowed); using var client = await allowed.Login("beta-author");
        Assert.Equal(HttpStatusCode.Forbidden, (await client.PostAsJsonAsync($"{Api}/sessions/{y.ForeignSessionId}/recommendations/{y.ForeignStudentId}", new { stage = "高中" })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await client.PostAsJsonAsync($"{Api}/sessions/{y.SessionId}/recommendations/{y.ForeignStudentId}", new { stage = "高中" })).StatusCode);
    }

    [Fact]
    public async Task RecommendationsRequireReviewedMarksAndRejectUnrelatedQuestionOverrides()
    {
        await using var f = new BetaFactory(); var x = await Seed(f); using var client = await f.Login("beta-author");
        var prefix = $"{Api}/sessions/{x.SessionId}/recommendations/";
        Assert.Equal(HttpStatusCode.Conflict, (await client.PostAsJsonAsync(prefix + x.UnreviewedStudentId, new { stage = "小学" })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await client.PostAsJsonAsync(prefix + x.StudentId, new { stage = "高中", questionId = x.OtherQuestionId, supplementalText = "函数" })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(prefix + x.StudentId, new { stage = "高中", supplementalText = "函数" })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(prefix + x.StudentId, new { stage = "高中", subject = "英语" })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(prefix + x.StudentId, new { stage = "高中", limitPerQuestion = 100 })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(prefix + x.StudentId, new { stage = "未标注" })).StatusCode);
    }

    [Fact]
    public async Task ImageOnlyWrongQuestionsAskForTextAndNeverUseAHighSchoolFallback()
    {
        await using var f = new BetaFactory(); var x = await Seed(f);
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); var q = await db.Questions.FindAsync(x.QuestionId);
            q!.BlocksJson = Json.Write(new[] { new ContentBlock("", "", "image") }); await db.SaveChangesAsync();
        }
        using var client = await f.Login("beta-author");
        var path = $"{Api}/sessions/{x.SessionId}/recommendations/{x.StudentId}";
        var first = await Factory.Post(client, path, new { stage = "小学" });
        Assert.Equal("needs_text", first.GetProperty("questions")[0].GetProperty("status").GetString());
        var second = await Factory.Post(client, path, new { stage = "小学", questionId = x.QuestionId, tags = new[] { "函数" } });
        Assert.Equal("no_match", second.GetProperty("questions")[0].GetProperty("status").GetString());
        Assert.Equal(0, second.GetProperty("questions")[0].GetProperty("matches").GetArrayLength());
        var none = await Factory.Post(client, $"{Api}/sessions/{x.SessionId}/recommendations/{x.SecondStudentId}", new { stage = "高中" });
        Assert.Equal(0, none.GetProperty("questions").GetArrayLength());
    }
}
