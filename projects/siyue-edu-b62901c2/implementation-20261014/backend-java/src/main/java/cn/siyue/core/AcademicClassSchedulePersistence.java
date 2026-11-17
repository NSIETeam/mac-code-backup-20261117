package cn.siyue.core;

import cn.siyue.core.AcademicDtos.ClassInput;
import cn.siyue.core.AcademicDtos.ClassSessionSummary;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** Persists class creation and its one-session-per-lesson timetable. */
final class AcademicClassSchedulePersistence {
    private final JdbcTemplate jdbc;

    AcademicClassSchedulePersistence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record LessonLink(long id, String publicId, int sequence, String title) { }
    record InstantRange(Instant start, Instant end) {
        boolean overlaps(InstantRange other) {
            return start.isBefore(other.end()) && end.isAfter(other.start());
        }
    }
    record ClassInsertPlan(ClassInput input, long courseId, long campusId, long teacherId,
            String classPublicId, String room) { }

    void lockClassResources(long campusId, long teacherId, String name) {
        // Lock order is campus, then teacher, before checking duplicate names.
        List<Long> activeCampuses = jdbc.query("""
            SELECT id FROM core_campus WHERE id = ? AND active = 1 AND deleted = 0 FOR UPDATE
            """, (rs, row) -> rs.getLong("id"), campusId);
        List<Long> activeTeachers = jdbc.query("""
            SELECT id FROM core_principal WHERE id = ? AND active = 1 AND deleted = 0 FOR UPDATE
            """, (rs, row) -> rs.getLong("id"), teacherId);
        if (activeCampuses.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "校区已停用");
        }
        if (activeTeachers.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "老师账号已停用");
        }
        Integer duplicateName = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_class WHERE campus_id = ? AND name = ? AND active = 1 AND deleted = 0
            """, Integer.class, campusId, name);
        if (duplicateName != null && duplicateName > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该校区已有同名班级");
        }
    }

    List<LessonLink> courseLessons(long courseId) {
        return jdbc.query("""
            SELECT l.id,l.public_id,l.sequence_no,l.title FROM core_lesson l
            JOIN core_course c ON c.id = l.course_id AND c.active = 1 AND c.deleted = 0
            WHERE c.id = ? AND l.deleted = 0 ORDER BY l.sequence_no
            """, (rs, row) -> new LessonLink(rs.getLong("id"), rs.getString("public_id"),
                rs.getInt("sequence_no"), rs.getString("title")), courseId);
    }

    List<InstantRange> validateSchedule(ClassInput input, List<LessonLink> lessons,
            long teacherId, long campusId, String room) {
        Set<String> lessonIds = new HashSet<>();
        lessons.forEach(lesson -> lessonIds.add(lesson.publicId()));
        Set<String> requestedLessons = new HashSet<>();
        for (var session : input.schedule()) {
            if (!requestedLessons.add(session.lessonId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "排课包含重复讲次");
            }
            if (!lessonIds.contains(session.lessonId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "排课讲次不属于所选课程");
            }
        }
        if (lessons.isEmpty() || requestedLessons.size() != lessons.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "每个课程讲次必须且只能排课一次");
        }
        List<InstantRange> requestedRanges = new ArrayList<>();
        for (var session : input.schedule()) {
            Instant start = session.startsAt().truncatedTo(ChronoUnit.MILLIS);
            Instant end = start.plusSeconds(input.durationMinutes() * 60L);
            if (!start.isAfter(Instant.now())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "新班级课次必须安排在未来");
            }
            InstantRange range = new InstantRange(start, end);
            rejectOverlapOrConflict(requestedRanges, range, teacherId, campusId, room);
            requestedRanges.add(range);
        }
        return List.copyOf(requestedRanges);
    }

    private void rejectOverlapOrConflict(List<InstantRange> ranges, InstantRange range,
            long teacherId, long campusId, String room) {
        if (ranges.stream().anyMatch(old -> old.overlaps(range))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "班级内课次时间不能重叠");
        }
        if (hasScheduleConflict(range, teacherId, campusId, room)) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "老师或教室在该时段已有排课");
        }
    }

    private boolean hasScheduleConflict(InstantRange range, long teacherId, long campusId, String room) {
        Integer conflicts = jdbc.queryForObject("""
            SELECT COUNT(ss.id) FROM core_session ss
            JOIN core_class c ON c.id = ss.class_id AND c.active = 1 AND c.deleted = 0
            WHERE ss.deleted = 0 AND ss.status NOT IN ('cancelled','canceled')
              AND ss.starts_at < ? AND ss.ends_at > ?
              AND (ss.teacher_id = ? OR (c.campus_id = ? AND c.room = ?))
            """, Integer.class, Timestamp.from(range.end()), Timestamp.from(range.start()), teacherId,
            campusId, room.trim());
        return conflicts != null && conflicts > 0;
    }

    long insertClass(ClassInsertPlan plan) {
        ClassInput input = plan.input();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO core_class
                (public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes)
                VALUES (?,?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, plan.classPublicId());
            statement.setLong(2, plan.courseId());
            statement.setLong(3, plan.campusId());
            statement.setLong(4, plan.teacherId());
            statement.setString(5, input.name().trim());
            statement.setString(6, plan.room());
            statement.setInt(7, input.capacity());
            statement.setInt(8, input.durationMinutes());
            return statement;
        }, key);
        Number generated = key.getKey();
        if (generated == null) {
            throw new IllegalStateException("MySQL did not return the class key");
        }
        return generated.longValue();
    }

    List<ClassSessionSummary> insertSessions(ClassInput input, List<LessonLink> lessons,
            List<InstantRange> ranges, long classId, long teacherId) {
        Map<String, Long> lessonInternalIds = new HashMap<>();
        lessons.forEach(lesson -> lessonInternalIds.put(lesson.publicId(), lesson.id()));
        List<ClassSessionSummary> sessions = new ArrayList<>();
        for (int index = 0; index < input.schedule().size(); index++) {
            var requested = input.schedule().get(index);
            InstantRange range = ranges.get(index);
            String sessionPublicId = "SESSION_" + UUID.randomUUID();
            jdbc.update("""
                INSERT INTO core_session
                (public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status)
                VALUES (?,?,?,?,?,?,'scheduled')
                """, sessionPublicId, classId, lessonInternalIds.get(requested.lessonId()), teacherId,
                Timestamp.from(range.start()), Timestamp.from(range.end()));
            sessions.add(new ClassSessionSummary(sessionPublicId, requested.lessonId(), input.teacherId(),
                range.start(), range.end()));
        }
        return List.copyOf(sessions);
    }
}
