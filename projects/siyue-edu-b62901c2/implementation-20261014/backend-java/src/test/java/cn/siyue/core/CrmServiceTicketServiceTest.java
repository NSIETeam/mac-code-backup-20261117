package cn.siyue.core;

import cn.siyue.core.CrmDtos.ServiceTicket;
import cn.siyue.core.CrmDtos.ServiceTicketInput;
import cn.siyue.core.CrmDtos.ServiceTicketPage;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.RegistryState;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.ReplaceInput;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.TypeValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrmServiceTicketServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final CrmServiceTicketRepository tickets = mock(CrmServiceTicketRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final CrmServiceTicketService service = new CrmServiceTicketService(access, tickets, mutations,
        new ObjectMapper().findAndRegisterModules());
    private final Actor actor = new Actor(5, "staff-5", "all", Set.of(), Set.of(
        "crm.customer.read", "crm.ticket.read", "crm.ticket.write", "crm.ticket.assign",
        "crm.order.read", "crm.refund.read"));

    @Test void createRechecksScopeAndStoresAnIdempotentTicket() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("ticket-hash");
        var input = new ServiceTicketInput("CUS_1", "complaint", "课程咨询", "家长来电咨询",
            null, null, null, null);
        var ticket = new ServiceTicket("TICKET_1", "CUS_1", "CAMPUS_1", null, "staff-5", "staff-5",
            null, null, null, null, null, null, "课程咨询", "家长来电咨询", "open", null, null, null, null, null, 1);
        when(tickets.create(actor, input)).thenReturn(ticket);
        assertEquals(ticket, service.create(actor, "ticket-create-key-123456", input));
        verify(tickets).authorizeCreate(actor, input);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.ticket.create")
            && write.resourceId().equals("TICKET_1")));
        when(mutations.replay(actor, "ticket-create-key-123456", "crm.ticket.create", "ticket-hash"))
            .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(ticket));
        assertEquals(ticket, service.create(actor, "ticket-create-key-123456", input));
        verify(tickets, times(1)).create(actor, input);
    }

    @Test void refundLinkRechecksBothScopesAndIsIdempotent() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("refund-link-hash");
        var input = new cn.siyue.core.CrmDtos.ServiceTicketRefundLink("RFDREQ_1", 1);
        var ticket = new ServiceTicket("TICKET_1", "CUS_1", "CAMPUS_1", null, "staff-5", "staff-5",
            null, null, "RFDREQ_1", "pending_approval", null, null, "退款跟进", "家长申请退款", "processing",
            null, null, null, null, null, 2);
        when(tickets.linkRefund(actor, "TICKET_1", input)).thenReturn(ticket);
        assertEquals(ticket, service.linkRefund(actor, "TICKET_1", "refund-link-key-123456", input));
        verify(tickets).authorizeRefundLink(actor, "TICKET_1", input);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.ticket.refund.link")
            && write.resourceId().equals("TICKET_1")));
        when(mutations.replay(actor, "refund-link-key-123456", "crm.ticket.refund.link", "refund-link-hash"))
            .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(ticket));
        assertEquals(ticket, service.linkRefund(actor, "TICKET_1", "refund-link-key-123456", input));
        verify(tickets, times(1)).linkRefund(actor, "TICKET_1", input);
    }

    @Test void assigningAnotherEmployeeRequiresExplicitAssignmentPermission() {
        Actor noAssign = new Actor(8, "staff-8", "own", Set.of(),
            Set.of("crm.customer.read", "crm.ticket.write"));
        var input = new ServiceTicketInput("CUS_1", "complaint", "课程咨询", "详情", "staff-9",
            null, null, null);
        assertThrows(BusinessException.class, () -> service.create(noAssign, "ticket-create-key-234567", input));
        verifyNoInteractions(access, tickets, mutations);
    }

    @Test void queueAndEventsHaveIndependentReadPermissionAndBoundedPage() {
        when(tickets.list(actor, "open", 1, 10, null)).thenReturn(new ServiceTicketPage(List.of(), 0, 1, 10));
        assertEquals(0, service.list(actor, "open", 1, 10).total());
        assertThrows(BusinessException.class, () -> service.list(actor, "unknown", 1, 10));
        Actor noRead = new Actor(9, "staff-9", "own", Set.of(), Set.of("crm.ticket.write"));
        assertThrows(BusinessException.class, () -> service.events(noRead, "TICKET_1"));
        verify(tickets, never()).events(noRead, "TICKET_1");
    }

    @Test void inactiveTypeCatalogueRequiresItsDedicatedManagementPermission() {
        assertThrows(BusinessException.class, () -> service.types(actor, "CAMPUS_1", true));
        Actor manager = new Actor(5, "staff-5", "all", Set.of(), Set.of(
            "crm.ticket.read", "crm.ticket.type.manage"));
        var types = List.of(new cn.siyue.core.CrmDtos.ServiceTicketType("TYPE_1", null,
            "complaint", "投诉处理", false, 2));
        when(tickets.types(manager, null, true)).thenReturn(types);
        assertEquals(types, service.types(manager, null, true));
        verify(tickets).types(manager, null, true);
    }

    @Test void globalTypeReplacementRequiresAllScopeAndUsesOptimisticIdempotentWrite() throws Exception {
        Actor manager = new Actor(5, "staff-5", "all", Set.of(), Set.of("lead.assign"));
        ReplaceInput input = new ReplaceInput(List.of(new TypeValue("question", "咨询", true)), 4);
        RegistryState result = new RegistryState(input.types(), 5);
        when(access.lockActive(5)).thenReturn(manager);
        when(tickets.lockTypeRegistry()).thenReturn(4L);
        when(mutations.hash(anyString())).thenReturn("types-hash");
        when(tickets.replaceTypeRegistry(input, 4)).thenReturn(result);
        assertEquals(result, service.replaceTypeRegistry(manager, "ticket-types-idempotency-001", input));
        verify(mutations).complete(eq(manager), argThat(write -> write.action().equals("serviceTicket.types.update")
            && write.resourceId().equals("service-ticket-types:global")));

        Actor campus = new Actor(6, "staff-6", "campus", Set.of(), Set.of("lead.assign"));
        assertThrows(BusinessException.class, () -> service.replaceTypeRegistry(campus,
            "ticket-types-campus-scope-001", input));
        verify(tickets, times(1)).replaceTypeRegistry(input, 4);
    }
}
