using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class UsagePostersTests
{
    [Fact]
    public async Task EndpointCountsRegisteredBookletTasksAndUsesTeacherHomeCity()
    {
        await using var factory = new Factory();
        using var admin = await factory.Login("poster-admin", bootstrap: true);
        using var anonymous = factory.CreateClient();
        var createdAt = new DateTimeOffset(2026, 11, 14, 17, 0, 0, TimeSpan.Zero);
        using (var scope = factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<Data>();
            var jn = new Campus { City = "济南", Name = "洪楼" };
            var gz = new Campus { City = "广州", Name = "万博" };
            var teacher = new Staff { Name = "李老师", Role = "teacher" };
            var helper = new Staff { Name = "王主任", Role = "admin" };
            var classroom = new SchoolClass { CampusId = jn.Id, Name = "初一数学" };
            var student = new Student { ClassId = classroom.Id, Name = "学员甲", Code = "001" };
            var course = new Course { Name = "数学" };
            var first = new Lecture { ClassId = classroom.Id, CourseId = course.Id, Sequence = 1 };
            var second = new Lecture { ClassId = classroom.Id, CourseId = course.Id, Sequence = 2 };
            var third = new Lecture { ClassId = classroom.Id, CourseId = course.Id, Sequence = 3 };
            var h1 = new Handout { LectureId = first.Id };
            var h2 = new Handout { LectureId = second.Id };
            var updatedH1 = new Handout { LectureId = first.Id, Version = 2 };
            var session = new ClassSession { ClassId = classroom.Id, HandoutId = h1.Id, CourseId = course.Id, StaffId = teacher.Id, LessonDate = "2026-11-14" };
            var device = new Device { CampusId = gz.Id, Name = "办公室", TokenHash = Secrets.New() };
            var printer = new Printer { CampusId = gz.Id, DeviceId = device.Id, Name = "办公室打印机" };
            var one = new ExportDocument { ClassSessionId = session.Id, ClassId = classroom.Id, StudentId = student.Id, CourseId = course.Id, StaffId = teacher.Id, State = "ready" };
            var both = new ExportDocument { ClassId = classroom.Id, StudentId = student.Id, CourseId = course.Id, StaffId = teacher.Id, State = "ready", Archived = true,
                SnapshotJson = Json.Write(new[] { new { handoutId = h1.Id }, new { handoutId = updatedH1.Id }, new { handoutId = h2.Id } }) };
            var failedOnly = new ExportDocument { ClassId = classroom.Id, StudentId = student.Id, CourseId = course.Id, StaffId = teacher.Id, State = "ready", LectureId = third.Id };
            var file = new ExportDocument { Kind = "file", ClassId = classroom.Id, StudentId = student.Id, CourseId = course.Id, StaffId = teacher.Id, State = "ready" };
            PrintJob Print(ExportDocument doc, Staff who, string state, int copies, string reference, DateTimeOffset at) => new()
            {
                DocumentId = doc.Id, PrinterId = printer.Id, DeviceId = device.Id, StaffId = who.Id,
                State = state, Copies = copies, RequestKey = reference, CreatedAt = at
            };
            db.AddRange(jn, gz, teacher, helper, classroom, student, course, first, second, third, h1, h2, updatedH1, session, device, printer, one, both, failedOnly, file);
            db.AddRange(Print(one, teacher, "submitted", 1, "job-original", createdAt),
                Print(one, teacher, "submitted", 2, "job-reprint", createdAt),
                Print(one, teacher, "submitted", 3, "job-collected", createdAt),
                Print(one, helper, "submitted", 1, "job-operator", createdAt),
                Print(both, teacher, "queued", 5, "job-queued", createdAt.AddDays(-1)),
                Print(failedOnly, teacher, "failed", 9, "job-failed", createdAt),
                Print(one, teacher, "uncertain", 4, "job-uncertain", createdAt),
                Print(file, teacher, "submitted", 6, "job-file", createdAt),
                Print(both, teacher, "submitted", 7, "job-outside", createdAt.AddDays(-2)));
            await db.SaveChangesAsync();
            var organization = scope.ServiceProvider.GetRequiredService<UsageOrganizationStore>();
            await organization.Save(new UsageOrganization(0, [], [new(teacher.Id, true, jn.Id)]), Guid.NewGuid(), default);
        }
        const string endpoint = "/api/admin/usage-dashboard?posters=true&start=2026-11-14&end=2026-11-15";
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync(endpoint)).StatusCode);
        var result = await admin.GetFromJsonAsync<JsonElement>(endpoint);
        var branches = result.GetProperty("branches").EnumerateArray().ToArray();
        Assert.Equal(new[] { "广州", "济南", "苏州", "佛山", "南京" }, branches.Select(x => x.GetProperty("name").GetString()));
        Assert.Equal(2, branches[0].GetProperty("activeUsers").GetInt32());
        Assert.Equal(12.5, branches[0].GetProperty("copiesPerUser").GetDouble());
        Assert.Equal(25, branches[0].GetProperty("copies").GetInt32());
        Assert.Equal("2026-11-14", result.GetProperty("heatmapStart").GetString());
        Assert.Equal(new[] { 5, 20 }, branches[0].GetProperty("daily").EnumerateArray().Select(x => x.GetInt32()));
        Assert.All(branches.Skip(1), x =>
        {
            Assert.Equal(0, x.GetProperty("copies").GetInt32());
            Assert.Equal(0, x.GetProperty("activeUsers").GetInt32());
            Assert.Equal(JsonValueKind.Null, x.GetProperty("copiesPerUser").ValueKind);
        });
        var rank = Assert.Single(result.GetProperty("teachers").EnumerateArray());
        Assert.Equal("李老师", rank.GetProperty("name").GetString());
        Assert.Equal(3, rank.GetProperty("studentLessons").GetInt32());
        Assert.Equal("济南", rank.GetProperty("city").GetString());
        Assert.Equal("organization", rank.GetProperty("affiliationSource").GetString());
        var empty = await admin.GetFromJsonAsync<JsonElement>(
            "/api/admin/usage-dashboard?posters=true&start=2026-11-11&end=2026-11-11");
        Assert.Equal(JsonValueKind.Null, empty.GetProperty("heatmapStart").ValueKind);
        Assert.Equal(HttpStatusCode.BadRequest, (await admin.GetAsync(
            "/api/admin/usage-dashboard?posters=true&start=2026-09-01&end=2026-11-15")).StatusCode);
    }

    [Fact]
    public void IndependentJobsAddCopiesAndTheSameStudentLessonIsCountedOnce()
    {
        var staff = Guid.NewGuid();
        var student = Guid.NewGuid();
        var day = "2026-11-15";
        var lecture = Guid.NewGuid();
        var first = new UsagePosters.Submission(Guid.NewGuid(), staff, "赵老师", true, student, "广州", day, 2, [lecture]);
        var reprint = first with { Job = Guid.NewGuid(), Copies = 1 };
        var otherLecture = first with { Job = Guid.NewGuid(), Copies = 3, Lectures = [Guid.NewGuid()] };
        var otherCampus = first with { Job = Guid.NewGuid(), Branch = "济南", Copies = 6 };
        var otherStudent = first with { Job = Guid.NewGuid(), Student = Guid.NewGuid(), Copies = 1 };
        var numbered = first with { Job = Guid.NewGuid(), Staff = Guid.NewGuid(), Name = "教师01", Copies = 4 };
        var outOfScope = first with { Job = Guid.NewGuid(), Branch = "东莞", Copies = 100 };
        var report = UsagePosters.Build([day], [first, first, reprint, otherLecture, otherCampus, otherStudent, numbered, outOfScope],
            new Dictionary<Guid, UsagePosters.Affiliation> { [staff] = new("济南", "organization") });
        var ranked = Assert.Single(report.Teachers);
        Assert.Equal(3, ranked.StudentLessons);
        Assert.Equal(staff, ranked.Id);
        Assert.Equal("济南", ranked.City);
        Assert.Equal("organization", ranked.AffiliationSource);
        Assert.Equal(11, report.Branches[0].Copies);
        Assert.Equal(6, report.Branches[1].Copies);
        Assert.Equal(new[] { 2, 1 }, report.Branches.Take(2).Select(x => x.ActiveUsers));
        Assert.Equal(5.5, report.Branches[0].CopiesPerUser);
        Assert.All(report.Branches.Skip(2), x => Assert.Equal(0, Assert.Single(x.Daily)));
        Assert.Equal("济南", UsagePosters.Branch(new(Guid.Empty, "唐冶", "城市未登记", [], [])));
        var withoutAffiliation = Assert.Single(UsagePosters.Build([day], [first]).Teachers);
        Assert.Equal("归属待核对", withoutAffiliation.City);
        Assert.Equal("unassigned", withoutAffiliation.AffiliationSource);
    }

    [Fact]
    public async Task AffiliationUsesConfiguredCityOrUnambiguousCurrentTeachingLinks()
    {
        await using var factory = new Factory();
        using var admin = await factory.Login("affiliation-admin", bootstrap: true);
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var jn = new Campus { City = "济南市", Name = "洪楼" };
        var jnAlias = new Campus { Name = "领秀城" };
        var gz = new Campus { City = "广州", Name = "万博" };
        var unknown = new Campus { Name = "新地点" };
        var course = new Course { Name = "数学" };
        var sourceClass = new SchoolClass { Name = "初一数学", CampusId = jn.Id };
        var student = new Student { ClassId = sourceClass.Id, Name = "学员甲", Code = "001" };
        var lecture = new Lecture { ClassId = sourceClass.Id, CourseId = course.Id, Sequence = 1 };
        var device = new Device { CampusId = gz.Id, Name = "办公室", TokenHash = Secrets.New() };
        var printer = new Printer { CampusId = gz.Id, DeviceId = device.Id, Name = "办公室打印机" };
        db.AddRange(jn, jnAlias, gz, unknown, course, sourceClass, student, lecture, device, printer);
        var at = new DateTimeOffset(2026, 11, 15, 0, 0, 0, TimeSpan.FromHours(8));
        Staff Teacher(string name, string state)
        {
            var teacher = new Staff { Name = name, Role = "teacher" };
            var doc = new ExportDocument { ClassId = sourceClass.Id, StudentId = student.Id, CourseId = course.Id,
                StaffId = teacher.Id, State = "ready", LectureId = lecture.Id };
            db.AddRange(teacher, doc, new PrintJob { DocumentId = doc.Id, PrinterId = printer.Id, DeviceId = device.Id,
                StaffId = teacher.Id, State = state, Copies = 1, RequestKey = name, CreatedAt = at });
            return teacher;
        }
        void Link(Staff teacher, Campus campus, bool owner = false, bool archived = false, bool test = false)
        {
            var classroom = new SchoolClass { Name = test ? "设备诊断测试班" : "初二数学", CampusId = campus.Id,
                OwnerStaffId = owner ? teacher.Id : null, Archived = archived };
            db.Add(classroom);
            if (!owner) db.Add(new Assignment { StaffId = teacher.Id, ClassId = classroom.Id, CourseId = course.Id });
        }
        var assigned = Teacher("张老师", "queued");
        Link(assigned, jn); Link(assigned, jnAlias);
        var owned = Teacher("李老师", "failed"); Link(owned, jnAlias, owner: true);
        var crossCity = Teacher("王老师", "uncertain"); Link(crossCity, jn); Link(crossCity, gz);
        var missing = Teacher("赵老师", "claimed");
        var unknownCity = Teacher("孙老师", "preparing"); Link(unknownCity, unknown);
        var partlyUnknown = Teacher("周老师", "submitting"); Link(partlyUnknown, jn); Link(partlyUnknown, unknown);
        var filtered = Teacher("吴老师", "submitted");
        Link(filtered, jn); Link(filtered, gz, archived: true); Link(filtered, gz, test: true);
        var configured = Teacher("郑老师", "queued"); Link(configured, jn);
        await db.SaveChangesAsync();
        var organization = new UsageOrganization(0, [new(gz.Id, "苏州市", "园区")], [new(configured.Id, true, gz.Id)]);
        var report = await UsagePosters.Read(db, at, at.AddDays(1), ["2026-11-15"], default, organization);
        var ranked = report.Teachers.ToDictionary(x => x.Id);
        Assert.Equal(8, ranked.Count);
        foreach (var teacher in new[] { assigned, owned, filtered })
        {
            Assert.Equal("济南", ranked[teacher.Id].City);
            Assert.Equal("teaching", ranked[teacher.Id].AffiliationSource);
        }
        foreach (var teacher in new[] { crossCity, missing, unknownCity, partlyUnknown })
        {
            Assert.Equal("归属待核对", ranked[teacher.Id].City);
            Assert.Equal("unassigned", ranked[teacher.Id].AffiliationSource);
        }
        Assert.Equal("苏州", ranked[configured.Id].City);
        Assert.Equal("organization", ranked[configured.Id].AffiliationSource);
        Assert.All(ranked.Values, teacher => Assert.Equal(1, teacher.StudentLessons));
        var branch = Assert.Single(report.Branches.Where(x => x.Copies > 0));
        Assert.Equal(8, branch.ActiveUsers);
        Assert.Equal(8, branch.Copies);
        Assert.Equal(8, Assert.Single(branch.Daily));
        Assert.Equal("2026-11-15", report.HeatmapStart);
    }

}
