package cn.siyue.core;

import cn.siyue.core.SalesReportDtos.Metric;
import cn.siyue.core.SalesReportDtos.Report;
import cn.siyue.core.SalesReportDtos.Activity;
import cn.siyue.core.SalesReportDtos.ActivityPage;
import cn.siyue.core.SalesReportDtos.Followup;
import cn.siyue.core.SalesReportDtos.ActivityQuery;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Computes sales metrics from assignment facts and first-receipt attribution snapshots. */
@Repository
public class SalesReportRepository {
    private static final int MINIMUM_SAMPLE = 10;
    private final JdbcTemplate jdbc;

    public SalesReportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Report report(Actor actor, String employeeId, Instant from, Instant to) {
        boolean manager = actor.permissions().contains("report.read") && !actor.scope().equals("own");
        if (!actor.permissions().contains("report.read") && !actor.permissions().contains("lead.conversion.read")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有销售报表查看权限");
        }
        String selected = manager ? employeeId : actor.publicId();
        if (!manager && employeeId != null && !employeeId.equals(actor.publicId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能查看本人销售报表");
        }
        List<Seller> sellers = sellers(actor, selected);
        List<Metric> metrics = new ArrayList<>();
        for (Seller seller : sellers) { metrics.add(metric(actor, seller, from, to)); }
        return new Report(metrics, scopeLabel(actor), from, to, "assignment",
            "按分配时间统计唯一线索；转化按首笔有效收款时冻结的负责人和校区归属，金额汇总该线索关联订单");
    }

    public ActivityPage activity(Actor actor, ActivityQuery query) {
        requireActivityPermission(actor);
        ScopeSql.Clause visible = ScopeSql.leadReads(actor);
        Filter filter = activityFilter(actor, query, visible);
        List<Object> args = new ArrayList<>(filter.args()); args.add(query.limit() + 1);
        List<Activity> rows = jdbc.query("""
            SELECT e.public_id,e.occurred_at,p.public_id employee_public_id,l.public_id lead_public_id,e.event_type
            FROM core_crm_lead_event e JOIN core_crm_lead l ON l.id=e.lead_id AND l.deleted=0
            JOIN core_principal p ON p.id=e.actor_id
            WHERE e.deleted=0 AND (""" + filter.sql() + ")"
                + " ORDER BY e.occurred_at DESC,BINARY(CONCAT('lead:',e.public_id)) DESC LIMIT ?",
            (rs, row) -> new Activity("lead:" + rs.getString("public_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("employee_public_id"), rs.getString("lead_public_id"),
                rs.getString("event_type"), "success"), args.toArray());
        List<Activity> auditRows = auditActivity(actor, query);
        rows.addAll(auditRows);
        rows.sort(Comparator.comparing(Activity::at).reversed()
            .thenComparing(Activity::id, Comparator.reverseOrder()));
        boolean hasMore = rows.size() > query.limit();
        List<Activity> page = rows.size() > query.limit() ? rows.subList(0, query.limit()) : rows;
        return new ActivityPage(page, hasMore, hasMore ? page.get(page.size() - 1).id() : null);
    }

    private Filter activityFilter(Actor actor, ActivityQuery query, ScopeSql.Clause visible) {
        List<Object> args = new ArrayList<>(visible.args());
        StringBuilder sql = new StringBuilder("(" + visible.sql() + ")");
        addActivityFilter(sql, args, "p.public_id", selectedEmployee(actor, query.employeeId()));
        addActivityFilter(sql, args, "e.event_type", query.action());
        addActivityFilter(sql, args, "l.public_id", query.leadId());
        addActivityTimeFilter(sql, args, "e.occurred_at", query.from(), query.to());
        addActivityCursor(sql, args, "e.occurred_at", "BINARY(CONCAT('lead:',e.public_id))", query.cursor());
        return new Filter(sql.toString(), args);
    }

    private List<Activity> auditActivity(Actor actor, ActivityQuery query) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("a.deleted=0");
        if (!actor.scope().equals("all")) { sql.append(" AND a.actor_id=?"); args.add(actor.id()); }
        String selected = selectedEmployee(actor, query.employeeId());
        if (selected != null) { sql.append(" AND p.public_id=?"); args.add(selected); }
        if (query.action() != null && !query.action().isBlank()) { sql.append(" AND a.action=?"); args.add(query.action()); }
        addActivityTimeFilter(sql, args, "a.created_at", query.from(), query.to());
        if (query.leadId() != null && !query.leadId().isBlank()) {
            sql.append(" AND a.resource_public_id=?"); args.add(query.leadId());
        }
        addActivityCursor(sql, args, "a.created_at", "BINARY(CONCAT('audit:',LPAD(a.id,20,'0')))", query.cursor());
        args.add(query.limit() + 1);
        return jdbc.query("""
            SELECT a.id,a.created_at,p.public_id employee_public_id,a.action
            FROM core_audit a JOIN core_principal p ON p.id=a.actor_id WHERE 
            """ + sql + " ORDER BY a.created_at DESC,BINARY(CONCAT('audit:',LPAD(a.id,20,'0'))) DESC LIMIT ?",
            (rs, row) -> new Activity("audit:" + String.format("%020d", rs.getLong("id")), rs.getTimestamp("created_at").toInstant(),
                rs.getString("employee_public_id"), null, rs.getString("action"), "success"), args.toArray());
    }

    private void addActivityCursor(StringBuilder sql, List<Object> args, String timeColumn,
            String identityExpression, String cursor) {
        if (cursor == null) { return; }
        Cursor value = activityCursor(cursor);
        sql.append(" AND (").append(timeColumn).append("<? OR (").append(timeColumn)
            .append("=? AND ").append(identityExpression).append("<?))");
        args.add(Timestamp.from(value.at())); args.add(Timestamp.from(value.at())); args.add(cursor);
    }

    private Cursor activityCursor(String cursor) {
        if (cursor.startsWith("lead:")) {
            return findCursor("SELECT id,occurred_at FROM core_crm_lead_event WHERE public_id=? AND deleted=0",
                cursor.substring(5));
        }
        if (!cursor.startsWith("audit:")) { throw new BusinessException(ErrorCode.INVALID_INPUT, "活动分页位置无效"); }
        try {
            long id = Long.parseLong(cursor.substring(6));
            return findCursor("SELECT id,created_at FROM core_audit WHERE id=? AND deleted=0", id);
        } catch (NumberFormatException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "活动分页位置无效");
        }
    }

    private Cursor findCursor(String query, Object key) {
        List<Cursor> rows = jdbc.query(query, (rs, row) -> new Cursor(rs.getLong(1), rs.getTimestamp(2).toInstant()), key);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.INVALID_INPUT, "活动分页位置无效"); }
        return rows.get(0);
    }

    private void addActivityFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(column).append("=?"); args.add(value); }
    }

    private void addActivityTimeFilter(StringBuilder sql, List<Object> args, String column, Instant from, Instant to) {
        if (from != null) { sql.append(" AND ").append(column).append(">=?"); args.add(Timestamp.from(from)); }
        if (to != null) { sql.append(" AND ").append(column).append("<=?"); args.add(Timestamp.from(to)); }
    }

    private void appendCursor(StringBuilder sql, List<Object> args, String table, String alias, String cursor) {
        List<Cursor> rows = jdbc.query("SELECT id,occurred_at FROM " + table + " WHERE public_id=? AND deleted=0",
            (rs, row) -> new Cursor(rs.getLong(1), rs.getTimestamp(2).toInstant()), cursor);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.INVALID_INPUT, "活动分页位置无效"); }
        Cursor value = rows.get(0);
        sql.append(" AND (").append(alias).append(".occurred_at<? OR (").append(alias)
            .append(".occurred_at=? AND ").append(alias).append(".id<?))");
        args.add(Timestamp.from(value.at())); args.add(Timestamp.from(value.at())); args.add(value.id());
    }

    private String selectedEmployee(Actor actor, String requested) {
        boolean manager = mayInspectEmployees(actor);
        validateSelectedEmployee(actor, requested, "只能查看本人销售记录");
        return manager ? requested : actor.publicId();
    }

    private void validateSelectedEmployee(Actor actor, String requested, String message) {
        if (!mayInspectEmployees(actor) && requested != null && !requested.equals(actor.publicId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message);
        }
    }

    private record Filter(String sql, List<Object> args) { }

    public SalesReportDtos.Page<Followup> followups(Actor actor, String employeeId, int limit, String cursor,
            PiiCipher cipher) {
        requireActivityPermission(actor);
        ScopeSql.Clause visible = ScopeSql.leadReads(actor);
        List<Object> args = new ArrayList<>(visible.args());
        String filter = followupFilter(actor, employeeId, cursor, visible, args);
        args.add(limit + 1);
        List<Followup> rows = jdbc.query("""
            SELECT f.public_id,f.occurred_at,p.public_id employee_public_id,l.public_id lead_public_id,
                   f.channel,f.summary_cipher,f.next_follow_at
            FROM core_crm_lead_followup f JOIN core_crm_lead l ON l.id=f.lead_id AND l.deleted=0
            JOIN core_principal p ON p.id=f.actor_id
            WHERE f.deleted=0 AND """ + filter
                + " ORDER BY f.occurred_at DESC,f.id DESC LIMIT ?",
            (rs, row) -> new Followup(rs.getString("public_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("employee_public_id"), rs.getString("lead_public_id"), rs.getString("channel"),
                cipher.decrypt(rs.getString("summary_cipher")), rs.getTimestamp("next_follow_at") == null ? null
                    : rs.getTimestamp("next_follow_at").toInstant()), args.toArray());
        boolean hasMore = rows.size() > limit;
        List<Followup> page = hasMore ? rows.subList(0, limit) : rows;
        return new SalesReportDtos.Page<>(page, hasMore, hasMore ? page.get(page.size() - 1).id() : null);
    }

    private String followupFilter(Actor actor, String employeeId, String cursor, ScopeSql.Clause visible,
            List<Object> args) {
        String selected = selectedEmployee(actor, employeeId);
        StringBuilder sql = new StringBuilder("(" + visible.sql() + ")");
        addActivityFilter(sql, args, "p.public_id", selected);
        if (cursor != null) { appendCursor(sql, args, "core_crm_lead_followup", "f", cursor); }
        return sql.toString();
    }

    private void requireActivityPermission(Actor actor) {
        if (!actor.permissions().contains("sales.activity.read") && !actor.permissions().contains("audit.read")
                && !actor.permissions().contains("lead.assign") && !actor.permissions().contains("crm.lead.assign")
                && !actor.permissions().contains("system.access.manage")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有销售活动查看权限");
        }
    }

    private boolean mayInspectEmployees(Actor actor) {
        return !actor.scope().equals("own") && (actor.permissions().contains("audit.read") || actor.permissions().contains("lead.assign")
            || actor.permissions().contains("crm.lead.assign") || actor.permissions().contains("system.access.manage"));
    }

    private List<Seller> sellers(Actor actor, String selected) {
        String scope = switch (actor.scope()) {
            case "all" -> "1=1";
            case "own", "assigned" -> "p.id=?";
            case "campus" -> "EXISTS (SELECT 1 FROM core_principal_campus mine "
                + "JOIN core_principal_campus theirs ON theirs.campus_id=mine.campus_id AND theirs.deleted=0 "
                + "WHERE mine.principal_id=? AND mine.deleted=0 AND theirs.principal_id=p.id)";
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
        List<Object> args = new ArrayList<>();
        if (actor.scope().equals("own") || actor.scope().equals("assigned")) { args.add(actor.id()); }
        if (actor.scope().equals("campus")) { args.add(actor.id()); }
        if (selected != null) { scope += " AND p.public_id=?"; args.add(selected); }
        return jdbc.query("""
            SELECT p.id,p.public_id
            FROM core_principal p
            LEFT JOIN core_staff_profile sp ON sp.principal_id=p.id AND sp.deleted=0
            WHERE p.deleted=0 AND (p.active=1 OR sp.employment_end IS NOT NULL)
              AND (""" + scope + ") AND EXISTS (SELECT 1 FROM ("
                + "SELECT permission_code FROM core_principal_permission WHERE principal_id=p.id AND deleted=0 "
                + "UNION SELECT pp.permission_code FROM core_principal_position link "
                + "JOIN core_access_position pos ON pos.id=link.position_id AND pos.active=1 AND pos.deleted=0 "
                + "JOIN core_access_position_permission pp ON pp.position_id=pos.id AND pp.deleted=0 "
                + "WHERE link.principal_id=p.id AND link.deleted=0) grants "
                + "WHERE grants.permission_code='lead.follow' AND NOT EXISTS (SELECT 1 "
                + "FROM core_principal_denied_permission d WHERE d.principal_id=p.id "
                + "AND d.permission_code='lead.follow' AND d.deleted=0)) "
                + "AND NOT EXISTS (SELECT 1 FROM (SELECT permission_code FROM core_principal_permission "
                + "WHERE principal_id=p.id AND deleted=0 UNION SELECT pp.permission_code "
                + "FROM core_principal_position link JOIN core_access_position pos ON pos.id=link.position_id "
                + "AND pos.active=1 AND pos.deleted=0 JOIN core_access_position_permission pp "
                + "ON pp.position_id=pos.id AND pp.deleted=0 WHERE link.principal_id=p.id AND link.deleted=0) admin_grants "
                + "WHERE admin_grants.permission_code='system.access.manage') ORDER BY p.public_id",
            (rs, row) -> new Seller(rs.getLong(1), rs.getString(2)), args.toArray());
    }

    private Metric metric(Actor actor, Seller seller, Instant from, Instant to) {
        List<AssignedLead> leads = assignedLeads(actor, seller, from, to);
        long converted = 0, coverage = 0;
        BigDecimal received = BigDecimal.ZERO, refunded = BigDecimal.ZERO, duration = BigDecimal.ZERO;
        for (AssignedLead lead : leads) {
            MetricContribution contribution = conversionContribution(lead, seller);
            if (contribution == null) { continue; }
            converted++;
            received = received.add(contribution.received());
            refunded = refunded.add(contribution.refunded());
            if (contribution.durationHours() != null) {
                duration = duration.add(contribution.durationHours()); coverage++;
            }
        }
        long assigned = leads.size(), overdue = leads.stream().filter(AssignedLead::overdue).count();
        boolean low = assigned < MINIMUM_SAMPLE;
        return new Metric(seller.publicId(), assigned, converted, rate(converted, assigned, low), overdue,
            rate(overdue, assigned, low), low, MINIMUM_SAMPLE, received, refunded, received.subtract(refunded),
            coverage == 0 ? null : duration.divide(BigDecimal.valueOf(coverage), 2, java.math.RoundingMode.HALF_UP), coverage);
    }

    private List<AssignedLead> assignedLeads(Actor actor, Seller seller, Instant from, Instant to) {
        String leadScope = switch (actor.scope()) {
            case "all" -> "1=1";
            case "own", "assigned" -> "1=1";
            case "campus" -> "l.campus_id IN (SELECT campus_id FROM core_principal_campus "
                + "WHERE principal_id=? AND deleted=0)";
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
        List<Object> args = new ArrayList<>();
        args.add(seller.id());
        args.add(seller.id());
        if (actor.scope().equals("campus")) { args.add(actor.id()); }
        if (from != null) { args.add(Timestamp.from(from)); }
        if (to != null) { args.add(Timestamp.from(to)); }
        return jdbc.query("""
            SELECT l.id,l.public_id,MIN(a.assigned_at) assigned_at,
                   MAX(CASE WHEN l.next_follow_at IS NOT NULL AND l.next_follow_at<UTC_TIMESTAMP(3)
                       AND l.owner_id=? THEN 1 ELSE 0 END) overdue
            FROM core_crm_lead l JOIN (
                SELECT e.lead_id,e.to_owner_id owner_id,e.occurred_at assigned_at
                FROM core_crm_lead_event e WHERE e.deleted=0 AND e.to_owner_id IS NOT NULL
                    AND e.event_type IN ('created','assigned','transferred')
                UNION ALL
                SELECT d.lead_id,d.owner_id,d.occurred_at FROM core_crm_lead_decision_event d
                WHERE d.deleted=0 AND d.owner_id IS NOT NULL
                    AND d.decision IN ('retained','transferred','retain','transfer','claim','reclaim')
                UNION ALL
                SELECT l2.id,l2.decision_by_id,l2.created_at FROM core_crm_lead l2
                WHERE l2.deleted=0 AND l2.decision_by_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM core_crm_lead_event x WHERE x.lead_id=l2.id
                      AND x.deleted=0 AND x.to_owner_id IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM core_crm_lead_decision_event d WHERE d.lead_id=l2.id
                      AND d.deleted=0 AND d.owner_id IS NOT NULL
                      AND d.decision IN ('retained','transferred','retain','transfer','claim','reclaim'))
                UNION ALL
                SELECT l3.id,l3.owner_id,COALESCE(l3.created_at,l3.updated_at) FROM core_crm_lead l3
                WHERE l3.deleted=0 AND l3.owner_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM core_crm_lead_event x WHERE x.lead_id=l3.id
                      AND x.deleted=0 AND x.to_owner_id IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM core_crm_lead_decision_event d WHERE d.lead_id=l3.id
                      AND d.deleted=0 AND d.owner_id IS NOT NULL
                      AND d.decision IN ('retained','transferred','retain','transfer','claim','reclaim'))
                  AND NOT EXISTS (SELECT 1 FROM core_crm_lead l4 WHERE l4.id=l3.id AND l4.decision_by_id IS NOT NULL)
            ) a ON a.lead_id=l.id AND a.owner_id=?
            WHERE l.deleted=0 AND (""" + leadScope + ")"
                + (from == null ? "" : " AND a.assigned_at>=?") + (to == null ? "" : " AND a.assigned_at<=?")
                + " GROUP BY l.id,l.public_id",
            (rs, row) -> new AssignedLead(rs.getLong("id"), rs.getString("public_id"),
                rs.getTimestamp("assigned_at").toInstant(), rs.getBoolean("overdue")), args.toArray());
    }

    private MetricContribution conversionContribution(AssignedLead lead, Seller seller) {
        List<Conversion> snapshots = jdbc.query("""
            SELECT owner_id,converted_at FROM core_crm_lead_conversion_snapshot
            WHERE lead_id=? AND deleted=0
            """, (rs, row) -> new Conversion(rs.getLong(1), rs.getTimestamp(2).toInstant()), lead.id());
        if (snapshots.isEmpty() || snapshots.get(0).ownerId() != seller.id()) { return null; }
        Conversion snapshot = snapshots.get(0);
        Instant assignedAt = latestAssignment(lead.id(), seller.id(), snapshot.at());
        if (assignedAt == null && !snapshot.at().isBefore(lead.assignedAt())) { assignedAt = lead.assignedAt(); }
        BigDecimal duration = assignedAt == null ? null : BigDecimal.valueOf(
            java.time.Duration.between(assignedAt, snapshot.at()).toMillis())
            .divide(BigDecimal.valueOf(3_600_000), 6, java.math.RoundingMode.HALF_UP);
        List<Money> sums = jdbc.query("""
            SELECT COALESCE(SUM(o.received_amount),0),COALESCE(SUM(o.refunded_amount),0)
            FROM core_crm_order o JOIN core_opportunity opp ON opp.id=o.opportunity_id
            WHERE opp.source_lead_id=? AND o.deleted=0 AND opp.deleted=0
            """, (rs, row) -> new Money(rs.getBigDecimal(1), rs.getBigDecimal(2)), lead.id());
        Money total = sums.isEmpty() ? new Money(BigDecimal.ZERO, BigDecimal.ZERO) : sums.get(0);
        return new MetricContribution(total.received(), total.refunded(), duration);
    }

    private Instant latestAssignment(long leadId, long ownerId, Instant convertedAt) {
        List<Timestamp> rows = jdbc.query("""
            SELECT MAX(e.occurred_at) FROM core_crm_lead_event e
            WHERE e.lead_id=? AND e.to_owner_id=? AND e.deleted=0
                AND e.event_type IN ('created','assigned','transferred') AND e.occurred_at<=?
            """, (rs, row) -> rs.getTimestamp(1), leadId, ownerId, Timestamp.from(convertedAt));
        Instant leadEvent = rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toInstant();
        List<Timestamp> decisions = jdbc.query("""
            SELECT MAX(d.occurred_at) FROM core_crm_lead_decision_event d
            WHERE d.lead_id=? AND d.owner_id=? AND d.deleted=0
                AND d.decision IN ('retained','transferred','retain','transfer','claim','reclaim') AND d.occurred_at<=?
            """, (rs, row) -> rs.getTimestamp(1), leadId, ownerId, Timestamp.from(convertedAt));
        Instant decisionEvent = decisions.isEmpty() || decisions.get(0) == null ? null : decisions.get(0).toInstant();
        return leadEvent == null ? decisionEvent : decisionEvent == null || leadEvent.isAfter(decisionEvent)
            ? leadEvent : decisionEvent;
    }

    private BigDecimal rate(long numerator, long denominator, boolean lowSample) {
        return lowSample || denominator == 0 ? null
            : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_UP);
    }

    private String scopeLabel(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> "全部授权数据";
            case "campus" -> "授权校区";
            default -> "本人负责范围";
        };
    }
    private record Seller(long id, String publicId) { }
    private record AssignedLead(long id, String publicId, Instant assignedAt, boolean overdue) { }
    private record Conversion(long ownerId, Instant at) { }
    private record Money(BigDecimal received, BigDecimal refunded) { }
    private record Cursor(long id, Instant at) { }
    private record MetricContribution(BigDecimal received, BigDecimal refunded, BigDecimal durationHours) { }
}
