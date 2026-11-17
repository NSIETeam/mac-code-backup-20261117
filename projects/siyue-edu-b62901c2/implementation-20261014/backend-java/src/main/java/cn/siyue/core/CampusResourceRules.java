package cn.siyue.core;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checks class resources after the caller locks the campus row in its business transaction. */
final class CampusResourceRules {
    record Request(String room, int capacity, String subject, String grade) { }
    private final JdbcTemplate jdbc;
    CampusResourceRules(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void validate(long campusId, Request input) {
        List<Boolean> ready = jdbc.queryForList("SELECT configuration_ready FROM core_campus "
            + "WHERE id=? AND active=1 AND deleted=0", Boolean.class, campusId);
        if (ready.isEmpty()) { throw invalid("校区不存在或已停用"); }
        // Historical campus metadata must be imported before enabling its configuration constraints.
        if (!ready.get(0)) { return; }
        List<Integer> capacities = jdbc.queryForList("SELECT capacity FROM core_campus_room "
            + "WHERE campus_id=? AND name=? AND deleted=0", Integer.class, campusId, input.room());
        if (capacities.size() != 1 || capacities.get(0) < input.capacity()) {
            throw invalid("教室不存在或班级人数超过教室容量");
        }
        requireOption(campusId, "subject", input.subject());
        requireOption(campusId, "grade", CampusConfigurationValidator.grade(input.grade()));
    }
    private void requireOption(long campusId, String kind, String value) {
        List<String> values = jdbc.queryForList("SELECT value FROM core_campus_academic_option "
            + "WHERE campus_id=? AND kind=? AND deleted=0", String.class, campusId, kind);
        if (!values.isEmpty() && !values.contains(value)) { throw invalid("班级学科或年级不在校区开设范围"); }
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
