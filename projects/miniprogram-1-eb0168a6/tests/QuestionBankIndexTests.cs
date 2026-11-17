using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Logging.Abstractions;
using Siyue;
using Xunit;

public sealed class QuestionBankIndexTests
{
    sealed class Env : IWebHostEnvironment {
        public string ApplicationName{get;set;}="tests";public string EnvironmentName{get;set;}="Testing";public string ContentRootPath{get;set;}=AppContext.BaseDirectory;public IFileProvider ContentRootFileProvider{get;set;}=new NullFileProvider();public string WebRootPath{get;set;}="";public IFileProvider WebRootFileProvider{get;set;}=new NullFileProvider();
    }
    static LocalQuestionBank Bank(string? directory=null,bool indexed=true)=>new(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?>{["QuestionBank:Directory"]=directory??Path.Combine(AppContext.BaseDirectory,"QuestionBankData"),["QuestionBank:PrebuiltIndexes"]=indexed.ToString()}).Build(),new Env(),NullLogger<LocalQuestionBank>.Instance);
    [Fact]
    public void PrebuiltIndexesPreserveRankingAnswersDiagramsAndExclusions()
    {
        var indexed=Bank();var raw=Bank(indexed:false);
        foreach(var query in new[]{new BankQuery("数学","高中","点线距离","填空题"),new BankQuery("数学","高中","立体几何"),new BankQuery("物理","高中","串联电路"),new BankQuery("化学","高中","物质的量浓度"),new BankQuery("生物","高中","光合作用"),new BankQuery("地理","高中","地方时"),new BankQuery("语文","高中","凋凌")}) {
            var expected=raw.Search(query);var actual=indexed.Search(query);Assert.NotEmpty(expected.Matches);
            Assert.Equal(expected.Matches.Select(m=>m.Question.Id),actual.Matches.Select(m=>m.Question.Id));Assert.Equal(expected.Matches.Select(m=>m.Question.Answer),actual.Matches.Select(m=>m.Question.Answer));
            Assert.Equal(expected.Matches.Select(m=>m.Question.MaterialText),actual.Matches.Select(m=>m.Question.MaterialText));
            Assert.Equal(expected.Matches.Select(m=>JsonSerializer.Serialize(m.Question)),actual.Matches.Select(m=>JsonSerializer.Serialize(m.Question)));
            var exclude=actual.Matches.Select(m=>m.Question.Id).ToArray();Assert.Equal(raw.Search(query with{ExcludeIds=exclude}).Matches.Select(m=>m.Question.Id),indexed.Search(query with{ExcludeIds=exclude}).Matches.Select(m=>m.Question.Id));
        }
    }
    [Fact]
    public void IndexLoadIsSelectiveAndCorruptDetailsCannotBeServed()
    {
        var root=Path.Combine(Path.GetTempPath(),"bank-index-test-"+Guid.NewGuid());Directory.CreateDirectory(Path.Combine(root,"expanded/questions"));
        try{
            File.WriteAllText(Path.Combine(root,"knowledge-rules.json"),"{}");
            object Part(string file,object value,int count=0){var bytes=JsonSerializer.SerializeToUtf8Bytes(value,new JsonSerializerOptions(JsonSerializerDefaults.Web));File.WriteAllBytes(Path.Combine(root,"expanded",file),bytes);return new{file,count,sha256=Convert.ToHexStringLower(SHA256.HashData(bytes))};}
            var knowledge=Part("knowledge.json",new{nodes=Array.Empty<object>()});
            BankQuestion Q(string id,string text)=>new(){Id=id,Subject="数学",Stage="高中",Type="填空题",Stem=text,Answer="1",Tags=[text],Source="测试",License="原创"};
            var a=Part("questions/math-000.json",new[]{Q("q1","青桥代数甲")},1);var b=Part("questions/math-001.json",new[]{Q("q2","翠岚代数乙")},1);
            File.WriteAllText(Path.Combine(root,"expanded/manifest.json"),JsonSerializer.Serialize(new{schema="siyue.server-bank/v2",count=2,subjects=new[]{new{subject="数学",stage="高中",count=2,parts=new[]{a,b}}},materials=Array.Empty<object>(),knowledge}));
            Bank(root).BuildSearchIndexes();
            File.AppendAllText(Path.Combine(root,"expanded/questions/math-001.json")," ");
            var fast=Bank(root);Assert.Equal(1,fast.Search(new("数学","高中","青桥代数甲")).Returned);
            Assert.Equal("unavailable",fast.Search(new("数学","高中","翠岚代数乙")).Status);
            Assert.Equal("unavailable",Bank(root,indexed:false).Search(new("数学","高中","青桥代数甲")).Status);
        }finally{Directory.Delete(root,true);}
    }
}
