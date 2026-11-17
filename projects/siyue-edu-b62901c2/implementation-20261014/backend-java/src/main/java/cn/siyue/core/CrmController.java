package cn.siyue.core;

import cn.siyue.core.CrmDtos.Customer;
import cn.siyue.core.CrmDtos.CustomerContact;
import cn.siyue.core.CrmDtos.CustomerContactInput;
import cn.siyue.core.CrmDtos.CustomerInput;
import cn.siyue.core.CrmDtos.CustomerPage;
import cn.siyue.core.CrmDtos.CustomerStudent;
import cn.siyue.core.CrmDtos.CustomerStudentInput;
import cn.siyue.core.CrmDtos.CustomerStudentIntakeInput;
import cn.siyue.core.CrmDtos.Touchpoint;
import cn.siyue.core.CrmDtos.TouchpointInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned CRM customer endpoints backed by Java and MySQL. */
@RestController
@RequestMapping("/api/v2/crm/customers")
@Validated
public class CrmController {
    private final AccessRepository access;
    private final CrmService service;
    private final CustomerStudentIntakeService studentIntake;
    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     * @param studentIntake student intake service invoked within the customer-link workflow.
     */
    public CrmController(AccessRepository access, CrmService service,
            CustomerStudentIntakeService studentIntake) {
        this.access = access;
        this.service = service;
        this.studentIntake = studentIntake;
    }
    /** Searches customer records using a typed search mode or a present imported saved view.
     * @param jwt validated OIDC token; issuer and subject resolve the application principal
     * @param filters typed search criteria and pagination; their page/size also apply to a saved view
     * @param savedView optional imported customer filters; an absent or empty view uses typed search
     * @return scoped customer summaries and matching total in the standard response envelope
     * @throws BusinessException if principal resolution, read/search grants, scope or filter validation fails
     */
    @GetMapping
    public ApiResponse<CustomerPage> customers(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute CustomerSearchDtos.Query filters,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute LegacySavedViewQuery savedView) {
        if (savedView != null && savedView.present()) {
            return ApiResponse.success(service.savedViewCustomers(actor(jwt), savedView, filters.page(), filters.size()));
        }
        return ApiResponse.success("auto".equals(filters.searchType())
            ? service.customers(actor(jwt), filters.query(), filters.page(), filters.size())
            : service.customers(actor(jwt), filters));
    }
    /**
     * Creates a household profile without merging duplicate identities and requires an idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping
    public ApiResponse<Customer> createCustomer(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CustomerInput input) throws JsonProcessingException {
        return ApiResponse.success(service.createCustomer(actor(jwt), key, input));
    }
    /**
     * Reads one scoped customer profile and its permission-filtered household details.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param customerId customer public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/{customerId}")
    public ApiResponse<Customer> customer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String customerId) {
        return ApiResponse.success(service.customer(actor(jwt), customerId));
    }
    /**
     * Appends a validated contact to a scoped customer using an idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param customerId customer public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/{customerId}/contacts")
    public ApiResponse<CustomerContact> addContact(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String customerId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CustomerContactInput input) throws JsonProcessingException {
        return ApiResponse.success(service.addContact(actor(jwt), customerId, key, input));
    }
    /**
     * Links an existing separately identified student to the customer household.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param customerId customer public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/{customerId}/students")
    public ApiResponse<CustomerStudent> linkStudent(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String customerId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CustomerStudentInput input) throws JsonProcessingException {
        return ApiResponse.success(service.linkStudent(actor(jwt), customerId, key, input));
    }
    /**
     * Creates and links a student intake profile within the same idempotent transaction.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param customerId customer public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/{customerId}/students/intake")
    public ApiResponse<CustomerStudent> createAndLinkStudent(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String customerId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CustomerStudentIntakeInput input) throws JsonProcessingException {
        return ApiResponse.success(studentIntake.create(actor(jwt), customerId, key, input));
    }
    /**
     * Reads the latest touchpoints visible under the current customer and touchpoint permissions.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param customerId customer public identifier from the route.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/{customerId}/touchpoints")
    public ApiResponse<java.util.List<Touchpoint>> touchpoints(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String customerId) {
        return ApiResponse.success(service.touchpoints(actor(jwt), customerId));
    }
    /**
     * Appends a touchpoint; its channel describes the interaction and does not trigger delivery.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param customerId customer public identifier from the route.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/{customerId}/touchpoints")
    public ApiResponse<Touchpoint> createTouchpoint(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String customerId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TouchpointInput input) throws JsonProcessingException {
        return ApiResponse.success(service.createTouchpoint(actor(jwt), customerId, key, input));
    }
    private Actor actor(Jwt jwt) {
        return access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
    }
}
