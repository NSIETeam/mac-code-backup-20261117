package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmOrder;
import cn.siyue.core.CrmDtos.CrmReceipt;
import cn.siyue.core.CrmDtos.OrderInput;
import cn.siyue.core.CrmDtos.OrderPage;
import cn.siyue.core.CrmDtos.ReceiptInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Provides versioned order attribution and receipt ledger endpoints. */
@RestController
@RequestMapping("/api/v2/crm/orders")
@Validated
public class CrmOrderController {
    private final AccessRepository access;
    private final CrmOrderService service;

    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     */
    public CrmOrderController(AccessRepository access, CrmOrderService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Returns class quotes available in the requested authorized opportunity campus.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param opportunityId value supplied to the request handler.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/class-quotes")
    public ApiResponse<CrmDtos.ClassQuotePage> classQuotes(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @jakarta.validation.constraints.NotBlank @Size(max=120) String opportunityId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.classQuotes(actor(jwt),opportunityId,page,size));
    }
    /**
     * Lists orders in the actor’s current scope using bounded pagination and optional opportunity filter.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param opportunityId value supplied to the request handler.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping
    public ApiResponse<OrderPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String opportunityId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(actor(jwt), opportunityId, page, size));
    }

    /**
     * Lists customer order history after both customer and order scope checks.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param customerId customer public identifier from the route.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/customer/{customerId}")
    public ApiResponse<OrderPage> customerOrders(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String customerId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.customerOrders(actor(jwt), customerId, page, size));
    }

    /**
     * Creates an order from the validated fixed-terms request and idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping
    public ApiResponse<CrmOrder> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OrderInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }

    /**
     * Records one actual receipt against the specified order with optimistic version and retry checks.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/{orderId}/receipts")
    public ApiResponse<CrmReceipt> receive(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReceiptInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.receive(actor(jwt), orderId, key, input));
    }

    /**
     * Reads the append-only receipt timeline for a visible order.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/{orderId}/receipts")
    public ApiResponse<java.util.List<CrmReceipt>> receipts(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId) {
        return ApiResponse.success(service.receipts(actor(jwt), orderId));
    }

    /**
     * Checks payment-link permission and order scope before returning the configured-provider result.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/{orderId}/payment-link")
    public ApiResponse<Void> paymentLink(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId) {
        service.paymentLink(actor(jwt), orderId);
        return ApiResponse.success(null);
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
