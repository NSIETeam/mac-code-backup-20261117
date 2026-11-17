package cn.siyue.core;

import cn.siyue.core.EnrollmentExitDtos.CheckoutCancelReceipt;
import cn.siyue.core.EnrollmentExitDtos.Receipt;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists cancellation, withdrawal, refund reservations and checkout-wide exits atomically. */
@Repository
public class EnrollmentExitRepository {
    private record Enrollment(long id, String publicId, String status, long version, long studentId,
            String studentPublicId, long classId, long orderId, String orderPublicId, String orderStatus,
            BigDecimal received, BigDecimal refunded, Long checkoutId, Timestamp activatedAt) { }
    private record Checkout(long id, String publicId, String type, String status, Long promotionId,
            int buyerCount, Long createdById, long version) { }
    private record EnrollmentExitWrite(Actor actor, Enrollment row, boolean withdrawal, String eventId,
            String reason, Timestamp at) { }
    private record ExitEventWrite(Actor actor, Enrollment row, String eventId, String reason,
            Timestamp at, String enrollmentStatus, String orderStatus, BigDecimal amount, String calculation,
            boolean withdrawal) { }
    private record RefundRequestWrite(Actor actor, Enrollment row, BigDecimal amount, String reason,
            long exitEventId, Timestamp at) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    /** Uses encrypted reasons and the caller's surrounding database transaction. */
    public EnrollmentExitRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Hides enrollment identifiers outside the current student, class and order scope. */
    public void authorizeEnrollment(Actor actor, String enrollmentId) {
        enrollment(actor, enrollmentId, false);
    }

    /** Hides checkout identifiers outside the creator or complete campus/order scope. */
    public void authorizeCheckout(Actor actor, String checkoutId) {
        Checkout row = checkout(actor, checkoutId, false);
        checkoutEnrollments(actor, row);
    }

    /** Cancels a pending unpaid enrollment while serializing with transfers and refunds. */
    public Receipt cancel(Actor actor, String enrollmentId, String reason, long expectedVersion) {
        Enrollment row = lockEnrollment(actor, enrollmentId);
        validateVersion(row, expectedVersion);
        if (!"pending".equals(row.status()) || row.activatedAt() != null || row.received().signum() != 0
                || !"pending".equals(row.orderStatus()) || row.checkoutId() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                row.checkoutId() == null ? "仅能取消未付款、未生效报名" : "商城报名须通过整单取消入口");
        }
        return persistExit(actor, row, reason, false, null);
    }

    /** Withdraws one live enrollment and reserves only its remaining refundable balance. */
    public Receipt withdraw(Actor actor, String enrollmentId, String reason, long expectedVersion) {
        Enrollment row = lockEnrollment(actor, enrollmentId);
        validateVersion(row, expectedVersion);
        if (!List.of("active", "pending").contains(row.status())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "该报名已办理退出");
        }
        if (!List.of("pending", "partially_received", "received").contains(row.orderStatus())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "该订单当前不能办理退课");
        }
        if (row.checkoutId() != null && "pending".equals(row.status()) && row.received().signum() == 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商城未付款报名须通过整单取消入口");
        }
        ensureNoOpenRefund(row.orderId());
        BigDecimal refundable = refundableAmount(row);
        return persistExit(actor, row, reason, true, refundable);
    }

    /** Cancels an awaiting request or all its accepted unpaid enrollments in one transaction. */
    public CheckoutCancelReceipt cancelCheckout(Actor actor, String checkoutId, String reason,
            long expectedVersion) {
        Checkout checkout = checkout(actor, checkoutId, true);
        List<Enrollment> rows = checkoutEnrollments(actor, checkout);
        lockCheckoutChildren(rows);
        checkout = checkout(actor, checkoutId, true);
        rows = checkoutEnrollments(actor, checkout);
        if (checkout.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "商城申请已更新，请刷新后重试");
        }
        if (!List.of("awaiting_verification", "accepted").contains(checkout.status())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商城申请已经取消");
        }
        List<Receipt> results = new ArrayList<>();
        if ("accepted".equals(checkout.status())) {
            if (rows.isEmpty()) { throw new BusinessException(ErrorCode.INVALID_INPUT, "商城申请缺少订单报名关联"); }
            for (Enrollment row : rows) {
                validateCheckoutEnrollment(row);
                results.add(persistExit(actor, row, reason, false, null));
            }
        } else if (!rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "待核对商城申请不应已有报名订单");
        }
        markCheckoutCancelled(actor, checkout, reason);
        return checkoutReceipt(checkout, results);
    }

    private void validateCheckoutEnrollment(Enrollment row) {
        if (!"pending".equals(row.status()) || row.activatedAt() != null || row.received().signum() != 0
                || !"pending".equals(row.orderStatus())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商城整单取消要求所有订单均未收款且未生效");
        }
    }

    private void markCheckoutCancelled(Actor actor, Checkout row, String reason) {
        int changed = jdbc.update("UPDATE core_commerce_checkout SET status='cancelled',cancelled_by_id=?,"
            + "cancelled_at=?,cancel_reason_cipher=?,version=version+1,updated_at=? "
            + "WHERE id=? AND version=? AND status IN ('awaiting_verification','accepted') AND deleted=0",
            actor.id(), now(), cipher.encrypt(reason), now(), row.id(), row.version());
        if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "商城申请已更新"); }
        if (row.status().equals("accepted") && row.promotionId() != null) {
            int decrement = row.type().equals("group") ? row.buyerCount() : 1;
            jdbc.update("UPDATE core_commerce_promotion SET usage_count=GREATEST(0,usage_count-?),"
                + "version=version+1,updated_at=? WHERE id=? AND deleted=0", decrement, now(), row.promotionId());
        }
    }

    private Enrollment lockEnrollment(Actor actor, String publicId) {
        Enrollment observed = enrollment(actor, publicId, false);
        if (observed.checkoutId() != null) {
            jdbc.queryForObject("SELECT id FROM core_commerce_checkout WHERE id=? AND deleted=0 FOR UPDATE",
                Long.class, observed.checkoutId());
        }
        jdbc.queryForObject("SELECT id FROM core_class WHERE id=? AND deleted=0 FOR UPDATE", Long.class,
            observed.classId());
        jdbc.queryForObject("SELECT id FROM core_enrollment WHERE id=? AND deleted=0 FOR UPDATE", Long.class,
            observed.id());
        jdbc.queryForObject("SELECT id FROM core_student WHERE id=? AND deleted=0 FOR UPDATE", Long.class,
            observed.studentId());
        jdbc.queryForObject("SELECT id FROM core_crm_order WHERE id=? AND deleted=0 FOR UPDATE", Long.class,
            observed.orderId());
        return enrollment(actor, publicId, false);
    }

    private Receipt persistExit(Actor actor, Enrollment row, String reason, boolean withdrawal,
            BigDecimal refundable) {
        String eventPublicId = "ENREXIT_" + UUID.randomUUID();
        Instant occurred = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Timestamp timestamp = Timestamp.from(occurred);
        String enrollmentStatus = withdrawal ? "withdrawn" : "cancelled";
        String orderStatus = withdrawal ? "closed" : "cancelled";
        String calculation = withdrawal ? "received_minus_refunds_and_consumption" : null;
        BigDecimal amount = withdrawal ? refundable : BigDecimal.ZERO;
        writeEnrollmentExit(new EnrollmentExitWrite(actor, row, withdrawal, eventPublicId, reason, timestamp));
        writeOrderExit(row, orderStatus, timestamp);
        long exitEventId = writeExitEvent(new ExitEventWrite(actor, row, eventPublicId, reason, timestamp,
            enrollmentStatus, orderStatus, amount, calculation, withdrawal));
        int cancelledAdjustments = withdrawal ? cancelAdjustments(actor, row, timestamp) : 0;
        String refundId = withdrawal && amount.signum() > 0
            ? createRefundRequest(new RefundRequestWrite(actor, row, amount, reason, exitEventId, timestamp)) : null;
        return new Receipt(row.publicId(), row.orderPublicId(), enrollmentStatus, amount, refundId,
            cancelledAdjustments, row.version() + 1, occurred);
    }

    private void writeEnrollmentExit(EnrollmentExitWrite write) {
        Actor actor = write.actor();
        Enrollment row = write.row();
        boolean withdrawal = write.withdrawal();
        String reason = write.reason();
        Timestamp at = write.at();
        String sql = withdrawal
            ? "UPDATE core_enrollment SET status='withdrawn',withdrawn_at=?,withdrawal_reason_cipher=?,"
                + "withdrawn_by_id=?,withdrawal_audit_event_id=?,version=version+1,updated_at=? "
                + "WHERE id=? AND status IN ('active','pending')"
            : "UPDATE core_enrollment SET status='cancelled',cancelled_at=?,cancel_reason_cipher=?,"
                + "cancelled_by_id=?,cancel_audit_event_id=?,version=version+1,updated_at=? "
                + "WHERE id=? AND status='pending'";
        if (jdbc.update(sql, at, cipher.encrypt(reason), actor.id(), write.eventId(), at, row.id()) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "报名状态已变化");
        }
        int placement = jdbc.update("UPDATE core_enrollment_placement SET version=? "
            + "WHERE enrollment_id=? AND version=?", row.version() + 1, row.id(), row.version());
        if (placement == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(enrollment_id) FROM core_enrollment_placement "
                + "WHERE enrollment_id=?", Integer.class, row.id());
            if (exists != null && exists > 0) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, "报名转班版本已变化");
            }
        }
    }

    private void writeOrderExit(Enrollment row, String status, Timestamp at) {
        int changed = jdbc.update("UPDATE core_crm_order SET status=?,version=version+1,updated_at=? "
            + "WHERE id=? AND status=? AND deleted=0", status, at, row.orderId(), row.orderStatus());
        if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "订单状态已变化"); }
    }

    private long writeExitEvent(ExitEventWrite write) {
        Actor actor = write.actor();
        Enrollment row = write.row();
        jdbc.update("INSERT INTO core_enrollment_exit_event(public_id,enrollment_id,order_id,checkout_id,actor_id,"
            + "exit_type,enrollment_from_status,enrollment_to_status,order_from_status,order_to_status,reason_cipher,"
            + "refund_calculation,refundable_amount,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            write.eventId(), row.id(), row.orderId(), row.checkoutId(), actor.id(),
            write.withdrawal() ? "withdraw" : "cancel_unpaid", row.status(), write.enrollmentStatus(),
            row.orderStatus(), write.orderStatus(), cipher.encrypt(write.reason()), write.calculation(),
            write.amount(), write.at());
        return jdbc.queryForObject("SELECT id FROM core_enrollment_exit_event WHERE public_id=?", Long.class,
            write.eventId());
    }

    private String createRefundRequest(RefundRequestWrite write) {
        Actor actor = write.actor();
        Enrollment row = write.row();
        String publicId = "RFDREQ_" + UUID.randomUUID();
        jdbc.update("INSERT INTO core_crm_refund_request(public_id,order_id,applicant_id,amount,reason_cipher,"
            + "enrollment_exit_event_id) VALUES(?,?,?,?,?,?)",
            publicId, row.orderId(), actor.id(), write.amount(), cipher.encrypt(write.reason()), write.exitEventId());
        Long requestId = jdbc.queryForObject("SELECT id FROM core_crm_refund_request WHERE public_id=?",
            Long.class, publicId);
        jdbc.update("INSERT INTO core_crm_refund_event(public_id,request_id,actor_id,event_type,from_status,"
            + "to_status,detail_cipher,occurred_at) VALUES(?,?,?,'requested',NULL,'pending_approval',?,?)",
            "RFDEVT_" + UUID.randomUUID(), requestId, actor.id(), cipher.encrypt(write.reason()), write.at());
        return publicId;
    }

    private int cancelAdjustments(Actor actor, Enrollment enrollment, Timestamp at) {
        List<Long> ids = jdbc.query("SELECT id FROM core_session_adjustment WHERE enrollment_id=? "
            + "AND status='active' AND deleted=0 ORDER BY id FOR UPDATE", (rs, row) -> rs.getLong(1), enrollment.id());
        for (Long id : ids) {
            String eventId = "SEADJEVT_" + UUID.randomUUID();
            int changed = jdbc.update("UPDATE core_session_adjustment SET status='cancelled',cancelled_at=?,"
                + "cancelled_by_id=?,cancel_reason_cipher=?,version=version+1,updated_at=? "
                + "WHERE id=? AND status='active' AND deleted=0", at, actor.id(), cipher.encrypt("原报名退课"), at, id);
            if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "课次调整状态已变化"); }
            jdbc.update("INSERT INTO core_session_adjustment_event(public_id,adjustment_id,actor_id,event_type,"
                + "from_status,to_status,reason_cipher,occurred_at) VALUES(?,?,?,'cancelled','active','cancelled',?,?)",
                eventId, id, actor.id(), cipher.encrypt("原报名退课"), at);
        }
        return ids.size();
    }

    private BigDecimal refundableAmount(Enrollment row) {
        ensureNoOpenRefund(row.orderId());
        BigDecimal consumed = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM core_consumption "
            + "WHERE order_id=? AND deleted=0", BigDecimal.class, row.orderId());
        BigDecimal available = row.received().subtract(row.refunded()).subtract(consumed == null
            ? BigDecimal.ZERO : consumed);
        return available.max(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private void ensureNoOpenRefund(long orderId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_refund_request WHERE order_id=? "
            + "AND deleted=0 AND status IN ('pending_approval','approved')", Integer.class, orderId);
        if (count != null && count > 0) { throw new BusinessException(ErrorCode.INVALID_INPUT, "已有退款申请"); }
    }

    private void validateVersion(Enrollment row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "报名已更新，请刷新后重试");
        }
    }

    private Enrollment enrollment(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause students = ScopeSql.studentWrite(actor);
        ScopeSql.Clause classes = ScopeSql.classes(actor);
        ScopeSql.Clause orders = ScopeSql.orders(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(students.args()); args.addAll(classes.args()); args.addAll(orders.args());
        String sql = """
            SELECT e.id,e.public_id,e.status,e.version,s.id student_id,s.public_id student_public_id,c.id class_id,
                o.id order_id,o.public_id order_public_id,o.status order_status,o.received_amount,o.refunded_amount,
                o.commerce_checkout_id,e.activated_at
            FROM core_effective_enrollment e JOIN core_student s ON s.id=e.student_id
            JOIN core_class c ON c.id=e.class_id JOIN core_crm_order o ON o.id=e.order_id
            WHERE e.public_id=? AND e.deleted=0 AND s.deleted=0 AND c.deleted=0 AND o.deleted=0
            AND (""" + students.sql() + ") AND (" + classes.sql() + ") AND (" + orders.sql() + ")"
            + (lock ? " FOR UPDATE" : "");
        List<Enrollment> rows = jdbc.query(sql, (rs, n) -> new Enrollment(rs.getLong("id"),
            rs.getString("public_id"), rs.getString("status"), rs.getLong("version"),
            rs.getLong("student_id"), rs.getString("student_public_id"), rs.getLong("class_id"),
            rs.getLong("order_id"), rs.getString("order_public_id"), rs.getString("order_status"),
            rs.getBigDecimal("received_amount"), rs.getBigDecimal("refunded_amount"),
            nullableLong(rs, "commerce_checkout_id"), rs.getTimestamp("activated_at")), args.toArray());
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "报名不存在或超出授权范围"); }
        return rows.get(0);
    }

    private Checkout checkout(Actor actor, String publicId, boolean lock) {
        List<Checkout> rows = jdbc.query("SELECT p.id,p.public_id,p.checkout_type,p.status,p.promotion_id,"
            + "p.buyer_count,p.created_by_id,p.version FROM core_commerce_checkout p WHERE p.public_id=? "
            + "AND p.deleted=0 AND " + checkoutScope(actor) + (lock ? " FOR UPDATE" : ""), (rs, n) ->
            new Checkout(rs.getLong("id"), rs.getString("public_id"), rs.getString("checkout_type"),
                rs.getString("status"), nullableLong(rs, "promotion_id"), rs.getInt("buyer_count"),
                nullableLong(rs, "created_by_id"), rs.getLong("version")), checkoutScopeArgs(actor, publicId));
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "商城申请不存在或超出授权范围"); }
        return rows.get(0);
    }

    private String checkoutScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> "1=1";
            case "campus" -> "EXISTS (SELECT 1 FROM core_commerce_checkout_campus cc "
                + "WHERE cc.checkout_id=p.id AND cc.deleted=0 AND cc.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc "
                + "WHERE pc.principal_id=? AND pc.deleted=0)) AND NOT EXISTS (SELECT 1 "
                + "FROM core_commerce_checkout_campus cc WHERE cc.checkout_id=p.id AND cc.deleted=0 AND cc.campus_id NOT IN "
                + "(SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0))";
            case "own" -> "p.created_by_id=?";
            default -> "1=0";
        };
    }

    private Object[] checkoutScopeArgs(Actor actor, String publicId) {
        return switch (actor.scope()) {
            case "campus" -> new Object[] {publicId, actor.id(), actor.id()};
            case "own" -> new Object[] {publicId, actor.id()};
            default -> new Object[] {publicId};
        };
    }

    private List<Enrollment> checkoutEnrollments(Actor actor, Checkout checkout) {
        List<String> publicIds = jdbc.query("SELECT e.public_id FROM core_effective_enrollment e "
            + "JOIN core_crm_order o ON o.id=e.order_id WHERE o.commerce_checkout_id=? AND e.deleted=0 "
            + "AND o.deleted=0 ORDER BY e.id", (rs, row) -> rs.getString(1), checkout.id());
        int linkedOrders = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_order WHERE commerce_checkout_id=? "
            + "AND deleted=0", Integer.class, checkout.id());
        if (publicIds.size() != linkedOrders) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商城整单存在未关联报名的订单");
        }
        List<Enrollment> rows = new ArrayList<>();
        for (String id : publicIds) { rows.add(enrollment(actor, id, false)); }
        return rows;
    }

    private void lockCheckoutChildren(List<Enrollment> rows) {
        rows.stream().map(Enrollment::classId).distinct().sorted().forEach(id -> lock("core_class", id));
        rows.stream().map(Enrollment::id).sorted().forEach(id -> lock("core_enrollment", id));
        rows.stream().map(Enrollment::studentId).distinct().sorted().forEach(id -> lock("core_student", id));
        rows.stream().map(Enrollment::orderId).distinct().sorted().forEach(id -> lock("core_crm_order", id));
    }

    private void lock(String table, long id) {
        jdbc.queryForObject("SELECT id FROM " + table + " WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id);
    }

    private CheckoutCancelReceipt checkoutReceipt(Checkout checkout, List<Receipt> results) {
        return new CheckoutCancelReceipt(checkout.publicId(), "cancelled", List.copyOf(results),
            checkout.version() + 1, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private Timestamp now() { return Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); }
    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
}
