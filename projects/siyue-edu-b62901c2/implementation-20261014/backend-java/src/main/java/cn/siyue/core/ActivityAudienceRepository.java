package cn.siyue.core;

import cn.siyue.core.ActivityAudienceDtos.Item;
import cn.siyue.core.ActivityAudienceDtos.Page;
import cn.siyue.core.ActivityAudienceDtos.Query;
import cn.siyue.core.CrmDtos.TaskSourceRef;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Pages direct leads and touchpoints using one shared authorized source relation. */
@Repository
public class ActivityAudienceRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public ActivityAudienceRepository(JdbcTemplate jdbc, PiiCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    Page list(Actor actor, String activityId, Query query) {
        long activity = authorizeActivity(actor, activityId);
        List<Object> args = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        if (allows(actor, query, "lead")) { branches.add(branch(actor, activity, "lead", args)); }
        if (allows(actor, query, "touchpoint")) { branches.add(branch(actor, activity, "touchpoint", args)); }
        if (branches.isEmpty()) { return new Page(List.of(), 0, query.page(), query.size(), "source", activityId); }
        String relation = " FROM (" + String.join(" UNION ALL ", branches) + ") audience WHERE 1=1";
        relation += filters(query, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + relation, Long.class, args.toArray());
        args.add(query.size()); args.add(Math.multiplyExact((long) query.page() - 1, query.size()));
        List<Item> items = jdbc.query("SELECT source_type,source_id,source_version,customer_id,customer_name,student_id,student_name,subject,term,owner_id,occurred_at,has_task_evidence" + relation + " ORDER BY occurred_at DESC,source_type,source_id LIMIT ? OFFSET ?",
            (rs, row) -> new Item(rs.getString("source_type") + ":" + rs.getString("source_id"),
                new TaskSourceRef(rs.getString("source_type"), rs.getString("source_id"), rs.getObject("source_version", Long.class)),
                rs.getString("customer_id"), cipher.decrypt(rs.getString("customer_name")), rs.getString("student_id"),
                rs.getString("student_name") == null ? null : cipher.decrypt(rs.getString("student_name")),
                rs.getString("subject"), rs.getString("term"), rs.getString("owner_id"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getBoolean("has_task_evidence")), args.toArray());
        return new Page(items, total == null ? 0 : total, query.page(), query.size(), "source", activityId);
    }

    long authorizeActivity(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.activities(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT a.id FROM core_activity a WHERE a.public_id=? AND a.deleted=0 AND ("
            + scope.sql() + ")", (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或不在授权范围"); }
        return ids.get(0);
    }

    private boolean allows(Actor actor, Query query, String type) {
        return (query.sourceType() == null || query.sourceType().equals(type))
            && CrmLegacyAccess.canRead(actor, "lead".equals(type) ? "crm.lead.read" : "crm.touchpoint.read");
    }

    private String branch(Actor actor, long activity, String type, List<Object> args) {
        boolean lead = "lead".equals(type);
        String alias = lead ? "l" : "tp";
        ScopeSql.Clause customer = ScopeSql.customers(actor);
        ScopeSql.Clause source = lead ? ScopeSql.leadReads(actor) : new ScopeSql.Clause("1=1", List.of());
        ScopeSql.Clause tasks = taskScope(actor);
        args.addAll(tasks.args()); args.add(activity); args.addAll(customer.args()); args.addAll(source.args());
        return "SELECT '" + type + "' AS source_type," + alias + ".public_id AS source_id,"
            + (lead ? "l.version" : "NULL") + " AS source_version,c.public_id AS customer_id,"
            + "c.display_name_cipher AS customer_name,s.public_id AS student_id,s.name_cipher AS student_name,"
            + (lead ? "l.subject,l.term,p.public_id" : "NULL AS subject,NULL AS term,NULL") + " AS owner_id,"
            + alias + (lead ? ".created_at" : ".occurred_at") + " AS occurred_at,"
            + "EXISTS (SELECT ts.id FROM core_crm_task_source ts JOIN core_crm_task t ON t.id=ts.task_id"
            + " WHERE ts." + (lead ? "lead_id=l.id" : "touchpoint_id=tp.id")
            + " AND t.deleted=0 AND t.activity_id=" + alias + ".activity_id AND (" + tasks.sql() + ")) AS has_task_evidence"
            + " FROM " + (lead ? "core_crm_lead l" : "core_customer_touchpoint tp")
            + " JOIN core_customer c ON c.id=" + alias + ".customer_id AND c.deleted=0"
            + " LEFT JOIN core_student s ON s.id=" + alias + ".student_id AND s.deleted=0"
            + (lead ? " LEFT JOIN core_principal p ON p.id=l.owner_id AND p.deleted=0" : "")
            + " WHERE " + alias + ".activity_id=? AND " + alias + ".deleted=0"
            + (lead ? " AND l.status<>'archived'" : "") + " AND (" + customer.sql() + ") AND (" + source.sql() + ")"
            + " AND (" + alias + ".student_id IS NULL OR (s.id IS NOT NULL AND EXISTS (SELECT cs.id"
            + " FROM core_customer_student cs WHERE cs.customer_id=c.id AND cs.student_id=s.id AND cs.deleted=0)))";
    }

    private ScopeSql.Clause taskScope(Actor actor) {
        if (!CrmLegacyAccess.canRead(actor, "crm.task.read")) { return new ScopeSql.Clause("1=0", List.of()); }
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("t.campus_id IN (SELECT campus_id FROM core_principal_campus "
                + "WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            default -> new ScopeSql.Clause("t.owner_id=?", List.of(actor.id()));
        };
    }

    private String filters(Query query, List<Object> args) {
        String result = "";
        if (query.customerId() != null) { result += " AND customer_id=?"; args.add(query.customerId()); }
        if (query.fromAt() != null) { result += " AND occurred_at>=?"; args.add(Timestamp.from(query.fromAt())); }
        if (query.untilAt() != null) { result += " AND occurred_at<?"; args.add(Timestamp.from(query.untilAt())); }
        if ("with_evidence".equals(query.taskState())) { result += " AND has_task_evidence=1"; }
        if ("without_evidence".equals(query.taskState())) { result += " AND has_task_evidence=0"; }
        return result;
    }
}
