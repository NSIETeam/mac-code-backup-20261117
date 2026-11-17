package cn.siyue.core;

import cn.siyue.core.CrmDtos.ReceiptReport;
import cn.siyue.core.CrmDtos.CashFlowReport;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, server-scoped CRM reporting endpoints. */
@RestController
@RequestMapping("/api/v2/crm/reports")
public class CrmReportController {
    private final AccessRepository access;
    private final CrmOrderService service;
    private final CrmRefundService refunds;

    /**
     * Resolves the authenticated actor and delegates to the corresponding scoped CRM service.
     * @param access repository used to resolve the authenticated server-side actor.
     * @param service application service that enforces permissions, scope and business rules.
     * @param refunds value supplied to the request handler.
     */
    public CrmReportController(AccessRepository access, CrmOrderService service, CrmRefundService refunds) {
        this.access = access;
        this.service = service;
        this.refunds = refunds;
    }

    /**
     * Aggregates receipt facts for the requested reporting interval.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param fromAt inclusive start instant for the report interval.
     * @param untilAt exclusive end instant for the report interval.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/receipts")
    public ApiResponse<ReceiptReport> receipts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam Instant fromAt, @RequestParam Instant untilAt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.receiptReport(actor, fromAt, untilAt, page, size));
    }

    /**
     * Aggregates receipts, refunds and net movement by immutable seller attribution for the interval.
     * @param jwt validated OIDC token; issuer and subject are resolved to the server-side actor.
     * @param fromAt inclusive start instant for the report interval.
     * @param untilAt exclusive end instant for the report interval.
     * @param page 1-based page number.
     * @param size page size subject to the service bounds.
     * @return A success response envelope containing the service result.
     * @throws BusinessException if authentication, authorization, input, resource scope or workflow-state checks fail.
     */
    @GetMapping("/cash-flow")
    public ApiResponse<CashFlowReport> cashFlow(@AuthenticationPrincipal Jwt jwt,
            @RequestParam Instant fromAt, @RequestParam Instant untilAt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(refunds.cashFlowReport(actor, fromAt, untilAt, page, size));
    }
}
