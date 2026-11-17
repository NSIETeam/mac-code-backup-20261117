package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates reviewed marketing-task creation and batch management under separate creation and management grants.
 * Previews reload active principals and may acquire database locks without writing task facts. Commits
 * atomically persist changes, routing events and replay receipts. Read endpoints use read-only transactions;
 * all declared transactions roll back on checked exceptions as well as runtime failures.
 */
@Service
public class TaskRoutingService {
    private final AccessRepository access;
    private final TaskRoutingReadRepository reads;
    private final TaskRoutingPlanRepository plans;
    private final TaskRoutingMutationRepository writes;
    private final MutationRepository mutations;
    /**
     * Creates the task-routing transaction boundary.
     * @param access reloads and locks active principals before planning or commit
     * @param reads resolves current management scope, batch locks and read projections
     * @param plans computes eligible targets, assignment plans and versioned previews
     * @param writes persists routing changes and projects replay receipts under current visibility
     * @param mutations stores command hashes, replay receipts and audit events
     */
    public TaskRoutingService(AccessRepository access,TaskRoutingReadRepository reads,TaskRoutingPlanRepository plans,TaskRoutingMutationRepository writes,MutationRepository mutations){this.access=access;this.reads=reads;this.plans=plans;this.writes=writes;this.mutations=mutations;}
    /**
     * Calculates eligible and skipped marketing demand and proposed owners without creating task rows.
     * @param actor caller requiring both {@code crm.task.write} and {@code crm.task.assign}, rechecked after reload
     * @param input task dates, campus, targets and either a supervisor or direct employee delivery plan
     * @return exact target and owner plan to submit as the expected preview when committing
     * @throws BusinessException when the caller is inactive or unauthorized, dates or delivery fields are invalid,
     *     or customer, source, campus, supervisor or employee eligibility checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public CreatePreview previewCreate(Actor actor,CreateInput input){TaskRoutingSupport.create(actor);actor=access.lockActive(actor.id());TaskRoutingSupport.create(actor);return plans.create(actor,input).preview();}
    /**
     * Commits eligible targets only if a newly computed plan exactly equals the supplied preview.
     * Replay precedes replanning. Stored batch facts and task receipts remain the original result, while
     * activity disclosure and available actions are projected from current visibility; an inaccessible batch
     * returns a committed {@code left_scope} receipt without batch or task details.
     * @param actor caller requiring both {@code crm.task.write} and {@code crm.task.assign}
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input original creation request and the exact server preview reviewed by the caller
     * @return committed batch receipt, or its visibility-projected retry receipt
     * @throws BusinessException when authorization, key, references or eligibility fail, the preview changes,
     *     no eligible targets remain, or an earlier command conflicts with this key
     * @throws IllegalStateException when command or stored receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor=Exception.class)
    public Receipt commitCreate(Actor actor,String key,CreateCommit input){
        TaskRoutingSupport.create(actor);TaskRoutingSupport.key(key);actor=access.lockActive(actor.id());TaskRoutingSupport.create(actor);
        String action="crm.task.routing.create",hash=mutations.hash(TaskRoutingSupport.json(input));
        String replay=mutations.replay(actor,key,action,hash);if(replay!=null)return writes.replay(actor,TaskRoutingSupport.parse(replay,Receipt.class));
        var plan=plans.create(actor,input.input());if(!plan.preview().equals(input.expectedPreview()))TaskRoutingSupport.stale();
        if(plan.eligible().isEmpty())TaskRoutingSupport.invalid("没有可创建的任务需求");
        var receipt=writes.create(actor,input.input(),plan);journal(actor,new Journal(key,action,hash,receipt));return receipt;
    }
    /**
     * Locks the batch and active caller, then previews a management change without persisting it.
     * @param actor caller requiring {@code crm.task.read}, {@code crm.task.manage}, {@code crm.task.write}
     *     and {@code crm.task.assign}, plus current batch management scope
     * @param id batch public identifier
     * @param operation one of allocation, recall, transfer, schedule or cancel
     * @param input management version, operation-specific selections or values, and required adjustment reason
     * @return planned changes and blocked rows, including versions for exact commit comparison
     * @throws BusinessException when authorization, visibility, operation, versions, active batch state,
     *     selections, target staff or date rules prevent planning
     */
    @Transactional(rollbackFor=Exception.class)
    public ChangePreview previewChange(Actor actor,String id,String operation,ChangeInput input){TaskRoutingSupport.write(actor);reads.lockBatch(id);actor=access.lockActive(actor.id());TaskRoutingSupport.write(actor);return plans.change(actor,id,operation,input,false).preview();}
    /**
     * Commits a management change only when its locked plan matches review and has no blocked rows.
     * Replay is checked before replanning and preserves original facts; current activity disclosure and
     * available actions are projected again, or batch/task details are removed when it has left scope.
     * @param actor caller requiring task read, manage, write and assign grants and current management scope
     * @param id batch public identifier
     * @param operation one of allocation, recall, transfer, schedule or cancel
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input operation request and exact expected preview including management and task versions
     * @return committed routing receipt or the visibility-projected stored receipt
     * @throws BusinessException when permission, key, scope, operation, references or versions fail,
     *     the preview changes, blocked tasks remain, or a conflicting command already used the key
     * @throws IllegalStateException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor=Exception.class)
    public Receipt commitChange(Actor actor,String id,String operation,String key,ChangeCommit input){
        TaskRoutingSupport.write(actor);TaskRoutingSupport.operation(operation);TaskRoutingSupport.key(key);reads.lockBatch(id);actor=access.lockActive(actor.id());TaskRoutingSupport.write(actor);
        String action="crm.task.routing."+operation,hash=mutations.hash(TaskRoutingSupport.json(List.of(id,input)));
        String replay=mutations.replay(actor,key,action,hash);if(replay!=null)return writes.replay(actor,TaskRoutingSupport.parse(replay,Receipt.class));
        var plan=plans.change(actor,id,operation,input.input(),true);
        if(!plan.preview().equals(input.expectedPreview()))TaskRoutingSupport.stale();
        if(plan.preview().blockedCount()>0)TaskRoutingSupport.invalid("部分任务不可分配，请调整后重新预览");
        var receipt=writes.change(actor,plan,input.input().reason());journal(actor,new Journal(key,action,hash,receipt));return receipt;
    }
    private void journal(Actor actor,Journal j){mutations.complete(actor,new MutationRepository.RetryWrite(j.key(),j.action(),j.hash(),TaskRoutingSupport.json(j.receipt()),j.receipt().batchId()));}
    /**
     * Lists nonlegacy batches and aggregates under current management scope.
     * @param actor caller requiring {@code crm.task.read} and {@code crm.task.manage}; activity filters also
     *     require {@code crm.activity.read} and activity visibility
     * @param q responsible, created or scope view with filters; page 1–100000 and size 1–100
     * @return batch summaries with counts, currently permitted activity links and available actions
     * @throws BusinessException when permission, view, status, search, date interval or pagination is invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page<Batch> list(Actor actor,Query q){return reads.list(actor,q);}
    /**
     * Reads one nonlegacy batch after enforcing current management visibility.
     * @param actor caller requiring {@code crm.task.read} and {@code crm.task.manage}; own scope covers supervised batches
     * @param id batch public identifier
     * @return scoped batch summary with current activity disclosure and available actions
     * @throws BusinessException when management permission is absent or the batch is missing or outside scope
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Batch get(Actor actor,String id){return reads.get(actor,id);}
    /**
     * Lists task rows through visible management batches with source-specific disclosure checks.
     * @param actor caller requiring {@code crm.task.read} and {@code crm.task.manage}
     * @param q batch, owner, task-state and other management filters; page 1–100000 and size 1–100
     * @return task projections with inaccessible acquisition evidence omitted
     * @throws BusinessException when management or activity-filter permission, query fields or pagination are invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page<TaskItem> tasks(Actor actor,Query q){return reads.tasks(actor,q);}
    /**
     * Groups visible batch tasks by assigned employee for management progress summaries.
     * @param actor caller requiring {@code crm.task.read} and {@code crm.task.manage}
     * @param q management filters and bounded pagination; activity filtering requires activity read access
     * @return per-batch employee counts within the selected management batches
     * @throws BusinessException when required permissions, query filters or pagination are invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page<Employee> employees(Actor actor,Query q){return reads.employees(actor,q);}
    /**
     * Lists active supervisors qualified to receive the chosen campus's marketing tasks.
     * Candidate workload covers only current management scope; callers without task manage permission receive zero workload.
     * @param actor caller requiring {@code crm.task.write} and {@code crm.task.assign}; batch context also requires management access
     * @param q campus or visible batch context, optional account-prefix search, page 1–100000 and size 1–100
     * @return eligible supervisor identifiers, account labels and scoped pending-task counts
     * @throws BusinessException when creation permission, campus context, batch visibility or pagination is invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page<Candidate> supervisors(Actor actor,CandidateQuery q){return writes.supervisors(actor,q);}
    /**
     * Lists active task receivers qualified for a currently visible batch's campus.
     * @param actor caller requiring task read, manage, write and assign grants
     * @param id batch public identifier
     * @param q optional account-prefix search and pagination, with page 1–100000 and size 1–100
     * @return eligible receiver identities and pending-task counts within current management scope
     * @throws BusinessException when permissions, batch visibility, search or pagination checks fail
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page<Candidate> assignees(Actor actor,String id,CandidateQuery q){TaskRoutingSupport.write(actor);return writes.assignees(actor,id,q);}
    /**
     * Reads a batch's immutable routing-event history after checking current management scope.
     * @param actor caller requiring {@code crm.task.read} and {@code crm.task.manage}
     * @param id batch public identifier
     * @param q event pagination with page 1–100000 and size 1–100
     * @return routing events with their recorded actors, reasons and assignment changes
     * @throws BusinessException when management permission, batch visibility or pagination checks fail
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page<Event> events(Actor actor,String id,CandidateQuery q){return writes.events(actor,id,q);}
    private record Journal(String key,String action,String hash,Receipt receipt) { }
}
