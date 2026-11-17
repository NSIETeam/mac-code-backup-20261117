using Microsoft.EntityFrameworkCore;
namespace Siyue.Collaboration;

public abstract class Row { public Guid Id { get; set; } = Guid.NewGuid(); }
public class Project : Row {
 public string Name {get;set;}=""; public string Goal {get;set;}=""; public string Approach {get;set;}=""; public string Department {get;set;}="";
 public string OwnerTeam {get;set;}=""; public Guid? OwnerId {get;set;} public bool Confidential {get;set;} public string Status {get;set;}="planned"; public string Summary {get;set;}="";
 public long Revision {get;set;}=1; public DateTime UpdatedAt {get;set;}=DateTime.UtcNow;
 public Guid? RepositoryId {get;set;} public Guid? TestingBuildId {get;set;} public Guid? LockedBuildId {get;set;} public Guid? ProductionBuildId {get;set;}
}
public class Member : Row {public Guid ProjectId {get;set;} public Guid StaffId {get;set;} public string Role {get;set;}="contributor";}
public class WorkTask : Row {
 public Guid ProjectId {get;set;} public Guid? MilestoneId {get;set;} public string Kind {get;set;}="task"; public string Title {get;set;}="";
 public string Description {get;set;}=""; public Guid? OwnerId {get;set;} public string Collaborators {get;set;}="[]";
 public string State {get;set;}="planned"; public DateOnly? StartsOn {get;set;} public DateOnly? DueOn {get;set;}
 public string Criteria {get;set;}=""; public string Blocker {get;set;}=""; public Guid? NeedsStaffId {get;set;}
 public DateTime? CompletedAt {get;set;} public long Revision {get;set;}=1; public DateTime UpdatedAt {get;set;}=DateTime.UtcNow;
}
public class TaskAction : Row {
 public Guid ProjectId {get;set;} public Guid? TaskId {get;set;} public Guid AuthorId {get;set;}
 public string Title {get;set;}=""; public string Body {get;set;}=""; public string Kind {get;set;}="update";
 public DateOnly? PlannedOn {get;set;} public string DateLabel {get;set;}=""; public string State {get;set;}="planned";
 public Guid? RepositoryId {get;set;} public string CodeRef {get;set;}=""; public string RequestId {get;set;}="";
 public long Revision {get;set;}=1; public DateTime CreatedAt {get;set;}=DateTime.UtcNow; public DateTime UpdatedAt {get;set;}=DateTime.UtcNow;
}
public class Progress : Row {
 public Guid ProjectId {get;set;} public Guid? TaskId {get;set;} public Guid? ActionId {get;set;} public Guid? ParentId {get;set;} public Guid AuthorId {get;set;}
 public string Body {get;set;}=""; public string Next {get;set;}=""; public string Mentions {get;set;}="[]";
 public string RequestId {get;set;}=""; public DateTime CreatedAt {get;set;}=DateTime.UtcNow;
}
public class Attachment : Row { public Guid ProjectId {get;set;} public Guid? TaskId {get;set;} public Guid? ActionId {get;set;} public Guid AuthorId {get;set;} public string Name {get;set;}=""; public string Hash {get;set;}=""; public long Size {get;set;} public DateTime CreatedAt {get;set;}=DateTime.UtcNow; }
public class Notification : Row {public Guid StaffId {get;set;} public Guid ProjectId {get;set;} public Guid? TargetId {get;set;} public string Kind {get;set;}=""; public string Text {get;set;}=""; public string EventKey {get;set;}=""; public DateTime CreatedAt {get;set;}=DateTime.UtcNow; public DateTime? ReadAt {get;set;}}
public class Audit : Row {public Guid? StaffId {get;set;} public Guid? ProjectId {get;set;} public string Action {get;set;}=""; public string Detail {get;set;}=""; public DateTime CreatedAt {get;set;}=DateTime.UtcNow;}
public class PersonProfile : Row {public Guid StaffId {get;set;} public string Department {get;set;}="";}
public class AgentGrant : Row {
 public Guid StaffId {get;set;} public string Name {get;set;}="Workbuddy"; public string TokenHash {get;set;}="";
 public string SessionHash {get;set;}=""; public string ProjectIds {get;set;}="[]"; public string Scope {get;set;}="read";
 public DateTime CreatedAt {get;set;}=DateTime.UtcNow; public DateTime ExpiresAt {get;set;} public DateTime? RevokedAt {get;set;}
}
public class Repository : Row {
 public string PrototypeAuthor {get;set;}=""; public Guid? ParentRepositoryId {get;set;} public string ModulePath {get;set;}="";
 public string Name {get;set;}=""; public string FullName {get;set;}=""; public long GiteaId {get;set;}
 public string MainBranch {get;set;}="main"; public string TestCommand {get;set;}="node --test";
 public string DeploymentKey {get;set;}=""; public string HeadSha {get;set;}=""; public bool Enabled {get;set;}
 public bool AutomationEnabled {get;set;} public string SyncState {get;set;}="pending"; public string SyncError {get;set;}="";
}
public class GitIdentity : Row {public Guid StaffId {get;set;} public string Login {get;set;}=""; public long GiteaId {get;set;} public DateTime? SyncedAt {get;set;}}
public class GitEvent : Row {public string Delivery {get;set;}=""; public string Kind {get;set;}=""; public string Payload {get;set;}=""; public string State {get;set;}="pending"; public int Attempts {get;set;} public DateTime CreatedAt {get;set;}=DateTime.UtcNow; public DateTime? NextAttemptAt {get;set;}}
public class CodeChange : Row { public Guid RepositoryId {get;set;} public long Number {get;set;} public string Title {get;set;}=""; public string State {get;set;}="open"; public string Sha {get;set;}=""; public string Branch {get;set;}=""; public string Actor {get;set;}=""; public string Url {get;set;}=""; public Guid? TaskId {get;set;} public DateTime UpdatedAt {get;set;}=DateTime.UtcNow; }
public class Build : Row {
 public Guid RepositoryId {get;set;} public string Sha {get;set;}=""; public string Branch {get;set;}=""; public string Kind {get;set;}="check";
 public string State {get;set;}="queued"; public string ArtifactDigest {get;set;}=""; public string Summary {get;set;}="";
 public string Actor {get;set;}=""; public DateTime CreatedAt {get;set;}=DateTime.UtcNow; public DateTime? FinishedAt {get;set;}
}
public class Acceptance : Row {public Guid ProjectId {get;set;} public Guid BuildId {get;set;} public Guid StaffId {get;set;} public string Sha {get;set;}=""; public string ArtifactDigest {get;set;}=""; public bool Passed {get;set;} public string Notes {get;set;}=""; public DateTime CreatedAt {get;set;}=DateTime.UtcNow;}
public class Release : Row {
 public Guid ProjectId {get;set;} public Guid BuildId {get;set;} public Guid RequestedBy {get;set;} public Guid? PublishedBy {get;set;}
 public string State {get;set;}="requested"; public string Notes {get;set;}=""; public string DatabaseChanges {get;set;}="none"; public string RecoveryPlan {get;set;}="";
 public string Result {get;set;}=""; public bool IsRollback {get;set;} public DateTime CreatedAt {get;set;}=DateTime.UtcNow; public DateTime? FinishedAt {get;set;}
}
public class CollaborationDb(DbContextOptions<CollaborationDb> o):DbContext(o) {
 public DbSet<AgentGrant> AgentGrants=>Set<AgentGrant>();
 public DbSet<Project> Projects=>Set<Project>(); public DbSet<Member> Members=>Set<Member>(); public DbSet<WorkTask> Tasks=>Set<WorkTask>();
 public DbSet<Progress> Progress=>Set<Progress>(); public DbSet<TaskAction> TaskActions=>Set<TaskAction>(); public DbSet<Attachment> Attachments=>Set<Attachment>(); public DbSet<Notification> Notifications=>Set<Notification>();
 public DbSet<Audit> Audits=>Set<Audit>(); public DbSet<PersonProfile> People=>Set<PersonProfile>(); public DbSet<Repository> Repositories=>Set<Repository>();
 public DbSet<GitIdentity> GitIdentities=>Set<GitIdentity>(); public DbSet<GitEvent> GitEvents=>Set<GitEvent>(); public DbSet<CodeChange> Changes=>Set<CodeChange>();
 public DbSet<Build> Builds=>Set<Build>(); public DbSet<Acceptance> Acceptances=>Set<Acceptance>(); public DbSet<Release> Releases=>Set<Release>();
 protected override void OnModelCreating(ModelBuilder b) {
  base.OnModelCreating(b); b.UseOpenIddict();
  b.Entity<AgentGrant>().HasIndex(x=>x.TokenHash).IsUnique();
  b.Entity<TaskAction>().HasIndex(x=>new{x.ProjectId,x.RequestId}).IsUnique();
  b.Entity<TaskAction>().HasOne<Project>().WithMany().HasForeignKey(x=>x.ProjectId).OnDelete(DeleteBehavior.Restrict);
  b.Entity<TaskAction>().HasOne<WorkTask>().WithMany().HasForeignKey(x=>x.TaskId).OnDelete(DeleteBehavior.Restrict);
  b.Entity<TaskAction>().Property(x=>x.Revision).IsConcurrencyToken();
  b.Entity<Progress>().HasIndex(x=>x.ActionId);
  b.Entity<Member>().HasIndex(x=>new{x.ProjectId,x.StaffId}).IsUnique(); b.Entity<Member>().HasOne<Project>().WithMany().HasForeignKey(x=>x.ProjectId).OnDelete(DeleteBehavior.Restrict);
  b.Entity<WorkTask>().HasOne<Project>().WithMany().HasForeignKey(x=>x.ProjectId).OnDelete(DeleteBehavior.Restrict);
  b.Entity<Project>().Property(x=>x.Revision).IsConcurrencyToken(); b.Entity<WorkTask>().Property(x=>x.Revision).IsConcurrencyToken();
  b.Entity<Notification>().HasIndex(x=>new{x.StaffId,x.EventKey}).IsUnique(); b.Entity<Progress>().HasIndex(x=>new{x.AuthorId,x.RequestId}).IsUnique();
  b.Entity<PersonProfile>().HasIndex(x=>x.StaffId).IsUnique(); b.Entity<GitIdentity>().HasIndex(x=>x.StaffId).IsUnique();
  b.Entity<Repository>().HasIndex(x=>x.FullName).IsUnique(); b.Entity<GitEvent>().HasIndex(x=>x.Delivery).IsUnique();
  b.Entity<CodeChange>().HasIndex(x=>new{x.RepositoryId,x.Number}).IsUnique(); b.Entity<Build>().HasIndex(x=>new{x.RepositoryId,x.Sha,x.Kind}).IsUnique();
  b.Entity<Notification>().HasIndex(x=>new{x.StaffId,x.CreatedAt}); b.Entity<Progress>().HasIndex(x=>new{x.ProjectId,x.CreatedAt});
 }
}
