package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmWorkbenchQueueItem;
import cn.siyue.core.CrmDtos.CrmWorkbenchQueuePage;
import cn.siyue.core.CrmDtos.CrmWorkbenchQueueQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Executes bounded workbench queue reads from the same scoped predicate used for each count. */
@Repository
public class CrmWorkbenchQueueRepository {
    private record Plan(String select, List<Object> args) { }
    private final JdbcTemplate jdbc;

    /** Creates the repository using the application's bound-parameter JDBC client.
     * @param jdbc JDBC client for the configured MySQL database
     */
    public CrmWorkbenchQueueRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Reads a stable count and bounded page for one known workbench card.
     * @param actor authenticated actor whose active grants and resource scope are applied
     * @param scope requested personal or authorized team view
     * @param resource fixed CRM resource key
     * @param queue fixed workbench queue key for that resource
     * @param asOf server timestamp shared with the count card
     * @param page one-based page number
     * @param size bounded page size
     * @return visible queue rows and their matching total
     */
    public CrmWorkbenchQueuePage page(Actor actor, CrmWorkbenchQueueQuery query) {
        Plan plan = plan(actor, query.scope(), query.resource(), query.queue(), query.asOf());
        String from = " FROM (" + plan.select() + ") q";
        Long total = jdbc.queryForObject("SELECT COUNT(q.sort_id)" + from, Long.class, plan.args().toArray());
        List<Object> args = new ArrayList<>(plan.args());
        args.add(query.size());
        args.add(Math.multiplyExact((long) query.page() - 1, query.size()));
        List<CrmWorkbenchQueueItem> items = jdbc.query("SELECT q.public_id,q.status,q.created_at,q.due_at,q.owner_id"
            + from + " ORDER BY q.sort_at,q.sort_id LIMIT ? OFFSET ?", (rs, row) -> new CrmWorkbenchQueueItem(
                rs.getString("public_id"), rs.getString("status"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("due_at")), rs.getString("owner_id")), args.toArray());
        return new CrmWorkbenchQueuePage(items, total == null ? 0 : total, query.page(), query.size(), query.asOf());
    }

    private Plan plan(Actor actor, String scope, String resource, String queue, Instant asOf) {
        return switch (resource) {
            case "leads" -> leadPlan(actor, scope, queue, asOf);
            case "trialBookings" -> trialPlan(actor, scope, queue, asOf);
            case "formSubmissions" -> submissionPlan(actor, scope, queue, asOf);
            case "leadReclaimRequests" -> reclaimPlan(actor, scope, queue, asOf);
            case "attributionRequests" -> attributionPlan(actor, scope, queue, asOf);
            case "crmTasks" -> taskPlan(actor, scope, queue, asOf);
            case "opportunities" -> opportunityPlan(actor, scope, queue, asOf);
            case "serviceTickets" -> ticketPlan(actor, scope, queue, asOf);
            default -> throw invalid("工作台资源不支持");
        };
    }

    private Plan leadPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause visible = ScopeSql.leadReads(actor);
        List<Object> args = new ArrayList<>(visible.args());
        String where = "l.deleted=0 AND (" + visible.sql() + ")";
        String assigned = "COALESCE(l.assigned_at,l.created_at)";
        String lastFollow = "(SELECT MAX(f.occurred_at) FROM core_crm_lead_followup f WHERE f.lead_id=l.id "
            + "AND f.deleted=0 AND f.occurred_at>=" + assigned + ")";
        String firstFollow = "COALESCE(l.assignment_follow_at,(SELECT MIN(f.occurred_at) FROM core_crm_lead_followup f "
            + "WHERE f.lead_id=l.id AND f.deleted=0 AND f.occurred_at>=" + assigned + "))";
        String active = "l.owner_id IS NOT NULL AND l.legacy_stage NOT IN ('已归档') AND l.legacy_status NOT IN ('cold','archived') "
            + "AND l.status NOT IN ('archived','no_need','invalid_contact') AND NOT EXISTS(SELECT cs.id "
            + "FROM core_crm_lead_conversion_snapshot cs WHERE cs.lead_id=l.id AND cs.deleted=0)";
        switch (queue) {
            case "firstContact" -> where += " AND " + active + " AND (" + firstFollow + " IS NULL OR "
                + firstFollow + "<" + assigned + ")";
            case "today" -> where += " AND " + active + " AND " + dayPredicate("l.next_follow_at", asOf, args);
            case "future" -> where += " AND " + active + " AND " + afterDay("l.next_follow_at", asOf, args);
            case "overdue" -> {
                args.add(Timestamp.from(asOf));
                args.add(Timestamp.from(asOf));
                where += " AND " + active + " AND (((" + firstFollow + " IS NULL OR " + firstFollow + "<" + assigned
                    + ") AND TIMESTAMPADD(HOUR,"
                    + "(SELECT first_follow_hours FROM core_crm_lead_policy WHERE singleton=1 AND deleted=0),"
                    + assigned + ")<=?) OR l.next_follow_at<?)";
            }
            case "unassigned" -> {
                where += " AND l.owner_id IS NULL AND l.decision_status<>'pending' AND l.legacy_stage<>'已归档' "
                    + "AND l.legacy_status NOT IN ('cold','archived') AND l.status NOT IN ('archived','no_need','invalid_contact') "
                    + "AND NOT EXISTS(SELECT cs.id FROM core_crm_lead_conversion_snapshot cs WHERE cs.lead_id=l.id AND cs.deleted=0)";
                if ("mine".equals(scope)) {
                    where += " AND (l.registrar_id=? OR l.source_created_by_id=?)";
                    args.add(actor.id()); args.add(actor.id());
                }
            }
            case "decision" -> {
                where += " AND l.decision_status='pending' AND l.legacy_stage<>'已归档' AND l.legacy_status NOT IN ('cold','archived')";
                if ("mine".equals(scope)) { where += " AND l.decision_by_id=?"; args.add(actor.id()); }
            }
            case "recycleSoon" -> {
                where += " AND " + active + " AND GREATEST(TIMESTAMPADD(DAY,p.reserve_days," + assigned
                    + "),TIMESTAMPADD(DAY,p.reclaim_days,COALESCE(" + lastFollow + "," + assigned + "))) > ?"
                    + " AND GREATEST(TIMESTAMPADD(DAY,p.reserve_days," + assigned + "),TIMESTAMPADD(DAY,p.reclaim_days,COALESCE("
                    + lastFollow + "," + assigned + "))) <= ?";
                args.add(Timestamp.from(asOf)); args.add(Timestamp.from(asOf.plusSeconds(3 * 86400L)));
            }
            default -> throw invalid("线索工作台队列无效");
        }
        if ("mine".equals(scope) && !Set.of("unassigned", "decision").contains(queue)) {
            where += " AND l.owner_id=?";
            args.add(actor.id());
        }
        return leadSelect(where, args);
    }

    private Plan leadSelect(String where, List<Object> args) {
        return new Plan("SELECT l.id sort_id,l.public_id,l.legacy_status status,l.created_at,"
            + "l.next_follow_at due_at,owner.public_id owner_id,COALESCE(l.next_follow_at,l.created_at) sort_at "
            + "FROM core_crm_lead l JOIN core_crm_lead_policy p ON p.singleton=1 AND p.deleted=0 "
            + "LEFT JOIN core_principal owner ON owner.id=l.owner_id WHERE " + where, List.copyOf(args));
    }

    private Plan trialPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause form = ScopeSql.forms(actor);
        ScopeSql.Clause submission = LegacyFormSubmissionAccess.visibility(jdbc, actor, false);
        List<Object> args = new ArrayList<>(form.args()); args.addAll(submission.args());
        String where = "b.deleted=0 AND b.status='confirmed' AND f.deleted=0 AND s.deleted=0 AND ("
            + form.sql() + ") AND (" + submission.sql() + ")";
        where += dateQueue("ss.starts_at", queue, asOf, args);
        if ("mine".equals(scope)) { where += " AND b.publisher_id=?"; args.add(actor.id()); }
        return new Plan("SELECT b.id sort_id,b.public_id,b.status,b.booked_at created_at,ss.starts_at due_at,"
            + "publisher.public_id owner_id,ss.starts_at sort_at FROM core_trial_booking b "
            + "JOIN core_crm_form f ON f.id=b.form_id JOIN core_crm_form_submission s ON s.id=b.submission_id "
            + "JOIN core_session ss ON ss.id=b.session_id LEFT JOIN core_principal publisher ON publisher.id=b.publisher_id "
            + "WHERE " + where, List.copyOf(args));
    }

    private Plan submissionPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause form = ScopeSql.forms(actor);
        ScopeSql.Clause visibility = LegacyFormSubmissionAccess.visibility(jdbc, actor, false);
        List<Object> args = new ArrayList<>(form.args()); args.addAll(visibility.args());
        String where = "s.deleted=0 AND f.deleted=0 AND s.status='pending' AND (" + form.sql()
            + ") AND (" + visibility.sql() + ")";
        if ("mine".equals(scope)) { where += " AND r.published_by=?"; args.add(actor.id()); }
        return new Plan("SELECT s.id sort_id,s.public_id,s.status,s.submitted_at created_at,s.submitted_at due_at,"
            + "publisher.public_id owner_id,s.submitted_at sort_at FROM core_crm_form_submission s "
            + "JOIN core_crm_form f ON f.id=s.form_id JOIN core_crm_form_revision r ON r.id=s.revision_id "
            + "LEFT JOIN core_principal publisher ON publisher.id=r.published_by WHERE " + where, List.copyOf(args));
    }

    private Plan reclaimPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause leads = ScopeSql.leadReads(actor);
        List<Object> args = new ArrayList<>(leads.args());
        String where = "r.deleted=0 AND l.deleted=0 AND r.status='pending' AND (" + leads.sql() + ")";
        if ("mine".equals(scope)) { where += " AND r.applicant_id=?"; args.add(actor.id()); }
        return new Plan("SELECT r.id sort_id,r.public_id,r.status,r.created_at,NULL due_at,"
            + "applicant.public_id owner_id,r.created_at sort_at FROM core_crm_lead_reclaim_request r "
            + "JOIN core_crm_lead l ON l.id=r.lead_id LEFT JOIN core_principal applicant ON applicant.id=r.applicant_id "
            + "WHERE " + where, List.copyOf(args));
    }

    private Plan attributionPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause orders = ScopeSql.orders(actor);
        List<Object> args = new ArrayList<>(orders.args());
        String where = "r.deleted=0 AND o.deleted=0 AND r.status='pending_approval' AND (" + orders.sql() + ")";
        if ("mine".equals(scope)) { where += " AND r.applicant_id=?"; args.add(actor.id()); }
        return new Plan("SELECT r.id sort_id,r.public_id,r.status,r.created_at,NULL due_at,"
            + "applicant.public_id owner_id,r.created_at sort_at FROM core_crm_attribution_request r "
            + "JOIN core_crm_order o ON o.id=r.order_id LEFT JOIN core_principal applicant ON applicant.id=r.applicant_id "
            + "WHERE " + where, List.copyOf(args));
    }

    private Plan taskPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause legacy = taskScope(actor);
        ScopeSql.Clause visible = TaskRoutingScope.execution(actor, legacy);
        List<Object> args = new ArrayList<>(visible.args());
        String where = "t.deleted=0 AND b.deleted=0 AND t.status IN ('pending','in_progress') AND ("
            + visible.sql() + ")";
        where += dateQueue("t.due_at", queue, asOf, args);
        if ("mine".equals(scope)) { where += " AND t.owner_id=?"; args.add(actor.id()); }
        return new Plan("SELECT t.id sort_id,t.public_id,t.status,t.created_at,t.due_at,owner.public_id owner_id,"
            + "t.due_at sort_at FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id "
            + "LEFT JOIN core_principal owner ON owner.id=t.owner_id WHERE " + where, List.copyOf(args));
    }

    private Plan opportunityPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause visible = ScopeSql.opportunityReads(actor);
        List<Object> args = new ArrayList<>(visible.args());
        String where = "o.deleted=0 AND o.stage NOT IN ('won','closed') AND (" + visible.sql() + ")";
        where += dateQueue("o.next_follow_at", queue, asOf, args);
        if ("mine".equals(scope)) { where += " AND o.owner_id=?"; args.add(actor.id()); }
        return new Plan("SELECT o.id sort_id,o.public_id,o.stage status,o.created_at,o.next_follow_at due_at,"
            + "owner.public_id owner_id,COALESCE(o.next_follow_at,o.created_at) sort_at FROM core_opportunity o "
            + "LEFT JOIN core_principal owner ON owner.id=o.owner_id WHERE " + where, List.copyOf(args));
    }

    private Plan ticketPlan(Actor actor, String scope, String queue, Instant asOf) {
        ScopeSql.Clause visible = CrmLegacyAccess.legacyRead(actor, "crm.ticket.read")
            ? LegacyCrmScope.ticket(actor, true) : ticketScope(actor);
        List<Object> args = new ArrayList<>(visible.args());
        String where = "t.deleted=0 AND t.status IN ('open','processing') AND t.due_at IS NOT NULL AND ("
            + visible.sql() + ") AND t.due_at<?";
        args.add(Timestamp.from(asOf));
        if ("mine".equals(scope)) { where += " AND (t.owner_id=? OR t.created_by=?)"; args.add(actor.id()); args.add(actor.id()); }
        return new Plan("SELECT t.id sort_id,t.public_id,t.status,t.created_at,t.due_at,owner.public_id owner_id,"
            + "t.due_at sort_at FROM core_crm_service_ticket t LEFT JOIN core_principal owner ON owner.id=t.owner_id WHERE "
            + where, List.copyOf(args));
    }

    private ScopeSql.Clause taskScope(Actor actor) {
        ScopeSql.Clause base = switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("t.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                + "WHERE pc.principal_id=? AND pc.deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> CrmLegacyAccess.legacyRead(actor, "crm.task.read")
                && actor.permissions().contains("lead.assign")
                ? new ScopeSql.Clause("(t.owner_id=? OR EXISTS(SELECT cb.id FROM core_crm_task_batch cb "
                    + "WHERE cb.id=t.batch_id AND cb.created_by=?))", List.of(actor.id(), actor.id()))
                : new ScopeSql.Clause("t.owner_id=?", List.of(actor.id()));
            default -> throw invalid("账号数据范围无效");
        };
        return TaskRoutingScope.execution(actor, base);
    }

    private ScopeSql.Clause ticketScope(Actor actor) {
        if (actor.permissions().contains("lead.follow") || actor.permissions().contains("lead.assign")) {
            return LegacyCrmScope.ticket(actor, false);
        }
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("t.campus_id IN (SELECT pc.campus_id FROM core_principal_campus "
                + "pc WHERE pc.principal_id=? AND pc.deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("t.owner_id=?", List.of(actor.id()));
            default -> throw invalid("账号数据范围无效");
        };
    }

    private String dateQueue(String column, String queue, Instant asOf, List<Object> args) {
        return switch (queue) {
            case "today" -> " AND " + dayPredicate(column, asOf, args);
            case "future" -> " AND " + afterDay(column, asOf, args);
            case "overdue" -> { args.add(Timestamp.from(asOf)); yield " AND " + column + "<?"; }
            default -> throw invalid("日期工作台队列无效");
        };
    }

    private String dayPredicate(String column, Instant asOf, List<Object> args) {
        Instant from = asOf.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        Instant until = from.plusSeconds(86400);
        args.add(Timestamp.from(from)); args.add(Timestamp.from(until));
        return column + ">=? AND " + column + "<?";
    }

    private String afterDay(String column, Instant asOf, List<Object> args) {
        Instant until = asOf.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1)
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        args.add(Timestamp.from(until));
        return column + ">=?";
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
