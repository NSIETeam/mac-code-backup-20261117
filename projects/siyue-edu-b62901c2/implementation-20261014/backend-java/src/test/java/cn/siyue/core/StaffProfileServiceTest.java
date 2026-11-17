package cn.siyue.core;

import cn.siyue.core.StaffProfileDtos.Profile;
import cn.siyue.core.StaffProfileDtos.UpdateInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaffProfileServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final StaffProfileRepository profiles = mock(StaffProfileRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final StaffProfileService service = new StaffProfileService(access, profiles, mutations, new ObjectMapper());

    @Test void deniesMissingPermissionAndNonGlobalScopeBeforeWriting() {
        assertThrows(BusinessException.class, () -> service.update(actor("all", Set.of()), "staff-profile-key-0001",
            "employee-1", new UpdateInput(null, null)));
        assertThrows(BusinessException.class, () -> service.update(actor("campus", Set.of("account.update")),
            "staff-profile-key-0002", "employee-1", new UpdateInput(null, null)));
        verifyNoInteractions(access, profiles, mutations);
    }

    @Test void checksAuthorizationAfterLockAndRequiresStableIdempotencyKey() {
        when(access.lockActive(1)).thenReturn(actor("campus", Set.of("account.update")));
        assertThrows(BusinessException.class, () -> service.update(actor("all", Set.of("account.update")),
            "staff-profile-key-0003", "employee-1", new UpdateInput(null, null)));
        assertThrows(BusinessException.class, () -> service.update(actor("all", Set.of("account.update")),
            "bad", "employee-1", new UpdateInput(null, null)));
        verifyNoInteractions(profiles, mutations);
    }

    private Actor actor(String scope, Set<String> permissions) {
        return new Actor(1, "admin", scope, Set.of(), permissions);
    }
}
