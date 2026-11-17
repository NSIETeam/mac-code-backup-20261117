using System.Net;
using System.Net.Sockets;

namespace Siyue.Printing;

public static class FailureMessages
{
    public static string Connection(Exception error)
    {
        if (error is OperationCanceledException or TimeoutException)
            return "连接服务器超时，助手会自动重试；请检查本机网络";
        if (error is HttpRequestException http)
        {
            if (http.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
                return "设备授权已失效，请管理员在小程序中重新连接";
            if ((int?)http.StatusCode is >= 500)
                return "服务器暂时不可用，助手会自动重试";
            if (Find<SocketException>(http) is { SocketErrorCode: SocketError.HostNotFound or SocketError.NoData })
                return "无法解析服务器地址，请检查 DNS 或网络设置";
            if (Find<SocketException>(http) is { SocketErrorCode: SocketError.NetworkUnreachable or SocketError.HostUnreachable or SocketError.ConnectionRefused })
                return "无法连接服务器，请检查网线、Wi-Fi 或防火墙";
            return "网络连接中断，助手会自动重试";
        }
        return "运行异常，助手会自动重试；如持续出现请生成诊断报告";
    }

    static T? Find<T>(Exception? error) where T : Exception
    {
        while (error != null) { if (error is T match) return match; error = error.InnerException; }
        return null;
    }
}
