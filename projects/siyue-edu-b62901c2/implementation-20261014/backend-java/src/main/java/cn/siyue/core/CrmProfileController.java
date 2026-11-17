package cn.siyue.core;

import cn.siyue.core.CrmProfileDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Exposes scoped CRM profile, customer and campaign endpoints. */
@RestController
@RequestMapping("/api/v2/crm")
@Validated
public class CrmProfileController {
    private final AccessRepository access;
    private final CrmProfileService service;
    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     */
    public CrmProfileController(AccessRepository access,CrmProfileService service) {this.access=access;this.service=service;}
    /**
     * Returns a bounded list of profile options for the requested kind and optional campus.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param kind profile-option category requested by the caller.
     * @param campusId optional campus public identifier.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/profile-options")
    public ApiResponse<Options> options(@AuthenticationPrincipal Jwt jwt,@RequestParam String kind,
            @RequestParam(required=false) String campusId,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="50") int size) {
        return ApiResponse.success(service.options(actor(jwt),new OptionsQuery(kind,campusId,page,size)));
    }
    /**
     * Reads a customer profile visible to the current caller.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param id resource public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/customers/{id}/profile")
    public ApiResponse<Customer> customer(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.customer(actor(jwt),id));
    }
    /**
     * Updates customer profile fields using the expected version and idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param id resource public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PutMapping("/customers/{id}")
    public ApiResponse<Customer> updateCustomer(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Patch input) throws JsonProcessingException {
        return ApiResponse.success(service.updateCustomer(actor(jwt),id,key,input));
    }
    /**
     * Reads the customer and lead profile relationship visible to the caller.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param id resource public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/customers/from-lead/{id}")
    public ApiResponse<LeadProfile> leadProfile(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.leadProfile(actor(jwt),id));
    }
    /**
     * Links the lead’s customer profile using an idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/customers/from-lead")
    public ApiResponse<Link> fromLead(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody FromLead input) throws JsonProcessingException {
        return ApiResponse.success(service.fromLead(actor(jwt),key,input));
    }
    /**
     * Creates a campaign from the supplied JSON payload with an idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/activities/campaigns")
    public ApiResponse<Campaign> createCampaign(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestBody java.util.Map<String,Object> input) throws JsonProcessingException {
        return ApiResponse.success(service.createCampaign(actor(jwt),key,input));
    }
    /**
     * Publishes the campaign only at its expected activity version.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param id resource public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/activities/{id}/publish")
    public ApiResponse<Campaign> publishCampaign(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CrmDtos.ActivityVersion input) throws JsonProcessingException {
        return ApiResponse.success(service.publishCampaign(actor(jwt),id,key,input));
    }
    /**
     * Reads a campaign profile visible under the current actor scope.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param id resource public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/activities/{id}/profile")
    public ApiResponse<Campaign> campaign(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id) {
        return ApiResponse.success(service.campaign(actor(jwt),id));
    }
    /**
     * Updates a campaign profile using its expected version and an idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param id resource public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PutMapping("/activities/{id}")
    public ApiResponse<Campaign> updateCampaign(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Patch input) throws JsonProcessingException {
        return ApiResponse.success(service.updateCampaign(actor(jwt),id,key,input));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
