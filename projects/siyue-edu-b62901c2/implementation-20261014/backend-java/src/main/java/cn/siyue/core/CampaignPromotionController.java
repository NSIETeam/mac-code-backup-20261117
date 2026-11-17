package cn.siyue.core;

import cn.siyue.core.CampaignPromotionDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Authenticated employee endpoints; public form access never grants employee authority. */
@RestController
@RequestMapping("/api/v2/crm/activities/{activityId}/promotion-links")
public class CampaignPromotionController {
    private final AccessRepository access;
    private final CampaignPromotionService service;
    /** Connects employee JWT identity resolution to the transaction service.
     * @param access issuer/subject lookup; JWT role claims do not grant authority
     * @param service link authorization and mutation boundary
     */
    public CampaignPromotionController(AccessRepository access,CampaignPromotionService service) {this.access=access;this.service=service;}
    /** Lists scope-filtered links and eligible form/promoter choices.
     * @param jwt verified employee JWT
     * @param activityId campaign public identifier
     * @return standard response containing scoped links and current version
     * @throws BusinessException if identity, permission or campaign scope is invalid
     */
    @GetMapping
    public ApiResponse<Page> list(@AuthenticationPrincipal Jwt jwt,@PathVariable String activityId) {
        return ApiResponse.success(service.list(actor(jwt),activityId));
    }
    /** Creates a version-checked, idempotent link under exact lead.create permission.
     * @param jwt verified employee JWT
     * @param activityId campaign public identifier
     * @param key client-generated exact retry key
     * @param input published form, eligible promoter, label and expected campaign version
     * @return standard response containing immutable link and incremented version
     * @throws BusinessException if authority, scope, state, version or retry checks fail
     * @throws JsonProcessingException if request or result cannot be serialized
     */
    @PostMapping
    public ApiResponse<Created> create(@AuthenticationPrincipal Jwt jwt,@PathVariable String activityId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create input)throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt),activityId,key,input));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
