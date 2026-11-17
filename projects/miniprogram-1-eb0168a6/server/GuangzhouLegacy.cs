using System.Text;
using System.Text.Json;
namespace Siyue;
// Only the preconfigured loopback service verifies existing device credentials.
// Never copy DPAPI secrets or rotate an existing assistant's identity.
public static class GuangzhouLegacy {
    public sealed record Reply(int Status,byte[] Body,string ContentType):IResult {
        public async Task ExecuteAsync(HttpContext context){context.Response.StatusCode=Status;context.Response.ContentType=ContentType;context.Response.Headers.CacheControl="no-store";await context.Response.Body.WriteAsync(Body,context.RequestAborted);}
    }
    public static async Task Verify(HttpContext h,Device device,GuangzhouDevice protocol){
        var r=await Call(h,"GET","/api/v2/agent/identity");using var json=JsonDocument.Parse(r.Body);var v=json.RootElement;
        Guard.That(v.GetProperty("id").GetGuid()==device.Id&&v.GetProperty("queue_id").GetGuid()==protocol.QueueId&&v.GetProperty("campus_id").GetInt64()==protocol.CampusNumber&&v.GetProperty("campus_name").GetString()==protocol.IdentityCampusName&&v.GetProperty("protocol_version").GetInt32()==2&&v.GetProperty("protocol_revision").GetInt32()==3,"原设备身份已变化，暂停统一接单",409);
    }
    public static async Task<Reply> Forward(HttpContext h){
        string? body=null;if(h.Request.Method=="POST"){h.Request.Body.Position=0;using var reader=new StreamReader(h.Request.Body,Encoding.UTF8,leaveOpen:true);body=await reader.ReadToEndAsync(h.RequestAborted);Guard.That(body.Length<=16384,"设备回执过长");}
        return await Call(h,h.Request.Method,h.Request.Path.Value!,body,false);
    }
    public static async Task<Reply> Call(HttpContext h,string method,string path,string? body=null,bool requireSuccess=true){
        Guard.That(path.StartsWith("/api/v2/agent/")&&!path.Contains("..")&&!path.Contains('?'),"设备路径无效",400);
        var config=h.RequestServices.GetRequiredService<IConfiguration>();var address=config["GuangzhouLegacy:BaseUrl"]??"http://127.0.0.1:8300";
        Guard.That(Uri.TryCreate(address,UriKind.Absolute,out var target)&&target.Scheme=="http"&&target.Host=="127.0.0.1"&&target.AbsolutePath=="/"&&target.UserInfo==""&&target.Query==""&&target.Fragment=="","原打印系统连接配置无效",503);
        using var request=new HttpRequestMessage(new HttpMethod(method),address.TrimEnd('/')+path);request.Headers.Add("X-Print-Agent-Token",h.Request.Headers["X-Print-Agent-Token"].ToString());
        if(body!=null)request.Content=new StringContent(body,Encoding.UTF8,"application/json");
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(h.RequestAborted);timeout.CancelAfter(TimeSpan.FromSeconds(12));
        try{
            using var response=await h.RequestServices.GetRequiredService<IHttpClientFactory>().CreateClient("guangzhou-legacy").SendAsync(request,HttpCompletionOption.ResponseHeadersRead,timeout.Token);
            if(requireSuccess)Guard.That(response.IsSuccessStatusCode,response.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden?"原设备凭证无效或已停用":"原打印服务暂不可用",response.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden?(int)response.StatusCode:503);
            var limit=path.EndsWith("/pdf")?128*1024*1024:256*1024;using var output=new MemoryStream();await using var source=await response.Content.ReadAsStreamAsync(timeout.Token);var buffer=new byte[81920];int n;while((n=await source.ReadAsync(buffer,timeout.Token))>0){Guard.That(output.Length+n<=limit,"原打印服务响应过大",502);output.Write(buffer,0,n);}
            return new((int)response.StatusCode,output.ToArray(),response.Content.Headers.ContentType?.ToString()??"application/json");
        }catch(HttpRequestException){throw new ApiException(503,"原打印服务连接失败，任务保持原状态");}catch(OperationCanceledException)when(!h.RequestAborted.IsCancellationRequested){throw new ApiException(503,"原打印服务超时，任务保持原状态");}
    }
}
