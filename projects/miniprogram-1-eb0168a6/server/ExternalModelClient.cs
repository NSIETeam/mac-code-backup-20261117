using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Text.Json;

namespace Siyue;

public static class ExternalModelClient
{
    public static string NormalizeEndpoint(string endpoint, string protocol)
    {
        endpoint = endpoint.Trim().TrimEnd('/');
        if (endpoint.Length == 0 || protocol != "chat-completions") return endpoint;
        if (Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) && (uri.AbsolutePath == "" || uri.AbsolutePath == "/")) return endpoint + "/v1/chat/completions";
        if (endpoint.EndsWith("/v1", StringComparison.OrdinalIgnoreCase)) return endpoint + "/chat/completions";
        return endpoint;
    }
    public static string Provider(string endpoint) => Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) && uri.Host is "api.moonshot.cn" or "api.moonshot.ai" ? "moonshot" : "custom";
    public static bool IsPublic(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (IPAddress.IsLoopback(address)) return false;
        var b = address.GetAddressBytes();
        if (b.Length == 4) return b[0] is not (0 or 10 or 127) && b[0] < 224 && !(b[0] == 169 && b[1] == 254) && !(b[0] == 172 && b[1] >= 16 && b[1] <= 31) && !(b[0] == 192 && b[1] == 168) && !(b[0] == 100 && b[1] >= 64 && b[1] <= 127);
        return !address.Equals(IPAddress.IPv6Any) && !address.Equals(IPAddress.IPv6None) && !address.IsIPv6LinkLocal && !address.IsIPv6SiteLocal && !address.IsIPv6Multicast && (b[0] & 0xfe) != 0xfc;
    }
    public static SocketsHttpHandler Handler() => new() {
        AllowAutoRedirect = false, UseProxy = false,
        ConnectCallback = async (context, ct) => {
            var addresses = await Dns.GetHostAddressesAsync(context.DnsEndPoint.Host, ct);
            foreach (var address in addresses.Where(IsPublic)) {
                var socket = new Socket(address.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
                try { await socket.ConnectAsync(new IPEndPoint(address, context.DnsEndPoint.Port), ct); return new NetworkStream(socket, ownsSocket: true); }
                catch { socket.Dispose(); if (ct.IsCancellationRequested) throw; }
            }
            throw new HttpRequestException("无法连接外部模型服务，请检查公网 HTTPS 地址");
        }
    };
    public static void AddOptions(Dictionary<string, object> body, IConfiguration config)
    {
        if (config.GetValue("LearningAi:JsonMode", true)) body["response_format"] = new { type = "json_object" };
        var effort = config["LearningAi:ReasoningEffort"] ?? "default";
        if (effort != "default") body["reasoning_effort"] = effort;
    }
    public static async Task<byte[]> Send(IHttpClientFactory clients, IConfiguration config, string endpoint, object? body, CancellationToken ct)
    {
        Guard.That(Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) && uri.Scheme == "https" && !uri.IsLoopback && uri.UserInfo.Length == 0 && uri.Query.Length == 0 && uri.Fragment.Length == 0, "请使用公网 HTTPS 接口地址");
        using var request = new HttpRequestMessage(body == null ? HttpMethod.Get : HttpMethod.Post, endpoint);
        var key = config["LearningAi:ApiKey"];
        if (!string.IsNullOrWhiteSpace(key)) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        if (body != null) request.Content = System.Net.Http.Json.JsonContent.Create(body);
        try {
            using var response = await clients.CreateClient("learning-ai").SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            Guard.That(response.IsSuccessStatusCode, response.StatusCode switch {
                HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden => "模型服务拒绝授权，请检查密钥与账号权限",
                HttpStatusCode.PaymentRequired => "模型账户余额不足，请在服务商后台充值后重试",
                HttpStatusCode.TooManyRequests => "模型服务额度不足或请求过于频繁，请稍后重试",
                HttpStatusCode.NotFound => "接口或模型不存在，请检查地址与模型名称",
                _ => "模型服务请求失败，请检查模型、接口类型和高级选项"
            }, 502);
            await using var stream = await response.Content.ReadAsStreamAsync(ct);
            using var memory = new MemoryStream(); var buffer = new byte[8192]; int count;
            while ((count = await stream.ReadAsync(buffer, ct)) > 0) { Guard.That(memory.Length + count <= 1_048_576, "模型返回内容过大", 502); memory.Write(buffer, 0, count); }
            return memory.ToArray();
        } catch (HttpRequestException) { throw new ApiException(502, "无法连接外部模型服务，请检查地址和网络"); }
    }
    public static JsonElement JsonContent(byte[] bytes)
    {
        try {
            using var outer = JsonDocument.Parse(bytes);
            var choice = outer.RootElement.GetProperty("choices")[0];
            Guard.That(choice.GetProperty("finish_reason").GetString() == "stop", "模型结果不完整，请重试", 422);
            var text = choice.GetProperty("message").GetProperty("content").GetString()!.Trim();
            if (text.StartsWith("```json\n") && text.EndsWith("```")) text = text[8..^3].Trim();
            using var payload = JsonDocument.Parse(text); return payload.RootElement.Clone();
        } catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or IndexOutOfRangeException or NullReferenceException) { throw new ApiException(502, "模型未返回可识别的结构化结果"); }
    }
}
