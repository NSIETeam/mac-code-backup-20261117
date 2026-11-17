package cn.siyue.core;

import cn.siyue.core.CrmDtos.AttributionAdjustment;
import cn.siyue.core.CrmDtos.AttributionEvent;
import cn.siyue.core.CrmDtos.AttributionRequest;
import cn.siyue.core.CrmDtos.AttributionRequestInput;
import cn.siyue.core.CrmDtos.AttributionRequestPage;
import cn.siyue.core.CrmDtos.AttributionReviewInput;
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

/** Provides order-specific correction requests and their immutable approved ledger. */
@RestController
@RequestMapping("/api/v2/crm")
@Validated
public class CrmAttributionController {
    private final AccessRepository access;
    private final CrmAttributionService service;

    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     */
    public CrmAttributionController(AccessRepository access, CrmAttributionService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Submits an idempotent attribution correction request for one visible order.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/orders/{orderId}/attribution-requests")
    public ApiResponse<AttributionRequest> request(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AttributionRequestInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.request(actor(jwt), orderId, key, input));
    }

    /**
     * Lists correction requests for one order within the actor’s current order scope.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/orders/{orderId}/attribution-requests")
    public ApiResponse<AttributionRequestPage> requests(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.requests(actor(jwt), orderId, page, size));
    }

    /**
     * Reviews a correction request using its expected version and an idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param requestId attribution request public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PutMapping("/attribution-requests/{requestId}/review")
    public ApiResponse<AttributionRequest> review(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String requestId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AttributionReviewInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.review(actor(jwt), requestId, key, input));
    }

    /**
     * Lists approved immutable owner adjustments applied to the requested order.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param orderId order public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/orders/{orderId}/attribution-adjustments")
    public ApiResponse<List<AttributionAdjustment>> adjustments(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String orderId) {
        return ApiResponse.success(service.adjustments(actor(jwt), orderId));
    }

    /**
     * Returns the bounded immutable review history for a visible attribution request.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param requestId attribution request public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/attribution-requests/{requestId}/events")
    public ApiResponse<List<AttributionEvent>> events(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String requestId) {
        return ApiResponse.success(service.events(actor(jwt), requestId));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
