using Microsoft.EntityFrameworkCore;
namespace Siyue;

public static class UsageComparison
{
    static readonly TimeSpan Beijing=TimeSpan.FromHours(8);
    static readonly HashSet<string> CoreActions=["class-session.mark","wrong-questions.save","class-session.export","export.create","history.export","student.retest","handout.publish","handout.confirm","handout.confirm-all","handout.verify-selected","book.page-split","book.split","handout.upload","book.upload","file.upload","print.submit","print.reprint","material-bank.paper.create","teaching.analysis.completed","teaching.lesson.completed","teaching.essays.completed"];
    static readonly HashSet<string> MiniCore=["session.mark","wrong.save","session.export","export.create","history.export","history.retest","handout.publish","handout.confirm","book.page-split","handout.upload","book.upload","file.upload","print.submit","print.reprint","bank.paper","teaching.analysis.generate","teaching.lesson.generate","teaching.essays.generate"];
    public static bool IsCore(string code)=>CoreActions.Contains(code)||code.StartsWith("usage.miniapp.")&&MiniCore.Contains(code[14..]);
    static string Day(DateTimeOffset at)=>at.ToOffset(Beijing).ToString("yyyy-MM-dd");
    public sealed record Location(Guid Id,string Name,string City,string Region);
    public sealed record Teacher(Guid Id,string Name,bool? Included,Guid? CampusId,string Campus,string City,string Region,int TeachingDays,string[] Days,int MarkCount,int StudentsCovered,int ExportBatches,int ReadyDocuments,int PrintTasks,int Submitted,bool WorkflowUsed,DateTimeOffset? LastTeachingAt,Location[] ActivityCampuses);
    public sealed record Campus(Guid Id,string Name,string City,string Region,bool RosterConfirmed,int ExpectedTeachers,int ActiveTeachers,int TeachingDays,int MarkCount,int ExportBatches,int ReadyDocuments,int PrintTasks,int Submitted,int Uncertain,int Failed,int Pending,double? UsageRate,Guid[] TeacherIds,Guid[] ObservedTeacherIds,int ClassExportBatches,int ClassReadyDocuments);
    public sealed record Group(string Key,string City,string Region,int Campuses,int ConfirmedCampuses,int ExpectedTeachers,int ActiveTeachers,double? UsageRate,int ExportBatches,int PrintTasks,int Uncertain);
    public static Group[] Groups(IEnumerable<Campus> campuses,string level)=>campuses.GroupBy(c=>level=="city"?c.City:c.City+"\u001f"+c.Region).Select(g=>{
        var expected=g.Sum(c=>c.ExpectedTeachers);var active=g.Sum(c=>c.ActiveTeachers);
        return new Group(g.Key,g.First().City,level=="city"?"":g.First().Region,g.Count(),g.Count(c=>c.RosterConfirmed),expected,active,g.All(c=>c.RosterConfirmed)&&expected>0?(double)active/expected:null,g.Sum(c=>c.ExportBatches),g.Sum(c=>c.PrintTasks),g.Sum(c=>c.Uncertain));
    }).OrderByDescending(g=>g.UsageRate.HasValue).ThenByDescending(g=>g.UsageRate).ThenBy(g=>g.City).ThenBy(g=>g.Region).ToArray();
    public static void Map(WebApplication app){
        UsageOrganizationStore.Map(app);
        app.MapGet("/api/admin/usage-comparison",async(string start,string end,Data db,Access access,UsageOrganizationStore store,CancellationToken ct)=>{
            access.Admin();var(since,until)=UsageStatistics.Range(start,end);var settings=await store.Read(ct);
            var staff=await db.Staff.AsNoTracking().Where(s=>s.Enabled&&s.Role=="teacher"&&s.Name!="test_teacher").Select(s=>new{s.Id,s.Name}).ToListAsync(ct);var ids=staff.Select(s=>s.Id).ToArray();
            var raw=await db.Audits.AsNoTracking().Where(a=>a.StaffId.HasValue&&ids.Contains(a.StaffId.Value)&&a.CreatedAt>=since&&a.CreatedAt<until).Select(a=>new{StaffId=a.StaffId!.Value,a.Action,a.TargetId,a.CreatedAt}).ToListAsync(ct);
            var core=raw.Where(a=>IsCore(a.Action)).ToLookup(a=>a.StaffId);
            var students=await db.Students.AsNoTracking().Select(s=>new{s.Id,s.ClassId}).ToListAsync(ct);var studentIds=students.Select(s=>s.Id).ToHashSet();
            var docs=await db.Documents.IgnoreQueryFilters().AsNoTracking().Where(d=>ids.Contains(d.StaffId)&&d.CreatedAt>=since&&d.CreatedAt<until&&d.State=="ready"&&d.Kind!="file").Select(d=>new{d.Id,d.StaffId,d.BatchId,d.StudentId,d.ClassId,d.CreatedAt}).ToListAsync(ct);var byDoc=docs.ToLookup(d=>d.StaffId);
            var jobs=await (from j in db.PrintJobs.AsNoTracking() join p in db.Printers.AsNoTracking() on j.PrinterId equals p.Id into ps from p in ps.DefaultIfEmpty() where j.CreatedAt>=since&&j.CreatedAt<until select new{j.StaffId,j.State,j.CreatedAt,CampusId=p==null?(Guid?)null:p.CampusId}).ToListAsync(ct);var byJob=jobs.ToLookup(j=>j.StaffId);
            var campuses=await db.Campuses.AsNoTracking().Select(c=>new{c.Id,c.Name,c.City}).ToListAsync(ct);var campusMap=campuses.ToDictionary(c=>c.Id);var memberMap=settings.Teachers.ToDictionary(t=>t.Id);var orgMap=settings.Campuses.ToDictionary(c=>c.Id);
            var classCampus=await db.Classes.IgnoreQueryFilters().AsNoTracking().ToDictionaryAsync(c=>c.Id,c=>c.CampusId,ct);var targetCampus=new Dictionary<Guid,Guid>(classCampus);
            foreach(var student in students)if(classCampus.TryGetValue(student.ClassId,out var campus))targetCampus[student.Id]=campus;
            foreach(var session in await db.ClassSessions.AsNoTracking().Select(s=>new{s.Id,s.ClassId}).ToListAsync(ct))if(classCampus.TryGetValue(session.ClassId,out var campus))targetCampus[session.Id]=campus;
            foreach(var book in await db.BookImports.AsNoTracking().Select(s=>new{s.Id,s.ClassId}).ToListAsync(ct))if(book.ClassId is Guid cl&&classCampus.TryGetValue(cl,out var campus))targetCampus[book.Id]=campus;
            foreach(var handout in await (from h in db.Handouts.AsNoTracking() join l in db.Lectures.AsNoTracking() on h.LectureId equals l.Id where l.ClassId!=null select new{h.Id,ClassId=l.ClassId!.Value}).ToListAsync(ct))if(classCampus.TryGetValue(handout.ClassId,out var campus))targetCampus[handout.Id]=campus;
            var observed=new Dictionary<Guid,HashSet<Guid>>();
            void Observe(Guid teacher,Guid? campus){if(campus is not Guid c||!campusMap.ContainsKey(c)||!ids.Contains(teacher))return;if(!observed.TryGetValue(teacher,out var set))observed[teacher]=set=[];set.Add(c);}
            foreach(var a in raw.Where(a=>IsCore(a.Action)))if(targetCampus.TryGetValue(a.TargetId,out var campus))Observe(a.StaffId,campus);
            var documentCampus=new Dictionary<Guid,Guid>();
            foreach(var d in docs){Guid? campus=d.ClassId is Guid cl&&classCampus.TryGetValue(cl,out var ca)?ca:d.StudentId is Guid sid&&targetCampus.TryGetValue(sid,out var sc)?sc:null;if(campus.HasValue)documentCampus[d.Id]=campus.Value;Observe(d.StaffId,campus);}
            foreach(var j in jobs)Observe(j.StaffId,j.CampusId);
            Location Place(Guid id){var c=campusMap[id];var o=orgMap.GetValueOrDefault(id);return new(id,c.Name,string.IsNullOrWhiteSpace(o?.City)?string.IsNullOrWhiteSpace(c.City)?"城市待补充":c.City:o.City,string.IsNullOrWhiteSpace(o?.Region)?"区域待补充":o.Region);}
            var teachers=staff.Select(s=>{
                memberMap.TryGetValue(s.Id,out var member);var home=member?.CampusId is Guid id?campusMap.GetValueOrDefault(id):null;var org=home==null?null:orgMap.GetValueOrDefault(home.Id);
                var events=core[s.Id].ToArray();var generated=byDoc[s.Id].ToArray();var printed=byJob[s.Id].ToArray();
                var times=events.Select(a=>a.CreatedAt).Concat(generated.Select(d=>d.CreatedAt)).Concat(printed.Select(j=>j.CreatedAt)).ToArray();var days=times.Select(Day).Distinct().Order().ToArray();
                var marks=events.Where(a=>a.Action is "class-session.mark" or "wrong-questions.save").ToArray();
                var covered=marks.Where(a=>a.Action=="class-session.mark"&&studentIds.Contains(a.TargetId)).Select(a=>a.TargetId).Concat(generated.Where(d=>d.StudentId.HasValue).Select(d=>d.StudentId!.Value)).Distinct().Count();
                var batches=generated.Select(d=>d.BatchId==Guid.Empty?d.Id:d.BatchId).Distinct().Count();
                return new Teacher(s.Id,s.Name,member?.Included,home?.Id,home?.Name??"归属待核对",(org?.City??home?.City) is string city&&city.Length>0?city:"城市待补充",org?.Region is string region&&region.Length>0?region:"区域待补充",days.Length,days,marks.Length,covered,batches,generated.Length,printed.Length,printed.Count(j=>j.State=="submitted"),marks.Length>0&&batches>0&&covered>0,times.Length==0?null:times.Max(),observed.GetValueOrDefault(s.Id,[]).Select(Place).OrderBy(c=>c.Name).ToArray());
            }).OrderByDescending(t=>t.TeachingDays).ThenByDescending(t=>t.StudentsCovered).ThenByDescending(t=>t.ExportBatches).ThenBy(t=>t.Id).ToArray();
            var groups=campuses.Select(c=>{
                var config=orgMap.GetValueOrDefault(c.Id);var members=teachers.Where(t=>t.Included==true&&t.CampusId==c.Id).ToArray();var prints=jobs.Where(j=>j.CampusId==c.Id).ToArray();var active=members.Count(t=>t.TeachingDays>0);
                var city=string.IsNullOrWhiteSpace(config?.City)?c.City:config.City;var region=config?.Region??"";var confirmed=config?.RosterConfirmed==true&&!string.IsNullOrWhiteSpace(city)&&!string.IsNullOrWhiteSpace(region);
                var classDocs=docs.Where(d=>documentCampus.GetValueOrDefault(d.Id)==c.Id).ToArray();
                return new Campus(c.Id,c.Name,string.IsNullOrWhiteSpace(city)?"城市待补充":city,string.IsNullOrWhiteSpace(region)?"区域待补充":region,confirmed,members.Length,active,members.Sum(t=>t.TeachingDays),members.Sum(t=>t.MarkCount),members.Sum(t=>t.ExportBatches),members.Sum(t=>t.ReadyDocuments),prints.Length,prints.Count(j=>j.State=="submitted"),prints.Count(j=>j.State=="uncertain"),prints.Count(j=>j.State=="failed"),prints.Count(j=>j.State is "queued" or "preparing" or "claimed" or "submitting"),confirmed&&members.Length>0?(double)active/members.Length:null,members.Select(t=>t.Id).ToArray(),observed.Where(o=>o.Value.Contains(c.Id)).Select(o=>o.Key).ToArray(),classDocs.Select(d=>d.BatchId==Guid.Empty?d.Id:d.BatchId).Distinct().Count(),classDocs.Length);
            }).OrderByDescending(c=>c.UsageRate.HasValue).ThenByDescending(c=>c.UsageRate).ThenBy(c=>c.City).ThenBy(c=>c.Region).ThenBy(c=>c.Name).ToArray();
            return Results.Ok(new{start,end,SnapshotAt=Clock.Now,OrganizationRevision=settings.Revision,Teachers=teachers,Campuses=groups,Cities=Groups(groups,"city"),Regions=Groups(groups,"region"),Coverage=new{Accounts=teachers.Length,Expected=teachers.Count(t=>t.Included==true&&t.CampusId.HasValue),Excluded=teachers.Count(t=>t.Included==false),Pending=teachers.Count(t=>t.Included==null||t.Included==true&&!t.CampusId.HasValue),ConfirmedCampuses=groups.Count(c=>c.RosterConfirmed),UnassignedPrints=jobs.Count(j=>!j.CampusId.HasValue)}});
        });
    }
}
