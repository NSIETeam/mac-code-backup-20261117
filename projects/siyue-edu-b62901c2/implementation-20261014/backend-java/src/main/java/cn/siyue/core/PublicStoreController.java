package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.CheckoutReceipt;
import cn.siyue.core.CommerceCheckoutDtos.PublicStoreCatalog;
import cn.siyue.core.CommerceCheckoutDtos.PublicStoreQuote;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin public visitor endpoints; they do not use employee authentication.
 * The signed HttpOnly visitor cookie and request-rate limits restrict public quote, application and receipt access;
 * applications still await separate staff verification and offline receipt confirmation.
 * @see PublicStoreVisitor
 * @see PublicStoreService
 */
@RestController
@RequestMapping("/api/v2/public/store")
@Validated
public class PublicStoreController {
    private final PublicStoreService service;
    private final PublicStoreVisitor visitor;
    private final PublicStoreRequestParser parser;
    private final String origin;

    /** Injects public checkout behavior, visitor-cookie handling, request parsing and allowed web origin.
     * @param service public catalog, quote, application and visitor-scoped receipt operations
     * @param visitor signer/verifier for the anonymous visitor cookie; it is not an employee identity
     * @param parser strict parser for untrusted public checkout JSON
     * @param origin configured web origin required for public write requests
     */
    public PublicStoreController(PublicStoreService service, PublicStoreVisitor visitor,
            PublicStoreRequestParser parser,
            @Value("${siyue.web-origin}") String origin) {
        this.service = service;
        this.visitor = visitor;
        this.parser = parser;
        this.origin = origin;
    }

    /** Reads the public commerce catalog after validating any supplied origin and applying rate limits.
     * @param request servlet request used for origin and source-address checks
     * @return public catalog and current storefront capabilities
     * @throws BusinessException if a supplied origin is rejected or rate limit is exceeded
     */
    @GetMapping
    public ApiResponse<PublicStoreCatalog> catalog(HttpServletRequest request) {
        readOrigin(request);
        return ApiResponse.success(service.catalog(sourceAddress(request)));
    }

    /** Quotes an anonymous visitor's submitted cart; this does not create a checkout or authenticate staff.
     * @param body untrusted public-checkout JSON
     * @param request servlet request used for same-origin and source-address checks
     * @param response servlet response on which the signed visitor cookie may be issued
     * @return current quote for the verified visitor fingerprint
     * @throws BusinessException if origin, visitor, rate limit, request or quote validation fails
     */
    @PostMapping("/quote")
    public ApiResponse<PublicStoreQuote> quote(@RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        writeOrigin(request);
        String visitorHash = visitor.fingerprint(request, response);
        return ApiResponse.success(service.quoteRequest(visitorHash, sourceAddress(request), body, parser));
    }

    /** Creates a public checkout application bound to the verified anonymous visitor cookie.
     * The application is pending separate employee acceptance; this endpoint does not record a payment receipt.
     * @param key request idempotency key
     * @param body untrusted public-checkout JSON
     * @param request servlet request used for same-origin and source-address checks
     * @param response servlet response on which the signed visitor cookie may be issued
     * @return pending public application and whether it replays an earlier submission
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if origin, rate limit, visitor, request, or idempotency checks fail
     */
    @PostMapping("/checkouts")
    public ApiResponse<PublicStoreService.PublicApplication> create(@RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        writeOrigin(request);
        String visitorHash = visitor.fingerprint(request, response);
        return ApiResponse.success(service.createRequest(visitorHash, sourceAddress(request), key, body, parser));
    }

    /** Reads a receipt only for the visitor identity verified from this request's signed cookie.
     * A guessed checkout ID or employee identity is not accepted as visitor authorization.
     * @param id public checkout identifier, limited to 120 characters
     * @param request servlet request used for optional origin and source-address checks
     * @param response servlet response used when establishing the visitor session
     * @return receipt visible to a fingerprint derived from the verified browser cookie
     * @throws BusinessException if origin, rate limit, or visitor ownership checks fail
     */
    @GetMapping("/checkouts/{id}")
    public ApiResponse<CheckoutReceipt> status(@PathVariable @Size(max = 120) String id,
            HttpServletRequest request, HttpServletResponse response) {
        readOrigin(request);
        return ApiResponse.success(service.status(visitor.receiptFingerprints(request, response),
            sourceAddress(request), id));
    }

    private void readOrigin(HttpServletRequest request) {
        String supplied = request.getHeader("Origin");
        if (supplied != null && !origin.equals(supplied)) {
            throw new BusinessException(ErrorCode.ORIGIN_REJECTED, "请求来源未授权");
        }
    }

    private void writeOrigin(HttpServletRequest request) {
        if (!origin.equals(request.getHeader("Origin"))) {
            throw new BusinessException(ErrorCode.ORIGIN_REJECTED, "公开商城请求须从授权页面提交");
        }
    }

    private String sourceAddress(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请求来源无效");
        }
        if (!peer.equals("127.0.0.1") && !peer.equals("::1") && !peer.equals("0:0:0:0:0:0:0:1")) {
            return peer;
        }
        String forwarded = request.getHeader("X-Real-IP");
        if (forwarded == null || forwarded.length() > 45 || !forwarded.matches("[0-9A-Fa-f:.]+")) {
            return peer;
        }
        try { return InetAddress.getByName(forwarded).getHostAddress(); }
        catch (Exception ignored) { return peer; }
    }
}
