using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
namespace Siyue;
public partial class WordDocuments
{
    sealed record PaperText(string Id,string Stem,string Material,string Answer,string Analysis,string Svg);
    public async Task<string> BankPaperDocx(BankPaperDraft draft,BankPaperStudent[] papers,bool teacher,Dictionary<string,string> images,string dir,CancellationToken ct){
        var source=Path.Combine(dir,"questions.json");var normalized=Path.Combine(dir,"display.json");var all=papers.SelectMany(p=>p.QuestionIds).Distinct().Select(id=>draft.Questions[id]).ToArray();
        await File.WriteAllTextAsync(source,JsonSerializer.Serialize(all,new JsonSerializerOptions(JsonSerializerDefaults.Web)),ct);
        await Run(config["Documents:Node"]??"node",[Path.Combine(AppContext.BaseDirectory,"question_bank_paper_text.mjs"),source,normalized],ct);
        var text=JsonSerializer.Deserialize<PaperText[]>(await File.ReadAllTextAsync(normalized,ct),new JsonSerializerOptions(JsonSerializerDefaults.Web))!.ToDictionary(t=>t.Id);
        var path=Path.Combine(dir,"paper.docx");var temporary=Path.Combine(dir,"paper.partial.docx");
        using(var doc=WordprocessingDocument.Create(temporary,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)){
            var main=doc.AddMainDocumentPart();var body=new XElement(W+"body");uint imageId=0;
            void Lines(string value,string size="22",bool keep=false){foreach(var line in value.Replace("\r","").Split('\n')){var p=P(line,size,line:360,after:90);if(keep)p.Element(W+"pPr")!.Add(new XElement(W+"keepNext"));body.Add(p);}}
            void Picture(byte[] bytes,string type,int width,int height){var part=main.AddImagePart(type);using(var ms=new MemoryStream(bytes))part.FeedData(ms);var maxWidth=type=="image/svg+xml"?3_400_000d:5_800_000d;var scale=Math.Min(maxWidth/width,6_000_000d/height);body.Add(XElement.Parse(new Paragraph(new Run(PdfDrawing(main.GetIdOfPart(part),++imageId,(long)(width*scale),(long)(height*scale),false))).OuterXml));}
            for(var n=0;n<papers.Length;n++){
                if(n>0)body.Add(Break());var student=papers[n];body.Add(P(draft.Title,"30",align:"center",line:480,after:150));body.Add(P((teacher?"教师答案卷":"学生卷")+" · "+draft.Stage+" · "+draft.Subject,"22",align:"center",after:140));
                var number=Array.FindIndex(draft.Papers,p=>p.StudentId==student.StudentId)+1;
                Lines($"卷号：{number:D2}    班级：{draft.ClassName}    共 {student.QuestionIds.Length} 题");
                if(!teacher)Lines("用时：________    得分：________");
                if(student.QuestionIds.Length<draft.RequestedCount)Lines($"本卷依据可用错题匹配 {student.QuestionIds.Length} 题。","19");
                for(var i=0;i<student.QuestionIds.Length;i++){
                    var q=draft.Questions[student.QuestionIds[i]];var t=text[q.Id];
                    body.Add(Spacer(130));Lines($"第 {i+1} 题 · {q.Type}","23",true);
                    if(t.Material.Length>0)Lines(t.Material);Lines(t.Stem);
                    if(t.Svg.Length>0)Picture(Encoding.UTF8.GetBytes(t.Svg),"image/svg+xml",720,540);
                    foreach(var asset in q.Images){var bytes=await File.ReadAllBytesAsync(images[asset],ct);(int Width,int Height,bool Png) size;try{size=ImageSize(bytes);}catch(ApiException){var png=Path.Combine(dir,"image-"+imageId+".png");await Run(config["Documents:ImagePython"]??"python3",[Path.Combine(AppContext.BaseDirectory,"question_bank_media.py"),images[asset],png],ct);bytes=await File.ReadAllBytesAsync(png,ct);size=ImageSize(bytes);}Picture(bytes,size.Png?"image/png":"image/jpeg",size.Width,size.Height);}
                    if(teacher){Lines("参考答案："+t.Answer);if(t.Analysis.Length>0)Lines("解析："+t.Analysis);Lines("来源："+q.Source+" · "+q.License,"18");if(q.SourceUrl.Length>0)Lines(q.SourceUrl,"18");}
                    else {var lines=q.Type is "解答题" or "综合题"?7:q.Type=="填空题"?3:2;for(var j=0;j<lines;j++)body.Add(Spacer(360));}
                }
            }
            var section=Section(11906);section.Element(W+"pgMar")!.SetAttributeValue(W+"bottom",850);body.Add(section);Write(main,new XElement(W+"document",new XAttribute(XNamespace.Xmlns+"w",W),body));
        }
        File.Move(temporary,path,true);return path;
    }
}
