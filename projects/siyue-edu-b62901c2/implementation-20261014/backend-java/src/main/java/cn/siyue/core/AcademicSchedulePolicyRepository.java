package cn.siyue.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Applies persisted move limits and checks the effective student roster before a timetable move. */
@Repository
class AcademicSchedulePolicyRepository {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    AcademicSchedulePolicyRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    void validateMove(AcademicRepository.SessionScheduleRow session, Instant startsAt, Instant endsAt) {
        validateDayLimit(session.startsAt(), startsAt);
        List<Long> students = jdbc.query("SELECT DISTINCT roster.student_id FROM ("
            + "SELECT e.student_id FROM core_session_enrollment e WHERE e.session_id=? "
            + "AND NOT EXISTS(SELECT a.id FROM core_session_adjustment a WHERE a.enrollment_id=e.id "
            + "AND a.source_session_id=e.session_id AND a.status IN ('active','completed') AND a.deleted=0) "
            + "UNION SELECT e.student_id FROM core_effective_enrollment e JOIN core_session s "
            + "ON s.class_id=e.class_id WHERE s.id=? AND e.status='pending' AND e.deleted=0 "
            + "UNION SELECT a.student_id FROM core_session_adjustment a WHERE a.target_session_id=? "
            + "AND a.status IN ('active','completed') AND a.deleted=0) roster",
            (rs, row) -> rs.getLong(1), session.id(), session.id(), session.id());
        for (long studentId : students) {
            if (hasStudentConflict(studentId, session.id(), startsAt, endsAt)) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "学生在调整后的时间已有其他课程");
            }
        }
    }

    private void validateDayLimit(Instant originalStarts, Instant requestedStarts) {
        List<String> values = jdbc.query("SELECT value_cipher FROM core_system_setting WHERE section='move' "
            + "AND field_name='maxDays' AND deleted=0", (rs, row) -> cipher.decrypt(rs.getString(1)));
        if (values.isEmpty()) { return; }
        final int maximum;
        try { maximum = Integer.parseInt(values.get(0)); }
        catch (NumberFormatException error) {
            throw new BusinessException(ErrorCode.CONFIGURATION_UNAVAILABLE, "调课天数配置无效");
        }
        long distance = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.ofInstant(originalStarts, BUSINESS_ZONE), LocalDate.ofInstant(requestedStarts, BUSINESS_ZONE)));
        if (maximum < 0 || maximum > 365 || distance > maximum) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "调课超出配置天数");
        }
    }

    private boolean hasStudentConflict(long studentId, long currentSessionId, Instant startsAt, Instant endsAt) {
        Integer conflicts = jdbc.queryForObject("SELECT COUNT(DISTINCT conflict.session_id) FROM ("
            + "SELECT e.session_id FROM core_session_enrollment e JOIN core_session s ON s.id=e.session_id "
            + "JOIN core_class c ON c.id=s.class_id AND c.active=1 AND c.deleted=0 "
            + "WHERE e.student_id=? AND e.session_id<>? AND s.status='scheduled' AND s.deleted=0 "
            + "AND s.starts_at<? AND s.ends_at>? AND NOT EXISTS(SELECT a.id FROM core_session_adjustment a "
            + "WHERE a.enrollment_id=e.id AND a.source_session_id=s.id AND a.status IN ('active','completed') "
            + "AND a.deleted=0) UNION SELECT s.id FROM core_session_adjustment a JOIN core_session s "
            + "ON s.id=a.target_session_id JOIN core_class c ON c.id=s.class_id AND c.active=1 AND c.deleted=0 "
            + "WHERE a.student_id=? AND s.id<>? AND a.status IN ('active','completed') AND a.deleted=0 "
            + "AND s.status='scheduled' AND s.deleted=0 AND s.starts_at<? AND s.ends_at>?) conflict",
            Integer.class, studentId, currentSessionId, Timestamp.from(endsAt), Timestamp.from(startsAt),
            studentId, currentSessionId, Timestamp.from(endsAt), Timestamp.from(startsAt));
        return conflicts != null && conflicts > 0;
    }
}
