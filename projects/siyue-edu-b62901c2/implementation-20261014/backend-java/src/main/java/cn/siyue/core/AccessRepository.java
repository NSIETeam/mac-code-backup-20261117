package cn.siyue.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Resolves current grants and scope for every request, including revoked accounts. */
@Repository
public class AccessRepository {
    private final JdbcTemplate jdbc;
    /** 装配当前身份、权限与改密状态读取，不缓存授权结果。
     * @param jdbc 连接当前业务数据库的参数化访问器
     */
    public AccessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 将已验证的issuer/subject绑定解析为有效员工，并读取直接权限、启用职位权限及拒权。
     * @param issuer 资源服务器已验证的可信OIDC issuer
     * @param subject 该issuer内的已验证subject
     * @return 当前范围、校区和有效权限构成的员工主体
     * @throws BusinessException 无唯一有效绑定或员工仍须完成改密时
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public Actor resolve(String issuer, String subject) {
        List<PrincipalState> users = jdbc.query("""
            SELECT id, public_id, scope_type, credential_change_required FROM core_principal
            WHERE oidc_issuer = ? AND oidc_subject = ? AND active = 1 AND deleted = 0
            """, (rs, row) -> new PrincipalState(new Actor(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("scope_type"), java.util.Set.of(), java.util.Set.of()),
                rs.getBoolean("credential_change_required")), issuer, subject);
        if (users.size() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号尚未授权或已停用");
        }
        if (users.get(0).passwordChangeRequired()) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED, "请先在身份平台完成密码修改");
        }
        return grants(users.get(0).actor());
    }
    private Actor grants(Actor user) {
        var campuses = new HashSet<>(jdbc.queryForList("""
            SELECT campus_id FROM core_principal_campus WHERE principal_id = ? AND deleted = 0
            """, Long.class, user.id()));
        var permissions = new HashSet<>(jdbc.queryForList("""
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
            """, String.class, user.id(), user.id(), user.id()));
        return new Actor(user.id(), user.publicId(), user.scope(), campuses, permissions);
    }
    /** 在调用方事务内锁定有效员工，再读取当前权限；调用方仍须对具体操作重新授权。
     * @param actorId 已解析员工的内部主键
     * @return 锁定后重新读取的员工范围、校区及有效权限
     * @throws BusinessException 员工已停用、被删除或仍须改密时
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public Actor lockActive(long actorId) {
        List<PrincipalState> ids = jdbc.query("""
            SELECT id, public_id, scope_type, credential_change_required FROM core_principal
            WHERE id = ? AND active = 1 AND deleted = 0 FOR UPDATE
            """, (rs, row) -> new PrincipalState(new Actor(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("scope_type"), java.util.Set.of(), java.util.Set.of()),
                rs.getBoolean("credential_change_required")), actorId);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        if (ids.get(0).passwordChangeRequired()) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED, "请先在身份平台完成密码修改");
        }
        return grants(ids.get(0).actor());
    }

    /** Holds a shared authorization lock for the student-create transaction, preserving concurrent revocation ordering.
     * @param actorId previously resolved principal key; a Web JWT must bind to this exact row
     * @return current grants while the principal row remains share-locked until the caller commits
     * @throws BusinessException when identity, active state, required change or credential generation is invalid
     * @throws IllegalStateException when invoked outside an explicit READ_COMMITTED transaction
     * @throws org.springframework.dao.DataAccessException when the locked identity or grants cannot be read
     */
    public Actor lockActiveShared(long actorId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Shared authorization requires a business transaction");
        }
        if (!Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Shared authorization requires READ_COMMITTED");
        }
        var rows = jdbc.query("""
            SELECT id,public_id,scope_type,credential_change_required,credential_change_generation,
                oidc_issuer,oidc_subject FROM core_principal
            WHERE id=? AND active=1 AND deleted=0 FOR SHARE
            """, (rs, row) -> new SharedPrincipal(
                new Actor(rs.getLong("id"), rs.getString("public_id"), rs.getString("scope_type"),
                    java.util.Set.of(), java.util.Set.of()), rs.getBoolean("credential_change_required"),
                rs.getLong("credential_change_generation"), rs.getString("oidc_issuer"), rs.getString("oidc_subject")),
            actorId);
        if (rows.size() != 1) { throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用"); }
        SharedPrincipal row = rows.get(0);
        requireLockedCredentials(row);
        return grants(row.actor());
    }

    private void requireLockedCredentials(SharedPrincipal row) {
        if (row.required()) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED, "请先在身份平台完成密码修改");
        }
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
        // Existing trusted in-process callers have no HTTP authentication context.
        if (authentication == null) { return; }
        if (!(authentication instanceof
                org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)
                || !jwt.isAuthenticated() || jwt.getToken().getIssuer() == null
                || !row.issuer().equals(jwt.getToken().getIssuer().toString())
                || !row.subject().equals(jwt.getToken().getSubject())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请求身份与锁定账号不匹配");
        }
        var parsed = CredentialTokenGeneration.parse(jwt.getToken());
        if (!parsed.valid() || row.generation() != (parsed.generation() == null ? 0 : parsed.generation())) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED, "请完成密码修改并重新登录");
        }
    }

    /** A current locked principal snapshot, including the identity binding and credential generation. */
    private record SharedPrincipal(Actor actor, boolean required, long generation, String issuer, String subject) { }

    /** 读取数据库改密门禁，不把旧JWT中的状态当作当前状态。
     * @param issuer 已验证的OIDC issuer
     * @param subject 该issuer内的subject
     * @return 唯一未删除绑定要求改密时为true；无绑定不在本方法内授予权限
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public boolean credentialChangeRequired(String issuer, String subject) {
        List<Boolean> rows = jdbc.query("""
            SELECT credential_change_required FROM core_principal
            WHERE oidc_issuer=? AND oidc_subject=? AND deleted=0
            """, (rs, row) -> rs.getBoolean(1), issuer, subject);
        return rows.size() == 1 && rows.get(0);
    }
    /** 将已验证JWT的凭证代次与当前持久代次比较，完成改密后也不重新接受旧令牌。
     * @param issuer 已验证的OIDC issuer
     * @param subject 该issuer内的subject
     * @param tokenGeneration JWT内严格解析的非负整数；null表示尚未携带代次的旧令牌
     * @return 唯一绑定仍须改密或代次不匹配时为true；缺失claim仅在数据库代次为0时兼容
     * @throws org.springframework.dao.DataAccessException 当前凭证状态查询失败时
     */
    public boolean credentialGenerationObsolete(String issuer, String subject, Long tokenGeneration) {
        List<Boolean> rows = jdbc.query("""
            SELECT credential_change_required,credential_change_generation FROM core_principal
            WHERE oidc_issuer=? AND oidc_subject=? AND deleted=0
            """, (rs, row) -> rs.getBoolean(1)
                || rs.getLong(2) != (tokenGeneration == null ? 0 : tokenGeneration), issuer, subject);
        return rows.size() == 1 && rows.get(0);
    }

    /** 按内部主键升序去重锁定多名员工，避免调用方以不同顺序获取相同员工锁。
     * @param actorIds 待锁定员工内部主键列表，不允许null列表或元素
     * @return 按锁定顺序组成的不可修改员工映射
     * @throws BusinessException 任一员工已停用、被删除或仍须改密时
     * @throws NullPointerException 主键列表或其中元素为null时
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public Map<Long, Actor> lockActiveMany(List<Long> actorIds) {
        Map<Long, Actor> locked = new LinkedHashMap<>();
        for (Long actorId : new TreeSet<>(actorIds)) { locked.put(actorId, lockActive(actorId)); }
        return java.util.Collections.unmodifiableMap(locked);
    }
    /** 读取员工当前关联且未删除的校区公开标识，避免对外泄漏内部序号。
     * @param actor 已完成身份验证和授权解析的员工
     * @return 按公开标识排序的校区列表
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public List<String> campusPublicIds(Actor actor) {
        return jdbc.queryForList("""
            SELECT c.public_id FROM core_principal_campus pc
            JOIN core_campus c ON c.id=pc.campus_id AND c.deleted=0
            WHERE pc.principal_id=? AND pc.deleted=0 ORDER BY c.public_id
            """, String.class, actor.id());
    }
    /** 同一数据库行读取的主体及改密状态；避免再次查询拆散状态快照。
     * @param actor 当前行中的身份及范围，尚未补充权限和校区
     * @param passwordChangeRequired 此行是否仍要求修改密码
     */
    private record PrincipalState(Actor actor, boolean passwordChangeRequired) { }

}
