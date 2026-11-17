package cn.siyue.core;

import cn.siyue.core.CrmDtos.Opportunity;
import cn.siyue.core.CrmDtos.OpportunityInput;
import cn.siyue.core.CrmDtos.OpportunityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpportunityServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final OpportunityRepository repository = mock(OpportunityRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final OpportunityService service = new OpportunityService(access, repository, mutations,
        new ObjectMapper().findAndRegisterModules());
    private final Actor actor = new Actor(5, "staff-5", "own", Set.of(),
        Set.of("crm.customer.read", "crm.opportunity.read", "crm.opportunity.write"));
    @Test void createIsScopedIdempotentAndAudited() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("opportunity-body-hash");
        var input = new OpportunityInput("CUS_1", null, null, null, "秋季数学", "数学", "2026秋", "家长明确需求");
        var result = new Opportunity("OPP_1", "CUS_1", null, null, "staff-5", null, null,
            "秋季数学", "数学", "2026秋", "qualified", "家长明确需求", null, 1);
        when(repository.create(actor, input)).thenReturn(result);
        assertEquals(result, service.create(actor, "opportunity-create-key-123", input));
        verify(repository).authorizeInput(actor, input);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.opportunity.create")
            && write.resourceId().equals("OPP_1")));
        when(mutations.replay(actor, "opportunity-create-key-123", "crm.opportunity.create", "opportunity-body-hash"))
            .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(result));
        assertEquals(result, service.create(actor, "opportunity-create-key-123", input));
        verify(repository, times(1)).create(actor, input);
    }
    @Test void manualWonAndMissingPermissionsAreRejected() {
        var invalid = new CrmDtos.OpportunityStage("won", null, null, 1);
        assertThrows(BusinessException.class, () -> service.advance(actor, "OPP_1",
            "opportunity-stage-key-123", invalid));
        Actor noWrite = new Actor(8, "staff-8", "own", Set.of(), Set.of("crm.customer.read"));
        assertThrows(BusinessException.class, () -> service.create(noWrite, "opportunity-create-key-123",
            new OpportunityInput("CUS_1", null, null, null, "测试", "数学", "2026秋", null)));
        var sourcedInput = new OpportunityInput("CUS_1", "LEAD_1", null, null,
            "测试", "数学", "2026秋", null);
        assertThrows(BusinessException.class, () -> service.create(actor,
            "opportunity-source-key-12345", sourcedInput));
        verifyNoInteractions(repository, mutations);
    }
    @Test void eventsRequireReadPermissionAndUseAuthorizedRepository() {
        var events = java.util.List.of(new OpportunityEvent("EVT_1", "staff-5", "stage",
            "qualified", "trial", null, "约试听", Instant.EPOCH));
        when(repository.events(actor, "OPP_1")).thenReturn(events);
        assertEquals(events, service.events(actor, "OPP_1"));
        Actor noRead = new Actor(8, "staff-8", "own", Set.of(), Set.of("crm.opportunity.write"));
        assertThrows(BusinessException.class, () -> service.events(noRead, "OPP_1"));
        verify(repository, never()).events(noRead, "OPP_1");
    }
    @Test void inheritedSourceLinksDoNotRequireUnrelatedAcademicPermissions() throws Exception {
        Actor seller = new Actor(5, "staff-5", "own", Set.of(),
            Set.of("crm.customer.read", "crm.lead.read", "crm.opportunity.write"));
        when(access.lockActive(5)).thenReturn(seller);
        var input = new OpportunityInput("CUS_1", "LEAD_1", null, null,
            "明确需求", "数学", "2026秋", "电话初筛确认");
        var result = new Opportunity("OPP_1", "CUS_1", "LEAD_1", null, "staff-5", null, null,
            input.title(), input.subject(), input.term(), "qualified", input.detail(), null, 1);
        when(repository.create(seller, input)).thenReturn(result);
        assertEquals(result, service.create(seller, "source-inheritance-key-123", input));
        verify(repository).authorizeInput(seller, input);
        var explicitStudent = new OpportunityInput("CUS_1", "LEAD_1", null, "STUDENT_OTHER",
            "明确需求", "数学", "2026秋", null);
        assertThrows(BusinessException.class,
            () -> service.create(seller, "explicit-student-key-123", explicitStudent));
        verify(repository, never()).authorizeInput(seller, explicitStudent);
        var explicitActivity = new OpportunityInput("CUS_1", "LEAD_1", "ACTIVITY_OTHER", null,
            "明确需求", "数学", "2026秋", null);
        assertThrows(BusinessException.class,
            () -> service.create(seller, "explicit-activity-key-123", explicitActivity));
        verify(repository, never()).authorizeInput(seller, explicitActivity);
    }

    @Test void confirmedFollowupReplaySurvivesAnExpiredAppointmentWithoutAnotherWrite() throws Exception {
        var input = new CrmDtos.OpportunityFollowup("phone", "已沟通", Instant.EPOCH, 1);
        var result = replayResult("crm.opportunity.followup");
        assertEquals(result, service.followup(actor, "OPP_1", "opportunity-retry-key-123", input));
        var order = inOrder(access, repository, mutations);
        order.verify(access).lockActive(actor.id());
        order.verify(repository).authorize(actor, "OPP_1");
        order.verify(mutations).hash(anyString());
        order.verify(mutations).replay(actor, "opportunity-retry-key-123",
            "crm.opportunity.followup", "original-body-hash");
        verify(repository, never()).followup(any(), anyString(), any());
        verify(mutations, never()).complete(any(), any());
    }

    @Test void confirmedStageReplaySurvivesAnExpiredAppointmentWithoutAnotherWrite() throws Exception {
        var input = new CrmDtos.OpportunityStage("appointment", "已约定", Instant.EPOCH, 1);
        var result = replayResult("crm.opportunity.stage");
        assertEquals(result, service.advance(actor, "OPP_1", "opportunity-retry-key-123", input));
        verify(repository).authorize(actor, "OPP_1");
        verify(repository, never()).advance(any(), anyString(), any());
        verify(mutations, never()).complete(any(), any());
    }

    @Test void newWritesStillRejectExpiredAppointmentsAfterFindingNoReplay() {
        when(access.lockActive(actor.id())).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("original-body-hash");
        assertThrows(BusinessException.class, () -> service.followup(actor, "OPP_1",
            "opportunity-retry-key-123", new CrmDtos.OpportunityFollowup("phone", "沟通", Instant.EPOCH, 1)));
        assertThrows(BusinessException.class, () -> service.advance(actor, "OPP_1",
            "opportunity-retry-key-123", new CrmDtos.OpportunityStage("appointment", "约定", Instant.EPOCH, 1)));
        verify(repository, never()).followup(any(), anyString(), any());
        verify(repository, never()).advance(any(), anyString(), any());
        verify(mutations, never()).complete(any(), any());
    }

    @Test void replayStillRequiresCurrentWritePermissionAndResourceScope() {
        Actor revoked = new Actor(actor.id(), actor.publicId(), "own", Set.of(), Set.of());
        var followup = new CrmDtos.OpportunityFollowup("phone", "沟通", Instant.EPOCH, 1);
        var stage = new CrmDtos.OpportunityStage("appointment", "约定", Instant.EPOCH, 1);
        when(access.lockActive(actor.id())).thenReturn(revoked);
        assertThrows(BusinessException.class, () -> service.followup(actor, "OPP_1", "opportunity-retry-key-123", followup));
        assertThrows(BusinessException.class, () -> service.advance(actor, "OPP_1", "opportunity-retry-key-123", stage));
        verifyNoInteractions(repository, mutations);
        when(access.lockActive(actor.id())).thenReturn(actor);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "商机已不在当前范围"))
            .when(repository).authorize(actor, "OPP_1");
        assertThrows(BusinessException.class, () -> service.followup(actor, "OPP_1", "opportunity-retry-key-123", followup));
        assertThrows(BusinessException.class, () -> service.advance(actor, "OPP_1", "opportunity-retry-key-123", stage));
        verifyNoInteractions(mutations);
    }

    private Opportunity replayResult(String action) throws Exception {
        when(access.lockActive(actor.id())).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("original-body-hash");
        var result = new Opportunity("OPP_1", "CUS_1", null, null, actor.publicId(), null, null,
            "秋季数学", "数学", "2026秋", "appointment", "已沟通", Instant.EPOCH, 2);
        when(mutations.replay(actor, "opportunity-retry-key-123", action, "original-body-hash"))
            .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(result));
        return result;
    }
}
