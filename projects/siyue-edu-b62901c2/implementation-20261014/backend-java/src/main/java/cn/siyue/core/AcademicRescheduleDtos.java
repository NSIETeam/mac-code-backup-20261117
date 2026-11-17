package cn.siyue.core;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

/** Explicit payloads and receipts for teacher-submitted schedule changes. */
public final class AcademicRescheduleDtos {
    private AcademicRescheduleDtos() { }
    /** 教师申请调整指定班级课次的本地日期/时间段、原因与预期课次版本，实际时区解释由服务执行。 */
    public record RequestInput(@NotBlank @Size(max = 120) String classId,
            @NotBlank @Size(max = 120) String sessionId, @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String date,
            @NotBlank @Size(max = 20) @Pattern(regexp = "\\d{2}:\\d{2}[–—-]\\d{2}:\\d{2}") String time,
            @NotBlank @Size(max = 500) String reason,
            @Min(1) long expectedVersion) { }
    /** 调课申请审核决定、意见及申请预期版本；具体决定枚举和审核资格由服务验证。 */
    public record ReviewInput(@NotBlank @Size(max = 16) String decision,
            @Size(max = 500) String note, @Min(1) long expectedVersion) { }
    /** 调课申请的状态、目标课次、拟调整开始时间及申请版本。 */
    public record RequestReceipt(String requestId, String status, String sessionId,
            Instant requestedStartsAt, long version) { }
    /** 调课审核回执及可选课次改期结果；拒绝申请不产生成功改期回执。 */
    public record ReviewReceipt(RequestReceipt request, AcademicDtos.SessionScheduleReceipt session) { }
    /** 调课申请状态与分页筛选，省略分页时第1页每页50条。 */
    public record ListQuery(String status, Integer page, Integer size) {
        /**
         * 补齐调课列表分页，不在构造时改变状态筛选。
         * @param status 可选申请状态
         * @param page 页码，null使用1
         * @param size 每页条数，null使用50
         */
        public ListQuery { page = page == null ? 1 : page; size = size == null ? 50 : size; }
    }
    /** 调课申请的原课次/班级快照、请求日期时间与审核链，原教师和原版本保留供冲突核对。 */
    public record RequestView(String requestId, String classId, String sessionId, String campusId,
            String applicantId, Instant originalStartsAt, Instant originalEndsAt, String originalTeacherId,
            String originalLessonId, String originalRoom, long originalSessionVersion, long originalClassVersion,
            Instant requestedStartsAt, String requestedDate, String requestedTime, String reason, String status,
            String reviewerId, Instant reviewedAt,
            String resolutionNote, long version, Instant createdAt) { }
    /** 当前审批/申请范围内的调课申请分页。 */
    public record RequestPage(List<RequestView> items, long total, int page, int size) { }
}
