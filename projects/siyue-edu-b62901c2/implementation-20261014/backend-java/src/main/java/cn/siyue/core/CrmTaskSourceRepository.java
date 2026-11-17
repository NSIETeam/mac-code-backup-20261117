package cn.siyue.core;

import cn.siyue.core.CrmDtos.TaskSourceRef;
import cn.siyue.core.CrmDtos.TaskTargetInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Validates immutable acquisition evidence without expanding customer profile permissions. */
final class CrmTaskSourceRepository {
    /** 从任务来源关系读取的内部行及活动、客户、孩子、学科、学期和版本状态。 */
    record Source(long id, String activityId, String customerId, String studentId,
            String subject, String term, Long version, String status) { }
    /** 按当前资源权限投影的任务来源集合、可披露活动标识及整体可见状态。 */
    record Summary(String activityId, List<TaskSourceRef> sources, String status) { }
    private final JdbcTemplate jdbc;
    CrmTaskSourceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static List<TaskSourceRef> normalized(List<TaskSourceRef> sources) {
        if (sources == null || sources.isEmpty()) { return List.of(); }
        if (sources.size() > 100) { invalid("每个需求最多关联 100 项来源"); }
        Set<String> keys = new HashSet<>();
        for (TaskSourceRef ref : sources) {
            validateReference(ref);
            if (!keys.add(ref.type() + ":" + ref.id())) { invalid("同一需求的来源不能重复"); }
        }
        return sources.stream().sorted(Comparator.comparing(TaskSourceRef::type).thenComparing(TaskSourceRef::id)).toList();
    }

    private static void validateReference(TaskSourceRef ref) {
        if (ref == null || ref.type() == null || !Set.of("lead", "touchpoint").contains(ref.type())
                || ref.id() == null || ref.id().isBlank() || ref.id().length() > 120) { invalid("来源编号无效"); }
        if ("lead".equals(ref.type()) && (ref.version() == null || ref.version() < 1)
                || "touchpoint".equals(ref.type()) && ref.version() != null) { invalid("来源版本无效"); }
    }

    /** Replay rechecks visibility and identity; mutable versions are checked only for a new write. */
    void validate(Actor actor, String activityId, List<TaskTargetInput> targets, boolean checkVersion) {
        for (TaskTargetInput target : targets) {
            List<TaskSourceRef> refs = normalized(target.sources());
            if (checkVersion && activityId != null && !activityId.isBlank() && refs.isEmpty()) {
                invalid("活动任务的每个需求必须关联真实活动来源");
            }
            if (!refs.isEmpty() && (activityId == null || activityId.isBlank())) {
                invalid("关联活动来源时必须指定活动");
            }
            for (TaskSourceRef ref : refs) {
                Source source = source(actor, ref, true);
                if (checkVersion && ("archived".equals(source.status()) || !java.util.Objects.equals(ref.version(), source.version()))) {
                    throw new BusinessException(ErrorCode.PREVIEW_STALE, "活动来源已变化，请刷新名单后重新预览");
                }
                validateBinding(activityId, target, source);
            }
        }
    }

    private void validateBinding(String activityId, TaskTargetInput target, Source source) {
        if (!java.util.Objects.equals(activityId, source.activityId()) || !target.customerId().equals(source.customerId())
                || source.studentId() != null && !source.studentId().equals(target.studentId())
                || !matches(source.subject(), target.subject()) || !matches(source.term(), target.term())) {
            invalid("来源与活动、客户、学生或购买需求不匹配");
        }
    }

    private static boolean matches(String known, String selected) {
        return known == null || known.isBlank() || known.trim().equalsIgnoreCase(selected);
    }

    Source source(Actor actor, TaskSourceRef ref, boolean lock) {
        boolean lead = "lead".equals(ref.type());
        CrmLegacyAccess.read(actor, "crm.activity.read"); CrmLegacyAccess.read(actor, "crm.customer.read");
        CrmLegacyAccess.read(actor, lead ? "crm.lead.read" : "crm.touchpoint.read");
        return selectSource(actor, ref, lock).orElseThrow(() ->
            new BusinessException(ErrorCode.NOT_FOUND, "活动来源不存在或不在授权范围"));
    }

    /**
     * Reads current source visibility without treating a missing grant or row as an exception.
     * @param actor current caller whose customer, activity and source scopes are intersected
     * @param ref original immutable source reference; its stored version is not replaced
     * @param lock whether the visible source must be locked for an assignment decision
     * @return the currently visible source, or empty when a required grant or scoped row is absent
     * @throws org.springframework.dao.DataAccessException if source evidence cannot be queried
     */
    Optional<Source> findVisible(Actor actor, TaskSourceRef ref, boolean lock) {
        String permission = "lead".equals(ref.type()) ? "crm.lead.read" : "crm.touchpoint.read";
        if (!CrmLegacyAccess.canRead(actor, "crm.activity.read")
                || !CrmLegacyAccess.canRead(actor, "crm.customer.read") || !CrmLegacyAccess.canRead(actor, permission)) {
            return Optional.empty();
        }
        return selectSource(actor, ref, lock);
    }

    private Optional<Source> selectSource(Actor actor, TaskSourceRef ref, boolean lock) {
        boolean lead = "lead".equals(ref.type());
        ScopeSql.Clause customer = ScopeSql.customers(actor);
        ScopeSql.Clause activity = ScopeSql.activities(actor);
        ScopeSql.Clause resource = lead ? ScopeSql.leadReads(actor) : new ScopeSql.Clause("1=1", List.of());
        List<Object> args = new ArrayList<>(List.of(ref.id())); args.addAll(customer.args()); args.addAll(resource.args()); args.addAll(activity.args());
        String alias = lead ? "l" : "tp";
        String sql = "SELECT " + alias + ".id,a.public_id AS activity_id,c.public_id AS customer_id,"
            + "s.public_id AS student_id," + (lead ? "l.subject,l.term,l.version,l.status" : "NULL AS subject,NULL AS term,NULL AS version,NULL AS status")
            + " FROM " + (lead ? "core_crm_lead l" : "core_customer_touchpoint tp")
            + " JOIN core_customer c ON c.id=" + alias + ".customer_id AND c.deleted=0"
            + " JOIN core_activity a ON a.id=" + alias + ".activity_id AND a.deleted=0"
            + " LEFT JOIN core_student s ON s.id=" + alias + ".student_id AND s.deleted=0"
            + " WHERE " + alias + ".public_id=? AND " + alias + ".deleted=0"
            + " AND (" + customer.sql() + ") AND (" + resource.sql() + ") AND (" + activity.sql() + ")"
            + " AND (" + alias + ".student_id IS NULL OR (s.id IS NOT NULL AND EXISTS (SELECT cs.id"
            + " FROM core_customer_student cs WHERE cs.customer_id=c.id AND cs.student_id=s.id AND cs.deleted=0)))"
            + (lock ? " FOR UPDATE" : "");
        List<Source> rows = jdbc.query(sql, (rs, row) -> new Source(rs.getLong("id"), rs.getString("activity_id"),
            rs.getString("customer_id"), rs.getString("student_id"), rs.getString("subject"), rs.getString("term"),
            rs.getObject("version", Long.class), rs.getString("status")), args.toArray());
        return rows.stream().findFirst();
    }

    void insert(long taskId, List<TaskSourceRef> refs) {
        for (TaskSourceRef ref : refs) {
            boolean lead = "lead".equals(ref.type());
            Long sourceId = jdbc.queryForObject("SELECT id FROM " + (lead ? "core_crm_lead" : "core_customer_touchpoint")
                + " WHERE public_id=? AND deleted=0", Long.class, ref.id());
            jdbc.update("""
                INSERT INTO core_crm_task_source (task_id,source_type,source_public_id,lead_id,touchpoint_id,source_version)
                VALUES (?,?,?,?,?,?)
                """, taskId, ref.type(), ref.id(), lead ? sourceId : null, lead ? null : sourceId, ref.version());
        }
    }

    Summary summary(Actor actor, long taskId) {
        List<TaskSourceRef> saved = jdbc.query("SELECT source_type,source_public_id,source_version "
            + "FROM core_crm_task_source WHERE task_id=? ORDER BY source_type,source_public_id",
            (rs, row) -> new TaskSourceRef(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class)), taskId);
        List<TaskSourceRef> visible = new ArrayList<>();
        for (TaskSourceRef ref : saved) {
            if (findVisible(actor, ref, false).isPresent()) { visible.add(ref); }
        }
        String activity = activityFor(actor, "core_crm_task", taskId);
        return new Summary(activity, List.copyOf(visible), saved.isEmpty() ? "unknown"
            : visible.size() == saved.size() ? "known" : "restricted");
    }

    String batchActivity(Actor actor, String batchId) {
        List<Long> ids = jdbc.query("SELECT id FROM core_crm_task_batch WHERE public_id=? AND deleted=0",
            (rs, row) -> rs.getLong(1), batchId);
        return ids.isEmpty() ? null : activityFor(actor, "core_crm_task_batch", ids.get(0));
    }

    private String activityFor(Actor actor, String table, long id) {
        if (!CrmLegacyAccess.canRead(actor, "crm.activity.read")) { return null; }
        ScopeSql.Clause scope = ScopeSql.activities(actor);
        List<Object> args = new ArrayList<>(List.of(id)); args.addAll(scope.args());
        List<String> ids = jdbc.query("SELECT a.public_id FROM " + table + " t JOIN core_activity a ON a.id=t.activity_id"
            + " WHERE t.id=? AND a.deleted=0 AND (" + scope.sql() + ")", (rs, row) -> rs.getString(1), args.toArray());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
