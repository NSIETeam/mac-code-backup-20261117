package cn.siyue.core;

import java.util.Set;

/** Database-resolved actor; JWT roles and scope claims cannot grant business access. */
public record Actor(long id, String publicId, String scope, Set<Long> campusIds,
                    Set<String> permissions) {
    /** Enforces an explicit permission; roles do not implicitly bypass writes. */
    public void require(String permission) {
        if (!permissions.contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号未获得该操作权限");
        }
    }

    /** Enforces one explicit action permission while retaining compatible native grants. */
    public void requireAny(String... alternatives) {
        for (String permission : alternatives) {
            if (permissions.contains(permission)) { return; }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号未获得该操作权限");
    }
}
