package cn.siyue.core;

import cn.siyue.core.LeadRoutingDtos.ApplyInput;
import cn.siyue.core.LeadRoutingDtos.ApplyResult;
import cn.siyue.core.LeadRoutingDtos.BatchPreview;
import cn.siyue.core.LeadRoutingDtos.QueuePage;
import cn.siyue.core.LeadRoutingDtos.RoutingPlan;
import cn.siyue.core.LeadRoutingDtos.RoutingRule;
import cn.siyue.core.LeadRoutingDtos.RuleCreate;
import cn.siyue.core.LeadRoutingDtos.RulePage;
import cn.siyue.core.LeadRoutingDtos.RuleHistoryPage;
import cn.siyue.core.LeadRoutingDtos.RuleUpdate;
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

/** Scoped lead-routing rule management, simulation, application and unmatched queue. */
@RestController
@RequestMapping("/api/v2/crm/lead-routing")
@Validated
public class LeadRoutingController {
    private final AccessRepository access;
    private final LeadRoutingService service;
    /**
     * 装配线索规则维护、模拟、执行和未匹配队列入口，不触发外部通知。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 规则范围、版本化分配和幂等审计服务
     */
    public LeadRoutingController(AccessRepository access, LeadRoutingService service) {
        this.access = access; this.service = service;
    }
    /**
     * 在 crm.lead.assign 和 all/campus 管理范围内列出路由规则。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param campusId 可选校区公开标识，用于缩小结果
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 按优先级及标识排列的规则页与总数
     * @throws BusinessException 身份、分配权限、管理范围或分页无效时
     */
    @GetMapping("/rules")
    public ApiResponse<RulePage> rules(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 120) String campusId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.rules(actor(jwt), campusId, page, size));
    }
    /**
     * 读取当前有权管理规则的不可变历史。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param ruleId 规则公开标识
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 保留原操作人、来源事件、原因和变更内容的历史页
     * @throws BusinessException 身份、管理权限、规则可见范围或分页无效时
     */
    @GetMapping("/rules/{ruleId}/history")
    public ApiResponse<RuleHistoryPage> ruleHistory(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String ruleId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.ruleHistory(actor(jwt), ruleId, page, size));
    }
    /**
     * 创建规则、历史事件和幂等回执；选定活动时另要求 crm.activity.read。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次创建的幂等键
     * @param input 校区、可选活动/学科/来源、接收人、优先级及启用状态
     * @return 新规则或同请求原回执
     * @throws BusinessException 身份、管理权限、校区、活动、接收人资格或幂等检查失败时
     * @throws JsonProcessingException 请求、历史事件或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/rules")
    public ApiResponse<RoutingRule> createRule(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RuleCreate input)
            throws JsonProcessingException {
        return ApiResponse.success(service.createRule(actor(jwt), key, input));
    }
    /**
     * 按期望版本修改规则且保持原校区；重放前仍校验管理权限和规则范围。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param ruleId 规则公开标识
     * @param key 本次修改的幂等键
     * @param input 替换规则字段与期望版本
     * @return 递增版本的规则或同请求原回执
     * @throws BusinessException 身份、权限、规则范围、版本、活动/接收人资格或幂等检查失败时
     * @throws JsonProcessingException 请求、历史事件或保存回执的 JSON 编解码失败时
     */
    @PutMapping("/rules/{ruleId}")
    public ApiResponse<RoutingRule> updateRule(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String ruleId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RuleUpdate input) throws JsonProcessingException {
        return ApiResponse.success(service.updateRule(actor(jwt), ruleId, key, input));
    }
    /**
     * 模拟可见未分配线索的规则匹配，按优先级选择首个合格接收人，不改变分配或队列。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param leadId 当前范围内尚无负责人的线索公开标识
     * @return 匹配计划及候选资格原因，或未匹配结果
     * @throws BusinessException 身份、管理权限、范围无效，线索不存在或已分配时
     */
    @GetMapping("/leads/{leadId}/simulate")
    public ApiResponse<RoutingPlan> simulate(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String leadId) {
        return ApiResponse.success(service.simulate(actor(jwt), leadId));
    }
    /**
     * 按期望版本预览选择的新公海线索，不写分配事实。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param input 不重复的线索公开标识和对应期望版本
     * @return 输入顺序的计划及可分配/未匹配数量
     * @throws BusinessException 身份、管理权限、线索范围、重复选择、公海状态或版本无效时
     */
    @PostMapping("/preview")
    public ApiResponse<BatchPreview> preview(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ApplyInput input) {
        return ApiResponse.success(service.preview(actor(jwt), input));
    }
    /**
     * 执行当前规则而非复用旧模拟；锁定选择的公海线索，分配匹配项并登记未匹配队列。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次规则分配的幂等键
     * @param input 不重复的线索公开标识及期望版本
     * @return 已分配线索与未匹配项，或同请求原回执
     * @throws BusinessException 身份、权限、线索范围、公海状态、版本、接收人资格或幂等检查失败时
     * @throws JsonProcessingException 请求、历史事件或保存回执的 JSON 编解码失败时
     */
    @PostMapping("/apply")
    public ApiResponse<ApplyResult> apply(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ApplyInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.apply(actor(jwt), key, input));
    }
    /**
     * 列出当前范围内仍未分配的待处理规则匹配异常。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 按创建时间和标识排列的待处理队列页
     * @throws BusinessException 身份、分配权限、管理范围或分页无效时
     */
    @GetMapping("/queue")
    public ApiResponse<QueuePage> queue(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.queue(actor(jwt), page, size));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
