package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmOrder;
import cn.siyue.core.CrmDtos.CrmReceipt;
import cn.siyue.core.CrmDtos.OrderInput;
import cn.siyue.core.CrmDtos.OrderPage;
import cn.siyue.core.CrmDtos.ReceiptReport;
import cn.siyue.core.CrmDtos.ReceiptInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies current permissions and idempotency to immutable attribution and receipt writes. */
@Service
public class CrmOrderService {
    private final AccessRepository access;
    private final CrmOrderRepository orders;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /**
     * Wires the scoped access, persistence, idempotency and JSON collaborators.
     * @param access actor resolution and active-principal access repository.
     * @param orders CRM order repository.
     * @param mutations idempotency and mutation-audit repository.
     * @param mapper JSON serializer used for request fingerprints and stored replay responses.
     */
    public CrmOrderService(AccessRepository access, CrmOrderRepository orders,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.orders = orders;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /**
     * Creates an order from fixed terms after checking the opportunity scope; identical idempotent retries return the stored order.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The created order or the stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmOrder create(Actor actor, String key, OrderInput input) throws JsonProcessingException {
        actor.require("crm.order.write");
        actor.require("crm.opportunity.read");
        requireKey(key);
        requireInput(input);
        actor = access.lockActive(actor.id());
        actor.require("crm.order.write");
        actor.require("crm.opportunity.read");
        orders.authorizeOpportunity(actor, input.opportunityId());
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.order.create", hash);
        if (replay != null) { return mapper.readValue(replay, CrmOrder.class); }
        CrmOrder result = orders.create(actor, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.order.create", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    /**
     * Returns bounded class quotes within the opportunity campus after requiring order-write and opportunity-read permissions.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param opportunityId opportunity public identifier.
     * @param page 1-based page number.
     * @param size maximum number of records in the page.
     * @return A bounded page of class quotes for the requested opportunity.
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public CrmDtos.ClassQuotePage classQuotes(Actor actor,String opportunityId,int page,int size) {
        actor.require("crm.order.write");actor.require("crm.opportunity.read");
        if(opportunityId==null || opportunityId.isBlank() || page<1 || page>100000 || size<1 || size>100) invalid("班级报价查询无效");
        return orders.classQuotes(actor,opportunityId,page,size);
    }
    /**
     * Returns a bounded order page in the caller’s current scope; filtering by opportunity also requires opportunity-read permission.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param opportunityId opportunity public identifier.
     * @param page 1-based page number.
     * @param size maximum number of records in the page.
     * @return A bounded page of orders visible to the actor.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OrderPage list(Actor actor, String opportunityId, int page, int size) {
        LegacyFinancePermissions.readOrders(actor);
        if (page < 1 || page > 100000 || size < 1 || size > 100) { invalid("订单分页范围无效"); }
        if (opportunityId != null && !opportunityId.isBlank()) { actor.require("crm.opportunity.read"); }
        return orders.list(actor, opportunityId, page, size);
    }

    /**
     * Returns order history only when both customer-read and order-read permissions and the requested customer scope permit it.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param customerId customer public identifier.
     * @param page 1-based page number.
     * @param size maximum number of records in the page.
     * @return A bounded page of orders visible to the actor.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OrderPage customerOrders(Actor actor, String customerId, int page, int size) {
        LegacyFinancePermissions.readOrders(actor);
        actor.require("crm.customer.read");
        if (customerId == null || customerId.isBlank() || customerId.length() > 120
                || page < 1 || page > 100000 || size < 1 || size > 100) {
            invalid("客户订单查询范围无效");
        }
        return orders.listForCustomer(actor, customerId, page, size);
    }

    /**
     * Aggregates append-only receipts and immutable seller snapshots for a bounded reporting period.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param fromAt inclusive reporting start instant.
     * @param untilAt exclusive reporting end instant.
     * @param page 1-based page number.
     * @param size maximum number of records in the page.
     * @return Receipt totals and a bounded page for the requested period.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ReceiptReport receiptReport(Actor actor, java.time.Instant fromAt,
            java.time.Instant untilAt, int page, int size) {
        actor.require("report.read");
        if (fromAt == null || untilAt == null || !untilAt.isAfter(fromAt)
                || java.time.Duration.between(fromAt, untilAt).compareTo(java.time.Duration.ofDays(366)) > 0
                || page < 1 || page > 100000 || size < 1 || size > 100) {
            invalid("收款报表期间或分页范围无效");
        }
        return orders.receiptReport(actor, fromAt, untilAt, page, size);
    }

    /**
     * Appends a real receipt to a scoped order and stores an actor-bound idempotent response.
     * Native receipt-record plus order-read grants, or the exact legacy order.confirmReceipt grant, are rechecked under the active-account lock.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param orderId order public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The appended receipt or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmReceipt receive(Actor actor, String orderId, String key, ReceiptInput input)
            throws JsonProcessingException {
        LegacyFinancePermissions.receive(actor);
        requireKey(key);
        requireReceipt(input);
        actor = access.lockActive(actor.id());
        LegacyFinancePermissions.receive(actor);
        orders.authorize(actor, orderId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(orderId, input)));
        String replay = mutations.replay(actor, key, "crm.receipt.record", hash);
        if (replay != null) { return mapper.readValue(replay, CrmReceipt.class); }
        CrmReceipt result = orders.receive(actor, orderId, input);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.receipt.record", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    /**
     * Checks the order payment-link permission and scope, then reports that no payment provider is configured.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param orderId order public identifier.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public void paymentLink(Actor actor, String orderId) {
        actor.require("order.paymentLink");
        if (orderId == null || orderId.isBlank() || orderId.length() > 120) { invalid("订单编号无效"); }
        orders.authorize(actor, orderId);
        throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE,
            "尚未配置并接通支付渠道；可由财务凭真实线下凭据确认收款");
    }

    /**
     * Returns the scoped order’s bounded immutable receipt timeline.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param orderId order public identifier.
     * @return The scoped order receipt timeline.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<CrmReceipt> receipts(Actor actor, String orderId) {
        LegacyFinancePermissions.readOrders(actor);
        return orders.receipts(actor, orderId);
    }

    private static void requireInput(OrderInput input) {
        if (input == null || input.totalAmount() == null || input.totalAmount().signum() <= 0
                || !hasMoneyPrecision(input.totalAmount())
                || input.productSnapshot() == null || input.productSnapshot().isBlank()
                || input.productSnapshot().length() > 300 || input.expectedOpportunityVersion() < 1) {
            invalid("订单金额、商品快照或商机版本无效");
        }
    }
    private static void requireReceipt(ReceiptInput input) {
        if (input == null || input.amount() == null || input.amount().signum() <= 0
                || !hasMoneyPrecision(input.amount()) || input.receivedAt() == null
                || input.receivedAt().isAfter(java.time.Instant.now()) || input.expectedOrderVersion() < 1
                || input.method() == null || !java.util.Set.of("bank_transfer", "pos", "cash", "online", "other")
                    .contains(input.method())
                || input.reference() != null && input.reference().length() > 1000) {
            invalid("收款金额、时间、参考信息或订单版本无效");
        }
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }
    private static boolean hasMoneyPrecision(java.math.BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 2 && value.precision() <= 18;
    }
    private static void invalid(String message) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}
