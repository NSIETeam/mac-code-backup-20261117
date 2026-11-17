package cn.siyue.core;

import cn.siyue.core.CampusConfigurationDtos.Campus;
import cn.siyue.core.CampusConfigurationDtos.CampusFields;
import cn.siyue.core.CampusConfigurationDtos.CampusPage;
import cn.siyue.core.CampusConfigurationDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

/** Scoped, versioned campus settings endpoints. */
@RestController
@Validated
@RequestMapping("/api/v2/settings/campuses")
public class CampusConfigurationController {
    private final AccessRepository access;
    private final CampusConfigurationService service;
    /**
     * 装配按数据范围读取及有版本修改的校区配置入口。
     * @param access 可信 issuer/subject 的本地身份解析器
     * @param service 校区、教室及教学选项的校验与幂等事务服务
     */
    public CampusConfigurationController(AccessRepository access, CampusConfigurationService service) {
        this.access = access; this.service = service;
    }
    /**
     * 使用 settings.update 查询可见校区；全部范围或校园成员范围由仓库过滤。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 含教室、学科、年级的校区分页；不支持的范围没有记录
     * @throws BusinessException 身份、权限或分页无效时
     */
    @GetMapping
    public ApiResponse<CampusPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(actor(jwt), page, size));
    }
    /**
     * 读取当前可见的单个校区资源配置。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 校区公开标识
     * @return 校区字段、资源配置及当前版本
     * @throws BusinessException 身份不可用、缺少 settings.update，或校区不存在、已删除、不在范围时
     */
    @GetMapping("/{id}")
    public ApiResponse<Campus> get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Size(max = 120) String id) {
        return ApiResponse.success(service.get(actor(jwt), id));
    }
    /**
     * 使用 settings.update 和全部数据范围创建校区；服务锁定目录并原子保存回执。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次校区创建的幂等键
     * @param input 新校区名称、启用状态及资源集合
     * @return 版本 1 的新校区或同请求原回执
     * @throws BusinessException 身份、全局权限、字段、唯一性或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping
    public ApiResponse<Campus> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CampusFields input) throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }
    /**
     * 按版本合并校区配置补丁，省略字段保持原值；现有班级与未来课次会限制不兼容修改。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 可见校区的公开标识
     * @param key 本次修改的幂等键
     * @param input 期望版本与校区字段补丁
     * @return 递增版本的校区或当前仍有范围权限的原回执
     * @throws BusinessException 身份、settings.update、范围、版本、配置、资源占用或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{id}")
    public ApiResponse<Campus> update(@AuthenticationPrincipal Jwt jwt, @PathVariable @Size(max = 120) String id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody UpdateInput input) throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), id, key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
