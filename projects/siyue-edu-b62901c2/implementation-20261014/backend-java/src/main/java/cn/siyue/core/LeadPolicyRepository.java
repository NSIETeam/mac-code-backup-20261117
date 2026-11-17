package cn.siyue.core;

import cn.siyue.core.LeadPolicyDtos.Candidate;
import cn.siyue.core.LeadPolicyDtos.Policy;
import cn.siyue.core.LeadPolicyDtos.PolicyInput;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists global lead SLA settings and calculates scoped due actions from assignment/followup facts. */
@Repository
public class LeadPolicyRepository {
    private final JdbcTemplate jdbc;
    public LeadPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CandidateQuery(Actor actor, Policy policy, Instant now, List<String> leadIds, int page, int size) { }

    public Policy get() { return policy(false); }
    public Policy lockPolicy() { return policy(true); }
    private Policy policy(boolean lock) {
        List<Policy> rows = jdbc.query("""
            SELECT p.reserve_days,p.reclaim_days,p.first_follow_hours,p.decision_hours,p.version,
                   p.updated_at,a.public_id AS updated_by
            FROM core_crm_lead_policy p LEFT JOIN core_principal a ON a.id=p.updated_by
            WHERE p.singleton=1 AND p.deleted=0
            """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Policy(rs.getInt("reserve_days"),
                rs.getInt("reclaim_days"), rs.getInt("first_follow_hours"), rs.getInt("decision_hours"),
                rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by")));
        if (rows.size() != 1) { throw new IllegalStateException("Global lead policy seed is missing"); }
        return rows.get(0);
    }

    public Policy update(Actor actor, PolicyInput input) {
        int changed = jdbc.update("""
            UPDATE core_crm_lead_policy SET reserve_days=?,reclaim_days=?,first_follow_hours=?,decision_hours=?,
                updated_by=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE singleton=1 AND deleted=0 AND version=?
            """, input.reserveDays(), input.reclaimDays(), input.firstFollowHours(),
            input.decisionHours(), actor.id(), input.expectedVersion());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索策略版本已变化，请刷新后重试"); }
        return get();
    }

    /** Captures the current decision window for newly created leads only. */
    public Instant newDecisionDeadline(Actor actor, Instant createdAt) {
        boolean decisionEligible = actor.permissions().contains("lead.follow")
            || actor.permissions().contains("crm.lead.follow");
        if (!decisionEligible) { return null; }
        Integer hours = jdbc.queryForObject("SELECT decision_hours FROM core_crm_lead_policy "
            + "WHERE singleton=1 AND deleted=0", Integer.class);
        if (hours == null || hours < 1 || hours > 720) {
            throw new IllegalStateException("Lead decision policy is invalid");
        }
        return createdAt.plus(hours, ChronoUnit.HOURS);
    }

    /** Returns a page of warning/recycle candidates; the count query uses the same scope and due predicate. */
    public Page<Candidate> candidates(CandidateQuery query) {
        Actor actor = query.actor();
        Policy policy = query.policy();
        Instant now = query.now();
        List<String> leadIds = query.leadIds();
        int page = query.page();
        int size = query.size();
        ScopeSql.Clause scope = ScopeSql.leads(actor);
        List<Object> computedArgs = computedArgs(policy, now, scope.args());
        String computed = computed(scope.sql());
        List<Object> countArgs = new ArrayList<>(computedArgs);
        countArgs.add(Timestamp.from(now));
        countArgs.add(policy.version());
        addLeadIds(countArgs, leadIds);
        String idFilter = idFilter(leadIds);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + computed + ") b WHERE "+ duePredicate() + idFilter,
            Long.class, countArgs.toArray());

        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.addAll(computedArgs);
        args.add(Timestamp.from(now));
        args.add(policy.version());
        addLeadIds(args, leadIds);
        args.add(size);
        args.add(Math.multiplyExact((long) page - 1, size));
        List<Candidate> items = jdbc.query("""
            SELECT b.lead_id,b.owner_id,b.campus_id,
                   CASE WHEN b.recycle_due_at<=? THEN 'recycle' ELSE 'warning' END AS kind,
                   b.assigned_at,b.last_follow_at,b.activity_at,
                   CASE WHEN b.recycle_due_at<=? THEN b.recycle_due_at ELSE b.warning_due_at END AS due_at,
                   b.version
            FROM (""" + computed + ") b WHERE " + duePredicate() + idFilter
                + " ORDER BY due_at,b.lead_id LIMIT ? OFFSET ?",
            (rs, row) -> new Candidate(rs.getString("lead_id"), rs.getString("owner_id"), rs.getString("campus_id"),
                rs.getString("kind"), rs.getTimestamp("assigned_at").toInstant(), instant(rs, "last_follow_at"),
                rs.getTimestamp("activity_at").toInstant(), rs.getTimestamp("due_at").toInstant(), rs.getLong("version")),
            args.toArray());
        return new Page<>(List.copyOf(items), total);
    }

    /** Locks one in-scope lead before a previewed policy action is re-evaluated and committed. */
    public void lockLead(Actor actor, String leadId) {
        ScopeSql.Clause scope = ScopeSql.leads(actor);
        List<Object> args = new ArrayList<>(List.of(leadId));
        args.addAll(scope.args());
        List<Long> rows = jdbc.query("""
            SELECT l.id FROM core_crm_lead l WHERE l.public_id=? AND l.deleted=0 AND ("""
            + scope.sql() + ") FOR UPDATE", (rs, row) -> rs.getLong(1), args.toArray());
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在或不在授权范围"); }
    }

    public void recordWarning(Actor actor, Candidate candidate, long policyVersion) {
        jdbc.update("""
            INSERT INTO core_crm_lead_policy_action
                (public_id,lead_id,actor_id,policy_version,action_type,activity_at,due_at)
            VALUES (?,(SELECT id FROM core_crm_lead WHERE public_id=?),?,?, 'warning',?,?)
            """, "LEADPOL_" + UUID.randomUUID(), candidate.leadId(), actor.id(), policyVersion,
            Timestamp.from(candidate.activityAt()), Timestamp.from(candidate.dueAt()));
    }

    public void recordRecycle(Actor actor, Candidate candidate, long policyVersion) {
        jdbc.update("""
            INSERT INTO core_crm_lead_policy_action
                (public_id,lead_id,actor_id,policy_version,action_type,activity_at,due_at)
            VALUES (?,(SELECT id FROM core_crm_lead WHERE public_id=?),?,?, 'recycled',?,?)
            """, "LEADPOL_" + UUID.randomUUID(), candidate.leadId(), actor.id(), policyVersion,
            Timestamp.from(candidate.activityAt()), Timestamp.from(candidate.dueAt()));
    }

    private List<Object> computedArgs(Policy policy, Instant now, List<Object> scopeArgs) {
        List<Object> args = new ArrayList<>();
        args.add(policy.reserveDays());
        args.add(policy.reclaimDays());
        args.add(policy.firstFollowHours());
        args.add(Timestamp.from(now));
        args.add(policy.firstFollowHours());
        args.add(Timestamp.from(now));
        args.addAll(scopeArgs);
        return args;
    }

    private String computed(String scope) {
        return """
            SELECT b.id,b.lead_id,b.owner_id,b.campus_id,b.assigned_at,b.last_follow_at,b.next_follow_at,b.version,COALESCE(b.last_follow_at,b.assigned_at) AS activity_at,
                   GREATEST(TIMESTAMPADD(DAY,?,b.assigned_at),
                       TIMESTAMPADD(DAY,?,COALESCE(b.last_follow_at,b.assigned_at))) AS recycle_due_at,
                   CASE WHEN b.last_follow_at IS NULL AND TIMESTAMPADD(HOUR,?,b.assigned_at)<=?
                        THEN TIMESTAMPADD(HOUR,?,b.assigned_at)
                        WHEN b.next_follow_at IS NOT NULL AND b.next_follow_at<? THEN b.next_follow_at
                        ELSE NULL END AS warning_due_at
            FROM (
                SELECT l.id,l.public_id AS lead_id,p.public_id AS owner_id,c.public_id AS campus_id,
                       COALESCE(ae.assigned_at,l.created_at) AS assigned_at,MAX(f.occurred_at) AS last_follow_at,
                       l.next_follow_at,l.version
                FROM core_crm_lead l
                JOIN core_principal p ON p.id=l.owner_id
                LEFT JOIN core_campus c ON c.id=l.campus_id
                LEFT JOIN (SELECT lead_id,MAX(occurred_at) AS assigned_at FROM core_crm_lead_event
                           WHERE deleted=0 AND event_type='assigned' GROUP BY lead_id) ae ON ae.lead_id=l.id
                LEFT JOIN core_crm_lead_followup f ON f.lead_id=l.id AND f.deleted=0
                    AND f.occurred_at>=COALESCE(ae.assigned_at,l.created_at)
                WHERE l.deleted=0 AND l.owner_id IS NOT NULL
                    AND l.status NOT IN ('archived','no_need','invalid_contact','unreachable')
                    AND NOT EXISTS (SELECT o.id FROM core_opportunity o
                        WHERE o.source_lead_id=l.id AND o.won_order_id IS NOT NULL AND o.deleted=0)
                    AND (""" + scope + ")"
                + " GROUP BY l.id,l.public_id,p.public_id,c.public_id,ae.assigned_at,l.created_at,"
                + "l.next_follow_at,l.version) b";
    }

    private String duePredicate() {
        return "(b.recycle_due_at<=? OR (b.warning_due_at IS NOT NULL AND NOT EXISTS ("
            + "SELECT pa.id FROM core_crm_lead_policy_action pa JOIN core_crm_lead l ON l.id=pa.lead_id "
            + "WHERE l.public_id=b.lead_id AND pa.deleted=0 AND pa.action_type='warning' "
            + "AND pa.policy_version=? AND pa.activity_at=b.activity_at)))";
    }
    private String idFilter(List<String> leadIds) {
        return leadIds == null || leadIds.isEmpty() ? "" : " AND b.lead_id IN ("
            + String.join(",", java.util.Collections.nCopies(leadIds.size(), "?")) + ")";
    }
    private void addLeadIds(List<Object> args, List<String> leadIds) {
        if (leadIds != null) { args.addAll(leadIds); }
    }
    private Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    public record Page<T>(List<T> items, long total) { }
}
