using System.Net;
using System.Text.RegularExpressions;

namespace Siyue;

public static class ResearchMedia
{
    static readonly Regex ImageTag = new(@"<img\b[^>]*>", RegexOptions.IgnoreCase, TimeSpan.FromSeconds(1));
    static readonly Regex Source = new("\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOptions.IgnoreCase, TimeSpan.FromSeconds(1));
    public static string[] Sources(string html) => ImageTag.Matches(html).Select(m => Source.Match(m.Value))
        .Where(m => m.Success).Select(m => WebUtility.HtmlDecode(m.Groups.Cast<Group>().Skip(1).First(g => g.Success).Value)).Distinct().ToArray();
    public static bool Allowed(string value) => Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.Scheme == "https" && uri.Port == 443 &&
        uri.UserInfo.Length == 0 && uri.Fragment.Length == 0 && new[] { "xkw.com", "zxxk.com", "zujuan.com" }.Any(host => uri.Host == host || uri.Host.EndsWith("." + host, StringComparison.OrdinalIgnoreCase));
    public static async Task<(byte[] Bytes,string Type)> Fetch(string url, IHttpClientFactory clients, CancellationToken ct)
    {
        Guard.That(Allowed(url), "题目图片不在学科网允许的资源域名内，请联系管理员核对", 422);
        using var response = await clients.CreateClient("research-xkw").GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        Guard.That(response.IsSuccessStatusCode, "学科网图片暂时无法读取", 502);
        await using var source = await response.Content.ReadAsStreamAsync(ct); using var memory = new MemoryStream(); var buffer = new byte[8192]; int count;
        while ((count = await source.ReadAsync(buffer, ct)) > 0) { Guard.That(memory.Length + count <= 8 * 1024 * 1024, "题目图片过大", 502); memory.Write(buffer, 0, count); }
        var bytes = memory.ToArray();
        var type = bytes.Length > 8 && bytes.AsSpan(0,8).SequenceEqual(new byte[]{137,80,78,71,13,10,26,10}) ? "image/png" : bytes.Length > 3 && bytes[0] == 255 && bytes[1] == 216 && bytes[2] == 255 ? "image/jpeg" : "";
        Guard.That(type.Length > 0, "题目图片格式暂不支持，请联系管理员核对", 422); return (bytes,type);
    }
    public static string SafeHtml(string html, IReadOnlyDictionary<string,string> images)
    {
        // Emit only allowlisted tags, and no upstream attributes except a server-materialized image.
        var allowed = new HashSet<string>("p br div span b strong i em u sub sup ul ol li table tbody thead tr td th".Split(' '));
        html = Regex.Replace(html, @"<(script|style|iframe|object)\b[^>]*>.*?</\1\s*>", "", RegexOptions.IgnoreCase | RegexOptions.Singleline, TimeSpan.FromSeconds(1));
        return Regex.Replace(html, @"<[^>]*>", m => {
            var tag = Regex.Match(m.Value, @"^<\s*(/?)\s*([a-zA-Z0-9]+)"); if (!tag.Success) return "";
            var name = tag.Groups[2].Value.ToLowerInvariant();
            if (name == "img") {
                var source = Sources(m.Value).FirstOrDefault();
                Guard.That(source != null && images.ContainsKey(source), "题目图片缺失，已停止导出以避免丢失图文", 422);
                return "<img style=\"max-width:100%;\" src=\"" + WebUtility.HtmlEncode(new Uri(images[source!]).AbsoluteUri) + "\">";
            }
            return allowed.Contains(name) ? "<" + tag.Groups[1].Value + name + ">" : "";
        }, RegexOptions.Singleline, TimeSpan.FromSeconds(1));
    }
}
