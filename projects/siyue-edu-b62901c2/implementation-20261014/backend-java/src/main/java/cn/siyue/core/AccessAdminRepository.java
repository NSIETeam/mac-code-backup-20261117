package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.CreateInput;
import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.AccessAdminDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Executes access updates under a singleton administrative lock and records immutable history. */
@Repository
public class AccessAdminRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Uses the shared transaction-bound connection for grants and audit history. */
    public AccessAdminRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Serializes administrative grant changes so two administrators cannot race. */
    public void lockAdminChanges() {
        jdbc.queryForObject("SELECT id FROM core_access_admin_lock WHERE singleton=1 AND deleted=0 FOR UPDATE",
            Long.class);
    }

    /** Lists employee access with stable IDs and no OIDC subject disclosure. */
    public List<PrincipalView> list(int page, int size) {
        List<PrincipalSummary> rows = jdbc.query("""
            SELECT public_id,scope_type,active,version,credential_change_required,id FROM core_principal WHERE deleted=0
            ORDER BY public_id LIMIT ? OFFSET ?
            """, (rs, row) -> new PrincipalSummary(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("scope_type"), rs.getBoolean("active"), rs.getLong("version"),
                rs.getBoolean("credential_change_required")), size,
            (page - 1) * size);
        List<PrincipalView> result = new ArrayList<>();
        for (PrincipalSummary summary : rows) { result.add(view(summary)); }
        return List.copyOf(result);
    }

    /** Creates a principal mapping and its grants as one auditable transaction. */
    public PrincipalView create(Actor actor, CreateInput input) {
        validateCampuses(input.campusIds());
        try {
            jdbc.update("""
                INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active)
                VALUES(?,?,?,?,TRUE)
                """, input.principalId(), input.issuer(), input.subject(), input.scope());
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "员工编号或外部身份已绑定");
        }
        long principalId = jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",
            Long.class, input.principalId());
        replacePermissions(principalId, input.permissionCodes());
        replaceDeniedPermissions(principalId, input.deniedPermissionCodes());
        replaceCampuses(principalId, input.campusIds());
        PrincipalView created = current(principalId);
        record(actor, principalId, "created", null, created);
        return created;
    }

    /** Updates one principal after expected-version validation; external identity is immutable. */
    public PrincipalView update(Actor actor, String principalId, UpdateInput input) {
        long targetId = lockTarget(principalId);
        PrincipalView before = current(targetId);
        validateCampuses(input.campusIds());
        int changed = jdbc.update("""
            UPDATE core_principal SET scope_type=?,active=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, input.scope(), input.active(), targetId, input.expectedVersion());
        if (changed != 1) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "员工授权版本已变化，请刷新后重试");
        }
        replacePermissions(targetId, input.permissionCodes());
        replaceDeniedPermissions(targetId, input.deniedPermissionCodes());
        replaceCampuses(targetId, input.campusIds());
        PrincipalView updated = current(targetId);
        record(actor, targetId, "updated", before, updated);
        return updated;
    }

    /** Reads exactly one active or inactive, non-deleted principal. */
    public PrincipalView current(String principalId) { return current(lockTarget(principalId)); }

    private PrincipalView current(long id) {
        List<PrincipalSummary> rows = jdbc.query("""
            SELECT id,public_id,scope_type,active,version,credential_change_required
            FROM core_principal WHERE id=? AND deleted=0
            """, (rs, row) -> new PrincipalSummary(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("scope_type"), rs.getBoolean("active"), rs.getLong("version"),
                rs.getBoolean("credential_change_required")), id);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "员工账号不存在"); }
        return view(rows.get(0));
    }

    private PrincipalView view(PrincipalSummary principal) {
        List<String> permissions = jdbc.queryForList("""
            SELECT permission_code FROM core_principal_permission
            WHERE principal_id=? AND deleted=0 ORDER BY permission_code
            """, String.class, principal.id());
        List<String> deniedPermissions = jdbc.queryForList("""
            SELECT permission_code FROM core_principal_denied_permission
            WHERE principal_id=? AND deleted=0 ORDER BY permission_code
            """, String.class, principal.id());
        List<String> effectivePermissions = jdbc.queryForList("""
            SELECT permission_code FROM (
                SELECT permission_code FROM core_principal_permission WHERE principal_id=? AND deleted=0
                UNION
                SELECT pp.permission_code FROM core_principal_position link
                JOIN core_access_position position ON position.id=link.position_id
                  AND position.deleted=0 AND position.active=1
                JOIN core_access_position_permission pp ON pp.position_id=position.id AND pp.deleted=0
                WHERE link.principal_id=? AND link.deleted=0
            ) granted
            WHERE permission_code NOT IN (SELECT permission_code FROM core_principal_denied_permission
                WHERE principal_id=? AND deleted=0)
            ORDER BY permission_code
            """, String.class, principal.id(), principal.id(), principal.id());
        List<String> campuses = jdbc.queryForList("""
            SELECT c.public_id FROM core_principal_campus pc JOIN core_campus c
              ON c.id=pc.campus_id AND c.deleted=0
            WHERE pc.principal_id=? AND pc.deleted=0 ORDER BY c.public_id
            """, String.class, principal.id());
        return new PrincipalView(principal.publicId(), principal.scope(), principal.active(),
            principal.version(), principal.credentialChangeRequired(), permissions, effectivePermissions,
            deniedPermissions, campuses);
    }

    private long lockTarget(String principalId) {
        List<Long> rows = jdbc.query("""
            SELECT id FROM core_principal WHERE public_id=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> rs.getLong(1), principalId);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "员工账号不存在"); }
        return rows.get(0);
    }

    private void validateCampuses(List<String> campusIds) {
        if (campusIds.isEmpty()) { return; }
        String marks = String.join(",", java.util.Collections.nCopies(campusIds.size(), "?"));
        List<Object> args = new ArrayList<>(campusIds);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM core_campus WHERE deleted=0 AND active=1"
            + " AND public_id IN (" + marks + ")", Integer.class, args.toArray());
        if (count == null || count != campusIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "校区列表含无效或重复编号");
        }
    }

    private void replacePermissions(long principalId, List<String> permissions) {
        jdbc.update("UPDATE core_principal_permission SET deleted=TRUE,updated_at=UTC_TIMESTAMP(3)"
            + " WHERE principal_id=? AND deleted=0", principalId);
        for (String permission : permissions) {
            jdbc.update("""
                INSERT INTO core_principal_permission(principal_id,permission_code,deleted)
                VALUES(?,?,FALSE) ON DUPLICATE KEY UPDATE deleted=FALSE,updated_at=UTC_TIMESTAMP(3)
                """, principalId, permission);
        }
    }

    private void replaceCampuses(long principalId, List<String> campusIds) {
        jdbc.update("UPDATE core_principal_campus SET deleted=TRUE,updated_at=UTC_TIMESTAMP(3)"
            + " WHERE principal_id=? AND deleted=0", principalId);
        for (String campusId : campusIds) {
            jdbc.update("""
                INSERT INTO core_principal_campus(principal_id,campus_id,deleted)
                SELECT ?,id,FALSE FROM core_campus WHERE public_id=? AND deleted=0 AND active=1
                ON DUPLICATE KEY UPDATE deleted=FALSE,updated_at=UTC_TIMESTAMP(3)
                """, principalId, campusId);
        }
    }

    private void replaceDeniedPermissions(long principalId, List<String> permissions) {
        jdbc.update("UPDATE core_principal_denied_permission SET deleted=TRUE,updated_at=UTC_TIMESTAMP(3)"
            + " WHERE principal_id=? AND deleted=0", principalId);
        for (String permission : permissions) {
            jdbc.update("""
                INSERT INTO core_principal_denied_permission(principal_id,permission_code,deleted)
                VALUES(?,?,FALSE) ON DUPLICATE KEY UPDATE deleted=FALSE,updated_at=UTC_TIMESTAMP(3)
                """, principalId, permission);
        }
    }

    private void record(Actor actor, long targetId, String action, PrincipalView before,
            PrincipalView after) {
        try {
            jdbc.update("""
                INSERT INTO core_access_change_event(public_id,actor_id,target_id,action_type,before_state,after_state)
                VALUES(?,?,?,?,?,?)
                """, "ACCESS_" + UUID.randomUUID(), actor.id(), targetId, action,
                before == null ? "{}" : mapper.writeValueAsString(before), mapper.writeValueAsString(after));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Access audit event could not be serialized", error);
        }
    }

    private record PrincipalSummary(long id, String publicId, String scope,
                                    boolean active, long version, boolean credentialChangeRequired) { }
}
