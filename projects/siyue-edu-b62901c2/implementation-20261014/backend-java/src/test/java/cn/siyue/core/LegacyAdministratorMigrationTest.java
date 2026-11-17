package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.LegacyIdentityMigrationDtos.BindInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyAdministratorMigrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AccessRepository access = mock(AccessRepository.class);
    private final LegacyIdentityMigrationRepository repository = mock(LegacyIdentityMigrationRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final Actor operator = new Actor(1, "migration-operator", "all", Set.of(), Set.of("system.access.manage"));
    private final String checkpoint = "synthetic-admin-migration-checkpoint";

    @Test void bindsExplicitTrustedAdministratorWithoutCreatingAssignablePosition() throws Exception {
        BindInput input = input();
        PrincipalView expected = new PrincipalView(input.subject(), "all", true, 2, false,
            input.permissionCodes(), input.expectedEffectivePermissionCodes(), List.of(), List.of());
        when(repository.bind(eq(operator), eq(input.subject()), eq(input), anyString(), anyString())).thenReturn(expected);
        assertEquals(expected, service(input).bind(operator, input.subject(), "admin-migration-key-001", checkpoint, input));
        verify(repository, never()).validateLegacyPosition(anyString(), anyString(), anyString(), anyList());
        assertFalse(AccessPermissionCatalog.isAssignable("system.access.manage"));
        assertThrows(BusinessException.class, () -> LegacyPermissionMappingCatalog.map(List.of("role.grant")));
    }

    @Test void rejectsOrdinarySubjectElevationAndUnreviewedAdministratorPermission() throws Exception {
        BindInput trusted = input();
        ObjectNode node = mapper.valueToTree(trusted);
        node.putArray("legacyRoleIds");
        BindInput forged = mapper.treeToValue(node, BindInput.class);
        assertThrows(BusinessException.class, () -> service(trusted).bind(operator, trusted.subject(),
            "admin-migration-key-002", checkpoint, forged));
        node.putArray("sourcePermissionCodes").add("student.read");
        node.putArray("sourceDirectPermissionCodes").add("student.read");
        node.put("sourceRoleFingerprint", "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945");
        BindInput ordinaryTrusted = mapper.treeToValue(node, BindInput.class);
        assertThrows(BusinessException.class, () -> service(ordinaryTrusted).bind(operator,
            ordinaryTrusted.subject(), "admin-migration-key-004", checkpoint, ordinaryTrusted));
        assertThrows(BusinessException.class, () -> LegacyAdministratorMigrationPolicy.map(
            List.of("employee.create", "role.grant", "unsupported.synthetic.permission")));
        verify(repository, never()).bind(any(), anyString(), any(), anyString(), anyString());
    }

    @Test void evenTrustedAdministratorCannotCarryCampusScopeOrPartialDenial() throws Exception {
        for (String field : List.of("scope", "sourceDeniedPermissionCodes", "campusIds")) {
            ObjectNode node = mapper.valueToTree(input());
            if (field.equals("scope")) { node.put("scope", "own"); }
            else { node.putArray(field).add(field.equals("campusIds") ? "synthetic-campus" : "student.read"); }
            BindInput changed = mapper.treeToValue(node, BindInput.class);
            assertThrows(BusinessException.class, () -> service(changed).bind(operator, changed.subject(),
                "admin-migration-key-003", checkpoint, changed));
        }
        verify(repository, never()).bind(any(), anyString(), any(), anyString(), anyString());
    }

    private BindInput input() throws Exception {
        return LegacyAdministratorMigrationFixture.input("synthetic-admin", "https://issuer.example", true);
    }

    private LegacyIdentityMigrationService service(BindInput trusted) throws Exception {
        when(access.lockActive(operator.id())).thenReturn(operator);
        when(mutations.hash(anyString())).thenReturn("synthetic-request-hash");
        return new LegacyIdentityMigrationService(access, repository, new LegacyIdentityMigrationSupport(mutations, mapper),
            new LegacyIdentityMigrationProperties(true, trusted.sourceSha256(), checkpoint,
                "https://migration-rehearsal.invalid", trusted.issuer(), LegacyAdministratorMigrationFixture.manifest(trusted)),
            new CredentialChangeProperties(true, trusted.issuer(), "h".repeat(32), 300));
    }
}
