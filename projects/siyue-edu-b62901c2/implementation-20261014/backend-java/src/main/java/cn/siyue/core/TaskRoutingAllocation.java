package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Computes deterministic allocations using live source visibility and receiver eligibility. */
final class TaskRoutingAllocation {
    /** Available with no owner means a genuine unowned source and permits the explicit fallback strategy. */
    record SourceOwner(boolean available, String ownerId) { }
    private final JdbcTemplate jdbc;
    private final CrmTaskAssignmentRepository staff;
    private final CrmTaskSourceRepository sources;
    TaskRoutingAllocation(JdbcTemplate jdbc){this.jdbc=jdbc;staff=new CrmTaskAssignmentRepository(jdbc);sources=new CrmTaskSourceRepository(jdbc);}
    void validate(Actor actor,long campus,EmployeePlan plan,int total) {
        if(plan==null||!Set.of("even","quota").contains(String.valueOf(plan.strategy())))TaskRoutingSupport.invalid("员工分派策略无效");
        validateOwners(plan.ownerIds());
        for(String owner:plan.ownerIds().stream().sorted().toList()){staff.requireEligible(actor,owner,List.of(campus),"marketing",true);}
        validateQuotas(plan,total);
    }
    private void validateOwners(List<String> owners) {
        if(owners==null||owners.isEmpty()||owners.size()>100||new HashSet<>(owners).size()!=owners.size())TaskRoutingSupport.invalid("员工不能为空或重复");
        for(String owner:owners)if(owner==null||owner.isBlank()||owner.length()>120)TaskRoutingSupport.invalid("员工编号无效");
    }
    private void validateQuotas(EmployeePlan plan,int total) {
        var owners=plan.ownerIds();
        if(plan.strategy().equals("even")){if(plan.quotas()!=null&&!plan.quotas().isEmpty())TaskRoutingSupport.invalid("平均分配不接受指定数量");return;}
        if(plan.quotas()==null||plan.quotas().size()!=owners.size())TaskRoutingSupport.invalid("请给每位员工指定数量");
        Set<String> known=new HashSet<>();long sum=0;
        for(var quota:plan.quotas()){if(quota==null||!owners.contains(quota.ownerId())||!known.add(quota.ownerId())||quota.count()<0)TaskRoutingSupport.invalid("员工分配数量无效");sum+=quota.count();}
        if(sum!=total)TaskRoutingSupport.invalid("指定数量总和须等于本次分配需求数");
    }

    String choose(EmployeePlan plan,Map<String,Long> counts) {
        return plan.ownerIds().stream().filter(id->!plan.strategy().equals("quota")||counts.getOrDefault(id,0L)<plan.quotas().stream().filter(q->q.ownerId().equals(id)).findFirst().orElseThrow().count())
            .min(Comparator.comparingLong((String id)->counts.getOrDefault(id,0L)).thenComparingInt(plan.ownerIds()::indexOf)).orElseThrow(()->new BusinessException(ErrorCode.INVALID_INPUT,"指定数量不足"));
    }
    /**
     * Resolves lead-owner priority while preserving unavailable and genuinely unowned as different outcomes.
     * @param actor current manager; source read scope is checked independently from batch management
     * @param task selected task whose immutable source references are inspected under row locks
     * @return blocked qualification, a unique eligible owner, or an available unowned fallback
     * @throws org.springframework.dao.DataAccessException if source or receiver evidence cannot be read
     */
    SourceOwner leadOwner(Actor actor,TaskRoutingRows.TaskRow task) {
        var refs=jdbc.query("SELECT source_public_id,source_version FROM core_crm_task_source WHERE task_id=? AND source_type='lead' ORDER BY source_public_id",(rs,n)->new CrmDtos.TaskSourceRef("lead",rs.getString(1),rs.getObject(2,Long.class)),task.id());
        Set<String> owners=new HashSet<>();
        for(var ref:refs){var visible=sources.findVisible(actor,ref,true);
            if(visible.isEmpty()||"archived".equals(visible.get().status()))return new SourceOwner(false,null);
            var source=visible.get();
            String owner=jdbc.queryForObject("SELECT p.public_id FROM core_crm_lead l LEFT JOIN core_principal p ON p.id=l.owner_id WHERE l.id=?",String.class,source.id());
            if(owner!=null)owners.add(owner);
        }
        if(owners.size()>1)return new SourceOwner(false,null);
        if(owners.isEmpty())return new SourceOwner(true,null);
        String id=owners.iterator().next();
        return staff.findEligible(actor,id,List.of(task.campusId()),"marketing",true).isPresent()
            ?new SourceOwner(true,id):new SourceOwner(false,null);
    }
    static void count(Map<String,Long> counts,String owner){if(owner!=null)counts.merge(owner,1L,Long::sum);}
}
