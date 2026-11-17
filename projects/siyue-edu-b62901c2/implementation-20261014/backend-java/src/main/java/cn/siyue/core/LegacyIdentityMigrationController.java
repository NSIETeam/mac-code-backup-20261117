package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.LegacyIdentityMigrationDtos.BindInput;
import cn.siyue.core.LegacyIdentityMigrationDtos.PrepareInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit endpoint for binding imported legacy identities to pre-created stable principals. */
@RestController
@RequestMapping("/api/v2/system/legacy-identity-migration")
public class LegacyIdentityMigrationController {
    private final AccessRepository access;
    private final LegacyIdentityMigrationService service;

    /**
     * 连接显式启用的旧身份准备和绑定入口，不执行身份平台用户创建。
     * @param access 可信 issuer/subject 的管理员身份解析器
     * @param service 锁后授权、可信清单、精确权限映射及幂等绑定服务
     */
    public LegacyIdentityMigrationController(AccessRepository access, LegacyIdentityMigrationService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * 按可信清单把稳定占位员工绑定到目标 issuer/subject，并核对最终有效权限。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param principalId 必须与旧账号编号及目标 subject 相同的稳定公开标识
     * @param idempotencyKey 绑定请求的稳定幂等键
     * @param checkpoint X-Migration-Checkpoint 私有校验值
     * @param input 来源摘要、身份、目录、精确权限及职位和改密状态
     * @return 绑定后的员工视图或同请求原回执
     * @throws BusinessException 迁移未启用、非全局访问管理员、校验值或清单不符、权限扩大、改密同步不可用或版本冲突时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/principals/{principalId}/bind")
    public ApiResponse<PrincipalView> bind(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String principalId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Migration-Checkpoint") String checkpoint,
            @Valid @RequestBody BindInput input) throws JsonProcessingException {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.bind(actor, principalId, idempotencyKey, checkpoint, input));
    }

    /**
     * 从可信清单创建或核验未启用的稳定员工占位，保留后续业务迁移引用。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param principalId 可信清单中目标员工的稳定公开标识
     * @param idempotencyKey 占位准备请求的稳定幂等键
     * @param checkpoint X-Migration-Checkpoint 私有校验值
     * @param input 可信来源摘要及准备输入
     * @return 已准备的未启用员工视图或同请求原回执
     * @throws BusinessException 迁移配置、管理员范围、校验值、可信清单、现有占位或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/principals/{principalId}/prepare")
    public ApiResponse<PrincipalView> prepare(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String principalId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Migration-Checkpoint") String checkpoint,
            @Valid @RequestBody PrepareInput input) throws JsonProcessingException {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.prepare(actor, principalId, idempotencyKey, checkpoint, input));
    }
}
