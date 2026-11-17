package cn.siyue.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Explicit payloads for the legacy whole-class and per-session academic operations. */
public final class AcademicClassRuntimeDtos {
    private AcademicClassRuntimeDtos() { }
    /** 整班课表编辑输入及班级预期版本；服务端保留不可修改课次并核对讲次/时间冲突。 */
    public record ScheduleInput(@Min(1) long expectedVersion,
            @Size(max = 200) List<@NotNull ScheduleSession> sessions) { }
    /** 课表编辑项；省略sessionId时先按讲次匹配未取消课次，无匹配才新增；讲次和开始时间必填。 */
    public record ScheduleSession(@Size(max = 120) String sessionId,
            @NotBlank @Size(max = 120) String lessonId, @NotNull Instant startsAt) { }
    /** 课表编辑后的班级版本及有效课次摘要。 */
    public record ScheduleReceipt(String classId, long version, List<AcademicDtos.ClassSessionSummary> sessions) { }
    /** 同一事务迁移最多100个班级到指定校区与教室的输入，每个班级单独携带预期版本。 */
    public record RelocateInput(@NotEmpty @Size(max = 100) List<@NotNull @jakarta.validation.Valid RelocateClass> classes,
            @NotBlank @Size(max = 120) String campusId, @NotBlank @Size(max = 100) String room) { }
    /** 批量迁址中的单个班级公开标识与预期版本。 */
    public record RelocateClass(@NotBlank @Size(max = 120) String classId, @Min(1) long expectedVersion) { }
    /** 成功迁址的班级数及目标校区、教室，不表示创建新班级。 */
    public record RelocateReceipt(int classCount, String campusId, String room) { }
    /** 单课次代课教师、必填原因及课次预期版本；不修改整班默认教师。 */
    public record SubstituteInput(@NotBlank @Size(max = 120) String teacherId,
            @NotBlank @Size(max = 500) String reason, @Min(1) long expectedVersion) { }
    /** 代课设置后的课次、有效教师公开标识和行版本。 */
    public record SubstituteReceipt(String sessionId, String teacherId, long version) { }
    /** 单课次教学记录正文及课次预期版本，正文最多10000字符。 */
    public record RecordInput(@NotBlank @Size(max = 10000) String content, @Min(1) long expectedVersion) { }
    /** 新增教学记录的公开标识、课次、操作者和记录时间，并返回课次更新后版本。 */
    public record RecordReceipt(String id, String sessionId, String actorId, Instant recordedAt, long version) { }
}
