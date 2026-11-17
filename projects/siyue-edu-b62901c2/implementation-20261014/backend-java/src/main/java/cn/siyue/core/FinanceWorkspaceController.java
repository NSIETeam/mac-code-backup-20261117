package cn.siyue.core;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Exposes financial projections; identity resolution and transactions belong to the service. */
@RestController
@RequestMapping("/api/v2/finance")
public class FinanceWorkspaceController {
    private final FinanceWorkspaceService service;
    /** @param service transactional financial read use cases */
    public FinanceWorkspaceController(FinanceWorkspaceService service) {this.service=service;}
    /**
     * @param jwt verified OAuth2 identity
     * @param filter bounded financial query criteria
     * @return authorized arrears in the common response envelope
     * @throws BusinessException if identity, permissions or filter values are invalid
     */
    @GetMapping("/arrears")
    public ApiResponse<FinanceWorkspaceRepository.Page<FinanceWorkspaceRepository.Arrear>> arrears(@AuthenticationPrincipal Jwt jwt,
            @ModelAttribute FinanceWorkspaceRepository.Filter filter) {
        return ApiResponse.success(service.arrears(jwt.getIssuer().toString(),jwt.getSubject(),filter));
    }
    /**
     * @param jwt verified OAuth2 identity
     * @param filter bounded financial query criteria
     * @return authorized refunds in the common response envelope
     * @throws BusinessException if identity, permissions or filter values are invalid
     */
    @GetMapping("/refunds")
    public ApiResponse<FinanceWorkspaceRepository.Page<FinanceWorkspaceRepository.Refund>> refunds(@AuthenticationPrincipal Jwt jwt,
            @ModelAttribute FinanceWorkspaceRepository.Filter filter) {
        return ApiResponse.success(service.refunds(jwt.getIssuer().toString(),jwt.getSubject(),filter));
    }
    /**
     * @param jwt verified OAuth2 identity
     * @param filter bounded financial query criteria
     * @return authorized consumption facts in the common response envelope
     * @throws BusinessException if identity, permissions or filter values are invalid
     */
    @GetMapping("/consumptions")
    public ApiResponse<FinanceWorkspaceRepository.Page<FinanceWorkspaceRepository.Consumption>> consumptions(@AuthenticationPrincipal Jwt jwt,
            @ModelAttribute FinanceWorkspaceRepository.Filter filter) {
        return ApiResponse.success(service.consumptions(jwt.getIssuer().toString(),jwt.getSubject(),filter));
    }
}
