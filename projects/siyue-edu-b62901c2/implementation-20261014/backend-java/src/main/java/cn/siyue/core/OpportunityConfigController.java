package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.OptionCreate;
import cn.siyue.core.OpportunityNeedDtos.OptionPage;
import cn.siyue.core.OpportunityNeedDtos.OptionQuery;
import cn.siyue.core.OpportunityNeedDtos.OptionReceipt;
import cn.siyue.core.OpportunityNeedDtos.OptionUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative configuration is independent of account-management privileges. */
@RestController
@RequestMapping("/api/v2/crm/opportunity-config")
@Validated
public class OpportunityConfigController {
    private final AccessRepository access;
    private final OpportunityConfigService service;
    /**
     * 装配商机需求选项的配置入口，不以账号管理权限替代需求配置权限。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 需求选项范围、版本与幂等事务服务
     */
    public OpportunityConfigController(AccessRepository access,OpportunityConfigService service) { this.access=access; this.service=service; }
    /**
     * 按 crm.opportunity.config.manage 和全部/校区范围读取选项目录。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param query 选项类型、校区、启用状态及分页
     * @return 选项分页和目录版本
     * @throws BusinessException 身份、配置权限、数据范围或查询条件无效时
     */
    @GetMapping
    public ApiResponse<OptionPage> list(@AuthenticationPrincipal Jwt jwt,@Valid @ModelAttribute OptionQuery query) {
        return ApiResponse.success(service.list(actor(jwt),query));
    }
    /**
     * 在当前配置范围内创建选项，并原子保存目录变化和幂等回执。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次选项创建的幂等键
     * @param input 选项类型、名称、父项及可选校区等配置
     * @return 新选项和目录版本的回执，或同请求原回执
     * @throws BusinessException 身份、配置权限、校区范围、选项约束或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping
    public ApiResponse<OptionReceipt> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OptionCreate input) throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt),key,input));
    }
    /**
     * 按期望版本修改选项；接受幂等重放前重新校验当前权限与该选项范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 选项公开标识
     * @param key 本次选项更新的幂等键
     * @param input 替换字段及期望版本
     * @return 更新选项和目录版本的回执，或同请求原回执
     * @throws BusinessException 身份、权限、范围、目标、字段、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{id}")
    public ApiResponse<OptionReceipt> update(@AuthenticationPrincipal Jwt jwt,@PathVariable @Size(max=120) String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OptionUpdate input) throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt),id,key,input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(),jwt.getSubject()); }
}
