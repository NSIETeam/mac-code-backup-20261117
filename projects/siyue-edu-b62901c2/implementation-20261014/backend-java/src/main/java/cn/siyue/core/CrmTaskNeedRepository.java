package cn.siyue.core;

import cn.siyue.core.CrmDtos.TaskTargetInput;
import cn.siyue.core.OpportunityNeedDtos.Identity;
import cn.siyue.core.OpportunityNeedDtos.NeedResolution;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Keeps classified demands distinct while retaining the original identity of historical task evidence. */
final class CrmTaskNeedRepository {
    /** 任务需求去重和写入使用的客户、可选孩子、校区内部引用及原目标输入。 */
    record Target(long customer,Long student,Long campus,TaskTargetInput input) { }
    /** 任务原需求与后续已解析需求的部门/项目引用，避免把历史未知身份当作已分类身份。 */
    record Keys(Long department,Long project,Long resolvedDepartment,Long resolvedProject) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final OpportunityConfigRepository config;
    CrmTaskNeedRepository(JdbcTemplate jdbc,PiiCipher cipher) {
        this.jdbc=jdbc; this.cipher=cipher; this.config=new OpportunityConfigRepository(jdbc,cipher);
    }
    void validate(Target target) {
        if(target.input().needIdentity()!=null)config.identity(target.input().needIdentity(),target.campus(),false);
    }
    String skipReason(Target target) {
        Identity identity=target.input().needIdentity();
        var wanted=identity==null?null:config.identity(identity,target.campus(),false);
        var rows=jdbc.query("""
            SELECT need_department_id,need_project_id FROM core_crm_task
            WHERE customer_id=? AND student_id <=> ? AND task_type='marketing'
              AND BINARY LOWER(subject)=BINARY LOWER(?) AND BINARY LOWER(term)=BINARY LOWER(?)
              AND status IN('unassigned','pending','in_progress') AND deleted=0
            """,(rs,n)->new Keys(rs.getObject(1,Long.class),rs.getObject(2,Long.class),null,null),
            target.customer(),target.student(),target.input().subject(),target.input().term());
        if(rows.stream().anyMatch(row->(row.department()==null)!=(wanted==null)))return "identity_review_required";
        if(rows.stream().anyMatch(row->wanted==null||Objects.equals(row.department(),wanted.department().id())
                &&Objects.equals(row.project(),wanted.project()==null?null:wanted.project().id())))return "open_marketing_task";
        return null;
    }
    void insert(long task,Target target) {
        if(target.input().needIdentity()==null)return;
        var identity=config.identity(target.input().needIdentity(),target.campus(),false);
        jdbc.update("UPDATE core_crm_task SET need_department_id=?,need_project_id=? WHERE id=?",identity.department().id(),
            identity.project()==null?null:identity.project().id(),task);
    }
    Keys keys(long task) {
        return jdbc.queryForObject("SELECT need_department_id,need_project_id,resolved_department_id,resolved_project_id FROM core_crm_task WHERE id=?",
            (rs,n)->new Keys(rs.getObject(1,Long.class),rs.getObject(2,Long.class),rs.getObject(3,Long.class),rs.getObject(4,Long.class)),task);
    }
    Identity identity(Long department,Long project) {
        if(department==null)return null;
        String dept=jdbc.queryForObject("SELECT public_id FROM core_opportunity_option WHERE id=?",String.class,department);
        String product=project==null?null:jdbc.queryForObject("SELECT public_id FROM core_opportunity_option WHERE id=?",String.class,project);
        return new Identity(dept,product);
    }
    void complete(Actor actor,long task,long opportunity,NeedResolution resolution) {
        Keys keys=keys(task);
        var target=jdbc.queryForObject("SELECT department_id,project_id,version,need_version FROM core_opportunity WHERE id=? FOR UPDATE",
            (rs,n)->new Deal(rs.getObject(1,Long.class),rs.getObject(2,Long.class),rs.getLong(3),rs.getInt(4)),opportunity);
        if(keys.department()!=null) {
            if(!Objects.equals(keys.department(),target.department())||!Objects.equals(keys.project(),target.project()))OpportunityNeedSupport.invalid("商机部门或项目与任务需求不匹配");
            if(resolution!=null)OpportunityNeedSupport.invalid("已明确的任务需求无需再次识别"); return;
        }
        if(target.needVersion()!=2) {
            if(resolution!=null)OpportunityNeedSupport.invalid("历史商机须先完成需求识别"); return;
        }
        if(resolution==null)OpportunityNeedSupport.invalid("任务需求尚未识别，请明确核对所选商机部门/项目");
        Identity selected=identity(target.department(),target.project());
        if(!selected.equals(new Identity(resolution.departmentId(),resolution.projectId()))||resolution.expectedOpportunityVersion()!=target.version())OpportunityNeedSupport.stale();
        if(OpportunityNeedSupport.clean(resolution.reason())==null)OpportunityNeedSupport.invalid("请填写需求识别依据");
        jdbc.update("UPDATE core_crm_task SET resolved_department_id=?,resolved_project_id=? WHERE id=?",target.department(),target.project(),task);
        jdbc.update("INSERT INTO core_opportunity_need_event(public_id,opportunity_id,actor_id,event_type,detail_cipher) VALUES(?,?,?,'task_resolution',?)",
            "NEVT_"+java.util.UUID.randomUUID(),opportunity,actor.id(),cipher.encrypt("任务 "+task+"："+resolution.reason().trim()));
    }
    /** 完成任务时锁定的商机需求身份及版本，用于校验选择的商机与任务需求一致。 */
    private record Deal(Long department,Long project,long version,int needVersion) { }
}
