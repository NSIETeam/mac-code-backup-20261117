package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates staff checkout creation, identity acceptance and offline receipt recording.
 * Mutations validate input, reload the active caller and atomically store business changes, audit and
 * replay receipts; acceptance uses READ_COMMITTED. Read methods use the supplied caller's permissions
 * and repository scope in read-only transactions. Every transaction rolls back for checked exceptions.
 */
@Service
public class CommerceCheckoutService {
    private final AccessRepository access;
    private final CommerceCheckoutRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    private final Validator validator;

    /**
     * Creates the staff checkout service with shared validation and replay support.
     * @param access reloads and locks active principals before mutations
     * @param repository creates quotes, resolves buyers and writes enrollment or payment facts
     * @param mutations manages request hashes, retry receipts and audit events
     * @param mapper serializes commands and stored checkout receipts
     * @param validator checks checkout DTO constraints before mutation authorization
     */
    public CommerceCheckoutService(AccessRepository access, CommerceCheckoutRepository repository,
            MutationRepository mutations, ObjectMapper mapper, Validator validator) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
        this.validator = validator;
    }

    /**
     * Creates an expiring checkout quote without reserving class seats or recording payment.
     * The current quote is compared with the optional expected hash. Identical retries return the stored
     * receipt only after the active caller's permission and access to that checkout have been checked.
     * @param actor authenticated caller requiring either {@code enrollment.create} or {@code enrollment.manage}
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input standard, group or joint purchase with 1–50 buyers, 1–100 lines and optional displayed quote hash
     * @return checkout receipt containing frozen quote amounts and expiry, or the original retry receipt
     * @throws BusinessException when input, active caller, permission, student or class scope, sale terms,
     *     coupon or promotion eligibility, expected quote hash, or idempotency checks fail
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckoutReceipt create(Actor actor, String key, CreateInput input) throws JsonProcessingException {
        validate(input);
        actor = authorizeMutation(actor, key, false);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        CheckoutReceipt replay = replay(actor, key, "commerce.checkout.create", hash);
        if (replay != null) {
            repository.detail(actor, replay.id());
            return replay;
        }
        CheckoutReceipt result = repository.create(actor, input);
        complete(actor, key, "commerce.checkout.create", hash, result);
        return result;
    }

    /**
     * Resolves all buyers and creates their orders and enrollments in one READ_COMMITTED transaction.
     * The request must still await verification, have the expected version and be unexpired. The repository
     * rechecks frozen quote terms, student identity, capacity and promotion limits before accepting it.
     * @param actor active caller requiring either {@code enrollment.create} or {@code enrollment.manage}
     * @param id checkout public identifier within the caller's checkout scope
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input expected version and optional buyer-index to student mappings; unmapped drafts may create students
     * @return accepted checkout with student and order identifiers, or the stored receipt for an identical retry
     * @throws BusinessException when authorization, key, version, status, expiry, identity, resource scope,
     *     capacity or current sale terms prevent acceptance; assigned scope cannot create draft students
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CheckoutReceipt accept(Actor actor, String id, String key, AcceptInput input)
            throws JsonProcessingException {
        validate(input);
        actor = authorizeMutation(actor, key, false);
        repository.detail(actor, id);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, input)));
        CheckoutReceipt replay = replay(actor, key, "commerce.checkout.accept", hash);
        if (replay != null) { repository.detail(actor, replay.id()); return replay; }
        CheckoutReceipt result = repository.accept(actor, id, input);
        complete(actor, key, "commerce.checkout.accept", hash, result);
        return result;
    }

    /** Confirms a staff-recorded cash or bank receipt for an in-scope checkout.
     * The caller must have receipt-record permission; the active principal and checkout scope are checked
     * before replay or commit, and successful retries return the stored receipt. A new receipt requires an
     * accepted checkout and an amount equal to its aggregate remaining balance. Current sale terms and
     * promotion limits are rechecked before payment facts and the retry receipt are committed together.
     * @param actor authenticated staff caller with {@code crm.receipt.record}
     * @param id checkout public identifier
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons, or hyphens
     * @param input nonnegative amount, method {@code cash} or {@code bank}, nonblank reference up to 150 characters,
     *              and expected checkout version of at least 1
     * @return updated checkout receipt, or the stored receipt for an identical retry
     * @throws BusinessException when DTO validation, active principal, permission, scope, idempotency, version,
     *     status, current sale terms, remaining-balance or duplicate payment-reference checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckoutReceipt confirmReceipt(Actor actor, String id, String key, ConfirmReceiptInput input)
            throws JsonProcessingException {
        validate(input);
        actor = authorizeMutation(actor, key, true);
        repository.detail(actor, id);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, input)));
        CheckoutReceipt replay = replay(actor, key, "commerce.checkout.receipt", hash);
        if (replay != null) { repository.detail(actor, replay.id()); return replay; }
        CheckoutReceipt result = repository.confirmReceipt(actor, id, input);
        complete(actor, key, "commerce.checkout.receipt", hash, result);
        return result;
    }

    /**
     * Reads a checkout receipt under the same checkout visibility predicate used for staff mutations.
     * @param actor caller with any of {@code commerce.read}, {@code crm.order.read}, {@code enrollment.create},
     *     {@code enrollment.manage} or {@code crm.receipt.record}
     * @param id checkout public identifier
     * @return current quote, status, version and linked student or order identifiers
     * @throws BusinessException when no permitted read grant exists or the checkout is missing or outside scope
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CheckoutReceipt detail(Actor actor, String id) {
        actor.requireAny("commerce.read","crm.order.read","order.read","enrollment.create","enrollment.manage","crm.receipt.record","order.confirmReceipt");
        return repository.detail(actor, id);
    }

    /**
     * Reads buyer identity data and matching student candidates for staff acceptance.
     * Checkout visibility is checked first; name/phone identity matches are restricted by student write scope.
     * @param actor caller requiring either {@code enrollment.create} or {@code enrollment.manage}
     * @param id checkout public identifier
     * @return checkout receipt and buyer identity fields with visible matching student candidates
     * @throws BusinessException when permission is absent or the checkout is missing or outside scope
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public IntakeDetail intake(Actor actor,String id) {
        actor.requireAny("enrollment.create","enrollment.manage");
        return repository.intake(actor,id);
    }

    /**
     * Lists scoped checkout receipts in descending creation order in a read-only transaction.
     * @param actor caller with any of {@code commerce.read}, {@code crm.order.read}, {@code enrollment.create},
     *     {@code enrollment.manage} or {@code crm.receipt.record}
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return matching checkout receipts, total count and pagination metadata
     * @throws BusinessException when read permission, data scope or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CheckoutList list(Actor actor, int page, int size) {
        actor.requireAny("commerce.read","crm.order.read","order.read","enrollment.create","enrollment.manage","crm.receipt.record","order.confirmReceipt");
        return repository.list(actor, page, size);
    }

    /**
     * Checks the legacy payment-link permission and checkout scope, then reports the unavailable provider.
     * @param actor caller requiring the exact {@code order.paymentLink} permission
     * @param id checkout public identifier
     * @throws BusinessException when permission or scope checks fail, or with
     *     {@code PAYMENT_PROVIDER_UNAVAILABLE} after a valid checkout is found
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public void paymentLink(Actor actor, String id) {
        actor.require("order.paymentLink");
        repository.detail(actor, id);
        throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE,
            "真实在线支付渠道尚未配置；请使用财务核对实际现金或银行凭据流程");
    }

    private Actor authorizeMutation(Actor actor, String key, boolean receipt) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
        Actor active = access.lockActive(actor.id());
        if(receipt) active.requireAny("crm.receipt.record","order.confirmReceipt");
        else active.requireAny("enrollment.create","enrollment.manage");
        return active;
    }

    private CheckoutReceipt replay(Actor actor, String key, String action, String hash)
            throws JsonProcessingException {
        String value = mutations.replay(actor, key, action, hash);
        return value == null ? null : mapper.readValue(value, CheckoutReceipt.class);
    }

    private void complete(Actor actor, String key, String action, String hash, CheckoutReceipt result)
            throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(result), result.id()));
    }

    private void validate(Object input) {
        if (input == null || !validator.validate(input).isEmpty()) { invalid("商城结账参数无效"); }
    }

    private static void invalid(String message) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}
