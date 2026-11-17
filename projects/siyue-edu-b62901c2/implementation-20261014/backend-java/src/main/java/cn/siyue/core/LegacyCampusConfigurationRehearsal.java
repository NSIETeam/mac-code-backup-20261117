package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Separate bounded, offline phase preserving campus configuration without inventing rooms. */
public final class LegacyCampusConfigurationRehearsal {
    private static final List<String> KINDS = List.of("subjects", "grades", "terms", "levels", "classTypes");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;

    public LegacyCampusConfigurationRehearsal(JdbcTemplate jdbc, PiiCipher cipher,
            PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyCampusConfigurationRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc = jdbc; this.cipher = cipher; this.transaction = phases;
    }

    /** Requires the exact catalog source checkpoint, and commits readiness only with every configuration row. */
    public int importConfiguration(JsonNode envelope) {
        return importConfiguration(envelope, null);
    }

    /** Candidate-only entry point; ordinary rehearsal calls remain restricted to *_test schemas. */
    int importConfiguration(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database, jdbc, envelope, authorization);
        require("siyue.legacy-business/v1".equals(envelope.path("schema").asText()), "快照格式不支持");
        require(envelope.path("revision").isIntegralNumber() && envelope.path("revision").longValue() >= 0, "快照版本无效");
        JsonNode state = envelope.path("state");
        int count = countRows(state);
        require(count < 999, "配置迁移须少于1000行，需拆分阶段");
        String hash = LegacyCatalogRehearsal.hash(state.toString());
        return transaction.execute(() -> importTransaction(envelope, state, hash, count));
    }

    private int importTransaction(JsonNode envelope, JsonNode state, String hash, int count) {
        long revision = envelope.path("revision").longValue();
        require(jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_catalog_v1' "
            + "AND source_revision=? AND source_hash=?", Integer.class, revision, hash) == 1, "须先导入同源目录");
        var previous = jdbc.query("SELECT source_hash,source_revision,imported_rows FROM core_migration_checkpoint "
            + "WHERE phase='campus_configuration_v1' FOR UPDATE", (rs, row) ->
                new Checkpoint(rs.getString(1), rs.getLong(2), rs.getInt(3)));
        if (!previous.isEmpty()) {
            Checkpoint old = previous.get(0);
            require(old.hash().equals(hash) && old.revision() == revision, "配置来源已变化，禁止覆盖");
            return old.rows();
        }
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
            + "VALUES('campus_configuration_v1',?,?,?,?)", revision, hash, cipher.encrypt(state.toString()), count);
        for (JsonNode campus : LegacyCatalogRehearsal.array(state, "campuses")) campus(campus);
        for (String kind : KINDS) dictionary(kind, values(state.path("dictionary"), kind));
        return count;
    }

    private record Checkpoint(String hash, long revision, int rows) { }

    private int countRows(JsonNode state) {
        int count = 1; // checkpoint
        for (JsonNode campus : LegacyCatalogRehearsal.array(state, "campuses")) {
            count = Math.addExact(count, 1 + values(campus, "roomDetails").size()
                + values(campus, "subjects").size() + values(campus, "grades").size());
        }
        for (String kind : KINDS) count = Math.addExact(count, values(state.path("dictionary"), kind).size());
        return count;
    }

    private void campus(JsonNode source) {
        String publicId = LegacyCatalogRehearsal.text(source, "id");
        var ids = jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND deleted=0 "
            + "AND configuration_ready=0 FOR UPDATE", (rs, row) -> rs.getLong(1), publicId);
        require(ids.size() == 1, "校区尚未迁移或已存在配置，禁止覆盖");
        long id = ids.get(0);
        JsonNode rooms = values(source, "roomDetails");
        if (source.has("rooms")) require(source.path("rooms").isIntegralNumber()
            && source.path("rooms").longValue() == rooms.size(), "教室计数与明细不一致，不能补造教室");
        int sequence = 0;
        for (JsonNode room : rooms) room(id, room, ++sequence);
        options(id, "subject", values(source, "subjects"));
        options(id, "grade", values(source, "grades"));
        jdbc.update("UPDATE core_campus SET address_cipher=?,phone_cipher=?,version=?,configuration_ready=1 WHERE id=?",
            encryptedOptional(source, "address"), encryptedOptional(source, "phone"),
            LegacyCatalogRehearsal.positive(source, "version"), id);
    }

    private void room(long campus, JsonNode room, int sequence) {
        long capacity = LegacyCatalogRehearsal.positive(room, "capacity");
        JsonNode virtual = room.path("virtualSeats");
        require(virtual.isMissingNode() || virtual.isIntegralNumber() && virtual.canConvertToInt()
            && virtual.intValue() >= 0 && virtual.intValue() <= 10000, "虚拟座位无效");
        require(capacity <= 10000, "教室容量超出范围");
        String name = LegacyCatalogRehearsal.text(room, "name");
        require(jdbc.queryForObject("SELECT COUNT(id) FROM core_campus_room WHERE campus_id=? AND name=?",
            Integer.class, campus, name) == 0, "历史教室名称重复");
        jdbc.update("INSERT INTO core_campus_room(public_id,campus_id,name,capacity,virtual_seats,sequence_no) VALUES(?,?,?,?,?,?)",
            LegacyCatalogRehearsal.text(room, "id"), campus, name, capacity, virtual.asInt(0), sequence);
    }

    private void options(long campus, String kind, JsonNode values) {
        int sequence = 0;
        for (JsonNode value : values) jdbc.update("INSERT INTO core_campus_academic_option(campus_id,kind,value,sequence_no) VALUES(?,?,?,?)",
            campus, kind, label(value), ++sequence);
    }

    private void dictionary(String kind, JsonNode values) {
        int sequence = 0;
        for (JsonNode value : values) jdbc.update("INSERT INTO core_academic_dictionary_value(kind,value,sequence_no) VALUES(?,?,?)",
            kind, label(value), ++sequence);
    }

    private String encryptedOptional(JsonNode source, String field) {
        JsonNode value = source.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        require(value.isTextual(), "校区联系字段格式无效");
        return cipher.encrypt(value.textValue());
    }

    private static JsonNode values(JsonNode source, String field) {
        JsonNode value = source.path(field);
        if (value.isMissingNode()) return JsonNodeFactory.instance.arrayNode();
        require(value.isArray(), "配置集合格式无效：" + field);
        return value;
    }

    private static String label(JsonNode value) {
        require(value.isTextual() && !value.textValue().isBlank(), "配置名称无效");
        return value.textValue(); // Preserve historical vocabulary exactly, including unknown labels.
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
