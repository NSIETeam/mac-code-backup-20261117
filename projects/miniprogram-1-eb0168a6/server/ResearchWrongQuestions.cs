using Microsoft.EntityFrameworkCore;

namespace Siyue;

public static class ResearchWrongQuestions
{
    public record Input(Guid SessionId, string Subject, string Stage, string Grade, string Region,
        Guid? StudentId = null, Guid[]? QuestionIds = null);

    public static void Map(RouteGroupBuilder api)
    {
        api.MapPost("/from-session", async (Input input, Data db, Access access, ResearchStore store,
            IObjects objects, IQuestionBank bank, CancellationToken ct) => {
            var session = await db.ClassSessions.AsNoTracking().SingleOrDefaultAsync(x => x.Id == input.SessionId, ct)
                ?? throw new ApiException(404, "课次不存在");
            await access.Class(session.ClassId, session.CourseId);
            var course = await db.Courses.AsNoTracking().SingleAsync(x => x.Id == session.CourseId, ct);
            var subjects = LocalQuestionBank.Subjects.Where(x => course.Name.Contains(x)).ToArray();
            Guard.That(subjects.Length != 1 || input.Subject == subjects[0], "所选学科与课程不一致");
            if (input.StudentId.HasValue)
                Guard.That(await db.Students.AnyAsync(x => x.Id == input.StudentId && x.ClassId == session.ClassId, ct), "学生不在此班级", 403);
            var marks = await (from m in db.ClassSessionMarks.AsNoTracking()
                join student in db.Students on m.StudentId equals student.Id
                where m.ClassSessionId == session.Id && student.ClassId == session.ClassId && m.Reviewed &&
                    (!input.StudentId.HasValue || m.StudentId == input.StudentId)
                select m.WrongIdsJson).ToArrayAsync(ct);
            Guard.That(marks.Length > 0, "请先完成本课次错题登记", 409);
            var wrong = marks.SelectMany(Json.Read<Guid[]>).Distinct().ToHashSet();
            Guard.That(input.QuestionIds == null || input.QuestionIds.Length is > 0 and <= 100 && input.QuestionIds.All(wrong.Contains), "只能选择本课次已登记的错题");
            var ids = input.QuestionIds?.Distinct().ToArray() ?? wrong.ToArray();
            Guard.That(ids.Length is > 0 and <= 100, "请选择1至100道已登记的错题");
            var handout = await db.Handouts.AsNoTracking().SingleOrDefaultAsync(x => x.Id == session.HandoutId && x.State == "published", ct)
                ?? throw new ApiException(409, "原讲义尚未发布或已变更");
            var source = await db.Questions.AsNoTracking().Where(x => x.HandoutId == handout.Id && x.Confirmed && ids.Contains(x.Id)).OrderBy(x => x.Order).ToArrayAsync(ct);
            Guard.That(source.Length == ids.Length, "原题已变更或未确认，请先核对错题登记", 409);
            var items = source.Select(q => {
                var blocks = Json.Read<ContentBlock[]>(q.BlocksJson);
                var text = string.Join("\n", blocks.Where(b => b.Kind is not ("image" or "drawing")).Select(b => b.Text)).Trim();
                var knowledge = ResearchKnowledge.Infer(bank, input.Subject, text);
                return new ResearchItem { Label = q.Label, Section = "基础巩固", Stem = text, Blocks = blocks,
                    Knowledge = knowledge, KnowledgeMethod = knowledge.Length > 0 ? "原题关键词 · 待核对" : "请识别或填写知识点",
                    Source = "课堂错题", SourceId = q.Id.ToString(), Reviewed = false };
            }).ToArray();
            foreach (var item in items) ResearchStore.ValidateItem(item);
            Guard.That(handout.NormalizedKey.Length > 0 || items.All(q => q.Blocks.Length == 0), "原讲义文件缺失，无法完整保留题目", 409);
            var draft = await store.Create(access.User.Id, new("错题同类训练 · " + session.LessonDate,
                input.Subject, input.Stage, input.Grade, input.Region, "practice"), null, ct);
            var dir = store.Dir(draft.Owner, draft.Id);
            try {
                if (handout.NormalizedKey.Length > 0) {
                    await using var file = await objects.Get(handout.NormalizedKey, ct);
                    await using var target = File.Create(Path.Combine(dir, "source.docx"));
                    await file.CopyToAsync(target, ct);
                }
                draft = await store.Change(draft.Owner, draft.Id, draft.Revision, d => d with {
                    Items = items, Message = "已导入已登记错题。核对知识点与题型，选择学科网同类题；正式导出前逐题确认。"
                }, ct);
                return ResearchImport.View(draft);
            } catch { Directory.Delete(dir, true); throw; }
        });
    }
}
