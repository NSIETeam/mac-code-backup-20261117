package cn.siyue.core;

import cn.siyue.core.CrmDtos.AttributionAdjustment;
import cn.siyue.core.CrmDtos.AttributionEvent;
import cn.siyue.core.CrmDtos.AttributionRequest;
import cn.siyue.core.CrmDtos.AttributionRequestInput;
import cn.siyue.core.CrmDtos.AttributionRequestPage;
import cn.siyue.core.CrmDtos.AttributionReviewInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates scoped requests and independent approval for order-attribution corrections.
 * Approved changes are recorded separately from the order's immutable original attribution snapshot.
 */
@Service
public class CrmAttributionService {
    private final AccessRepository access;
    private final CrmAttributionRepository attribution;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Creates the service with active-principal access, attribution persistence, retry receipts and JSON mapping.
     * @param access account lock and active-principal access service
     * @param attribution scoped attribution repository and append-only correction ledger
     * @param mutations idempotency receipt repository
     * @param mapper JSON serializer used to fingerprint and replay mutations
     */
    public CrmAttributionService(AccessRepository access, CrmAttributionRepository attribution,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.attribution = attribution;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Requests an owner correction without changing the original order-attribution snapshot.
     * Requires {@code crm.attribution.correct.request} and {@code crm.order.read}; the active principal and
     * order scope are rechecked before an identical idempotency key can replay its result.
     * @param actor authenticated requester with attribution-request and order-read grants
     * @param orderId public identifier of the in-scope order
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input active proposed-owner public ID in the order's campus, different from the current owner, a
     *              nonblank reason up to 2000 characters and optional evidence up to 2000 characters
     * @return the pending attribution request, or its stored result for an identical retry
     * @throws BusinessException when permission, order scope, input or key validation fails
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AttributionRequest request(Actor actor, String orderId, String key, AttributionRequestInput input)
            throws JsonProcessingException {
        actor.require("crm.attribution.correct.request");
        actor.require("crm.order.read");
        requireKey(key);
        if (input == null || input.proposedOwnerId() == null || input.proposedOwnerId().isBlank()
                || input.reason() == null || input.reason().isBlank() || input.reason().length() > 2000
                || input.evidence() != null && input.evidence().length() > 2000) {
            invalid("新归属员工、纠错原因或凭证说明无效");
        }
        actor = access.lockActive(actor.id());
        actor.require("crm.attribution.correct.request");
        actor.require("crm.order.read");
        attribution.authorizeOrder(actor, orderId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(orderId, input)));
        String replay = mutations.replay(actor, key, "crm.attribution.correct.request", hash);
        if (replay != null) { return mapper.readValue(replay, AttributionRequest.class); }
        AttributionRequest result = attribution.request(actor, orderId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.attribution.correct.request", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    /** Lists attribution requests for one order after validating order-read access and current order scope.
     * @param actor authenticated caller with {@code crm.order.read}
     * @param orderId public identifier of the in-scope order
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return the scoped page of attribution requests
     * @throws BusinessException when order access, scope or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AttributionRequestPage requests(Actor actor, String orderId, int page, int size) {
        actor.require("crm.order.read");
        requirePage(page, size);
        return attribution.requests(actor, orderId, page, size);
    }

    /** Independently approves or rejects a pending correction and appends the decision to audit history.
     * Requires {@code crm.attribution.correct.review} and {@code crm.order.read}; expected version and request
     * scope are rechecked before changing the current attribution.
     * @param actor authenticated reviewer with attribution-review and order-read grants
     * @param requestId public identifier of the in-scope request
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input decision {@code approve} or {@code reject}, nonblank note up to 2000 characters, and version >= 1
     * @return the updated request, or its stored result for an identical retry
     * @throws BusinessException when permission, scope, input, state, version or key validation fails
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AttributionRequest review(Actor actor, String requestId, String key, AttributionReviewInput input)
            throws JsonProcessingException {
        actor.require("crm.attribution.correct.review");
        actor.require("crm.order.read");
        requireKey(key);
        if (input == null || input.decision() == null || !Set.of("approve", "reject").contains(input.decision())
                || input.note() == null || input.note().isBlank() || input.note().length() > 2000
                || input.expectedVersion() < 1) { invalid("归因纠错审批结论、说明或版本无效"); }
        actor = access.lockActive(actor.id());
        actor.require("crm.attribution.correct.review");
        actor.require("crm.order.read");
        attribution.authorizeRequest(actor, requestId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(requestId, input)));
        String replay = mutations.replay(actor, key, "crm.attribution.correct.review", hash);
        if (replay != null) { return mapper.readValue(replay, AttributionRequest.class); }
        AttributionRequest result = attribution.review(actor, requestId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.attribution.correct.review", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    /** Lists approved attribution adjustments for an order visible to the caller.
     * Requires {@code crm.order.read}; the returned ledger does not rewrite original order attribution.
     * @param actor authenticated caller with order-read permission
     * @param orderId public identifier of the in-scope order
     * @return up to 100 newest approved adjustments; sensitive reason and evidence are shown to involved owners
     *         or callers with {@code crm.attribution.read_detail}
     * @throws BusinessException when order access or scope is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<AttributionAdjustment> adjustments(Actor actor, String orderId) {
        actor.require("crm.order.read");
        return attribution.adjustments(actor, orderId);
    }

    /** Reads review events for a request after checking its current order scope.
     * Requires {@code crm.order.read}; the repository bounds the event history.
     * @param actor authenticated caller with order-read permission
     * @param requestId public identifier of the in-scope attribution request
     * @return up to 100 newest decision events; event details are shown to the applicant or callers with
     *         {@code crm.attribution.read_detail}
     * @throws BusinessException when order access or request scope is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<AttributionEvent> events(Actor actor, String requestId) {
        actor.require("crm.order.read");
        return attribution.events(actor, requestId);
    }

    private void complete(Actor actor, MutationRepository.RetryWrite write) {
        mutations.complete(actor, write);
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) { invalid("分页范围无效"); }
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT,message); }
}
