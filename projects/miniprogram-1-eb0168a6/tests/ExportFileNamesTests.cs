using Siyue;
using Xunit;
public class ExportFileNamesTests
{
    [Theory]
    [InlineData("pdf")]
    [InlineData("docx")]
    public void ClassroomNameIncludesStudentLectureAndContent(string format) => Assert.Equal("内测02学生甲个性化错题-第1讲-代数专题—因式分解." + format, ExportFileNames.Build("内测02学生甲", [(1,"代数专题—因式分解")], format));
    [Fact]
    public void MultipleLecturesAreOrderedAndDeduplicated() => Assert.Equal("甲个性化错题-第1、4讲-因式分解、数列.pdf",ExportFileNames.Build("甲",[(4,"数列"),(1,"因式分解"),(4,"数列")],"pdf"));
    [Fact]
    public void UnsafeCharactersAndEmptyMetadataAreHandled() {
        Assert.Equal("甲_乙个性化错题-历史汇编.pdf",ExportFileNames.Build("甲/乙",[],"pdf"));
        Assert.DoesNotContain("/",ExportFileNames.Build("甲",[(2,"函数/图像:性质")],"pdf"));
        Assert.True(System.Text.Encoding.UTF8.GetByteCount(ExportFileNames.Safe(new string('数',200),"pdf")) < 255);
    }
}
