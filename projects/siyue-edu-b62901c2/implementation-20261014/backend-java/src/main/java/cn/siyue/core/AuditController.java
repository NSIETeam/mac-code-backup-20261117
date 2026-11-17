package cn.siyue.core;

import cn.siyue.core.AuditDtos.AuditPage;
import cn.siyue.core.AuditDtos.AuditQuery;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Scoped, read-only audit history endpoint. */
@RestController
@RequestMapping("/api/v2/audit")
@Validated
public class AuditController {
    private final AccessRepository access;
    private final AuditService service;
    /** 装配身份解析与受限审计查询服务。
     * @param access 员工身份及当前权限解析仓库
     * @param service 校验审计权限、范围及分页的服务
     */
    public AuditController(AccessRepository access,AuditService service) {
        this.access=access;this.service=service;
    }

    /** 返回当前身份权限和数据范围内的一页审计历史。
     * @param jwt 资源服务器已验证的员工访问令牌
     * @param query 入口绑定的操作人、动作、资源、时间及分页条件
     * @return 审计记录页的成功响应
     * @throws BusinessException 身份、audit.read 权限、范围或查询边界检查失败时
     */
    @GetMapping
    public ApiResponse<AuditPage> search(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute AuditQuery query) {
        return ApiResponse.success(service.search(access.resolve(jwt.getIssuer().toString(),jwt.getSubject()),
            query));
    }
}
