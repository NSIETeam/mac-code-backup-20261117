package cn.siyue.core;

import cn.siyue.core.CrmDtos.ActivityFormLink;
import cn.siyue.core.CrmDtos.ActivityFormLinkInput;
import cn.siyue.core.CrmDtos.CrmFormSubmission;
import cn.siyue.core.CrmDtos.CrmFormSubmissionPage;
import cn.siyue.core.CrmDtos.FormSubmissionReviewInput;
import cn.siyue.core.CrmDtos.MarketingForm;
import cn.siyue.core.CrmDtos.MarketingFormInput;
import cn.siyue.core.CrmDtos.MarketingFormPage;
import cn.siyue.core.CrmDtos.MarketingFormVersion;
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

/** Employee APIs for form design and acquisition-activity association. */
@RestController
@RequestMapping("/api/v2/crm")
@Validated
public class CrmFormController {
    private final AccessRepository access;
    private final CrmFormService service;
    private final PublicCrmFormService publicForms;
    private final CrmFormSubmissionReviewService submissionReviews;

    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     * @param publicForms value supplied to the request handler.
     * @param submissionReviews value supplied to the request handler.
     */
    public CrmFormController(AccessRepository access,CrmFormService service,PublicCrmFormService publicForms,
            CrmFormSubmissionReviewService submissionReviews){
        this.access=access;this.service=service;this.publicForms=publicForms;this.submissionReviews=submissionReviews;
    }

    /**
     * Creates a draft marketing or survey form from the validated request.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/forms")
    public ApiResponse<MarketingForm> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MarketingFormInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt),key,input));
    }

    /**
     * Lists forms visible under the caller’s current scope and bounded page parameters.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param campusId optional campus public identifier.
     * @param status optional status filter.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/forms")
    public ApiResponse<MarketingFormPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) @Size(max=120) String campusId,
            @RequestParam(required=false) @Size(max=16) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size){
        return ApiResponse.success(service.list(actor(jwt),campusId,status,page,size));
    }
    /**
     * Reads one form after the service checks its current scope.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param formId form public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */

    @GetMapping("/forms/{formId}")
    public ApiResponse<MarketingForm> read(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String formId) {
        return ApiResponse.success(service.read(actor(jwt),formId));
    }

    /**
     * Updates a draft form using the caller’s expected version and idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param formId form public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @param expectedVersion value supplied to the request handler.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PutMapping("/forms/{formId}")
    public ApiResponse<MarketingForm> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String formId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MarketingFormInput input,@RequestParam long expectedVersion)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt),formId,key,input,new MarketingFormVersion(expectedVersion)));
    }

    /**
     * Publishes a new immutable form revision using the expected version.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param formId form public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/forms/{formId}/publish")
    public ApiResponse<MarketingForm> publish(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String formId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MarketingFormVersion input)throws JsonProcessingException{
        return ApiResponse.success(service.publish(actor(jwt),formId,key,input));
    }

    /**
     * Stops new public submissions without removing existing revisions or submissions.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param formId form public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/forms/{formId}/unpublish")
    public ApiResponse<MarketingForm> unpublish(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String formId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MarketingFormVersion input)throws JsonProcessingException{
        return ApiResponse.success(service.unpublish(actor(jwt),formId,key,input));
    }

    /**
     * Associates a published form with an activity in the same authorized campus.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param activityId activity public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/activities/{activityId}/forms")
    public ApiResponse<ActivityFormLink> link(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String activityId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ActivityFormLinkInput input)throws JsonProcessingException{
        return ApiResponse.success(service.link(actor(jwt),activityId,key,input));
    }

    /**
     * Lists form associations after checking activity and form visibility.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param activityId activity public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/activities/{activityId}/forms")
    public ApiResponse<List<ActivityFormLink>> activityForms(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String activityId){
        return ApiResponse.success(service.activityForms(actor(jwt),activityId));
    }

    /**
     * Lists bounded form submissions for a caller authorized to review that form.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param formId form public identifier from the route.
     * @param status optional status filter.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/forms/{formId}/submissions")
    public ApiResponse<CrmFormSubmissionPage> submissions(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String formId,@RequestParam(required=false) @Size(max=16) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size){
        return ApiResponse.success(submissionReviews.submissions(actor(jwt),formId,status,page,size));
    }

    /**
     * Reviews one pending submission; configured lead forms may create CRM records atomically.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param submissionId form submission public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/form-submissions/{submissionId}/review")
    public ApiResponse<CrmFormSubmission> reviewSubmission(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String submissionId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody FormSubmissionReviewInput input)throws JsonProcessingException{
        return ApiResponse.success(submissionReviews.review(actor(jwt),submissionId,key,input));
    }

    private Actor actor(Jwt jwt){return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
