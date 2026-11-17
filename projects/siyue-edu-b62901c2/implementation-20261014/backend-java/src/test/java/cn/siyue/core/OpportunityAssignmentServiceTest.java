package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpportunityAssignmentServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final OpportunityAssignmentRepository repository=mock(OpportunityAssignmentRepository.class);
    private final OpportunityNeedReadRepository reads=mock(OpportunityNeedReadRepository.class);
    private final MutationRepository mutations=mock(MutationRepository.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final OpportunityAssignmentService service=new OpportunityAssignmentService(access,repository,reads,mutations,mapper);
    private final Actor actor=new Actor(1,"P1","own",Set.of(),Set.of("crm.opportunity.assign","crm.opportunity.write"));
    @Test void ownHandoffLostResponseReplaysMinimumReceiptAfterLeavingScope() throws Exception {
        AssignmentReceipt saved=new AssignmentReceipt("O1","P2",2,"left_scope"); replay(actor,saved);
        assertEquals(saved,service.assign(actor,"O1","assignment-original-key",new Assignment("P2","交接",1)));
        verify(reads,never()).authorized(any(),any(),anyBoolean()); verify(repository,never()).assign(any(),any(),any());
        verify(mutations,never()).complete(any(),any());
    }
    @Test void campusRevocationDoesNotUseOwnHandoffException() throws Exception {
        Actor campus=new Actor(1,"P1","campus",Set.of(),actor.permissions());
        replay(campus,new AssignmentReceipt("O1","P2",2,"left_scope"));
        doThrow(new BusinessException(ErrorCode.NOT_FOUND,"outside")).when(reads).authorized(campus,"O1",false);
        assertThrows(BusinessException.class,()->service.assign(campus,"O1","assignment-original-key",new Assignment("P2","交接",1)));
        verify(repository,never()).assign(any(),any(),any());
    }
    @Test void newWriteStillRequiresCurrentScopeAndPermission() {
        when(access.lockActive(1)).thenReturn(actor);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND,"outside")).when(reads).authorized(actor,"O1",false);
        assertThrows(BusinessException.class,()->service.assign(actor,"O1","assignment-original-key",new Assignment("P2","交接",1)));
        verify(repository,never()).assign(any(),any(),any());
        Actor revoked=new Actor(1,"P1","own",Set.of(),Set.of("crm.opportunity.write")); when(access.lockActive(1)).thenReturn(revoked);
        assertThrows(BusinessException.class,()->service.assign(actor,"O1","assignment-original-key",new Assignment("P2","交接",1)));
    }
    private void replay(Actor principal,AssignmentReceipt receipt) throws Exception {
        when(access.lockActive(1)).thenReturn(principal); when(mutations.hash(anyString())).thenReturn("hash");
        when(mutations.replay(principal,"assignment-original-key","crm.opportunity.assign","hash")).thenReturn(mapper.writeValueAsString(receipt));
    }
}
