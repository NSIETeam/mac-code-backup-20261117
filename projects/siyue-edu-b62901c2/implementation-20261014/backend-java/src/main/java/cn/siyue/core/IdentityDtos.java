package cn.siyue.core;

import java.util.List;
import java.util.Set;

/** Authenticated identity state for browser and CLI clients; contains no database sequence IDs. */
public final class IdentityDtos {
    private IdentityDtos() { }
    /** 已验证issuer/subject绑定后的当前员工授权；不包含数据库自增主键。
     * @param principalId 稳定员工公开标识
     * @param scope 当前数据范围模式
     * @param campusIds 授权校区公开标识列表
     * @param permissions 当前有效权限集合，客户端不能据此持久赋权
     */
    public record CurrentIdentity(String principalId, String scope, List<String> campusIds,
            Set<String> permissions) { }
}
