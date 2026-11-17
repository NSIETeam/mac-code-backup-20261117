using System.Text.RegularExpressions;
namespace Siyue;

// One allow-list drives credential authorization, discovery, OpenAPI and the SDK.
// /agent/* belongs to the physical Windows printer assistant, not AI callers.
public record AutomationOperation(string Id, string Method, string Path, string Summary, string Scope = "read", string Retry = "read", string? Key = null, bool Admin = false);
public static class AutomationCatalog
{
    public static string Path(string path) => Regex.Replace(path.Split('?')[0].TrimStart('/'), @"\{([^}:]+):[^}]+\}", "{$1}");
    public static readonly AutomationOperation[] Operations = [
        new("capabilities", "GET", "/api/automation/v1/capabilities", "获取操作目录、权限范围和并发建议"),
        new("task_status", "GET", "/api/automation/v1/tasks/{kind}/{id}", "轻量查询 book/handout/export/print/support 的状态，不生成预览或下载链接"),
        new("session_roster", "GET", "/api/automation/v1/sessions/{id}", "读取课次学生、题目 ID 与批改 revision，不生成图片链接"),
        new("list_classes", "GET", "/api/automation/v1/classes", "分页查询授权班级，query/page/pageSize；每页最多 100；沿用返回的 classId/courseIds"),
        new("me", "GET", "/api/me", "当前身份"),
        new("workspace", "GET", "/api/workspace", "可见班级、课程、校区和任课关系；名称重复必须用 ID 消歧"),
        new("class_detail", "GET", "/api/classes/{id}", "班级讲次，query courseId 沿用已选择课程"),
        new("course_lectures", "GET", "/api/courses/{id}/lectures", "课程讲次"),
        new("students", "GET", "/api/classes/{classId}/students", "班级学生"),
        new("create_class", "POST", "/api/classes", "创建班级；同一 RequestId 同一内容；教师受现有校区课程授权限制", "teaching:write", "reconcile", "requestId"),
        new("add_students", "POST", "/api/classes/{classId}/students/batch", "批量添加 1–200 个学生，不需要学号；并发冲突先核对名单", "teaching:write", "reconcile", "requestId"),
        new("edit_student", "PUT", "/api/classes/{classId}/students/{id}", "修改学生；无 revision，需按学生串行并读回核对", "teaching:write", "never"),
        new("import_roster", "POST", "/api/classes/{classId}/students/import", "xlsx 名单，multipart file；先 commit=false 预检，再 commit=true 导入；无自动重试", "teaching:write", "never"),
        new("create_lecture", "POST", "/api/lectures", "创建讲次，必须提供 classId/courseId；Agent 应提供 requestId", "teaching:write", "reconcile", "requestId"),
        new("share_lecture", "POST", "/api/lectures/{id}/share", "管理员将本班讲次转为课程共享讲次", "organization:write", "never", Admin:true),
        new("upload_book", "POST", "/api/classes/{classId}/books", "上传合订讲义，query courseId，multipart file；返回导入 ID 后轮询，不重复上传", "teaching:write", "never"),
        new("list_books", "GET", "/api/classes/{classId}/books", "query courseId、offset；每页最多 50"),
        new("book_detail", "GET", "/api/books/{id}", "合订讲义识别结果；blockOffset 分页"),
        new("retry_book", "POST", "/api/books/{id}/retry", "显式重试失败的合订讲义任务", "teaching:write", "reconcile"),
        new("confirm_book", "POST", "/api/books/{id}/confirm", "校对讲次段落范围后确认；携带 revision", "teaching:write", "revision"),
        new("upload_handout", "POST", "/api/lectures/{id}/handouts", "单讲讲义上传，multipart file；生成新版本，不覆盖已发布讲义", "teaching:write", "never"),
        new("handout_detail", "GET", "/api/handouts/{id}", "讲义及题目校对信息；共享讲义须传 classId"),
        new("edit_question", "POST", "/api/handouts/{id}/edit", "按题目 ID 修改、合并、拆分；携带讲义 revision", "teaching:write", "revision"),
        new("question_region", "GET", "/api/handouts/{id}/regions/{questionId}/{blockIndex}", "查看 PDF 题目裁剪区域"),
        new("edit_regions", "POST", "/api/handouts/{id}/regions", "调整 PDF 区域，禁止未经核对裁掉公式图片", "teaching:write", "revision"),
        new("confirm_questions", "POST", "/api/handouts/{id}/confirm-all", "已完成原稿核对后批量确认；不允许为了结束流程跳过阻断问题", "teaching:write", "revision"),
        new("publish_handout", "POST", "/api/handouts/{id}/publish", "校对后发布；携带 revision，阻断问题必须先修复", "teaching:write", "revision"),
        new("retry_handout", "POST", "/api/handouts/{id}/retry", "重试失败解析或缺失预览；先查询状态", "teaching:write", "reconcile"),
        new("list_sessions", "GET", "/api/class-sessions", "query classId；最多最近 200 个课次，不能视为全量历史"),
        new("create_session", "POST", "/api/class-sessions", "选择已发布讲义建立课堂；保存 classId、handoutId、lessonDate 和稳定 requestKey", "teaching:write", "idempotent", "requestKey"),
        new("mark_student", "PUT", "/api/class-sessions/{id}/students/{studentId}", "用完整错题 ID 集合替换该学生本课次标记；空数组表示已批改且无错题；必须携带 revision", "teaching:write", "revision"),
        new("export_session", "POST", "/api/class-sessions/{id}/exports", "每次 1–100 人；analysis 错题分析版 / compilation 集合版；无错题学生跳过；异步返回文档 ID", "documents:write", "idempotent", "requestKey"),
        new("list_exports", "GET", "/api/exports", "按 batchId 或 documentId 查询导出；优先用轻量 task_status 轮询"),
        new("retry_export", "POST", "/api/exports/{id}/retry", "显式重试失败文档生成；不会打印", "documents:write", "reconcile"),
        new("reflow_export", "POST", "/api/exports/{id}/reflow", "按当前模板生成替代文档，原打印文档不变", "documents:write", "reconcile"),
        new("start_preview", "POST", "/api/previews/{kind}/{id}", "kind=export 或 handout，创建/获取预览任务；多次调用不重复入队", "documents:write", "reconcile"),
        new("preview", "GET", "/api/previews/{kind}/{id}", "读取预览页；图片链接有时效"),
        new("download", "GET", "/api/exports/{id}/download", "query format=pdf 或 docx；返回临时 url 和文件名，按返回名保存"),
        new("upload_print_file", "POST", "/api/print-files", "上传独立文件转为可打印 PDF，multipart file；异步返回文档 ID", "documents:write", "never"),
        new("printers", "GET", "/api/printers", "授权校区打印机及状态；离线不能解释为已出纸"),
        new("print_jobs", "GET", "/api/print-jobs", "最近 200 个打印任务；按单任务精确查询用 task_status"),
        new("submit_print", "POST", "/api/print-jobs", "将有权访问的 1–100 个文档提交已启用打印机；waitForDocuments=true 可后台生成后自动打印；copies 1–99；实际打印前必须获得用户对范围和设备的授权", "printing:write", "idempotent", "requestKey"),
        new("reprint", "POST", "/api/print-jobs/{id}/reprint", "明确补打才调用，使用新的 requestKey；uncertain 需现场核实并 acknowledgeUncertain=true", "printing:write", "idempotent", "requestKey"),
        new("student_history", "GET", "/api/students/{id}/history", "query courseId；支持 from/to/lectureId/state/query/page/pageSize"),
        new("history_question", "GET", "/api/students/{id}/history/{questionId}", "query courseId；历史题目与事件"),
        new("record_retest", "POST", "/api/students/{id}/retests", "登记复测；固定 requestKey，先核对业务结果，不盲目重试", "teaching:write", "reconcile", "requestKey"),
        new("export_history", "POST", "/api/students/{id}/history/export", "汇编过往错题；当前无请求去重，超时后先查导出列表", "documents:write", "never"),
        new("list_support", "GET", "/api/students/{id}/learning-support", "query courseId；变式题和学情报告"),
        new("create_variants", "POST", "/api/students/{id}/variants", "生成变式草稿；需模型可用；固定 requestKey，数学内容必须校对", "documents:write", "reconcile", "requestKey"),
        new("create_report", "POST", "/api/students/{id}/reports", "生成报告草稿，当前无请求去重", "documents:write", "never"),
        new("support_detail", "GET", "/api/learning-support/{id}", "学习资料草稿与审查状态"),
        new("retry_support", "POST", "/api/learning-support/{id}/retry", "显式重试失败学习资料任务", "documents:write", "reconcile"),
        new("edit_support", "PUT", "/api/learning-support/{id}", "携带 revision 修改学习资料草稿", "teaching:write", "revision"),
        new("approve_support", "POST", "/api/learning-support/{id}/approve", "教师核对后批准资料，携带 revision", "teaching:write", "revision"),
        new("admin_catalog", "GET", "/api/admin/catalog", "管理员组织资料", Admin:true),
        new("admin_activity", "GET", "/api/admin/activity", "page、staffId；每页 50 条并返回 hasMore", Admin:true),
        new("admin_create", "POST", "/api/admin/catalog/{kind}", "创建 campuses/courses/classes/students/staff/assignments；无自动重试", "organization:write", "never", Admin:true),
        new("admin_update", "PUT", "/api/admin/catalog/{kind}/{id}", "修改组织资料；无 revision，按对象串行", "organization:write", "never", Admin:true),
        new("delete_assignment", "DELETE", "/api/admin/assignments/{id}", "移除任课关系，同时撤销教师的数据权限", "organization:write", "never", Admin:true),
        new("teacher_account", "POST", "/api/admin/teacher-accounts", "管理员创建教师账号；密码不得放入提示词或日志", "organization:write", "never", Admin:true),
        new("staff_account", "POST", "/api/admin/accounts", "管理员创建教师或管理员（含店长）账号；密码不得写入日志", "organization:write", "never", Admin:true),
        new("guangzhou_binding", "POST", "/api/admin/guangzhou-bindings", "管理员生成广州交接版助手一次性接入码", "organization:write", "never", Admin:true),
        new("device_binding", "POST", "/api/admin/device-bindings", "管理员生成打印设备绑定码；只交给指定电脑", "organization:write", "never", Admin:true),
        new("edit_device", "PUT", "/api/admin/devices/{id}", "管理打印电脑名称、校区和启用状态", "organization:write", "never", Admin:true),
        new("edit_printer", "PUT", "/api/admin/printers/{id}", "管理打印机名称与启用状态", "organization:write", "never", Admin:true)
    ];
    static readonly Dictionary<string, AutomationOperation> Index = Operations.ToDictionary(x => x.Method + " " + Path(x.Path));
    public static AutomationOperation? Find(string method, string? path) => path != null ? Index.GetValueOrDefault(method.ToUpperInvariant() + " " + Path(path)) : null;
    public static readonly string[] Scopes = ["read", "teaching:write", "documents:write", "printing:write", "organization:write"];
}
