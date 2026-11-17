package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.*;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes routing facts and returns only the committed snapshot still visible to the current caller. */
@Repository
public class TaskRoutingMutationRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final TaskRoutingReadRepository reads;
    private final TaskRoutingEventRepository events;
    private final CrmTaskSourceRepository sources;
    private final CrmTaskNeedRepository needs;
    /**
     * Creates persistence helpers; the caller owns the encompassing write transaction.
     * @param jdbc transactional MySQL access for routing facts and immutable journals
     * @param cipher encrypts notes and event detail before persistence
     */
    public TaskRoutingMutationRepository(JdbcTemplate jdbc,PiiCipher cipher){this.jdbc=jdbc;this.cipher=cipher;reads=new TaskRoutingReadRepository(jdbc,cipher);events=new TaskRoutingEventRepository(jdbc,cipher);sources=new CrmTaskSourceRepository(jdbc);needs=new CrmTaskNeedRepository(jdbc,cipher);}
    Receipt create(Actor actor,CreateInput input,TaskRoutingPlanRepository.CreatePlan plan) {
        String id="TASKB_"+UUID.randomUUID();var preview=plan.preview();
        Long activity=input.activityId()==null?null:jdbc.queryForObject("SELECT id FROM core_activity WHERE public_id=? AND deleted=0",Long.class,input.activityId());
        jdbc.update("INSERT INTO core_crm_task_batch(public_id,created_by,title,task_type,campus_id,due_at,status,note_cipher,skipped_count,activity_id,delivery_mode,supervisor_id,starts_at) VALUES(?,?,?,'marketing',?,?,'active',?,?,?,?,?,?)",id,actor.id(),input.title().trim(),plan.campusId(),Timestamp.from(input.dueAt()),encrypt(input.note()),preview.skippedCount(),activity,input.delivery().mode(),events.principal(input.delivery().supervisorId()),Timestamp.from(input.startsAt()));
        long batch=jdbc.queryForObject("SELECT id FROM core_crm_task_batch WHERE public_id=?",Long.class,id);
        List<Assignment> changes=new ArrayList<>();
        for(int i=0;i<plan.eligible().size();i++)changes.add(insertTask(actor,new NewTask(batch,activity,input,plan.eligible().get(i),preview.targets().get(i))));
        String event=events.insert(actor,new TaskRoutingEventRepository.Write(batch,"create",null,input.delivery().supervisorId(),input.note(),changes));
        return receipt(actor,id,event,changes);
    }
    private Assignment insertTask(Actor actor,NewTask data) {
        var row=data.row();var target=row.input();var route=data.route();String id="TASK_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_crm_task(public_id,batch_id,customer_id,student_id,campus_id,owner_id,task_type,title,subject,term,status,due_at,note_cipher,activity_id) VALUES(?,?,?,?,?,?,'marketing',?,?,?,?,?,?,?)",id,data.batch(),row.customerId(),row.studentId(),row.campusId(),events.principal(route.ownerId()),data.input().title().trim(),target.subject(),target.term(),route.status(),Timestamp.from(data.input().dueAt()),encrypt(data.input().note()),data.activity());
        long task=jdbc.queryForObject("SELECT id FROM core_crm_task WHERE public_id=?",Long.class,id);
        sources.insert(task,target.sources());needs.insert(task,TaskRoutingTargets.need(row));
        jdbc.update("INSERT INTO core_crm_task_event(public_id,task_id,actor_id,event_type,occurred_at,detail_cipher) VALUES(?,?,?,'created',UTC_TIMESTAMP(3),?)","TASKEVT_"+UUID.randomUUID(),task,actor.id(),cipher.encrypt(route.ownerId()==null?"任务进入主管待分配池":"任务已分配给 "+route.ownerId()));
        return new Assignment(id,0,null,route.ownerId(),null,route.status(),null,data.input().dueAt(),"create");
    }
    Receipt change(Actor actor,TaskRoutingPlanRepository.ChangePlan plan,String reason) {
        var p=plan.preview();var batch=plan.batch();
        for(var change:p.assignments())applyTask(actor,change);
        int changed=jdbc.update("UPDATE core_crm_task_batch SET supervisor_id=?,starts_at=?,due_at=?,management_version=management_version+1,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND management_version=? AND status='active'",events.principal(p.toSupervisorId()),Timestamp.from(p.startsAt()),Timestamp.from(p.dueAt()),batch.id(),p.managementVersion());
        if(changed!=1)TaskRoutingSupport.stale();
        finish(batch.id());
        String event=events.insert(actor,new TaskRoutingEventRepository.Write(batch.id(),p.action(),p.fromSupervisorId(),p.toSupervisorId(),reason,p.assignments()));
        return receipt(actor,batch.publicId(),event,p.assignments());
    }
    private void applyTask(Actor actor,Assignment a) {
        int changed=jdbc.update("UPDATE core_crm_task SET owner_id=?,status=?,due_at=?,cancelled_at=CASE WHEN ?='cancelled' THEN UTC_TIMESTAMP(3) ELSE cancelled_at END,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE public_id=? AND version=? AND deleted=0",events.principal(a.toOwnerId()),a.toStatus(),Timestamp.from(a.toDueAt()),a.toStatus(),a.taskId(),a.expectedVersion());
        if(changed!=1)TaskRoutingSupport.stale();
        jdbc.update("INSERT INTO core_crm_task_event(public_id,task_id,actor_id,event_type,occurred_at,detail_cipher) SELECT ?,id,?,?,UTC_TIMESTAMP(3),? FROM core_crm_task WHERE public_id=?","TASKEVT_"+UUID.randomUUID(),actor.id(),a.toStatus().equals("cancelled")?"cancelled":"updated",cipher.encrypt("任务管理："+a.basis()),a.taskId());
    }
    void finish(long batch) {
        jdbc.update("UPDATE core_crm_task_batch b SET status=CASE WHEN EXISTS(SELECT t.id FROM core_crm_task t WHERE t.batch_id=b.id AND t.deleted=0 AND t.status IN ('unassigned','pending','in_progress')) THEN 'active' WHEN EXISTS(SELECT t.id FROM core_crm_task t WHERE t.batch_id=b.id AND t.deleted=0 AND t.status='completed') THEN 'completed' ELSE 'cancelled' END WHERE b.id=?",batch);
    }
    /** Projects the new receipt only if its batch remains readable after the mutation. */
    private Receipt receipt(Actor actor,String id,String event,List<Assignment> changes) {
        var current=reads.findVisible(actor,id);
        if(current.isEmpty())return leftScope(id,event);
        List<TaskReceipt> tasks=changes.stream().map(c->new TaskReceipt(c.taskId(),c.expectedVersion()+1,c.toOwnerId(),c.toStatus(),c.toDueAt())).toList();
        return new Receipt(true,id,event,"visible",current.get(),tasks);
    }
    /**
     * Replays original versions, task assignments and dates while refreshing only permitted disclosure.
     * @param actor freshly authorized caller from the surrounding service transaction
     * @param saved immutable receipt stored under the original actor, request hash and idempotency key
     * @return the original snapshot with current activity/actions, or a minimal left-scope receipt
     * @throws org.springframework.dao.DataAccessException if visibility cannot be queried; failure is never scope loss
     */
    Receipt replay(Actor actor,Receipt saved) {
        var visible=reads.findVisible(actor,saved.batchId());
        if(visible.isEmpty()||saved.batch()==null)return leftScope(saved.batchId(),saved.operationId());
        Batch current=visible.get();var b=saved.batch();
        var projected=new Batch(b.id(),b.title(),b.campusId(),current.activityId(),b.createdBy(),b.deliveryMode(),b.supervisorId(),b.startsAt(),b.dueAt(),b.status(),b.managementVersion(),b.version(),b.counts(),b.distributionStatus(),b.executionStatus(),current.actions());
        return new Receipt(true,saved.batchId(),saved.operationId(),"visible",projected,saved.tasks());
    }
    private Receipt leftScope(String id,String event) {
        return new Receipt(true,id,event,"left_scope",null,List.of());
    }
    /**
     * Lists the immutable routing journal after checking current management scope.
     * @param actor currently authorized manager
     * @param id batch public identifier
     * @param q bounded pagination and directory query
     * @return authorized routing events in journal order
     * @throws BusinessException when management permission or batch visibility is absent
     */
    public Page<Event> events(Actor actor,String id,CandidateQuery q){return events.list(reads.batch(actor,id,false).id(),q);}
    /**
     * Lists supervisors qualified for the chosen campus without exposing customer details.
     * @param actor creator whose assignment rights and campus scope are enforced
     * @param q campus or batch context and bounded pagination
     * @return eligible supervisors with workload inside the caller's current management scope
     * @throws BusinessException when creation rights, campus context or pagination are invalid
     */
    public Page<Candidate> supervisors(Actor actor,CandidateQuery q){return new TaskRoutingDirectory(jdbc,cipher).supervisors(actor,q);}
    /**
     * Lists eligible receivers within a batch visible to the service-authorized manager.
     * @param actor current manager, already checked by the service for assignment rights
     * @param id batch public identifier
     * @param q bounded candidate search and pagination
     * @return receiver identifiers and only the workload visible to this manager
     * @throws BusinessException when the batch is outside management scope or filters are invalid
     */
    public Page<Candidate> assignees(Actor actor,String id,CandidateQuery q){return new TaskRoutingDirectory(jdbc,cipher).assignees(actor,id,q);}
    private String encrypt(String value){return value==null?null:cipher.encrypt(value);}
    private record NewTask(long batch,Long activity,CreateInput input,TaskRoutingRows.Target row,Reserved route) { }
}
