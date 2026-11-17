using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

public class LearningModelConfiguration : Entity
{
    public string ProtectedJson { get; set; } = "";
    public int Revision { get; set; }
}
public record ModelConfigurationInput(int Revision, bool Enabled, string Endpoint, string Model,
    string Protocol = "chat-completions", bool Vision = true, bool JsonMode = true,
    string? ApiKey = null, bool ClearApiKey = false, bool PdfAssistEnabled = false, int PdfAssistMaxPages = 20, int TimeoutSeconds = 45, string ReasoningEffort = "default");
public record ModelConfigurationValue(bool Enabled, string Endpoint, string Model, string Protocol,
    bool Vision, bool JsonMode, string ApiKey, bool PdfAssistEnabled = false, int PdfAssistMaxPages = 20, int TimeoutSeconds = 45, string ReasoningEffort = "default");

public class LearningModelSettings(Data db, IConfiguration environment, IDataProtectionProvider protection)
{
    public static readonly Guid ConfigurationId = Guid.Parse("8b682b06-600c-428e-9de8-30a7a180c8c0");
    IDataProtector Protector => protection.CreateProtector("Siyue.LearningModelConfiguration.v1");

    async Task<(LearningModelConfiguration? Row, ModelConfigurationValue Value)> Read()
    {
        var row = await db.LearningModelConfigurations.SingleOrDefaultAsync(x => x.Id == ConfigurationId);
        if (row != null) return (row, Json.Read<ModelConfigurationValue>(Protector.Unprotect(row.ProtectedJson)));
        return (null, new(VariantGenerator.Configured(environment), environment["LearningAi:Endpoint"] ?? "",
            environment["LearningAi:Model"] ?? "", VariantGenerator.Protocol(environment),
            environment.GetValue("LearningAi:Vision", true), environment.GetValue("LearningAi:JsonMode", true),
            environment["LearningAi:ApiKey"] ?? "", environment.GetValue("LearningAi:PdfAssistEnabled",false), environment.GetValue("LearningAi:PdfAssistMaxPages",20), environment.GetValue("LearningAi:TimeoutSeconds",45), environment["LearningAi:ReasoningEffort"] ?? "default"));
    }

    static IConfiguration Apply(IConfiguration environment, ModelConfigurationValue value) =>
        new ConfigurationBuilder().AddConfiguration(environment).AddInMemoryCollection(new Dictionary<string, string?> {
            ["LearningAi:Enabled"] = value.Enabled.ToString(), ["LearningAi:Endpoint"] = ExternalModelClient.NormalizeEndpoint(value.Endpoint, value.Protocol),
            ["LearningAi:Model"] = value.Model, ["LearningAi:Protocol"] = value.Protocol,
            ["LearningAi:Vision"] = value.Vision.ToString(), ["LearningAi:JsonMode"] = value.JsonMode.ToString(),
            ["LearningAi:ApiKey"] = value.ApiKey, ["LearningAi:PdfAssistEnabled"] = value.PdfAssistEnabled.ToString(),
            ["LearningAi:PdfAssistMaxPages"] = value.PdfAssistMaxPages.ToString(), ["LearningAi:TimeoutSeconds"] = value.TimeoutSeconds.ToString(),
            ["LearningAi:ReasoningEffort"] = value.ReasoningEffort
        }).Build();

    public async Task<IConfiguration> Resolve() => Apply(environment, (await Read()).Value);

    public async Task<object> PublicView()
    {
        var (row, value) = await Read();
        return new { revision = row?.Revision ?? 0, value.Enabled, value.Endpoint, value.Model, value.Protocol,
            value.Vision, value.JsonMode, value.PdfAssistEnabled, value.PdfAssistMaxPages, value.TimeoutSeconds, value.ReasoningEffort, provider = ExternalModelClient.Provider(value.Endpoint), hasApiKey = value.ApiKey.Length > 0,
            configured = VariantGenerator.Configured(Apply(environment, value)), source = row == null ? "environment" : "saved" };
    }

    public async Task<IConfiguration> ForProbe(int revision)
    {
        var (row, value) = await Read();
        Guard.That(revision == (row?.Revision ?? 0), "配置已变化，请刷新后测试", 409);
        var resolved = Apply(environment, value with { Enabled = true });
        Guard.That(VariantGenerator.Configured(resolved), "请先保存完整的接口、模型和密钥");
        return resolved;
    }

    public async Task Save(ModelConfigurationInput input, Guid staffId)
    {
        var (row, previous) = await Read();
        Guard.That(input.Revision == (row?.Revision ?? 0), "配置已被修改，请刷新后重试", 409);
        var endpoint = ExternalModelClient.NormalizeEndpoint(input.Endpoint, input.Protocol); var model = input.Model.Trim();
        Guard.That(endpoint.Length <= 2000 && model.Length <= 200 && input.Protocol is "chat-completions" or "structured", "模型配置格式不正确");
        Guard.That(endpoint.Length == 0 || Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) && uri.Scheme == "https" && !uri.IsLoopback && (!System.Net.IPAddress.TryParse(uri.Host, out var ip) || ExternalModelClient.IsPublic(ip)) && uri.UserInfo.Length == 0 && uri.Fragment.Length == 0 && uri.Query.Length == 0, "请填写完整 HTTPS 接口地址，不要在地址中填写密钥或其他参数");
        Guard.That(!input.ClearApiKey || string.IsNullOrWhiteSpace(input.ApiKey), "清除密钥和填写新密钥不能同时操作");
        Guard.That(input.ApiKey == null || input.ApiKey.Length <= 4096 && !input.ApiKey.Any(char.IsControl), "密钥格式不正确");
        Guard.That(input.PdfAssistMaxPages is >= 1 and <= 100 && input.TimeoutSeconds is >= 10 and <= 120 && input.ReasoningEffort is "default" or "low" or "high" or "max", "页数、超时或推理强度设置不正确");
        Guard.That(!input.PdfAssistEnabled || input.Vision && input.Protocol == "chat-completions", "PDF 辅助需要标准接口及支持图片的模型");
        var key = input.ClearApiKey ? "" : string.IsNullOrWhiteSpace(input.ApiKey) ? previous.ApiKey : input.ApiKey.Trim();
        // Do not forward an existing credential to a newly selected service.
        if (endpoint != ExternalModelClient.NormalizeEndpoint(previous.Endpoint, previous.Protocol) && string.IsNullOrWhiteSpace(input.ApiKey)) key = "";
        var value = new ModelConfigurationValue(input.Enabled, endpoint, model, input.Protocol, input.Vision, input.JsonMode, key, input.PdfAssistEnabled, input.PdfAssistMaxPages, input.TimeoutSeconds, input.ReasoningEffort);
        Guard.That(!input.Enabled || VariantGenerator.Configured(Apply(environment, value)), "启用前请填写接口地址、模型名称和密钥");
        if (row == null) { row = new() { Id = ConfigurationId }; db.LearningModelConfigurations.Add(row); }
        row.ProtectedJson = Protector.Protect(Json.Write(value)); row.Revision++;
        db.Audits.Add(new Audit { StaffId = staffId, Action = "learning-model.configure", TargetId = ConfigurationId });
        await db.SaveChangesAsync();
    }
}

public record IntegrationProbeInput(int Revision);

public static class LearningModelSettingsEndpoints
{
    public static void MapLearningModelSettings(this WebApplication app)
    {
        app.MapGet("/api/admin/learning-model", async (Access access, LearningModelSettings settings) => {
            access.Admin(); return await settings.PublicView();
        });
        app.MapPut("/api/admin/learning-model", async (ModelConfigurationInput input, Access access, LearningModelSettings settings) => {
            access.Admin(); await settings.Save(input, access.User.Id); return await settings.PublicView();
        });
        app.MapPost("/api/admin/learning-model/models", async (IntegrationProbeInput input, Access access, LearningModelSettings settings, IHttpClientFactory clients, CancellationToken ct) => {
            access.Admin(); var config = await settings.ForProbe(input.Revision);
            Guard.That(VariantGenerator.Protocol(config) == "chat-completions", "专用中转接口请手动填写模型名称");
            var endpoint = config["LearningAi:Endpoint"]!;
            Guard.That(endpoint.EndsWith("/chat/completions"), "当前接口不支持自动获取模型，请手动填写");
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(20));
            try {
                var bytes = await ExternalModelClient.Send(clients, config, endpoint[..^17] + "/models", null, timeout.Token);
                using var json = System.Text.Json.JsonDocument.Parse(bytes);
                var models = json.RootElement.GetProperty("data").EnumerateArray().Select(x => x.GetProperty("id").GetString() ?? "").Where(x => x.Length is > 0 and <= 200).Distinct().Order().Take(300).ToArray();
                return Results.Ok(new { models });
            } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new ApiException(504, "获取模型列表超时，请稍后重试"); }
            catch (Exception e) when (e is System.Text.Json.JsonException or KeyNotFoundException or InvalidOperationException) { throw new ApiException(502, "服务商未提供兼容的模型列表，请手动填写"); }
        });
        app.MapPost("/api/admin/learning-model/test", async (IntegrationProbeInput input, Access access, LearningModelSettings settings, IHttpClientFactory clients, Data db, CancellationToken ct) => {
            access.Admin(); var config = await settings.ForProbe(input.Revision);
            var watch = System.Diagnostics.Stopwatch.StartNew();
            var id = Guid.Parse("5b063b23-0fee-4e80-8cfe-f0ff9653c2c6");
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(config.GetValue("LearningAi:TimeoutSeconds",45)));
            try {
                var sources = new[] { new VariantSource(id, [new ContentBlock("<p />", "2+3 等于多少？生成一道同类加法题。", "text")], config.GetValue("LearningAi:Vision",true) ? [PdfModelAssist.ProbeImage] : []) };
                var items = await VariantGenerator.Generate(config, clients, 1, sources, timeout.Token);
                LearningSupport.Validate(items, [id], 1);
                db.Audits.Add(new Audit { StaffId = access.User.Id, Action = "integration.test", TargetId = LearningModelSettings.ConfigurationId }); await db.SaveChangesAsync(ct);
                return Results.Ok(new { ok = true, milliseconds = watch.ElapsedMilliseconds, visionTested = config.GetValue("LearningAi:Vision",true), message = "连接成功，已验证模型输出格式" });
            } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new ApiException(504, "模型测试超时，配置已保留，可稍后重试"); }
        });

    }
}
