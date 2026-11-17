package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.MarkRead;
import cn.siyue.core.OpportunityNeedDtos.Notification;
import cn.siyue.core.OpportunityNeedDtos.NotificationPage;
import cn.siyue.core.OpportunityNeedDtos.NotificationQuery;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes recipient-only reminders without any permission to send messages or read old consultation text. */
@RestController
@RequestMapping("/api/v2/crm/opportunity-notifications")
@Validated
public class OpportunityNotificationController {
    private final AccessRepository access;
    private final OpportunityNotificationService service;
    /**
     * 装配仅面向当前接收人的商机提醒读取和已读确认入口。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 提醒接收人、商机读取权限及幂等确认服务
     */
    public OpportunityNotificationController(AccessRepository access, OpportunityNotificationService service) {
        this.access = access; this.service = service;
    }

    /**
     * 读取本人商机提醒，要求 crm.opportunity.read 或原 lead.read/create/follow 之一。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param query unread、read 或 all 状态及分页；未指定状态时为 unread
     * @return 当前接收人的提醒分页，关联信息按当前可见性投影
     * @throws BusinessException 身份、读取权限、提醒状态或分页不符合规则时
     */
    @GetMapping
    public ApiResponse<NotificationPage> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute NotificationQuery query) {
        return ApiResponse.success(service.list(actor(jwt), query));
    }

    /**
     * 确认本人提醒已读；重放前重新检查接收人及权限，回执按当前状态去除不可见信息。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param id 提醒公开标识
     * @param key 本次已读确认的幂等键
     * @param input 提醒期望版本
     * @return 已读提醒或经当前可见性投影的原回执
     * @throws BusinessException 身份、商机读取权限、接收人、标识、版本或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或已保存回执的 JSON 编解码失败时
     */
    @PutMapping("/{id}/read")
    public ApiResponse<Notification> markRead(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @NotBlank @Size(max = 120) String id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody MarkRead input)
            throws JsonProcessingException {
        return ApiResponse.success(service.markRead(actor(jwt), id, key, input));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
