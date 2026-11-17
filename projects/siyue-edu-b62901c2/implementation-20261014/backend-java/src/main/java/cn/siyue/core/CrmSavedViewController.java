package cn.siyue.core;

import cn.siyue.core.CrmSavedViewDtos.Create;
import cn.siyue.core.CrmSavedViewDtos.Delete;
import cn.siyue.core.CrmSavedViewDtos.Update;
import cn.siyue.core.CrmSavedViewDtos.View;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Private saved-filter views for CRM employee queues. */
@RestController
@RequestMapping("/api/v2/crm/saved-views")
@Validated
public class CrmSavedViewController {
    private final AccessRepository access;
    private final CrmSavedViewService service;
    /**
     * 装配 CRM 员工私有筛选预设入口。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 队列读取授权、预设所有权及版本化幂等服务
     */
    public CrmSavedViewController(AccessRepository access, CrmSavedViewService service) {
        this.access = access;
        this.service = service;
    }
    /**
     * 列出本人指定队列的未删除筛选预设，不查询预设所指向的业务记录。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param resourceType 此读取路由接受 leads、tasks、taskBatches、opportunities、serviceTickets 或 customers
     * @return 本人该队列的预设列表，最多 20 项
     * @throws BusinessException 身份、队列类型或对应队列读取权限无效时
     */
    @GetMapping
    public ApiResponse<List<View>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @Size(max = 32)
                @Pattern(regexp = "leads|tasks|taskBatches|opportunities|serviceTickets|customers")
                String resourceType) {
        return ApiResponse.success(service.list(actor(jwt), resourceType));
    }
    /**
     * 创建个人筛选预设；服务按资源白名单校验字段并串行控制每类队列数量。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次创建的幂等键
     * @param input 资源类型、名称及该类允许的筛选键值
     * @return 新预设或同请求原回执
     * @throws BusinessException 身份、队列读取权限、输入、数量、名称唯一性或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping
    public ApiResponse<View> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }
    /**
     * 修改本人预设，先校验所有权及队列读取权限，再校验版本并保存回执。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param viewId 本人预设的公开标识
     * @param key 本次修改的幂等键
     * @param input 新名称、筛选键值及期望版本
     * @return 更新后的预设或同请求原回执
     * @throws BusinessException 身份、所有权、权限、筛选、名称、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{viewId}")
    public ApiResponse<View> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String viewId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Update input) throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), viewId, key, input));
    }
    /**
     * 逻辑删除本人筛选预设并保留变更审计。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param viewId 本人预设的公开标识
     * @param key 本次删除的幂等键
     * @param input 预设期望版本
     * @return 已删除预设的公开标识或同请求已保存标识
     * @throws BusinessException 身份、所有权、队列读取权限、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping("/{viewId}/delete")
    public ApiResponse<String> delete(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String viewId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Delete input) throws JsonProcessingException {
        return ApiResponse.success(service.delete(actor(jwt), viewId, key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
