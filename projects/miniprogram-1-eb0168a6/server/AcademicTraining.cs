using Microsoft.EntityFrameworkCore;
using System.Text.RegularExpressions;
namespace Siyue;

public sealed record ClassTrainingInput(string? Stage = null, Guid? StudentId = null, string? Subject = null, int PerError = 2, int MaxQuestions = 12);
public sealed record TrainingClass(Guid ClassId, Guid CourseId, string ClassName, string CourseName, string SuggestedStage, string SuggestedSubject, int StudentCount);
public sealed record TrainingOrigin(Guid QuestionId, string Label, string LessonDate, int Occurrences);
public sealed record TrainingExercise(BankQuestion Question, string[] Reasons, TrainingOrigin Origin);
public sealed record StudentTraining(Guid StudentId, string Name, string Status, int WrongCount, int PendingRecords, TrainingExercise[] Questions, string[] Notices);
public static class AcademicTraining
{
    sealed record Mark(Guid StudentId, bool Reviewed, string WrongIdsJson, Guid HandoutId, string LessonDate, int Revision);
    sealed record Source(Question Question, string Text, string LessonDate, int Occurrences);
    sealed record Snapshot(SchoolClass Class, Course Course, Student[] Students, Mark[] Marks, Dictionary<Guid, Question> Questions);
    public static string SuggestStage(string name) => Regex.IsMatch(name, "高中|高[一二三123]") ? "高中"
        : Regex.IsMatch(name, "初中|初[一二三123]|[七八九789]年级") ? "初中"
        : Regex.IsMatch(name, "小学|[一二三四五六123456]年级") ? "小学" : "";
    static string Subject(string name) { var found = LocalQuestionBank.Subjects.Where(name.Contains).ToArray(); return found.Length == 1 ? found[0] : ""; }
    static string Text(Question q) => string.Join("\n", Json.Read<ContentBlock[]>(q.BlocksJson).Where(b => b.Kind is not ("image" or "drawing")).Select(b => b.Text)).Trim();
    public static void MapEndpoints(RouteGroupBuilder group)
    {
        group.MapGet("/training/classes", Classes);
        group.MapGet("/training/classes/{classId:guid}/courses/{courseId:guid}", Roster);
        group.MapPost("/training/classes/{classId:guid}/courses/{courseId:guid}/generate", Generate);
    }
    static async Task<TrainingClass[]> Classes(Data db, Access a)
    {
        var admin = a.User.Role == "admin"; var uid = a.User.Id;
        var pairs = db.Assignments.Where(x => admin || x.StaffId == uid).Select(x => new { x.ClassId, x.CourseId })
            .Union(db.ClassSessions.Where(s => admin || db.Assignments.Any(x => x.StaffId == uid && x.ClassId == s.ClassId && x.CourseId == s.CourseId)).Select(s => new { s.ClassId, s.CourseId }));
        var rows = await (from p in pairs join cl in db.Classes on p.ClassId equals cl.Id join c in db.Courses on p.CourseId equals c.Id
            where !cl.Archived && !c.Archived orderby cl.Name, c.Name
            select new { classId = cl.Id, courseId = c.Id, cl.Name, courseName = c.Name, count = db.Students.Count(x => x.ClassId == cl.Id) }).ToArrayAsync();
        return rows.Select(x => new TrainingClass(x.classId, x.courseId, x.Name, x.courseName, SuggestStage(x.Name), Subject(x.courseName), x.count)).ToArray();
    }
    static async Task<Snapshot> Load(Guid classId, Guid courseId, Data db, Access a)
    {
        await a.Class(classId, courseId);
        var cl = await db.Classes.AsNoTracking().SingleOrDefaultAsync(x => x.Id == classId && !x.Archived) ?? throw new ApiException(404, "班级不存在");
        var course = await db.Courses.AsNoTracking().SingleOrDefaultAsync(x => x.Id == courseId && !x.Archived) ?? throw new ApiException(404, "课程不存在");
        var students = await db.Students.AsNoTracking().Where(x => x.ClassId == classId).OrderBy(x => x.Name).ThenBy(x => x.Id).ToArrayAsync();
        Guard.That(students.Length <= 200, "本班人数超过200，请拆分班级后生成训练", 409);
        var ids = students.Select(x => x.Id).ToArray();
        var marks = await (from m in db.ClassSessionMarks.AsNoTracking() join s in db.ClassSessions on m.ClassSessionId equals s.Id
            where s.ClassId == classId && s.CourseId == courseId && ids.Contains(m.StudentId)
            select new Mark(m.StudentId, m.Reviewed, m.WrongIdsJson, s.HandoutId, s.LessonDate, m.Revision)).Take(20001).ToArrayAsync();
        Guard.That(marks.Length <= 20000, "本班历史记录过多，请先联系管理员划分训练范围", 409);
        var wrongIds = marks.Where(x => x.Reviewed).SelectMany(x => Json.Read<Guid[]>(x.WrongIdsJson)).Distinct().ToArray();
        var handouts = marks.Select(x => x.HandoutId).Distinct().ToArray();
        var questions = await db.Questions.AsNoTracking().Where(x => handouts.Contains(x.HandoutId) && wrongIds.Contains(x.Id)).ToDictionaryAsync(x => x.Id);
        return new(cl, course, students, marks, questions);
    }
    static Source[] Sources(Snapshot snapshot, Guid studentId) => snapshot.Marks.Where(m => m.StudentId == studentId && m.Reviewed)
        .SelectMany(m => Json.Read<Guid[]>(m.WrongIdsJson).Distinct().Where(id => snapshot.Questions.TryGetValue(id, out var q) && q.HandoutId == m.HandoutId)
            .Select(id => new { Q = snapshot.Questions[id], m.LessonDate }))
        .GroupBy(x => x.Q.Id).Select(g => new Source(g.First().Q, Text(g.First().Q), g.Max(x => x.LessonDate)!, g.Count()))
        .OrderByDescending(x => x.Occurrences).ThenByDescending(x => x.LessonDate).ThenBy(x => x.Question.Order).ThenBy(x => x.Question.Id).ToArray();
    static async Task<IResult> Roster(Guid classId, Guid courseId, Data db, Access a)
    {
        var s = await Load(classId, courseId, db, a);
        return Results.Ok(new { classId, courseId, className = s.Class.Name, courseName = s.Course.Name, suggestedStage = SuggestStage(s.Class.Name), suggestedSubject = Subject(s.Course.Name),
            students = s.Students.Select(st => new { studentId = st.Id, st.Name, wrongCount = Sources(s, st.Id).Length, reviewedRecords = s.Marks.Count(m => m.StudentId == st.Id && m.Reviewed), pendingRecords = s.Marks.Count(m => m.StudentId == st.Id && !m.Reviewed) }) });
    }
    static async Task<IResult> Generate(Guid classId, Guid courseId, ClassTrainingInput input, Data db, Access a, IQuestionBank bank)
    {
        var s = await Load(classId, courseId, db, a);
        Guard.That(!input.StudentId.HasValue || s.Students.Any(st => st.Id == input.StudentId), "学生不属于所选班级", 403);
        var stage = input.Stage ?? SuggestStage(s.Class.Name); var knownSubject = Subject(s.Course.Name); var subject = input.Subject?.Trim() ?? knownSubject;
        Guard.That(LocalQuestionBank.Stages.Contains(stage), "请先为班级设置学段，之后无需逐人选择");
        Guard.That(LocalQuestionBank.Subjects.Contains(subject) && (knownSubject.Length == 0 || subject == knownSubject), "请核对班级课程的学科设置");
        Guard.That(input.PerError is >= 1 and <= 3 && input.MaxQuestions is >= 1 and <= 30, "每道错题选1至3道，每人训练1至30道");
        var allSources = s.Students.ToDictionary(st => st.Id, st => Sources(s, st.Id));
        // The same class snapshot is used for batch and single generation. An exercise
        // from the original classroom errors is never returned as a new practice item.
        var originals = allSources.Values.SelectMany(x => x).Select(x => LocalQuestionBank.Fingerprint(subject, x.Text)).ToHashSet();
        var cached = new Dictionary<Guid, QuestionMatch[]>(); var available = bank.Availability().Available;
        var plans = new List<StudentTraining>();
        foreach (var st in s.Students.Where(st => !input.StudentId.HasValue || st.Id == input.StudentId)) {
            var sources = allSources[st.Id]; var marks = s.Marks.Where(m => m.StudentId == st.Id).ToArray();
            var pending = marks.Count(m => !m.Reviewed); var notices = new List<string>(); var exercises = new List<TrainingExercise>();
            if (pending > 0) notices.Add($"另有{pending}次记录尚未批改，未纳入训练。");
            var missing = marks.Where(m => m.Reviewed).SelectMany(m => Json.Read<Guid[]>(m.WrongIdsJson).Where(id => !s.Questions.TryGetValue(id, out var q) || q.HandoutId != m.HandoutId)).Distinct().Count();
            if (missing > 0) notices.Add($"{missing}道原错题来源缺失，已跳过，请核对课堂记录。");
            if (sources.Length == 0) {
                var status = missing > 0 ? "source_missing" : marks.Any(m => m.Reviewed) ? "no_wrong" : "unreviewed";
                plans.Add(new(st.Id, st.Name, status, 0, pending, [], notices.ToArray())); continue;
            }
            if (sources.Length > 100) notices.Add("本次优先使用重复出错较多及最近的100道错题。");
            var selected = sources.Take(100).ToArray(); var needsText = selected.Count(x => x.Text.Length < 4);
            if (needsText > 0) notices.Add($"{needsText}道错题缺少可检索题干，需补充文字后再匹配。");
            var used = new HashSet<string>();
            for (var pass = 0; pass < input.PerError && exercises.Count < input.MaxQuestions; pass++) {
                foreach (var source in selected.Where(x => x.Text.Length >= 4)) {
                    if (exercises.Count >= input.MaxQuestions) break;
                    if (!cached.TryGetValue(source.Question.Id, out var pool)) {
                        pool = bank.Recommend(subject, stage, source.Text, [], input.MaxQuestions + input.PerError, originals); cached[source.Question.Id] = pool;
                    }
                    var match = pool.FirstOrDefault(m => !used.Contains(LocalQuestionBank.Fingerprint(subject, m.Question.Stem)));
                    if (match == null) continue;
                    used.Add(LocalQuestionBank.Fingerprint(subject, match.Question.Stem));
                    exercises.Add(new(match.Question, match.Reasons, new(source.Question.Id, source.Question.Label, source.LessonDate, source.Occurrences)));
                }
            }
            var expected = Math.Min(input.MaxQuestions, selected.Length * input.PerError);
            if (exercises.Count < expected) notices.Add($"计划最多{expected}道，当前题源可匹配{exercises.Count}道；未用无关题补足。");
            var state = !available ? "unavailable" : exercises.Count == 0 ? needsText == selected.Length ? "needs_text" : "no_match" : exercises.Count < expected || missing > 0 ? "partial" : "generated";
            plans.Add(new(st.Id, st.Name, state, sources.Length, pending, exercises.ToArray(), notices.ToArray()));
        }
        return Results.Ok(new { classId, courseId, className = s.Class.Name, courseName = s.Course.Name, stage, subject,
            scope = "all_reviewed_class_course_errors", requiresTeacherReview = true,
            snapshot = Secrets.Hash(Json.Write(new { s.Marks, revisions = s.Questions.Values.OrderBy(q => q.Id).Select(q => new { q.Id, q.Revision }), stage, subject, input.PerError, input.MaxQuestions })),
            students = plans, summary = new { total = plans.Count, generated = plans.Count(p => p.Questions.Length > 0), noWrong = plans.Count(p => p.Status == "no_wrong"), pending = plans.Count(p => p.Status == "unreviewed"), unavailable = plans.Count(p => p.Questions.Length == 0 && p.Status is not ("no_wrong" or "unreviewed")) } });
    }
}
