package cn.siyue.core;

import cn.siyue.core.DashboardDtos.Summary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the permission-projected home dashboard aggregates. */
@RestController
@RequestMapping("/api/v2/dashboard")
public class DashboardController {
    private final AccessRepository access;
    private final DashboardService service;

    /**
     * 装配经营概览的身份解析和聚合服务。
     * @param access 可信 issuer/subject 的本地身份读取器
     * @param service 按底层资源权限分别投影概览指标的服务
     */
    public DashboardController(AccessRepository access, DashboardService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * 读取经营概览；dashboard.read 不单独授予底层线索、学生、订单或转化指标。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析当前本地身份
     * @return 分别按 lead.read、student.read、order.read、lead.conversion.read 投影的汇总，无权限指标为 null
     * @throws BusinessException 本地身份不可用或缺少 dashboard.read 权限时
     */
    @GetMapping
    public ApiResponse<Summary> get(@AuthenticationPrincipal Jwt jwt) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.get(actor));
    }
}
