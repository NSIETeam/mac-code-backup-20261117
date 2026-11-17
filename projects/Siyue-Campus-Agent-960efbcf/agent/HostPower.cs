using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Siyue.Agent;

sealed class HostPower
{
    const int SystemRequired = 1;
    int resumeCount;

    public void RefreshAfterResume() => Interlocked.Increment(ref resumeCount);

    public async Task Run(CancellationToken cancellation)
    {
        SafeFileHandle? request = null;
        var observedResume = -1;
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(15));
        try
        {
            do
            {
                var resume = Volatile.Read(ref resumeCount);
                // Some desktops and VMs report AC status as unknown. An explicit
                // switch to battery releases the request.
                var keepAwake = !GetSystemPowerStatus(out var power) || power.AcLineStatus != 0;
                if (!keepAwake || observedResume != resume)
                {
                    Release(ref request);
                    observedResume = resume;
                }
                if (keepAwake && request is null)
                    request = PreventIdleSleep();
            } while (await timer.WaitForNextTickAsync(cancellation));
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        finally
        {
            Release(ref request);
        }
    }

    static SafeFileHandle? PreventIdleSleep()
    {
        var reason = new ReasonContext
        {
            Flags = 1, // POWER_REQUEST_CONTEXT_SIMPLE_STRING
            Reason = Marshal.StringToHGlobalUni("Siyue Campus Agent: keep print host available")
        };
        SafeFileHandle request;
        try { request = PowerCreateRequest(ref reason); }
        finally { Marshal.FreeHGlobal(reason.Reason); }
        if (!request.IsInvalid && PowerSetRequest(request, SystemRequired))
            return request;
        request.Dispose();
        return null;
    }

    static void Release(ref SafeFileHandle? request)
    {
        if (request is null) return;
        PowerClearRequest(request, SystemRequired);
        request.Dispose();
        request = null;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct ReasonContext
    {
        public uint Version;
        public uint Flags;
        public nint Reason;
        // Padding for the larger Detailed member of the native union.
        uint reasonId, stringCount;
        nint reasonStrings;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct SystemPowerStatus
    {
        public byte AcLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        public uint BatteryLifeTime, BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    static extern bool GetSystemPowerStatus(out SystemPowerStatus status);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    static extern SafeFileHandle PowerCreateRequest(ref ReasonContext context);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    static extern bool PowerSetRequest(SafeFileHandle request, int requestType);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    static extern bool PowerClearRequest(SafeFileHandle request, int requestType);
}
