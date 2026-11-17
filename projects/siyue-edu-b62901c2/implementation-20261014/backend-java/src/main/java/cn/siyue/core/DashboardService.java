package cn.siyue.core;

import cn.siyue.core.DashboardDtos.Conversion;
import cn.siyue.core.DashboardDtos.Summary;
import cn.siyue.core.SalesReportDtos.Metric;
import cn.siyue.core.SalesReportDtos.Report;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Returns only aggregate dashboard tiles whose exact source permission is currently granted. */
@Service
public class DashboardService {
    private final DashboardRepository repository;
    private final SalesReportRepository salesReports;

    /**
     * Connects the dashboard to scoped aggregate queries without loading business record lists.
     * @param repository lead, active student and outstanding order aggregate queries
     * @param salesReports caller-specific conversion metrics, including the minimum sample threshold
     */
    public DashboardService(DashboardRepository repository, SalesReportRepository salesReports) {
        this.repository = repository;
        this.salesReports = salesReports;
    }

    /**
     * Builds the home summary; dashboard access alone grants no underlying lead, student or order data.
     * @param actor active caller with dashboard.read and any per-resource grants for optional tiles
     * @return only aggregate tiles for lead.read, student.read, order.read and lead.conversion.read
     * @throws BusinessException if the caller is inactive or lacks dashboard.read
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Summary get(Actor actor) {
        actor.require("dashboard.read");
        Long leads = actor.permissions().contains("lead.read") ? repository.leads(actor) : null;
        Long students = actor.permissions().contains("student.read") ? repository.activeStudents(actor) : null;
        Long orders = actor.permissions().contains("order.read") ? repository.outstandingOrders(actor) : null;
        Conversion conversion = conversion(actor);
        return new Summary(leads, students, orders, conversion);
    }

    private Conversion conversion(Actor actor) {
        if (!actor.permissions().contains("lead.conversion.read")) { return null; }
        Report report = salesReports.report(actor, actor.publicId(), null, null);
        List<Metric> metrics = report.items();
        if (metrics.isEmpty()) { return new Conversion(0, 0, null, true, 10); }
        Metric metric = metrics.get(0);
        return new Conversion(metric.assignedUnique(), metric.convertedUnique(), metric.conversionRate(),
            metric.lowSample(), metric.minimumSample());
    }
}
