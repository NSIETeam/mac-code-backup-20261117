package cn.siyue.core;

import cn.siyue.core.LearningMetricsDtos.Summary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed report endpoint; aggregation and visibility are enforced in the server repository. */
@RestController
@RequestMapping("/api/v2/academic/learning-metrics")
public class LearningMetricsController {
    private final AccessRepository access;
    private final LearningMetricsRepository metrics;

    /**
     * 装配学习报表的身份解析和关系账本聚合入口。
     * @param access 可信 issuer/subject 的本地身份解析器
     * @param metrics 按学员、有效报名及有效课消范围分别聚合的仓库
     */
    public LearningMetricsController(AccessRepository access, LearningMetricsRepository metrics) {
        this.access = access;
        this.metrics = metrics;
    }

    /**
     * 在 report.read 授权后汇总当前范围的学员、报名与课消；历史学员排除仍有有效在读报名者。
     * @param jwt 经资源服务器验证的 JWT；以 issuer 和 subject 解析当前本地身份
     * @return 范围说明及学员总数、在读/历史人数、有效报名数、净课消为正的考勤记录数
     * @throws BusinessException 本地身份不可用、缺少 report.read 或数据范围类型无效时
     */
    @GetMapping
    public ApiResponse<Summary> summary(@AuthenticationPrincipal Jwt jwt) {
        Actor actor = access.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        actor.require("report.read");
        return ApiResponse.success(metrics.summary(actor));
    }
}
