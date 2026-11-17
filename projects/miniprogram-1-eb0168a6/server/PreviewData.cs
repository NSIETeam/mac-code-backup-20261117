using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.EntityFrameworkCore;
namespace Siyue;
// Explicit, loopback-only development data. Never called by the production login endpoint.
public static class PreviewData
{
    public static async Task Ensure(Data db, IObjects objects)
    {
        if (await db.Staff.AnyAsync(x => x.OpenId == "local-preview")) return;
        var u = new Staff { Name = "本地体验管理员", Role = "admin", OpenId = "local-preview", Code = "LOCAL-PREVIEW" };
        var campus = new Campus { Name = "体验校区" }; var course = new Course { Name = "数学" }; var cl = new SchoolClass { Name = "初一数学 · 体验班", CampusId = campus.Id };
        var lecture = new Lecture { ClassId = cl.Id, CourseId = course.Id, Sequence = 1, Title = "一元一次方程 · 示例" };
        var h = new Handout { LectureId = lecture.Id, Version = 1, FileName = "示例讲义.docx" }; h.OriginalKey = $"handouts/{h.Id}/original.docx";
        using var stream = new MemoryStream();
        using (var pack = WordprocessingDocument.Create(stream, DocumentFormat.OpenXml.WordprocessingDocumentType.Document, true)) {
            var main = pack.AddMainDocumentPart(); main.Document = new Document(new Body(
                P("例1. 某童装店以100元卖出两套不同的童装，一套赚20%，一套亏20%，总的来说是赚钱还是亏本？"), P("A. 赚钱    B. 亏本    C. 不亏也不赚    D. 无法判断"),
                P("练1. 师傅单独加工一个零件需要4天，徒弟单独加工需要6天。徒弟先加工1天，再两人合作，请列方程求师傅加工的时间。"), P("（1）设师傅一共加工x天。"),
                P("1. 每人种12棵树还剩8棵树苗，每人种14棵树则缺8棵树苗。这个小组有多少人？"), P("2. 两个物流站相距200千米，甲车以40千米/时先出发2小时，乙车以60千米/时随后出发。请分析两车相距20千米时的情况。")));
        }
        stream.Position = 0; await objects.Put(h.OriginalKey, stream, WordDocuments.DocxType);
        db.AddRange(u, campus, course, cl, lecture, h);
        db.Assignments.Add(new Assignment { StaffId = u.Id, ClassId = cl.Id, CourseId = course.Id });
        db.Students.AddRange(new Student { ClassId = cl.Id, Code = "DEMO001", Name = "示例学生甲" }, new Student { ClassId = cl.Id, Code = "DEMO002", Name = "示例学生乙" });
        db.Work.Add(new WorkItem { Kind = "import", EntityId = h.Id }); await db.SaveChangesAsync();
    }
    static Paragraph P(string text) => new(new Run(new Text(text)));
}
