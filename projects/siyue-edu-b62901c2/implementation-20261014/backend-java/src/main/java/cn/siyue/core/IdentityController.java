package cn.siyue.core;

import cn.siyue.core.IdentityDtos.CurrentIdentity;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the server-resolved identity and current grants for a validated bearer token. */
@RestController
@RequestMapping("/api/v2/identity")
public class IdentityController {
    private final AccessRepository access;
    /** 装配服务端身份及当前权限解析仓库。
     * @param access 按 issuer/subject 映射有效员工身份并读取权限的仓库
     */
    public IdentityController(AccessRepository access) { this.access = access; }

    /** 将已验签令牌映射为当前数据库身份，不以令牌自报角色作为权限依据。
     * @param jwt 由资源服务器安全链验证的员工访问令牌
     * @return 当前员工公开编号、数据范围、校区编号及权限集合的成功响应
     * @throws BusinessException 员工映射不存在、停用或身份解析不允许访问时
     */
    @GetMapping("/me")
    public ApiResponse<CurrentIdentity> current(@AuthenticationPrincipal Jwt jwt) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        List<String> campusIds = access.campusPublicIds(actor);
        CurrentIdentity identity = new CurrentIdentity(actor.publicId(), actor.scope(), campusIds,
            Set.copyOf(actor.permissions()));
        return ApiResponse.success(identity);
    }
}
