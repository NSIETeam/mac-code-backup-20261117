package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.LegacyIdentityMigrationDtos.BindInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyIdentityMigrationServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final LegacyIdentityMigrationRepository repository = mock(LegacyIdentityMigrationRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Actor admin = new Actor(1, "admin", "all", Set.of(), Set.of("system.access.manage"));
    private final BindInput input = new BindInput("legacy-1", 1L, "a".repeat(64), "https://issuer.example",
        "legacy-1", "own", true, List.of("student.read"), List.of("student.read"), List.of(), List.of(), List.of(), List.of(),
        List.of("student.read"), List.of(), List.of("student.read"), "siyue-permission-map-v1",
        "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", false, null, null);

    @Test void exactAcademicLegacyPermissionsAreMappableAndAssignable() {
        assertEquals(List.of("session.attendance", "session.reschedule.request"),
            LegacyPermissionMappingCatalog.map(List.of("session.reschedule.request", "session.attendance")));
        assertTrue(AccessPermissionCatalog.isAssignable("session.attendance"));
        assertTrue(AccessPermissionCatalog.isAssignable("session.reschedule.request"));
    }

    @Test void featureIsDisabledByDefault() {
        LegacyIdentityMigrationService service = service(false, "", "");
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
            () -> service.bind(admin, "legacy-1", "migration-bind-key-0001", "checkpoint-value-0000000000000000", input)).code());
        verifyNoInteractions(access, repository, mutations);
    }

    @Test void onlyGlobalAccessAdministratorWithConfiguredCheckpointCanBind() throws Exception {
        LegacyIdentityMigrationService service = service(true, "a".repeat(64), "checkpoint-value-0000000000000000");
        when(access.lockActive(1)).thenReturn(admin);
        when(mutations.hash(anyString())).thenReturn("request-hash");
        when(mutations.replay(admin, "migration-bind-key-0001", "system.legacy-identity.bind:legacy-1", "request-hash"))
            .thenReturn(null);
        PrincipalView expected = new PrincipalView("legacy-1", "own", true, 2, false,
            List.of("student.read"), List.of("student.read"), List.of(), List.of());
        when(repository.bind(admin, "legacy-1", input, "https://migration-rehearsal.invalid", "request-hash"))
            .thenReturn(expected);
        assertEquals(expected, service.bind(admin, "legacy-1", "migration-bind-key-0001",
            "checkpoint-value-0000000000000000", input));
        verify(repository).bind(admin, "legacy-1", input, "https://migration-rehearsal.invalid", "request-hash");
        verify(mutations).complete(eq(admin), any(MutationRepository.RetryWrite.class));
    }

    @Test void badSourceHashOrCheckpointCannotReachRepository() throws Exception {
        LegacyIdentityMigrationService service = service(true, "b".repeat(64), "checkpoint-value-0000000000000000");
        when(access.lockActive(1)).thenReturn(admin);
        assertThrows(BusinessException.class, () -> service.bind(admin, "legacy-1",
            "migration-bind-key-0001", "incorrect-checkpoint-0000000000000000", input));
        assertThrows(BusinessException.class, () -> service.bind(admin, "legacy-1",
            "migration-bind-key-0001", "checkpoint-value-0000000000000000", input));
        verify(repository, times(2)).lockAdministration();
        verify(repository, never()).bind(any(), anyString(), any(), anyString(), anyString());
        verifyNoInteractions(mutations);
    }

    @Test void idempotentReplayDoesNotRebindPlaceholder() throws Exception {
        LegacyIdentityMigrationService service = service(true, "a".repeat(64), "checkpoint-value-0000000000000000");
        when(access.lockActive(1)).thenReturn(admin);
        when(mutations.hash(anyString())).thenReturn("request-hash");
        when(mutations.replay(admin, "migration-bind-key-0001", "system.legacy-identity.bind:legacy-1", "request-hash"))
            .thenReturn("{\"principalId\":\"legacy-1\",\"scope\":\"own\",\"active\":true,\"version\":2,\"permissionCodes\":[\"student.read\"],\"effectivePermissionCodes\":[\"student.read\"],\"deniedPermissionCodes\":[],\"campusIds\":[]}");
        PrincipalView result = service.bind(admin, "legacy-1", "migration-bind-key-0001",
            "checkpoint-value-0000000000000000", input);
        assertEquals("legacy-1", result.principalId());
        verify(repository).lockAdministration();
        verify(repository, never()).bind(any(), anyString(), any(), anyString(), anyString());
        verify(mutations, never()).complete(any(), any());
    }

    private LegacyIdentityMigrationService service(boolean enabled, String sourceHash, String checkpoint) {
        return new LegacyIdentityMigrationService(access, repository, new LegacyIdentityMigrationSupport(mutations, mapper),
        new LegacyIdentityMigrationProperties(enabled, sourceHash, checkpoint,
                "https://migration-rehearsal.invalid", "https://issuer.example",
                "{\"sourceSha256\":\"" + sourceHash + "\",\"permissionMappingVersion\":\"siyue-permission-map-v1\",\"roles\":{},\"principals\":{\"legacy-1\":{"
                    + "\"issuer\":\"https://issuer.example\",\"subject\":\"legacy-1\",\"scope\":\"own\","
                    + "\"active\":true,\"sourcePermissionCodes\":[\"student.read\"],"
                    + "\"permissionCodes\":[\"student.read\"],\"campusIds\":[],"
                    + "\"expectedEffectivePermissionCodes\":[\"student.read\"],\"sourceDirectPermissionCodes\":[\"student.read\"],"
                    + "\"sourceDeniedPermissionCodes\":[],\"legacyRoleIds\":[],\"positionIds\":[],"
                    + "\"deniedPermissionCodes\":[],\"permissionMappingVersion\":\"siyue-permission-map-v1\","
                    + "\"sourceRoleFingerprint\":\"4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945\","
                    + "\"mustChangePassword\":false,\"credentialChangeTicketHash\":null}}}"),
            new CredentialChangeProperties(true, "https://issuer.example", "h".repeat(32), 300));
    }
}
