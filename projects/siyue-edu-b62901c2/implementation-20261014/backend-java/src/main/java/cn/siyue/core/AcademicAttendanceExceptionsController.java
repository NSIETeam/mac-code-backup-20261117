package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes exception-only roster attendance under /api/v2/academic. Resolves the local caller and delegates
 * attended defaults, correction history and atomic recording to AcademicAttendanceExceptionsService.
 */
@RestController
@RequestMapping("/api/v2/academic")
public class AcademicAttendanceExceptionsController {
    private final AccessRepository access;
    private final AcademicAttendanceExceptionsService service;

    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public AcademicAttendanceExceptionsController(AccessRepository access,
            AcademicAttendanceExceptionsService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Registers the current roster atomically, with explicit exceptions and attended defaults.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with attendance.manage or
     *     session.attendance, reloaded under the principal lock
     * @param sessionId public identifier of a session visible in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input selected class and up to 1000 distinct roster-student exceptions with late, left_early, leave or
     *     absent status; omitted students become attended and an absent note becomes empty text
     * @return success response containing attendance records, total roster size and separate attended, leave and
     *     absent counts; late and left_early remain in the records and total but not those three counts
     * @throws BusinessException if no active local account matches the JWT, or for missing/revoked permission,
     *     invisible or ineligible session, teacher-date restriction, invalid or duplicate exceptions,
     *     correction/version failure or conflicting retry key
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/sessions/{sessionId}/attendance/exceptions")
    public ApiResponse<AcademicDtos.AttendanceExceptionReceipt> record(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String sessionId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicDtos.AttendanceExceptionInput input) throws JsonProcessingException {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.record(actor, sessionId, key, input));
    }
}
