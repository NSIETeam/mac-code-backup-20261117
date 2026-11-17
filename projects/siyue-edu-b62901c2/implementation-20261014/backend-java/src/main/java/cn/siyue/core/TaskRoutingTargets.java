package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class TaskRoutingTargets {
    private final JdbcTemplate jdbc;
    private final CrmTaskSourceRepository sources;
    private final CrmTaskNeedRepository needs;
    TaskRoutingTargets(JdbcTemplate jdbc,PiiCipher cipher){this.jdbc=jdbc;sources=new CrmTaskSourceRepository(jdbc);needs=new CrmTaskNeedRepository(jdbc,cipher);}
    List<TaskRoutingRows.Target> load(Actor actor,CreateInput input) {
        var legacy=new CrmDtos.TaskBatchInput(input.title(),"marketing",null,null,input.activityId(),null,null,List.of(actor.publicId()),"even",null,input.dueAt(),input.note(),input.targets());
        var normalized=CrmTaskRepository.normalizedTargets(legacy);validateActivity(actor,input);Map<String,Customer> customers=new HashMap<>();
        for(String id:normalized.stream().map(CrmDtos.TaskTargetInput::customerId).distinct().sorted().toList())customers.put(id,customer(actor,id));
        List<TaskRoutingRows.Target> result=new ArrayList<>();
        for(var target:normalized){var c=customers.get(target.customerId());
            if(c.campus()==null||!input.campusId().equals(c.campusPublicId()))TaskRoutingSupport.invalid("目标客户须属于本批次校区");
            Long student=target.studentId()==null?null:student(c.id(),target.studentId());
            var row=new TaskRoutingRows.Target(c.id(),student,c.campus(),target);needs.validate(need(row));result.add(row);
        }
        sources.validate(actor,input.activityId(),normalized,true);return List.copyOf(result);
    }
    private void validateActivity(Actor actor,CreateInput input) {
        if(input.activityId()==null)return;
        if(input.activityId().isBlank())TaskRoutingSupport.invalid("活动编号不能为空白");
        actor.require("crm.activity.read");var scope=ScopeSql.activities(actor);List<Object> args=new ArrayList<>(List.of(input.activityId(),input.campusId()));args.addAll(scope.args());
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM core_activity a JOIN core_campus cp ON cp.id=a.campus_id WHERE a.public_id=? AND cp.public_id=? AND a.deleted=0 AND ("+scope.sql()+")",Long.class,args.toArray());
        if(count==null||count==0)TaskRoutingSupport.notFound();
    }
    private Customer customer(Actor actor,String id) {
        var scope=ScopeSql.customers(actor);List<Object> args=new ArrayList<>(List.of(id));args.addAll(scope.args());
        var rows=jdbc.query("SELECT c.id,c.campus_id,p.public_id FROM core_customer c LEFT JOIN core_campus p ON p.id=c.campus_id AND p.active=1 AND p.deleted=0 WHERE c.public_id=? AND c.deleted=0 AND ("+scope.sql()+") FOR UPDATE",(rs,n)->new Customer(rs.getLong(1),rs.getObject(2,Long.class),rs.getString(3)),args.toArray());
        if(rows.isEmpty())TaskRoutingSupport.notFound();return rows.get(0);
    }
    private long student(long customer,String id) {
        var rows=jdbc.query("SELECT s.id FROM core_student s JOIN core_customer_student c ON c.student_id=s.id AND c.customer_id=? AND c.deleted=0 WHERE s.public_id=? AND s.deleted=0 FOR UPDATE",(rs,n)->rs.getLong(1),customer,id);
        if(rows.isEmpty())TaskRoutingSupport.invalid("孩子未关联该客户");return rows.get(0);
    }
    String skip(TaskRoutingRows.Target row){return needs.skipReason(need(row));}
    static CrmTaskNeedRepository.Target need(TaskRoutingRows.Target row){return new CrmTaskNeedRepository.Target(row.customerId(),row.studentId(),row.campusId(),row.input());}
    boolean valid(TaskRoutingRows.TaskRow row) {
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM core_customer c WHERE c.id=? AND c.deleted=0 AND c.campus_id=? AND (? IS NULL OR EXISTS(SELECT s.id FROM core_student s JOIN core_customer_student cs ON cs.student_id=s.id AND cs.customer_id=c.id AND cs.deleted=0 WHERE s.id=? AND s.deleted=0))",Long.class,row.customerId(),row.campusId(),row.studentId(),row.studentId());return count!=null&&count>0;
    }
    private record Customer(long id,Long campus,String campusPublicId) { }
}
