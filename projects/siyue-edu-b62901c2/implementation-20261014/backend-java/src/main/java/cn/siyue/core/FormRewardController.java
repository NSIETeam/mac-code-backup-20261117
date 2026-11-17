package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Employee-authenticated reads and draft edits for CRM form reward settings.
 * Public reward fulfillment is a separate workflow and is not performed by these routes.
 * @see FormRewardService
 */
@RestController
@RequestMapping("/api/v2/crm/forms/{formId}/reward")
@Validated
public class FormRewardController {
    private final AccessRepository access;
    private final FormRewardService service;
    /** Injects employee identity resolution and form reward operations.
     * @param access repository that resolves the authenticated issuer and subject
     * @param service scoped reward options, read and draft-update operations
     */
    public FormRewardController(AccessRepository access,FormRewardService service) {this.access=access;this.service=service;}
    /** Lists available coupon options for the selected form and actor scope.
     * @param jwt authenticated employee JWT
     * @param formId public form identifier, limited to 120 characters
     * @param page one-based page number, defaulting to 1
     * @param size requested page size, defaulting to 50
     * @return scoped coupon option page
     * @throws BusinessException if actor, crm.form.read permission, form scope, or pagination checks fail
     */
    @GetMapping("/options")
    public ApiResponse<FormRewardRepository.CouponPage> options(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String formId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size) {
        return ApiResponse.success(service.options(actor(jwt),formId,page,size));
    }
    /** Reads the form's reward draft configuration.
     * @param jwt authenticated employee JWT
     * @param formId public form identifier, limited to 120 characters
     * @return current reward configuration, or no configuration if none is saved
     * @throws BusinessException if actor, crm.form.read permission, or form scope checks fail
     */
    @GetMapping
    public ApiResponse<FormRewardRepository.Configuration> read(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String formId) {
        return ApiResponse.success(service.read(actor(jwt),formId));
    }
    /** Updates the reward draft and advances the form version.
     * @param jwt authenticated employee JWT
     * @param formId public form identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input reward configuration and expected form version
     * @return updated form
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, crm.form.write permission, scope, form state, version, or retry checks fail
     */
    @PutMapping
    public ApiResponse<CrmDtos.MarketingForm> update(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String formId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody FormRewardService.Update input) throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt),formId,key,input));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
