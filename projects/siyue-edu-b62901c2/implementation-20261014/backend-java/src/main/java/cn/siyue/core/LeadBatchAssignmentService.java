package cn.siyue.core;

import cn.siyue.core.LeadBatchAssignmentDtos.Input;
import cn.siyue.core.LeadBatchAssignmentDtos.Result;
import cn.siyue.core.LeadBatchAssignmentDtos.OwnerOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Coordinates permission-checked, all-or-nothing explicit lead allocation.
 * @see LeadBatchAssignmentRepository
 * @see MutationRepository
 */
@Service
public class LeadBatchAssignmentService {
    private final AccessRepository access;
    private final LeadBatchAssignmentRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Injects active-principal locking, allocation persistence and retry serialization.
     * @param access repository for reloading and locking active actors and selected owners
     * @param repository scoped lead and eligible-owner persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public LeadBatchAssignmentService(AccessRepository access, LeadBatchAssignmentRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Assigns selected leads to explicit owners using the requested allocation strategy.
     * Requires {@code lead.assign}; validates and locks all participants, then applies the whole selection in one
     * transaction. Identical retries return the stored result after current authorization checks.
     * @param actor authenticated actor requesting allocation
     * @param key stable idempotency key
     * @param input lead IDs with expected versions, selected owner IDs, per-owner quotas, strategy, and reason
     * @return assigned lead results or the stored result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, participant activity, scope, input, version, or retry checks fail
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Result assign(Actor actor, String key, Input input) throws JsonProcessingException {
        actor.require("lead.assign");
        requireKey(key);
        validateIds(input);
        LeadBatchAssignmentRepository.LockedParticipants participants =
            repository.lockParticipants(actor, input, access);
        actor = participants.actor();
        actor.require("lead.assign");
        repository.authorize(actor, input, participants.owners());
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "lead.batch.assign", hash);
        if (replay != null) { return mapper.readValue(replay, Result.class); }
        Result result = repository.assign(actor, input, participants.owners());
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "lead.batch.assign", hash,
            mapper.writeValueAsString(result), input.leadIds().get(0)));
        return result;
    }

    /** Lists safe owner choices for the exact selected leads without exposing internal IDs.
     * @param actor authenticated actor requesting eligible owners
     * @param leadIds selected public lead identifiers whose scope defines eligible choices
     * @param page one-based page number
     * @param size requested page size
     * @return paged owner choices containing public identifiers and safe display fields
     * @throws BusinessException if the repository rejects actor scope, selection, or pagination
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OwnerOptions ownerOptions(Actor actor, List<String> leadIds, int page, int size) {
        return repository.ownerOptions(actor, leadIds, page, size);
    }

    private void validateIds(Input input) {
        if (input.leadIds().isEmpty() || input.leadIds().size() > 498
                || input.ownerIds().isEmpty() || input.ownerIds().size() > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "一次最多分配498条线索和100名员工");
        }
        if (new HashSet<>(input.leadIds()).size() != input.leadIds().size()
                || new HashSet<>(input.ownerIds()).size() != input.ownerIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "线索或员工列表重复");
        }
        validateExpectedVersions(input);
        validateReasonAndStrategy(input);
    }

    private void validateExpectedVersions(Input input) {
        if (input.expectedVersions() == null || !input.expectedVersions().keySet().equals(new HashSet<>(input.leadIds()))
                || input.expectedVersions().values().stream().anyMatch(version -> version == null || version < 1)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "每条线索都必须提供有效版本");
        }
    }

    private void validateReasonAndStrategy(Input input) {
        if (input.reason().isBlank() || input.reason().length() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分配原因无效");
        }
        if (!input.strategy().equals("even") && !input.strategy().equals("quota")
                && !input.strategy().equals("owner_first")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分配策略无效");
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() < 16 || key.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Idempotency-Key 须为 16 到 100 个字符");
        }
    }
}
