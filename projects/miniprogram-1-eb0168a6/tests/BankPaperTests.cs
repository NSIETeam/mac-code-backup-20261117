using System.Net;
using System.Net.Http.Json;
using System.IO.Compression;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Siyue;
using Xunit;

public sealed class BankPaperTests {
    sealed class TestFactory:Factory {
        protected override void ConfigureWebHost(IWebHostBuilder b){
            base.ConfigureWebHost(b);
            b.ConfigureAppConfiguration((_,c)=>c.AddInMemoryCollection(new Dictionary<string,string?>{["QuestionBank:UploadsDirectory"]=Path.Combine(Root,"bank")}));
            b.ConfigureServices(s=>{s.RemoveAll<IQuestionBank>();s.AddSingleton<IQuestionBank,TestBank>();});
        }
    }
    sealed class TestBank:IQuestionBank {
        public BankAvailability Availability()=>new(true,2,[],"fixture");
        public bool ContainsQuestion(BankQuestion q)=>true;
        public BankQueryResult Search(BankQuery q)=>throw new InvalidOperationException();
        public BankKnowledgeNode[] Knowledge(string subject,string query)=>[];
        public QuestionMatch[] Recommend(string subject,string stage,string stem,string[] tags,int limit,ISet<string> excluded){
            var physics=stem.Contains("斜率");var q=new BankQuestion{Id=physics?"slope":"distance",Subject=subject,Stage=stage,Type="填空题",Stem=physics?"已知直线斜率为 2，写出一个方向向量。":"点 P(2,3) 到直线 3x+4y−8=0 的距离为多少？",Answer=physics?"(1,2)":"2",Analysis=physics?"方向向量可取 (1,k)。":"代入距离公式，得到 $\\frac{10}{5}=2$。",Source="原创验收题",License="仅供演示",Diagram=physics?null:new(360,270,[new("line",[55,180,305,85]),new("line",[152,80,187,130],Dash:true),new("circle",[152,80,3]),new("text",[158,72],"P")])};
            return [new(q,1,["同类错题"])];
        }
    }
    [Fact] public async Task ClassDraftKeepsSeparatePapersAndExportsStudentAndTeacherVersions(){
        await using var f=new TestFactory();using var admin=await f.Login("paper-owner",true);
        var campus=new Campus{Name="演示校区"};var c=new SchoolClass{Name="高一验收班",CampusId=campus.Id};var course=new Course{Name="高中数学"};var a=new Student{Name="演示同学甲",Code="S0",ClassId=c.Id};var b=new Student{Name="演示同学乙",Code="S1",ClassId=c.Id};var empty=new Student{Name="演示同学丙",Code="S2",ClassId=c.Id};var lecture=new Lecture{CourseId=course.Id,Title="直线"};var handoutDoc=new Handout{LectureId=lecture.Id};var handout=handoutDoc.Id;var session=new ClassSession{ClassId=c.Id,CourseId=course.Id,HandoutId=handout,LessonDate="2026-11-03"};
        var q1=new Question{HandoutId=handout,BlocksJson=Json.Write(new[]{new ContentBlock("","点到直线的距离怎么计算？","text")})};var q2=new Question{HandoutId=handout,BlocksJson=Json.Write(new[]{new ContentBlock("","已知直线的斜率，求方向向量。","text")})};
        using(var scope=f.Services.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<Data>();session.StaffId=await db.Staff.Select(x=>x.Id).FirstAsync();db.AddRange(campus,c,course,lecture,handoutDoc,a,b,empty,session,q1,q2,new ClassSessionMark{ClassSessionId=session.Id,StudentId=a.Id,Reviewed=true,WrongIdsJson=Json.Write(new[]{q1.Id})},new ClassSessionMark{ClassSessionId=session.Id,StudentId=b.Id,Reviewed=true,WrongIdsJson=Json.Write(new[]{q2.Id})});await db.SaveChangesAsync();}
        var papers = Path.Combine(f.Root, "bank", "papers");
        string DraftFile(string directory, int age)
        {
            Directory.CreateDirectory(Path.Combine(papers, directory));
            var file = Path.Combine(papers, directory, "draft.json");
            File.WriteAllText(file, "{}");
            File.SetLastWriteTimeUtc(file, DateTime.UtcNow.AddHours(-age));
            return file;
        }
        var expired = DraftFile(Guid.NewGuid().ToString("N"), 49);
        var grace = DraftFile(Guid.NewGuid().ToString("N"), 25);
        var unrelated = DraftFile("unrelated", 100);
        var draft=await Factory.Post(admin,"/api/question-bank/papers",new BankPaperInput(c.Id,[course.Id]));Assert.Equal(3,draft.GetProperty("total").GetInt32());Assert.Equal(2,draft.GetProperty("generated").GetInt32());
        Assert.False(File.Exists(expired));
        Assert.True(File.Exists(grace));
        Assert.True(File.Exists(unrelated));
        var id=draft.GetProperty("id").GetGuid();var root="/api/question-bank/papers/"+id;
        var roster=draft.GetProperty("papers").EnumerateArray().ToArray();var i=Array.FindIndex(roster,p=>p.GetProperty("studentId").GetGuid()==b.Id);
        var second=await admin.GetFromJsonAsync<JsonElement>(root+"?index="+i);Assert.Equal("slope",second.GetProperty("questions")[0].GetProperty("id").GetString());
        var one=await Factory.Post(admin,"/api/question-bank/papers",new BankPaperInput(c.Id,[course.Id],a.Id));Assert.Equal(1,one.GetProperty("total").GetInt32());Assert.Equal("distance",one.GetProperty("questions")[0].GetProperty("id").GetString());
        using var anonymous=f.CreateClient();Assert.Equal(HttpStatusCode.Unauthorized,(await anonymous.GetAsync(root)).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict,(await admin.GetAsync(root+"/export?format=docx&studentId="+empty.Id)).StatusCode);
        async Task<byte[]> FileBytes(string query){using var response=await admin.GetAsync(root+"/export?"+query);Assert.True(response.IsSuccessStatusCode,response.StatusCode.ToString());return await response.Content.ReadAsByteArrayAsync();}
        static string Text(byte[] bytes){using var z=new ZipArchive(new MemoryStream(bytes));using var sr=new StreamReader(z.GetEntry("word/document.xml")!.Open());return sr.ReadToEnd();}
        var student=await FileBytes("format=docx");var teacher=await FileBytes("format=docx&version=teacher");
        Assert.DoesNotContain("参考答案",Text(student));Assert.Contains("参考答案",Text(teacher));Assert.DoesNotContain("演示同学",Text(student));Assert.DoesNotContain("演示同学",Text(teacher));Assert.Contains("卷号：",Text(student));Assert.DoesNotContain("演示同学丙",Text(student));Assert.Contains("w:type=\"page\"",Text(student));
        var individual=await FileBytes("format=docx&studentId="+b.Id);Assert.Contains($"卷号：{i+1:D2}",Text(individual));
        var output=Environment.GetEnvironmentVariable("SIYUE_BANK_PAPER_OUTPUT");if(!string.IsNullOrEmpty(output)){Directory.CreateDirectory(output);await File.WriteAllBytesAsync(Path.Combine(output,"student.docx"),student);await File.WriteAllBytesAsync(Path.Combine(output,"teacher.docx"),teacher);await File.WriteAllBytesAsync(Path.Combine(output,"student.pdf"),await FileBytes("format=pdf"));await File.WriteAllBytesAsync(Path.Combine(output,"teacher.pdf"),await FileBytes("format=pdf&version=teacher"));}
    }
}
