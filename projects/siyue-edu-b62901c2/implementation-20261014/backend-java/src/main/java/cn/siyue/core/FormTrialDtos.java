package cn.siyue.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 公开表单试听配置、可预约席位和提交回执；预约容量仍由服务在事务内重新核验。 */
public final class FormTrialDtos {
    private FormTrialDtos() { }
    /** 绑定具体班级课次的预约选项，配置容量不能替代课次实际剩余容量。
     * @param id 表单内稳定选项标识，1至80位字母数字下划线连字符
     * @param classId 班级公开标识，非空且不超过120字符
     * @param sessionId 课次公开标识，非空且不超过120字符
     * @param capacity 选项配置容量，范围1至1000
     */
    public record SlotInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String id,
            @NotBlank @Size(max=120) String classId, @NotBlank @Size(max=120) String sessionId,
            @Min(1) @Max(1000) int capacity) { }
    /** 启用试听时的表单字段映射和选项；停用通过外层trial=null表达。
     * @param enabled 必须为true，不能用false携带残留配置
     * @param campusFieldId 可选校区字段标识，至多80字符
     * @param gradeFieldId 非空年级字段标识
     * @param subjectFieldId 非空学科字段标识
     * @param nameFieldId 非空姓名字段标识
     * @param phoneFieldId 非空手机号字段标识
     * @param slots 1至100个经验证的班级课次选项
     */
    public record Configuration(@AssertTrue boolean enabled, @Size(max=80) String campusFieldId,
            @NotBlank @Size(max=80) String gradeFieldId, @NotBlank @Size(max=80) String subjectFieldId,
            @NotBlank @Size(max=80) String nameFieldId, @NotBlank @Size(max=80) String phoneFieldId,
            @NotNull @Size(min=1,max=100) List<@Valid SlotInput> slots) { }
    /** 版本化替换表单试听配置。
     * @param trial 完整启用配置；null表示移除试听配置
     * @param expectedVersion 至少为1的表单期望版本
     */
    public record Update(@Valid Configuration trial, @Min(1) long expectedVersion) { }
    /** 员工配置试听时可选课次的范围化投影。
     * @param classId 班级公开标识
     * @param className 班级显示名
     * @param sessionId 课次公开标识
     * @param course 课程显示名
     * @param grade 年级显示值
     * @param subject 学科显示值
     * @param startsAt 课次开始时刻
     * @param endsAt 课次结束时刻
     * @param remaining 查询时可用席位数，提交前仍需重新核验
     */
    public record SlotChoice(String classId,String className,String sessionId,String course,String grade,String subject,java.time.Instant startsAt,java.time.Instant endsAt,int remaining) { }
    /** 可选试听课次分页结果。
     * @param items 当前页课次选项
     * @param total 匹配课次总数
     * @param page 从1开始的页码
     * @param size 页大小
     */
    public record SlotPage(List<SlotChoice> items,long total,int page,int size) { }
    /** 公开表单提交引用一个已有试听选项，不接受客户端指定容量或课次。
     * @param slotId 表单配置中的稳定选项标识，1至80位字母数字下划线连字符
     */
    public record BookingInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String slotId) { }
    /** 公开表单可披露的试听时段快照，不含教师或学员身份。
     * @param id 配置选项标识
     * @param campusId 校区公开标识
     * @param campus 校区显示名
     * @param course 课程显示名
     * @param grade 年级显示值
     * @param subject 学科显示值
     * @param date 预约当地日期
     * @param time 预约时段显示文本
     * @param remaining 查询时剩余预约数，非席位锁定承诺
     */
    public record PublicSlot(String id, String campusId, String campus, String course, String grade,
            String subject, java.time.LocalDate date, String time, int remaining) { }
    /** 公开表单试听展示配置及当前可选时段。
     * @param enabled 是否展示启用的试听预约
     * @param campusFieldId 校区字段映射，可为空
     * @param gradeFieldId 年级字段映射
     * @param subjectFieldId 学科字段映射
     * @param nameFieldId 姓名字段映射
     * @param phoneFieldId 手机号字段映射
     * @param slots 公开可选时段列表
     */
    public record PublicTrial(boolean enabled, String campusFieldId, String gradeFieldId,
            String subjectFieldId, String nameFieldId, String phoneFieldId, List<PublicSlot> slots) { }
    /** 已持久化预约的公开回执。
     * @param id 预约公开标识
     * @param status 预约当前状态
     * @param slot 预约所选时段的公开投影
     */
    public record BookingReceipt(String id, String status, PublicSlot slot) { }
}
