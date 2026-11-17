using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Xunit;

public class MiniappFeaturesIsolationTests
{
    sealed class FeatureFactory : Factory
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.UseSetting("MiniappFeatures:Dedicated", "true");
        }
    }

    [Fact]
    public async Task Dedicated_service_exposes_only_feature_routes_and_health()
    {
        await using var factory = new FeatureFactory();
        using var client = factory.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/health")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/student-follow-up/classes")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/grade-calendar")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync("/api/outreach/diagnosis", new {})).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.PostAsJsonAsync("/api/print-jobs", new {})).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync("/api/auth/status")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync("/question-bank/")).StatusCode);
    }
}
