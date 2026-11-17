package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads management-scoped batches and their current disclosure-safe activity and action projections. */
@Repository
public class TaskRoutingReadRepository {
    static final String COUNTS="""
        JOIN (SELECT batch_id,COUNT(*) AS total_count,
        SUM(status='unassigned') AS unassigned_count,SUM(status='pending') AS pending_count,
        SUM(status='in_progress') AS in_progress_count,SUM(status='completed') AS completed_count,
        SUM(status='cancelled') AS cancelled_count FROM core_crm_task WHERE deleted=0 GROUP BY batch_id) z ON z.batch_id=b.id
        """;
    static final String DISTRIBUTION="CASE WHEN z.cancelled_count=z.total_count THEN 'empty' WHEN z.unassigned_count=0 THEN 'allocated' WHEN z.unassigned_count+z.cancelled_count=z.total_count THEN 'unassigned' ELSE 'partial' END";
    static final String EXECUTION="CASE WHEN z.cancelled_count=z.total_count THEN 'cancelled' WHEN z.completed_count+z.cancelled_count=z.total_count THEN 'completed' WHEN z.in_progress_count+z.completed_count>0 THEN 'in_progress' ELSE 'not_started' END";
    private final JdbcTemplate jdbc;
    private final CrmTaskSourceRepository sources;
    private final CrmTaskNeedRepository needs;
    /**
     * Creates scoped read helpers without opening a transaction independently.
     * @param jdbc MySQL access participating in the service transaction
     * @param cipher decrypts only authorized need metadata
     */
    public TaskRoutingReadRepository(JdbcTemplate jdbc,PiiCipher cipher) {
        this.jdbc=jdbc; sources=new CrmTaskSourceRepository(jdbc); needs=new CrmTaskNeedRepository(jdbc,cipher);
    }
    void lockBatch(String id) {
        jdbc.query("SELECT id FROM core_crm_task_batch WHERE public_id=? AND deleted=0 FOR UPDATE",(rs,n)->rs.getLong(1),id);
    }
    TaskRoutingRows.BatchRow batch(Actor actor,String id,boolean lock) {
        return findBatch(actor,id,lock).orElseThrow(() ->
            new BusinessException(ErrorCode.NOT_FOUND,"任务批次不存在或不在当前范围"));
    }
    private Optional<TaskRoutingRows.BatchRow> findBatch(Actor actor,String id,boolean lock) {
        if(lock)lockBatch(id);
        var scope=TaskRoutingScope.management(actor); List<Object> args=new ArrayList<>(List.of(id)); args.addAll(scope.args());
        var rows=jdbc.query("""
            SELECT b.id,b.public_id,b.campus_id,cp.public_id AS campus_public_id,b.delivery_mode,
            b.supervisor_id,p.public_id AS supervisor_public_id,b.starts_at,b.due_at,b.status,b.management_version
            FROM core_crm_task_batch b JOIN core_campus cp ON cp.id=b.campus_id AND cp.deleted=0 AND cp.active=1
            LEFT JOIN core_principal p ON p.id=b.supervisor_id
            WHERE b.public_id=? AND b.deleted=0 AND b.delivery_mode<>'legacy' AND (
            """+scope.sql()+")",(rs,n)->new TaskRoutingRows.BatchRow(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getObject(6,Long.class),rs.getString(7),rs.getTimestamp(8).toInstant(),rs.getTimestamp(9).toInstant(),rs.getString(10),rs.getLong(11)),args.toArray());
        return rows.stream().findFirst();
    }
    /**
     * Finds a receipt projection without raising exceptions for normal visibility loss.
     * @param actor current identity; both read and management grants are required for disclosure
     * @param id batch public identifier from the committed operation
     * @return empty for missing grants, deleted/inactive campus or an out-of-scope batch
     * @throws org.springframework.dao.DataAccessException if current visibility cannot be established
     */
    Optional<Batch> findVisible(Actor actor,String id) {
        if(!actor.permissions().containsAll(Set.of("crm.task.read","crm.task.manage"))
                ||findBatch(actor,id,false).isEmpty())return Optional.empty();
        var q=new Query("scope",null,null,null,null,null,null,null,null,id,null,null,1,1);
        return Optional.of(list(actor,q).items().get(0));
    }
    /**
     * Reads a batch with current management permission and active campus visibility.
     * @param actor current caller
     * @param id batch public identifier
     * @return current counts, versions and permitted actions
     * @throws BusinessException when the caller lacks management rights or the batch is unavailable
     */
    public Batch get(Actor actor,String id) {
        batch(actor,id,false);
        var q=new Query("scope",null,null,null,null,null,null,null,null,id,null,null,1,1);
        return list(actor,q).items().get(0);
    }
    /**
     * Applies the current management scope before paginating batch aggregates.
     * @param actor current caller whose permissions govern management and activity disclosure
     * @param query validated views, filters and pagination
     * @return batches and a total calculated from the same scoped predicate
     * @throws BusinessException when permissions, filter values or pagination are invalid
     */
    public Page<Batch> list(Actor actor,Query query) {
        var where=filter(actor,query); String from=" FROM core_crm_task_batch b "+COUNTS+" WHERE "+where.sql();
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,where.args().toArray());
        List<Object> args=new ArrayList<>(where.args()); args.add(query.size()); args.add(offset(query));
        var items=jdbc.query("SELECT b.public_id,b.title,cp.public_id AS campus,p.public_id AS creator,m.public_id AS manager,b.delivery_mode,b.starts_at,b.due_at,b.status,b.management_version,b.version,z.total_count,z.unassigned_count,z.pending_count,z.in_progress_count,z.completed_count,z.cancelled_count,"+DISTRIBUTION+" AS distribution,"+EXECUTION+" AS execution FROM core_crm_task_batch b "+COUNTS
            +" JOIN core_campus cp ON cp.id=b.campus_id JOIN core_principal p ON p.id=b.created_by LEFT JOIN core_principal m ON m.id=b.supervisor_id WHERE "+where.sql()+" ORDER BY b.id DESC LIMIT ? OFFSET ?",(rs,n)->new Batch(rs.getString("public_id"),rs.getString("title"),rs.getString("campus"),sources.batchActivity(actor,rs.getString("public_id")),rs.getString("creator"),rs.getString("delivery_mode"),rs.getString("manager"),rs.getTimestamp("starts_at").toInstant(),rs.getTimestamp("due_at").toInstant(),rs.getString("status"),rs.getLong("management_version"),rs.getLong("version"),counts(rs),rs.getString("distribution"),rs.getString("execution"),TaskRoutingSupport.actions(actor,rs.getString("delivery_mode"),rs.getString("status"))),args.toArray());
        return new Page<>(items,total==null?0:total,query.page(),query.size());
    }
    /**
     * Reads task rows only through batches in the caller's management scope.
     * @param actor current manager; source disclosure has its own additional permissions
     * @param query batch, owner, state and pagination filters
     * @return tasks with inaccessible acquisition evidence omitted
     * @throws BusinessException when management permission or filter validation fails
     */
    public Page<TaskItem> tasks(Actor actor,Query query) {
        var where=taskFilter(actor,query); String from=" FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id "+COUNTS+" WHERE "+where.sql();
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,where.args().toArray());
        List<Object> args=new ArrayList<>(where.args()); args.add(query.size()); args.add(offset(query));
        var ids=jdbc.query("SELECT t.id"+from+" ORDER BY t.id LIMIT ? OFFSET ?",(rs,n)->rs.getLong(1),args.toArray());
        return new Page<>(ids.stream().map(id->item(actor,id)).toList(),total==null?0:total,query.page(),query.size());
    }
    TaskItem item(Actor actor,long id) {
        var source=sources.summary(actor,id); var keys=needs.keys(id);
        return jdbc.queryForObject("""
            SELECT t.public_id,b.public_id,c.public_id,s.public_id,t.subject,t.term,p.public_id,t.status,t.due_at,t.version
            FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id
            JOIN core_customer c ON c.id=t.customer_id LEFT JOIN core_student s ON s.id=t.student_id
            LEFT JOIN core_principal p ON p.id=t.owner_id WHERE t.id=?
            """,(rs,n)->new TaskItem(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),needs.identity(keys.department(),keys.project()),rs.getString(7),rs.getString(8),rs.getTimestamp(9).toInstant(),rs.getLong(10),source.activityId(),source.sources(),source.status()),id);
    }
    /**
     * Aggregates assigned workload using the same scoped task predicate as task drill-down.
     * @param actor current manager
     * @param query filters and bounded pagination
     * @return one workload row per batch and assigned employee
     * @throws BusinessException when management permission or filter validation fails
     */
    public Page<Employee> employees(Actor actor,Query query) {
        var where=taskFilter(actor,query); String from=" FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id "+COUNTS+" WHERE "+where.sql()+" AND t.owner_id IS NOT NULL";
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT t.batch_id,t.owner_id"+from+" GROUP BY t.batch_id,t.owner_id) grouped",Long.class,where.args().toArray());
        List<Object> args=new ArrayList<>(where.args()); args.add(query.size()); args.add(offset(query));
        var items=jdbc.query("SELECT b.public_id,b.title,(SELECT p.public_id FROM core_principal p WHERE p.id=t.owner_id) AS owner,COUNT(*) total_count,SUM(t.status='unassigned') unassigned_count,SUM(t.status='pending') pending_count,SUM(t.status='in_progress') in_progress_count,SUM(t.status='completed') completed_count,SUM(t.status='cancelled') cancelled_count"+from+" GROUP BY b.id,b.public_id,b.title,t.owner_id ORDER BY b.id DESC,t.owner_id LIMIT ? OFFSET ?",(rs,n)->new Employee(rs.getString(1),rs.getString(2),rs.getString(3),counts(rs)),args.toArray());
        return new Page<>(items,total==null?0:total,query.page(),query.size());
    }
    private ScopeSql.Clause taskFilter(Actor actor,Query q) {
        var filter=filter(actor,q); List<Object> args=new ArrayList<>(filter.args()); String sql=filter.sql()+" AND t.deleted=0";
        if(q.ownerId()!=null){sql+=" AND t.owner_id=(SELECT id FROM core_principal WHERE public_id=?)";args.add(q.ownerId());}
        if(q.status()!=null){sql+=" AND t.status=?";args.add(q.status());}
        return new ScopeSql.Clause(sql,args);
    }
    private ScopeSql.Clause filter(Actor actor,Query q) {
        validate(q); var scope=TaskRoutingScope.management(actor); List<Object> args=new ArrayList<>(scope.args());
        String sql="b.deleted=0 AND b.delivery_mode<>'legacy' AND ("+scope.sql()+")";
        if("responsible".equals(q.view())){sql+=" AND b.supervisor_id=?";args.add(actor.id());}
        if("created".equals(q.view())){sql+=" AND b.created_by=?";args.add(actor.id());}
        String[] values={q.campusId(),q.supervisorId(),q.activityId(),q.batchId()};
        String[] expressions={"b.campus_id=(SELECT id FROM core_campus WHERE public_id=?)","b.supervisor_id=(SELECT id FROM core_principal WHERE public_id=?)","b.activity_id=(SELECT id FROM core_activity WHERE public_id=?)","b.public_id=?"};
        for(int i=0;i<values.length;i++){if(values[i]!=null){sql+=" AND "+expressions[i];args.add(values[i]);}}
        if(q.activityId()!=null){actor.require("crm.activity.read");var a=ScopeSql.activities(actor);sql+=" AND EXISTS(SELECT a.id FROM core_activity a WHERE a.id=b.activity_id AND a.deleted=0 AND ("+a.sql()+"))";args.addAll(a.args());}
        return extraFilters(new ScopeSql.Clause(sql,args),q);
    }
    private ScopeSql.Clause extraFilters(ScopeSql.Clause base,Query q) {
        String sql=base.sql();List<Object> args=new ArrayList<>(base.args());
        if(q.query()!=null){sql+=" AND LOCATE(?,b.title)>0";args.add(q.query());}
        if(q.distributionStatus()!=null){sql+=" AND ("+DISTRIBUTION+")=?";args.add(q.distributionStatus());}
        if(q.executionStatus()!=null){sql+=" AND ("+EXECUTION+")=?";args.add(q.executionStatus());}
        if(q.fromAt()!=null){sql+=" AND b.created_at>=?";args.add(java.sql.Timestamp.from(q.fromAt()));}
        if(q.untilAt()!=null){sql+=" AND b.created_at<?";args.add(java.sql.Timestamp.from(q.untilAt()));}
        return new ScopeSql.Clause(sql,args);
    }
    private void validate(Query q) {
        TaskRoutingSupport.page(q.page(),q.size());
        if(!Set.of("responsible","created","scope").contains(q.view()))TaskRoutingSupport.invalid("任务范围无效");
        check(q.distributionStatus(),Set.of("unassigned","partial","allocated","empty"));
        check(q.executionStatus(),Set.of("not_started","in_progress","completed","cancelled"));
        check(q.status(),Set.of("unassigned","pending","in_progress","completed","cancelled"));
        for(String value:Arrays.asList(q.campusId(),q.supervisorId(),q.activityId(),q.batchId(),q.ownerId(),q.query()))if(value!=null&&(value.isBlank()||value.length()>200))TaskRoutingSupport.invalid("任务筛选无效");
        if(q.fromAt()!=null&&q.untilAt()!=null&&!q.untilAt().isAfter(q.fromAt()))TaskRoutingSupport.invalid("创建期间无效");
    }
    private static void check(String value,Set<String> allowed){if(value!=null&&!allowed.contains(value))TaskRoutingSupport.invalid("任务筛选状态无效");}
    private static long offset(Query q){return (long)(q.page()-1)*q.size();}
    private static Counts counts(java.sql.ResultSet rs)throws java.sql.SQLException {
        long pending=rs.getLong("pending_count"),progress=rs.getLong("in_progress_count"),done=rs.getLong("completed_count");
        return new Counts(rs.getLong("total_count"),rs.getLong("unassigned_count"),pending,progress,done,rs.getLong("cancelled_count"),pending+progress+done);
    }
    List<TaskRoutingRows.TaskRow> rows(long batchId,boolean lock) {
        return jdbc.query("SELECT t.id,t.public_id,t.batch_id,t.customer_id,t.student_id,t.campus_id,p.public_id,t.status,t.due_at,t.version FROM core_crm_task t LEFT JOIN core_principal p ON p.id=t.owner_id WHERE t.batch_id=? AND t.deleted=0 ORDER BY t.id"+(lock?" FOR UPDATE":""),(rs,n)->new TaskRoutingRows.TaskRow(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getObject(5,Long.class),rs.getLong(6),rs.getString(7),rs.getString(8),rs.getTimestamp(9).toInstant(),rs.getLong(10)),batchId);
    }
}
