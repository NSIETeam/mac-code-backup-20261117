package cn.siyue.core;

import cn.siyue.core.LegacyIdentityMigrationDtos.DirectoryProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes trusted legacy directory facts within the already locked identity-bind transaction. */
final class LegacyIdentityDirectoryRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;

    /** Shares the binding connection, configured encryption keys and audit serializer. */
    LegacyIdentityDirectoryRepository(JdbcTemplate jdbc, PiiCipher cipher, ObjectMapper mapper) {
        this.jdbc = jdbc; this.cipher = cipher; this.mapper = mapper;
    }

    /** Preserves inactive historical archivers; no directory field can activate or grant an identity. */
    void bind(long principalId, DirectoryProfile profile, boolean active) {
        if (profile == null) { return; }
        validate(profile, active);
        Long archivedBy = archiver(profile.archivedBy());
        jdbc.update("UPDATE core_principal SET display_name_cipher=?,username_cipher=?,employee_no_cipher=?,archived_at=?,"
            + "archived_by_id=?,archive_reason_cipher=? WHERE id=? AND deleted=0",
            cipher.encrypt(profile.displayName()), cipher.encrypt(profile.username()), encrypted(profile.employeeNo()), timestamp(profile.archivedAt()),
            archivedBy, encrypted(profile.archiveReason()), principalId);
        if (profile.employeeNo() != null) {
            for (Map.Entry<String, byte[]> index : cipher.exactBlindIndexes("employee_no", profile.employeeNo()).entrySet()) {
                jdbc.update("INSERT INTO core_principal_employee_no_index(principal_id,key_id,value_hash) VALUES(?,?,?)",
                    principalId, index.getKey(), index.getValue());
            }
        }
    }

    /** Adds encrypted original directory evidence to the immutable access-change event. */
    String auditSnapshot(DirectoryProfile profile) throws JsonProcessingException {
        return profile == null ? null : cipher.encrypt(mapper.writeValueAsString(profile));
    }

    private void validate(DirectoryProfile profile, boolean active) {
        requireText(profile.displayName(), 120, false);
        requireText(profile.username(), 48, false);
        requireText(profile.employeeNo(), 40, true);
        requireText(profile.archiveReason(), 500, true);
        boolean archived = profile.archivedAt() != null;
        if (archived != (profile.archivedBy() != null) || archived && active
                || !archived && profile.archiveReason() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "员工归档信息与停用状态不一致");
        }
        timestamp(profile.archivedAt());
    }

    private Long archiver(String publicId) {
        if (publicId == null) { return null; }
        List<Long> ids = jdbc.query("SELECT id FROM core_principal WHERE public_id=? AND deleted=0",
            (rs, row) -> rs.getLong(1), publicId);
        if (ids.size() != 1) { throw new BusinessException(ErrorCode.INVALID_INPUT, "历史归档操作人未建立稳定身份映射"); }
        return ids.get(0);
    }

    private static void requireText(String value, int maximum, boolean optional) {
        if (value == null && optional) { return; }
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > maximum) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "迁移员工目录文本无效");
        }
    }

    private static Timestamp timestamp(String value) {
        if (value == null) { return null; }
        try { return Timestamp.from(Instant.parse(value)); }
        catch (DateTimeParseException error) { throw new BusinessException(ErrorCode.INVALID_INPUT, "员工归档时间无效"); }
    }

    private String encrypted(String value) { return value == null ? null : cipher.encrypt(value); }
}
