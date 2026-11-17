package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmRefund;
import cn.siyue.core.CrmDtos.CrmRefundRequest;
import cn.siyue.core.CrmDtos.RefundEvent;
import cn.siyue.core.CrmDtos.RefundPage;
import cn.siyue.core.CrmDtos.RefundRecordInput;
import cn.siyue.core.CrmDtos.RefundRequestInput;
import cn.siyue.core.CrmDtos.RefundRequestPage;
import cn.siyue.core.CrmDtos.RefundReviewInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Provides reviewed refund requests and an append-only actual refund ledger. */
@RestController
@RequestMapping("/api/v2/crm")
@Validated
public class CrmRefundController {
    private final AccessRepository access;
    private final CrmRefundService service;

    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     */
    public CrmRefundController(AccessRepository access, CrmRefundService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Submits a refund request for a visible order without changing receipt facts.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/orders/{orderId}/refund-requests")
    public ApiResponse<CrmRefundRequest> request(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RefundRequestInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.request(actor(jwt), orderId, key, input));
    }

    /**
     * Lists refund requests for an order visible under current scope.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/orders/{orderId}/refund-requests")
    public ApiResponse<RefundRequestPage> requests(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.requests(actor(jwt), orderId, page, size));
    }

    /**
     * Records an approval or rejection at the expected request version.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param requestId attribution request public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PutMapping("/refund-requests/{requestId}/review")
    public ApiResponse<CrmRefundRequest> review(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String requestId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RefundReviewInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.review(actor(jwt), requestId, key, input));
    }

    /**
     * Records the actual refund only for an approved request.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param requestId attribution request public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/refund-requests/{requestId}/refunds")
    public ApiResponse<CrmRefund> record(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String requestId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RefundRecordInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.record(actor(jwt), requestId, key, input));
    }

    /**
     * Lists actual refund ledger entries for a visible order.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/orders/{orderId}/refunds")
    public ApiResponse<RefundPage> refunds(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.refunds(actor(jwt), orderId, page, size));
    }

    /**
     * Returns a bounded immutable request and review history.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param requestId attribution request public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/refund-requests/{requestId}/events")
    public ApiResponse<List<RefundEvent>> events(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String requestId) {
        return ApiResponse.success(service.events(actor(jwt), requestId));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
