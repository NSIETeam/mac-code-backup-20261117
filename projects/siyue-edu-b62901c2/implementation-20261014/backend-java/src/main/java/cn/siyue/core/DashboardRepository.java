package cn.siyue.core;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Counts authorized resources without returning their underlying records. */
@Repository
public class DashboardRepository {
    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long leads(Actor actor) {
        ScopeSql.Clause scope = ScopeSql.leadReads(actor);
        return count("SELECT COUNT(l.id) FROM core_crm_lead l WHERE l.deleted=0 AND (" + scope.sql() + ")",
            scope.args());
    }

    public long activeStudents(Actor actor) {
        ScopeSql.Clause scope = ScopeSql.studentReads(actor);
        return count("SELECT COUNT(DISTINCT e.student_id) FROM core_effective_enrollment e "
            + "JOIN core_student s ON s.id=e.student_id AND s.deleted=0 "
            + "WHERE e.status='active' AND e.deleted=0 AND (" + scope.sql() + ")", scope.args());
    }

    public long outstandingOrders(Actor actor) {
        ScopeSql.Clause scope = ScopeSql.orders(actor);
        return count("SELECT COUNT(o.id) FROM core_crm_order o WHERE o.deleted=0 "
            + "AND o.status NOT IN ('cancelled','closed') AND o.total_amount>o.received_amount AND ("
            + scope.sql() + ")", scope.args());
    }

    private long count(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }
}
