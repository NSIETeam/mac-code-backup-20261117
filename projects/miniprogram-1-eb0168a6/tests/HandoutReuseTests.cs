using System.Text;
using System.Xml.Linq;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Siyue;
using Xunit;

public class HandoutReuseTests
{
    sealed class Objects : IObjects
    {
        public Dictionary<string,byte[]> Data = new();
        public Task<Stream> Get(string key, CancellationToken ct = default) => Task.FromResult<Stream>(new MemoryStream(Data.TryGetValue(key, out var value) ? value : throw new FileNotFoundException(key)));
        public async Task Put(string key, Stream input, string type, CancellationToken ct = default) { using var output = new MemoryStream(); await input.CopyToAsync(output,ct); Data[key] = output.ToArray(); }
        public void File(string key, string content = "bytes") => Data[key] = Encoding.UTF8.GetBytes(content);
    }
    sealed class Fixture : IAsyncDisposable
    {
        public SqliteConnection Connection = new("Data Source=:memory:"); public Data Db = null!; public Objects Objects = new();
        public Staff Teacher = new() { Name = "测试教师" }; public Course Course = new() { Name = "数学" }; public Campus Campus = new() { Name = "测试校区" };
        public SchoolClass SourceClass = null!, TargetClass = null!; public BookImport Book = null!; public Handout[] H = []; public Question[] Q = [];
        public static async Task<Fixture> Create()
        {
            var f = new Fixture(); await f.Connection.OpenAsync(); f.Db = new(new DbContextOptionsBuilder<Data>().UseSqlite(f.Connection).Options); await f.Db.Database.EnsureCreatedAsync();
            f.SourceClass = new() { Name = "源班", CampusId = f.Campus.Id }; f.TargetClass = new() { Name = "目标班", CampusId = f.Campus.Id };
            f.Db.AddRange(f.Teacher,f.Course,f.Campus,f.SourceClass,f.TargetClass);
            f.Book = new() { StaffId=f.Teacher.Id,ClassId=f.SourceClass.Id,CourseId=f.Course.Id,FileName="原讲义.pdf",OriginalKey="book/original.pdf",NormalizedKey="book/normalized.docx",State="split",RecognitionMode="legacy",BlockCount=2 };
            f.Objects.File(f.Book.OriginalKey,"unique original bytes"); f.Objects.File(f.Book.NormalizedKey);
            var hs = new List<Handout>(); var qs = new List<Question>(); var ss = new List<BookSection>();
            for (var i=0;i<2;i++) {
                var l=new Lecture {ClassId=f.SourceClass.Id,CourseId=f.Course.Id,Sequence=i+1,Title="讲次"+(i+1)};
                var h=new Handout {LectureId=l.Id,Version=1,State="published",RecognitionMode="legacy",OriginalKey=$"h{i}/original.docx",NormalizedKey=$"h{i}/normalized.docx",SourcePdfKey=f.Book.OriginalKey,FileName=$"第{i+1}讲.docx"};
                var xml=new XElement(WordDocuments.W+"p",new XElement(WordDocuments.W+"r",new XElement(WordDocuments.W+"t","例1 原题")));
                if(i==0)xml.Add(new XElement(XName.Get("blip","http://schemas.openxmlformats.org/drawingml/2006/main"),new XAttribute(WordDocuments.R+"embed","rId1")));
                var q=new Question {HandoutId=h.Id,Order=1,Label="例1",Family="example",Confirmed=true,BlocksJson=Json.Write(new[]{WordDocuments.Block(xml)}),PreviewsJson=Json.Write(new[]{$"p{i}.png"}),IssuesJson=Json.Write(new[]{new Issue("source-note","保留原疑点",true)})};
                f.Db.AddRange(l,h,q);hs.Add(h);qs.Add(q);ss.Add(new(i,i+1,i+1,l.Title,"lesson",h.Id));f.Objects.File(h.OriginalKey);f.Objects.File(h.NormalizedKey);f.Objects.File($"p{i}.png");
                if(i==0){var a=new Asset {HandoutId=h.Id,RelationshipId="rId1",ObjectKey="asset.png",ContentType="image/png"};f.Db.Add(a);f.Db.Add(new QuestionAsset {QuestionId=q.Id,AssetId=a.Id,BlockIndex=0,Occurrence=0,Width=100,Height=100});f.Objects.File(a.ObjectKey);}
            }
            f.H=hs.ToArray();f.Q=qs.ToArray();f.Book.SectionsJson=Json.Write(ss);f.Db.Add(f.Book);await f.Db.SaveChangesAsync();return f;
        }
        public BookImport Target() => new(){StaffId=Teacher.Id,ClassId=TargetClass.Id,CourseId=Course.Id,FileName="重命名.pdf",RecognitionMode="named"};
        public async Task<string> Hash() {await using var s=await Objects.Get(Book.OriginalKey);return await HandoutReuse.Hash(s);}
        public async ValueTask DisposeAsync(){await Db.DisposeAsync();await Connection.DisposeAsync();}
    }
    [Fact]
    public async Task HistoricalWholeBookCloneSkipsAllJobsAndKeepsIndependentIdentityAndOriginalMode()
    {
        await using var f=await Fixture.Create();var indexed=await HandoutReuse.IndexExisting(f.Db,f.Objects);Assert.Equal(1,indexed.Cached);
        var result=await HandoutReuse.TryBook(f.Db,f.Objects,f.Target(),await f.Hash(),false);Assert.NotNull(result);Assert.Equal("legacy",result!.Book.RecognitionMode);Assert.Equal(2,result.Reuse.ReusedLectures);Assert.Equal(2,result.Reuse.ReusedQuestions);
        var sections=Json.Read<BookSection[]>(result.Book.SectionsJson);Assert.DoesNotContain(sections,s=>f.H.Any(h=>h.Id==s.HandoutId));
        var ids=sections.Select(s=>s.HandoutId!.Value).ToArray();var qs=await f.Db.Questions.Where(q=>ids.Contains(q.HandoutId)).ToArrayAsync();Assert.Equal(2,qs.Length);Assert.All(qs,q=>{Assert.False(q.Confirmed);Assert.Equal(f.Q[0].IssuesJson,q.IssuesJson);Assert.DoesNotContain(f.Q,x=>x.Id==q.Id);});
        Assert.All(await f.Db.Handouts.Where(h=>ids.Contains(h.Id)).ToArrayAsync(),h=>Assert.Equal("draft",h.State));
        var links=await f.Db.QuestionAssets.Where(a=>qs.Select(q=>q.Id).Contains(a.QuestionId)).ToArrayAsync();Assert.Single(links);Assert.NotEqual((await f.Db.QuestionAssets.SingleAsync(a=>a.QuestionId==f.Q[0].Id)).AssetId,links[0].AssetId);
        Assert.Empty(await f.Db.Work.ToArrayAsync());Assert.Empty(await f.Db.ClassSessions.ToArrayAsync());Assert.Empty(await f.Db.ClassSessionMarks.ToArrayAsync());Assert.Empty(await f.Db.WrongQuestions.ToArrayAsync());Assert.True(f.Q[0].Confirmed);
        var again=await HandoutReuse.TryBook(f.Db,f.Objects,f.Target(),await f.Hash(),false);Assert.Equal(result.Book.Id,again!.Book.Id);Assert.Equal(2,await f.Db.BookImports.CountAsync());
        var back=f.Target();back.ClassId=f.SourceClass.Id;var returnToSource=await HandoutReuse.TryBook(f.Db,f.Objects,back,await f.Hash(),false);
        Assert.Equal(f.Book.Id,returnToSource!.Book.Id);Assert.Equal(2,await f.Db.BookImports.CountAsync());Assert.Equal(4,await f.Db.Handouts.CountAsync());Assert.Equal(4,await f.Db.Questions.CountAsync());
    }
    [Fact]
    public async Task SameClassReturnsExistingBookButForceAndDifferentBytesDoNotReuse()
    {
        await using var f=await Fixture.Create();await HandoutReuse.IndexExisting(f.Db,f.Objects);var target=f.Target();target.ClassId=f.SourceClass.Id;
        var result=await HandoutReuse.TryBook(f.Db,f.Objects,target,await f.Hash(),false);Assert.Equal(f.Book.Id,result!.Book.Id);Assert.Single(await f.Db.BookImports.ToArrayAsync());
        Assert.Null(await HandoutReuse.TryBook(f.Db,f.Objects,f.Target(),await f.Hash(),true));
        Assert.Null(await HandoutReuse.TryBook(f.Db,f.Objects,f.Target(),new string('a',64),false));
    }
    [Theory]
    [InlineData("failed")][InlineData("preview")][InlineData("revision")][InlineData("asset-link")]
    public async Task ChangedFailedIncompleteOrDamagedSourceCannotBeReused(string change)
    {
        await using var f=await Fixture.Create();await HandoutReuse.IndexExisting(f.Db,f.Objects);
        if(change=="failed")f.H[0].State="failed";
        if(change=="preview")f.Q[0].PreviewsJson="[]";
        if(change=="revision")f.H[0].Revision++;
        if(change=="asset-link")f.Db.QuestionAssets.Remove(await f.Db.QuestionAssets.SingleAsync());
        await f.Db.SaveChangesAsync();Assert.Null(await HandoutReuse.TryBook(f.Db,f.Objects,f.Target(),await f.Hash(),false));Assert.Single(await f.Db.BookImports.ToArrayAsync());
    }
    [Fact]
    public async Task MissingPreviewObjectAndUnsplitBookAreNotIndexedAsUsable()
    {
        await using var f=await Fixture.Create();f.Objects.Data.Remove("p0.png");var result=await HandoutReuse.IndexExisting(f.Db,f.Objects);Assert.Equal(0,result.Cached);Assert.Equal(1,result.Missing);
        f.Book.State="review";await f.Db.SaveChangesAsync();result=await HandoutReuse.IndexExisting(f.Db,f.Objects);Assert.Equal(1,result.Invalid);Assert.Equal(0,result.Cached);
    }
    [Fact]
    public async Task SingleHandoutReuseClonesAssetsButNeverStudentOrPublicationState()
    {
        await using var f=await Fixture.Create();f.Book.SectionsJson="[]";await f.Db.SaveChangesAsync();f.Objects.File(f.H[0].OriginalKey,"standalone-one");f.Objects.File(f.H[1].OriginalKey,"standalone-two");
        var index=await HandoutReuse.IndexExisting(f.Db,f.Objects);Assert.Equal(2,index.Cached);Assert.Equal(1,index.Invalid);
        var lecture=new Lecture{ClassId=f.TargetClass.Id,CourseId=f.Course.Id,Sequence=1,Title="目标讲"};f.Db.Lectures.Add(lecture);await f.Db.SaveChangesAsync();
        var h=new Handout{LectureId=lecture.Id,FileName="改名.docx",RecognitionMode="named",Version=1};string hash;await using(var s=await f.Objects.Get(f.H[0].OriginalKey))hash=await HandoutReuse.Hash(s);
        Assert.Null(await HandoutReuse.TryHandout(f.Db,f.Objects,h,f.Teacher.Id,hash,true));
        var summary=await HandoutReuse.TryHandout(f.Db,f.Objects,h,f.Teacher.Id,hash,false);Assert.NotNull(summary);Assert.Equal("legacy",h.RecognitionMode);Assert.Equal("draft",h.State);
        var q=await f.Db.Questions.SingleAsync(q=>q.HandoutId==h.Id);Assert.False(q.Confirmed);Assert.Equal(f.Q[0].BlocksJson,q.BlocksJson);Assert.Equal(f.Q[0].PreviewsJson,q.PreviewsJson);
        Assert.NotEqual((await f.Db.Assets.SingleAsync(a=>a.HandoutId==f.H[0].Id)).Id,(await f.Db.Assets.SingleAsync(a=>a.HandoutId==h.Id)).Id);
        var summaries=await HandoutReuse.Summaries(f.Objects,new[]{("handout",h.Id),("book",Guid.NewGuid())});Assert.Equal("reused",Assert.Single(summaries).Value.State);Assert.Empty(await f.Db.Work.ToArrayAsync());
    }
    [Fact]
    public async Task WholeBookIndexIsNotTreatedAsASingleLectureAndUnknownSummaryKindsAreIgnored()
    {
        await using var f=await Fixture.Create();await HandoutReuse.IndexExisting(f.Db,f.Objects);
        var target=new Handout{LectureId=f.H[0].LectureId,FileName="同原件.pdf",RecognitionMode="named",Version=2};
        Assert.Null(await HandoutReuse.TryHandout(f.Db,f.Objects,target,f.Teacher.Id,await f.Hash(),false));
        Assert.Null(await HandoutReuse.Summary(f.Objects,"../content",f.Book.Id));Assert.Empty(await HandoutReuse.Summaries(f.Objects,new[]{("other",f.Book.Id)}));
    }
    [Fact]
    public async Task OversizedButStructurallyValidSnapshotIsSkippedBeforeLoadingQuestionBodies()
    {
        await using var f=await Fixture.Create();var xml=new XElement(WordDocuments.W+"p",new XElement(WordDocuments.W+"r",new XElement(WordDocuments.W+"t",new string('x',8*1024*1024))));
        f.Q[1].BlocksJson=Json.Write(new[]{WordDocuments.Block(xml)});await f.Db.SaveChangesAsync();
        var index=await HandoutReuse.IndexExisting(f.Db,f.Objects);Assert.Equal(0,index.Cached);Assert.Equal(1,index.Invalid);
        Assert.Null(await HandoutReuse.TryBook(f.Db,f.Objects,f.Target(),await f.Hash(),false));
    }
    [Fact]
    public async Task OriginalClassIsStillReusedAfterMoreThanSixteenOtherClasses()
    {
        await using var f=await Fixture.Create();await HandoutReuse.IndexExisting(f.Db,f.Objects);var hash=await f.Hash();
        for(var i=0;i<17;i++) {
            var schoolClass=new SchoolClass{Name="复用班"+i,CampusId=f.Campus.Id};f.Db.Classes.Add(schoolClass);await f.Db.SaveChangesAsync();
            var target=f.Target();target.ClassId=schoolClass.Id;Assert.NotNull(await HandoutReuse.TryBook(f.Db,f.Objects,target,hash,false));
        }
        var index=Assert.Single(f.Objects.Data.Where(p=>p.Key.Contains("/content/")));Assert.Equal(16,System.Text.Json.JsonDocument.Parse(index.Value).RootElement.GetArrayLength());
        var original=f.Target();original.ClassId=f.SourceClass.Id;var reused=await HandoutReuse.TryBook(f.Db,f.Objects,original,hash,false);
        Assert.Equal(f.Book.Id,reused!.Book.Id);Assert.Equal(18,await f.Db.BookImports.CountAsync());Assert.Equal(36,await f.Db.Handouts.CountAsync());Assert.Equal(36,await f.Db.Questions.CountAsync());
    }
}
