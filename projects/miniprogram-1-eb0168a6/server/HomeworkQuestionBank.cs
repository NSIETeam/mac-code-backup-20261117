namespace Siyue;
// Homework-only supplement: does not change legacy question lookup or printing.
public sealed class HomeworkQuestionBank {
 readonly IQuestionBank main;readonly LocalQuestionBank supplement;
 public HomeworkQuestionBank(IQuestionBank main,IConfiguration config,IWebHostEnvironment env,ILogger<LocalQuestionBank> log){this.main=main;supplement=new(new ConfigurationBuilder().AddConfiguration(config).AddInMemoryCollection(new Dictionary<string,string?>{["QuestionBank:Directory"]=Path.Combine(env.ContentRootPath,"HomeworkQuestionBankData")}).Build(),env,log);}
 public BankAvailability Availability(){var a=main.Availability();var b=supplement.Availability();return new(a.Available||b.Available,a.Count+b.Count,a.Coverage.Concat(b.Coverage).ToArray(),a.Method);}
 public QuestionMatch[] Recommend(string subject,string stage,string stem,string[] tags,int limit,ISet<string> excluded)=>main.Recommend(subject,stage,stem,tags,limit,excluded).Concat(supplement.Recommend(subject,stage,stem,tags,limit,excluded)).DistinctBy(m=>LocalQuestionBank.Fingerprint(subject,m.Question.Stem,m.Question.MaterialId)).Take(limit).ToArray();
}
