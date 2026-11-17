package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.Input;
import cn.siyue.core.OpportunityNeedReadRepository.CustomerRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Resolves provenance without converting a sales relationship into academic access. */
@Repository
public class OpportunityNeedSourceRepository {
    public record Links(CustomerRow customer,Long studentId,Long sourceLeadId,Long activityId,
            Long leadVersion,Long activityVersion) { }
    private record Lead(long id,long customerId,Long campusId,Long studentId,Long activityId,long version,String status) { }
    private record Activity(long id,Long campusId,long version) { }
    private final JdbcTemplate jdbc;
    private final OpportunityNeedReadRepository reads;
    public OpportunityNeedSourceRepository(JdbcTemplate jdbc,OpportunityNeedReadRepository reads) { this.jdbc=jdbc; this.reads=reads; }
    public Links resolve(Actor actor,Input input,boolean lock,boolean mutable) {
        OpportunityNeedSupport.intake(actor); CustomerRow customer=reads.customer(actor,input.customerId(),lock);
        Long student=reads.student(customer.id(),input.studentId());
        Lead lead=OpportunityNeedSupport.clean(input.sourceLeadId())==null?null:lead(actor,input.sourceLeadId(),lock);
        if(lead!=null) student=studentFromLead(customer,student,lead,mutable);
        Activity activity=activity(actor,input,lead,lock);
        if(activity!=null&&!Objects.equals(activity.campusId(),customer.campusId()))OpportunityNeedSupport.invalid("来源活动与负责校区不一致");
        return new Links(customer,student,lead==null?null:lead.id(),activity==null?null:activity.id(),
            lead==null?null:lead.version(),activity==null?null:activity.version());
    }
    private Long studentFromLead(CustomerRow customer,Long student,Lead lead,boolean mutable) {
        if(lead.customerId()!=customer.id()||!Objects.equals(lead.campusId(),customer.campusId()))OpportunityNeedSupport.invalid("来源线索不属于该客户与负责校区");
        if(mutable&&!"qualified".equals(lead.status()))OpportunityNeedSupport.invalid("来源线索须先完成有效需求初筛");
            if(lead.studentId()!=null&&!lead.studentId().equals(student))OpportunityNeedSupport.invalid("来源线索已明确孩子，请选择同一孩子");
        if(lead.studentId()!=null) { requireStudentLink(customer.id(),lead.studentId()); return lead.studentId(); }
        return student;
    }
    private Lead lead(Actor actor,String publicId,boolean lock) {
        CrmLegacyAccess.read(actor, "crm.lead.read"); var scope=ScopeSql.leadReads(actor);
        var args=OpportunityNeedSupport.args(publicId,scope);
        var rows=jdbc.query("SELECT l.id,l.customer_id,l.campus_id,l.student_id,l.activity_id,l.version,l.status FROM core_crm_lead l WHERE l.public_id=? AND l.deleted=0 AND ("
            +scope.sql()+")"+(lock?" FOR UPDATE":""),(rs,n)->new Lead(rs.getLong(1),rs.getLong(2),rs.getObject(3,Long.class),
                rs.getObject(4,Long.class),rs.getObject(5,Long.class),rs.getLong(6),rs.getString(7)),args.toArray());
        if(rows.isEmpty())OpportunityNeedSupport.missing(); return rows.get(0);
    }
    private Activity activity(Actor actor,Input input,Lead lead,boolean lock) {
        Long inherited=lead==null?null:lead.activityId();
        if(OpportunityNeedSupport.clean(input.activityId())!=null) {
            CrmLegacyAccess.read(actor, "crm.activity.read"); var scope=ScopeSql.activities(actor);
            var args=OpportunityNeedSupport.args(input.activityId(),scope);
            var rows=jdbc.query("SELECT a.id,a.campus_id,a.version FROM core_activity a WHERE a.public_id=? AND a.deleted=0 AND ("
                +scope.sql()+")"+(lock?" FOR UPDATE":""),(rs,n)->new Activity(rs.getLong(1),rs.getObject(2,Long.class),rs.getLong(3)),args.toArray());
            if(rows.isEmpty())OpportunityNeedSupport.missing(); Activity found=rows.get(0);
            if(inherited!=null&&inherited!=found.id())OpportunityNeedSupport.invalid("商机活动须与来源线索一致"); return found;
        }
        if(inherited==null)return null;
        var rows=jdbc.query("SELECT id,campus_id,version FROM core_activity WHERE id=? AND deleted=0"+(lock?" FOR UPDATE":""),
            (rs,n)->new Activity(rs.getLong(1),rs.getObject(2,Long.class),rs.getLong(3)),inherited);
        if(rows.isEmpty())OpportunityNeedSupport.missing(); return rows.get(0);
    }
    private void requireStudentLink(long customer,long student) {
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM core_customer_student l JOIN core_student s ON s.id=l.student_id WHERE l.customer_id=? AND l.student_id=? AND l.deleted=0 AND s.deleted=0",Long.class,customer,student);
        if(count==0)OpportunityNeedSupport.invalid("来源线索孩子未有效关联到客户");
    }
}
