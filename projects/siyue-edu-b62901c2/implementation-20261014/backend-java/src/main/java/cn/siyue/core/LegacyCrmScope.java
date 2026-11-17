package cn.siyue.core;

import java.util.ArrayList;
import java.util.List;

/** Original CRM stable-ID read relationships are separate from ownership-based mutations.
 * Registration, collection and teaching links are read-only; every SQL value remains bound.
 */
final class LegacyCrmScope {
    private LegacyCrmScope() { }
    /** Builds original lead visibility with alias l; assigned teachers never acquire the public pool. */
    static ScopeSql.Clause lead(Actor actor,boolean read) {
        if(actor.scope().equals("all"))return new ScopeSql.Clause("1=1",List.of());
        if(actor.scope().equals("campus"))return new ScopeSql.Clause(
            "l.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)",List.of(actor.id()));
        if(!List.of("own","assigned").contains(actor.scope()))throw invalid();
        if(!read)return new ScopeSql.Clause("(l.owner_id=? OR (l.decision_status='pending' AND l.decision_by_id=?))",List.of(actor.id(),actor.id()));
        String pool=actor.scope().equals("own")&&actor.permissions().contains("lead.follow")
            ?" OR (l.owner_id IS NULL AND l.decision_status='timed_out')":"";
        return new ScopeSql.Clause("(l.owner_id=? OR l.decision_by_id=? OR l.source_created_by_id=? OR l.registrar_id=?"
            +" OR EXISTS(SELECT lc.id FROM core_crm_lead_collector lc WHERE lc.lead_id=l.id AND lc.principal_id=? AND lc.deleted=0)"+pool+")",
            List.of(actor.id(),actor.id(),actor.id(),actor.id(),actor.id()));
    }
    /** Builds original customer scope with alias c, preserving read-only task/student relationships. */
    static ScopeSql.Clause customer(Actor actor,boolean read) {
        if(actor.scope().equals("all"))return new ScopeSql.Clause("1=1",List.of());
        if(actor.scope().equals("campus"))return new ScopeSql.Clause(
            "c.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)",List.of(actor.id()));
        if(!List.of("own","assigned").contains(actor.scope()))throw invalid();
        List<Object> args=new ArrayList<>(List.of(actor.id(),actor.id()));
        String sql="c.owner_id=? OR "+(read?"c.source_created_by_id=?":"(c.owner_id IS NULL AND c.source_created_by_id=?)");
        var lead=lead(actor,read);sql+=" OR EXISTS(SELECT l.id FROM core_crm_lead l WHERE l.customer_id=c.id AND l.deleted=0 AND "+lead.sql()+")";
        args.addAll(lead.args());
        sql+=" OR EXISTS(SELECT op.id FROM core_opportunity op WHERE op.customer_id=c.id AND op.owner_id=? AND op.deleted=0"+(read?"":" AND op.stage<>'closed'")+")";
        args.add(actor.id());
        if(read) {
            sql+=" OR EXISTS(SELECT t.id FROM core_crm_task t WHERE t.customer_id=c.id AND t.owner_id=? AND t.status IN ('pending','in_progress') AND t.deleted=0)";
            args.add(actor.id());var student=ScopeSql.studentReads(actor);
            sql+=" OR EXISTS(SELECT s.id FROM core_student s WHERE s.deleted=0 AND (s.customer_id=c.id OR EXISTS("
                +"SELECT cs.id FROM core_customer_student cs WHERE cs.customer_id=c.id AND cs.student_id=s.id AND cs.deleted=0)) AND "+student.sql()+")";
            args.addAll(student.args());
        }
        return new ScopeSql.Clause("("+sql+")",List.copyOf(args));
    }
    /** Separates creator/customer read relations from legacy ticket treatment and transfer scope. */
    static ScopeSql.Clause ticket(Actor actor,boolean read) {
        if(actor.scope().equals("all"))return new ScopeSql.Clause("1=1",List.of());
        boolean manager=actor.permissions().contains("lead.assign");
        if(actor.scope().equals("campus")) {
            List<Object> args=new ArrayList<>(List.of(actor.id()));
            String sql="t.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)";
            if(!read&&!manager) {sql+=" AND t.owner_id=?";args.add(actor.id());}
            return new ScopeSql.Clause(sql,args);
        }
        if(!List.of("own","assigned").contains(actor.scope()))throw invalid();
        if(!read)return manager?new ScopeSql.Clause("(t.owner_id=? OR t.created_by=?)",List.of(actor.id(),actor.id()))
            :new ScopeSql.Clause("t.owner_id=?",List.of(actor.id()));
        var customer=customer(actor,true);List<Object> args=new ArrayList<>(List.of(actor.id(),actor.id()));args.addAll(customer.args());
        return new ScopeSql.Clause("(t.owner_id=? OR t.created_by=? OR EXISTS(SELECT c.id FROM core_customer c WHERE c.id=t.customer_id AND c.deleted=0 AND "+customer.sql()+"))",args);
    }
    /** Additional legacy treatment capability; callers still apply the ticket's scoped row predicate. */
    static boolean mayTreatTicket(Actor actor,String creator) {
        return actor.permissions().contains("lead.follow")&&(actor.scope().equals("all")
            ||actor.permissions().contains("lead.assign")&&(actor.scope().equals("campus")||actor.publicId().equals(creator)));
    }
    private static BusinessException invalid() {return new BusinessException(ErrorCode.FORBIDDEN,"账号数据范围无效");}
}
