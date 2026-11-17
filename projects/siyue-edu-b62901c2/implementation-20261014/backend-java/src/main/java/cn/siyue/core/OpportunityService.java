package cn.siyue.core;

import cn.siyue.core.CrmDtos.Opportunity;
import cn.siyue.core.CrmDtos.OpportunityEvent;
import cn.siyue.core.CrmDtos.OpportunityFollowup;
import cn.siyue.core.CrmDtos.OpportunityInput;
import cn.siyue.core.CrmDtos.OpportunityPage;
import cn.siyue.core.CrmDtos.OpportunityStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns authorization, optimistic writes and idempotent retries for the sales opportunity lifecycle.
 * @see OpportunityRepository
 * @see MutationRepository
 */
@Service
public class OpportunityService {
    private final AccessRepository access;
    private final OpportunityRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Uses the shared account lock, scoped repository, and audit/idempotency store.
     * @param access repository for reloading and locking the active actor
     * @param repository scoped opportunity persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public OpportunityService(AccessRepository access, OpportunityRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access; this.repository = repository; this.mutations = mutations; this.mapper = mapper;
    }
    /** Creates one independent need, with no implicit task completion or sales-channel delivery.
     * Requires opportunity-write and customer-read grants, plus read grants for any linked source records.
     * @param actor authenticated actor requesting creation
     * @param key stable idempotency key
     * @param input opportunity fields and optional linked lead/activity/student identifiers
     * @return created opportunity or the stored result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, linked-record scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Opportunity create(Actor actor, String key, OpportunityInput input) throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write", "crm.customer.read"); requireKey(key);
        requireText(input.subject(), "意向学科", 100); requireText(input.term(), "意向学期", 100);
        requireText(input.title(), "商机名称", 200);
        if (input.detail() != null && input.detail().length() > 5000) { invalid("商机说明过长"); }
        requireLinkedRecordPermissions(actor,input);
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write", "crm.customer.read");
        requireLinkedRecordPermissions(actor,input);
        repository.authorizeInput(actor, input);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.opportunity.create", hash);
        if (replay != null) { return mapper.readValue(replay, Opportunity.class); }
        repository.requireLegacyCreateEnabled();
        Opportunity result = repository.create(actor, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.opportunity.create", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }
    private void requireLinkedRecordPermissions(Actor actor,OpportunityInput input) {
        if (hasValue(input.sourceLeadId())) { CrmLegacyAccess.read(actor, "crm.lead.read"); }
        if (hasValue(input.activityId())) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        if (hasValue(input.studentId())) { actor.require("student.read"); }
    }
    private boolean hasValue(String value) { return value != null && !value.isBlank(); }
    /** Lists a bounded page with matching count and detail-query filters.
     * Requires opportunity-read permission; a customer filter additionally requires customer-read permission.
     * @param actor authenticated actor whose opportunity scope is applied
     * @param customerId optional public customer identifier filter
     * @param stage optional opportunity stage filter
     * @param page one-based page number
     * @param size requested page size, from 1 through 100
     * @return scoped opportunity page and matching total
     * @throws BusinessException if permissions, stage, or pagination checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OpportunityPage list(Actor actor, String customerId, String stage, int page, int size) {
        return list(actor, new OpportunityListFilters(customerId, stage, page, size), null);
    }
    /** Reads opportunities through their current owner, campus or legacy customer/creator relations.
     * @param actor caller requiring modern opportunity-read or an exact legacy alternative
     * @param filters optional customer and stage filters, page 1–100000 and size 1–100
     * @param savedView optional opportunities saved-view filters authorized before querying
     * @return matching visible opportunities and total; no ownership mutation is performed
     * @throws BusinessException if resource or customer-filter permissions, stage, pagination or view authorization fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OpportunityPage list(Actor actor, OpportunityListFilters filters, LegacySavedViewQuery savedView) {
        CrmLegacyAccess.read(actor, "crm.opportunity.read");
        if (filters.page() < 1 || filters.page() > 100000 || filters.size() < 1 || filters.size() > 100
                || filters.stage() != null && !filters.stage().isBlank()
                && !Set.of("qualified", "appointment", "trial", "negotiation", "won", "closed").contains(filters.stage())) {
            invalid("商机筛选或分页范围无效");
        }
        if (filters.customerId() != null && !filters.customerId().isBlank()) { CrmLegacyAccess.read(actor, "crm.customer.read"); }
        if (savedView != null) { savedView.authorize(actor, "opportunities"); }
        return repository.list(actor, filters, savedView);
    }
    /** Returns the most recent append-only interactions and stage changes.
     * Requires opportunity-read permission and applies repository scope to the selected opportunity.
     * @param actor authenticated actor requesting the event history
     * @param opportunityId public opportunity identifier
     * @return recent visible opportunity events
     * @throws BusinessException if permission or opportunity scope checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<OpportunityEvent> events(Actor actor, String opportunityId) {
        CrmLegacyAccess.read(actor, "crm.opportunity.read");
        return repository.events(actor, opportunityId);
    }
    /** Appends an interaction note and follow-up date using the version reviewed by the operator.
     * Requires opportunity-write permission; the reviewed follow-up date is validated after an idempotent replay
     * check so a confirmed retry retains its original result.
     * @param actor authenticated actor recording the follow-up
     * @param opportunityId public opportunity identifier
     * @param key stable idempotency key
     * @param input follow-up details and expected opportunity version
     * @return updated opportunity or the stored result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, scope, validation, version, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Opportunity followup(Actor actor, String opportunityId, String key, OpportunityFollowup input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); requireKey(key);
        requireText(input.detail(), "跟进内容", 5000);
        actor = access.lockActive(actor.id()); CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write");
        repository.authorize(actor, opportunityId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(opportunityId, input)));
        String replay = mutations.replay(actor, key, "crm.opportunity.followup", hash);
        if (replay != null) { return mapper.readValue(replay, Opportunity.class); }
        // A confirmed retry keeps its original appointment even after that time has passed.
        validateFuture(input.nextFollowAt());
        Opportunity result = repository.followup(actor, opportunityId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.opportunity.followup", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }
    /** Moves or closes the opportunity; a won stage can only be written by a receipt workflow.
     * Requires opportunity-write permission and rejects {@code won} so actual receipt workflows remain the source
     * of sales conversion.
     * @param actor authenticated actor requesting the stage change
     * @param opportunityId public opportunity identifier
     * @param key stable idempotency key
     * @param input allowed stage, optional next follow-up time, and expected version
     * @return updated opportunity or the stored result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, scope, stage, validation, version, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Opportunity advance(Actor actor, String opportunityId, String key, OpportunityStage input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); requireKey(key);
        if (input == null || !Set.of("qualified", "appointment", "trial", "negotiation", "closed").contains(input.stage())) {
            invalid("成交阶段只能由实际收款确认，手工阶段无效");
        }
        actor = access.lockActive(actor.id()); CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write");
        repository.authorize(actor, opportunityId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(opportunityId, input)));
        String replay = mutations.replay(actor, key, "crm.opportunity.stage", hash);
        if (replay != null) { return mapper.readValue(replay, Opportunity.class); }
        validateFuture(input.nextFollowAt());
        Opportunity result = repository.advance(actor, opportunityId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.opportunity.stage", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }
    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) { invalid(field + "不能为空或过长"); }
    }
    private static void validateFuture(Instant value) {
        if (value != null && !value.isAfter(Instant.now())) { invalid("下次跟进时间须晚于当前时间"); }
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
