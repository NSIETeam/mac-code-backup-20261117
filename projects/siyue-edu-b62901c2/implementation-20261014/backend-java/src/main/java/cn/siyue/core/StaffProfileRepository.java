package cn.siyue.core;

import cn.siyue.core.StaffProfileDtos.Profile;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores employee profile fields without changing principal permissions, scope or enabled state. */
@Repository
public class StaffProfileRepository {
    private static final List<String> FIELDS = List.of("phone", "employmentStart", "employmentEnd", "title", "note", "campusId");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    public StaffProfileRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public Profile get(String employeeId, boolean lock) {
        long principalId = principal(employeeId, lock);
        List<Profile> rows = jdbc.query("""
            SELECT sp.phone_cipher,sp.employment_start,sp.employment_end,sp.title_cipher,sp.note_cipher,
                c.public_id AS campus_public_id,c.name_cipher AS campus_name_cipher,sp.version
            FROM core_staff_profile sp LEFT JOIN core_campus c ON c.id=sp.campus_id
            WHERE sp.principal_id=? AND sp.deleted=0
            """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Profile(employeeId,
                decrypt(rs.getString("phone_cipher")), date(rs.getDate("employment_start")),
                date(rs.getDate("employment_end")), decrypt(rs.getString("title_cipher")),
                decrypt(rs.getString("note_cipher")), rs.getString("campus_public_id"),
                decrypt(rs.getString("campus_name_cipher")),
                rs.getLong("version")), principalId);
        return rows.isEmpty() ? new Profile(employeeId, null, null, null, null, null, null, null, 0) : rows.get(0);
    }

    public void ensureEditable(String employeeId) {
        requireNotArchived(principal(employeeId, true));
    }

    public Profile update(String employeeId, JsonNode patch, JsonNode expectedVersion) {
        long principalId = principal(employeeId, true);
        requireNotArchived(principalId);
        Profile before = get(employeeId, false);
        validateExpected(expectedVersion, before.version());
        validatePatch(patch);
        String phone = patchedPhone(patch, before.phone());
        String start = patchedDay(patch, "employmentStart", before.employmentStart());
        String end = patchedDay(patch, "employmentEnd", before.employmentEnd());
        validateEmploymentDates(start, end);
        String title = patchedText(patch, "title", before.title(), 100);
        String note = patchedText(patch, "note", before.note(), 5000);
        Long campusId = patchedCampus(patch, before.campusId());
        if (before.version() == 0) {
            jdbc.update("""
                INSERT INTO core_staff_profile(principal_id,name_cipher,username_cipher,phone_cipher,employment_start,
                    employment_end,title_cipher,note_cipher,campus_id,version)
                SELECT id,display_name_cipher,username_cipher,?,?,?,?,?,?,1 FROM core_principal WHERE id=?
                """, encrypt(phone), start, end, encrypt(title), encrypt(note), campusId, principalId);
        } else {
            int changed = jdbc.update("""
                UPDATE core_staff_profile sp JOIN core_principal p ON p.id=sp.principal_id
                SET sp.name_cipher=p.display_name_cipher,sp.username_cipher=p.username_cipher,sp.phone_cipher=?,
                    sp.employment_start=?,sp.employment_end=?,sp.title_cipher=?,sp.note_cipher=?,sp.campus_id=?,
                    sp.version=sp.version+1,sp.updated_at=UTC_TIMESTAMP(3)
                WHERE sp.principal_id=? AND sp.version=? AND sp.deleted=0
                """, encrypt(phone), start, end, encrypt(title), encrypt(note), campusId, principalId, before.version());
            if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "职工档案已更新，请刷新后重试"); }
        }
        return get(employeeId, false);
    }

    private static void validateExpected(JsonNode expectedVersion, long currentVersion) {
        if (expectedVersion != null && (!expectedVersion.isIntegralNumber() || expectedVersion.longValue() != currentVersion)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "职工档案已更新，请刷新后重试");
        }
    }

    private static void validatePatch(JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) { invalid("没有修改字段"); }
        patch.fieldNames().forEachRemaining(key -> { if (!FIELDS.contains(key)) { invalid("档案包含不支持的字段"); } });
    }

    private static String patchedPhone(JsonNode patch, String previous) {
        return patch.has("phone") ? phone(value(patch.get("phone"), "phone", 50, false)) : previous;
    }

    private static String patchedDay(JsonNode patch, String field, String previous) {
        return patch.has(field) ? day(patch.get(field), field) : previous;
    }

    private static String patchedText(JsonNode patch, String field, String previous, int max) {
        return patch.has(field) ? value(patch.get(field), field, max, true) : previous;
    }

    private Long patchedCampus(JsonNode patch, String previous) {
        if (patch.has("campusId")) { return campus(patch.get("campusId")); }
        return previous == null ? null : existingCampusId(previous);
    }

    private static void validateEmploymentDates(String start, String end) {
        if (end != null && (start == null || end.compareTo(start) < 0)) { invalid("离职日期不能早于入职日期"); }
    }

    private long principal(String employeeId, boolean lock) {
        List<Long> ids = jdbc.query("SELECT id FROM core_principal WHERE public_id=? AND deleted=0" +
            (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), employeeId);
        if (ids.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "职工必须对应真实员工账号"); }
        return ids.get(0);
    }

    private void requireNotArchived(long principalId) {
        List<Boolean> archived = jdbc.query("SELECT archived_at IS NOT NULL FROM core_principal WHERE id=?",
            (rs, row) -> rs.getBoolean(1), principalId);
        if (archived.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "职工必须对应真实员工账号"); }
        if (archived.get(0)) { throw new BusinessException(ErrorCode.FORBIDDEN, "已归档员工档案不可修改"); }
    }

    private Long campusId(String publicId) {
        List<CampusRow> rows = jdbc.query("SELECT id,active FROM core_campus WHERE public_id=? AND deleted=0",
            (rs, row) -> new CampusRow(rs.getLong("id"), rs.getBoolean("active")), publicId);
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "档案校区不存在"); }
        if (!rows.get(0).active()) { invalid("校区已停用"); }
        return rows.get(0).id();
    }

    private Long existingCampusId(String publicId) {
        List<Long> ids = jdbc.query("SELECT id FROM core_campus WHERE public_id=?",
            (rs, row) -> rs.getLong(1), publicId);
        return ids.size() == 1 ? ids.get(0) : null;
    }

    private record CampusRow(long id, boolean active) { }

    private Long campus(JsonNode node) {
        if (node == null || node.isNull()) { return null; }
        if (!node.isTextual()) { invalid("档案校区编号无效"); }
        if (node.asText().isBlank()) { return null; }
        return campusId(node.asText().trim());
    }

    private static String value(JsonNode node, String field, int max, boolean optional) {
        if (node == null || node.isNull()) {
            if (optional) { return ""; }
            invalid(field + "必须是文字");
        }
        if (!node.isTextual()) { invalid(field + "必须是文字"); }
        String result = java.text.Normalizer.normalize(node.asText(), java.text.Normalizer.Form.NFKC).trim();
        if ((!optional && result.isEmpty()) || result.length() > max) { invalid(field + "长度无效"); }
        return result;
    }

    private static String phone(String value) {
        String normalized = value.replaceAll("[\\s()-]", "").replaceFirst("^(?:\\+86|0086)(?=1\\d{10}$)", "");
        if (!normalized.matches("1[3-9]\\d{9}")) { invalid("请填写正确的11位手机号"); }
        return normalized;
    }

    private static String day(JsonNode node, String field) {
        if (node == null || node.isNull() || node.asText().isEmpty()) { return null; }
        if (!node.isTextual()) { invalid(field + "无效"); }
        try {
            LocalDate parsed = LocalDate.parse(node.asText());
            if (!parsed.toString().equals(node.asText())) { invalid(field + "无效"); }
            return parsed.toString();
        } catch (DateTimeParseException exception) { throw new BusinessException(ErrorCode.INVALID_INPUT, field + "无效"); }
    }

    private String encrypt(String value) { return value == null ? null : cipher.encrypt(value); }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private static String date(java.sql.Date value) { return value == null ? null : value.toLocalDate().toString(); }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
