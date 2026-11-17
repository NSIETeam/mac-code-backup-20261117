package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.LegacyIdentityMigrationDtos.BindInput;
import cn.siyue.core.LegacyIdentityMigrationDtos.PrepareInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Disabled-by-default, audited binding for stable legacy employee identifiers.
 * Requires a trusted source manifest, a matching issuer/checkpoint, and an all-scope access administrator.
 * @see LegacyIdentityMigrationRepository
 * @see LegacyPermissionMappingCatalog
 */
@Service
public class LegacyIdentityMigrationService {
    private final AccessRepository access;
    private final LegacyIdentityMigrationRepository repository;
    private final LegacyIdentityMigrationSupport support;
    private final LegacyIdentityMigrationProperties properties;
    private final CredentialChangeProperties credentialChange;

    /** Injects principal locking, migration persistence, retry tracking and trusted migration settings.
     * @param access repository used to reload and lock the active administrator
     * @param repository placeholder preparation and identity-binding persistence
     * @param support serialization and idempotency services for migration requests
     * @param properties feature gate, source digest, trusted issuer/manifest and checkpoint configuration
     * @param credentialChange trusted password-change callback configuration
     */
    public LegacyIdentityMigrationService(AccessRepository access, LegacyIdentityMigrationRepository repository,
            LegacyIdentityMigrationSupport support, LegacyIdentityMigrationProperties properties,
            CredentialChangeProperties credentialChange) {
        this.access = access;
        this.repository = repository;
        this.support = support;
        this.properties = properties;
        this.credentialChange = credentialChange;
    }

    /** Binds one disabled placeholder to its legacy OIDC subject under one idempotent transaction.
     * A successful bind preserves the stable principal identifier and verifies the resulting effective grants
     * against the trusted manifest before writing the completion audit record.
     * @param authenticatedActor authenticated administrator requesting the binding
     * @param principalId stable Java principal identifier to bind
     * @param idempotencyKey stable key for this bind request
     * @param presentedCheckpoint secret checkpoint supplied through the migration-only API
     * @param input trusted-source identity, scope, campus, role, permission mapping and expected-version fields
     * @return bound principal view, or the prior view for an identical retry
     * @throws JsonProcessingException if trusted manifest or retry data cannot be serialized/deserialized
     * @throws BusinessException if migration is disabled or checkpoint, permission, manifest, state, or identity checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public PrincipalView bind(Actor authenticatedActor, String principalId, String idempotencyKey,
            String presentedCheckpoint, BindInput input) throws JsonProcessingException {
        requireEnabledConfiguration();
        if (Boolean.TRUE.equals(input.mustChangePassword())) { requireCredentialCallback(); }
        repository.lockAdministration();
        Actor actor = authorize(authenticatedActor, presentedCheckpoint);
        requireManifestEntry(principalId, input);
        validateTrustedInput(principalId, input);
        validateIdempotencyKey(idempotencyKey);
        String hash = requestHash(input);
        return performBind(actor, principalId, idempotencyKey, hash, input);
    }

    /** Establishes the full stable-ID principal set before any business import can reference it.
     * Only principals present in the trusted manifest and matching the configured source digest and trusted issuer
     * may be prepared; preparation does not activate the placeholder for login.
     * @param authenticatedActor authenticated administrator requesting placeholder preparation
     * @param principalId stable Java principal identifier to prepare
     * @param idempotencyKey stable key for this preparation request
     * @param presentedCheckpoint secret checkpoint supplied through the migration-only API
     * @param input legacy identity and trusted source digest used to select the manifest entry
     * @return prepared principal view, or the prior view for an identical retry
     * @throws JsonProcessingException if trusted manifest or retry data cannot be serialized/deserialized
     * @throws BusinessException if migration is disabled or administrator, checkpoint, manifest, or identity checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public PrincipalView prepare(Actor authenticatedActor, String principalId, String idempotencyKey,
            String presentedCheckpoint, PrepareInput input) throws JsonProcessingException {
        requireEnabledConfiguration();
        repository.lockAdministration();
        Actor actor = authorize(authenticatedActor, presentedCheckpoint);
        if (!principalId.equals(input.legacyUserId()) || !properties.sourceSha256().equals(input.sourceSha256())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "稳定身份编号或迁移来源校验失败");
        }
        TrustedManifestEntry entry = trustedManifestEntry(principalId);
        if (entry == null || !principalId.equals(entry.subject())
                || !properties.trustedIssuer().equals(entry.issuer())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "可信身份清单中的OIDC主体或issuer无效");
        }
        validateIdempotencyKey(idempotencyKey);
        String entryHash = support.mutations().hash(support.mapper().writeValueAsString(entry));
        String hash = support.mutations().hash(support.mapper().writeValueAsString(new PrepareRequest(principalId,
            input.sourceSha256(), entryHash)));
        String action = "system.legacy-identity.prepare:" + principalId;
        String replay = support.mutations().replay(actor, idempotencyKey, action, hash);
        if (replay != null) { return support.mapper().readValue(replay, PrincipalView.class); }
        PrincipalView prepared = repository.prepare(actor, principalId, input,
            properties.placeholderIssuer(), entryHash);
        support.mutations().complete(actor, new MutationRepository.RetryWrite(idempotencyKey, action, hash,
            support.mapper().writeValueAsString(prepared), principalId));
        return prepared;
    }

    private void requireEnabledConfiguration() {
        boolean valid = properties.enabled() && properties.sourceSha256() != null
            && properties.sourceSha256().matches("[0-9a-f]{64}") && properties.checkpoint() != null
            && properties.checkpoint().length() >= 32 && properties.trustedIssuer() != null
            && !properties.trustedIssuer().isBlank() && properties.placeholderIssuer() != null
            && !properties.placeholderIssuer().isBlank() && properties.trustedManifestJson() != null
            && !properties.trustedManifestJson().isBlank();
        if (!valid) { throw new BusinessException(ErrorCode.FORBIDDEN, "旧身份迁移接口未启用"); }
    }

    private void requireCredentialCallback() {
        if (!credentialChange.enabled() || !properties.trustedIssuer().equals(credentialChange.issuer())
                || credentialChange.hmacSecret() == null || credentialChange.hmacSecret().length() < 32
                || credentialChange.maxClockSkewSeconds() < 1 || credentialChange.maxClockSkewSeconds() > 900) {
            throw new BusinessException(ErrorCode.CONFIGURATION_UNAVAILABLE,
                "强制改密账号尚未配置可信密码状态同步");
        }
    }

    private Actor authorize(Actor authenticatedActor, String presentedCheckpoint) {
        Actor actor = access.lockActive(authenticatedActor.id());
        actor.require("system.access.manage");
        if (!"all".equals(actor.scope())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "旧身份迁移仅允许全局访问管理员办理");
        }
        if (!constantTimeEquals(properties.checkpoint(), presentedCheckpoint)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "迁移校验失败");
        }
        return actor;
    }

    private void validateTrustedInput(String principalId, BindInput input) {
        if (!properties.sourceSha256().equals(input.sourceSha256())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "迁移来源校验失败");
        }
        if (!principalId.equals(input.legacyUserId()) || !principalId.equals(input.subject())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "旧账号编号、主体和稳定员工编号必须一致");
        }
        if (!properties.trustedIssuer().equals(input.issuer())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "迁移OIDC issuer与平台配置不匹配");
        }
        if (!validPermissionMapping(input)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "迁移权限不得扩大或重复");
        }
        validateIssuer(input.issuer());
    }

    private boolean validPermissionMapping(BindInput input) {
        return input.sourcePermissionCodes() != null && input.permissionCodes() != null
            && LegacyPermissionMappingCatalog.VERSION.equals(input.permissionMappingVersion())
            && LegacyPermissionMappingCatalog.uniqueTargets()
            && validDirectPermissions(input)
            && LegacyPermissionMappingCatalog.map(input.sourceDeniedPermissionCodes()).equals(input.deniedPermissionCodes())
            && unique(input.legacyRoleIds()) && unique(input.positionIds())
            && unique(input.deniedPermissionCodes()) && unique(input.expectedEffectivePermissionCodes())
            && (LegacyAdministratorMigrationPolicy.applies(input)
                ? LegacyAdministratorMigrationPolicy.validScope(input)
                : input.expectedEffectivePermissionCodes().stream().allMatch(AccessPermissionCatalog::isAssignable))
            && input.deniedPermissionCodes().stream().allMatch(AccessPermissionCatalog::contains);
    }

    private boolean validDirectPermissions(BindInput input) {
        List<String> direct = LegacyPermissionMappingCatalog.map(input.sourceDirectPermissionCodes());
        if (LegacyAdministratorMigrationPolicy.applies(input)) {
            List<String> effective = LegacyAdministratorMigrationPolicy.map(input.sourcePermissionCodes());
            return effective.containsAll(direct) && effective.equals(input.permissionCodes());
        }
        return direct.equals(input.permissionCodes());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
    }

    private String requestHash(BindInput input) throws JsonProcessingException {
        com.fasterxml.jackson.databind.node.ObjectNode immutableRequest = support.mapper().valueToTree(input);
        immutableRequest.remove("expectedVersion");
        return support.mutations().hash(support.mapper().writeValueAsString(immutableRequest));
    }

    private PrincipalView performBind(Actor actor, String principalId, String idempotencyKey, String hash,
            BindInput input) throws JsonProcessingException {
        String action = "system.legacy-identity.bind:" + principalId;
        String replay = support.mutations().replay(actor, idempotencyKey, action, hash);
        if (replay != null) { return support.mapper().readValue(replay, PrincipalView.class); }

        PrincipalView bound = repository.bind(actor, principalId, input, properties.placeholderIssuer(), hash);
        if (!bound.effectivePermissionCodes().equals(input.expectedEffectivePermissionCodes())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "绑定后的Java有效权限与可信旧权限映射不一致");
        }
        support.mutations().complete(actor, new MutationRepository.RetryWrite(idempotencyKey, action, hash,
            support.mapper().writeValueAsString(bound), principalId));
        return bound;
    }

    private void requireManifestEntry(String principalId, BindInput input) throws JsonProcessingException {
        TrustedManifestEntry entry = trustedManifestEntry(principalId);
        requireManifestEntryMatches(entry, input);
        TrustedManifest manifest = readTrustedManifest();
        List<String> mappedPositions = mapLegacyRoles(input, manifest);
        if (!mappedPositions.equals(input.positionIds())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "可信角色清单与职位关联不一致");
        }
        if (LegacyAdministratorMigrationPolicy.applies(input)
                && !manifest.roles().get(LegacyAdministratorMigrationPolicy.ROLE).sourcePermissionCodes()
                    .equals(input.sourcePermissionCodes())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员有效权限必须与可信保留角色快照完全一致");
        }
        validateRoleFingerprint(input, manifest);
        validateExpectedEffective(input, manifest);
    }

    private void requireManifestEntryMatches(TrustedManifestEntry entry, BindInput input) {
        if (entry == null) { manifestMismatch(); }
        requireManifestIdentity(entry, input);
        if (!java.util.Objects.equals(entry.directory(), input.directory())) { manifestMismatch(); }
        requireManifestPermissions(entry, input);
        requireManifestAssociations(entry, input);
    }

    private void requireManifestIdentity(TrustedManifestEntry entry, BindInput input) {
        if (!input.issuer().equals(entry.issuer()) || !input.subject().equals(entry.subject())
                || !input.scope().equals(entry.scope()) || !input.active().equals(entry.active())) {
            manifestMismatch();
        }
    }

    private void requireManifestPermissions(TrustedManifestEntry entry, BindInput input) {
        if (!input.sourcePermissionCodes().equals(entry.sourcePermissionCodes())
                || !input.permissionCodes().equals(entry.permissionCodes())
                || !input.sourceDirectPermissionCodes().equals(entry.sourceDirectPermissionCodes())
                || !input.sourceDeniedPermissionCodes().equals(entry.sourceDeniedPermissionCodes())
                || !input.expectedEffectivePermissionCodes().equals(entry.expectedEffectivePermissionCodes())
                || !input.deniedPermissionCodes().equals(entry.deniedPermissionCodes())) {
            manifestMismatch();
        }
    }

    private void requireManifestAssociations(TrustedManifestEntry entry, BindInput input) {
        if (!input.permissionMappingVersion().equals(entry.permissionMappingVersion())
                || !input.sourceRoleFingerprint().equals(entry.sourceRoleFingerprint())
                || !input.campusIds().equals(entry.campusIds())
                || !input.legacyRoleIds().equals(entry.legacyRoleIds())
                || !input.positionIds().equals(entry.positionIds())
                || !validCredentialChange(entry, input)) {
            manifestMismatch();
        }
    }

    private boolean validCredentialChange(TrustedManifestEntry entry, BindInput input) {
        if (!java.util.Objects.equals(input.mustChangePassword(), entry.mustChangePassword())) { return false; }
        if (!Boolean.TRUE.equals(input.mustChangePassword())) {
            return input.credentialChangeTicket() == null && entry.credentialChangeTicketHash() == null;
        }
        return constantTimeEquals(entry.credentialChangeTicketHash(), ticketHash(input.credentialChangeTicket()));
    }

    private void manifestMismatch() {
        throw new BusinessException(ErrorCode.FORBIDDEN, "身份授权与可信迁移清单不一致");
    }

    private String ticketHash(String ticket) {
        if (ticket == null || ticket.length() < 32 || ticket.length() > 128
                || !ticket.matches("[A-Za-z0-9_-]+")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "强制改密凭据无效");
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private TrustedManifest readTrustedManifest() throws JsonProcessingException {
        TrustedManifest manifest = support.mapper().readValue(properties.trustedManifestJson(), TrustedManifest.class);
        if (!LegacyPermissionMappingCatalog.VERSION.equals(manifest.permissionMappingVersion())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "可信权限映射版本不匹配");
        }
        return manifest;
    }

    private List<String> mapLegacyRoles(BindInput input, TrustedManifest manifest) {
        return input.legacyRoleIds().stream().map(roleId -> validateLegacyRole(roleId, manifest))
            .filter(java.util.Objects::nonNull).toList();
    }

    private String validateLegacyRole(String roleId, TrustedManifest manifest) {
        TrustedRoleEntry role = manifest.roles() == null ? null : manifest.roles().get(roleId);
        if (!validLegacyRole(role) || !roleId.equals(role.roleId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "可信角色清单缺少目标职位映射");
        }
        if (LegacyAdministratorMigrationPolicy.ROLE.equals(roleId)) { return null; }
        repository.validateLegacyPosition(role.positionId(), role.name(), role.description(), role.permissionCodes());
        repository.validateLegacyDirectoryKind(role.positionId(), role.roleId(), role.directoryKind());
        return role.positionId();
    }

    private boolean validLegacyRole(TrustedRoleEntry role) {
        return role != null && validLegacyRoleIdentity(role) && validLegacyRolePermissions(role)
            && validLegacyRoleFingerprint(role);
    }

    private boolean validLegacyRoleIdentity(TrustedRoleEntry role) {
        return (LegacyAdministratorMigrationPolicy.ROLE.equals(role.roleId())
            ? role.positionId() == null && role.sourceVersion() == 1
            : role.positionId() != null && !role.positionId().isBlank()) && role.name() != null
            && role.description() != null && role.roleId() != null
            && LegacyPermissionMappingCatalog.VERSION.equals(role.permissionMappingVersion())
            && role.sourceVersion() >= 1;
    }

    private boolean validLegacyRolePermissions(TrustedRoleEntry role) {
        return unique(role.sourcePermissionCodes()) && unique(role.permissionCodes())
            && (LegacyAdministratorMigrationPolicy.ROLE.equals(role.roleId())
                ? LegacyAdministratorMigrationPolicy.map(role.sourcePermissionCodes()).equals(role.permissionCodes())
                : LegacyPermissionMappingCatalog.map(role.sourcePermissionCodes()).equals(role.permissionCodes())
                    && role.permissionCodes().stream().allMatch(AccessPermissionCatalog::isAssignable));
    }

    private boolean validLegacyRoleFingerprint(TrustedRoleEntry role) {
        return role.sourceFingerprint() != null && role.sourceFingerprint().matches("[0-9a-f]{64}")
            && role.sourceFingerprint().equals(roleFingerprint(role.roleId(), role.sourceVersion(), role.sourcePermissionCodes()));
    }

    private boolean unique(List<String> values) {
        return values != null && new java.util.HashSet<>(values).size() == values.size();
    }

    private TrustedManifestEntry trustedManifestEntry(String principalId) throws JsonProcessingException {
        TrustedManifest manifest = support.mapper().readValue(properties.trustedManifestJson(), TrustedManifest.class);
        if (!properties.sourceSha256().equals(manifest.sourceSha256())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "可信迁移清单来源摘要无效");
        }
        return manifest.principals() == null ? null : manifest.principals().get(principalId);
    }

    private void validateRoleFingerprint(BindInput input, TrustedManifest manifest) throws JsonProcessingException {
        List<RoleFingerprint> roles = input.legacyRoleIds().stream().map(roleId -> {
            TrustedRoleEntry role = manifest.roles() == null ? null : manifest.roles().get(roleId);
            if (role == null) { manifestMismatch(); }
            return new RoleFingerprint(roleId, role.sourceVersion(), role.sourceFingerprint());
        }).toList();
        try {
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(support.mapper().writeValueAsBytes(roles)));
            if (!actual.equals(input.sourceRoleFingerprint())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "旧角色来源指纹不匹配");
            }
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException("Unable to verify source role fingerprint", error);
        }
    }

    private void validateExpectedEffective(BindInput input, TrustedManifest manifest) {
        List<String> mappedLegacyEffective = LegacyAdministratorMigrationPolicy.applies(input)
            ? LegacyAdministratorMigrationPolicy.map(input.sourcePermissionCodes())
            : LegacyPermissionMappingCatalog.map(input.sourcePermissionCodes());
        if (!mappedLegacyEffective.equals(input.expectedEffectivePermissionCodes())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标有效权限映射与旧权限快照不一致");
        }
        java.util.Set<String> effective = new java.util.HashSet<>(input.permissionCodes());
        for (String roleId : input.legacyRoleIds()) {
            TrustedRoleEntry role = manifest.roles() == null ? null : manifest.roles().get(roleId);
            if (role != null) { effective.addAll(role.permissionCodes()); }
        }
        effective.removeAll(input.deniedPermissionCodes());
        if (!effective.stream().sorted().toList().equals(input.expectedEffectivePermissionCodes())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标直接/职位/拒权组合与旧有效权限映射不一致");
        }
    }

    private String roleFingerprint(String roleId, int sourceVersion, List<String> sourcePermissionCodes) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = support.mapper().createObjectNode();
            node.put("roleId", roleId); node.put("sourceVersion", sourceVersion);
            node.set("sourcePermissionCodes", support.mapper().valueToTree(sourcePermissionCodes));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(support.mapper().writeValueAsBytes(node)));
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException("Unable to fingerprint trusted role", error);
        }
    }

    private void validateIssuer(String issuer) {
        try {
            URI value = URI.create(issuer);
            if (!value.isAbsolute() || value.getHost() == null || !"https".equalsIgnoreCase(value.getScheme())
                    || value.getQuery() != null || value.getFragment() != null || value.getUserInfo() != null
                    || issuer.getBytes(StandardCharsets.UTF_8).length > 1024) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "OIDC issuer 必须是有效的 HTTPS 地址");
            }
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "OIDC issuer 无效");
        }
    }

    private boolean constantTimeEquals(String expected, String presented) {
        if (expected == null || presented == null) { return false; }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }

    private record TrustedManifest(String sourceSha256, String permissionMappingVersion,
            Map<String, TrustedManifestEntry> principals, Map<String, TrustedRoleEntry> roles) { }
    private record TrustedManifestEntry(String issuer, String subject, String scope, Boolean active,
            List<String> sourcePermissionCodes, List<String> permissionCodes, List<String> expectedEffectivePermissionCodes,
            List<String> sourceDirectPermissionCodes, List<String> sourceDeniedPermissionCodes,
            List<String> campusIds, List<String> legacyRoleIds, List<String> positionIds,
            List<String> deniedPermissionCodes, String permissionMappingVersion, String sourceRoleFingerprint,
            LegacyIdentityMigrationDtos.DirectoryProfile directory, Boolean mustChangePassword,
            String credentialChangeTicketHash) { }
    private record TrustedRoleEntry(String roleId, String positionId, String name, String description,
            List<String> sourcePermissionCodes, List<String> permissionCodes, String permissionMappingVersion,
            int sourceVersion, String sourceFingerprint, String directoryKind) { }
    private record RoleFingerprint(String roleId, int sourceVersion, String sourceFingerprint) { }
    private record PrepareRequest(String principalId, String sourceSha256, String entrySha256) { }
}
