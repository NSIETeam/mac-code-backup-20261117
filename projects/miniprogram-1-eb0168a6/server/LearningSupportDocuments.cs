using System.Xml.Linq;
using DocumentFormat.OpenXml.Packaging;
namespace Siyue;
public partial class WordDocuments
{
    public async Task<(string Docx,string Pdf)> SupportPdf(ExportDocument d,string dir,CancellationToken ct)
    {
        var path=Path.Combine(dir,"learning-support.docx");
        using(var doc=WordprocessingDocument.Create(path,DocumentFormat.OpenXml.WordprocessingDocumentType.Document)){
            var main=doc.AddMainDocumentPart();var content=new List<XElement>();
            void Lines(string text){foreach(var line in text.Split('\n'))content.Add(P(line));}
            if(d.Kind=="report"){
                var r=Json.Read<ReportFacts>(d.SnapshotJson);Lines($"学生姓名：{r.StudentName}\n课程：{r.CourseName}\n阶段：{r.Stage}\n时间范围：{(r.From.HasValue?r.From.Value.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd"):"全部历史")} 至 {(r.To.HasValue?r.To.Value.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd"):"生成时")}\n生成日期：{d.CreatedAt.ToOffset(TimeSpan.FromHours(8)):yyyy-MM-dd}");
                Lines($"已记录批改的不同题目：{r.MarkedQuestions} 道\n期间有错误记录的题目：{r.WrongQuestions} 道\n期间复测题目：{r.RetestedQuestions} 道\n期间最后一次复测为正确：{r.LatestRetestCorrect} 道\n期间复测错误记录：{r.FailedRetestEvents} 次");
                Lines("各讲记录");foreach(var line in r.LectureSummaries)Lines(line);
                if(r.KnowledgeSummaries?.Length>0){Lines("已审核知识点");foreach(var line in r.KnowledgeSummaries)Lines(line);}
                Lines("复习安排建议");foreach(var line in r.Suggestions??[])Lines(line);
                Lines("教师反馈与下阶段安排");Lines(string.IsNullOrWhiteSpace(r.TeacherNote)?"请结合课堂表现与后续复测继续观察。":r.TeacherNote);
                Lines("统计说明：仅汇总系统实际登记的数据，未登记不等于正确。修改批改结果不等于复测通过；一次复测正确不代表长期掌握。旧记录无法恢复之前已被覆盖的批改过程。");
                Lines("错题依据");foreach(var line in r.Evidence)Lines(line);
            }else{
                var v=Json.Read<VariantPayload>(d.SnapshotJson);var course=await db.Courses.FindAsync([d.CourseId],ct);
                Lines($"课程：{course?.Name}\n日期：{d.CreatedAt.ToOffset(TimeSpan.FromHours(8)):yyyy-MM-dd}\n学生姓名：{d.StudentName}");
                for(var i=0;i<v.Items.Length;i++){Lines($"变式{i+1}：{v.Items[i].Question}");Lines("解题过程：\n\n\n\n");}
                // Answers and explanations remain in the teacher review page, never in the student worksheet.
            }
            var section=Section(11906); section.Element(W+"pgMar")!.SetAttributeValue(W+"bottom",850); content.Add(section);Write(main,new XElement(W+"document",new XAttribute(XNamespace.Xmlns+"w",W),new XElement(W+"body",content)));
        }
        return(path,await Convert(path,"pdf",dir,ct));
    }
}
