package cn.siyue.core;

import cn.siyue.core.AccessPositionDtos.CreateInput;
import cn.siyue.core.AccessPositionDtos.Position;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccessPositionServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final AccessPositionRepository repository = mock(AccessPositionRepository.class);
    private final AccessAdminRepository admin = mock(AccessAdminRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AccessPositionService service = new AccessPositionService(access, repository, admin, mutations, mapper);

    @Test void roleTemplatesRequireFreshGlobalAccessManager() {
        Actor campusAdmin = new Actor(1, "admin", "campus", Set.of(2L), Set.of("system.access.manage"));
        when(access.lockActive(1)).thenReturn(campusAdmin);
        BusinessException error = assertThrows(BusinessException.class, () -> service.list(campusAdmin, 1, 20));
        assertEquals(ErrorCode.FORBIDDEN, error.code());
        verifyNoInteractions(repository);

        Actor revoked = new Actor(2, "revoked", "all", Set.of(), Set.of());
        when(access.lockActive(2)).thenReturn(revoked);
        error = assertThrows(BusinessException.class, () -> service.list(revoked, 1, 20));
        assertEquals(ErrorCode.FORBIDDEN, error.code());
        verifyNoInteractions(repository);
    }

    @Test void templatesRejectUnknownAndAdministrativePermissionsBeforeAnyWrite() {
        Actor actor = manager();
        when(access.lockActive(1)).thenReturn(actor);
        assertThrows(BusinessException.class, () -> service.create(actor, "position-key-0000001",
            new CreateInput(null, "Position", "", null, List.of("system.access.manage"))));
        assertThrows(BusinessException.class, () -> service.create(actor, "position-key-0000002",
            new CreateInput(null, "Position", "", null, List.of("not.registered"))));
        verifyNoInteractions(mutations, repository);
    }

    @Test void onlyAssignablePermissionSetIsPersistedAndIdempotent() throws Exception {
        Actor actor = manager();
        when(access.lockActive(1)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("request-hash");
        when(mutations.replay(actor, "position-key-0000003", "system.position.create", "request-hash"))
            .thenReturn(null);
        Position created = new Position("POS_SYNTHETIC", "Course reader", "", "staff", true, 1,
            List.of("course.read"), List.of());
        when(repository.create(actor, new CreateInput(null, "Course reader", "", "staff", List.of("course.read"))))
            .thenReturn(created);
        assertEquals(created, service.create(actor, "position-key-0000003",
            new CreateInput(null, " Course reader ", "", null, List.of("course.read"))));
        verify(repository).create(actor, new CreateInput(null, "Course reader", "", "staff", List.of("course.read")));
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("system.position.create")
            && write.resourceId().equals("POS_SYNTHETIC")));
    }

    @Test void directoryKindDefaultsToStaffAndAcceptsOnlyExplicitTeacherClassification() throws Exception {
        Actor actor = manager();
        when(access.lockActive(1)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("directory-kind-hash");
        when(mutations.replay(eq(actor), anyString(), eq("system.position.create"), anyString())).thenReturn(null);
        when(repository.create(eq(actor), any(CreateInput.class))).thenAnswer(invocation -> {
            CreateInput input = invocation.getArgument(1);
            return new Position("POS_DIRECTORY", input.name(), input.description(), input.directoryKind(),
                true, 1, input.permissionCodes(), List.of());
        });

        Position defaultStaff = service.create(actor, "position-default-kind-0001",
            new CreateInput(null, "Default staff", "", null, List.of("course.read")));
        Position teacher = service.create(actor, "position-teacher-kind-0001",
            new CreateInput(null, "Explicit teacher", "", "teacher", List.of("course.read")));

        assertEquals("staff", defaultStaff.directoryKind());
        assertEquals("teacher", teacher.directoryKind());
        assertThrows(BusinessException.class, () -> service.create(actor, "position-invalid-kind-0001",
            new CreateInput(null, "Invalid kind", "", "student", List.of("course.read"))));
    }

    private static Actor manager() {
        return new Actor(1, "admin", "all", Set.of(), Set.of("system.access.manage"));
    }
}
