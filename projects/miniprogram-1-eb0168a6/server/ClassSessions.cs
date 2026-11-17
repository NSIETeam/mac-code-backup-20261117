using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
using System.Text;
namespace Siyue;

public class ClassSession : Entity {
    public Guid ClassId { get; set; } public Guid CourseId { get; set; } public Guid HandoutId { get; set; } public Guid StaffId { get; set; }
    public string LessonDate { get; set; } = ""; public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public class ClassSessionMark : Entity {
    public Guid ClassSessionId { get; set; } public Guid StudentId { get; set; }
    public string WrongIdsJson { get; set; } = "[]"; public string NotesJson { get; set; } = "{}";
    public bool Reviewed { get; set; } public int Revision { get; set; } public Guid RecordedBy { get; set; }
}
public record ClassSessionInput(Guid ClassId, Guid HandoutId, string LessonDate, string RequestKey);
public record ClassMarkInput(int Revision, Guid[] QuestionIds, Dictionary<Guid,string>? Notes = null);
public record ClassExportInput(Guid[] StudentIds, string Layout = "analysis", Guid[]? QuestionIds = null, string RequestKey = "");
public static class ClassSessions {
    static Guid Identity(string text) => new(SHA256.HashData(Encoding.UTF8.GetBytes(text))[..16]);
    static Guid AutomaticId(Guid classId, Guid lectureId) => Identity($"class-session:auto:{classId}:{lectureId}");

    // Call inside the publication/synchronization transaction. Lock the class so
    // parallel confirmations cannot create a second classroom entry for a lecture.
    public static async Task<ClassSession?> EnsurePublished(Guid handoutId, Guid staffId, Data db, CancellationToken ct = default) {
        var row = await (from h in db.Handouts.AsNoTracking() join l in db.Lectures on h.LectureId equals l.Id
            where h.Id == handoutId && h.State == "published" && l.ClassId.HasValue
            select new { Handout = h, Lecture = l }).SingleOrDefaultAsync(ct);
        if (row == null) return null;
        var classId = row.Lecture.ClassId!.Value;
        await db.Classes.Where(c => c.Id == classId).ExecuteUpdateAsync(s => s.SetProperty(c => c.Name, c => c.Name), ct);
        var existing = await (from s in db.ClassSessions join h in db.Handouts on s.HandoutId equals h.Id
            where s.ClassId == classId && h.LectureId == row.Lecture.Id orderby s.CreatedAt, s.Id select s).FirstOrDefaultAsync(ct);
        if (existing != null) {
            // Future automatically-created sessions may precede roster import.
            // Keep completed lessons and manually selected rosters immutable.
            if (existing.Id == AutomaticId(classId, row.Lecture.Id) && existing.LessonDate.Length == 0 &&
                !await db.ClassSessionMarks.AnyAsync(m => m.ClassSessionId == existing.Id && m.Reviewed, ct) &&
                !await db.Documents.AnyAsync(d => d.ClassSessionId == existing.Id, ct)) {
                var known = await db.ClassSessionMarks.Where(m => m.ClassSessionId == existing.Id).Select(m => m.StudentId).ToArrayAsync(ct);
                var added = await db.Students.Where(s => s.ClassId == classId && !known.Contains(s.Id)).Select(s => s.Id).ToArrayAsync(ct);
                db.ClassSessionMarks.AddRange(added.Select(studentId => new ClassSessionMark { ClassSessionId = existing.Id, StudentId = studentId }));
                if (added.Length > 0) await db.SaveChangesAsync(ct);
            }
            return existing;
        }
        var session = new ClassSession { Id = AutomaticId(classId, row.Lecture.Id), ClassId = classId,
            CourseId = row.Lecture.CourseId, HandoutId = row.Handout.Id, StaffId = staffId, LessonDate = "" };
        db.ClassSessions.Add(session);
        var students = await db.Students.Where(s => s.ClassId == classId).Select(s => s.Id).ToArrayAsync(ct);
        db.ClassSessionMarks.AddRange(students.Select(studentId => new ClassSessionMark { ClassSessionId = session.Id, StudentId = studentId }));
        db.Audits.Add(new Audit { StaffId = staffId, Action = "class-session.auto-create", TargetId = session.Id });
        await db.SaveChangesAsync(ct);
        return session;
    }
    static object Mark(ClassSessionMark m) => new { m.StudentId, m.Revision, m.Reviewed, questionIds = Json.Read<Guid[]>(m.WrongIdsJson), notes = Json.Read<Dictionary<Guid,string>>(m.NotesJson) };
    static async Task<ClassSession> Scope(Guid id, Data db, Access a) {
        var s=await db.ClassSessions.FindAsync(id)??throw new ApiException(404,"课次不存在"); await a.Class(s.ClassId,s.CourseId); return s;
    }
    public static void MapClassSessions(this WebApplication app) {
        app.MapPost("/api/classes/{id:guid}/sync-sessions", async (Guid id, Data db, Access a, CancellationToken ct) => {
            await a.Class(id); var staff = a.User;
            Guard.That(staff.Role is "admin" or "teacher", "当前身份不能整理班级讲次", 403);
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            await db.Classes.Where(c => c.Id == id).ExecuteUpdateAsync(s => s.SetProperty(c => c.Name, c => c.Name), ct);
            var rows = await (from h in db.Handouts join l in db.Lectures on h.LectureId equals l.Id
                where l.ClassId == id && h.State == "published" && (staff.Role == "admin" ||
                    db.Assignments.Any(x => x.StaffId == staff.Id && x.ClassId == id && x.CourseId == l.CourseId))
                orderby l.Sequence, h.Version descending, h.Id
                select new { h.Id, h.LectureId }).ToArrayAsync(ct);
            var before = await db.ClassSessions.CountAsync(s => s.ClassId == id, ct);
            foreach (var handout in rows.DistinctBy(x => x.LectureId)) await EnsurePublished(handout.Id, staff.Id, db, ct);
            var after = await db.ClassSessions.CountAsync(s => s.ClassId == id, ct);
            await tx.CommitAsync(ct);
            return new { created = after - before };
        });
        app.MapGet("/api/class-sessions",async (Guid? classId,Data db,Access a) => {
            if(classId.HasValue)await a.Class(classId.Value);
            var staff=a.User;var assignments=db.Assignments.Where(x=>x.StaffId==staff.Id);
            var rows=await (from s in db.ClassSessions join c in db.Classes on s.ClassId equals c.Id join h in db.Handouts on s.HandoutId equals h.Id join l in db.Lectures on h.LectureId equals l.Id
                where (!classId.HasValue || s.ClassId==classId.Value) && (staff.Role=="admin" || assignments.Any(x=>x.ClassId==s.ClassId && x.CourseId==s.CourseId))
                orderby l.Sequence,s.LessonDate descending,s.CreatedAt descending,s.Id
                select new {s.Id,s.ClassId,s.CourseId,s.HandoutId,s.LessonDate,className=c.Name,l.Title,l.Sequence}).Take(200).ToArrayAsync();
            var ids=rows.Select(s=>s.Id).ToArray();var marks=await db.ClassSessionMarks.Where(m=>ids.Contains(m.ClassSessionId)).ToArrayAsync();
            return rows.Select(s=>new {s.Id,s.ClassId,s.CourseId,s.HandoutId,s.LessonDate,s.className,s.Title,s.Sequence,total=marks.Count(m=>m.ClassSessionId==s.Id),reviewed=marks.Count(m=>m.ClassSessionId==s.Id&&m.Reviewed)});
        });
        app.MapPost("/api/class-sessions",async (ClassSessionInput r,Data db,Access a) => {
            var h=await a.Handout(r.HandoutId,true);var l=await a.Lecture(h.LectureId);await a.LectureClass(l,r.ClassId);
            Guard.That(DateOnly.TryParseExact(r.LessonDate,"yyyy-MM-dd",out _),"请选择有效上课日期");Guard.That(r.RequestKey is {Length: >0 and <=120},"缺少课次标识");
            var id=Identity($"class-session:{a.User.Id}:{r.RequestKey}");var previous=await db.ClassSessions.FindAsync(id);
            if(previous!=null){Guard.That(previous.ClassId==r.ClassId&&previous.HandoutId==r.HandoutId&&previous.LessonDate==r.LessonDate,"课次标识已用于其他内容",409);return previous;}
            var students=await db.Students.Where(x=>x.ClassId==r.ClassId).Select(x=>x.Id).ToArrayAsync();Guard.That(students.Length>0,"请先在电脑端导入学生名单");
            var s=new ClassSession {Id=id,ClassId=r.ClassId,CourseId=l.CourseId,HandoutId=h.Id,StaffId=a.User.Id,LessonDate=r.LessonDate};db.ClassSessions.Add(s);
            db.ClassSessionMarks.AddRange(students.Select(studentId=>new ClassSessionMark {ClassSessionId=id,StudentId=studentId}));
            db.Audits.Add(new Audit {StaffId=a.User.Id,Action="class-session.create",TargetId=id});
            try{await db.SaveChangesAsync();}catch(DbUpdateException){db.ChangeTracker.Clear();var existing=await db.ClassSessions.FindAsync(id);if(existing==null)throw;Guard.That(existing.ClassId==r.ClassId&&existing.HandoutId==r.HandoutId&&existing.LessonDate==r.LessonDate,"课次标识已被使用",409);return existing;}
            return s;
        });
        app.MapGet("/api/class-sessions/{id:guid}",async(Guid id,Data db,Access a,Files files)=>{
            var s=await Scope(id,db,a);var h=await a.Handout(s.HandoutId,true);var l=await a.Lecture(h.LectureId);
            var marks=await db.ClassSessionMarks.Where(x=>x.ClassSessionId==id).ToArrayAsync();var studentIds=marks.Select(x=>x.StudentId).ToArray();
            var students=await db.Students.Where(x=>studentIds.Contains(x.Id)&&x.ClassId==s.ClassId).OrderBy(x=>x.Code).Select(x=>new{x.Id,x.Name,x.Code}).ToArrayAsync();
            var cards=new List<object>();
            foreach(var q in await db.Questions.Where(x=>x.HandoutId==h.Id).OrderBy(x=>x.Order).ToArrayAsync()){
                var previews=new List<string>();foreach(var key in Json.Read<string[]>(q.PreviewsJson))previews.Add(await files.Grant(key,"image/png","题目.png",a.User.Id,classId:s.ClassId,courseId:s.CourseId));
                cards.Add(new{q.Id,q.Label,q.Order,previews,text=string.Join("\n",Json.Read<ContentBlock[]>(q.BlocksJson).Select(b=>b.Text))});
            }
            return new{session=s,lecture=l,className=(await db.Classes.FindAsync(s.ClassId))!.Name,students,questions=cards,marks=marks.Select(Mark)};
        });
        app.MapPut("/api/class-sessions/{id:guid}/students/{studentId:guid}",async(Guid id,Guid studentId,ClassMarkInput r,Data db,Access a)=>{
            var s=await Scope(id,db,a);Guard.That(await db.Students.AnyAsync(x=>x.Id==studentId&&x.ClassId==s.ClassId),"学生不属于该课次",403);
            var mark=await db.ClassSessionMarks.SingleOrDefaultAsync(x=>x.ClassSessionId==id&&x.StudentId==studentId)??throw new ApiException(404,"该学生不在本课次名单中");
            var questionIds=await db.Questions.Where(x=>x.HandoutId==s.HandoutId).Select(x=>x.Id).ToArrayAsync();
            Guard.That(r.QuestionIds!=null,"请选择有效题目列表");
            Guard.That(r.QuestionIds!.Distinct().Count()==r.QuestionIds.Length&&r.QuestionIds.All(questionIds.Contains),"题目不属于该课次",403);
            var notes=r.Notes??new();Guard.That(notes.Keys.All(questionIds.Contains)&&notes.Values.All(x=>x!=null&&x.Length<=200),"错因备注限200字，且需属于当前讲次");
            notes=notes.Where(x=>r.QuestionIds.Contains(x.Key)&&!string.IsNullOrWhiteSpace(x.Value)).OrderBy(x=>x.Key).ToDictionary(x=>x.Key,x=>x.Value.Trim());
            var wrongJson=Json.Write(r.QuestionIds.Order().ToArray());var notesJson=Json.Write(notes);
            if(mark.Reviewed&&mark.WrongIdsJson==wrongJson&&mark.NotesJson==notesJson)return Mark(mark);
            await using var tx=await db.Database.BeginTransactionAsync();
            Guard.That(await db.ClassSessionMarks.Where(x=>x.Id==mark.Id&&x.Revision==r.Revision).ExecuteUpdateAsync(u=>u.SetProperty(x=>x.WrongIdsJson,wrongJson).SetProperty(x=>x.NotesJson,notesJson).SetProperty(x=>x.Reviewed,true).SetProperty(x=>x.Revision,x=>x.Revision+1).SetProperty(x=>x.RecordedBy,a.User.Id))==1,"该学生标记已更新，请刷新后重试",409);
            var old=Json.Read<Guid[]>(mark.WrongIdsJson);var changed=old.Union(r.QuestionIds).Where(q=>old.Contains(q)!=r.QuestionIds.Contains(q));
            foreach(var q in changed)db.LearningEvents.Add(new LearningEvent{StudentId=studentId,QuestionId=q,ClassId=s.ClassId,CourseId=s.CourseId,RecordedBy=a.User.Id,State=r.QuestionIds.Contains(q)?"wrong":"unmarked",RequestKey=$"class:{id}:{r.Revision+1}"});
            // Keep legacy/history views current without erasing marks from other handouts.
            await db.WrongQuestions.Where(x=>x.StudentId==studentId&&questionIds.Contains(x.QuestionId)).ExecuteDeleteAsync();
            db.WrongQuestions.AddRange(r.QuestionIds.Select(q=>new WrongQuestion{StudentId=studentId,QuestionId=q,RecordedBy=a.User.Id}));
            // Bulk CAS bypasses tracked values; reload rather than resetting Modified to
            // Unchanged, which can restore the pre-save values returned to the client.
            await db.Entry(mark).ReloadAsync();
            db.Audits.Add(new Audit {StaffId=a.User.Id,Action="class-session.mark",TargetId=studentId});await db.SaveChangesAsync();await tx.CommitAsync();return Mark(mark);
        });
        app.MapPost("/api/class-sessions/{id:guid}/exports",async(Guid id,ClassExportInput r,Data db,Access a)=>{
            var s=await Scope(id,db,a);Guard.That(r.Layout is "analysis" or "compilation","请选择有效打印版本");Guard.That(r.RequestKey is {Length: >0 and <=120},"缺少生成标识");
            Guard.That(r.StudentIds is {Length: >0 and <=100}&&r.StudentIds.Distinct().Count()==r.StudentIds.Length,"请选择学生");
            var marks=await db.ClassSessionMarks.Where(x=>x.ClassSessionId==id&&r.StudentIds.Contains(x.StudentId)).ToArrayAsync();Guard.That(marks.Length==r.StudentIds.Length,"学生不属于该课次",403);
            var questions=await db.Questions.Where(x=>x.HandoutId==s.HandoutId).OrderBy(x=>x.Order).ToArrayAsync();
            if(r.QuestionIds!=null)Guard.That(r.StudentIds.Length==1&&r.QuestionIds.Length>0&&r.QuestionIds.Distinct().Count()==r.QuestionIds.Length&&r.QuestionIds.All(q=>questions.Any(x=>x.Id==q)),"请选择该学生的错题",403);
            var requestHash=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(Json.Write(new{students=r.StudentIds.Order(),questions=r.QuestionIds?.Order(),r.Layout}))));
            // Older mini-program pages reused their module-level key when reopened for another
            // student. Scope generation (not print submission) by the immutable selection as
            // well, while retaining exact retries of batches created before this change.
            var legacyBatchId=Identity($"session-export:{a.User.Id}:{id}:{r.RequestKey}");
            var legacy=await db.Documents.Where(x=>x.BatchId==legacyBatchId).ToArrayAsync();
            if(legacy.Length>0&&legacy.All(x=>x.MaterialKind==requestHash))return new{batchId=legacyBatchId,documents=legacy.Select(x=>new{x.Id,x.StudentName,x.State})};
            var batchId=Identity($"session-export:{a.User.Id}:{id}:{r.RequestKey}:{requestHash}");
            var existing=await db.Documents.Where(x=>x.BatchId==batchId).ToArrayAsync();if(existing.Length>0){Guard.That(existing.All(x=>x.MaterialKind==requestHash),"生成标识已用于其他选择",409);return new{batchId,documents=existing.Select(x=>new{x.Id,x.StudentName,x.State})};}
            var docs=new List<ExportDocument>();var lecture=await a.Lecture((await a.Handout(s.HandoutId,true)).LectureId);
            foreach(var mark in marks){
                var wrong=Json.Read<Guid[]>(mark.WrongIdsJson);if(r.QuestionIds!=null)Guard.That(r.QuestionIds.All(wrong.Contains),"只能打印已登记的错题");
                var picked=r.QuestionIds??wrong;if(picked.Length==0)continue;var notes=Json.Read<Dictionary<Guid,string>>(mark.NotesJson);
                var student=await db.Students.SingleAsync(x=>x.Id==mark.StudentId&&x.ClassId==s.ClassId);
                var d=new ExportDocument{Id=Identity($"{batchId}:{student.Id}"),BatchId=batchId,ClassSessionId=id,Layout=r.Layout,MaterialKind=requestHash,StaffId=a.User.Id,ClassId=s.ClassId,CourseId=s.CourseId,StudentId=student.Id,StudentName=student.Name,SnapshotJson=Json.Write(questions.Where(q=>picked.Contains(q.Id)).Select(q=>new QuestionSnapshot(q.Id,q.HandoutId,q.Label,$"第{lecture.Sequence}讲",q.BlocksJson,q.HalfPage,notes.GetValueOrDefault(q.Id,""))))};
                docs.Add(d);db.Documents.Add(d);db.Work.Add(new WorkItem{Kind="export",EntityId=d.Id});
            }
            Guard.That(docs.Count>0,"所选学生暂无错题");db.Audits.Add(new Audit{StaffId=a.User.Id,Action="class-session.export",TargetId=id});
            try{await db.SaveChangesAsync();}catch(DbUpdateException){db.ChangeTracker.Clear();var recovered=await db.Documents.Where(x=>x.BatchId==batchId).ToArrayAsync();if(recovered.Length==0)throw;Guard.That(recovered.All(x=>x.MaterialKind==requestHash),"生成标识已用于其他选择",409);return new{batchId,documents=recovered.Select(x=>new{x.Id,x.StudentName,x.State})};}
            return new{batchId,documents=docs.Select(x=>new{x.Id,x.StudentName,x.State})};
        });
    }
}
