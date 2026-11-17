using Microsoft.EntityFrameworkCore;
using System.Text.Json;
namespace Siyue;
public abstract class Entity { public Guid Id { get; set; } = Guid.NewGuid(); }
public class Staff : Entity { public bool CanSelectAnyCampus { get; set; } public bool Archived { get; set; } public string? Code { get; set; } public string Name { get; set; } = ""; public string Role { get; set; } = "teacher"; public string? OpenId { get; set; } public string? BootstrapKey { get; set; } public bool Enabled { get; set; } = true; }
public class Session : Entity { public string TokenHash { get; set; } = ""; public string OpenId { get; set; } = ""; public Guid? StaffId { get; set; } public DateTimeOffset ExpiresAt { get; set; } }
public class PasswordAccount : Entity { public bool MustChangePassword { get; set; } public string Account { get; set; } = ""; public Guid StaffId { get; set; } [System.Text.Json.Serialization.JsonIgnore] public string PasswordHash { get; set; } = ""; }
public class Invite : Entity { public string CodeHash { get; set; } = ""; public Guid StaffId { get; set; } public bool Used { get; set; } public DateTimeOffset ExpiresAt { get; set; } }
public class Campus : Entity { public string City { get; set; } = ""; public bool Archived { get; set; } public string Name { get; set; } = ""; }
public class Course : Entity { public bool Archived { get; set; } public string Name { get; set; } = ""; }
public class SchoolClass : Entity { public Guid? OwnerStaffId { get; set; } public bool Archived { get; set; } public string Name { get; set; } = ""; public Guid CampusId { get; set; } }
public class Assignment : Entity { public Guid StaffId { get; set; } public Guid ClassId { get; set; } public Guid CourseId { get; set; } }
public class Student : Entity { public string Name { get; set; } = ""; public string Code { get; set; } = ""; public Guid ClassId { get; set; } }
public class Lecture : Entity { public Guid? ClassId { get; set; } public Guid CourseId { get; set; } public string Title { get; set; } = ""; public int Sequence { get; set; } }
public class LessonPlan : Entity { public string WordKey { get; set; } = ""; public string WordFileName { get; set; } = ""; public Guid StaffId { get; set; } public Guid ClassId { get; set; } public Guid CourseId { get; set; } public string Title { get; set; } = ""; public string Content { get; set; } = ""; public DateTimeOffset UpdatedAt { get; set; } = Clock.Now; }
public class DevicePairing : Entity { public string? RequestedProfileJson { get; set; } public bool Automatic { get; set; } public DateTimeOffset? LastSeen { get; set; } public string CodeHash { get; set; } = ""; public string PollHash { get; set; } = ""; public string DeviceTokenHash { get; set; } = ""; public string Name { get; set; } = ""; public Guid? DeviceId { get; set; } public DateTimeOffset ExpiresAt { get; set; } }
public class Handout : Entity
{
    public string RecognitionMode { get; set; } = QuestionRecognition.Legacy;
    public Guid LectureId { get; set; } public int Version { get; set; } public string FileName { get; set; } = "";
    public string OriginalKey { get; set; } = ""; public string NormalizedKey { get; set; } = ""; public string SourcePdfKey { get; set; } = "";
    public string State { get; set; } = "queued"; public string? Error { get; set; } public int Revision { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public record ContentBlock(string Xml, string Text, string Kind);
public record Issue(string Code, string Message, bool Blocking);
public class Question : Entity
{
    public Guid HandoutId { get; set; } public int Order { get; set; } public string Label { get; set; } = ""; public string Family { get; set; } = "number";
    public string BlocksJson { get; set; } = "[]"; public string IssuesJson { get; set; } = "[]"; public string PreviewsJson { get; set; } = "[]";
    public bool Confirmed { get; set; } public int Revision { get; set; } public bool HalfPage { get; set; }
}
public class Asset : Entity { public Guid HandoutId { get; set; } public string RelationshipId { get; set; } = ""; public string ObjectKey { get; set; } = ""; public string ContentType { get; set; } = ""; }
public class QuestionAsset : Entity { public Guid QuestionId { get; set; } public Guid AssetId { get; set; } public int BlockIndex { get; set; } public int Occurrence { get; set; } public long Width { get; set; } public long Height { get; set; } }
public class MobileUpload : Entity { public Guid StaffId { get; set; } public string SessionHash { get; set; } = ""; public string TokenHash { get; set; } = ""; public string Mode { get; set; } = "print"; public Guid? ClassId { get; set; } public Guid? CourseId { get; set; } public Guid? LectureId { get; set; } public string MaterialKind { get; set; } = ""; public string State { get; set; } = "pending"; public string ResultUrl { get; set; } = ""; public DateTimeOffset ExpiresAt { get; set; } }
public class LectureAttendance : Entity { public Guid StudentId { get; set; } public Guid LectureId { get; set; } public bool Absent { get; set; } public Guid RecordedBy { get; set; } public DateTimeOffset UpdatedAt { get; set; } = Clock.Now; }
public class WrongSetting : Entity { public string MarksJson { get; set; } = "{}"; public Guid StudentId { get; set; } public Guid LectureId { get; set; } public Guid HandoutId { get; set; } public Guid RecordedBy { get; set; } public DateTimeOffset RecordedAt { get; set; } = Clock.Now; }
public class WrongQuestion : Entity { public Guid StudentId { get; set; } public Guid QuestionId { get; set; } public Guid RecordedBy { get; set; } public DateTimeOffset RecordedAt { get; set; } = Clock.Now; }
public record QuestionSnapshot(Guid Id, Guid HandoutId, string Label, string Source, string BlocksJson, bool HalfPage, string Note = "");
public class ExportDocument : Entity
{
    public string Layout { get; set; } = "analysis"; public Guid? ClassSessionId { get; set; }
    public bool Archived { get; set; }
    public Guid BatchId { get; set; } public Guid StaffId { get; set; } public Guid? StudentId { get; set; } public Guid? ClassId { get; set; } public Guid? CourseId { get; set; }
    public string Kind { get; set; } = "booklet"; public string SourceKey { get; set; } = ""; public string SourceFileName { get; set; } = ""; public Guid? LectureId { get; set; } public string MaterialKind { get; set; } = "";
    public string StudentName { get; set; } = ""; public string SnapshotJson { get; set; } = "[]"; public string TemplateVersion { get; set; } = WordDocuments.BookletTemplateVersion;
    public string State { get; set; } = "queued"; public string? Error { get; set; } public string DocxKey { get; set; } = ""; public string PdfKey { get; set; } = ""; public string PdfHash { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public class Device : Entity { public string? MonitorVersion { get; set; } public DateTimeOffset? MonitorLastSeen { get; set; } public string? MonitorError { get; set; }  public Guid CampusId { get; set; } public string Name { get; set; } = ""; public string TokenHash { get; set; } = ""; public bool Enabled { get; set; } = true; public DateTimeOffset? LastSeen { get; set; } public string AgentVersion { get; set; } = ""; public string MachineName { get; set; } = ""; public string? ProbeError { get; set; } }
public class DeviceBinding : Entity { public Guid CampusId { get; set; } public string CodeHash { get; set; } = ""; public bool Used { get; set; } public DateTimeOffset ExpiresAt { get; set; } }
public class Printer : Entity { public bool Suppressed { get; set; } public bool? DeviceReachable { get; set; } public DateTimeOffset? DeviceCheckedAt { get; set; }  public Guid DeviceId { get; set; } public Guid CampusId { get; set; } public string Name { get; set; } = ""; public string QueueName { get; set; } = ""; public bool Duplex { get; set; } public bool Enabled { get; set; } = true; public string Status { get; set; } = "unknown"; public string StatusDetail { get; set; } = ""; public long RawStatus { get; set; } public int JobCount { get; set; } public string Driver { get; set; } = ""; public string Port { get; set; } = ""; public DateTimeOffset? StatusAt { get; set; } }
public class PrintJob : Entity
{
    public string? ExecutionJson { get; set; } public DateTimeOffset? ExecutionAt { get; set; }
    public Guid BatchId { get; set; } public Guid StaffId { get; set; } public Guid DocumentId { get; set; } public Guid PrinterId { get; set; } public Guid DeviceId { get; set; }
    public string RequestKey { get; set; } = ""; public string RequestHash { get; set; } = ""; public int Copies { get; set; } public bool Duplex { get; set; }
    public Guid? ReprintOf { get; set; } public string State { get; set; } = "queued"; public string? Detail { get; set; }
    public string? ClaimToken { get; set; } public DateTimeOffset? LeaseUntil { get; set; } public DateTimeOffset CreatedAt { get; set; } = Clock.Now;
}
public class WorkItem : Entity { public string Kind { get; set; } = ""; public Guid EntityId { get; set; } public string State { get; set; } = "queued"; public string? Claim { get; set; } public DateTimeOffset? LeaseUntil { get; set; } public int Attempts { get; set; } public string? Error { get; set; } }
public class FileGrant : Entity { public Guid? ClassId { get; set; } public Guid? CourseId { get; set; } public string TokenHash { get; set; } = ""; public string ObjectKey { get; set; } = ""; public string FileName { get; set; } = ""; public string ContentType { get; set; } = ""; public Guid? StaffId { get; set; } public Guid? DeviceId { get; set; } public DateTimeOffset ExpiresAt { get; set; } }
public class Audit : Entity { public Guid? StaffId { get; set; } public string Action { get; set; } = ""; public Guid TargetId { get; set; } public DateTimeOffset CreatedAt { get; set; } = Clock.Now; }
public partial class Data(DbContextOptions<Data> options) : DbContext(options)
{
    public DbSet<GuangzhouDevice> GuangzhouDevices => Set<GuangzhouDevice>();
    public DbSet<GuangzhouJob> GuangzhouJobs => Set<GuangzhouJob>();
    public DbSet<Staff> Staff => Set<Staff>(); public DbSet<Session> Sessions => Set<Session>(); public DbSet<Invite> Invites => Set<Invite>();
    public DbSet<AutomationCredential> AutomationCredentials => Set<AutomationCredential>();
    public DbSet<PrintRequest> PrintRequests => Set<PrintRequest>();
    public DbSet<PasswordAccount> PasswordAccounts => Set<PasswordAccount>();
    public DbSet<Campus> Campuses => Set<Campus>(); public DbSet<Course> Courses => Set<Course>(); public DbSet<SchoolClass> Classes => Set<SchoolClass>();
    public DbSet<Assignment> Assignments => Set<Assignment>(); public DbSet<Student> Students => Set<Student>(); public DbSet<Lecture> Lectures => Set<Lecture>();
    public DbSet<LessonPlan> LessonPlans => Set<LessonPlan>(); public DbSet<DevicePairing> Pairings => Set<DevicePairing>();
    public DbSet<Handout> Handouts => Set<Handout>(); public DbSet<Question> Questions => Set<Question>(); public DbSet<Asset> Assets => Set<Asset>(); public DbSet<QuestionAsset> QuestionAssets => Set<QuestionAsset>();
    public DbSet<LearningModelConfiguration> LearningModelConfigurations => Set<LearningModelConfiguration>();
    public DbSet<LearningArtifact> LearningArtifacts => Set<LearningArtifact>();
    public DbSet<LearningEvent> LearningEvents => Set<LearningEvent>();
    public DbSet<BookImport> BookImports => Set<BookImport>();
    public DbSet<MobileUpload> MobileUploads => Set<MobileUpload>();
    public DbSet<LectureAttendance> Attendances => Set<LectureAttendance>();
    public DbSet<WrongSetting> WrongSettings => Set<WrongSetting>();
    public DbSet<WrongQuestion> WrongQuestions => Set<WrongQuestion>(); public DbSet<ExportDocument> Documents => Set<ExportDocument>();
    public DbSet<Device> Devices => Set<Device>(); public DbSet<DeviceBinding> Bindings => Set<DeviceBinding>(); public DbSet<Printer> Printers => Set<Printer>(); public DbSet<PrintJob> PrintJobs => Set<PrintJob>();
    public DbSet<DeviceOperation> DeviceOperations => Set<DeviceOperation>();
    public DbSet<ClassSession> ClassSessions => Set<ClassSession>(); public DbSet<ClassSessionMark> ClassSessionMarks => Set<ClassSessionMark>();
    public DbSet<WorkItem> Work => Set<WorkItem>(); public DbSet<FileGrant> Grants => Set<FileGrant>(); public DbSet<Audit> Audits => Set<Audit>();
    protected override void OnModelCreating(ModelBuilder b)
    {
        AcademicPilot.ConfigureModel(b);
        AcademicHomeworkCollection.Configure(b);
        AcademicParentPortal.Configure(b);
        AcademicHomeworkGrading.Configure(b);
        AcademicHomeworkWorkflow.Configure(b);
        AcademicOnlineTesting.Configure(b);
        AcademicTrainingRetesting.Configure(b);
        AcademicOperations.ConfigureModel(b);
        AcademicBeta.ConfigureModel(b);
        b.Entity<GuangzhouDevice>().HasOne<Device>().WithOne().HasForeignKey<GuangzhouDevice>(x=>x.Id).OnDelete(DeleteBehavior.Restrict);
        b.Entity<GuangzhouDevice>().HasIndex(x=>x.QueueId).IsUnique();
        b.Entity<GuangzhouDevice>().HasIndex(x=>x.BindingHash).IsUnique();
        b.Entity<GuangzhouJob>().HasOne<PrintJob>().WithOne().HasForeignKey<GuangzhouJob>(x=>x.Id).OnDelete(DeleteBehavior.Restrict);
        b.Entity<AutomationCredential>().HasIndex(x => x.TokenHash).IsUnique();
        b.Entity<AutomationCredential>().HasOne<Staff>().WithMany().HasForeignKey(x => x.StaffId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<PrintRequest>().HasIndex(x => new { x.StaffId, x.RequestKey }).IsUnique();
        b.Entity<ClassSession>().HasOne<SchoolClass>().WithMany().HasForeignKey(x=>x.ClassId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<ClassSession>().HasOne<Course>().WithMany().HasForeignKey(x=>x.CourseId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<ClassSession>().HasOne<Handout>().WithMany().HasForeignKey(x=>x.HandoutId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<ClassSessionMark>().HasOne<ClassSession>().WithMany().HasForeignKey(x=>x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<ClassSessionMark>().HasOne<Student>().WithMany().HasForeignKey(x=>x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<ClassSessionMark>().HasIndex(x=>new{x.ClassSessionId,x.StudentId}).IsUnique();
        b.Entity<ExportDocument>().Property(x=>x.Layout).HasDefaultValue("analysis");
        b.Entity<ExportDocument>().HasOne<ClassSession>().WithMany().HasForeignKey(x=>x.ClassSessionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<GradeCalendarOwner>().HasIndex(x => new { x.Grade, x.StaffId }).IsUnique();
        b.Entity<GradeCalendarEvent>().HasIndex(x => new { x.Grade, x.TemplateId, x.Anchor, x.StepIndex }).IsUnique();
        b.Entity<GradeCalendarEvent>().HasIndex(x => new { x.Grade, x.Date });
        b.Entity<StudentFollowUpGrant>().HasIndex(x => new { x.ClassId, x.StaffId }).IsUnique();
        b.Entity<StudentFollowUpGrant>().HasIndex(x => x.StaffId);
        b.Entity<StudentFollowUp>().HasIndex(x => new { x.ClassId, x.StudentId, x.CreatedAt });
        b.Entity<StudentFollowUp>().HasIndex(x => new { x.ClassId, x.StudentId, x.StaffId, x.RequestKey }).IsUnique();
        b.Entity<Staff>().HasQueryFilter(x => !x.Archived);
        b.Entity<Campus>().HasQueryFilter(x => !x.Archived);
        b.Entity<Course>().HasQueryFilter(x => !x.Archived);
        b.Entity<SchoolClass>().HasQueryFilter(x => !x.Archived);
        b.Entity<ExportDocument>().HasQueryFilter(x => !x.Archived);
        b.Entity<LearningModelConfiguration>().Property(x => x.Revision).IsConcurrencyToken();
        b.Entity<LearningArtifact>().HasIndex(x => new { x.StudentId, x.RequestKey }).IsUnique();
        b.Entity<LearningArtifact>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<LearningEvent>().HasIndex(x => new { x.StudentId, x.QuestionId, x.RequestKey }).IsUnique();
        b.Entity<LearningEvent>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<LearningEvent>().HasOne<Question>().WithMany().HasForeignKey(x => x.QuestionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<BookImport>().HasIndex(x => new { x.ClassId, x.CourseId });
        b.Entity<BookImport>().HasOne<SchoolClass>().WithMany().HasForeignKey(x => x.ClassId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<BookImport>().HasOne<Course>().WithMany().HasForeignKey(x => x.CourseId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<MobileUpload>().HasIndex(x => x.TokenHash).IsUnique();
        b.Entity<LectureAttendance>().HasIndex(x => new { x.StudentId, x.LectureId }).IsUnique();
        b.Entity<LectureAttendance>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<LectureAttendance>().HasOne<Lecture>().WithMany().HasForeignKey(x => x.LectureId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<WrongSetting>().Property(x => x.MarksJson).HasDefaultValue("{}");
        b.Entity<WrongSetting>().HasIndex(x => new { x.StudentId, x.LectureId }).IsUnique();
        b.Entity<WrongSetting>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<WrongSetting>().HasOne<Lecture>().WithMany().HasForeignKey(x => x.LectureId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<DeviceOperation>().HasIndex(x => new { x.DeviceId, x.RequestKey }).IsUnique();
        b.Entity<DevicePairing>().HasIndex(x => x.CodeHash).IsUnique(); b.Entity<DevicePairing>().HasIndex(x => x.PollHash).IsUnique();
        b.Entity<LessonPlan>().HasOne<Staff>().WithMany().HasForeignKey(x => x.StaffId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<LessonPlan>().HasOne<SchoolClass>().WithMany().HasForeignKey(x => x.ClassId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<LessonPlan>().HasOne<Course>().WithMany().HasForeignKey(x => x.CourseId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Staff>().HasIndex(x => x.Code).IsUnique(); b.Entity<Staff>().HasIndex(x => x.OpenId).IsUnique(); b.Entity<Staff>().HasIndex(x => x.BootstrapKey).IsUnique();
        b.Entity<PasswordAccount>().HasIndex(x => x.Account).IsUnique();
        b.Entity<PasswordAccount>().HasIndex(x => x.StaffId).IsUnique();
        b.Entity<PasswordAccount>().HasOne<Staff>().WithMany().HasForeignKey(x => x.StaffId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Session>().HasIndex(x => x.TokenHash).IsUnique(); b.Entity<Invite>().HasIndex(x => x.CodeHash).IsUnique(); b.Entity<DeviceBinding>().HasIndex(x => x.CodeHash).IsUnique();
        b.Entity<Assignment>().HasIndex(x => new { x.StaffId, x.ClassId, x.CourseId }).IsUnique(); b.Entity<Student>().HasIndex(x => new { x.ClassId, x.Code }).IsUnique();
        b.Entity<Handout>().HasIndex(x => new { x.LectureId, x.Version }).IsUnique(); b.Entity<WrongQuestion>().HasIndex(x => new { x.StudentId, x.QuestionId }).IsUnique();
        b.Entity<Printer>().HasIndex(x => new { x.DeviceId, x.QueueName }).IsUnique(); b.Entity<PrintJob>().HasIndex(x => new { x.StaffId, x.RequestKey, x.DocumentId }).IsUnique();
        b.Entity<PrintJob>().HasIndex(x => new { x.DeviceId, x.State }); b.Entity<WorkItem>().HasIndex(x => x.State); b.Entity<FileGrant>().HasIndex(x => x.TokenHash).IsUnique(); b.Entity<Device>().HasIndex(x => x.TokenHash).IsUnique();
        b.Entity<SchoolClass>().HasOne<Staff>().WithMany().HasForeignKey(x => x.OwnerStaffId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<SchoolClass>().HasOne<Campus>().WithMany().HasForeignKey(x => x.CampusId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Assignment>().HasOne<Staff>().WithMany().HasForeignKey(x => x.StaffId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Assignment>().HasOne<SchoolClass>().WithMany().HasForeignKey(x => x.ClassId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Assignment>().HasOne<Course>().WithMany().HasForeignKey(x => x.CourseId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Student>().HasOne<SchoolClass>().WithMany().HasForeignKey(x => x.ClassId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Lecture>().HasOne<SchoolClass>().WithMany().HasForeignKey(x => x.ClassId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Lecture>().HasOne<Course>().WithMany().HasForeignKey(x => x.CourseId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Handout>().HasOne<Lecture>().WithMany().HasForeignKey(x => x.LectureId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<Question>().HasOne<Handout>().WithMany().HasForeignKey(x => x.HandoutId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<WrongQuestion>().HasOne<Question>().WithMany().HasForeignKey(x => x.QuestionId).OnDelete(DeleteBehavior.Restrict);
        b.Entity<WrongQuestion>().HasOne<Student>().WithMany().HasForeignKey(x => x.StudentId).OnDelete(DeleteBehavior.Restrict);
        foreach (var p in b.Model.GetEntityTypes().SelectMany(e => e.GetProperties()).Where(p => p.ClrType == typeof(DateTimeOffset)))
            p.SetValueConverter(new Microsoft.EntityFrameworkCore.Storage.ValueConversion.ValueConverter<DateTimeOffset, long>(v => v.UtcTicks, v => new DateTimeOffset(v, TimeSpan.Zero)));
        foreach (var p in b.Model.GetEntityTypes().SelectMany(e => e.GetProperties()).Where(p => p.ClrType == typeof(DateTimeOffset?)))
            p.SetValueConverter(new Microsoft.EntityFrameworkCore.Storage.ValueConversion.ValueConverter<DateTimeOffset?, long?>(v => v.HasValue ? v.Value.UtcTicks : null, v => v.HasValue ? new DateTimeOffset(v.Value, TimeSpan.Zero) : null));
    }
}
public static class Json
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);
    public static string Write<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T Read<T>(string value) => JsonSerializer.Deserialize<T>(value, Options)!;
}
