using Microsoft.EntityFrameworkCore;
namespace Siyue;
public class LearningEvent : Entity
{
    public Guid StudentId { get; set; } public Guid QuestionId { get; set; } public Guid ClassId { get; set; } public Guid CourseId { get; set; }
    public Guid RecordedBy { get; set; } public string State { get; set; } = "wrong"; public string Kind { get; set; } = "mark";
    public string RequestKey { get; set; } = ""; public DateTimeOffset RecordedAt { get; set; } = Clock.Now;
}
public record RetestInput(Guid CourseId, Guid QuestionId, string State, string RequestKey);
public record HistoryExportInput(Guid CourseId, Guid[] QuestionIds);
public record HistoryItem(Guid QuestionId, Guid HandoutId, Guid LectureId, int Sequence, string Title, string Label, int Version, string Text, string[] Previews, string State, string LatestKind, DateTimeOffset FirstWrongAt, DateTimeOffset LastRecordedAt, int WrongRecords, int Retests, int FailedRetests, LearningEvent[] Events);
public static class StudentHistory
{
    public static async Task<Student> StudentScope(Guid id, Guid courseId, Data db, Access a) { var s = await db.Students.FindAsync(id) ?? throw new ApiException(404, "学生不存在"); await a.Class(s.ClassId, courseId); await a.Course(courseId); return s; }
    // Capture legacy wrong links before replacement. Existing records stay available even after correction.
    public static async Task Record(Data db, WrongInput input, Lecture lecture, Guid classId, Guid staffId)
    {
        var ids = await db.Questions.Where(q => q.HandoutId == input.HandoutId).Select(q=>q.Id).ToArrayAsync();
        var events = await db.LearningEvents.Where(e => input.StudentIds.Contains(e.StudentId) && ids.Contains(e.QuestionId)).ToListAsync();
        var legacy = await db.WrongQuestions.Where(w => input.StudentIds.Contains(w.StudentId) && ids.Contains(w.QuestionId)).ToListAsync();
        foreach (var w in legacy.Where(w => !events.Any(e => e.StudentId == w.StudentId && e.QuestionId == w.QuestionId && e.State == "wrong"))) {
            var e = new LearningEvent { StudentId=w.StudentId,QuestionId=w.QuestionId,ClassId=classId,CourseId=lecture.CourseId,RecordedBy=w.RecordedBy,RecordedAt=w.RecordedAt,Kind="legacy",RequestKey="legacy:"+w.Id }; db.LearningEvents.Add(e); events.Add(e);
        }
        foreach (var student in input.StudentIds) {
            var marks = input.Marks != null ? new Dictionary<Guid,string>(input.Marks) : input.QuestionIds.Distinct().ToDictionary(q=>q,_=>"wrong");
            var previous = await db.WrongSettings.SingleOrDefaultAsync(x=>x.StudentId == student && x.HandoutId == input.HandoutId);
            var oldMarks = previous == null ? new Dictionary<Guid,string>() : Json.Read<Dictionary<Guid,string>>(previous.MarksJson);
            if (input.Mode == "replace") foreach (var q in oldMarks.Keys.Concat(legacy.Where(w=>w.StudentId==student).Select(w=>w.QuestionId)).Distinct()) if (!marks.ContainsKey(q)) marks[q] = "unmarked";
            foreach (var pair in marks) {
                // A re-sent autosave is not another attempt. Retests have their own explicit event endpoint.
                var last = events.Where(e=>e.StudentId==student && e.QuestionId==pair.Key && e.Kind != "retest").OrderByDescending(e=>e.RecordedAt).FirstOrDefault();
                if (last?.State == pair.Value) continue;
                db.LearningEvents.Add(new LearningEvent {StudentId=student,QuestionId=pair.Key,ClassId=classId,CourseId=lecture.CourseId,RecordedBy=staffId,State=pair.Value,RequestKey=Guid.NewGuid().ToString("N")});
            }
        }
    }
    public static async Task<HistoryItem[]> Items(Student student, Guid courseId, Data db, Access a, Files? files = null, Guid[]? onlyQuestionIds = null)
    {
        var events = await db.LearningEvents.Where(x=>x.StudentId == student.Id && x.ClassId == student.ClassId && x.CourseId == courseId && (onlyQuestionIds == null || onlyQuestionIds.Contains(x.QuestionId))).OrderBy(x=>x.RecordedAt).ToListAsync();
        var legacy = await (from w in db.WrongQuestions join q in db.Questions on w.QuestionId equals q.Id join h in db.Handouts on q.HandoutId equals h.Id join l in db.Lectures on h.LectureId equals l.Id where w.StudentId == student.Id && (onlyQuestionIds == null || onlyQuestionIds.Contains(w.QuestionId)) && l.CourseId == courseId && (l.ClassId == null || l.ClassId == student.ClassId) select w).ToListAsync();
        foreach (var w in legacy.Where(w=>!events.Any(e=>e.QuestionId==w.QuestionId && e.State=="wrong"))) events.Add(new LearningEvent {Id=w.Id,StudentId=w.StudentId,QuestionId=w.QuestionId,ClassId=student.ClassId,CourseId=courseId,State="wrong",Kind="legacy",RecordedAt=w.RecordedAt,RecordedBy=w.RecordedBy});
        var ids = events.Where(e=>e.State=="wrong").Select(e=>e.QuestionId).Distinct().ToArray();
        var questions = await (from q in db.Questions join h in db.Handouts on q.HandoutId equals h.Id join l in db.Lectures on h.LectureId equals l.Id where ids.Contains(q.Id) && h.State=="published" && l.CourseId==courseId && (l.ClassId==null || l.ClassId==student.ClassId) orderby l.Sequence,h.Version,q.Order select new {q,h,l}).ToListAsync();
        var items = new List<HistoryItem>(); var groupedEvents = events.GroupBy(e=>e.QuestionId).ToDictionary(g=>g.Key,g=>g.OrderBy(e=>e.RecordedAt).ToArray());
        foreach (var x in questions) {
            var log = groupedEvents[x.q.Id]; var last=log[^1]; var previews=new List<string>();
            if (files != null) foreach(var key in Json.Read<string[]>(x.q.PreviewsJson)) previews.Add(await files.Grant(key,"image/png","题目.png",a.User.Id,classId:student.ClassId,courseId:courseId));
            items.Add(new(x.q.Id,x.h.Id,x.l.Id,x.l.Sequence,x.l.Title,x.q.Label,x.h.Version,string.Join("\n",Json.Read<ContentBlock[]>(x.q.BlocksJson).Select(b=>b.Text)),previews.ToArray(),last.State,last.Kind,log.Where(e=>e.State=="wrong").Min(e=>e.RecordedAt),last.RecordedAt,log.Count(e=>e.State=="wrong"),log.Count(e=>e.Kind=="retest"),log.Count(e=>e.Kind=="retest" && e.State=="wrong"),log));
        }
        return items.ToArray();
    }
    static object Card(HistoryItem x)=>new {x.QuestionId,x.HandoutId,x.LectureId,x.Sequence,x.Title,x.Label,x.Version,x.Text,x.Previews,x.State,x.LatestKind,x.FirstWrongAt,x.LastRecordedAt,x.WrongRecords,x.Retests,x.FailedRetests,events=x.Events.Select(e=>new {e.Id,e.State,e.Kind,e.RecordedAt})};
    public static void MapStudentHistory(this WebApplication app)
    {
        app.MapGet("/api/students/{id:guid}/history", async (Guid id, Guid courseId, DateTimeOffset? from, DateTimeOffset? to, Guid? lectureId, string? state, string? query, int? page, int? pageSize, Data db, Access a) => {
            var student=await StudentScope(id,courseId,db,a); Guard.That(!from.HasValue || !to.HasValue || from <= to,"日期范围无效");
            var number=page??1;var size=pageSize??50;Guard.That(number is >0 and <=1000000 && size is >0 and <=100 && (query?.Length??0)<=200,"查询范围无效");
            Guard.That(string.IsNullOrEmpty(state) || state is "all" or "wrong" or "correct" or "unfinished" or "unmarked","批改状态无效");
            var keyword=query?.Trim()??"";var items=await Items(student,courseId,db,a);
            var filtered=items.Where(x=>(!lectureId.HasValue || x.LectureId==lectureId) && (string.IsNullOrEmpty(state) || state=="all" || x.State==state) && (keyword.Length==0 || (x.Label+" "+x.Title+" "+x.Text).Contains(keyword,StringComparison.OrdinalIgnoreCase)) && x.Events.Any(e=>e.State=="wrong" && (!from.HasValue || e.RecordedAt>=from) && (!to.HasValue || e.RecordedAt<=to))).ToArray();
            // Preview grants are issued only when a question is opened, not for the student's entire archive.
            return new {student,total=filtered.Length,page=number,pageSize=size,hasMore=number*size<filtered.Length,items=filtered.Skip((number-1)*size).Take(size).Select(Card)};
        });
        app.MapGet("/api/students/{id:guid}/history/{questionId:guid}",async(Guid id,Guid questionId,Guid courseId,Data db,Access a,Files files)=>{
            var student=await StudentScope(id,courseId,db,a);var item=(await Items(student,courseId,db,a,files,[questionId])).SingleOrDefault()??throw new ApiException(404,"该学生没有这道历史错题");return Card(item);
        });
        app.MapPost("/api/students/{id:guid}/retests", async (Guid id, RetestInput input, Data db, Access a) => {
            var student=await StudentScope(id,input.CourseId,db,a); Guard.That(input.State is "correct" or "wrong" or "unfinished" && input.RequestKey.Length is > 0 and <= 100,"复测状态或请求标识无效");
            var items=await Items(student,input.CourseId,db,a,onlyQuestionIds:[input.QuestionId]); Guard.That(items.Any(x=>x.QuestionId==input.QuestionId),"题目不属于该学生的历史错题",403);
            var old=await db.LearningEvents.SingleOrDefaultAsync(e=>e.StudentId==id && e.QuestionId==input.QuestionId && e.RequestKey==input.RequestKey);
            if(old!=null) { Guard.That(old.State==input.State && old.Kind=="retest","同一复测请求不能更改结果",409); return Results.Ok(new {old.Id}); }
            var item=new LearningEvent {StudentId=id,QuestionId=input.QuestionId,ClassId=student.ClassId,CourseId=input.CourseId,RecordedBy=a.User.Id,State=input.State,Kind="retest",RequestKey=input.RequestKey};
            db.LearningEvents.Add(item); db.Audits.Add(new Audit {StaffId=a.User.Id,Action="student.retest",TargetId=id}); await db.SaveChangesAsync(); return Results.Ok(new {item.Id});
        });
        app.MapPost("/api/students/{id:guid}/history/export", async (Guid id, HistoryExportInput input, Data db, Access a) => {
            var student=await StudentScope(id,input.CourseId,db,a); var history=await Items(student,input.CourseId,db,a);
            Guard.That(input.QuestionIds.Length is > 0 and <= 500 && input.QuestionIds.Distinct().Count()==input.QuestionIds.Length,"请选择 1–500 道不同的历史错题");
            Guard.That(input.QuestionIds.All(q=>history.Any(h=>h.QuestionId==q)),"所选题目不属于该学生的历史错题",403);
            var questions=await db.Questions.Where(q=>input.QuestionIds.Contains(q.Id)).ToDictionaryAsync(q=>q.Id);
            var doc=new ExportDocument {BatchId=Guid.NewGuid(),StaffId=a.User.Id,StudentId=id,StudentName=student.Name,ClassId=student.ClassId,CourseId=input.CourseId,SnapshotJson=Json.Write(history.Where(h=>input.QuestionIds.Contains(h.QuestionId)).Select(h=>new QuestionSnapshot(h.QuestionId,h.HandoutId,h.Label,$"第{h.Sequence}讲 · {h.Title} · v{h.Version}",questions[h.QuestionId].BlocksJson,questions[h.QuestionId].HalfPage)))};
            db.Documents.Add(doc);db.Work.Add(new WorkItem {Kind="export",EntityId=doc.Id});db.Audits.Add(new Audit {StaffId=a.User.Id,Action="history.export",TargetId=doc.Id});await db.SaveChangesAsync();return Results.Accepted(value:new {doc.Id,doc.BatchId});
        });
    }
}
