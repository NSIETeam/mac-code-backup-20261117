package cn.siyue.core;

import cn.siyue.core.CrmDtos.Activity;
import cn.siyue.core.CrmDtos.ActivityInput;
import cn.siyue.core.CrmDtos.ActivityMembersInput;
import cn.siyue.core.CrmDtos.ActivityPage;
import cn.siyue.core.CrmDtos.ActivityVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned acquisition-activity endpoints. */
@RestController
@RequestMapping("/api/v2/crm/activities")
@Validated
public class CrmActivityController {
    private final AccessRepository access;
    private final CrmService service;
    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     */
    public CrmActivityController(AccessRepository access, CrmService service) {
        this.access = access;
        this.service = service;
    }
    /**
     * Creates an activity scoped to a campus with the submitted participant and promoter memberships.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping
    public ApiResponse<Activity> createActivity(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ActivityInput input) throws JsonProcessingException {
        return ApiResponse.success(service.createActivity(actor(jwt), key, input));
    }
    /**
     * Lists activities visible under current campus, owner or membership scope and supplied filters.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param query validated filters or optional search text.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping
    public ApiResponse<ActivityPage> activities(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute CrmActivityQuery query) {
        return ApiResponse.success(service.activities(actor(jwt),query));
    }
    /**
     * Closes an activity only at the expected version supplied by the caller.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param activityId activity public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PutMapping("/{activityId}/close")
    public ApiResponse<Activity> closeActivity(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String activityId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ActivityVersion input) throws JsonProcessingException {
        return ApiResponse.success(service.closeActivity(actor(jwt), activityId, key, input));
    }
    /**
     * Replaces participant and promoter memberships at the caller’s expected activity version.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param activityId activity public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PutMapping("/{activityId}/members")
    public ApiResponse<Activity> updateMembers(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String activityId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ActivityMembersInput input) throws JsonProcessingException {
        return ApiResponse.success(service.updateActivityMembers(actor(jwt), activityId, key, input));
    }
    private Actor actor(Jwt jwt) {
        return access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
    }
}
