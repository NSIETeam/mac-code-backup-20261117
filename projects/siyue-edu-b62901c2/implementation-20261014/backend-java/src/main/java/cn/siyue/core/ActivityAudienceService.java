package cn.siyue.core;

import cn.siyue.core.ActivityAudienceDtos.Page;
import cn.siyue.core.ActivityAudienceDtos.Query;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the acquisition evidence available for the actor's task creation scope. */
@Service
public class ActivityAudienceService {
    private final ActivityAudienceRepository repository;
    /**
     * Connects activity audience queries to scoped acquisition-evidence persistence.
     * @param repository resolves visible activity, customer and source evidence rows
     */
    public ActivityAudienceService(ActivityAudienceRepository repository) { this.repository = repository; }
    /**
     * Lists activity acquisition evidence usable for task creation.
     * Requires the exact legacy lead.assign grant or all three Java activity-read, task-write and
     * customer-read grants. Explicit source filters require source-read access unless lead.assign
     * is held; a non-all task-state filter requires Java or legacy task-read access.
     * @param actor authenticated caller carrying the permissions used by these queries
     * @param activityId nonblank public activity identifier of at most 120 characters
     * @param query optional customer/source/time/state filters and bounded one-based pagination
     * @return page of evidence restricted by repository activity and customer visibility
     * @throws BusinessException if permissions, filters, time range or pagination are invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Page list(Actor actor, String activityId, Query query) {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.activity.read", "crm.task.write", "crm.customer.read");
        validateQuery(activityId, query);
        if (query.sourceType() != null) { if (!actor.permissions().contains("lead.assign")) {
            CrmLegacyAccess.read(actor, "lead".equals(query.sourceType()) ? "crm.lead.read" : "crm.touchpoint.read");
        } }
        if (query.taskState() != null && !"all".equals(query.taskState())) { CrmLegacyAccess.read(actor, "crm.task.read"); }
        return repository.list(actor, activityId, query);
    }
    private void validateQuery(String activityId, Query query) {
        if (activityId == null || activityId.isBlank() || activityId.length() > 120 || query == null) {
            invalid();
        }
        validateBounds(query);
        validateChoices(query);
    }
    private void validateBounds(Query query) {
        if (query.customerId() != null && (query.customerId().isBlank() || query.customerId().length() > 120)
                || query.page() < 1 || query.page() > 100000 || query.size() < 1 || query.size() > 100) { invalid(); }
        if (query.fromAt() != null && query.untilAt() != null && !query.untilAt().isAfter(query.fromAt())) { invalid(); }
    }
    private void validateChoices(Query query) {
        if (query.sourceType() != null && !Set.of("lead", "touchpoint").contains(query.sourceType())
                || query.taskState() != null && !Set.of("all", "with_evidence", "without_evidence").contains(query.taskState())) { invalid(); }
    }
    private void invalid() { throw new BusinessException(ErrorCode.INVALID_INPUT, "活动来源筛选或分页范围无效"); }
}
