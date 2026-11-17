using Amazon.S3;
using Amazon.S3.Model;
using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
using System.Text;
namespace Siyue;
public sealed class ApiException(int status, string message) : Exception(message) { public int Status { get; } = status; }
public static class Guard
{
    public static void That([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition, string message, int status = 400) { if (!condition) throw new ApiException(status, message); }
    public static string Name(string value) { value = value.Trim(); That(value.Length is > 0 and <= 120, "名称需要 1–120 个字符"); return value; }
}
public static class Secrets
{
    public static string New() => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
    public static string Hash(string value) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    public static bool Equal(string a, string b) => CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(Hash(a)), Encoding.UTF8.GetBytes(Hash(b)));
}
public record Actor(Guid Id, string Name, string Role);
public class Access(Data db, IHttpContextAccessor contexts)
{
    public Actor User => contexts.HttpContext?.Items["actor"] as Actor ?? throw new ApiException(401, "请先登录");
    public void Admin() => Guard.That(User.Role == "admin", "需要管理员权限", 403);
    public async Task Class(Guid classId, Guid? courseId = null)
    {
        var u = User; Guard.That(await db.Classes.AnyAsync(x => x.Id == classId), "班级不存在", 404);
        Guard.That(u.Role == "admin" || (!courseId.HasValue && await db.Classes.AnyAsync(x => x.Id == classId && x.OwnerStaffId == u.Id)) || await db.Assignments.AnyAsync(x => x.StaffId == u.Id && x.ClassId == classId && (!courseId.HasValue || x.CourseId == courseId)), "无权访问此班级课程", 403);
    }
    public async Task<Lecture> Lecture(Guid id) { var x = await db.Lectures.FindAsync(id) ?? throw new ApiException(404, "讲次不存在"); if (x.ClassId.HasValue) await Class(x.ClassId.Value, x.CourseId); else await Course(x.CourseId); return x; }
    public async Task<Guid?> LectureClass(Lecture lecture, Guid? classId) {
        if (classId.HasValue) { await Class(classId.Value, lecture.CourseId); Guard.That(!lecture.ClassId.HasValue || lecture.ClassId == classId, "讲义不属于当前班级", 403); return classId; }
        if (lecture.ClassId.HasValue) return lecture.ClassId;
        Admin(); return null;
    }
    public async Task Course(Guid id) { var u = User; Guard.That(await db.Courses.AnyAsync(x => x.Id == id), "课程不存在", 404); Guard.That(u.Role == "admin" || await db.Assignments.AnyAsync(x => x.StaffId == u.Id && x.CourseId == id), "无权访问此课程", 403); }
    public async Task<Handout> Handout(Guid id, bool published = false) { var x = await db.Handouts.FindAsync(id) ?? throw new ApiException(404, "讲义不存在"); await Lecture(x.LectureId); Guard.That(!published || x.State == "published", "讲义尚未发布", 403); return x; }
    public async Task<Lecture> WriteLecture(Guid id) { var l = await Lecture(id); Guard.That(User.Role == "admin" || l.ClassId.HasValue, "课程共享讲义由管理员维护，请上传本班专用讲次", 403); return l; }
    public async Task<ExportDocument> Document(Guid id) { var x = await db.Documents.FindAsync(id) ?? throw new ApiException(404, "文档不存在"); if (x.ClassId.HasValue) await Class(x.ClassId.Value, x.CourseId);
        else Guard.That(User.Role == "admin" || x.Kind == "file" && x.StaffId == User.Id, "无权查看此文件", 403); return x; }
    public async Task Campus(Guid id)
    {
        var u = User; Guard.That(u.Role == "admin" || await (from a in db.Assignments join c in db.Classes on a.ClassId equals c.Id where a.StaffId == u.Id && c.CampusId == id select c.Id).AnyAsync(), "无权使用此校区打印机", 403);
    }
}
public interface IObjects { Task Put(string key, Stream stream, string contentType, CancellationToken ct = default); Task<Stream> Get(string key, CancellationToken ct = default); }
public class LocalObjects(IConfiguration config) : IObjects
{
    readonly string root = Path.GetFullPath(config["Storage:Directory"] ?? ".local/files");
    string PathFor(string key) { var p = Path.GetFullPath(Path.Combine(root, key)); Guard.That(p.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.Ordinal), "无效文件路径"); return p; }
    public async Task Put(string key, Stream stream, string contentType, CancellationToken ct = default) { var p = PathFor(key); Directory.CreateDirectory(Path.GetDirectoryName(p)!); var temp = p + ".tmp-" + Guid.NewGuid().ToString("N"); try { await using (var f = File.Create(temp)) { await stream.CopyToAsync(f, ct); } File.Move(temp,p,true); } finally { if(File.Exists(temp)) File.Delete(temp); } }
    public Task<Stream> Get(string key, CancellationToken ct = default) => Task.FromResult<Stream>(File.OpenRead(PathFor(key)));
}
public class S3Objects : IObjects
{
    readonly AmazonS3Client client; readonly string bucket;
    public S3Objects(IConfiguration c) { client = new AmazonS3Client(c["Storage:AccessKey"], c["Storage:SecretKey"], new AmazonS3Config { ServiceURL = c["Storage:Endpoint"], ForcePathStyle = true, AuthenticationRegion = c["Storage:Region"] ?? "us-east-1" }); bucket = c["Storage:Bucket"] ?? "siyue"; }
    public Task Put(string key, Stream stream, string contentType, CancellationToken ct = default) => client.PutObjectAsync(new PutObjectRequest { BucketName = bucket, Key = key, InputStream = stream, ContentType = contentType, AutoCloseStream = false, UseChunkEncoding = false }, ct);
    public async Task<Stream> Get(string key, CancellationToken ct = default) { var r = await client.GetObjectAsync(bucket, key, ct); var m = new MemoryStream(); using (r) await r.ResponseStream.CopyToAsync(m, ct); m.Position = 0; return m; }
}
public class Files(Data db, IObjects objects, IConfiguration config)
{
    public async Task<string> Grant(string key, string type, string name, Guid? staffId = null, Guid? deviceId = null, Guid? classId = null, Guid? courseId = null)
    {
        Guard.That(!string.IsNullOrEmpty(key), "文件尚未生成", 409); var token = Secrets.New();
        db.Grants.Add(new FileGrant { TokenHash = Secrets.Hash(token), ObjectKey = key, ContentType = type, FileName = name, StaffId = staffId, DeviceId = deviceId, ClassId = classId, CourseId = courseId, ExpiresAt = Clock.Now.AddMinutes(10) }); await db.SaveChangesAsync();
        return (config["PublicUrl"] ?? "http://127.0.0.1:5080").TrimEnd('/') + "/files/" + token;
    }
    public async Task<IResult> Read(string token, CancellationToken ct)
    {
        var hash = Secrets.Hash(token); var g = await db.Grants.SingleOrDefaultAsync(x => x.TokenHash == hash && x.ExpiresAt > Clock.Now, ct) ?? throw new ApiException(404, "文件链接已过期，请重新打开");
        Guard.That(!g.ObjectKey.StartsWith("installer:"), "下载入口无效", 404);
        if (g.StaffId is Guid u) Guard.That(await db.Staff.AnyAsync(x => x.Id == u && x.Enabled, ct), "账号已停用", 403);
        if (g.StaffId is Guid uid && g.ClassId is Guid cid) { var owner = await db.Staff.FindAsync([uid], ct); Guard.That(owner?.Role == "admin" || await db.Assignments.AnyAsync(x => x.StaffId == uid && x.ClassId == cid && (!g.CourseId.HasValue || x.CourseId == g.CourseId), ct), "任课权限已变更", 403); }
        if (g.StaffId is Guid sharedUser && g.ClassId == null && g.CourseId is Guid course) { var owner = await db.Staff.FindAsync([sharedUser], ct); Guard.That(owner?.Role == "admin" || await db.Assignments.AnyAsync(x => x.StaffId == sharedUser && x.CourseId == course, ct), "任课权限已变更", 403); }
        if (g.DeviceId is Guid d) Guard.That(await db.Devices.AnyAsync(x => x.Id == d && x.Enabled, ct), "设备已停用", 403);
        return Results.File(await objects.Get(g.ObjectKey, ct), g.ContentType, g.FileName);
    }
}

public static class Clock { public static DateTimeOffset Now => DateTimeOffset.UtcNow; }
