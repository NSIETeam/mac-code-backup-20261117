using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class TeacherUsageTests
{
    [Fact]
    public async Task OnlyAdminCanReadUsageAndOnlyAuthenticatedMiniappRequestsAreAttributed()
    {
        await using var fixture = new Factory();
        using var admin = await fixture.Login("usage-admin", true);
        fixture.Services.GetRequiredService<IConfiguration>()["TeacherUsage:MiniappId"] = "wxstatistics-test";
        var teacherId = Guid.NewGuid();
        using (var scope = fixture.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            db.Staff.Add(new Staff { Id = teacherId, Name = "同名老师", Role = "teacher", OpenId = "development:usage-teacher" });
            db.Audits.Add(new Audit { StaffId = teacherId, Action = "print.submit", TargetId = Guid.NewGuid(),
                CreatedAt = new DateTimeOffset(2026, 11, 11, 16, 0, 0, TimeSpan.Zero) });
            db.Audits.Add(new Audit { StaffId = teacherId, Action = "print.reprint", TargetId = Guid.NewGuid(),
                CreatedAt = new DateTimeOffset(2026, 11, 11, 15, 59, 59, TimeSpan.Zero) });
            db.Audits.Add(new Audit { StaffId = teacherId, Action = "identity.password-login", TargetId = teacherId,
                CreatedAt = new DateTimeOffset(2026, 11, 12, 8, 0, 0, TimeSpan.Zero) });
            await db.SaveChangesAsync();
        }

        const string past = "/api/admin/teacher-usage?start=2026-11-12&end=2026-11-12";
        var history = (await admin.GetFromJsonAsync<JsonElement>(past)).GetProperty("items").EnumerateArray();
        var old = Assert.Single(history);
        Assert.Equal("2026-11-12", old.GetProperty("day").GetString());
        Assert.Equal("unattributed", old.GetProperty("source").GetString());
        Assert.Equal("提交打印", old.GetProperty("action").GetString());
        using var teacher = await fixture.Login("usage-teacher");
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync(past)).StatusCode);
        using var guest = fixture.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized, (await guest.GetAsync(past)).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await admin.GetAsync("/api/admin/teacher-usage?start=2026-11-01&end=2026-12-02")).StatusCode);

        teacher.DefaultRequestHeaders.Referrer = new Uri("https://servicewechat.com/wxstatistics-test/5/page-frame.html");
        Assert.Equal(HttpStatusCode.OK, (await teacher.GetAsync("/api/workspace")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await teacher.GetAsync("/api/handouts/" + Guid.NewGuid())).StatusCode);
        teacher.DefaultRequestHeaders.Referrer = new Uri("https://servicewechat.com/wxstatistics-test/0/page-frame.html");
        Assert.Equal(HttpStatusCode.OK, (await teacher.GetAsync("/api/workspace")).StatusCode);
        teacher.DefaultRequestHeaders.Referrer = new Uri("https://servicewechat.com.invalid/wxstatistics-test/5/page-frame.html");
        Assert.Equal(HttpStatusCode.OK, (await teacher.GetAsync("/api/workspace")).StatusCode);
        teacher.DefaultRequestHeaders.Referrer = new Uri("https://servicewechat.com/wxdifferent/5/page-frame.html");
        Assert.Equal(HttpStatusCode.OK, (await teacher.GetAsync("/api/workspace")).StatusCode);

        var day = Clock.Now.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd");
        var path = $"/api/admin/teacher-usage?start={day}&end={day}";
        JsonElement[] rows = [];
        for (var attempt = 0; attempt < 20; attempt++)
        {
            rows = (await admin.GetFromJsonAsync<JsonElement>(path)).GetProperty("items").EnumerateArray()
                .Where(row => row.GetProperty("staffId").GetGuid() == teacherId && row.GetProperty("source").GetString() != "unattributed").ToArray();
            if (rows.Length >= 2) break;
            await Task.Delay(50);
        }
        Assert.Equal(new[] { "miniapp", "preview" }, rows.Select(x => x.GetProperty("source").GetString()).ToArray());
        Assert.All(rows, row => {
            Assert.Equal("打开工作台", row.GetProperty("action").GetString());
            Assert.Equal(1, row.GetProperty("count").GetInt32());
        });
    }
}
