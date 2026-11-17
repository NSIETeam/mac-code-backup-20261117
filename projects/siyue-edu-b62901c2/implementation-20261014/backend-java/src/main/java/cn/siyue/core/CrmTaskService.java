package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmTask;
import cn.siyue.core.CrmDtos.TaskBatchInput;
import cn.siyue.core.CrmDtos.TaskBatchPage;
import cn.siyue.core.CrmDtos.TaskBatchPreview;
import cn.siyue.core.CrmDtos.TaskBatchReceipt;
import cn.siyue.core.CrmDtos.TaskCompletion;
import cn.siyue.core.CrmDtos.TaskPage;
import cn.siyue.core.CrmDtos.TaskUpdate;
import cn.siyue.core.CrmDtos.TaskTargetStudentPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates marketing and service task batches and employee execution.
 * Writes reload the active caller and commit task changes, event history and replay receipts together.
 * Task completion/update retries retain original facts while source links are redacted against current
 * access. Read endpoints use the supplied caller in read-only transactions; checked exceptions roll back.
 */
@Service
public class CrmTaskService {
    /** Validated batch input paired with the recomputed preview that must match the caller-approved allocation. */
    private record CheckedTaskBatch(TaskBatchInput input, TaskBatchPreview expectedPreview) { }
    private final AccessRepository access;
    private final CrmTaskRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /**
     * Creates the task service with transactional persistence and command journaling.
     * @param access reloads and locks active callers for previews and mutations
     * @param repository checks target and employee eligibility and stores task facts
     * @param mutations stores command hashes, audit events and replay receipts
     * @param mapper serializes requests, previews and stored task results
     */
    public CrmTaskService(AccessRepository access, CrmTaskRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access; this.repository = repository; this.mutations = mutations; this.mapper = mapper;
    }
    /**
     * Lists linked child identity fields needed to prepare a writable customer's task.
     * @param actor caller requiring both {@code crm.task.write} and {@code crm.customer.read}
     * @param customerId nonblank customer public identifier of at most 120 characters, within customer write scope
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return distinct linked student identifiers, names, grades and relationships with total count
     * @throws BusinessException when permission, customer scope, identifier or pagination checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TaskTargetStudentPage targetStudents(Actor actor, String customerId, int page, int size) {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.write", "crm.customer.read");
        if (customerId == null || customerId.isBlank() || customerId.length() > 120
                || page < 1 || page > 100000 || size < 1 || size > 100) {
            invalid("客户编号或目标学生分页范围无效");
        }
        return repository.targetStudents(actor, customerId, page, size);
    }
    /**
     * Creates a task batch without requiring a reviewed preview; equivalent to the preview overload with null.
     * @param actor caller requiring {@code crm.task.write}; assigning others also requires {@code crm.task.assign},
     *     and an activity selection requires {@code crm.activity.read}
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input task definition, targets, deadline, owners and distribution strategy
     * @return created batch and eligible tasks with skipped targets, or the stored result for an identical retry
     * @throws BusinessException when active caller, permissions, scope, targets, assignments, deadline or key checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskBatchReceipt createBatch(Actor actor, String key, TaskBatchInput input)
            throws JsonProcessingException {
        return createBatch(actor, key, input, null);
    }

    /**
     * Creates a batch after current source, target and assignment authorization and optional preview comparison.
     * Replay follows authorization but precedes eligibility recalculation. New task writes, audit and receipt
     * are committed atomically; a changed reviewed plan rejects the whole command.
     * @param actor caller requiring {@code crm.task.write}, plus assign permission for other owners and activity
     *     read permission when an activity is selected; these grants are rechecked after active-principal reload
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input task definition with exactly one of customerIds or targets, 1–500 distinct targets,
     *     nonempty owners and a future deadline
     * @param expectedPreview exact previously reviewed plan, or null to create without preview comparison
     * @return created task batch receipt or the original stored receipt for an identical command
     * @throws BusinessException when authorization, key, references, eligibility or allocation checks fail,
     *     or the freshly computed plan differs from the supplied preview
     * @throws JsonProcessingException when command, preview or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskBatchReceipt createBatch(Actor actor, String key, TaskBatchInput input,
            TaskBatchPreview expectedPreview) throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.write"); requireKey(key);
        if (input == null || input.ownerIds() == null || input.ownerIds().isEmpty()) { invalid("任务负责人不能为空"); }
        if (requiresAssignment(actor, input)) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.assign"); }
        if (input.activityId() != null && !input.activityId().isBlank()) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        actor = access.lockActive(actor.id()); CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.write");
        if (requiresAssignment(actor, input)) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.assign"); }
        if (input.activityId() != null && !input.activityId().isBlank()) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        repository.authorizeBatchInput(actor, input);
        String command = mapper.writeValueAsString(expectedPreview == null ? input : new CheckedTaskBatch(input, expectedPreview));
        String hash = mutations.hash(command);
        String replay = mutations.replay(actor, key, "crm.task.batch.create", hash);
        if (replay != null) { return mapper.readValue(replay, TaskBatchReceipt.class); }
        TaskBatchReceipt result = expectedPreview == null ? repository.createBatch(actor, input)
            : repository.createBatch(actor, input, expectedPreview);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.task.batch.create", hash,
            mapper.writeValueAsString(result), result.batch().id()));
        return result;
    }
    /**
     * Calculates eligible and skipped targets and owner distribution without inserting tasks or a batch.
     * The active caller is locked and rechecked, and target planning may acquire database locks.
     * @param actor caller requiring task write, assign permission for other owners, and activity read when selected
     * @param input task definition, exactly one target representation, owner strategy and future deadline
     * @return current eligible/skipped targets and assignment counts, including a valid zero-eligible preview
     * @throws BusinessException when caller, permission, target/source visibility, identity, owner or planning rules fail
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskBatchPreview previewBatch(Actor actor, TaskBatchInput input) {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.write");
        if (requiresAssignment(actor, input)) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.assign"); }
        if (input != null && input.activityId() != null && !input.activityId().isBlank()) {
            CrmLegacyAccess.read(actor, "crm.activity.read");
        }
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.write");
        if (requiresAssignment(actor, input)) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.assign"); }
        if (input != null && input.activityId() != null && !input.activityId().isBlank()) {
            CrmLegacyAccess.read(actor, "crm.activity.read");
        }
        return repository.previewBatch(actor, input);
    }
    /**
     * Lists executable tasks without a batch or activity filter using the query overload.
     * @param actor caller requiring {@code crm.task.read}
     * @param status optional pending, in_progress, completed or cancelled status
     * @param taskType optional marketing or service type
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return scoped task page with source links projected under current permissions
     * @throws BusinessException when read permission, status, task type or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TaskPage list(Actor actor, String status, String taskType, int page, int size) {
        return list(actor, new CrmDtos.TaskQuery(status, taskType, null, null, page, size));
    }
    /**
     * Lists the authorized subset of tasks selected by execution scope and optional source filters.
     * Legacy tasks use owner/campus task scope; routed tasks require the caller to be their assigned employee.
     * @param actor caller requiring {@code crm.task.read}; an activity filter also requires {@code crm.activity.read}
     * @param query task state/type, optional nonblank batch/activity identifiers of at most 120 characters,
     *     page 1–100000 and size 1–100
     * @return scoped tasks with independently authorized activity and acquisition-source disclosure
     * @throws BusinessException when permissions, source visibility, filters or pagination checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TaskPage list(Actor actor, CrmDtos.TaskQuery query) {
        return list(actor, query, null);
    }
    /** Lists tasks using execution visibility and the supplied ordinary or imported-view filters.
     * @param actor caller with a modern task-read or exact legacy task-read grant
     * @param query task status/type, optional batch/activity identifiers, and bounded pagination
     * @param savedView optional crmTasks saved-view criteria checked against this caller
     * @return scoped task page; routed tasks retain their assigned-employee visibility rule
     * @throws BusinessException if read permissions, source identifiers, filters or pagination fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TaskPage list(Actor actor, CrmDtos.TaskQuery query, LegacySavedViewQuery savedView) {
        String status = query.status(), taskType = query.taskType(), batchId = query.batchId(), activityId = query.activityId();
        int page = query.page(), size = query.size();
        CrmLegacyAccess.read(actor, "crm.task.read");
        if (page < 1 || page > 100000 || size < 1 || size > 100
                || status != null && !status.isBlank() && !Set.of("pending", "in_progress", "completed", "cancelled").contains(status)
                || taskType != null && !taskType.isBlank() && !Set.of("marketing", "service").contains(taskType)) {
            invalid("任务筛选或分页范围无效");
        }
        validateSourceFilter(batchId); validateSourceFilter(activityId);
        if (activityId != null) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        if (savedView != null) { savedView.authorize(actor, "crmTasks"); }
        return repository.list(actor, query, savedView);
    }
    /**
     * Lists task-batch summaries without an activity filter using the query overload.
     * @param actor caller requiring {@code crm.task.read}
     * @param status optional active, completed or cancelled batch status
     * @param taskType optional marketing or service type
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return batches represented by visible tasks, with task progress counts restricted to that same scope
     * @throws BusinessException when read permission, filters or pagination checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TaskBatchPage listBatches(Actor actor,String status,String taskType,int page,int size) {
        return listBatches(actor, new CrmDtos.TaskBatchQuery(status, taskType, null, page, size));
    }
    /**
     * Lists batch progress calculated only from tasks currently visible to the caller.
     * @param actor caller requiring {@code crm.task.read}; an activity filter also requires {@code crm.activity.read}
     * @param query optional active/completed/cancelled status, marketing/service type and activity identifier,
     *     page 1–100000 and size 1–100
     * @return authorized batch summaries, scoped progress counts and permitted activity links
     * @throws BusinessException when permission, activity visibility, filters or pagination checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public TaskBatchPage listBatches(Actor actor, CrmDtos.TaskBatchQuery query) {
        String status = query.status(), taskType = query.taskType(), activityId = query.activityId();
        int page = query.page(), size = query.size();
        CrmLegacyAccess.read(actor, "crm.task.read");
        if(page<1||page>100000||size<1||size>100
                ||status!=null&&!status.isBlank()&&!Set.of("active","completed","cancelled").contains(status)
                ||taskType!=null&&!taskType.isBlank()&&!Set.of("marketing","service").contains(taskType)) {
            invalid("任务批次筛选或分页范围无效");
        }
        validateSourceFilter(activityId);
        if (activityId != null) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        return activityId == null ? repository.listBatches(actor,status,taskType,page,size)
            : repository.listBatches(actor, query);
    }
    /**
     * Completes a pending or in-progress task at its expected version with a type-specific outcome.
     * Qualified marketing outcomes require a compatible same-customer opportunity and explicit need checks.
     * Routed tasks can be executed only by their current assignee after the start time. Retries reauthorize
     * scope and redact source evidence while retaining the original completion result.
     * @param actor caller requiring {@code crm.task.write}, and {@code crm.opportunity.read} for qualified outcomes
     * @param taskId task public identifier within current execution scope
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input expected version, result, note and any opportunity/need-resolution fields required by that result
     * @return completed task or the stored completion result with current source disclosure
     * @throws BusinessException when active caller, permission, execution scope/start time, key, version, status,
     *     result, opportunity or need compatibility checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmTask complete(Actor actor, String taskId, String key, TaskCompletion input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.create", "crm.task.write"); requireKey(key);
        if (input.result() != null && input.result().equals("qualified")) { CrmLegacyAccess.read(actor, "crm.opportunity.read"); }
        repository.lockMutationBatch(taskId);
        actor = access.lockActive(actor.id()); CrmLegacyAccess.operation(actor, "lead.create", "crm.task.write");
        if (input.result() != null && input.result().equals("qualified")) { CrmLegacyAccess.read(actor, "crm.opportunity.read"); }
        repository.authorizeMutation(actor, taskId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(taskId, input)));
        String replay = mutations.replay(actor, key, "crm.task.complete", hash);
        if (replay != null) { return repository.redactSources(actor, mapper.readValue(replay, CrmTask.class)); }
        CrmTask result = repository.complete(actor, taskId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.task.complete", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }
    /**
     * Changes an open task at its expected version and records an event in the same transaction.
     * Reassignment, deadline changes and cancellation require assign permission and a reason. Routed tasks
     * must use supervisor management for those changes; employee execution remains assignee-only after start.
     * Retries reauthorize the task and reproject source disclosure without replacing original result facts.
     * @param actor caller requiring {@code crm.task.write}, plus {@code crm.task.assign} for owner/deadline/cancel fields
     * @param taskId task public identifier within current execution scope
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input expected version and requested owner, future deadline or allowed status, with adjustment reason
     * @return updated task or its original stored result with current source disclosure
     * @throws BusinessException when active caller, permissions, scope, key, version, open status, reason,
     *     target employee, deadline or routed-task execution rules reject the change
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmTask update(Actor actor, String taskId, String key, TaskUpdate input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.create", "crm.task.write"); requireKey(key);
        boolean assignmentPermission = input.ownerId() != null && !input.ownerId().isBlank()
            || input.dueAt() != null || "cancelled".equals(input.status());
        if (assignmentPermission) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.assign"); }
        repository.lockMutationBatch(taskId);
        actor = access.lockActive(actor.id()); CrmLegacyAccess.operation(actor, "lead.create", "crm.task.write");
        if (assignmentPermission) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.assign"); }
        repository.authorizeMutation(actor, taskId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(taskId, input)));
        String replay = mutations.replay(actor, key, "crm.task.update", hash);
        if (replay != null) { return repository.redactSources(actor, mapper.readValue(replay, CrmTask.class)); }
        CrmTask result = repository.update(actor, taskId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.task.update", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }
    private static void validateSourceFilter(String value) {
        if (value != null && (value.isBlank() || value.length() > 120)) { invalid("任务来源筛选无效"); }
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }
    private static boolean requiresAssignment(Actor actor, TaskBatchInput input) {
        return input != null && input.ownerIds() != null && input.ownerIds().stream().anyMatch(id -> !id.equals(actor.publicId()));
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
