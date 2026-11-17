package cn.siyue.core;

import java.util.List;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;

/** Management delegation never expands customer access or employee execution scope. */
final class TaskRoutingScope {
    private TaskRoutingScope() { }
    static ScopeSql.Clause management(Actor actor) {
        TaskRoutingSupport.management(actor);
        return switch(actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1",List.of());
            case "campus" -> new ScopeSql.Clause("b.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)",List.of(actor.id()));
            case "own" -> new ScopeSql.Clause("b.supervisor_id=?",List.of(actor.id()));
            default -> new ScopeSql.Clause("1=0",List.of());
        };
    }
    static ScopeSql.Clause execution(Actor actor,ScopeSql.Clause legacy) {
        List<Object> args=new ArrayList<>(legacy.args()); args.add(actor.id());
        return new ScopeSql.Clause("((EXISTS (SELECT b0.id FROM core_crm_task_batch b0 WHERE b0.id=t.batch_id AND b0.delivery_mode='legacy') AND ("
            +legacy.sql()+")) OR (EXISTS (SELECT b0.id FROM core_crm_task_batch b0 WHERE b0.id=t.batch_id AND b0.delivery_mode<>'legacy') AND t.owner_id=?))",args);
    }
    static void lockTaskBatch(JdbcTemplate jdbc,String taskId) {
        var batches=jdbc.query("SELECT batch_id FROM core_crm_task WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),taskId);
        if(!batches.isEmpty())jdbc.query("SELECT id FROM core_crm_task_batch WHERE id=? FOR UPDATE",(rs,n)->rs.getLong(1),batches.get(0));
    }
    static void employeeChange(JdbcTemplate jdbc,Actor actor,String taskId,CrmDtos.TaskUpdate input) {
        var rows=jdbc.query("SELECT b.delivery_mode,t.owner_id,b.starts_at FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id WHERE t.public_id=?",(rs,n)->new Gate(rs.getString(1),rs.getObject(2,Long.class),rs.getTimestamp(3)),taskId);
        if(rows.isEmpty()||rows.get(0).mode().equals("legacy"))return;
        executionGate(actor,rows.get(0));
        if(input.ownerId()!=null||input.dueAt()!=null||"cancelled".equals(input.status()))TaskRoutingSupport.invalid("此任务的分配、日期或取消须由主管管理操作办理");
    }
    static void employeeComplete(JdbcTemplate jdbc,Actor actor,String taskId) {
        var rows=jdbc.query("SELECT b.delivery_mode,t.owner_id,b.starts_at FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id WHERE t.public_id=?",(rs,n)->new Gate(rs.getString(1),rs.getObject(2,Long.class),rs.getTimestamp(3)),taskId);
        if(!rows.isEmpty()&&!rows.get(0).mode().equals("legacy"))executionGate(actor,rows.get(0));
    }
    private static void executionGate(Actor actor,Gate gate) {
        if(gate.owner()==null||gate.owner()!=actor.id())throw new BusinessException(ErrorCode.FORBIDDEN,"仅当前受派员工可办理此任务");
        if(gate.start()!=null&&gate.start().toInstant().isAfter(java.time.Instant.now()))TaskRoutingSupport.invalid("任务尚未开始");
    }
    private record Gate(String mode,Long owner,java.sql.Timestamp start) { }
}
