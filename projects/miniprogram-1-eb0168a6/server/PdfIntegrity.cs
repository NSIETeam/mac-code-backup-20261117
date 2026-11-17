using System.Text.RegularExpressions;
using System.Xml.Linq;
namespace Siyue;
public partial class WordDocuments
{
    public static readonly string[] PdfReviewableIssues = ["pdf-source-review","pdf-number-gap","pdf-duplicate-number","pdf-cross-page","pdf-multiple-labels","pdf-contextual-label","pdf-formula-review","pdf-ai-review"];
    static bool IsAiLayout(PdfProvenance? origin) => origin?.Engine == "ai-layout";
    static string? AiCheckValue(PdfProvenance origin, string prefix) {
        var matches = (origin.Checks ?? []).Where(check => check != null && check.StartsWith(prefix, StringComparison.Ordinal)).ToArray();
        return matches.Length == 1 ? matches[0][prefix.Length..] : null;
    }
    static string? AiRegionId(PdfProvenance origin) {
        var id = AiCheckValue(origin, "ai-id:");
        var match = Regex.Match(id ?? "", @"^p(?<page>[1-9][0-9]{0,5})-r(?<region>[1-9][0-9]?)$");
        return match.Success && int.TryParse(match.Groups["page"].Value, out var page) && page == origin.Page
            && int.TryParse(match.Groups["region"].Value, out var region) && region <= AiHandoutLayout.MaxRegions ? id : null;
    }
    static bool AiLayoutIntegrity(ContentBlock[] blocks, List<Issue> issues) {
        var xmls = blocks.Select(block => XElement.Parse(block.Xml)).ToArray();
        var origins = xmls.Select(PdfOrigin).ToArray();
        if (!origins.Any(IsAiLayout)) return false;
        var verified = origins.All(origin => IsAiLayout(origin) && (origin!.Checks ?? []).Contains("ai-verified"));
        var ids = new HashSet<string>(StringComparer.Ordinal); string? rootId = null; var rootPage = 0; var previousPage = 0;
        issues.Add(new("pdf-ai-layout", "AI已划分完整题目区域，显示和打印保留原稿题图；尚需教师确认发布。", false));
        for (var index = 0; index < blocks.Length; index++) {
            var xml = xmls[index]; var origin = origins[index];
            if (!IsAiLayout(origin)) { verified = false; continue; }
            var id = AiRegionId(origin!); var description = (string?)xml.Descendants(WP + "docPr").FirstOrDefault()?.Attribute("descr");
            if (id == null || !ids.Add(id)) { verified = false; issues.Add(new("pdf-ai-identity", "AI题目区域标识缺失、无效或重复，请对照原稿重新划分。", true)); }
            if (!IsPdfRaster(xml) || ImageReferences(blocks[index].Xml).Length != 1 || origin!.Bounds == null || origin.Bounds.Length != 4
                || origin.Bounds.Any(value => !double.IsFinite(value) || value is < 0 or > 1)
                || origin.Bounds[0] >= origin.Bounds[2] || origin.Bounds[1] >= origin.Bounds[3]) {
                verified = false; issues.Add(new("pdf-ai-boundary", "AI题目区域缺少完整原图或有效边界，请重新划分。", true));
            }
            if (index == 0 && description == "question") {
                rootId = id; rootPage = origin!.Page;
                if ((origin.Checks ?? []).Any(check => check?.StartsWith("ai-continuation:", StringComparison.Ordinal) == true)) {
                    verified = false; issues.Add(new("pdf-ai-boundary", "独立题目不应携带续题关联，请核对题目边界。", true));
                }
            } else if (description == "continuation") {
                if (rootId == null || AiCheckValue(origin!, "ai-continuation:") != rootId || origin!.Page <= rootPage || origin.Page < previousPage) {
                    verified = false; issues.Add(new("pdf-ai-continuation", "续题关联与本题不一致或顺序错误，已保留原图，请调整跨页归属。", true));
                }
            } else { verified = false; issues.Add(new("pdf-ai-boundary", "本题内存在额外的独立题目或缺少边界标记，请重新划分。", true)); }
            previousPage = origin!.Page;
        }
        if (!verified || origins.Any(origin => IsAiLayout(origin) && (origin!.Checks ?? []).Contains("ai-review")))
            issues.Add(new("pdf-ai-review", "AI版面计划仍有疑点或校验尚未完整通过，请核对题目、图形和讲次归属。", true));
        return verified;
    }
    public static void RefreshPdfContentIssues(Question question) {
        var derived=new HashSet<string>(["pdf-source-review","pdf-merged-region","pdf-contextual-label","pdf-orphan-prefix","pdf-cross-page","pdf-multiple-labels","pdf-page-review","pdf-formula-review","pdf-model-assisted","pdf-ai-layout","pdf-ai-review","pdf-ai-identity","pdf-ai-continuation","pdf-ai-boundary"]);
        question.IssuesJson=Json.Write(Json.Read<Issue[]>(question.IssuesJson).Where(i=>!derived.Contains(i.Code)));
        AddPdfIntegrityIssues([question]);
        if(Json.Read<ContentBlock[]>(question.BlocksJson).Any(b=>IsPdfReview(XElement.Parse(b.Xml))))
            question.IssuesJson=Json.Write(Json.Read<Issue[]>(question.IssuesJson).Append(new Issue("pdf-page-review","原页仍有待框选内容，请逐块核对。",true)));
    }
    static void AddPdfIntegrityIssues(List<Question> questions) {
        var previous=new Dictionary<string,int>();
        var seen=new HashSet<(string,int)>();
        foreach(var q in questions) {
            var blocks=Json.Read<ContentBlock[]>(q.BlocksJson);
            var origins=blocks.Select(b=>PdfOrigin(XElement.Parse(b.Xml))).Where(x=>x!=null).ToArray();
            if(origins.Length==0)continue;
            var issues=Json.Read<Issue[]>(q.IssuesJson).ToList();
            var aiVerified = origins.Any(IsAiLayout) && AiLayoutIntegrity(blocks, issues);
            var formula=blocks.Select(b=>FormulaRecognition.Evidence(XElement.Parse(b.Xml))).Where(x=>x!=null).ToArray();
            if(formula.Length>0 && (!aiVerified || formula.Any(x=>x!.Disagreement)))issues.Add(new("pdf-formula-review",formula.Any(x=>x!.Disagreement)?"公式识别结果有分歧或服务未完成，请重点核对分式、根式、上下标；显示与打印保留原图。":"已完成多路公式识别，仍请对照原图核对分式、根式与上下标。",true));
            if(origins.Any(x=>x!.Engine.Contains("model-review")))issues.Add(new("pdf-model-assisted","模型已辅助复核题号和讲次，原图完整保留；仍需对照原稿校对。",false));
            if(!aiVerified && origins.Any(x=>x!.Checks.Contains("source-review")))issues.Add(new("pdf-source-review","本题使用后备文字识别，原图已保留。请核对题号、题目数量和图文边界后确认；也可在批量校对中统一确认。",true));
            if(origins.Any(x=>x!.Checks.Contains("merged-region")))issues.Add(new("pdf-merged-region","此区域含多道题，图形跨越题目边界；已完整保留共用图形，请使用框选拆题调整，其他题目可继续校对。",true));
            if(!aiVerified && origins.Any(x=>x!.Checks.Contains("contextual-label")))issues.Add(new("pdf-contextual-label","此题号根据位置和编号序列推断，请确认它是独立题目而非题内小问。",true));
            if(origins.Any(x=>x!.Checks.Contains("orphan-prefix")))issues.Add(new("pdf-orphan-prefix","这部分位于题号之前，无法确认属于上一题还是说明文字，请框选归属或排除。",true));
            if(origins.Select(x=>x!.Page).Distinct().Count()>1)issues.Add(new("pdf-cross-page",aiVerified ? "本题已按明确的原稿关联合并跨页内容，题图完整保留。" : "本题跨越原稿页面，请核对衔接处是否缺少条件或混入下一题。",!aiVerified));
            if(!aiVerified && blocks.Any(b=>b.Text.Split('\n').Count(line => QuestionRecognition.NamedStart.IsMatch(q.Label) ? QuestionRecognition.NamedStart.IsMatch(line) : IsQuestionStart(line))>1))issues.Add(new("pdf-multiple-labels","同一内容块出现多个题号，请检查是否需要框选拆题。",true));
            var n=Regex.Match(q.Label.Normalize(System.Text.NormalizationForm.FormKC),@"(?<!\d)\d{1,3}(?!\d)");
            if(!aiVerified && n.Success && !Regex.IsMatch(q.Label,@"[-－﹣—]")) {
                var number=int.Parse(n.Value);
                if(previous.TryGetValue(q.Family,out var last) && number>last+1)issues.Add(new("pdf-number-gap",$"题号由 {last} 跳到 {number}，请对照原稿确认中间题目是否遗漏。",true));
                if(!seen.Add((q.Family,number)))issues.Add(new("pdf-duplicate-number","本节出现重复题号，请确认归属或调整题号。",true));
                previous[q.Family]=number;
            }
            q.IssuesJson=Json.Write(issues.Distinct());
        }
    }
}
