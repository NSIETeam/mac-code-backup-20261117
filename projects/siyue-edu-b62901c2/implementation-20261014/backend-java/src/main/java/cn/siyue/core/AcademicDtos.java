package cn.siyue.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Explicit DTOs keep database columns, sensitive fields and user input separate. */
public final class AcademicDtos {
    private AcademicDtos() { }
    /** 学员分页结果；总数与列表使用相同权限范围，页码从1开始。 */
    public record StudentPage(List<Student> items, long total, int page, int size) { }
    /** 学员档案变更的追加式审计时间线分页，包含总数及当前页信息。 */
    public record StudentHistoryPage(List<StudentHistoryEvent> items, long total, int page, int size) { }
    /** 档案审计事件的动作、发生时间和操作者公开标识，不包含原始敏感字段差异。 */
    public record StudentHistoryEvent(String action, Instant occurredAt, String actorId) { }
    /** 学员档案列表分页输入；页码范围1至100000，每页1至100条。 */
    public record StudentArchiveQuery(@Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size) { }
    /** 学员列表的个人/授权范围、可选班级与分页筛选，筛选不会扩大数据权限。 */
    public record StudentQuery(@Pattern(regexp = "mine|scope") String view,
            @Size(max = 120) String classId, @Min(1) @Max(100000) Integer page,
            @Min(1) @Max(100) Integer size) { }
    /** Bounded student schedule query; dates are a half-open business-time window no longer than 31 days. */
    public record StudentScheduleQuery(@Pattern(regexp = "mine|scope") String view,
            @Size(max = 120) String studentId,
            @Size(max = 120) String campusId, @Size(max = 120) String teacherId,
            @Size(max = 100) String room,
            @NotNull
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant fromAt,
            @NotNull @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant untilAt, @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size) { }
    /** One authorized student's scheduled lesson with only schedule-relevant student and teaching fields. */
    public record StudentScheduleItem(String studentId, String studentName, String classId, String className,
            String courseName, String campusId, String room, String teacherId, String sessionId,
            String lessonTitle, int sequence, Instant startsAt, Instant endsAt, String sessionStatus,
            long sessionVersion, String attendanceStatus, String adjustmentKind) { }
    /** Paged schedule rows; total counts matching student-session rows, not a claim of complete workspace data. */
    public record StudentSchedulePage(List<StudentScheduleItem> items, long total, int page, int size) { }
    /** 班级目录组合筛选：校区、教师、教室、时间、课程分类与运行阶段；空字段不附加该筛选。 */
    public record ClassQuery(@Pattern(regexp = "mine|scope") String view,
            @Size(max = 120) String campusId, @Size(max = 120) String teacherId,
            @Size(max = 100) String room,
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant fromAt,
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant untilAt, @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size,
            @Size(max=100) String search,@Pattern(regexp="onsite|live") String courseType,
            @Pattern(regexp="group|one_to_one|one_to_many") String format,@Min(2000) @Max(2100) Integer year,
            @Size(max=40) String term,@Size(max=40) String subject,@Size(max=40) String grade,
            @Size(max=40) String classType,@Pattern(regexp="long_term|short_term|trial") String courseKind,
            @Pattern(regexp="open|upcoming|current|finished") String phase) { }
    /** 教务筛选器的公开标识及显示名称选项，不承载授权信息。 */
    public record AcademicOption(String id, String name) { }
    /** 按校区区分的教室名称选项，避免将同名教室视为同一资源。 */
    public record RoomOption(String campusId, String name) { }
    /** 当前读取范围内的校区、教师和教室选项集合。 */
    public record AcademicOptions(List<AcademicOption> campuses, List<AcademicOption> teachers,
            List<RoomOption> rooms) { }
    /** 学员关联班级的简要投影，保留班级、校区及教师公开标识。 */
    public record StudentClass(String id, String name, String campusId, String teacherId) { }
    /** 学员关联班级的范围受限分页结果及总数。 */
    public record StudentClassPage(List<StudentClass> items, long total, int page, int size) { }
    /** 学员摘要及来源/负责人关系；联系方式由调用方投影为脱敏显示，公开标识沿用历史身份。 */
    public record Student(String id, String name, String grade, String phone, long version,
            String campusId, String originLeadId, String customerId, String ownerId, String assigneeId) {
        /** 构造不携带校区、来源和负责人关系的兼容学员摘要。
         * @param id 学员公开标识
         * @param name 姓名显示值
         * @param grade 年级
         * @param phone 按调用端已处理的联系方式显示值
         * @param version 学员行版本
         */
        public Student(String id, String name, String grade, String phone, long version) {
            this(id, name, grade, phone, version, null, null, null, null, null);
        }
    }
    /** 新建学员的姓名、手机号及可选年级、校区和来源线索；身份重复及范围另由服务端校验。 */
    public record StudentInput(@NotBlank @Size(max = 80) String name,
            @Size(max = 40) String grade,
            @NotBlank @Pattern(regexp = "\\s*1[3-9][0-9]{9}\\s*") String phone,
            @Size(max = 120) String campusId, @Size(max = 120) String originLeadId) {
        /** 构造未指定校区和来源线索的新学员输入，业务校验在提交阶段执行。
         * @param name 学员姓名
         * @param grade 可选年级
         * @param phone 报名手机号
         */
        public StudentInput(String name, String grade, String phone) {
            this(name, grade, phone, null, null);
        }
    }
    /** 带预期行版本的学员字段更新输入；姓名、手机号、校区和来源关系均受原业务规则约束。 */
    public record StudentUpdateInput(@Size(max = 80) String name,
            @Size(max = 40) String grade,
            @Pattern(regexp = "\\s*1[3-9][0-9]{9}\\s*") String phone,
            @Size(max = 120) String campusId, @Size(max = 120) String originLeadId,
            @NotNull @Min(1) Long expectedVersion) { }
    /** 将既有学员与已具备报名条件的订单绑定到路由指定班级的输入；不提供客户端应收金额。 */
    public record EnrollmentInput(@NotBlank @Size(max = 120) String studentId,
            @NotBlank @Size(max = 120) String orderId) { }
    /** 报名结果的学员、班级、状态及激活时间；激活时间可在待生效状态下为空。 */
    public record EnrollmentReceipt(String id, String studentId, String classId,
            String status, Instant activatedAt) { }
    /** 批量报名中待创建学员的身份草稿，来源线索及校区仍须服务端核对。 */
    public record EnrollmentStudentDraft(@NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = "\\s*1[3-9][0-9]{9}\\s*") String phone,
            @Size(max = 40) String grade, @Size(max = 120) String campusId,
            @Size(max = 120) String originLeadId) { }
    /** 批量报名的一名学员配置；可关联商机、已有学员或草稿，并覆盖批次级券和推荐人配置。 */
    public record EnrollmentBatchRow(@Size(max = 120) String opportunityId,
            @Size(max = 120) String studentId, @Valid EnrollmentStudentDraft studentDraft,
            @Size(max = 120) String couponId, @Size(min = 24, max = 128) String couponClaimToken,
            @Size(max = 120) String referrerStudentId) { }
    /** 旧报名操作兼容输入：studentIds与rows二选一；券和推荐人默认值适用于没有行级覆盖的学员。 */
    public record EnrollmentBatchInput(@NotBlank @Size(max = 120) String classId,
            @Size(min = 1, max = 500) List<@NotBlank @Size(max = 120) String> studentIds,
            @Size(min = 1, max = 500) List<@NotNull @Valid EnrollmentBatchRow> rows,
            @Size(max = 120) String couponId, @Size(min = 24, max = 128) String couponClaimToken,
            @Size(max = 120) String referrerStudentId) { }
    /** 一名学员的批量报名结果，关联学员、报名和订单；amount为以元计的应收金额。 */
    public record EnrollmentBatchItemReceipt(Student student, EnrollmentReceipt enrollment,
            String orderId, java.math.BigDecimal amount) { }
    /** 原子批量报名的逐学员回执集合；集合本身不表示已完成实收。 */
    public record EnrollmentBatchReceipt(List<EnrollmentBatchItemReceipt> results) { }
    /** 记录学员在指定班级的沟通内容、渠道及可选下次跟进时间，不触发消息发送。 */
    public record CommunicationInput(
        @NotBlank @Size(max = 120) String classId,
        @NotBlank @Pattern(regexp = "phone|wecom|text") String channel,
        @NotBlank @Size(max = 2000) String content,
        Instant nextFollowAt) { }
    /** 授权范围内的追加式学员沟通记录，包含操作者、创建时间及下次跟进时间。 */
    public record Communication(String id, String studentId, String classId, String actorId,
                                String channel, String content, Instant nextFollowAt, Instant createdAt) { }
    /** 按权限范围计算的月度班级、学员、出勤和课消指标；比例为0至100百分数，课消金额以元计。 */
    public record TeachingMetrics(String month, long classCount, long studentCount,
            long fullClassCount, BigDecimal fullClassRate, BigDecimal seatOccupancyRate,
            Map<String, Long> attendance, long consumptionCount, BigDecimal consumptionAmount) { }
    /** 月度教学统计使用的月份、个人/授权视图及UTC起止边界。 */
    public record TeachingPeriod(String month, String view, Instant fromAt, Instant untilAt) { }
    /** 单名学员的班级出勤事实；consume可显式控制课消，省略时按既有出勤规则处理。 */
    public record AttendanceInput(@NotBlank @Size(max = 120) String classId,
            @NotBlank @Pattern(regexp = "attended|late|left_early|leave|absent") String status,
            @Size(max = 500) String note, Boolean consume) {
        /** 构造使用默认课消判定规则的单人点名输入。
         * @param classId 班级公开标识
         * @param status 出勤状态
         * @param note 可选点名备注
         */
        public AttendanceInput(String classId, String status, String note) { this(classId, status, note, null); }
    }
    /** 同课次原子点名的一名学员状态和备注，可选consume仍受课消业务条件约束。 */
    public record AttendanceBatchRow(@NotBlank @Size(max = 120) String studentId,
            @NotBlank @Pattern(regexp = "attended|late|left_early|leave|absent") String status,
            @Size(max = 500) String note, Boolean consume) {
        /** 构造使用默认课消判定规则的批量点名项。
         * @param studentId 学员公开标识
         * @param status 出勤状态
         * @param note 可选点名备注
         */
        public AttendanceBatchRow(String studentId, String status, String note) { this(studentId, status, note, null); }
    }
    /** 指定班级的一批点名输入；同一学员不可重复，实际事务行预算另由服务校验。 */
    public record AttendanceBatchInput(@NotBlank @Size(max = 120) String classId,
            @jakarta.validation.constraints.NotEmpty @Size(max = 1000)
            List<@NotNull @jakarta.validation.Valid AttendanceBatchRow> rows) { }
    /** 仅列出异常学员的点名输入；有效名册中省略的学员按到课处理。 */
    public record AttendanceExceptionInput(@NotBlank @Size(max = 120) String classId,
            @Size(max = 1000) List<@NotNull @jakarta.validation.Valid AttendanceException> exceptions) { }
    /** 异常点名项，只允许迟到、早退、请假或缺席，并可附备注。 */
    public record AttendanceException(@NotBlank @Size(max = 120) String studentId,
            @NotBlank @Pattern(regexp = "late|left_early|leave|absent") String status,
            @Size(max = 500) String note) { }
    /** 已经记录的课次出勤事实及操作者和时间；后续更正须使用独立审计流程。 */
    public record AttendanceRecord(String id, String classId, String sessionId, String studentId,
            String status, String note, String actorId, Instant createdAt) { }
    /** 原子点名完成后返回的逐学员出勤事实。 */
    public record AttendanceBatchReceipt(List<AttendanceRecord> items) { }
    /** 异常模式点名回执及学生、出席、请假、缺席人数汇总。 */
    public record AttendanceExceptionReceipt(List<AttendanceRecord> items, int students, int present,
            int leave, int absent) { }
    /** 跨班级学员课次考勤筛选，仅线下课程；支持时间、校区、教师、搜索及已记/未记状态。 */
    public record AllStudentAttendanceQuery(@Size(max=120) String campusId,
            @Size(max=120) String teacherId,
            @Pattern(regexp="onsite") String courseType,
            @Pattern(regexp="recorded|unrecorded") String attendanceStatus,
            @Size(max=100) String search,
            @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant fromAt,
            @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant untilAt,
            @Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer size) { }
    /** 真实学员与课次关系的考勤投影，区分是否已记录和具体考勤结果，联系方式按查询端脱敏。 */
    public record AllStudentAttendance(String classId,String sessionId,String campusId,String campusName,
            String courseName,String className,Instant startsAt,String teacherId,
            String studentId,String studentName,String phone,String attendanceStatus,String attendanceOutcome) { }
    /** 跨班学员考勤分页及同范围总数。 */
    public record AllStudentAttendancePage(List<AllStudentAttendance> items,long total,int page,int size) { }
    /** 受导出条数上限约束的跨班学员考勤集合及匹配总数。 */
    public record AllStudentAttendanceExport(List<AllStudentAttendance> items,long total) { }
    /** 移动尚未开始且无出勤记录的课次所需的新开始时间、审核原因和预期版本。 */
    public record SessionScheduleInput(@NotNull Instant startsAt,
            @NotBlank @Size(max = 500) String reason, @Min(1) long expectedVersion) { }
    /** 课次改期结果及更新后的起止时间和行版本。 */
    public record SessionScheduleReceipt(String id, String classId, Instant startsAt,
            Instant endsAt, long version) { }
    /** 课次名册中的学员、出勤及报名版本信息；课消金额为十进制字符串，不接收客户端计费或披露订单身份。 */
    public record SessionStudent(String id, String name, String grade, String phone,
            String attendanceId, String attendanceStatus, String attendanceNote,
            String attendanceActorId, Instant attendanceAt, String consumptionState, String consumptionAmount,
            Long attendanceVersion, String enrollmentId, Long enrollmentVersion) { }
    /** 单课次的班级、时间、状态、版本及按当前报名关系生成的学员名册。 */
    public record SessionRoster(String id, String classId, Instant startsAt, Instant endsAt,
            String status, List<SessionStudent> students, long version) { }
    /** 新建课程中的讲次标题输入；参考课程另受30字符标题规则约束。 */
    public record LessonInput(@NotBlank @Size(max = 100) String title) { }
    /** 参考课程分类，包含年份、学期、班型、课程周期和授课形式；形式不等同onsite/live渠道。 */
    public record CourseMetadata(@NotNull @Min(2000) @Max(2100) Integer year,
            @NotBlank @Size(max = 40) String term,
            @Size(max = 40) String classType,
            @NotBlank @Pattern(regexp = "long_term|short_term|trial") String courseKind,
            @NotBlank @Pattern(regexp = "face|intention|one_to_one|one_to_many") String format) { }
    /** 课程创建内容及有序讲次；金额以元计，metadata提供时启用参考课程的附加业务校验。 */
    public record CourseInput(@Size(max = 120) String campusId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "onsite|live") String courseType,
            @NotBlank @Size(max = 40) String grade,
            @NotBlank @Size(max = 40) String subject,
            @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal price,
            @jakarta.validation.constraints.NotEmpty @Size(max = 200) List<@jakarta.validation.Valid LessonInput> lessons,
            @jakarta.validation.Valid
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            CourseMetadata metadata) {
        /** 检查参考课程讲次标题上限，不替代其他Bean Validation约束。
         * @return 未提供metadata或lessons时为true；否则仅在每项非空、标题非空且不超过30字符时为true
         */
        @jakarta.validation.constraints.AssertTrue(message = "讲次名称不能超过30字")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isReferenceLessonTitlesValid() {
            return metadata == null || lessons == null || lessons.stream()
                .allMatch(lesson -> lesson != null && lesson.title() != null && lesson.title().length() <= 30);
        }
    }
    /** 课程讲次的稳定公开标识、顺序和标题投影。 */
    public record LessonSummary(String id, int sequence, String title) { }
    /** 课程内容、价格、行版本及讲次回执；metadata和active为空时不输出相应JSON字段。 */
    public record CourseReceipt(String id, String campusId, String name, String courseType, String grade,
            String subject, BigDecimal price, long version, List<LessonSummary> lessons,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            CourseMetadata metadata,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Boolean active) { }
    /** 按同一授权与筛选口径计算的课程分页及总数。 */
    public record CoursePage(List<CourseReceipt> items, long total, int page, int size) { }
    /** 课程目录的名称、分类、校区、启用状态和分页筛选；筛选在统计与分页前应用。 */
    public record CourseQuery(@Size(max = 120) String campusId, @Size(max = 100) String search,
            @Pattern(regexp = "face|intention|one_to_one|one_to_many") String format,
            @Min(2000) @Max(2100) Integer year, @Size(max = 40) String term,
            @Size(max = 40) String grade, @Size(max = 40) String subject,
            @Size(max = 40) String classType,
            @Pattern(regexp = "long_term|short_term|trial") String courseKind,
            @Min(1) @Max(100000) Integer page, @Min(1) @Max(100) Integer size,
            @Pattern(regexp = "onsite|live") String courseType,
            @Pattern(regexp = "active|inactive|all") String status) { }
    /** 课程名称、年份、讲次列表及可选价格的版本化修改；保留既有公开课程身份和可对应讲次身份。 */
    public record CourseUpdateInput(@NotBlank @Size(max = 100) String name,
            @Min(2000) @Max(2100) Integer year, @Min(1) long expectedVersion,
            @jakarta.validation.constraints.NotEmpty @Size(max = 200)
            List<@NotNull @jakarta.validation.Valid CourseLessonUpdate> lessons,
            @DecimalMin("0.00") @Digits(integer=16,fraction=2)
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            BigDecimal price) {
        /** 构造不修改课程价格的兼容更新输入。
         * @param name 更新后的课程名称
         * @param year 课程年份
         * @param expectedVersion 调用方读取的当前课程版本
         * @param lessons 更新后的有序讲次标题列表
         */
        public CourseUpdateInput(String name,Integer year,long expectedVersion,List<CourseLessonUpdate> lessons) {
            this(name,year,expectedVersion,lessons,null);
        }
    }
    /** 编辑课程时的讲次标题，长度不超过30字符。 */
    public record CourseLessonUpdate(@NotBlank @Size(max = 30) String title) { }
    /** 将课程公开标识与更新输入绑定，用于服务端幂等摘要。 */
    public record CourseUpdateCommand(String courseId, CourseUpdateInput input) { }
    /** 课程修改后的名称、年份、价格、版本及有效讲次列表。 */
    public record CourseUpdateReceipt(String id,String name,Integer year,long version,
            List<LessonSummary> lessons,BigDecimal price) {
        /** 构造未附价格字段的兼容课程更新回执。
         * @param id 课程公开标识
         * @param name 更新后的名称
         * @param year 课程年份
         * @param version 更新后的行版本
         * @param lessons 有效讲次摘要
         */
        public CourseUpdateReceipt(String id,String name,Integer year,long version,List<LessonSummary> lessons) {
            this(id,name,year,version,lessons,null);
        }
    }
    /** 单独切换课程启用状态的输入，必须提交当前行版本。 */
    public record CourseStatusInput(@NotNull Boolean active, @Min(1) long expectedVersion) { }
    /** 课程标识与启用状态输入组成的幂等命令内容。 */
    public record CourseStatusCommand(String courseId, CourseStatusInput input) { }
    /** 课程启用状态变更后的公开标识、状态和版本。 */
    public record CourseStatusReceipt(String id, boolean active, long version) { }
    /** 开班课表中的一节课：指定课程讲次和UTC开始时间，结束时间由班级时长计算。 */
    public record ClassSessionInput(@NotBlank @Size(max = 120) String lessonId,
            @NotNull Instant startsAt) { }
    /** 班级整期学费、材料费及明确选择的退费规则；金额以元计并按JSON字符串编码。 */
    public record ClassFees(@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            @NotNull @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal courseFee,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            @NotNull @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal materialFee,
            @NotBlank @Pattern(regexp="unused_lessons|first_n_full|last_n_none") String courseRefundRule,
            @Min(1) @Max(200) Integer refundLessonCount,
            @NotBlank @Pattern(regexp="after_enrollment|after_start") String materialRefundRule) {
        /** 检查退费规则是否需要讲次数阈值；数值范围仍由字段注解校验。
         * @return unused_lessons规则且阈值为空，或其他规则且阈值非空时为true
         */
        @jakarta.validation.constraints.AssertTrue(message="退款规则讲次数无效")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isThresholdValid() {
            return "unused_lessons".equals(courseRefundRule) ? refundLessonCount == null : refundLessonCount != null;
        }
    }
    /** 开班所需课程、校区、教师、容量、课时时长及课表；可选fees保存班级专属收费条款。 */
    public record ClassInput(@NotBlank @Size(max = 120) String courseId,
            @NotBlank @Size(max = 120) String campusId,
            @NotBlank @Size(max = 120) String teacherId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String room,
            @Min(1) @Max(10000) int capacity,
            @Min(1) @Max(1440) int durationMinutes,
            @jakarta.validation.constraints.NotEmpty @Size(max = 200)
            List<@jakarta.validation.Valid ClassSessionInput> schedule,
            @jakarta.validation.Valid
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ClassFees fees) {
    }
    /** 原子批量开班输入，最多50个班级；实际课次数及事务写入量仍由服务端限制。 */
    public record ClassBatchInput(@jakarta.validation.constraints.NotEmpty @Size(max = 50)
            List<@NotNull @jakarta.validation.Valid ClassInput> classes) { }
    /** 批量开班回执及创建班级数、课次数汇总。 */
    public record ClassBatchReceipt(List<ClassReceipt> classes, int classCount, int sessionCount) { }
    /** 班级名称、教室、教师和容量的版本化更新；课程及校区变更使用独立业务流程。 */
    public record ClassUpdateInput(@NotBlank @Size(max = 100) String name,
            @Size(max = 100) String room,
            @NotBlank @Size(max = 120) String teacherId,
            @Min(1) @Max(10000) int capacity,
            @Min(1) long expectedVersion) { }
    /** 班级基本设置变更后的教师、容量、教室与行版本。 */
    public record ClassUpdateReceipt(String id,String name,String room,String teacherId,
            int capacity,long version) { }
    /** 课次名册中的学生人数及尚未登记出勤的人数，用于待点名提示。 */
    public record SessionAttendanceCounts(long studentCount,long unrecordedCount) { }
    /** 课次有效校区与教室投影；空值不输出到JSON。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SessionLocation(String campusId, String room) { }
    /** 课次起止时间、行版本及可选考勤计数，通过所属摘要平铺为JSON属性。 */
    public record SessionSchedule(Instant startsAt, Instant endsAt, long version,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            SessionAttendanceCounts attendance) { }
    /** 班级课次摘要，把时间/考勤及校区/教室子记录平铺到JSON，同时保留讲次与教师标识。 */
    public record ClassSessionSummary(String id, String lessonId, String teacherId,
            @com.fasterxml.jackson.annotation.JsonUnwrapped SessionSchedule schedule,
            @com.fasterxml.jackson.annotation.JsonUnwrapped SessionLocation location) {
        /** 构造默认版本1、无考勤计数且未附地点的兼容课次摘要。
         * @param id 课次公开标识
         * @param lessonId 课程讲次公开标识
         * @param teacherId 教师公开标识
         * @param startsAt UTC开始时间
         * @param endsAt UTC结束时间
         */
        public ClassSessionSummary(String id, String lessonId, String teacherId, Instant startsAt, Instant endsAt) {
            this(id, lessonId, teacherId, new SessionSchedule(startsAt, endsAt, 1, null),
                new SessionLocation(null, null));
        }
        /** 读取平铺时间记录的开始时间。
         * @return UTC开始时间
         * @throws NullPointerException 直接构造摘要时将schedule置为空而后调用此访问器
         */
        public Instant startsAt() { return schedule.startsAt(); }
        /** 读取平铺时间记录的结束时间。
         * @return UTC结束时间
         * @throws NullPointerException 直接构造摘要时将schedule置为空而后调用此访问器
         */
        public Instant endsAt() { return schedule.endsAt(); }
        /** 读取课次的乐观锁版本。
         * @return 当前课次行版本
         * @throws NullPointerException 直接构造摘要时将schedule置为空而后调用此访问器
         */
        public long version() { return schedule.version(); }
        /** 读取课次考勤汇总。
         * @return 考勤汇总，未查询时为空
         * @throws NullPointerException 直接构造摘要时将schedule置为空而后调用此访问器
         */
        public SessionAttendanceCounts attendance() { return schedule.attendance(); }
        /** 读取课次有效校区。
         * @return 校区公开标识，兼容摘要可为空
         * @throws NullPointerException 直接构造摘要时将location置为空而后调用此访问器
         */
        public String campusId() { return location.campusId(); }
        /** 读取课次教室显示值。
         * @return 教室名称，兼容摘要可为空
         * @throws NullPointerException 直接构造摘要时将location置为空而后调用此访问器
         */
        public String room() { return location.room(); }
    }
    /** 班级目录统计：课程分类、报名计数和未取消课表计数，以及最早开课与最晚结束时间。 */
    public record ClassCatalog(String courseName,String courseType,String grade,String subject,CourseMetadata metadata,
            long enrolledCount,long registeredCount,long sessionCount,long startedCount,
            Instant firstStartsAt,Instant lastEndsAt) { }
    /** 班级当前配置、课表与可选收费条款/目录统计的完整回执。 */
    public record ClassReceipt(String id, String courseId, String campusId, String teacherId,
            String name, String room, int capacity, int durationMinutes, long version,
            List<ClassSessionSummary> schedule,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ClassFees fees,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ClassCatalog catalog) {
    }
    /** 课次与课程讲次顺序、标题的关联，供班级详情展示。 */
    public record ClassLesson(ClassSessionSummary session,int sequence,String title) { }
    /** 班级配置详情与对应讲次课表集合。 */
    public record ClassDetail(ClassReceipt info,List<ClassLesson> lessons) { }
    /** 班级学员当前/历史报名范围及身份搜索、分页条件。 */
    public record ClassStudentQuery(@Pattern(regexp="active|history") String membership,
            @Size(max=100) String search,@Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer size) { }
    /** 受服务端导出条数限制的班级列表与匹配总数。 */
    public record ClassExport(List<ClassReceipt> items,long total) { }
    /** 班级学员导出的当前/历史报名范围与搜索条件，不含分页。 */
    public record ClassStudentExportQuery(@Pattern(regexp="active|history") String membership,@Size(max=100) String search) { }
    /** 班级学员导出的班级标识、使用的筛选条件、学员列表及总数。 */
    public record ClassStudentExport(String classId,String membership,String search,List<ClassStudent> items,long total) { }
    /** 班级学员摘要，包含脱敏联系方式及关联报名状态和版本。 */
    public record ClassStudent(String id,String name,String grade,String phone,String enrollmentId,String enrollmentStatus,long version) { }
    /** 班级学员分页，同时返回当前与历史报名计数及目标班级身份。 */
    public record ClassStudentPage(List<ClassStudent> items,long total,int page,int size,long activeCount,long historyCount,String classId) { }
    /** 班级分页与可选在读汇总；enrolledTotal未计算时为空且不输出。 */
    public record ClassPage(List<ClassReceipt> items, long total, int page, int size,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Long enrolledTotal) {
        /** 构造未计算在读汇总的兼容班级分页结果。
         * @param items 当前页班级
         * @param total 同条件班级总数
         * @param page 从1开始的页码
         * @param size 每页条数
         */
        public ClassPage(List<ClassReceipt> items,long total,int page,int size) { this(items,total,page,size,null); }
    }
}
