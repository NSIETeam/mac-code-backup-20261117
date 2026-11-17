package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class TaskRoutingEventRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    TaskRoutingEventRepository(JdbcTemplate jdbc,PiiCipher cipher){this.jdbc=jdbc;this.cipher=cipher;}
    record Write(long batchId,String action,String fromSupervisor,String toSupervisor,String reason,List<Assignment> changes) { }
    String insert(Actor actor,Write write) {
        String id="ROUTE_EVT_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_crm_task_routing_event(public_id,batch_id,actor_id,action,from_supervisor_id,to_supervisor_id,reason_cipher,changes_cipher) VALUES(?,?,?,?,?,?,?,?)",id,write.batchId(),actor.id(),write.action(),principal(write.fromSupervisor()),principal(write.toSupervisor()),write.reason()==null?null:cipher.encrypt(write.reason()),cipher.encrypt(TaskRoutingSupport.json(write.changes())));return id;
    }
    Page<Event> list(long batchId,CandidateQuery q) {
        TaskRoutingSupport.page(q.page(),q.size());Long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_task_routing_event WHERE batch_id=?",Long.class,batchId);
        var items=jdbc.query("SELECT e.public_id,e.action,a.public_id,f.public_id,t.public_id,e.occurred_at,e.reason_cipher,e.changes_cipher FROM core_crm_task_routing_event e JOIN core_principal a ON a.id=e.actor_id LEFT JOIN core_principal f ON f.id=e.from_supervisor_id LEFT JOIN core_principal t ON t.id=e.to_supervisor_id WHERE e.batch_id=? ORDER BY e.id DESC LIMIT ? OFFSET ?",(rs,n)->new Event(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getString(7)==null?null:cipher.decrypt(rs.getString(7)),Arrays.asList(TaskRoutingSupport.parse(cipher.decrypt(rs.getString(8)),Assignment[].class))),batchId,q.size(),(long)(q.page()-1)*q.size());
        return new Page<>(items,total==null?0:total,q.page(),q.size());
    }
    Long principal(String id){return id==null?null:jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",Long.class,id);}
}
