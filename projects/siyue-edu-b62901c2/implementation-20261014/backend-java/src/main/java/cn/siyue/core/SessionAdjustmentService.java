package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.siyue.core.SessionAdjustmentDtos.AssignInput;
import cn.siyue.core.SessionAdjustmentDtos.CancelInput;
import cn.siyue.core.SessionAdjustmentDtos.Receipt;
import cn.siyue.core.SessionAdjustmentDtos.WaiveInput;
import cn.siyue.core.SessionAdjustmentDtos.Query;
import cn.siyue.core.SessionAdjustmentDtos.Page;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundary for fixed makeup, waiver and cancellation operations.
 * @see SessionAdjustmentRepository
 * @see MutationRepository
 */
@Service
public class SessionAdjustmentService {
    private final AccessRepository access;
    private final SessionAdjustmentRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Injects actor locking, adjustment persistence and idempotency tracking.
     * @param access repository used to reload and lock the active actor
     * @param repository scoped session-adjustment persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public SessionAdjustmentService(AccessRepository access, SessionAdjustmentRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Lists session adjustments visible under the actor's class-read scope.
     * @param actor authenticated actor requesting the page
     * @param query requested filters and pagination
     * @return scoped adjustment page
     * @throws BusinessException if the actor lacks class-read permission or query is invalid
     */
    public Page list(Actor actor, Query query) {
        actor.require("class.read");
        return repository.list(actor, query);
    }

    /** Assigns a version-checked makeup or temporary placement to a target class session.
     * The service requires {@code session.move}, rechecks the active principal under lock, authorizes the
     * source enrollment/session and target session, then stores an idempotent receipt in the same transaction.
     * @param actor authenticated caller with session-move permission and applicable source/target scope
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input enrollment, source and target identifiers, kind {@code makeup} or {@code temporary},
     *              nonblank reason up to 500 characters, and positive versions for enrollment and both sessions
     * @return the created adjustment receipt, or the stored receipt for an identical retry
     * @throws BusinessException when permission, scope, input, state, version, or idempotency checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt assign(Actor actor, String key, AssignInput input) throws JsonProcessingException {
        String action = "academic.makeup.assign";
        actor.require("session.move");
        requireKey(key);
        repository.authorizeAssign(actor, input);
        Actor current = access.lockActive(actor.id());
        current.require("session.move");
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(current, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Receipt.class); }
        Receipt receipt = repository.assign(current, input);
        complete(current, key, action, hash, receipt);
        return receipt;
    }

    /** Waives one version-checked source session without creating a target placement.
     * The service requires {@code session.move}, rechecks the active principal under lock, and records the
     * versioned waiver and idempotent receipt atomically.
     * @param actor authenticated caller with session-move permission and source enrollment/session scope
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input enrollment and source session identifiers, nonblank reason up to 500 characters, and positive
     *              enrollment/source-session versions
     * @return the waiver receipt, or the stored receipt for an identical retry
     * @throws BusinessException when permission, scope, input, state, version, or idempotency checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt waive(Actor actor, String key, WaiveInput input) throws JsonProcessingException {
        String action = "academic.makeup.waive";
        actor.require("session.move");
        requireKey(key);
        repository.authorizeWaive(actor, input);
        Actor current = access.lockActive(actor.id());
        current.require("session.move");
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(current, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Receipt.class); }
        Receipt receipt = repository.waive(current, input);
        complete(current, key, action, hash, receipt);
        return receipt;
    }

    /** Cancels an active session adjustment using its expected version and an audit reason.
     * The service requires {@code session.move}, rechecks the active principal and adjustment scope, and stores
     * the cancellation retry receipt atomically.
     * @param actor authenticated caller with session-move permission and adjustment scope
     * @param adjustmentId adjustment public identifier
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input nonblank cancellation reason up to 500 characters and expected adjustment version at least 1
     * @return the cancelled adjustment receipt, or the stored receipt for an identical retry
     * @throws BusinessException when permission, scope, input, state, version, or idempotency checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt cancel(Actor actor, String adjustmentId, String key, CancelInput input)
            throws JsonProcessingException {
        String action = "academic.makeup.cancel";
        actor.require("session.move");
        requireKey(key);
        repository.authorizeCancel(actor, adjustmentId);
        Actor current = access.lockActive(actor.id());
        current.require("session.move");
        String hash = mutations.hash(mapper.writeValueAsString(List.of(adjustmentId, input)));
        String replay = mutations.replay(current, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Receipt.class); }
        Receipt receipt = repository.cancel(current, adjustmentId, input);
        complete(current, key, action, hash, receipt);
        return receipt;
    }

    private void complete(Actor actor, String key, String action, String hash, Receipt receipt)
            throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(receipt), receipt.adjustmentId()));
    }

    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "幂等键格式无效");
        }
    }
}
