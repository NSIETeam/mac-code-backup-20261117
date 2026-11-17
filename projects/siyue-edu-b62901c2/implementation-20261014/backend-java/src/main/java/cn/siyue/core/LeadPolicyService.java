package cn.siyue.core;

import cn.siyue.core.CrmDtos.LeadRelease;
import cn.siyue.core.LeadPolicyDtos.ApplyInput;
import cn.siyue.core.LeadPolicyDtos.ApplyResult;
import cn.siyue.core.LeadPolicyDtos.Candidate;
import cn.siyue.core.LeadPolicyDtos.LeadVersion;
import cn.siyue.core.LeadPolicyDtos.Policy;
import cn.siyue.core.LeadPolicyDtos.PolicyInput;
import cn.siyue.core.LeadPolicyDtos.Preview;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and changes the lead SLA policy and applies its versioned warning/reclaim decisions.
 * Policy writes require a previewed candidate set, stable lead ordering, the current policy version, and an audit receipt.
 */
@Service
public class LeadPolicyService {
    private final AccessRepository access;
    private final LeadPolicyRepository repository;
    private final LeadRepository leads;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Creates the policy workflow with actor locking, policy/candidate storage, lead release, and retry support.
     * @param access active-principal lookup and lock for write-time permission rechecks
     * @param repository policy and candidate persistence, including lock and audit facts
     * @param leads lead release operation used for approved reclaim candidates
     * @param mutations idempotency receipt and audit persistence
     * @param mapper serializer used to hash requests and persist replay results
     */
    public LeadPolicyService(AccessRepository access, LeadPolicyRepository repository, LeadRepository leads,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.leads = leads;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Reads the current lead SLA policy under a CRM-read or exact legacy policy grant.
     * @param actor caller requiring crm.lead.read, leadPolicy.preview or leadPolicy.publish
     * @return the current thresholds and policy version
     * @throws BusinessException when the caller lacks lead-read access
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
public Policy get(Actor actor) {
        actor.requireAny("crm.lead.read", "leadPolicy.preview", "leadPolicy.publish");
        return repository.get();
    }

    /** Replaces policy thresholds using optimistic versioning and an idempotent audit receipt.
     * @param actor global policy administrator requiring leadPolicy.publish, or both crm.lead.assign and
     *              crm.lead.policy.manage; scope and permissions are rechecked after locking
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input reserve/reclaim/follow thresholds and expected current policy version; omitted decision hours
     *              preserve the current value
     * @return updated policy, or the saved result for an identical retry
     * @throws BusinessException when permission, scope, key, or policy-version validation fails
     * @throws JsonProcessingException when request hashing or replay-result serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
public Policy update(Actor actor, String key, PolicyInput input) throws JsonProcessingException {
        policyWriteManager(actor);
        if (!"all".equals(actor.scope())) { throw new BusinessException(ErrorCode.FORBIDDEN, "线索策略只允许全局管理"); }
        actor.requireAny("crm.lead.policy.manage", "leadPolicy.publish");
        requireKey(key);
        actor = access.lockActive(actor.id());
        policyWriteManager(actor);
        if (!"all".equals(actor.scope())) { throw new BusinessException(ErrorCode.FORBIDDEN, "线索策略只允许全局管理"); }
        actor.requireAny("crm.lead.policy.manage", "leadPolicy.publish");
        Policy current = repository.lockPolicy();
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.lead.policy.update", hash);
        if (replay != null) { return mapper.readValue(replay, Policy.class); }
        if (current.version() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索策略版本已变化，请刷新后重试");
        }
        PolicyInput effective = input.decisionHours() == null
            ? new PolicyInput(input.reserveDays(), input.reclaimDays(), input.firstFollowHours(),
                current.decisionHours(), input.expectedVersion()) : input;
        Policy updated = repository.update(actor, effective);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.policy.update", hash,
            mapper.writeValueAsString(updated), "lead-policy"));
        return updated;
    }

    /** Previews current warning and reclaim candidates without changing lead state.
     * @param actor global or campus manager requiring leadPolicy.preview, or both crm.lead.assign and crm.customer.read
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return current policy and bounded candidate page with the total count
     * @throws BusinessException when permission, scope, or pagination validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
public Preview preview(Actor actor, int page, int size) {
        policyPreviewManager(actor);
        actor.requireAny("crm.customer.read", "leadPolicy.preview");
        validatePage(page, size);
        Policy policy = repository.get();
        LeadPolicyRepository.Page<Candidate> result = repository.candidates(
            new LeadPolicyRepository.CandidateQuery(actor, policy, Instant.now(), null, page, size));
        return new Preview(policy, result.items(), result.total(), page, size);
    }

    /** Applies selected preview decisions only while policy and each candidate version/type still match.
     * Reclaim candidates are released to the lead pool; warning candidates receive a warning event.
     * @param actor global or campus manager requiring crm.lead.assign or lead.assign, plus leadPolicy.publish
     *              or both crm.customer.read and crm.lead.policy.manage; rechecked after locking the active principal
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input current policy version and at most 100 unique lead IDs with expected versions and decision kinds
     * @return warning events and released leads, or the saved result for an identical retry
     * @throws BusinessException when permission, scope, key, selection, policy version, or preview state is invalid
     * @throws JsonProcessingException when request hashing or replay-result serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
public ApplyResult apply(Actor actor, String key, ApplyInput input) throws JsonProcessingException {
        policyApplyManager(actor);
        actor.requireAny("crm.customer.read", "leadPolicy.publish");
        actor.requireAny("crm.lead.policy.manage", "leadPolicy.publish");
        requireKey(key);
        uniqueLeads(input.leads());
        actor = access.lockActive(actor.id());
        policyApplyManager(actor);
        actor.requireAny("crm.customer.read", "leadPolicy.publish");
        actor.requireAny("crm.lead.policy.manage", "leadPolicy.publish");
        Policy policy = repository.lockPolicy();
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.lead.policy.apply", hash);
        if (replay != null) { return mapper.readValue(replay, ApplyResult.class); }
        if (policy.version() != input.policyVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索策略已更新，请重新预览");
        }
        List<LeadVersion> ordered = input.leads().stream().sorted(java.util.Comparator.comparing(LeadVersion::leadId)).toList();
        for (LeadVersion item : ordered) { repository.lockLead(actor, item.leadId()); }
        List<String> ids = ordered.stream().map(LeadVersion::leadId).toList();
        var current = repository.candidates(
            new LeadPolicyRepository.CandidateQuery(actor, policy, Instant.now(), ids, 1, 100));
        verifyPreview(ordered, current.items(), current.total());
        ApplyResult result = persist(actor, key, hash, policy, current.items());
        return result;
    }

    private ApplyResult persist(Actor actor, String key, String hash, Policy policy, List<Candidate> candidates)
            throws JsonProcessingException {
        List<Candidate> warnings = new ArrayList<>();
        List<CrmDtos.Lead> recycled = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if ("warning".equals(candidate.kind())) {
                repository.recordWarning(actor, candidate, policy.version());
                warnings.add(candidate);
            } else {
                repository.recordRecycle(actor, candidate, policy.version());
                recycled.add(leads.release(actor, candidate.leadId(), new LeadRelease(
                    "策略回收", candidate.version())));
            }
        }
        ApplyResult result = new ApplyResult(List.copyOf(warnings), List.copyOf(recycled));
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.policy.apply", hash,
            mapper.writeValueAsString(result), "lead-policy"));
        return result;
    }

    private void verifyPreview(List<LeadVersion> requested, List<Candidate> current, long total) {
        if (total != requested.size() || current.size() != requested.size()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索策略候选已变化，请重新预览");
        }
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate row : current) { byId.put(row.leadId(), row); }
        for (LeadVersion item : requested) {
            Candidate candidate = byId.get(item.leadId());
            if (candidate == null || candidate.version() != item.expectedVersion() || !candidate.kind().equals(item.kind())) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索策略候选已变化，请重新预览");
            }
        }
    }

    private void uniqueLeads(List<LeadVersion> items) {
        if (items.size() > 100 || new HashSet<>(items.stream().map(LeadVersion::leadId).toList()).size() != items.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "策略执行最多选择 100 条且不能重复");
        }
    }
    private void policyPreviewManager(Actor actor) {
        actor.requireAny("crm.lead.assign", "leadPolicy.preview");
        policyScope(actor);
    }
    private void policyWriteManager(Actor actor) {
        actor.requireAny("crm.lead.assign", "leadPolicy.publish");
        policyScope(actor);
    }
    private void policyApplyManager(Actor actor) {
        actor.requireAny("crm.lead.assign", "lead.assign");
        actor.requireAny("crm.lead.policy.manage", "leadPolicy.publish");
        policyScope(actor);
    }
    private void policyScope(Actor actor) {
        if (!Set.of("all", "campus").contains(actor.scope())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "线索策略仅供全局或校区管理范围使用");
        }
    }
    private void validatePage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "线索策略预览分页范围无效");
        }
    }
    private void complete(Actor actor, MutationRepository.RetryWrite write) {
        mutations.complete(actor, write);
    }
    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
    }
}
