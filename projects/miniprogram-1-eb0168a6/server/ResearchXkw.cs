using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Caching.Memory;

namespace Siyue;

public interface IResearchQuestions
{
    object Status();
    Task<ResearchSearchResult> Search(ResearchDraft draft, ResearchSearch query, CancellationToken ct);
    ResearchItem Candidate(Guid owner, Guid draft, string id);
}

// XOP v2 signing follows xkw-mp/xkw-xop-client, HmacUtilsV2 + HmacUtils at
// 1a1078ab6081ca6d6d2a56785d6a58f228382082. No guessed credentials or silent local fallback.
// Tenant-specific filter/response field mappings must be verified against the contracted API.
public sealed class ResearchXkw(IConfiguration environment, IHttpClientFactory clients, XkwConnectionSettings? settings = null) : IResearchQuestions, IDisposable
{
    readonly MemoryCache cache = new(new MemoryCacheOptions { SizeLimit = 2000 });
    IConfiguration config => settings?.Resolve() ?? environment;
    string Setting(string key, string fallback = "") => config["Research:Xkw:" + key] ?? fallback;
    bool Configured => Setting("AppId").Length > 0 && Setting("Secret").Length > 0 && config.GetValue<bool>("Research:Xkw:ContractVerified");
    public object Status() => new { provider = "xkw", configured = Configured, state = Configured ? "configured" : "not_configured",
        message = Configured ? "学科网接口已配置，搜索时验证连接与权限" : "学科网尚未接通：需配置应用凭据、学科与知识点映射并完成接口联调", portal = "https://open.xkw.com" };
    public static Dictionary<string,string> Sign(string appId, string secret, string path, string body, long timestamp, string nonce)
    {
        var values = new SortedDictionary<string,string>(StringComparer.Ordinal) { ["Xop-App-Id"] = appId, ["Xop-Nonce"] = nonce,
            ["Xop-Timestamp"] = timestamp.ToString(System.Globalization.CultureInfo.InvariantCulture), ["xop_url"] = path };
        if (body.Length > 0) values["xop_body"] = body;
        var canonical = Convert.ToBase64String(Encoding.UTF8.GetBytes(string.Join("&", values.Select(p => p.Key + "=" + p.Value))));
        var signature = Convert.ToHexStringLower(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), Encoding.UTF8.GetBytes(canonical)));
        return new() { ["Xop-App-Id"] = appId, ["Xop-Nonce"] = nonce, ["Xop-Timestamp"] = values["Xop-Timestamp"], ["Xop-Sign-V2"] = signature };
    }
    string Map(string group, string key) => config[$"Research:Xkw:{group}:{key}"] ?? throw new ApiException(422, $"学科网尚未映射{group switch { "Courses" => "学科", "Knowledge" => "知识点", "Regions" => "地区", _ => "筛选项" }}“{key}”，请联系管理员配置");
    static JsonElement Field(JsonElement root, string path)
    {
        foreach (var part in path.Split('.', StringSplitOptions.RemoveEmptyEntries)) {
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty(part, out var value)) return default;
            root = value;
        }
        return root;
    }
    static string Text(JsonElement root, string path) { var value = Field(root, path); return value.ValueKind is JsonValueKind.String ? value.GetString()! : value.ValueKind == JsonValueKind.Number ? value.GetRawText() : ""; }
    public async Task<ResearchSearchResult> Search(ResearchDraft draft, ResearchSearch query, CancellationToken ct)
    {
        var snapshot = config;
        string Setting(string key, string fallback = "") => snapshot["Research:Xkw:" + key] ?? fallback;
        string Map(string group, string key) => snapshot[$"Research:Xkw:{group}:{key}"] ?? throw new ApiException(422, $"学科网尚未映射{group}“{key}”，请在接入配置中核对");
        Guard.That(Setting("AppId").Length > 0 && Setting("Secret").Length > 0 && snapshot.GetValue<bool>("Research:Xkw:ContractVerified"), "学科网尚未接通，请先配置官方应用凭据并完成接口联调", 503);
        Guard.That(query.Knowledge is { Length: > 0 and <= 6 } && query.Knowledge.All(k => k.Length is > 0 and <= 100) && query.Page is >= 1 and <= 100 && query.Region.Length <= 40 && query.Type.Length <= 40 && query.Difficulty.Length <= 40, "请确认知识点和筛选条件");
        var course = Map("Courses", draft.Stage + draft.Subject);
        var points = query.Knowledge.Select(k => Map("Knowledge", draft.Stage + draft.Subject + "/" + k)).ToArray();
        int NumericId(string value) { Guard.That(int.TryParse(value, out var number) && number > 0, "学科网的学科、知识点映射必须是有效的数值ID，请联系管理员核对", 422); return number; }
        var parameters = new Dictionary<string,object> { ["course_id"] = NumericId(course), ["kpoint_ids"] = points.Select(NumericId).ToArray(), ["count"] = 10 };
        void Filter(string value, string group, string setting) {
            if (value.Length == 0) return;
            var key = Setting(setting); Guard.That(key.Length > 0, "当前学科网接口未配置该筛选能力，请联系管理员完成联调", 422);
            var mapped = Map(group, value); parameters[key] = long.TryParse(mapped, out var number) ? number : mapped;
        }
        Filter(query.Region, "Regions", "RegionParameter"); Filter(query.Type, "Types", "TypeParameter"); Filter(query.Difficulty, "Difficulties", "DifficultyParameter");
        if (query.Page > 1) Guard.That(Setting("PageParameter").Length > 0, "当前接口不支持分页", 422);
        if (Setting("PageParameter").Length > 0) parameters[Setting("PageParameter")] = query.Page;
        var path = Setting("SearchPath", "/xopqbm/questions");
        Guard.That(Regex.IsMatch(path, @"\A/xopqbm/[a-zA-Z0-9/_-]+\z"), "学科网接口路径无效", 503);
        var body = Json.Write(parameters);
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://openapi.xkw.com" + path) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        foreach (var header in Sign(Setting("AppId"), Setting("Secret"), path, body, DateTimeOffset.UtcNow.ToUnixTimeSeconds(), Guid.NewGuid().ToString("N"))) request.Headers.Add(header.Key, header.Value);
        try {
            using var response = await clients.CreateClient("research-xkw").SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            Guard.That(response.IsSuccessStatusCode, response.StatusCode is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized ? "学科网凭据或题库权限无效" : "学科网暂时不可用，请稍后重试", 502);
            await using var stream = await response.Content.ReadAsStreamAsync(ct); using var memory = new MemoryStream();
            var buffer = new byte[8192]; int count;
            while ((count = await stream.ReadAsync(buffer, ct)) > 0) { Guard.That(memory.Length + count <= 4 * 1024 * 1024, "学科网返回内容过大", 502); memory.Write(buffer, 0, count); }
            using var json = JsonDocument.Parse(memory.ToArray());
            Guard.That(Text(json.RootElement, "code") == Setting("SuccessCode", "200"), "学科网未返回成功结果，请核对接口权限和筛选配置", 502);
            var rows = Field(json.RootElement, Setting("ItemsPath", "data"));
            Guard.That(rows.ValueKind == JsonValueKind.Array, "学科网返回结构与配置不一致，请完成联调", 502);
            var candidates = new List<ResearchCandidate>(); var excluded = draft.Items.Where(q => q.Source == "学科网").Select(q => q.SourceId).ToHashSet();
            foreach (var row in rows.EnumerateArray().Take(30)) {
                var id = Text(row, Setting("Fields:Id", "id")); if (id.Length == 0 || excluded.Contains(id)) continue;
                // Match filters against returned metadata as well. Never broaden a filter silently.
                var knowledgeValues = Field(row, Setting("Fields:KnowledgeIds", "kpoint_ids"));
                if (knowledgeValues.ValueKind != JsonValueKind.Array || !points.All(k => knowledgeValues.EnumerateArray().Any(v => v.ToString() == k))) continue;
                if (query.Region.Length > 0 && Text(row, Setting("Fields:RegionId", "area_id")) != Map("Regions", query.Region)) continue;
                if (query.Type.Length > 0 && Text(row, Setting("Fields:TypeId", "type_id")) != Map("Types", query.Type)) continue;
                if (query.Difficulty.Length > 0 && Text(row, Setting("Fields:DifficultyId", "difficulty_id")) != Map("Difficulties", query.Difficulty)) continue;
                var item = new ResearchItem { Stem = Text(row, Setting("Fields:Stem", "stem")), Answer = Text(row, Setting("Fields:Answer", "answer")), Analysis = Text(row, Setting("Fields:Analysis", "explanation")), Source = "学科网", SourceId = id,
                    Knowledge = query.Knowledge, KnowledgeMethod = "学科网知识点", Region = query.Region, Type = query.Type, Difficulty = query.Difficulty, SourceUrl = Text(row, Setting("Fields:SourceUrl", "source_url")) };
                Guard.That(item.Stem.Length > 0, "学科网题干字段为空，请核对接口配置", 502); ResearchStore.ValidateItem(item);
                excluded.Add(id);
                var token = Guid.NewGuid().ToString("N"); cache.Set(token, (draft.Owner, draft.Id, item), new MemoryCacheEntryOptions().SetSize(1).SetAbsoluteExpiration(TimeSpan.FromMinutes(20)));
                candidates.Add(new(token, item));
            }
            return new(candidates.Count > 0 ? "ready" : "empty", candidates.Count > 0 ? "学科网实时结果，请核对答案及适用性" : "未找到满足全部条件的题目，请调整筛选条件；未自动扩大范围", candidates.ToArray(), query.Page,
                Setting("PageParameter").Length > 0 && rows.GetArrayLength() >= 10);
        } catch (HttpRequestException) { throw new ApiException(502, "无法连接学科网，请稍后重试"); }
        catch (TaskCanceledException) when (!ct.IsCancellationRequested) { throw new ApiException(504, "学科网查询超时，请稍后重试"); }
        catch (JsonException) { throw new ApiException(502, "学科网返回内容无法解析"); }
    }
    public ResearchItem Candidate(Guid owner, Guid draft, string id)
    {
        Guard.That(id.Length <= 64, "候选题已失效，请重新搜索", 409);
        Guard.That(cache.TryGetValue<(Guid Owner, Guid Draft, ResearchItem Item)>(id, out var value) && value.Owner == owner && value.Draft == draft, "候选题已失效，请重新搜索", 409);
        return value.Item;
    }
    public void Dispose() => cache.Dispose();
}
