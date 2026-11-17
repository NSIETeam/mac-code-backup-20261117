package cn.siyue.core;

import java.util.ArrayList;
import java.util.List;

/** Builds only constant SQL fragments; every variable is passed as a bind parameter. */
final class ScopeSql {
    record Clause(String sql, List<Object> args) { }
    private ScopeSql() { }
    static Clause classes(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new Clause("1 = 1", List.of());
            case "campus" -> new Clause("""
                c.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc
                WHERE pc.principal_id = ? AND pc.deleted = 0)
                """, List.of(actor.id()));
            case "assigned" -> new Clause("""
                (c.teacher_id = ? OR EXISTS (SELECT ss.id FROM core_session ss
                WHERE ss.class_id = c.id AND ss.teacher_id = ? AND ss.deleted = 0))
                """, List.of(actor.id(), actor.id()));
            case "own" -> new Clause("1 = 0", List.of());
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    /** Profile mutation scope. Assigned sales work must never grant household editing rights. */
    static Clause customers(Actor actor) {
        if(actor.permissions().contains("lead.follow"))return LegacyCrmScope.customer(actor,false);
        return switch (actor.scope()) {
            case "all" -> new Clause("1 = 1", List.of());
            case "campus" -> new Clause("""
                c.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc
                WHERE pc.principal_id = ? AND pc.deleted = 0)
                """, List.of(actor.id()));
            case "assigned", "own" -> new Clause("c.owner_id = ?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    /** Reads a household through current sales assignments without changing its owning employee. */
    static Clause customerReads(Actor actor) {
        if(CrmLegacyAccess.legacyRead(actor,"crm.customer.read"))return LegacyCrmScope.customer(actor,true);
        Clause owned = customers(actor);
        if (!actor.scope().equals("own") && !actor.scope().equals("assigned")) { return owned; }
        List<Object> args = new ArrayList<>(owned.args());
        String sql = owned.sql();
        if (CrmLegacyAccess.canRead(actor, "crm.lead.read")) {
            sql += " OR EXISTS (SELECT assigned_lead.id FROM core_crm_lead assigned_lead "
                + "WHERE assigned_lead.customer_id=c.id AND assigned_lead.owner_id=? "
                + "AND assigned_lead.deleted=0 AND assigned_lead.status<>'archived')";
            args.add(actor.id());
        }
        if (CrmLegacyAccess.canRead(actor, "crm.opportunity.read")) {
            sql += " OR EXISTS (SELECT assigned_deal.id FROM core_opportunity assigned_deal "
                + "WHERE assigned_deal.customer_id=c.id AND assigned_deal.owner_id=? AND assigned_deal.deleted=0)";
            args.add(actor.id());
        }
        return new Clause("(" + sql + ")", List.copyOf(args));
    }
    static Clause orders(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new Clause("1=1", List.of());
            case "campus" -> new Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus "
                + "WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new Clause("(o.attributed_owner_id=? OR EXISTS "
                + "(SELECT a.id FROM core_crm_attribution_adjustment a WHERE a.order_id=o.id AND a.deleted=0 "
                + "AND (a.from_owner_id=? OR a.to_owner_id=?)))", List.of(actor.id(),actor.id(),actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    /** Opportunity readers may follow original creator/customer relations; writes still use owner scope. */
    static Clause opportunityReads(Actor actor) {
        if(actor.scope().equals("all"))return new Clause("1=1",List.of());
        if(actor.scope().equals("campus"))return new Clause("o.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)",List.of(actor.id()));
        if(!List.of("own","assigned").contains(actor.scope()))throw new BusinessException(ErrorCode.FORBIDDEN,"账号数据范围无效");
        if(!CrmLegacyAccess.legacyRead(actor,"crm.opportunity.read"))return new Clause("o.owner_id=?",List.of(actor.id()));
        Clause customer=LegacyCrmScope.customer(actor,true);List<Object> args=new ArrayList<>(List.of(actor.id(),actor.id()));args.addAll(customer.args());
        return new Clause("(o.owner_id=? OR o.source_created_by_id=? OR EXISTS(SELECT c.id FROM core_customer c WHERE c.id=o.customer_id AND c.deleted=0 AND "+customer.sql()+"))",args);
    }
    static Clause activities(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new Clause("1 = 1", List.of());
            case "campus" -> new Clause("""
                a.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc
                WHERE pc.principal_id = ? AND pc.deleted = 0)
                """, List.of(actor.id()));
            case "assigned", "own" -> new Clause("""
                (a.owner_id = ? OR EXISTS (SELECT am.id FROM core_activity_member am
                WHERE am.activity_id = a.id AND am.principal_id = ? AND am.deleted = 0))
                """, List.of(actor.id(), actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    /** Original campaign writes require ownership/creation; campus managers may manage their own campuses. */
    static Clause campaignWrites(Actor actor) {
        if (actor.scope().equals("all")) { return new Clause("1=1", List.of()); }
        String owner="(a.owner_id=? OR a.source_created_by_id=?)";
        if (actor.scope().equals("own") || actor.scope().equals("assigned")) {
            return new Clause(owner,List.of(actor.id(),actor.id()));
        }
        Clause campus=activities(actor);
        if (actor.permissions().contains("lead.assign")) { return campus; }
        List<Object> args=new ArrayList<>(campus.args());args.add(actor.id());args.add(actor.id());
        return new Clause("("+campus.sql()+") AND "+owner,List.copyOf(args));
    }
    static Clause forms(Actor actor) {
        return switch(actor.scope()) {
            case "all" -> new Clause("1=1",List.of());
            case "campus" -> new Clause("f.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)",List.of(actor.id()));
            case "assigned","own" -> new Clause("f.owner_id=?",List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN,"账号数据范围无效");
        };
    }
    /** Original read relations never change the existing default mutation predicate. */
    static Clause leadReads(Actor actor) {
        return CrmLegacyAccess.legacyRead(actor,"crm.lead.read")?LegacyCrmScope.lead(actor,true):leads(actor);
    }
    /** Explicit legacy handlers use the original ownership/decision write predicate. */
    static Clause leadWrites(Actor actor) { return LegacyCrmScope.lead(actor,false); }
    static Clause leads(Actor actor) {
        String owner = "l.owner_id = ?";
        return switch (actor.scope()) {
            case "all" -> new Clause("1 = 1", List.of());
            case "campus" -> actor.permissions().contains("lead.read")
                || actor.permissions().contains("crm.lead.assign")
                || actor.permissions().contains("lead.assign")
                ? new Clause("(l.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                    + "WHERE pc.principal_id = ? AND pc.deleted = 0) OR (l.owner_id IS NULL AND l.campus_id IN "
                    + "(SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id = ? AND pc.deleted = 0)))",
                    List.of(actor.id(), actor.id()))
                : new Clause("(l.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                    + "WHERE pc.principal_id = ? AND pc.deleted = 0) AND " + owner + ")",
                    List.of(actor.id(), actor.id()));
            case "assigned", "own" -> actor.permissions().contains("crm.lead.assign")
                || actor.permissions().contains("lead.assign")
                ? new Clause("(l.owner_id = ? OR (l.owner_id IS NULL AND l.campus_id IN "
                    + "(SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id = ? AND pc.deleted = 0)))",
                    List.of(actor.id(), actor.id()))
                : new Clause(owner, List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    static Clause students(Actor actor, String classId) {
        Clause classes = actor.scope().equals("own")
            ? new Clause("s.owner_id = ?", List.of(actor.id())) : classes(actor);
        List<Object> args = new ArrayList<>(classes.args());
        String classFilter = "";
        if (classId != null && !classId.isBlank()) {
            classFilter = " AND c.public_id = ?";
            args.add(classId);
        }
        return new Clause("""
            s.deleted = 0 AND EXISTS (SELECT e.id FROM core_effective_enrollment e
            JOIN core_class c ON c.id = e.class_id AND c.deleted = 0 AND c.active = 1
            WHERE e.student_id = s.id AND e.status = 'active' AND e.deleted = 0 AND (
            """ + classes.sql() + ")" + classFilter + ")", List.copyOf(args));
    }
    /** Enforces the legacy write scope for student profiles, including explicit campus ownership. */
    static Clause studentWrite(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new Clause("1=1", List.of());
            case "campus" -> new Clause("(s.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                + "WHERE pc.principal_id=? AND pc.deleted=0) OR EXISTS (SELECT e.id FROM core_effective_enrollment e "
                + "JOIN core_class c ON c.id=e.class_id AND c.deleted=0 WHERE e.student_id=s.id "
                + "AND e.status IN ('active','pending') AND e.deleted=0 AND c.campus_id IN "
                + "(SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)))",
                List.of(actor.id(), actor.id()));
            case "own" -> new Clause("s.owner_id=?", List.of(actor.id()));
            case "assigned" -> new Clause("EXISTS (SELECT e.id FROM core_effective_enrollment e "
                + "JOIN core_class c ON c.id=e.class_id AND c.deleted=0 WHERE e.student_id=s.id "
                + "AND e.status IN ('active','pending') AND e.deleted=0 AND " + classes(actor).sql() + ")",
                classes(actor).args());
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    /** Read scope follows the same stable customer, lead and teaching links as Node CRM reads. */
    static Clause studentReads(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new Clause("1=1", List.of());
            case "campus" -> new Clause("(s.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                + "WHERE pc.principal_id=? AND pc.deleted=0) OR EXISTS (SELECT e.id FROM core_effective_enrollment e "
                + "JOIN core_class c ON c.id=e.class_id AND c.deleted=0 WHERE e.student_id=s.id "
                + "AND e.status IN ('active','pending') AND e.deleted=0 AND c.campus_id IN "
                + "(SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)))",
                List.of(actor.id(), actor.id()));
            case "own" -> new Clause("(s.owner_id=? OR EXISTS (SELECT l.id FROM core_crm_lead l "
                + "WHERE l.student_id=s.id AND l.owner_id=? AND l.deleted=0) OR "
                + "EXISTS (SELECT cs.id FROM core_customer_student cs JOIN core_opportunity o "
                + "ON o.customer_id=cs.customer_id WHERE cs.student_id=s.id AND cs.deleted=0 "
                + "AND o.owner_id=? AND o.deleted=0) OR EXISTS (SELECT direct_o.id FROM core_opportunity direct_o "
                + "WHERE direct_o.customer_id=s.customer_id AND direct_o.owner_id=? AND direct_o.deleted=0) "
                + "OR EXISTS (SELECT t.id FROM core_crm_task t "
                + "JOIN core_customer_student cs ON cs.customer_id=t.customer_id "
                + "WHERE cs.student_id=s.id AND cs.deleted=0 AND t.owner_id=? "
                + "AND t.deleted=0 AND t.status IN ('pending','in_progress')) OR EXISTS (SELECT direct_t.id "
                + "FROM core_crm_task direct_t WHERE direct_t.customer_id=s.customer_id "
                + "AND direct_t.owner_id=? AND direct_t.deleted=0 "
                + "AND direct_t.status IN ('pending','in_progress')))",
                List.of(actor.id(), actor.id(), actor.id(), actor.id(), actor.id(), actor.id()));
            case "assigned" -> new Clause("(EXISTS (SELECT e.id FROM core_effective_enrollment e "
                + "JOIN core_class c ON c.id=e.class_id AND c.deleted=0 WHERE e.student_id=s.id "
                + "AND e.status IN ('active','pending') AND e.deleted=0 AND " + classes(actor).sql() + ") "
                + "OR EXISTS (SELECT a.id FROM core_session_adjustment a JOIN core_session ss "
                + "ON ss.id=a.target_session_id JOIN core_class c ON c.id=ss.class_id "
                + "WHERE a.student_id=s.id AND a.status IN ('active','completed') AND a.deleted=0 "
                + "AND " + classes(actor).sql() + ") OR EXISTS (SELECT cs.id FROM core_customer_student cs "
                + "JOIN core_opportunity o ON o.customer_id=cs.customer_id WHERE cs.student_id=s.id "
                + "AND cs.deleted=0 AND o.owner_id=? AND o.deleted=0) OR EXISTS (SELECT direct_o.id "
                + "FROM core_opportunity direct_o WHERE direct_o.customer_id=s.customer_id "
                + "AND direct_o.owner_id=? AND direct_o.deleted=0) OR s.owner_id=? OR EXISTS"
                + " (SELECT t.id FROM core_crm_task t JOIN core_customer_student cs ON cs.customer_id=t.customer_id"
                + " WHERE cs.student_id=s.id AND cs.deleted=0 AND t.owner_id=?"
                + " AND t.deleted=0 AND t.status IN ('pending','in_progress')) OR EXISTS (SELECT direct_t.id "
                + "FROM core_crm_task direct_t WHERE direct_t.customer_id=s.customer_id "
                + "AND direct_t.owner_id=? AND direct_t.deleted=0 "
                + "AND direct_t.status IN ('pending','in_progress')))",
                List.of(actor.id(), actor.id(), actor.id(), actor.id(), actor.id(), actor.id(), actor.id(), actor.id(), actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
}
