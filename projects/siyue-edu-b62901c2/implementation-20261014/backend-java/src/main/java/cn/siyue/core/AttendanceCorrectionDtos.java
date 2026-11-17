package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Audited attendance corrections with explicit optimistic concurrency. */
public final class AttendanceCorrectionDtos {
    private AttendanceCorrectionDtos() { }
    /** 更正已存在出勤的受控输入，与初次录入区分。
     * @param classId 当前办理班级公开标识
     * @param expectedVersion 出勤期望版本
     * @param status attended、late、left_early、leave或absent
     * @param note 可选新出勤备注，至多1000字符
     * @param reason 必填更正原因，至多500字符
     */
    public record Input(@NotBlank @Size(max=120) String classId,
            @Min(1) long expectedVersion,
            @NotBlank @Pattern(regexp="attended|late|left_early|leave|absent") String status,
            @Size(max=1000) String note, @NotBlank @Size(max=500) String reason) { }
    /** 通过路由指定的出勤目标身份。
     * @param studentId 学员公开标识
     * @param sessionId 课次公开标识
     */
    public record Target(String studentId,String sessionId) { }
    /** 出勤更正结果及关联课消金额，原更正审计另存。
     * @param id 出勤公开标识
     * @param correctionId 本次更正记录公开标识
     * @param classId 班级公开标识
     * @param sessionId 课次公开标识
     * @param studentId 学员公开标识
     * @param status 更正后的出勤状态
     * @param note 更正后的备注
     * @param version 递增的出勤版本
     * @param actorId 更正操作员工公开标识
     * @param correctedAt 更正发生时刻
     * @param consumptionAmount 更正后课消金额的十进制字符串
     */
    public record Receipt(String id,String correctionId,String classId,String sessionId,String studentId,
            String status,String note,long version,String actorId,Instant correctedAt,String consumptionAmount) { }
}
