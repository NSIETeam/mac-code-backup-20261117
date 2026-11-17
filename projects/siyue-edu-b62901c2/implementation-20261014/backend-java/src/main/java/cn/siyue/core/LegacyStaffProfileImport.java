package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Preserves original employee business profiles inside the bounded catalog phase, without changing account access. */
final class LegacyStaffProfileImport {
    private static final Set<String> FIELDS = Set.of("id", "createdAt", "updatedAt", "version", "name", "username",
        "employmentStart", "employmentEnd", "phone", "title", "note", "campusId", "campus", "history");
    private LegacyStaffProfileImport() { }

    /** Older synthetic fixtures may omit this collection; an explicitly malformed collection is rejected. */
    static JsonNode rows(JsonNode state) {
        return state.has("staff") ? LegacyCatalogRehearsal.array(state, "staff")
            : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    /** Inserts each original row once; missing principals or campuses reject the encompassing catalog transaction. */
    static void insert(JdbcTemplate jdbc, PiiCipher cipher, JsonNode row) {
        row.fieldNames().forEachRemaining(key -> {
            if (!FIELDS.contains(key)) { throw new IllegalArgumentException("历史员工档案含未映射字段"); }
        });
        String principal = LegacyCatalogRehearsal.text(row, "id");
        Date start = date(row, "employmentStart"), end = date(row, "employmentEnd");
        if (end != null && (start == null || end.before(start))) {
            throw new IllegalArgumentException("历史员工入离职日期不一致");
        }
        Long campus = optional(row, "campusId", 120) == null ? null
            : id(jdbc, "core_campus", optional(row, "campusId", 120));
        jdbc.update("INSERT INTO core_staff_profile(principal_id,phone_cipher,employment_start,employment_end,"
            + "title_cipher,note_cipher,campus_id,version,created_at,updated_at,name_cipher,username_cipher,source_profile_cipher) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", id(jdbc, "core_principal", principal),
            encrypted(cipher, optional(row, "phone", 80)), start, end, encrypted(cipher, optional(row, "title", 100)),
            encrypted(cipher, optional(row, "note", 5000)), campus, LegacyCatalogRehearsal.positive(row, "version"),
            LegacyCatalogRehearsal.date(row, "createdAt"), LegacyCatalogRehearsal.date(row, "updatedAt"),
            cipher.encrypt(LegacyCatalogRehearsal.text(row, "name")),
            cipher.encrypt(LegacyCatalogRehearsal.text(row, "username")), cipher.encrypt(row.toString()));
    }

    private static long id(JdbcTemplate jdbc, String table, String publicId) {
        if (!Set.of("core_principal", "core_campus").contains(table)) { throw new IllegalArgumentException("员工档案关联类型无效"); }
        var ids = jdbc.query("SELECT id FROM " + table + " WHERE public_id=? AND deleted=0",
            (rs, index) -> rs.getLong(1), publicId);
        if (ids.size() != 1) { throw new IllegalArgumentException("历史员工档案关联身份或校区未映射"); }
        return ids.get(0);
    }

    private static String optional(JsonNode row, String field, int maximum) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) { return null; }
        if (!value.isTextual() || value.textValue().length() > maximum) {
            throw new IllegalArgumentException("历史员工档案文本字段无效");
        }
        return value.textValue().isEmpty() ? null : value.textValue();
    }

    private static Date date(JsonNode row, String field) {
        String value = optional(row, field, 10);
        return value == null ? null : Date.valueOf(LocalDate.parse(value));
    }
    private static String encrypted(PiiCipher cipher, String value) { return value == null ? null : cipher.encrypt(value); }
}
