using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class OutreachDiagnosisTests
{
    [Fact]
    public async Task DiagnosisRequiresStaffAndOnlySuggestsTeachersInVisibleClasses()
    {
        await using var factory = new Factory();
        using var admin = await factory.Login("admin", true);
        using var guest = factory.CreateClient();
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<Data>();
        var campus = new Campus { Name = "测试校区" };
        var high = new SchoolClass { CampusId = campus.Id, Name = "高一测试班" };
        var middle = new SchoolClass { CampusId = campus.Id, Name = "初一测试班" };
        var course = new Course { Name = "数学" };
        var other = new Staff { Name = "另一学段教师" };
        db.AddRange(campus, high, middle, course, other);
        await db.SaveChangesAsync();
        var staff = await Factory.Post(admin, "/api/admin/catalog/staff", new { name = "高中教师" });
        var id = staff.GetProperty("id").GetGuid();
        var invite = await Factory.Post(admin, $"/api/admin/staff/{id}/invite", new { });
        using var teacher = await factory.Login("outreach-teacher");
        await Factory.Post(teacher, "/api/auth/bind", new { code = invite.GetProperty("code").GetString() });
        db.Assignments.AddRange(new Assignment { ClassId = high.Id, CourseId = course.Id, StaffId = id },
            new Assignment { ClassId = middle.Id, CourseId = course.Id, StaffId = other.Id });
        await db.SaveChangesAsync();

        const string url = "/api/outreach/diagnosis";
        var input = new { subject = "数学", stage = "高中", confirmedWrong = new[] { "求等差数列通项公式。", "求函数的导数，并判断其单调性。" } };
        Assert.Equal(HttpStatusCode.Unauthorized, (await guest.PostAsJsonAsync(url, input)).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await teacher.PostAsJsonAsync(url, new { subject = "数学", stage = "高中", confirmedWrong = Array.Empty<string>() })).StatusCode);
        var result = await Factory.Post(teacher, url, input);
        var matched = result.GetProperty("matched").EnumerateArray().ToArray();
        Assert.Equal(2, matched.Length);
        Assert.Contains(matched[1].GetProperty("topics").EnumerateArray(), topic => topic.GetString()!.Contains("导数"));
        Assert.Equal("高中教师", Assert.Single(result.GetProperty("teachers").EnumerateArray()).GetProperty("name").GetString());
    }
}
