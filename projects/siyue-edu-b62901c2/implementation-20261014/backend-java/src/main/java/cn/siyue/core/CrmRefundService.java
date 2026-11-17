package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmRefund;
import cn.siyue.core.CrmDtos.CrmRefundRequest;
import cn.siyue.core.CrmDtos.CashFlowReport;
import cn.siyue.core.CrmDtos.RefundEvent;
import cn.siyue.core.CrmDtos.RefundPage;
import cn.siyue.core.CrmDtos.RefundRecordInput;
import cn.siyue.core.CrmDtos.RefundRequestInput;
import cn.siyue.core.CrmDtos.RefundRequestPage;
import cn.siyue.core.CrmDtos.RefundReviewInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates scoped refund requests, independent review, actual settlement and cash-flow reporting.
 * Request, review and settlement use separate permissions and idempotent mutation receipts; repository
 * operations preserve the received-balance reservation and append-only refund history.
 */
@Service
public class CrmRefundService {
    private final AccessRepository access;
    private final CrmRefundRepository refunds;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Creates the service with active-account access, refund persistence, mutation receipts and JSON mapping.
     * @param access account lock and active-principal access service
     * @param refunds scoped refund and cash-flow repository
     * @param mutations idempotency receipt repository
     * @param mapper JSON serializer used to fingerprint and replay mutations
     */
    public CrmRefundService(AccessRepository access, CrmRefundRepository refunds,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.refunds = refunds;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Reserves an available received balance for a refund awaiting separate approval.
     * Requires {@code crm.refund.request} and {@code crm.order.read}; the active principal and order scope
     * are rechecked before an identical key can replay its prior result.
     * @param actor authenticated requester with refund-request and order-read grants
     * @param orderId public identifier of the in-scope order
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input positive amount with at most two fractional digits, nonblank reason up to 2000 characters,
     *              and optional evidence text up to 2000 characters
     * @return the pending request, or the stored request for an identical retry
     * @throws BusinessException when permission, order scope, input, key or available-balance checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CrmRefundRequest request(Actor actor, String orderId, String key, RefundRequestInput input)
            throws JsonProcessingException {
        actor.require("crm.refund.request");
        actor.require("crm.order.read");
        requireKey(key);
        requireRequest(input);
        actor = access.lockActive(actor.id());
        actor.require("crm.refund.request");
        actor.require("crm.order.read");
        refunds.authorizeOrder(actor, orderId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(orderId, input)));
        String replay = mutations.replay(actor, key, "crm.refund.request", hash);
        if (replay != null) { return mapper.readValue(replay, CrmRefundRequest.class); }
        CrmRefundRequest result = refunds.request(actor, orderId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.refund.request", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    /** Lists requests for one order after checking the caller's refund-read grant and order scope.
     * @param actor authenticated caller with {@code crm.refund.read}
     * @param orderId public identifier of the in-scope order
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return the scoped page of refund requests
     * @throws BusinessException when access, order scope or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public RefundRequestPage requests(Actor actor, String orderId, int page, int size) {
        LegacyFinancePermissions.readRefunds(actor);
        requirePage(page, size);
        return refunds.requests(actor, orderId, page, size);
    }

    /** Approves or rejects a pending request, disallowing the applicant from reviewing their own request.
     * Requires native refund-approval plus order-read grants, or the exact legacy {@code refund.approve} grant; the expected version is checked while
     * the request and order balance are locked, and the decision is recorded in the event history.
     * @param actor authenticated reviewer with refund-approval and order-read grants
     * @param requestId public identifier of the in-scope refund request
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input decision {@code approve} or {@code reject}, nonblank note up to 2000 characters, and version >= 1
     * @return the updated request, or the stored request for an identical retry
     * @throws BusinessException when permission, scope, input, self-review, state, version or key checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CrmRefundRequest review(Actor actor, String requestId, String key, RefundReviewInput input)
            throws JsonProcessingException {
        LegacyFinancePermissions.review(actor);
        requireKey(key);
        if (input == null || input.decision() == null || input.note() == null || input.note().isBlank()
                || input.note().length() > 2000 || input.expectedVersion() < 1
                || !java.util.Set.of("approve", "reject").contains(input.decision())) {
            invalid("退款审批结论、说明或版本无效");
        }
        actor = access.lockActive(actor.id());
        LegacyFinancePermissions.review(actor);
        refunds.authorizeRequest(actor, requestId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(requestId, input)));
        String replay = mutations.replay(actor, key, "crm.refund.review", hash);
        if (replay != null) { return mapper.readValue(replay, CrmRefundRequest.class); }
        CrmRefundRequest result = refunds.review(actor, requestId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.refund.review", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    /** Settles an approved request and appends an immutable actual-refund ledger fact.
     * Requires native refund-record plus order-read grants, or the exact legacy {@code refund.submit} grant; the applicant cannot record their own refund.
     * Request version and remaining refundable balance are rechecked under locks.
     * @param actor authenticated settlement operator with refund-record and order-read grants
     * @param requestId public identifier of the in-scope approved request
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input supported refund method, optional reference up to 1000 characters, nonfuture timestamp,
     *              and expected request version >= 1
     * @return the recorded refund; the reference is returned only to callers with its separate read grant
     * @throws BusinessException when permission, scope, input, self-recording, state, version, balance or key checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CrmRefund record(Actor actor, String requestId, String key, RefundRecordInput input)
            throws JsonProcessingException {
        LegacyFinancePermissions.settle(actor);
        requireKey(key);
        requireRecord(input);
        actor = access.lockActive(actor.id());
        LegacyFinancePermissions.settle(actor);
        refunds.authorizeRequest(actor, requestId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(requestId, input)));
        String replay = mutations.replay(actor, key, "crm.refund.record", hash);
        if (replay != null) { return mapper.readValue(replay, CrmRefund.class); }
        CrmRefund result = refunds.record(actor, requestId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.refund.record", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    /** Lists actual refund ledger rows for an in-scope order with optional reference visibility.
     * Requires {@code crm.refund.read}; reference values are included only with
     * {@code crm.refund.reference.read}.
     * @param actor authenticated caller with refund-read permission
     * @param orderId public identifier of the in-scope order
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return the scoped page of actual refund facts
     * @throws BusinessException when access, order scope or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public RefundPage refunds(Actor actor, String orderId, int page, int size) {
        LegacyFinancePermissions.readRefunds(actor);
        requirePage(page, size);
        return refunds.refunds(actor, orderId, page, size);
    }

    /** Returns at most 100 request events after checking the caller's refund-read grant and current order scope.
     * Event details are visible to the applicant or a caller with {@code crm.refund.read_detail}.
     * @param actor authenticated caller with refund-read permission
     * @param requestId public identifier of the in-scope refund request
     * @return newest request, review and settlement events first
     * @throws BusinessException when permission or request scope is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<RefundEvent> events(Actor actor, String requestId) {
        LegacyFinancePermissions.readRefunds(actor);
        return refunds.events(actor, requestId);
    }

    /** Reports receipts, refunds and net cash movement grouped by scoped orders and frozen seller attribution.
     * Requires {@code report.read}; the period must be positive and no longer than 366 days.
     * @param actor authenticated caller whose order visibility limits the report
     * @param fromAt inclusive start instant
     * @param untilAt exclusive end instant
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return scoped cash-flow totals and page metrics
     * @throws BusinessException when permission, period or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CashFlowReport cashFlowReport(Actor actor, Instant fromAt, Instant untilAt, int page, int size) {
        actor.require("report.read");
        if (fromAt == null || untilAt == null || !untilAt.isAfter(fromAt)
                || java.time.Duration.between(fromAt, untilAt).compareTo(java.time.Duration.ofDays(366)) > 0) {
            invalid("经营报表期间须为 366 天以内的有效区间");
        }
        requirePage(page, size);
        return refunds.cashFlowReport(actor, fromAt, untilAt, page, size);
    }

    private void complete(Actor actor, MutationRepository.RetryWrite write) {
        mutations.complete(actor, write);
    }

    private static void requireRequest(RefundRequestInput input) {
        if (input == null || !validMoney(input.amount()) || input.reason() == null || input.reason().isBlank()
                || input.reason().length() > 2000 || input.evidence() != null && input.evidence().length() > 2000) {
            invalid("退款金额、原因或凭证说明无效");
        }
    }

    private static void requireRecord(RefundRecordInput input) {
        if (input == null || input.method() == null
                || !java.util.Set.of("bank_transfer", "pos", "cash", "online", "other").contains(input.method())
                || input.refundedAt() == null || input.refundedAt().isAfter(Instant.now())
                || input.reference() != null && input.reference().length() > 1000 || input.expectedVersion() < 1) {
            invalid("退款方式、时间、参考信息或申请版本无效");
        }
    }

    private static boolean validMoney(java.math.BigDecimal amount) {
        return amount != null && amount.signum() > 0 && amount.precision() <= 18
            && amount.stripTrailingZeros().scale() <= 2;
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) { invalid("分页范围无效"); }
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }

    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
