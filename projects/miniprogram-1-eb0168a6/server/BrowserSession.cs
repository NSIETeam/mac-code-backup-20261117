using Microsoft.EntityFrameworkCore;

namespace Siyue;

internal static class BrowserSession
{
    const string Cookie = "__Host-siyue_web_session";
    const string LegacyCookie = "siyue_print_session";
    const string CollaborationCookie = "siyue_collaboration";

    internal static bool IsRequest(HttpContext ctx)
    {
        var request = ctx.Request;
        return request.Headers["X-Siyue-Web-Session"] == "1"
            && request.Headers["Sec-Fetch-Site"].ToString() is "" or "same-origin"
            && Uri.TryCreate(request.Headers.Origin, UriKind.Absolute, out var origin)
            && (origin.Scheme == "https" || (origin.IsLoopback && origin.Scheme == "http"))
            && string.Equals(origin.Authority, request.Host.Value, StringComparison.OrdinalIgnoreCase);
    }

    internal static void Issue(HttpContext ctx, string token, DateTimeOffset expiresAt, bool clearLegacy = false)
    {
        ctx.Response.Headers.CacheControl = "no-store";
        var options = CookieSettings();
        options.Expires = expiresAt;
        options.MaxAge = expiresAt - Clock.Now;
        ctx.Response.Cookies.Append(Cookie, token, options);
        if (clearLegacy)
        {
            ctx.Response.Cookies.Delete(LegacyCookie, CookieSettings());
            ctx.Response.Cookies.Delete(CollaborationCookie, CookieSettings());
        }
    }

    internal static async Task<IResult> Restore(HttpContext ctx, Data db)
    {
        ctx.Response.Headers.CacheControl = "no-store";
        if (!IsRequest(ctx)) return Results.StatusCode(StatusCodes.Status403Forbidden);
        var cookies = ctx.Request.Cookies;
        var shared = cookies[Cookie];
        var token = shared ?? Bearer(ctx) ?? cookies[LegacyCookie];
        if (!Valid(token)) return Results.Unauthorized();
        var hash = Secrets.Hash(token!);
        var now = Clock.Now;
        var expiresAt = await (from session in db.Sessions
                               join staff in db.Staff on session.StaffId equals staff.Id
                               where session.TokenHash == hash && session.ExpiresAt > now && staff.Enabled
                               select (DateTimeOffset?)session.ExpiresAt)
            .SingleOrDefaultAsync(ctx.RequestAborted);
        if (expiresAt == null) return Results.Unauthorized();
        if (shared == null) Issue(ctx, token!, expiresAt.Value);
        return Results.Ok(new { token });
    }

    internal static async Task<IResult> Logout(HttpContext ctx, Data db)
    {
        ctx.Response.Headers.CacheControl = "no-store";
        if (!IsRequest(ctx)) return Results.StatusCode(StatusCodes.Status403Forbidden);
        var cookies = ctx.Request.Cookies;
        var tokens = new[] { cookies[Cookie], cookies[LegacyCookie], Bearer(ctx) };
        var hashes = tokens.Where(Valid).Select(token => Secrets.Hash(token!)).Distinct().ToArray();
        if (hashes.Length > 0)
            await db.Sessions.Where(s => hashes.Contains(s.TokenHash)).ExecuteDeleteAsync(ctx.RequestAborted);
        foreach (var name in new[] { Cookie, LegacyCookie, CollaborationCookie })
            ctx.Response.Cookies.Delete(name, CookieSettings());
        return Results.NoContent();
    }

    static CookieOptions CookieSettings() => new()
    {
        Path = "/",
        HttpOnly = true,
        Secure = true,
        SameSite = SameSiteMode.Lax
    };

    static bool Valid(string? token) => token is { Length: 64 } && token.All(Uri.IsHexDigit);

    static string? Bearer(HttpContext ctx)
    {
        var value = ctx.Request.Headers.Authorization.ToString();
        return value.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) ? value[7..] : null;
    }
}
