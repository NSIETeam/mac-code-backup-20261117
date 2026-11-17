using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Siyue;

public sealed record BankQuestion
{
    public string Id { get; init; } = "";
    public string Subject { get; init; } = "";
    public string Stage { get; init; } = "";
    public string Grade { get; init; } = "未标注";
    public string Type { get; init; } = "";
    public string Stem { get; init; } = "";
    public string Answer { get; init; } = "";
    public string Analysis { get; init; } = "";
    public string Difficulty { get; init; } = "未标注";
    public string[] Tags { get; init; } = [];
    public string[] Issues { get; init; } = [];
    public string Source { get; init; } = "";
    public string SourceUrl { get; init; } = "";
    public string License { get; init; } = "";
    public string SourceKind { get; init; } = "public";
    public string TemplateId { get; init; } = "";
    public string[] KnowledgeIds { get; init; } = [];
    public string MaterialId { get; init; } = "";
    public string MaterialChunk { get; init; } = "";
    public string MaterialText { get; init; } = "";
    public string Attribution { get; init; } = "";
    public string LicenseUrl { get; init; } = "";
    public BankDiagram? Diagram { get; init; }
    public string[] Images { get; init; } = [];
    public string ImageKey { get; init; } = "";
    public bool Reviewed { get; init; }
}
public sealed record BankShape(string Kind, double[] Values, string Text = "", string Color = "#315e52", double Width = 1.6, bool Dash = false, double FontSize = 11, string Align = "left");
public sealed record BankDiagram(int Width, int Height, BankShape[] Shapes);
public sealed record BankKnowledgeNode(string Id, string Name, string ParentId, string Subject, string[] Aliases);
public sealed record BankQuery(string Subject, string Stage, string Topic, string Type = "", int Limit = 5, string[]? ExcludeIds = null, string SourceText = "");
public sealed record BankQueryResult(string Status, string Message, string Subject, string Stage, string Topic, string Type, int Requested, int Returned, double QueryMs, string Method, bool RequiresTeacherReview, QuestionMatch[] Matches);
public sealed record BankAvailability(bool Available, int Count, object[] Coverage, string Method);
public sealed record QuestionMatch(BankQuestion Question, double Score, string[] Reasons);
public interface IQuestionBank
{
    BankAvailability Availability();
    bool ContainsQuestion(BankQuestion question);
    BankQueryResult Search(BankQuery query);
    BankKnowledgeNode[] Knowledge(string subject, string query);
    QuestionMatch[] Recommend(string subject, string stage, string stem, string[] tags, int limit, ISet<string> excluded);
}

// All student recommendations and topic lookup share these immutable, subject-scoped indexes.
public sealed partial class LocalQuestionBank : IQuestionBank
{
    public static readonly string[] Subjects = ["数学", "语文", "英语", "物理", "化学", "生物", "政治", "历史", "地理"];
    public static readonly string[] Stages = ["小学", "初中", "高中"];
    public static readonly string[] Types = ["选择题", "填空题", "解答题", "综合题"];
    const string Method = "同学科学段内按知识点、题型与题干词项检索；原题与自编变式分列，排序分数不是准确率";
    sealed record Part(string File, string Sha256, int Count = 0);
    sealed record SubjectPart(string Subject, string Stage, int Count, Part[] Parts);
    sealed record Catalog(string Schema, int Count, SubjectPart[] Subjects, Part[] Materials, Part Knowledge, IndexedPart[]? Indexes = null);
    sealed record KnowledgeData(BankKnowledgeNode[] Nodes);
    sealed record Material(string Id, string Text);
    sealed record Materials(Material[] Items);
    sealed record Entry(BankQuestion Question, string Key, HashSet<string> Tokens, HashSet<string> Tags, string PlainKey);
    sealed class Scope
    {
        public readonly Entry[] Entries;
        public readonly Dictionary<string, Part> Details;
        public readonly Dictionary<string, int[]> Words, Tags, Knowledge;
        public long Revision;
        public Scope(Entry[] entries, Dictionary<string,Part>? details = null, Dictionary<string,int[]>? words = null, Dictionary<string,int[]>? tags = null, Dictionary<string,int[]>? knowledge = null) {
            Entries=entries;Details=details??[];Words=words??Invert(entries,e=>e.Tokens);Tags=tags??Invert(entries,e=>e.Tags);Knowledge=knowledge??Invert(entries,e=>e.Question.KnowledgeIds);
        }
        static Dictionary<string, int[]> Invert(Entry[] entries, Func<Entry, IEnumerable<string>> keys) => entries.SelectMany((e, i) => keys(e).Distinct().Select(k => (k, i))).GroupBy(x => x.k).ToDictionary(g => g.Key, g => g.Select(x => x.i).ToArray(), StringComparer.Ordinal);
    }
    readonly bool prebuiltIndexes;
    readonly CommunityQuestionStore? community;
    readonly ConcurrentDictionary<(string,string),(long Revision,Scope Scope)> combinedScopes = new();
    readonly string directory;
    readonly ILogger<LocalQuestionBank> log;
    readonly Catalog? catalog;
    readonly BankQuestion[] legacy = [];
    readonly BankKnowledgeNode[] nodes = [];
    readonly Dictionary<string, Dictionary<string, string[]>> rules = [];
    readonly ConcurrentDictionary<(string, string), Lazy<Scope>> scopes = new();
    readonly ConcurrentDictionary<string, Lazy<Dictionary<string, string>>> materials = new();
    static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);
    static readonly HashSet<string> Stop = new("下列 正确 错误 选项 已知 其中 则有 关于 根据 一个 一种 可以 如果 下述 分别 有关 说法 的是 不正 题目 求值 求解 下列选项 请选择 所求 数值 单位 以下 计算 写出 过程".Split(' '));

    public LocalQuestionBank(IConfiguration config, IWebHostEnvironment env, ILogger<LocalQuestionBank> logger, CommunityQuestionStore? contributions = null)
    {
        prebuiltIndexes=config.GetValue("QuestionBank:PrebuiltIndexes",true);
        community = contributions;
        log = logger;
        directory = config["QuestionBank:Directory"] ?? Path.Combine(env.ContentRootPath, "QuestionBankData");
        try {
            rules = JsonSerializer.Deserialize<Dictionary<string, Dictionary<string, string[]>>>(File.ReadAllText(Path.Combine(directory, "knowledge-rules.json")), Options) ?? [];
            if (rules.Any(p => !Subjects.Contains(p.Key) || p.Value == null || p.Value.Any(r => string.IsNullOrWhiteSpace(r.Key) || r.Value == null || r.Value.Any(string.IsNullOrWhiteSpace)))) throw new InvalidDataException("Invalid knowledge rules");
            var expanded = Path.Combine(directory, "expanded");
            if (File.Exists(Path.Combine(expanded, "manifest.json"))) {
                directory = expanded;
                catalog = JsonSerializer.Deserialize<Catalog>(File.ReadAllText(Path.Combine(directory, "manifest.json")), Options) ?? throw new InvalidDataException("Missing manifest");
                if (catalog.Schema != "siyue.server-bank/v2" || catalog.Subjects == null || catalog.Materials == null || catalog.Knowledge == null || catalog.Count is < 1 or > 250000
                    || catalog.Subjects.Sum(s => s.Count) != catalog.Count || catalog.Subjects.Any(s => !Subjects.Contains(s.Subject) || !Stages.Contains(s.Stage) || s.Parts == null || s.Parts.Sum(p => p.Count) != s.Count)
                    || catalog.Subjects.Select(s => (s.Subject, s.Stage)).Distinct().Count() != catalog.Subjects.Length) throw new InvalidDataException("Invalid manifest");
                foreach (var part in catalog.Subjects.SelectMany(s => s.Parts).Concat(catalog.Materials).Append(catalog.Knowledge)) CheckPart(part);
                nodes = ReadChecked<KnowledgeData>(catalog.Knowledge).Nodes;
                if (nodes == null || nodes.Any(n => n == null || !Subjects.Contains(n.Subject) || n.Aliases == null || string.IsNullOrWhiteSpace(n.Id) || n.Name == null || n.ParentId == null) || nodes.Select(n => n.Id).Distinct().Count() != nodes.Length) throw new InvalidDataException("Invalid knowledge tree");
            } else {
                legacy = JsonSerializer.Deserialize<BankQuestion[]>(File.ReadAllText(Path.Combine(directory, "questions.json")), Options) ?? [];
                ValidateRows(legacy);
                legacy = Clean(legacy).ToArray();
            }
        } catch (Exception e) when (DataError(e)) {
            catalog = null; legacy = []; nodes = []; rules = [];
            log.LogWarning("Question bank unavailable ({ErrorType}); recommendations are disabled", e.GetType().Name);
        }
    }
    static bool DataError(Exception e) => e is InvalidDataException or IOException or JsonException or UnauthorizedAccessException or ArgumentException or InvalidOperationException or NullReferenceException;
    void CheckPart(Part part)
    {
        if (part == null || !Regex.IsMatch(part.File ?? "", @"\A(?:questions|materials)/[A-Za-z0-9.-]+\.json\z|\Aindexes/[A-Za-z0-9.-]+\.json\.br\z|\Aknowledge\.json\z") || !Regex.IsMatch(part.Sha256 ?? "", @"\A[a-f0-9]{64}\z") || !File.Exists(Path.Combine(directory, part.File!))) throw new InvalidDataException("Invalid catalog path");
    }
    T ReadChecked<T>(Part part)
    {
        CheckPart(part);
        var bytes = File.ReadAllBytes(Path.Combine(directory, part.File));
        if (bytes.Length > 16 * 1024 * 1024 || Convert.ToHexStringLower(SHA256.HashData(bytes)) != part.Sha256) throw new InvalidDataException("Catalog hash mismatch");
        if (part.File.EndsWith(".br",StringComparison.Ordinal)) {
            using var input=new MemoryStream(bytes);using var compressed=new System.IO.Compression.BrotliStream(input,System.IO.Compression.CompressionMode.Decompress);using var bounded=new BoundedReadStream(compressed,128*1024*1024);
            return JsonSerializer.Deserialize<T>(bounded,Options)??throw new InvalidDataException("Empty index");
        }
        return JsonSerializer.Deserialize<T>(bytes, Options) ?? throw new InvalidDataException("Empty catalog part");
    }
    static void ValidateRows(BankQuestion[] rows)
    {
        if (rows.Length > 250000 || rows.Any(q => q == null || q.Id is not { Length: > 0 and <= 200 } || q.Stem is not { Length: > 0 and <= 40000 }
            || !Subjects.Contains(q.Subject) || !Stages.Contains(q.Stage) || q.Tags == null || q.Issues == null || q.Answer == null || q.Source == null || q.License == null
            || q.KnowledgeIds == null || q.MaterialId == null || q.MaterialChunk == null || q.MaterialText == null || q.Tags.Any(string.IsNullOrWhiteSpace))) throw new InvalidDataException("Invalid question bank catalog");
    }
    static IEnumerable<BankQuestion> Clean(IEnumerable<BankQuestion> rows) => rows.Where(q => q.Issues.Length == 0 && !string.IsNullOrWhiteSpace(q.Answer) && !string.IsNullOrWhiteSpace(q.Source) && !string.IsNullOrWhiteSpace(q.License))
        .DistinctBy(q => q.Id).DistinctBy(q => Fingerprint(q.Subject, q.Stem, q.MaterialId));
    Scope GetBuiltinScope(string subject,string stage) => scopes.GetOrAdd((subject,stage),key=>new(()=>BuildScope(key.Item1,key.Item2),LazyThreadSafetyMode.ExecutionAndPublication)).Value;
    Scope GetScope(string subject,string stage)
    {
        var builtin=GetBuiltinScope(subject,stage);var snapshot=community?.Snapshot();if(snapshot==null||community?.Enabled!=true)return builtin;
        if(combinedScopes.TryGetValue((subject,stage),out var cached)&&cached.Revision==snapshot.Revision)return cached.Scope;
        var extra=snapshot.Questions.Where(q=>q.Subject==subject&&q.Stage==stage).Select(q=>new Entry(q,KeyHash(Fingerprint(q.Subject,q.Stem,q.MaterialId)),Tokens(q.Stem+" "+string.Join(" ",q.Tags)),Tags(q.Subject,q.Stem,q.Tags),KeyHash(Fingerprint(q.Subject,q.Stem)))).ToArray();
        var added=new Scope(extra);var offset=builtin.Entries.Length;
        static Dictionary<string,int[]> Merge(Dictionary<string,int[]> original,Dictionary<string,int[]> extra,int offset){var result=new Dictionary<string,int[]>(original,StringComparer.Ordinal);foreach(var(k,v)in extra)result[k]=original.TryGetValue(k,out var previous)?[..previous,..v.Select(i=>i+offset)]:v.Select(i=>i+offset).ToArray();return result;}
        var scope=extra.Length==0?new Scope(builtin.Entries,builtin.Details,builtin.Words,builtin.Tags,builtin.Knowledge){Revision=snapshot.Revision}:new Scope([..builtin.Entries,..extra],builtin.Details,Merge(builtin.Words,added.Words,offset),Merge(builtin.Tags,added.Tags,offset),Merge(builtin.Knowledge,added.Knowledge,offset)){Revision=snapshot.Revision};combinedScopes[(subject,stage)]=(snapshot.Revision,scope);return scope;
    }
    BankQuestion Hydrate(BankQuestion q)
    {
        if (q.MaterialId.Length == 0 || q.MaterialText.Length > 0) return q;
        var part = catalog?.Materials.SingleOrDefault(p => p.File == q.MaterialChunk) ?? throw new InvalidDataException("Reading material missing");
        var values = materials.GetOrAdd(part.File, _ => new(() => {
            var data = ReadChecked<JsonElement>(part).GetProperty("materials").Deserialize<Material[]>(Options) ?? throw new InvalidDataException("Material format");
            return data.ToDictionary(m => m.Id, m => m.Text);
        }, LazyThreadSafetyMode.ExecutionAndPublication)).Value;
        if (!values.TryGetValue(q.MaterialId, out var text) || text is not { Length: > 0 and <= 100000 }) throw new InvalidDataException("Reading material mismatch");
        return q with { MaterialText = text };
    }
    public BankAvailability Availability()
    {
        var contributed=community?.Snapshot().Questions??[];var coverage=(catalog?.Subjects.Select(s=>(s.Subject,s.Stage,s.Count))??legacy.GroupBy(q=>(q.Subject,q.Stage)).Select(g=>(g.Key.Subject,g.Key.Stage,g.Count()))).Concat(contributed.GroupBy(q=>(q.Subject,q.Stage)).Select(g=>(g.Key.Subject,g.Key.Stage,g.Count()))).GroupBy(s=>(s.Subject,s.Stage)).Select(g=>(object)new{g.Key.Subject,g.Key.Stage,count=g.Sum(x=>x.Item3)}).ToArray();
        var count=(catalog?.Count??legacy.Length)+contributed.Length;return new(count>0,count,coverage,Method);
    }

    static string Normalize(string value) => Regex.Replace(value.Normalize(NormalizationForm.FormKC).ToLowerInvariant(), @"^\s*(?:(?:例|练|测)(?:题)?\s*)?\d+[.、．:：\s]+", "", RegexOptions.CultureInvariant);
    public static string Fingerprint(string subject, string stem, string materialId = "") => subject + "|" + Regex.Replace(Normalize(stem), @"[\s$，,。.;；:：!?！？（）()]", "") + (materialId.Length > 0 ? "|material:" + materialId : "");
    static HashSet<string> Tokens(string value)
    {
        var result = new HashSet<string>(StringComparer.Ordinal);
        foreach (Match run in Regex.Matches(Normalize(value), @"[\p{IsCJKUnifiedIdeographs}]+|[a-z]{2,}")) {
            if (run.Value[0] <= 'z') { if (run.Length <= 128 && run.Value is not ("frac" or "left" or "right" or "mathrm" or "text" or "sqrt" or "begin" or "end")) result.Add(run.Value); }
            else for (var i = 0; i < run.Length - 1; i++) { var token = run.Value.Substring(i, 2); if (!Stop.Contains(token)) result.Add(token); }
        }
        return result;
    }
    HashSet<string> Tags(string subject, string stem, IEnumerable<string> explicitTags)
    {
        var tags = explicitTags.Where(x => !string.IsNullOrWhiteSpace(x)).Select(x => x.Trim()).ToHashSet(StringComparer.Ordinal);
        if (rules.TryGetValue(subject, out var subjectRules)) foreach (var (tag, keywords) in subjectRules) if (keywords.Any(word => stem.Contains(word, StringComparison.OrdinalIgnoreCase))) tags.Add(tag);
        return tags;
    }
    static HashSet<int> Candidates(Scope scope, IEnumerable<string> tokens, IEnumerable<string> tags)
    {
        var ids = new HashSet<int>();
        foreach (var token in tokens) if (scope.Words.TryGetValue(token, out var found)) ids.UnionWith(found);
        foreach (var tag in tags) if (scope.Tags.TryGetValue(tag, out var found)) ids.UnionWith(found);
        return ids;
    }
    public QuestionMatch[] Recommend(string subject, string stage, string stem, string[] tags, int limit, ISet<string> excluded)
    {
        try {
            var scope = GetScope(subject, stage); var pool = scope.Entries;
            var tokens = Tokens(stem); var knowledge = Tags(subject, stem, tags);
            double Weight(string token) => Math.Log(1 + (pool.Length + 1.0) / ((scope.Words.GetValueOrDefault(token)?.Length ?? 0) + 1.0));
            var weight = tokens.Sum(Weight); var matches = new List<QuestionMatch>(); var hits = TermHits(scope,tokens); var sourceKey=KeyHash(Fingerprint(subject,stem)); var excludedHashes=excluded.Select(KeyHash).Concat(excluded).ToHashSet(StringComparer.Ordinal);
            foreach (var i in Candidates(scope, tokens, knowledge)) {
                var entry = pool[i];
                if (excludedHashes.Contains(entry.Key) || excludedHashes.Contains(entry.PlainKey) || entry.PlainKey == sourceKey) continue;
                var commonTags = knowledge.Intersect(entry.Tags).Order(StringComparer.Ordinal).ToArray();
                var common = hits.TryGetValue(i,out var commonWords)?commonWords.ToArray():[]; var similarity = weight == 0 ? 0 : common.Sum(Weight) / weight;
                if (commonTags.Length == 0 && (common.Length < 3 || similarity < .22)) continue;
                var score = Math.Min(1, commonTags.Length * .25) * .65 + similarity * .35;
                var reasons = new List<string> { "同学科、同学段" };
                if (commonTags.Length > 0) reasons.Add("知识点关键词：" + string.Join("、", commonTags));
                if (common.Length >= 3) reasons.Add("题干相关词项：" + string.Join("、", common.OrderByDescending(Weight).ThenBy(x => x, StringComparer.Ordinal).Take(5)));
                if (!entry.Question.Reviewed) reasons.Add("题目尚未经教研校对，请教师核对答案与适用性");
                matches.Add(new(entry.Question, Math.Round(score, 4), reasons.ToArray()));
            }
            return Diverse(matches, limit).Select(m => m with { Question = Hydrate(Detail(m.Question,scope)) }).ToArray();
        } catch (Exception e) when (DataError(e)) { log.LogWarning("Question bank scope unavailable ({ErrorType})", e.GetType().Name); return []; }
    }
    static IEnumerable<QuestionMatch> Diverse(IEnumerable<QuestionMatch> matches, int limit)
    {
        // Broad topics cover different template families before repeating numeric variants.
        var groups = matches.OrderByDescending(x => x.Score).ThenBy(x => x.Question.Id, StringComparer.Ordinal)
            .GroupBy(x => x.Question.TemplateId.Length > 0 ? "template:" + x.Question.TemplateId : x.Question.MaterialId.Length > 0 ? "material:" + x.Question.MaterialId : x.Question.Id).Select(g => new Queue<QuestionMatch>(g)).ToArray();
        var result = new List<QuestionMatch>();
        while (result.Count < limit) { var added = false; foreach (var group in groups) { if (group.Count == 0) continue; result.Add(group.Dequeue()); added = true; if (result.Count == limit) break; } if (!added) break; }
        return result;
    }
    public BankKnowledgeNode[] Knowledge(string subject, string query) => nodes.Where(n => n.Subject == subject && n.ParentId.Length > 0 && (query.Length == 0 || (n.Name + " " + string.Join(" ", n.Aliases)).Contains(query, StringComparison.OrdinalIgnoreCase))).Take(500).ToArray();
    QuestionMatch[] SelectTopic(Scope scope,BankQuery query)
    {
            var topic = Regex.Replace(query.Topic.Trim(), "(?:选择题|填空题|解答题|综合题|大题)$", "").Trim();
            var tokens = Tokens(topic == "光合作用" ? "光合" : topic); var exact = nodes.Where(n => n.Subject == query.Subject && n.ParentId.Length > 0 && (n.Name.Equals(topic, StringComparison.OrdinalIgnoreCase) || n.Aliases.Any(a => a.Equals(topic, StringComparison.OrdinalIgnoreCase)))).Select(n => n.Id).ToArray();
            var candidates = Candidates(scope, tokens, [topic]);
            var descendants = nodes.Where(n => exact.Any(id => n.Id == id || n.Id.StartsWith(id + ".", StringComparison.Ordinal))).Select(n => n.Id).ToHashSet();
            foreach (var id in descendants) if (scope.Knowledge.TryGetValue(id, out var knowledgeHits)) candidates.UnionWith(knowledgeHits);
            var excluded = (query.ExcludeIds ?? []).ToHashSet(StringComparer.Ordinal); var ranked = new List<QuestionMatch>();
            double Weight(string t) => Math.Log(1 + (scope.Entries.Length + 1.0) / ((scope.Words.GetValueOrDefault(t)?.Length ?? 0) + 1.0));
            var total = tokens.Sum(Weight); var hits=TermHits(scope,tokens); var sourceKey=query.SourceText.Length>0?KeyHash(Fingerprint(query.Subject,query.SourceText)):"";
            foreach (var i in candidates) {
                var e = scope.Entries[i]; var q = e.Question;
                if (excluded.Contains(q.Id) || query.Type.Length > 0 && q.Type != query.Type || sourceKey.Length>0 && e.PlainKey==sourceKey) continue;
                var exactKnowledge = q.KnowledgeIds.Any(descendants.Contains); var exactTag = q.Tags.Contains(topic, StringComparer.OrdinalIgnoreCase);
                var common = hits.TryGetValue(i,out var commonWords)?commonWords.ToArray():[]; var similarity = total > 0 ? common.Sum(Weight) / total : 0;
                if (!exactKnowledge && !exactTag && (tokens.Count == 0 || similarity < .65 || common.Length < Math.Min(2, tokens.Count))) continue;
                var reasons = new List<string> { "同学科、同学段" };
                if (exactKnowledge) reasons.Add("知识点目录：" + string.Join("、", nodes.Where(n => exact.Contains(n.Id)).Select(n => n.Name)));
                else if (exactTag) reasons.Add("题型标签：" + topic);
                else reasons.Add("类型词项：" + string.Join("、", common.Take(5)));
                reasons.Add(q.SourceKind == "original" ? "自编练习（参数变式不等于独立题型）" : q.SourceKind == "community" ? "员工共享上传；未经教研校对" : "公开来源原题");
                ranked.Add(new(q, Math.Round((exactKnowledge ? 1 : 0) + (exactTag ? .5 : 0) + similarity, 4), reasons.ToArray()));
            }
        return Diverse(ranked,query.Limit).ToArray();
    }
    public BankQueryResult Search(BankQuery query)
    {
        var clock = Stopwatch.StartNew(); var matches = Array.Empty<QuestionMatch>(); var status = "no_match"; var message = "没有找到该类型的同学科学段题目，请换一个更具体的知识点。";
        try {
            var scope=GetScope(query.Subject,query.Stage);var revision=scope.Revision;
            var key=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new{query.Subject,query.Stage,query.Topic,query.Type,query.Limit,exclude=(query.ExcludeIds??[]).Order(StringComparer.Ordinal).ToArray(),source=query.SourceText,revision},Options))));
            matches=queryCache.Get(key,()=>SelectTopic(scope,query)).Select(m=>m with{Question=Hydrate(Detail(m.Question,scope))}).ToArray();
            if (matches.Length > 0) { status = matches.Length == query.Limit ? "matched" : "partial"; message = matches.Length == query.Limit ? "已返回同类题，使用前请核对教学适用性。" : $"当前仅找到{matches.Length}道匹配题，未用其他学段或无关题补齐。"; }
            else if (!Availability().Available) { status = "unavailable"; message = "题库暂不可用，请联系管理员检查题源。"; }
        } catch (Exception e) when (DataError(e)) { status = "unavailable"; message = "该学科题源读取失败，请联系管理员检查题库。"; log.LogWarning("Topic lookup unavailable ({ErrorType})", e.GetType().Name); }
        return new(status, message, query.Subject, query.Stage, query.Topic, query.Type, query.Limit, matches.Length, Math.Round(clock.Elapsed.TotalMilliseconds, 2), Method, true, matches);
    }
}

