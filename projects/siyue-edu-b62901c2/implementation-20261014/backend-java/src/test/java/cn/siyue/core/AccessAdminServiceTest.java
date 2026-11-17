package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.CreateInput;
import cn.siyue.core.AccessAdminDtos.UpdateInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessAdminServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final AccessAdminRepository repository = mock(AccessAdminRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final AccessAdminService service = new AccessAdminService(access, repository, mutations,
        new ObjectMapper().findAndRegisterModules());

    @Test void unprivilegedActorCannotListAccounts() {
        Actor actor = new Actor(2, "staff-2", "all", Set.of(), Set.of());
        when(access.lockActive(2)).thenReturn(actor);
        assertThrows(BusinessException.class, () -> service.list(actor, 1, 20));
        verify(repository).lockAdminChanges();
        verify(repository, never()).list(1, 20);
    }

    @Test void managerCannotChangeTheirOwnAccessEvenWithExpectedVersion() {
        Actor actor = admin();
        when(access.lockActive(1)).thenReturn(actor);
        UpdateInput input = new UpdateInput(1L, "all", true, List.of("student.read"), List.of(), List.of());
        assertThrows(BusinessException.class, () -> service.update(actor, "admin-1",
            "access-change-key-00001", input));
        verify(repository).lockAdminChanges();
        verifyNoInteractions(mutations);
    }

    @Test void accountProvisioningRejectsUnknownOrPrivilegedGrantBeforeWriting() {
        Actor actor = admin();
        when(access.lockActive(1)).thenReturn(actor);
        CreateInput input = new CreateInput("staff-3", "https://issuer.example", "sub-3", "own",
            List.of("system.access.manage"), List.of(), List.of());
        assertThrows(BusinessException.class, () -> service.create(actor,
            "access-create-key-00001", input));
        verify(repository).lockAdminChanges();
        verifyNoInteractions(mutations);
    }

    @Test void accountProvisioningRejectsNonTlsRemoteIssuerBeforeWriting() {
        Actor actor = admin();
        when(access.lockActive(1)).thenReturn(actor);
        CreateInput input = new CreateInput("staff-3", "http://issuer.example", "sub-3", "own",
            List.of("student.read"), List.of(), List.of());
        BusinessException error = assertThrows(BusinessException.class, () -> service.create(actor,
            "access-create-key-00002", input));
        assertEquals(ErrorCode.INVALID_INPUT, error.code());
        verifyNoInteractions(mutations);
        verify(repository, never()).create(any(), any());
    }

    @Test void exactLegacyActionCodesAreValidAdministratorGrants() throws Exception {
        Actor actor=admin();when(access.lockActive(1)).thenReturn(actor);
        List<String> codes=List.of("student.create","course.create","course.update","class.create",
            "class.batchCreate","enrollment.create","enrollment.transfer","enrollment.batchTransfer",
            "session.reschedule.request","session.attendance");
        CreateInput input=new CreateInput("staff-4","https://issuer.example","subject-4","own",codes,List.of(),List.of());
        var created=new AccessAdminDtos.PrincipalView("staff-4","own",true,1,false,codes,codes,List.of(),List.of());
        when(mutations.hash(anyString())).thenReturn("hash");
        when(repository.create(actor,input)).thenReturn(created);
        assertEquals(created,service.create(actor,"access-create-key-00004",input));
        verify(repository).create(actor,input);
    }

    private Actor admin() {
        return new Actor(1, "admin-1", "all", Set.of(), Set.of("system.access.manage"));
    }
}
