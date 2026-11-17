package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Explicit legacy lead owner allocation contract. */
public final class LeadBatchAssignmentDtos {
    private LeadBatchAssignmentDtos() { }

    /** 线索配额策略中指定负责人的数量上限，数量范围0至10000。 */
    public record Quota(@NotBlank @Size(max = 120) String ownerId,
            @NotNull @jakarta.validation.constraints.Min(0)
            @jakarta.validation.constraints.Max(10000) Integer count) { }

    /** 旧CRM批量分配线索输入，逐线索提供预期版本，最多498条并附负责人、策略和原因。 */
    public record Input(@NotEmpty @Size(max = 498)
            List<@NotBlank @Size(max = 120) String> leadIds,
            @NotNull Map<@NotBlank @Size(max = 120) String, @NotNull @jakarta.validation.constraints.Min(1) Long> expectedVersions,
            @NotEmpty @Size(max = 100)
            List<@NotBlank @Size(max = 120) String> ownerIds,
            @NotBlank @Pattern(regexp = "even|quota|owner_first") String strategy,
            @Size(max = 100) List<@Valid Quota> quotas,
            @NotBlank @Size(max = 1000) String reason) {
        /**
         * 为程序内兼容调用构造版本均为1的分配输入；HTTP调用仍须显式提供真实expectedVersions。
         * @param leadIds 线索公开标识集合，版本映射按首次出现顺序生成
         * @param ownerIds 候选负责人公开标识集合
         * @param strategy 分配策略
         * @param quotas 可选配额集合
         * @param reason 分配原因
         * @throws NullPointerException leadIds为null
         */
        public Input(List<String> leadIds, List<String> ownerIds, String strategy,
                List<Quota> quotas, String reason) {
            this(leadIds, leadIds.stream().collect(java.util.stream.Collectors.toMap(
                id -> id, id -> 1L, (left, right) -> left, java.util.LinkedHashMap::new)),
                ownerIds, strategy, quotas, reason);
        }
    }

    /** 成功分配后的线索负责人、初筛/销售状态、分配跟进时限及新版本。 */
    public record AssignedLead(String leadId, String ownerId, String status, String stage,
            Instant assignedAt, Instant assignmentFollowAt, Instant nextFollowAt,
            String nextAction, long version) { }
    /** 批量分配中未写入的线索标识及明确跳过原因。 */
    public record SkippedLead(String leadId, String reason) { }
    /** 批量分配实际写入、跳过记录与负责人分配数量，不能替代逐条当前范围校验。 */
    public record Result(List<AssignedLead> records, List<SkippedLead> skipped,
            Map<String, Integer> assignmentCounts) { }
    /** 线索接收人员下拉选项，包含公开标识、显示名称及职称。 */
    public record OwnerChoice(String id, String displayName, String title) { }
    /** 当前线索集合可分配负责人的分页选择结果。 */
    public record OwnerOptions(List<OwnerChoice> items, long total, int page, int size) { }
    /** 按选中线索共同范围计算负责人选项的输入，最多498个线索公开标识。 */
    public record OwnerOptionsInput(@NotEmpty @Size(max = 498)
            List<@NotBlank @Size(max = 120) String> leadIds) { }
}
