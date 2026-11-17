package cn.siyue.core;

import cn.siyue.core.SalesReportDtos.ActivityPage;
import cn.siyue.core.SalesReportDtos.ActivityQuery;
import cn.siyue.core.SalesReportDtos.ActivityRequest;
import cn.siyue.core.SalesReportDtos.Followup;
import cn.siyue.core.SalesReportDtos.Page;
import cn.siyue.core.SalesReportDtos.Report;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only sales reporting with the original assignment and first-receipt cohorts. */
@RestController
@RequestMapping("/api/v2/crm")
public class SalesReportController {
    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private final AccessRepository access;
    private final SalesReportRepository repository;
    private final PiiCipher cipher;

    /**
     * 装配销售转化、销售事件和跟进记录的只读 HTTP 入口。
     * @param access 可信 issuer/subject 的本地身份解析器
     * @param repository 按销售指标和线索范围过滤的查询仓库
     * @param cipher 解密已授权跟进记录摘要的组件
     */
    public SalesReportController(AccessRepository access, SalesReportRepository repository, PiiCipher cipher) {
        this.access = access;
        this.repository = repository;
        this.cipher = cipher;
    }

    /**
     * 按分配时间及首笔有效收款归属读取销售报表；日期按 UTC+8 的首尾日完整边界转换。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param employeeId 可选员工公开标识；非报表管理查看者仅能指定本人
     * @param from 可选开始日期，包含该日零时
     * @param to 可选结束日期，包含该日最后一毫秒
     * @return 范围内销售人员的唯一线索、转化及关联订单金额指标
     * @throws BusinessException 身份、report.read/lead.conversion.read 权限、员工范围或日期区间无效时
     */
    @GetMapping("/reports/sales")
    public ApiResponse<Report> report(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        validateRange(from, to);
        return ApiResponse.success(repository.report(actor(jwt), employeeId, beginning(from), end(to)));
    }

    /**
     * 读取销售活动游标分页；活动读取权限与跨员工查看资格分别校验。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param request 可选员工、动作、线索、UTC+8 日期区间、游标和每页数量，默认 100
     * @return 当前线索读取范围内的销售活动分页
     * @throws BusinessException 身份、销售活动读取或员工查看权限、日期、游标或 1 至 200 条分页限制不满足时
     */
    @GetMapping("/sales/activity")
    public ApiResponse<ActivityPage> activity(@AuthenticationPrincipal Jwt jwt, @ModelAttribute ActivityRequest request) {
        validateRange(request.from(), request.to());
        int limit = request.limit() == null ? 100 : request.limit();
        validateLimit(limit);
        return ApiResponse.success(repository.activity(actor(jwt),
            new ActivityQuery(request.employeeId(), request.action(), request.leadId(), beginning(request.from()),
                end(request.to()), limit, request.cursor())));
    }

    /**
     * 读取范围内的销售跟进摘要；仅在授权查询返回后解密摘要。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析本地身份
     * @param employeeId 可选员工公开标识，无跨员工查看资格时仅能指定本人
     * @param limit 每页 1 至 200 条
     * @param cursor 可选上一页末条跟进公开标识
     * @return 按发生时间倒序的跟进记录、是否有更多及后续游标
     * @throws BusinessException 身份、销售活动读取或员工查看权限、范围、游标或分页无效时
     */
    @GetMapping("/sales/followups")
    public ApiResponse<Page<Followup>> followups(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String employeeId, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        validateLimit(limit);
        return ApiResponse.success(repository.followups(actor(jwt), employeeId, limit, cursor, cipher));
    }

    private Actor actor(Jwt jwt) { return access.resolve(jwt.getIssuer().toString(), jwt.getSubject()); }

    private Instant beginning(LocalDate date) { return date == null ? null : date.atStartOfDay().toInstant(BUSINESS_ZONE); }

    private Instant end(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay().toInstant(BUSINESS_ZONE).minusMillis(1);
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "开始日期不能晚于结束日期");
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 200) { throw new BusinessException(ErrorCode.INVALID_INPUT, "分页数量超出范围"); }
    }
}
