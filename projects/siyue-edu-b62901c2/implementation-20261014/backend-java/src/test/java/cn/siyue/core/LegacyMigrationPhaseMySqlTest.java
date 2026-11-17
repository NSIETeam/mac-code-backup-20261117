package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies both the offline annotation proxy and container proxy against a dedicated real MySQL schema. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class LegacyMigrationPhaseMySqlTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    private record Database(JdbcTemplate jdbc, DataSourceTransactionManager manager) { }

    @Test void eachCliStylePhaseCommitsOrRollsBackItsCheckpointWithItsRows() {
        Database db = database();
        var service = LegacyMigrationPhaseTransactions.create(db.manager());
        assertTrue(AopUtils.isAopProxy(service));
        assertEquals(1, service.execute(() -> insert(db.jdbc(), "committed")));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var failure = new IllegalStateException("synthetic phase failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.execute(() -> {
            insert(db.jdbc(), "failed");
            throw failure;
        })));
        assertEquals(1, count(db.jdbc(), "committed"));
        assertEquals(0, count(db.jdbc(), "failed"));
    }

    @Test void requiredPropagationRetainsOriginalOuterRollbackSemantics() {
        Database db = database();
        var service = LegacyMigrationPhaseTransactions.create(db.manager());
        var outer = new TransactionTemplate(db.manager());
        assertThrows(IllegalArgumentException.class, () -> outer.execute(status -> {
            service.execute(() -> insert(db.jdbc(), "outer-first"));
            service.execute(() -> insert(db.jdbc(), "outer-second"));
            throw new IllegalArgumentException("synthetic outer failure");
        }));
        assertEquals(0, count(db.jdbc(), "outer-first"));
        assertEquals(0, count(db.jdbc(), "outer-second"));
    }

    @Test void springContainerUsesTheSameAnnotationAndRollsBackOnFailure() {
        Database db = database();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class, db::manager);
            context.register(LegacyMigrationPhaseService.class);
            context.refresh();
            var service = context.getBean(LegacyMigrationPhaseService.class);
            assertTrue(AopUtils.isAopProxy(service));
            assertThrows(IllegalStateException.class, () -> service.execute(() -> {
                insert(db.jdbc(), "container-failed");
                throw new IllegalStateException("synthetic container failure");
            }));
        }
        assertEquals(0, count(db.jdbc(), "container-failed"));
    }

    @Test void unadvisedServiceRefusesToExecuteWork() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertThrows(IllegalStateException.class, () -> new LegacyMigrationPhaseService().execute(() -> {
            fail("Unadvised phase must never execute");
            return 0;
        }));
    }

    private Database database() {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(53306, uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_full_[a-f0-9]{10}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS migration_phase_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "phase VARCHAR(50) NOT NULL UNIQUE) ENGINE=InnoDB");
        return new Database(jdbc, new DataSourceTransactionManager(source));
    }

    private int insert(JdbcTemplate jdbc, String phase) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        return jdbc.update("INSERT INTO migration_phase_probe(phase) VALUES(?)", phase);
    }

    private int count(JdbcTemplate jdbc, String phase) {
        return jdbc.queryForObject("SELECT COUNT(id) FROM migration_phase_probe WHERE phase=?", Integer.class, phase);
    }
}
