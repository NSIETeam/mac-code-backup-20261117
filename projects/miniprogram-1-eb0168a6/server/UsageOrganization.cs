using System.Text.Json;
using Microsoft.EntityFrameworkCore;
namespace Siyue;

public sealed record UsageCampusSetting(Guid Id,string City,string Region,bool RosterConfirmed=false);
public sealed record UsageTeacherSetting(Guid Id,bool? Included,Guid? CampusId);
public sealed record UsageOrganization(int Revision,UsageCampusSetting[] Campuses,UsageTeacherSetting[] Teachers,DateTimeOffset? UpdatedAt=null,Guid? UpdatedBy=null);
public sealed class UsageOrganizationStore(IConfiguration config)
{
    readonly SemaphoreSlim gate=new(1,1);
    string FileName=>Path.Combine(config["UsageStatistics:Directory"]??Path.Combine(config["Storage:Directory"]??".local/objects",".usage-statistics"),"organization.json");
    UsageOrganization ReadFile()=>File.Exists(FileName)?JsonSerializer.Deserialize<UsageOrganization>(File.ReadAllText(FileName))??throw new IOException("统计组织名单无法读取"):new(0,[],[]);
    public async Task<UsageOrganization> Read(CancellationToken ct){await gate.WaitAsync(ct);try{return ReadFile();}finally{gate.Release();}}
    public async Task<UsageOrganization> Save(UsageOrganization input,Guid actor,CancellationToken ct){
        await gate.WaitAsync(ct);try{
            await using var fileLock=await ResearchStore.Lock(Path.GetDirectoryName(FileName)!,"organization.lock",ct);
            var old=ReadFile();Guard.That(old.Revision==input.Revision,"组织名单已被更新，请刷新后再保存",409);
            // Changing a teacher's eligibility/home invalidates completeness of both affected campus rosters.
            var affected=new HashSet<Guid>();var before=old.Teachers.ToDictionary(x=>x.Id);
            foreach(var row in input.Teachers){if(!before.TryGetValue(row.Id,out var previous)||previous!=row){if(previous?.CampusId is Guid a)affected.Add(a);if(row.CampusId is Guid b)affected.Add(b);}}
            foreach(var row in old.Teachers.Where(x=>!input.Teachers.Any(y=>y.Id==x.Id)))if(row.CampusId is Guid id)affected.Add(id);
            var result=input with{Revision=old.Revision+1,UpdatedAt=Clock.Now,UpdatedBy=actor,Campuses=input.Campuses.Select(x=>affected.Contains(x.Id)?x with{RosterConfirmed=false}:x).ToArray()};
            Directory.CreateDirectory(Path.GetDirectoryName(FileName)!);var temp=FileName+"."+Guid.NewGuid().ToString("N")+".tmp";
            try{await File.WriteAllTextAsync(temp,JsonSerializer.Serialize(result),ct);File.Move(temp,FileName,true);}finally{if(File.Exists(temp))File.Delete(temp);}
            return result;
        }finally{gate.Release();}
    }
    public static void Map(WebApplication app){
        app.MapGet("/api/admin/usage-organization",async(Data db,Access access,UsageOrganizationStore store,CancellationToken ct)=>{
            access.Admin();var settings=await store.Read(ct);
            var teachers=await db.Staff.AsNoTracking().Where(s=>s.Role=="teacher"&&s.Enabled&&s.Name!="test_teacher").Select(s=>new{s.Id,s.Name}).OrderBy(s=>s.Name).ToListAsync(ct);
            var campuses=await db.Campuses.AsNoTracking().Select(c=>new{c.Id,c.Name,c.City}).OrderBy(c=>c.City).ThenBy(c=>c.Name).ToListAsync(ct);
            var links=await (from a in db.Assignments.AsNoTracking() join c in db.Classes.AsNoTracking() on a.ClassId equals c.Id select new{a.StaffId,CampusId=c.CampusId}).ToListAsync(ct);
            var owners=await db.Classes.AsNoTracking().Where(c=>c.OwnerStaffId.HasValue).Select(c=>new{StaffId=c.OwnerStaffId!.Value,c.CampusId}).ToListAsync(ct);
            return Results.Ok(new{Settings=settings,Campuses=campuses,Teachers=teachers.Select(t=>new{t.Id,t.Name,SuggestedCampusIds=links.Concat(owners).Where(a=>a.StaffId==t.Id).Select(a=>a.CampusId).Distinct().ToArray()})});
        });
        app.MapPut("/api/admin/usage-organization",async(UsageOrganization input,Data db,Access access,UsageOrganizationStore store,CancellationToken ct)=>{
            access.Admin();Guard.That(input.Campuses!=null&&input.Teachers!=null&&input.Campuses.Length<=2000&&input.Teachers.Length<=10000,"名单格式无效");
            Guard.That(input.Campuses!.Select(x=>x.Id).Distinct().Count()==input.Campuses.Length&&input.Teachers!.Select(x=>x.Id).Distinct().Count()==input.Teachers.Length,"名单存在重复编号");
            var campusIds=await db.Campuses.AsNoTracking().Select(x=>x.Id).ToListAsync(ct);var teacherIds=await db.Staff.IgnoreQueryFilters().AsNoTracking().Where(x=>x.Role=="teacher"&&x.Name!="test_teacher").Select(x=>x.Id).ToListAsync(ct);
            Guard.That(input.Campuses.All(x=>campusIds.Contains(x.Id)&&x.City!=null&&x.Region!=null&&x.City.Trim().Length<=80&&x.Region.Trim().Length<=80),"城市、区域或校区无效");
            Guard.That(input.Teachers!.All(x=>teacherIds.Contains(x.Id)&&(!x.CampusId.HasValue||campusIds.Contains(x.CampusId.Value))&&(x.Included!=true||x.CampusId.HasValue)),"应使用教师必须选择主属校区，且人员编号必须有效");
            Guard.That(input.Campuses.All(x=>!x.RosterConfirmed||x.City.Trim().Length>0&&x.Region.Trim().Length>0),"确认校区名单前，请补齐城市和内部区域");
            var saved=await store.Save(input with{Campuses=input.Campuses.Select(x=>x with{City=x.City.Trim(),Region=x.Region.Trim()}).ToArray()},access.User.Id,ct);
            db.Audits.Add(new Audit{StaffId=access.User.Id,Action="usage.organization.update",TargetId=access.User.Id});await db.SaveChangesAsync(ct);
            return Results.Ok(saved);
        });
    }
}
