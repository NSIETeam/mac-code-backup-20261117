using Microsoft.EntityFrameworkCore;
namespace Siyue;

public sealed record BankLearnerQuery(Guid ClassId,Guid CourseId,Guid[] StudentIds,string Subject="",string Stage="",string Topic="",string Type="",Guid[]? CourseIds=null);
public static class QuestionBankLearners
{
    public static void Map(WebApplication app) {
        app.MapGet("/api/question-bank/learner-groups",async(Access access,Data db)=>{
            var actor=access.User;
            var rows=await (from assignment in db.Assignments.AsNoTracking()
                join schoolClass in db.Classes on assignment.ClassId equals schoolClass.Id
                join course in db.Courses on assignment.CourseId equals course.Id
                where actor.Role=="admin"||assignment.StaffId==actor.Id
                select new {classId=schoolClass.Id,courseId=course.Id,className=schoolClass.Name,courseName=course.Name,studentCount=db.Students.Count(s=>s.ClassId==schoolClass.Id)})
                .Distinct().OrderBy(x=>x.className).ThenBy(x=>x.courseName).Take(500).ToArrayAsync();
            return rows.Select(r=>{var subjects=LocalQuestionBank.Subjects.Where(r.courseName.Contains).ToArray();return new{r.classId,r.courseId,r.className,r.courseName,r.studentCount,subject=subjects.Length==1?subjects[0]:"",stage=StageOf(r.className,r.courseName)};});
        });
        app.MapGet("/api/question-bank/learners",ListLearners);
        app.MapPost("/api/question-bank/learners/recommend",async(BankLearnerQuery input,Access access,Data db,IQuestionBank bank)=>Results.Ok(await Select(input,access,db,bank)));

    }
    public sealed record BankLearnerBatch(string Mode,string Topic,string Subject,string Stage,string ClassName,string CourseName,BankLearnerRecommendation[] Groups);
    internal static async Task<BankLearnerBatch> Select(BankLearnerQuery input,Access access,Data db,IQuestionBank bank,int limit=5,int maxStudents=10){
        Guard.That(limit is >=1 and <=20,"每份试卷最多20题");
            var courseIds=new[]{input.CourseId}.Concat(input.CourseIds??[]).Distinct().ToArray();Guard.That(courseIds.Length<=20,"同一科目课程过多，请缩小范围");
            foreach(var id in courseIds)await access.Class(input.ClassId,id);
            Guard.That(input.StudentIds is {Length:>0}&&input.StudentIds.Length<=maxStudents&&input.StudentIds.Distinct().Count()==input.StudentIds.Length,"学员人数超出本次出题范围");
            var students=await db.Students.AsNoTracking().Where(s=>s.ClassId==input.ClassId&&input.StudentIds.Contains(s.Id)).OrderBy(s=>s.Name).ToArrayAsync();
            Guard.That(students.Length==input.StudentIds.Length,"学员不属于所选班级",403);
            Guard.That(input.Topic is {Length:<=10000},"知识点最多10000字");
            var automatic=string.IsNullOrWhiteSpace(input.Topic);
            var schoolClass=await db.Classes.AsNoTracking().SingleAsync(c=>c.Id==input.ClassId);
            var courses=await db.Courses.AsNoTracking().Where(c=>courseIds.Contains(c.Id)).ToArrayAsync();Guard.That(courses.Length==courseIds.Length,"课程不存在",404);
            var subjects=LocalQuestionBank.Subjects.Where(s=>courses.Any(c=>c.Name.Contains(s,StringComparison.Ordinal))).ToArray();
            var subject=string.IsNullOrWhiteSpace(input.Subject)&&subjects.Length==1?subjects[0]:input.Subject;
            Guard.That(subjects.Length!=1||subjects[0]==subject,"所选学科与学员课程不一致");
            Guard.That(!automatic||subjects.Length<=1,"请选择同一学科的课程");
            if(courseIds.Length>1)Guard.That(courses.All(c=>{var found=LocalQuestionBank.Subjects.Where(c.Name.Contains).ToArray();return found.Length==1&&found[0]==subject;}),"请选择同一学科的课程");
            var knownStages=courses.Select(c=>StageOf(schoolClass.Name,c.Name)).Where(s=>s.Length>0).Distinct().ToArray();
            var stage=automatic?(knownStages.Length==1?knownStages[0]:""):input.Stage;
            var query=automatic?null:QuestionBankEndpoints.Validate(new(subject,stage,input.Topic,input.Type));
            // Topic mode stays compatible; automatic mode uses reviewed source errors only.
            // Neither mode writes marks, creates homework or sends messages.
            var sessions=await db.ClassSessions.AsNoTracking().Where(s=>s.ClassId==input.ClassId&&courseIds.Contains(s.CourseId)).OrderByDescending(s=>s.CreatedAt).Take(30).ToArrayAsync();var sessionIds=sessions.Select(s=>s.Id).ToArray();
            var marks=await db.ClassSessionMarks.AsNoTracking().Where(m=>sessionIds.Contains(m.ClassSessionId)&&input.StudentIds.Contains(m.StudentId)&&m.Reviewed).ToArrayAsync();
            var handoutIds=sessions.Select(s=>s.HandoutId).Distinct().ToArray();var wrongIds=marks.SelectMany(m=>Json.Read<Guid[]>(m.WrongIdsJson)).Distinct().ToArray();
            var originals=await db.Questions.AsNoTracking().Where(q=>handoutIds.Contains(q.HandoutId)&&wrongIds.Contains(q.Id)).ToDictionaryAsync(q=>q.Id);
            var lookup=query==null?null:bank.Search(query with{Limit=20});
            var sharedExcluded=maxStudents>10?originals.Values.Select(q=>LocalQuestionBank.Fingerprint(subject,Text(q))).ToHashSet():null;
            var poolCache=sharedExcluded==null?null:new Dictionary<Guid,QuestionMatch[]>();
            var groups=students.Select(student=>{
                if(automatic)return Automatic(student,subject,stage,sessions,marks,originals,bank,limit,sharedExcluded,poolCache);
                var fingerprints=marks.Where(m=>m.StudentId==student.Id).SelectMany(m=>Json.Read<Guid[]>(m.WrongIdsJson)).Where(originals.ContainsKey).Select(id=>LocalQuestionBank.Fingerprint(input.Subject,string.Join("\n",Json.Read<ContentBlock[]>(originals[id].BlocksJson).Select(b=>b.Text)))).ToHashSet();
                var matches=lookup!.Matches.Where(m=>!fingerprints.Contains(LocalQuestionBank.Fingerprint(input.Subject,m.Question.Stem))).Take(limit).ToArray();
                return new BankLearnerRecommendation(student.Id,student.Name,matches.Length,matches,matches.Length>0?"按当前知识点推荐，已排除可识别的已登记原错题":"当前知识点没有足够题目，请换一个知识点",matches.Length>0?"matched":"no_match");
            }).ToArray();
            return new(automatic?"reviewed-errors-for-selected-learners":"topic-for-selected-learners",query?.Topic??"",subject,stage,schoolClass.Name,string.Join("、",courses.Select(c=>c.Name)),groups);
    }
    public sealed record BankLearnerCourse(Guid ClassId,Guid CourseId,string ClassName,string CourseName,string Subject,string Stage);
    public sealed record BankLearnerEntry(Guid Id,string Name,string ClassName,BankLearnerCourse[] Courses);
    public sealed record BankLearnerRecommendation(Guid StudentId,string StudentName,int Returned,QuestionMatch[] Matches,string Message,string Status);
    static async Task<IResult> ListLearners(Guid? classId,Guid? courseId,string? query,Access access,Data db) {
        var actor=access.User;Guard.That(query==null||query.Length<=60,"学员搜索最多60字");var term=query?.Trim()??"";
        if(classId.HasValue||courseId.HasValue){
            Guard.That(classId.HasValue&&courseId.HasValue,"请同时选择班级与课程");await access.Class(classId!.Value,courseId!.Value);
            return Results.Ok(await db.Students.AsNoTracking().Where(s=>s.ClassId==classId.Value&&(term==""||s.Name.Contains(term)||s.Code.Contains(term))).OrderBy(s=>s.Name).ThenBy(s=>s.Id).Take(200).Select(s=>new{s.Id,s.Name}).ToArrayAsync());
        }
        var assigned=await(from a in db.Assignments.AsNoTracking() join c in db.Classes on a.ClassId equals c.Id join course in db.Courses on a.CourseId equals course.Id
            where actor.Role=="admin"||a.StaffId==actor.Id select new{classId=c.Id,className=c.Name,courseId=course.Id,courseName=course.Name}).Distinct().ToArrayAsync();
        var ids=assigned.Select(a=>a.classId).Distinct().ToArray();
        var students=await db.Students.AsNoTracking().Where(s=>ids.Contains(s.ClassId)&&(term==""||s.Name.Contains(term)||s.Code.Contains(term))).OrderBy(s=>s.Name).ThenBy(s=>s.Id).Take(200).ToArrayAsync();
        var courses=assigned.Select(a=>{var found=LocalQuestionBank.Subjects.Where(a.courseName.Contains).ToArray();return new BankLearnerCourse(a.classId,a.courseId,a.className,a.courseName,found.Length==1?found[0]:"",StageOf(a.className,a.courseName));}).GroupBy(c=>c.ClassId).ToDictionary(g=>g.Key,g=>g.OrderBy(c=>c.Subject).ThenBy(c=>c.CourseName).ToArray());
        return Results.Ok(students.Select(s=>new BankLearnerEntry(s.Id,s.Name,courses[s.ClassId][0].ClassName,courses[s.ClassId])));
    }
    static string StageOf(string className,string courseName){var stage=AcademicTraining.SuggestStage(className);return stage.Length>0?stage:AcademicTraining.SuggestStage(courseName);}
    static string Text(Question q)=>string.Join("\n",Json.Read<ContentBlock[]>(q.BlocksJson).Where(b=>b.Kind is not("image" or "drawing")).Select(b=>b.Text)).Trim();
    static BankLearnerRecommendation Automatic(Student student,string subject,string stage,ClassSession[] sessions,ClassSessionMark[] marks,Dictionary<Guid,Question> originals,IQuestionBank bank,int limit=5,HashSet<string>? sharedExcluded=null,Dictionary<Guid,QuestionMatch[]>? poolCache=null){
        BankLearnerRecommendation Empty(string status,string message)=>new(student.Id,student.Name,0,[],message,status);
        if(!LocalQuestionBank.Subjects.Contains(subject))return Empty("subject_missing","学员课程的学科尚不明确，请完善课程资料。");
        if(!LocalQuestionBank.Stages.Contains(stage))return Empty("stage_missing","学员班级的学段尚不明确，请完善班级资料。");
        var own=marks.Where(m=>m.StudentId==student.Id).ToArray();if(own.Length==0)return Empty("no_reviewed_errors","该科目暂无已核对的错题记录。");
        var sessionMap=sessions.ToDictionary(s=>s.Id);var ids=own.OrderByDescending(m=>sessionMap[m.ClassSessionId].LessonDate).SelectMany(m=>Json.Read<Guid[]>(m.WrongIdsJson).Where(id=>originals.TryGetValue(id,out var q)&&q.HandoutId==sessionMap[m.ClassSessionId].HandoutId)).ToArray();
        if(ids.Length==0)return Empty("no_wrong","近期已核对记录中没有可用错题。");
        var sourceIds=ids.GroupBy(id=>id).OrderByDescending(g=>g.Count()).Take(20).Select(g=>g.Key).ToArray();var sources=sourceIds.Select(id=>Text(originals[id])).ToArray();
        var usable=sources.Where(t=>t.Length>=4).ToArray();if(usable.Length==0)return Empty("needs_text","原错题只有图片或文字不完整，请补充题干后再推荐。");
        var excluded=sharedExcluded??ids.Distinct().Select(id=>LocalQuestionBank.Fingerprint(subject,Text(originals[id]))).ToHashSet();var used=new HashSet<string>(excluded);var matches=new List<QuestionMatch>();
        var pools=sourceIds.Where(id=>Text(originals[id]).Length>=4).Select(id=>{if(poolCache!=null&&poolCache.TryGetValue(id,out var cached))return cached;var rows=bank.Recommend(subject,stage,Text(originals[id]),[],limit,excluded);if(poolCache!=null)poolCache[id]=rows;return rows;}).ToArray();
        for(var pass=0;pass<limit&&matches.Count<limit;pass++)foreach(var pool in pools){var next=pool.FirstOrDefault(m=>!used.Contains(LocalQuestionBank.Fingerprint(subject,m.Question.Stem)));if(next==null)continue;used.Add(LocalQuestionBank.Fingerprint(subject,next.Question.Stem));matches.Add(next);if(matches.Count==limit)break;}
        return new(student.Id,student.Name,matches.Count,matches.ToArray(),matches.Count>0?"根据近期已核对错题推荐，已排除原题":"当前题库暂无足够同类题，未用无关题补足。",matches.Count>0?"matched":"no_match");
    }

}
