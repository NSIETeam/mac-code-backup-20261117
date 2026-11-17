package cn.siyue.core;

import cn.siyue.core.CrmDtos.Lead;
import cn.siyue.core.CrmDtos.LeadEvent;
import cn.siyue.core.CrmDtos.LeadFollowupInput;
import cn.siyue.core.CrmDtos.LeadInput;
import cn.siyue.core.CrmDtos.LeadQuery;
import cn.siyue.core.CrmDtos.LeadOwner;
import cn.siyue.core.CrmDtos.LeadPage;
import cn.siyue.core.LeadBatchAssignmentDtos.OwnerOptions;
import cn.siyue.core.LeadBatchAssignmentDtos.OwnerOptionsInput;
import cn.siyue.core.CrmDtos.LeadRelease;
import cn.siyue.core.CrmDtos.LeadStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned lead lifecycle endpoints for inquiry screening and controlled handoff. */
@RestController
@RequestMapping("/api/v2/crm/leads")
@Validated
public class LeadController {
    private final AccessRepository access;
    private final LeadService service;
    private final LeadBatchAssignmentService batchAssignment;
    /** Wires principal resolution and the separate single-lead and batch-assignment services.
     * @param access resolves validated OIDC issuer/subject pairs to current application principals
     * @param service single-lead reads, transitions and idempotent mutation transactions
     * @param batchAssignment explicit selection, recipient options and batch assignment transactions
     */
    public LeadController(AccessRepository access, LeadService service, LeadBatchAssignmentService batchAssignment) {
        this.access = access;
        this.service = service;
        this.batchAssignment = batchAssignment;
    }
    /** Reads the scoped lead queue, dispatching a present saved view to the same lead service.
     * @param jwt validated OIDC token identifying the caller
     * @param query ordinary lead filters and pagination, validated by the service
     * @param savedView optional imported lead filters; null or empty values use the ordinary route
     * @return lead rows and the total from the same visibility predicate
     * @throws BusinessException if principal resolution, read grants, resource filters or pagination fail
     */
    @GetMapping
    public ApiResponse<LeadPage> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute LeadQuery query, @Valid @ModelAttribute LegacySavedViewQuery savedView) {
        Actor actor = actor(jwt);
        return ApiResponse.success(savedView != null && savedView.present()
            ? service.list(actor, query, savedView) : service.list(actor, query));
    }
    /** Creates an employee-assisted lead with current linked-record and identity checks.
     * @param jwt validated OIDC token identifying the caller
     * @param key stable idempotency key reused only with the original body
     * @param input customer, optional activity/student references, source and lead details
     * @return created lead or the stored receipt of an authorized identical retry
     * @throws BusinessException if identity, creation grants, scope, linked records or retry checks fail
     * @throws JsonProcessingException if the request hash or receipt cannot be serialized
     */
    @PostMapping
    public ApiResponse<Lead> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LeadInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }
    /** Assigns one lead under the modern assignment or exact legacy assignment grant.
     * @param jwt validated OIDC token identifying the caller
     * @param leadId lead public identifier, limited to 120 characters
     * @param key idempotency key bound to this lead and request body
     * @param input receiving employee, audit reason and expected lead version
     * @return updated lead receipt, including a successful handoff that removes the caller's ownership
     * @throws BusinessException if scope, recipient readiness, pending decision, version or retry checks fail
     * @throws JsonProcessingException if the hash input or receipt cannot be encoded
     */
    @PostMapping("/{leadId}/assign")
    public ApiResponse<Lead> assign(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String leadId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LeadOwner input)
            throws JsonProcessingException {
        return ApiResponse.success(service.assign(actor(jwt), leadId, key, input));
    }
    /** Assigns an explicit lead selection through the transactional batch allocation service.
     * @param jwt validated OIDC token identifying the caller
     * @param key stable idempotency key for the entire selection and allocation plan
     * @param input selected lead IDs, expected versions, recipients, strategy and reason
     * @return per-lead assignment outcomes and the batch receipt
     * @throws BusinessException if assignment permission, selection scope, versions, recipients or allocation are invalid
     * @throws JsonProcessingException if the allocation request or resulting receipt cannot be serialized
     */
    @PostMapping("/batch-assign")
    public ApiResponse<LeadBatchAssignmentDtos.Result> batchAssign(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody LeadBatchAssignmentDtos.Input input) throws JsonProcessingException {
        return ApiResponse.success(batchAssignment.assign(actor(jwt), key, input));
    }
    /** Reads recipient options for a validated selection without assigning any lead.
     * @param jwt validated OIDC token identifying the caller
     * @param input explicit lead identifiers used to determine the applicable campus requirements
     * @param page one-based options page
     * @param size maximum options per page
     * @return eligible recipient identifiers and bounded pagination metadata
     * @throws BusinessException if assignment permission, lead selection, scope or pagination is invalid
     */
    @PostMapping("/assignment-owners")
    public ApiResponse<OwnerOptions> assignmentOwners(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OwnerOptionsInput input, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(batchAssignment.ownerOptions(actor(jwt), input.leadIds(), page, size));
    }
    /** Transfers one lead while retaining the distinct legacy transfer permission and audit action.
     * @param jwt validated OIDC token identifying the caller
     * @param leadId lead public identifier
     * @param key idempotency key bound to the original transfer request
     * @param input destination employee, reason and expected lead version
     * @return successful transfer receipt or an identical retry after current authorization is rechecked
     * @throws BusinessException if permission, source scope, recipient eligibility, decision or version checks fail
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     */
    @PostMapping("/{leadId}/transfer")
    public ApiResponse<Lead> transfer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String leadId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LeadOwner input)
            throws JsonProcessingException {
        return ApiResponse.success(service.transfer(actor(jwt), leadId, key, input));
    }
    /** Removes lead ownership through the separately authorized release transition.
     * @param jwt validated OIDC token identifying the caller
     * @param leadId lead public identifier
     * @param key idempotency key retained with the original release body
     * @param input release reason and expected lead version
     * @return successful release receipt with no owner; later reads and retries still require current scope
     * @throws BusinessException if authorization, lead state, order linkage, version or retry checks reject release
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     */
    @PostMapping("/{leadId}/release")
    public ApiResponse<Lead> release(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String leadId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LeadRelease input)
            throws JsonProcessingException {
        return ApiResponse.success(service.release(actor(jwt), leadId, key, input));
    }
    /** Changes the modern screening status; the separate sales-stage endpoint does not use this transition.
     * @param jwt validated OIDC token identifying the caller
     * @param leadId lead public identifier
     * @param key stable idempotency key for this screening change
     * @param input screening status, reason, optional next follow-up and expected version
     * @return updated screening projection or an authorized identical retry receipt
     * @throws BusinessException if modern status permission, scope, transition or version checks fail
     * @throws JsonProcessingException if the request or result cannot be serialized
     */
    @PutMapping("/{leadId}/status")
    public ApiResponse<Lead> status(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String leadId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody LeadStatus input)
            throws JsonProcessingException {
        return ApiResponse.success(service.changeStatus(actor(jwt), leadId, key, input));
    }
    /** Reads the separate legacy sales stage without exposing unrelated customer fields.
     * @param jwt validated employee JWT
     * @param leadId public lead identifier
     * @return scoped stage/version projection
     * @throws BusinessException if identity, read permission or scope is invalid
     */
    @GetMapping("/{leadId}/sales-stage")
    public ApiResponse<CrmDtos.LeadSalesStage> salesStage(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String leadId) {
        return ApiResponse.success(service.salesStage(actor(jwt),leadId));
    }
    /** Updates the original sales stage using exact legacy follow permission and versioned retries.
     * @param jwt validated employee JWT
     * @param leadId public lead identifier
     * @param key stable idempotency request key
     * @param input allowed sales stage, optional reason and expected version
     * @return updated stage or identical retry receipt
     * @throws BusinessException if permission, scope, ownership, conversion or retry validation fails
     * @throws JsonProcessingException if request or receipt serialization fails
     */
    @PutMapping("/{leadId}/sales-stage")
    public ApiResponse<CrmDtos.LeadSalesStage> salesStage(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String leadId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CrmDtos.LeadSalesStageInput input) throws JsonProcessingException {
        return ApiResponse.success(service.changeSalesStage(actor(jwt),leadId,key,input));
    }
    /** Records a descriptive lead interaction and next follow-up time without sending a message.
     * @param jwt validated OIDC token identifying the caller
     * @param leadId lead public identifier
     * @param key idempotency key bound to the original interaction body
     * @param input descriptive channel, summary, next follow-up time and expected version
     * @return lead after the follow-up or an authorized identical retry receipt
     * @throws BusinessException if follow permission, current owner, lead state, scope or version checks fail
     * @throws JsonProcessingException if request hashing or receipt encoding fails
     */
    @PostMapping("/{leadId}/followups")
    public ApiResponse<Lead> followup(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String leadId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody LeadFollowupInput input) throws JsonProcessingException {
        return ApiResponse.success(service.followup(actor(jwt), leadId, key, input));
    }
    /** Reads visible lead events merged from ownership, screening, follow-up, policy and decision history.
     * @param jwt validated OIDC token identifying the caller
     * @param leadId lead public identifier
     * @return up to 100 recent events, ordered by occurrence time
     * @throws BusinessException if principal resolution, lead read permission or scope rejects the request
     */
    @GetMapping("/{leadId}/events")
    public ApiResponse<List<LeadEvent>> events(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String leadId) {
        return ApiResponse.success(service.events(actor(jwt), leadId));
    }
    private Actor actor(Jwt jwt) {
        return access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
    }
}
