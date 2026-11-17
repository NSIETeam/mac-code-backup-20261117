using System.Text.Json;

namespace Siyue;

// Separate durable workspace: no lectures, classes, assignments or publication records are created.
// Atomic replace + OS file locks also protect two API/worker processes sharing this directory.
public sealed class ResearchStore(IConfiguration config, IWebHostEnvironment env)
{
    public string Root => Path.GetFullPath(config["Research:Directory"] ?? Path.Combine(env.ContentRootPath, ".local", "research"));
    public string OwnerDir(Guid owner) => Path.Combine(Root, owner.ToString("N"));
    public string Dir(Guid owner, Guid id) => Path.Combine(OwnerDir(owner), id.ToString("N"));
    public static async Task<FileStream> Lock(string directory, string name, CancellationToken ct)
    {
        Directory.CreateDirectory(directory);
        for (int i = 0; ; i++) {
            ct.ThrowIfCancellationRequested();
            try { return new FileStream(Path.Combine(directory, name), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
            catch (IOException) when (i < 100) { await Task.Delay(50, ct); }
        }
    }
    public static async Task Write<T>(string path, T value, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value, Json.Options);
        Guard.That(bytes.Length <= 40 * 1024 * 1024, "讲义编辑记录过大，请分成多份讲义");
        var temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try { await File.WriteAllBytesAsync(temp, bytes, ct); File.Move(temp, path, true); }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
    public async Task<ResearchDraft> Load(Guid owner, Guid id, CancellationToken ct = default)
    {
        var path = Path.Combine(Dir(owner, id), "draft.json");
        Guard.That(File.Exists(path), "讲义不存在或无权访问", 404);
        var d = Json.Read<ResearchDraft>(await File.ReadAllTextAsync(path, ct));
        Guard.That(d.Owner == owner && d.Id == id, "讲义不存在或无权访问", 404); return d;
    }
    public async Task<ResearchDraft[]> List(Guid owner, CancellationToken ct)
    {
        var root = OwnerDir(owner); if (!Directory.Exists(root)) return [];
        var items = new List<ResearchDraft>();
        foreach (var dir in Directory.EnumerateDirectories(root)) {
            if (!Guid.TryParseExact(Path.GetFileName(dir), "N", out var id) || !File.Exists(Path.Combine(dir, "draft.json"))) continue;
            items.Add(await Load(owner, id, ct));
        }
        return items.OrderByDescending(x => x.UpdatedAt).ToArray();
    }
    public async Task<ResearchDraft> Create(Guid owner, ResearchCreate r, IFormFile? file, CancellationToken ct)
    {
        Guard.That(LocalQuestionBank.Subjects.Contains(r.Subject) && LocalQuestionBank.Stages.Contains(r.Stage) && r.Grade.Length <= 40 && r.Region.Length <= 40, "请填写学科、学段、年级和地区");
        var template = (await Templates(owner, ct)).SingleOrDefault(t => t.Id == r.TemplateId) ?? throw new ApiException(400, "模板不存在");
        var d = new ResearchDraft { Owner = owner, Title = Guard.Name(r.Title), Subject = r.Subject, Stage = r.Stage, Grade = r.Grade, Region = r.Region,
            Template = template, State = file == null ? "ready" : "queued", FileName = file == null ? "" : Path.GetFileName(file.FileName), Message = file == null ? "" : "等待拆题" };
        if (file != null) Guard.That(file.Length is > 0 and <= 52_428_800 && Path.GetExtension(file.FileName).ToLowerInvariant() is ".pdf" or ".docx", "请上传不超过50MB的 PDF 或 DOCX 讲义");
        var dir = Dir(owner, d.Id); Directory.CreateDirectory(dir);
        try {
            if (file != null) { await using var output = File.Create(Path.Combine(dir, "original" + Path.GetExtension(file.FileName).ToLowerInvariant())); await file.CopyToAsync(output, ct); }
            await Write(Path.Combine(dir, "draft.json"), d, ct); return d;
        } catch { Directory.Delete(dir, true); throw; }
    }
    public async Task<ResearchDraft> Change(Guid owner, Guid id, int? revision, Func<ResearchDraft, ResearchDraft> change, CancellationToken ct)
    {
        // Do not create an unknown workspace merely to acquire its lock.
        await Load(owner, id, ct);
        await using var gate = await Lock(Dir(owner, id), ".edit.lock", ct);
        var d = await Load(owner, id, ct);
        Guard.That(revision == null || revision == d.Revision, "讲义已在其他窗口更新，请刷新后重试", 409);
        d = change(d) with { Revision = d.Revision + 1, UpdatedAt = Clock.Now };
        await Write(Path.Combine(Dir(owner, id), "draft.json"), d, ct); return d;
    }
    public async Task<ResearchTemplate[]> Templates(Guid owner, CancellationToken ct)
    {
        var path = Path.Combine(OwnerDir(owner), "templates.json");
        return ResearchTemplates.BuiltIn.Concat(File.Exists(path) ? Json.Read<ResearchTemplate[]>(await File.ReadAllTextAsync(path, ct)) : []).ToArray();
    }
    public async Task<ResearchTemplate> SaveTemplate(Guid owner, ResearchTemplate input, CancellationToken ct)
    {
        ResearchTemplates.Validate(input);
        await using var gate = await Lock(OwnerDir(owner), ".templates.lock", ct);
        var all = (await Templates(owner, ct)).Where(x => !ResearchTemplates.BuiltIn.Any(b => b.Id == x.Id)).ToList();
        // Saving is always a new version; existing drafts retain their template snapshot.
        var t = input with { Id = Guid.NewGuid().ToString("N") };
        Guard.That(all.Count < 200, "最多保存200份模板"); all.Add(t);
        await Write(Path.Combine(OwnerDir(owner), "templates.json"), all, ct); return t;
    }
    public static void ValidateItem(ResearchItem item)
    {
        Guard.That(item.Stem.Length <= 32000 && item.Answer.Length <= 16000 && item.Analysis.Length <= 24000 &&
            item.Label.Length <= 120 && item.Section.Length is > 0 and <= 40 && item.Type.Length <= 40 && item.Difficulty.Length <= 40 &&
            item.Knowledge is {Length: <= 20} && item.Knowledge.All(x => !string.IsNullOrWhiteSpace(x) && x.Length <= 100) &&
            item.AnswerLines is >= -1 and <= 20, "题目内容或标签过长");
    }
    public static ResearchDraft Edit(ResearchDraft d, ResearchEdit r, ResearchItem? candidate = null)
    {
        Guard.That(d.State == "ready", "请等待拆题完成后编辑", 409);
        var list = d.Items.ToList(); var index = list.FindIndex(x => x.Id == r.ItemId);
        ResearchItem Current() => index >= 0 ? list[index] : throw new ApiException(404, "题目不存在");
        if (r.Action == "settings") {
            var t = r.Template ?? d.Template; ResearchTemplates.Validate(t);
            Guard.That(r.Region == null || r.Region.Length <= 40, "地区名称过长");
            var notes = r.Notes ?? d.Notes;
            Guard.That(notes.Count <= 20 && notes.All(x => t.Sections.Contains(x.Key) && x.Value.Length <= 12000), "栏目正文无效");
            return d with { Title = r.Title == null ? d.Title : Guard.Name(r.Title), Region = r.Region ?? d.Region, Template = t, Notes = notes };
        }
        if (r.Action == "undo") {
            Guard.That(d.History.Length > 0, "没有可以撤销的编辑", 409);
            return d with { Items = d.History[^1].Items, History = d.History[..^1] };
        }
        switch (r.Action) {
            case "add": {
                var input = r.Item ?? throw new ApiException(400, "请输入题目"); ValidateItem(input);
                Guard.That(!string.IsNullOrWhiteSpace(input.Stem), "请输入题干");
                list.Add(new ResearchItem { Stem = input.Stem, Answer = input.Answer, Analysis = input.Analysis, Section = input.Section, Type = input.Type, Difficulty = input.Difficulty, Knowledge = input.Knowledge, Source = "教研自编", Reviewed = false }); break;
            }
            case "edit": {
                var old = Current(); var input = r.Item ?? throw new ApiException(400, "请输入题目"); ValidateItem(input);
                Guard.That(old.Blocks.Length == 0 || input.Stem == old.Stem, "原稿题干按原版面保留，请框选修正或替换题目");
                list[index] = old with { Stem = input.Stem, Answer = input.Answer, Analysis = input.Analysis, Section = input.Section, Type = input.Type, Difficulty = input.Difficulty, Knowledge = input.Knowledge, KnowledgeMethod = "人工确认", AnswerLines = input.AnswerLines, Reviewed = false }; break;
            }
            case "append-candidate": {
                var old = Current(); Guard.That(candidate != null, "候选题已失效，请重新搜索", 409);
                Guard.That(!list.Any(q => q.Source == "学科网" && q.SourceId == candidate!.SourceId), "这道题已经加入讲义", 409);
                list.Insert(index + 1, candidate! with { Id = Guid.NewGuid(), Label = "同类训练", Section = old.Section, Reviewed = false }); break;
            }
            case "replace": {
                var old = Current(); Guard.That(candidate != null, "候选题已失效，请重新搜索", 409);
                list[index] = candidate! with { Id = old.Id, Label = old.Label, Section = old.Section, AnswerLines = old.AnswerLines, Reviewed = false }; break;
            }
            case "review": { var old = Current(); list[index] = old with { Reviewed = true }; break; }
            case "remove": Current(); list.RemoveAt(index); break;
            case "order":
                Guard.That(r.Order != null && r.Order.Length == list.Count && r.Order.Distinct().Count() == list.Count && r.Order.All(id => list.Any(x => x.Id == id)), "题目顺序已变化，请刷新");
                list = r.Order!.Select(id => list.Single(x => x.Id == id)).ToList(); break;
            case "merge": {
                var old = Current(); var other = list.FindIndex(x => x.Id == r.OtherItemId);
                Guard.That(other >= 0 && Math.Abs(other - index) == 1 && old.Blocks.Length > 0 && list[other].Blocks.Length > 0, "请选择相邻的两道原稿题");
                var first = Math.Min(index, other); var second = Math.Max(index, other);
                list[first] = list[first] with { Blocks = [..list[first].Blocks, ..list[second].Blocks], Stem = list[first].Stem + "\n" + list[second].Stem, Issues = [..list[first].Issues, ..list[second].Issues], Reviewed = false };
                list.RemoveAt(second); break;
            }
            case "split": {
                var old = Current(); Guard.That(r.BlockIndex >= 0 && r.BlockIndex < old.Blocks.Length && r.Regions != null, "请选择原稿题目区域");
                var block = old.Blocks[r.BlockIndex]; var bounds = PdfRegions.Bounds(System.Xml.Linq.XElement.Parse(block.Xml));
                PdfRegions.Validate(new(0, old.Id, r.BlockIndex, r.Regions!), bounds);
                var split = r.Regions!.Select(region => new ResearchItem { Label = region.Label, Section = old.Section, Blocks = [PdfRegions.Crop(block, region)], Stem = "", Issues = [new("manual", "人工框选，请核对题目完整性并补充知识点", false)] }).ToList();
                if (r.BlockIndex > 0) split.Insert(0, old with { Id = Guid.NewGuid(), Blocks = old.Blocks[..r.BlockIndex], Reviewed = false });
                if (r.BlockIndex + 1 < old.Blocks.Length) split[^1] = split[^1] with { Blocks = [..split[^1].Blocks, ..old.Blocks[(r.BlockIndex+1)..]] };
                list.RemoveAt(index); list.InsertRange(index, split); break;
            }
            default: throw new ApiException(400, "编辑操作无效");
        }
        Guard.That(list.Count <= 1000, "单份讲义最多1000题");
        return d with { Items = list.ToArray(), History = d.History.TakeLast(19).Append(new(d.Revision, r.Action, Clock.Now, d.Items)).ToArray() };
    }
}
