package cn.siyue.core;

import cn.siyue.core.StaffDirectoryDtos.DirectoryPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves permission-filtered staff and teacher directory pages. */
@RestController
@Validated
@RequestMapping("/api/v2/system/staff/directory")
public class StaffDirectoryController {
    private final AccessRepository access;
    private final StaffDirectoryService service;

    /** Wires OIDC principal resolution and the directory query service.
     * @param access maps the validated token issuer and subject to a server-side principal
     * @param service directory authorization and profile query boundary
     */
    public StaffDirectoryController(AccessRepository access, StaffDirectoryService service) {
        this.access = access;
        this.service = service;
    }

    /** Returns the staff or teacher directory permitted by the authenticated principal's current scope.
     * @param jwt validated OIDC token; credentials and token claims are not used as directory profile data
     * @param category all, staff or teacher, defaulting to all
     * @param page one-based page number, defaulting to 1
     * @param size requested maximum rows from 1 through 100
     * @return directory page wrapped in the standard API response
     * @throws BusinessException if principal resolution, directory grants, scope or pagination reject the request
     */
    @GetMapping
    public ApiResponse<DirectoryPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|staff|teacher") String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(actor(jwt), category, page, size));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
