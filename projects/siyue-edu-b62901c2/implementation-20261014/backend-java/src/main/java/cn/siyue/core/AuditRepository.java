package cn.siyue.core;

import cn.siyue.core.AuditDtos.AuditEvent;
import cn.siyue.core.AuditDtos.AuditPage;
import cn.siyue.core.AuditDtos.AuditQuery;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads immutable operational audit facts without exposing database sequence IDs or payloads. */
@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;
    /** 装配仅投影最小审计字段的参数化查询。
     * @param jdbc 业务审计数据库访问器
     */
    public AuditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 读取审计事实；非all范围固定只读本人事件，不接受过滤条件扩大范围。
     * @param actor 已由服务校验审计读取许可的当前员工
     * @param query 已验证的员工/动作/资源、含起始不含截止时刻以及有界分页
     * @return 按时刻及内部序号倒序的最小审计投影和总数
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     * @throws ArithmeticException 未经服务限制的分页乘积溢出时
     */
    public AuditPage search(Actor actor,AuditQuery query) {
        StringBuilder where=new StringBuilder("a.deleted=0");
        List<Object> args=new ArrayList<>();
        String actorPublicId=query.actorId(),action=query.action(),resourceId=query.resourceId();
        Timestamp fromAt=query.fromAt()==null?null:Timestamp.from(query.fromAt());
        Timestamp untilAt=query.untilAt()==null?null:Timestamp.from(query.untilAt());
        int page=query.page(),size=query.size();
        if(!actor.scope().equals("all")) {
            where.append(" AND a.actor_id=?");args.add(actor.id());
        } else if(actorPublicId!=null&&!actorPublicId.isBlank()) {
            where.append(" AND p.public_id=?");args.add(actorPublicId);
        }
        if(action!=null&&!action.isBlank()) {where.append(" AND a.action=?");args.add(action);}
        if(resourceId!=null&&!resourceId.isBlank()) {where.append(" AND a.resource_public_id=?");args.add(resourceId);}
        if(fromAt!=null) {where.append(" AND a.created_at>=?");args.add(fromAt);}
        if(untilAt!=null) {where.append(" AND a.created_at<?");args.add(untilAt);}
        String from=" FROM core_audit a JOIN core_principal p ON p.id=a.actor_id WHERE "+where;
        Long total=jdbc.queryForObject("SELECT COUNT(a.id)"+from,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);
        pageArgs.add(size);pageArgs.add(Math.multiplyExact((long)page-1,size));
        List<AuditEvent> items=jdbc.query("""
            SELECT a.created_at,p.public_id AS actor_public_id,a.action,a.resource_public_id,a.trace_id
            """+from+" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",
            (rs,row)->new AuditEvent(rs.getTimestamp("created_at").toInstant(),rs.getString("actor_public_id"),
                rs.getString("action"),rs.getString("resource_public_id"),rs.getString("trace_id")),pageArgs.toArray());
        return new AuditPage(items,total==null?0:total,page,size);
    }
}
