package cn.siyue.core;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class UiAnnotationServiceTest {
    private final AccessRepository access=mock(AccessRepository.class);
    private final UiAnnotationRepository repository=mock(UiAnnotationRepository.class);
    private final MutationRepository mutations=mock(MutationRepository.class);
    private final UiAnnotationService service=new UiAnnotationService(access,repository,mutations,new ObjectMapper());
    private Actor actor(String scope,Set<String> permissions) {
        Actor a=new Actor(1,"staff",scope,Set.of(),permissions);when(access.lockActive(1)).thenReturn(a);return a;
    }
    @Test void permissionAndGlobalScopeAreBothRequired() {
        assertThrows(BusinessException.class,()->service.list(actor("all",Set.of()),"open",1,20));
        assertThrows(BusinessException.class,()->service.list(actor("campus",Set.of("system.access.manage")),"open",1,20));
        verifyNoInteractions(repository);
    }
    @Test void incompleteWorkflowNeverWrites() {
        var a=actor("all",Set.of("system.access.manage"));
        var input=new UiAnnotationDtos.Create("/crm/","测试","other","流程","#flow","建议","workflow","流程","a->b","");
        assertThrows(BusinessException.class,()->service.create(a,"annotation-key-00001",input));
        verifyNoInteractions(repository,mutations);
    }
    @Test void completionRequiresVerification() {
        var a=actor("all",Set.of("system.access.manage"));
        assertThrows(BusinessException.class,()->service.update(a,"annotation","annotation-key-00002",new UiAnnotationDtos.Update("resolved",1,false)));
        verifyNoInteractions(repository,mutations);
    }
}
