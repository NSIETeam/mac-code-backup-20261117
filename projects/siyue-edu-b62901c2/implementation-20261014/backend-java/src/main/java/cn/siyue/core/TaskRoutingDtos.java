package cn.siyue.core;

import cn.siyue.core.CrmDtos.TaskTargetInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Explicit routing contracts; old task bodies remain unchanged. */
public final class TaskRoutingDtos {
    private TaskRoutingDtos() { }
    /** 直接向员工分配任务时的策略、负责人集合和可选配额；资格与配额合计在规划阶段验证。 */
    public record EmployeePlan(String strategy,List<String> ownerIds,List<CrmDtos.TaskQuota> quotas) { }
    /** 任务交付模式及主管/员工分配方案，具体模式要求由路由规划服务核验。 */
    public record Delivery(String mode,String supervisorId,@Valid EmployeePlan employeePlan) { }
    /** 新建营销任务批次的校区、时间、交付方式和精确需求目标，最多500个输入目标。 */
    public record CreateInput(@NotBlank @Size(max=200) String title,@Pattern(regexp="marketing") String taskType,
        @NotBlank String campusId,String activityId,@NotNull Instant startsAt,@NotNull Instant dueAt,
        @NotNull @Valid Delivery delivery,@NotEmpty @Size(max=500) List<@Valid TaskTargetInput> targets,
        @Size(max=5000) String note) { }
    /** 创建预览中保留的目标、拟接收人和规划状态；预览本身不写入任务。 */
    public record Reserved(String targetKey,CrmDtos.TaskTargetInput target,String ownerId,String status) { }
    /** 创建预览中跳过的目标及具体原因，保留原需求目标便于重选。 */
    public record Skipped(String targetKey,CrmDtos.TaskTargetInput target,String reason) { }
    /** 创建批次前计算的目标、交付、时间和分配计数快照，提交时须与服务端重算结果一致。 */
    public record CreatePreview(String action,String campusId,String deliveryMode,String supervisorId,
        Instant startsAt,Instant dueAt,int selectedCount,int reservedCount,int skippedCount,
        Map<String,Long> assignmentCounts,List<Reserved> targets,List<Skipped> skippedTargets) { }
    /** 绑定原创建请求和已确认预览的提交输入，不接受仅由客户端指定分配结果。 */
    public record CreateCommit(@NotNull @Valid CreateInput input,@NotNull CreatePreview expectedPreview) { }
    /** 批次管理中选中的任务公开标识及其预期行版本。 */
    public record Selection(String taskId,long expectedVersion) { }
    /** 批次管理请求，携带管理版本、选中任务、分配/主管/时间参数及原因，按具体操作校验。 */
    public record ChangeInput(long expectedManagementVersion,List<Selection> selection,String strategy,
        List<String> ownerIds,List<CrmDtos.TaskQuota> quotas,String sourceOwnerPolicy,String supervisorId,
        Instant startsAt,Instant dueAt,String reason) { }
    /** 管理预览中一项任务变更的原值和目标值，包括负责人、状态、截止时间及规划依据。 */
    public record Assignment(String taskId,long expectedVersion,String fromOwnerId,String toOwnerId,
        String fromStatus,String toStatus,Instant fromDueAt,Instant toDueAt,String basis) { }
    /** 管理预览中无法执行的任务及阻断原因，提交规则要求先处理阻断项。 */
    public record Blocked(String taskId,String reason) { }
    /** 批次管理操作预览，区分可变更与阻断任务，并保留管理版本、主管和时间变化。 */
    public record ChangePreview(String action,String batchId,long managementVersion,int selectedCount,
        int assignableCount,int blockedCount,List<Assignment> assignments,List<Blocked> blocked,
        Map<String,Long> assignmentCounts,long remainingUnassignedCount,String fromSupervisorId,
        String toSupervisorId,Instant startsAt,Instant dueAt) { }
    /** 原管理请求与确认预览的组合，写入前重新规划并核对版本及结果。 */
    public record ChangeCommit(@NotNull @Valid ChangeInput input,@NotNull ChangePreview expectedPreview) { }
    /** 任务批次或员工维度的总数、各状态数量及已分配数量统计。 */
    public record Counts(long totalCount,long unassignedCount,long pendingCount,long inProgressCount,
        long completedCount,long cancelledCount,long allocatedCount) { }
    /** 路由批次读取投影，管理版本与实体版本分开，操作列表按当前授权和状态计算。 */
    public record Batch(String id,String title,String campusId,String activityId,String createdBy,String deliveryMode,
        String supervisorId,Instant startsAt,Instant dueAt,String status,long managementVersion,long version,
        Counts counts,String distributionStatus,String executionStatus,List<String> actions) { }
    /** 路由列表中的任务身份、需求、负责人、状态及来源投影，不展开客户联系方式。 */
    public record TaskItem(String id,String batchId,String customerId,String studentId,String subject,String term,
        OpportunityNeedDtos.Identity needIdentity,String ownerId,String status,Instant dueAt,long version,
        String activityId,List<CrmDtos.TaskSourceRef> sources,String sourceStatus) { }
    /** 批次内某负责人的任务数量汇总及批次显示信息。 */
    public record Employee(String batchId,String batchTitle,String ownerId,Counts counts) { }
    /** 主管/接收人员候选项，说明其数据范围及待办计数的统计范围。 */
    public record Candidate(String id,String label,String scopeType,long pendingCount,String countScope) { }
    /** 追加式批次路由事件，保留操作者、主管变化、原因及逐任务变更快照。 */
    public record Event(String id,String action,String actorId,String fromSupervisorId,String toSupervisorId,
        Instant occurredAt,String reason,List<Assignment> changes) { }
    /** 提交产生的单项任务回执，记录当时版本、负责人、状态及截止时间。 */
    public record TaskReceipt(String taskId,long version,String ownerId,String status,Instant dueAt) { }
    /** 路由提交/重放回执；scopeStatus为left_scope时可隐藏批次与任务详情，但保留已提交事实。 */
    public record Receipt(boolean committed,String batchId,String operationId,String scopeStatus,Batch batch,List<TaskReceipt> tasks) { }
    /** 路由读取接口的通用分页结果，列表与总数使用相同筛选和范围。
     * @param <T> 页面条目的读取投影类型
     */
    public record Page<T>(List<T> items,long total,int page,int size) { }
    /** 批次、任务或员工路由列表的组合筛选；默认responsible视图、第1页每页50条。 */
    public record Query(String view,String campusId,String supervisorId,String activityId,String query,
        String distributionStatus,String executionStatus,Instant fromAt,Instant untilAt,String batchId,
        String ownerId,String status,Integer page,Integer size) {
        /**
         * 补齐默认负责范围视图和分页，不在构造器中替代查询校验。
         * @param view 读取视图，null使用responsible
         * @param campusId 可选校区标识
         * @param supervisorId 可选主管标识
         * @param activityId 可选活动标识
         * @param query 可选搜索文本
         * @param distributionStatus 分配状态筛选
         * @param executionStatus 执行状态筛选
         * @param fromAt 可选时间起点
         * @param untilAt 可选时间终点
         * @param batchId 可选批次标识
         * @param ownerId 可选负责人标识
         * @param status 任务状态筛选
         * @param page 页码，null使用1
         * @param size 每页条数，null使用50
         */
        public Query { view=view==null?"responsible":view; page=page==null?1:page; size=size==null?50:size; }
    }
    /** 主管或员工选择器的校区、批次和搜索筛选；默认第1页每页50条。 */
    public record CandidateQuery(String campusId,String batchId,String query,Integer page,Integer size) {
        /**
         * 补齐人员选择器分页默认值，不改变校区、批次或搜索条件。
         * @param campusId 可选校区标识
         * @param batchId 可选批次标识
         * @param query 可选搜索文本
         * @param page 页码，null使用1
         * @param size 每页条数，null使用50
         */
        public CandidateQuery { page=page==null?1:page; size=size==null?50:size; }
    }
}
