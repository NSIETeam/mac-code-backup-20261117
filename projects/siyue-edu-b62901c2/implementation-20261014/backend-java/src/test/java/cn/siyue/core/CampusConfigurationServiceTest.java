package cn.siyue.core;

import cn.siyue.core.CampusConfigurationDtos.CampusFields;
import cn.siyue.core.CampusConfigurationDtos.UpdateInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CampusConfigurationServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final CampusConfigurationRepository repository = mock(CampusConfigurationRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final CampusConfigurationService service = new CampusConfigurationService(access, repository, mutations,
        new ObjectMapper().findAndRegisterModules());
    private final CampusFields input = new CampusFields("合成校区", null, null, null, null, null, null);
    @Test void creationRejectsCampusScopeBeforeLocking() {
        Actor actor = actor("campus", Set.of("settings.update"));
        assertThrows(BusinessException.class, () -> service.create(actor, "campus-create-key-0001", input));
        verifyNoInteractions(access, repository, mutations);
    }
    @Test void creationRechecksRevokedGlobalScopeAfterAccountLock() {
        Actor actor = actor("all", Set.of("settings.update"));
        when(access.lockActive(1)).thenReturn(actor("campus", actor.permissions()));
        assertThrows(BusinessException.class, () -> service.create(actor, "campus-create-key-0001", input));
        verifyNoInteractions(repository, mutations);
    }
    @Test void updateRechecksRevokedSettingsPermission() {
        Actor actor = actor("all", Set.of("settings.update"));
        when(access.lockActive(1)).thenReturn(actor("all", Set.of()));
        assertThrows(BusinessException.class, () -> service.update(actor, "C1", "campus-update-key-0001", new UpdateInput(input, 1)));
        verifyNoInteractions(repository, mutations);
    }
    @Test void listRejectsUnboundedRequests() {
        assertThrows(BusinessException.class, () -> service.list(actor("all", Set.of("settings.update")), 1, 101));
        verifyNoInteractions(repository);
    }
    private Actor actor(String scope, Set<String> permissions) { return new Actor(1, "admin", scope, Set.of(1L), permissions); }
}
