package cn.siyue.core;

import cn.siyue.core.CrmDtos.Lead;
import cn.siyue.core.CrmDtos.LeadOwner;
import cn.siyue.core.LeadRoutingDtos.BatchPreview;
import cn.siyue.core.LeadRoutingDtos.ApplyInput;
import cn.siyue.core.LeadRoutingDtos.ApplyResult;
import cn.siyue.core.LeadRoutingDtos.QueueEntry;
import cn.siyue.core.LeadRoutingDtos.QueuePage;
import cn.siyue.core.LeadRoutingDtos.RoutingPlan;
import cn.siyue.core.LeadRoutingDtos.RoutingRule;
import cn.siyue.core.LeadRoutingDtos.RuleCreate;
import cn.siyue.core.LeadRoutingDtos.RulePage;
import cn.siyue.core.LeadRoutingDtos.RuleHistoryPage;
import cn.siyue.core.LeadRoutingDtos.RuleUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages campus lead-routing rules, simulations and unmatched queues without external communications.
 * Every endpoint requires lead assignment permission and all/campus scope. Writes reload and lock the
 * active principal and commit rule history or assignment facts with audit and retry receipts. Reads use
 * the supplied actor in read-only transactions; all declared transactions roll back for checked exceptions.
 */
@Service
public class LeadRoutingService {
    private final AccessRepository access;
    private final LeadRoutingRepository routing;
    private final LeadRepository leads;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /**
     * Creates the lead-routing transaction boundary.
     * @param access reloads and locks active callers before mutations
     * @param routing checks rule/lead scope, selects eligible owners and stores rules, history and queue events
     * @param leads performs versioned lead assignment and its associated business updates
     * @param mutations stores command hashes, audit events and replay receipts
     * @param mapper serializes rule commands, history changes and stored results
     */
    public LeadRoutingService(AccessRepository access, LeadRoutingRepository routing,
            LeadRepository leads, MutationRepository mutations, ObjectMapper mapper) {
        this.access = access; this.routing = routing; this.leads = leads;
        this.mutations = mutations; this.mapper = mapper;
    }
    /**
     * Lists nondeleted routing rules ordered by priority and identifier under campus management scope.
     * @param actor caller requiring {@code crm.lead.assign} and all or campus data scope
     * @param campusId optional campus public identifier used to narrow the authorized result
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return scoped routing rules and total count
     * @throws BusinessException when assignment permission, management scope or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public RulePage rules(Actor actor, String campusId, int page, int size) {
        manager(actor);
        if (page < 1 || page > 100000 || size < 1 || size > 100) { invalid("规则分页范围无效"); }
        return routing.listRules(actor, campusId, page, size);
    }
    /**
     * Reads immutable rule history after checking current access to the rule's campus.
     * @param actor caller requiring {@code crm.lead.assign} and all or campus data scope
     * @param ruleId rule public identifier
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return chronological events with recorded actor, source event, reason and change details
     * @throws BusinessException when permission or pagination fails, or the rule is missing or outside scope
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public RuleHistoryPage ruleHistory(Actor actor, String ruleId, int page, int size) {
        manager(actor);
        if (page < 1 || page > 100000 || size < 1 || size > 100) { invalid("规则历史分页范围无效"); }
        return routing.ruleHistory(actor, ruleId, page, size);
    }
    /**
     * Creates a routing rule, its history event and the idempotency receipt atomically.
     * Active caller permission, campus, optional activity and receiver eligibility are checked before replay.
     * The receiver must be active, have lead follow permission and belong to the rule's campus.
     * @param actor caller requiring {@code crm.lead.assign} and all/campus scope; selecting an activity also
     *     requires {@code crm.activity.read}
     * @param key idempotency key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input rule name, active campus, optional activity/subject/source filters, eligible owner, priority and active flag
     * @return created rule or the original stored rule for an identical retry
     * @throws BusinessException when the caller is inactive, permission or scope fails, references are unavailable,
     *     the activity is from another campus, the receiver is ineligible, or the key is invalid or conflicts
     * @throws JsonProcessingException when the request, history payload or receipt cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public RoutingRule createRule(Actor actor, String key, RuleCreate input) throws JsonProcessingException {
        manager(actor); requireKey(key); activityPermission(actor, input.activityId());
        actor = access.lockActive(actor.id()); manager(actor); activityPermission(actor, input.activityId());
        routing.authorizeCreateRule(actor, input);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.routing-rule.create", hash);
        if (replay != null) { return mapper.readValue(replay, RoutingRule.class); }
        RoutingRule rule = routing.create(actor, input);
        routing.appendRuleEvent(actor, rule.id(), "create", mapper.writeValueAsString(input));
        complete(actor, new MutationRepository.RetryWrite(key, "crm.routing-rule.create", hash,
            mapper.writeValueAsString(rule), rule.id()));
        return rule;
    }
    /**
     * Updates a rule at its expected version without changing its campus and appends a history event.
     * Active manager permission, rule visibility and any required activity-read grant are checked before replay;
     * new writes additionally resolve the activity and eligible receiver for the rule's fixed campus.
     * @param actor caller requiring {@code crm.lead.assign} and all/campus scope, plus activity read when selected
     * @param id rule public identifier
     * @param key idempotency key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input replacement rule fields and expected current version
     * @return updated rule with incremented version, or the original receipt for an identical retry
     * @throws BusinessException when caller, permissions, key, rule scope, expected version or activity/owner eligibility fails
     * @throws JsonProcessingException when the command, history payload or receipt cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public RoutingRule updateRule(Actor actor, String id, String key, RuleUpdate input)
            throws JsonProcessingException {
        manager(actor); requireKey(key); activityPermission(actor, input.activityId());
        actor = access.lockActive(actor.id()); manager(actor); routing.authorizeRule(actor, id);
        activityPermission(actor, input.activityId());
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, input)));
        String replay = mutations.replay(actor, key, "crm.routing-rule.update", hash);
        if (replay != null) { return mapper.readValue(replay, RoutingRule.class); }
        RoutingRule rule = routing.update(actor, id, input);
        routing.appendRuleEvent(actor, rule.id(), "update", mapper.writeValueAsString(input));
        complete(actor, new MutationRepository.RetryWrite(key, "crm.routing-rule.update", hash,
            mapper.writeValueAsString(rule), rule.id()));
        return rule;
    }
    /**
     * Simulates matching enabled rules for a visible unassigned lead without modifying ownership or queue state.
     * Rules are examined by priority then identifier; ineligible receivers remain in the candidate explanation.
     * @param actor caller requiring {@code crm.lead.assign} and all/campus scope
     * @param leadId unassigned lead public identifier within current lead scope
     * @return first eligible rule/owner and candidate reasons, or an unmatched plan
     * @throws BusinessException when permission or scope fails, the lead is unavailable, or it already has an owner
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public RoutingPlan simulate(Actor actor, String leadId) {
        manager(actor);
        LeadRoutingRepository.RouteLead lead = routing.lead(actor, leadId, false);
        if (lead.ownerId() != null) { invalid("只能模拟未分配线索"); }
        return routing.simulate(lead);
    }
    /**
     * Simulates each selected new public-pool lead at its expected version without applying assignments.
     * @param actor caller requiring {@code crm.lead.assign} and all/campus scope
     * @param input validated list of distinct lead identifiers and expected versions
     * @return plans in input order with assignable and unmatched counts
     * @throws BusinessException when permission, lead visibility, duplicate selection, public-pool status or version checks fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public BatchPreview preview(Actor actor, ApplyInput input) {
        manager(actor);
        uniqueLeadIds(input);
        List<RoutingPlan> plans = new ArrayList<>();
        int assignable = 0;
        for (LeadRoutingDtos.LeadVersion item : input.leads()) {
            LeadRoutingRepository.RouteLead lead = routing.lead(actor, item.leadId(), false);
            requirePublicPool(lead, item.expectedVersion());
            RoutingPlan plan = routing.simulate(lead);
            plans.add(plan);
            if (plan.ownerId() != null) { assignable++; }
        }
        return new BatchPreview(List.copyOf(plans), assignable, plans.size() - assignable);
    }
    /**
     * Applies current routing rules atomically to the selected new public-pool leads.
     * The active manager and visibility of every selected lead are checked before replay. A new execution
     * locks leads in identifier order, checks versions, assigns eligible matches and creates or refreshes
     * pending queue entries for unmatched leads; it does not compare against a prior simulation.
     * @param actor caller requiring {@code crm.lead.assign} and all/campus data scope
     * @param key idempotency key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input validated distinct lead identifiers and their expected current versions
     * @return assigned lead records and unmatched queue entries, or the original stored result for an identical retry
     * @throws BusinessException when active caller, permissions, key, selected-lead scope, duplicate IDs,
     *     public-pool status, versions or assignment eligibility checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplyResult apply(Actor actor, String key, ApplyInput input) throws JsonProcessingException {
        manager(actor); requireKey(key);
        uniqueLeadIds(input);
        actor = access.lockActive(actor.id()); manager(actor);
        for (LeadRoutingDtos.LeadVersion item : input.leads()) {
            routing.lead(actor, item.leadId(), false);
        }
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.routing.apply", hash);
        if (replay != null) { return mapper.readValue(replay, ApplyResult.class); }
        List<ApplyInputRow> ordered = input.leads().stream().map(row -> new ApplyInputRow(row.leadId(), row.expectedVersion()))
            .sorted(java.util.Comparator.comparing(ApplyInputRow::leadId)).toList();
        List<Lead> assigned = new ArrayList<>();
        List<QueueEntry> unmatched = new ArrayList<>();
        for (ApplyInputRow item : ordered) {
            LeadRoutingRepository.RouteLead lead = routing.lead(actor, item.leadId(), true);
            requirePublicPool(lead, item.expectedVersion());
            RoutingPlan plan = routing.simulate(lead);
            if (plan.ownerId() == null) {
                unmatched.add(routing.unmatched(actor, lead, plan.reason()));
            } else {
                Lead updated = leads.assign(actor, lead.publicId(), new LeadOwner(plan.ownerId(), plan.reason(), lead.version()));
                routing.completed(actor, lead, plan.matchedRuleId(), plan.reason());
                assigned.add(updated);
            }
        }
        ApplyResult result = new ApplyResult(List.copyOf(assigned), List.copyOf(unmatched));
        complete(actor, new MutationRepository.RetryWrite(key, "crm.routing.apply", hash,
            mapper.writeValueAsString(result), "lead-routing-batch"));
        return result;
    }
    /**
     * Lists pending unmatched routing entries whose leads remain unassigned and visible.
     * @param actor caller requiring {@code crm.lead.assign} and all/campus data scope
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return scoped queue entries ordered by creation time and identifier, with total count
     * @throws BusinessException when assignment permission, management scope or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public QueuePage queue(Actor actor, int page, int size) {
        manager(actor);
        if (page < 1 || page > 100000 || size < 1 || size > 100) { invalid("队列分页范围无效"); }
        return routing.queue(actor, page, size);
    }
    private void requirePublicPool(LeadRoutingRepository.RouteLead lead, long expectedVersion) {
        if (lead.ownerId() != null || !"new".equals(lead.status())) { invalid("只能分配公海中的新线索"); }
        if (lead.version() != expectedVersion) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索版本已变化，请刷新后重试"); }
    }
    private void uniqueLeadIds(ApplyInput input) {
        List<String> ids = input.leads().stream().map(row -> row.leadId()).toList();
        if (new HashSet<>(ids).size() != ids.size()) { invalid("批次中不能重复选择线索"); }
    }
    private Actor manager(Actor actor) {
        actor.require("crm.lead.assign");
        if (!Set.of("all", "campus").contains(actor.scope())) { throw new BusinessException(
            ErrorCode.FORBIDDEN, "自动分配仅供全局或校区管理范围使用"); }
        return actor;
    }
    private void activityPermission(Actor actor, String activityId) {
        if (activityId != null && !activityId.isBlank()) { actor.require("crm.activity.read"); }
    }
    private void complete(Actor actor, MutationRepository.RetryWrite write) { mutations.complete(actor, write); }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) { invalid("请提供有效幂等键"); }
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
    private record ApplyInputRow(String leadId, long expectedVersion) { }
}
