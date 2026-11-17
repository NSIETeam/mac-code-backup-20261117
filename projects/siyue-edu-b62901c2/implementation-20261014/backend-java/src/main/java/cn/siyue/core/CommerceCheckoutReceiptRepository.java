package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.CheckoutReceipt;
import cn.siyue.core.CommerceCheckoutDtos.QuoteLine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Projects persisted checkout rows to the public API receipt contract. */
@Repository
public class CommerceCheckoutReceiptRepository {
    private final JdbcTemplate jdbc;

    public CommerceCheckoutReceiptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    CheckoutReceipt receipt(CheckoutState checkout, boolean replayed) {
        List<QuoteLine> lines = jdbc.query("SELECT l.line_index,l.buyer_index,c.public_id class_id,"
            + "co.public_id course_id,cp.public_id campus_id,l.base_amount,l.coupon_discount,"
            + "l.promotion_discount,l.amount FROM core_commerce_checkout_line l "
            + "JOIN core_class c ON c.id=l.class_id JOIN core_course co ON co.id=l.course_id "
            + "JOIN core_campus cp ON cp.id=l.campus_id WHERE l.checkout_id=? AND l.deleted=0 "
            + "ORDER BY l.line_index", (rs, row) -> new QuoteLine(rs.getInt("line_index"),
                rs.getInt("buyer_index"), rs.getString("class_id"), rs.getString("course_id"),
                rs.getString("campus_id"), rs.getBigDecimal("base_amount"),
                rs.getBigDecimal("coupon_discount"), rs.getBigDecimal("promotion_discount"),
                rs.getBigDecimal("amount")), checkout.id());
        List<String> students = jdbc.query("SELECT s.public_id FROM core_commerce_checkout_buyer b "
            + "JOIN core_student s ON s.id=b.resolved_student_id WHERE b.checkout_id=? "
            + "AND b.resolved_student_id IS NOT NULL ORDER BY b.buyer_index", (rs, row) -> rs.getString(1),
            checkout.id());
        List<String> orders = jdbc.query("SELECT o.public_id FROM core_crm_order o "
            + "WHERE o.commerce_checkout_id=? AND o.deleted=0 ORDER BY o.id",
            (rs, row) -> rs.getString(1), checkout.id());
        String promotion = checkout.promotionId() == null ? null : jdbc.queryForObject(
            "SELECT public_id FROM core_commerce_promotion WHERE id=?", String.class, checkout.promotionId());
        return new CheckoutReceipt(checkout.publicId(), checkout.type(), checkout.status(), checkout.buyerCount(),
            promotion, checkout.quoteHash(), checkout.baseAmount(), checkout.discountAmount(), checkout.totalAmount(),
            checkout.expiresAt().toInstant(), checkout.version(), lines, students, orders, replayed);
    }
}
