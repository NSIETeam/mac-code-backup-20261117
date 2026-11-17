using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class LearningModelSettingsTests
{
    const string Endpoint = "https://provider.invalid/v1/chat/completions";
    static ModelConfigurationInput Input(int revision = 0, bool enabled = false, string endpoint = Endpoint, string? key = null) =>
        new(revision, enabled, endpoint, "test-vision-model", ApiKey: key);

    [Fact]
    public async Task EmptyDraftCanBeSavedWithoutActivatingGeneration()
    {
        await using var f = new Factory(); using var admin = await f.Login("model-draft", true);
        var before = await admin.GetFromJsonAsync<JsonElement>("/api/admin/learning-model");
        Assert.False(before.GetProperty("configured").GetBoolean());
        var save = await admin.PutAsJsonAsync("/api/admin/learning-model", new ModelConfigurationInput(0, false, "", ""));
        Assert.Equal(HttpStatusCode.OK, save.StatusCode);
        var after = await save.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(1, after.GetProperty("revision").GetInt32()); Assert.False(after.GetProperty("enabled").GetBoolean());
        using var scope = f.Services.CreateScope();
        Assert.False(VariantGenerator.Configured(await scope.ServiceProvider.GetRequiredService<LearningModelSettings>().Resolve()));
    }

    [Fact]
    public async Task CredentialsAreEncryptedNeverReturnedAndBlankPreservesThem()
    {
        await using var f = new Factory(); using var admin = await f.Login("model-secret", true);
        const string key = "private-fixture-key";
        var response = await admin.PutAsJsonAsync("/api/admin/learning-model", Input(enabled: true, key: key));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.DoesNotContain(key, await response.Content.ReadAsStringAsync());
        var detail = await admin.GetFromJsonAsync<JsonElement>("/api/admin/learning-model");
        Assert.True(detail.GetProperty("hasApiKey").GetBoolean()); Assert.False(detail.TryGetProperty("apiKey", out _));
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>(); var row = await db.LearningModelConfigurations.SingleAsync();
            Assert.DoesNotContain(key, row.ProtectedJson);
            Assert.Equal(key, (await scope.ServiceProvider.GetRequiredService<LearningModelSettings>().Resolve())["LearningAi:ApiKey"]);
            Assert.Single(await db.Audits.Where(x => x.Action == "learning-model.configure").ToListAsync());
        }
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(1, true))).StatusCode);
        using (var scope = f.Services.CreateScope()) Assert.Equal(key, (await scope.ServiceProvider.GetRequiredService<LearningModelSettings>().Resolve())["LearningAi:ApiKey"]);
        Assert.Equal(HttpStatusCode.Conflict, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(1, false))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(2) with { ClearApiKey = true })).StatusCode);
        using (var scope = f.Services.CreateScope()) {
            var config = await scope.ServiceProvider.GetRequiredService<LearningModelSettings>().Resolve();
            Assert.False(VariantGenerator.Configured(config)); Assert.Equal("", config["LearningAi:ApiKey"]);
        }
    }

    [Fact]
    public async Task EndpointChangesCannotReuseAnotherServicesCredentialAndInvalidInputsFail()
    {
        await using var f = new Factory(); using var admin = await f.Login("model-validation", true);
        Assert.Equal(HttpStatusCode.BadRequest, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(enabled: true))).StatusCode);
        foreach (var endpoint in new[] { "http://provider.invalid", "https://key@provider.invalid/v1", "https://provider.invalid/v1?key=secret", "https://provider.invalid/#secret" })
            Assert.Equal(HttpStatusCode.BadRequest, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(endpoint: endpoint))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(enabled: true, key: "fixture-key"))).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(1, true, "https://other.invalid/v1"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsJsonAsync("/api/admin/learning-model", Input(1, false, "https://other.invalid/v1"))).StatusCode);
        var view = await admin.GetFromJsonAsync<JsonElement>("/api/admin/learning-model"); Assert.False(view.GetProperty("hasApiKey").GetBoolean());
    }

    [Fact]
    public async Task TeachersCannotReadOrSaveConfiguration()
    {
        await using var f = new Factory(); using var admin = await f.Login("model-admin", true);
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "模型权限教师" });
        var invite = await Factory.Post(admin, $"/api/admin/staff/{staff.GetProperty("id").GetGuid()}/invite", new { });
        using var teacher = await f.Login("model-teacher"); await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync("/api/admin/learning-model")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.PutAsJsonAsync("/api/admin/learning-model", Input())).StatusCode);
    }
}
