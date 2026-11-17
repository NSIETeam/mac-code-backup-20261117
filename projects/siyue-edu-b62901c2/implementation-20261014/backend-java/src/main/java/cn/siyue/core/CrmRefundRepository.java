package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmRefund;
import cn.siyue.core.CrmDtos.CrmRefundRequest;
import cn.siyue.core.CrmDtos.CashFlowMetric;
import cn.siyue.core.CrmDtos.CashFlowReport;
import cn.siyue.core.CrmDtos.RefundEvent;
import cn.siyue.core.CrmDtos.RefundRecordInput;
import cn.siyue.core.CrmDtos.RefundPage;
import cn.siyue.core.CrmDtos.RefundRequestInput;
import cn.siyue.core.CrmDtos.RefundRequestPage;
import cn.siyue.core.CrmDtos.RefundReviewInput;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists independently reviewed refund requests and append-only refund facts. */
@Repository
public class CrmRefundRepository {
    /** 退款资格与余额校验的锁定订单，含实收、实退和已课消金额。 */
    private record OrderRow(long id, String publicId, long ownerId, Long campusId,
            BigDecimal received, BigDecimal refunded, BigDecimal consumed) { }
    /** 退款申请持久化投影，保留申请/审核身份、金额、证据密文、状态和版本。 */
    private record RequestRow(long id, String publicId, long orderId, String orderPublicId,
            long applicantId, String applicantPublicId, BigDecimal amount, String reasonCipher,
            String evidenceCipher, String status, Long reviewerId, String reviewerPublicId,
            String reviewNoteCipher, Timestamp reviewedAt, long version) { }
    /** 退款审核审计的原状态与目标状态，不承载转账或支付提供方结果。 */
    private record RefundTransition(String from,String to) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    /** Uses the shared CRM keyring for refund reasons, evidence and references. */
    public CrmRefundRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Reserves available received balance before a refund request enters approval. */
    public CrmRefundRequest request(Actor actor, String orderPublicId, RefundRequestInput input) {
        OrderRow order = order(actor, orderPublicId, true);
        BigDecimal reserved = reservedAmount(order.id(), null);
        requireAvailable(order, reserved, input.amount());
        String publicId = "RFDREQ_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_refund_request
                (public_id,order_id,applicant_id,amount,reason_cipher,evidence_cipher)
            VALUES (?,?,?,?,?,?)
            """, publicId, order.id(), actor.id(), input.amount(), cipher.encrypt(input.reason().trim()),
            input.evidence() == null || input.evidence().isBlank() ? null : cipher.encrypt(input.evidence().trim()));
        long id = requestId(publicId);
        event(id,actor,"requested",new RefundTransition(null,"pending_approval"),input.reason());
        return requestDto(actor, request(actor, publicId, false));
    }

    /** Lists the current order's requests without exposing encrypted details by default. */
    public RefundRequestPage requests(Actor actor, String orderPublicId, int page, int size) {
        OrderRow order = order(actor, orderPublicId, false);
        Long total = jdbc.queryForObject("SELECT COUNT(r.id) FROM core_crm_refund_request r WHERE r.order_id=? AND r.deleted=0",
            Long.class, order.id());
        List<CrmRefundRequest> items = jdbc.query(requestSelect()
            + " WHERE r.order_id=? AND r.deleted=0 ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> requestDto(actor, requestRow(rs, row)), order.id(), size,
            Math.multiplyExact((long) page - 1, size));
        return new RefundRequestPage(items, total == null ? 0 : total, page, size);
    }

    /** Reviews a request only after locking both the request and its order balance. */
    public CrmRefundRequest review(Actor actor, String requestPublicId, RefundReviewInput input) {
        RequestRow request = request(actor, requestPublicId, true);
        if (request.applicantId() == actor.id()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "退款申请人不能审批自己的申请");
        }
        if (!request.status().equals("pending_approval")) { throw invalid("退款申请已处理"); }
        OrderRow order = order(actor, request.orderPublicId(), true);
        if (input.decision().equals("approve")) {
            requireAvailable(order, reservedAmount(order.id(), request.id()), request.amount());
        }
        String next = input.decision().equals("approve") ? "approved" : "rejected";
        int changed = jdbc.update("""
            UPDATE core_crm_refund_request SET status=?,reviewer_id=?,review_note_cipher=?,reviewed_at=?,
                version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status='pending_approval' AND deleted=0
            """, next, actor.id(), cipher.encrypt(input.note().trim()), Timestamp.from(Instant.now()),
            request.id(), input.expectedVersion());
        if (changed != 1) { throw conflict("退款申请版本已变化"); }
        event(request.id(),actor,input.decision().equals("approve")?"approved":"rejected",
            new RefundTransition("pending_approval",next),input.note());
        return requestDto(actor, request(actor, requestPublicId, false));
    }

    /** Settles exactly one approved request and updates only the order's refund balance. */
    public CrmRefund record(Actor actor, String requestPublicId, RefundRecordInput input) {
        RequestRow request = request(actor, requestPublicId, true);
        if (request.applicantId() == actor.id()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "退款申请人不能登记本人申请的退款");
        }
        if (!request.status().equals("approved")) { throw invalid("只有已审批的退款申请可以登记实际退款"); }
        if (request.version() != input.expectedVersion()) { throw conflict("退款申请版本已变化"); }
        OrderRow order = order(actor, request.orderPublicId(), true);
        requireAvailable(order, reservedAmount(order.id(), request.id()), request.amount());
        String refundPublicId = "RFD_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_refund
                (public_id,refund_request_id,order_id,actor_id,amount,method,reference_cipher,refunded_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, refundPublicId, request.id(), order.id(), actor.id(), request.amount(), input.method(),
            input.reference() == null || input.reference().isBlank() ? null : cipher.encrypt(input.reference().trim()),
            Timestamp.from(input.refundedAt()));
        updateRefundBalance(order, request.amount());
        settleRequest(actor, request, input.expectedVersion());
        event(request.id(),actor,"settled",new RefundTransition("approved","settled"),"已登记实际退款 "+refundPublicId);
        return new CrmRefund(refundPublicId, request.publicId(), order.publicId(), actor.publicId(),
            request.amount(), input.method(), (actor.permissions().contains("crm.refund.reference.read") || LegacyFinancePermissions.legacyRefundReader(actor))
                ? input.reference() : null, input.refundedAt());
    }

    /** Reads the immutable refund ledger after order scope is revalidated. */
    public RefundPage refunds(Actor actor, String orderPublicId, int page, int size) {
        OrderRow order = order(actor, orderPublicId, false);
        boolean readReference = (actor.permissions().contains("crm.refund.reference.read") || LegacyFinancePermissions.legacyRefundReader(actor));
        Long total = jdbc.queryForObject("SELECT COUNT(f.id) FROM core_crm_refund f WHERE f.order_id=? AND f.deleted=0",
            Long.class, order.id());
        List<CrmRefund> items = jdbc.query("""
            SELECT f.public_id,r.public_id request_public_id,p.public_id actor_public_id,
                   f.amount,f.method,f.reference_cipher,f.refunded_at
            FROM core_crm_refund f JOIN core_crm_refund_request r ON r.id=f.refund_request_id
            JOIN core_principal p ON p.id=f.actor_id
            WHERE f.order_id=? AND f.deleted=0 ORDER BY f.refunded_at DESC,f.id DESC LIMIT ? OFFSET ?
            """, (rs, row) -> new CrmRefund(rs.getString("public_id"), rs.getString("request_public_id"),
                order.publicId(), rs.getString("actor_public_id"), rs.getBigDecimal("amount"),
                rs.getString("method"), !readReference || rs.getString("reference_cipher") == null
                    ? null : cipher.decrypt(rs.getString("reference_cipher")),
                rs.getTimestamp("refunded_at").toInstant()), order.id(), size, Math.multiplyExact((long) page - 1, size));
        return new RefundPage(items, total == null ? 0 : total, page, size);
    }

    /** Reads a bounded immutable refund decision and settlement timeline. */
    public List<RefundEvent> events(Actor actor, String requestPublicId) {
        RequestRow request = request(actor, requestPublicId, false);
        boolean readDetail = (actor.permissions().contains("crm.refund.read_detail") || LegacyFinancePermissions.legacyRefundReader(actor))
            || request.applicantPublicId().equals(actor.publicId());
        return jdbc.query("""
            SELECT e.public_id,p.public_id actor_public_id,e.event_type,e.from_status,e.to_status,
                   e.detail_cipher,e.occurred_at
            FROM core_crm_refund_event e JOIN core_principal p ON p.id=e.actor_id
            WHERE e.request_id=? AND e.deleted=0 ORDER BY e.occurred_at DESC,e.id DESC LIMIT 100
            """, (rs, row) -> new RefundEvent(rs.getString("public_id"), rs.getString("actor_public_id"),
                rs.getString("event_type"), rs.getString("from_status"), rs.getString("to_status"),
                !readDetail || rs.getString("detail_cipher") == null ? null : cipher.decrypt(rs.getString("detail_cipher")),
                rs.getTimestamp("occurred_at").toInstant()), request.id());
    }

    /** Aggregates actual cash inflows and refunds by event time and frozen order attribution. */
    public CashFlowReport cashFlowReport(Actor actor, Instant fromAt, Instant untilAt, int page, int size) {
        ScopeSql.Clause scope = reportOrderScope(actor);
        Timestamp from = Timestamp.from(fromAt);
        Timestamp until = Timestamp.from(untilAt);
        String affectedOrders = "(SELECT r.order_id FROM core_crm_receipt r WHERE r.deleted=0 AND r.received_at>=? AND r.received_at<? "
            + "UNION SELECT f.order_id FROM core_crm_refund f WHERE f.deleted=0 AND f.refunded_at>=? AND f.refunded_at<?)";
        List<Object> args = cashArgs(from, until, scope.args());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + affectedOrders + " e JOIN core_crm_order o ON o.id=e.order_id "
            + "WHERE o.deleted=0 AND (" + scope.sql() + ")", Long.class, args.toArray());
        BigDecimal gross = sumInPeriod("core_crm_receipt", "received_at", from, until, scope);
        BigDecimal refundTotal = sumInPeriod("core_crm_refund", "refunded_at", from, until, scope);
        String sql = """
            SELECT o.public_id order_public_id,opp.public_id opportunity_public_id,c.public_id customer_public_id,
                   cp.public_id campus_public_id,
                   COALESCE((SELECT target.public_id FROM core_crm_attribution_adjustment a
                       JOIN core_principal target ON target.id=a.to_owner_id
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       p.public_id) owner_public_id,
                   COALESCE(r.receipts,0) receipts,COALESCE(f.refunds,0) refunds,
                   COALESCE(r.receipts,0)-COALESCE(f.refunds,0) net_cash_flow,
                   COALESCE(r.receipt_count,0) receipt_count,COALESCE(f.refund_count,0) refund_count,
                   CASE WHEN r.last_at IS NULL THEN f.last_at WHEN f.last_at IS NULL THEN r.last_at
                        WHEN r.last_at>f.last_at THEN r.last_at ELSE f.last_at END last_event_at
            """ + " FROM " + affectedOrders + " " + """
            e JOIN core_crm_order o ON o.id=e.order_id
            LEFT JOIN core_opportunity opp ON opp.id=o.opportunity_id LEFT JOIN core_customer c ON c.id=o.customer_id
            LEFT JOIN core_campus cp ON cp.id=o.campus_id JOIN core_principal p ON p.id=o.attributed_owner_id
            LEFT JOIN (SELECT order_id,SUM(amount) receipts,COUNT(id) receipt_count,MAX(received_at) last_at
                       FROM core_crm_receipt WHERE deleted=0 AND received_at>=? AND received_at<? GROUP BY order_id) r ON r.order_id=o.id
            LEFT JOIN (SELECT order_id,SUM(amount) refunds,COUNT(id) refund_count,MAX(refunded_at) last_at
                       FROM core_crm_refund WHERE deleted=0 AND refunded_at>=? AND refunded_at<? GROUP BY order_id) f ON f.order_id=o.id
            WHERE o.deleted=0 AND (""" + scope.sql() + """
            )
            GROUP BY o.id,o.public_id,opp.public_id,c.public_id,cp.public_id,owner_public_id,
                     r.receipts,r.receipt_count,r.last_at,f.refunds,f.refund_count,f.last_at
            ORDER BY last_event_at DESC,o.id DESC LIMIT ? OFFSET ?
            """;
        List<CashFlowMetric> items = jdbc.query(sql,
            (rs, row) -> new CashFlowMetric(rs.getString("order_public_id"),rs.getString("opportunity_public_id"),
                rs.getString("customer_public_id"),rs.getString("campus_public_id"),rs.getString("owner_public_id"),
                rs.getBigDecimal("receipts"),rs.getBigDecimal("refunds"),rs.getBigDecimal("net_cash_flow"),
                rs.getLong("receipt_count"),rs.getLong("refund_count"),rs.getTimestamp("last_event_at").toInstant()),
            cashDetailArgs(from, until, scope.args(), size, page).toArray());
        BigDecimal nonNullGross = gross == null ? BigDecimal.ZERO : gross;
        BigDecimal nonNullRefunds = refundTotal == null ? BigDecimal.ZERO : refundTotal;
        return new CashFlowReport(items,total == null ? 0 : total,nonNullGross,nonNullRefunds,
            nonNullGross.subtract(nonNullRefunds),fromAt,untilAt,Instant.now(),page,size);
    }

    /** Rechecks the current order's data scope before replaying a refund operation. */
    public void authorizeOrder(Actor actor, String orderId) { order(actor, orderId, false); }

    /** Rechecks the request's order scope before replaying a decision or settlement. */
    public void authorizeRequest(Actor actor, String requestId) { request(actor, requestId, false); }

    private void updateRefundBalance(OrderRow order, BigDecimal amount) {
        BigDecimal refunded = order.refunded().add(amount);
        String state = refunded.compareTo(order.received()) == 0 ? "full" : "partial";
        int changed = jdbc.update("""
            UPDATE core_crm_order SET refunded_amount=?,refund_status=?,version=version+1,
                updated_at=UTC_TIMESTAMP(3) WHERE id=? AND refunded_amount=? AND deleted=0
            """, refunded, state, order.id(), order.refunded());
        if (changed != 1) { throw conflict("订单退款余额已变化"); }
    }

    private void settleRequest(Actor actor, RequestRow request, long expectedVersion) {
        int changed = jdbc.update("""
            UPDATE core_crm_refund_request SET status='settled',version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status='approved' AND deleted=0
            """, request.id(), expectedVersion);
        if (changed != 1) { throw conflict("退款申请版本已变化"); }
    }

    private void requireAvailable(OrderRow order, BigDecimal reserved, BigDecimal requested) {
        BigDecimal available = order.received().subtract(order.refunded())
            .subtract(order.consumed()).subtract(reserved);
        if (requested.compareTo(available) > 0) { throw invalid("退款申请超过可退净实收余额"); }
    }

    private BigDecimal reservedAmount(long orderId, Long excludeRequestId) {
        String exclusion = excludeRequestId == null ? "" : " AND r.id<>?";
        BigDecimal total = excludeRequestId == null
            ? jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.amount),0) FROM core_crm_refund_request r
                WHERE r.order_id=? AND r.deleted=0 AND r.status IN ('pending_approval','approved')
                """, BigDecimal.class, orderId)
            : jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.amount),0) FROM core_crm_refund_request r
                WHERE r.order_id=? AND r.deleted=0 AND r.status IN ('pending_approval','approved')
                """ + exclusion, BigDecimal.class, orderId, excludeRequestId);
        return total == null ? BigDecimal.ZERO : total;
    }

    private BigDecimal sumInPeriod(String table, String timeColumn, Timestamp from, Timestamp until,
            ScopeSql.Clause scope) {
        List<Object> args = new ArrayList<>(List.of(from, until));
        args.addAll(scope.args());
        return jdbc.queryForObject("SELECT COALESCE(SUM(x.amount),0) FROM " + table + " x "
            + "JOIN core_crm_order o ON o.id=x.order_id WHERE x.deleted=0 AND x." + timeColumn
            + ">=? AND x." + timeColumn + "<? AND o.deleted=0 AND (" + scope.sql() + ")",
            BigDecimal.class, args.toArray());
    }

    private List<Object> cashArgs(Timestamp from, Timestamp until, List<Object> scopeArgs) {
        List<Object> args = new ArrayList<>(List.of(from, until, from, until));
        args.addAll(scopeArgs);
        return args;
    }

    private List<Object> cashDetailArgs(Timestamp from, Timestamp until, List<Object> scopeArgs,
            int size, int page) {
        List<Object> args = cashArgs(from, until, List.of());
        args.add(from);
        args.add(until);
        args.add(from);
        args.add(until);
        args.addAll(scopeArgs);
        args.add(size);
        args.add(Math.multiplyExact((long) page - 1, size));
        return args;
    }

    private OrderRow order(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = orderScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<OrderRow> rows = jdbc.query("""
            SELECT o.id,o.public_id,o.attributed_owner_id,o.campus_id,o.received_amount,o.refunded_amount,
                   COALESCE((SELECT SUM(x.amount) FROM core_consumption x
                       WHERE x.order_id=o.id AND x.deleted=0),0) AS consumed_amount
            FROM core_crm_order o WHERE o.public_id=? AND o.deleted=0 AND (""" + scope.sql() + ")"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new OrderRow(rs.getLong("id"),rs.getString("public_id"),
                rs.getLong("attributed_owner_id"),rs.getObject("campus_id", Long.class),
                rs.getBigDecimal("received_amount"),rs.getBigDecimal("refunded_amount"),
                rs.getBigDecimal("consumed_amount")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不在授权范围"); }
        return rows.get(0);
    }

    private RequestRow request(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = orderScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<RequestRow> rows = jdbc.query(requestSelect() + " WHERE r.public_id=? AND r.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), this::requestRow, args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "退款申请不存在或不在授权范围"); }
        return rows.get(0);
    }

    private String requestSelect() {
        return """
            SELECT r.id,r.public_id,r.order_id,o.public_id order_public_id,r.applicant_id,
                   a.public_id applicant_public_id,r.amount,r.reason_cipher,r.evidence_cipher,r.status,
                   r.reviewer_id,p.public_id reviewer_public_id,r.review_note_cipher,r.reviewed_at,r.version
            FROM core_crm_refund_request r JOIN core_crm_order o ON o.id=r.order_id
            JOIN core_principal a ON a.id=r.applicant_id LEFT JOIN core_principal p ON p.id=r.reviewer_id
            """;
    }

    private RequestRow requestRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        long reviewerId = rs.getLong("reviewer_id");
        Long reviewer = rs.wasNull() ? null : reviewerId;
        Timestamp reviewed = rs.getTimestamp("reviewed_at");
        return new RequestRow(rs.getLong("id"),rs.getString("public_id"),rs.getLong("order_id"),
            rs.getString("order_public_id"),rs.getLong("applicant_id"),rs.getString("applicant_public_id"),
            rs.getBigDecimal("amount"),rs.getString("reason_cipher"),rs.getString("evidence_cipher"),
            rs.getString("status"),reviewer,rs.getString("reviewer_public_id"),
            rs.getString("review_note_cipher"),reviewed,rs.getLong("version"));
    }

    private CrmRefundRequest requestDto(Actor actor, RequestRow row) {
        boolean readDetail = (actor.permissions().contains("crm.refund.read_detail") || LegacyFinancePermissions.legacyRefundReader(actor))
            || row.applicantPublicId().equals(actor.publicId());
        return new CrmRefundRequest(row.publicId(),row.orderPublicId(),row.applicantPublicId(),row.amount(),
            decrypt(readDetail ? row.reasonCipher() : null), decrypt(readDetail ? row.evidenceCipher() : null),
            row.status(),row.reviewerPublicId(),decrypt(readDetail ? row.reviewNoteCipher() : null),
            instant(row.reviewedAt()),row.version());
    }

    private void event(long requestId,Actor actor,String type,RefundTransition transition,String detail) {
        jdbc.update("""
            INSERT INTO core_crm_refund_event
                (public_id,request_id,actor_id,event_type,from_status,to_status,detail_cipher,occurred_at)
            VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
            """, "RFDEVT_" + UUID.randomUUID(),requestId,actor.id(),type,transition.from(),transition.to(),
            detail == null ? null : cipher.encrypt(detail));
    }

    private long requestId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_crm_refund_request WHERE public_id=?", Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "退款申请创建失败"); }
        return id;
    }

    private ScopeSql.Clause orderScope(Actor actor) {
        return ScopeSql.orders(actor);
    }

    private ScopeSql.Clause reportOrderScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("COALESCE((SELECT a.to_owner_id FROM core_crm_attribution_adjustment a "
                + "WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),o.attributed_owner_id)=?",
                List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }

    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.ALREADY_EXISTS, message); }
}
