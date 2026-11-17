using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
namespace Siyue;
public partial class WordDocuments
{
    public static byte[] EnglishLesson(string unit,EnglishTeachingTools.Lesson lesson,string audience="teacher")
    {
        Guard.That(audience is "student" or "teacher","请选择学生版或教师版");
        using var stream=new MemoryStream();
        using(var document=WordprocessingDocument.Create(stream,WordprocessingDocumentType.Document)) {
            var main=document.AddMainDocumentPart();var body=new Body();
            var styles=main.AddNewPart<StyleDefinitionsPart>();
            styles.Styles=new Styles(new Style(new StyleName{Val="Normal"},new StyleRunProperties(new RunFonts{Ascii="Calibri",EastAsia="Microsoft YaHei"},new FontSize{Val="22"})){Type=StyleValues.Paragraph,StyleId="Normal",Default=true},
                new Style(new StyleName{Val="Title"},new BasedOn{Val="Normal"},new StyleRunProperties(new Bold(),new FontSize{Val="32"})){Type=StyleValues.Paragraph,StyleId="Title"},
                new Style(new StyleName{Val="Heading 1"},new BasedOn{Val="Normal"},new StyleParagraphProperties(new KeepNext()),new StyleRunProperties(new Bold(),new FontSize{Val="26"})){Type=StyleValues.Paragraph,StyleId="Heading1"});
            void Line(string text,string style="Normal",bool next=false,bool newPage=false) {
                foreach(var line in text.Replace("\r","").Split('\n')) {
                    var props=new ParagraphProperties(new ParagraphStyleId{Val=style});
                    if(next)props.Append(new KeepNext());props.Append(new KeepLines());if(newPage){props.Append(new PageBreakBefore());newPage=false;}
                    props.Append(new SpacingBetweenLines{After="100",Line="280",LineRule=LineSpacingRuleValues.Auto});
                    body.Append(new Paragraph(props,new Run(new Text(line){Space=SpaceProcessingModeValues.Preserve})));
                }
            }
            void Exercises(string level,EnglishTeachingTools.Exercise[] questions) {
                Line(level,"Heading1");
                for(var i=0;i<questions.Length;i++) {
                    Line($"{i+1}. {questions[i].Question}",next:true);
                    Line("________________________________________________________________");
                    Line("________________________________________________________________");
                }
            }
            Line(unit,"Title");Line("七年级英语学生讲义");Line("姓名：________________  班级：________________");
            Line(lesson.Handout);Exercises("基础练习",lesson.Basic);Exercises("提升练习",lesson.Advanced);
            if(audience=="teacher") {
                Line("教师参考板书与答案","Heading1",newPage:true);Line("本部分仅供教师备课，请勿随学生讲义发放。");
                Line("板书","Heading1");Line(lesson.Board);
                foreach(var (title,items) in new[]{("基础练习答案",lesson.Basic),("提升练习答案",lesson.Advanced)}) {
                    Line(title,"Heading1");for(var i=0;i<items.Length;i++){Line($"{i+1}. {items[i].Answer}",next:true);Line("解析："+items[i].Explanation);}
                }
            }
            body.Append(new SectionProperties(new PageSize{Width=11906,Height=16838},new PageMargin{Top=1134,Bottom=1134,Left=1134,Right=1134,Header=500,Footer=500,Gutter=0}));
            main.Document=new Document(body);main.Document.Save();
        }
        return stream.ToArray();
    }
}
