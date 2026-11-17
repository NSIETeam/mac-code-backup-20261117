package cn.siyue.core;

import cn.siyue.core.StudentIdentifierDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes external identifier filing for an explicitly named customer/student relationship under
 * /api/v2/crm/customers/{customerId}/students/{studentId}/identifiers. Delegates relationship scope, versions and
 * audit to the service; filing does not grant academic access.
 */
@RestController
@RequestMapping("/api/v2/crm/customers/{customerId}/students/{studentId}/identifiers")
public class StudentIdentifierController {
    private final AccessRepository access;
    private final StudentIdentifierService service;
    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public StudentIdentifierController(AccessRepository access,StudentIdentifierService service){this.access=access;this.service=service;}
    /**
     * Lists identifiers attached to the explicitly selected customer/student relationship.
     * @param jwt accepted JWT resolving to an active local caller with crm.customer.read and either
     *     crm.opportunity.intake or student.read
     * @param path path variables containing customerId and studentId for the currently linked household/child
     * @param query status and pagination filters
     * @return success response containing scoped identifier page
     * @throws BusinessException if no active local account matches the JWT, or if required read grants, relationship
     *     scope, status, or pagination are invalid
     */
    @GetMapping
    public ApiResponse<Page> list(@AuthenticationPrincipal Jwt jwt,@PathVariable Map<String,String> path,@Valid @ModelAttribute Query query){
        return ApiResponse.success(service.list(actor(jwt),target(path),query));
    }
    /**
     * Creates an identifier record without changing the student version or family linkage.
     * @param jwt accepted JWT resolving to an active local caller with crm.customer.read and
     *     crm.customer.identity.write
     * @param path path variables containing customerId and studentId; both participate in the idempotency command
     * @param key Idempotency-Key header matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input identifier namespace/value and expected student version
     * @return success response containing created identifier or the prior result for an identical retry
     * @throws BusinessException if no active local account matches the JWT, or if permission, relationship scope,
     *     validation, version, or retry checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping
    public ApiResponse<Identifier> create(@AuthenticationPrincipal Jwt jwt,@PathVariable Map<String,String> path,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create input)throws JsonProcessingException{
        return ApiResponse.success(service.create(actor(jwt),target(path),key,input));
    }
    /**
     * Deactivates an identifier with a reason and optimistic version check.
     * @param jwt accepted JWT resolving to an active local caller with crm.customer.read and
     *     crm.customer.identity.write
     * @param path path variables customerId, studentId and identifierId identifying the linked child and identifier
     *     record
     * @param key Idempotency-Key header matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input expected identifier version and deactivation reason
     * @return success response containing deactivated identifier or the prior result for an identical retry
     * @throws BusinessException if no active local account matches the JWT, or if permission, relationship scope,
     *     validation, version, or retry checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/{identifierId}/deactivate")
    public ApiResponse<Identifier> deactivate(@AuthenticationPrincipal Jwt jwt,@PathVariable Map<String,String> path,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Deactivate input)throws JsonProcessingException{
        return ApiResponse.success(service.deactivate(actor(jwt),target(path),path.get("identifierId"),key,input));
    }
    private Target target(Map<String,String> path){return new Target(path.get("customerId"),path.get("studentId"));}
    private Actor actor(Jwt jwt){return access.resolve(jwt.getIssuer().toString(),jwt.getSubject());}
}
