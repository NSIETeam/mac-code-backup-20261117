using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Siyue;

public static partial class UsagePosters
{
    static readonly string[] BranchNames = ["济南", "广州", "苏州", "佛山", "南京"];
    public sealed record Campus(string Name, int ActiveUsers, long Copies, double? CopiesPerUser, long[] Daily);
    public sealed record Affiliation(string City, string Source);
    public sealed record Teacher(Guid Id, string Name, int StudentLessons, string City, string AffiliationSource);
    public sealed record Report(Campus[] Branches, Teacher[] Teachers, int EligibleTeachers, string? HeatmapStart);
    public sealed record Submission(Guid Job, Guid Staff, string Name, bool IsTeacher, Guid Student,
        string Branch, string Day, int Copies, Guid[] Lectures);

    [GeneratedRegex(@"测试|内测|验收|演练|模拟|test|demo", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex TestName();

    [GeneratedRegex(@"^教师\s*\d+$", RegexOptions.CultureInvariant)]
    private static partial Regex NumberedTeacher();

    public static string Branch(UsageDashboard.Place place) =>
        place.City == "城市未登记" && place.Name is "领秀城" or "唐冶" ? "济南" : place.City;

    public static async Task<Report> Read(Data db, DateTimeOffset since, DateTimeOffset until, string[] days, CancellationToken ct, UsageOrganization? organization = null)
    {
        var places = UsageDashboard.Places(await db.Campuses.IgnoreQueryFilters().AsNoTracking().ToArrayAsync(ct));
        var branches = places.SelectMany(place => place.SourceIds.Select(id => (Id: id, Branch: Branch(place))))
            .Where(x => BranchNames.Contains(x.Branch))
            .ToDictionary(x => x.Id, x => x.Branch);
        var campusIds = branches.Keys.ToArray();

        var rows = await (from job in db.PrintJobs.AsNoTracking()
                          where job.CreatedAt >= since && job.CreatedAt < until && job.Copies > 0
                          join printer in db.Printers.AsNoTracking() on job.PrinterId equals printer.Id
                          where campusIds.Contains(printer.CampusId)
                          join doc in db.Documents.IgnoreQueryFilters().AsNoTracking() on job.DocumentId equals doc.Id
                          where doc.Kind == "booklet" && doc.StudentId.HasValue
                          join staff in db.Staff.IgnoreQueryFilters().AsNoTracking() on job.StaffId equals staff.Id
                          join session in db.ClassSessions.AsNoTracking() on doc.ClassSessionId equals (Guid?)session.Id into sessionRows
                          from session in sessionRows.DefaultIfEmpty()
                          join student in db.Students.AsNoTracking() on doc.StudentId equals (Guid?)student.Id into studentRows
                          from student in studentRows.DefaultIfEmpty()
                          join classroom in db.Classes.IgnoreQueryFilters().AsNoTracking()
                              on ((Guid?)session.ClassId ?? doc.ClassId ?? (Guid?)student.ClassId) equals (Guid?)classroom.Id
                          select new
                          {
                              JobId = job.Id, StaffId = staff.Id, staff.Name, staff.Role, job.CreatedAt, job.Copies,
                              printer.CampusId, DocumentId = doc.Id, StudentId = doc.StudentId!.Value,
                              doc.SnapshotJson, doc.LectureId, SessionHandout = (Guid?)session.HandoutId, ClassName = classroom.Name,
                          }).ToArrayAsync(ct);

        var valid = rows.Where(row => UsageDashboard.IsTeachingClass(row.ClassName) && !TestName().IsMatch(row.Name)).ToArray();
        var handouts = valid.DistinctBy(row => row.DocumentId).ToDictionary(row => row.DocumentId, row =>
            SnapshotHandouts(row.SnapshotJson)
                .Concat(row.SessionHandout.HasValue ? [row.SessionHandout.Value] : Array.Empty<Guid>()).Distinct().ToArray());
        var handoutIds = handouts.Values.SelectMany(x => x).Distinct().ToArray();
        var lectureByHandout = await db.Handouts.AsNoTracking().Where(x => handoutIds.Contains(x.Id))
            .Select(x => new { x.Id, x.LectureId }).ToDictionaryAsync(x => x.Id, x => x.LectureId, ct);
        var submissions = valid.Select(row => new Submission(row.JobId, row.StaffId, row.Name, row.Role == "teacher",
            row.StudentId, branches[row.CampusId], Day(row.CreatedAt), row.Copies,
            handouts[row.DocumentId].Where(lectureByHandout.ContainsKey).Select(id => lectureByHandout[id])
                .Concat(row.LectureId.HasValue ? [row.LectureId.Value] : Array.Empty<Guid>()).Distinct().ToArray()));
        var affiliations = await ReadAffiliations(db, places, valid.Select(row => row.StaffId).Distinct().ToArray(), organization, ct);
        return Build(days, submissions, affiliations);
    }

    static async Task<Dictionary<Guid, Affiliation>> ReadAffiliations(Data db, UsageDashboard.Place[] places,
        Guid[] staffIds, UsageOrganization? organization, CancellationToken ct)
    {
        var cities = places.SelectMany(place => place.SourceIds.Select(id => (Id: id, City: Branch(place))))
            .ToDictionary(x => x.Id, x => x.City);
        foreach (var campus in organization?.Campuses ?? [])
            if (cities.ContainsKey(campus.Id) && !string.IsNullOrWhiteSpace(campus.City))
                cities[campus.Id] = campus.City.Trim().TrimEnd('市');
        var members = (organization?.Teachers ?? []).ToDictionary(x => x.Id);
        var classes = await db.Classes.AsNoTracking().Select(x => new { x.Id, x.Name, x.CampusId, x.OwnerStaffId }).ToArrayAsync(ct);
        var teaching = classes.Where(x => UsageDashboard.IsTeachingClass(x.Name)).ToDictionary(x => x.Id);
        var assignments = await db.Assignments.AsNoTracking().Where(x => staffIds.Contains(x.StaffId))
            .Select(x => new { x.StaffId, x.ClassId }).ToArrayAsync(ct);
        var links = assignments.Where(x => teaching.ContainsKey(x.ClassId))
            .Select(x => (x.StaffId, CampusId: teaching[x.ClassId].CampusId))
            .Concat(teaching.Values.Where(x => x.OwnerStaffId.HasValue && staffIds.Contains(x.OwnerStaffId.Value))
                .Select(x => (StaffId: x.OwnerStaffId!.Value, x.CampusId))).ToLookup(x => x.StaffId, x => x.CampusId);
        bool Known(string? city) => !string.IsNullOrWhiteSpace(city) && city != "城市未登记";
        return staffIds.ToDictionary(id => id, id =>
        {
            // A person's home is independent of the printer used. Never infer it from a print job.
            if (members.GetValueOrDefault(id)?.CampusId is Guid home)
            {
                var city = cities.GetValueOrDefault(home);
                return Known(city) ? new Affiliation(city!, "organization") : new Affiliation("归属待核对", "unassigned");
            }
            // Legacy accounts have no home field. Infer only an unambiguous teaching city without writing it back.
            var associated = links[id].Select(campus => cities.GetValueOrDefault(campus)).Distinct().ToArray();
            return associated.Length == 1 && Known(associated[0])
                ? new Affiliation(associated[0]!, "teaching") : new Affiliation("归属待核对", "unassigned");
        });
    }

    public static string Day(DateTimeOffset at) => at.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd");

    static Guid[] SnapshotHandouts(string snapshot)
    {
        try
        {
            using var json = JsonDocument.Parse(snapshot);
            if (json.RootElement.ValueKind != JsonValueKind.Array) return [];
            return json.RootElement.EnumerateArray()
                .Where(x => x.ValueKind == JsonValueKind.Object)
                .Select(x => (x.TryGetProperty("handoutId", out var value) || x.TryGetProperty("HandoutId", out value))
                    && value.ValueKind == JsonValueKind.String && value.TryGetGuid(out var id) ? id : Guid.Empty)
                .Where(id => id != Guid.Empty).Distinct().ToArray();
        }
        catch (JsonException) { return []; }
    }

    public static Report Build(string[] days, IEnumerable<Submission> submissions, IReadOnlyDictionary<Guid, Affiliation>? affiliations = null)
    {
        var selectedDays = days.ToHashSet();
        var records = submissions.Where(x => BranchNames.Contains(x.Branch) && selectedDays.Contains(x.Day) && x.Copies > 0).DistinctBy(x => x.Job).ToArray();
        bool NamedTeacher(Submission row) => row.IsTeacher && !string.IsNullOrWhiteSpace(row.Name)
            && !NumberedTeacher().IsMatch(row.Name.Trim());

        long[] DailyCopies(IEnumerable<Submission> source)
        {
            var copies = source.GroupBy(x => x.Day).ToDictionary(g => g.Key, g => g.Sum(x => (long)x.Copies));
            return days.Select(day => copies.GetValueOrDefault(day)).ToArray();
        }

        var recordsByBranch = records.ToLookup(x => x.Branch);
        var branches = BranchNames.Select(branch =>
        {
            var branchRecords = recordsByBranch[branch];
            var active = branchRecords.Select(x => x.Staff).Distinct().Count();
            var copies = branchRecords.Sum(x => (long)x.Copies);
            return new Campus(branch, active, copies, active == 0 ? null : (double)copies / active,
                DailyCopies(branchRecords));
        }).OrderByDescending(x => x.ActiveUsers).ThenByDescending(x => x.Copies).ToArray();

        var ranked = records.Where(NamedTeacher).GroupBy(x => x.Staff).Select(group =>
        {
            var pairs = group.SelectMany(row => row.Lectures.Select(lecture => (row.Student, Lecture: lecture))).Distinct().ToArray();
            var affiliation = affiliations?.GetValueOrDefault(group.Key) ?? new Affiliation("归属待核对", "unassigned");
            return (Id: group.Key, Name: group.First().Name, Count: pairs.Length, Students: pairs.Select(x => x.Student).Distinct().Count(),
                Affiliation: affiliation);
        }).Where(x => x.Count > 0)
            .OrderByDescending(x => x.Count).ThenByDescending(x => x.Students)
            .ThenBy(x => x.Name, StringComparer.Ordinal).ThenBy(x => x.Id).ToArray();
        var requestedDays = records.Select(x => x.Day).ToHashSet();
        return new Report(branches, ranked.Take(10).Select(x => new Teacher(x.Id, x.Name, x.Count, x.Affiliation.City, x.Affiliation.Source)).ToArray(),
            ranked.Length, days.FirstOrDefault(requestedDays.Contains));
    }
}
