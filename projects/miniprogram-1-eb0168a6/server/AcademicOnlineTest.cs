using Microsoft.EntityFrameworkCore;
using System.Text;
using System.Text.RegularExpressions;
namespace Siyue;
public class AcademicOnlineTest:Entity { public string QuestionsJson {get;set;}="[]"; }
public record OnlineTestQuestion(string Label,string Stem);
public record OnlineTestAnswer(string Label,string Answer);
public record OnlineTestInput(OnlineTestQuestion[] Questions,string ReferenceAnswer,string Subject,string Stage);
public record OnlineAnswersInput(string Receipt,int Revision,string Name,string RequestKey,OnlineTestAnswer[] Answers,Guid? BindingId=null);
public partial class Data { public DbSet<AcademicOnlineTest> AcademicOnlineTests=>Set<AcademicOnlineTest>(); }
public static class AcademicOnlineTesting {
 public static void Configure(ModelBuilder b){b.Entity<AcademicOnlineTest>().HasOne<AcademicCollection>().WithOne().HasForeignKey<AcademicOnlineTest>(x=>x.Id).OnDelete(DeleteBehavior.Restrict);}
 const string ReferenceHeading = "\n教师参考答案：\n";
 const string ChoiceHeading = "选择题答案";
 static Dictionary<string, string>? ChoiceKey(string reference, OnlineTestQuestion[] questions)
 {
  var lines = reference.Trim().Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
  if (lines.Length == 0 || lines[0] != ChoiceHeading || lines.Length != questions.Length + 1) return null;
  var key = new Dictionary<string, string>(StringComparer.Ordinal);
  foreach (var line in lines.Skip(1))
  {
   var match = Regex.Match(line.Normalize(NormalizationForm.FormKC), @"\A([0-9]{1,2})\s*:\s*([A-Da-d])\z");
   if (!match.Success || !key.TryAdd(match.Groups[1].Value, match.Groups[2].Value.ToUpperInvariant())) return null;
  }
  return questions.All(q => key.ContainsKey(q.Label)) ? key : null;
 }

 internal static HomeworkGradeResult? GradeChoices(AcademicOnlineTest test, string reference, string answersJson)
 {
  var split = reference.LastIndexOf(ReferenceHeading, StringComparison.Ordinal);
  if (split < 0) return null;
  var questions = Json.Read<OnlineTestQuestion[]>(test.QuestionsJson);
  var key = ChoiceKey(reference[(split + ReferenceHeading.Length)..], questions);
  if (key == null) return null;
  var answers = Json.Read<OnlineTestAnswer[]>(answersJson).ToDictionary(a => a.Label, a => a.Answer);
  var grades = questions.Select(q => {
   var raw = answers.GetValueOrDefault(q.Label) ?? "";
   var value = raw.Trim().Normalize(NormalizationForm.FormKC).ToUpperInvariant();
   var valid = value.Length == 1 && value[0] is >= 'A' and <= 'D';
   var correct = valid && value == key[q.Label];
   return new HomeworkGradeQuestion(q.Label, raw, !valid ? "uncertain" : correct ? "correct" : "incorrect",
    valid ? correct ? 1 : 0 : null, 1, valid ? 1 : 0,
    valid ? "标准答案：" + key[q.Label] : "未识别到单选字母，请结合答卷复核。",
    [], q.Stem);
  }).ToArray();
  return new("rules", "single-choice", grades, []);
 }

 public static void Map(RouteGroupBuilder group){
  group.MapPut("/collections/{id:guid}/online-test",async(Guid id,OnlineTestInput r,Data db,Access a)=>{
   var row=await AcademicHomeworkCollection.Scope(id,db,a);Guard.That(row.State=="draft","已确认的测验不能修改，请新建测验",409);
   Guard.That(r.Questions is {Length:>0 and <=30}&&r.Questions.All(q=>q.Label is {Length:>0 and <=30}&&q.Stem is {Length:>0 and <=2000})&&r.Questions.Select(q=>q.Label).Distinct().Count()==r.Questions.Length,"请输入1–30道题，题号不能重复");
   Guard.That(r.ReferenceAnswer is {Length:>0 and <=12000}&&r.Subject is {Length:>0 and <=20}&&r.Stage is "小学" or "初中" or "高中","请填写参考答案、学科和学段");
   Guard.That(r.Questions.Sum(q=>q.Stem.Length)+r.ReferenceAnswer.Length<16000,"测验内容过长，请拆分为两份测验");
   if (r.ReferenceAnswer.TrimStart().StartsWith(ChoiceHeading, StringComparison.Ordinal))
    Guard.That(ChoiceKey(r.ReferenceAnswer, r.Questions) != null, "选择题答案请逐行填写题号和 A–D，且须与全部题号对应");
   var test=await db.AcademicOnlineTests.SingleOrDefaultAsync(x=>x.Id==id);if(test==null){test=new(){Id=id};db.AcademicOnlineTests.Add(test);}test.QuestionsJson=Json.Write(r.Questions);
   var policy=await db.AcademicHomeworkPolicies.SingleOrDefaultAsync(x=>x.Id==id);if(policy==null){policy=new(){Id=id};db.AcademicHomeworkPolicies.Add(policy);}policy.ReferenceAnswer=string.Join("\n",r.Questions.Select(q=>q.Label+"："+q.Stem))+ReferenceHeading+r.ReferenceAnswer;policy.Subject=r.Subject;policy.Stage=r.Stage;policy.Enabled=true;policy.UpdatedBy=a.User.Id;policy.Revision++;
   row.Revision++;await db.SaveChangesAsync();return Results.Ok(new{revision=row.Revision,questions=r.Questions,requiresAcademicApproval=true});
  });
 }
}
