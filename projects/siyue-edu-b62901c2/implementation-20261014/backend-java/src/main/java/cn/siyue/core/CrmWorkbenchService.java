package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmWorkbench;
import cn.siyue.core.CrmDtos.CrmWorkbenchCard;
import cn.siyue.core.CrmDtos.CrmWorkbenchQueuePage;
import cn.siyue.core.CrmDtos.CrmWorkbenchQueueQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds scoped workbench counts and serves matching bounded resource queues. */
@Service
public class CrmWorkbenchService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<Definition> DEFINITIONS = List.of(
        new Definition("leads:firstContact", "待首联", "leads", "firstContact"),
        new Definition("leads:today", "今天回访", "leads", "today"),
        new Definition("leads:overdue", "逾期跟进", "leads", "overdue"),
        new Definition("leads:future", "未来回访", "leads", "future"),
        new Definition("leads:unassigned", "待分配", "leads", "unassigned"),
        new Definition("leads:decision", "待处置", "leads", "decision"),
        new Definition("leads:recycleSoon", "即将回收", "leads", "recycleSoon"),
        new Definition("trialBookings:today", "今天试听", "trialBookings", "today"),
        new Definition("trialBookings:future", "未来试听", "trialBookings", "future"),
        new Definition("formSubmissions:pending", "表单待审核", "formSubmissions", "pending"),
        new Definition("leadReclaimRequests:pending", "取回待审批", "leadReclaimRequests", "pending"),
        new Definition("attributionRequests:pending", "归因待审核", "attributionRequests", "pending"),
        new Definition("crmTasks:today", "今天任务", "crmTasks", "today"),
        new Definition("opportunities:today", "商机今天回访", "opportunities", "today"),
        new Definition("opportunities:overdue", "商机逾期回访", "opportunities", "overdue"),
        new Definition("opportunities:future", "商机未来回访", "opportunities", "future"),
        new Definition("serviceTickets:overdue", "服务工单逾期", "serviceTickets", "overdue"));
    /** 固定工作台队列与显示标签的对应关系；资源和队列组合亦用作列表请求白名单。 */
    private record Definition(String key, String label, String resource, String queue) { }
    private final CrmWorkbenchQueueRepository queues;

    /** Injects the shared resource-specific count/list repository for the workbench queues.
     * @param queues scoped list/count executor for the 17 workbench queues
     */
    public CrmWorkbenchService(CrmWorkbenchQueueRepository queues) {
        this.queues = queues;
    }

    /** Returns only cards allowed by the caller's current resource grants.
     * @param actor authenticated actor whose current database scope is applied
     * @param view mine for personal rows or scope for an authorized team view
     * @param requestedDate optional Asia/Shanghai business date
     * @return scoped cards and the exact instant used by each card's queue predicate
     * @throws BusinessException when the requested scope is invalid or unauthorized, or no queue is readable
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CrmWorkbench get(Actor actor, String view, LocalDate requestedDate) {
        requireView(actor, view);
        Instant asOf = Instant.now();
        LocalDate date = requestedDate == null ? asOf.atZone(BUSINESS_ZONE).toLocalDate() : requestedDate;
        Instant from = date.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant until = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        String scope = "scope".equals(view) ? "team" : "mine";
        List<CrmWorkbenchCard> cards = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            if (!canRead(actor, definition.resource())) { continue; }
            CrmWorkbenchQueueQuery query = new CrmWorkbenchQueueQuery(scope, definition.resource(),
                definition.queue(), asOf, 1, 1);
            CrmDtos.CrmWorkbenchQueuePage page = queues.page(actor, query);
            cards.add(card(definition, page.total(), scope, asOf));
        }
        if (cards.isEmpty()) { throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号没有可访问的工作台队列"); }
        return new CrmWorkbench(view, date, asOf, from, until, List.copyOf(cards), asOf);
    }

    /** Returns a bounded page using the same resource/queue predicate as the corresponding workbench count.
     * @param actor authenticated actor whose resource grant and row scope are checked; team queries additionally
     *     require all/campus scope and one of lead.assign, report.read, crm.lead.assign or crm.report.read
     * @param query mine/team scope, whitelisted resource/queue pair, optional asOf, page 1–100000 and size 1–100;
     *     omitted asOf uses now, otherwise it must be within the last 24 hours and no more than 60 seconds ahead
     * @return queue rows with safe identifiers, display labels and status/timing fields, plus the scoped total
     * @throws BusinessException for invalid filters, stale/future time, missing grants or disallowed team scope
     * @throws org.springframework.dao.DataAccessException if the scoped count or page query fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CrmWorkbenchQueuePage queue(Actor actor, CrmWorkbenchQueueQuery query) {
        requireScope(actor, query.scope());
        requireDefinition(query.resource(), query.queue());
        if (!canRead(actor, query.resource())) { throw new BusinessException(ErrorCode.FORBIDDEN, "没有此队列的读取权限"); }
        if (query.page() < 1 || query.page() > 100000 || query.size() < 1 || query.size() > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "工作台分页范围无效");
        }
        Instant fixed = query.asOf() == null ? Instant.now() : query.asOf();
        if (fixed.isBefore(Instant.now().minusSeconds(86400)) || fixed.isAfter(Instant.now().plusSeconds(60))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "工作台时间快照已过期，请刷新");
        }
        return queues.page(actor, new CrmWorkbenchQueueQuery(query.scope(), query.resource(), query.queue(), fixed,
            query.page(), query.size()));
    }

    private CrmWorkbenchCard card(Definition definition, long count, String scope, Instant asOf) {
        Map<String, String> query = Map.of("resource", definition.resource(), "scope", scope,
            "queue", definition.queue(), "asOf", asOf.toString());
        Map<String, String> legacy = definition.resource().equals("leads")
            ? Map.of("activeOnly", "true", "queue", definition.queue()) : Map.of();
        return new CrmWorkbenchCard(definition.key(), definition.label(), count, legacy,
            definition.resource(), definition.queue(), query);
    }

    private void requireView(Actor actor, String view) {
        if (!"mine".equals(view) && !"scope".equals(view)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "工作台范围须为 mine 或 scope");
        }
        requireScope(actor, "scope".equals(view) ? "team" : "mine");
    }

    private void requireScope(Actor actor, String scope) {
        if (!Set.of("mine", "team").contains(scope)) { throw new BusinessException(ErrorCode.INVALID_INPUT, "工作台范围无效"); }
        if ("team".equals(scope) && (!("all".equals(actor.scope()) || "campus".equals(actor.scope()))
                || !has(actor, "lead.assign", "report.read", "crm.lead.assign", "crm.report.read"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有团队范围查看权限");
        }
    }

    private boolean canRead(Actor actor, String resource) {
        return switch (resource) {
            case "leads" -> CrmLegacyAccess.canRead(actor, "crm.lead.read");
            case "trialBookings" -> has(actor, "lead.read", "lead.follow", "lead.create")
                || actor.permissions().containsAll(Set.of("crm.form.read", "crm.form.submission.read"));
            case "formSubmissions" -> CrmLegacyAccess.canRead(actor, "crm.form.read")
                && CrmLegacyAccess.canRead(actor, "crm.form.submission.read");
            case "leadReclaimRequests" -> has(actor, "lead.follow", "lead.assign", "crm.lead.reclaim.read");
            case "attributionRequests" -> has(actor, "lead.read", "lead.follow", "lead.assign", "order.read",
                "crm.order.read", "crm.attribution.correct.request", "crm.attribution.correct.review");
            case "crmTasks" -> CrmLegacyAccess.canRead(actor, "crm.task.read");
            case "opportunities" -> CrmLegacyAccess.canRead(actor, "crm.opportunity.read");
            case "serviceTickets" -> CrmLegacyAccess.canRead(actor, "crm.ticket.read");
            default -> false;
        };
    }

    private void requireDefinition(String resource, String queue) {
        if (DEFINITIONS.stream().noneMatch(item -> item.resource().equals(resource) && item.queue().equals(queue))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "工作台资源或队列无效");
        }
    }

    private boolean has(Actor actor, String... permissions) {
        for (String permission : permissions) { if (actor.permissions().contains(permission)) { return true; } }
        return false;
    }
}
