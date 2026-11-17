package cn.siyue.core;

import cn.siyue.core.CrmTaskReadDtos.AssigneePage;
import cn.siyue.core.CrmTaskReadDtos.Context;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business-specific directory and task context, independent of account administration. */
@Service
public class CrmTaskReadService {
    private final CrmTaskAssignmentRepository assignments;
    private final CrmTaskReadRepository reads;
    private final CrmTaskRepository tasks;
    /**
     * Connects task-specific candidate and context queries to scoped repositories.
     * @param assignments returns employees eligible for the selected task type and campus
     * @param reads loads task context and related opportunity summaries
     * @param tasks checks visibility of the referenced task
     */
    public CrmTaskReadService(CrmTaskAssignmentRepository assignments, CrmTaskReadRepository reads,
            CrmTaskRepository tasks) { this.assignments = assignments; this.reads = reads; this.tasks = tasks; }

    /**
     * Lists eligible task assignees without exposing account-administration operations.
     * @param actor authenticated caller with crm.task.write or the original lead.assign operation grant
     * @param input marketing or service task type, optional campus/search and bounded pagination
     * @return eligible employee page in the caller's assignment scope
     * @throws BusinessException if permission is absent or filters and pagination are invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AssigneePage assignees(Actor actor, CrmTaskReadDtos.AssigneeQuery input) {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.write");
        if (input == null) { invalid("候选员工筛选不能为空"); }
        String campusId = input.campusId(), taskType = input.taskType(), query = input.query();
        int page = input.page(), size = input.size();
        if (!Set.of("marketing", "service").contains(taskType == null ? "" : taskType)
                || page < 1 || page > 100000 || size < 1 || size > 100
                || campusId != null && campusId.length() > 120 || query != null && query.length() > 120) {
            invalid("候选员工筛选或分页无效");
        }
        return assignments.list(actor, new CrmTaskReadDtos.AssigneeQuery(optional(campusId), taskType,
            optional(query), page, size));
    }

    /**
     * Reads the context of one task after checking its visibility.
     * @param actor authenticated caller with crm.task.read or original lead.read, lead.follow or lead.assign
     * @param taskId nonblank public task identifier of at most 120 characters
     * @return related task context restricted by repository visibility rules
     * @throws BusinessException if permission, identifier or task visibility is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Context context(Actor actor, String taskId) {
        CrmLegacyAccess.read(actor, "crm.task.read");
        if (taskId == null || taskId.isBlank() || taskId.length() > 120) { invalid("任务编号无效"); }
        return reads.context(actor, tasks.get(actor, taskId));
    }
    /**
     * Lists opportunities available from a visible task after independently checking both resource read rules.
     * Task reads accept crm.task.read or lead.read/lead.follow/lead.assign; opportunity reads accept
     * crm.opportunity.read or lead.read/lead.create/lead.follow. Repository visibility remains required.
     * @param actor authenticated caller satisfying the task and opportunity read alternatives separately
     * @param taskId nonblank public task identifier of at most 120 characters
     * @param page one-based page, from 1 through 100000
     * @param size number of records, from 1 through 100
     * @return scoped page of opportunities related to the task
     * @throws BusinessException if permissions, task visibility or pagination are invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CrmTaskReadDtos.OpportunityPage opportunities(Actor actor, String taskId, int page, int size) {
        CrmLegacyAccess.read(actor, "crm.task.read"); CrmLegacyAccess.read(actor, "crm.opportunity.read");
        if (taskId == null || taskId.isBlank() || taskId.length() > 120
                || page < 1 || page > 100000 || size < 1 || size > 100) { invalid("任务或分页范围无效"); }
        tasks.authorize(actor, taskId);
        return reads.opportunities(actor, taskId, page, size);
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
