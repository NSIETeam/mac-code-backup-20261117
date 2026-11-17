package cn.siyue.core;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** Immutable persistence projection for one staff checkout. */
record CheckoutState(long id, String publicId, String type, String status, Long promotionId,
        int buyerCount, String quoteHash, String quoteCipher, Timestamp expiresAt,
        String promotionSignature, BigDecimal baseAmount, BigDecimal discountAmount,
        BigDecimal totalAmount, long version, Long createdBy) { }
