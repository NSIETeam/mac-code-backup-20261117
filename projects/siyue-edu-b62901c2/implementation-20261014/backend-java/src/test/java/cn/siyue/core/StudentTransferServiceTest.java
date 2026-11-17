package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentTransferServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final StudentTransferRepository repository=mock(StudentTransferRepository.class);
    private final MutationRepository mutations=mock(MutationRepository.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final StudentTransferService service=new StudentTransferService(access,repository,mutations,mapper);
    private final Actor actor=new Actor(1,"teacher","all",Set.of(),Set.of("enrollment.manage"));
    private final StudentTransferDtos.Input input=new StudentTransferDtos.Input(List.of(new StudentTransferDtos.Item("enrollment",1)),"target","调整时间");
    @Test void rejectsPermissionBeforeAccessAndRechecksAfterLock() {
        var reader=new Actor(1,"teacher","all",Set.of(),Set.of());
        assertThrows(BusinessException.class,()->service.transfer(reader,"transfer-key-123456",input));
        verifyNoInteractions(access,repository,mutations);
        when(access.lockActive(1)).thenReturn(reader);
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-key-123456",input));
        verifyNoInteractions(repository,mutations);
    }
    @Test void enrollmentCreateDoesNotAuthorizeTransferAndExactTransferGrantIsRechecked() {
        var enrollmentCreator=new Actor(1,"teacher","all",Set.of(),Set.of("enrollment.create"));
        assertThrows(BusinessException.class,()->service.transfer(enrollmentCreator,"transfer-key-123456",input));
        verifyNoInteractions(access,repository,mutations);
        var transferActor=new Actor(1,"teacher","all",Set.of(),Set.of("enrollment.transfer"));
        when(access.lockActive(1)).thenReturn(new Actor(1,"teacher","all",Set.of(),Set.of()));
        assertThrows(BusinessException.class,()->service.transfer(transferActor,"transfer-key-123456",input));
        verifyNoInteractions(repository,mutations);
    }
    @Test void exactTransferGrantAuthorizesOnlyAfterActivePrincipalRecheck() throws Exception {
        var transferActor=new Actor(1,"teacher","all",Set.of(),Set.of("enrollment.transfer"));
        when(access.lockActive(1)).thenReturn(transferActor);
        when(mutations.hash(anyString())).thenReturn("hash");
        var row=new StudentTransferDtos.Result("enrollment","student","source","target",2,"event");
        when(repository.transfer(transferActor,input)).thenReturn(List.of(row));
        assertEquals(new StudentTransferDtos.Receipt(List.of(row),1),
            service.transfer(transferActor,"transfer-key-123456",input));
        verify(repository).authorize(transferActor,input);
        verify(repository).transfer(transferActor,input);
    }
    @Test void exactSingleTransferGrantCannotExecuteBatchButBatchGrantCan() throws Exception {
        var second=new StudentTransferDtos.Item("enrollment-2",1);
        var batch=new StudentTransferDtos.Input(List.of(input.items().get(0),second),"target","调整时间");
        var singleOnly=new Actor(1,"teacher","all",Set.of(),Set.of("enrollment.transfer"));
        assertThrows(BusinessException.class,()->service.transfer(singleOnly,"transfer-key-123456",batch));
        verifyNoInteractions(access,repository,mutations);

        var batchActor=new Actor(1,"teacher","all",Set.of(),Set.of("enrollment.batchTransfer"));
        when(access.lockActive(1)).thenReturn(batchActor);when(mutations.hash(anyString())).thenReturn("batch-hash");
        var firstRow=new StudentTransferDtos.Result("enrollment","student","source","target",2,"event-1");
        var secondRow=new StudentTransferDtos.Result("enrollment-2","student-2","source","target",2,"event-2");
        when(repository.transfer(batchActor,batch)).thenReturn(List.of(firstRow,secondRow));
        assertEquals(new StudentTransferDtos.Receipt(List.of(firstRow,secondRow),2),
            service.transfer(batchActor,"transfer-batch-key-123456",batch));
        verify(repository).transfer(batchActor,batch);
    }
    @Test void duplicateOrInvalidItemsNeverReachStorage() {
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-key-123456",
            new StudentTransferDtos.Input(List.of(input.items().get(0),input.items().get(0)),"target","调整")));
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-key-123456",
            new StudentTransferDtos.Input(List.of(new StudentTransferDtos.Item("enrollment",0)),"target","调整")));
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-key-123456",
            new StudentTransferDtos.Input(input.items(),"target"," ")));
        verifyNoInteractions(access,repository,mutations);
    }
    @Test void replayStillChecksCurrentAccessButDoesNotTransferTwice() throws Exception {
        when(access.lockActive(1)).thenReturn(actor);when(mutations.hash(anyString())).thenReturn("hash");
        var result=new StudentTransferDtos.Receipt(List.of(new StudentTransferDtos.Result("enrollment","student","source","target",2,"event")),1);
        when(mutations.replay(actor,"transfer-key-123456","academic.enrollment.batchTransfer","hash")).thenReturn(mapper.writeValueAsString(result));
        assertEquals(result,service.transfer(actor,"transfer-key-123456",input));
        verify(repository).authorize(actor,input);verify(repository,never()).transfer(any(),any());
        verify(mutations,never()).complete(any(),any());
    }
    @Test void sourceOrTargetScopeFailurePreventsReplayAndWrite() {
        when(access.lockActive(1)).thenReturn(actor);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND,"无权限")).when(repository).authorize(actor,input);
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-key-123456",input));
        verifyNoInteractions(mutations);verify(repository,never()).transfer(any(),any());
    }
}
