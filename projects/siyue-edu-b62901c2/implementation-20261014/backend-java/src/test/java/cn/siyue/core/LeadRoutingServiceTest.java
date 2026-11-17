package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeadRoutingServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final LeadRoutingRepository routing = mock(LeadRoutingRepository.class);
    private final LeadRepository leads = mock(LeadRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final LeadRoutingService service = new LeadRoutingService(access,routing,leads,mutations,mapper);
    private final Actor actor = new Actor(1,"manager","all",Set.of(),Set.of("crm.lead.assign","crm.activity.read"));
    @BeforeEach void prepare() {
        when(access.lockActive(1)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("hash-value");
    }
    @Test void appliesMatchingRuleWithTheLeadVersionAndCommitsOneIdempotentBatch() throws Exception {
        var input = new LeadRoutingDtos.ApplyInput(List.of(new LeadRoutingDtos.LeadVersion("LEAD_1",2)));
        var row = new LeadRoutingRepository.RouteLead(9,"LEAD_1",10L,"CAMPUS_1","ACT_1","数学",
            "online_form","new",null,2);
        when(routing.lead(actor,"LEAD_1",false)).thenReturn(row);
        when(routing.lead(actor,"LEAD_1",true)).thenReturn(row);
        when(routing.simulate(row)).thenReturn(new LeadRoutingDtos.RoutingPlan("LEAD_1","RULE_1",
            "STAFF_2","命中优先级 1 的规则 数学线索",List.of()));
        var assigned = new CrmDtos.Lead("LEAD_1","CUS_1","CAMPUS_1","ACT_1",null,"STAFF_2",
            "online_form","数学",null,"课程咨询","new",null,null,3,"public",null,null,null);
        when(leads.assign(actor,"LEAD_1",new CrmDtos.LeadOwner("STAFF_2","命中优先级 1 的规则 数学线索",2)))
            .thenReturn(assigned);
        assertEquals(new LeadRoutingDtos.ApplyResult(List.of(assigned),List.of()),
            service.apply(actor,"routing-batch-key-12345",input));
        verify(routing).completed(actor,row,"RULE_1","命中优先级 1 的规则 数学线索");
        verify(mutations).complete(eq(actor),argThat(write -> write.action().equals("crm.routing.apply")
            && write.resourceId().equals("lead-routing-batch")));
        when(mutations.replay(actor,"routing-batch-key-12345","crm.routing.apply","hash-value"))
            .thenReturn(mapper.writeValueAsString(new LeadRoutingDtos.ApplyResult(List.of(assigned),List.of())));
        assertEquals(new LeadRoutingDtos.ApplyResult(List.of(assigned),List.of()),
            service.apply(actor,"routing-batch-key-12345",input));
        verify(leads,times(1)).assign(any(),any(),any());
    }
    @Test void rejectsDuplicateLeadSelectionBeforeAnyDatabaseWrite() {
        var row = new LeadRoutingDtos.LeadVersion("LEAD_1",1);
        var input = new LeadRoutingDtos.ApplyInput(List.of(row,row));
        assertThrows(BusinessException.class,()->service.apply(actor,"routing-batch-key-12345",input));
        verifyNoInteractions(access,routing,leads,mutations);
    }
    @Test void previewsBatchMatchesWithoutAssigningOrWritingAnIdempotencyRecord() {
        var row = new LeadRoutingRepository.RouteLead(9,"LEAD_1",10L,"CAMPUS_1","ACT_1","数学",
            "online_form","new",null,2);
        var input = new LeadRoutingDtos.ApplyInput(List.of(new LeadRoutingDtos.LeadVersion("LEAD_1",2)));
        var plan = new LeadRoutingDtos.RoutingPlan("LEAD_1","RULE_1","STAFF_2","命中规则",List.of());
        when(routing.lead(actor,"LEAD_1",false)).thenReturn(row);
        when(routing.simulate(row)).thenReturn(plan);
        assertEquals(new LeadRoutingDtos.BatchPreview(List.of(plan),1,0), service.preview(actor,input));
        verifyNoInteractions(leads,mutations,access);
    }
    @Test void refusesAutomaticRoutingForTeacherOnlyScope() {
        Actor teacher = new Actor(1,"teacher","assigned",Set.of(),Set.of("crm.lead.assign"));
        assertThrows(BusinessException.class,()->service.queue(teacher,1,50));
        verifyNoInteractions(routing);
    }
}
