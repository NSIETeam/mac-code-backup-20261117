using System.Globalization;
using Microsoft.EntityFrameworkCore;
namespace Siyue;

public static class UsageStatistics
{
    static readonly TimeSpan Beijing=TimeSpan.FromHours(8);
    public static (string Source,string? Action) Describe(string raw)
    {
        if(raw.StartsWith("maintenance.") || raw.StartsWith("device.") || raw.StartsWith("printer.") || raw.StartsWith("print.guangzhou.") || raw.StartsWith("print.test.")) return ("excluded",null);
        if(raw=="identity.password-login")return ("login","账号登录");
        if(raw=="identity.password-change")return ("account","修改密码");
        var item=TeacherUsage.Describe(raw);
        if(item.Action!=null)return item;
        if(item.Source is "miniapp" or "preview")return (item.Source,raw);
        return raw switch {
            "class.archive"=>("unattributed","归档班级"),
            "students.create"=>("unattributed","批量添加学生"),
            "material-bank.paper.create"=>("unattributed","题库组卷"),
            "lecture.create"=>("unattributed","创建讲次"),
            "print.create"=>("unattributed","提交打印"),
            "campuses.create"=>("unattributed","创建校区"),
            "assignments.create"=>("unattributed","关联任课课程"),
            "assignment.delete"=>("unattributed","移除任课关联"),
            "research.auto-parse"=>("unattributed","自动解析上传资料"),
            "research.submit"=>("unattributed","提交资料审核"),
            "report.create"=>("unattributed","创建报告"),
            "report.edit"=>("unattributed","编辑报告"),
            "report.approve"=>("unattributed","审核报告"),
            "print.diagnostic.authorized"=>("unattributed","授权打印诊断"),
            _=>("unattributed",raw)
        };
    }
    static string Day(DateTimeOffset at)=>at.ToOffset(Beijing).ToString("yyyy-MM-dd");
    public static (DateTimeOffset Since,DateTimeOffset Until) Range(string start,string end)
    {
        if(!DateOnly.TryParseExact(start,"yyyy-MM-dd",CultureInfo.InvariantCulture,DateTimeStyles.None,out var a)||
           !DateOnly.TryParseExact(end,"yyyy-MM-dd",CultureInfo.InvariantCulture,DateTimeStyles.None,out var b)||b<a||b.DayNumber-a.DayNumber>366||a.Year<2000||b.Year>2100)
            throw new ApiException(400,"请选择 1 至 367 天的日期范围");
        return (new(a.ToDateTime(TimeOnly.MinValue),Beijing),new(b.AddDays(1).ToDateTime(TimeOnly.MinValue),Beijing));
    }
    public static void MapUsageStatistics(this WebApplication app)
    {
        UsageComparison.Map(app);
        UsageDashboard.Map(app);
        app.MapGet("/api/admin/usage-statistics",async(string start,string end,Data db,Access access,CancellationToken ct)=>{
            access.Admin();var (since,until)=Range(start,end);
            var teachers=await db.Staff.IgnoreQueryFilters().AsNoTracking().Where(s=>s.Role=="teacher"&&s.Name!="test_teacher").Select(s=>new{s.Id,s.Name,s.Enabled,s.Archived}).ToListAsync(ct);
            var ids=teachers.Select(s=>s.Id).ToArray();
            var audit=await db.Audits.AsNoTracking().Where(x=>x.StaffId.HasValue&&ids.Contains(x.StaffId.Value)&&x.CreatedAt>=since&&x.CreatedAt<until).Select(x=>new{x.StaffId,x.Action,x.CreatedAt}).ToListAsync(ct);
            var events=audit.Select(x=>new{x.StaffId,x.CreatedAt,Info=Describe(x.Action)}).Where(x=>x.Info.Action!=null&&x.Info.Source!="preview").ToLookup(x=>x.StaffId!.Value);
            var jobs=await db.PrintJobs.AsNoTracking().Where(x=>ids.Contains(x.StaffId)&&x.CreatedAt>=since&&x.CreatedAt<until).Select(x=>new{x.StaffId,x.State,x.Copies,x.CreatedAt}).ToListAsync(ct);
            var byStaff=jobs.ToLookup(x=>x.StaffId);
            var classes=await (from a in db.Assignments.AsNoTracking() join c in db.Classes.AsNoTracking() on a.ClassId equals c.Id join p in db.Campuses.AsNoTracking() on c.CampusId equals p.Id select new{a.StaffId,c.Id,c.Name,Campus=p.Name}).ToListAsync(ct);
            var assignments=classes.ToLookup(x=>x.StaffId);
            var rows=teachers.Select(t=>{
                var e=events[t.Id].ToArray();var j=byStaff[t.Id].ToArray();var business=e.Count(x=>x.Info.Source=="unattributed");
                var days=e.Select(x=>Day(x.CreatedAt)).Concat(j.Select(x=>Day(x.CreatedAt))).Distinct().Order().ToArray();
                var times=e.Select(x=>x.CreatedAt).Concat(j.Select(x=>x.CreatedAt)).ToArray();
                var mini=e.Count(x=>x.Info.Source=="miniapp");var login=e.Count(x=>x.Info.Source=="login");
                return new{t.Id,t.Name,t.Enabled,t.Archived,Campuses=assignments[t.Id].Select(x=>x.Campus).Distinct().Order().ToArray(),Classes=assignments[t.Id].Select(x=>x.Name).Distinct().Order().ToArray(),
                    Status=business>0||mini>0||j.Length>0?"有使用记录":login>0?"仅登录或改密":e.Length>0?"仅登录或改密":"无使用记录",
                    ActiveDays=days.Length,Days=days,LoginCount=login,BusinessCount=business,MiniappCount=mini,PrintCount=j.Length,
                    Submitted=j.Count(x=>x.State=="submitted"),Failed=j.Count(x=>x.State=="failed"),Uncertain=j.Count(x=>x.State=="uncertain"),
                    Pending=j.Count(x=>x.State is "queued" or "preparing" or "claimed" or "submitting"),OtherPrintStates=j.Count(x=>x.State is not ("submitted" or "failed" or "uncertain" or "queued" or "preparing" or "claimed" or "submitting")),
                    Copies=j.Sum(x=>x.Copies),FirstAt=times.Length==0?(DateTimeOffset?)null:times.Min(),LastAt=times.Length==0?(DateTimeOffset?)null:times.Max(),
                    Operations=e.Where(x=>x.Info.Source=="unattributed").GroupBy(x=>x.Info.Action).Select(g=>new{Action=g.Key,Count=g.Count()}).OrderByDescending(x=>x.Count).ToArray()};
            }).OrderByDescending(x=>x.Enabled&&!x.Archived).ThenByDescending(x=>x.ActiveDays).ThenByDescending(x=>x.BusinessCount).ThenBy(x=>x.Name).ToArray();
            return Results.Ok(new{start,end,SnapshotAt=Clock.Now,Rows=rows});
        });
        app.MapGet("/api/admin/usage-statistics/{id:guid}",async(Guid id,string start,string end,string? day,int? page,Data db,Access access,CancellationToken ct)=>{
            access.Admin();var(since,until)=Range(start,end);var index=page??0;
            Guard.That(index>=0&&index<=10000,"页码无效");
            var staff=await db.Staff.IgnoreQueryFilters().AsNoTracking().Where(x=>x.Id==id&&x.Role=="teacher"&&x.Name!="test_teacher").Select(x=>new{x.Id,x.Name}).SingleOrDefaultAsync(ct)??throw new ApiException(404,"老师不存在");
            var raw=await db.Audits.AsNoTracking().Where(x=>x.StaffId==id&&x.CreatedAt>=since&&x.CreatedAt<until).OrderByDescending(x=>x.CreatedAt).ThenBy(x=>x.Id).Select(x=>new{x.Id,x.Action,x.TargetId,x.CreatedAt}).ToListAsync(ct);
            var records=raw.Select(x=>new{x.Id,x.TargetId,x.CreatedAt,Code=x.Action,Info=Describe(x.Action)}).Where(x=>x.Info.Action!=null&&x.Info.Source!="preview").ToArray();
            var jobs=await (from j in db.PrintJobs.AsNoTracking() join p in db.Printers.AsNoTracking() on j.PrinterId equals p.Id into ps from p in ps.DefaultIfEmpty() where j.StaffId==id&&j.CreatedAt>=since&&j.CreatedAt<until orderby j.CreatedAt descending select new{j.Id,j.BatchId,j.CreatedAt,j.State,j.Copies,j.Duplex,j.Detail,Printer=p==null?"打印机已删除":p.Name}).ToListAsync(ct);
            var dates=records.Select(x=>Day(x.CreatedAt)).Concat(jobs.Select(x=>Day(x.CreatedAt))).Distinct().OrderDescending().ToArray();
            if(day!=null){var(d,_)=Range(day,day);Guard.That(d>=since&&d<until,"查看日期必须在统计日期范围内");}
            var selected=day??dates.FirstOrDefault();
            var selectedRecords=records.Where(x=>Day(x.CreatedAt)==selected).ToArray();
            var pageRows=selectedRecords.Skip(index*100).Take(100).ToArray();
            var targets=pageRows.Select(x=>x.TargetId).ToArray();var names=new Dictionary<Guid,string>();
            foreach(var x in await db.Classes.IgnoreQueryFilters().AsNoTracking().Where(x=>targets.Contains(x.Id)).Select(x=>new{x.Id,x.Name}).ToListAsync(ct))names[x.Id]=x.Name;
            foreach(var x in await db.Students.AsNoTracking().Where(x=>targets.Contains(x.Id)).Select(x=>new{x.Id,x.Name}).ToListAsync(ct))names[x.Id]=x.Name;
            foreach(var x in await db.Handouts.AsNoTracking().Where(x=>targets.Contains(x.Id)).Select(x=>new{x.Id,x.FileName}).ToListAsync(ct))names[x.Id]=x.FileName;
            foreach(var x in await db.BookImports.AsNoTracking().Where(x=>targets.Contains(x.Id)).Select(x=>new{x.Id,x.FileName}).ToListAsync(ct))names[x.Id]=x.FileName;
            foreach(var x in await db.Courses.IgnoreQueryFilters().AsNoTracking().Where(x=>targets.Contains(x.Id)).Select(x=>new{x.Id,x.Name}).ToListAsync(ct))names[x.Id]=x.Name;
            foreach(var x in await db.Lectures.AsNoTracking().Where(x=>targets.Contains(x.Id)).Select(x=>new{x.Id,x.Title,x.Sequence}).ToListAsync(ct))names[x.Id]="第"+x.Sequence+"讲 · "+x.Title;
            foreach(var x in await (from s in db.ClassSessions.AsNoTracking() join h in db.Handouts.AsNoTracking() on s.HandoutId equals h.Id join l in db.Lectures.AsNoTracking() on h.LectureId equals l.Id where targets.Contains(s.Id) select new{s.Id,l.Title}).ToListAsync(ct))names[x.Id]=x.Title;
            foreach(var x in await db.Documents.IgnoreQueryFilters().AsNoTracking().Where(x=>targets.Contains(x.Id)).Select(x=>new{x.Id,x.Kind,x.SourceFileName,x.StudentName}).ToListAsync(ct))names[x.Id]=x.Kind=="file"?x.SourceFileName:x.StudentName+"错题本";
            names[id]=staff.Name;
            var printTargets=pageRows.Where(x=>x.Code is "print.submit" or "print.reprint" or "print.create").Select(x=>x.TargetId).ToArray();
            foreach(var batch in jobs.Where(x=>printTargets.Contains(x.BatchId)).GroupBy(x=>x.BatchId))names[batch.Key]="打印批次 · "+batch.Count()+" 个任务 · "+string.Join("、",batch.Select(x=>x.Printer).Distinct());
            var items=pageRows.Select(x=>new{x.Id,x.TargetId,TargetName=names.GetValueOrDefault(x.TargetId,"对象已删除或未记录名称"),x.CreatedAt,x.Code,Action=x.Info.Action,Source=x.Info.Source}).ToArray();
            var byDay=records.ToLookup(x=>Day(x.CreatedAt));var jobsByDay=jobs.ToLookup(x=>Day(x.CreatedAt));
            var daily=dates.Select(d=>new{Day=d,Business=byDay[d].Count(x=>x.Info.Source=="unattributed"),Logins=byDay[d].Count(x=>x.Info.Source=="login"),Miniapp=byDay[d].Count(x=>x.Info.Source=="miniapp"),PrintCount=jobsByDay[d].Count(),Actions=byDay[d].GroupBy(x=>new{x.Info.Source,x.Info.Action}).Select(a=>new{a.Key.Source,a.Key.Action,Count=a.Count()}).ToArray()}).ToArray();
            return Results.Ok(new{Staff=staff,SelectedDay=selected,PeriodTotal=records.Length,Total=selectedRecords.Length,Page=index,HasMore=(index+1)*100<selectedRecords.Length,Items=items,Daily=daily,Jobs=jobs.Where(x=>Day(x.CreatedAt)==selected).ToArray()});
        });
    }
}
