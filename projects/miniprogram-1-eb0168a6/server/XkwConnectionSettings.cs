using Microsoft.AspNetCore.DataProtection;
using System.Text.RegularExpressions;

namespace Siyue;

public record XkwConnectionInput(int Revision, string AppId, string? Secret, bool Enabled,
    Dictionary<string,string> Profile, bool ClearSecret = false);
public record XkwConnectionValue(int Revision, string AppId, string Secret, bool Enabled,
    Dictionary<string,string> Profile);

// Shared by API/worker processes. Persist only protected content; never return credentials.
public sealed class XkwConnectionSettings(ResearchStore store, IConfiguration environment, IDataProtectionProvider protection)
{
    string PathName => Path.Combine(store.Root, "xkw-connection.protected.json");
    IDataProtector Protector => protection.CreateProtector("Siyue.XkwConnection.v1");
    XkwConnectionValue Read()
    {
        if (File.Exists(PathName)) return Json.Read<XkwConnectionValue>(Protector.Unprotect(Json.Read<string>(File.ReadAllText(PathName))));
        var section = environment.GetSection("Research:Xkw");
        var profile = section.AsEnumerable().Where(x => x.Value != null && x.Key != section.Path)
            .ToDictionary(x => x.Key[(section.Path.Length + 1)..], x => x.Value!);
        foreach (var key in new[] { "AppId", "Secret", "ContractVerified" }) profile.Remove(key);
        return new(0, section["AppId"] ?? "", section["Secret"] ?? "", section.GetValue<bool>("ContractVerified"), profile);
    }
    public object PublicView()
    {
        var value = Read();
        return new { value.Revision, value.AppId, value.Enabled, value.Profile, hasSecret = value.Secret.Length > 0,
            portal = "https://open.xkw.com", message = "登录开放平台获取已开通题库权限的应用凭据；普通会员登录不会自动授权本系统。" };
    }
    public IConfiguration Resolve()
    {
        var value = Read();
        var profile = value.Profile.ToDictionary(x => "Research:Xkw:" + x.Key, x => (string?)x.Value);
        profile["Research:Xkw:AppId"] = value.AppId; profile["Research:Xkw:Secret"] = value.Secret;
        profile["Research:Xkw:ContractVerified"] = value.Enabled.ToString();
        // Do not overlay stale maps from environment after an administrator removes them.
        return new ConfigurationBuilder().AddInMemoryCollection(profile).Build();
    }
    public async Task Save(XkwConnectionInput input, CancellationToken ct)
    {
        Guard.That(input.AppId is {Length: > 0 and <= 200} && input.Secret is null or {Length: <= 2000}, "应用凭据格式无效");
        Guard.That(input.Profile is {Count: <= 5000} && input.Profile.All(x =>
            x.Value != null && x.Value.Length <= 300 && Regex.IsMatch(x.Key,
                @"\A(?:SearchPath|ItemsPath|SuccessCode|RegionParameter|TypeParameter|DifficultyParameter|PageParameter|Fields:[A-Za-z]+|(?:Courses|Knowledge|Regions|Types|Difficulties):[^:\r\n]{1,140})\z")), "接口映射格式无效");
        await using var gate = await ResearchStore.Lock(store.Root, ".xkw-settings.lock", ct);
        var old = Read(); Guard.That(old.Revision == input.Revision, "接入配置已变化，请刷新后再保存", 409);
        var secret = input.ClearSecret ? "" : !string.IsNullOrWhiteSpace(input.Secret) ? input.Secret.Trim() : input.AppId == old.AppId ? old.Secret : "";
        Guard.That(!input.Enabled || secret.Length > 0, "启用前请填写该应用的 Secret");
        var value = new XkwConnectionValue(old.Revision + 1, input.AppId.Trim(), secret, input.Enabled, input.Profile);
        await ResearchStore.Write(PathName, Protector.Protect(Json.Write(value)), ct);
    }
    public static void MapEndpoints(WebApplication app)
    {
        app.MapGet("/api/admin/xkw", (Access access, XkwConnectionSettings settings) => { access.Admin(); return settings.PublicView(); });
        app.MapPut("/api/admin/xkw", async (XkwConnectionInput input, Access access, XkwConnectionSettings settings, CancellationToken ct) => {
            access.Admin(); await settings.Save(input, ct); return settings.PublicView();
        });
        app.MapPost("/api/admin/xkw/test", async (XkwProbe input, Access access, XkwConnectionSettings settings, IResearchQuestions provider, CancellationToken ct) => {
            access.Admin(); Guard.That(LocalQuestionBank.Subjects.Contains(input.Subject) && LocalQuestionBank.Stages.Contains(input.Stage), "请选择学科与学段");
            var draft = new ResearchDraft { Owner = access.User.Id, Subject = input.Subject, Stage = input.Stage };
            var result = await provider.Search(draft, new(Guid.Empty, input.Knowledge, input.Region, input.Type), ct);
            return new { ok = true, matched = result.Items.Length, message = result.Items.Length > 0 ? "已收到符合筛选条件的学科网试题" : "接口响应正常，但未找到满足全部条件的试题；请核对映射与条件" };
        });
    }
    public record XkwProbe(string Subject, string Stage, string[] Knowledge, string Region = "", string Type = "");
}
