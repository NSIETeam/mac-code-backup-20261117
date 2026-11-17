package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Contracts for scoped rule-based lead assignment and unmatched routing queues. */
public final class LeadRoutingDtos {
    private LeadRoutingDtos() { }
    /** 创建校区内线索分配规则；目标员工资格和条件引用由服务检查。
     * @param name 非空规则名，至多120字符
     * @param campusId 固定规则校区公开标识
     * @param activityId 可选活动公开标识
     * @param subject 可选匹配学科，至多100字符
     * @param source 可选精确线索来源，至多120字符
     * @param ownerId 目标负责人公开标识
     * @param priority 0至10000优先级，匹配时按升序处理
     * @param active 显式启用状态
     */
    public record RuleCreate(@NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 120) String campusId, @Size(max = 120) String activityId,
            @Size(max = 100) String subject,
            @Size(max = 120) String source,
            @NotBlank @Size(max = 120) String ownerId, @Min(0) @Max(10000) int priority,
            @NotNull Boolean active) { }
    /** 替换规则条件及负责人，保留创建时校区。
     * @param name 非空规则名
     * @param activityId 可选活动条件
     * @param subject 可选学科条件
     * @param source 可选精确来源条件
     * @param ownerId 目标负责人公开标识
     * @param priority 0至10000优先级
     * @param active 新启用状态
     * @param expectedVersion 当前规则期望版本
     */
    public record RuleUpdate(@NotBlank @Size(max = 120) String name,
            @Size(max = 120) String activityId, @Size(max = 100) String subject,
            @Size(max = 120) String source,
            @NotBlank @Size(max = 120) String ownerId, @Min(0) @Max(10000) int priority,
            @NotNull Boolean active, @Min(1) long expectedVersion) { }
    /** 已持久化规则及当前版本。
     * @param id 规则公开标识
     * @param name 规则名称
     * @param campusId 规则校区
     * @param activityId 可选活动条件
     * @param subject 可选学科条件
     * @param source 可选来源条件
     * @param ownerId 目标负责人
     * @param priority 匹配优先级
     * @param active 是否启用
     * @param version 当前版本
     */
    public record RoutingRule(String id, String name, String campusId, String activityId,
            String subject, String source, String ownerId, int priority, boolean active, long version) { }
    /** 当前管理范围内的线索分配规则页。
     * @param items 当前页规则
     * @param total 匹配规则总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record RulePage(List<RoutingRule> items, long total, int page, int size) { }
    /** 规则不可变变更历史，保留导入来源事件和实际操作人。
     * @param id 历史记录公开标识
     * @param sourceEventId 原系统事件标识，本地事件可为空
     * @param action 规则操作码
     * @param actorId 操作员工公开标识
     * @param occurredAt 原事件发生时刻
     * @param reason 变更原因
     * @param changes 结构化变更内容的JSON字符串
     */
    public record RuleHistoryEvent(String id, String sourceEventId, String action, String actorId,
            java.time.Instant occurredAt, String reason, String changes) { }
    /** 单条规则的历史事件页。
     * @param items 当前页历史
     * @param total 历史总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record RuleHistoryPage(List<RuleHistoryEvent> items, long total, int page, int size) { }
    /** 模拟中符合条件规则的目标资格说明。
     * @param ruleId 规则公开标识
     * @param name 规则名称
     * @param ownerId 规则指定负责人
     * @param priority 规则优先级
     * @param eligible 目标负责人当前是否合格
     * @param reason 合格或不合格原因
     */
    public record Candidate(String ruleId, String name, String ownerId, int priority,
            boolean eligible, String reason) { }
    /** 单条线索当前模拟结果，实际执行仍重新匹配规则。
     * @param leadId 线索公开标识
     * @param matchedRuleId 首个合格匹配规则，未匹配时为空
     * @param ownerId 计划负责人，未匹配时为空
     * @param reason 匹配或未匹配说明
     * @param candidates 按优先级排列的候选资格
     */
    public record RoutingPlan(String leadId, String matchedRuleId, String ownerId,
            String reason, List<Candidate> candidates) { }
    /** 待批量路由的线索及期望版本。
     * @param leadId 线索公开标识
     * @param expectedVersion 至少为1的线索期望版本
     */
    public record LeadVersion(@NotBlank @Size(max = 120) String leadId, @Min(1) long expectedVersion) { }
    /** 选择待路由的新公海线索，版本与范围在执行时重新检查。
     * @param leads 1至100条不重复线索及版本
     */
    public record ApplyInput(@NotEmpty @Size(max = 100) List<@NotNull @Valid LeadVersion> leads) { }
    /** 仍待办理的未匹配规则队列记录。
     * @param id 队列公开标识
     * @param leadId 线索公开标识
     * @param campusId 线索校区标识
     * @param reason 未匹配原因
     * @param createdAt 入队时刻
     * @param version 队列版本
     */
    public record QueueEntry(String id, String leadId, String campusId, String reason,
            Instant createdAt, long version) { }
    /** 当前管理范围内未匹配队列页。
     * @param items 当前页未匹配项
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record QueuePage(List<QueueEntry> items, long total, int page, int size) { }
    /** 选中线索按当前规则计算的计划汇总。
     * @param plans 按输入顺序排列的路由计划
     * @param assignable 可分配数量
     * @param unmatched 未匹配数量
     */
    public record BatchPreview(List<RoutingPlan> plans, int assignable, int unmatched) { }
    /** 路由事务提交后的分配与未匹配结果。
     * @param assigned 已分配线索投影
     * @param unmatched 已登记未匹配队列项
     */
    public record ApplyResult(List<CrmDtos.Lead> assigned, List<QueueEntry> unmatched) { }
}
