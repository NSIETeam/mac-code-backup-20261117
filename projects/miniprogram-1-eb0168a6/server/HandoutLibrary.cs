using System.Data;
using System.Data.Common;
using System.Globalization;
using System.Text;
using Microsoft.EntityFrameworkCore;

namespace Siyue;

/// <summary>Administrator-only, read-through metadata catalogue. No material is copied or reprocessed.</summary>
public static class HandoutLibrary
{
    const int MaximumPageSize = 30, MaximumExport = 10_000;
    static readonly string[] StatusValues = ["queued", "processing", "review", "published", "failed", "other"];
    public record Item(string Kind, Guid Id, string FileName, DateTimeOffset CreatedAt, string State, string Status,
        Guid? StaffId, string StaffName, bool StaffArchived, Guid? ClassId, string ClassName, bool ClassArchived,
        Guid? CourseId, string CourseName, bool CourseArchived, bool Shared, bool SourceRecorded,
        long LectureCount, long HandoutCount, long QuestionCount, long ConfirmedCount,
        bool HasOriginal, bool CanOpen, string? OpenUrl, string? ContentFingerprint = null, HandoutReuseSummary? Reuse = null);
    public record Child(Guid Id, Guid LectureId, int Sequence, string Title, int Version, string State, string FileName,
        long QuestionCount, long ConfirmedCount, bool CanOpen, string? OpenUrl);
    public record Stats(long Uploads, long Books, long StandaloneHandouts, long Teachers, long Classes, long Lectures,
        long Review, long Failed, long Published, long Processing, long Unattributed);
    public record Option(Guid Id, string Name, bool Archived, bool Enabled = true);
    public record StatusOption(string Value, string Label);
    public record Filters(Option[] Teachers, Option[] Classes, Option[] Courses, StatusOption[] Statuses);
    public record Pagination(int Page, int PageSize, long Total, long TotalPages);
    public record Page(Item[] Items, Pagination Pagination, Stats Stats, Filters Filters);
    public record Detail(Item Item, Child[] Children, string? OriginalUrl, string? SourcePdfUrl);
    sealed record Query(string? Q, Guid? StaffId, Guid? ClassId, Guid? CourseId, string? Status, int Page, int PageSize)
    {
        public static Query Create(string? q, Guid? staffId, Guid? classId, Guid? courseId, string? status, int? page, int? size)
        {
            q = q?.Trim(); status = status?.Trim().ToLowerInvariant();
            Guard.That(q == null || q.Length <= 120, "搜索内容不能超过120个字符");
            Guard.That(string.IsNullOrEmpty(status) || status == "all" || StatusValues.Contains(status), "资料状态无效");
            Guard.That((page ?? 1) is >= 1 and <= 1_000_000, "页码无效");
            Guard.That((size ?? 20) is >= 1 and <= MaximumPageSize, "每页最多30份资料");
            return new(string.IsNullOrEmpty(q) ? null : q, staffId, classId, courseId,
                string.IsNullOrEmpty(status) || status == "all" ? null : status, page ?? 1, size ?? 20);
        }
    }
    sealed record Stored(Item Item, string OriginalKey, string SourcePdfKey);

    public static void MapHandoutLibrary(this WebApplication app)
    {
        app.MapGet("/api/handout-library", async (string? q, Guid? staffId, Guid? classId, Guid? courseId, string? status,
            int? page, int? pageSize, Data db, Access access, IObjects objects, CancellationToken ct) => {
            access.Admin(); var query = Query.Create(q, staffId, classId, courseId, status, page, pageSize);
            var stats = await Statistics(db, query, ct);
            var rows = await Read(db, Sql(db) + "SELECT * FROM scoped ORDER BY \"CreatedAt\" DESC, \"Kind\", \"Id\" LIMIT @limit OFFSET @offset", query, ReadStored, ct,
                ("limit", query.PageSize), ("offset", (long)(query.Page - 1) * query.PageSize));
            var reuse = await HandoutReuse.Summaries(objects, rows.Select(x => (x.Item.Kind, x.Item.Id)), ct);
            return new Page(rows.Select(x => x.Item with { Reuse = reuse.GetValueOrDefault(x.Item.Id) }).ToArray(), new(query.Page, query.PageSize, stats.Uploads, (stats.Uploads + query.PageSize - 1) / query.PageSize), stats, await Options(db, ct));
        });
        app.MapGet("/api/handout-library/export.csv", async (string? q, Guid? staffId, Guid? classId, Guid? courseId, string? status,
            Data db, Access access, CancellationToken ct) => {
            access.Admin(); var query = Query.Create(q, staffId, classId, courseId, status, 1, MaximumPageSize);
            var stats = await Statistics(db, query, ct); Guard.That(stats.Uploads <= MaximumExport, "导出最多10000份资料，请缩小筛选范围");
            var rows = await Read(db, Sql(db) + "SELECT * FROM scoped ORDER BY \"CreatedAt\" DESC, \"Kind\", \"Id\" LIMIT @limit", query, ReadStored, ct, ("limit", MaximumExport));
            var csv = new StringBuilder("类型,文件名,上传老师,班级,课程,上传时间,状态,讲次数,讲义版本数,题目数,已确认数,归档状态\r\n");
            foreach (var row in rows) { var x = row.Item; csv.AppendLine(string.Join(",", new[] {
                x.Kind == "book" ? "原始合订讲义" : "独立讲义", x.FileName, x.StaffName, x.ClassName, x.CourseName,
                x.CreatedAt.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture), StatusLabel(x.Status),
                x.LectureCount.ToString(CultureInfo.InvariantCulture), x.HandoutCount.ToString(CultureInfo.InvariantCulture), x.QuestionCount.ToString(CultureInfo.InvariantCulture),
                x.ConfirmedCount.ToString(CultureInfo.InvariantCulture), x.ClassArchived || x.CourseArchived || x.StaffArchived ? "含停用资料" : "正常"
            }.Select(CsvCell))); }
            return Results.File(Encoding.UTF8.GetPreamble().Concat(Encoding.UTF8.GetBytes(csv.ToString())).ToArray(), "text/csv; charset=utf-8", "讲义总库.csv");
        });
        app.MapGet("/api/handout-library/{kind}/{id:guid}", async (string kind, Guid id, Data db, Access access, Files files, IObjects objects, CancellationToken ct) => {
            access.Admin(); Guard.That(kind is "book" or "handout", "资料类型无效", 404);
            var query = Query.Create(null, null, null, null, null, 1, 20);
            var rows = await Read(db, Sql(db) + "SELECT * FROM scoped WHERE \"Kind\"=@kind AND LOWER(CAST(\"Id\" AS TEXT))=@id", query, ReadStored, ct, ("kind", kind), ("id", id.ToString()));
            var row = rows.SingleOrDefault() ?? throw new ApiException(404, "原始上传资料不存在；拆出的讲次请从原稿进入");
            var children = await Read(db, Sql(db) + """
                SELECT h."Id", l."Id" AS "LectureId", l."Sequence", l."Title", h."Version", h."State", h."FileName", l."ClassId", l."CourseId",
                    CASE WHEN l."ClassId" IS NOT NULL AND (c."Id" IS NULL OR c."Archived") THEN 1 ELSE 0 END AS "ClassArchived",
                    CASE WHEN co."Id" IS NULL OR co."Archived" THEN 1 ELSE 0 END AS "CourseArchived",
                    COUNT(q."Id") AS "QuestionCount", COALESCE(SUM(CASE WHEN q."Confirmed" THEN 1 ELSE 0 END),0) AS "ConfirmedCount"
                FROM item_handouts ih JOIN "Handouts" h ON h."Id"=ih."HandoutId" JOIN "Lectures" l ON l."Id"=h."LectureId"
                LEFT JOIN "Classes" c ON c."Id"=l."ClassId" LEFT JOIN "Courses" co ON co."Id"=l."CourseId" LEFT JOIN "Questions" q ON q."HandoutId"=h."Id"
                WHERE ih."Kind"=@kind AND LOWER(CAST(ih."ItemId" AS TEXT))=@id
                GROUP BY h."Id", l."Id", l."Sequence", l."Title", h."Version", h."State", h."FileName", l."ClassId", l."CourseId", c."Id", c."Archived", co."Id", co."Archived"
                ORDER BY l."Sequence", h."Version", h."CreatedAt", h."Id"
                """, query, r => { var canOpen = !Bool(r, "ClassArchived") && !Bool(r, "CourseArchived"); var handoutId = GuidValue(r, "Id")!.Value;
                    return new Child(handoutId, GuidValue(r, "LectureId")!.Value, (int)Long(r, "Sequence"), Text(r, "Title"), (int)Long(r, "Version"), Text(r, "State"), Text(r, "FileName"),
                        Long(r, "QuestionCount"), Long(r, "ConfirmedCount"), canOpen, canOpen ? Link("handout", handoutId, GuidValue(r, "ClassId")) + (kind == "book" ? "&bookId=" + id : "") : null); }, ct, ("kind", kind), ("id", id.ToString()));
            string? original = null, sourcePdf = null;
            if (row.OriginalKey.Length > 0) original = await files.Grant(row.OriginalKey, ContentType(row.Item.FileName), Path.GetFileName(row.Item.FileName), access.User.Id, classId: row.Item.ClassId, courseId: row.Item.CourseId);
            if (row.SourcePdfKey.Length > 0 && row.SourcePdfKey != row.OriginalKey) sourcePdf = await files.Grant(row.SourcePdfKey, "application/pdf", "原讲义.pdf", access.User.Id, classId: row.Item.ClassId, courseId: row.Item.CourseId);
            else if (row.SourcePdfKey == row.OriginalKey || Path.GetExtension(row.Item.FileName).Equals(".pdf", StringComparison.OrdinalIgnoreCase)) sourcePdf = original;
            var reuse = await HandoutReuse.Summaries(objects, [(kind, id)], ct);
            return new Detail(row.Item with { Reuse = reuse.GetValueOrDefault(id) }, children, original, sourcePdf);
        });
    }

    static string ContentType(string name) => Path.GetExtension(name).ToLowerInvariant() switch { ".pdf" => "application/pdf", ".docx" => WordDocuments.DocxType, ".doc" => "application/msword", _ => "application/octet-stream" };
    static string Link(string kind, Guid id, Guid? classId) => "/miniapp/workbench/#" + kind + "?id=" + id + (classId.HasValue ? "&classId=" + classId : "");
    static string StatusLabel(string status) => status switch { "queued" => "排队中", "processing" => "处理中", "review" => "待校对", "published" => "已发布", "failed" => "处理失败", _ => "其他" };
    public static string CsvCell(string value)
    {
        var first = value.TrimStart().TrimStart('\uFEFF').TrimStart().FirstOrDefault();
        if ("=+-@".Contains(first) || value.StartsWith('\t') || value.StartsWith('\r') || value.StartsWith('\n')) value = "'" + value;
        return "\"" + value.Replace("\"", "\"\"") + "\"";
    }
    static long Long(DbDataReader r, string n) => r[n] == DBNull.Value ? 0 : Convert.ToInt64(r[n], CultureInfo.InvariantCulture);
    static bool Bool(DbDataReader r, string n) => r[n] != DBNull.Value && Convert.ToBoolean(r[n], CultureInfo.InvariantCulture);
    static string Text(DbDataReader r, string n) => r[n] == DBNull.Value ? "" : Convert.ToString(r[n], CultureInfo.InvariantCulture) ?? "";
    static Guid? GuidValue(DbDataReader r, string n) => r[n] == DBNull.Value ? null : r[n] is Guid id ? id : Guid.Parse(Text(r, n));
    static Stored ReadStored(DbDataReader r)
    {
        var kind = Text(r, "Kind"); var id = GuidValue(r, "Id")!.Value; var classId = GuidValue(r, "ClassId");
        var canOpen = !Bool(r, "ClassArchived") && !Bool(r, "CourseArchived");
        return new(new Item(kind, id, Text(r, "FileName"), new DateTimeOffset(Long(r, "CreatedAt"), TimeSpan.Zero), Text(r, "State"), Text(r, "Status"),
            GuidValue(r, "StaffId"), Text(r, "StaffName"), Bool(r, "StaffArchived"), classId, Text(r, "ClassName"), Bool(r, "ClassArchived"),
            GuidValue(r, "CourseId"), Text(r, "CourseName"), Bool(r, "CourseArchived"), !classId.HasValue, GuidValue(r, "StaffId").HasValue,
            Long(r, "LectureCount"), Long(r, "HandoutCount"), Long(r, "QuestionCount"), Long(r, "ConfirmedCount"), Text(r, "OriginalKey").Length > 0,
            canOpen, canOpen ? Link(kind, id, classId) : null), Text(r, "OriginalKey"), Text(r, "SourcePdfKey"));
    }
    static async Task<Stats> Statistics(Data db, Query query, CancellationToken ct) => (await Read(db, Sql(db) + """
        SELECT COUNT(*) AS "Uploads", COALESCE(SUM(CASE WHEN "Kind"='book' THEN 1 ELSE 0 END),0) AS "Books",
            COALESCE(SUM(CASE WHEN "Kind"='handout' THEN 1 ELSE 0 END),0) AS "StandaloneHandouts",
            COUNT(DISTINCT "StaffId") AS "Teachers", COUNT(DISTINCT "ClassId") AS "Classes",
            (SELECT COUNT(DISTINCT h."LectureId") FROM item_handouts ih JOIN scoped s ON s."Kind"=ih."Kind" AND s."Id"=ih."ItemId" JOIN "Handouts" h ON h."Id"=ih."HandoutId") AS "Lectures",
            COALESCE(SUM(CASE WHEN "Status"='review' THEN 1 ELSE 0 END),0) AS "Review",
            COALESCE(SUM(CASE WHEN "Status"='failed' THEN 1 ELSE 0 END),0) AS "Failed",
            COALESCE(SUM(CASE WHEN "Status"='published' THEN 1 ELSE 0 END),0) AS "Published",
            COALESCE(SUM(CASE WHEN "Status" IN ('queued','processing') THEN 1 ELSE 0 END),0) AS "Processing",
            COALESCE(SUM(CASE WHEN "StaffId" IS NULL THEN 1 ELSE 0 END),0) AS "Unattributed" FROM scoped
        """, query, r => new Stats(Long(r, "Uploads"), Long(r, "Books"), Long(r, "StandaloneHandouts"), Long(r, "Teachers"), Long(r, "Classes"), Long(r, "Lectures"),
            Long(r, "Review"), Long(r, "Failed"), Long(r, "Published"), Long(r, "Processing"), Long(r, "Unattributed")), ct)).Single();

    static async Task<Filters> Options(Data db, CancellationToken ct)
    {
        var query = Query.Create(null, null, null, null, null, 1, 20);
        var teachers = await Read(db, Sql(db) + "SELECT DISTINCT \"StaffId\" AS \"Id\", \"StaffName\" AS \"Name\", \"StaffArchived\" AS \"Archived\", \"StaffEnabled\" AS \"Enabled\" FROM rows WHERE \"StaffId\" IS NOT NULL ORDER BY \"Name\", \"Id\"", query,
            r => new Option(GuidValue(r, "Id")!.Value, Text(r, "Name"), Bool(r, "Archived"), Bool(r, "Enabled")), ct);
        var classes = await Read(db, Sql(db) + "SELECT DISTINCT \"ClassId\" AS \"Id\", \"ClassName\" AS \"Name\", \"ClassArchived\" AS \"Archived\" FROM rows WHERE \"ClassId\" IS NOT NULL ORDER BY \"Name\", \"Id\"", query,
            r => new Option(GuidValue(r, "Id")!.Value, Text(r, "Name"), Bool(r, "Archived")), ct);
        var courses = await Read(db, Sql(db) + "SELECT DISTINCT \"CourseId\" AS \"Id\", \"CourseName\" AS \"Name\", \"CourseArchived\" AS \"Archived\" FROM rows WHERE \"CourseId\" IS NOT NULL ORDER BY \"Name\", \"Id\"", query,
            r => new Option(GuidValue(r, "Id")!.Value, Text(r, "Name"), Bool(r, "Archived")), ct);
        return new(teachers, classes, courses, StatusValues.Select(x => new StatusOption(x, StatusLabel(x))).ToArray());
    }

    static async Task<T[]> Read<T>(Data db, string sql, Query query, Func<DbDataReader, T> map, CancellationToken ct, params (string Name, object Value)[] extra)
    {
        var connection = db.Database.GetDbConnection(); var opened = connection.State != ConnectionState.Open;
        if (opened) await connection.OpenAsync(ct);
        try {
            await using var command = connection.CreateCommand(); command.CommandText = sql; command.CommandTimeout = 15;
            void Parameter(string name, object? value, DbType type) { var p = command.CreateParameter(); p.ParameterName = name; p.DbType = type; p.Value = value ?? DBNull.Value; command.Parameters.Add(p); }
            var search = query.Q == null ? null : "%" + query.Q.ToLowerInvariant().Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%";
            Parameter("q", search, DbType.String); Parameter("staff", query.StaffId?.ToString(), DbType.String); Parameter("class", query.ClassId?.ToString(), DbType.String);
            Parameter("course", query.CourseId?.ToString(), DbType.String); Parameter("status", query.Status, DbType.String);
            foreach (var (name, value) in extra) Parameter(name, value, value is string ? DbType.String : DbType.Int64);
            await using var reader = await command.ExecuteReaderAsync(ct); var result = new List<T>();
            while (await reader.ReadAsync(ct)) result.Add(map(reader)); return result.ToArray();
        } finally { if (opened) await connection.CloseAsync(); }
    }

    // JSON expansion is done by the database; only metadata and aggregate counts are returned.
    // Dirty/non-array legacy JSON is treated as an empty relation, never a reason to omit an upload.
    static string Sql(Data db)
    {
        var postgres = db.Database.IsNpgsql();
        var links = postgres ? """
            SELECT DISTINCT b."Id" AS "BookId", h."Id" AS "HandoutId" FROM "BookImports" b
            CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN b."SectionsJson" IS JSON ARRAY THEN b."SectionsJson"::jsonb ELSE '[]'::jsonb END) j
            JOIN "Handouts" h ON LOWER(CAST(h."Id" AS TEXT))=LOWER(COALESCE(j->>'handoutId',j->>'HandoutId'))
            """ : """
            SELECT DISTINCT b."Id" AS "BookId", h."Id" AS "HandoutId" FROM "BookImports" b
            JOIN json_each(CASE WHEN json_valid(b."SectionsJson") THEN CASE WHEN json_type(b."SectionsJson")='array' THEN b."SectionsJson" ELSE '[]' END ELSE '[]' END) j
            JOIN "Handouts" h ON LOWER(CAST(h."Id" AS TEXT))=LOWER(CASE WHEN j.type='object' THEN COALESCE(json_extract(j.value,'$.handoutId'),json_extract(j.value,'$.HandoutId')) END)
            """;
        return "WITH links AS (" + links + "), " + """
            item_handouts AS (
                SELECT 'book' AS "Kind", "BookId" AS "ItemId", "HandoutId" FROM links
                UNION ALL SELECT 'handout', h."Id", h."Id" FROM "Handouts" h WHERE NOT EXISTS(SELECT 1 FROM links bl WHERE bl."HandoutId"=h."Id")
            ), child_stats AS (
                SELECT ih."Kind", ih."ItemId", COUNT(DISTINCT h."LectureId") AS "LectureCount", COUNT(*) AS "HandoutCount",
                    SUM(CASE WHEN h."State"='draft' THEN 1 ELSE 0 END) AS "ReviewChildren",
                    SUM(CASE WHEN h."State"='failed' THEN 1 ELSE 0 END) AS "FailedChildren",
                    SUM(CASE WHEN h."State" IN ('queued','processing') THEN 1 ELSE 0 END) AS "ActiveChildren",
                    SUM(CASE WHEN h."State"='published' THEN 1 ELSE 0 END) AS "PublishedChildren"
                FROM item_handouts ih JOIN "Handouts" h ON h."Id"=ih."HandoutId" GROUP BY ih."Kind", ih."ItemId"
            ), question_stats AS (
                SELECT ih."Kind", ih."ItemId", COUNT(q."Id") AS "QuestionCount", COALESCE(SUM(CASE WHEN q."Confirmed" THEN 1 ELSE 0 END),0) AS "ConfirmedCount"
                FROM item_handouts ih LEFT JOIN "Questions" q ON q."HandoutId"=ih."HandoutId" GROUP BY ih."Kind", ih."ItemId"
            ), first_upload AS (
                SELECT "TargetId", "StaffId", ROW_NUMBER() OVER(PARTITION BY "TargetId" ORDER BY "CreatedAt", "Id") AS rn FROM "Audits" WHERE "Action"='handout.upload'
            ), originals AS (
                SELECT 'book' AS "Kind", b."Id", b."FileName", b."CreatedAt", b."State", b."StaffId", b."ClassId", b."CourseId", b."OriginalKey", '' AS "SourcePdfKey" FROM "BookImports" b
                UNION ALL
                SELECT 'handout', h."Id", h."FileName", h."CreatedAt", h."State", a."StaffId", l."ClassId", l."CourseId", h."OriginalKey", h."SourcePdfKey"
                FROM "Handouts" h JOIN "Lectures" l ON l."Id"=h."LectureId" LEFT JOIN first_upload a ON a."TargetId"=h."Id" AND a.rn=1
                WHERE NOT EXISTS(SELECT 1 FROM links bl WHERE bl."HandoutId"=h."Id")
            ), rows AS (
                SELECT o.*, COALESCE(s."Name", CASE WHEN o."StaffId" IS NULL THEN '来源未记录' ELSE '人员记录已缺失' END) AS "StaffName",
                    CASE WHEN s."Archived" OR NOT s."Enabled" THEN 1 ELSE 0 END AS "StaffArchived", COALESCE(s."Enabled",FALSE) AS "StaffEnabled",
                    COALESCE(c."Name",CASE WHEN o."ClassId" IS NULL THEN '课程共享讲义' ELSE '班级记录已缺失' END) AS "ClassName",
                    CASE WHEN o."ClassId" IS NOT NULL AND (c."Id" IS NULL OR c."Archived") THEN 1 ELSE 0 END AS "ClassArchived",
                    COALESCE(co."Name",'课程记录已缺失') AS "CourseName", CASE WHEN co."Id" IS NULL OR co."Archived" THEN 1 ELSE 0 END AS "CourseArchived",
                    COALESCE(cs."LectureCount",0) AS "LectureCount", COALESCE(cs."HandoutCount",0) AS "HandoutCount",
                    COALESCE(qs."QuestionCount",0) AS "QuestionCount", COALESCE(qs."ConfirmedCount",0) AS "ConfirmedCount",
                    CASE WHEN o."State"='failed' OR cs."FailedChildren">0 THEN 'failed'
                         WHEN o."State"='queued' THEN 'queued'
                         WHEN o."State" IN ('processing','splitting') OR cs."ActiveChildren">0 THEN 'processing'
                         WHEN o."State" IN ('draft','review') OR cs."ReviewChildren">0 THEN 'review'
                         WHEN o."State"='published' OR (cs."HandoutCount">0 AND cs."PublishedChildren"=cs."HandoutCount") THEN 'published'
                         WHEN o."State"='split' THEN 'review' ELSE 'other' END AS "Status"
                FROM originals o LEFT JOIN "Staff" s ON s."Id"=o."StaffId" LEFT JOIN "Classes" c ON c."Id"=o."ClassId" LEFT JOIN "Courses" co ON co."Id"=o."CourseId"
                LEFT JOIN child_stats cs ON cs."Kind"=o."Kind" AND cs."ItemId"=o."Id" LEFT JOIN question_stats qs ON qs."Kind"=o."Kind" AND qs."ItemId"=o."Id"
            ), scoped AS (
                SELECT * FROM rows WHERE (@staff IS NULL OR LOWER(CAST("StaffId" AS TEXT))=@staff) AND (@class IS NULL OR LOWER(CAST("ClassId" AS TEXT))=@class)
                    AND (@course IS NULL OR LOWER(CAST("CourseId" AS TEXT))=@course) AND (@status IS NULL OR "Status"=@status)
                    AND (@q IS NULL OR LOWER("FileName") LIKE @q ESCAPE '\' OR LOWER("StaffName") LIKE @q ESCAPE '\' OR LOWER("ClassName") LIKE @q ESCAPE '\' OR LOWER("CourseName") LIKE @q ESCAPE '\')
            )
            """;
    }
}
