package cn.siyue.core;

import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes the scoped current-student export at /api/v2/academic/students/export. Resolves local grants and returns
 * the service export as a JSON response envelope.
 */
@RestController
@RequestMapping("/api/v2/academic/students")
@Validated
public class StudentExportController {
    private final AccessRepository access;
    private final StudentExportService service;
    /**
     * Creates the academic HTTP adapter with local identity resolution and its business service.
     * @param access resolves the JWT issuer and subject to an active local account and current grants
     * @param service implements the scoped business operations and their transaction/replay rules
     */
    public StudentExportController(AccessRepository access,StudentExportService service) { this.access=access;this.service=service; }
    /**
     * Exports rows from the current student directory under the actor's existing read scope.
     * @param jwt accepted JWT resolving to an active local caller with student.read
     * @param view requested directory view normalized by the academic read-scope rules
     * @param classId optional public class identifier filter
     * @return success response containing export rows and the normalized filters applied
     * @throws BusinessException if no active local account matches the JWT, or if student-read permission is missing
     *     or the view/class filter is invalid
     */
    @GetMapping("/export")
    public ApiResponse<StudentExportService.Export> export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="scope") String view,@RequestParam(required=false) @Size(max=120) String classId) {
        return ApiResponse.success(service.export(access.resolve(jwt.getIssuer().toString(),jwt.getSubject()),view,classId));
    }
}
