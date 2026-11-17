package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Authenticated staff endpoints for commerce checkout processing.
 * Routes resolve the JWT subject to a platform actor; checkout visibility and write grants are enforced by services.
 * @see CommerceCheckoutService
 */
@RestController
@RequestMapping("/api/v2/commerce/checkouts")
@Validated
public class CommerceCheckoutController {
    private final AccessRepository access;
    private final CommerceCheckoutService service;

    /** Injects employee identity resolution and checkout operations.
     * @param access repository that resolves the authenticated issuer and subject
     * @param service checkout, acceptance, receipt and payment-link operations
     */
    public CommerceCheckoutController(AccessRepository access, CommerceCheckoutService service) {
        this.access = access;
        this.service = service;
    }

    /** Creates an employee-submitted checkout with an idempotent request key.
     * @param jwt authenticated employee JWT
     * @param key request idempotency key
     * @param input validated checkout buyers and purchase lines
     * @return created checkout receipt
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, permission, scope, input, quote, or idempotency checks fail
     */
    @PostMapping
    public ApiResponse<CheckoutReceipt> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }

    /** Lists checkout receipts visible to the authenticated actor.
     * @param jwt authenticated employee JWT
     * @param page one-based page number, defaulting to 1
     * @param size requested page size, defaulting to 50
     * @return scoped checkout list and pagination metadata
     * @throws BusinessException if actor, read grant, scope, or pagination checks fail
     */
    @GetMapping
    public ApiResponse<CheckoutList> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(actor(jwt), page, size));
    }

    /** Reads a checkout receipt under the service's checkout visibility rules.
     * @param jwt authenticated employee JWT
     * @param id public checkout identifier, limited to 120 characters
     * @return checkout receipt and linked-order state
     * @throws BusinessException if the actor lacks a supported read grant or the checkout is inaccessible
     */
    @GetMapping("/{id}")
    public ApiResponse<CheckoutReceipt> detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String id) {
        return ApiResponse.success(service.detail(actor(jwt), id));
    }

    /** Reads checkout buyer details and matching student candidates for staff acceptance.
     * @param jwt authenticated employee JWT
     * @param id public checkout identifier, limited to 120 characters
     * @return intake projection containing scoped buyer matches
     * @throws BusinessException if the actor lacks enrollment permission or the checkout is inaccessible
     */
    @GetMapping("/{id}/intake")
    public ApiResponse<IntakeDetail> intake(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.intake(actor(jwt),id));
    }

    /** Accepts a checkout and creates linked orders/enrollments under one idempotent operation.
     * @param jwt authenticated employee JWT
     * @param id public checkout identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input expected checkout version and optional buyer-to-student mappings
     * @return accepted checkout receipt
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, permission, scope, state, version, capacity, or acceptance validation fails
     */
    @PostMapping("/{id}/accept")
    public ApiResponse<CheckoutReceipt> accept(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcceptInput input) throws JsonProcessingException {
        return ApiResponse.success(service.accept(actor(jwt), id, key, input));
    }

    /** Confirms a staff-recorded receipt against the checkout's remaining balance.
     * @param jwt authenticated employee JWT
     * @param id public checkout identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input payment method, amount, reference and expected checkout version
     * @return updated checkout receipt
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, receipt permission, scope, balance, duplicate reference, or version checks fail
     */
    @PostMapping("/{id}/receipt")
    public ApiResponse<CheckoutReceipt> confirmReceipt(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ConfirmReceiptInput input) throws JsonProcessingException {
        return ApiResponse.success(service.confirmReceipt(actor(jwt), id, key, input));
    }

    /** Checks exact payment-link permission and checkout scope; the provider is currently unavailable.
     * @param jwt authenticated employee JWT
     * @param id public checkout identifier, limited to 120 characters
     * @return empty API success envelope if the service completes; current configuration always rejects this path
     * @throws BusinessException for denied permission/scope or with {@code PAYMENT_PROVIDER_UNAVAILABLE}
     */
    @PostMapping("/{id}/payment-link")
    public ApiResponse<Void> paymentLink(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String id) {
        service.paymentLink(actor(jwt), id);
        return ApiResponse.success(null);
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
