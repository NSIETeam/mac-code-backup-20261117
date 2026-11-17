package cn.siyue.core;

import cn.siyue.core.StaffDirectoryDtos.DirectoryPage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaffDirectoryServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final StaffDirectoryRepository repository = mock(StaffDirectoryRepository.class);
    private final StaffDirectoryService service = new StaffDirectoryService(access, repository);

    @Test void exactTeacherGrantCanReadOnlyTheTeacherDirectory() {
        Actor actor = actor("teacher.read", "teacher.archive");
        when(access.lockActiveShared(actor.id())).thenReturn(actor);
        DirectoryPage page = new DirectoryPage(List.of(), 0, 1, 25, "teacher");
        when(repository.list(actor, "teacher", 1, 25)).thenReturn(page);

        assertEquals(page, service.list(actor, "teacher", 1, 25));
        assertThrows(BusinessException.class, () -> service.list(actor, "all", 1, 25));
        assertThrows(BusinessException.class, () -> service.list(actor, "staff", 1, 25));
        verify(repository, times(1)).list(actor, "teacher", 1, 25);
    }

    @Test void broadStaffAndAccountUpdateGrantsReadAllDirectoryCategories() {
        for (String permission : List.of("staff.read", "staff.archive", "account.update")) {
            Actor actor = actor(permission);
            when(access.lockActiveShared(actor.id())).thenReturn(actor);
            when(repository.list(actor, "all", 1, 50)).thenReturn(new DirectoryPage(List.of(), 0, 1, 50, "all"));
            assertEquals("all", service.list(actor, "all", 1, 50).category());
        }
    }

    @Test void inactiveOrUnscopedActorCannotReadAndInvalidQueryFailsClosed() {
        Actor actor = actor("student.read");
        when(access.lockActiveShared(actor.id())).thenReturn(actor);
        assertThrows(BusinessException.class, () -> service.list(actor, "all", 1, 50));
        assertThrows(BusinessException.class, () -> service.list(actor, "unknown", 1, 50));
        assertThrows(BusinessException.class, () -> service.list(actor("staff.read"), "staff", 0, 50));
        verifyNoInteractions(repository);
    }

    private static Actor actor(String... permissions) {
        return new Actor(1, "directory-reader", "campus", Set.of(10L), Set.of(permissions));
    }
}
