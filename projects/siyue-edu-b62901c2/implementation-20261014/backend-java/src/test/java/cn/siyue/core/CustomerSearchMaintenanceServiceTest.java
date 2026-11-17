package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerSearchMaintenanceServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final StudentNameIndexRepository names=mock(StudentNameIndexRepository.class);
    private final StudentIdentifierRepository numbers=mock(StudentIdentifierRepository.class);
    private final MutationRepository mutations=mock(MutationRepository.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final CustomerSearchMaintenanceService service=new CustomerSearchMaintenanceService(access,names,numbers,mutations,mapper);
    private final Actor manager=new Actor(1,"maintenance","all",Set.of(),Set.of("crm.customer.search.manage"));
    @Test void noImplicitSystemAdministratorGrantAndNoCampusWideMaintenance() {
        var system=new Actor(2,"system","all",Set.of(),Set.of("system.access.manage"));
        var campus=new Actor(3,"campus","campus",Set.of(1L),manager.permissions());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->service.nameStatus(system)).code());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->service.numberStatus(campus)).code());
        verifyNoInteractions(names,numbers,mutations,access);
    }
    @Test void revocationIsCheckedBeforeLedgerAndAnyRebuild() {
        when(access.lockActive(manager.id())).thenReturn(new Actor(1,"maintenance","all",Set.of(),Set.of()));
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,
            ()->service.rebuildNames(manager,"maintenance-key-123",new Rebuild(0,100))).code());
        verifyNoInteractions(names,numbers,mutations);
    }
    @Test void rebuildingNamesIsAuditedAndOriginalKeyDoesNotRunBatchAgain() throws Exception {
        when(access.lockActive(1)).thenReturn(manager); when(mutations.hash(anyString())).thenReturn("hash");
        var input=new Rebuild(0,100); var receipt=new NameIndexRebuild(100,115,true,new NameIndexStatus(200,100,100,2,1));
        when(names.rebuild(input)).thenReturn(receipt);
        assertEquals(receipt,service.rebuildNames(manager,"maintenance-name-123",input));
        verify(mutations).complete(eq(manager),argThat(write->write.action().equals("crm.customer.search.names.rebuild")&&write.resourceId().equals("student-name-index")));
        when(mutations.replay(manager,"maintenance-name-123","crm.customer.search.names.rebuild","hash")).thenReturn(mapper.writeValueAsString(receipt));
        assertEquals(receipt,service.rebuildNames(manager,"maintenance-name-123",input)); verify(names,times(1)).rebuild(input);
    }
    @Test void numberMaintenanceHasIndependentCursorAndAction() throws Exception {
        when(access.lockActive(1)).thenReturn(manager); when(mutations.hash(anyString())).thenReturn("hash");
        var receipt=new StudentIdentifierDtos.RebuildResult(20,30,false,new StudentIdentifierDtos.IndexStatus(20,20,0,0));
        when(numbers.rebuildIndexes(10,20)).thenReturn(receipt);
        assertEquals(receipt,service.rebuildNumbers(manager,"maintenance-number-123",new Rebuild(10,20)));
        verify(mutations).complete(eq(manager),argThat(write->write.action().equals("crm.customer.search.numbers.rebuild")));
        verifyNoInteractions(names);
    }
    @Test void invalidCursorLimitOrKeyFailsBeforeAccountLock() {
        for(var input:java.util.List.of(new Rebuild(-1,100),new Rebuild(0,201),new Rebuild(0,0)))
            assertEquals(ErrorCode.INVALID_INPUT,assertThrows(BusinessException.class,()->service.rebuildNames(manager,"maintenance-name-123",input)).code());
        assertThrows(BusinessException.class,()->service.rebuildNumbers(manager,"short",new Rebuild(0,20)));
        verifyNoInteractions(access,names,numbers,mutations);
    }
}
