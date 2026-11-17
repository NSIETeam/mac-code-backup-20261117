package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** System settings read and global mutation API. */
@RestController
@RequestMapping("/api/v2/settings")
public class SystemSettingsController {
    private final AccessRepository access;
    private final SystemSettingsService service;
    /**
     * 装配按分类读取和修改系统设置的入口。
     * @param access JWT issuer/subject 到本地身份的解析器
     * @param service 设置读取及加密、幂等更新服务
     */
    public SystemSettingsController(AccessRepository access, SystemSettingsService service) {
        this.access = access; this.service = service;
    }
    /**
     * 读取已保存的设置；允许 settings.read 或 settings.update。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @return 按分类组织的布尔值、整数及文本，未保存分类为空映射
     * @throws BusinessException 本地身份不可用或缺少设置读取权限时
     * @throws IllegalStateException 存储设置包含未知分类或字段时
     */
    @GetMapping
    public ApiResponse<SystemSettingsService.Settings> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.get(actor(jwt)));
    }
    /**
     * 以 settings.update 和全部数据范围更新一个分类，未提交字段保持不变。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次修改的幂等键
     * @param input 分类与非空字段映射，由服务按类型及固定安全规则校验
     * @return 全部当前设置或同请求已保存的设置结果
     * @throws BusinessException 身份、权限、范围、字段类型、安全规则或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或重放结果的 JSON 编解码失败时
     * @throws IllegalStateException 读取存储值时遇到未知分类或字段时
     */
    @PutMapping
    public ApiResponse<SystemSettingsService.Settings> update(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SystemSettingsService.Input input)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
