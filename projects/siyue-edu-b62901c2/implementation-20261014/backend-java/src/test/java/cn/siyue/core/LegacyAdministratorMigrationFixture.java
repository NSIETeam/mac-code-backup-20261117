package cn.siyue.core;

import cn.siyue.core.LegacyIdentityMigrationDtos.BindInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Synthetic trusted role with a deliberately small reviewed source set; not a production identity manifest. */
final class LegacyAdministratorMigrationFixture {
    static final List<String> SOURCE = List.of("employee.create", "role.grant", "student.read");
    static final List<String> TARGET = List.of("student.read", "system.access.manage");
    static final ObjectMapper MAPPER = new ObjectMapper();

    private LegacyAdministratorMigrationFixture() { }

    static ObjectNode role() throws Exception {
        ObjectNode role = MAPPER.createObjectNode();
        role.put("roleId", "superadmin"); role.put("sourceVersion", 1);
        role.set("sourcePermissionCodes", MAPPER.valueToTree(SOURCE));
        String fingerprint = hash(MAPPER.writeValueAsBytes(role));
        role.putNull("positionId"); role.put("name", "Synthetic administrator");
        role.put("description", "Reserved migration-only synthetic administrator");
        role.set("permissionCodes", MAPPER.valueToTree(TARGET));
        role.put("permissionMappingVersion", LegacyPermissionMappingCatalog.VERSION);
        role.put("sourceFingerprint", fingerprint);
        return role;
    }

    static BindInput input(String id, String issuer, boolean active) throws Exception {
        ObjectNode roleFingerprint = MAPPER.createObjectNode();
        roleFingerprint.put("roleId", "superadmin"); roleFingerprint.put("sourceVersion", 1);
        roleFingerprint.put("sourceFingerprint", role().path("sourceFingerprint").asText());
        String fingerprint = hash(MAPPER.writeValueAsBytes(List.of(roleFingerprint)));
        return new BindInput(id, 1L, "a".repeat(64), issuer, id, "all", active, SOURCE, TARGET,
            List.of(), List.of("superadmin"), List.of(), List.of(), List.of(), List.of(), TARGET,
            LegacyPermissionMappingCatalog.VERSION, fingerprint, false, null, null);
    }

    static ObjectNode entry(BindInput input) {
        ObjectNode entry = MAPPER.valueToTree(input);
        entry.remove(List.of("legacyUserId", "expectedVersion", "sourceSha256", "credentialChangeTicket"));
        entry.putNull("credentialChangeTicketHash");
        return entry;
    }

    static String manifest(BindInput input) throws Exception {
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("sourceSha256", input.sourceSha256());
        manifest.put("permissionMappingVersion", LegacyPermissionMappingCatalog.VERSION);
        manifest.putObject("roles").set("superadmin", role());
        manifest.putObject("principals").set(input.legacyUserId(), entry(input));
        return MAPPER.writeValueAsString(manifest);
    }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
