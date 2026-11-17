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

/** Versioned lead SLA policy and dry-run/apply records. */
public final class LeadPolicyDtos {
    private LeadPolicyDtos() { }
    /** 线索保留/回收天数、首次跟进与决策小时数的版本化设置输入；省略决策小时的兼容处理由服务负责。 */
    public record PolicyInput(@Min(1) @Max(3650) int reserveDays,
            @Min(1) @Max(3650) int reclaimDays,
            @Min(1) @Max(8760) int firstFollowHours,
            @Min(1) @Max(720) Integer decisionHours,
            @Min(1) long expectedVersion) {
        /**
         * 构造未提供决策小时数的兼容策略输入，其余时限原样传入。
         * @param reserveDays 保留天数
         * @param reclaimDays 回收天数
         * @param firstFollowHours 首次跟进时限小时数
         * @param expectedVersion 当前策略预期版本
         */
        public PolicyInput(int reserveDays, int reclaimDays, int firstFollowHours, long expectedVersion) {
            this(reserveDays, reclaimDays, firstFollowHours, null, expectedVersion);
        }
    }
    /** 当前线索时限策略及版本、最近更新者和时间。 */
    public record Policy(int reserveDays, int reclaimDays, int firstFollowHours, int decisionHours,
            long version, Instant updatedAt, String updatedBy) { }
    /** 策略预览中的线索及其分配、跟进、活跃和到期时间依据，保留当前版本。 */
    public record Candidate(String leadId, String ownerId, String campusId, String kind,
            Instant assignedAt, Instant lastFollowAt, Instant activityAt, Instant dueAt, long version) { }
    /** 策略及同一范围内符合预警/回收条件的线索分页。 */
    public record Preview(Policy policy, List<Candidate> items, long total, int page, int size) { }
    /** 执行策略时复核的线索标识、预期版本及可选warning/recycle分类。 */
    public record LeadVersion(@NotBlank @Size(max = 120) String leadId, @Min(1) long expectedVersion,
            @jakarta.validation.constraints.Pattern(regexp = "warning|recycle") String kind) { }
    /** 按明确策略版本执行最多100条线索预警/回收的请求。 */
    public record ApplyInput(@Min(1) long policyVersion,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid LeadVersion> leads) { }
    /** 策略执行后产生的预警项及已回收线索回执集合。 */
    public record ApplyResult(List<Candidate> warnings, List<CrmDtos.Lead> recycled) { }
}
