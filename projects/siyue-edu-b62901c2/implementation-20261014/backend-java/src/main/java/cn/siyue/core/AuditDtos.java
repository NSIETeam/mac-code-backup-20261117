package cn.siyue.core;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.Size;

/** Bounded, non-PII audit search result types. */
public final class AuditDtos {
    private AuditDtos() { }
    /** 审计检索的最小字段投影，不包含请求体或业务个人资料。
     * @param createdAt 审计写入时刻
     * @param actorId 操作员工公开标识
     * @param action 审计操作码
     * @param resourceId 被操作资源的公开标识
     * @param traceId 关联请求的追踪标识
     */
    public record AuditEvent(Instant createdAt, String actorId, String action,
            String resourceId, String traceId) { }
    /** 经授权的审计事件分页结果。
     * @param items 当前页审计事件
     * @param total 匹配事件总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record AuditPage(List<AuditEvent> items, long total, int page, int size) { }
    /** 可选审计过滤条件；时间顺序和分页边界由服务检查。
     * @param actorId 可选操作员工公开标识，最多120字符
     * @param action 可选操作码，最多100字符
     * @param resourceId 可选资源标识，最多120字符
     * @param fromAt 可选起始时刻
     * @param untilAt 可选截止时刻
     * @param page 从1开始的页码
     * @param size 每页条数
     */
    public record AuditQuery(@Size(max=120) String actorId,@Size(max=100) String action,
            @Size(max=120) String resourceId,Instant fromAt,Instant untilAt,int page,int size) { }
}
