using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public partial class AcademicBetaTests
{
    static string TrainingPath(Fixture x) => $"{Api}/training/classes/{x.ClassId}/courses/{x.CourseId}";
    static async Task<Fixture> TrainingSeed(BetaFactory f)
    {
        var x = await Seed(f); using var scope = f.Services.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<Data>();
        (await db.Classes.FindAsync(x.ClassId))!.Name = "高一甲班";
        (await db.Questions.FindAsync(x.QuestionId))!.BlocksJson = Json.Write(new[] { new ContentBlock("", "已知函数的导数，求函数的单调区间与极值。", "paragraph") });
        (await db.Questions.FindAsync(x.OtherQuestionId))!.BlocksJson = Json.Write(new[] { new ContentBlock("", "已知复数的共轭，求复数的模及虚部。", "paragraph") });
        var second = db.ClassSessionMarks.Single(m => m.ClassSessionId == x.SessionId && m.StudentId == x.SecondStudentId); second.WrongIdsJson = Json.Write(new[] { x.OtherQuestionId });
        var session = await db.ClassSessions.FindAsync(x.SessionId);
        var previous = new ClassSession { ClassId = x.ClassId, CourseId = x.CourseId, HandoutId = session!.HandoutId, StaffId = f.AuthorId, LessonDate = "2026-10-20" };
        db.ClassSessions.Add(previous);db.ClassSessionMarks.Add(new() { ClassSessionId = previous.Id, StudentId = x.StudentId, Reviewed = true, WrongIdsJson = Json.Write(new[] { x.QuestionId }), RecordedBy = f.AuthorId });
        await db.SaveChangesAsync();return x;
    }
    [Fact]
    public async Task ClassTrainingBatchIsPersonalizedAndMatchesSingleStudentGenerationWithoutBusinessWrites()
    {
        await using var f = new BetaFactory();var x = await TrainingSeed(f);using var client = await f.Login("beta-author");
        var before = await OriginalBusinessSnapshot(f);
        var result = await Factory.Post(client, TrainingPath(x) + "/generate", new { maxQuestions = 4 });
        var rows = result.GetProperty("students");Assert.Equal(4,rows.GetArrayLength());
        Assert.Equal("高中",result.GetProperty("stage").GetString());Assert.Equal("all_reviewed_class_course_errors",result.GetProperty("scope").GetString());
        var first = StudentRow(rows,x.StudentId);var second = StudentRow(rows,x.SecondStudentId);
        Assert.NotEmpty(first.GetProperty("questions").EnumerateArray());Assert.NotEmpty(second.GetProperty("questions").EnumerateArray());
        Assert.All(first.GetProperty("questions").EnumerateArray(),q => {Assert.Equal(x.QuestionId,q.GetProperty("origin").GetProperty("questionId").GetGuid());Assert.Equal(2,q.GetProperty("origin").GetProperty("occurrences").GetInt32());});
        Assert.All(second.GetProperty("questions").EnumerateArray(),q => Assert.Equal(x.OtherQuestionId,q.GetProperty("origin").GetProperty("questionId").GetGuid()));
        foreach(var row in rows.EnumerateArray()) {
            var exercises = row.GetProperty("questions").EnumerateArray().ToArray();Assert.InRange(exercises.Length,0,4);
            var ids = exercises.Select(q=>q.GetProperty("question").GetProperty("id").GetString()).ToArray();Assert.Equal(ids.Length,ids.Distinct().Count());
        }
        Assert.Equal("no_wrong",StudentRow(rows,x.ThirdStudentId).GetProperty("status").GetString());
        Assert.Equal("unreviewed",StudentRow(rows,x.UnreviewedStudentId).GetProperty("status").GetString());
        var single = await Factory.Post(client,TrainingPath(x)+"/generate",new {studentId=x.StudentId,maxQuestions=4});
        Assert.Single(single.GetProperty("students").EnumerateArray());
        Assert.Equal(first.GetProperty("questions").GetRawText(),single.GetProperty("students")[0].GetProperty("questions").GetRawText());
        Assert.Equal(before,await OriginalBusinessSnapshot(f));
    }
    [Fact]
    public async Task ClassTrainingRosterUsesAllReviewedHistoryAndOnlyTheAuthorizedCourse()
    {
        await using var f = new BetaFactory();var x = await TrainingSeed(f);using var client = await f.Login("beta-author");
        var classes = await Get(client,Api+"/training/classes");Assert.Single(classes.EnumerateArray());Assert.Equal(x.ClassId,classes[0].GetProperty("classId").GetGuid());
        var roster = await Get(client,TrainingPath(x));var student = StudentRow(roster,x.StudentId);
        Assert.Equal(1,student.GetProperty("wrongCount").GetInt32());Assert.Equal(2,student.GetProperty("reviewedRecords").GetInt32());
        Assert.DoesNotContain(roster.GetProperty("students").EnumerateArray(),r=>r.GetProperty("studentId").GetGuid()==x.ForeignStudentId);
        Assert.Equal("高中",roster.GetProperty("suggestedStage").GetString());
    }
    [Fact]
    public async Task ClassTrainingHonorsPilotClassCourseAndStudentPermissions()
    {
        foreach(var enabled in new[]{false,true}){
            await using var f = new BetaFactory(enabled);var x = await Seed(f);using var client = await f.Login(enabled?"beta-outsider":"beta-author");
            Assert.Equal(HttpStatusCode.NotFound,(await client.GetAsync(Api+"/training/classes")).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound,(await client.GetAsync(TrainingPath(x))).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound,(await client.PostAsJsonAsync(TrainingPath(x)+"/generate",new{stage="高中"})).StatusCode);
        }
        await using var allowed=new BetaFactory();var y=await TrainingSeed(allowed);using var teacher=await allowed.Login("beta-author");
        Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync(TrainingPath(y)+"/generate",new{studentId=y.ForeignStudentId})).StatusCode);
        using(var scope=allowed.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();var foreign=await db.ClassSessions.FindAsync(y.ForeignSessionId);var other=await db.ClassSessions.FindAsync(y.OtherCourseSessionId);
            Assert.Equal(HttpStatusCode.Forbidden,(await teacher.GetAsync($"{Api}/training/classes/{foreign!.ClassId}/courses/{y.CourseId}")).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden,(await teacher.PostAsJsonAsync($"{Api}/training/classes/{y.ClassId}/courses/{other!.CourseId}/generate",new{stage="高中"})).StatusCode);
        }
    }
    [Fact]
    public async Task ClassTrainingReportsCoverageAndSourceProblemsWithoutInventingExercises()
    {
        await using var f=new BetaFactory();var x=await TrainingSeed(f);using var client=await f.Login("beta-author");
        var low=await Factory.Post(client,TrainingPath(x)+"/generate",new{stage="小学"});
        Assert.Equal(0,low.GetProperty("summary").GetProperty("generated").GetInt32());
        Assert.Equal("no_match",StudentRow(low.GetProperty("students"),x.StudentId).GetProperty("status").GetString());
        using(var scope=f.Services.CreateScope()){
            var db=scope.ServiceProvider.GetRequiredService<Data>();(await db.Questions.FindAsync(x.QuestionId))!.BlocksJson="[]";
            var mark=db.ClassSessionMarks.Single(m=>m.ClassSessionId==x.SessionId&&m.StudentId==x.SecondStudentId);mark.WrongIdsJson=Json.Write(new[]{Guid.NewGuid()});await db.SaveChangesAsync();
        }
        var incomplete=await Factory.Post(client,TrainingPath(x)+"/generate",new{});
        Assert.Equal("needs_text",StudentRow(incomplete.GetProperty("students"),x.StudentId).GetProperty("status").GetString());
        Assert.Equal("source_missing",StudentRow(incomplete.GetProperty("students"),x.SecondStudentId).GetProperty("status").GetString());
        Assert.Equal(HttpStatusCode.BadRequest,(await client.PostAsJsonAsync(TrainingPath(x)+"/generate",new{maxQuestions=100})).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest,(await client.PostAsJsonAsync(TrainingPath(x)+"/generate",new{subject="英语"})).StatusCode);
    }
    [Theory]
    [InlineData("高一甲班","高中")][InlineData("初二数学","初中")][InlineData("七年级一班","初中")][InlineData("五年级甲班","小学")][InlineData("数学甲班","")]
    public void ClassTrainingStageSuggestionsRequireExplicitGradeNames(string name,string expected) => Assert.Equal(expected,AcademicTraining.SuggestStage(name));
}
