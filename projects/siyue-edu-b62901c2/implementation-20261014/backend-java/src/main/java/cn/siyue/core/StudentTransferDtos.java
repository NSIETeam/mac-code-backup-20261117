package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Versioned permanent class placement contracts; purchased enrollment identity stays stable. */
public final class StudentTransferDtos {
    private StudentTransferDtos() { }
    /** 一次永久转班中选中的报名及其版本条件。
     * @param enrollmentId 购买权益报名公开标识，转班后保持不变
     * @param expectedVersion 当前报名期望版本，至少1
     */
    public record Item(@NotBlank @Size(max=120) String enrollmentId,@Min(1) long expectedVersion) { }
    /** 对选中报名执行同一目标班级的永久安置变更。
     * @param items 1至500条报名及版本，服务拒绝重复选择
     * @param targetClassId 目标班级公开标识
     * @param reason 非空转班原因，至多500字符
     */
    public record Input(@NotEmpty @Size(max=500) List<@NotNull @Valid Item> items,
            @NotBlank @Size(max=120) String targetClassId,@NotBlank @Size(max=500) String reason) { }
    /** 学员当前可转班报名及实际安置班级。
     * @param enrollmentId 稳定报名公开标识
     * @param classId 当前安置班级公开标识
     * @param className 当前班级名称
     * @param version 当前有效报名版本
     */
    public record Enrollment(String enrollmentId,String classId,String className,long version) { }
    /** 目标班级选择项，容量仍在转班事务内重新校验。
     * @param id 目标班级公开标识
     * @param name 班级名称
     * @param courseId 课程公开标识
     * @param courseName 课程名称
     * @param campusId 校区公开标识
     * @param capacity 班级容量
     * @param enrolledCount 查询时有效报名数量
     */
    public record Target(String id,String name,String courseId,String courseName,String campusId,int capacity,long enrolledCount) { }
    /** 学员可转报名及当前授权范围内的目标班级页。
     * @param studentId 学员公开标识
     * @param enrollments 可转报名及当前安置
     * @param targets 当前页目标班级
     * @param targetTotal 匹配目标班级总数
     * @param page 从1开始的目标页码
     * @param size 目标页大小
     */
    public record Options(String studentId,List<Enrollment> enrollments,List<Target> targets,long targetTotal,int page,int size) { }
    /** 单个报名永久转班的已提交事实。
     * @param enrollmentId 保持不变的报名公开标识
     * @param studentId 学员公开标识
     * @param fromClassId 转班前班级标识
     * @param targetClassId 转班后的班级标识
     * @param version 转班后版本
     * @param transferId 不可变转班记录标识
     */
    public record Result(String enrollmentId,String studentId,String fromClassId,String targetClassId,long version,String transferId) { }
    /** 同一事务内全部报名转班结果。
     * @param items 逐条转班结果
     * @param total 本次转班报名数量
     */
    public record Receipt(List<Result> items,int total) { }
}
