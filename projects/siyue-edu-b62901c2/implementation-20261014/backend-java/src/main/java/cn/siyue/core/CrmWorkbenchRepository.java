package cn.siyue.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads server-authoritative CRM queue counts from the same lead visibility scope as list queries. */
@Repository
public class CrmWorkbenchRepository {
    record Counts(long newLeads, long dueToday, long overdue, long upcoming, long publicLeads) { }
    private final JdbcTemplate jdbc;
    public CrmWorkbenchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Counts each queue in one snapshot query, constrained by current scope and optional own-owner view. */
    public Counts counts(Actor actor, boolean mine, Instant fromAt, Instant untilAt) {
        ScopeSql.Clause scope = ScopeSql.leads(actor);
        String owner = mine ? " AND l.owner_id=?" : "";
        List<Object> args = new java.util.ArrayList<>(List.of(Timestamp.from(fromAt), Timestamp.from(untilAt),
            Timestamp.from(fromAt), Timestamp.from(untilAt)));
        args.addAll(scope.args());
        if (mine) { args.add(actor.id()); }
        List<Counts> rows = jdbc.query("""
            SELECT COALESCE(SUM(l.status='new' AND l.owner_id IS NOT NULL),0) AS new_leads,
                   COALESCE(SUM(l.next_follow_at>=? AND l.next_follow_at<?),0) AS due_today,
                   COALESCE(SUM(l.next_follow_at IS NOT NULL AND l.next_follow_at<?),0) AS overdue,
                   COALESCE(SUM(l.next_follow_at>=?),0) AS upcoming,
                   COALESCE(SUM(l.owner_id IS NULL AND l.status NOT IN ('archived','invalid_contact')),0) AS public_leads
            FROM core_crm_lead l
            WHERE l.deleted=0 AND l.status NOT IN ('archived','no_need','invalid_contact')
              AND (""" + scope.sql() + ")" + owner,
            (rs, row) -> new Counts(rs.getLong("new_leads"), rs.getLong("due_today"), rs.getLong("overdue"),
                rs.getLong("upcoming"), rs.getLong("public_leads")), args.toArray());
        return rows.get(0);
    }
}
