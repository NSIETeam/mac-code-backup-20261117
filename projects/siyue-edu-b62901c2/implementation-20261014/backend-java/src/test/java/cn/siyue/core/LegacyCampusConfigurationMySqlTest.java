package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class LegacyCampusConfigurationMySqlTest {
    @Test void preservesSourceAndRollsBackBeforeReadiness() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost()); assertEquals(53306, uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_full_[a-f0-9]{10}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"), System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        var jdbc = new JdbcTemplate(source);
        var cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var manager = new DataSourceTransactionManager(source);
        var envelope = new ObjectMapper().readTree("""
            {"schema":"siyue.legacy-business/v1","revision":1,"state":{
              "campuses":[{"id":"CAMPUS_CFG","name":"合成校区","active":true,"version":7,
                "createdAt":"2026-10-16T00:00:00Z","updatedAt":"2026-10-16T00:00:00Z",
                "address":"合成地址","phone":"10000","rooms":1,
                "roomDetails":[{"id":"ROOM_CFG","name":"教室甲","capacity":20,"virtualSeats":3}],
                "subjects":["历史自定义学科"],"grades":["小一"]}],
              "courses":[],"classes":[],"students":[],
              "settings":{"attendance":{"presentConsume":false},"organization":{"name":"模拟机构"}},
              "dictionary":{"subjects":["历史自定义学科"],"terms":["秋季"]}}}
            """);
        var migration = new LegacyCampusConfigurationRehearsal(jdbc, cipher, manager);
        assertThrows(IllegalArgumentException.class, () -> migration.importConfiguration(envelope));
        new LegacyCatalogRehearsal(jdbc, cipher, manager).importCatalog(envelope);
        jdbc.execute("CREATE TRIGGER fail_campus_dictionary BEFORE INSERT ON core_academic_dictionary_value "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> migration.importConfiguration(envelope));
        jdbc.execute("DROP TRIGGER fail_campus_dictionary");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_campus WHERE configuration_ready=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_campus_room", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='campus_configuration_v1'", Integer.class));
        assertEquals(7, migration.importConfiguration(envelope));
        assertEquals(7, migration.importConfiguration(envelope));
        assertEquals(7, jdbc.queryForObject("SELECT version FROM core_campus", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_campus WHERE configuration_ready=1", Integer.class));
        assertEquals("合成地址", cipher.decrypt(jdbc.queryForObject("SELECT address_cipher FROM core_campus", String.class)));
        assertEquals("小一", jdbc.queryForObject("SELECT value FROM core_campus_academic_option WHERE kind='grade'", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT virtual_seats FROM core_campus_room", Integer.class));
        assertEquals(20, jdbc.queryForObject("SELECT capacity FROM core_campus_room", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(id) FROM core_academic_dictionary_value", Integer.class));
        var settingsMigration = new LegacySystemSettingsRehearsal(jdbc, cipher, manager);
        jdbc.execute("CREATE TRIGGER fail_system_settings BEFORE INSERT ON core_system_setting FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");
        try { assertThrows(org.springframework.dao.DataAccessException.class, () -> settingsMigration.importSettings(envelope)); }
        finally { jdbc.execute("DROP TRIGGER fail_system_settings"); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_system_setting", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='system_settings_v1'", Integer.class));
        assertEquals(2, settingsMigration.importSettings(envelope));
        assertEquals(2, settingsMigration.importSettings(envelope));
        assertEquals("false", cipher.decrypt(jdbc.queryForObject("SELECT value_cipher FROM core_system_setting WHERE section='attendance'", String.class)));
        var changed = envelope.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("state").path("campuses").get(0)).put("version", 8);
        assertThrows(IllegalArgumentException.class, () -> migration.importConfiguration(changed));
        assertThrows(IllegalArgumentException.class, () -> settingsMigration.importSettings(changed));
    }
}
