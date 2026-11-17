package cn.siyue.core;

import cn.siyue.core.LegacyIdentityMigrationDtos.BindInput;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps the reserved legacy administrator role only inside trusted identity migration.
 * Normal position editing and direct permission delegation continue to reject access administration.
 * The migration service must match the subject, source digest and role fingerprint to its private manifest first.
 */
final class LegacyAdministratorMigrationPolicy {
    static final String ROLE = "superadmin";
    private static final Set<String> RESERVED = Set.of("employee.create", "role.grant");

    private LegacyAdministratorMigrationPolicy() { }

    /** Identifies the explicit legacy role; this predicate alone does not authorize a binding. */
    static boolean applies(BindInput input) {
        return input.legacyRoleIds() != null && input.legacyRoleIds().contains(ROLE);
    }

    /** Requires the immutable administrator scope and absence of partial denials from the old policy. */
    static boolean validScope(BindInput input) {
        return "all".equals(input.scope()) && input.campusIds().isEmpty()
            && input.sourceDeniedPermissionCodes().isEmpty() && input.deniedPermissionCodes().isEmpty();
    }

    /** Maps a trusted administrator source snapshot, retaining every reviewed ordinary grant.
     * The two reserved account/role-management operations share the migration-only administration grant.
     * Unknown ordinary permissions remain rejected by the versioned mapping catalog.
     * @param sourceCodes exact, unique legacy effective or role permission snapshot
     * @return sorted explicit Java grants, including access administration
     * @throws BusinessException if reserved source permissions are absent, duplicate, or an ordinary mapping is unsupported
     */
    static List<String> map(List<String> sourceCodes) {
        if (sourceCodes == null || new HashSet<>(sourceCodes).size() != sourceCodes.size()
                || !sourceCodes.containsAll(RESERVED)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "可信管理员来源权限不完整");
        }
        Set<String> mapped = new HashSet<>(LegacyPermissionMappingCatalog.map(
            sourceCodes.stream().filter(code -> !RESERVED.contains(code)).toList()));
        mapped.add("system.access.manage");
        return mapped.stream().sorted().toList();
    }
}
