using Microsoft.EntityFrameworkCore;

namespace Siyue;

internal enum PrintAgentQueue { Jobs, Operations }
internal sealed record PrintAgentPoll(PrintAgentQueue Queue);

internal static class PrintAgentDispatch
{
    const string PollKey = "device-poll";

    sealed record PollState(Device Device, bool HasWork, bool HasExpired);

    public static async ValueTask<object?> Authenticate(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        var http = context.HttpContext;
        var authorization = http.Request.Headers.Authorization.ToString();
        Guard.That(authorization.StartsWith("Bearer "), "设备未认证", 401);
        var db = http.RequestServices.GetRequiredService<Data>();
        var hash = Secrets.Hash(authorization[7..]);
        var devices = db.Devices.Where(d => d.TokenHash == hash && d.Enabled && !db.GuangzhouDevices.Any(g => g.Id == d.Id));
        var ct = http.RequestAborted;
        var now = Clock.Now;
        var queue = http.GetEndpoint()?.Metadata.GetMetadata<PrintAgentPoll>()?.Queue;
        var poll = queue switch {
            PrintAgentQueue.Jobs => await devices.AsNoTracking().Select(d => new PollState(d,
                db.PrintJobs.Any(j => j.DeviceId == d.Id && j.State == "queued"),
                db.PrintJobs.Any(j => j.DeviceId == d.Id && (j.State == "claimed" || j.State == "submitting") && j.LeaseUntil < now)))
                .SingleOrDefaultAsync(ct),
            PrintAgentQueue.Operations => await devices.AsNoTracking().Select(d => new PollState(d,
                db.DeviceOperations.Any(o => o.DeviceId == d.Id && o.State == "queued" && o.ExpiresAt > now),
                db.DeviceOperations.Any(o => o.DeviceId == d.Id && (o.State == "queued" || o.State == "running") && o.ExpiresAt < now)))
                .SingleOrDefaultAsync(ct),
            _ => new PollState(await devices.SingleOrDefaultAsync(ct)
                ?? throw new ApiException(401, "设备凭证无效或已吊销"), true, false)
        };
        if (poll == null) throw new ApiException(401, "设备凭证无效或已吊销");
        if (!poll.HasWork && !poll.HasExpired) return Results.NoContent();
        http.Items["device"] = poll.Device;
        http.Items[PollKey] = poll.HasExpired;
        return await next(context);
    }

    public static bool HasExpired(HttpContext http) => http.Items[PollKey] is true;

    public static async Task RecoverLeases(Data db, DateTimeOffset now, Guid? deviceId = null, CancellationToken ct = default)
    {
        var jobs = db.PrintJobs.Where(j => j.LeaseUntil < now);
        if (deviceId is Guid id) jobs = jobs.Where(j => j.DeviceId == id);
        await jobs.Where(j => j.State == "claimed").ExecuteUpdateAsync(update => update
            .SetProperty(j => j.State, "queued").SetProperty(j => j.ClaimToken, (string?)null), ct);
        await jobs.Where(j => j.State == "submitting").ExecuteUpdateAsync(update => update
            .SetProperty(j => j.State, "uncertain").SetProperty(j => j.Detail, "打印助手回执超时，请现场核实"), ct);
    }
}
