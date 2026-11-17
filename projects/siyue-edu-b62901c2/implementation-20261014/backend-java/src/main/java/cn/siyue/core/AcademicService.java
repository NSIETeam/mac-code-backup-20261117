package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import cn.siyue.core.AcademicDtos.Communication;
import cn.siyue.core.AcademicDtos.CommunicationInput;
import cn.siyue.core.AcademicDtos.AttendanceInput;
import cn.siyue.core.AcademicDtos.AttendanceRecord;
import cn.siyue.core.AcademicDtos.AttendanceBatchInput;
import cn.siyue.core.AcademicDtos.AttendanceBatchReceipt;
import cn.siyue.core.AcademicDtos.SessionScheduleInput;
import cn.siyue.core.AcademicDtos.SessionScheduleReceipt;
import cn.siyue.core.AcademicDtos.SessionRoster;
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
import cn.siyue.core.AcademicDtos.StudentPage;
import cn.siyue.core.AcademicDtos.Student;
import cn.siyue.core.AcademicDtos.StudentInput;
import cn.siyue.core.AcademicDtos.EnrollmentInput;
import cn.siyue.core.AcademicDtos.EnrollmentReceipt;
import cn.siyue.core.AcademicDtos.EnrollmentBatchInput;
import cn.siyue.core.AcademicDtos.EnrollmentBatchReceipt;
import cn.siyue.core.AcademicDtos.TeachingMetrics;

/** Coordinates scoped academic reads and transactional student, enrollment, course, class, and attendance writes.
 * Persistence helpers enforce row-level scope and locking; this service rechecks mutable actor permissions before
 * writes and records idempotency receipts for retryable mutations. Internal support runs on the same thread
 * and transaction; no helper creates an additional transaction boundary.
 */
@Service
public class AcademicService {
    private final AccessRepository access;
    private final AcademicRepository repository;
    private final AcademicCatalogRepository catalog;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    private AcademicBatchEnrollmentRepository batchEnrollments;
    private final AcademicServiceSupport support;
    /** Creates the service with local authorization, persistence, mutation, and JSON dependencies.
     * @param access active-principal lookup and lock used to recheck write permissions
     * @param repository student, enrollment, attendance, and communication persistence
     * @param catalog course/class persistence and scoped academic queries
     * @param mutations idempotency receipt and audit persistence
     * @param mapper serializer used to hash requests and persist replay receipts
     */
    public AcademicService(AccessRepository access, AcademicRepository repository, AcademicCatalogRepository catalog,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.catalog = catalog;
        this.mutations = mutations;
        this.mapper = mapper;
        this.support = new AcademicServiceSupport(access, repository, catalog, mutations, mapper);
    }
    /** Injects the optional batch workflow without widening the public constructor contract. */
    @Autowired
    void configureBatchEnrollments(AcademicBatchEnrollmentRepository batchEnrollments) {
        this.batchEnrollments = batchEnrollments;
    }
    /** Returns a page of active students visible through the actor's current database grants.
     * @param actor caller requiring {@code student.read}
     * @param classId optional class public ID used to restrict the roster
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return the bounded student page
     * @throws BusinessException when access is denied or pagination is outside the supported range
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public StudentPage students(Actor actor, String classId, int page, int size) {
        actor.require("student.read");
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分页范围无效");
        }
        return repository.students(actor, classId, page, size);
    }
    /** Returns a student page after validating the requested personal/team view and pagination.
     * @param actor caller requiring {@code student.read}; repository scope remains authoritative
     * @param query class, view, and optional page filters; omitted page and size use service defaults
     * @return the bounded page of visible students
     * @throws BusinessException when permission, view, or pagination validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public StudentPage students(Actor actor, AcademicDtos.StudentQuery query) {
        actor.require("student.read");
        return repository.students(actor, support.studentQuery(query));
    }
    /** Lists visible student profiles, including profiles without a current enrollment.
     * @param actor caller requiring {@code student.read} or the exact legacy {@code student.archive} grant
     * @param query optional page and size; defaults are page 1 and size 20
     * @return a bounded archive page under the actor's database scope
     * @throws BusinessException when permission or pagination validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public StudentPage studentArchives(Actor actor, AcademicDtos.StudentArchiveQuery query) {
        actor.requireAny("student.read", "student.archive");
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        support.validatePage(page, size);
        return repository.studentArchives(actor, page, size);
    }
    /** Reads a bounded append-only history for a student profile in the actor's CRM/student scope.
     * @param actor caller requiring {@code student.read}
     * @param studentId public ID of the profile whose history is requested
     * @param query optional page and size; defaults are page 1 and size 20
     * @return the visible profile-history page
     * @throws BusinessException when permission, profile scope, or pagination validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AcademicDtos.StudentHistoryPage studentHistory(Actor actor, String studentId,
            AcademicDtos.StudentArchiveQuery query) {
        actor.require("student.read");
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        support.validatePage(page, size);
        return repository.studentHistory(actor, studentId, page, size);
    }
    /** Supplies the active classes for which the student has an enrollment, for communication selection.
     * @param actor caller requiring {@code student.read}
     * @param studentId public ID of the student
     * @param query nonnull student-view and pagination fields; view may be omitted
     * @return the bounded class page visible to the actor
     * @throws BusinessException when permission, student scope, view, or pagination validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AcademicDtos.StudentClassPage studentClasses(Actor actor, String studentId, AcademicDtos.StudentQuery query) {
        actor.require("student.read");
        return catalog.studentClasses(actor, studentId, support.studentQuery(query));
    }
    /** Returns academic selector options without exposing OIDC identity data.
     * @param actor caller with at least one supported academic read or session-move permission
     * @param campusId optional campus public ID to scope the returned options
     * @return visible campuses, staff labels, and known room labels
     * @throws BusinessException when the actor has no supported academic permission or campus scope is denied
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AcademicDtos.AcademicOptions options(Actor actor, String campusId) {
        if (java.util.Collections.disjoint(actor.permissions(), java.util.Set.of("course.read", "course.publish", "course.write",
                "class.read", "class.write", "student.read", "report.read", "session.move"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少教务查看权限");
        }
        return catalog.options(actor, campusId);
    }
    /** Creates an owned student profile with scoped permission, idempotency and audit.
     * @param actor caller requiring student.write or student.create; assigned scope cannot create profiles
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input student name, phone, grade and campus; campus scope requires a campus identifier
     * @return created student or the original receipt for an identical retry
     * @throws BusinessException when active caller, permissions, campus scope, identity or retry-key checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Student createStudent(Actor actor, String key, StudentInput input)
            throws JsonProcessingException {
        String action = "academic.student.create";
        actor.requireAny("student.write", "student.create");
        support.requireIdempotencyKey(key);
        repository.lockCreateIdentity(input);
        actor = access.lockActiveShared(actor.id());
        actor.requireAny("student.write", "student.create");
        if (actor.scope().equals("assigned") || actor.scope().equals("campus")
                && (input.campusId() == null || input.campusId().isBlank())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号不能在此范围创建学生档案");
        }
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.reserveAndReplay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Student.class); }
        Student student = repository.createStudent(actor, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(student), student.id()));
        return student;
    }
    /** Internal CRM intake path; the enclosing customer transaction owns its idempotency receipt. */
    Student createStudent(Actor actor, StudentInput input) {
        actor.requireAny("student.write", "student.create");
        if (actor.scope().equals("assigned") || actor.scope().equals("campus")
                && (input.campusId() == null || input.campusId().isBlank())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号不能在此范围创建学生档案");
        }
        return repository.createStudent(actor, input);
    }
    /** Reserves a student identity before customer intake locks the acting principal row. */
    void lockStudentCreateIdentity(StudentInput input) { repository.lockCreateIdentity(input); }
    /** Updates one existing profile with exact action permission, optimistic version and idempotent audit.
     * @param actor caller requiring student.write or student.update, rechecked after identity locking
     * @param studentId student public identifier within current update scope
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input profile patch and positive expected version; supplied name and mobile must be valid
     * @return updated student or the stored result after current update authorization is rechecked
     * @throws BusinessException when input, active caller, permission, scope, identity, version or key checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Student updateStudent(Actor actor, String studentId, String key, AcademicDtos.StudentUpdateInput input)
            throws JsonProcessingException {
        String action = "academic.student.update";
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1
                || input.name() != null && input.name().isBlank()
                || input.phone() != null && !input.phone().trim().matches("1[3-9][0-9]{9}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "学生档案字段无效");
        }
        actor.requireAny("student.write", "student.update");
        support.requireIdempotencyKey(key);
        repository.lockUpdateIdentity(actor, studentId, input);
        actor = access.lockActive(actor.id());
        actor.requireAny("student.write", "student.update");
        repository.authorizeStudentUpdate(actor, studentId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(studentId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Student.class); }
        Student student = repository.updateStudent(actor, studentId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(student), student.id()));
        return student;
    }
    /** Enrolls a visible student in an authorized class with a capacity lock and audit.
     * @param actor caller requiring enrollment.manage or enrollment.create, and crm.order.read
     * @param classId target class public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input student and order identifiers to validate against class enrollment terms
     * @return enrollment result or original retry receipt after current enrollment authorization
     * @throws BusinessException when active caller, permission, references, scope, order terms, capacity or key checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public EnrollmentReceipt enrollStudent(Actor actor, String classId, String key, EnrollmentInput input)
            throws JsonProcessingException {
        String action = "academic.enrollment.create";
        actor.requireAny("enrollment.manage", "enrollment.create");
        actor.require("crm.order.read");
        support.requireIdempotencyKey(key);
        actor = access.lockActive(actor.id());
        actor.requireAny("enrollment.manage", "enrollment.create");
        actor.require("crm.order.read");
        repository.authorizeEnrollment(actor, classId, input.studentId(), input.orderId());
        String hash = mutations.hash(mapper.writeValueAsString(List.of(classId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, EnrollmentReceipt.class); }
        EnrollmentReceipt enrollment = repository.enrollStudent(actor, classId, input.studentId(), input.orderId());
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(enrollment), enrollment.id()));
        return enrollment;
    }
    /** Creates pending enrollments for a bounded set of existing students or validated student drafts.
     * The caller needs the exact legacy batch-create grant, or enrollment management plus order-read; opportunity and origin-lead
     * references additionally require their corresponding CRM permissions. Identity and class capacity checks,
     * writes, and the idempotency receipt are committed together.
     * @param actor authenticated caller whose current permissions and scope are rechecked
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input one class and either studentIds or rows (not both), with 1–500 unique valid rows; each row
     *              identifies an existing student or supplies a name and mobile number, never both coupon forms
     * @return one result per created pending enrollment/order, or the prior result for the same request key
     * @throws BusinessException when permission, scope, row, identity, coupon, capacity, or key validation fails
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public EnrollmentBatchReceipt enrollBatch(Actor actor, String key, EnrollmentBatchInput input)
            throws JsonProcessingException {
        return support.enrollBatch(actor, key, input, batchEnrollments);
    }
    /** Reports the requested Shanghai calendar month from UTC-stored session facts.
     * @param actor caller requiring report.read; repository scope limits included teaching facts
     * @param month calendar month in YYYY-MM form
     * @return scoped metrics between Shanghai midnight at this month's start and the next month's start
     * @throws BusinessException when report permission is absent or the month cannot be parsed
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TeachingMetrics teachingMetrics(Actor actor, String month) {
        return support.teachingMetrics(actor, month);
    }
    /** Personal monthly facts are filtered at the actual lesson teacher on the server.
     * @param actor caller requiring report.read
     * @param month calendar month in YYYY-MM form
     * @param view mine or scope; mine restricts facts to the actual session teacher
     * @return teaching metrics for the validated view and Shanghai calendar-month boundaries
     * @throws BusinessException when permission, view or month validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TeachingMetrics teachingMetrics(Actor actor, String month, String view) {
        return support.teachingMetrics(actor, month, view);
    }
    /** Returns the active roster and recorded attendance for one authorized lesson session.
     * @param actor caller requiring attendance.read
     * @param sessionId session public identifier
     * @return scoped session roster and existing attendance records
     * @throws BusinessException when permission is absent or the session is missing or outside scope
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public SessionRoster sessionRoster(Actor actor, String sessionId) {
        actor.require("attendance.read");
        return repository.sessionRoster(actor, sessionId);
    }
    /** Resolves a roster for an attendance write without requiring separate read-only access.
     * @param actor caller requiring attendance.manage or session.attendance
     * @param sessionId session public identifier
     * @return roster and existing attendance under the repository's session scope
     * @throws BusinessException when attendance-write permission or session visibility fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public SessionRoster attendanceExceptionRoster(Actor actor, String sessionId) {
        actor.requireAny("attendance.manage", "session.attendance");
        return repository.sessionRoster(actor, sessionId);
    }
    /** Lists real student/session attendance facts across authorized face-to-face classes.
     * @param actor caller requiring both class.read and attendance.read
     * @param query nonnull attendance filters; page defaults to 1, size to 20 and course type to onsite
     * @return bounded attendance rows and total within current academic scope
     * @throws BusinessException when permission, query, pagination or repository filter checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AcademicDtos.AllStudentAttendancePage allStudentAttendance(Actor actor,
            AcademicDtos.AllStudentAttendanceQuery query) {
        actor.require("class.read");actor.require("attendance.read");
        return catalog.allStudentAttendance(actor,support.attendanceQuery(query));
    }
    /** Exports the same complete authorized attendance result as the paged directory.
     * @param actor caller requiring both class.read and attendance.read
     * @param query nonnull attendance filters; normalization and pagination validation also apply to export
     * @return authorized attendance export subject to repository export limits
     * @throws BusinessException when permission, query, filter or export-limit checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AcademicDtos.AllStudentAttendanceExport exportAllStudentAttendance(Actor actor,
            AcademicDtos.AllStudentAttendanceQuery query) {
        actor.require("class.read");actor.require("attendance.read");
        return catalog.exportAllStudentAttendance(actor,support.attendanceQuery(query));
    }
    /** Reschedules one future session only when no attendance or consumption fact protects it.
     * @param actor caller requiring session.move, rechecked after active-principal locking
     * @param sessionId session public identifier within schedule scope
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input proposed start, nonblank reason of at most 500 characters and positive expected version
     * @return changed schedule or the original receipt after current session authorization
     * @throws BusinessException when input, permission, scope, key, version, protected history or schedule conflicts reject the move
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public SessionScheduleReceipt moveSession(Actor actor, String sessionId, String key,
            SessionScheduleInput input) throws JsonProcessingException {
        String action = "academic.session.reschedule";
        actor.require("session.move");
        support.requireIdempotencyKey(key);
        if(input==null||input.startsAt()==null||input.reason()==null||input.reason().isBlank()
                ||input.reason().length()>500||input.expectedVersion()<1)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"课次时间、原因或版本无效");
        actor = access.lockActive(actor.id());
        actor.require("session.move");
        AcademicRepository.SessionScheduleRow current = repository.scheduleRow(actor, sessionId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(sessionId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, SessionScheduleReceipt.class); }
        SessionScheduleReceipt updated = repository.moveSession(actor, current, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(updated), updated.id()));
        return updated;
    }
    /** Records one student's attendance and optional lesson consumption under row locks.
     * The caller needs attendance-management or session-attendance permission and must be authorized for the
     * specified student, class, and session. An identical idempotent retry returns the original record.
     * @param actor authenticated caller whose active status and permission are rechecked
     * @param studentId student public identifier
     * @param sessionId session public identifier
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input class identifier, status ({@code attended}, {@code late}, {@code left_early}, {@code leave},
     *              or {@code absent}), optional note up to 500 characters, and optional consumption flag
     * @return the appended attendance record
     * @throws BusinessException when permission, membership/scope, input, or idempotency validation fails
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AttendanceRecord recordAttendance(Actor actor, String studentId, String sessionId,
            String key, AttendanceInput input) throws JsonProcessingException {
        String action = "academic.attendance.create";
        actor.requireAny("attendance.manage", "session.attendance");
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
        actor = access.lockActive(actor.id());
        actor.requireAny("attendance.manage", "session.attendance");
        AcademicRepository.AttendanceMember member = repository.attendanceMember(actor,
            studentId, input.classId(), sessionId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(studentId, sessionId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, AttendanceRecord.class); }
        AttendanceRecord record = support.appendAttendance(actor, member,
            new AcademicServiceSupport.AttendanceWrite(sessionId, input.status(), input.note(), input.consume()));
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(record), record.id()));
        return record;
    }

    /** Records all unique roster members atomically with one idempotency key and audit transaction.
     * @param actor caller requiring attendance.manage or session.attendance
     * @param sessionId session public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input class and 1–1000 distinct student rows containing status, note and optional consumption flag
     * @return records in input order, or the stored receipt after each member's current authorization
     * @throws BusinessException when active caller, permission, scope, duplicate rows, attendance or consumption rules fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AttendanceBatchReceipt recordAttendanceBatch(Actor actor, String sessionId, String key,
            AttendanceBatchInput input) throws JsonProcessingException {
        return support.recordAttendanceBatch(actor, sessionId, key, input);
    }

    /** Creates a course and its lessons with permission, scope, idempotency and audit checks.
     * @param actor caller requiring course.write or course.create and authority over the requested campus
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input course classification, price, campus and teaching lesson definitions
     * @return created course or the stored result after current campus authorization
     * @throws BusinessException when active caller, permissions, campus, lesson definitions or key checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public CourseReceipt createCourse(Actor actor, String key, CourseInput input)
            throws JsonProcessingException {
        String action = "academic.course.create";
        actor.requireAny("course.write", "course.create");
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
        actor = access.lockActive(actor.id());
        actor.requireAny("course.write", "course.create");
        catalog.authorizeCourseCampus(actor, input.campusId());
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, CourseReceipt.class); }
        CourseReceipt course = catalog.createCourse(actor, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(course), course.id()));
        return course;
    }
    /** Edits mutable course fields under current write scope with audited retry receipts.
     * @param actor caller requiring course.write or course.update and course write scope
     * @param courseId course public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input course fields, lessons and expected current version
     * @return updated draft course version, or original retry receipt; prior published snapshots remain unchanged
     * @throws BusinessException when active caller, permission, scope, key, version or protected teaching references reject editing
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public AcademicDtos.CourseUpdateReceipt updateCourse(Actor actor, String courseId, String key,
            AcademicDtos.CourseUpdateInput input) throws JsonProcessingException {
        actor.requireAny("course.write", "course.update");
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
        actor = access.lockActive(actor.id()); actor.requireAny("course.write", "course.update");
        var current = catalog.lockCourseStatus(actor, courseId);
        String action = "academic.course.update";
        String hash = mutations.hash(mapper.writeValueAsString(new AcademicDtos.CourseUpdateCommand(courseId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, AcademicDtos.CourseUpdateReceipt.class); }
        var receipt = catalog.updateCourse(current, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(receipt), courseId));
        return receipt;
    }
    /** Changes course status under scope with version, retry and audit checks.
     * @param actor caller requiring course.write or course.update and course write scope
     * @param courseId course public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input nonnull active flag and expected version; an unchanged status is rejected
     * @return incremented course version with teaching publication cleared, or the original retry receipt
     * @throws BusinessException when active caller, permission, scope, key, version or requested status checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public AcademicDtos.CourseStatusReceipt setCourseStatus(Actor actor, String courseId, String key,
            AcademicDtos.CourseStatusInput input) throws JsonProcessingException {
        actor.requireAny("course.write", "course.update");
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}") || input.active() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "课程状态或幂等键无效");
        }
        actor = access.lockActive(actor.id()); actor.requireAny("course.write", "course.update");
        var current = catalog.lockCourseStatus(actor, courseId);
        String action = "academic.course.status";
        String hash = mutations.hash(mapper.writeValueAsString(new AcademicDtos.CourseStatusCommand(courseId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, AcademicDtos.CourseStatusReceipt.class); }
        var receipt = catalog.setCourseStatus(current, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(receipt), courseId));
        return receipt;
    }
    /** Pages active courses and lessons under the actor's current campus/teacher scope.
     * @param actor caller requiring course.read, course.publish, or product.publish for scoped read access
     * @param campusId optional campus public identifier filter
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return scoped active courses and their lesson definitions
     * @throws BusinessException when permission, pagination or course visibility validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CoursePage courses(Actor actor, String campusId, int page, int size) {
        actor.requireAny("course.read", "course.publish", "product.publish");
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分页范围无效");
        }
        return catalog.courses(actor, campusId, page, size);
    }
    /** Applies reference course classification and search before pagination.
     * @param actor caller requiring course.read, course.publish, or product.publish for scoped read access
     * @param query nonnull course filters with page 1–100000 and size 1–100; omitted values default to 1 and 50
     * @return filtered course page within current catalog scope
     * @throws BusinessException when permission, pagination or repository filter validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CoursePage courses(Actor actor, AcademicDtos.CourseQuery query) {
        actor.requireAny("course.read", "course.publish", "product.publish");
        int page = query.page() == null ? 1 : query.page();
        int size = query.size() == null ? 50 : query.size();
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分页范围无效");
        }
        return catalog.courses(actor, query);
    }
    /** Reads one class detail within the actor's class-read scope.
     * @param actor caller requiring {@code class.read} or the exact legacy {@code schedule.read} grant
     * @param classId public ID of the class
     * @return class details and its schedule
     * @throws BusinessException when permission is denied or the class is outside the actor's scope
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public AcademicDtos.ClassDetail classDetail(Actor actor,String classId) {
        actor.require("class.read");return catalog.classDetail(actor,classId);
    }
    /** Lists current or historical student membership for one visible class.
     * @param actor caller requiring both {@code class.read} and {@code student.read}
     * @param classId public ID of the class
     * @param query membership filter ({@code active} or {@code history}), search, and optional pagination
     * @return the bounded class-student page
     * @throws BusinessException when permission, class scope, membership filter, or pagination is invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public AcademicDtos.ClassStudentPage classStudents(Actor actor,String classId,AcademicDtos.ClassStudentQuery query) {
        actor.require("class.read");actor.require("student.read");
        int page=query.page()==null ? 1 : query.page(),size=query.size()==null ? 20 : query.size();support.validatePage(page,size);
        if(query.membership()!=null && !List.of("active","history").contains(query.membership())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"学生范围应为在班或历史");
        }
        return catalog.classStudents(actor,classId,query);
    }
    /** Exports the visible student membership for one class using the same scope as the paged query.
     * @param actor caller requiring both {@code class.read} and {@code student.read}
     * @param classId public ID of the class
     * @param query active/history membership and optional search filters
     * @return the class-student export with the authorized result set
     * @throws BusinessException when permission, class scope, membership filter, or search validation fails
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public AcademicDtos.ClassStudentExport exportClassStudents(Actor actor,String classId,AcademicDtos.ClassStudentExportQuery query) {
        actor.require("class.read");actor.require("student.read");
        if(query.membership()!=null && !List.of("active","history").contains(query.membership())
                || query.search()!=null && query.search().length()>100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"学生导出条件无效");
        }
        return catalog.exportClassStudents(actor,classId,query);
    }
    /** Returns a page of active classes and schedules under the actor's campus/teacher scope.
     * @param actor caller requiring {@code class.read} or the exact legacy {@code schedule.read} grant
     * @param campusId optional campus public ID filter
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return the bounded class page
     * @throws BusinessException when permission or pagination validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ClassPage classes(Actor actor, String campusId, int page, int size) {
        actor.requireAny("class.read", "schedule.read");
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分页范围无效");
        }
        return catalog.classes(actor, campusId, page, size);
    }
    /** Queries classes and timetable rows with validated server-side filters.
     * @param actor caller requiring {@code class.read} or {@code schedule.read}; row scope is enforced by the repository
     * @param query campus, teacher, room, time range, search, and classification filters
     * @return the bounded class page matching the filters
     * @throws BusinessException when permission, view, date range, or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ClassPage classes(Actor actor, AcademicDtos.ClassQuery query) {
        actor.requireAny("class.read", "schedule.read");
        return catalog.classes(actor,support.validatedClassQuery(query));
    }
    /** Exports the classes and timetable rows matching the validated class-query filters.
     * @param actor caller requiring {@code class.read}; row scope is enforced by the repository
     * @param query campus, teacher, room, time range, search, and classification filters
     * @return the authorized class export
     * @throws BusinessException when permission, view, date range, or filter validation fails
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public AcademicDtos.ClassExport exportClasses(Actor actor,AcademicDtos.ClassQuery query) {
        actor.require("class.read");
        return catalog.exportClasses(actor,support.validatedClassQuery(query));
    }
    /** Updates a class shell and only future, unrecorded session assignments atomically.
     * @param actor caller requiring class.write or class.create; changing to another teacher requires class.assign
     * @param classId class public identifier within current write scope
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input name, teacher, capacity and class fields with expected current version
     * @return updated class or the stored result after current class and teacher-assignment authorization
     * @throws BusinessException when active caller, permissions, input, key, version, scope or scheduling/capacity rules fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public ClassUpdateReceipt updateClass(Actor actor, String classId, String key, ClassUpdateInput input)
            throws JsonProcessingException {
        String action = "academic.class.update";
        actor.requireAny("class.write", "class.create");
        support.requireIdempotencyKey(key);
        if (input == null || input.expectedVersion() < 1 || input.name() == null || input.name().isBlank()
                || input.teacherId() == null
                || input.teacherId().isBlank() || input.capacity() < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "班级信息或版本无效");
        }
        actor = access.lockActive(actor.id());
        actor.requireAny("class.write", "class.create");
        AcademicCatalogRepository.ClassUpdateRow current = catalog.lockClass(actor, classId);
        if (!current.teacherPublicId().equals(input.teacherId())
                && !actor.publicId().equals(input.teacherId())) {
            actor.require("class.assign");
        }
        String hash = mutations.hash(mapper.writeValueAsString(List.of(classId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, ClassUpdateReceipt.class); }
        ClassUpdateReceipt updated = catalog.updateClass(actor, current, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(updated), updated.id()));
        return updated;
    }
    /** Creates an entire class and schedule atomically after checking classroom and teacher conflicts.
     * @param actor caller requiring class.write or class.create; non-all scope needs class.assign for another teacher
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input class, campus, course, teacher, capacity and schedule definitions
     * @return created class or original retry receipt after current creation authorization
     * @throws BusinessException when active caller, permissions, key, references, scope, capacity or schedule conflicts fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public ClassReceipt createClass(Actor actor, String key, ClassInput input)
            throws JsonProcessingException {
        String action = "academic.class.create";
        actor.requireAny("class.write", "class.create");
        support.requireTeacherAssignment(actor, input.teacherId());
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
        actor = access.lockActive(actor.id());
        actor.requireAny("class.write", "class.create");
        support.requireTeacherAssignment(actor, input.teacherId());
        catalog.authorizeClassCreation(actor, input);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, ClassReceipt.class); }
        ClassReceipt newClass = catalog.createClass(actor, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(newClass), newClass.id()));
        return newClass;
    }
    /** Creates a bounded set of classes atomically while acquiring shared resources in stable order.
     * @param actor caller requiring class.write or class.batchCreate and each class's teacher/campus authority
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input validated class list with at most 400 total sessions; writes sort by campus, teacher and class name
     * @return class receipts in original input order and aggregate counts, or the identical retry's stored result
     * @throws BusinessException when active caller, permission, key, batch size, references, scope or schedule conflicts fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public ClassBatchReceipt createClasses(Actor actor, String key, ClassBatchInput input)
            throws JsonProcessingException {
        return support.createClasses(actor, key, input);
    }
    /** Reads communication content only after permission and membership checks.
     * @param actor caller requiring student.communication.read
     * @param studentId student public identifier
     * @param classId class public identifier used to verify authorized student membership
     * @return communication records visible through the authorized membership
     * @throws BusinessException when permission, class/student scope or membership checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<Communication> communications(Actor actor, String studentId, String classId) {
        actor.require("student.communication.read");
        repository.member(actor, studentId, classId);
        return repository.communications(actor, studentId, classId);
    }
    /** Commits one append-only communication, retry record and audit event atomically.
     * @param actor caller requiring student.communicate, rechecked after active-principal locking
     * @param studentId student public identifier
     * @param key retry key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input class, channel, content and optional follow-up time that cannot precede current time on a new write
     * @return appended communication or the original receipt after current membership authorization
     * @throws BusinessException when active caller, permissions, key, membership or new follow-up time checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public Communication communicate(Actor actor, String studentId, String key,
            CommunicationInput input) throws JsonProcessingException {
        actor.require("student.communicate");
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
        actor = access.lockActive(actor.id());
        actor.require("student.communicate");
        AcademicRepository.Member member = repository.lockMember(actor, studentId, input.classId());
        String hash = mutations.hash(mapper.writeValueAsString(List.of(studentId, input)));
        String replay = mutations.replay(actor, key, hash);
        if (replay != null) { return mapper.readValue(replay, Communication.class); }
        if (input.nextFollowAt() != null && input.nextFollowAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "回访时间不能位于过去");
        }
        Communication record = new Communication("COMM_" + UUID.randomUUID(), studentId,
            input.classId(), actor.publicId(), input.channel(), input.content().trim(),
            input.nextFollowAt(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        repository.append(member, actor, record);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "student.communication.create", hash,
            mapper.writeValueAsString(record), record.id()));
        return record;
    }
}
