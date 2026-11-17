package cn.siyue.core;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cn.siyue.core.AcademicClassRuntimeDtos.RecordReceipt;
import cn.siyue.core.AcademicClassRuntimeDtos.RecordInput;
import cn.siyue.core.AcademicClassRuntimeDtos.RelocateInput;
import cn.siyue.core.AcademicClassRuntimeDtos.RelocateClass;
import cn.siyue.core.AcademicClassRuntimeDtos.RelocateReceipt;
import cn.siyue.core.AcademicClassRuntimeDtos.ScheduleInput;
import cn.siyue.core.AcademicClassRuntimeDtos.ScheduleReceipt;
import cn.siyue.core.AcademicClassRuntimeDtos.ScheduleSession;
import cn.siyue.core.AcademicClassRuntimeDtos.SubstituteInput;
import cn.siyue.core.AcademicClassRuntimeDtos.SubstituteReceipt;

/** Persists class-wide schedule/location and session teaching actions with stable row locks. */
@Repository
public class AcademicClassRuntimeRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final CampusResourceRules campusRules;
    public AcademicClassRuntimeRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.campusRules = new CampusResourceRules(jdbc);
    }
    private record ClassCourse(long id) { }
    private record ClassLocation(long campusId, String room) { }
    private record ClassTeacher(long id, String publicId) { }
    private record ClassCapacity(int capacity, int duration) { }
    private record ClassDetails(ClassCourse course, ClassLocation location, ClassTeacher teacher,
            String name, ClassCapacity capacity) { }
    private record ClassRow(long id, String publicId, ClassDetails details, long version) {
        static ClassRow read(ResultSet rs) throws SQLException {
            return new ClassRow(rs.getLong("id"), rs.getString("public_id"),
                new ClassDetails(new ClassCourse(rs.getLong("course_id")),
                    new ClassLocation(rs.getLong("campus_id"), rs.getString("room")),
                    new ClassTeacher(rs.getLong("teacher_id"), rs.getString("teacher_public_id")),
                    rs.getString("name"), new ClassCapacity(rs.getInt("capacity"),
                        rs.getInt("duration_minutes"))), rs.getLong("version"));
        }
        long courseId() { return details.course().id(); }
        long campusId() { return details.location().campusId(); }
        long teacherId() { return details.teacher().id(); }
        String teacherPublicId() { return details.teacher().publicId(); }
        String name() { return details.name(); }
        String room() { return details.location().room(); }
        int capacity() { return details.capacity().capacity(); }
        int duration() { return details.capacity().duration(); }
    }
    private record SessionLesson(long id, String publicId) { }
    private record SessionTeacher(long id) { }
    private record SessionPlace(long campusId, String room) { }
    private record SessionTimes(Instant start, Instant end) { }
    private record SessionDetails(SessionLesson lesson, SessionTeacher teacher, SessionPlace place,
            SessionTimes times, String status) { }
    private record SessionRow(long id, String publicId, SessionDetails details, long version) {
        static SessionRow read(ResultSet rs) throws SQLException {
            return new SessionRow(rs.getLong("id"), rs.getString("public_id"),
                new SessionDetails(new SessionLesson(rs.getLong("lesson_id"), rs.getString("lesson_public_id")),
                    new SessionTeacher(rs.getLong("teacher_id")),
                    new SessionPlace(rs.getLong("session_campus"), rs.getString("session_room")),
                    new SessionTimes(rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
                    rs.getString("status")), rs.getLong("version"));
        }
        long lessonId() { return details.lesson().id(); }
        String lessonPublicId() { return details.lesson().publicId(); }
        long teacherId() { return details.teacher().id(); }
        long campusId() { return details.place().campusId(); }
        String room() { return details.place().room(); }
        Instant start() { return details.times().start(); }
        Instant end() { return details.times().end(); }
        String status() { return details.status(); }
    }
    private record SessionConflict(long sessionId, long teacherId, Instant start, Instant end,
            Long campusId, String room) { }

    ScheduleReceipt schedule(Actor actor, String classId, ScheduleInput input) {
        ClassRow row = classForUpdate(actor, classId);
        if (row.version() != input.expectedVersion()) { throw conflict("班级已更新，请刷新后重试"); }
        lockTeacher(row.teacherId());
        lockCampus(row.campusId());
        validateClassResources(row);
        List<SessionRow> prior = sessionsForUpdate(row.id());
        List<Long> lessonIds = lessons(row.courseId());
        validateScheduleInput(input.sessions(), lessonIds);
        validateTeacherRestrictedSchedule(actor, prior, input.sessions());
        Set<Long> retained = applySchedule(actor, row, prior, input.sessions());
        cancelOmitted(prior, retained);
        validateStudentSchedule(row);
        bumpClass(row, input.expectedVersion());
        return new ScheduleReceipt(row.publicId(), input.expectedVersion() + 1, sessionSummaries(row.id()));
    }

    private void validateScheduleInput(List<ScheduleSession> proposed, List<Long> lessons) {
        Set<String> seenIds = new HashSet<>();
        Set<String> seenLessons = new HashSet<>();
        for (ScheduleSession item : proposed) {
            if (!lessons.contains(resolveLesson(item.lessonId())) || !seenLessons.add(item.lessonId())) {
                throw invalid("排期中的讲次无效或重复");
            }
            if (item.sessionId() != null && !item.sessionId().isBlank() && !seenIds.add(item.sessionId())) {
                throw invalid("课次编号重复");
            }
            if (item.startsAt().isBefore(Instant.now())) { throw invalid("新排期不能位于过去"); }
        }
    }

    private List<Long> lessons(long courseId) {
        return jdbc.queryForList("SELECT id FROM core_lesson WHERE course_id=? AND deleted=0 ORDER BY sequence_no",
            Long.class, courseId);
    }

    private long resolveLesson(String publicId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM core_lesson WHERE public_id=? AND deleted=0",
            Long.class, publicId);
        if (ids.size() != 1) { throw invalid("课程讲次不存在"); }
        return ids.get(0);
    }

    private Set<Long> applySchedule(Actor actor, ClassRow row, List<SessionRow> prior,
            List<ScheduleSession> proposed) {
        Set<Long> retained = new HashSet<>();
        for (ScheduleSession item : proposed) {
            long lessonId = resolveLesson(item.lessonId());
            SessionRow existing = findSession(prior, item.sessionId(), lessonId);
            if (existing != null) {
                retained.add(existing.id());
                assertProtectedUnchanged(existing, lessonId, item.startsAt());
                updateSessionTime(existing, item, row);
            } else {
                insertSession(row, lessonId, item.startsAt());
            }
        }
        if (actor.scope().equals("assigned") && !actor.permissions().contains("class.assign")) {
            for (SessionRow old : prior) {
                if (!retained.contains(old.id()) && isFuture(old.start())) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "教师不能直接删除已有排课");
                }
            }
        }
        return retained;
    }

    private SessionRow findSession(List<SessionRow> prior, String publicId, long lessonId) {
        if (publicId == null || publicId.isBlank()) {
            return prior.stream().filter(row -> row.lessonId() == lessonId && !row.status().equals("cancelled"))
                .findFirst().orElse(null);
        }
        return prior.stream().filter(row -> row.publicId().equals(publicId)
                && !row.status().equals("cancelled") && !row.status().equals("canceled"))
            .findFirst()
            .orElseThrow(() -> invalid("课次编号不属于该班级"));
    }

    private void validateTeacherRestrictedSchedule(Actor actor, List<SessionRow> prior,
            List<ScheduleSession> proposed) {
        if (!actor.scope().equals("assigned") || prior.isEmpty()) { return; }
        boolean same = prior.size() == proposed.size() && prior.stream().allMatch(old -> proposed.stream()
            .anyMatch(item -> old.publicId().equals(item.sessionId()) && old.lessonPublicId().equals(item.lessonId())
                && old.start().equals(item.startsAt())));
        if (!same) { throw new BusinessException(ErrorCode.FORBIDDEN, "教师调整课次必须提交调课申请"); }
    }

    private void assertProtectedUnchanged(SessionRow session, long lessonId, Instant startsAt) {
        if (!protectedSession(session)) { return; }
        if (session.lessonId() != lessonId || !session.start().equals(startsAt)) {
            throw invalid("历史或已记录课次不得重写");
        }
    }

    private boolean protectedSession(SessionRow session) {
        if (!session.status().equals("scheduled")) { return true; }
        if (session.start().isBefore(Instant.now())) { return true; }
        Integer facts = jdbc.queryForObject("""
            SELECT (SELECT COUNT(id) FROM core_attendance WHERE session_id=? AND deleted=0)
                 +(SELECT COUNT(id) FROM core_consumption WHERE session_id=? AND deleted=0)
                 +(SELECT COUNT(id) FROM core_session_adjustment WHERE deleted=0 AND status IN ('active','completed')
                    AND (source_session_id=? OR target_session_id=?))
            """, Integer.class, session.id(), session.id(), session.id(), session.id());
        return facts != null && facts > 0;
    }

    private void updateSessionTime(SessionRow session, ScheduleSession item, ClassRow classRow) {
        long lessonId = resolveLesson(item.lessonId());
        Instant startsAt = item.startsAt();
        int duration = classRow.duration();
        Instant endsAt = startsAt.plusSeconds(duration * 60L);
        if (!session.start().equals(startsAt) || session.lessonId() != lessonId) {
            checkSessionConflict(new SessionConflict(session.id(), session.teacherId(), startsAt, endsAt,
                classRow.campusId(), classRow.room()));
            int changed = jdbc.update("UPDATE core_session SET lesson_id=?,starts_at=?,ends_at=?,version=version+1,"
                + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=?", lessonId, Timestamp.from(startsAt),
                Timestamp.from(endsAt), session.id(), session.version());
            if (changed != 1) { throw conflict("课次已更新，请刷新后重试"); }
        }
    }

    private void insertSession(ClassRow row, long lessonId, Instant startsAt) {
        long teacherId = row.teacherId();
        Instant endsAt = startsAt.plusSeconds(row.duration() * 60L);
        checkSessionConflict(new SessionConflict(0, teacherId, startsAt, endsAt, row.campusId(), row.room()));
        jdbc.update("""
            INSERT INTO core_session(public_id,class_id,campus_id,room,lesson_id,teacher_id,starts_at,ends_at,status)
            VALUES(?,?,?,?,?,?,?,?,'scheduled')
            """, "SES_" + UUID.randomUUID(), row.id(), row.campusId(), row.room(), lessonId, teacherId,
            Timestamp.from(startsAt), Timestamp.from(endsAt));
    }

    private void cancelOmitted(List<SessionRow> prior, Set<Long> retained) {
        for (SessionRow old : prior) {
            if (retained.contains(old.id())) { continue; }
            if (protectedSession(old)) { throw invalid("已记录或预约课次不能从排期删除"); }
            jdbc.update("UPDATE core_session SET status='cancelled',version=version+1,updated_at=UTC_TIMESTAMP(3) "
                + "WHERE id=? AND version=?", old.id(), old.version());
        }
    }

    private void validateStudentSchedule(ClassRow row) {
        Integer classStudents = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT e.student_id) FROM core_effective_enrollment e
            WHERE e.class_id=? AND e.deleted=0 AND e.status IN ('active','pending')
            """, Integer.class, row.id());
        if (classStudents != null && classStudents > row.capacity()) { throw invalid("班级人数超过容量"); }
        Integer conflicts = jdbc.queryForObject("""
            SELECT COUNT(*) FROM core_session candidate
            JOIN core_effective_enrollment member ON member.class_id=candidate.class_id
                AND member.deleted=0 AND member.status IN ('active','pending')
            JOIN core_effective_enrollment other_member ON other_member.student_id=member.student_id
                AND other_member.class_id<>member.class_id AND other_member.deleted=0
                AND other_member.status IN ('active','pending')
            JOIN core_session other_session ON other_session.class_id=other_member.class_id
                AND other_session.deleted=0 AND other_session.status NOT IN ('cancelled','canceled')
            JOIN core_class other_class ON other_class.id=other_session.class_id
                AND other_class.active=1 AND other_class.deleted=0
            WHERE candidate.class_id=? AND candidate.deleted=0 AND candidate.status='scheduled'
              AND candidate.starts_at>=UTC_TIMESTAMP(3) AND other_session.starts_at< candidate.ends_at
              AND other_session.ends_at>candidate.starts_at
            """, Integer.class, row.id());
        if (conflicts != null && conflicts > 0) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "学生上课时间与已有报名冲突"); }
    }

    RelocateReceipt relocate(Actor actor, RelocateInput input) {
        long toCampus = campusId(input.campusId());
        List<Long> campusLocks = new ArrayList<>();
        campusLocks.add(toCampus);
        for (RelocateClass target : input.classes()) {
            List<Long> source = jdbc.queryForList("SELECT campus_id FROM core_class "
                + "WHERE public_id=? AND deleted=0 AND active=1", Long.class, target.classId());
            if (source.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级不存在或已停用"); }
            campusLocks.add(source.get(0));
        }
        for (Long campusLock : campusLocks.stream().distinct().sorted().toList()) { lockCampus(campusLock); }
        validateCampusScope(actor, toCampus);
        List<ClassRow> classes = new ArrayList<>();
        List<RelocateClass> ordered = input.classes().stream().sorted(java.util.Comparator.comparing(
            RelocateClass::classId)).toList();
        for (RelocateClass target : ordered) {
            ClassRow row = classForUpdate(actor, target.classId());
            if (row.version() != target.expectedVersion()) { throw conflict("班级已更新，请刷新后重试"); }
            classes.add(row);
        }
        for (ClassRow row : classes) { relocateOne(actor, row, toCampus, input.room()); }
        return new RelocateReceipt(classes.size(), input.campusId(), input.room());
    }

    private void relocateOne(Actor actor, ClassRow row, long toCampus, String room) {
        if (row.campusId() == toCampus && row.room().equals(room)) { throw invalid("班级已经位于目标教室"); }
        validateNoDuplicateClass(row, toCampus);
        lockCampus(row.campusId());
        validateTeacherCampus(row.teacherId(), toCampus);
        campusRules.validate(toCampus, new CampusResourceRules.Request(room, row.capacity(),
            courseField(row.courseId(), "subject"), courseField(row.courseId(), "grade")));
        List<SessionRow> sessions = sessionsForUpdate(row.id());
        long now = System.currentTimeMillis();
        for (SessionRow session : sessions) {
            if (!isFuture(session.start()) || protectedSession(session)) { preserveOldLocation(row, session); continue; }
            checkSessionConflict(new SessionConflict(session.id(), session.teacherId(), session.start(),
                session.end(), toCampus, room));
            jdbc.update("UPDATE core_session SET campus_id=?,room=?,version=version+1,updated_at=UTC_TIMESTAMP(3) "
                + "WHERE id=?", toCampus, room, session.id());
        }
        jdbc.update("""
            INSERT INTO core_class_location_history(public_id,class_id,actor_id,from_campus_id,to_campus_id,
                from_room,to_room,occurred_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
            """, "CLH_" + UUID.randomUUID(), row.id(), actor.id(), row.campusId(), toCampus, row.room(), room);
        jdbc.update("UPDATE core_class SET campus_id=?,room=?,version=version+1,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE id=? AND version=?", toCampus, room, row.id(), row.version());
        jdbc.update("""
            UPDATE core_crm_order o
            LEFT JOIN core_crm_order_class_terms t ON t.order_id=o.id
            LEFT JOIN core_enrollment e ON e.order_id=o.id AND e.deleted=0
            SET o.current_campus_id=?,o.version=o.version+1,o.updated_at=UTC_TIMESTAMP(3)
            WHERE (t.class_id=? OR e.class_id=?) AND o.deleted=0
            """, toCampus, row.id(), row.id());
    }

    private void preserveOldLocation(ClassRow row, SessionRow session) {
        jdbc.update("UPDATE core_session SET campus_id=COALESCE(campus_id,?),room=COALESCE(room,?),"
            + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
            row.campusId(), row.room(), session.id());
    }

    private void validateNoDuplicateClass(ClassRow row, long campusId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_class WHERE campus_id=? AND name=? "
            + "AND id<>? AND active=1 AND deleted=0", Integer.class, campusId, row.name(), row.id());
        if (count != null && count > 0) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "目标校区已有同名班级"); }
    }

    SubstituteReceipt substitute(Actor actor, String sessionId, SubstituteInput input) {
        SessionRow session = sessionForUpdate(actor, sessionId);
        if (session.version() != input.expectedVersion()) { throw conflict("课次已更新，请刷新后重试"); }
        if (protectedSession(session) || !isFuture(session.start())) { throw invalid("历史或已记录课次不能更换老师"); }
        long newTeacher = teacherId(input.teacherId());
        lockTeacher(newTeacher);
        validateTeacherCampus(newTeacher, session.campusId());
        checkSessionConflict(new SessionConflict(session.id(), newTeacher, session.start(), session.end(),
            session.campusId(), session.room()));
        if (newTeacher == session.teacherId()) { throw invalid("代课老师与原老师相同"); }
        jdbc.update("UPDATE core_session SET teacher_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE id=? AND version=?", newTeacher, session.id(), session.version());
        jdbc.update("""
            INSERT INTO core_session_teacher_event(public_id,session_id,actor_id,from_teacher_id,to_teacher_id,
                reason_cipher,occurred_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))
            """, "STE_" + UUID.randomUUID(), session.id(), actor.id(), session.teacherId(), newTeacher,
            cipher.encrypt(input.reason().trim()));
        bumpClassForSession(session.id());
        return new SubstituteReceipt(session.publicId(), input.teacherId(), session.version() + 1);
    }

    RecordReceipt record(Actor actor, String sessionId, RecordInput input) {
        SessionRow session = sessionForUpdate(actor, sessionId);
        if (session.version() != input.expectedVersion()) { throw conflict("课次已更新，请刷新后重试"); }
        if (session.start().isAfter(Instant.now())) { throw invalid("课程尚未开始"); }
        Instant recordedAt = Instant.now();
        String publicId = "STR_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_session_teaching_record(public_id,session_id,actor_id,content_cipher,occurred_at)
            VALUES(?,?,?,?,?)
            """, publicId, session.id(), actor.id(), cipher.encrypt(input.content().trim()), Timestamp.from(recordedAt));
        jdbc.update("UPDATE core_session SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=?",
            session.id(), session.version());
        bumpClassForSession(session.id());
        return new RecordReceipt(publicId, session.publicId(), actor.publicId(), recordedAt, session.version() + 1);
    }

    private SessionRow sessionForUpdate(Actor actor, String publicId) {
        List<Long> campuses = jdbc.queryForList("SELECT c.campus_id FROM core_session s JOIN core_class c "
            + "ON c.id=s.class_id WHERE s.public_id=? AND s.deleted=0 AND c.deleted=0 UNION SELECT s.campus_id "
            + "FROM core_session s WHERE s.public_id=? AND s.deleted=0 AND s.campus_id IS NOT NULL",
            Long.class, publicId, publicId);
        if (campuses.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "课次不存在或不在当前范围"); }
        for (Long campusId : campuses.stream().distinct().sorted().toList()) { lockCampus(campusId); }
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<SessionRow> rows = jdbc.query("""
            SELECT s.id,s.public_id,s.lesson_id,l.public_id lesson_public_id,s.teacher_id,
                   COALESCE(s.campus_id,c.campus_id) session_campus,COALESCE(s.room,c.room) session_room,
                   s.starts_at,s.ends_at,s.status,s.version
            FROM core_session s JOIN core_class c ON c.id=s.class_id AND c.deleted=0 AND c.active=1
            JOIN core_lesson l ON l.id=s.lesson_id
            WHERE s.public_id=? AND s.deleted=0 AND s.status NOT IN ('cancelled','canceled') AND"""
                + " " + scope.sql() + " FOR UPDATE",
            (rs, index) -> SessionRow.read(rs),
            concat(List.of(publicId), scope.args()).toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "课次不存在或不在当前范围"); }
        return rows.get(0);
    }

    private ClassRow classForUpdate(Actor actor, String publicId) {
        List<Long> campuses = jdbc.queryForList("SELECT campus_id FROM core_class WHERE public_id=? AND deleted=0",
            Long.class, publicId);
        if (campuses.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级不存在或不在当前范围"); }
        lockCampus(campuses.get(0));
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<ClassRow> rows = jdbc.query("""
            SELECT c.id,c.public_id,c.course_id,c.campus_id,c.teacher_id,p.public_id teacher_public_id,
                   c.name,c.room,c.capacity,c.duration_minutes,c.version
            FROM core_class c JOIN core_principal p ON p.id=c.teacher_id
            WHERE c.public_id=? AND c.deleted=0 AND c.active=1 AND""" + " " + scope.sql() + " FOR UPDATE",
            (rs, index) -> ClassRow.read(rs), concat(List.of(publicId), scope.args()).toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级不存在或不在当前范围"); }
        return rows.get(0);
    }

    private List<SessionRow> sessionsForUpdate(long classId) {
        return jdbc.query("""
            SELECT s.id,s.public_id,s.lesson_id,l.public_id lesson_public_id,s.teacher_id,
                   COALESCE(s.campus_id,c.campus_id) session_campus,COALESCE(s.room,c.room) session_room,
                   s.starts_at,s.ends_at,s.status,s.version
            FROM core_session s JOIN core_lesson l ON l.id=s.lesson_id
            JOIN core_class c ON c.id=s.class_id
            WHERE s.class_id=? AND s.deleted=0 AND s.status NOT IN ('cancelled','canceled')
            ORDER BY s.id FOR UPDATE
            """, (rs, index) -> SessionRow.read(rs), classId);
    }

    private List<AcademicDtos.ClassSessionSummary> sessionSummaries(long classId) {
        return jdbc.query("""
            SELECT s.public_id,l.public_id lesson_public_id,p.public_id teacher_public_id,s.starts_at,s.ends_at,s.version,
                   cp.public_id session_campus_public_id,COALESCE(s.room,c.room) session_room
            FROM core_session s JOIN core_lesson l ON l.id=s.lesson_id
            JOIN core_principal p ON p.id=s.teacher_id
            JOIN core_class c ON c.id=s.class_id
            LEFT JOIN core_campus cp ON cp.id=COALESCE(s.campus_id,c.campus_id)
            WHERE s.class_id=? AND s.deleted=0 AND s.status<>'cancelled' ORDER BY s.starts_at,s.id
            """, (rs, index) -> new AcademicDtos.ClassSessionSummary(rs.getString("public_id"),
                rs.getString("lesson_public_id"), rs.getString("teacher_public_id"),
                new AcademicDtos.SessionSchedule(rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("ends_at").toInstant(), rs.getLong("version"), null),
                new AcademicDtos.SessionLocation(rs.getString("session_campus_public_id"),
                    rs.getString("session_room"))), classId);
    }

    private void checkSessionConflict(SessionConflict input) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(s.id) FROM core_session s JOIN core_class c ON c.id=s.class_id
            WHERE s.id<>? AND s.deleted=0 AND c.deleted=0 AND c.active=1
              AND s.status NOT IN ('cancelled','canceled') AND s.starts_at<? AND s.ends_at>?
              AND (s.teacher_id=? OR (COALESCE(s.campus_id,c.campus_id)=? AND COALESCE(s.room,c.room)=?))
            """, Integer.class, input.sessionId(), Timestamp.from(input.end()), Timestamp.from(input.start()),
            input.teacherId(), input.campusId() == null ? -1 : input.campusId(),
            input.room() == null ? "" : input.room());
        if (count != null && count > 0) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "老师或教室在该时段已有排课"); }
    }

    private void validateClassResources(ClassRow row) {
        campusRules.validate(row.campusId(), new CampusResourceRules.Request(row.room(), row.capacity(),
            courseField(row.courseId(), "subject"), courseField(row.courseId(), "grade")));
    }
    private String courseField(long courseId, String field) {
        return jdbc.queryForObject("SELECT " + (field.equals("subject") ? "subject" : "grade")
            + " FROM core_course WHERE id=? AND active=1 AND deleted=0", String.class, courseId);
    }
    private void lockCampus(long campusId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM core_campus WHERE id=? AND active=1 "
            + "AND deleted=0 FOR UPDATE", Long.class, campusId);
        if (ids.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或已停用"); }
    }
    private void lockTeacher(long teacherId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM core_principal WHERE id=? AND active=1 "
            + "AND deleted=0 FOR UPDATE", Long.class, teacherId);
        if (ids.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "老师账号不存在或已停用"); }
    }
    private void validateCampusScope(Actor actor, long campusId) {
        if (actor.scope().equals("all")) { return; }
        if (!actor.scope().equals("campus") || !actor.campusIds().contains(campusId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标校区不在当前授权范围");
        }
    }
    private void validateTeacherCampus(long teacherId, long campusId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_principal_campus "
            + "WHERE principal_id=? AND campus_id=? AND deleted=0", Integer.class, teacherId, campusId);
        if (count == null || count == 0) { throw invalid("老师不属于目标校区"); }
    }
    private long campusId(String publicId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0",
            Long.class, publicId);
        if (ids.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "目标校区不存在或已停用"); }
        return ids.get(0);
    }
    private long teacherId(String publicId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM core_principal WHERE public_id=? AND active=1 AND deleted=0",
            Long.class, publicId);
        if (ids.size() != 1) { throw invalid("代课老师不存在或已停用"); }
        return ids.get(0);
    }
    private void bumpClass(ClassRow row, long version) {
        int changed = jdbc.update("UPDATE core_class SET version=version+1,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE id=? AND version=? AND active=1 AND deleted=0", row.id(), version);
        if (changed != 1) { throw conflict("班级已更新，请刷新后重试"); }
    }
    private void bumpClassForSession(long sessionId) {
        jdbc.update("UPDATE core_class c JOIN core_session s ON s.class_id=c.id SET c.version=c.version+1,"
            + "c.updated_at=UTC_TIMESTAMP(3) WHERE s.id=?", sessionId);
    }
    private boolean isFuture(Instant start) { return start.isAfter(Instant.now()); }
    private List<Object> concat(List<?> first, List<Object> second) {
        List<Object> values = new ArrayList<>(first);
        values.addAll(second);
        return values;
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
    private BusinessException conflict(String message) { return new BusinessException(ErrorCode.VERSION_CONFLICT, message); }
}
