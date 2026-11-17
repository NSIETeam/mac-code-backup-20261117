using Microsoft.Win32;
using System.Collections.Concurrent;
using System.Net.Sockets;
using Siyue.Printing;
namespace Siyue.Agent;

static class PrinterConnectionMonitor
{
    record Reading(bool? Reachable, DateTimeOffset? At, Task? Pending);
    static readonly ConcurrentDictionary<string, Reading> readings = new(StringComparer.OrdinalIgnoreCase);
    static readonly SemaphoreSlim limit = new(4);
    public static ProbeResult Enrich(ProbeResult probe)
    {
        return probe with { Printers = probe.Printers.Select(p => {
            // Only probe the address and port actually configured by Windows, not a subnet scan.
            using var key = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Print\Monitors\Standard TCP/IP Port\Ports\" + p.Port);
            var host = key?.GetValue("HostName") as string;
            if (string.IsNullOrWhiteSpace(host)) host = key?.GetValue("IPAddress") as string;
            var protocol = Convert.ToInt32(key?.GetValue("Protocol") ?? 0);
            var port = protocol == 2 ? 515 : Convert.ToInt32(key?.GetValue("PortNumber") ?? 0);
            if (string.IsNullOrWhiteSpace(host) || host.Length > 253 || port is < 1 or > 65535) return p;
            var id = host + ":" + port; var old = readings.GetOrAdd(id, _ => new(null, null, null));
            if (old.Pending is not { IsCompleted: false } && (old.At == null || DateTimeOffset.UtcNow - old.At > TimeSpan.FromSeconds(15))) {
                var target = host;
                var task = Task.Run(async () => {
                    await limit.WaitAsync(); bool reachable;
                    try { using var client = new TcpClient(); using var timeout = new CancellationTokenSource(2000); await client.ConnectAsync(target, port, timeout.Token); reachable = true; }
                    catch { reachable = false; }
                    finally { limit.Release(); }
                    readings[id] = new(reachable, DateTimeOffset.UtcNow, null);
                });
                readings.TryUpdate(id, old with { Pending = task }, old);
            }
            return p with { DeviceReachable = old.Reachable, DeviceCheckedAt = old.At };
        }).ToArray() };
    }
}
