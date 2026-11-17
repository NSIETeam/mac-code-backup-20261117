package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.hash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Validation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Preserves separately captured auth-store feedback and its original audit chain during offline migration. */
public final class LegacyUiAnnotationRehearsal {
    private static final String PHASE = "auth_ui_annotations_v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;

    /** Creates an atomic importer for an isolated schema using the supplied encryption keyring. */
    public LegacyUiAnnotationRehearsal(JdbcTemplate jdbc, PiiCipher cipher, PlatformTransactionManager manager) {
        this(jdbc, cipher, LegacyMigrationPhaseTransactions.create(manager));
    }

    LegacyUiAnnotationRehearsal(JdbcTemplate jdbc, PiiCipher cipher, LegacyMigrationPhaseService transaction) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transaction = transaction;
    }

    /** Imports a captured feedback subset; the enclosing business revision is never treated as an auth revision.
     * @param envelope combined business snapshot with an independently hashed auth feedback subset
     * @return inserted feedback and audit row count, or the identical prior checkpoint count
     * @throws IllegalArgumentException when source provenance or target isolation is invalid
     */
    public int importAnnotations(JsonNode envelope) { return importAnnotations(envelope, null); }

    int importAnnotations(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database, jdbc, envelope, authorization);
        JsonNode payload = payload(envelope);
        List<ImportRow> rows = validate(payload);
        String source = payload.toString(), fingerprint = hash(source);
        long revision = envelope.path("revision").longValue();
        int count = rows.stream().mapToInt(row -> 1 + row.events().size()).sum();
        if (count >= 999) { throw invalid(); }
        return transaction.execute(() -> {
            var prior = jdbc.query("SELECT source_revision,source_hash,imported_rows FROM core_migration_checkpoint "
                + "WHERE phase=? AND deleted=0 FOR UPDATE", (rs, index) -> new Checkpoint(rs.getLong(1),
                    rs.getString(2), rs.getInt(3)), PHASE);
            if (!prior.isEmpty()) {
                Checkpoint old = prior.get(0);
                if (old.revision() != revision || !old.hash().equals(fingerprint)) { throw invalid(); }
                return old.rows();
            }
            jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,"
                + "imported_rows) VALUES(?,?,?,?,?)", PHASE, revision, fingerprint, cipher.encrypt(source), count);
            for (ImportRow row : rows) { insert(row); }
            return count;
        });
    }

    static JsonNode payload(JsonNode envelope) {
        if (!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())
                || !envelope.path("revision").isIntegralNumber() || envelope.path("revision").longValue() < 0) {
            throw invalid();
        }
        JsonNode source = envelope.path("state").path("authFeedback");
        if (source.isMissingNode()) {
            return JsonNodeFactory.instance.objectNode().put("schema", "siyue.legacy-auth-feedback/v1")
                .put("absentInSource", true).set("annotations", JsonNodeFactory.instance.arrayNode());
        }
        if (!source.isObject() || !source.path("authSourceSha256").asText().matches("[a-f0-9]{64}")
                || !source.path("businessSourceSha256").asText().matches("[a-f0-9]{64}")
                || !source.path("audit").isArray()) { throw invalid(); }
        source.fieldNames().forEachRemaining(name -> {
            if (!Set.of("schema", "authSourceSha256", "businessSourceSha256", "annotations", "audit")
                    .contains(name)) { throw invalid(); }
        });
        return source;
    }

    static List<ImportRow> validate(JsonNode payload) {
        if (!"siyue.legacy-auth-feedback/v1".equals(payload.path("schema").asText())
                || !payload.path("annotations").isArray()) { throw invalid(); }
        List<ImportRow> result = new ArrayList<>();
        Set<String> ids = new HashSet<>(), events = new HashSet<>();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (JsonNode row : payload.path("annotations")) {
                String id = uuid(row, "id");
                if (!ids.add(id)) { throw invalid(); }
                UiAnnotationDtos.Create request = request(row);
                if (!factory.getValidator().validate(request).isEmpty()) { throw invalid(); }
                if ("workflow".equals(request.category()) && (blank(request.workflowName())
                        || blank(request.flowDefinition()) || blank(request.desiredFlowDefinition()))) { throw invalid(); }
                List<JsonNode> chain = new ArrayList<>();
                for (JsonNode event : payload.path("audit")) {
                    if (id.equals(event.path("targetId").asText())) { chain.add(event); }
                }
                chain.sort(Comparator.comparing(event -> time(event, "at")));
                validateChain(row, chain, events);
                result.add(new ImportRow(row, request, chain));
            }
        }
        if (events.size() != payload.path("audit").size()) { throw invalid(); }
        return result;
    }

    private static void validateChain(JsonNode row, List<JsonNode> chain, Set<String> events) {
        if (chain.isEmpty() || !Set.of("open", "resolved").contains(row.path("status").asText())) { throw invalid(); }
        String status = "open";
        for (int index = 0; index < chain.size(); index++) {
            JsonNode event = chain.get(index);
            if (!events.add(uuid(event, "id")) || !"success".equals(event.path("outcome").asText())) { throw invalid(); }
            uuid(event, "requestId");
            required(event, "actorId");
            String expected = index == 0 ? "ui.annotation.create" : "ui.annotation.update";
            if (!expected.equals(event.path("action").asText())) { throw invalid(); }
            if (index == 0) {
                if (!required(row, "createdBy").equals(required(event, "actorId"))
                        || !required(row, "page").equals(event.path("details").path("page").asText())
                        || !required(row, "elementKind").equals(event.path("details").path("elementKind").asText())) {
                    throw invalid();
                }
                near(time(row, "createdAt"), time(event, "at"));
            } else {
                status = event.path("details").path("status").asText();
                if (!Set.of("open", "resolved").contains(status)) { throw invalid(); }
            }
        }
        if (!status.equals(row.path("status").asText()) || time(row, "updatedAt").isBefore(time(row, "createdAt"))) {
            throw invalid();
        }
        near(time(row, "updatedAt"), time(chain.get(chain.size() - 1), "at"));
    }

    private void insert(ImportRow item) {
        JsonNode row = item.source();
        String id = required(row, "id");
        jdbc.update("INSERT INTO core_ui_annotation(public_id,category,status,payload_cipher,created_by,version,"
            + "created_at,updated_at,created_by_name_cipher) VALUES(?,?,?,?,?,?,?,?,?)", id, item.request().category(), required(row, "status"),
            cipher.encrypt(json(item.request())), principal(required(row, "createdBy")), item.events().size(),
            Timestamp.from(time(row, "createdAt")), Timestamp.from(time(row, "updatedAt")),
            cipher.encrypt(required(row, "createdByName")));
        for (JsonNode event : item.events()) {
            jdbc.update("INSERT INTO core_audit(actor_id,action,resource_public_id,trace_id,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?)", principal(required(event, "actorId")), required(event, "action"), id,
                required(event, "requestId"), Timestamp.from(time(event, "at")), Timestamp.from(time(event, "at")));
        }
    }

    private long principal(String id) {
        var rows = jdbc.query("SELECT id FROM core_principal WHERE public_id=? AND deleted=0",
            (rs, index) -> rs.getLong(1), id);
        if (rows.size() != 1) { throw invalid(); }
        return rows.get(0);
    }

    private static UiAnnotationDtos.Create request(JsonNode row) {
        return new UiAnnotationDtos.Create(required(row, "page"), required(row, "pageTitle"),
            required(row, "elementKind"), required(row, "elementLabel"), required(row, "selector"),
            required(row, "issue"), required(row, "category"), row.path("workflowName").asText(""),
            row.path("flowDefinition").asText(""), row.path("desiredFlowDefinition").asText(""));
    }

    private static String required(JsonNode row, String key) {
        if (!row.path(key).isTextual() || row.path(key).asText().isBlank()) { throw invalid(); }
        return row.path(key).asText();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static Instant time(JsonNode row, String key) { return Instant.parse(required(row, key)); }
    private static String uuid(JsonNode row, String key) {
        String value = required(row, key);
        if (!UUID.fromString(value).toString().equals(value)) { throw invalid(); }
        return value;
    }
    private static void near(Instant source, Instant audit) {
        if (Math.abs(java.time.Duration.between(source, audit).toMillis()) > 1000) { throw invalid(); }
    }
    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("界面反馈迁移来源或审计链无效"); }
    record ImportRow(JsonNode source, UiAnnotationDtos.Create request, List<JsonNode> events) { }
    private record Checkpoint(long revision, String hash, int rows) { }
}
