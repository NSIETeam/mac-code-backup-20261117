package cn.siyue.core;

import cn.siyue.core.AcademicDtos.StudentSchedulePage;
import cn.siyue.core.AcademicDtos.StudentScheduleQuery;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the bounded, dual-scope student schedule query under the academic API. */
@RestController
@Validated
@RequestMapping("/api/v2/academic/schedule")
public class AcademicStudentScheduleController {
    private final AccessRepository access;
    private final AcademicStudentScheduleService service;

    /** Creates the route adapter with local identity resolution and the schedule query service. */
    public AcademicStudentScheduleController(AccessRepository access, AcademicStudentScheduleService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * Returns schedule rows for all visible students or one visible student.
     * @param jwt accepted JWT resolved to current local grants
     * @param query requires a half-open interval of at most 31 days and supports pages of up to 100 rows
     * @return the bounded page; total counts matching student-session rows in the caller's intersected scopes
     * @throws BusinessException when the local account or either read grant is unavailable, or query bounds fail
     */
    @GetMapping("/students")
    public ApiResponse<StudentSchedulePage> students(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute StudentScheduleQuery query) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.list(actor, query));
    }
}
