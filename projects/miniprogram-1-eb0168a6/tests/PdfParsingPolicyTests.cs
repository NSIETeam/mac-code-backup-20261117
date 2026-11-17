using Microsoft.Extensions.Configuration;
using Siyue;
using Xunit;

public class PdfParsingPolicyTests
{
    [Theory]
    [InlineData(null, true)]
    [InlineData("fixed", true)]
    [InlineData("unexpected", true)]
    [InlineData("model-assisted", false)]
    public void OnlyExplicitModelStrategyCanEnableInference(string? strategy, bool fixedExpected)
    {
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> {
            ["Documents:ParsingStrategy"] = strategy,
            ["LearningAi:Enabled"] = "true",
            ["LearningAi:PdfAssistEnabled"] = "true"
        }).Build();
        Assert.Equal(fixedExpected, PdfParsingPolicy.IsFixed(config));
    }

    [Theory]
    [InlineData(null, "named")]
    [InlineData("auto", "named")]
    [InlineData("named", "named")]
    [InlineData("legacy", "legacy")]
    public void FixedImportsUseNamedMarkersWhileExplicitLegacyIsPreserved(string? mode, string expected)
    {
        Assert.Equal(expected, PdfParsingPolicy.RecognitionMode(new ConfigurationBuilder().Build(), mode));
    }
}
