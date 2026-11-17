using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public partial class AcademicBetaTests
{
    static string RollCallPath(Fixture x) => $"{Api}/sessions/{x.SessionId}/roll-call";
    static string RollCallSnapshot(JsonElement value) => value.GetProperty("snapshot").GetString()!;
    static object RollCallLeave(Guid studentId) => new { studentId, status = "leave" };
    static object RollCallTransfer(Guid studentId, Guid classId, string date = "2026-11-02")
        => new { studentId, status = "transfer", transferClassId = classId, transferOn = date };
    static object RollCallInput(JsonElement value, params object[] exceptions)
        => new { snapshot = RollCallSnapshot(value), confirmed = true, exceptions };

    static async Task<(Guid ClassId, Guid SessionId)> RollCallTarget(BetaFactory f, Fixture x)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var source = await db.ClassSessions.AsNoTracking().SingleAsync(s => s.Id == x.SessionId);
        var campusId = (await db.Classes.AsNoTracking().SingleAsync(c => c.Id == x.ClassId)).CampusId;
        var target = new SchoolClass { Name = "临时调入班", CampusId = campusId };
        var session = new ClassSession { ClassId = target.Id, CourseId = x.CourseId, HandoutId = source.HandoutId,
            StaffId = f.AuthorId, LessonDate = "2026-11-02" };
        db.AddRange(target, session,
            new Assignment { StaffId = f.AuthorId, ClassId = target.Id, CourseId = x.CourseId },
            new Assignment { StaffId = f.ReviewerId, ClassId = target.Id, CourseId = x.CourseId });
        await db.SaveChangesAsync();
        return (target.Id, session.Id);
    }

    static async Task<string> RollCallBetaSnapshot(BetaFactory f)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        return Json.Write(new {
            attendance = await db.AcademicAttendances.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            events = await db.AcademicOperationEvents.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            communications = await db.AcademicCommunications.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            messageCommunications = await db.AcademicMessageCommunications.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync()
        });
    }

    static async Task<string> RollCallOriginalSnapshot(BetaFactory f)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        return Json.Write(new {
            original = await OriginalBusinessSnapshot(f),
            students = await db.Students.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            classes = await db.Classes.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            sessions = await db.ClassSessions.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync(),
            assignments = await db.Assignments.AsNoTracking().OrderBy(x => x.Id).ToArrayAsync()
        });
    }

    [Fact]
    public async Task RollCallReadsDoNotWriteAndConfirmationRecordsOnlySelectedExceptionsWithoutMovingStudents()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        var target = await RollCallTarget(f, x);
        using var author = await f.Login("beta-author");
        var original = await RollCallOriginalSnapshot(f);
        var beta = await RollCallBetaSnapshot(f);
        var path = RollCallPath(x);
        var before = await Get(author, path);
        Assert.Equal(4, before.GetProperty("students").GetArrayLength());
        Assert.All(before.GetProperty("students").EnumerateArray(), s => Assert.Equal("unrecorded", s.GetProperty("status").GetString()));
        Assert.Equal(beta, await RollCallBetaSnapshot(f));
        Assert.Equal(RollCallSnapshot(before), RollCallSnapshot(await Get(author, path)));
        var unconfirmed = await author.PutAsJsonAsync(path, new { snapshot = RollCallSnapshot(before), confirmed = false, exceptions = Array.Empty<object>() });
        Assert.Equal(HttpStatusCode.BadRequest, unconfirmed.StatusCode);
        Assert.Equal(beta, await RollCallBetaSnapshot(f));

        var after = await Put(author, path, RollCallInput(before, RollCallLeave(x.StudentId), RollCallTransfer(x.SecondStudentId, target.ClassId)));
        Assert.NotEqual(RollCallSnapshot(before), RollCallSnapshot(after));
        Assert.Equal("leave", StudentRow(after, x.StudentId).GetProperty("status").GetString());
        var transferred = StudentRow(after, x.SecondStudentId);
        Assert.Equal("transfer", transferred.GetProperty("status").GetString());
        Assert.Equal(target.ClassId, transferred.GetProperty("transferClassId").GetGuid());
        Assert.Equal("临时调入班", transferred.GetProperty("transferClassName").GetString());
        Assert.Equal("2026-11-02", transferred.GetProperty("transferOn").GetString());
        foreach (var studentId in new[] { x.ThirdStudentId, x.UnreviewedStudentId })
            Assert.Equal("present", StudentRow(after, studentId).GetProperty("status").GetString());
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            Assert.Equal(4, await db.AcademicAttendances.CountAsync(a => a.ClassSessionId == x.SessionId));
            Assert.Equal("pending", (await db.AcademicAttendances.SingleAsync(a => a.StudentId == x.StudentId)).MakeupStatus);
            Assert.Empty(await db.AcademicCommunications.ToArrayAsync());
            Assert.Empty(await db.AcademicMessageCommunications.ToArrayAsync());
        }
        Assert.Equal(original, await RollCallOriginalSnapshot(f));
        var stats = await Get(author, $"{Api}/statistics?from=2026-10-31&to=2026-10-31&classSessionId={x.SessionId}");
        Assert.Equal(2, stats.GetProperty("attendance").GetProperty("present").GetInt32());
        Assert.Equal(1, stats.GetProperty("attendance").GetProperty("leave").GetInt32());
        Assert.Equal(1, stats.GetProperty("attendance").GetProperty("transfer").GetInt32());
        Assert.Equal(0, stats.GetProperty("attendance").GetProperty("unrecorded").GetInt32());
    }

    [Fact]
    public async Task RollCallRejectsInvalidOrDuplicateExceptionsBeforeAnyStudentIsSaved()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        var target = await RollCallTarget(f, x);
        using var author = await f.Login("beta-author");
        var path = RollCallPath(x);
        var before = await Get(author, path);
        var beta = await RollCallBetaSnapshot(f);
        var original = await RollCallOriginalSnapshot(f);
        var invalid = new object[][] {
            [RollCallLeave(x.StudentId), RollCallLeave(x.StudentId)],
            [RollCallLeave(x.StudentId), new { studentId = x.SecondStudentId, status = "present" }],
            [RollCallLeave(x.StudentId), new { studentId = x.SecondStudentId, status = "transfer" }],
            [RollCallLeave(x.StudentId), new { studentId = x.SecondStudentId, status = "transfer", transferClassId = target.ClassId }],
            [RollCallLeave(x.StudentId), RollCallTransfer(x.SecondStudentId, target.ClassId, "2026-02-30")],
            [RollCallLeave(x.StudentId), RollCallTransfer(x.SecondStudentId, x.ClassId)],
            [RollCallLeave(x.StudentId), new { studentId = x.SecondStudentId, status = "leave", transferClassId = target.ClassId, transferOn = "2026-11-02" }]
        };
        foreach (var exceptions in invalid) {
            using var response = await author.PutAsJsonAsync(path, RollCallInput(before, exceptions));
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
            Assert.Equal(beta, await RollCallBetaSnapshot(f));
        }
        using var foreign = await author.PutAsJsonAsync(path, RollCallInput(before, RollCallLeave(x.StudentId), RollCallLeave(x.ForeignStudentId)));
        Assert.Equal(HttpStatusCode.Forbidden, foreign.StatusCode);
        Assert.Equal(beta, await RollCallBetaSnapshot(f));
        Assert.Equal(original, await RollCallOriginalSnapshot(f));
    }

    [Fact]
    public async Task RollCallRejectsStaleAttendanceOrRosterInsteadOfOverwritingAnotherTeachersWork()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        var path = RollCallPath(x);
        var before = await Get(author, path);
        await Put(author, $"{Api}/sessions/{x.SessionId}/attendance/{x.StudentId}", new {
            revision = 0, status = "leave", note = "教务已核实请假", makeupStatus = "scheduled", makeupOn = "2026-11-02"
        });
        var changed = await RollCallBetaSnapshot(f);
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(path, RollCallInput(before))).StatusCode);
        Assert.Equal(changed, await RollCallBetaSnapshot(f));
        var rosterBefore = await Get(author, path);
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var student = new Student { ClassId = x.ClassId, Name = "刚补入课次名单", Code = "005" };
            db.AddRange(student, new ClassSessionMark { ClassSessionId = x.SessionId, StudentId = student.Id, RecordedBy = f.AuthorId });
            await db.SaveChangesAsync();
        }
        Assert.Equal(HttpStatusCode.Conflict, (await author.PutAsJsonAsync(path, RollCallInput(rosterBefore))).StatusCode);
        Assert.Equal(changed, await RollCallBetaSnapshot(f));
        var fresh = await Get(author, path);
        Assert.Equal(5, fresh.GetProperty("students").GetArrayLength());
        await Put(author, path, RollCallInput(fresh, RollCallLeave(x.StudentId)));
        var saved = await Get(author, path);
        Assert.Equal("教务已核实请假", StudentRow(saved, x.StudentId).GetProperty("note").GetString());
        Assert.Equal(4, saved.GetProperty("students").EnumerateArray().Count(s => s.GetProperty("status").GetString() == "present"));
    }

    [Fact]
    public async Task RollCallConcurrentSubmissionsHaveOneWinnerAndNeverMixTheirExceptions()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var path = RollCallPath(x);
        var before = await Get(author, path);
        var responses = await Task.WhenAll(
            author.PutAsJsonAsync(path, RollCallInput(before, RollCallLeave(x.StudentId))),
            reviewer.PutAsJsonAsync(path, RollCallInput(before, RollCallLeave(x.SecondStudentId))));
        Assert.Single(responses, r => r.StatusCode == HttpStatusCode.OK);
        Assert.Single(responses, r => r.StatusCode == HttpStatusCode.Conflict);
        var saved = await Get(author, path);
        var leaveId = Assert.Single(saved.GetProperty("students").EnumerateArray(), s => s.GetProperty("status").GetString() == "leave").GetProperty("studentId").GetGuid();
        Assert.Equal(responses[0].IsSuccessStatusCode ? x.StudentId : x.SecondStudentId, leaveId);
        Assert.Equal(3, saved.GetProperty("students").EnumerateArray().Count(s => s.GetProperty("status").GetString() == "present"));
        using var scope = f.Services.CreateScope();
        Assert.Equal(4, await scope.ServiceProvider.GetRequiredService<Data>().AcademicAttendances.CountAsync());
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RollCallRemainsHiddenForDisabledBetaAndNonPilotAdministrators(bool enabled)
    {
        await using var f = new BetaFactory(enabled);
        var x = await Seed(f);
        using var client = await f.Login(enabled ? "beta-outsider" : "beta-author");
        var before = await RollCallOriginalSnapshot(f);
        var beta = await RollCallBetaSnapshot(f);
        var path = RollCallPath(x);
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync(path)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.PutAsJsonAsync(path, new { snapshot = "untrusted", confirmed = true, exceptions = Array.Empty<object>() })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/me")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/class-sessions")).StatusCode);
        Assert.Equal(before, await RollCallOriginalSnapshot(f));
        Assert.Equal(beta, await RollCallBetaSnapshot(f));
    }

    [Fact]
    public async Task StandaloneAttendanceUsesCoursePermissionsWithoutOpeningThePilot()
    {
        await using var f = new BetaFactory(false);
        var x = await Seed(f);
        using var teacher = await f.Login("beta-author");
        using var foreign = await f.Login("beta-foreign");
        using var anonymous = f.CreateClient();
        var path = $"/api/attendance/sessions/{x.SessionId}/roll-call";
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync(path)).StatusCode);
        var snapshot = await Get(teacher, path);
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync(path)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.PutAsJsonAsync(path, RollCallInput(snapshot))).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await teacher.GetAsync($"/api/attendance/sessions/{x.OtherCourseSessionId}/roll-call")).StatusCode);
        await Put(teacher, path, RollCallInput(snapshot));
        Assert.Equal(HttpStatusCode.NotFound, (await teacher.GetAsync(RollCallPath(x))).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await teacher.GetAsync(Api+"/collections")).StatusCode);
    }

    [Fact]
    public async Task RollCallChecksSourceCourseAndTargetClassPermissionsOnEveryWrite()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        var target = await RollCallTarget(f, x);
        using var author = await f.Login("beta-author");
        using var foreign = await f.Login("beta-foreign");
        var path = RollCallPath(x);
        var before = await Get(author, path);
        var choices = before.GetProperty("transferClasses").EnumerateArray().ToArray();
        Assert.Contains(choices, c => c.GetProperty("id").GetGuid() == target.ClassId);
        Assert.DoesNotContain(choices, c => c.GetProperty("id").GetGuid() == x.ClassId);
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.GetAsync(path)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await foreign.PutAsJsonAsync(path, RollCallInput(before))).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await author.GetAsync($"{Api}/sessions/{x.OtherCourseSessionId}/roll-call")).StatusCode);
        Guid foreignClassId;
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            foreignClassId = (await db.ClassSessions.SingleAsync(s => s.Id == x.ForeignSessionId)).ClassId;
            var assignment = await db.Assignments.SingleAsync(a => a.StaffId == f.AuthorId && a.ClassId == target.ClassId);
            db.Assignments.Remove(assignment); await db.SaveChangesAsync();
        }
        Assert.DoesNotContain(choices, c => c.GetProperty("id").GetGuid() == foreignClassId);
        var beta = await RollCallBetaSnapshot(f);
        foreach (var targetId in new[] { foreignClassId, target.ClassId }) {
            Assert.Equal(HttpStatusCode.Forbidden, (await author.PutAsJsonAsync(path, RollCallInput(before, RollCallTransfer(x.StudentId, targetId)))).StatusCode);
            Assert.Equal(beta, await RollCallBetaSnapshot(f));
        }
        var fresh = await Get(author, path);
        Assert.DoesNotContain(fresh.GetProperty("transferClasses").EnumerateArray(), c => c.GetProperty("id").GetGuid() == target.ClassId);
    }

    [Fact]
    public async Task RollCallPreservesUnchangedNotesAndMakeupThenClearsTemporaryTransferWhenRestored()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        var target = await RollCallTarget(f, x);
        using var author = await f.Login("beta-author");
        var path = RollCallPath(x);
        await Put(author, $"{Api}/sessions/{x.SessionId}/attendance/{x.StudentId}", new {
            revision = 0, status = "leave", note = "保留：与家长确认过的补课安排", makeupStatus = "scheduled", makeupOn = "2026-11-03"
        });
        var before = await Get(author, path);
        var saved = await Put(author, path, RollCallInput(before, RollCallLeave(x.StudentId), RollCallTransfer(x.SecondStudentId, target.ClassId)));
        Assert.Equal(Revision(StudentRow(before, x.StudentId)), Revision(StudentRow(saved, x.StudentId)));
        Assert.Equal("保留：与家长确认过的补课安排", StudentRow(saved, x.StudentId).GetProperty("note").GetString());
        using (var scope = f.Services.CreateScope()) {
            var row = await scope.ServiceProvider.GetRequiredService<Data>().AcademicAttendances.SingleAsync(a => a.StudentId == x.StudentId);
            Assert.Equal("scheduled", row.MakeupStatus); Assert.Equal("2026-11-03", row.MakeupOn);
        }
        var beta = await RollCallBetaSnapshot(f);
        await Put(author, path, RollCallInput(saved, RollCallLeave(x.StudentId), RollCallTransfer(x.SecondStudentId, target.ClassId)));
        Assert.Equal(beta, await RollCallBetaSnapshot(f));
        var restored = await Put(author, path, RollCallInput(saved, RollCallLeave(x.StudentId)));
        var normal = StudentRow(restored, x.SecondStudentId);
        Assert.Equal("present", normal.GetProperty("status").GetString());
        Assert.Equal(JsonValueKind.Null, normal.GetProperty("transferClassId").ValueKind);
        Assert.Equal(JsonValueKind.Null, normal.GetProperty("transferOn").ValueKind);

        var transferredAgain = await Put(author, path, RollCallInput(restored, RollCallLeave(x.StudentId), RollCallTransfer(x.SecondStudentId, target.ClassId)));
        await Put(author, $"{Api}/sessions/{x.SessionId}/attendance/{x.SecondStudentId}", new {
            revision = Revision(StudentRow(transferredAgain, x.SecondStudentId)), status = "present", note = "已回原班", makeupStatus = "none"
        });
        var legacyRestored = StudentRow(await Get(author, path), x.SecondStudentId);
        Assert.Equal(JsonValueKind.Null, legacyRestored.GetProperty("transferClassId").ValueKind);
        Assert.Equal(JsonValueKind.Null, legacyRestored.GetProperty("transferOn").ValueKind);
    }

    [Fact]
    public async Task RollCallIncomingTransfersAreDateAndCourseScopedReadOnlyAndDoNotJoinTheFormalRoster()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        var target = await RollCallTarget(f, x);
        using var author = await f.Login("beta-author");
        Guid otherDateId, otherCourseId, targetStudentId;
        using (var scope = f.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var targetSession = await db.ClassSessions.SingleAsync(s => s.Id == target.SessionId);
            var otherCourse = await db.ClassSessions.SingleAsync(s => s.Id == x.OtherCourseSessionId);
            var otherDateSession = new ClassSession { ClassId = target.ClassId, CourseId = x.CourseId,
                HandoutId = targetSession.HandoutId, StaffId = f.AuthorId, LessonDate = "2026-11-03" };
            var otherCourseSession = new ClassSession { ClassId = target.ClassId, CourseId = otherCourse.CourseId,
                HandoutId = otherCourse.HandoutId, StaffId = f.AuthorId, LessonDate = "2026-11-02" };
            var targetStudent = new Student { ClassId = target.ClassId, Name = "接收班原有学生", Code = "001" };
            targetStudentId = targetStudent.Id;
            otherDateId = otherDateSession.Id; otherCourseId = otherCourseSession.Id;
            db.AddRange(otherDateSession, otherCourseSession, targetStudent,
                new ClassSessionMark { ClassSessionId = target.SessionId, StudentId = targetStudent.Id, RecordedBy = f.AuthorId },
                new Assignment { StaffId = f.AuthorId, ClassId = target.ClassId, CourseId = otherCourse.CourseId });
            await db.SaveChangesAsync();
        }
        var original = await RollCallOriginalSnapshot(f);
        var path = RollCallPath(x);
        await Put(author, path, RollCallInput(await Get(author, path), RollCallTransfer(x.StudentId, target.ClassId)));
        var beta = await RollCallBetaSnapshot(f);
        var targetPath = $"{Api}/sessions/{target.SessionId}/roll-call";
        var incoming = await Get(author, targetPath);
        Assert.Equal(targetStudentId, Assert.Single(incoming.GetProperty("students").EnumerateArray()).GetProperty("studentId").GetGuid());
        var row = Assert.Single(incoming.GetProperty("incomingTransfers").EnumerateArray());
        Assert.Equal(x.StudentId, row.GetProperty("studentId").GetGuid());
        Assert.Equal("本班学生甲", row.GetProperty("name").GetString());
        Assert.Equal("Beta本班", row.GetProperty("sourceClassName").GetString());
        Assert.Equal("2026-11-02", row.GetProperty("transferOn").GetString());
        foreach (var sessionId in new[] { otherDateId, otherCourseId })
            Assert.Empty((await Get(author, $"{Api}/sessions/{sessionId}/roll-call")).GetProperty("incomingTransfers").EnumerateArray());
        Assert.Equal(HttpStatusCode.Forbidden, (await author.PutAsJsonAsync(targetPath, RollCallInput(incoming, RollCallLeave(x.StudentId)))).StatusCode);
        Assert.Equal(beta, await RollCallBetaSnapshot(f));
        Assert.Equal(original, await RollCallOriginalSnapshot(f));
    }

    [Fact]
    public async Task RollCallChangesMakeSharedFeedbackStaleAndRequireAcademicReviewAgain()
    {
        await using var f = new BetaFactory();
        var x = await Seed(f);
        var target = await RollCallTarget(f, x);
        using var author = await f.Login("beta-author");
        using var reviewer = await f.Login("beta-reviewer");
        var id = await Message(author, x, "learning_feedback", x.StudentId);
        await EditMessage(author, id, "教务先前审核的学习反馈", "已核实的学生甲监护人", "wecom_private");
        var approved = await ApproveMessage(reviewer, id);
        Assert.False(approved.GetProperty("sourceStale").GetBoolean());
        var path = RollCallPath(x);
        await Put(author, path, RollCallInput(await Get(author, path), RollCallTransfer(x.StudentId, target.ClassId)));
        foreach (var prefix in new[] { "drafts", "messages" }) {
            var feedbackPath = $"{Api}/{prefix}/{id}";
            Assert.True((await Get(reviewer, feedbackPath)).GetProperty("sourceStale").GetBoolean());
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync(feedbackPath + "/handoff")).StatusCode);
            Assert.Equal(HttpStatusCode.Conflict, (await reviewer.PostAsJsonAsync(feedbackPath + "/approve", new { revision = Revision(approved), confirmed = true })).StatusCode);
        }
        await Factory.Post(author, $"{Api}/drafts/{id}/refresh", new { revision = Revision(approved) });
        var refreshed = await Get(reviewer, $"{Api}/drafts/{id}");
        Assert.False(refreshed.GetProperty("sourceStale").GetBoolean());
        Assert.Equal("draft", refreshed.GetProperty("state").GetString());
        Assert.Contains("临时调班", refreshed.GetProperty("body").GetString());
        Assert.Equal(HttpStatusCode.Conflict, (await reviewer.GetAsync($"{Api}/drafts/{id}/handoff")).StatusCode);
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        Assert.Empty(await db.AcademicCommunications.ToArrayAsync());
        Assert.Empty(await db.AcademicMessageCommunications.ToArrayAsync());
    }
}
