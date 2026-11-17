package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.ConfirmReceiptInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

record CheckoutOrderBalance(long id, String publicId, BigDecimal total, BigDecimal received,
        String status, long version, long enrollmentId, String enrollmentPublicId,
        String enrollmentStatus, long classId, int lineIndex) {
    BigDecimal due() { return total.subtract(received); }
}

record CheckoutEvent(long checkoutId, BigDecimal amount, String method, String eventType) { }

record CheckoutEventWrite(long checkoutId, long actorId, String type, BigDecimal amount,
        String method, String referenceCipher, byte[] referenceHash) { }

/** Locks checkout orders and applies each all-or-nothing receipt transition. */
@Repository
class CommerceCheckoutPaymentRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    CommerceCheckoutPaymentRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    List<CheckoutOrderBalance> lockCheckoutOrders(long checkoutId) {
        return jdbc.query("SELECT o.id,o.public_id,o.total_amount,o.received_amount,o.status,o.version,"
            + "e.id enrollment_id,e.public_id enrollment_public_id,e.status enrollment_status,e.class_id,"
            + "l.line_index FROM core_crm_order o JOIN core_enrollment e ON e.order_id=o.id AND e.deleted=0 "
            + "JOIN core_commerce_checkout_buyer b ON b.checkout_id=o.commerce_checkout_id AND b.resolved_student_id=o.student_id AND b.deleted=0 "
            + "JOIN core_commerce_checkout_line l ON l.checkout_id=o.commerce_checkout_id AND l.buyer_index=b.buyer_index "
            + "AND l.class_id=e.class_id AND l.deleted=0 WHERE o.commerce_checkout_id=? AND o.deleted=0 "
            + "ORDER BY l.line_index FOR UPDATE", (rs, row) -> new CheckoutOrderBalance(rs.getLong("id"),
                rs.getString("public_id"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("received_amount"),
                rs.getString("status"), rs.getLong("version"), rs.getLong("enrollment_id"),
                rs.getString("enrollment_public_id"), rs.getString("enrollment_status"), rs.getLong("class_id"),
                rs.getInt("line_index")), checkoutId);
    }

    void ensureUniqueReceipt(long checkoutId, byte[] referenceHash, BigDecimal amount, String method) {
        List<CheckoutEvent> found = jdbc.query("SELECT checkout_id,amount,method,event_type FROM "
            + "core_commerce_checkout_event WHERE reference_hash=?", (rs, row) -> new CheckoutEvent(
                rs.getLong("checkout_id"), rs.getBigDecimal("amount"), rs.getString("method"),
                rs.getString("event_type")), referenceHash);
        if (!found.isEmpty()) {
            CheckoutEvent event = found.get(0);
            if (event.checkoutId() == checkoutId && event.amount().compareTo(amount) == 0
                    && (amount.signum() == 0 && event.eventType().equals("zero_due_confirmed")
                        || amount.signum() > 0 && event.eventType().equals("receipt_confirmed")
                            && event.method().equals(method.equals("bank") ? "bank_transfer" : method))) {
                return;
            }
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "收款凭据已用于其他商城申请或金额");
        }
        Integer receiptUse = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_receipt WHERE reference_hash=?",
            Integer.class, referenceHash);
        if (receiptUse != null && receiptUse > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "收款凭据已用于其他收款");
        }
    }

    void confirmZeroDue(Actor actor, CheckoutState checkout, byte[] referenceHash) {
        List<CheckoutOrderBalance> orders = lockCheckoutOrders(checkout.id());
        for (CheckoutOrderBalance order : orders) {
            if (order.total().signum() != 0 || order.received().signum() != 0
                    || !order.status().equals("pending") || !order.enrollmentStatus().equals("pending")) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, "零应收订单状态无效");
            }
            jdbc.update("UPDATE core_crm_order SET status='received',version=version+1,updated_at=UTC_TIMESTAMP(3) "
                + "WHERE id=? AND version=?", order.id(), order.version());
            jdbc.update("UPDATE core_enrollment SET status='active',activated_at=UTC_TIMESTAMP(3),version=version+1,"
                + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='pending'", order.enrollmentId());
        }
        jdbc.update("UPDATE core_commerce_checkout SET receipt_reference_hash=?,version=version+1,"
            + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=?", referenceHash, checkout.id(), checkout.version());
        insertEvent(new CheckoutEventWrite(checkout.id(), actor.id(), "zero_due_confirmed",
            new BigDecimal("0.00"), null, null, null));
    }

    void confirmOrders(Actor actor, CheckoutState checkout, List<CheckoutOrderBalance> orders,
            ConfirmReceiptInput input, byte[] checkoutReferenceHash) {
        String ledgerMethod = input.method().equals("bank") ? "bank_transfer" : "cash";
        for (CheckoutOrderBalance order : orders) { confirmOneOrder(actor, order, input, ledgerMethod); }
        jdbc.update("UPDATE core_commerce_checkout SET receipt_reference_hash=?,version=version+1,"
            + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=?", checkoutReferenceHash,
            checkout.id(), checkout.version());
        insertEvent(new CheckoutEventWrite(checkout.id(), actor.id(), "receipt_confirmed", input.amount(),
            ledgerMethod, cipher.encrypt(input.reference().trim()), checkoutReferenceHash));
    }

    private void confirmOneOrder(Actor actor, CheckoutOrderBalance order, ConfirmReceiptInput input, String method) {
        if (order.status().equals("closed") || order.status().equals("cancelled")
                || order.enrollmentStatus().equals("withdrawn") || !order.enrollmentStatus().equals("pending")) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "已有订单退出或报名不处于待生效状态");
        }
        String reference = input.reference().trim() + "/" + (order.lineIndex() + 1);
        byte[] refHash = cipher.fingerprint("payment_ref", reference);
        Integer used = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_receipt WHERE reference_hash=?",
            Integer.class, refHash);
        if (used != null && used > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "收款凭据已用于其他收款");
        }
        jdbc.update("INSERT INTO core_crm_receipt(public_id,order_id,actor_id,amount,method,reference_cipher,"
            + "reference_hash,received_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3))", "RCT_" + UUID.randomUUID(),
            order.id(), actor.id(), order.due(), method, cipher.encrypt(reference), refHash);
        int updated = jdbc.update("UPDATE core_crm_order SET received_amount=total_amount,status='received',"
            + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? "
            + "AND status IN ('pending','partially_received')", order.id(), order.version());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "订单状态已变化");
        }
        jdbc.update("UPDATE core_enrollment SET status='active',activated_at=UTC_TIMESTAMP(3),version=version+1,"
            + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='pending'", order.enrollmentId());
    }

    void insertEvent(CheckoutEventWrite event) {
        jdbc.update("INSERT INTO core_commerce_checkout_event(public_id,checkout_id,actor_id,event_type,"
            + "amount,method,reference_cipher,reference_hash,occurred_at) "
            + "VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))", "CHKEV_" + UUID.randomUUID(), event.checkoutId(),
            event.actorId(), event.type(), event.amount(), event.method(), event.referenceCipher(), event.referenceHash());
    }
}
