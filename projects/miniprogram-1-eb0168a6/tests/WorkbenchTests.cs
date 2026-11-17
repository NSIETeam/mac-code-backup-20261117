using System.Net;
using Xunit;

public class WorkbenchTests
{
    [Fact]
    public async Task PublicShellDoesNotExposeBusinessDataOrDisableApiAuthentication()
    {
        await using var factory = new Factory();
        using var client = factory.CreateClient();
        var response = await client.GetAsync("/workbench/");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var html = await response.Content.ReadAsStringAsync();
        Assert.Contains("思跃 · 工作台", html);
        Assert.Contains("script-src 'self'", response.Headers.GetValues("Content-Security-Policy").Single());
        Assert.Equal("nosniff", response.Headers.GetValues("X-Content-Type-Options").Single());
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/workbench/app.js")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/workspace")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/print-jobs")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync("/workbench/.env")).StatusCode);
    }
}
