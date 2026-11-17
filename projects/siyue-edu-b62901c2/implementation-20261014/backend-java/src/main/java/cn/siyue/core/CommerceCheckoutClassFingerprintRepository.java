package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds an immutable digest of class facts and its complete current timetable. */
class CommerceCheckoutClassFingerprintRepository {
    /** 报价有效性摘要中的课次计划快照，覆盖讲次、教师、校区、教室、时间、状态和版本。 */
    private record SessionSnapshot(String publicId, long lessonId, long teacherId, Long campusId,
            String room, Timestamp start, Timestamp end, String status, long version) { }

    private final JdbcTemplate jdbc;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    CommerceCheckoutClassFingerprintRepository(JdbcTemplate jdbc, MutationRepository mutations,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    String signature(long classId) {
        Map<String, Object> signature = new TreeMap<>();
        jdbc.query("SELECT c.public_id,c.course_id,c.campus_id,c.teacher_id,c.name,c.room,c.capacity,"
            + "c.duration_minutes,c.version FROM core_class c WHERE c.id=? AND c.deleted=0 AND c.active=1",
            rs -> {
                signature.put("publicId", rs.getString("public_id"));
                signature.put("courseId", rs.getLong("course_id"));
                signature.put("campusId", rs.getLong("campus_id"));
                signature.put("teacherId", rs.getLong("teacher_id"));
                signature.put("name", rs.getString("name"));
                signature.put("room", rs.getString("room"));
                signature.put("capacity", rs.getInt("capacity"));
                signature.put("duration", rs.getInt("duration_minutes"));
                signature.put("version", rs.getLong("version"));
            }, classId);
        List<SessionSnapshot> sessions = jdbc.query("SELECT s.public_id,s.lesson_id,s.teacher_id,s.campus_id,s.room,"
            + "s.starts_at,s.ends_at,s.status,s.version FROM core_session s WHERE s.class_id=? AND s.deleted=0 "
            + "ORDER BY s.id", (rs, row) -> new SessionSnapshot(rs.getString("public_id"),
                rs.getLong("lesson_id"), rs.getLong("teacher_id"), nullableLong(rs, "campus_id"),
                rs.getString("room"), rs.getTimestamp("starts_at"), rs.getTimestamp("ends_at"),
                rs.getString("status"), rs.getLong("version")), classId);
        signature.put("sessions", sessions);
        try { return mutations.hash(mapper.writeValueAsString(signature)); }
        catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to fingerprint checkout class", error);
        }
    }

    private Long nullableLong(java.sql.ResultSet result, String name) throws java.sql.SQLException {
        Number value = (Number) result.getObject(name);
        return value == null ? null : value.longValue();
    }
}
