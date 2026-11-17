package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Offline, exact-source import of typed settings into an independent rehearsal database. */
public final class LegacySystemSettingsRehearsal {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;
    public LegacySystemSettingsRehearsal(JdbcTemplate jdbc, PiiCipher cipher, PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacySystemSettingsRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc = jdbc; this.cipher = cipher; this.transaction = phases;
    }
    public int importSettings(JsonNode envelope) {
        return importSettings(envelope, null);
    }

    /** Candidate-only entry point; ordinary rehearsal calls remain restricted to *_test schemas. */
    int importSettings(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database, jdbc, envelope, authorization);
        require("siyue.legacy-business/v1".equals(envelope.path("schema").asText()), "快照格式不支持");
        require(envelope.path("revision").isIntegralNumber() && envelope.path("revision").longValue() >= 0, "快照版本无效");
        JsonNode state = envelope.path("state"), settings = state.path("settings");
        require(settings.isObject(), "设置数据缺失");
        Map<String, Map<String, Object>> clean = parse(settings);
        int count = clean.values().stream().mapToInt(Map::size).sum();
        require(count < 1000, "设置迁移阶段超过单事务行数限制");
        String hash = LegacyCatalogRehearsal.hash(state.toString());
        return transaction.execute(() -> write(envelope, clean, hash, count));
    }
    private Map<String, Map<String, Object>> parse(JsonNode settings) {
        Map<String, Map<String, Object>> clean = new LinkedHashMap<>();
        settings.fieldNames().forEachRemaining(section -> {
            if (section.equals("updatedAt") || section.equals("updatedBy")) { return; }
            require(SystemSettingsService.FIELDS.containsKey(section) && settings.path(section).isObject(), "未知设置分类");
            Map<String, Object> values = new LinkedHashMap<>();
            settings.path(section).fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                Object typed = value.isTextual() ? value.textValue() : value.isBoolean() ? value.booleanValue()
                    : value.isIntegralNumber() ? value.longValue() : null;
                require(typed != null, "设置字段类型不支持"); values.put(field.getKey(), typed);
            });
            if (!values.isEmpty()) { clean.put(section, SystemSettingsService.validate(new SystemSettingsService.Input(section, values)).values()); }
        });
        return clean;
    }
    private int write(JsonNode envelope, Map<String, Map<String, Object>> clean, String hash, int count) {
        long revision = envelope.path("revision").longValue();
        require(jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_catalog_v1' AND source_revision=? AND source_hash=?",
            Integer.class, revision, hash) == 1, "须先导入同源目录");
        var old = jdbc.query("SELECT source_hash,source_revision,imported_rows FROM core_migration_checkpoint WHERE phase='system_settings_v1' FOR UPDATE",
            (rs, row) -> new Checkpoint(rs.getString(1), rs.getLong(2), rs.getInt(3)));
        if (!old.isEmpty()) {
            require(old.get(0).hash().equals(hash) && old.get(0).revision() == revision, "配置来源已变化，禁止覆盖");
            return old.get(0).count();
        }
        require(jdbc.queryForObject("SELECT COUNT(id) FROM core_system_setting", Integer.class) == 0, "不能覆盖已有设置");
        for (var section : clean.entrySet()) {
            for (var field : section.getValue().entrySet()) {
                Object value = field.getValue();
                String type = value instanceof Boolean ? "boolean" : value instanceof Number ? "integer" : "text";
                jdbc.update("INSERT INTO core_system_setting(section,field_name,value_type,value_cipher) VALUES(?,?,?,?)",
                    section.getKey(), field.getKey(), type, cipher.encrypt(value.toString()));
            }
        }
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) VALUES('system_settings_v1',?,?,?,?)",
            revision, hash, cipher.encrypt(envelope.path("state").path("settings").toString()), count);
        return count;
    }
    private record Checkpoint(String hash, long revision, int count) { }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
