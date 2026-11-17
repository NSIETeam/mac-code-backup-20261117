package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LegacyImportBatchRehearsalTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsLaterAuditTimeAndNormalizesOffsetAtMillisecondPrecision() throws Exception {
        var batch = mapper.readTree("""
            {"createdAt":"2026-10-03T01:02:03Z","updatedAt":"2026-10-03T01:02:03Z"}
            """);
        var audit = new LegacyImportBatchRehearsal.AuditEvent("actor",
            Timestamp.from(Instant.parse("2026-10-03T09:02:03.125+08:00")));

        assertDoesNotThrow(() -> LegacyImportBatchRehearsal.requireAuditNotBeforeBatch(audit, batch));
    }

    @Test
    void rejectsAuditBeforeCreatedOrUpdatedBusinessTime() throws Exception {
        var batch = mapper.readTree("""
            {"createdAt":"2026-10-03T01:02:03Z","updatedAt":"2026-10-03T01:02:04Z"}
            """);
        var beforeCreated = new LegacyImportBatchRehearsal.AuditEvent("actor",
            Timestamp.from(Instant.parse("2026-10-03T01:02:02.999Z")));
        var beforeUpdated = new LegacyImportBatchRehearsal.AuditEvent("actor",
            Timestamp.from(Instant.parse("2026-10-03T01:02:03.500Z")));

        assertThrows(IllegalArgumentException.class,
            () -> LegacyImportBatchRehearsal.requireAuditNotBeforeBatch(beforeCreated, batch));
        assertThrows(IllegalArgumentException.class,
            () -> LegacyImportBatchRehearsal.requireAuditNotBeforeBatch(beforeUpdated, batch));
    }

    @Test
    void attributionRequestKeepsIndependentAuditTimeAndRejectsEarlierAudit() throws Exception {
        var request = mapper.readTree("""
            {"createdAt":"2026-10-03T01:02:04Z","updatedAt":"2026-10-03T01:02:04Z"}
            """);
        var later = new LegacyAttributionRequestRehearsal.AuditEvent("actor",
            Timestamp.from(Instant.parse("2026-10-03T09:02:04.125+08:00")));
        var earlier = new LegacyAttributionRequestRehearsal.AuditEvent("actor",
            Timestamp.from(Instant.parse("2026-10-03T01:02:03.999Z")));

        assertDoesNotThrow(() -> LegacyAttributionRequestRehearsal.requireAuditNotBeforeRequest(later, request));
        assertThrows(IllegalArgumentException.class,
            () -> LegacyAttributionRequestRehearsal.requireAuditNotBeforeRequest(earlier, request));
    }
}
