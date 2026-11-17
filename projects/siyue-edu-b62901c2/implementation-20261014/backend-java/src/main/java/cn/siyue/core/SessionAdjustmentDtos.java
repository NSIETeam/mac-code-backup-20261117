package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Versioned request and receipt contracts for makeup, temporary placement and waiver actions. */
public final class SessionAdjustmentDtos {
    private SessionAdjustmentDtos() { }
    /** 将购买权益的指定课次安排到另一课次，不改变永久报名身份。
     * @param enrollmentId 报名公开标识
     * @param sourceSessionId 原课次公开标识
     * @param targetClassId 目标班级公开标识
     * @param targetSessionId 目标课次公开标识
     * @param kind makeup补课或temporary临时调班，可由具体入口限定
     * @param reason 非空安排原因，至多500字符
     * @param enrollmentVersion 报名期望版本
     * @param sourceSessionVersion 原课次期望版本
     * @param targetSessionVersion 目标课次期望版本
     */
    public record AssignInput(@NotBlank @Size(max = 120) String enrollmentId,
            @NotBlank @Size(max = 120) String sourceSessionId,
            @NotBlank @Size(max = 120) String targetClassId,
            @NotBlank @Size(max = 120) String targetSessionId,
            @Pattern(regexp = "makeup|temporary") String kind,
            @NotBlank @Size(max = 500) String reason,
            @Min(1) long enrollmentVersion, @Min(1) long sourceSessionVersion,
            @Min(1) long targetSessionVersion) { }
    /** 明确放弃指定课次补课权益，保留原因与原课次关联。
     * @param enrollmentId 报名公开标识
     * @param sourceSessionId 原课次公开标识
     * @param reason 非空放弃原因，至多500字符
     * @param enrollmentVersion 报名期望版本
     * @param sourceSessionVersion 原课次期望版本
     */
    public record WaiveInput(@NotBlank @Size(max = 120) String enrollmentId,
            @NotBlank @Size(max = 120) String sourceSessionId,
            @NotBlank @Size(max = 500) String reason, @Min(1) long enrollmentVersion,
            @Min(1) long sourceSessionVersion) { }
    /** 取消既有调整记录的版本条件。
     * @param reason 非空取消原因，至多500字符
     * @param expectedVersion 非空且至少为1的调整期望版本
     */
    public record CancelInput(@NotBlank @Size(max = 500) String reason, @NotNull @Min(1) Long expectedVersion) { }
    /** 调整台账类型、状态及有界分页条件。
     * @param kind 可选makeup、temporary或waiver
     * @param status 可选all、active、cancelled、waived或completed
     * @param page 可选页码，范围1至100000
     * @param size 可选页大小，范围1至100
     */
    public record Query(@Pattern(regexp = "makeup|temporary|waiver") String kind,
            @Pattern(regexp = "all|active|cancelled|waived|completed") String status,
            @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size) { }
    /** 调整台账中的原课次、目标课次和当前状态投影。
     * @param adjustmentId 调整公开标识
     * @param kind 调整类型
     * @param status 调整当前状态
     * @param studentId 学员公开标识
     * @param studentName 学员姓名
     * @param enrollmentId 关联报名公开标识
     * @param sourceClassId 原班级标识
     * @param sourceSessionId 原课次标识
     * @param sourceStartsAt 原课次开始时刻
     * @param targetClassId 目标班级标识，放弃记录可为空
     * @param targetSessionId 目标课次标识，放弃记录可为空
     * @param targetStartsAt 目标课次时刻，可为空
     * @param version 当前版本
     * @param createdAt 调整创建时刻
     */
    public record Item(String adjustmentId, String kind, String status, String studentId, String studentName,
            String enrollmentId, String sourceClassId, String sourceSessionId, Instant sourceStartsAt,
            String targetClassId, String targetSessionId, Instant targetStartsAt, long version, Instant createdAt) { }
    /** 当前范围内的课次调整台账页。
     * @param items 本页调整
     * @param total 匹配总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record Page(List<Item> items, long total, int page, int size) { }
    /** 安排、放弃或取消课次调整的最小提交回执。
     * @param adjustmentId 调整公开标识
     * @param kind 调整类型
     * @param status 操作后状态
     * @param enrollmentId 报名公开标识
     * @param sourceSessionId 原课次标识
     * @param targetSessionId 目标课次标识，可为空
     * @param version 操作后版本
     * @param occurredAt 操作发生时刻
     */
    public record Receipt(String adjustmentId, String kind, String status, String enrollmentId,
            String sourceSessionId, String targetSessionId, long version, Instant occurredAt) { }
}
