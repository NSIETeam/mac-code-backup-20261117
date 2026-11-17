using Microsoft.EntityFrameworkCore;
using System.Text.RegularExpressions;
namespace Siyue;

// Reporting aliases only: no changes to classes, device bindings or source records.
public static class UsageDashboard
{
    public sealed record Place(Guid Id,string Name,string City,Guid[] SourceIds,string[] Aliases);
    public sealed record Use(Guid Teacher,Guid Class,string Day,string Kind,Guid Evidence,Guid? Student=null,Guid? Batch=null,string State="");
    public sealed record Metrics(int Teachers,int ActiveTeachers,double? UsageRate,int Classes,int ActiveClasses,int TeachingDays,int MarkCount,int StudentsCovered,int ExportBatches,int ReadyDocuments,int PrintTasks,int Submitted,int Uncertain,int Failed);
    public static bool IsTeachingClass(string name)=>!Regex.IsMatch(name,"测试|内测|验收|演练|模拟|非教学|设备诊断",RegexOptions.CultureInvariant);
    public static string CampusName(string name){
        var n=Regex.Replace(name.Trim(),@"\s+","").Replace("写字楼","");
        foreach(var city in new[]{"济南","广州"})if(n.StartsWith(city))n=n[city.Length..];
        if(n.EndsWith("校区"))n=n[..^2];
        return n;
    }
    public static Place[] Places(IEnumerable<Campus> source){
        var all=source.ToArray();
        string City(Campus c)=>c.Name.Contains("万达")?"济南":c.City.Trim().TrimEnd('市');
        return all.GroupBy(c=>CampusName(c.Name)).SelectMany(g=>{
            var known=g.Select(City).Where(x=>x.Length>0).Distinct().ToArray();
            // A missing city inherits only an unambiguous existing alias city.
            return g.GroupBy(c=>City(c).Length>0?City(c):known.Length==1?known[0]:"城市未登记").Select(s=>new Place(s.OrderBy(x=>x.Archived).ThenBy(x=>x.Name.Length).ThenBy(x=>x.Id).First().Id,g.Key,s.Key,s.Select(c=>c.Id).ToArray(),s.Select(c=>c.Name).Distinct().Order().ToArray()));
        }).OrderBy(x=>x.City).ThenBy(x=>x.Name).ToArray();
    }
    public static Metrics Measure(IEnumerable<Use> records,IEnumerable<Guid> roster,IEnumerable<Guid> classes){
        var r=records.ToArray();var active=r.Select(x=>x.Teacher).Distinct().Count();var teachers=roster.Concat(r.Select(x=>x.Teacher)).Distinct().Count();var docs=r.Where(x=>x.Kind=="document").ToArray();var jobs=r.Where(x=>x.Kind=="print").DistinctBy(x=>x.Evidence).ToArray();
        return new(teachers,active,teachers>0?(double)active/teachers:null,classes.Distinct().Count(),r.Select(x=>x.Class).Distinct().Count(),r.Select(x=>x.Day).Distinct().Count(),r.Where(x=>x.Kind=="mark").Select(x=>x.Evidence).Distinct().Count(),r.Where(x=>x.Student.HasValue).Select(x=>x.Student).Distinct().Count(),docs.Select(x=>x.Batch??x.Evidence).Distinct().Count(),docs.Select(x=>x.Evidence).Distinct().Count(),jobs.Length,jobs.Count(x=>x.State=="submitted"),jobs.Count(x=>x.State=="uncertain"),jobs.Count(x=>x.State=="failed"));
    }
    public static void Map(WebApplication app)=>app.MapGet("/api/admin/usage-dashboard",async(string start,string end,string? city,Guid? campusId,bool? posters,Data db,Access access,UsageOrganizationStore organization,CancellationToken ct)=>{
        access.Admin();var(since,until)=UsageStatistics.Range(start,end);
        if (posters == true)
        {
            Guard.That((until-since).TotalDays <= 31,"海报日期范围最多为31天");
            var posterDays = Enumerable.Range(0, (int)(until-since).TotalDays)
                .Select(i => UsagePosters.Day(since.AddDays(i))).ToArray();
            var report = await UsagePosters.Read(db, since, until, posterDays, ct, await organization.Read(ct));
            return Results.Ok(new { Start=start,End=end,SnapshotAt=Clock.Now,report.Branches,report.Teachers,report.EligibleTeachers,report.HeatmapStart,Days=posterDays });
        }
        var staff=await db.Staff.AsNoTracking().Where(s=>s.Enabled&&s.Role=="teacher"&&s.Name!="test_teacher").Select(s=>new{s.Id,s.Name}).ToListAsync(ct);var staffIds=staff.Select(s=>s.Id).ToHashSet();
        var places=Places(await db.Campuses.IgnoreQueryFilters().AsNoTracking().ToListAsync(ct));var placeBySource=places.SelectMany(p=>p.SourceIds.Select(id=>(id,p))).ToDictionary(x=>x.id,x=>x.p);
        var allClasses=await db.Classes.IgnoreQueryFilters().AsNoTracking().ToListAsync(ct);
        var teaching=allClasses.Where(c=>IsTeachingClass(c.Name)&&placeBySource.ContainsKey(c.CampusId)).ToDictionary(c=>c.Id);
        var students=await db.Students.AsNoTracking().Select(s=>new{s.Id,s.ClassId}).ToListAsync(ct);var studentMap=students.ToDictionary(s=>s.Id,s=>s.ClassId);
        var sessions=await db.ClassSessions.AsNoTracking().Select(s=>new{s.Id,s.ClassId}).ToListAsync(ct);var sessionMap=sessions.ToDictionary(s=>s.Id,s=>s.ClassId);
        var lectures=await db.Lectures.AsNoTracking().Where(l=>l.ClassId.HasValue).Select(l=>new{l.Id,ClassId=l.ClassId!.Value}).ToListAsync(ct);var lectureMap=lectures.ToDictionary(l=>l.Id,l=>l.ClassId);
        var documents=await db.Documents.IgnoreQueryFilters().AsNoTracking().Select(d=>new{d.Id,d.StaffId,d.ClassId,d.ClassSessionId,d.StudentId,d.LectureId,d.BatchId,d.CreatedAt,d.Kind,d.State}).ToListAsync(ct);
        Guid? ClassOf(Guid? cls,Guid? session,Guid? student,Guid? lecture)=>session.HasValue&&sessionMap.TryGetValue(session.Value,out var sc)?sc:cls??(student.HasValue&&studentMap.TryGetValue(student.Value,out var st)?st:lecture.HasValue&&lectureMap.TryGetValue(lecture.Value,out var lc)?lc:null);
        var docClass=documents.ToDictionary(d=>d.Id,d=>ClassOf(d.ClassId,d.ClassSessionId,d.StudentId,d.LectureId));
        var targets=new Dictionary<Guid,HashSet<Guid>>();
        void Target(Guid id,Guid? cl){if(cl is not Guid c||!teaching.ContainsKey(c))return;if(!targets.TryGetValue(id,out var set))targets[id]=set=[];set.Add(c);}
        foreach(var c in teaching.Values)Target(c.Id,c.Id);
        foreach(var s in students)Target(s.Id,s.ClassId);
        foreach(var s in sessions)Target(s.Id,s.ClassId);
        foreach(var l in lectures)Target(l.Id,l.ClassId);
        foreach(var d in documents){Target(d.Id,docClass[d.Id]);if(d.BatchId!=Guid.Empty)Target(d.BatchId,docClass[d.Id]);}
        foreach(var h in await db.Handouts.AsNoTracking().Select(h=>new{h.Id,h.LectureId}).ToListAsync(ct))if(lectureMap.TryGetValue(h.LectureId,out var c))Target(h.Id,c);
        foreach(var b in await db.BookImports.AsNoTracking().Select(b=>new{b.Id,b.ClassId}).ToListAsync(ct))Target(b.Id,b.ClassId);
        var events=new List<Use>();string Day(DateTimeOffset at)=>at.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd");
        void Add(Guid teacher,Guid? cls,DateTimeOffset at,string kind,Guid id,Guid? student=null,Guid? batch=null,string state=""){
            if(staffIds.Contains(teacher)&&cls is Guid c&&teaching.ContainsKey(c))events.Add(new(teacher,c,Day(at),kind,id,student,batch,state));
        }
        var audits=await db.Audits.AsNoTracking().Where(a=>a.StaffId.HasValue&&a.CreatedAt>=since&&a.CreatedAt<until).Select(a=>new{a.Id,a.StaffId,a.TargetId,a.Action,a.CreatedAt}).ToListAsync(ct);
        var unresolved=0;
        foreach(var a in audits.Where(a=>UsageComparison.IsCore(a.Action)&&!a.Action.Contains("print.")&&staffIds.Contains(a.StaffId!.Value))){
            if(!targets.TryGetValue(a.TargetId,out var cs)){unresolved++;continue;}
            foreach(var c in cs)Add(a.StaffId!.Value,c,a.CreatedAt,a.Action is "class-session.mark" or "wrong-questions.save"?"mark":"action",a.Id,a.Action=="class-session.mark"&&studentMap.ContainsKey(a.TargetId)?a.TargetId:null);
        }
        foreach(var d in documents.Where(d=>d.CreatedAt>=since&&d.CreatedAt<until&&d.State=="ready"&&d.Kind!="file"))Add(d.StaffId,docClass[d.Id],d.CreatedAt,"document",d.Id,d.StudentId,d.BatchId==Guid.Empty?d.Id:d.BatchId);
        var jobs=await db.PrintJobs.AsNoTracking().Where(j=>j.CreatedAt>=since&&j.CreatedAt<until).Select(j=>new{j.Id,j.StaffId,j.DocumentId,j.CreatedAt,j.State}).ToListAsync(ct);
        foreach(var j in jobs)Add(j.StaffId,docClass.GetValueOrDefault(j.DocumentId),j.CreatedAt,"print",j.Id,state:j.State);
        var usedClasses=events.Select(x=>x.Class).ToHashSet();var classes=teaching.Values.Where(c=>!c.Archived||usedClasses.Contains(c.Id)).ToArray();
        var days=Enumerable.Range(0,(int)(until-since).TotalDays).Select(i=>Day(since.AddDays(i))).ToArray();
        var assignments=await db.Assignments.AsNoTracking().Select(a=>new{a.ClassId,a.StaffId}).Distinct().ToListAsync(ct);
        var roster=assignments.Where(a=>staffIds.Contains(a.StaffId)&&teaching.TryGetValue(a.ClassId,out var c)&&!c.Archived).Select(a=>(Teacher:a.StaffId,Class:a.ClassId)).Concat(events.Select(x=>(x.Teacher,x.Class))).Distinct().ToArray();
        var relevant=classes.Select(c=>placeBySource[c.CampusId].Id).ToHashSet();var available=places.Where(p=>relevant.Contains(p.Id)).ToArray();
        var selected=available.Where(p=>(string.IsNullOrEmpty(city)||p.City==city)&&(!campusId.HasValue||p.Id==campusId||p.SourceIds.Contains(campusId.Value))).ToArray();var selectedIds=selected.Select(p=>p.Id).ToHashSet();
        var selectedClasses=classes.Where(c=>selectedIds.Contains(placeBySource[c.CampusId].Id)).ToArray();var classIds=selectedClasses.Select(c=>c.Id).ToHashSet();var uses=events.Where(x=>classIds.Contains(x.Class)).ToArray();var people=roster.Where(x=>classIds.Contains(x.Class)).ToArray();
        object[] Daily(Use[] rows)=>days.Select(day=>{var r=rows.Where(x=>x.Day==day).ToArray();return (object)new{Day=day,ActiveTeachers=r.Select(x=>x.Teacher).Distinct().Count(),ReadyDocuments=r.Where(x=>x.Kind=="document").Select(x=>x.Evidence).Distinct().Count()};}).ToArray();
        var campusRows=selected.Select(p=>{var cls=selectedClasses.Where(c=>p.SourceIds.Contains(c.CampusId)).Select(c=>c.Id).ToHashSet();var rows=uses.Where(x=>cls.Contains(x.Class)).ToArray();return new{p.Id,p.Name,p.City,p.Aliases,p.SourceIds,Metrics=Measure(rows,people.Where(x=>cls.Contains(x.Class)).Select(x=>x.Teacher),cls),Daily=Daily(rows)};}).OrderByDescending(x=>x.Metrics.ActiveTeachers).ThenByDescending(x=>x.Metrics.ReadyDocuments).ThenBy(x=>x.Name).ToArray();
        var teacherRows=staff.Where(t=>people.Any(x=>x.Teacher==t.Id)).Select(t=>{var cls=people.Where(x=>x.Teacher==t.Id).Select(x=>x.Class).ToHashSet();var rows=uses.Where(x=>x.Teacher==t.Id).ToArray();return new{t.Id,t.Name,Metrics=Measure(rows,[t.Id],cls),Days=rows.Select(x=>x.Day).Distinct().Order().ToArray(),Campuses=selected.Where(p=>selectedClasses.Any(c=>cls.Contains(c.Id)&&p.SourceIds.Contains(c.CampusId))).Select(p=>new{p.Id,p.Name,p.City}).ToArray(),Classes=selectedClasses.Where(c=>cls.Contains(c.Id)).Select(c=>new{c.Id,c.Name,CampusId=placeBySource[c.CampusId].Id}).ToArray()};}).OrderByDescending(t=>t.Metrics.TeachingDays).ThenByDescending(t=>t.Metrics.StudentsCovered).ThenByDescending(t=>t.Metrics.ExportBatches).ToArray();
        return Results.Ok(new{Start=start,End=end,SnapshotAt=Clock.Now,AvailableCampuses=available,Summary=Measure(uses,people.Select(x=>x.Teacher),classIds),Campuses=campusRows,Teachers=teacherRows,Daily=Daily(uses),Classes=selectedClasses.Select(c=>new{c.Id,c.Name,CampusId=placeBySource[c.CampusId].Id,Campus=placeBySource[c.CampusId].Name,c.Archived,Teachers=people.Where(x=>x.Class==c.Id).Select(x=>x.Teacher).Distinct().Count(),Metrics=Measure(uses.Where(x=>x.Class==c.Id),people.Where(x=>x.Class==c.Id).Select(x=>x.Teacher),[c.Id])}),Coverage=new{ExcludedTestClasses=allClasses.Count(c=>!IsTeachingClass(c.Name)),UnattributedActions=unresolved,UnattributedPrints=jobs.Count(j=>staffIds.Contains(j.StaffId)&&(!docClass.TryGetValue(j.DocumentId,out var c)||!c.HasValue)),AliasesMerged=available.Sum(p=>p.SourceIds.Length-1)}});
    });
}
