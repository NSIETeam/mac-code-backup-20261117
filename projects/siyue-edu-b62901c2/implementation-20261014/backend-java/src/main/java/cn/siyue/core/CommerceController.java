package cn.siyue.core;

import cn.siyue.core.CommerceDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Java-owned product publication and class-level promotion configuration.
 * Every route resolves the authenticated JWT to an active platform actor in its service layer.
 * @see CommerceService
 */
@RestController
@RequestMapping("/api/v2/commerce")
@Validated
public class CommerceController {
    private final AccessRepository access;
    private final CommerceService service;
    /** Injects JWT-to-actor resolution and commerce operations.
     * @param access repository that resolves the authenticated issuer and subject
     * @param service scoped product, class and promotion operations
     */
    public CommerceController(AccessRepository access,CommerceService service) { this.access=access;this.service=service; }
    /** Lists products visible under the authenticated actor's commerce scope.
     * @param jwt authenticated employee JWT
     * @param page one-based page number, defaulting to 1
     * @param pageSize requested page size, defaulting to 50
     * @return scoped product page
     * @throws BusinessException if actor resolution, permission, scope, or pagination checks fail
     */
    @GetMapping("/products")
    public ApiResponse<ProductPage> products(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize) {
        return ApiResponse.success(service.products(actor(jwt),page,pageSize));
    }
    /** Lists classes attached to a course that are visible to the authenticated actor.
     * @param jwt authenticated employee JWT
     * @param courseId public course identifier, limited to 120 characters
     * @param page one-based page number, defaulting to 1
     * @param pageSize requested page size, defaulting to 100
     * @return scoped class page
     * @throws BusinessException if actor resolution, read permission, course scope, or pagination checks fail
     */
    @GetMapping("/courses/{courseId}/classes")
    public ApiResponse<ClassPage> courseClasses(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String courseId,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="100") int pageSize) {
        return ApiResponse.success(service.courseClasses(actor(jwt),courseId,page,pageSize));
    }
    /** Creates a product and records the required idempotency key.
     * @param jwt authenticated employee JWT
     * @param key request idempotency key
     * @param input validated product details
     * @return created product
     * @throws JsonProcessingException if request or response serialization fails
     * @throws BusinessException if actor, permission, input, scope, or idempotency checks fail
     */
    @PostMapping("/products")
    public ApiResponse<Product> createProduct(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ProductInput input) throws JsonProcessingException {
        return ApiResponse.success(service.saveProduct(actor(jwt),null,key,input));
    }
    /** Updates a product using the complete validated product input.
     * @param jwt authenticated employee JWT
     * @param id public product identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input validated product replacement details
     * @return updated product
     * @throws JsonProcessingException if request or response serialization fails
     * @throws BusinessException if actor, permission, input, scope, or idempotency checks fail
     */
    @PutMapping("/products/{id}")
    public ApiResponse<Product> updateProduct(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ProductInput input) throws JsonProcessingException {
        return ApiResponse.success(service.saveProduct(actor(jwt),id,key,input));
    }
    /** Lists promotions visible under the actor's commerce scope.
     * @param jwt authenticated employee JWT
     * @param page one-based page number, defaulting to 1
     * @param pageSize requested page size, defaulting to 50
     * @param type optional promotion type filter
     * @return scoped promotion page
     * @throws BusinessException if actor resolution, read permission, filter, scope, or pagination checks fail
     */
    @GetMapping("/promotions")
    public ApiResponse<PromotionPage> promotions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,
            @RequestParam(required=false) String type) {
        return ApiResponse.success(service.promotions(actor(jwt),page,pageSize,type));
    }
    /** Creates a promotion and records the required idempotency key.
     * @param jwt authenticated employee JWT
     * @param key request idempotency key
     * @param input validated promotion details
     * @return created promotion
     * @throws JsonProcessingException if request or response serialization fails
     * @throws BusinessException if actor, permission, input, scope, sale-term, or retry checks fail
     */
    @PostMapping("/promotions")
    public ApiResponse<Promotion> createPromotion(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PromotionInput input) throws JsonProcessingException {
        return ApiResponse.success(service.savePromotion(actor(jwt),null,key,input));
    }
    /** Updates a promotion using the complete validated promotion input.
     * @param jwt authenticated employee JWT
     * @param id public promotion identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input validated promotion replacement details
     * @return updated promotion
     * @throws JsonProcessingException if request or response serialization fails
     * @throws BusinessException if actor, permission, input, scope, sale-term, or retry checks fail
     */
    @PutMapping("/promotions/{id}")
    public ApiResponse<Promotion> updatePromotion(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PromotionInput input) throws JsonProcessingException {
        return ApiResponse.success(service.savePromotion(actor(jwt),id,key,input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(),jwt.getSubject()); }
}
