package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmTask;
import cn.siyue.core.CrmDtos.TaskBatchInput;
import cn.siyue.core.CrmDtos.TaskBatchCommitInput;
import cn.siyue.core.CrmDtos.TaskBatchReceipt;
import cn.siyue.core.CrmDtos.TaskBatchPage;
import cn.siyue.core.CrmDtos.TaskBatchPreview;
import cn.siyue.core.CrmDtos.TaskCompletion;
import cn.siyue.core.CrmDtos.TaskPage;
import cn.siyue.core.CrmDtos.TaskUpdate;
import cn.siyue.core.CrmDtos.TaskTargetStudentPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

/** Versioned API for scoped task batches and employee work queues. */
@RestController
@RequestMapping("/api/v2/crm/tasks")
@Validated
public class CrmTaskController {
    private final AccessRepository access;
    private final CrmTaskService service;
    /**
     * 连接任务批次、执行队列和版本化任务办理入口。
     * @param access 可信 issuer/subject 的员工绑定解析器
     * @param service 任务资格、执行范围、分配计划、幂等及审计服务
     */
    public CrmTaskController(AccessRepository access, CrmTaskService service) {
        this.access = access; this.service = service;
    }
    /**
     * 要求原 lead.assign 或现代任务写入与客户读取组合，列出可办理客户的有效关联孩子。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param customerId 当前客户写范围内的客户公开标识
     * @param page 1 至 100000 的页码
     * @param size 1 至 100 的页大小
     * @return 去重的关联学生页，包括尚未报名的孩子
     * @throws BusinessException 权限、客户范围、标识或分页无效时
     */
    @GetMapping("/targets")
    public ApiResponse<TaskTargetStudentPage> targetStudents(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotBlank @Size(max = 120) String customerId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.targetStudents(actor(jwt), customerId, page, size));
    }
    /**
     * 锁后重新校验批量派发权限及目标、员工资格，创建批次和任务并保存幂等回执。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 任务定义、目标客户或孩子、截止时间、负责人及分配策略
     * @return 已创建任务、分配结果和被跳过目标
     * @throws BusinessException 权限、范围、来源、目标、员工、截止时间或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/batches")
    public ApiResponse<TaskBatchReceipt> createBatch(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TaskBatchInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.createBatch(actor(jwt), key, input));
    }
    /**
     * 重新计算计划并与用户已核对预览逐项一致后提交批次。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 原任务输入与用户确认的完整预览
     * @return 批次和任务结果或同请求原回执
     * @throws BusinessException 授权或目标资格变化、计划与预览不一致、幂等冲突时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/batches/commit-preview")
    public ApiResponse<TaskBatchReceipt> commitPreview(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TaskBatchCommitInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.createBatch(actor(jwt), key, input.input(), input.expectedPreview()));
    }
    /**
     * 锁定并核验当前调用者和任务来源，预览可派发目标与分配，不创建任务。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param input 任务定义、唯一目标表示、负责人策略及未来截止时间
     * @return 可办理和跳过目标、员工分配数；可办理数允许为零
     * @throws BusinessException 权限、目标范围、来源、员工或计划条件无效时
     */
    @PostMapping("/batches/preview")
    public ApiResponse<TaskBatchPreview> previewBatch(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TaskBatchInput input) {
        return ApiResponse.success(service.previewBatch(actor(jwt), input));
    }
    /**
     * 按可见任务范围汇总批次进度，指定活动时另核验活动读取范围。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param query 可选批次状态、任务类型、活动标识和有界分页
     * @return 当前可见任务构成的批次页及完成计数
     * @throws BusinessException 任务/活动权限、活动范围、状态、类型或分页无效时
     */
    @GetMapping("/batches")
    public ApiResponse<TaskBatchPage> listBatches(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute CrmDtos.TaskBatchQuery query) {
        return ApiResponse.success(query.activityId() == null
            ? service.listBatches(actor(jwt),query.status(),query.taskType(),query.page(),query.size())
            : service.listBatches(actor(jwt),query));
    }
    /**
     * 读取执行范围内的任务队列，并按当前权限隐藏不可见来源。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param query 状态、任务类型、批次、活动及有界分页
     * @param savedView 可选任务保存筛选，存在时核验资源和所有权
     * @return 可见任务页；路由任务仍限其执行员工
     * @throws BusinessException 读取权限、来源范围、筛选或分页无效时
     */
    @GetMapping
    public ApiResponse<TaskPage> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute CrmDtos.TaskQuery query,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute LegacySavedViewQuery savedView) {
        Actor actor = actor(jwt);
        return ApiResponse.success(savedView != null && savedView.present()
            ? service.list(actor, query, savedView) : service.list(actor, query));
    }
    /**
     * 要求原 lead.create 或 crm.task.write，完成执行范围内任务并校验其结果关联。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param taskId 任务公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 期望版本、执行结果、说明及所需商机或需求处理信息
     * @return 完成后的任务或按当前来源披露规则投影的原回执
     * @throws BusinessException 权限、执行范围/开始时间、状态、版本、结果或关联商机不合格时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/{taskId}/complete")
    public ApiResponse<CrmTask> complete(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String taskId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TaskCompletion input) throws JsonProcessingException {
        return ApiResponse.success(service.complete(actor(jwt), taskId, key, input));
    }
    /**
     * 修改开放任务的负责人、期限或状态；重新分配、改期和取消另要求分配许可。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param taskId 任务公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 期望版本、调整原因及负责人、未来期限或允许的状态
     * @return 更新后的任务或按当前来源披露规则投影的原回执
     * @throws BusinessException 权限、执行范围、版本、开放状态、接收人资格、期限或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{taskId}")
    public ApiResponse<CrmTask> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String taskId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TaskUpdate input) throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), taskId, key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
