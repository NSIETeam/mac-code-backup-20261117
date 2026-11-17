package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes teacher schedule-change requests, scoped listing and manager review under
 * /api/v2/academic/session-reschedules. Resolves local grants and delegates request/review authorization, versions
 * and replay to the service.
 */
@RestController
@RequestMapping("/api/v2/academic/session-reschedules")
public class AcademicRescheduleController {
    private final AccessRepository access;
    private final AcademicRescheduleService service;

    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public AcademicRescheduleController(AccessRepository access, AcademicRescheduleService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Creates a pending request without moving the session, preserving its original timetable facts.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session.reschedule.request,
     *     revalidated under the active-principal lock
     * @param key retry key containing 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input matching class/session identifiers, nonblank reason up to 500 characters, expected session version
     *     at least 1, ISO local date and HH:mm–HH:mm range of the original duration; ASCII hyphen or em dash is also
     *     accepted, and an end time of 24:00 denotes midnight
     * @return success response containing pending request identifier, requested start and request version, or the
     *     retained retry receipt
     * @throws BusinessException if no active local account matches the JWT, or for invalid fields/time/key,
     *     authority/scope or teacher mismatch, stale/protected session, schedule/resource conflict or conflicting
     *     retry key
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping
    public ApiResponse<AcademicRescheduleDtos.RequestReceipt> request(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicRescheduleDtos.RequestInput input) throws JsonProcessingException {
        return ApiResponse.success(service.request(actor(jwt), key, input));
    }

    /**
     * Lists visible requests after rechecking current active grants.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session.reschedule.request or
     *     session.move
     * @param status optional pending, approved or rejected status filter
     * @param page optional one-based page number from 1 through 100000; defaults to 1
     * @param size optional page size from 1 through 100; defaults to 50
     * @return success response containing visible requests, total count and paging metadata, with requested business
     *     date/time projections
     * @throws BusinessException if no active local account matches the JWT, or for missing/revoked grants, no
     *     applicable read scope or invalid query fields
     */
    @GetMapping
    public ApiResponse<AcademicRescheduleDtos.RequestPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.list(actor(jwt), new AcademicRescheduleDtos.ListQuery(status, page, size)));
    }

    /**
     * Approves or rejects one pending request at its expected version and appends a decision event.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session.move, revalidated under
     *     the active-principal lock
     * @param requestId nonblank public identifier of the request to decide
     * @param key retry key containing 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input approve or reject, expected request version at least 1 and note up to 500 characters; rejection
     *     requires a nonblank note
     * @return success response containing updated request receipt and, for approval, the schedule-move receipt;
     *     rejection has no move receipt
     * @throws BusinessException if no active local account matches the JWT, or for invalid fields/key, missing
     *     authority or request scope, stale/non-pending request, self-approval, changed original timetable, failed
     *     move validation or conflicting retry key
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/{requestId}/review")
    public ApiResponse<AcademicRescheduleDtos.ReviewReceipt> review(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String requestId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicRescheduleDtos.ReviewInput input) throws JsonProcessingException {
        return ApiResponse.success(service.review(actor(jwt), requestId, key, input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
