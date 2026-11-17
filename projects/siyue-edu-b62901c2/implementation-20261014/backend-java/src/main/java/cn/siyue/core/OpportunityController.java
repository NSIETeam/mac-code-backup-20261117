package cn.siyue.core;

import cn.siyue.core.CrmDtos.Opportunity;
import cn.siyue.core.CrmDtos.OpportunityEvent;
import cn.siyue.core.CrmDtos.OpportunityFollowup;
import cn.siyue.core.CrmDtos.OpportunityInput;
import cn.siyue.core.CrmDtos.OpportunityPage;
import cn.siyue.core.CrmDtos.OpportunityStage;
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
import org.springframework.web.bind.annotation.RestController;

/** Versioned endpoints for the independent CRM opportunity lifecycle. */
@RestController
@RequestMapping("/api/v2/crm/opportunities")
@Validated
public class OpportunityController {
    private final AccessRepository access;
    private final OpportunityService service;
    /**
     * 连接独立商机生命周期接口，员工身份仅由已验证 OIDC 绑定解析。
     * @param access 员工绑定解析器
     * @param service 商机读取、跟进、阶段转换和幂等审计服务
     */
    public OpportunityController(AccessRepository access, OpportunityService service) {
        this.access = access; this.service = service;
    }
    /**
     * 在当前商机读取范围内按普通条件或已保存筛选分页。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param filters 可选客户和阶段，以及有界分页
     * @param savedView 可选已保存筛选；存在时验证所有权、资源及附加条件
     * @return 当前范围的商机页；客户过滤另核验客户读取许可
     * @throws BusinessException 读取权限、客户筛选、阶段、分页或保存筛选授权无效时
     */
    @GetMapping
    public ApiResponse<OpportunityPage> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute OpportunityListFilters filters,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute LegacySavedViewQuery savedView) {
        Actor actor = actor(jwt);
        if (savedView == null || !savedView.present()) {
            return ApiResponse.success(service.list(actor, filters.customerId(), filters.stage(),
                filters.page(), filters.size()));
        }
        return ApiResponse.success(service.list(actor, filters, savedView));
    }
    /**
     * 读取当前可见商机的跟进和阶段事件。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param opportunityId 商机公开标识
     * @return 该商机最近的事件列表
     * @throws BusinessException 读取权限或商机范围校验失败时
     */
    @GetMapping("/{opportunityId}/events")
    public ApiResponse<java.util.List<OpportunityEvent>> events(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String opportunityId) {
        return ApiResponse.success(service.events(actor(jwt), opportunityId));
    }
    /**
     * 旧创建入口按客户、孩子及意向匹配新增商机或追加线索，受当前需求配置约束。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 客户、可选来源和学生、学科、学期、标题及说明
     * @return 新增或匹配的商机，或同请求原回执
     * @throws BusinessException lead.follow 或现代组合权限、关联记录范围、字段、创建功能或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping
    public ApiResponse<Opportunity> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OpportunityInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.create(actor(jwt), key, input));
    }
    /**
     * 在写范围内追加跟进，已成功重放保留原约定的下次跟进时间。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param opportunityId 商机公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 跟进内容、可选未来跟进时间和期望版本
     * @return 跟进后的商机或同请求原回执
     * @throws BusinessException 跟进权限、商机范围、内容、时间、版本或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/{opportunityId}/followups")
    public ApiResponse<Opportunity> followup(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String opportunityId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OpportunityFollowup input)
            throws JsonProcessingException {
        return ApiResponse.success(service.followup(actor(jwt), opportunityId, key, input));
    }
    /**
     * 手动切换允许的商机阶段；won 必须由实际收款流程产生。
     * @param jwt 已验证的 JWT；以 issuer 和 subject 解析当前数据库员工身份
     * @param opportunityId 商机公开标识
     * @param key 本次操作的幂等键；不确定结果必须以相同键和原请求重试
     * @param input 目标阶段、说明、可选未来跟进时间和期望版本
     * @return 阶段更新后的商机或同请求原回执
     * @throws BusinessException 写权限、范围、手工阶段、时间、版本或幂等校验失败时
     * @throws JsonProcessingException 请求摘要、可信清单或保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{opportunityId}/stage")
    public ApiResponse<Opportunity> advance(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String opportunityId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OpportunityStage input)
            throws JsonProcessingException {
        return ApiResponse.success(service.advance(actor(jwt), opportunityId, key, input));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
