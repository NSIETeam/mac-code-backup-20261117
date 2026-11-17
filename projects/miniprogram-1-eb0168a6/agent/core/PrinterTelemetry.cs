namespace Siyue.Printing;
public record PrinterTelemetry(string QueueName, bool Duplex, string Status, uint RawStatus, int JobCount, string Detail, string Driver, string Port, bool? DeviceReachable = null, DateTimeOffset? DeviceCheckedAt = null);
public record ProbeResult(PrinterTelemetry[] Printers, bool Complete, string? Error = null);
public static class SpoolerStatus
{
    public static (string Status, string Detail) Describe(uint flags)
    {
        var problems = new List<string>();
        foreach (var (bit, label) in new (uint, string)[] { (1,"已暂停"),(2,"打印错误"),(4,"正在删除"),(8,"卡纸"),(16,"缺纸"),(64,"纸张问题"),(128,"脱机"),(2048,"出纸口已满"),(4096,"不可用"),(16384,"正在处理"),(1024,"正在打印"),(131072,"墨粉不足"),(262144,"墨粉用尽"),(1048576,"需要人工处理"),(2097152,"内存不足"),(4194304,"机盖打开"),(8388608,"服务器不可用") })
            if ((flags & bit) != 0) problems.Add(label);
        var state = (flags & (128u | 4096u | 8388608u)) != 0 ? "offline" : (flags & 1) != 0 ? "paused" :
            (flags & (2u | 4u | 8u | 16u | 64u | 2048u | 262144u | 1048576u | 2097152u | 4194304u)) != 0 ? "error" :
            (flags & (1024u | 16384u)) != 0 ? "busy" : (flags & 131072u) != 0 ? "warning" : flags == 0 ? "ready" : "unknown";
        return (state, problems.Count > 0 ? string.Join("、", problems) : flags == 0 ? "Windows 驱动未报告异常" : "驱动状态：" + flags);
    }
}
