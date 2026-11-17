package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.LegacyIdentityMigrationDtos.BindInput;
import cn.siyue.core.LegacyIdentityMigrationDtos.PrepareInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Binds only the pre-created disabled rehearsal placeholder while retaining its stable public ID. */
@Repository
public class LegacyIdentityMigrationRepository {
    private final JdbcTemplate jdbc;
    private final AccessAdminRepository accessAdmin;
    private final AccessPositionRepository positions;
    private final ObjectMapper mapper;
    private final LegacyIdentityDirectoryRepository directory;

    /**
     * Shares the administration lock, position writer and encrypted directory persistence with binding.
     * @param jdbc JDBC template participating in the caller's transaction
     * @param accessAdmin current principal views and administration serialization
     * @param positions checked position membership persistence
     * @param mapper serializer for immutable migration and access-event evidence
     * @param cipher configured PII encryption and exact employee-number blind-index keys
     */
    public LegacyIdentityMigrationRepository(JdbcTemplate jdbc, AccessAdminRepository accessAdmin,
            AccessPositionRepository positions, ObjectMapper mapper, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.accessAdmin = accessAdmin;
        this.positions = positions;
        this.mapper = mapper;
        this.directory = new LegacyIdentityDirectoryRepository(jdbc, cipher, mapper);
    }

    /** Serializes legacy binding with ordinary access administration. */
    public void lockAdministration() { accessAdmin.lockAdminChanges(); }

    /** Confirms the target grant template exactly matches its trusted legacy role manifest entry. */
    public void validateLegacyPosition(String positionId, String name, String description, List<String> permissions) {
        List<PositionManifestRow> rows = jdbc.query("SELECT id,name,description FROM core_access_position "
            + "WHERE public_id=? AND deleted=FALSE AND active=TRUE", (rs, row) -> new PositionManifestRow(
                rs.getLong("id"), rs.getString("name"), rs.getString("description")), positionId);
        if (rows.size() != 1 || !rows.get(0).name().equals(name)
                || !rows.get(0).description().equals(description)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标职位与可信旧角色清单不一致");
        }
        List<String> actual = jdbc.queryForList("SELECT permission_code FROM core_access_position_permission "
            + "WHERE position_id=? AND deleted=FALSE ORDER BY permission_code", String.class, rows.get(0).id());
        if (!actual.equals(permissions.stream().sorted().toList())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标职位权限与可信旧角色清单不一致");
        }
    }

    /**
     * Verifies the current position classification against the explicit source role.
     * @param positionId trusted target position identifier
     * @param roleId exact legacy role identifier; only teacher denotes teaching staff
     * @param declaredKind optional old-manifest classification, otherwise inferred from its exact role
     * @throws BusinessException if either the manifest or current position has a different classification
     */
    public void validateLegacyDirectoryKind(String positionId, String roleId, String declaredKind) {
        String expected = "teacher".equals(roleId) ? "teacher" : "staff";
        if (declaredKind != null && !expected.equals(declaredKind)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "旧岗位目录分类与角色不一致");
        }
        String actual = jdbc.queryForObject("SELECT directory_kind FROM core_access_position WHERE public_id=? AND deleted=0",
            String.class, positionId);
        if (!expected.equals(actual)) { throw new BusinessException(ErrorCode.FORBIDDEN, "目标岗位目录分类不一致"); }
    }

    /** Creates a missing disabled placeholder or audits an exact existing one without replacing it. */
    public PrincipalView prepare(Actor actor, String principalId, PrepareInput input,
            String placeholderIssuer, String entrySha256) throws JsonProcessingException {
        if (!principalId.equals(input.legacyUserId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "稳定身份编号与路径不一致");
        }
        List<PlaceholderCheckpoint> checkpoints = jdbc.query("""
            SELECT source_sha256,manifest_entry_sha256 FROM core_legacy_identity_placeholder_checkpoint
            WHERE legacy_user_id=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> new PlaceholderCheckpoint(rs.getString("source_sha256"),
                rs.getString("manifest_entry_sha256")), principalId);
        if (!checkpoints.isEmpty()) {
            PlaceholderCheckpoint old = checkpoints.get(0);
            if (!input.sourceSha256().equals(old.sourceSha256()) || !entrySha256.equals(old.entrySha256())) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "稳定身份占位已由不同可信来源登记");
            }
            return accessAdmin.current(principalId);
        }
        Placeholder existing = findAnyPlaceholder(principalId);
        boolean created = existing == null;
        if (created) {
            ensurePlaceholderIdentityAvailable(placeholderIssuer, principalId);
            jdbc.update("""
                INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active)
                VALUES(?,?,?,'own',FALSE)
                """, principalId, placeholderIssuer, principalId);
            existing = findAnyPlaceholder(principalId);
        }
        validatePlaceholder(existing, principalId, placeholderIssuer);
        PrincipalView view = accessAdmin.current(principalId);
        ensureEmptyGrants(existing.id(), view);
        jdbc.update("""
            INSERT INTO core_legacy_identity_placeholder_checkpoint
                (legacy_user_id,principal_id,source_sha256,manifest_entry_sha256,actor_id)
            VALUES(?,?,?,?,?)
            """, principalId, existing.id(), input.sourceSha256(), entrySha256, actor.id());
        AccessSnapshot snapshot = new AccessSnapshot(existing.issuer(), existing.subject(), existing.scope(),
            existing.active(), existing.version(), view.permissionCodes(), view.effectivePermissionCodes(),
            view.deniedPermissionCodes(), view.campusIds(), List.of(), List.of(), "", "", List.of(), List.of(),
            List.of(), List.of(), true, null);
        recordAccessChange(actor, existing.id(), new AccessSnapshot("", "", "", false, 0,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", List.of(), List.of(),
            List.of(), List.of(), false, null),
            snapshot, created ? "created" : "updated");
        return view;
    }

    private Placeholder findAnyPlaceholder(String principalId) {
        List<Placeholder> rows = jdbc.query("""
            SELECT id,public_id,oidc_issuer,oidc_subject,scope_type,active,version,deleted
            FROM core_principal WHERE public_id=? FOR UPDATE
            """, (rs, row) -> new Placeholder(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("oidc_issuer"), rs.getString("oidc_subject"), rs.getString("scope_type"),
                rs.getBoolean("active"), rs.getLong("version"), rs.getBoolean("deleted")), principalId);
        if (rows.size() > 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "稳定身份编号重复"); }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ensurePlaceholderIdentityAvailable(String issuer, String subject) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM core_principal "
            + "WHERE oidc_issuer=? AND oidc_subject=? AND deleted=0", Integer.class, issuer, subject);
        if (count == null || count != 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "占位外部身份已被其他账号占用");
        }
    }

    private void validatePlaceholder(Placeholder row, String principalId, String placeholderIssuer) {
        if (row == null || row.deleted() || !row.publicId().equals(principalId) || row.active()
                || !row.issuer().equals(placeholderIssuer) || !row.subject().equals(principalId)
                || !"own".equals(row.scope()) || row.version() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "现有账号不符合禁用占位身份要求");
        }
    }

    private void ensureEmptyGrants(long principalId, PrincipalView view) {
        if (!view.permissionCodes().isEmpty() || !view.campusIds().isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "占位账号不得预置权限或校区范围");
        }
        Integer permissionRows = jdbc.queryForObject("SELECT COUNT(*) FROM core_principal_permission "
            + "WHERE principal_id=? AND deleted=0", Integer.class, principalId);
        if (permissionRows == null || permissionRows != 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "占位账号存在未登记权限");
        }
        Integer denyRows = jdbc.queryForObject("SELECT COUNT(*) FROM core_principal_denied_permission "
            + "WHERE principal_id=? AND deleted=0", Integer.class, principalId);
        Integer positionRows = jdbc.queryForObject("SELECT COUNT(*) FROM core_principal_position "
            + "WHERE principal_id=? AND deleted=0", Integer.class, principalId);
        if (denyRows == null || denyRows != 0 || positionRows == null || positionRows != 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "占位账号存在未登记的拒权或职位关联");
        }
    }

    /** Applies a checked bind and immutable source checkpoint in the caller's transaction. */
    public PrincipalView bind(Actor actor, String principalId, BindInput input, String placeholderIssuer,
            String requestSha256) throws JsonProcessingException {
        validateBindingRequest(principalId, input);
        PrincipalView replay = findReplay(principalId, input, requestSha256);
        if (replay != null) { return replay; }
        Placeholder before = lockPlaceholder(principalId, input, placeholderIssuer);
        PrincipalView beforeAccess = accessAdmin.current(principalId);
        List<String> beforePositionIds = jdbc.queryForList("SELECT position.public_id FROM core_principal_position link "
            + "JOIN core_access_position position ON position.id=link.position_id "
            + "WHERE link.principal_id=? AND link.deleted=0 ORDER BY position.public_id", String.class, before.id());
        ensureIdentityAvailable(input, before.id());
        validateCampuses(input.campusIds());
        updatePrincipal(before, input);
        if (input.mustChangePassword()) { recordInitialCredentialGate(before.id(), principalId, input); }
        directory.bind(before.id(), input.directory(), input.active());
        replacePermissions(before.id(), input.permissionCodes());
        replaceDeniedPermissions(before.id(), input.deniedPermissionCodes());
        replaceCampuses(before.id(), input.campusIds());
        positions.replaceLegacyMemberships(actor, principalId, input.positionIds());
        insertCheckpoint(actor, principalId, before.id(), input, requestSha256);
        AccessSnapshot beforeSnapshot = new AccessSnapshot(before.issuer(), before.subject(), beforeAccess.scope(),
            beforeAccess.active(), beforeAccess.version(), beforeAccess.permissionCodes(),
            beforeAccess.effectivePermissionCodes(), beforeAccess.deniedPermissionCodes(), beforeAccess.campusIds(),
            beforePositionIds, List.of(), "", "", List.of(), List.of(), List.of(), List.of(), true, null);
        AccessSnapshot afterSnapshot = new AccessSnapshot(input.issuer(), input.subject(), input.scope(), input.active(),
            before.version() + 1, input.permissionCodes(), input.expectedEffectivePermissionCodes(),
            input.deniedPermissionCodes(), input.campusIds(), input.positionIds(), input.legacyRoleIds(),
            input.permissionMappingVersion(), input.sourceRoleFingerprint(), input.sourcePermissionCodes(),
            input.sourceDirectPermissionCodes(), input.sourceDeniedPermissionCodes(),
            input.expectedEffectivePermissionCodes(), true, directory.auditSnapshot(input.directory()));
        recordAccessChange(actor, before.id(), beforeSnapshot, afterSnapshot, "updated");
        return accessAdmin.current(principalId);
    }

    private void validateBindingRequest(String principalId, BindInput input) {
        if (!principalId.equals(input.legacyUserId()) || !principalId.equals(input.subject())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "迁移身份编号必须与原账号编号一致");
        }
        boolean duplicatePermissions = new HashSet<>(input.permissionCodes()).size() != input.permissionCodes().size();
        boolean unknownPermission = input.permissionCodes().stream().anyMatch(code -> !AccessPermissionCatalog.contains(code));
        if (duplicatePermissions || unknownPermission) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "权限列表含重复项或未登记权限");
        }
        boolean invalidScope = "campus".equals(input.scope()) && input.campusIds().isEmpty()
            || "all".equals(input.scope()) && !input.campusIds().isEmpty();
        if (invalidScope || new HashSet<>(input.campusIds()).size() != input.campusIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "迁移授权范围无效");
        }
    }

    private PrincipalView findReplay(String principalId, BindInput input, String requestSha256) {
        List<MigrationCheckpoint> checkpoint = jdbc.query("""
            SELECT source_sha256,request_sha256,oidc_issuer,oidc_subject,principal_id
            FROM core_legacy_identity_migration_checkpoint WHERE legacy_user_id=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> new MigrationCheckpoint(rs.getString("source_sha256"),
                rs.getString("request_sha256"), rs.getString("oidc_issuer"), rs.getString("oidc_subject")), principalId);
        if (checkpoint.isEmpty()) { return null; }
        MigrationCheckpoint row = checkpoint.get(0);
        boolean same = input.sourceSha256().equals(row.sourceSha256())
            && requestSha256.equals(row.requestSha256()) && input.issuer().equals(row.issuer())
            && input.subject().equals(row.subject());
        if (!same) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该账号已由不同迁移来源绑定"); }
        return accessAdmin.current(principalId);
    }

    private Placeholder lockPlaceholder(String principalId, BindInput input, String placeholderIssuer) {
        List<Placeholder> rows = jdbc.query("""
            SELECT id,public_id,oidc_issuer,oidc_subject,scope_type,active,version
            FROM core_principal WHERE public_id=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> new Placeholder(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("oidc_issuer"), rs.getString("oidc_subject"), rs.getString("scope_type"),
                rs.getBoolean("active"), rs.getLong("version"), false), principalId);
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "迁移占位账号不存在"); }
        Placeholder before = rows.get(0);
        if (before.active() || !before.issuer().equals(placeholderIssuer)
                || !before.subject().equals(principalId) || before.version() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号不是可绑定的禁用占位身份或版本已变化");
        }
        return before;
    }

    private void ensureIdentityAvailable(BindInput input, long principalDatabaseId) {
        Integer subjectCollision = jdbc.queryForObject("""
            SELECT COUNT(*) FROM core_principal WHERE oidc_issuer=? AND oidc_subject=? AND deleted=0 AND id<>?
            """, Integer.class, input.issuer(), input.subject(), principalDatabaseId);
        if (subjectCollision == null || subjectCollision != 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "外部身份已绑定到其他账号");
        }
    }

    private void updatePrincipal(Placeholder before, BindInput input) {
        String ticketHash = input.mustChangePassword() ? sha256(input.credentialChangeTicket()) : null;
        int changed = jdbc.update("""
            UPDATE core_principal SET oidc_issuer=?,oidc_subject=?,scope_type=?,active=?,
                credential_change_required=?,credential_change_quarantined=FALSE,
                credential_change_generation=?,credential_change_ticket_hash=?,
                credential_change_requested_at=IF(?,UTC_TIMESTAMP(3),NULL),version=version+1,
                updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND active=0 AND deleted=0
            """, input.issuer(), input.subject(), input.scope(), input.active(), input.mustChangePassword(),
            input.mustChangePassword() ? 1 : 0, ticketHash, input.mustChangePassword(), before.id(), input.expectedVersion());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "占位账号版本已变化"); }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private void recordInitialCredentialGate(long principalId, String publicId, BindInput input) {
        String ticketHash = sha256(input.credentialChangeTicket());
        String eventKey = sha256("legacy-bind\n" + input.sourceSha256() + "\n" + publicId);
        String payloadHash = sha256("PASSWORD_CHANGE_REQUIRED\n" + publicId + "\n" + ticketHash);
        jdbc.update("""
            INSERT INTO core_principal_credential_change_event
                (issuer_event_hash,event_type,outcome,principal_id,generation,payload_hash,event_at)
            VALUES(?,'PASSWORD_CHANGE_REQUIRED','accepted',?,1,?,UTC_TIMESTAMP(3))
            """, eventKey, principalId, payloadHash);
    }

    private void insertCheckpoint(Actor actor, String principalId, long databaseId, BindInput input,
            String requestSha256) throws JsonProcessingException {
        jdbc.update("""
            INSERT INTO core_legacy_identity_migration_checkpoint
                (legacy_user_id,principal_id,source_sha256,request_sha256,oidc_issuer,oidc_subject,actor_id,
                 source_role_ids,target_position_ids,denied_permission_codes,source_direct_permission_codes,
                 source_denied_permission_codes,expected_effective_permission_codes,permission_mapping_version,
                 source_role_fingerprint)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, principalId, databaseId, input.sourceSha256(), requestSha256,
                input.issuer(), input.subject(), actor.id(), mapper.writeValueAsString(input.legacyRoleIds()),
                mapper.writeValueAsString(input.positionIds()), mapper.writeValueAsString(input.deniedPermissionCodes()),
                mapper.writeValueAsString(input.sourceDirectPermissionCodes()),
                mapper.writeValueAsString(input.sourceDeniedPermissionCodes()),
                mapper.writeValueAsString(input.expectedEffectivePermissionCodes()), input.permissionMappingVersion(),
                input.sourceRoleFingerprint());
    }

    private void validateCampuses(List<String> campusIds) {
        if (campusIds.isEmpty()) { return; }
        String marks = String.join(",", java.util.Collections.nCopies(campusIds.size(), "?"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM core_campus WHERE deleted=0 AND active=1 AND public_id IN ("
            + marks + ")", Integer.class, campusIds.toArray());
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
        jdbc.update("UPDATE core_principal_denied_permission SET deleted=TRUE,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE principal_id=? AND deleted=FALSE", principalId);
        for (String permission : permissions) {
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code,deleted) "
                + "VALUES(?,?,FALSE) ON DUPLICATE KEY UPDATE deleted=FALSE,updated_at=UTC_TIMESTAMP(3)",
                principalId, permission);
        }
    }

    private void recordAccessChange(Actor actor, long targetId, AccessSnapshot before, AccessSnapshot after,
            String action)
            throws JsonProcessingException {
        jdbc.update("""
            INSERT INTO core_access_change_event(public_id,actor_id,target_id,action_type,before_state,after_state)
            VALUES(?,?,?, ?,?,?)
            """, "ACCESS_MIGRATION_" + java.util.UUID.randomUUID(), actor.id(), targetId, action,
            mapper.writeValueAsString(before), mapper.writeValueAsString(after));
    }

    private record Placeholder(long id, String publicId, String issuer, String subject, String scope,
            boolean active, long version, boolean deleted) { }
    private record PositionManifestRow(long id, String name, String description) { }
    private record MigrationCheckpoint(String sourceSha256, String requestSha256, String issuer, String subject) { }
    private record PlaceholderCheckpoint(String sourceSha256, String entrySha256) { }
    private record AccessSnapshot(String issuer, String subject, String scope, boolean active, long version,
            List<String> permissionCodes, List<String> effectivePermissionCodes, List<String> deniedPermissionCodes,
            List<String> campusIds, List<String> positionIds, List<String> legacyRoleIds,
            String permissionMappingVersion, String sourceRoleFingerprint, List<String> sourcePermissionCodes,
            List<String> sourceDirectPermissionCodes, List<String> sourceDeniedPermissionCodes,
            List<String> expectedEffectivePermissionCodes, boolean exists, String directoryCipher) { }
}
