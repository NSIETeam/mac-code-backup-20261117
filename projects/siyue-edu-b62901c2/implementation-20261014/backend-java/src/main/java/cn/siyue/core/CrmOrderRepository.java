package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmOrder;
import cn.siyue.core.CrmDtos.CrmReceipt;
import cn.siyue.core.CrmDtos.OrderInput;
import cn.siyue.core.CrmDtos.ReceiptInput;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores immutable order attribution and append-only received-payment facts. */
@Repository
public class CrmOrderRepository {
    /** 创建订单时授权核对的商机身份、客户、孩子、校区、负责人和阶段版本。 */
    private record OpportunityRef(long id, String publicId, long customerId, Long studentId,
            Long campusId, long ownerId, String stage, long version) { }
    /** 订单读写所需金额、状态与归因负责人投影，当前商机负责人另列以免混同历史归因。 */
    private record OrderRow(long id, String publicId, String opportunityId, String customerId,
            String studentId, String campusId, String ownerId, String currentOwnerId,
            String product, BigDecimal total,
            BigDecimal received, BigDecimal refunded, String refundStatus, String status, long version) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    /** Uses the shared PII keyring to protect payment references. */
    public CrmOrderRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Creates one order per opportunity and snapshots current deal ownership. */
    public CrmOrder create(Actor actor, OrderInput input) {
        OpportunityRef opportunity = opportunity(actor, input.opportunityId(), true);
        if (opportunity.version() != input.expectedOpportunityVersion()) { throw conflict("商机版本已变化"); }
        if (opportunity.stage().equals("won") || opportunity.stage().equals("closed")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "已成交或已关闭商机不能新建订单");
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_order WHERE opportunity_id=? AND deleted=0",
            Integer.class, opportunity.id());
        if (existing != null && existing > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该商机已有订单；分期收款应记入原订单");
        }
        var classQuote = input.classBinding()==null ? null : new CrmClassOrderRepository(jdbc)
            .confirmed(opportunity.campusId(),input.classBinding(),input.totalAmount());
        String publicId = "ORD_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_order
                (public_id,opportunity_id,customer_id,student_id,campus_id,attributed_owner_id,product_snapshot,total_amount)
            VALUES (?,?,?,?,?,?,?,?)
            """, publicId, opportunity.id(), opportunity.customerId(), opportunity.studentId(), opportunity.campusId(),
            opportunity.ownerId(), input.productSnapshot().trim(), input.totalAmount());
        if(classQuote!=null) new CrmClassOrderRepository(jdbc).save(internalOrderId(publicId),classQuote.terms());
        return dto(order(actor, publicId, false));
    }

    CrmDtos.ClassQuotePage classQuotes(Actor actor,String opportunityId,int page,int size) {
        return new CrmClassOrderRepository(jdbc).quotes(opportunity(actor,opportunityId,false).campusId(),page,size);
    }
    /** Lists orders from one consistent employee, campus or all-record scope. */
    public CrmDtos.OrderPage list(Actor actor, String opportunityId, int page, int size) {
        return list(actor, opportunityId, null, page, size);
    }
    /** Verifies customer visibility before intersecting it with the actor's order visibility. */
    public CrmDtos.OrderPage listForCustomer(Actor actor, String customerPublicId, int page, int size) {
        ScopeSql.Clause customerScope = ScopeSql.customerReads(actor);
        List<Object> customerArgs = new ArrayList<>(List.of(customerPublicId));
        customerArgs.addAll(customerScope.args());
        List<Long> customerIds = jdbc.query("SELECT c.id FROM core_customer c WHERE c.public_id=? "
                + "AND c.deleted=0 AND (" + customerScope.sql() + ")",
            (rs, row) -> rs.getLong(1), customerArgs.toArray());
        if (customerIds.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围");
        }
        return list(actor, null, customerIds.get(0), page, size);
    }
    private CrmDtos.OrderPage list(Actor actor, String opportunityId, Long customerId, int page, int size) {
        ScopeSql.Clause scope = orderScope(actor);
        String filter = "";
        List<Object> filterArgs = new ArrayList<>();
        if (opportunityId != null && !opportunityId.isBlank()) {
            opportunity(actor, opportunityId, false);
            filter = " AND o.opportunity_id=(SELECT x.id FROM core_opportunity x WHERE x.public_id=?)";
            filterArgs.add(opportunityId);
        }
        if (customerId != null) {
            filter += " AND o.customer_id=?";
            filterArgs.add(customerId);
        }
        String where = "o.deleted=0 AND (" + scope.sql() + ")" + filter;
        List<Object> countArgs = new ArrayList<>(scope.args()); countArgs.addAll(filterArgs);
        Long total = jdbc.queryForObject("SELECT COUNT(o.id) FROM core_crm_order o WHERE " + where,
            Long.class, countArgs.toArray());
        List<Object> args = new ArrayList<>(scope.args()); args.addAll(filterArgs);
        args.add(size); args.add(Math.multiplyExact((long) page - 1, size));
        List<OrderRow> rows = jdbc.query("""
            SELECT o.id,o.public_id,opp.public_id AS opportunity_public_id,c.public_id AS customer_public_id,
                   st.public_id AS student_public_id,cp.public_id AS campus_public_id,p.public_id AS owner_public_id,
                   COALESCE((SELECT target.public_id FROM core_crm_attribution_adjustment a
                       JOIN core_principal target ON target.id=a.to_owner_id
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       p.public_id) current_owner_public_id,
                   o.product_snapshot,o.total_amount,o.received_amount,o.refunded_amount,o.refund_status,o.status,o.version
            FROM core_crm_order o LEFT JOIN core_opportunity opp ON opp.id=o.opportunity_id
            LEFT JOIN core_customer c ON c.id=o.customer_id LEFT JOIN core_student st ON st.id=o.student_id
            LEFT JOIN core_campus cp ON cp.id=o.campus_id JOIN core_principal p ON p.id=o.attributed_owner_id
            """ + " WHERE " + where + " ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new OrderRow(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("opportunity_public_id"), rs.getString("customer_public_id"),
                rs.getString("student_public_id"), rs.getString("campus_public_id"),
                rs.getString("owner_public_id"), rs.getString("current_owner_public_id"), rs.getString("product_snapshot"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("refunded_amount"), rs.getString("refund_status"),
                rs.getString("status"), rs.getLong("version")), args.toArray());
        return new CrmDtos.OrderPage(rows.stream().map(this::dto).toList(), total == null ? 0 : total, page, size);
    }

    /** Aggregates receipt events by frozen order attribution and receipt occurrence time. */
    public CrmDtos.ReceiptReport receiptReport(Actor actor, Instant fromAt, Instant untilAt, int page, int size) {
        ScopeSql.Clause scope = reportOrderScope(actor);
        String where = "r.received_at>=? AND r.received_at<? AND r.deleted=0 AND o.deleted=0 AND ("
            + scope.sql() + ")";
        List<Object> countArgs = new ArrayList<>(List.of(Timestamp.from(fromAt), Timestamp.from(untilAt)));
        countArgs.addAll(scope.args());
        Long orderCount = jdbc.queryForObject("SELECT COUNT(DISTINCT o.id) FROM core_crm_receipt r "
            + "JOIN core_crm_order o ON o.id=r.order_id WHERE " + where, Long.class, countArgs.toArray());
        BigDecimal totalReceived = jdbc.queryForObject("SELECT COALESCE(SUM(r.amount),0) FROM core_crm_receipt r "
            + "JOIN core_crm_order o ON o.id=r.order_id WHERE " + where, BigDecimal.class, countArgs.toArray());
        List<Object> pageArgs = new ArrayList<>(countArgs);
        pageArgs.add(size);
        pageArgs.add(Math.multiplyExact((long) page - 1, size));
        List<CrmDtos.ReceiptMetric> items = jdbc.query("""
            SELECT o.public_id AS order_public_id,opp.public_id AS opportunity_public_id,
                   c.public_id AS customer_public_id,cp.public_id AS campus_public_id,
                   COALESCE((SELECT target.public_id FROM core_crm_attribution_adjustment a
                       JOIN core_principal target ON target.id=a.to_owner_id
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       p.public_id) AS owner_public_id,SUM(r.amount) AS period_received,
                   COUNT(r.id) AS receipt_count,MAX(r.received_at) AS last_received_at
            FROM core_crm_receipt r JOIN core_crm_order o ON o.id=r.order_id
            LEFT JOIN core_opportunity opp ON opp.id=o.opportunity_id
            LEFT JOIN core_customer c ON c.id=o.customer_id LEFT JOIN core_campus cp ON cp.id=o.campus_id
            JOIN core_principal p ON p.id=o.attributed_owner_id
            """ + " WHERE " + where + " GROUP BY o.id,o.public_id,opp.public_id,c.public_id,cp.public_id,owner_public_id "
                + "ORDER BY MAX(r.received_at) DESC,o.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new CrmDtos.ReceiptMetric(rs.getString("order_public_id"),
                rs.getString("opportunity_public_id"), rs.getString("customer_public_id"),
                rs.getString("campus_public_id"), rs.getString("owner_public_id"),
                rs.getBigDecimal("period_received"), rs.getLong("receipt_count"),
                rs.getTimestamp("last_received_at").toInstant()), pageArgs.toArray());
        return new CrmDtos.ReceiptReport(items, orderCount == null ? 0 : orderCount,
            totalReceived == null ? BigDecimal.ZERO : totalReceived, fromAt, untilAt, Instant.now(), page, size);
    }

    /** Locks a visible order, appends a receipt and advances sales status on the first receipt. */
    public CrmReceipt receive(Actor actor, String orderPublicId, ReceiptInput input) {
        OrderRow order = order(actor, orderPublicId, true);
        requireStandaloneOrder(order.id());
        if (order.version() != input.expectedOrderVersion()) { throw conflict("订单版本已变化"); }
        if (order.status().equals("cancelled") || order.status().equals("closed") || order.status().equals("received")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "当前订单状态不接受收款");
        }
        BigDecimal next = order.received().add(input.amount());
        if (next.compareTo(order.total()) > 0) { throw new BusinessException(ErrorCode.INVALID_INPUT, "收款金额超过订单未收余额"); }
        String receiptId = "RCT_" + UUID.randomUUID();
        byte[] referenceHash=receiptReference(input.reference());
        Instant receivedAt = input.receivedAt();
        jdbc.update("""
            INSERT INTO core_crm_receipt (public_id,order_id,actor_id,amount,method,reference_cipher,reference_hash,received_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, receiptId, order.id(), actor.id(), input.amount(), input.method(),
            input.reference() == null || input.reference().isBlank() ? null : cipher.encrypt(input.reference().trim()),referenceHash,
            Timestamp.from(receivedAt));
        String status = next.compareTo(order.total()) == 0 ? "received" : "partially_received";
        int changed = jdbc.update("""
            UPDATE core_crm_order SET received_amount=?,status=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, next, status, order.id(), input.expectedOrderVersion());
        if (changed != 1) { throw conflict("订单状态已变化"); }
        new EnrollmentReceiptActivationRepository(jdbc).activateAfterFullReceipt(order.id());
        if (order.received().signum() == 0) { markWon(order, actor); }
        return new CrmReceipt(receiptId, order.publicId(), actor.publicId(), input.amount(), input.method(),
            input.reference() == null || input.reference().isBlank() ? null : input.reference().trim(), receivedAt);
    }

    private void requireStandaloneOrder(long orderId) {
        if (jdbc.queryForObject("SELECT commerce_checkout_id IS NOT NULL FROM core_crm_order WHERE id=?",Boolean.class,orderId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"商城订单须通过整单核对收款入口办理");
        }
    }
    private byte[] receiptReference(String reference) {
        if(reference==null||reference.isBlank()) return null;
        byte[] hash=cipher.fingerprint("payment_ref",reference.trim());
        if(jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_receipt WHERE reference_hash=?",Integer.class,hash)>0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,"收款凭据已登记，请核对原收款记录");
        }
        return hash;
    }

    /** Reads recent append-only receipt facts after verifying order visibility. */
    public List<CrmReceipt> receipts(Actor actor, String orderPublicId) {
        OrderRow order = order(actor, orderPublicId, false);
        boolean canReadReference = actor.permissions().contains("crm.receipt.reference.read")
            || LegacyFinancePermissions.legacyOrderReader(actor);
        return jdbc.query("""
            SELECT r.public_id,p.public_id AS actor_public_id,r.amount,r.method,r.reference_cipher,r.received_at
            FROM core_crm_receipt r JOIN core_principal p ON p.id=r.actor_id
            WHERE r.order_id=? AND r.deleted=0 ORDER BY r.received_at DESC,r.id DESC LIMIT 100
            """, (rs, row) -> new CrmReceipt(rs.getString("public_id"), order.publicId(),
                rs.getString("actor_public_id"), rs.getBigDecimal("amount"), rs.getString("method"),
                !canReadReference || rs.getString("reference_cipher") == null
                    ? null : cipher.decrypt(rs.getString("reference_cipher")),
                rs.getTimestamp("received_at").toInstant()), order.id());
    }

    /** Rechecks order visibility before an idempotent replay is returned. */
    public void authorize(Actor actor, String orderPublicId) { order(actor, orderPublicId, false); }

    /** Rechecks the source opportunity without reapplying new-order eligibility to a successful retry. */
    public void authorizeOpportunity(Actor actor, String opportunityPublicId) {
        opportunity(actor, opportunityPublicId, false);
    }

    private void markWon(OrderRow order, Actor actor) {
        Long opportunityId = jdbc.queryForObject("SELECT opportunity_id FROM core_crm_order WHERE id=?", Long.class, order.id());
        if (opportunityId == null) { return; } // Direct enrollment does not create a sales conversion.
        freezeLeadConversionAttribution(opportunityId, order.id(), actor.id());
        List<String> stages = jdbc.query("SELECT stage FROM core_opportunity WHERE id=? AND deleted=0 FOR UPDATE",
            (rs, row) -> rs.getString(1), opportunityId);
        if (stages.isEmpty() || stages.get(0).equals("closed")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商机已关闭，不能确认成交");
        }
        if (stages.get(0).equals("won")) { return; }
        jdbc.update("""
            UPDATE core_opportunity SET stage='won',won_order_id=?,next_follow_at=NULL,
                version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND stage<>'closed' AND deleted=0
            """, order.id(), opportunityId);
        jdbc.update("""
            INSERT INTO core_opportunity_event
                (public_id,opportunity_id,actor_id,event_type,from_stage,to_stage,occurred_at,detail_cipher)
            VALUES (?,?,?,'stage',?,'won',UTC_TIMESTAMP(3),?)
            """, "OPPEVT_" + UUID.randomUUID(), opportunityId, actor.id(), stages.get(0),
            cipher.encrypt("订单实际收款确认成交：" + order.publicId()));
    }

    private void freezeLeadConversionAttribution(long opportunityId, long orderId, long actorId) {
        List<Long> links = jdbc.query("""
            SELECT source_lead_id FROM core_opportunity WHERE id=? AND deleted=0
                AND source_lead_id IS NOT NULL FOR UPDATE
            """, (rs, row) -> rs.getLong(1), opportunityId);
        if (links.isEmpty()) { return; }
        long leadId = links.get(0);
        List<ConversionOwner> owners = jdbc.query("""
            SELECT owner_id,campus_id FROM core_crm_lead WHERE id=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> new ConversionOwner(nullableLong(rs, "owner_id"),
                nullableLong(rs, "campus_id")), leadId);
        if (owners.isEmpty() || owners.get(0).ownerId() == null) { return; }
        jdbc.update("""
            INSERT INTO core_crm_lead_conversion_snapshot
                (public_id,lead_id,opportunity_id,order_id,owner_id,campus_id,actor_id,converted_at)
            SELECT ?,?,?,?,?,?,?,UTC_TIMESTAMP(3)
            WHERE NOT EXISTS (SELECT id FROM core_crm_lead_conversion_snapshot WHERE lead_id=? AND deleted=0)
            """, "CONV_" + UUID.randomUUID(), leadId, opportunityId, orderId, owners.get(0).ownerId(),
            owners.get(0).campusId(), actorId, leadId);
    }

    /** 首次有效实收时选定的销售归因员工及校区，不随后续商机转交静默改写。 */
    private record ConversionOwner(Long ownerId, Long campusId) { }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) { return null; }
        if (!(value instanceof Number number)) { throw new SQLException("Expected numeric " + column); }
        try {
            if (number instanceof BigInteger integer) { return integer.longValueExact(); }
            if (number instanceof BigDecimal decimal) { return decimal.longValueExact(); }
        } catch (ArithmeticException error) {
            throw new SQLException("Numeric value is outside Java long range: " + column, error);
        }
        return number.longValue();
    }

    private OrderRow order(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = orderScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<OrderRow> rows = jdbc.query("""
            SELECT o.id,o.public_id,opp.public_id AS opportunity_public_id,c.public_id AS customer_public_id,
                   st.public_id AS student_public_id,cp.public_id AS campus_public_id,p.public_id AS owner_public_id,
                   COALESCE((SELECT target.public_id FROM core_crm_attribution_adjustment a
                       JOIN core_principal target ON target.id=a.to_owner_id
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       p.public_id) current_owner_public_id,
                   o.product_snapshot,o.total_amount,o.received_amount,o.refunded_amount,o.refund_status,o.status,o.version
            FROM core_crm_order o LEFT JOIN core_opportunity opp ON opp.id=o.opportunity_id
            LEFT JOIN core_customer c ON c.id=o.customer_id LEFT JOIN core_student st ON st.id=o.student_id
            LEFT JOIN core_campus cp ON cp.id=o.campus_id JOIN core_principal p ON p.id=o.attributed_owner_id
            WHERE o.public_id=? AND o.deleted=0 AND (""" + scope.sql() + ")"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new OrderRow(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("opportunity_public_id"), rs.getString("customer_public_id"),
                rs.getString("student_public_id"), rs.getString("campus_public_id"),
                rs.getString("owner_public_id"), rs.getString("current_owner_public_id"), rs.getString("product_snapshot"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("refunded_amount"), rs.getString("refund_status"),
                rs.getString("status"), rs.getLong("version")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不在授权范围"); }
        return rows.get(0);
    }

    private OpportunityRef opportunity(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("o.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<OpportunityRef> rows = jdbc.query("""
            SELECT o.id,o.public_id,o.customer_id,o.student_id,o.campus_id,o.owner_id,o.stage,o.version
            FROM core_opportunity o WHERE o.public_id=? AND o.deleted=0 AND (""" + scope.sql() + ")"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new OpportunityRef(rs.getLong("id"),rs.getString("public_id"),rs.getLong("customer_id"),
                rs.getObject("student_id", Long.class),rs.getObject("campus_id", Long.class),rs.getLong("owner_id"),
                rs.getString("stage"),rs.getLong("version")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "商机不存在或不在授权范围"); }
        return rows.get(0);
    }

    private ScopeSql.Clause orderScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("(o.attributed_owner_id=? OR EXISTS "
                + "(SELECT a.id FROM core_crm_attribution_adjustment a WHERE a.order_id=o.id AND a.deleted=0 "
                + "AND (a.from_owner_id=? OR a.to_owner_id=?)))", List.of(actor.id(),actor.id(),actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
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

    private CrmOrder dto(OrderRow row) {
        return new CrmOrder(row.publicId(), row.opportunityId(), row.customerId(), row.studentId(),
            row.campusId(), row.ownerId(), row.currentOwnerId(), row.product(), row.total(), row.received(), row.refunded(),
            row.refundStatus(), row.status(), row.version(),new CrmClassOrderRepository(jdbc).read(row.id()));
    }
    private long internalOrderId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_crm_order WHERE public_id=?", Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "订单创建失败"); }
        return id;
    }
    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.ALREADY_EXISTS, message);
    }
}
