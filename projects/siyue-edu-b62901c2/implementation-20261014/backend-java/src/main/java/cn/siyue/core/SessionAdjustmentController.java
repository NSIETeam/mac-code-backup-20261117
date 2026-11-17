package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes scoped makeup listing, assignment, waiver and cancellation under /api/v2/academic/makeup. Resolves the
 * local caller and delegates business authorization, versions and idempotency to SessionAdjustmentService.
 */
@RestController
@RequestMapping("/api/v2/academic/makeup")
@Validated
public class SessionAdjustmentController {
    private final AccessRepository access;
    private final SessionAdjustmentService service;

    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public SessionAdjustmentController(AccessRepository access, SessionAdjustmentService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Lists session adjustments visible under the actor's class-read scope.
     * @param jwt accepted JWT resolving to an active local caller with class.read
     * @param query requested filters and pagination
     * @return success response containing scoped adjustment page
     * @throws BusinessException if no active local account matches the JWT, or if the actor lacks class-read
     *     permission or query is invalid
     */
    @GetMapping
    public ApiResponse<SessionAdjustmentDtos.Page> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute SessionAdjustmentDtos.Query query) {
        return ApiResponse.success(service.list(actor(jwt), query));
    }

    /**
     * Assigns a version-checked makeup or temporary placement to a target class session.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session-move
     *     permission and applicable source/target scope
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input enrollment, source and target identifiers, kind {@code makeup} or {@code temporary}, nonblank
     *     reason up to 500 characters, and positive versions for enrollment and both sessions
     * @return success response containing the created adjustment receipt, or the stored receipt for an identical retry
     * @throws BusinessException when no active local account matches the JWT, or when permission, scope, input, state,
     *     version, or idempotency checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/assign")
    public ApiResponse<SessionAdjustmentDtos.Receipt> assign(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SessionAdjustmentDtos.AssignInput input) throws JsonProcessingException {
        return ApiResponse.success(service.assign(actor(jwt), key, input));
    }

    /**
     * Waives one version-checked source session without creating a target placement.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session-move
     *     permission and source enrollment/session scope
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input enrollment and source session identifiers, nonblank reason up to 500 characters, and positive
     *     enrollment/source-session versions
     * @return success response containing the waiver receipt, or the stored receipt for an identical retry
     * @throws BusinessException when no active local account matches the JWT, or when permission, scope, input, state,
     *     version, or idempotency checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/waive")
    public ApiResponse<SessionAdjustmentDtos.Receipt> waive(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SessionAdjustmentDtos.WaiveInput input) throws JsonProcessingException {
        return ApiResponse.success(service.waive(actor(jwt), key, input));
    }

    /**
     * Cancels an active session adjustment using its expected version and an audit reason.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with session-move
     *     permission and adjustment scope
     * @param adjustmentId adjustment public identifier
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input nonblank cancellation reason up to 500 characters and expected adjustment version at least 1
     * @return success response containing the cancelled adjustment receipt, or the stored receipt for an identical
     *     retry
     * @throws BusinessException when no active local account matches the JWT, or when permission, scope, input, state,
     *     version, or idempotency checks fail
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PostMapping("/{adjustmentId}/cancel")
    public ApiResponse<SessionAdjustmentDtos.Receipt> cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String adjustmentId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SessionAdjustmentDtos.CancelInput input) throws JsonProcessingException {
        return ApiResponse.success(service.cancel(actor(jwt), adjustmentId, key, input));
    }

    private Actor actor(Jwt jwt) {
        return access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
    }
}
