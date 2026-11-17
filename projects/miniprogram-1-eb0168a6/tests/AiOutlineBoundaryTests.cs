using Siyue;
using Xunit;
public class AiOutlineBoundaryTests
{
    [Fact] public void MovesIntoActualBlankGapInsteadOfCuttingHeaderRule() {
        var cut=AiBookOutline.SafeCut(83.0283203,842,[[80.375,81.125]]);
        Assert.NotNull(cut);Assert.True(cut>81.125&&cut<83.0283203);
    }
    [Fact] public void DoesNotDeclareACrossingQuestionDiagramSafe() => Assert.Null(AiBookOutline.SafeCut(83,842,[[50,100]]));
    [Fact] public void KeepsAlreadySafeBoundary() => Assert.Equal(81,AiBookOutline.SafeCut(83,842,[[40,60]]));
}
