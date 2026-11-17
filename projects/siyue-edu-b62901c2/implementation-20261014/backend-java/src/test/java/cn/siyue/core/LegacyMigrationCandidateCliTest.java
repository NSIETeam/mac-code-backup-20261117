package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyMigrationCandidateCliTest {
    private static final String HASH = "a".repeat(64);
    private static final String CHECKPOINT = "source-stop-20261017-0001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsToReadOnlyAndRequiresExplicitCandidateIdentity() {
        var options = LegacyMigrationCandidateCli.Options.parse(arguments());

        assertFalse(options.execute());
        assertEquals("siyue_migration_candidate_0123456789ab", options.targetSchema());
        assertEquals(HASH, options.sourceSha256());
        assertEquals(CHECKPOINT, options.checkpoint());
    }

    @Test
    void executeIsOptInAndTestOrProductionSchemasAreRejected() {
        var options = LegacyMigrationCandidateCli.Options.parse(arguments("--execute"));
        assertTrue(options.execute());

        assertThrows(IllegalArgumentException.class, () -> LegacyMigrationCandidateCli.Options.parse(
            arguments("--target-schema", "siyue_production")));
        assertThrows(IllegalArgumentException.class, () -> LegacyMigrationCandidateCli.Options.parse(
            arguments("--target-schema", "siyue_migration_candidate_0123456789_test")));
    }

    @Test
    void duplicateAndMalformedOptionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LegacyMigrationCandidateCli.Options.parse(
            arguments("--execute", "--execute")));
        assertThrows(IllegalArgumentException.class, () -> LegacyMigrationCandidateCli.Options.parse(
            arguments("--source-sha256", "ABC")));
        assertThrows(IllegalArgumentException.class, () -> LegacyMigrationCandidateCli.Options.parse(
            new String[] {"--snapshot", "/tmp/source.json"}));
    }

    @Test
    void unsupportedCollectionsAndMissingAuditBlockBeforeConnecting() throws Exception {
        var envelope = MAPPER.readTree("""
            {"schema":"siyue.legacy-business/v1","revision":1,
             "state":{"students":[],"unmappedInventory":[{"id":"synthetic"}]},"auditProvenance":[]}
            """);

        var blockers = LegacyMigrationCandidateCli.sourceBlockers(envelope);

        assertTrue(blockers.contains("source auditProvenance missing or empty"));
        assertTrue(blockers.contains("unsupported source collection: unmappedInventory"));
    }

    @Test
    void blockedSnapshotStopsBeforeDatabaseConfigurationIsNeeded(@TempDir Path directory) throws Exception {
        byte[] bytes = """
            {"schema":"siyue.legacy-business/v1","revision":1,
             "state":{"students":[],"unmappedInventory":[{"id":"synthetic"}]},"auditProvenance":[]}
            """.getBytes(StandardCharsets.UTF_8);
        Path snapshot = directory.resolve("snapshot.json");
        Files.write(snapshot, bytes);
        String sourceHash = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String[] args = {"--snapshot", snapshot.toString(), "--target-schema",
            "siyue_migration_candidate_0123456789ab", "--source-sha256", sourceHash,
            "--checkpoint", CHECKPOINT};

        assertEquals(2, LegacyMigrationCandidateCli.run(args));
    }

    @Test
    void unmappedCatalogHistoryBlocksReadOnlyCliBeforeDatabaseConfiguration(@TempDir Path directory) throws Exception {
        for (String field : java.util.List.of("record", "substituteReason", "substituteTeacherId", "teacherIds")) {
            var envelope=MAPPER.createObjectNode();envelope.put("schema","siyue.legacy-business/v1").put("revision",1);
            var classroom=envelope.putObject("state").putArray("classes").addObject();
            if (field.equals("teacherIds")) classroom.putArray(field).add("PRIVATE_FIXTURE");
            else {
                var session=classroom.putArray("schedule").addObject();
                if (field.equals("record")) session.putObject(field).put("content","PRIVATE_FIXTURE")
                    .put("actorId","PRIVATE_FIXTURE").put("at","2026-10-17T00:00:00Z");
                else session.put(field,"PRIVATE_FIXTURE");
            }
            envelope.putArray("auditProvenance").addObject().put("outcome","success");
            byte[] bytes=MAPPER.writeValueAsBytes(envelope);Path snapshot=directory.resolve(field+".json");
            Files.write(snapshot,bytes);
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            String[] args={"--snapshot",snapshot.toString(),"--target-schema","siyue_migration_candidate_0123456789ab",
                "--source-sha256",hash,"--checkpoint",CHECKPOINT};
            var output=new java.io.StringWriter();var errors=new java.io.StringWriter();
            assertEquals(2,LegacyMigrationCandidateCli.run(args,new java.io.PrintWriter(output),new java.io.PrintWriter(errors)));
            assertTrue(errors.toString().contains("failureStage=SOURCE_VALIDATE"));
            assertFalse(errors.toString().contains("PRIVATE_FIXTURE"));
            assertFalse(output.toString().contains("PRIVATE_FIXTURE"));
        }
    }

    @Test
    void campaignAuditOnlyActorsAreRequiredInactivePrincipals() throws Exception {
        var envelope = MAPPER.readTree("""
            {"schema":"siyue.legacy-business/v1","revision":1,
             "state":{"campaigns":[{"id":"campaign","ownerId":"owner","createdBy":"creator",
               "participantIds":["member"],"promoterIds":["promoter"]}],
               "staff":[{"id":"synthetic-staff-profile","history":[{"actorId":"profile-editor"}]}]},
             "auditProvenance":[
               {"actorId":"audit-linker","action":"campaign.linkForm"},
               {"actorId":"audit-creator","action":"campaign.create"},
               {"actorId":"audit-updater","action":"campaign.update"},
               {"actorId":"audit-promotion-creator","action":"campaign.promotionLink.create"}]}
            """);

        Set<String> required = LegacyMigrationCandidateCli.requiredPrincipals(envelope.path("state"),
            envelope.path("auditProvenance"));

        assertTrue(required.containsAll(Set.of("audit-linker", "audit-creator", "audit-updater",
            "audit-promotion-creator", "synthetic-staff-profile", "profile-editor")));
        assertFalse(required.contains(""));
    }

    @Test
    void leadRelationsAndReclaimActorsAreRequiredWithoutInventingCreators() throws Exception {
        var envelope = MAPPER.readTree("""
            {"state":{"leads":[{"id":"lead","registrarId":"registrar",
              "collectedByIds":["collector-one","collector-two",""]}],
              "leadReclaimRequests":[{"id":"request","leadId":"lead",
                "applicantId":"applicant","reviewedBy":"reviewer"}]},"auditProvenance":[]}
            """);

        Set<String> required = LegacyMigrationCandidateCli.requiredPrincipals(envelope.path("state"),
            envelope.path("auditProvenance"));

        assertEquals(Set.of("registrar", "collector-one", "collector-two", "applicant", "reviewer"), required);
    }

    private static String[] arguments(String... extra) {
        String[] base = {"--snapshot", "/tmp/snapshot.json", "--target-schema",
            "siyue_migration_candidate_0123456789ab", "--source-sha256", HASH, "--checkpoint", CHECKPOINT};
        String[] result = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
