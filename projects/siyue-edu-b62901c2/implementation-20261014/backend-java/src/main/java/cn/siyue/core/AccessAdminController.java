package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.CreateInput;
import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.AccessAdminDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit employee provisioning and grant maintenance. */
@RestController
@RequestMapping("/api/v2/system/access")
@Validated
public class AccessAdminController {
    private final AccessRepository access;
    private final AccessAdminService service;

    /**
     * 装配员工身份映射及授权维护入口；不在此接口验证外部账号密码。
     * @param access 可信 issuer/subject 到本地身份的解析器
     * @param service 全局管理员校验、授权版本及幂等审计服务
     */
    public AccessAdminController(AccessRepository access, AccessAdminService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * 读取员工授权分页；服务串行化管理变更后重新检查 system.access.manage 及全部范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 不包含提供方 subject 的员工授权列表
     * @throws BusinessException 身份、全局管理权限或分页无效时
     */
    @GetMapping("/principals")
    public ApiResponse<List<PrincipalView>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(actor(jwt), page, size));
    }

    /**
     * 创建管理员提供的 OIDC 身份绑定及可授予权限，不创建登录凭据或验证外部 subject 的拥有权。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次身份创建的幂等键
     * @param input issuer、subject、本地标识、范围、授予/拒绝权限和校区
     * @return 创建后的身份授权或同请求原回执
     * @throws BusinessException 身份、全局管理权限、输入、目标版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping("/principals")
    public ApiResponse<PrincipalView> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }

    /**
     * 按期望版本更新另一员工的授权及启用状态；重放前仍禁止管理员修改自身授权。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param principalId 目标员工公开标识，不能是当前管理员本人
     * @param key 本次授权修改的幂等键
     * @param input 期望版本、范围、启用状态、权限和校区设置
     * @return 更新后的授权或同请求原回执
     * @throws BusinessException 身份、全局管理权限、输入、目标版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/principals/{principalId}")
    public ApiResponse<PrincipalView> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String principalId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody UpdateInput input) throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), principalId, key, input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
