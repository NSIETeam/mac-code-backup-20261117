package cn.siyue.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Serializes retries and stores minimal audit facts in the business transaction. */
@Repository
public class MutationRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** 连接业务事务内的幂等回执和最小审计；员工锁由调用服务建立。
     * @param jdbc 参与调用方事务的数据库访问器
     * @param cipher 保存及读取幂等结果的PII加密器
     */
    public MutationRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }
    /** 计算规范载荷UTF-8字节的SHA-256，不额外归一化字段或空白。
     * @param canonical 调用方已规范序列化的非null请求字符串
     * @return 64位小写十六进制SHA-256
     * @throws IllegalStateException JVM不提供SHA-256算法时
     * @throws NullPointerException 载荷为null时
     */
    public String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Required SHA-256 provider unavailable", error);
        }
    }
    /** 使用历史学员沟通操作码读取原幂等回执。
     * @param actor 调用方已授权并在事务内锁定的员工
     * @param key 当前员工下的请求幂等键
     * @param hash 规范请求摘要
     * @return 同student.communication.create和摘要的解密原回执；未执行过时为空
     * @throws BusinessException 同键已用于其他操作或请求时
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public String replay(Actor actor, String key, String hash) {
        return replay(actor, key, "student.communication.create", hash);
    }
    /** 按员工和幂等键查找保存结果，并要求操作码及请求摘要完全相同。
     * @param actor 调用方已授权并锁定的员工；本仓储不替代权限或范围复核
     * @param key 员工级幂等键
     * @param action 本次精确操作码
     * @param hash 规范请求摘要
     * @return 已保存结果的解密字符串；无匹配键时为空
     * @throws BusinessException 同键绑定的操作或摘要不同而发生幂等冲突时
     * @throws IllegalStateException 保存结果的密文无法解密时
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public String replay(Actor actor, String key, String action, String hash) {
        List<Retry> matches = jdbc.query("""
            SELECT action, request_hash, result_cipher FROM core_idempotency
            WHERE actor_id = ? AND request_key = ? AND deleted = 0
            """, (rs, row) -> new Retry(rs.getString("action"), rs.getString("request_hash"),
                rs.getString("result_cipher")), actor.id(), key);
        if (matches.isEmpty()) { return null; }
        Retry previous = matches.get(0);
        if (!previous.action().equals(action) || !previous.hash().equals(hash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "幂等键已用于其他请求");
        }
        return cipher.decrypt(previous.result());
    }
    /** Reserves the actor-wide key after a shared principal lock and reads any receipt using a current read.
     * @param actor freshly authorized caller whose shared or exclusive principal lock is already held
     * @param key actor-wide retry key, never partitioned by action
     * @param action exact operation code used to reject cross-operation key reuse
     * @param hash canonical request digest
     * @return original decrypted receipt or null when this transaction must perform the operation
     * @throws BusinessException when an existing key belongs to another action or payload
     * @throws IllegalStateException when no READ_COMMITTED transaction exists or the stored receipt cannot be decrypted
     * @throws org.springframework.dao.DataAccessException when reservation or current receipt lookup fails
     */
    public String reserveAndReplay(Actor actor, String key, String action, String hash) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Mutation key reservation requires a business transaction");
        }
        if (!Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Shared-key reservation requires READ_COMMITTED");
        }
        jdbc.update("""
            INSERT INTO core_mutation_key_lock(actor_id,request_key) VALUES(?,?)
            ON DUPLICATE KEY UPDATE id=id
            """, actor.id(), key);
        List<Retry> matches = jdbc.query("""
            SELECT action,request_hash,result_cipher FROM core_idempotency
            WHERE actor_id=? AND request_key=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> new Retry(rs.getString("action"), rs.getString("request_hash"),
                rs.getString("result_cipher")), actor.id(), key);
        if (matches.isEmpty()) { return null; }
        Retry previous = matches.get(0);
        if (!previous.action().equals(action) || !previous.hash().equals(hash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "幂等键已用于其他请求");
        }
        return cipher.decrypt(previous.result());
    }

    /** 在调用方业务事务内写加密回执和最小审计，不单独提交事务。
     * @param actor 已授权并锁定的操作员工
     * @param write 原请求键/操作/摘要、待加密结果及资源标识
     * @throws IllegalStateException 结果加密失败时
     * @throws org.springframework.dao.DataAccessException 数据库查询或写入失败时
     */
    public void complete(Actor actor, RetryWrite write) {
        jdbc.update("""
            INSERT INTO core_idempotency (actor_id, request_key, action, request_hash, result_cipher)
            VALUES (?, ?, ?, ?, ?)
            """, actor.id(), write.key(), write.action(), write.hash(),
            cipher.encrypt(write.result()));
        jdbc.update("""
            INSERT INTO core_audit (actor_id, action, resource_public_id, trace_id)
            VALUES (?, ?, ?, ?)
            """, actor.id(), write.action(), write.resourceId(), MDC.get("traceId"));
    }
    /** 待与业务变更同事务提交的回执输入；结果加密保存，审计只保留操作和资源关联。
     * @param key 员工级幂等键
     * @param action 精确操作码
     * @param hash 规范请求摘要
     * @param result 待加密的结果JSON字符串
     * @param resourceId 最小审计中的资源公开标识
     */
    public record RetryWrite(String key, String action, String hash, String result, String resourceId) { }
    /** 数据库已有幂等事实，解密前先验证操作及摘要一致。
     * @param action 原操作码
     * @param hash 原请求摘要
     * @param result 加密保存的结果
     */
    private record Retry(String action, String hash, String result) { }
}
