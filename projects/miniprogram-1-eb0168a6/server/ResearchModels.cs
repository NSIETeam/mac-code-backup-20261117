namespace Siyue;

public sealed record ResearchTemplate(string Id, string Name, string Header, string Footer, string Paper,
    int FontSize, int MarginMm, int AnswerLines, string[] Sections, bool Cover = false);
public sealed record ResearchItem
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public string Label { get; init; } = "";
    public string Section { get; init; } = "例题讲解";
    public string Stem { get; init; } = "";
    public string Answer { get; init; } = "";
    public string Analysis { get; init; } = "";
    public string Type { get; init; } = "";
    public string Difficulty { get; init; } = "";
    public string[] Knowledge { get; init; } = [];
    public string KnowledgeMethod { get; init; } = "待识别";
    public ContentBlock[] Blocks { get; init; } = [];
    public Issue[] Issues { get; init; } = [];
    public bool Reviewed { get; init; }
    public string Source { get; init; } = "上传讲义";
    public string SourceId { get; init; } = "";
    public string SourceUrl { get; init; } = "";
    public string Region { get; init; } = "";
    public int AnswerLines { get; init; } = -1;
}
public sealed record ResearchRevision(int Revision, string Action, DateTimeOffset At, ResearchItem[] Items);
public sealed record ResearchDraft
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public Guid Owner { get; init; }
    public string Title { get; init; } = "新讲义";
    public string Subject { get; init; } = "数学";
    public string Stage { get; init; } = "初中";
    public string Grade { get; init; } = "七年级";
    public string Region { get; init; } = "山东";
    public string FileName { get; init; } = "";
    public string State { get; init; } = "ready";
    public string Message { get; init; } = "";
    public int Revision { get; init; }
    public DateTimeOffset UpdatedAt { get; init; } = Clock.Now;
    public ResearchTemplate Template { get; init; } = ResearchTemplates.BuiltIn[0];
    public Dictionary<string,string> Notes { get; init; } = [];
    public ResearchItem[] Items { get; init; } = [];
    public ResearchRevision[] History { get; init; } = [];
}
public record ResearchCreate(string Title, string Subject, string Stage, string Grade, string Region, string TemplateId = "standard");
public record ResearchEdit(int Revision, string Action, Guid? ItemId = null, ResearchItem? Item = null,
    string? Title = null, string? Region = null, ResearchTemplate? Template = null,
    Dictionary<string,string>? Notes = null, Guid[]? Order = null, int BlockIndex = 0,
    PdfRegion[]? Regions = null, Guid? OtherItemId = null, string? CandidateId = null);
public record ResearchSearch(Guid ItemId, string[] Knowledge, string Region, string Type = "", string Difficulty = "", int Page = 1);
public record ResearchCandidate(string Id, ResearchItem Item);
public record ResearchSearchResult(string Status, string Message, ResearchCandidate[] Items, int Page, bool HasMore);

public static class ResearchTemplates
{
    public static readonly ResearchTemplate[] BuiltIn = [
        new("standard", "标准课堂讲义", "思跃教育 · 教研室", "用心教研 · 扎实成长", "A4", 11, 18, 3, ["学习目标", "知识梳理", "例题讲解", "随堂练习", "课后作业"]),
        new("practice", "专题训练", "思跃教育 · 专题训练", "姓名：________  日期：________", "A4", 11, 18, 5, ["训练目标", "基础巩固", "能力提升", "拓展探究"]),
        new("book", "教材讲次", "思跃教育 · 校本教材", "教研室编制", "A4", 11, 20, 4, ["本讲目标", "知识导图", "典型例题", "方法总结", "分层练习", "课后作业"], true)
    ];
    public static void Validate(ResearchTemplate t)
    {
        Guard.Name(t.Name);
        Guard.That(t.Header.Length <= 120 && t.Footer.Length <= 120 && t.Paper is "A4" or "B5" &&
            t.FontSize is >= 9 and <= 18 && t.MarginMm is >= 10 and <= 30 && t.AnswerLines is >= 0 and <= 20 &&
            t.Sections is {Length: > 0 and <= 20} && t.Sections.Distinct().Count() == t.Sections.Length &&
            t.Sections.All(x => !string.IsNullOrWhiteSpace(x) && x.Length <= 40), "模板栏目或版式设置无效");
    }
}
