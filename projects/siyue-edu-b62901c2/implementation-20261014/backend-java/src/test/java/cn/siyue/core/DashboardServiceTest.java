package cn.siyue.core;

import cn.siyue.core.SalesReportDtos.Metric;
import cn.siyue.core.SalesReportDtos.Report;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {
    private final DashboardRepository repository = mock(DashboardRepository.class);
    private final SalesReportRepository salesReports = mock(SalesReportRepository.class);
    private final DashboardService service = new DashboardService(repository, salesReports);

    @Test void dashboardPermissionAloneReturnsNoResourceAggregates() {
        Actor actor = actor("dashboard.read");
        assertEquals(new DashboardDtos.Summary(null, null, null, null), service.get(actor));
        verifyNoInteractions(repository, salesReports);
    }

    @Test void eachTileRequiresItsOwnReadPermissionAndUsesScopedRepository() {
        Actor actor = actor("dashboard.read", "lead.read", "student.read", "order.read", "lead.conversion.read");
        when(repository.leads(actor)).thenReturn(12L);
        when(repository.activeStudents(actor)).thenReturn(8L);
        when(repository.outstandingOrders(actor)).thenReturn(3L);
        Metric metric = new Metric("dashboard-actor", 10, 2, new BigDecimal("0.2000"), 1,
            new BigDecimal("0.1000"), false, 10, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, 0);
        when(salesReports.report(actor, "dashboard-actor", null, null))
            .thenReturn(new Report(List.of(metric), "本人", null, null, "assignment", ""));

        DashboardDtos.Summary result = service.get(actor);

        assertEquals(12L, result.leads());
        assertEquals(8L, result.activeStudents());
        assertEquals(3L, result.outstandingOrders());
        assertEquals(2L, result.conversion().converted());
        verify(repository).leads(actor);
        verify(repository).activeStudents(actor);
        verify(repository).outstandingOrders(actor);
        verify(salesReports).report(actor, "dashboard-actor", null, null);
    }

    @Test void dashboardGateIsRequiredEvenWithResourcePermissions() {
        Actor actor = actor("lead.read", "student.read", "order.read");
        assertThrows(BusinessException.class, () -> service.get(actor));
        verifyNoInteractions(repository, salesReports);
    }

    private Actor actor(String... permissions) {
        return new Actor(7, "dashboard-actor", "own", Set.of(), Set.of(permissions));
    }
}
