package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class TaskRoutingDirectory {
    private final JdbcTemplate jdbc;
    private final CrmTaskAssignmentRepository employees;
    private final TaskRoutingReadRepository reads;
    TaskRoutingDirectory(JdbcTemplate jdbc,PiiCipher cipher){this.jdbc=jdbc;employees=new CrmTaskAssignmentRepository(jdbc);reads=new TaskRoutingReadRepository(jdbc,cipher);}
    long campus(String id) {
        var rows=jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND deleted=0 AND active=1",(rs,n)->rs.getLong(1),id);
        if(rows.isEmpty())TaskRoutingSupport.notFound(); return rows.get(0);
    }
    long supervisor(Actor actor,String id,long campus,boolean lock) {
        var staff=employees.requireEligible(actor,id,List.of(campus),"marketing",lock);
        Long allowed=jdbc.queryForObject("SELECT COUNT(*) FROM core_principal_permission WHERE principal_id=? AND permission_code IN ('crm.task.manage','crm.task.assign') AND deleted=0",Long.class,staff.id());
        if(allowed==null||allowed!=2||staff.scopeType().equals("assigned"))throw new BusinessException(ErrorCode.FORBIDDEN,"主管未启用或缺少管理分派资格");
        return staff.id();
    }
    Page<Candidate> supervisors(Actor actor,CandidateQuery q) {
        TaskRoutingSupport.create(actor);validate(q);long campus;
        if(q.batchId()!=null){var b=reads.batch(actor,q.batchId(),false);campus=b.campusId();if(q.campusId()!=null&&!q.campusId().equals(b.campusPublicId()))TaskRoutingSupport.invalid("主管目录校区与批次不一致");}
        else {campus=campus(q.campusId());creatorCampus(actor,campus);}
        return candidates(actor,campus,q,true);
    }
    Page<Candidate> assignees(Actor actor,String id,CandidateQuery q){validate(q);return candidates(actor,reads.batch(actor,id,false).campusId(),q,false);}
    private void creatorCampus(Actor actor,long campus) {
        if(actor.scope().equals("all"))return;var scope=ScopeSql.customers(actor);List<Object> args=new ArrayList<>(List.of(campus));args.addAll(scope.args());
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM core_customer c WHERE c.deleted=0 AND c.campus_id=? AND ("+scope.sql()+")",Long.class,args.toArray());
        if(count==null||count==0)TaskRoutingSupport.notFound();
    }
    private Page<Candidate> candidates(Actor actor,long campus,CandidateQuery q,boolean manager) {
        String where="p.active=1 AND p.deleted=0 AND p.archived_at IS NULL AND p.credential_change_required=0 AND p.scope_type IN ('all','own','campus') AND (p.scope_type<>'campus' OR EXISTS(SELECT id FROM core_principal_campus WHERE principal_id=p.id AND campus_id=? AND deleted=0))";
        List<Object> args=new ArrayList<>(List.of(campus));
        for(String permission:manager?List.of("crm.task.read","crm.task.write","crm.task.assign","crm.task.manage"):List.of("crm.task.read","crm.task.write")){
            where+=" AND EXISTS(SELECT r.id FROM core_principal_permission r WHERE r.principal_id=p.id AND r.permission_code=? AND r.deleted=0)";args.add(permission);
        }
        if(q.query()!=null){where+=" AND LEFT(CONVERT(p.public_id USING utf8mb4),CHAR_LENGTH(?))=?";args.add(q.query());args.add(q.query());}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_principal p WHERE "+where,Long.class,args.toArray());
        args.add(q.size());args.add((long)(q.page()-1)*q.size());
        var people=jdbc.query("SELECT p.id,p.public_id,p.scope_type FROM core_principal p WHERE "+where+" ORDER BY p.public_id,p.id LIMIT ? OFFSET ?",(rs,n)->new Staff(rs.getLong(1),rs.getString(2),rs.getString(3)),args.toArray());
        var result=people.stream().map(p->new Candidate(p.publicId(),p.publicId(),p.scope(),pending(actor,p.id(),campus),"current_management_scope")).toList();
        return new Page<>(result,total==null?0:total,q.page(),q.size());
    }
    private long pending(Actor actor,long person,long campus) {
        if(!actor.permissions().contains("crm.task.manage"))return 0;
        var scope=TaskRoutingScope.management(actor);List<Object> args=new ArrayList<>(List.of(person,campus));args.addAll(scope.args());
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id WHERE t.owner_id=? AND t.campus_id=? AND t.deleted=0 AND b.deleted=0 AND b.delivery_mode<>'legacy' AND t.status IN ('pending','in_progress') AND ("+scope.sql()+")",Long.class,args.toArray());return count==null?0:count;
    }
    private void validate(CandidateQuery q){TaskRoutingSupport.page(q.page(),q.size());if(q.query()!=null&&(q.query().isBlank()||q.query().length()>120))TaskRoutingSupport.invalid("员工搜索无效");}
    private record Staff(long id,String publicId,String scope) { }
}
