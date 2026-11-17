package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import cn.siyue.core.AcademicDtos.Communication;
import cn.siyue.core.AcademicDtos.CommunicationInput;
import cn.siyue.core.AcademicDtos.StudentPage;
import cn.siyue.core.AcademicDtos.Student;
import cn.siyue.core.AcademicDtos.StudentInput;
import cn.siyue.core.AcademicDtos.StudentUpdateInput;
import cn.siyue.core.AcademicDtos.EnrollmentInput;
import cn.siyue.core.AcademicDtos.EnrollmentReceipt;
import cn.siyue.core.AcademicDtos.EnrollmentBatchInput;
import cn.siyue.core.AcademicDtos.EnrollmentBatchReceipt;
import cn.siyue.core.AcademicDtos.TeachingMetrics;
import cn.siyue.core.AcademicDtos.AttendanceInput;
import cn.siyue.core.AcademicDtos.AttendanceRecord;
import cn.siyue.core.AcademicDtos.AttendanceBatchInput;
import cn.siyue.core.AcademicDtos.AttendanceBatchReceipt;
import cn.siyue.core.AcademicDtos.SessionScheduleInput;
import cn.siyue.core.AcademicDtos.SessionScheduleReceipt;
import cn.siyue.core.AcademicDtos.CourseInput;
import cn.siyue.core.AcademicDtos.CourseReceipt;
import cn.siyue.core.AcademicDtos.CoursePage;
import cn.siyue.core.AcademicDtos.ClassInput;
import cn.siyue.core.AcademicDtos.ClassUpdateInput;
import cn.siyue.core.AcademicDtos.ClassUpdateReceipt;
import cn.siyue.core.AcademicDtos.ClassReceipt;
import cn.siyue.core.AcademicDtos.ClassBatchInput;
import cn.siyue.core.AcademicDtos.ClassBatchReceipt;
import cn.siyue.core.AcademicDtos.ClassPage;
import cn.siyue.core.AcademicDtos.SessionRoster;

/**
 * Exposes academic student, enrollment, course, class, attendance and communication routes under /api/v2/academic.
 * Resolves the accepted JWT issuer/subject to current local grants and delegates business scope, mutations and
 * transaction boundaries to AcademicService.
 */
@RestController
@RequestMapping("/api/v2/academic")
@Validated
public class AcademicController {
    private final AccessRepository access;
    private final AcademicService service;
    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public AcademicController(AccessRepository access, AcademicService service) {
        this.access = access;
        this.service = service;
    }
    /**
     * Queries the active roster with optional class filter and bounded pagination.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring {@code student.read};
     *     repository scope remains authoritative
     * @param query class, view, and optional page filters; omitted page and size use service defaults
     * @return success response containing the bounded page of visible students
     * @throws BusinessException when no active local account matches the JWT, or when permission, view, or pagination
     *     validation fails
     */
    @GetMapping("/students")
    public ApiResponse<StudentPage> students(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.StudentQuery query) {
        return ApiResponse.success(service.students(actor(jwt), query));
    }
    /**
     * Reads scoped profiles regardless of current enrollment state.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring {@code student.read}
     * @param query optional page and size; defaults are page 1 and size 20
     * @return success response containing a bounded archive page under the actor's database scope
     * @throws BusinessException when no active local account matches the JWT, or when permission or pagination
     *     validation fails
     */
    @GetMapping("/students/archive")
    public ApiResponse<StudentPage> studentArchives(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.StudentArchiveQuery query) {
        return ApiResponse.success(service.studentArchives(actor(jwt), query));
    }
    /**
     * Reads a profile's append-only operation history without exposing audit trace IDs.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring {@code student.read}
     * @param studentId public ID of the profile whose history is requested
     * @param query optional page and size; defaults are page 1 and size 20
     * @return success response containing the visible profile-history page
     * @throws BusinessException when no active local account matches the JWT, or when permission, profile scope, or
     *     pagination validation fails
     */
    @GetMapping("/students/{studentId}/history")
    public ApiResponse<AcademicDtos.StudentHistoryPage> studentHistory(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String studentId,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.StudentArchiveQuery query) {
        return ApiResponse.success(service.studentHistory(actor(jwt), studentId, query));
    }
    /**
     * Lists only authorized academic choices needed by normal teaching and scheduling pages.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with at least one supported academic
     *     read or session-move permission
     * @param campusId optional campus public ID to scope the returned options
     * @return success response containing visible campuses, staff labels, and known room labels
     * @throws BusinessException when no active local account matches the JWT, or when the actor has no supported
     *     academic permission or campus scope is denied
     */
    @GetMapping("/options")
    public ApiResponse<AcademicDtos.AcademicOptions> options(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String campusId) {
        return ApiResponse.success(service.options(actor(jwt), campusId));
    }
    /**
     * Returns the student's authorized active classes as a bounded communication selector.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring {@code student.read}
     * @param studentId public ID of the student
     * @param query nonnull student-view and pagination fields; view may be omitted
     * @return success response containing the bounded class page visible to the actor
     * @throws BusinessException when no active local account matches the JWT, or when permission, student scope, view,
     *     or pagination validation fails
     */
    @GetMapping("/students/{studentId}/classes")
    public ApiResponse<AcademicDtos.StudentClassPage> studentClasses(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String studentId,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.StudentQuery query) {
        return ApiResponse.success(service.studentClasses(actor(jwt), studentId, query));
    }
    /**
     * Creates an employee-owned profile; names and phone numbers are encrypted at rest.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring student.write or
     *     student.create; assigned scope cannot create profiles
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input student name, phone, grade and campus; campus scope requires a campus identifier
     * @return success response containing created student or the original receipt for an identical retry
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permissions, campus
     *     scope, identity or retry-key checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/students")
    public ApiResponse<Student> createStudent(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody StudentInput input) throws JsonProcessingException {
        return ApiResponse.success(service.createStudent(actor(jwt), key, input));
    }
    /**
     * Updates a scoped profile and synchronizes its immutable source lead identity when present.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring student.write or
     *     student.update, rechecked after identity locking
     * @param studentId student public identifier within current update scope
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input profile patch and positive expected version; supplied name and mobile must be valid
     * @return success response containing updated student or the stored result after current update authorization is
     *     rechecked
     * @throws BusinessException when no active local account matches the JWT, or when input, active caller, permission,
     *     scope, identity, version or key checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/students/{studentId}")
    public ApiResponse<Student> updateStudent(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String studentId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody StudentUpdateInput input) throws JsonProcessingException {
        return ApiResponse.success(service.updateStudent(actor(jwt), studentId, key, input));
    }
    /**
     * Enrolls one visible student in an authorized class.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring enrollment.manage or
     *     enrollment.create, and crm.order.read
     * @param classId target class public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input student and order identifiers to validate against class enrollment terms
     * @return success response containing enrollment result or original retry receipt after current enrollment
     *     authorization
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permission,
     *     references, scope, order terms, capacity or key checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/classes/{classId}/enrollments")
    public ApiResponse<EnrollmentReceipt> enrollStudent(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String classId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody EnrollmentInput input) throws JsonProcessingException {
        return ApiResponse.success(service.enrollStudent(actor(jwt), classId, key, input));
    }
    /**
     * Creates a whole Node-compatible batch of pending enrollment orders atomically.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller whose current
     *     permissions and scope are rechecked
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input one class and either studentIds or rows (not both), with 1–500 unique valid rows; each row
     *     identifies an existing student or supplies a name and mobile number, never both coupon forms
     * @return success response containing one result per created pending enrollment/order, or the prior result for the
     *     same request key
     * @throws BusinessException when no active local account matches the JWT, or when permission, scope, row, identity,
     *     coupon, capacity, or key validation fails
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/enrollments/batch")
    public ApiResponse<EnrollmentBatchReceipt> enrollBatch(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody EnrollmentBatchInput input) throws JsonProcessingException {
        return ApiResponse.success(service.enrollBatch(actor(jwt), key, input));
    }
    /**
     * Returns authorized monthly class fullness, attendance and consumption totals.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring report.read
     * @param month calendar month in YYYY-MM form
     * @param view mine or scope; mine restricts facts to the actual session teacher
     * @return success response containing teaching metrics for the validated view and Shanghai calendar-month
     *     boundaries
     * @throws BusinessException when no active local account matches the JWT, or when permission, view or month
     *     validation fails
     */
    @GetMapping("/teaching-metrics")
    public ApiResponse<TeachingMetrics> teachingMetrics(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month,
            @RequestParam(defaultValue = "scope") @Pattern(regexp = "mine|scope") String view) {
        return ApiResponse.success(service.teachingMetrics(actor(jwt), month, view));
    }
    /**
     * Records one started-session roster member; external messaging is never triggered.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller whose active status
     *     and permission are rechecked
     * @param sessionId session public identifier
     * @param studentId student public identifier
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input class identifier, status ({@code attended}, {@code late}, {@code left_early}, {@code leave}, or
     *     {@code absent}), optional note up to 500 characters, and optional consumption flag
     * @return success response containing the appended attendance record
     * @throws BusinessException when no active local account matches the JWT, or when permission, membership/scope,
     *     input, or idempotency validation fails
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/sessions/{sessionId}/students/{studentId}/attendance")
    public ApiResponse<AttendanceRecord> attendance(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String sessionId,
            @PathVariable @Size(max = 120) String studentId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AttendanceInput input) throws JsonProcessingException {
        return ApiResponse.success(service.recordAttendance(actor(jwt), studentId, sessionId, key, input));
    }
    /**
     * Records a bounded roster in one transaction; any invalid row rolls the entire batch back.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring attendance.manage or
     *     session.attendance
     * @param sessionId session public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input class and 1–1000 distinct student rows containing status, note and optional consumption flag
     * @return success response containing records in input order, or the stored receipt after each member's current
     *     authorization
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permission, scope,
     *     duplicate rows, attendance or consumption rules fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/sessions/{sessionId}/attendance")
    public ApiResponse<AttendanceBatchReceipt> attendanceBatch(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String sessionId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AttendanceBatchInput input) throws JsonProcessingException {
        if (input == null || input.rows() == null || input.rows().stream().anyMatch(java.util.Objects::isNull)
                || input.rows().stream()
                .map(AcademicDtos.AttendanceBatchRow::studentId).distinct().count() != input.rows().size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"考勤名单为空或重复登记同一学生");
        }
        return ApiResponse.success(service.recordAttendanceBatch(actor(jwt), sessionId, key, input));
    }
    /**
     * Lists only roster members visible to the current teacher/class scope.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring attendance.read
     * @param sessionId session public identifier
     * @return success response containing scoped session roster and existing attendance records
     * @throws BusinessException when no active local account matches the JWT, or when permission is absent or the
     *     session is missing or outside scope
     */
    @GetMapping("/sessions/{sessionId}/roster")
    public ApiResponse<SessionRoster> sessionRoster(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String sessionId) {
        return ApiResponse.success(service.sessionRoster(actor(jwt), sessionId));
    }
    /**
     * Lists authorized, real student-to-session attendance facts for the cross-class view.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring both class.read and
     *     attendance.read
     * @param query nonnull attendance filters; page defaults to 1, size to 20 and course type to onsite
     * @return success response containing bounded attendance rows and total within current academic scope
     * @throws BusinessException when no active local account matches the JWT, or when permission, query, pagination or
     *     repository filter checks fail
     */
    @GetMapping("/attendance/students")
    public ApiResponse<AcademicDtos.AllStudentAttendancePage> allStudentAttendance(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.AllStudentAttendanceQuery query) {
        return ApiResponse.success(service.allStudentAttendance(actor(jwt),query));
    }
    /**
     * Exports the same date-bounded and filtered attendance facts without page truncation.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring both class.read and
     *     attendance.read
     * @param query nonnull attendance filters; normalization and pagination validation also apply to export
     * @return success response containing authorized attendance export subject to repository export limits
     * @throws BusinessException when no active local account matches the JWT, or when permission, query, filter or
     *     export-limit checks fail
     */
    @GetMapping("/attendance/students/export")
    public ApiResponse<AcademicDtos.AllStudentAttendanceExport> exportAllStudentAttendance(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.AllStudentAttendanceQuery query) {
        return ApiResponse.success(service.exportAllStudentAttendance(actor(jwt),query));
    }
    /**
     * Moves one future session without rewriting any attendance, consumption, or enrollment facts.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring session.move, rechecked
     *     after active-principal locking
     * @param sessionId session public identifier within schedule scope
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input proposed start, nonblank reason of at most 500 characters and positive expected version
     * @return success response containing changed schedule or the original receipt after current session authorization
     * @throws BusinessException when no active local account matches the JWT, or when input, permission, scope, key,
     *     version, protected history or schedule conflicts reject the move
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/sessions/{sessionId}/schedule")
    public ApiResponse<SessionScheduleReceipt> moveSession(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String sessionId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SessionScheduleInput input) throws JsonProcessingException {
        return ApiResponse.success(service.moveSession(actor(jwt), sessionId, key, input));
    }
    /**
     * Creates one typed course with a sequence of title-only lessons.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring course.write or
     *     course.create and authority over the requested campus
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input course classification, price, campus and teaching lesson definitions
     * @return success response containing created course or the stored result after current campus authorization
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permissions,
     *     campus, lesson definitions or key checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/courses")
    public ApiResponse<CourseReceipt> createCourse(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CourseInput input) throws JsonProcessingException {
        return ApiResponse.success(service.createCourse(actor(jwt), key, input));
    }
    /**
     * Pages active courses and ordered lessons under the actor's current scope.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring course.read or
     *     course.publish
     * @param query nonnull course filters with page 1–100000 and size 1–100; omitted values default to 1 and 50
     * @return success response containing filtered course page within current catalog scope
     * @throws BusinessException when no active local account matches the JWT, or when permission, pagination or
     *     repository filter validation fails
     */
    @GetMapping("/courses")
    public ApiResponse<CoursePage> courses(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.CourseQuery query) {
        return ApiResponse.success(service.courses(actor(jwt), query));
    }
    /**
     * Changes the name, year and ordered lesson titles; fixed classification is not writable.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring course.write or
     *     course.update and course write scope
     * @param courseId course public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input course fields, lessons and expected current version
     * @return success response containing updated draft course version, or original retry receipt; prior published
     *     snapshots remain unchanged
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permission, scope,
     *     key, version or protected teaching references reject editing
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/courses/{courseId}")
    public ApiResponse<AcademicDtos.CourseUpdateReceipt> updateCourse(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String courseId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicDtos.CourseUpdateInput input) throws JsonProcessingException {
        return ApiResponse.success(service.updateCourse(actor(jwt), courseId, key, input));
    }
    /**
     * Enables or disables a scoped course without deleting its related records.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring course.write or
     *     course.update and course write scope
     * @param courseId course public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input nonnull active flag and expected version; an unchanged status is rejected
     * @return success response containing incremented course version with teaching publication cleared, or the
     *     original retry receipt
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permission, scope,
     *     key, version or requested status checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/courses/{courseId}/status")
    public ApiResponse<AcademicDtos.CourseStatusReceipt> courseStatus(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String courseId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicDtos.CourseStatusInput input) throws JsonProcessingException {
        return ApiResponse.success(service.setCourseStatus(actor(jwt), courseId, key, input));
    }
    /**
     * Exports the classes and timetable rows matching the validated class-query filters.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring {@code class.read}; row
     *     scope is enforced by the repository
     * @param query campus, teacher, room, time range, search, and classification filters
     * @return success response containing the authorized class export
     * @throws BusinessException when no active local account matches the JWT, or when permission, view, date range, or
     *     filter validation fails
     */
    @GetMapping("/classes/export")
    public ApiResponse<AcademicDtos.ClassExport> exportClasses(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.ClassQuery query) {
        return ApiResponse.success(service.exportClasses(actor(jwt),query));
    }
    /**
     * Reads one class detail within the actor's class-read scope.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring {@code class.read}
     * @param classId public ID of the class
     * @return success response containing class details and its schedule
     * @throws BusinessException when no active local account matches the JWT, or when permission is denied or the class
     *     is outside the actor's scope
     */
    @GetMapping("/classes/{classId}")
    public ApiResponse<AcademicDtos.ClassDetail> classDetail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String classId) {
        return ApiResponse.success(service.classDetail(actor(jwt),classId));
    }
    /**
     * Lists current or historical student membership for one visible class.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring both {@code class.read}
     *     and {@code student.read}
     * @param classId public ID of the class
     * @param query membership filter ({@code active} or {@code history}), search, and optional pagination
     * @return success response containing the bounded class-student page
     * @throws BusinessException when no active local account matches the JWT, or when permission, class scope,
     *     membership filter, or pagination is invalid
     */
    @GetMapping("/classes/{classId}/students")
    public ApiResponse<AcademicDtos.ClassStudentPage> classStudents(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String classId,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.ClassStudentQuery query) {
        return ApiResponse.success(service.classStudents(actor(jwt),classId,query));
    }
    /**
     * Exports the visible student membership for one class using the same scope as the paged query.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring both {@code class.read}
     *     and {@code student.read}
     * @param classId public ID of the class
     * @param query active/history membership and optional search filters
     * @return success response containing the class-student export with the authorized result set
     * @throws BusinessException when no active local account matches the JWT, or when permission, class scope,
     *     membership filter, or search validation fails
     */
    @GetMapping("/classes/{classId}/students/export")
    public ApiResponse<AcademicDtos.ClassStudentExport> exportClassStudents(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String classId,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.ClassStudentExportQuery query) {
        return ApiResponse.success(service.exportClassStudents(actor(jwt),classId,query));
    }
    /**
     * Creates a class with class-owned duration and one schedule row per course lesson.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring class.write or
     *     class.create; non-all scope needs class.assign for another teacher
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input class, campus, course, teacher, capacity and schedule definitions
     * @return success response containing created class or original retry receipt after current creation authorization
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permissions, key,
     *     references, scope, capacity or schedule conflicts fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/classes")
    public ApiResponse<ClassReceipt> createClass(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ClassInput input) throws JsonProcessingException {
        return ApiResponse.success(service.createClass(actor(jwt), key, input));
    }
    /**
     * Creates up to fifty classes atomically with their conflict-checked schedules.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring class.write or
     *     class.batchCreate and each class's teacher/campus authority
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input validated class list with at most 400 total sessions; writes sort by campus, teacher and class name
     * @return success response containing class receipts in original input order and aggregate counts, or the
     *     identical retry's stored result
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permission, key,
     *     batch size, references, scope or schedule conflicts fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/classes/batch")
    public ApiResponse<ClassBatchReceipt> createClasses(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ClassBatchInput input) throws JsonProcessingException {
        return ApiResponse.success(service.createClasses(actor(jwt), key, input));
    }
    /**
     * Pages active classes and schedules under the actor's current scope.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring {@code class.read}; row
     *     scope is enforced by the repository
     * @param query campus, teacher, room, time range, search, and classification filters
     * @return success response containing the bounded class page matching the filters
     * @throws BusinessException when no active local account matches the JWT, or when permission, view, date range, or
     *     pagination is invalid
     */
    @GetMapping("/classes")
    public ApiResponse<ClassPage> classes(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute AcademicDtos.ClassQuery query) {
        return ApiResponse.success(service.classes(actor(jwt), query));
    }
    /**
     * Updates class metadata and safe future session teacher assignments with a version check.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring class.write or
     *     class.create; changing to another teacher requires class.assign
     * @param classId class public identifier within current write scope
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input name, teacher, capacity and class fields with expected current version
     * @return success response containing updated class or the stored result after current class and
     *     teacher-assignment authorization
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permissions, input,
     *     key, version, scope or scheduling/capacity rules fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/classes/{classId}")
    public ApiResponse<ClassUpdateReceipt> updateClass(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String classId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ClassUpdateInput input) throws JsonProcessingException {
        return ApiResponse.success(service.updateClass(actor(jwt), classId, key, input));
    }
    /**
     * Reads the last 100 communication records in an authorized active class.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring student.communication.read
     * @param studentId student public identifier
     * @param classId class public identifier used to verify authorized student membership
     * @return success response containing communication records visible through the authorized membership
     * @throws BusinessException when no active local account matches the JWT, or when permission, class/student scope or
     *     membership checks fail
     */
    @GetMapping("/students/{studentId}/communications")
    public ApiResponse<List<Communication>> communications(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String studentId,
            @RequestParam @Size(max = 120) String classId) {
        return ApiResponse.success(service.communications(actor(jwt), studentId, classId));
    }
    /**
     * Records a contact attempt; it does not invoke phone, WeChat or SMS providers.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller requiring student.communicate,
     *     rechecked after active-principal locking
     * @param studentId student public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input class, channel, content and optional follow-up time that cannot precede current time on a new write
     * @return success response containing appended communication or the original receipt after current membership
     *     authorization
     * @throws BusinessException when no active local account matches the JWT, or when active caller, permissions, key,
     *     membership or new follow-up time checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/students/{studentId}/communications")
    public ApiResponse<Communication> communicate(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String studentId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CommunicationInput input) throws JsonProcessingException {
        return ApiResponse.success(service.communicate(actor(jwt), studentId, key, input));
    }
    private Actor actor(Jwt jwt) {
        return access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
    }
}
