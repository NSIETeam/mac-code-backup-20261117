package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes whole-class schedule/location changes and per-session substitute/teaching-record routes under
 * /api/v2/academic. Resolves the local caller and delegates versioned, idempotent writes to
 * AcademicClassRuntimeService.
 */
@RestController
@RequestMapping("/api/v2/academic")
public class AcademicClassRuntimeController {
    private final AccessRepository access;
    private final AcademicClassRuntimeService service;
    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public AcademicClassRuntimeController(AccessRepository access, AcademicClassRuntimeService service) {
        this.access = access;
        this.service = service;
    }
    /**
     * Replaces a class's proposed session schedule and increments its class version once.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with class.create or class.write;
     *     active status and grants are rechecked
     * @param classId public identifier of an active class in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input expected class version at least 1 and a non-null list of at most 200 sessions; each entry
     *     identifies a course lesson and future start, and may retain an existing session identifier
     * @return success response containing class identifier, incremented version and resulting non-cancelled session
     *     summaries, or replay
     * @throws BusinessException if no active local account matches the JWT, or for authority/scope failure, invalid or
     *     duplicate lessons/sessions, protected history changes, resource/schedule conflicts, stale versions or
     *     conflicting retry keys
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/classes/{classId}/schedule")
    public ApiResponse<AcademicClassRuntimeDtos.ScheduleReceipt> schedule(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String classId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicClassRuntimeDtos.ScheduleInput input) throws JsonProcessingException {
        return ApiResponse.success(service.schedule(actor(jwt), classId, key, input));
    }
    /**
     * Relocates a batch of classes atomically and retains the original location of protected sessions.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with class.relocate; active grants,
     *     source class scope and target campus scope apply
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input 1–100 distinct class identifiers with expected versions at least 1, an active target campus and a
     *     room accepted by its subject, grade and capacity configuration
     * @return success response containing relocated class count and target campus/room, or the original receipt for an
     *     identical retry
     * @throws BusinessException if no active local account matches the JWT, or for invalid input/key, missing
     *     authority, unavailable classes/campus, stale versions, unchanged location, duplicate class name,
     *     teacher-campus or room/schedule conflicts
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/classes/relocate")
    public ApiResponse<AcademicClassRuntimeDtos.RelocateReceipt> relocate(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicClassRuntimeDtos.RelocateInput input) throws JsonProcessingException {
        return ApiResponse.success(service.relocate(actor(jwt), key, input));
    }
    /**
     * Replaces the teacher of an eligible future session and appends encrypted substitution history.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session.substitute, revalidated
     *     under the principal lock
     * @param sessionId public identifier of a non-cancelled session in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input different replacement teacher, nonblank reason and expected session version at least 1
     * @return success response containing session identifier, replacement teacher and new session version, or an
     *     identical retry receipt
     * @throws BusinessException if no active local account matches the JWT, or for invalid fields/key, authority/scope
     *     failure, stale version, protected or past session, invalid/same teacher, campus/schedule conflict or
     *     conflicting retry key
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping("/sessions/{sessionId}/substitute")
    public ApiResponse<AcademicClassRuntimeDtos.SubstituteReceipt> substitute(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String sessionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicClassRuntimeDtos.SubstituteInput input) throws JsonProcessingException {
        return ApiResponse.success(service.substitute(actor(jwt), sessionId, key, input));
    }
    /**
     * Appends an encrypted teaching record for a started session and advances session/class versions.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session.record, revalidated
     *     under the principal lock
     * @param sessionId public identifier of a started, non-cancelled session in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input nonblank teaching content of at most 10000 characters and expected session version at least 1
     * @return success response containing new record identifier, session, actor, recorded time and session version, or
     *     an identical replay
     * @throws BusinessException if no active local account matches the JWT, or for invalid fields/key, missing
     *     authority or session, stale version, a session that has not started, or conflicting retry key
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/sessions/{sessionId}/record")
    public ApiResponse<AcademicClassRuntimeDtos.RecordReceipt> record(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String sessionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AcademicClassRuntimeDtos.RecordInput input) throws JsonProcessingException {
        return ApiResponse.success(service.record(actor(jwt), sessionId, key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
