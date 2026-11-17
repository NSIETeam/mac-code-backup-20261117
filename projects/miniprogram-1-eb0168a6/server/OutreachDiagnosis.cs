using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

public record OutreachDiagnosisInput(string Subject, string Stage, string[] ConfirmedWrong);

public static class OutreachDiagnosis
{
    public static void MapOutreachDiagnosis(this WebApplication app)
    {
        app.MapPost("/api/outreach/diagnosis/photo", async (HttpRequest request, Access access, QuestionBankIntake intake, CancellationToken cancellation) =>
        {
            _ = access.User;
            Guard.That(request.HasFormContentType && request.ContentLength is > 0 and <= 9 * 1024 * 1024, "请上传一张 8MB 以内的照片");
            var form = await request.ReadFormAsync(cancellation);
            var photo = form.Files.GetFile("file");
            Guard.That(form.Files.Count == 1 && photo != null, "请上传一张试卷照片");
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(50));
            string text;
            try { text = await intake.ReadPrivatePhoto(photo!, timeout.Token); }
            catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
            {
                throw new ApiException(422, "照片识别超时，请裁剪后重试");
            }
            var questions = SplitQuestions(text);
            Guard.That(questions.Length > 0, "没有识别到题号，请拍清题目或手动输入错题", 422);
            return Results.Ok(questions);
        });

        app.MapPost("/api/outreach/diagnosis", async (OutreachDiagnosisInput input, Access access, Data db, IQuestionBank bank) =>
        {
            var actor = access.User;
            Guard.That(LocalQuestionBank.Subjects.Contains(input.Subject) && LocalQuestionBank.Stages.Contains(input.Stage), "请选择学科和学段");
            Guard.That(input.ConfirmedWrong is { Length: > 0 and <= 20 }
                && input.ConfirmedWrong.All(s => s is { Length: >= 4 and <= 2000 }), "请确认 1–20 道错题，并填写完整题干");

            var topics = bank.Knowledge(input.Subject, "");
            var matched = input.ConfirmedWrong.Select((text, index) => new
            {
                number = index + 1,
                topics = topics.Where(topic => new[] { topic.Name }.Concat(topic.Aliases).Concat(topic.Name.Split('与'))
                    .Any(alias => alias.Length >= 2 && text.Contains(alias, StringComparison.OrdinalIgnoreCase)))
                    .Select(topic => topic.Name).Distinct().Take(5).ToArray()
            }).ToArray();
            var candidates = await (from assignment in db.Assignments
                                    join course in db.Courses on assignment.CourseId equals course.Id
                                    join schoolClass in db.Classes on assignment.ClassId equals schoolClass.Id
                                    join teacher in db.Staff on assignment.StaffId equals teacher.Id
                                    where teacher.Enabled && teacher.Role == "teacher" && course.Name.Contains(input.Subject)
                                        && (actor.Role == "admin" || db.Assignments.Any(a => a.ClassId == schoolClass.Id && a.StaffId == actor.Id))
                                    select new { teacher.Id, teacher.Name, Course = course.Name, Class = schoolClass.Name })
                .Distinct().Take(300).ToArrayAsync();
            var teachers = candidates.Where(c => (AcademicTraining.SuggestStage(c.Class) is { Length: > 0 } stage
                    ? stage : AcademicTraining.SuggestStage(c.Course)) == input.Stage)
                .GroupBy(c => c.Id).Select(g => new { name = g.First().Name, courses = g.Select(c => c.Course).Distinct().Order().ToArray() })
                .OrderBy(x => x.name).Take(10).ToArray();
            return Results.Ok(new { matched, teachers });
        });
    }

    internal static string[] SplitQuestions(string text)
    {
        var matches = Regex.Matches(text[..Math.Min(text.Length, 40000)],
            @"(?m)^\s*(?:第\s*)?\d{1,3}\s*(?:题\s*[:：]?|[.．、)）])\s*");
        if (matches.Count == 0) return [];
        var result = new List<string>();
        for (var i = 0; i < Math.Min(matches.Count, 40); i++)
        {
            var start = matches[i].Index + matches[i].Length;
            var end = i + 1 < matches.Count ? matches[i + 1].Index : Math.Min(text.Length, 40000);
            var question = text[start..end].Trim();
            if (question.Length >= 4) result.Add(question[..Math.Min(question.Length, 2000)]);
        }
        return result.ToArray();
    }
}
