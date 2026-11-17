package cn.siyue.core;

import cn.siyue.core.LeadRoutingDtos.Candidate;
import cn.siyue.core.LeadRoutingDtos.QueueEntry;
import cn.siyue.core.LeadRoutingDtos.QueuePage;
import cn.siyue.core.LeadRoutingDtos.RoutingPlan;
import cn.siyue.core.LeadRoutingDtos.RoutingRule;
import cn.siyue.core.LeadRoutingDtos.RulePage;
import cn.siyue.core.LeadRoutingDtos.RuleHistoryEvent;
import cn.siyue.core.LeadRoutingDtos.RuleHistoryPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists scoped lead-routing rules and the unmatched assignment queue. */
@Repository
public class LeadRoutingRepository {
    record RouteLead(long id, String publicId, Long campusId, String campusPublicId,
            String activityPublicId, String subject, String source, String status,
            String ownerId, long version) { }
    private record RuleRow(long id, String publicId, String name, long campusId, String campusPublicId,
            String activityPublicId, String subject, String source, String ownerId,
            int priority, boolean active, long version) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** Uses only parameterized MySQL operations inside the caller's transaction. */
    public LeadRoutingRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc; this.cipher = cipher;
    }
    /** Rechecks every reference before an idempotent create replay is returned. */
    public void authorizeCreateRule(Actor actor, LeadRoutingDtos.RuleCreate input) {
        long campus = campus(actor, input.campusId());
        activity(actor, input.activityId(), campus);
        eligibleOwner(input.ownerId(), campus);
    }
    /** Lists rules only for the employee's authorized campuses. */
    public RulePage listRules(Actor actor, String campusPublicId, int page, int size) {
        String where = "r.deleted=0";
        List<Object> args = new ArrayList<>();
        if (actor.scope().equals("campus")) {
            where += " AND r.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)";
            args.add(actor.id());
        }
        if (campusPublicId != null) { where += " AND c.public_id=?"; args.add(campusPublicId); }
        Long total = jdbc.queryForObject("SELECT COUNT(r.id) FROM core_crm_routing_rule r "
            + "JOIN core_campus c ON c.id=r.campus_id WHERE " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        List<RuleRow> rows = jdbc.query(selectRule() + " WHERE " + where
            + " ORDER BY r.priority,r.id LIMIT ? OFFSET ?", (rs, row) -> rule(rs), pageArgs.toArray());
        return new RulePage(rows.stream().map(this::dto).toList(), total == null ? 0 : total, page, size);
    }
    /** Confirms a rule identifier belongs to the actor's currently visible campuses. */
    public void authorizeRule(Actor actor, String publicId) { ruleRow(actor, publicId, false); }
    /** Pages immutable rule changes after verifying the rule remains in the caller's campus scope. */
    public RuleHistoryPage ruleHistory(Actor actor, String publicId, int page, int size) {
        RuleRow rule = ruleRow(actor, publicId, false);
        Long total = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_routing_rule_event WHERE rule_id=? AND deleted=0",
            Long.class, rule.id());
        List<RuleHistoryEvent> items = jdbc.query("SELECT e.public_id,e.source_event_id,e.action,p.public_id AS actor_public_id,"
            + "e.occurred_at,e.reason_cipher,e.changes_cipher FROM core_crm_routing_rule_event e "
            + "JOIN core_principal p ON p.id=e.actor_id WHERE e.rule_id=? AND e.deleted=0 ORDER BY e.occurred_at,e.id LIMIT ? OFFSET ?",
            (rs, row) -> new RuleHistoryEvent(rs.getString("public_id"), rs.getString("source_event_id"),
                rs.getString("action"), rs.getString("actor_public_id"), rs.getTimestamp("occurred_at").toInstant(),
                decrypt(rs.getString("reason_cipher")), decrypt(rs.getString("changes_cipher"))),
            rule.id(), size, (long) (page - 1) * size);
        return new RuleHistoryPage(items, total == null ? 0 : total, page, size);
    }
    /** Appends an immutable create/update record in the same business transaction. */
    public void appendRuleEvent(Actor actor, String publicId, String action, String changes) {
        long ruleId = ruleRow(actor, publicId, false).id();
        jdbc.update("INSERT INTO core_crm_routing_rule_event(public_id,rule_id,actor_id,action,occurred_at,changes_cipher) "
            + "VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)", "ROUTE_RULE_EVENT_" + UUID.randomUUID(), ruleId, actor.id(), action,
            cipher.encrypt(changes));
    }
    /** Creates a rule after verifying campus, optional activity and owner relations. */
    public RoutingRule create(Actor actor, LeadRoutingDtos.RuleCreate input) {
        long campus = campus(actor, input.campusId());
        Long activity = activity(actor, input.activityId(), campus);
        long owner = eligibleOwner(input.ownerId(), campus);
        String id = "ROUTE_RULE_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_routing_rule
                (public_id,name,campus_id,activity_id,subject,source,owner_id,priority,active,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """, id, input.name().trim(), campus, activity, clean(input.subject()), input.source(), owner,
            input.priority(), input.active(), actor.id());
        return rule(actor, id, true);
    }
    /** Updates a rule without allowing its campus to move across authorization boundaries. */
    public RoutingRule update(Actor actor, String publicId, LeadRoutingDtos.RuleUpdate input) {
        RuleRow current = ruleRow(actor, publicId, true);
        Long activity = activity(actor, input.activityId(), current.campusId());
        long owner = eligibleOwner(input.ownerId(), current.campusId());
        int changed = jdbc.update("""
            UPDATE core_crm_routing_rule SET name=?,activity_id=?,subject=?,source=?,owner_id=?,priority=?,
                active=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, input.name().trim(), activity, clean(input.subject()), input.source(), owner,
            input.priority(), input.active(), current.id(), input.expectedVersion());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "分配规则已变化，请刷新后重试"); }
        return rule(actor, publicId, false);
    }
    /** Loads an unassigned lead under the actor's current assignment scope, optionally locking it. */
    RouteLead lead(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.leads(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<RouteLead> rows = jdbc.query("""
            SELECT l.id,l.public_id,l.campus_id,c.public_id AS campus_public_id,a.public_id AS activity_public_id,
                   l.subject,COALESCE(l.source_label,l.source) AS source,l.status,owner.public_id AS owner_public_id,l.version
            FROM core_crm_lead l LEFT JOIN core_campus c ON c.id=l.campus_id
            LEFT JOIN core_activity a ON a.id=l.activity_id
            LEFT JOIN core_principal owner ON owner.id=l.owner_id
            WHERE l.public_id=? AND l.deleted=0 AND (""" + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new RouteLead(rs.getLong("id"), rs.getString("public_id"), rs.getObject("campus_id", Long.class),
                rs.getString("campus_public_id"), rs.getString("activity_public_id"), rs.getString("subject"),
                rs.getString("source"), rs.getString("status"), rs.getString("owner_public_id"),
                rs.getLong("version")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在或不在授权范围"); }
        return routingInput(actor, rows.get(0));
    }
    /** Uses the newest linked touchpoint visible in the manager's campus, preserving independent fallbacks. */
    private RouteLead routingInput(Actor actor, RouteLead lead) {
        List<Object> args = new ArrayList<>(List.of(lead.id()));
        String scope;
        if (actor.scope().equals("all")) { scope = "1=1"; }
        else if (actor.scope().equals("campus")) {
            scope = "t.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)";
            args.add(actor.id());
        } else { return lead; }
        List<RouteLead> inputs = jdbc.query("SELECT a.public_id AS activity_public_id,t.subject,"
            + "COALESCE(t.source_label,t.source) AS source FROM core_customer_touchpoint t "
            + "LEFT JOIN core_activity a ON a.id=t.activity_id WHERE t.lead_id=? AND t.deleted=0 AND ("
            + scope + ") ORDER BY t.created_at DESC,t.id DESC LIMIT 1", (rs, index) -> new RouteLead(
                lead.id(), lead.publicId(), lead.campusId(), lead.campusPublicId(),
                fallback(rs.getString("activity_public_id"), lead.activityPublicId()),
                fallback(rs.getString("subject"), lead.subject()), fallback(rs.getString("source"), lead.source()),
                lead.status(), lead.ownerId(), lead.version()), args.toArray());
        return inputs.isEmpty() ? lead : inputs.get(0);
    }
    private static String fallback(String latest, String original) {
        return latest == null || latest.isEmpty() ? original : latest;
    }
    /** Simulates matching rules and reports ineligible owners without applying a rule. */
    RoutingPlan simulate(RouteLead lead) {
        List<RuleRow> matches = matchingRules(lead);
        List<Candidate> candidates = matches.stream().map(row -> {
            boolean eligible = ownerEligible(row.ownerId(), row.campusId());
            return new Candidate(row.publicId(), row.name(), row.ownerId(), row.priority(), eligible,
                eligible ? "可接收" : "负责人已停用、缺少跟进权限或不属于此校区");
        }).toList();
        Candidate selected = candidates.stream().filter(Candidate::eligible).findFirst().orElse(null);
        String reason = selected != null ? "命中优先级 " + selected.priority() + " 的规则 " + selected.name()
            : candidates.isEmpty() ? "未匹配任何已启用规则" : "匹配规则的负责人当前不具备接收条件";
        return new RoutingPlan(lead.publicId(), selected == null ? null : selected.ruleId(),
            selected == null ? null : selected.ownerId(), reason, candidates);
    }
    /** Returns the best currently matching rule for a lead, ordered by specificity then priority. */
    private List<RuleRow> matchingRules(RouteLead lead) {
        if (lead.campusId() == null) { return List.of(); }
        return jdbc.query(selectRule() + " WHERE r.deleted=0 AND r.active=1 AND r.campus_id=? "
            + "AND (r.activity_id IS NULL OR r.activity_id=(SELECT id FROM core_activity WHERE public_id=? AND deleted=0)) "
            + "AND (r.subject IS NULL OR r.subject=?) AND (r.source IS NULL OR r.source=?) "
            + "ORDER BY r.priority,r.id LIMIT 100", (rs, row) -> rule(rs), lead.campusId(),
            lead.activityPublicId(), lead.subject(), lead.source());
    }
    /** Creates or refreshes the one pending routing row and appends its event. */
    QueueEntry unmatched(Actor actor, RouteLead lead, String reason) {
        List<Long> existing = jdbc.query("SELECT id FROM core_crm_routing_queue WHERE lead_id=? AND deleted=0 FOR UPDATE",
            (rs, row) -> rs.getLong(1), lead.id());
        long queueId;
        if (existing.isEmpty()) {
            jdbc.update("""
                INSERT INTO core_crm_routing_queue (public_id,lead_id,campus_id,created_by,status,reason_cipher)
                VALUES (?,?,?,?,'pending',?)
                """, "ROUTE_QUEUE_" + UUID.randomUUID(), lead.id(), lead.campusId(), actor.id(), cipher.encrypt(reason));
            queueId = jdbc.queryForObject("SELECT id FROM core_crm_routing_queue WHERE lead_id=?", Long.class, lead.id());
            event(queueId, actor.id(), "unmatched", null, reason);
        } else {
            queueId = existing.get(0);
            jdbc.update("""
                UPDATE core_crm_routing_queue SET status='pending',rule_id=NULL,reason_cipher=?,
                    version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND deleted=0
                """, cipher.encrypt(reason), queueId);
            event(queueId, actor.id(), "requeued", null, reason);
        }
        return queueEntry(queueId);
    }
    /** Completes a prior unmatched queue row after a rule successfully assigned its lead. */
    void completed(Actor actor, RouteLead lead, String ruleId, String reason) {
        List<Long> ids = jdbc.query("SELECT id FROM core_crm_routing_queue WHERE lead_id=? AND deleted=0 AND status='pending' FOR UPDATE",
            (rs, row) -> rs.getLong(1), lead.id());
        if (ids.isEmpty()) { return; }
        Long rule = ruleId == null ? null : internalRule(ruleId);
        jdbc.update("""
            UPDATE core_crm_routing_queue SET status='completed',rule_id=?,reason_cipher=?,
                version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?
            """, rule, cipher.encrypt(reason), ids.get(0));
        event(ids.get(0), actor.id(), "matched", rule, reason);
    }
    /** Pages unresolved routing rows under the actor's lead visibility. */
    public QueuePage queue(Actor actor, int page, int size) {
        ScopeSql.Clause scope = ScopeSql.leads(actor);
        String where = "q.deleted=0 AND q.status='pending' AND l.owner_id IS NULL AND (" + scope.sql() + ")";
        List<Object> args = new ArrayList<>(scope.args());
        Long total = jdbc.queryForObject("SELECT COUNT(q.id) FROM core_crm_routing_queue q "
            + "JOIN core_crm_lead l ON l.id=q.lead_id WHERE " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        List<Long> ids = jdbc.query("SELECT q.id FROM core_crm_routing_queue q "
            + "JOIN core_crm_lead l ON l.id=q.lead_id WHERE " + where
            + " ORDER BY q.created_at,q.id LIMIT ? OFFSET ?", (rs, row) -> rs.getLong(1), pageArgs.toArray());
        return new QueuePage(ids.stream().map(this::queueEntry).toList(), total == null ? 0 : total, page, size);
    }
    private RuleRow ruleRow(Actor actor, String publicId, boolean lock) {
        List<Object> args = new ArrayList<>(List.of(publicId));
        String scope = ruleScope(actor, args);
        List<RuleRow> rows = jdbc.query(selectRule() + " WHERE r.public_id=? AND r.deleted=0 AND (" + scope + ")"
            + (lock ? " FOR UPDATE" : ""), (rs, row) -> rule(rs), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "分配规则不存在或不在授权范围"); }
        return rows.get(0);
    }
    private RoutingRule rule(Actor actor, String publicId, boolean lock) { return dto(ruleRow(actor, publicId, lock)); }
    private RuleRow rule(ResultSet rs) throws SQLException {
        return new RuleRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("name"),
            rs.getLong("campus_id"), rs.getString("campus_public_id"), rs.getString("activity_public_id"),
            rs.getString("subject"), rs.getString("source"), rs.getString("owner_public_id"),
            rs.getInt("priority"), rs.getBoolean("active"), rs.getLong("version"));
    }
    private RoutingRule dto(RuleRow row) {
        return new RoutingRule(row.publicId(), row.name(), row.campusPublicId(), row.activityPublicId(),
            row.subject(), row.source(), row.ownerId(), row.priority(), row.active(), row.version());
    }
    private String ruleScope(Actor actor, List<Object> args) {
        if (actor.scope().equals("all")) { return "1=1"; }
        if (!actor.scope().equals("campus")) { throw new BusinessException(ErrorCode.FORBIDDEN, "仅校区管理范围可维护自动分配规则"); }
        args.add(actor.id());
        return "r.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)";
    }
    private long campus(Actor actor, String publicId) {
        List<Long> ids = jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0",
            (rs, row) -> rs.getLong(1), publicId);
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或已停用"); }
        long id = ids.get(0);
        if (actor.scope().equals("campus") && !actor.campusIds().contains(id)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权管理此校区规则");
        }
        if (!actor.scope().equals("all") && !actor.scope().equals("campus")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅全局或校区管理范围可维护规则");
        }
        return id;
    }
    private Long activity(Actor actor, String publicId, long campusId) {
        if (publicId == null || publicId.isBlank()) { return null; }
        ScopeSql.Clause scope = ScopeSql.activities(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args()); args.add(campusId);
        List<Long> ids = jdbc.query("SELECT a.id FROM core_activity a WHERE a.public_id=? AND a.deleted=0 AND ("
            + scope.sql() + ") AND a.campus_id=?", (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在、跨校区或不在授权范围"); }
        return ids.get(0);
    }
    private long eligibleOwner(String ownerPublicId, long campusId) {
        List<Long> ids = eligibleOwnerIds(ownerPublicId, campusId);
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.INVALID_INPUT, "负责人须启用跟进权限并属于规则校区"); }
        return ids.get(0);
    }
    private boolean ownerEligible(String ownerPublicId, long campusId) {
        return !eligibleOwnerIds(ownerPublicId, campusId).isEmpty();
    }
    private List<Long> eligibleOwnerIds(String ownerPublicId, long campusId) {
        String membership = "EXISTS(SELECT pc.id FROM core_principal_campus pc WHERE pc.principal_id=p.id "
            + "AND pc.deleted=0 AND pc.campus_id=?)";
        String legacy = CrmPrincipalEligibility.permission("lead.follow")
            + " AND (p.scope_type IN ('all','own') OR (p.scope_type='campus' AND " + membership + "))";
        String modern = CrmPrincipalEligibility.permission("crm.lead.follow") + " AND " + membership;
        return jdbc.query("SELECT p.id FROM core_principal p WHERE p.public_id=? AND p.active=1 AND p.deleted=0 "
            + "AND p.archived_at IS NULL AND p.credential_change_required=0 AND p.scope_type<>'assigned' "
            + "AND ((" + legacy + ") OR (" + modern + "))", (rs, row) -> rs.getLong(1),
            ownerPublicId, campusId, campusId);
    }
    private long internalRule(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_crm_routing_rule WHERE public_id=? AND deleted=0",
            Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "分配规则已不存在"); }
        return id;
    }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private String selectRule() {
        return """
            SELECT r.id,r.public_id,r.name,r.campus_id,c.public_id AS campus_public_id,
                   a.public_id AS activity_public_id,r.subject,r.source,p.public_id AS owner_public_id,
                   r.priority,r.active,r.version
            FROM core_crm_routing_rule r JOIN core_campus c ON c.id=r.campus_id
            LEFT JOIN core_activity a ON a.id=r.activity_id
            JOIN core_principal p ON p.id=r.owner_id
            """;
    }
    private QueueEntry queueEntry(long id) {
        return jdbc.queryForObject("""
            SELECT q.public_id,l.public_id AS lead_public_id,c.public_id AS campus_public_id,
                   q.reason_cipher,q.created_at,q.version
            FROM core_crm_routing_queue q JOIN core_crm_lead l ON l.id=q.lead_id
            LEFT JOIN core_campus c ON c.id=q.campus_id WHERE q.id=? AND q.deleted=0
            """, (rs, row) -> new QueueEntry(rs.getString("public_id"), rs.getString("lead_public_id"),
                rs.getString("campus_public_id"), cipher.decrypt(rs.getString("reason_cipher")),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version")), id);
    }
    private void event(long queueId, long actorId, String type, Long ruleId, String reason) {
        jdbc.update("""
            INSERT INTO core_crm_routing_event (public_id,queue_id,actor_id,event_type,rule_id,reason_cipher)
            VALUES (?,?,?,?,?,?)
            """, "ROUTE_EVT_" + UUID.randomUUID(), queueId, actorId, type, ruleId, cipher.encrypt(reason));
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
