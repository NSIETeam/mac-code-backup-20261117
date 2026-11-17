package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes /api/v2/settings/dictionary reads and updates. The service requires settings.update for reads and
 * additionally all data scope for updates; this adapter resolves the local caller and wraps the result.
 */
@RestController
@RequestMapping("/api/v2/settings/dictionary")
public class AcademicDictionaryController {
    private final AccessRepository access;
    private final AcademicDictionaryService service;
    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public AcademicDictionaryController(AccessRepository access, AcademicDictionaryService service) {
        this.access = access; this.service = service;
    }
    /**
     * Reads all supported categories and their non-deleted values in configured order.
     * @param jwt accepted JWT; its issuer/subject resolves an active local database-resolved caller with
     *     settings.update; no all-data-scope condition applies to this read
     * @return success response containing all five dictionary categories with active values ordered by sequence and
     *     internal identifier
     * @throws BusinessException if no active local account matches the JWT, or if settings.update is absent
     */
    @GetMapping
    public ApiResponse<AcademicDictionaryService.Dictionary> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.get(actor(jwt)));
    }
    /**
     * Replaces one dictionary category while retaining prior rows and recording an exact retry receipt.
     * @param jwt accepted JWT; its issuer/subject resolves an active local caller with settings.update and all-data
     *     scope, rechecked after locking the active principal
     * @param key retry key containing 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input supported category and 1–200 values, each nonblank and at most 100 trimmed characters; duplicate
     *     trimmed values are rejected and supplied ordering is retained
     * @return success response containing the complete dictionary after replacement, or the original result for the
     *     same normalized request
     * @throws BusinessException if no active local account matches the JWT, or for missing/revoked permission,
     *     non-global scope, invalid category/values/key, duplicate normalized values or reuse of a retry key for
     *     another request
     * @throws JsonProcessingException when service request or replay/result JSON cannot be encoded or decoded
     */
    @PutMapping
    public ApiResponse<AcademicDictionaryService.Dictionary> update(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @RequestBody AcademicDictionaryService.Input input)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
