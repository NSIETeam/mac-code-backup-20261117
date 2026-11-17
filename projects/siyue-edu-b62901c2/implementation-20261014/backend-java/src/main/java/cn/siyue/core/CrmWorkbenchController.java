package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmWorkbench;
import cn.siyue.core.CrmDtos.CrmWorkbenchQueuePage;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves CRM dashboard counts from current database facts and caller scope. */
@RestController
@RequestMapping("/api/v2/crm/workbench")
public class CrmWorkbenchController {
    private final AccessRepository access;
    private final CrmWorkbenchService service;
    /**
     * 装配统一工作台卡片和对应队列列表的读取入口。
     * @param access 可信 issuer/subject 绑定解析器
     * @param service 按各资源权限和范围执行共用计数/列表条件的服务
     */
    public CrmWorkbenchController(AccessRepository access, CrmWorkbenchService service) {
        this.access = access;
        this.service = service;
    }

    /**
     * 读取个人或团队工作台，仅返回当前资源权限允许的队列卡片。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param view mine 为个人；scope 为需额外管理权限及 all/campus 范围的团队视图
     * @param date 可选 Asia/Shanghai 日期，用于响应日期和日边界说明；队列仍按服务生成的 asOf 计算
     * @return 卡片及共同计数时点，供点击列表复用
     * @throws BusinessException 身份、视图、团队范围无效或没有可访问队列时
     */
    @GetMapping
    public ApiResponse<CrmWorkbench> get(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "mine") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.get(actor, view, date));
    }

    /**
     * 使用卡片同源的资源/队列条件返回列表，重新核验权限及 asOf 的有效期。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param query mine/team 范围、资源/队列白名单组合、可选时点及有界分页
     * @return 范围内的记录标识、显示标签、状态和时间字段及总数
     * @throws BusinessException 身份、权限、团队范围、队列、分页或请求时点无效时
     * @throws org.springframework.dao.DataAccessException 队列计数或分页 SQL 执行失败时
     */
    @GetMapping("/queues")
    public ApiResponse<CrmWorkbenchQueuePage> queue(@AuthenticationPrincipal Jwt jwt,
            @Valid @org.springframework.web.bind.annotation.ModelAttribute CrmDtos.CrmWorkbenchQueueQuery query) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        return ApiResponse.success(service.queue(actor, query));
    }
}
