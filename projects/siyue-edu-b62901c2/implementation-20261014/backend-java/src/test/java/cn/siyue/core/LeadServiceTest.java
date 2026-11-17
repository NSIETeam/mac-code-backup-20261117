package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LeadServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final LeadRepository repository = mock(LeadRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final LeadService service = new LeadService(access, repository, mutations,
        new ObjectMapper().findAndRegisterModules());

    @Test void legacyLeadCreateGrantIsAcceptedWithoutUnrelatedCustomerReadGrant() throws Exception {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.create"));
        when(access.lockActive(7)).thenReturn(actor);
        var input = new CrmDtos.LeadInput("CUS_1", null, null, "front_desk", "前台咨询",
            null, null, null, null, null);
        var lead = new CrmDtos.Lead("LEAD_1", "CUS_1", "CAMPUS_1", null, null, "staff-7",
            "front_desk", null, null, "前台咨询", "new", null, null, 1,
            "public", null, null, null);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(actor, "lead-permission-key-123", "crm.lead.create", "hash"))
            .thenReturn(null);
        when(repository.create(actor, input)).thenReturn(lead);
        assertEquals(lead, service.create(actor, "lead-permission-key-123", input));
        verify(repository).authorizeCreate(actor, input);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.lead.create")));
    }

    @Test void qualifyingOrClosingALeadRequiresAReason() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(),
            Set.of("crm.lead.write", "crm.customer.read"));
        when(access.lockActive(7)).thenReturn(actor);
        var input = new CrmDtos.LeadStatus("qualified", " ", Instant.now().plusSeconds(600), 1);
        assertThrows(BusinessException.class,
            () -> service.changeStatus(actor, "LEAD_1", "lead-status-key-12345", input));
        verifyNoInteractions(repository, mutations);
    }

    @Test void legacyFollowGrantCannotCallTheBroaderJavaStatusMutation() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.follow"));
        var input = new CrmDtos.LeadStatus("archived", "归档", null, 1);
        assertThrows(BusinessException.class,
            () -> service.changeStatus(actor, "LEAD_1", "legacy-status-key-123", input));
        verifyNoInteractions(access, repository, mutations);
    }

    @Test void supervisorQueueFiltersArePassedThroughWithoutExpandingVisibility() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("crm.lead.read", "crm.customer.read"));
        var query = new CrmDtos.LeadQuery("new", null, null, 1, 25,
            "CAMPUS_1", "STAFF_2", "online_form", "数学", false, true,
            Instant.parse("2026-10-14T00:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"));
        when(repository.list(actor, query, null)).thenReturn(new CrmDtos.LeadPage(List.of(), 0, 1, 25));
        service.list(actor, query);
        verify(repository).list(actor, query, null);
    }

    @Test void exactLegacyLeadReadListsScopedLeadsWithoutGrantingLeadMutations() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.read"));
        var query = new CrmDtos.LeadQuery(null, null, null, 1, 20);
        var page = new CrmDtos.LeadPage(List.of(), 0, 1, 20);
        when(repository.list(actor, query, null)).thenReturn(page);

        assertEquals(page, service.list(actor, query));
        verify(repository).list(actor, query, null);
        assertThrows(BusinessException.class,
            () -> service.changeStatus(actor, "LEAD_1", "legacy-read-only-key-123", 
                new CrmDtos.LeadStatus("archived", "reason", null, 1)));
        verifyNoMoreInteractions(repository);
    }

    @Test void ownerAndUnassignedFiltersCannotContradictEachOther() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("crm.lead.read", "crm.customer.read"));
        var query = new CrmDtos.LeadQuery(null, null, null, 1, 25,
            null, "STAFF_2", null, null, true, null, null, null);
        assertThrows(BusinessException.class, () -> service.list(actor, query));
        verifyNoInteractions(repository);
    }

    @Test void followUpTimeRangeMustBeOrdered() {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("crm.lead.read", "crm.customer.read"));
        var query = new CrmDtos.LeadQuery(null, null, null, 1, 25, null, null,
            null, null, null, null, Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-10-14T00:00:00Z"));
        assertThrows(BusinessException.class, () -> service.list(actor, query));
        verifyNoInteractions(repository);
    }

    @Test void followupRequiresCurrentScopeAndRecordsIdempotently() throws Exception {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(),
            Set.of("crm.lead.write", "crm.customer.read"));
        when(access.lockActive(7)).thenReturn(actor);
        var input = new CrmDtos.LeadFollowupInput("phone", "已联系家长并确认数学试听",
            Instant.now().plusSeconds(3600), 2);
        var lead = new CrmDtos.Lead("LEAD_1", "CUS_1", "CAMPUS_1", null, null, "staff-7",
            "front_desk", "数学", "2026秋", "前台咨询", "contacting", "已联系家长并确认数学试听",
            input.nextFollowAt(), 3, "public", null, null, null);
        when(repository.followup(actor, "LEAD_1", input)).thenReturn(lead);
        when(mutations.replay(actor, "lead-followup-key-12345", "crm.lead.followup", "hash-value"))
            .thenReturn(null);
        assertEquals(lead, service.followup(actor, "LEAD_1", "lead-followup-key-12345", input));
        verify(repository).authorizeLead(actor, "LEAD_1");
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.lead.followup")
            && write.resourceId().equals("LEAD_1")));
    }

    @Test void legacyLeadFollowGrantCanRecordFollowupAndIsRecheckedAfterPrincipalLock() throws Exception {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.follow"));
        when(access.lockActive(7)).thenReturn(actor);
        var input = new CrmDtos.LeadFollowupInput("phone", "已联系家长",
            Instant.now().plusSeconds(3600), 2);
        var lead = new CrmDtos.Lead("LEAD_1", "CUS_1", "CAMPUS_1", null, null, "staff-7",
            "front_desk", null, null, "前台咨询", "contacting", null, input.nextFollowAt(), 3,
            "public", null, null, null);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(actor, "legacy-follow-key-123", "crm.lead.followup", "hash"))
            .thenReturn(null);
        when(repository.followup(actor, "LEAD_1", input)).thenReturn(lead);
        assertEquals(lead, service.followup(actor, "LEAD_1", "legacy-follow-key-123", input));
        verify(repository).authorizeLegacyLead(actor, "LEAD_1");
        verify(repository,never()).authorizeLead(actor,"LEAD_1");
        verify(repository).followup(actor, "LEAD_1", input);
    }

    @Test void leadTransferUsesItsOwnExactGrantAndRepositoryAction() throws Exception {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.transfer"));
        when(access.lockActive(7)).thenReturn(actor);
        var input = new CrmDtos.LeadOwner("staff-8", "转交", 1);
        var lead = new CrmDtos.Lead("LEAD_1", "CUS_1", "CAMPUS_1", null, null, "staff-8",
            "front_desk", null, null, "前台咨询", "new", null, null, 2,
            "public", null, null, null);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(actor, "legacy-transfer-key-123", "crm.lead.transfer", "hash"))
            .thenReturn(null);
        when(repository.transfer(actor, "LEAD_1", input)).thenReturn(lead);
        assertEquals(lead, service.transfer(actor, "LEAD_1", "legacy-transfer-key-123", input));
        verify(repository).transfer(actor, "LEAD_1", input);
        verify(repository, never()).assign(actor, "LEAD_1", input);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.lead.transfer")));
    }

    @Test void legacyLeadAssignGrantUsesAssignableActionWithoutBecomingTransfer() throws Exception {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.assign"));
        when(access.lockActive(7)).thenReturn(actor);
        var input = new CrmDtos.LeadOwner("staff-8", "分配", 1);
        var lead = new CrmDtos.Lead("LEAD_1", "CUS_1", "CAMPUS_1", null, null, "staff-8",
            "front_desk", null, null, "前台咨询", "new", null, null, 2,
            "public", null, null, null);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(actor, "legacy-assign-key-123", "crm.lead.assign", "hash")).thenReturn(null);
        when(repository.assign(actor, "LEAD_1", input)).thenReturn(lead);
        assertEquals(lead, service.assign(actor, "LEAD_1", "legacy-assign-key-123", input));
        verify(repository).assign(actor, "LEAD_1", input);
        verify(repository, never()).transfer(actor, "LEAD_1", input);
    }

    @Test void legacyLeadReleaseGrantCanReleaseWithoutAssignGrant() throws Exception {
        Actor actor = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.release"));
        when(access.lockActive(7)).thenReturn(actor);
        var input = new CrmDtos.LeadRelease("转入公海", 1);
        var lead = new CrmDtos.Lead("LEAD_1", "CUS_1", "CAMPUS_1", null, null, null,
            "front_desk", null, null, "前台咨询", "new", null, null, 2,
            "public", null, null, null);
        when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(actor, "legacy-release-key-123", "crm.lead.release", "hash")).thenReturn(null);
        when(repository.release(actor, "LEAD_1", input)).thenReturn(lead);
        assertEquals(lead, service.release(actor, "LEAD_1", "legacy-release-key-123", input));
        verify(repository).release(actor, "LEAD_1", input);
    }

    @Test void legacyLeadActionPermissionsAreAssignableButTransferDoesNotExpandOwnScope() {
        for (String permission : List.of("lead.create", "lead.follow", "lead.assign", "lead.transfer", "lead.release")) {
            org.junit.jupiter.api.Assertions.assertTrue(AccessPermissionCatalog.isAssignable(permission));
        }
        Actor transferOnly = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.transfer"));
        assertEquals("l.owner_id = ?", ScopeSql.leads(transferOnly).sql());
        Actor assign = new Actor(7, "staff-7", "own", Set.of(), Set.of("lead.assign"));
        assertEquals(2, ScopeSql.leads(assign).args().size());
    }
}
