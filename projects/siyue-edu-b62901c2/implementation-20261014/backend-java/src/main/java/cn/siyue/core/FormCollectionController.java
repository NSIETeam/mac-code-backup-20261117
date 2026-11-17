package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Employee-authenticated reads and draft updates for CRM form collection settings.
 * Form read/write permission and form data scope are enforced by the service.
 * @see FormCollectionService
 */
@RestController
@RequestMapping("/api/v2/crm/forms/{formId}/collection-settings")
@Validated
public class FormCollectionController {
    private final AccessRepository access;
    private final FormCollectionService service;
    /** Injects employee identity resolution and collection-settings operations.
     * @param access repository that resolves the authenticated issuer and subject
     * @param service scoped form collection settings read and update operations
     */
    public FormCollectionController(AccessRepository access,FormCollectionService service) {this.access=access;this.service=service;}
    /** Reads collection settings for an accessible form.
     * @param jwt authenticated employee JWT
     * @param formId public form identifier, limited to 120 characters
     * @return current form collection settings
     * @throws BusinessException if actor resolution, crm.form.read permission, or form scope fails
     */
    @GetMapping
    public ApiResponse<FormCollectionRepository.Settings> read(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String formId) {
        return ApiResponse.success(service.read(actor(jwt),formId));
    }
    /** Updates collection settings while the form remains a draft at the submitted version.
     * @param jwt authenticated employee JWT
     * @param formId public form identifier, limited to 120 characters
     * @param key request idempotency key
     * @param input collection settings and expected form version
     * @return updated form
     * @throws JsonProcessingException if request or receipt serialization fails
     * @throws BusinessException if actor, crm.form.write permission, form scope, state, version, or retry checks fail
     */
    @PutMapping
    public ApiResponse<CrmDtos.MarketingForm> update(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String formId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody FormCollectionService.Update input) throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt),formId,key,input));
    }
    private Actor actor(Jwt jwt) {return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
