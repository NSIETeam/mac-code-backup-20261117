using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
namespace Siyue.Printing;

public sealed class ServerEndpoint
{
    public Uri BaseUri { get; }
    public IPAddress? ConnectAddress { get; }
    public ServerEndpoint(string address, string? connectIp = null)
    {
        if (!Uri.TryCreate(address.Trim().TrimEnd('/') + "/", UriKind.Absolute, out var uri) ||
            !(uri.Scheme == "https" || uri.Scheme == "http" && uri.IsLoopback) ||
            uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0)
            throw new ArgumentException("请填写 HTTPS 服务器地址，可包含服务路径。本地调试允许 HTTP localhost。");
        BaseUri = uri;
        if (!string.IsNullOrWhiteSpace(connectIp)) {
            if (!IPAddress.TryParse(connectIp.Trim(), out var ip)) throw new ArgumentException("连接 IP 格式无效");
            if (uri.Scheme == "http" && !IPAddress.IsLoopback(ip)) throw new ArgumentException("公网连接必须使用 HTTPS");
            ConnectAddress = ip;
        }
    }
    public string Scope(Guid deviceId) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(BaseUri.AbsoluteUri + deviceId)))[..24];
    public bool AllowsFile(Uri uri) => uri.IsAbsoluteUri && uri.UserInfo.Length == 0 && uri.Scheme == BaseUri.Scheme && uri.Host == BaseUri.Host && uri.Port == BaseUri.Port &&
        uri.AbsolutePath.StartsWith(new Uri(BaseUri, "files/").AbsolutePath, StringComparison.Ordinal);
    public HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler { AllowAutoRedirect = false, ConnectTimeout = TimeSpan.FromSeconds(10), PooledConnectionLifetime = TimeSpan.FromMinutes(2) };
        if (ConnectAddress != null) {
            handler.UseProxy = false;
            handler.ConnectCallback = async (context, ct) => {
                if (!context.DnsEndPoint.Host.Equals(BaseUri.Host, StringComparison.OrdinalIgnoreCase)) throw new HttpRequestException("连接目标不匹配");
                var socket = new Socket(ConnectAddress.AddressFamily, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
                try { await socket.ConnectAsync(new IPEndPoint(ConnectAddress, context.DnsEndPoint.Port), ct); return new NetworkStream(socket, ownsSocket: true); }
                catch { socket.Dispose(); throw; }
            };
        }
        // TLS still validates the configured domain and certificate, including when a connection IP is specified.
        return new HttpClient(handler) { BaseAddress = BaseUri, Timeout = TimeSpan.FromSeconds(25) };
    }
}
