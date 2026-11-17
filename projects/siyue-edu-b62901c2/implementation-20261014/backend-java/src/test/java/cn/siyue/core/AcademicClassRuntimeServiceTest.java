package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicClassRuntimeServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final AcademicClassRuntimeRepository repository = mock(AcademicClassRuntimeRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AcademicClassRuntimeService service = new AcademicClassRuntimeService(access, repository,
        mutations, mapper);

    @Test void classCreateAllowsScheduleAndRechecksFreshGrant() throws Exception {
        Actor actor = new Actor(7, "teacher", "all", Set.of(), Set.of("class.create"));
        when(access.lockActive(7)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("schedule-hash");
        var input = new AcademicClassRuntimeDtos.ScheduleInput(3, List.of(new AcademicClassRuntimeDtos.ScheduleSession(
            "session-1", "lesson-1", Instant.parse("2027-03-01T09:00:00Z"))));
        var result = new AcademicClassRuntimeDtos.ScheduleReceipt("class-1", 4, List.of());
        when(repository.schedule(actor, "class-1", input)).thenReturn(result);
        assertEquals(result, service.schedule(actor, "class-1", "schedule-key-000001", input));
        verify(repository).schedule(actor, "class-1", input);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("academic.class.schedule")));
    }

    @Test void classCreateDoesNotAuthorizeRelocationAndPermissionIsRechecked() {
        Actor creator = new Actor(7, "teacher", "all", Set.of(), Set.of("class.create"));
        var input = new AcademicClassRuntimeDtos.RelocateInput(List.of(
            new AcademicClassRuntimeDtos.RelocateClass("class-1", 3)), "campus-2", "Room 2");
        assertThrows(BusinessException.class, () -> service.relocate(creator, "relocate-key-000001", input));
        verifyNoInteractions(access, repository, mutations);

        Actor relocator = new Actor(7, "manager", "all", Set.of(), Set.of("class.relocate"));
        when(access.lockActive(7)).thenReturn(new Actor(7, "manager", "all", Set.of(), Set.of()));
        assertThrows(BusinessException.class, () -> service.relocate(relocator, "relocate-key-000001", input));
        verifyNoInteractions(repository, mutations);
    }

    @Test void sessionSubstituteAndRecordRequireTheirOwnExactActions() {
        Actor mover = new Actor(7, "teacher", "all", Set.of(), Set.of("session.move"));
        var substitute = new AcademicClassRuntimeDtos.SubstituteInput("teacher-2", "因故代课", 1);
        assertThrows(BusinessException.class, () -> service.substitute(mover, "session-1",
            "substitute-key-0001", substitute));
        var record = new AcademicClassRuntimeDtos.RecordInput("已完成授课", 1);
        assertThrows(BusinessException.class, () -> service.record(mover, "session-1", "record-key-000001", record));
        verifyNoInteractions(access, repository, mutations);
    }
}
