package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists a fully validated checkout quote and its immutable line snapshots. */
@Repository
public class CommerceCheckoutPersistenceRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;

    public CommerceCheckoutPersistenceRepository(JdbcTemplate jdbc, PiiCipher cipher, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    CheckoutState insert(CommerceCheckoutRepository.QuoteBuild quote, String visitorHash) {
        String publicId = "CHK_" + UUID.randomUUID();
        String snapshot = snapshot(quote);
        jdbc.update("INSERT INTO core_commerce_checkout(public_id,checkout_type,status,promotion_id,buyer_count,"
            + "quote_hash,quote_cipher,expires_at,promotion_signature,base_amount,discount_amount,total_amount,"
            + "created_by_id,visitor_hash,version) VALUES(?,?,'awaiting_verification',?,?,?,?,?,?,?,?,?,?,?,1)", publicId,
            quote.input().type(), quote.promotion() == null ? null : quote.promotion().id(), quote.buyers().size(),
            quote.quoteHash(), snapshot, Timestamp.from(quote.expires()), quote.promotionSignature(), quote.base(),
            quote.discount(), quote.total(), quote.actor() == null ? null : quote.actor().id(),
            visitorHash == null ? null : Base64.getUrlDecoder().decode(visitorHash));
        long checkoutId = internalId(publicId);
        insertCampusLinks(checkoutId, quote.lines());
        insertBuyers(checkoutId, quote.buyers());
        insertLines(checkoutId, quote.lines());
        return checkoutById(checkoutId);
    }

    CheckoutState publicDetail(String checkoutId, List<String> visitorHashes) {
        if (visitorHashes == null || visitorHashes.isEmpty() || visitorHashes.size() > 2) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "访客会话无效");
        }
        byte[][] hashes = visitorHashes.stream().map(Base64.getUrlDecoder()::decode).toArray(byte[][]::new);
        if (java.util.Arrays.stream(hashes).anyMatch(hash -> hash.length != 32)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "访客会话无效");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(hashes.length, "?"));
        Object[] arguments = new Object[hashes.length + 1];
        arguments[0] = checkoutId;
        System.arraycopy(hashes, 0, arguments, 1, hashes.length);
        List<CheckoutState> rows = jdbc.query("SELECT p.id,p.public_id,p.checkout_type,p.status,p.promotion_id,"
            + "p.buyer_count,p.quote_hash,p.quote_cipher,p.expires_at,p.promotion_signature,p.base_amount,"
            + "p.discount_amount,p.total_amount,p.version,p.created_by_id FROM core_commerce_checkout p "
            + "WHERE p.public_id=? AND p.visitor_hash IN (" + placeholders + ") AND p.deleted=0",
            this::mapCheckoutState, arguments);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "商城申请不存在"); }
        return rows.get(0);
    }

    private String snapshot(CommerceCheckoutRepository.QuoteBuild quote) {
        try {
            return cipher.encrypt(mapper.writeValueAsString(quote.quoteSnapshot()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize checkout quote", error);
        }
    }

    private void insertCampusLinks(long checkoutId, List<CommerceCheckoutRepository.Line> lines) {
        for (Long campusId : lines.stream().map(line -> line.item().campusId()).distinct().sorted().toList()) {
            jdbc.update("INSERT INTO core_commerce_checkout_campus(checkout_id,campus_id) VALUES(?,?)",
                checkoutId, campusId);
        }
    }

    private void insertBuyers(long checkoutId, List<CommerceCheckoutRepository.Buyer> buyers) {
        for (CommerceCheckoutRepository.Buyer buyer : buyers) {
            jdbc.update("INSERT INTO core_commerce_checkout_buyer(checkout_id,buyer_index,selected_student_id,"
                + "name_cipher,phone_cipher,grade) VALUES(?,?,?,?,?,?)", checkoutId, buyer.index(),
                buyer.selectedStudent(), cipher.encrypt(buyer.name()), cipher.encrypt(buyer.phone()), buyer.grade());
        }
    }

    private void insertLines(long checkoutId, List<CommerceCheckoutRepository.Line> lines) {
        for (CommerceCheckoutRepository.Line line : lines) {
            CommerceCheckoutRepository.ProductClass item = line.item();
            jdbc.update("INSERT INTO core_commerce_checkout_line(checkout_id,line_index,buyer_index,product_id,"
                + "course_id,class_id,campus_id,promotion_id,base_amount,coupon_discount,promotion_discount,amount,"
                + "product_version,course_version,class_version,class_signature,coupon_offer_id,coupon_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                checkoutId, line.index(), line.buyerIndex(), item.productId(), item.courseId(), item.classId(),
                item.campusId(), line.promotionId(), line.base(), line.couponDiscount(), line.promotionDiscount(),
                line.amount(), item.productVersion(), item.courseVersion(), item.classVersion(), line.signature(),
                line.coupon()==null?null:line.coupon().offerId(),line.coupon()==null?null:line.coupon().version());
        }
    }

    private long internalId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_commerce_checkout WHERE public_id=?", Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "商城关联写入失败"); }
        return id;
    }

    private CheckoutState checkoutById(long id) {
        List<CheckoutState> rows = jdbc.query("SELECT p.id,p.public_id,p.checkout_type,p.status,p.promotion_id,"
            + "p.buyer_count,p.quote_hash,p.quote_cipher,p.expires_at,p.promotion_signature,p.base_amount,"
            + "p.discount_amount,p.total_amount,p.version,p.created_by_id FROM core_commerce_checkout p "
            + "WHERE p.id=? AND p.deleted=0", this::mapCheckoutState, id);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "商城申请保存失败"); }
        return rows.get(0);
    }

    CheckoutState mapCheckoutState(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new CheckoutState(rs.getLong("id"), rs.getString("public_id"), rs.getString("checkout_type"),
            rs.getString("status"), nullableLong(rs, "promotion_id"), rs.getInt("buyer_count"),
            rs.getString("quote_hash"), rs.getString("quote_cipher"), rs.getTimestamp("expires_at"),
            rs.getString("promotion_signature"), rs.getBigDecimal("base_amount"),
            rs.getBigDecimal("discount_amount"), rs.getBigDecimal("total_amount"), rs.getLong("version"),
            nullableLong(rs, "created_by_id"));
    }

    private Long nullableLong(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        Number value = (Number) rs.getObject(name);
        return value == null ? null : value.longValue();
    }
}
