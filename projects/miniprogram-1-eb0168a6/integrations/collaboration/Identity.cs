using System.Security.Cryptography;
using System.Text;
using Npgsql;
using Microsoft.EntityFrameworkCore;
namespace Siyue.Collaboration;

public record Person(Guid Id,string Name,string Role,string Account,bool Enabled,string Department="");
public interface IIdentityDirectory {Task<Person?> Session(string hash,CancellationToken ct=default); Task<Person[]> People(CancellationToken ct=default);}
public sealed class IdentityDirectory(IConfiguration config):IIdentityDirectory {
 public async Task<Person?> Session(string hash,CancellationToken ct=default) {
  await using var c=new NpgsqlConnection(config.GetConnectionString("Identity")); await c.OpenAsync(ct);
  await using var q=new NpgsqlCommand("SELECT id,name,role,account,must_change_password FROM collaboration_sessions WHERE token_hash=@hash AND expires_at>@now",c);
  q.Parameters.AddWithValue("hash",hash);q.Parameters.AddWithValue("now",DateTime.UtcNow.Ticks);
  await using var r=await q.ExecuteReaderAsync(ct);if(!await r.ReadAsync(ct))return null;
  Check.That(!r.GetBoolean(4),"请先在教学工作台修改初始密码",428);
  return new(r.GetGuid(0),r.GetString(1),r.GetString(2),r.GetString(3),true);
 }
 public async Task<Person[]> People(CancellationToken ct=default) {
  await using var c=new NpgsqlConnection(config.GetConnectionString("Identity"));await c.OpenAsync(ct);
  await using var q=new NpgsqlCommand("SELECT id,name,role,account,enabled FROM collaboration_people",c);await using var r=await q.ExecuteReaderAsync(ct);
  var people=new List<Person>();while(await r.ReadAsync(ct))people.Add(new(r.GetGuid(0),r.GetString(1),r.GetString(2),r.GetString(3),r.GetBoolean(4)));return people.ToArray();
 }
}
public sealed class Fault(int status,string message):Exception(message){public int Status=>status;}
public static class Check {
 public static void That([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool value,string message,int status=400){if(!value)throw new Fault(status,message);}
 public static string Text(string? v,int min=0,int max=4000){var s=(v??"").Trim();That(s.Length>=min&&s.Length<=max,$"请输入{min}–{max}个字符");return s;}
 public static string Hash(string value)=>Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
 public static string Hash(byte[] value)=>Convert.ToHexStringLower(SHA256.HashData(value));
 public static bool Same(string a,string b)=>CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(Hash(a)),Encoding.UTF8.GetBytes(Hash(b)));
 public static Person Actor(HttpContext ctx)=>ctx.Items["actor"] as Person??throw new Fault(401,"请先登录教学工作台");
 public static void Admin(Person p)=>That(p.Role=="admin","只有管理员可以执行此操作",403);
}
public sealed class ProjectAccess(CollaborationDb db,IIdentityDirectory identity) {
 public async Task<Project> Read(Guid id,Person person,bool detail=true) {
  var p=await db.Projects.FindAsync(id)??throw new Fault(404,"项目不存在");
  var member=person.Role=="admin"||p.OwnerId==person.Id||await db.Members.AnyAsync(x=>x.ProjectId==id&&x.StaffId==person.Id);
  Check.That(member||!detail&&!p.Confidential,"请加入项目后查看",p.Confidential?404:403);return p;
 }
 public async Task<Project> Owner(Guid id,Person person){var p=await Read(id,person);Check.That(person.Role=="admin"||p.OwnerId==person.Id,"需要项目负责人权限",403);return p;}
 public async Task RequirePeople(IEnumerable<Guid> ids){var people=await identity.People();Check.That(ids.All(id=>people.Any(p=>p.Id==id&&p.Enabled)),"人员不存在或已停用");}
 public async Task RequireMembers(Guid project,IEnumerable<Guid> ids){var p=await db.Projects.FindAsync(project)??throw new Fault(404,"项目不存在");var members=await db.Members.Where(m=>m.ProjectId==project).Select(m=>m.StaffId).ToListAsync();if(p.OwnerId.HasValue)members.Add(p.OwnerId.Value);await RequirePeople(ids);Check.That(ids.All(members.Contains),"请先将负责人和协作者加入项目");}
 public void Audit(Person? p,Guid? project,string action,string detail=""){db.Audits.Add(new(){StaffId=p?.Id,ProjectId=project,Action=action,Detail=detail});}
 public void Notify(Guid staff,Guid project,Guid? target,string kind,string text,string key){db.Notifications.Add(new(){StaffId=staff,ProjectId=project,TargetId=target,Kind=kind,Text=text,EventKey=key});}
}
