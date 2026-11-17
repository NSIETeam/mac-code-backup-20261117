package cn.siyue.core;

import cn.siyue.core.ActivityAudienceDtos.Page;
import java.time.Instant;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scoped activity people-selection endpoint; one row is one source evidence record. */
@RestController
@Validated
public class ActivityAudienceController {
    private final AccessRepository access;
    private final ActivityAudienceService service;
    /**
     * 装配活动来源证据的分页查询入口。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 活动及客户范围内的来源证据查询服务
     */
    public ActivityAudienceController(AccessRepository access, ActivityAudienceService service) {
        this.access = access; this.service = service;
    }
    /**
     * 查询可用于任务创建的活动获客证据，每行表示一条来源记录。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析当前本地身份
     * @param activityId 活动公开标识
     * @param query 客户、来源、时间、任务状态筛选及分页
     * @return 符合当前活动与客户可见范围的来源证据分页
     * @throws BusinessException 身份、创建任务所需权限、来源或任务筛选权限、范围或查询参数无效时
     */
    @GetMapping("/api/v2/crm/activities/{activityId}/audience")
    public ApiResponse<Page> list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 120) String activityId,
            @org.springframework.web.bind.annotation.ModelAttribute ActivityAudienceDtos.Query query) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.list(actor, activityId, query));
    }
}
