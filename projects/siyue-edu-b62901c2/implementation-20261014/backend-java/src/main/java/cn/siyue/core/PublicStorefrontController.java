package cn.siyue.core;

import cn.siyue.core.PublicStorefrontDtos.Configuration;
import cn.siyue.core.PublicStorefrontDtos.Content;
import cn.siyue.core.PublicStorefrontDtos.ContentInput;
import cn.siyue.core.PublicStorefrontDtos.DecorInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Employee-authenticated management endpoints for content served by the anonymous storefront.
 * Public decoration rendering is served separately; these routes resolve staff JWTs and enforce service grants.
 * @see PublicStorefrontService
 */
@RestController
@RequestMapping("/api/v2/commerce/storefront")
@Validated
public class PublicStorefrontController {
    private final AccessRepository access;
    private final PublicStorefrontService service;

    /** Injects employee identity resolution and storefront configuration operations.
     * @param access repository that resolves the authenticated issuer and subject
     * @param service scoped storefront content and decoration operations
     */
    public PublicStorefrontController(AccessRepository access, PublicStorefrontService service) {
        this.access = access;
        this.service = service;
    }

    /** Reads editable public copy for a product visible to the actor.
     * @param jwt authenticated employee JWT
     * @param id public product identifier, limited to 120 characters
     * @return current storefront copy for the product
     * @throws BusinessException if the actor lacks commerce-read permission or product scope
     */
    @GetMapping("/products/{id}")
    public ApiResponse<Content> content(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.get(actor(jwt), id));
    }

    /** Updates storefront copy while preserving the current course price and product version.
     * @param jwt authenticated employee JWT
     * @param id public product identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input validated storefront copy and expected product version
     * @return saved storefront copy
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if permission, scope, input, price, version, or retry checks fail
     */
    @PutMapping("/products/{id}")
    public ApiResponse<Content> update(@AuthenticationPrincipal Jwt jwt, @PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ContentInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), id, key, input));
    }

    /** Reads draft and published decoration for the global design administrator.
     * @param jwt authenticated employee JWT
     * @return current decoration versions and singleton version metadata
     * @throws BusinessException if the actor lacks store-design permission or all-data scope
     */
    @GetMapping("/decor")
    public ApiResponse<Configuration> decor(@AuthenticationPrincipal Jwt jwt) { return ApiResponse.success(service.draft(actor(jwt))); }

    /** Saves a validated decoration draft without publishing it.
     * @param jwt authenticated employee JWT
     * @param key request idempotency key
     * @param input decoration payload and expected singleton version
     * @return updated draft and published-version metadata
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if permission, global scope, input, version, or retry checks fail
     */
    @PutMapping("/decor")
    public ApiResponse<Configuration> updateDecor(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody DecorInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.updateDecor(actor(jwt), key, input));
    }

    /** Publishes the validated decoration draft for public storefront rendering.
     * @param jwt authenticated employee JWT
     * @param key request idempotency key
     * @param input decoration payload and expected singleton version
     * @return published decoration and updated version metadata
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if permission, global scope, input, version, or retry checks fail
     */
    @org.springframework.web.bind.annotation.PostMapping("/decor/publish")
    public ApiResponse<Configuration> publishDecor(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DecorInput input) throws JsonProcessingException {
        return ApiResponse.success(service.publishDecor(actor(jwt),key,input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
