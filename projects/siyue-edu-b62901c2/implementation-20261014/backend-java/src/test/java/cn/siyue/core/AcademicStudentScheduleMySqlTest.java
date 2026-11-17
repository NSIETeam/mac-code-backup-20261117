package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Verifies the scoped, deduplicated student schedule against an isolated MySQL schema. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/siyue_[A-Za-z0-9_]+_test(?:\\?.*)?")
class AcademicStudentScheduleMySqlTest {
    @Test
    void scheduleIsBoundedDeduplicatedAndConcurrentForSharedReads() {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_[A-Za-z0-9_]+_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        AcademicReadMySqlAssertions.verifyStudentSchedule(jdbc, cipher);
    }
}
