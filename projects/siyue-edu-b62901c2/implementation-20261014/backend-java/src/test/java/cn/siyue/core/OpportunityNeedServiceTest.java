package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpportunityNeedServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final OpportunityNeedRepository repository=mock(OpportunityNeedRepository.class);
    private final OpportunityNeedReadRepository reads=mock(OpportunityNeedReadRepository.class);
    private final MutationRepository mutations=mock(MutationRepository.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final OpportunityNeedService service=new OpportunityNeedService(access,repository,reads,mutations,mapper);
    private final Actor actor=new Actor(1,"P1","own",Set.of(),Set.of("crm.customer.read","crm.opportunity.intake","crm.opportunity.read","crm.opportunity.write"));
    private static final String KEY="need-confirm-original-key";
    @Test void commitAuditsOnlyAfterLockedActorAndCurrentIntakeAuthorization() throws Exception {
        Commit input=commit(); Receipt expected=receipt(); setup(); when(repository.commit(actor,input)).thenReturn(expected);
        assertEquals(expected,service.commit(actor,KEY,input));
        var order=inOrder(access,repository,mutations);
        order.verify(access).lockActive(1); order.verify(repository).authorizeReplay(actor,input);
        order.verify(mutations).hash(anyString()); order.verify(mutations).replay(actor,KEY,"crm.opportunity.need.commit","hash");
        order.verify(repository).commit(actor,input); order.verify(mutations).complete(eq(actor),argThat(write->write.resourceId().equals("O1")));
    }
    @Test void replayDoesNotRecalculatePreviewOrReinsertConsultationAndIsRedacted() throws Exception {
        Commit input=commit(); setup(); Receipt expected=receipt();
        when(mutations.replay(actor,KEY,"crm.opportunity.need.commit","hash")).thenReturn(mapper.writeValueAsString(expected));
        when(repository.redact(actor,expected)).thenReturn(expected);
        assertEquals(expected,service.commit(actor,KEY,input));
        verify(repository).authorizeReplay(actor,input); verify(repository).redact(actor,expected);
        verify(repository,never()).commit(any(),any()); verify(mutations,never()).complete(any(),any());
    }
    @Test void currentIntakeAuthorizationPrecedesSuccessfulLedgerLookup() {
        setup(); Commit input=commit(); doThrow(new BusinessException(ErrorCode.NOT_FOUND,"scope")).when(repository).authorizeReplay(actor,input);
        assertThrows(BusinessException.class,()->service.commit(actor,KEY,input)); verifyNoInteractions(mutations);
    }
    @Test void profileReplayRetainsFactVersionButAppliesCurrentLinkProjection() throws Exception {
        setup(); Detail saved=detail(); Detail redacted=new Detail(null,"classified",saved.identity(),null,null);
        when(mutations.replay(actor,KEY,"crm.opportunity.need.profile","hash")).thenReturn(mapper.writeValueAsString(saved));
        when(reads.redact(actor,saved)).thenReturn(redacted);
        assertEquals(redacted,service.profile(actor,"O1",KEY,new ProfileUpdate(new Profile(null,null,null,null,null,null),1,1)));
        verify(reads).authorized(actor,"O1",false); verify(repository,never()).profile(any(),any(),any());
    }
    @Test void judgmentReplayRechecksCurrentWriteGrant() {
        setup(); Actor revoked=new Actor(1,"P1","own",Set.of(),Set.of("crm.opportunity.read"));
        when(access.lockActive(1)).thenReturn(revoked);
        assertThrows(BusinessException.class,()->service.judge(actor,"O1",KEY,new JudgmentInput("J1",null,"真实判断",1,1)));
        verifyNoInteractions(mutations,repository,reads);
    }
    @Test void legacyTaskInputSerializationAndVersionOneKeyRemainUnchanged() throws Exception {
        var old=new CrmDtos.TaskTargetInput("C1","S1","数学","秋",List.of());
        assertEquals("{\"customerId\":\"C1\",\"studentId\":\"S1\",\"subject\":\"数学\",\"term\":\"秋\"}",mapper.writeValueAsString(old));
        var first=new CrmDtos.TaskTargetInput("C1","S1","数学","秋",List.of(),new Identity("D1","P1"));
        var second=new CrmDtos.TaskTargetInput("C1","S1","数学","秋",List.of(),new Identity("D1","P2"));
        assertNotEquals(CrmTaskRepository.targetKey(old),CrmTaskRepository.targetKey(first));
        assertNotEquals(CrmTaskRepository.targetKey(first),CrmTaskRepository.targetKey(second));
        assertFalse(mapper.writeValueAsString(new CrmDtos.TaskCompletion("qualified",null,"O1",1)).contains("needResolution"));
    }
    @Test void identityKeyKeepsDepartmentProjectChildAndTermIndependent() {
        var base=new OpportunityNeedSupport.IdentityKey(1L,2L,3L,"数学","2026秋");
        assertArrayEquals(OpportunityNeedSupport.demandKey(base),OpportunityNeedSupport.demandKey(new OpportunityNeedSupport.IdentityKey(1L,2L,3L,"数学","2026秋")));
        assertFalse(java.util.Arrays.equals(OpportunityNeedSupport.demandKey(base),OpportunityNeedSupport.demandKey(new OpportunityNeedSupport.IdentityKey(1L,2L,4L,"数学","2026秋"))));
        assertFalse(java.util.Arrays.equals(OpportunityNeedSupport.demandKey(base),OpportunityNeedSupport.demandKey(new OpportunityNeedSupport.IdentityKey(1L,4L,3L,"数学","2026秋"))));
    }
    private void setup() { when(access.lockActive(1)).thenReturn(actor); when(mutations.hash(anyString())).thenReturn("hash"); }
    private Commit commit() {
        Input input=new Input("C1",null,null,null,"需求","数学","秋",new Identity("D1",null),null,"本次咨询",null);
        Preview preview=new Preview(1,"hash",1,null,null,null,List.of(),List.of("create"),"P1",List.of());
        return new Commit(input,preview,new Decision("create",null,null));
    }
    private Receipt receipt() { return new Receipt("created","O1","N1",false,"not_required",null,"classified"); }
    private Detail detail() { return new Detail(null,"classified",new IdentityView("D1","部门",null,null,"not_used"),null,null); }
}
