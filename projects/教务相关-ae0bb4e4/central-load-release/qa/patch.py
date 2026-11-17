from pathlib import Path
root=Path('/Users/king/WeChatProjects/miniprogram-1/.local/acceptance/central-resolve-20261105/src')
f=root/'server/CentralResearchWorkbench.cs'
s=f.read_text()
a=s.index('    public static async Task<ResearchWorkbenchSnapshot>')
s=s[:a]+'''    sealed record OccurrenceStatus(Guid Id, string CanonicalId, bool Replaceable);
    sealed record EditionStatus(Guid Id, Guid ActorStaffId, string ActorName, OccurrenceStatus[] Occurrences);
    struct QuestionCounts
    {
        public int Total, Blocking, Unconfirmed, Problems;
    }

    static readonly SemaphoreSlim SnapshotGate = new(1);
    static async Task<ResearchWorkbenchSnapshot> Snapshot(Data db, LibraryEditDb library, IObjects objects, CancellationToken ct)
    {
        await SnapshotGate.WaitAsync(ct);
        try { return await BuildSnapshot(db, library, objects, ct); }
        finally { SnapshotGate.Release(); }
    }

'''+s[a:].replace('public static async Task<ResearchWorkbenchSnapshot> Snapshot','static async Task<ResearchWorkbenchSnapshot> BuildSnapshot',1)
a=s.index('        var questions='); b=s.index('        var owned=',a)
s=s[:a]+'''        var counts = new Dictionary<Guid, QuestionCounts>();
        var questions = db.Questions.AsNoTracking().Where(q => handoutMap.Keys.Contains(q.HandoutId));
        await foreach (var q in questions.AsAsyncEnumerable().WithCancellation(ct))
        {
            var group = counts.GetValueOrDefault(q.HandoutId);
            var broken = Broken(q);
            var unconfirmed = Unreviewed(q);
            group.Total++;
            if (broken) group.Blocking++;
            if (unconfirmed) group.Unconfirmed++;
            if (broken || unconfirmed) group.Problems++;
            counts[q.HandoutId] = group;
        }
        var submissions=await db.ReviewSubmissions.AsNoTracking().Select(s=>new {s.Kind,s.TargetId,s.State,s.UploaderId}).ToArrayAsync(ct);var submit=submissions.ToDictionary(s=>(s.Kind,s.TargetId));
        var uploads=await db.Audits.AsNoTracking().Where(a=>a.Action=="handout.upload").Select(a=>new {a.TargetId,a.StaffId}).ToArrayAsync(ct);
'''+s[b:]
s=s.replace('var all=children.SelectMany(h=>qs[h.Id]).ToArray();var blocking=all.Count(q=>broken.Contains(q.Id));var unconfirmed=all.Count(q=>uncheckedQuestions.Contains(q.Id));','var groups=children.Select(h=>counts.GetValueOrDefault(h.Id)).ToArray();var blocking=groups.Sum(g=>g.Blocking);var unconfirmed=groups.Sum(g=>g.Unconfirmed);').replace('!qs[h.Id].Any()','!counts.ContainsKey(h.Id)').replace('all.Count(q=>broken.Contains(q.Id)||uncheckedQuestions.Contains(q.Id))','groups.Sum(g=>g.Problems)').replace('all.Length,blocking','groups.Sum(g=>g.Total),blocking')
a=s.index('        var editions=');b=s.index('        foreach(var entry',a)
s=s[:a]+'''        var editions = new Dictionary<Guid, EditionStatus>();
        var currentEditions = library.Editions.AsNoTracking().Where(e => editionIds.Contains(e.Id))
            .Select(e => new { e.Id, e.ActorStaffId, e.ActorName, e.OccurrencesJson });
        await foreach (var edition in currentEditions.AsAsyncEnumerable().WithCancellation(ct))
        {
            var occurrences = Read<OccurrenceStatus>(edition.OccurrencesJson);
            editions.Add(edition.Id, new(edition.Id, edition.ActorStaffId, edition.ActorName, occurrences));
        }
        var canonicalIds = editions.Values.SelectMany(e => e.Occurrences).Select(o => o.CanonicalId).Distinct().ToArray();
        var canonicals = await library.Canonicals.AsNoTracking().Where(q => canonicalIds.Contains(q.Id))
            .Select(q => new { q.Id, q.ReviewState, HasContent = q.Text != "" || q.PayloadKey != "" })
            .ToDictionaryAsync(q => q.Id, ct);
'''+s[b:]
s=s.replace('edition==null?[]:Read<LibraryOccurrence>(edition.OccurrencesJson)','edition?.Occurrences ?? []').replace('(q.Text.Length>0||q.PayloadKey.Length>0)','q.HasContent')
a=s.index('int Count(string category)');b=s.index('\n        });',a)
dashboard=s[a:b];s=s[:a]+'return Dashboard(s);'+s[b:]
a=s.index('    public static void MapCentralResearch')
s=s[:a]+'''    static object Dashboard(ResearchWorkbenchSnapshot s)
    {
        '''+dashboard.strip().replace('            return','        return')+'''
    }

'''+s[a:]
s=s.replace('total=rows.Length,page=index,pageSize=size','total=rows.Length,page=index,pageSize=size,dashboard=Dashboard(s)').replace('return new {courses=(await Snapshot(db,library,objects,ct)).Courses};','var snapshot=await Snapshot(db,library,objects,ct);return new {courses=snapshot.Courses,dashboard=Dashboard(snapshot)};')
f.write_text(s)
f=root/'tests/CentralResearchWorkbenchTests.cs';s=f.read_text();needle='var course=Assert.Single(courses.GetProperty("courses").EnumerateArray());'
s=s.replace(needle,'Assert.Equal(dashboard.GetRawText(), rows.GetProperty("dashboard").GetRawText());Assert.Equal(dashboard.GetRawText(), courses.GetProperty("dashboard").GetRawText());'+needle,1);f.write_text(s)
p=Path('web/central-research.mjs');s=p.read_text().replace("const [dashboard,first]=await Promise.all([api('/central-research/dashboard'),loadList(page)]);","const first=await loadList(page);\n const dashboard=first.dashboard ?? await api('/central-research/dashboard');");p.write_text(s)
for name,old,new in [('app.js','central-research.mjs?v=20261107-classic','central-research.mjs?v=20261107-central-load'),('index.html','app.js?v=20261107-label-confirm','app.js?v=20261107-central-load')]:
 p=Path('web')/name;s=p.read_text();assert old in s;p.write_text(s.replace(old,new))
