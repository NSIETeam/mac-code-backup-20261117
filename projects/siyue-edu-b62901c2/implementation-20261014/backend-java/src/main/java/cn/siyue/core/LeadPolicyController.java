package cn.siyue.core;

import cn.siyue.core.LeadPolicyDtos.ApplyInput;
import cn.siyue.core.LeadPolicyDtos.ApplyResult;
import cn.siyue.core.LeadPolicyDtos.Policy;
import cn.siyue.core.LeadPolicyDtos.PolicyInput;
import cn.siyue.core.LeadPolicyDtos.Preview;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned lead SLA settings, dry-run candidates and explicit policy actions. */
@RestController
@RequestMapping("/api/v2/crm/lead-policy")
@Validated
public class LeadPolicyController {
    private final AccessRepository access;
    private final LeadPolicyService service;

    /**
     * 装配线索时限策略的读取、预览和显式执行入口。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 策略版本、候选锁定及幂等事务服务
     */
    public LeadPolicyController(AccessRepository access, LeadPolicyService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * 读取全局阈值；要求 crm.lead.read、leadPolicy.preview 或 leadPolicy.publish 之一。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @return 当前保留、回收、首次跟进、决策时限及策略版本
     * @throws BusinessException 身份不可用或缺少策略读取权限时
     */
    @GetMapping
    public ApiResponse<Policy> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.get(actor(jwt)));
    }

    /**
     * 由全部范围策略管理者更新阈值；省略决策时限时保留原值。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次策略修改的幂等键
     * @param input 阈值与期望策略版本
     * @return 更新策略或同请求原回执
     * @throws BusinessException 身份、策略发布权限组合、全部范围、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping
    public ApiResponse<Policy> update(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PolicyInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.update(actor(jwt), key, input));
    }

    /**
     * 在全部或校区管理范围预览到期警告与回收候选，不改变线索状态。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 当前策略、候选页及总数
     * @throws BusinessException 身份、预览权限组合、管理范围或分页不符合规则时
     */
    @GetMapping("/preview")
    public ApiResponse<Preview> preview(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.preview(actor(jwt), page, size));
    }

    /**
     * 仅执行明确选择且策略、线索版本和候选类型仍一致的项目，警告与回收在服务事务中完成。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param key 本次执行的幂等键
     * @param input 策略版本及最多 100 个不重复线索的期望版本、决策类型
     * @return 警告和已回收线索，或同请求原回执
     * @throws BusinessException 身份、分配及策略发布权限组合、范围、选择、版本、候选状态或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PostMapping("/apply")
    public ApiResponse<ApplyResult> apply(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ApplyInput input)
            throws JsonProcessingException {
        return ApiResponse.success(service.apply(actor(jwt), key, input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
