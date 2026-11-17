package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises actual Hikari/Spring shared-read transactions and current permission revocation. */
final class StaffDirectoryConcurrencyAssertions {
    private StaffDirectoryConcurrencyAssertions() { }

    static void verify(JdbcTemplate jdbc, StaffDirectoryService directory, Actor actor) throws Exception {
        var transactions = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transactions.execute(status -> {
                new AccessRepository(jdbc).lockActiveShared(actor.id());
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> directory.list(actor, "teacher", 1, 50));
            assertEquals(2, second.get(10, TimeUnit.SECONDS).total());
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=? AND permission_code='teacher.read'",
            actor.id());
        BusinessException revoked = assertThrows(BusinessException.class,
            () -> directory.list(actor, "teacher", 1, 50));
        assertEquals(ErrorCode.FORBIDDEN, revoked.code());
    }
}
