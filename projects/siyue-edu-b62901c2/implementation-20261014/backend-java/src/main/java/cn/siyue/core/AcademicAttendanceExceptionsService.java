package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.siyue.core.AcademicDtos.AttendanceBatchInput;
import cn.siyue.core.AcademicDtos.AttendanceBatchRow;
import cn.siyue.core.AcademicDtos.AttendanceException;
import cn.siyue.core.AcademicDtos.AttendanceExceptionInput;
import cn.siyue.core.AcademicDtos.AttendanceExceptionReceipt;
import cn.siyue.core.AcademicDtos.AttendanceRecord;
import cn.siyue.core.AcademicDtos.SessionRoster;
import cn.siyue.core.AcademicDtos.SessionStudent;

/** Applies one exception list to the current scoped roster, treating omitted students as attended.
 * New attendance and corrections to existing rows participate in the same rollback-on-exception transaction;
 * each operation retains its own audit/history and the outer receipt includes the roster used for retry hashing.
 * Assigned teachers may register only sessions on the current Asia/Shanghai business date.
 */
@Service
public class AcademicAttendanceExceptionsService {
    private static final Set<String> EXCEPTION_STATUSES = Set.of("late", "left_early", "leave", "absent");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final AccessRepository access;
    private final AcademicService academic;
    private final AttendanceCorrectionService corrections;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    private record RecordContext(Actor actor, String sessionId, String classId, String key) { }
    private record AttendanceCounts(int present, int leave, int absent) { }

    /** Connects roster authorization, attendance creation/correction and the shared retry journal.
     * @param access database principal lookup and active-account locking
     * @param academic scoped roster reads and creation of missing attendance rows
     * @param corrections version-checked correction of existing attendance and immutable correction history
     * @param mutations request hashing, encrypted idempotency receipts and audit persistence
     * @param mapper request and receipt JSON serializer
     */
    public AcademicAttendanceExceptionsService(AccessRepository access, AcademicService academic,
            AttendanceCorrectionService corrections, MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.academic = academic;
        this.corrections = corrections;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Registers the current roster atomically, with explicit exceptions and attended defaults.
     * The session must have started, remain scheduled and belong to the supplied class; its roster must contain
     * 1–1000 students. Replays recheck the active account and roster; changing the request or roster changes its hash.
     * @param actor caller with attendance.manage or session.attendance, reloaded under the principal lock
     * @param sessionId public identifier of a session visible in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input selected class and up to 1000 distinct roster-student exceptions with late, left_early, leave
     *              or absent status; omitted students become attended and an absent note becomes empty text
     * @return attendance records, total roster size and separate attended, leave and absent counts;
     *         late and left_early remain in the records and total but not those three counts
     * @throws BusinessException for missing/revoked permission, invisible or ineligible session, teacher-date
     *         restriction, invalid or duplicate exceptions, correction/version failure or conflicting retry key
     * @throws JsonProcessingException if request hashing, child-operation or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public AttendanceExceptionReceipt record(Actor actor, String sessionId, String key,
            AttendanceExceptionInput input) throws JsonProcessingException {
        actor.requireAny("attendance.manage", "session.attendance");
        validate(key, input);
        Actor current = access.lockActive(actor.id());
        current.requireAny("attendance.manage", "session.attendance");
        SessionRoster roster = requireRoster(current, sessionId, input.classId());
        Map<String, AttendanceException> exceptions = indexExceptions(input, roster);
        String action = "academic.attendance.exceptions";
        String hash = requestHash(sessionId, input, roster);
        String replay = mutations.replay(current, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, AttendanceExceptionReceipt.class); }

        RecordContext context = new RecordContext(current, sessionId, input.classId(), key);
        List<AttendanceRecord> records = recordRoster(context, roster.students(), exceptions);
        AttendanceCounts counts = count(records);
        AttendanceExceptionReceipt receipt = new AttendanceExceptionReceipt(records, records.size(),
            counts.present(), counts.leave(), counts.absent());
        mutations.complete(current, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(receipt), sessionId));
        return receipt;
    }

    private SessionRoster requireRoster(Actor actor, String sessionId, String classId) {
        SessionRoster roster = academic.attendanceExceptionRoster(actor, sessionId);
        if (!roster.classId().equals(classId) || roster.students().isEmpty() || roster.students().size() > 1000
                || !"scheduled".equals(roster.status()) || roster.startsAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "当前课次未开始、名单为空或不属于所选班级");
        }
        if ("assigned".equals(actor.scope())
                && !LocalDate.ofInstant(roster.startsAt(), BUSINESS_ZONE).equals(LocalDate.now(BUSINESS_ZONE))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "教师只能登记北京时间当天课次的考勤");
        }
        return roster;
    }

    private Map<String, AttendanceException> indexExceptions(AttendanceExceptionInput input, SessionRoster roster) {
        Set<String> rosterIds = new HashSet<>();
        roster.students().forEach(student -> rosterIds.add(student.id()));
        Map<String, AttendanceException> result = new HashMap<>();
        for (AttendanceException exception : input.exceptions()) {
            if (!EXCEPTION_STATUSES.contains(exception.status()) || !rosterIds.contains(exception.studentId())
                    || result.putIfAbsent(exception.studentId(), exception) != null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "异常名单重复、状态无效或学生不在当前课次");
            }
        }
        return result;
    }

    private String requestHash(String sessionId, AttendanceExceptionInput input, SessionRoster roster)
            throws JsonProcessingException {
        List<String> rosterIds = roster.students().stream().map(SessionStudent::id).toList();
        return mutations.hash(mapper.writeValueAsString(List.of(sessionId, input, rosterIds)));
    }

    private List<AttendanceRecord> recordRoster(RecordContext context, List<SessionStudent> students,
            Map<String, AttendanceException> exceptions) throws JsonProcessingException {
        List<AttendanceRecord> created = createMissingRows(context, students, exceptions);
        Map<String, AttendanceRecord> createdByStudent = new HashMap<>();
        for (AttendanceRecord record : created) { createdByStudent.put(record.studentId(), record); }
        List<AttendanceRecord> records = new ArrayList<>();
        for (SessionStudent student : students) {
            records.add(recordStudent(context, student, exceptions.get(student.id()), createdByStudent));
        }
        return List.copyOf(records);
    }

    private List<AttendanceRecord> createMissingRows(RecordContext context, List<SessionStudent> students,
            Map<String, AttendanceException> exceptions) throws JsonProcessingException {
        List<AttendanceBatchRow> rows = new ArrayList<>();
        for (SessionStudent student : students) {
            if (student.attendanceId() == null) { rows.add(attendanceRow(student, exceptions.get(student.id()))); }
        }
        if (rows.isEmpty()) { return List.of(); }
        AttendanceBatchInput input = new AttendanceBatchInput(context.classId(), rows);
        return academic.recordAttendanceBatch(context.actor(), context.sessionId(), childKey(context.key(), "new-roster"),
            input).items();
    }

    private AttendanceRecord recordStudent(RecordContext context, SessionStudent student,
            AttendanceException exception, Map<String, AttendanceRecord> createdByStudent)
            throws JsonProcessingException {
        String status = exception == null ? "attended" : exception.status();
        String note = exception == null || exception.note() == null ? "" : exception.note();
        AttendanceRecord record = student.attendanceId() == null
            ? createdByStudent.get(student.id())
            : correctExisting(context, student, status, note);
        if (record == null) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "考勤名单已变化"); }
        return record;
    }

    private AttendanceCounts count(List<AttendanceRecord> records) {
        int present = 0, leave = 0, absent = 0;
        for (AttendanceRecord record : records) {
            if (record.status().equals("attended")) { present++; }
            if (record.status().equals("leave")) { leave++; }
            if (record.status().equals("absent")) { absent++; }
        }
        return new AttendanceCounts(present, leave, absent);
    }

    private AttendanceBatchRow attendanceRow(SessionStudent student, AttendanceException exception) {
        return new AttendanceBatchRow(student.id(), exception == null ? "attended" : exception.status(),
            exception == null ? null : exception.note());
    }

    private AttendanceRecord correctExisting(RecordContext context, SessionStudent student,
            String status, String note) throws JsonProcessingException {
        long version = student.attendanceVersion() == null ? 1 : student.attendanceVersion();
        var target = new AttendanceCorrectionDtos.Target(student.id(), context.sessionId());
        var input = new AttendanceCorrectionDtos.Input(context.classId(), version, status, note, "异常考勤批量登记");
        var correction = corrections.correct(context.actor(), target, childKey(context.key(), student.id()), input);
        return new AttendanceRecord(correction.id(), context.classId(), context.sessionId(), student.id(),
            correction.status(), correction.note(), correction.actorId(), correction.correctedAt());
    }

    private String childKey(String key, String suffix) {
        return "att-ex-" + mutations.hash(key + ":" + suffix);
    }

    private void validate(String key, AttendanceExceptionInput input) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}") || input == null
                || input.classId() == null || input.classId().isBlank() || input.exceptions() == null
                || input.exceptions().size() > 1000 || input.exceptions().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "考勤异常参数或幂等键无效");
        }
    }
}
