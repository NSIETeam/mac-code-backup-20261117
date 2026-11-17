package cn.siyue.core;

import cn.siyue.core.CrmDtos.ServiceTicket;
import cn.siyue.core.CrmDtos.ServiceTicketClose;
import cn.siyue.core.CrmDtos.ServiceTicketEvent;
import cn.siyue.core.CrmDtos.ServiceTicketFollowup;
import cn.siyue.core.CrmDtos.ServiceTicketInput;
import cn.siyue.core.CrmDtos.ServiceTicketOwner;
import cn.siyue.core.CrmDtos.ServiceTicketPage;
import cn.siyue.core.CrmDtos.ServiceTicketRefundLink;
import cn.siyue.core.CrmDtos.ServiceTicketType;
import cn.siyue.core.CrmDtos.ServiceTicketTypeInput;
import cn.siyue.core.CrmDtos.ServiceTicketTypeUpdate;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.RegistryState;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.ReplaceInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

/** Versioned CRM service-ticket and configurable ticket-type APIs. */
@RestController
@RequestMapping("/api/v2/crm")
@Validated
public class CrmServiceTicketController {
    private final AccessRepository access;
    private final CrmServiceTicketService service;

    /**
     * Wires the OIDC actor resolver and ticket application service.
     * @param access actor resolution and active-principal access repository.
     * @param service ticket application service.
     */
    public CrmServiceTicketController(AccessRepository access, CrmServiceTicketService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Lists ticket types for an optional campus; inactive types are returned only when the service authorizes the request.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param campusId optional campus filter.
     * @param includeInactive include inactive values when authorized.
     * @return the operation result.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @GetMapping("/service-ticket-types")
    public ApiResponse<java.util.List<ServiceTicketType>> types(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String campusId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(service.types(actor(jwt), campusId, includeInactive));
    }

    /**
     * Creates a ticket type from a validated request and required idempotency header.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PostMapping("/service-ticket-types")
    public ApiResponse<ServiceTicketType> createType(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ServiceTicketTypeInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.createType(actor(jwt), key, input));
    }

    /**
     * Updates a ticket type using its path identifier, validated body and required idempotency header.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param typeId ticket-type public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PutMapping("/service-ticket-types/{typeId}")
    public ApiResponse<ServiceTicketType> updateType(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String typeId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ServiceTicketTypeUpdate input) throws JsonProcessingException {
        return ApiResponse.success(service.updateType(actor(jwt), typeId, key, input));
    }

    /**
     * Returns the authorized global type catalog and its version.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @return the operation result.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @GetMapping("/service-ticket-types/registry")
    public ApiResponse<RegistryState> typeRegistry(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.typeRegistry(actor(jwt)));
    }

    /**
     * Replaces the global type catalog from the validated body with idempotent request handling.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PutMapping("/service-ticket-types/registry")
    public ApiResponse<RegistryState> replaceTypeRegistry(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReplaceInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.replaceTypeRegistry(actor(jwt), key, input));
    }

    /**
     * Lists tickets using optional status and bounded page parameters.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param status optional ticket status filter.
     * @param page 1-based page number.
     * @param size maximum number of records in the page.
     * @return the operation result.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     * @param savedView optional imported ticket filters; an absent or empty view uses the ordinary list route.
     */
    @GetMapping("/service-tickets")
    public ApiResponse<ServiceTicketPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 20) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute LegacySavedViewQuery savedView) {
        Actor actor = actor(jwt);
        return ApiResponse.success(savedView != null && savedView.present()
            ? service.list(actor, status, page, size, savedView) : service.list(actor, status, page, size));
    }

    /**
     * Creates a ticket from the validated request and required idempotency header.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PostMapping("/service-tickets")
    public ApiResponse<ServiceTicket> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ServiceTicketInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }

    /**
     * Reassigns the path ticket using the validated owner/reason/version body and idempotency header.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PutMapping("/service-tickets/{ticketId}/assign")
    public ApiResponse<ServiceTicket> assign(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String ticketId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ServiceTicketOwner input) throws JsonProcessingException {
        return ApiResponse.success(service.assign(actor(jwt), ticketId, key, input));
    }

    /**
     * Appends a validated treatment step for the path ticket with an idempotency header.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PostMapping("/service-tickets/{ticketId}/followups")
    public ApiResponse<ServiceTicket> followup(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String ticketId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ServiceTicketFollowup input) throws JsonProcessingException {
        return ApiResponse.success(service.followup(actor(jwt), ticketId, key, input));
    }

    /**
     * Closes the path ticket from the validated resolution/version body with an idempotency header.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PutMapping("/service-tickets/{ticketId}/close")
    public ApiResponse<ServiceTicket> close(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String ticketId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ServiceTicketClose input) throws JsonProcessingException {
        return ApiResponse.success(service.close(actor(jwt), ticketId, key, input));
    }

    /**
     * Links a validated refund request to the path ticket with an idempotency header.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return the operation result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @PutMapping("/service-tickets/{ticketId}/refund-request")
    public ApiResponse<ServiceTicket> linkRefund(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String ticketId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ServiceTicketRefundLink input) throws JsonProcessingException {
        return ApiResponse.success(service.linkRefund(actor(jwt), ticketId, key, input));
    }

    /**
     * Returns the latest ticket events visible to the authenticated caller.
     * @param jwt authenticated OIDC token; issuer and subject resolve the server-side actor.
     * @param ticketId ticket public identifier.
     * @return the operation result.
     * @throws BusinessException if authentication, authorization, validation, scope or state checks fail.
     */
    @GetMapping("/service-tickets/{ticketId}/events")
    public ApiResponse<java.util.List<ServiceTicketEvent>> events(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String ticketId) {
        return ApiResponse.success(service.events(actor(jwt), ticketId));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
