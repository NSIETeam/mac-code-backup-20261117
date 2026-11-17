package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 将营销任务批次的预览、提交和管理查询映射为 HTTP API；身份解析后由服务执行权限、范围及事务检查。 */
@RestController
@RequestMapping("/api/v2/crm/task-routing")
public class TaskRoutingController {
    private final AccessRepository access;
    private final TaskRoutingService service;
    /**
     * 装配批次路由入口，不在控制器中持久化预览或执行分配。
     * @param access JWT issuer/subject 绑定解析器
     * @param service 预览、提交、幂等回执和管理查询服务
     */
    public TaskRoutingController(AccessRepository access,TaskRoutingService service){this.access=access;this.service=service;}
    /**
     * 计算可创建需求及接收人计划；服务要求任务写入和分配权限，不创建任务事实。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param input 校园、来源需求、任务日期及交付方式
     * @return 含跳过项及版本的完整创建预览
     * @throws BusinessException 身份、创建权限、校园、来源、日期或接收人资格校验失败时
     */
    @PostMapping("/batches/preview")
    public ApiResponse<CreatePreview> preview(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateInput input){return ApiResponse.success(service.previewCreate(actor(jwt),input));}
    /**
     * 提交与重新计算结果完全相同的创建预览，并返回按当前可见范围投影的幂等回执。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 创建操作的幂等键
     * @param input 原创建输入与用户确认的服务端预览
     * @return 批次提交结果；重放已失权时可返回不含业务详情的 left_scope 回执
     * @throws BusinessException 创建权限、幂等键、来源或资格无效，预览已变化或无可创建需求时
     * @throws IllegalStateException 命令或回执 JSON 编解码失败时
     */
    @PostMapping("/batches/commit")
    public ApiResponse<Receipt> commit(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateCommit input){return ApiResponse.success(service.commitCreate(actor(jwt),key,input));}
    /**
     * 列出当前管理范围内的非历史批次与进度汇总。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param q 负责、创建或范围视图及筛选、分页
     * @return 批次分页，活动链接和可用动作按当前权限投影
     * @throws BusinessException 身份不可用、管理权限不足，或查询字段、范围及分页校验失败时
     */
    @GetMapping("/batches")
    public ApiResponse<Page<Batch>> batches(@AuthenticationPrincipal Jwt jwt,@ModelAttribute Query q){return ApiResponse.success(service.list(actor(jwt),q));}
    /**
     * 读取单个非历史批次，校验任务读取、管理权限及当前管理范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 批次公开标识
     * @return 批次详情与当前可用动作
     * @throws BusinessException 身份或管理权限无效、批次不存在或不在当前范围时
     */
    @GetMapping("/batches/{id}")
    public ApiResponse<Batch> batch(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return ApiResponse.success(service.get(actor(jwt),id));}
    /**
     * 经可管理批次查询任务，不披露当前不可见的获客来源证据。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param q 批次、负责人、状态及分页筛选
     * @return 范围内的任务分页
     * @throws BusinessException 身份不可用、管理权限不足，或查询字段、范围及分页校验失败时
     */
    @GetMapping("/tasks")
    public ApiResponse<Page<TaskItem>> tasks(@AuthenticationPrincipal Jwt jwt,@ModelAttribute Query q){return ApiResponse.success(service.tasks(actor(jwt),q));}
    /**
     * 按批次与员工汇总当前管理范围内的任务进度。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param q 管理视图、活动及其他批次筛选与分页
     * @return 批次员工进度分页
     * @throws BusinessException 身份不可用、管理权限不足，或查询字段、范围及分页校验失败时
     */
    @GetMapping("/employees")
    public ApiResponse<Page<Employee>> employees(@AuthenticationPrincipal Jwt jwt,@ModelAttribute Query q){return ApiResponse.success(service.employees(actor(jwt),q));}
    /**
     * 查询指定批次的任务；路径标识覆盖查询批次，视图固定为 scope，其他筛选保留。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 路径中的批次公开标识
     * @param q 负责人、状态等筛选及分页
     * @return 该批次在当前管理范围内的任务分页
     * @throws BusinessException 身份不可用、管理权限不足，或查询字段、范围及分页校验失败时
     */
    @GetMapping("/batches/{id}/tasks")
    public ApiResponse<Page<TaskItem>> batchTasks(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@ModelAttribute Query q){return ApiResponse.success(service.tasks(actor(jwt),fixed(id,q)));}
    /**
     * 查询指定批次的员工进度；路径标识覆盖查询批次，视图固定为 scope。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 路径中的批次公开标识
     * @param q 保留的管理筛选及分页
     * @return 该批次在当前管理范围内的员工汇总分页
     * @throws BusinessException 身份不可用、管理权限不足，或查询字段、范围及分页校验失败时
     */
    @GetMapping("/batches/{id}/employees")
    public ApiResponse<Page<Employee>> batchEmployees(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@ModelAttribute Query q){return ApiResponse.success(service.employees(actor(jwt),fixed(id,q)));}
    /**
     * 读取具备校园任务接收资格的主管候选；没有管理权限时不披露工作量。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param q 校园或可管理批次上下文、账号前缀及分页
     * @return 主管候选及当前管理范围内的待办数量
     * @throws BusinessException 身份、任务写入或分配权限、校园上下文、批次范围或分页无效时
     */
    @GetMapping("/supervisors")
    public ApiResponse<Page<Candidate>> supervisors(@AuthenticationPrincipal Jwt jwt,@ModelAttribute CandidateQuery q){return ApiResponse.success(service.supervisors(actor(jwt),q));}
    /**
     * 读取指定批次的合格接收人；服务要求任务读取、管理、写入和分配权限。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 可管理批次公开标识
     * @param q 账号前缀筛选及分页
     * @return 接收人身份及当前管理范围内的待办数量
     * @throws BusinessException 身份不可用、管理权限不足，或查询字段、范围及分页校验失败时
     */
    @GetMapping("/batches/{id}/assignees")
    public ApiResponse<Page<Candidate>> assignees(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@ModelAttribute CandidateQuery q){return ApiResponse.success(service.assignees(actor(jwt),id,q));}
    /**
     * 读取批次的不可变路由事件，先校验当前管理范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 批次公开标识
     * @param q 事件分页参数
     * @return 含原操作人、原因及分配变更的事件分页
     * @throws BusinessException 身份不可用、管理权限不足，或查询字段、范围及分页校验失败时
     */
    @GetMapping("/batches/{id}/events")
    public ApiResponse<Page<Event>> events(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@ModelAttribute CandidateQuery q){return ApiResponse.success(service.events(actor(jwt),id,q));}
    /**
     * 锁定批次和当前身份后预览管理操作，不持久化变更。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 批次公开标识
     * @param operation allocation、recall、transfer、schedule 或 cancel
     * @param input 管理版本、操作选项及调整原因
     * @return 包含被阻止任务与各行版本的变更预览
     * @throws BusinessException 身份、管理权限、范围、版本、批次状态、接收人或日期规则不满足时
     */
    @PostMapping("/batches/{id}/{operation}/preview")
    public ApiResponse<ChangePreview> changePreview(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable String operation,@Valid @RequestBody ChangeInput input){return ApiResponse.success(service.previewChange(actor(jwt),id,operation,input));}
    /**
     * 仅在锁定计划与确认预览一致且没有阻止项时提交；重放按当前可见范围投影原事实。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 批次公开标识
     * @param operation allocation、recall、transfer、schedule 或 cancel
     * @param key 该管理变更的幂等键
     * @param input 原操作输入与完整期望预览
     * @return 提交回执或当前范围投影后的原回执
     * @throws BusinessException 权限、范围、版本、幂等检查失败，预览变化或仍有阻止项时
     * @throws IllegalStateException 命令或回执 JSON 编解码失败时
     */
    @PostMapping("/batches/{id}/{operation}/commit")
    public ApiResponse<Receipt> changeCommit(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable String operation,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ChangeCommit input){return ApiResponse.success(service.commitChange(actor(jwt),id,operation,key,input));}
    private Query fixed(String id,Query q){return new Query("scope",q.campusId(),q.supervisorId(),q.activityId(),q.query(),q.distributionStatus(),q.executionStatus(),q.fromAt(),q.untilAt(),id,q.ownerId(),q.status(),q.page(),q.size());}
    private Actor actor(Jwt jwt){return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
