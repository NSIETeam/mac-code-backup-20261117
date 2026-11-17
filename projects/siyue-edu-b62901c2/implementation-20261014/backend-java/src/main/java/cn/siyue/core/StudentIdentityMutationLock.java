package cn.siyue.core;

import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes writes that target the same normalized student name and phone identity. */
final class StudentIdentityMutationLock {
    private static final Object TRANSACTION_LOCKS = new Object();
    private static final int WAIT_SECONDS = 30;
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    StudentIdentityMutationLock(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Acquires database-scoped HMAC mutexes in deterministic order through transaction completion. */
    void lock(String name, String phone) {
        if (name == null || phone == null) { return; }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Student identity locks require an active transaction");
        }
        Map<String, byte[]> indexes = cipher.exactBlindIndexes("student_name", identityKey(name, phone));
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (database == null || database.isBlank()) {
            throw new IllegalStateException("Student identity lock database is unavailable");
        }
        Set<String> names = new TreeSet<>();
        for (byte[] index : indexes.values()) { names.add(lockName(database, index)); }
        LockState state = lockState();
        Set<String> acquired = new TreeSet<>();
        try {
            for (String lockName : names) {
                if (state.names().contains(lockName)) { continue; }
                acquire(lockName);
                state.names().add(lockName);
                acquired.add(lockName);
            }
        } catch (RuntimeException failure) {
            release(acquired);
            state.names().removeAll(acquired);
            throw failure;
        }
    }

    /** Uses the same normalized pair as the unique student identity index. */
    static String identityKey(String name, String phone) {
        String normalizedPhone = java.text.Normalizer.normalize(phone, java.text.Normalizer.Form.NFKC)
            .trim().replaceAll("[\\s()\\-]", "");
        return StudentNameIndexRepository.normalize(name) + "\u0000" + normalizedPhone;
    }

    private LockState lockState() {
        Object existing = TransactionSynchronizationManager.getResource(TRANSACTION_LOCKS);
        if (existing instanceof LockState state) { return state; }
        LockState state = new LockState();
        TransactionSynchronizationManager.bindResource(TRANSACTION_LOCKS, state);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try { release(state.names()); }
                finally { TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_LOCKS); }
            }
        });
        return state;
    }

    private void acquire(String lockName) {
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?,?)", Integer.class, lockName, WAIT_SECONDS);
        if (acquired == null || acquired != 1) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "学生身份正在并发办理，请稍后重试");
        }
    }

    private void release(Iterable<String> names) {
        ArrayList<String> reverse = new ArrayList<>();
        names.forEach(reverse::add);
        Collections.reverse(reverse);
        RuntimeException failure = null;
        for (String lockName : reverse) {
            try {
                Integer released = jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
                if (released == null || released != 1) {
                    throw new IllegalStateException("Student identity mutex release was not confirmed");
                }
            } catch (RuntimeException error) {
                if (failure == null) { failure = error; }
                else { failure.addSuppressed(error); }
            }
        }
        if (failure != null) { throw failure; }
    }

    private String lockName(String database, byte[] hash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(database.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(hash);
            // MySQL limits named locks to 64 characters.
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /** 当前事务已持有的学员身份锁集合，防止同事务重复申请并保持一致加锁顺序。 */
    private static final class LockState {
        private final Set<String> names = new TreeSet<>();
        Set<String> names() { return names; }
    }
}
