using Microsoft.EntityFrameworkCore;
namespace Siyue;

public sealed record AcademicRecommendationInput(string Stage, string? Subject = null, int LimitPerQuestion = 3,
    Guid? QuestionId = null, string? SupplementalText = null, string[]? Tags = null);
public sealed record AcademicQuestionRecommendations(Guid QuestionId, string Label, string SourceText, string Status, string Message, QuestionMatch[] Matches);

public static class AcademicRecommendations
{
    public static void MapEndpoints(RouteGroupBuilder group)
    {
        group.MapGet("/question-bank", (IQuestionBank bank) => bank.Availability());
        group.MapPost("/sessions/{id:guid}/recommendations/{studentId:guid}", Recommend);
    }
    static async Task<IResult> Recommend(Guid id, Guid studentId, AcademicRecommendationInput input, Data db, Access a, IQuestionBank bank)
    {
        var session = await AcademicPilot.Session(id, db, a);
        await AcademicBeta.Student(id, studentId, db, a);
        var mark = await db.ClassSessionMarks.AsNoTracking().SingleAsync(x => x.ClassSessionId == id && x.StudentId == studentId);
        Guard.That(mark.Reviewed, "请先完成该学生的课堂错题登记", 409);
        Guard.That(LocalQuestionBank.Stages.Contains(input.Stage), "请选择学生的实际学段");
        Guard.That(input.LimitPerQuestion is >= 1 and <= 5, "每道错题推荐数量为1至5道");
        Guard.That(input.SupplementalText is null or { Length: <= 10000 }, "补充题干最多10000字");
        Guard.That(input.Tags is null || (input.Tags.Length <= 10 && input.Tags.All(x => x is { Length: >= 1 and <= 40 })), "最多填写10个知识点，每项1至40字");
        Guard.That(input.QuestionId.HasValue || (string.IsNullOrWhiteSpace(input.SupplementalText) && (input.Tags?.Length ?? 0) == 0), "补充题干或知识点时须指定一道错题");
        var course = await db.Courses.AsNoTracking().SingleAsync(x => x.Id == session.CourseId);
        var courseSubjects = LocalQuestionBank.Subjects.Where(subject => course.Name.Contains(subject, StringComparison.Ordinal)).ToArray();
        var subject = input.Subject?.Trim() ?? (courseSubjects.Length == 1 ? courseSubjects[0] : "");
        Guard.That(LocalQuestionBank.Subjects.Contains(subject), "课程学科不明确，请选择学科");
        Guard.That(courseSubjects.Length != 1 || subject == courseSubjects[0], "所选学科与当前课程不一致");
        var wrongIds = Json.Read<Guid[]>(mark.WrongIdsJson).Distinct().ToArray();
        Guard.That(!input.QuestionId.HasValue || wrongIds.Contains(input.QuestionId.Value), "只能推荐该学生本课次已登记的错题", 409);
        var wrong = await db.Questions.AsNoTracking().Where(q => q.HandoutId == session.HandoutId && wrongIds.Contains(q.Id)).OrderBy(q => q.Order).ThenBy(q => q.Id).ToArrayAsync();
        Guard.That(wrong.Length == wrongIds.Length, "错题来源已变化，请先核对课堂记录", 409);
        Guard.That(input.QuestionId.HasValue || wrong.Length <= 100, "错题超过100道，请按单题生成推荐");
        static string Text(Question q) => string.Join("\n", Json.Read<ContentBlock[]>(q.BlocksJson).Where(b => b.Kind is not ("image" or "drawing") && !string.IsNullOrWhiteSpace(b.Text)).Select(b => b.Text)).Trim();
        var excluded = wrong.Select(q => LocalQuestionBank.Fingerprint(subject, Text(q))).ToHashSet();
        var availability = bank.Availability();
        var rows = new List<AcademicQuestionRecommendations>();
        foreach (var question in wrong.Where(q => !input.QuestionId.HasValue || q.Id == input.QuestionId)) {
            var stem = input.QuestionId.HasValue && !string.IsNullOrWhiteSpace(input.SupplementalText) ? input.SupplementalText.Trim() : Text(question);
            var tags = input.QuestionId.HasValue ? input.Tags ?? [] : [];
            if (stem.Length < 4 && tags.Length == 0) {
                rows.Add(new(question.Id, question.Label, stem, "needs_text", "原题缺少可检索文字，请补充题干或知识点后匹配。", [])); continue;
            }
            var matches = bank.Recommend(subject, input.Stage, stem, tags, input.LimitPerQuestion, excluded);
            foreach (var match in matches) excluded.Add(LocalQuestionBank.Fingerprint(subject, match.Question.Stem));
            rows.Add(new(question.Id, question.Label, stem, matches.Length > 0 ? "matched" : availability.Available ? "no_match" : "unavailable",
                matches.Length > 0 ? "供教师挑选，使用前请核对年级、难度、答案和解析。" : availability.Available ? "当前题源没有足够匹配题目，可补充知识点或扩充同学段题库。" : "题库暂不可用，请联系管理员检查题源。", matches));
        }
        return Results.Ok(new { classSessionId = id, studentId, subject, stage = input.Stage, markRevision = mark.Revision,
            method = availability.Method, questions = rows, requiresTeacherReview = true });
    }
}
