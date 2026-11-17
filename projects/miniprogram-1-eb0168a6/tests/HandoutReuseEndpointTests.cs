using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Siyue;
using Xunit;

public class HandoutReuseEndpointTests
{
    [Fact]
    public async Task UploadResponseKeepsEveryExistingHandoutFieldAndOnlyAddsReuse()
    {
        await using var f=new Factory();using var admin=await f.Login("reuse-contract",true);
        using var scope=f.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<Data>();
        var campus=new Campus{Name="测试校区"};var course=new Course{Name="数学"};
        var cl=new SchoolClass{Name="测试班",CampusId=campus.Id};var lecture=new Lecture{ClassId=cl.Id,CourseId=course.Id,Title="第一讲",Sequence=1};
        db.AddRange(campus,course,cl,lecture);await db.SaveChangesAsync();
        using var form=new MultipartFormDataContent();form.Add(new ByteArrayContent(Encoding.UTF8.GetBytes("queued test upload")),"file","测试讲义.docx");
        form.Add(new StringContent("named"),"recognitionMode");
        var response=await admin.PostAsync($"/api/lectures/{lecture.Id}/handouts",form);Assert.Equal(HttpStatusCode.Accepted,response.StatusCode);
        var json=await response.Content.ReadFromJsonAsync<JsonElement>();var id=json.GetProperty("id").GetGuid();
        var saved=await db.Handouts.AsNoTracking().SingleAsync(h=>h.Id==id);var original=JsonSerializer.SerializeToElement(saved,Json.Options);
        foreach(var property in original.EnumerateObject()) {
            var actual=json.GetProperty(property.Name);Assert.Equal(property.Value.ValueKind,actual.ValueKind);
            Assert.Equal(property.Value.ToString(),actual.ToString());
        }
        Assert.Equal(original.EnumerateObject().Count()+1,json.EnumerateObject().Count());Assert.Equal(JsonValueKind.Null,json.GetProperty("reuse").ValueKind);
        Assert.Equal("queued",saved.State);Assert.Equal(1,await db.Work.CountAsync(w=>w.Kind=="import"&&w.EntityId==id));
    }
}
