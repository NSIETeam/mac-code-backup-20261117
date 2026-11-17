package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Short-lived capability for one explicitly authorized, inactive candidate schema and source. */
final class LegacyMigrationTargetAuthorization {
    private static final Set<String> STATUSES = Set.of("authorized", "migration_running", "migration_incomplete",
        "migration_verified");
    private final String schema;
    private final String sourceSha256;
    private final String checkpoint;
    private final String envelopeHash;

    private LegacyMigrationTargetAuthorization(String schema, String sourceSha256, String checkpoint,
            String envelopeHash) {
        this.schema = schema;
        this.sourceSha256 = sourceSha256;
        this.checkpoint = checkpoint;
        this.envelopeHash = envelopeHash;
    }

    static LegacyMigrationTargetAuthorization authorize(JdbcTemplate jdbc, AuthorizationRequest request) {
        String schema = request.schema();
        String sourceSha256 = request.sourceSha256();
        String checkpoint = request.checkpoint();
        JsonNode envelope = request.envelope();
        requireCandidateSchema(schema);
        requireHash(sourceSha256);
        requireCheckpoint(checkpoint);
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!schema.equals(database)) { throw rejected(); }
        String sql = "SELECT source_sha256,checkpoint_id,status FROM core_migration_candidate_gate "
            + "WHERE target_schema=? AND deleted=0"
            + (request.lockGate() ? " FOR UPDATE" : "");
        List<Gate> gates = jdbc.query(sql, (rs, row) ->
                new Gate(rs.getString(1), rs.getString(2), rs.getString(3)), schema);
        if (gates.size() != 1) { throw rejected(); }
        Gate gate = gates.get(0);
        if (!sourceSha256.equals(gate.sourceSha256()) || !checkpoint.equals(gate.checkpoint())
                || !STATUSES.contains(gate.status())) { throw rejected(); }
        String canonicalHash = LegacyCatalogRehearsal.hash(envelope.toString());
        return new LegacyMigrationTargetAuthorization(schema, sourceSha256, checkpoint, canonicalHash);
    }

    void verify(JdbcTemplate jdbc, String database, JsonNode envelope) {
        if (!schema.equals(database) || !schema.equals(jdbc.queryForObject("SELECT DATABASE()", String.class))
                || !envelopeHash.equals(LegacyCatalogRehearsal.hash(envelope.toString()))) { throw rejected(); }
        List<Gate> gates = jdbc.query("SELECT source_sha256,checkpoint_id,status FROM "
            + "core_migration_candidate_gate WHERE target_schema=? AND deleted=0", (rs, row) ->
                new Gate(rs.getString(1), rs.getString(2), rs.getString(3)), schema);
        if (gates.size() != 1 || !sourceSha256.equals(gates.get(0).sourceSha256())
                || !checkpoint.equals(gates.get(0).checkpoint()) || !STATUSES.contains(gates.get(0).status())) {
            throw rejected();
        }
    }

    String schema() { return schema; }
    String sourceSha256() { return sourceSha256; }
    String checkpoint() { return checkpoint; }

    static void requireRehearsalOrCandidate(String database, JdbcTemplate jdbc, JsonNode envelope,
            LegacyMigrationTargetAuthorization authorization) {
        if (authorization == null) {
            if (database == null || !database.endsWith("_test")) { throw rejected(); }
            return;
        }
        authorization.verify(jdbc, database, envelope);
    }

    static void requireCandidateSchema(String schema) {
        if (schema == null || !schema.matches("siyue_migration_candidate_[a-f0-9]{10,16}")
                || schema.endsWith("_test")) { throw rejected(); }
    }

    static void requireHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) { throw rejected(); }
    }

    static void requireCheckpoint(String checkpoint) {
        if (checkpoint == null || !checkpoint.matches("[A-Za-z0-9._:-]{16,128}")) { throw rejected(); }
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("迁移目标授权、来源哈希或检查点不匹配");
    }

    private record Gate(String sourceSha256, String checkpoint, String status) { }
    record AuthorizationRequest(String schema, String sourceSha256, String checkpoint, JsonNode envelope,
            boolean lockGate) { }
}
