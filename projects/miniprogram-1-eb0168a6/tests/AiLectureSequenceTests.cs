using System.Text.Json;
using Siyue;
using Xunit;
public class AiLectureSequenceTests
{
    [Theory][InlineData("\"第一讲\"",1)][InlineData("\"第１６讲\"",16)][InlineData("\"12\"",12)][InlineData("2",2)]
    public void ExactPrintedLectureNumbersAreNormalized(string raw,int expected) {
        var page=Json.Read<AiHandoutPage>("{\"regions\":[{\"kind\":\"lecture\",\"label\":\"\",\"bounds\":[0,0,1,0.1],\"confidence\":0.99,\"lectureSequence\":"+raw+",\"lectureTitle\":\"方程\"}],\"uncertain\":false}");
        Assert.Equal(expected,page.Regions[0].LectureSequence);
    }
    [Theory][InlineData("\"第一讲和第二讲\"")][InlineData("1.5")][InlineData("\"未知\"")][InlineData("true")]
    public void AmbiguousSequenceCannotBecomeAConfirmedLecture(string raw) {
        Assert.Throws<JsonException>(()=>Json.Read<AiHandoutRegion>("{\"kind\":\"lecture\",\"bounds\":[0,0,1,1],\"confidence\":0.99,\"lectureSequence\":"+raw+"}"));
    }
}
