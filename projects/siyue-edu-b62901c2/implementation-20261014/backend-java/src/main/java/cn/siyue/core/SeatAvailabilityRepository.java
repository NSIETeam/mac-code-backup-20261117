package cn.siyue.core;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared class and session capacity projection; callers lock the campus and class before reservation. */
@Repository
public class SeatAvailabilityRepository {
    public record SessionSeats(long id, int capacity, int virtualSeats, int roster, Instant startsAt, String status) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    public SeatAvailabilityRepository(JdbcTemplate jdbc) { this(jdbc, null); }

    @org.springframework.beans.factory.annotation.Autowired
    public SeatAvailabilityRepository(JdbcTemplate jdbc, PiiCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    public int remaining(long classId) {
        int enrolled = count("SELECT COUNT(*) FROM core_effective_enrollment WHERE class_id=? "
            + "AND status IN ('active','pending') AND deleted=0", classId);
        Integer capacity = jdbc.queryForObject("SELECT capacity FROM core_class WHERE id=? AND deleted=0",
            Integer.class, classId);
        int remaining = (capacity == null ? 0 : capacity) - enrolled;
        for (SessionSeats session : sessions(classId)) {
            if (!session.startsAt().isBefore(Instant.now()) && session.status().equals("scheduled")) {
                remaining = Math.min(remaining, session.capacity() - enrolled);
                remaining = Math.min(remaining, session.capacity() + session.virtualSeats() - session.roster() - trialOccupied(session.id(), roster(session.id())));
            }
        }
        return Math.max(0, remaining);
    }

    public int sessionRemaining(long classId,long sessionId) {
        SessionSeats session=sessions(classId).stream().filter(value->value.id()==sessionId).findFirst()
            .orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"课次不存在"));
        return Math.max(0,session.capacity()+session.virtualSeats()-session.roster()-trialOccupied(sessionId,roster(sessionId)));
    }

    public List<SessionSeats> sessions(long classId) {
        return jdbc.query("SELECT s.id,s.starts_at,s.status,"
            + "LEAST(COALESCE(s.class_capacity_override,c.capacity),COALESCE(s.capacity_override,c.capacity),"
            + "COALESCE(r.capacity,c.capacity)) seat_limit,COALESCE(r.virtual_seats,0) virtual_seats "
            + "FROM core_session s JOIN core_class c ON c.id=s.class_id LEFT JOIN core_campus_room r "
            + "ON r.campus_id=COALESCE(s.campus_id,c.campus_id) AND r.name=COALESCE(s.room,c.room) AND r.deleted=0 "
            + "WHERE s.class_id=? AND s.deleted=0 ORDER BY s.id", (rs, row) -> new SessionSeats(rs.getLong("id"),
                rs.getInt("seat_limit"), rs.getInt("virtual_seats"), roster(rs.getLong("id")).size(),
                rs.getTimestamp("starts_at").toInstant(), rs.getString("status")), classId);
    }

    /** Active effective placements plus pending applications and active/completed incoming adjustments. */
    public List<Long> roster(long sessionId) {
        return jdbc.query("SELECT DISTINCT occupants.student_id FROM ("
            + "SELECT e.student_id,e.id FROM core_session_enrollment e WHERE e.session_id=? AND e.status='active' "
            + "AND NOT EXISTS(SELECT a.id FROM core_session_adjustment a WHERE a.enrollment_id=e.id "
            + "AND a.source_session_id=? AND a.status IN ('active','completed') AND a.deleted=0) "
            + "UNION SELECT e.student_id,e.id FROM core_effective_enrollment e JOIN core_session s ON s.class_id=e.class_id "
            + "WHERE s.id=? AND e.status='pending' AND e.deleted=0 "
            + "UNION SELECT e.student_id,e.id FROM core_session_adjustment a "
            + "JOIN core_effective_enrollment e ON e.id=a.enrollment_id AND e.status='active' AND e.deleted=0 "
            + "WHERE a.target_session_id=? AND a.status IN ('active','completed') AND a.deleted=0) occupants",
            (rs, row) -> rs.getLong(1), sessionId, sessionId, sessionId, sessionId);
    }

    /** Deduplicates confirmed bookings by the same normalized name and phone pair used by the legacy runtime. */
    public int trialOccupied(long sessionId, List<Long> knownStudents) {
        List<String[]> bookings = jdbc.query("SELECT name_cipher,phone_cipher FROM core_trial_booking "
            + "WHERE session_id=? AND status='confirmed' AND deleted=0",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, sessionId);
        if (bookings.isEmpty()) { return 0; }
        if (cipher == null) { throw new IllegalStateException("Trial occupancy requires the PII cipher"); }
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (String[] booking : bookings) {
            identities.add(identity(cipher.decrypt(booking[0]), cipher.decrypt(booking[1])));
        }
        for (Long studentId : knownStudents) {
            jdbc.query("SELECT name_cipher,phone_cipher FROM core_student WHERE id=? AND deleted=0", rs -> {
                String phone = rs.getString(2);
                if (phone != null) { identities.remove(identity(cipher.decrypt(rs.getString(1)), cipher.decrypt(phone))); }
            }, studentId);
        }
        return identities.size();
    }

    static String identity(String name, String phone) {
        return name.trim().toLowerCase(java.util.Locale.ROOT) + "|" + phone;
    }

    private int count(String sql, long id) {
        Integer value = jdbc.queryForObject(sql, Integer.class, id);
        return value == null ? 0 : value;
    }
}
