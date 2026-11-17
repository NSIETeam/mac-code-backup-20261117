using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;
using System.Text.Json.Nodes;
namespace Siyue;

public static class AutomationOpenApi
{
    static OpenApiSchema ObjectSchema(params (string Name,string? Format)[] fields) => new() { Type = JsonSchemaType.Object, Properties = fields.ToDictionary(x=>x.Name,x=>(IOpenApiSchema)new OpenApiSchema { Type=JsonSchemaType.String, Format=x.Format }) };
    static OpenApiResponse Response(IOpenApiSchema schema,string description) => new() { Description=description, Content=new Dictionary<string,OpenApiMediaType> { ["application/json"] = new() { Schema=schema } } };
    public static IServiceCollection AddAutomationOpenApi(this IServiceCollection services)
    {
        services.AddOpenApi("agent-v1", options => {
            options.ShouldInclude = description => AutomationCatalog.Find(description.HttpMethod ?? "", description.RelativePath) != null;
            options.AddOperationTransformer((operation, context, ct) => {
                var entry = AutomationCatalog.Find(context.Description.HttpMethod!, context.Description.RelativePath)!;
                operation.OperationId = entry.Id;
                operation.Summary = entry.Summary;
                operation.Description = $"权限范围：{entry.Scope}。重试策略：{entry.Retry}。" + (entry.Key == null ? "" : $"同一业务意图必须始终使用相同 {entry.Key}，保存后再提交；不同内容不得复用。") + " HTTP 成功仅表示本次请求完成，异步任务需继续查状态。";
                operation.Extensions ??= new Dictionary<string, IOpenApiExtension>();
                operation.Extensions["x-agent"] = new JsonNodeExtension(new JsonObject { ["scope"] = entry.Scope, ["retry"] = entry.Retry, ["key"] = entry.Key, ["admin"] = entry.Admin });
                operation.Responses ??= new();
                foreach (var (code, text) in new[] { ("400","参数无效；修正参数"),("401","凭证无效或已过期；停止调用"),("403","权限不足；禁止换身份绕过"),("404","对象不存在"),("409","状态、revision 或幂等内容冲突；读取现状后核对，不能盲目重试"),("429","并发过多；遵守 Retry-After"),("500","服务错误；仅 read/idempotent 可在退避后自动重试") })
                    operation.Responses[code] = new OpenApiResponse { Description = text, Content = new Dictionary<string, OpenApiMediaType> { ["application/json"] = new() { Schema = new OpenApiSchema { Type = JsonSchemaType.Object, Properties = new Dictionary<string, IOpenApiSchema> { ["message"] = new OpenApiSchema { Type = JsonSchemaType.String }, ["code"] = new OpenApiSchema { Type = JsonSchemaType.String }, ["traceId"] = new OpenApiSchema { Type = JsonSchemaType.String } } } } } };
                if (entry.Id is "upload_book" or "upload_handout" or "upload_print_file" or "import_roster") {
                    var properties = new Dictionary<string, IOpenApiSchema> { ["file"] = new OpenApiSchema { Type = JsonSchemaType.String, Format = "binary", Description = entry.Id == "import_roster" ? "xlsx，不超过5 MiB" : "Word/PDF，不超过50 MiB；独立打印还支持PNG/JPEG" } };
                    if (entry.Id == "import_roster") properties["commit"] = new OpenApiSchema { Type = JsonSchemaType.String, Description = "先 false 预检，通过后 true 提交；字段值为字符串" };
                    if (entry.Id == "upload_print_file") foreach (var field in new[] { "fileName", "classId", "courseId", "lectureId", "materialKind" }) properties[field] = new OpenApiSchema { Type = JsonSchemaType.String, Description = "可选；关联班级时必须同时填写 classId/courseId/lectureId/materialKind，materialKind=handout|homework" };
                    operation.RequestBody = new OpenApiRequestBody { Required = true, Content = new Dictionary<string, OpenApiMediaType> { ["multipart/form-data"] = new() { Schema = new OpenApiSchema { Type = JsonSchemaType.Object, Required = new HashSet<string>{"file"}, Properties = properties } } } };
                    if (entry.Id != "import_roster") { operation.Responses.Remove("200"); operation.Responses["202"] = new OpenApiResponse { Description = "已入队；读取 id，轮询 task_status；不是解析或打印完成", Content = new Dictionary<string,OpenApiMediaType> { ["application/json"] = new() { Schema = new OpenApiSchema { Type = JsonSchemaType.Object, Properties = new Dictionary<string,IOpenApiSchema> { ["id"] = new OpenApiSchema { Type = JsonSchemaType.String, Format = "uuid" }, ["state"] = new OpenApiSchema { Type = JsonSchemaType.String } } } } } }; }
                }
                if (entry.Id is "publish_handout" or "edit_device" or "edit_printer" or "delete_assignment") {
                    operation.Responses.Remove("200"); operation.Responses["204"] = new OpenApiResponse { Description = "操作完成，无响应体" };
                }
                if (entry.Id is "retry_handout" or "retry_export" or "retry_book" or "retry_support") {
                    operation.Responses.Remove("200"); operation.Responses["202"] = new OpenApiResponse { Description = "已接受；原任务 ID 不变，继续查 task_status，无响应体" };
                }
                if (entry.Id is "submit_print" or "reprint")
                    operation.Responses["200"] = Response(new OpenApiSchema { Type = JsonSchemaType.Array, Items = ObjectSchema(("id","uuid"),("batchId","uuid"),("documentId","uuid"),("state",null)) }, "每个学生一个任务；preparing 为等待文档，完成后自动排队；queued 不是已出纸，submitted 也不是物理出纸证明");
                if (entry.Id is "record_retest" or "approve_support") operation.Responses["200"] = Response(ObjectSchema(("id","uuid")), entry.Id == "approve_support" ? "返回新生成文档 ID，之后轮询 export 状态" : "复测记录 ID");
                if (entry.Id is "create_variants" or "export_history") {
                    operation.Responses.Remove("200"); operation.Responses["202"] = Response(ObjectSchema(("id","uuid"),(entry.Id=="create_variants" ? "state" : "batchId",entry.Id=="create_variants" ? null : "uuid")), "已创建异步任务");
                }
                if (entry.Id == "teacher_account") operation.Responses["200"] = Response(ObjectSchema(("id","uuid"),("name",null),("account",null)), "教师账号已创建，不返回密码");
                if (entry.Id is "admin_create" or "admin_update") operation.Responses["200"] = Response(ObjectSchema(("id","uuid")), "返回保存的对象；其他字段按 kind 对应的业务对象返回");
                if (entry.Id == "import_roster") operation.Responses["200"] = Response(new OpenApiSchema { Type = JsonSchemaType.Object, Properties = new Dictionary<string,IOpenApiSchema> { ["committed"] = new OpenApiSchema { Type = JsonSchemaType.Boolean }, ["rows"] = new OpenApiSchema { Type = JsonSchemaType.Array, Items = new OpenApiSchema { Type = JsonSchemaType.Object, Properties = new Dictionary<string,IOpenApiSchema> { ["row"] = new OpenApiSchema { Type = JsonSchemaType.Integer }, ["name"] = new OpenApiSchema { Type = JsonSchemaType.String }, ["code"] = new OpenApiSchema { Type = JsonSchemaType.String } } } } } }, "committed=false 是预检；true 才表示已导入");
                if (entry.Id == "confirm_book") operation.Responses["200"] = Response(new OpenApiSchema { Type = JsonSchemaType.Object, Properties = new Dictionary<string,IOpenApiSchema> { ["sections"] = new OpenApiSchema { Type = JsonSchemaType.Array, Items = new OpenApiSchema { Type = JsonSchemaType.Object, Properties = new Dictionary<string,IOpenApiSchema> { ["start"] = new OpenApiSchema { Type = JsonSchemaType.Integer }, ["end"] = new OpenApiSchema { Type = JsonSchemaType.Integer }, ["sequence"] = new OpenApiSchema { Type = JsonSchemaType.Integer }, ["title"] = new OpenApiSchema { Type = JsonSchemaType.String }, ["kind"] = new OpenApiSchema { Type = JsonSchemaType.String }, ["handoutId"] = new OpenApiSchema { Type = JsonSchemaType.String | JsonSchemaType.Null, Format = "uuid" } } } } } }, "讲次任务已创建，仍需轮询每份 handout 并校对后发布");
                return Task.CompletedTask;
            });
            options.AddDocumentTransformer((document, context, ct) => {
                document.Info = new() { Title = "思跃 Agent 操作接口", Version = "1.0.0", Description = "外部 Agent 通过 HTTP 调用既有业务接口。复用班级权限；独立凭证按操作范围授权。/agent/* 为打印助手内部协议，不在此接口中。" };
                var config = context.ApplicationServices.GetRequiredService<IConfiguration>();
                document.Servers = [new OpenApiServer { Url = (config["PublicUrl"] ?? "http://127.0.0.1:5080").TrimEnd('/') }];
                document.Components ??= new();
                document.Components.SecuritySchemes ??= new Dictionary<string,IOpenApiSecurityScheme>();
                document.Components.SecuritySchemes["agentBearer"] = new OpenApiSecurityScheme { Type = SecuritySchemeType.Http, Scheme = "bearer", BearerFormat = "sya_...", Description = "由账号登录后创建的 Agent 凭证；不得把教师密码或凭证写入提示词" };
                document.Security = [new OpenApiSecurityRequirement { [new OpenApiSecuritySchemeReference("agentBearer", document)] = [] }];
                return Task.CompletedTask;
            });
        });
        return services;
    }
}
