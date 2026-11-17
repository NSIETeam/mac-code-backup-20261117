package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Builds immutable routing previews from current source eligibility and management versions. */
@Repository
public class TaskRoutingPlanRepository {
    record CreatePlan(CreatePreview preview,List<TaskRoutingRows.Target> eligible,long campusId) { }
    record ChangePlan(ChangePreview preview,TaskRoutingRows.BatchRow batch) { }
    private final TaskRoutingTargets targets;
    private final TaskRoutingDirectory directory;
    private final TaskRoutingAllocation allocation;
    private final TaskRoutingReadRepository reads;
    /**
     * Creates planning helpers sharing the service transaction's JDBC connection.
     * @param jdbc transactional MySQL access for eligibility, versions and locks
     * @param cipher decrypts authorized customer/task metadata used in previews
     */
    public TaskRoutingPlanRepository(JdbcTemplate jdbc,PiiCipher cipher){targets=new TaskRoutingTargets(jdbc,cipher);directory=new TaskRoutingDirectory(jdbc,cipher);allocation=new TaskRoutingAllocation(jdbc);reads=new TaskRoutingReadRepository(jdbc,cipher);}
    CreatePlan create(Actor actor,CreateInput input) {
        validateCreate(input);long campus=directory.campus(input.campusId());
        if(input.delivery().mode().equals("supervisor"))directory.supervisor(actor,input.delivery().supervisorId(),campus,true);
        List<TaskRoutingRows.Target> eligible=new ArrayList<>();List<Skipped> skipped=new ArrayList<>();
        for(var row:targets.load(actor,input)){String reason=targets.skip(row);if(reason==null)eligible.add(row);else skipped.add(new Skipped(CrmTaskRepository.targetKey(row.input()),row.input(),reason));}
        EmployeePlan employee=input.delivery().employeePlan();if(employee!=null)allocation.validate(actor,campus,employee,eligible.size());
        Map<String,Long> counts=new LinkedHashMap<>();List<Reserved> reserved=new ArrayList<>();
        for(var row:eligible){String owner=employee==null?null:allocation.choose(employee,counts);TaskRoutingAllocation.count(counts,owner);
            reserved.add(new Reserved(CrmTaskRepository.targetKey(row.input()),row.input(),owner,owner==null?"unassigned":"pending"));}
        return new CreatePlan(new CreatePreview("create",input.campusId(),input.delivery().mode(),input.delivery().supervisorId(),input.startsAt(),input.dueAt(),eligible.size()+skipped.size(),eligible.size(),skipped.size(),counts,List.copyOf(reserved),List.copyOf(skipped)),List.copyOf(eligible),campus);
    }
    private void validateCreate(CreateInput i) {
        if(i==null||!"marketing".equals(i.taskType())||TaskRoutingSupport.clean(i.title())==null)TaskRoutingSupport.invalid("营销任务名称和类型不能为空");
        if(i.title().length()>200||i.note()!=null&&i.note().length()>5000)TaskRoutingSupport.invalid("任务文字超出长度");
        TaskRoutingSupport.period(i.startsAt(),i.dueAt());
        if(i.delivery()==null||!Set.of("direct","supervisor").contains(String.valueOf(i.delivery().mode())))TaskRoutingSupport.invalid("请选择发给主管或员工");
        if(i.delivery().mode().equals("supervisor")){
            if(TaskRoutingSupport.clean(i.delivery().supervisorId())==null||i.delivery().employeePlan()!=null)TaskRoutingSupport.invalid("主管模式只可指定一名主管");
        }else if(i.delivery().supervisorId()!=null||i.delivery().employeePlan()==null)TaskRoutingSupport.invalid("员工模式须提供员工分配计划");
    }
    ChangePlan change(Actor actor,String id,String action,ChangeInput input,boolean lock) {
        TaskRoutingSupport.operation(action);validateChange(action,input);
        var batch=reads.batch(actor,id,lock);
        if(batch.managementVersion()!=input.expectedManagementVersion())TaskRoutingSupport.stale();
        if(!batch.status().equals("active"))TaskRoutingSupport.invalid("已结束批次不能调整");
        if(Set.of("allocation","recall","transfer").contains(action)&&!batch.mode().equals("supervisor"))TaskRoutingSupport.invalid("员工直派任务不能再次拆分");
        List<TaskRoutingRows.TaskRow> rows=reads.rows(batch.id(),lock);
        return new ChangePlan(planChange(actor,batch,rows,new Command(action,input)),batch);
    }
    private ChangePreview planChange(Actor actor,TaskRoutingRows.BatchRow batch,List<TaskRoutingRows.TaskRow> rows,Command command) {
        String action=command.action();ChangeInput input=command.input();
        var chosen=selected(rows,input.selection(),action);List<Blocked> blocked=new ArrayList<>();List<Assignment> changes=new ArrayList<>();Map<String,Long> counts=new LinkedHashMap<>();
        if(action.equals("allocation"))allocation.validate(actor,batch.campusId(),employee(input),chosen.size());
        validateBatchChange(actor,batch,command);
        for(var row:chosen){Assignment change=changeRow(actor,row,command,counts,blocked);if(change!=null)changes.add(change);}
        long remaining=rows.stream().filter(r->r.status().equals("unassigned")).count();
        if(action.equals("allocation"))remaining-=changes.size();
        if(action.equals("recall"))remaining+=changes.size();
        if(action.equals("cancel"))remaining=0;
        return new ChangePreview(action,batch.publicId(),batch.managementVersion(),chosen.size(),changes.size(),blocked.size(),List.copyOf(changes),List.copyOf(blocked),Map.copyOf(counts),remaining,batch.supervisorPublicId(),action.equals("transfer")?input.supervisorId():batch.supervisorPublicId(),action.equals("schedule")?input.startsAt():batch.startsAt(),action.equals("schedule")?input.dueAt():batch.dueAt());
    }
    private void validateBatchChange(Actor actor,TaskRoutingRows.BatchRow batch,Command command) {
        String action=command.action();var input=command.input();
        if(action.equals("transfer")){directory.supervisor(actor,input.supervisorId(),batch.campusId(),true);if(input.supervisorId().equals(batch.supervisorPublicId()))TaskRoutingSupport.invalid("主管未变化");}
        if(action.equals("schedule")){TaskRoutingSupport.period(input.startsAt(),input.dueAt());if(input.startsAt().equals(batch.startsAt())&&input.dueAt().equals(batch.dueAt()))TaskRoutingSupport.invalid("任务日期没有变化");if(!batch.startsAt().isAfter(Instant.now())&&input.startsAt().isAfter(Instant.now()))TaskRoutingSupport.invalid("已开始任务不能推迟到未来开始");}
    }
    private Assignment changeRow(Actor actor,TaskRoutingRows.TaskRow row,Command command,Map<String,Long> counts,List<Blocked> blocked) {
        String action=command.action();var input=command.input();String owner=row.ownerPublicId(),status=row.status(),basis=action;
        if(action.equals("transfer"))return null;
        if(action.equals("allocation")){
            if(!row.status().equals("unassigned")||!targets.valid(row)){blocked.add(new Blocked(row.publicId(),"task_not_assignable"));return null;}
            var source="source_lead_owner_first".equals(input.sourceOwnerPolicy())
                ?allocation.leadOwner(actor,row):new TaskRoutingAllocation.SourceOwner(true,null);
            if(!source.available()){blocked.add(new Blocked(row.publicId(),"source_owner_unavailable"));return null;}
            owner=source.ownerId();
            basis=owner==null?input.strategy():"source_lead_owner";
            if(owner==null)owner=allocation.choose(employee(input),counts);TaskRoutingAllocation.count(counts,owner);status="pending";
        }else if(action.equals("recall")){
            if(!Set.of("pending","in_progress").contains(row.status())){blocked.add(new Blocked(row.publicId(),"task_not_recallable"));return null;}owner=null;status="unassigned";
        }else if(action.equals("cancel"))status="cancelled";
        return new Assignment(row.publicId(),row.version(),row.ownerPublicId(),owner,row.status(),status,row.dueAt(),action.equals("schedule")?input.dueAt():row.dueAt(),basis);
    }
    private List<TaskRoutingRows.TaskRow> selected(List<TaskRoutingRows.TaskRow> rows,List<Selection> selection,String action) {
        if(action.equals("transfer"))return List.of();
        if(selection==null)return rows.stream().filter(r->Set.of("unassigned","pending","in_progress").contains(r.status())).toList();
        Map<String,TaskRoutingRows.TaskRow> known=new HashMap<>();rows.forEach(r->known.put(r.publicId(),r));List<TaskRoutingRows.TaskRow> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var selected:selection){if(selected==null||!seen.add(selected.taskId()))TaskRoutingSupport.invalid("任务选择不能为空或重复");var row=known.get(selected.taskId());if(row==null)TaskRoutingSupport.notFound();if(row.version()!=selected.expectedVersion())TaskRoutingSupport.stale();result.add(row);}
        return result.stream().sorted(Comparator.comparingLong(TaskRoutingRows.TaskRow::id)).toList();
    }
    private void validateChange(String action,ChangeInput i) {
        if(i==null||i.expectedManagementVersion()<1)TaskRoutingSupport.invalid("管理版本无效");
        if(i.reason()!=null&&i.reason().length()>1000)TaskRoutingSupport.invalid("调整原因过长");
        boolean select=Set.of("allocation","recall").contains(action);
        if(select&&(i.selection()==null||i.selection().isEmpty()||i.selection().size()>500))TaskRoutingSupport.invalid("请选中1至500条任务");
        if(!select&&i.selection()!=null)TaskRoutingSupport.invalid("该操作使用整批范围，不接受另选条目");
        if(!action.equals("allocation")&&TaskRoutingSupport.clean(i.reason())==null)TaskRoutingSupport.invalid("请填写调整原因");
        validateFields(action,i);
    }
    private void validateFields(String action,ChangeInput i) {
        validateAllocationFields(action,i);
        if(!action.equals("transfer")&&i.supervisorId()!=null)TaskRoutingSupport.invalid("该操作不接受主管字段");
        if(action.equals("transfer")&&TaskRoutingSupport.clean(i.supervisorId())==null)TaskRoutingSupport.invalid("目标主管不能为空");
        if(!action.equals("schedule")&&(i.startsAt()!=null||i.dueAt()!=null))TaskRoutingSupport.invalid("该操作不接受日期字段");
    }
    private void validateAllocationFields(String action,ChangeInput i) {
        if(!action.equals("allocation")&&(i.strategy()!=null||i.ownerIds()!=null||i.quotas()!=null||i.sourceOwnerPolicy()!=null))TaskRoutingSupport.invalid("该操作不接受员工分派字段");
        if(action.equals("allocation")){
            if(i.sourceOwnerPolicy()!=null&&!Set.of("none","source_lead_owner_first").contains(i.sourceOwnerPolicy()))TaskRoutingSupport.invalid("来源归属策略无效");
            if("source_lead_owner_first".equals(i.sourceOwnerPolicy())&&!"even".equals(i.strategy()))TaskRoutingSupport.invalid("原线索负责人优先只支持平均分配");
        }
    }
    private static EmployeePlan employee(ChangeInput i){return new EmployeePlan(i.strategy(),i.ownerIds(),i.quotas());}
    private record Command(String action,ChangeInput input) { }
}
