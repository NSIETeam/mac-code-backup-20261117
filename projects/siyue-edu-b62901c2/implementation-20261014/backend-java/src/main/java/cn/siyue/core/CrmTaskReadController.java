package cn.siyue.core;

import cn.siyue.core.CrmTaskReadDtos.AssigneePage;
import cn.siyue.core.CrmTaskReadDtos.Context;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scoped reads needed by the activity-to-task workflow. */
@RestController
@RequestMapping("/api/v2/crm/tasks")
@Validated
public class CrmTaskReadController {
    private final AccessRepository access;
    private final CrmTaskReadService service;
    /**
     * 装配任务所需的员工候选、上下文和关联商机读取入口。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 任务和商机独立权限、数据范围及接收人资格查询服务
     */
    public CrmTaskReadController(AccessRepository access, CrmTaskReadService service) {
        this.access = access; this.service = service;
    }
    /**
     * 按任务类型和校区读取合格接收人，要求 crm.task.write 或原 lead.assign。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param query marketing/service 类型、可选校区、账号筛选及分页
     * @return 当前分配范围内的接收人候选页
     * @throws BusinessException 身份、分配查询权限、任务类型、筛选或分页无效时
     */
    @GetMapping("/assignees")
    public ApiResponse<AssigneePage> assignees(@AuthenticationPrincipal Jwt jwt,
            @ModelAttribute CrmTaskReadDtos.AssigneeQuery query) {
        return ApiResponse.success(service.assignees(actor(jwt), query));
    }
    /**
     * 读取任务上下文，要求 Java 任务读取权限或原 lead.read/follow/assign，并校验任务可见性。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param taskId 任务公开标识
     * @return 按当前关联资源范围投影的上下文
     * @throws BusinessException 身份、任务读取权限、标识或任务可见范围无效时
     */
    @GetMapping("/{taskId}/context")
    public ApiResponse<Context> context(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @NotBlank @Size(max = 120) String taskId) {
        return ApiResponse.success(service.context(actor(jwt), taskId));
    }
    /**
     * 在任务与商机两类读取权限分别通过后列出任务关联商机。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param taskId 当前可见任务公开标识
     * @param page 从 1 开始的页码，最大 100000
     * @param size 每页 1 至 100 条
     * @return 当前范围内的关联商机分页
     * @throws BusinessException 身份、任务/商机读取权限、任务范围或分页无效时
     */
    @GetMapping("/{taskId}/opportunities")
    public ApiResponse<CrmTaskReadDtos.OpportunityPage> opportunities(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @NotBlank @Size(max = 120) String taskId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.opportunities(actor(jwt), taskId, page, size));
    }
    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }
}
