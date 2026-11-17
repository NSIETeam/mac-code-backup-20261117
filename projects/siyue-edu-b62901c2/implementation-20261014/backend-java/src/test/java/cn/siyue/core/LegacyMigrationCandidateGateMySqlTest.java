package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Requires a dedicated, local candidate schema created only for this gate/lock acceptance. */
@EnabledIfEnvironmentVariable(named = "SIYUE_MIGRATION_CANDIDATE_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SIYUE_MIGRATION_CANDIDATE_TEST_ACK", matches = "isolated-schema-only")
class LegacyMigrationCandidateGateMySqlTest {
    private static final String HASH = "b".repeat(64);
    private static final String CHECKPOINT = "isolated-gate-20261017-0001";

    @Test
    void gateBindsCandidateAndSourceWhileAdvisoryLockSerializesRunners() throws Exception {
        String url = System.getenv("SIYUE_MIGRATION_CANDIDATE_MYSQL_URL");
        URI parsed = URI.create(url.substring("jdbc:".length()));
        String schema = parsed.getPath().substring(1);
        LegacyMigrationTargetAuthorization.requireCandidateSchema(schema);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
            System.getenv("SIYUE_MIGRATION_CANDIDATE_MYSQL_USER"),
            System.getenv("SIYUE_MIGRATION_CANDIDATE_MYSQL_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS core_migration_candidate_gate ("
            + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,"
            + "created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),"
            + "updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),"
            + "deleted BOOLEAN NOT NULL DEFAULT FALSE,target_schema VARCHAR(64) NOT NULL,"
            + "source_sha256 CHAR(64) NOT NULL,checkpoint_id VARCHAR(128) NOT NULL,"
            + "status ENUM('authorized','migration_running','migration_incomplete','migration_verified') NOT NULL,"
            + "verified_at DATETIME(3) NULL,UNIQUE KEY uq_gate_target(target_schema),"
            + "UNIQUE KEY uq_gate_checkpoint(checkpoint_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
            + "COLLATE=utf8mb4_0900_ai_ci");
        var envelope = new ObjectMapper().readTree("""
            {"schema":"siyue.legacy-business/v1","revision":1,"state":{},"auditProvenance":[]}
            """);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> {
            jdbc.update("INSERT INTO core_migration_candidate_gate(target_schema,source_sha256,checkpoint_id,status) "
                + "VALUES(?,?,?,'authorized')", schema, HASH, CHECKPOINT);
            var request = new LegacyMigrationTargetAuthorization.AuthorizationRequest(schema, HASH, CHECKPOINT,
                envelope, false);
            LegacyMigrationTargetAuthorization authorization =
                LegacyMigrationTargetAuthorization.authorize(jdbc, request);
            try (java.sql.Connection first = dataSource.getConnection();
                    java.sql.Connection second = dataSource.getConnection()) {
                LegacyMigrationCandidateCli.acquireMigrationLock(first, schema);
                assertThrows(IllegalStateException.class,
                    () -> LegacyMigrationCandidateCli.acquireMigrationLock(second, schema));
                LegacyMigrationCandidateCli.setGateStatus(jdbc, authorization, "migration_running");
                authorization.verify(jdbc, schema, envelope);
                LegacyMigrationCandidateCli.setGateStatus(jdbc, authorization, "migration_incomplete");
                LegacyMigrationCandidateCli.setGateStatus(jdbc, authorization, "migration_verified");
                assertEquals("migration_verified", jdbc.queryForObject(
                    "SELECT status FROM core_migration_candidate_gate WHERE target_schema=?", String.class, schema));
                LegacyMigrationCandidateCli.releaseMigrationLock(first, schema);
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException("isolated lock test failed", error);
            }
            status.setRollbackOnly();
            return null;
        });
    }
}
