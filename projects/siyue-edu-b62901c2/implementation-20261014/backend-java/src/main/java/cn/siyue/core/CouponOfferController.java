package cn.siyue.core;

import static cn.siyue.core.CouponOfferDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Authenticated staff endpoints for coupon options, scoped offers and publication lifecycle.
 * The service applies offer visibility and exact mutation grants using the resolved employee actor.
 * @see CouponOfferService
 */
@RestController
@RequestMapping("/api/v2/commerce/coupons")
@Validated
public class CouponOfferController {
    private final AccessRepository access;
    private final CouponOfferService service;
    /** Injects employee identity resolution and coupon operations.
     * @param access repository that resolves the authenticated issuer and subject
     * @param service scoped coupon list, save, and publication operations
     */
    public CouponOfferController(AccessRepository access,CouponOfferService service) {this.access=access;this.service=service;}
    /** Lists course and campus choices available for configuring an offer.
     * @param jwt authenticated employee JWT
     * @param page one-based page number, defaulting to 1
     * @param size requested page size, defaulting to 100
     * @return scoped coupon configuration options
     * @throws BusinessException if actor resolution, option permission, scope, or pagination checks fail
     */
    @GetMapping("/options")
    public ApiResponse<Options> options(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int size) {
        return ApiResponse.success(service.options(actor(jwt),page,size));
    }
    /** Lists coupon offers visible under the actor's current data scope.
     * @param jwt authenticated employee JWT
     * @param page one-based page number, defaulting to 1
     * @param pageSize requested page size, defaulting to 50
     * @return scoped offer page with usage and pagination information
     * @throws BusinessException if actor resolution, read grant, scope, or pagination checks fail
     */
    @GetMapping
    public ApiResponse<Page> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize) {
        return ApiResponse.success(service.list(actor(jwt),page,pageSize));
    }
    /** Creates a coupon draft with the required request idempotency key.
     * @param jwt authenticated employee JWT
     * @param key request idempotency key
     * @param input validated coupon definition
     * @return saved offer and usage information
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, create permission, scope, coupon rules, or retry checks fail
     */
    @PostMapping
    public ApiResponse<Offer> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Input input) throws JsonProcessingException {
        return ApiResponse.success(service.save(actor(jwt),null,key,input));
    }
    /** Updates an offer and returns it to draft state through the service contract.
     * @param jwt authenticated employee JWT
     * @param id public coupon identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input validated coupon definition and expected version
     * @return updated offer and usage information
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, create permission, scope, version, coupon rules, or retry checks fail
     */
    @PutMapping("/{id}")
    public ApiResponse<Offer> update(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Input input) throws JsonProcessingException {
        return ApiResponse.success(service.save(actor(jwt),id,key,input));
    }
    /** Publishes an offer at the submitted expected version.
     * @param jwt authenticated employee JWT
     * @param id public coupon identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input expected offer version
     * @return published offer and usage information
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, publish permission, scope, version, expiry, or course eligibility fails
     */
    @PostMapping("/{id}/publish")
    public ApiResponse<Offer> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Publication input) throws JsonProcessingException {
        return ApiResponse.success(service.publication(actor(jwt),id,key,input,true));
    }
    /** Withdraws an offer at the submitted expected version.
     * @param jwt authenticated employee JWT
     * @param id public coupon identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input expected offer version
     * @return withdrawn offer and usage information
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, publish permission, scope, version, or publication-state checks fail
     */
    @PostMapping("/{id}/unpublish")
    public ApiResponse<Offer> unpublish(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Publication input) throws JsonProcessingException {
        return ApiResponse.success(service.publication(actor(jwt),id,key,input,false));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
