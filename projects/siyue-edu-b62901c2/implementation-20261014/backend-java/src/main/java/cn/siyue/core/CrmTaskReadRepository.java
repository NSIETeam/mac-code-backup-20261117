package cn.siyue.core;

import cn.siyue.core.CrmTaskReadDtos.Context;
import cn.siyue.core.CrmTaskReadDtos.Customer;
import cn.siyue.core.CrmTaskReadDtos.Student;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads only the household and child necessary for an already authorized task. */
@Repository
public class CrmTaskReadRepository {
    /** 任务上下文授权后的客户、孩子和活动显示资料；个人姓名仍以密文留在中间投影。 */
    private record ContextRow(long customerId, String customerPublicId, String nameCipher,
            String studentPublicId, String studentNameCipher, String grade, String activityId,
            String activityTitle) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public CrmTaskReadRepository(JdbcTemplate jdbc, PiiCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    /** Assignment is rechecked here as well; no general customer read or write scope is expanded. */
    public Context context(Actor actor, CrmDtos.CrmTask task) {
        var scope = taskScope(actor);
        var activityScope = CrmLegacyAccess.canRead(actor, "crm.activity.read")
            ? ScopeSql.activities(actor) : new ScopeSql.Clause("1=0", List.of());
        List<Object> args = new ArrayList<>(activityScope.args()); args.add(task.id()); args.add(task.version());
        args.addAll(scope.args());
        String sql = """
            SELECT c.id,c.public_id,c.display_name_cipher,st.public_id AS student_public_id,
              st.name_cipher AS student_name_cipher,st.grade,a.public_id AS activity_public_id,
              a.title AS activity_title FROM core_crm_task t
            JOIN core_crm_task_batch b ON b.id=t.batch_id AND b.deleted=0
            JOIN core_customer c ON c.id=t.customer_id AND c.deleted=0
            LEFT JOIN core_student st ON st.id=t.student_id AND st.deleted=0 AND EXISTS (
              SELECT link.id FROM core_customer_student link WHERE link.customer_id=c.id
                AND link.student_id=st.id AND link.deleted=0)
            LEFT JOIN core_activity a ON a.id=b.activity_id AND a.deleted=0 AND (
            """ + activityScope.sql() + ") WHERE t.public_id=? AND t.version=? AND t.deleted=0 AND ("
            + scope.sql() + ")";
        List<ContextRow> rows = jdbc.query(sql, (rs, n) -> new ContextRow(rs.getLong("id"),
            rs.getString("public_id"), rs.getString("display_name_cipher"), rs.getString("student_public_id"),
            rs.getString("student_name_cipher"), rs.getString("grade"), rs.getString("activity_public_id"),
            rs.getString("activity_title")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "任务信息已变化或不在授权范围"); }
        ContextRow row = rows.get(0);
        boolean open = List.of("pending", "in_progress").contains(task.status());
        Student student = row.studentPublicId() == null ? null : new Student(row.studentPublicId(),
            cipher.decrypt(row.studentNameCipher()), row.grade());
        return new Context(task, new Customer(row.customerPublicId(), cipher.decrypt(row.nameCipher())),
            student, open ? contacts(row.customerId()) : List.of(), row.activityId(), row.activityTitle(),
            open ? "masked_guardian_contacts" : "no_contacts_after_close");
    }

    private List<CrmDtos.CustomerContact> contacts(long customerId) {
        return jdbc.query("""
            SELECT public_id,person_role,channel,value_cipher,is_primary FROM core_customer_contact
            WHERE customer_id=? AND deleted=0 AND person_role='guardian'
            ORDER BY is_primary DESC,id LIMIT 20
            """, (rs, n) -> new CrmDtos.CustomerContact(rs.getString("public_id"), rs.getString("person_role"),
                rs.getString("channel"), cipher.maskedContact(rs.getString("channel"), rs.getString("value_cipher")),
                rs.getBoolean("is_primary")), customerId);
    }
    /** Matches this exact task demand within the actor's separately granted opportunity scope. */
    public CrmTaskReadDtos.OpportunityPage opportunities(Actor actor, String taskId, int page, int size) {
        var taskScope = taskScope(actor);
        var dealScope = opportunityScope(actor);
        List<Object> args = new ArrayList<>(List.of(taskId)); args.addAll(taskScope.args()); args.addAll(dealScope.args());
        String from = """
            FROM core_crm_task t JOIN core_opportunity o ON o.customer_id=t.customer_id
              AND o.student_id <=> t.student_id AND BINARY LOWER(o.subject) <=> BINARY LOWER(t.subject)
              AND BINARY LOWER(o.term) <=> BINARY LOWER(t.term)
              AND (t.need_department_id IS NULL OR o.need_version=2
                AND o.department_id=t.need_department_id AND o.project_id <=> t.need_project_id)
            LEFT JOIN core_opportunity_option dep ON dep.id=o.department_id
            LEFT JOIN core_opportunity_option prj ON prj.id=o.project_id
            WHERE t.public_id=? AND t.deleted=0 AND o.deleted=0 AND (
            """ + taskScope.sql() + ") AND (" + dealScope.sql() + ")";
        Long total = jdbc.queryForObject("SELECT COUNT(o.id) " + from, Long.class, args.toArray());
        args.add(size); args.add((long) (page - 1) * size);
        List<CrmTaskReadDtos.Opportunity> items = jdbc.query("SELECT o.public_id,o.title,o.stage,o.subject,o.term,o.version,dep.public_id AS department_public_id,prj.public_id AS project_public_id,t.need_department_id "
            + from + " ORDER BY o.id DESC LIMIT ? OFFSET ?", (rs, n) -> new CrmTaskReadDtos.Opportunity(
                rs.getString("public_id"), rs.getString("title"), rs.getString("stage"),
                rs.getString("subject"), rs.getString("term"),rs.getString("department_public_id")==null?null:
                    new OpportunityNeedDtos.Identity(rs.getString("department_public_id"),rs.getString("project_public_id")),
                rs.getObject("need_department_id",Long.class)==null&&rs.getString("department_public_id")!=null?"needs_identity_confirmation":"exact",rs.getLong("version")), args.toArray());
        return new CrmTaskReadDtos.OpportunityPage(items, total == null ? 0 : total, page, size);
    }
    private ScopeSql.Clause opportunityScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                + "WHERE pc.principal_id=? AND pc.deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("o.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    static ScopeSql.Clause taskScope(Actor actor) {
        ScopeSql.Clause legacy = switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("t.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                + "WHERE pc.principal_id=? AND pc.deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("t.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
        return TaskRoutingScope.execution(actor,legacy);
    }
}
