package cn.siyue.core;

import cn.siyue.core.AuditDtos.AuditPage;
import cn.siyue.core.AuditDtos.AuditQuery;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies audit permissions, query bounds and scope before reading immutable history. */
@Service
public class AuditService {
    private final AuditRepository repository;
    /** 装配只读审计检索仓库。
     * @param repository 按操作人数据范围读取不可变审计历史的仓库
     */
    public AuditService(AuditRepository repository) { this.repository = repository; }

    /** 校验审计查询范围后分页检索记录，不改变历史数据。
     * 非全部数据范围的身份不得显式指定操作人筛选；时间区间必须成对提供、为正且不超过 93 天。
     * @param actor 具有 audit.read 权限的当前身份
     * @param query 查询条件；null 使用默认条件，页码和页大小为 0 时分别使用 1 和 50
     * @return 当前数据范围可见的审计记录页
     * @throws BusinessException 缺少权限、操作人筛选超范围、分页或时间及文本筛选不合法时
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public AuditPage search(Actor actor,AuditQuery query) {
        actor.requireAny("audit.read", "audit.query");
        if(query==null)query=new AuditQuery(null,null,null,null,null,0,0);
        String actorId=query.actorId(),action=query.action(),resourceId=query.resourceId();
        Instant fromAt=query.fromAt(),untilAt=query.untilAt();
        int page=query.page()==0?1:query.page(),size=query.size()==0?50:query.size();
        validatePaging(page,size);
        validateActorScope(actor,actorId);
        validateFilters(actorId,action,resourceId);
        validateWindow(fromAt,untilAt);
        AuditQuery normalized=new AuditQuery(actorId,action,resourceId,fromAt,untilAt,page,size);
        return repository.search(actor,normalized);
    }
    private void validatePaging(int page,int size){
        if(page<1||page>100000||size<1||size>100)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"审计分页范围无效");
    }
    private void validateActorScope(Actor actor,String actorId){
        if(actorId!=null&&!actorId.isBlank()&&!actor.scope().equals("all"))
            throw new BusinessException(ErrorCode.FORBIDDEN,"当前数据范围只能查询本人操作记录");
    }
    private void validateFilters(String actorId,String action,String resourceId){
        if(action!=null&&action.length()>100||resourceId!=null&&resourceId.length()>120
                ||actorId!=null&&actorId.length()>120)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"审计筛选值过长");
    }
    private void validateWindow(Instant fromAt,Instant untilAt){
        if((fromAt==null)!=(untilAt==null))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"起止时间必须同时提供");
        if(fromAt!=null&&(!untilAt.isAfter(fromAt)||Duration.between(fromAt,untilAt).compareTo(Duration.ofDays(93))>0))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"审计查询区间须为正数且不超过 93 天");
    }
}
