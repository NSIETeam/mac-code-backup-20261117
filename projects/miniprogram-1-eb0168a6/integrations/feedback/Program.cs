using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.Data.Sqlite;
using Npgsql;
using System.Data.Common;

var builder=WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(o=>o.Limits.MaxRequestBodySize=4*1024*1024);
builder.Services.Configure<FormOptions>(o=>o.MultipartBodyLengthLimit=4*1024*1024);
builder.Services.AddSingleton<Store>();
builder.Services.AddSingleton<Identity>();
builder.Services.AddRateLimiter(o=>{
 o.RejectionStatusCode=429;
 o.AddPolicy("feedback",ctx=>RateLimitPartition.GetFixedWindowLimiter(ctx.Request.Headers["X-Real-IP"].FirstOrDefault()??ctx.Connection.RemoteIpAddress?.ToString()??"unknown",_=>new FixedWindowRateLimiterOptions{PermitLimit=90,Window=TimeSpan.FromMinutes(1),QueueLimit=0}));
});
var app=builder.Build();
app.Use(async(ctx,next)=>{
 ctx.Response.Headers.CacheControl="no-store";ctx.Response.Headers["X-Content-Type-Options"]="nosniff";ctx.Response.Headers["Referrer-Policy"]="no-referrer";
 ctx.Response.Headers.ContentSecurityPolicy="default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'self'; object-src 'none'; base-uri 'self'; form-action 'self'";
 try{await next();}catch(Fault e){if(!ctx.Response.HasStarted){ctx.Response.StatusCode=e.Status;await ctx.Response.WriteAsJsonAsync(new{message=e.Message});}}
 catch(BadHttpRequestException e){ctx.Response.StatusCode=e.StatusCode;await ctx.Response.WriteAsJsonAsync(new{message="请求过大或格式不正确"});}
 catch(Exception e){app.Logger.LogError(e,"Feedback request failed {Trace}",ctx.TraceIdentifier);if(!ctx.Response.HasStarted){ctx.Response.StatusCode=500;await ctx.Response.WriteAsJsonAsync(new{message="反馈暂时无法保存，请稍后重试",traceId=ctx.TraceIdentifier});}}
});
app.UseRateLimiter();
app.UseDefaultFiles();app.UseStaticFiles();
app.MapGet("/health",(Store s)=>new{status=s.Health()?"ok":"failed",application="siyue-feedback"});
var api=app.MapGroup("/api/support").RequireRateLimiting("feedback");
api.MapPost("/identity",()=>new{key=Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32))});
api.MapGet("/me",async(HttpContext c,Identity id)=>{var a=await id.Get(c);return new{user=a,admin=a?.Role=="admin"};});
api.MapGet("/tickets",async(HttpContext c,Identity id,Store s,string? scope,string? status,int? offset)=>{
 var a=await id.Get(c);bool all=scope=="all";if(all)Check.That(a?.Role=="admin","需要管理员权限",403);
 return s.List(a,Identity.Key(c),all,status,Math.Clamp(offset??0,0,100000));
});
api.MapPost("/tickets",async(HttpContext c,Identity id,Store s,TicketInput input)=>{
 var a=await id.Get(c);var key=Identity.Key(c);Check.That(a!=null||key.Length==64,"请刷新反馈页面后重试",400);
 Check.That(Guid.TryParse(input.RequestId,out _),"提交标识无效，请刷新后重试");
 var body=Check.Text(input.Body,1,2000,"请用1–2000字描述遇到的问题");var contact=Check.Text(input.Contact,0,120,"联系方式最多120字");
 return Results.Ok(s.Create(a,key,input.RequestId!,body,contact,Check.Context(input.Context)));
});
api.MapGet("/tickets/{ticket:guid}",async(Guid ticket,HttpContext c,Identity id,Store s)=>s.Detail(ticket,await id.Get(c),Identity.Key(c)));
api.MapPost("/tickets/{ticket:guid}/replies",async(Guid ticket,HttpContext c,Identity id,Store s,ReplyInput input)=>{
 var a=await id.Get(c);return s.Reply(ticket,a,Identity.Key(c),Check.Text(input.Body,1,2000,"回复需要1–2000字"),Check.Text(input.RequestId,1,80,"回复标识无效"));
});
api.MapPut("/tickets/{ticket:guid}/status",async(Guid ticket,HttpContext c,Identity id,Store s,StatusInput input)=>s.ChangeStatus(ticket,await id.Get(c),Identity.Key(c),input.Status));
api.MapPost("/tickets/{ticket:guid}/images",async(Guid ticket,HttpContext c,Identity id,Store s)=>{
 var a=await id.Get(c);var key=Identity.Key(c);s.Detail(ticket,a,key);
 Check.That(c.Request.HasFormContentType,"请选择截图");var form=await c.Request.ReadFormAsync();var file=form.Files.GetFile("file");
 Check.That(file!=null&&file.Length is >0 and <=3*1024*1024,"每张截图不超过3MB");
 using var ms=new MemoryStream();await file!.CopyToAsync(ms);var bytes=ms.ToArray();
 string? type=bytes.Length>=8&&bytes.AsSpan(0,8).SequenceEqual(new byte[]{137,80,78,71,13,10,26,10})?"image/png":bytes.Length>=3&&bytes[0]==255&&bytes[1]==216&&bytes[2]==255?"image/jpeg":bytes.Length>=12&&Encoding.ASCII.GetString(bytes,0,4)=="RIFF"&&Encoding.ASCII.GetString(bytes,8,4)=="WEBP"?"image/webp":null;
 Check.That(type!=null,"截图只支持PNG、JPEG或WebP图片");return s.Image(ticket,a,key,bytes,type!);
}).DisableAntiforgery();
api.MapGet("/tickets/{ticket:guid}/images/{image:guid}",async(Guid ticket,Guid image,HttpContext c,Identity id,Store s)=>{
 s.Detail(ticket,await id.Get(c),Identity.Key(c));var file=s.ReadImage(ticket,image);return Results.File(file.Bytes,file.Type);
});
app.Run();

record TicketInput(string? RequestId,string? Body,string? Contact,JsonElement? Context);
record ReplyInput(string? Body,string? RequestId);
record StatusInput(string Status);
record Actor(string Id,string Name,string Role);
class Fault(int status,string reason):Exception(reason){public int Status{get;}=status;}
static class Check{
 public static void That(bool ok,string message,int status=400){if(!ok)throw new Fault(status,message);}
 public static string Text(string? value,int min,int max,string error){var s=(value??"").Trim();That(s.Length>=min&&s.Length<=max,error);return s;}
 public static string Context(JsonElement? value){
  var clean=new Dictionary<string,string>();if(value is {ValueKind:JsonValueKind.Object} v)foreach(var name in new[]{"page","version","platform","scene","error","errorPath","traceId","status"}){
   if(!v.TryGetProperty(name,out var p)||p.ValueKind!=JsonValueKind.String)continue;var text=p.GetString()??"";
   if(name is "page" or "errorPath"){text=text.Split('?','#')[0];if(!text.StartsWith('/'))text="/"+text;}
   if(name=="error")text=System.Text.RegularExpressions.Regex.Replace(text,@"(?i)(bearer\s+\S+|(?:password|token|secret|密码)\s*[:=：]\s*\S+)","[已隐藏]");
   clean[name]=text[..Math.Min(text.Length,name=="error"?500:200)];
  }return JsonSerializer.Serialize(clean);
 }
}
class Identity(IConfiguration config){
 static string Hash(string s)=>Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(s)));
 public static string Key(HttpContext c){var s=c.Request.Headers["X-Feedback-Key"].ToString();return s.Length==64&&s.All(Uri.IsHexDigit)?Hash(s):"";}
 public async Task<Actor?> Get(HttpContext c){
  var header=c.Request.Headers.Authorization.ToString();if(string.IsNullOrEmpty(header)||header=="Bearer ")return null;
  Check.That(header.StartsWith("Bearer ")&&header.Length<=1024,"登录已失效，请重新登录",401);
  await using var conn=new NpgsqlConnection(config.GetConnectionString("Database"));await conn.OpenAsync();
  await using var cmd=new NpgsqlCommand("SELECT u.\"Id\"::text,u.\"Name\",u.\"Role\" FROM \"Sessions\" s JOIN \"Staff\" u ON u.\"Id\"=s.\"StaffId\" WHERE s.\"TokenHash\"=@hash AND s.\"ExpiresAt\">@now AND u.\"Enabled\" AND NOT u.\"Archived\"",conn);
  cmd.Parameters.AddWithValue("hash",Hash(header[7..]));cmd.Parameters.AddWithValue("now",DateTime.UtcNow.Ticks);
  await using var r=await cmd.ExecuteReaderAsync();Check.That(await r.ReadAsync(),"登录已失效，请重新登录，或通过未登录反馈继续提交",401);
  return new(r.GetString(0),r.GetString(1),r.GetString(2));
 }
}
class Store{
 readonly string connection;readonly bool postgres;readonly object gate=new();
 public Store(IConfiguration c){
  var pg=c.GetConnectionString("Feedback");postgres=!string.IsNullOrWhiteSpace(pg);
  if(postgres)connection=pg!;
  else {var path=c["Feedback:Database"]??"/data/feedback.db";Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);connection=new SqliteConnectionStringBuilder{DataSource=path,DefaultTimeout=10}.ToString();}
  using var db=Open();using var cmd=db.CreateCommand();cmd.CommandText="""
  PRAGMA journal_mode=WAL;
  CREATE TABLE IF NOT EXISTS tickets(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,owner_id TEXT,owner_key TEXT NOT NULL,guest_hash TEXT NOT NULL,name TEXT NOT NULL,body TEXT NOT NULL,contact TEXT NOT NULL,context TEXT NOT NULL,status TEXT NOT NULL,created TEXT NOT NULL,updated TEXT NOT NULL,request_id TEXT NOT NULL,UNIQUE(owner_key,request_id));
  CREATE INDEX IF NOT EXISTS tickets_owner ON tickets(owner_id,updated);
  CREATE INDEX IF NOT EXISTS tickets_guest ON tickets(guest_hash,updated);
  CREATE TABLE IF NOT EXISTS replies(id TEXT PRIMARY KEY,ticket_id TEXT NOT NULL,actor_id TEXT,author TEXT NOT NULL,admin INTEGER NOT NULL,body TEXT NOT NULL,created TEXT NOT NULL,request_key TEXT NOT NULL,UNIQUE(ticket_id,request_key));
  CREATE TABLE IF NOT EXISTS images(id TEXT PRIMARY KEY,ticket_id TEXT NOT NULL,type TEXT NOT NULL,data BLOB NOT NULL,sha TEXT NOT NULL,created TEXT NOT NULL,UNIQUE(ticket_id,sha));
  """;
  if(postgres)cmd.CommandText=cmd.CommandText.Replace("PRAGMA journal_mode=WAL;", "").Replace("seq INTEGER PRIMARY KEY AUTOINCREMENT", "seq BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY").Replace("data BLOB", "data BYTEA");
  cmd.ExecuteNonQuery();
 }
 DbConnection Open(){DbConnection c=postgres?new NpgsqlConnection(connection):new SqliteConnection(connection);c.Open();return c;}
 static string Now()=>DateTime.UtcNow.ToString("O");
 public bool Health(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT 1";return Convert.ToInt32(q.ExecuteScalar())==1;}
 static DbCommand Query(DbConnection c,string text,params (string,object?)[] args){var q=c.CreateCommand();q.CommandText=text;foreach(var(k,v) in args){var p=q.CreateParameter();p.ParameterName=k;p.Value=v??DBNull.Value;if(v is null)p.DbType=System.Data.DbType.String;q.Parameters.Add(p);}return q;}
 static Dictionary<string,object?> Ticket(DbDataReader r)=>new(){["id"]=r.GetString(1),["number"]="FK"+r.GetInt64(0).ToString("D6"),["ownerId"]=r.IsDBNull(2)?null:r.GetString(2),["name"]=r.GetString(5),["body"]=r.GetString(6),["contact"]=r.GetString(7),["context"]=JsonSerializer.Deserialize<Dictionary<string,string>>(r.GetString(8)),["status"]=r.GetString(9),["created"]=r.GetString(10),["updated"]=r.GetString(11)};
 Dictionary<string,object?> Authorized(DbConnection db,Guid ticket,Actor? a,string key){
  using var q=Query(db,"SELECT * FROM tickets WHERE id=@id",("@id",ticket.ToString()));using var r=q.ExecuteReader();Check.That(r.Read(),"反馈不存在或无权查看",404);
  bool owner=a!=null&&!r.IsDBNull(2)&&r.GetString(2)==a.Id;bool guest=r.IsDBNull(2)&&key.Length==64&&r.GetString(4)==key;
  Check.That(owner||guest||a?.Role=="admin","反馈不存在或无权查看",404);return Ticket(r);
 }
 public object List(Actor? a,string key,bool all,string? status,int offset){
  Check.That(status==null||new[]{"open","processing","verify","resolved"}.Contains(status),"反馈状态无效");
  using var db=Open();var where=all?"1=1":"(owner_id=@owner OR (owner_id IS NULL AND guest_hash=@key AND @key<>''))";
  if(status!=null)where+=" AND status=@status";
  using var q=Query(db,"SELECT * FROM tickets WHERE "+where+" ORDER BY updated DESC,seq DESC LIMIT 51 OFFSET @offset",("@owner",a?.Id??""),("@key",key),("@status",status),("@offset",offset));using var r=q.ExecuteReader();var rows=new List<Dictionary<string,object?>>();while(r.Read())rows.Add(Ticket(r));bool more=rows.Count>50;if(more)rows.RemoveAt(50);return new{items=rows,hasMore=more};
 }
 public object Create(Actor? a,string key,string request,string body,string contact,string context){lock(gate){
  using var db=Open();var owner=a!=null?"u:"+a.Id:"g:"+key;
  using(var q=Query(db,"SELECT * FROM tickets WHERE owner_key=@owner AND request_id=@request",("@owner",owner),("@request",request))){using var r=q.ExecuteReader();if(r.Read()){Check.That(r.GetString(6)==body&&r.GetString(7)==contact&&r.GetString(8)==context,"同一次提交内容发生变化，请刷新后重试",409);return Ticket(r);}}
  using(var q=Query(db,"SELECT count(*) FROM tickets WHERE owner_key=@owner AND created>@since",("@owner",owner),("@since",DateTime.UtcNow.AddHours(-1).ToString("O"))))Check.That(Convert.ToInt32(q.ExecuteScalar())<20,"反馈提交较多，请在已有反馈里补充信息",429);
  var id=Guid.NewGuid();var now=Now();using(var q=Query(db,"INSERT INTO tickets(id,owner_id,owner_key,guest_hash,name,body,contact,context,status,created,updated,request_id) VALUES(@id,@uid,@owner,@key,@name,@body,@contact,@context,'open',@now,@now,@request)",("@id",id.ToString()),("@uid",a?.Id),("@owner",owner),("@key",key),("@name",a?.Name??"未登录用户"),("@body",body),("@contact",contact),("@context",context),("@now",now),("@request",request)))q.ExecuteNonQuery();
  return Authorized(db,id,a,key);
 }}
 public object Detail(Guid ticket,Actor? a,string key){using var db=Open();var t=Authorized(db,ticket,a,key);var replies=new List<object>();var images=new List<object>();
  using(var q=Query(db,"SELECT id,author,admin,body,created FROM replies WHERE ticket_id=@id ORDER BY created,id",("@id",ticket.ToString()))){using var r=q.ExecuteReader();while(r.Read())replies.Add(new{id=r.GetString(0),author=r.GetString(1),admin=r.GetInt32(2)==1,body=r.GetString(3),created=r.GetString(4)});}
  using(var q=Query(db,"SELECT id FROM images WHERE ticket_id=@id ORDER BY created,id",("@id",ticket.ToString()))){using var r=q.ExecuteReader();while(r.Read())images.Add(new{id=r.GetString(0)});}
  return new{ticket=t,replies,images};
 }
 public object Reply(Guid ticket,Actor? a,string key,string body,string request){lock(gate){using var db=Open();Authorized(db,ticket,a,key);var isAdmin=a?.Role=="admin";var requestKey=(a?.Id??key)+":"+request;
  using(var q=Query(db,"SELECT body FROM replies WHERE ticket_id=@id AND request_key=@key",("@id",ticket.ToString()),("@key",requestKey))){var prior=q.ExecuteScalar();if(prior!=null){Check.That((string)prior==body,"重复提交的回复内容不同",409);return Detail(ticket,a,key);}}
  using var tx=db.BeginTransaction();var now=Now();using(var q=Query(db,"INSERT INTO replies VALUES(@id,@ticket,@actor,@name,@admin,@body,@now,@key)",("@id",Guid.NewGuid().ToString()),("@ticket",ticket.ToString()),("@actor",a?.Id),("@name",a?.Name??"反馈人"),("@admin",isAdmin?1:0),("@body",body),("@now",now),("@key",requestKey))){q.Transaction=tx;q.ExecuteNonQuery();}
  using(var q=Query(db,"UPDATE tickets SET updated=@now,status=CASE WHEN status='resolved' AND @admin=0 THEN 'open' WHEN status='open' AND @admin=1 THEN 'processing' ELSE status END WHERE id=@id",("@now",now),("@admin",isAdmin?1:0),("@id",ticket.ToString()))){q.Transaction=tx;q.ExecuteNonQuery();}tx.Commit();return Detail(ticket,a,key);
 }}
 public object ChangeStatus(Guid ticket,Actor? a,string key,string status){lock(gate){using var db=Open();var t=Authorized(db,ticket,a,key);bool admin=a?.Role=="admin";
  Check.That((admin?new[]{"open","processing","verify","resolved"}:new[]{"open","resolved"}).Contains(status),"无权设置此处理状态",403);
  var prior=(string)t["status"]!;if(prior==status)return Detail(ticket,a,key);using var tx=db.BeginTransaction();var now=Now();
  using(var q=Query(db,"UPDATE tickets SET status=@status,updated=@now WHERE id=@id",("@status",status),("@now",now),("@id",ticket.ToString()))){q.Transaction=tx;q.ExecuteNonQuery();}
  var labels=new Dictionary<string,string>{{"open","待处理"},{"processing","处理中"},{"verify","待验证"},{"resolved","已解决"}};
  using(var q=Query(db,"INSERT INTO replies VALUES(@id,@ticket,@actor,@name,@admin,@body,@now,@key)",("@id",Guid.NewGuid().ToString()),("@ticket",ticket.ToString()),("@actor",a?.Id),("@name",a?.Name??"反馈人"),("@admin",admin?1:0),("@body","将状态更新为："+labels[status]),("@now",now),("@key",Guid.NewGuid().ToString()))){q.Transaction=tx;q.ExecuteNonQuery();}tx.Commit();return Detail(ticket,a,key);
 }}
 public object Image(Guid ticket,Actor? a,string key,byte[] bytes,string type){lock(gate){using var db=Open();Authorized(db,ticket,a,key);var sha=Convert.ToHexStringLower(SHA256.HashData(bytes));
  using(var q=Query(db,"SELECT id FROM images WHERE ticket_id=@ticket AND sha=@sha",("@ticket",ticket.ToString()),("@sha",sha))){var prior=q.ExecuteScalar();if(prior!=null)return new{id=(string)prior};}
  using(var q=Query(db,"SELECT count(*) FROM images WHERE ticket_id=@ticket",("@ticket",ticket.ToString())))Check.That(Convert.ToInt32(q.ExecuteScalar())<3,"每条反馈最多3张截图");
  var id=Guid.NewGuid().ToString();using(var q=Query(db,"INSERT INTO images VALUES(@id,@ticket,@type,@data,@sha,@now)",("@id",id),("@ticket",ticket.ToString()),("@type",type),("@data",bytes),("@sha",sha),("@now",Now())))q.ExecuteNonQuery();return new{id};
 }}
 public (byte[] Bytes,string Type) ReadImage(Guid ticket,Guid image){using var db=Open();using var q=Query(db,"SELECT data,type FROM images WHERE ticket_id=@ticket AND id=@id",("@ticket",ticket.ToString()),("@id",image.ToString()));using var r=q.ExecuteReader();Check.That(r.Read(),"截图不存在",404);return ((byte[])r[0],r.GetString(1));}
}
public partial class Program{}
