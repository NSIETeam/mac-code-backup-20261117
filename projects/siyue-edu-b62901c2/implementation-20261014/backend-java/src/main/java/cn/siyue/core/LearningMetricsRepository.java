package cn.siyue.core;

import cn.siyue.core.LearningMetricsDtos.Summary;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Computes the legacy learning report from authorized relational rows and effective ledger state. */
@Repository
public class LearningMetricsRepository {
    private final JdbcTemplate jdbc;

    /** 装配学习报表的关系数据及有效权益查询。
     * @param jdbc 当前业务数据库参数化访问器
     */
    public LearningMetricsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 聚合授权学员、有效报名和净课消；历史学员必须有曾激活报名且当前无有效报名。
     * @param actor 已由服务核验报表权限的员工，按all/campus/assigned/own应用不同范围
     * @return 范围标签、学员和报名计数及净单位大于零的去重出勤课消数
     * @throws BusinessException 员工数据范围模式无效时
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public Summary summary(Actor actor) {
        long students = count("SELECT COUNT(DISTINCT s.id) FROM core_student s WHERE s.deleted=0 AND ("
            + studentScope(actor).sql() + ")", studentScope(actor).args());
        long activeEnrollments = count(enrollmentCountSql(actor, "e.status='active'"), enrollmentScope(actor).args());
        long activeStudents = count("SELECT COUNT(DISTINCT e.student_id)" + enrollmentFrom(actor)
            + " AND e.status='active'", enrollmentScope(actor).args());
        List<Object> historicalArgs = new ArrayList<>(enrollmentScope(actor).args());
        historicalArgs.addAll(enrollmentScope(actor, "active_c", "active_o", "active_s").args());
        long historicalStudents = count("SELECT COUNT(DISTINCT e.student_id)" + enrollmentFrom(actor)
            + " AND e.status IN ('withdrawn','completed') AND e.activated_at IS NOT NULL"
            + " AND NOT EXISTS (SELECT active_e.id" + enrollmentFrom(actor, "active_e", "active_c", "active_s", "active_o")
            + " AND active_e.student_id=e.student_id AND active_e.status='active')",
            List.copyOf(historicalArgs));
        long consumptions = count(consumptionCountSql(actor), consumptionScope(actor).args());
        return new Summary(scopeLabel(actor), students, activeStudents, historicalStudents,
            activeEnrollments, consumptions);
    }

    private String enrollmentCountSql(Actor actor, String condition) {
        return "SELECT COUNT(e.id)" + enrollmentFrom(actor) + " AND " + condition;
    }

    private String enrollmentFrom(Actor actor) {
        return enrollmentFrom(actor, "e", "c", "s", "o");
    }

    private String enrollmentFrom(Actor actor, String enrollment, String klass, String student, String order) {
        // The alias and joins are constant; actor-specific authorization stays in a bound predicate.
        return " FROM core_effective_enrollment " + enrollment + " JOIN core_class " + klass + " ON "
            + klass + ".id=" + enrollment + ".class_id AND " + klass + ".deleted=0"
            + " JOIN core_student " + student + " ON " + student + ".id=" + enrollment + ".student_id AND " + student + ".deleted=0"
            + " LEFT JOIN core_crm_order " + order + " ON " + order + ".id=" + enrollment + ".order_id AND " + order + ".deleted=0"
            + " WHERE " + enrollment + ".deleted=0 AND (" + enrollmentScope(actor, klass, order, student).sql() + ")";
    }

    private String consumptionCountSql(Actor actor) {
        return "SELECT COUNT(*) FROM (SELECT x.attendance_id FROM core_consumption x"
            + " JOIN core_session ss ON ss.id=x.session_id AND ss.deleted=0"
            + " JOIN core_class c ON c.id=ss.class_id AND c.deleted=0"
            + " JOIN core_student s ON s.id=x.student_id AND s.deleted=0"
            + " LEFT JOIN core_crm_order o ON o.id=x.order_id AND o.deleted=0"
            + " WHERE x.deleted=0 AND (" + consumptionScope(actor).sql() + ")"
            + " GROUP BY x.attendance_id HAVING SUM(x.units)>0) confirmed";
    }

    private ScopeSql.Clause studentScope(Actor actor) {
        return ScopeSql.studentReads(actor);
    }

    private ScopeSql.Clause enrollmentScope(Actor actor) { return enrollmentScope(actor, "c", "o", "s"); }

    private ScopeSql.Clause enrollmentScope(Actor actor, String klass, String order, String student) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("(" + klass + ".campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc"
                + " WHERE pc.principal_id=? AND pc.deleted=0) OR " + order + ".campus_id IN"
                + " (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0))",
                List.of(actor.id(), actor.id()));
            case "assigned" -> ScopeSql.classes(actor);
            case "own" -> new ScopeSql.Clause("((" + order + ".opportunity_id IS NOT NULL AND ("
                + order + ".attributed_owner_id=? OR EXISTS (SELECT aa.id FROM core_crm_attribution_adjustment aa"
                + " WHERE aa.order_id=" + order + ".id AND aa.deleted=0 AND (aa.from_owner_id=? OR aa.to_owner_id=?))))"
                + " OR (" + order + ".opportunity_id IS NULL AND (" + student + ".owner_id=? OR EXISTS"
                + " (SELECT l.id FROM core_crm_lead l WHERE l.student_id=" + student + ".id AND l.owner_id=? AND l.deleted=0))))",
                List.of(actor.id(), actor.id(), actor.id(), actor.id(), actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }

    private ScopeSql.Clause consumptionScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("COALESCE(ss.campus_id,c.campus_id,o.campus_id) IN"
                + " (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)",
                List.of(actor.id()));
            case "assigned" -> new ScopeSql.Clause("COALESCE((SELECT te.to_teacher_id FROM core_session_teacher_event te "
                + "WHERE te.session_id=ss.id AND te.deleted=0 AND te.occurred_at<=x.created_at "
                + "ORDER BY te.occurred_at DESC,te.id DESC LIMIT 1),(SELECT te.from_teacher_id "
                + "FROM core_session_teacher_event te WHERE te.session_id=ss.id AND te.deleted=0 "
                + "ORDER BY te.occurred_at,te.id LIMIT 1),ss.teacher_id,c.teacher_id)=?",
                List.of(actor.id()));
            case "own" -> new ScopeSql.Clause("(x.actor_id=? OR (" + studentScope(actor).sql() + "))",
                prepend(actor.id(), studentScope(actor).args()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }

    private List<Object> prepend(Object first, List<Object> rest) {
        List<Object> values = new ArrayList<>(); values.add(first); values.addAll(rest); return List.copyOf(values);
    }

    private long count(String sql, List<Object> args) {
        Long result = jdbc.queryForObject(sql, Long.class, args.toArray());
        return result == null ? 0 : result;
    }

    private String scopeLabel(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> "全部授权数据";
            case "campus" -> "授权校区";
            case "assigned" -> "本人授课范围";
            default -> "本人负责范围";
        };
    }
}
