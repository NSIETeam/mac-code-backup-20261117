package cn.siyue.core;

import cn.siyue.core.AcademicRescheduleDtos.RequestReceipt;
import cn.siyue.core.AcademicRescheduleDtos.ListQuery;
import cn.siyue.core.AcademicRescheduleDtos.RequestPage;
import cn.siyue.core.AcademicRescheduleDtos.RequestView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores reschedule requests separately from the mutable timetable and appends decisions. */
@Repository
public class AcademicRescheduleRepository {
    record Request(long id, String publicId, long classId, String classPublicId, long sessionId,
            String sessionPublicId, long campusId, long applicantId, Instant originalStarts,
            Instant originalEnds, long originalTeacherId, long originalLessonId, String originalRoom,
            long originalSessionVersion, long originalClassVersion, Instant requestedStarts,
            String reasonCipher, String status, Long reviewerId, long version) {
        static Request read(ResultSet rs) throws SQLException {
            return new Request(rs.getLong("id"), rs.getString("public_id"), rs.getLong("class_id"),
                rs.getString("class_public_id"), rs.getLong("session_id"), rs.getString("session_public_id"),
                rs.getLong("campus_id"), rs.getLong("applicant_id"), rs.getTimestamp("original_starts_at").toInstant(),
                rs.getTimestamp("original_ends_at").toInstant(), rs.getLong("original_teacher_id"),
                rs.getLong("original_lesson_id"), rs.getString("original_room"),
                rs.getLong("original_session_version"), rs.getLong("original_class_version"),
                rs.getTimestamp("requested_starts_at").toInstant(), rs.getString("reason_cipher"),
                rs.getString("status"), nullableLong(rs, "reviewer_id"), rs.getLong("version"));
        }

        private static Long nullableLong(ResultSet rs, String column) throws SQLException {
            String value = rs.getString(column);
            if (value == null) { return null; }
            try { return Long.valueOf(value); }
            catch (NumberFormatException error) {
                throw new SQLException("Unsigned identifier is outside the supported range", error);
            }
        }
    }
    record Decision(Actor actor, Request request, String status, String note) { }
    private record Event(long requestId, long actorId, String type, String from, String to, String note) { }

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    public AcademicRescheduleRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    RequestPage listVisible(Actor actor, ListQuery query) {
        validateListQuery(query);
        List<Object> args = new ArrayList<>();
        String visible = visibility(actor, args);
        String filter = query.status() == null ? "" : " AND r.status=?";
        if (query.status() != null) { args.add(query.status()); }
        String from = " FROM core_session_reschedule_request r WHERE r.deleted=0 AND (" + visible + ")" + filter;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((long) (query.page() - 1) * query.size());
        List<RequestView> items = jdbc.query("""
            SELECT r.public_id,c.public_id AS class_public_id,s.public_id AS session_public_id,
              cp.public_id AS campus_public_id,app.public_id AS applicant_public_id,
              r.original_starts_at,r.original_ends_at,teacher.public_id AS original_teacher_public_id,
              lesson.public_id AS original_lesson_public_id,r.original_room,r.original_session_version,
              r.original_class_version,r.requested_starts_at,r.reason_cipher,r.status,
              reviewer.public_id AS reviewer_public_id,r.reviewed_at,r.resolution_note_cipher,r.version,r.created_at
            FROM core_session_reschedule_request r
            JOIN core_class c ON c.id=r.class_id
            JOIN core_session s ON s.id=r.session_id
            JOIN core_campus cp ON cp.id=r.campus_id
            JOIN core_principal app ON app.id=r.applicant_id
            JOIN core_principal teacher ON teacher.id=r.original_teacher_id
            JOIN core_lesson lesson ON lesson.id=r.original_lesson_id
            LEFT JOIN core_principal reviewer ON reviewer.id=r.reviewer_id
            WHERE r.deleted=0 AND (
            """ + visible + ")" + filter + " ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new RequestView(rs.getString("public_id"), rs.getString("class_public_id"),
                rs.getString("session_public_id"), rs.getString("campus_public_id"),
                rs.getString("applicant_public_id"), rs.getTimestamp("original_starts_at").toInstant(),
                rs.getTimestamp("original_ends_at").toInstant(), rs.getString("original_teacher_public_id"),
                rs.getString("original_lesson_public_id"), rs.getString("original_room"),
                rs.getLong("original_session_version"), rs.getLong("original_class_version"),
                rs.getTimestamp("requested_starts_at").toInstant(),
                businessDate(rs.getTimestamp("requested_starts_at").toInstant()),
                businessTime(rs.getTimestamp("requested_starts_at").toInstant(),
                    rs.getTimestamp("original_starts_at").toInstant(), rs.getTimestamp("original_ends_at").toInstant()),
                cipher.decrypt(rs.getString("reason_cipher")),
                rs.getString("status"), rs.getString("reviewer_public_id"), instant(rs.getTimestamp("reviewed_at")),
                decryptNullable(rs.getString("resolution_note_cipher")), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant()), pageArgs.toArray());
        return new RequestPage(items, total == null ? 0 : total, query.page(), query.size());
    }

    private String visibility(Actor actor, List<Object> args) {
        List<String> alternatives = new java.util.ArrayList<>();
        if (actor.permissions().contains("session.reschedule.request")) {
            alternatives.add("r.applicant_id=?");
            args.add(actor.id());
        }
        if (actor.permissions().contains("session.move")) {
            if ("all".equals(actor.scope())) {
                alternatives.add("1=1");
            } else if ("campus".equals(actor.scope())) {
                alternatives.add("r.campus_id IN (SELECT campus_id FROM core_principal_campus "
                    + "WHERE principal_id=? AND deleted=0)");
                args.add(actor.id());
            }
        }
        if (alternatives.isEmpty()) { throw new BusinessException(ErrorCode.FORBIDDEN, "无调课申请读取范围"); }
        return String.join(" OR ", alternatives);
    }

    private static void validateListQuery(ListQuery query) {
        if (query.page() < 1 || query.page() > 100_000 || query.size() < 1 || query.size() > 100
                || (query.status() != null && !List.of("pending", "approved", "rejected").contains(query.status()))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "调课查询条件无效");
        }
    }

    private String decryptNullable(String value) { return value == null ? null : cipher.decrypt(value); }

    private String businessDate(Instant value) {
        return value.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
    }

    private String businessTime(Instant startsAt, Instant originalStarts, Instant originalEnds) {
        var start = startsAt.atZone(ZoneId.of("Asia/Shanghai"));
        long minutes = java.time.Duration.between(originalStarts, originalEnds).toMinutes();
        var end = start.plusMinutes(minutes);
        String endTime = end.toLocalDate().equals(start.toLocalDate())
            ? String.format(java.util.Locale.ROOT, "%02d:%02d", end.getHour(), end.getMinute()) : "24:00";
        return String.format(java.util.Locale.ROOT, "%02d:%02d–%s", start.getHour(), start.getMinute(), endTime);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    RequestReceipt create(Actor actor, AcademicRepository.SessionScheduleRow session, long classVersion,
            Instant requestedStartsAt, String reason) {
        String publicId = "RSRQ_" + UUID.randomUUID();
        Long lessonId = jdbc.queryForObject("SELECT lesson_id FROM core_session WHERE id=? AND deleted=0",
            Long.class, session.id());
        jdbc.update("""
            INSERT INTO core_session_reschedule_request(public_id,class_id,session_id,campus_id,applicant_id,
              original_starts_at,original_ends_at,original_teacher_id,original_lesson_id,original_room,
              original_session_version,original_class_version,requested_starts_at,reason_cipher)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, publicId, session.classId(), session.id(), session.campusId(), actor.id(),
            Timestamp.from(session.startsAt()), Timestamp.from(session.endsAt()), session.teacherId(),
            lessonId, session.room(), session.version(), classVersion, Timestamp.from(requestedStartsAt),
            cipher.encrypt(reason));
        long id = jdbc.queryForObject("SELECT id FROM core_session_reschedule_request WHERE public_id=?",
            Long.class, publicId);
        appendEvent(new Event(id, actor.id(), "requested", null, "pending", reason));
        return new RequestReceipt(publicId, "pending", session.publicId(), requestedStartsAt, 1);
    }

    Request lock(Actor actor, String publicId) {
        ScopeSql.Clause scope = campusScope(actor, "r.campus_id");
        String sql = "SELECT r.id,r.public_id,r.class_id,c.public_id class_public_id,r.session_id,s.public_id "
            + "session_public_id,r.campus_id,r.applicant_id,r.original_starts_at,r.original_ends_at,"
            + "r.original_teacher_id,r.original_lesson_id,r.original_room,r.original_session_version,"
            + "r.original_class_version,r.requested_starts_at,r.reason_cipher,r.status,r.reviewer_id,r.version "
            + "FROM core_session_reschedule_request r JOIN core_class c ON c.id=r.class_id "
            + "JOIN core_session s ON s.id=r.session_id WHERE r.public_id=? AND r.deleted=0 AND " + scope.sql();
        List<Request> visible = jdbc.query(sql, (rs, row) -> Request.read(rs), args(publicId, scope.args()));
        if (visible.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "调课申请不存在或不在当前范围"); }
        Request observed = visible.get(0);
        lockTimetable(observed);
        List<Request> rows = jdbc.query(sql + " FOR UPDATE", (rs, row) -> Request.read(rs), args(publicId, scope.args()));
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "调课申请不存在或不在当前范围"); }
        return rows.get(0);
    }

    void assertUnchanged(Request request, AcademicRepository.SessionScheduleRow current) {
        long classVersion = jdbc.queryForObject("SELECT version FROM core_class WHERE id=? AND deleted=0",
            Long.class, request.classId());
        long lessonId = jdbc.queryForObject("SELECT lesson_id FROM core_session WHERE id=? AND deleted=0",
            Long.class, request.sessionId());
        if (current.id() != request.sessionId() || current.classId() != request.classId()
                || current.version() != request.originalSessionVersion() || classVersion != request.originalClassVersion()
                || lessonId != request.originalLessonId()
                || !current.startsAt().equals(request.originalStarts()) || !current.endsAt().equals(request.originalEnds())
                || current.teacherId() != request.originalTeacherId()
                || !java.util.Objects.equals(current.room(), request.originalRoom())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "原课次已变化，请驳回后重新申请");
        }
    }

    RequestReceipt decide(Decision decision) {
        Actor actor = decision.actor();
        Request request = decision.request();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String nextStatus = "approve".equals(decision.status()) ? "approved" : "rejected";
        int changed = jdbc.update("UPDATE core_session_reschedule_request SET status=?,reviewer_id=?,reviewed_at=?,"
            + "resolution_note_cipher=?,version=version+1,updated_at=? WHERE id=? AND status='pending' AND version=?",
            nextStatus, actor.id(), Timestamp.from(now), decision.note() == null ? null : cipher.encrypt(decision.note()),
            Timestamp.from(now), request.id(), request.version());
        if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "调课申请已被处理"); }
        appendEvent(new Event(request.id(), actor.id(), "reviewed", "pending", nextStatus,
            decision.note() == null ? "" : decision.note()));
        return new RequestReceipt(request.publicId(), nextStatus, request.sessionPublicId(),
            request.requestedStarts(), request.version() + 1);
    }

    private void appendEvent(Event event) {
        jdbc.update("INSERT INTO core_session_reschedule_event(public_id,request_id,actor_id,event_type,"
            + "from_status,to_status,note_cipher,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
            "RSEV_" + UUID.randomUUID(), event.requestId(), event.actorId(), event.type(), event.from(), event.to(),
            event.note().isEmpty() ? null : cipher.encrypt(event.note()), Timestamp.from(Instant.now()));
    }

    private ScopeSql.Clause campusScope(Actor actor, String column) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause(column + " IN (SELECT campus_id FROM core_principal_campus "
                + "WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "调课审批需要全部或校区范围");
        };
    }

    private Object[] args(String publicId, List<Object> scopeArgs) {
        Object[] result = new Object[scopeArgs.size() + 1];
        result[0] = publicId;
        for (int index = 0; index < scopeArgs.size(); index++) { result[index + 1] = scopeArgs.get(index); }
        return result;
    }

    void lockTimetable(long classId, long sessionId) {
        List<Long> campuses = jdbc.queryForList("SELECT DISTINCT campus_id FROM (SELECT campus_id FROM core_class "
            + "WHERE id=? UNION SELECT COALESCE(campus_id,(SELECT campus_id FROM core_class WHERE id=class_id)) "
            + "FROM core_session WHERE id=?) places ORDER BY campus_id", Long.class, classId, sessionId);
        for (Long campusId : campuses) {
            jdbc.queryForObject("SELECT id FROM core_campus WHERE id=? AND deleted=0 FOR UPDATE",
                Long.class, campusId);
        }
        jdbc.queryForObject("SELECT id FROM core_class WHERE id=? AND deleted=0 FOR UPDATE",
            Long.class, classId);
        jdbc.queryForObject("SELECT id FROM core_session WHERE id=? AND deleted=0 FOR UPDATE",
            Long.class, sessionId);
    }

    long classVersion(long classId) {
        return jdbc.queryForObject("SELECT version FROM core_class WHERE id=? AND deleted=0", Long.class, classId);
    }

    private void lockTimetable(Request request) { lockTimetable(request.classId(), request.sessionId()); }
}
