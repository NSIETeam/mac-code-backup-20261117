package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes versioned attendance corrections under /api/v2/academic. Combines the selected session/student into an
 * explicit correction target and delegates authorization, history and lesson-consumption adjustments to the
 * service.
 */
@RestController
@RequestMapping("/api/v2/academic")
@Validated
public class AttendanceCorrectionController {
    private final AccessRepository access;
    private final AttendanceCorrectionService service;
    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public AttendanceCorrectionController(AccessRepository access,AttendanceCorrectionService service) {
        this.access=access;this.service=service;
    }
    /**
     * Corrects scoped attendance and appends its history and replay receipt in the service transaction.
     * @param jwt accepted JWT resolving to an active caller with attendance.manage or session.attendance
     * @param sessionId session public identifier paired with the selected student
     * @param studentId student public identifier; the pair participates in authorization and command hashing
     * @param key Idempotency-Key header matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input expected version, class, target status and nonblank correction reason of at most 500 characters
     * @return success response containing the correction receipt or the original identical-request receipt
     * @throws BusinessException when local identity, permission, target scope, input, version, retry key,
     *     lesson balance or locked-attendance checks reject the correction
     * @throws JsonProcessingException when service command or receipt JSON cannot be encoded or decoded
     */
    @PostMapping("/sessions/{sessionId}/students/{studentId}/attendance/corrections")
    public ApiResponse<AttendanceCorrectionDtos.Receipt> correct(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max=120) String sessionId,@PathVariable @Size(max=120) String studentId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AttendanceCorrectionDtos.Input input)
            throws JsonProcessingException {
        Actor actor=access.resolve(jwt.getIssuer().toString(),jwt.getSubject());
        return ApiResponse.success(service.correct(actor,new AttendanceCorrectionDtos.Target(studentId,sessionId),key,input));
    }
}
