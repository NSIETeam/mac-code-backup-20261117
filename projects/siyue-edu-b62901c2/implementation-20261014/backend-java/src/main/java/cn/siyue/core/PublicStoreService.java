package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.CheckoutReceipt;
import cn.siyue.core.CommerceCheckoutDtos.CreateInput;
import cn.siyue.core.CommerceCheckoutDtos.PublicStoreCatalog;
import cn.siyue.core.CommerceCheckoutDtos.PublicStoreQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves the anonymous storefront catalog and visitor-bound checkout applications.
 * Visitor fingerprints and source-address limits constrain public requests; applications are not employee-authenticated
 * and this service neither confirms offline receipts nor collects payment.
 */
@Service
public class PublicStoreService {
    private final CommerceCheckoutRepository checkouts;
    private final PublicStoreCatalogRepository catalog;
    private final PublicStoreRateLimiter limiter;
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;

    /** Creates the public-store workflow with checkout, rate-limit, encrypted identity, and serialization support.
     * @param checkouts public quote, application, and visitor-bound status operations
     * @param limiter public-request and submission rate limiter
     * @param jdbc persistence for visitor-key idempotency reservations
     * @param cipher fingerprints visitor keys and encrypts saved checkout receipts
     * @param mapper parses/serializes request and receipt bodies
     */
    public PublicStoreService(CommerceCheckoutRepository checkouts, PublicStoreRateLimiter limiter,
            JdbcTemplate jdbc, PiiCipher cipher, ObjectMapper mapper) {
        this.checkouts = checkouts;
        this.catalog = new PublicStoreCatalogRepository(jdbc, cipher, mapper);
        this.limiter = limiter;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    /** Returns the public catalog after applying the source-address request limit.
     * @param address normalized network address used by the public rate limiter
     * @return public products, current promotions, storefront decoration, and checkout capability metadata
     * @throws BusinessException when the request rate limit is exceeded
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PublicStoreCatalog catalog(String address) {
        requirePublicLimit(address);
        return catalog.catalog();
    }

    /** Quotes a visitor's proposed checkout after applying the public request limit.
     * @param visitorHash opaque URL-safe visitor fingerprint used to scope public quote rules
     * @param address normalized network address used by the public rate limiter
     * @param input validated checkout type, buyers, class items, and optional quote hash
     * @return current itemized quote and quote hash; it does not create an application or accept payment
     * @throws BusinessException when the visitor, request limit, quote, or referenced classes are invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PublicStoreQuote quote(String visitorHash, String address, CreateInput input) {
        requirePublicLimit(address);
        return checkouts.publicQuote(input, visitorHash);
    }

    /** Applies the public bucket before parsing, matching the public request ordering.
     * @param visitorHash opaque URL-safe visitor fingerprint used to scope public quote rules
     * @param address normalized network address used by the public rate limiter
     * @param body untrusted request JSON parsed only after the public bucket is accepted
     * @param parser fixed public-checkout parser and validator
     * @return current itemized quote and quote hash; no application or payment is created
     * @throws BusinessException when rate limiting, request parsing, quote, or class validation fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PublicStoreQuote quoteRequest(String visitorHash, String address, JsonNode body,
            PublicStoreRequestParser parser) {
        requirePublicLimit(address);
        return checkouts.publicQuote(parser.parse(body), visitorHash);
    }

    /** Creates a visitor-bound checkout application after both public and submission rate checks.
     * @param visitorHash opaque URL-safe visitor fingerprint binding the application to its visitor
     * @param address normalized network address used for rate limits
     * @param key idempotency key of 16–128 letters, digits, underscores, or hyphens
     * @param input validated checkout type, buyers, and class items
     * @return checkout application and whether it replays a prior submission; this call does not confirm or collect payment
     * @throws BusinessException when visitor/key/request checks, rate limits, or checkout creation fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public PublicApplication create(String visitorHash, String address, String key, CreateInput input)
            throws JsonProcessingException {
        requirePublicLimit(address);
        requireSubmissionLimit(address);
        return createValidated(visitorHash, key, input);
    }

    /** Applies the public limit before parsing and the submission limit after request validation.
     * @param visitorHash opaque URL-safe visitor fingerprint binding the application to its visitor
     * @param address normalized network address used for rate limits
     * @param key idempotency key of 16–128 letters, digits, underscores, or hyphens
     * @param body untrusted checkout JSON parsed after the public rate check
     * @param parser fixed public-checkout parser and validator
     * @return checkout application and whether it replays a prior submission; it does not confirm or collect payment
     * @throws BusinessException when visitor/key/request checks, rate limits, or checkout creation fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public PublicApplication createRequest(String visitorHash, String address, String key, JsonNode body,
            PublicStoreRequestParser parser) throws JsonProcessingException {
        requirePublicLimit(address);
        CreateInput input = parser.parse(body);
        requireSubmissionLimit(address);
        return createValidated(visitorHash, key, input);
    }

    private PublicApplication createValidated(String visitorHash, String key, CreateInput input)
            throws JsonProcessingException {
        byte[] visitor = decodeHash(visitorHash);
        byte[] keyHash = cipher.fingerprint("public_store_key", validKey(key));
        byte[] requestHash = cipher.fingerprint("public_store_request", mapper.writeValueAsString(input));
        jdbc.update("INSERT IGNORE INTO core_public_store_idempotency(visitor_hash,request_key_hash,request_hash) "
            + "VALUES(?,?,?)", visitor, keyHash, requestHash);
        Idempotency row = idempotency(visitor, keyHash);
        if (!MessageDigest.isEqual(row.requestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "该提交编号已用于不同内容");
        }
        if (row.resultCipher() != null) {
            CheckoutReceipt replay = mapper.readValue(cipher.decrypt(row.resultCipher()), CheckoutReceipt.class);
            return new PublicApplication(withReplay(replay), true, false);
        }
        CheckoutReceipt result = checkouts.createPublic(input, visitorHash);
        long checkoutId = jdbc.queryForObject("SELECT id FROM core_commerce_checkout WHERE public_id=? AND deleted=0",
            Long.class, result.id());
        jdbc.update("UPDATE core_public_store_idempotency SET checkout_id=?,result_cipher=?,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE id=? AND result_cipher IS NULL", checkoutId,
            cipher.encrypt(mapper.writeValueAsString(result)), row.id());
        return new PublicApplication(result, false, false);
    }

    /** Reads a checkout only when it belongs to the supplied visitor fingerprint.
     * @param visitorHash opaque URL-safe visitor fingerprint associated with the checkout
     * @param address normalized network address used by the public rate limiter
     * @param checkoutId public ID of the checkout
     * @return public checkout receipt visible to that visitor
     * @throws BusinessException when the rate limit is exceeded or the visitor cannot access the checkout
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CheckoutReceipt status(String visitorHash, String address, String checkoutId) {
        requirePublicLimit(address);
        return checkouts.publicDetail(checkoutId, visitorHash);
    }

    /** Reads a checkout using the visitor fingerprints supplied for the current browser's public receipt session.
     * @param visitorHashes opaque fingerprints issued to this visitor session; no employee identity is accepted
     * @param address normalized network address used by the public rate limiter
     * @param checkoutId public ID of the checkout
     * @return public checkout receipt visible to at least one supplied visitor fingerprint
     * @throws BusinessException when the rate limit is exceeded or none of the fingerprints grants checkout access
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CheckoutReceipt status(List<String> visitorHashes, String address, String checkoutId) {
        requirePublicLimit(address);
        return checkouts.publicDetail(checkoutId, visitorHashes);
    }

    private void requirePublicLimit(String address) {
        if (!limiter.allow("public", address, Instant.now())) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
        }
    }

    private void requireSubmissionLimit(String address) {
        if (!limiter.allow("submission", address, Instant.now())) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "提交过于频繁，请稍后重试");
        }
    }

    private Idempotency idempotency(byte[] visitor, byte[] keyHash) {
        return jdbc.queryForObject("SELECT id,request_hash,result_cipher FROM core_public_store_idempotency "
            + "WHERE visitor_hash=? AND request_key_hash=? FOR UPDATE",
            (rs, row) -> new Idempotency(rs.getLong("id"), rs.getBytes("request_hash"),
                rs.getString("result_cipher")), visitor, keyHash);
    }

    private CheckoutReceipt withReplay(CheckoutReceipt receipt) {
        return new CheckoutReceipt(receipt.id(), receipt.type(), receipt.status(), receipt.buyerCount(),
            receipt.promotionId(), receipt.quoteHash(), receipt.baseAmount(), receipt.discountAmount(),
            receipt.totalAmount(), receipt.expiresAt(), receipt.version(), receipt.lines(), receipt.studentIds(),
            receipt.orderIds(), true);
    }

    private String validKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Idempotency-Key 格式无效");
        }
        return key;
    }

    private byte[] decodeHash(String hash) {
        try {
            byte[] value = Base64.getUrlDecoder().decode(hash);
            if (value.length != 32) { throw new IllegalArgumentException(); }
            return value;
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "访客会话无效");
        }
    }

    /** 公开访客幂等记录的内部主键、原请求摘要与加密回执，用于验证重试未换载荷。 */
    private record Idempotency(long id, byte[] requestHash, String resultCipher) { }
    /** Result of creating or replaying a public checkout application.
     * @param checkout visitor-visible checkout receipt
     * @param replayed true when the saved result for the same idempotency key was returned
     * @param paymentProviderReady payment-provider readiness reported by the public workflow; this service does not
     *                             itself collect or confirm payment
     */
    public record PublicApplication(CheckoutReceipt checkout, boolean replayed, boolean paymentProviderReady) { }
}
