package cn.siyue.core;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Activates frozen direct-enrollment terms after their non-checkout order is fully received. */
@Repository
public class EnrollmentReceiptActivationRepository {
    private final JdbcTemplate jdbc;

    public EnrollmentReceiptActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The caller holds the order row lock and runs this method in the same transaction. */
    public void activateAfterFullReceipt(long orderId) {
        OrderState order = lockOrder(orderId);
        if (!isFullyReceivedDirectOrder(order)) {
            return;
        }
        List<EnrollmentState> enrollments = lockEnrollments(orderId);
        for (EnrollmentState enrollment : enrollments) {
            activateIfPending(orderId, order.total(), enrollment);
        }
    }

    private OrderState lockOrder(long orderId) {
        return jdbc.queryForObject("SELECT status,total_amount,received_amount,refunded_amount,commerce_checkout_id "
            + "FROM core_crm_order WHERE id=? AND deleted=0 FOR UPDATE", (rs, row) -> new OrderState(
                rs.getString("status"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("refunded_amount"), rs.getObject("commerce_checkout_id") != null), orderId);
    }

    private boolean isFullyReceivedDirectOrder(OrderState order) {
        return order != null && !order.checkout() && "received".equals(order.status())
            && order.total().compareTo(order.received()) == 0 && order.refunded().signum() == 0;
    }

    private List<EnrollmentState> lockEnrollments(long orderId) {
        return jdbc.query("SELECT id,status,class_id FROM core_enrollment WHERE order_id=? AND deleted=0 "
            + "ORDER BY id FOR UPDATE", (rs, row) -> new EnrollmentState(rs.getLong("id"),
                rs.getString("status"), rs.getLong("class_id")), orderId);
    }

    private void activateIfPending(long orderId, BigDecimal orderTotal, EnrollmentState enrollment) {
        if (!"pending".equals(enrollment.status())) {
            return;
        }
        requireFrozenTerms(orderId, orderTotal, enrollment);
        int changed = jdbc.update("UPDATE core_enrollment SET status='active',"
            + "activated_at=COALESCE(activated_at,UTC_TIMESTAMP(3)),version=version+1,"
            + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND order_id=? AND status='pending' AND deleted=0",
            enrollment.id(), orderId);
        if (changed != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "报名状态已变化，请刷新后重试");
        }
    }

    private void requireFrozenTerms(long orderId, BigDecimal orderTotal, EnrollmentState enrollment) {
        FrozenTerms terms = jdbc.queryForObject("SELECT COUNT(el.id) lesson_count,"
            + "COALESCE(SUM(el.unit_amount),0) lesson_total FROM core_enrollment_lesson el "
            + "JOIN core_lesson l ON l.id=el.source_lesson_id JOIN core_class c ON c.id=? AND c.course_id=l.course_id "
            + "WHERE el.order_id=? AND el.enrollment_id=? AND el.deleted=0", (rs, row) ->
                new FrozenTerms(rs.getInt("lesson_count"), rs.getBigDecimal("lesson_total")),
            enrollment.classId(), orderId, enrollment.id());
        if (terms == null || terms.lessonCount() == 0 || terms.lessonTotal().compareTo(orderTotal) != 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                "待生效报名的冻结讲次与订单金额不匹配，不能按当前课程重建权益");
        }
    }

    /** 判断是否足额实收后激活报名的订单状态和余额，显式区分商城整单。 */
    private record OrderState(String status, BigDecimal total, BigDecimal received, BigDecimal refunded,
            boolean checkout) { }
    /** 待激活报名的内部主键、当前状态和班级引用。 */
    private record EnrollmentState(long id, String status, long classId) { }
    /** 待激活权益的已冻结讲次数量及合计金额，用于核对完整购买条款。 */
    private record FrozenTerms(int lessonCount, BigDecimal lessonTotal) { }
}
