using Microsoft.EntityFrameworkCore;
using System.Text.RegularExpressions;

namespace Siyue;

public static class ExportFileNames
{
    public static string Build(string student, IEnumerable<(int Sequence, string Title)> lectures, string format)
    {
        var rows = lectures.Distinct().OrderBy(x => x.Sequence).ThenBy(x => x.Title).ToArray();
        var sequence = rows.Length == 0 ? "历史汇编" : "第" + string.Join("、", rows.Select(x => x.Sequence).Distinct()) + "讲";
        var content = string.Join("、", rows.Select(x => x.Title.Trim()).Where(x => x.Length > 0).Distinct());
        return Safe(student + "个性化错题-" + sequence + (content.Length > 0 ? "-" + content : ""), format);
    }

    public static string Safe(string name, string format)
    {
        var clean = Regex.Replace(name, "[\\\\/:*?\"<>|\\x00-\\x1f]", "_").Trim().TrimEnd('.');
        if (clean.Length > 70) clean = clean[..70].TrimEnd();
        return (clean.Length == 0 ? "思跃文档" : clean) + "." + format;
    }

    public static async Task<string> For(ExportDocument document, string format, Data db)
    {
        if (document.Kind != "booklet") return Safe(document.Kind == "file" ? Path.GetFileNameWithoutExtension(document.SourceFileName) : document.StudentName + (document.Kind == "report" ? "学情报告" : "变式训练"), format);
        var ids = Json.Read<QuestionSnapshot[]>(document.SnapshotJson).Select(x => x.HandoutId).Distinct().ToArray();
        var lectures = await (from h in db.Handouts.IgnoreQueryFilters() join l in db.Lectures.IgnoreQueryFilters() on h.LectureId equals l.Id where ids.Contains(h.Id) select new { l.Sequence, l.Title }).Distinct().ToArrayAsync();
        return Build(document.StudentName, lectures.Select(x => (x.Sequence, x.Title)), format);
    }
}
