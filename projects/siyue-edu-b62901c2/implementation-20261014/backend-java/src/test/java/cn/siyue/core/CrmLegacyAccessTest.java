package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CrmLegacyAccessTest {
    private Actor actor(String... grants) { return new Actor(7,"legacy-staff","campus",Set.of(2L),Set.of(grants)); }

    @Test void originalResourceAlternativesAreExactAndDoNotMutateGrants() {
        Map<String,Set<String>> matrix=Map.of(
            "crm.customer.read",Set.of("lead.read","lead.create","lead.follow"),
            "crm.activity.read",Set.of("lead.read","lead.create","lead.assign"),
            "crm.lead.read",Set.of("lead.read","lead.create","lead.follow","lead.assign"),
            "crm.touchpoint.read",Set.of("lead.read","lead.create","lead.follow"),
            "crm.opportunity.read",Set.of("lead.read","lead.create","lead.follow"),
            "crm.task.read",Set.of("lead.read","lead.follow","lead.assign"),
            "crm.ticket.read",Set.of("lead.read","lead.follow","lead.assign"),
            "crm.form.submission.read",Set.of("lead.create","promotion.publish","store.design"),
            "crm.form.read",Set.of("lead.create","promotion.create","promotion.publish","store.design"));
        for(var entry:matrix.entrySet()) {
            for(String permission:Set.of("lead.read","lead.create","lead.follow","lead.assign",
                    "promotion.create","promotion.publish","store.design","report.read","system.access.manage")) {
                Actor caller=actor(permission);
                assertEquals(entry.getValue().contains(permission),CrmLegacyAccess.canRead(caller,entry.getKey()),
                    entry.getKey()+":"+permission);
                if(entry.getValue().contains(permission))assertDoesNotThrow(()->CrmLegacyAccess.read(caller,entry.getKey()));
                else assertThrows(BusinessException.class,()->CrmLegacyAccess.read(caller,entry.getKey()));
                assertEquals(Set.of(permission),caller.permissions());
            }
            assertDoesNotThrow(()->CrmLegacyAccess.read(actor(entry.getKey()),entry.getKey()));
        }
        assertFalse(CrmLegacyAccess.canRead(actor("lead.read"),"crm.order.read"));
    }

    @Test void optionalCampaignLinkVersionPreservesThePreviousOneFieldDigestShape() throws Exception {
        var mapper=new ObjectMapper();
        assertEquals("{\"formId\":\"form-1\"}",mapper.writeValueAsString(new CrmDtos.ActivityFormLinkInput("form-1")));
        assertEquals("{\"formId\":\"form-1\",\"expectedVersion\":2}",mapper.writeValueAsString(new CrmDtos.ActivityFormLinkInput("form-1",2L)));
    }

    @Test void operationAcceptsExactLegacyGrantOrTheEntireModernCombination() {
        assertDoesNotThrow(()->CrmLegacyAccess.operation(actor("lead.create"),"lead.create",
            "crm.customer.write","crm.customer.read"));
        assertDoesNotThrow(()->CrmLegacyAccess.operation(actor("crm.customer.write","crm.customer.read"),
            "lead.create","crm.customer.write","crm.customer.read"));
        for(String permission:Set.of("lead.read","lead.follow","lead.assign","crm.customer.write","crm.customer.read"))
            assertThrows(BusinessException.class,()->CrmLegacyAccess.operation(actor(permission),"lead.create",
                "crm.customer.write","crm.customer.read"));
    }

    @Test void legacyCustomerCreateRechecksLockedPrincipalBeforeAnyScopedWrite() throws Exception {
        var access=mock(AccessRepository.class);var repository=mock(CrmRepository.class);
        var mutations=mock(MutationRepository.class);var service=new CrmService(access,repository,mutations,new ObjectMapper());
        Actor creator=actor("lead.create");var input=new CrmDtos.CustomerInput("campus-2","合成家庭",List.of());
        when(access.lockActive(7)).thenReturn(actor("lead.read"));
        assertThrows(BusinessException.class,()->service.createCustomer(creator,"legacy-create-key-001",input));
        verifyNoInteractions(repository,mutations);
        when(access.lockActive(7)).thenReturn(creator);when(mutations.hash(anyString())).thenReturn("hash");
        var result=new CrmDtos.Customer("C1","campus-2","legacy-staff","合成家庭",1,List.of(),List.of());
        when(repository.createCustomer(creator,input)).thenReturn(result);
        assertEquals(result,service.createCustomer(creator,"legacy-create-key-001",input));
        verify(repository).authorizeCampus(creator,"campus-2");
        verify(mutations).complete(eq(creator),argThat(x->x.action().equals("crm.customer.create")));
        assertThrows(BusinessException.class,()->service.createCustomer(actor("lead.follow"),"legacy-create-key-002",input));
    }

    @Test void legacyFormStopRetainsReplayAndRechecksRevocation() throws Exception {
        var access=mock(AccessRepository.class);var forms=mock(CrmFormRepository.class);
        var mutations=mock(MutationRepository.class);var mapper=new ObjectMapper();
        var service=new CrmFormService(access,forms,mutations,mapper);Actor creator=actor("lead.create");
        var version=new CrmDtos.MarketingFormVersion(2);
        var closed=new CrmDtos.MarketingForm("F1","campus-2","legacy-staff","问卷",null,
            "survey",false,"closed",1,List.of(),Map.of(),3);
        when(access.lockActive(7)).thenReturn(creator);when(mutations.hash(anyString())).thenReturn("hash");
        when(forms.unpublish(creator,"F1",2)).thenReturn(closed);
        assertEquals(closed,service.unpublish(creator,"F1","legacy-form-stop-001",version));
        when(mutations.replay(creator,"legacy-form-stop-001","crm.form.unpublish","hash"))
            .thenReturn(mapper.writeValueAsString(closed));
        assertEquals(closed,service.unpublish(creator,"F1","legacy-form-stop-001",version));
        verify(forms,times(1)).unpublish(creator,"F1",2);
        when(access.lockActive(7)).thenReturn(actor("promotion.publish"));
        assertThrows(BusinessException.class,()->service.unpublish(creator,"F1","legacy-form-stop-001",version));
    }

    @Test void legacyWorkbenchUsesOriginalTeamGateWithoutInventingGrants() {
        var repository=mock(CrmWorkbenchQueueRepository.class);var service=new CrmWorkbenchService(repository);
        Actor reader=actor("lead.read");LocalDate date=LocalDate.of(2026,10,17);
        when(repository.page(any(),any())).thenAnswer(call->{
            var query=call.<CrmDtos.CrmWorkbenchQueueQuery>getArgument(1);
            return new CrmDtos.CrmWorkbenchQueuePage(List.of(),0,query.page(),query.size(),query.asOf());
        });
        assertEquals(15,service.get(reader,"mine",date).cards().size());
        assertThrows(BusinessException.class,()->service.get(reader,"scope",date));
        Actor manager=actor("lead.assign");
        assertEquals(11,service.get(manager,"scope",date).cards().size());
        Actor personal=new Actor(7,"legacy-staff","own",Set.of(),Set.of("lead.read","lead.assign"));
        assertThrows(BusinessException.class,()->service.get(personal,"scope",date));
        assertEquals(Set.of("lead.read"),reader.permissions());
    }
}
