package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmImportInput;
import cn.siyue.core.CrmDtos.CrmImportPreview;
import cn.siyue.core.CrmDtos.CrmImportReceipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Java/MySQL customer and lead import endpoints. */
@RestController
@RequestMapping("/api/v2/crm/imports")
public class CrmImportController {
    private final AccessRepository access;
    private final CrmImportService service;
    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     */
    public CrmImportController(AccessRepository access, CrmImportService service) {
        this.access = access; this.service = service;
    }
    /**
     * Parses an uploaded workbook or CSV and returns masked row outcomes without committing them.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping("/preview")
    public ApiResponse<CrmImportPreview> preview(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CrmImportInput input) {
        return ApiResponse.success(service.preview(actor(jwt), input));
    }
    /**
     * Revalidates eligible rows and commits the upload with an idempotency key.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param key Idempotency-Key request header.
     * @param input validated request body.
     * @return A success response envelope containing the service result.
     * @throws JsonProcessingException if request or replay JSON serialization or parsing fails.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @PostMapping
    public ApiResponse<CrmImportReceipt> commit(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CrmImportInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.commit(actor(jwt), key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
