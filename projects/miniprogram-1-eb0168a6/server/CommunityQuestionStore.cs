using Microsoft.Data.Sqlite;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Siyue;

public sealed record CommunityQuestionInput(string Subject, string Stage, string Type, string Stem, string Answer,
    string Source, string License, string Analysis = "", string[]? Tags = null, string[]? KnowledgeIds = null,
    string SourceUrl = "", string MaterialText = "", BankDiagram? Diagram = null);
public sealed record CommunityUploadInput(string RequestId, CommunityQuestionInput[] Questions);
public sealed record CommunityUploadResult(int Added, int Duplicates, string[] Ids, long Revision);
public sealed record CommunityQuestionRow(string Id, BankQuestion Question, string CreatedAt, bool Active);
public sealed record CommunitySnapshot(long Revision, BankQuestion[] Questions);

// Employee contributions have their own database. Existing student/printing databases stay read-only.
public sealed class CommunityQuestionStore
{
    readonly string connection = "";
    readonly object gate = new();
    CommunitySnapshot snapshot = new(0, []);
    DateTime nextCheck = DateTime.MinValue;
    static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { Encoder=System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    public bool Enabled { get; }
    public CommunityQuestionStore(IConfiguration config, IWebHostEnvironment env)
    {
        Enabled = config.GetValue<bool>("QuestionBank:UploadsEnabled");
        if (!Enabled) return;
        var directory = Path.GetFullPath(config["QuestionBank:UploadsDirectory"] ?? Path.Combine(env.ContentRootPath, ".local", "question-bank-uploads"));
        Directory.CreateDirectory(directory);
        connection = new SqliteConnectionStringBuilder { DataSource = Path.Combine(directory, "community.db"), Mode = SqliteOpenMode.ReadWriteCreate, DefaultTimeout = 10 }.ToString();
        using var db = Open();
        using var cmd = db.CreateCommand();
        cmd.CommandText = """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS BankState(Id INTEGER PRIMARY KEY CHECK(Id=1),Revision INTEGER NOT NULL);
            INSERT OR IGNORE INTO BankState VALUES(1,0);
            CREATE TABLE IF NOT EXISTS Contributions(Id TEXT PRIMARY KEY,OwnerId TEXT NOT NULL,ContentKey TEXT NOT NULL,Subject TEXT NOT NULL,Stage TEXT NOT NULL,QuestionJson TEXT NOT NULL,CreatedAt TEXT NOT NULL,Active INTEGER NOT NULL CHECK(Active IN(0,1)));
            CREATE UNIQUE INDEX IF NOT EXISTS IX_Contributions_Content ON Contributions(ContentKey) WHERE Active=1;
            CREATE INDEX IF NOT EXISTS IX_Contributions_Owner ON Contributions(OwnerId,CreatedAt DESC);
            CREATE TABLE IF NOT EXISTS UploadRequests(OwnerId TEXT NOT NULL,RequestId TEXT NOT NULL,PayloadHash TEXT NOT NULL,ResultJson TEXT NOT NULL,PRIMARY KEY(OwnerId,RequestId));
            """;
        cmd.ExecuteNonQuery();
        Refresh(db);
    }
    SqliteConnection Open() { var db = new SqliteConnection(connection); db.Open(); return db; }
    void Available() => Guard.That(Enabled, "共享上传服务暂不可用，请稍后重试", 503);
    static string Hash(string text) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
    static SqliteCommand Command(SqliteConnection db, string sql, params (string, object)[] values)
    {
        var cmd = db.CreateCommand(); cmd.CommandText = sql;
        foreach (var (key, value) in values) cmd.Parameters.AddWithValue(key, value);
        return cmd;
    }
    static SqliteCommand Command(SqliteConnection db, SqliteTransaction tx, string sql, params (string, object)[] values)
    { var cmd = Command(db, sql, values); cmd.Transaction = tx; return cmd; }
    void Refresh(SqliteConnection db)
    {
        using var read = db.BeginTransaction(deferred:true);
        using var rev = Command(db, read, "SELECT Revision FROM BankState WHERE Id=1");
        var revision = Convert.ToInt64(rev.ExecuteScalar());
        if (snapshot.Revision != revision || nextCheck == DateTime.MinValue) {
            using var cmd = Command(db, read, "SELECT QuestionJson FROM Contributions WHERE Active=1 ORDER BY Id");
            using var rows = cmd.ExecuteReader(); var questions = new List<BankQuestion>();
            while (rows.Read()) questions.Add(JsonSerializer.Deserialize<BankQuestion>(rows.GetString(0), JsonOptions) ?? throw new InvalidDataException("Invalid contribution record"));
            snapshot = new(revision, questions.ToArray());
        }
        read.Commit();
        nextCheck = DateTime.UtcNow.AddSeconds(1);
    }
    public CommunitySnapshot Snapshot()
    {
        if (!Enabled) return snapshot;
        lock (gate) { if (DateTime.UtcNow >= nextCheck) { using var db = Open(); Refresh(db); } return snapshot; }
    }
    internal static BankQuestion Validate(CommunityQuestionInput value, IQuestionBank bank, bool imageSource = false)
    {
        Guard.That(value != null && LocalQuestionBank.Subjects.Contains(value.Subject) && LocalQuestionBank.Stages.Contains(value.Stage), "请为每道题填写有效学科与学段");
        Guard.That(LocalQuestionBank.Types.Contains(value.Type), "请选择选择题、填空题、解答题或综合题");
        static string Text(string? text, int max, string label, bool required = false) { Guard.That(text != null && text.Length <= max && (!required || !string.IsNullOrWhiteSpace(text)), label + (required ? "为必填项，且内容不能过长" : "内容过长")); return text.Trim(); }
        var stem = Text(value.Stem, 40000, "题干", true); var answer = Text(value.Answer, 20000, "参考答案", true);
        var source = Text(value.Source, 500, "来源", true); var license = Text(value.License, 1000, "使用授权说明", true);
        var analysis = Text(value.Analysis, 40000, "解析"); var material = Text(value.MaterialText, 60000, "阅读材料");
        var url = Text(value.SourceUrl, 2000, "来源链接");
        Guard.That(url.Length == 0 || Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Scheme == "https" && string.IsNullOrEmpty(uri.UserInfo), "来源链接须为不含登录信息的HTTPS地址");
        var tags = value.Tags ?? []; Guard.That(tags.Length <= 20 && tags.All(t => t is { Length: > 0 and <= 60 } && !string.IsNullOrWhiteSpace(t)), "知识点标签最多20个，每个1至60字");
        var ids = value.KnowledgeIds ?? []; Guard.That(ids.Length <= 10 && ids.All(id => id is { Length: > 0 and <= 150 } && id.StartsWith(value.Subject + ".", StringComparison.Ordinal)), "知识点目录不属于所选学科");
        var known = bank.Knowledge(value.Subject, "").Select(n => n.Id).ToHashSet();
        // An unrecognised catalog ID must not be presented as an official curriculum annotation.
        Guard.That(ids.All(known.Contains), "知识点目录标识无效，请改用文字标签");
        
        if (value.Diagram is { } d) {
            Guard.That(d.Width == 360 && d.Height == 270 && d.Shapes is { Length: > 0 and <= 1000 }, "几何图格式无效");
            var points = 0;
            foreach (var s in d.Shapes) {
                Guard.That(s != null && s.Values != null && s.Values.Length <= 2000 && s.Values.All(v => double.IsFinite(v) && Math.Abs(v) <= 1000000), "几何图坐标无效"); points += s.Values.Length;
                Guard.That((s.Kind == "line" && s.Values.Length == 4 || s.Kind == "circle" && s.Values.Length == 3 && s.Values[2] > 0 || s.Kind == "text" && s.Values.Length == 2 || s.Kind == "path" && s.Values.Length >= 4 && s.Values.Length % 2 == 0)
                    && s.Text is { Length: <= 300 } && s.Color != null && Regex.IsMatch(s.Color, @"\A#[a-fA-F0-9]{6}\z") && s.Width is >= .1 and <= 8 && s.FontSize is >= 8 and <= 24 && s.Align is "left" or "center", "几何图只支持有效的点线和文字标注");
            }
            Guard.That(points <= 20000, "几何图数据过大");
        }
        Guard.That(imageSource || value.Diagram != null || !Regex.IsMatch(stem, "如[图圖]|图中|下图|右图|左图"), "题干引用了图形，请提供结构化图形或补充完整文字条件后上传");
        return new() { Id = "community-" + Guid.NewGuid().ToString("N"), Subject = value.Subject, Stage = value.Stage, Type = value.Type,
            Stem = stem, Answer = answer, Analysis = analysis, Source = source, License = license, SourceUrl = url,
            Tags = tags.Select(t => t.Trim()).Distinct().ToArray(), KnowledgeIds = ids.Distinct().ToArray(),
            MaterialText = material, MaterialId = material.Length > 0 ? "community-material-" + Hash(material) : "", Diagram = value.Diagram,
            SourceKind = "community", Attribution = "员工共享上传；尚未经教研校对", Reviewed = false };
    }
    public CommunityUploadResult Upload(Guid owner, CommunityUploadInput input, IQuestionBank bank) => SaveUpload(owner,input,bank,null,null);
    internal CommunityUploadResult UploadIntake(Guid owner, CommunityUploadInput input, IQuestionBank bank, string[][] images, string[] imageKeys) => SaveUpload(owner,input,bank,images,imageKeys);
    CommunityUploadResult SaveUpload(Guid owner, CommunityUploadInput input, IQuestionBank bank, string[][]? images, string[]? imageKeys)
    {
        Available();
        Guard.That(input.RequestId is { Length: > 0 and <= 120 } && input.Questions is { Length: > 0 and <= 100 }, "每次上传1至100道题，请保留上传标识后重试");
        var hash = Hash(imageKeys == null ? JsonSerializer.Serialize(input.Questions, JsonOptions) : JsonSerializer.Serialize(new {input.Questions,imageKeys}, JsonOptions));
        // Validate the entire batch before any insert; one malformed question cannot partly publish a batch.
        var questions = input.Questions.Select((q,i) => Validate(q, bank, images != null && images[i].Length>0) with { Images=images?[i]??[],ImageKey=imageKeys?[i]??"" }).ToArray();
        Dictionary<string,bool> builtinDuplicates;
        try{builtinDuplicates=questions.ToDictionary(q=>q.Id,q=>q.Images.Length==0 && bank.ContainsQuestion(q));}catch(Exception e)when(e is InvalidDataException or IOException or JsonException){throw new ApiException(503,"原题库暂无法完成去重，请稍后重试");}
        lock (gate) {
            using var db = Open(); using var tx = db.BeginTransaction();
            using var previous = Command(db, tx, "SELECT PayloadHash,ResultJson FROM UploadRequests WHERE OwnerId=$owner AND RequestId=$request", ("$owner", owner.ToString()), ("$request", input.RequestId));
            using (var reader = previous.ExecuteReader()) if (reader.Read()) { Guard.That(reader.GetString(0) == hash, "该上传标识已用于其他内容，请重新提交", 409); return JsonSerializer.Deserialize<CommunityUploadResult>(reader.GetString(1), JsonOptions)!; }
            using var total = Command(db, tx, "SELECT count(*) FROM Contributions WHERE Active=1");
            Guard.That(Convert.ToInt32(total.ExecuteScalar()) + questions.Length <= 50000, "共享题库容量已满，请联系管理员扩容", 409);
            using var storage=Command(db,tx,"SELECT coalesce(sum(length(CAST(QuestionJson AS BLOB))),0) FROM Contributions");var bytes=Convert.ToInt64(storage.ExecuteScalar());
            var ids = new List<string>(); var duplicates = 0;
            foreach (var q in questions) {
                if(builtinDuplicates[q.Id]){duplicates++;continue;}
                var key = q.Stage + "|" + LocalQuestionBank.Fingerprint(q.Subject, q.Stem, q.MaterialId) + (q.ImageKey.Length>0?"|image:"+q.ImageKey:"");
                using var exists = Command(db, tx, "SELECT Id FROM Contributions WHERE ContentKey=$key AND Active=1", ("$key", key));
                if (exists.ExecuteScalar() != null) { duplicates++; continue; }
                var json=JsonSerializer.Serialize(q,JsonOptions);bytes+=Encoding.UTF8.GetByteCount(json);Guard.That(bytes<=128*1024*1024,"共享题库存储容量已满，请联系管理员扩容",409);
                using var add = Command(db, tx, "INSERT INTO Contributions VALUES($id,$owner,$key,$subject,$stage,$json,$at,1)", ("$id", q.Id), ("$owner", owner.ToString()), ("$key", key), ("$subject", q.Subject), ("$stage", q.Stage), ("$json", json), ("$at", DateTimeOffset.UtcNow.ToString("O")));
                add.ExecuteNonQuery(); ids.Add(q.Id);
            }
            if (ids.Count > 0) { using var bump = Command(db, tx, "UPDATE BankState SET Revision=Revision+1 WHERE Id=1"); bump.ExecuteNonQuery(); }
            using var revision = Command(db, tx, "SELECT Revision FROM BankState WHERE Id=1");
            var result = new CommunityUploadResult(ids.Count, duplicates, ids.ToArray(), Convert.ToInt64(revision.ExecuteScalar()));
            using var request = Command(db, tx, "INSERT INTO UploadRequests VALUES($owner,$request,$hash,$json)", ("$owner", owner.ToString()), ("$request", input.RequestId), ("$hash", hash), ("$json", JsonSerializer.Serialize(result, JsonOptions))); request.ExecuteNonQuery();
            tx.Commit(); nextCheck = DateTime.MinValue; Refresh(db); return result;
        }
    }
    public CommunityQuestionRow[] Mine(Guid owner, int page)
    {
        Available(); Guard.That(page is >= 1 and <= 10000, "页码无效");
        lock (gate) { using var db = Open(); using var cmd = Command(db, "SELECT Id,QuestionJson,CreatedAt,Active FROM Contributions WHERE OwnerId=$owner ORDER BY CreatedAt DESC,Id LIMIT 25 OFFSET $offset", ("$owner", owner.ToString()), ("$offset", (page - 1) * 25)); using var rows = cmd.ExecuteReader(); var result = new List<CommunityQuestionRow>();
            while (rows.Read()) result.Add(new(rows.GetString(0), JsonSerializer.Deserialize<BankQuestion>(rows.GetString(1), JsonOptions)!, rows.GetString(2), rows.GetInt32(3) == 1)); return result.ToArray(); }
    }
    public long SetActive(Guid owner, string id, bool active)
    {
        Available(); Guard.That(Regex.IsMatch(id, @"\Acommunity-[a-f0-9]{32}\z"), "题目标识无效");
        lock (gate) { using var db = Open(); using var tx = db.BeginTransaction(); using var change = Command(db, tx, "UPDATE Contributions SET Active=$active WHERE Id=$id AND OwnerId=$owner", ("$active", active ? 1 : 0), ("$id", id), ("$owner", owner.ToString()));
            try { Guard.That(change.ExecuteNonQuery() == 1, "只能管理自己上传的题目", 403); } catch (SqliteException e) when (e.SqliteErrorCode == 19) { throw new ApiException(409, "共享题库已有相同内容，请保留现有题目"); }
            using var bump = Command(db, tx, "UPDATE BankState SET Revision=Revision+1 WHERE Id=1"); bump.ExecuteNonQuery(); tx.Commit(); nextCheck = DateTime.MinValue; Refresh(db); return snapshot.Revision; }
    }
}
