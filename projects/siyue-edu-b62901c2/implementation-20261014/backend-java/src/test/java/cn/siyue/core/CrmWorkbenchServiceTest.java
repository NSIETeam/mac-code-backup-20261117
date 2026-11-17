package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.siyue.core.CrmDtos.CrmWorkbenchQueuePage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CrmWorkbenchServiceTest {
    private final CrmWorkbenchQueueRepository queues = mock(CrmWorkbenchQueueRepository.class);
    private final CrmWorkbenchService service = new CrmWorkbenchService(queues);

    @Test void emitsAllSeventeenQueuesWithStableQueryAndAsOf() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("crm.lead.read", "crm.customer.read"));
        when(queues.page(eq(actor), any())).thenAnswer(call -> {
            var query = call.<CrmDtos.CrmWorkbenchQueueQuery>getArgument(1);
            return new CrmWorkbenchQueuePage(List.of(), 2, query.page(), query.size(), query.asOf());
        });
        var result = service.get(actor, "mine", LocalDate.parse("2026-10-14"));
        assertEquals(7, result.cards().size());
        assertTrue(result.cards().stream().allMatch(card -> "leads".equals(card.resource())));
        assertEquals("firstContact", result.cards().get(0).queue());
        assertEquals("mine", result.cards().get(0).query().get("scope"));
        assertEquals(result.asOf().toString(), result.cards().get(0).query().get("asOf"));
        assertEquals(2, result.cards().get(0).count());
        verify(queues, times(7)).page(eq(actor), argThat(query -> "mine".equals(query.scope())
            && result.asOf().equals(query.asOf())));
    }

    @Test void permissionFilteringProducesFullSeventeenAndTeamViewMapsToTeam() {
        Actor actor = new Actor(7, "manager-7", "campus", Set.of(2L), Set.of("lead.read", "lead.assign",
            "report.read", "promotion.publish", "order.read", "crm.form.read", "crm.form.submission.read",
            "crm.task.read", "crm.opportunity.read", "crm.ticket.read", "crm.lead.reclaim.read"));
        when(queues.page(any(), any())).thenAnswer(call -> {
            var query = call.<CrmDtos.CrmWorkbenchQueueQuery>getArgument(1);
            return new CrmWorkbenchQueuePage(List.of(), 0, query.page(), query.size(), query.asOf());
        });
        var result = service.get(actor, "scope", LocalDate.parse("2026-10-14"));
        assertEquals(17, result.cards().size());
        assertTrue(result.cards().stream().allMatch(card -> "team".equals(card.query().get("scope"))));
        assertEquals("trialBookings", result.cards().get(7).resource());
        assertEquals("serviceTickets", result.cards().get(16).resource());
    }

    @Test void rejectsUnauthorizedScopeResourceAndStaleSnapshot() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("crm.lead.read"));
        assertThrows(BusinessException.class, () -> service.get(actor, "scope", LocalDate.now()));
        assertThrows(BusinessException.class, () -> service.queue(actor, query("team", "leads", "today", Instant.now())));
        assertThrows(BusinessException.class, () -> service.queue(actor, query("mine", "trialBookings", "today", Instant.now())));
        assertThrows(BusinessException.class, () -> service.queue(actor, query("mine", "leads", "today", Instant.now().minusSeconds(90000))));
        verifyNoInteractions(queues);
    }

    private CrmDtos.CrmWorkbenchQueueQuery query(String scope, String resource, String queue, Instant asOf) {
        return new CrmDtos.CrmWorkbenchQueueQuery(scope, resource, queue, asOf, 1, 50);
    }
}
