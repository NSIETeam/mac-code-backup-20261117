package cn.siyue.core;

import cn.siyue.core.EnrollmentExitDtos.CheckoutCancelInput;
import cn.siyue.core.EnrollmentExitDtos.CheckoutCancelReceipt;
import cn.siyue.core.EnrollmentExitDtos.Input;
import cn.siyue.core.EnrollmentExitDtos.Receipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Applies enrollment exits with exact action grants, scope checks, retry safety and audit.
 * @see EnrollmentExitRepository
 * @see MutationRepository
 */
@Service
public class EnrollmentExitService {
    private record Completion(String key, String action, String hash, String result, String resourceId) { }
    private final AccessRepository access;
    private final EnrollmentExitRepository exits;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Uses the shared database actor lock, audit and encrypted idempotency response.
     * @param access repository for reloading and locking the active actor
     * @param exits scoped enrollment and checkout exit persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public EnrollmentExitService(AccessRepository access, EnrollmentExitRepository exits,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.exits = exits;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Cancels one unpaid direct enrollment; mall orders require checkout-wide cancellation.
     * Requires {@code enrollment.create}; authorization is rechecked under the actor lock and the receipt is
     * recorded atomically. An identical retry returns the stored receipt.
     * @param actor authenticated actor requesting cancellation
     * @param enrollmentId public enrollment identifier
     * @param key stable idempotency key
     * @param input nonblank cancellation reason and expected enrollment version
     * @return cancellation receipt or the stored receipt for an identical retry
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     * @throws BusinessException if permission, scope, validation, state, version, or retry checks fail
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt cancel(Actor actor, String enrollmentId, String key, Input input)
            throws JsonProcessingException {
        validateInput(input);
        actor.require("enrollment.create");
        requireKey(key);
        actor = access.lockActive(actor.id());
        actor.require("enrollment.create");
        exits.authorizeEnrollment(actor, enrollmentId);
        String action = "academic.enrollment.cancel";
        String hash = mutations.hash(mapper.writeValueAsString(List.of(enrollmentId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Receipt.class); }
        Receipt receipt = exits.cancel(actor, enrollmentId, input.reason().trim(), input.expectedVersion());
        complete(actor, new Completion(key, action, hash, mapper.writeValueAsString(receipt), receipt.enrollmentId()));
        return receipt;
    }

    /** Withdraws a live enrollment and atomically creates only a positive refund request.
     * Requires {@code enrollment.withdraw}; authorization is rechecked under the actor lock and the receipt is
     * recorded atomically. An identical retry returns the stored receipt.
     * @param actor authenticated actor requesting withdrawal
     * @param enrollmentId public enrollment identifier
     * @param key stable idempotency key
     * @param input nonblank withdrawal reason and expected enrollment version
     * @return withdrawal receipt or the stored receipt for an identical retry
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     * @throws BusinessException if permission, scope, validation, state, version, or retry checks fail
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt withdraw(Actor actor, String enrollmentId, String key, Input input)
            throws JsonProcessingException {
        validateInput(input);
        actor.require("enrollment.withdraw");
        requireKey(key);
        actor = access.lockActive(actor.id());
        actor.require("enrollment.withdraw");
        exits.authorizeEnrollment(actor, enrollmentId);
        String action = "academic.enrollment.withdraw";
        String hash = mutations.hash(mapper.writeValueAsString(List.of(enrollmentId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, Receipt.class); }
        Receipt receipt = exits.withdraw(actor, enrollmentId, input.reason().trim(), input.expectedVersion());
        complete(actor, new Completion(key, action, hash, mapper.writeValueAsString(receipt), receipt.enrollmentId()));
        return receipt;
    }

    /** Cancels a whole accepted unpaid checkout or an unaccepted request atomically.
     * Requires {@code enrollment.create}; authorization is rechecked under the actor lock and the receipt is
     * recorded atomically. An identical retry returns the stored receipt.
     * @param actor authenticated actor requesting checkout cancellation
     * @param checkoutId public checkout identifier
     * @param key stable idempotency key
     * @param input nonblank cancellation reason and expected checkout version
     * @return checkout cancellation receipt or the stored receipt for an identical retry
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     * @throws BusinessException if permission, scope, validation, checkout state, version, or retry checks fail
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public CheckoutCancelReceipt cancelCheckout(Actor actor, String checkoutId, String key,
            CheckoutCancelInput input) throws JsonProcessingException {
        validateCheckoutInput(input);
        actor.require("enrollment.create");
        requireKey(key);
        actor = access.lockActive(actor.id());
        actor.require("enrollment.create");
        exits.authorizeCheckout(actor, checkoutId);
        String action = "academic.commerce.checkout.cancel";
        String hash = mutations.hash(mapper.writeValueAsString(List.of(checkoutId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, CheckoutCancelReceipt.class); }
        CheckoutCancelReceipt receipt = exits.cancelCheckout(actor, checkoutId, input.reason().trim(),
            input.expectedVersion());
        complete(actor, new Completion(key, action, hash, mapper.writeValueAsString(receipt), receipt.checkoutId()));
        return receipt;
    }

    private void complete(Actor actor, Completion completion) {
        mutations.complete(actor, new MutationRepository.RetryWrite(completion.key(), completion.action(),
            completion.hash(), completion.result(), completion.resourceId()));
    }

    private static void validateInput(Input input) {
        if (input == null || input.reason() == null || input.reason().isBlank() || input.reason().length() > 500
                || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "退出原因或报名版本无效");
        }
    }

    private static void validateCheckoutInput(CheckoutCancelInput input) {
        if (input == null || input.reason() == null || input.reason().isBlank() || input.reason().length() > 500
                || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "取消原因或商城申请版本无效");
        }
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Idempotency-Key 格式无效");
        }
    }
}
