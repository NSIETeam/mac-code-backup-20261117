package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class LegacyUiAnnotationMySqlTest {
    @Test void preservesEncryptedSourceAuditAndAtomicReplay() throws Exception {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL"); var uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost()); assertEquals(53306,uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_[a-z0-9_]+_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate(); var jdbc=new JdbcTemplate(source);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var migration=new LegacyUiAnnotationRehearsal(jdbc,cipher,new DataSourceTransactionManager(source));
        var envelope=LegacyUiAnnotationRehearsalTest.envelope();
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES('actor','https://test.invalid','actor','all')");
        assertThrows(IllegalArgumentException.class,()->migration.importAnnotations(envelope));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM core_ui_annotation",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM core_migration_checkpoint",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM core_audit",Integer.class));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES('reviewer','https://test.invalid','reviewer','all')");
        assertEquals(3,migration.importAnnotations(envelope)); assertEquals(3,migration.importAnnotations(envelope));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM core_ui_annotation",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM core_audit",Integer.class));
        var view=new UiAnnotationRepository(jdbc,cipher,new ObjectMapper()).get("11111111-1111-4111-8111-111111111111",false);
        assertEquals("模拟管理",view.createdByName());assertEquals("resolved",view.status());assertEquals(2,view.version());assertEquals("actor",view.createdBy());
        assertEquals(java.time.Instant.parse("2026-10-16T00:01:00Z"),view.updatedAt());
        String stored=jdbc.queryForObject("SELECT source_cipher FROM core_migration_checkpoint",String.class);
        assertFalse(stored.contains("模拟"));assertEquals(envelope.path("state").path("authFeedback"),new ObjectMapper().readTree(cipher.decrypt(stored)));
        var changed=envelope.deepCopy();((ObjectNode)changed.path("state").path("authFeedback").path("annotations").get(0)).put("issue","另一建议");
        assertThrows(IllegalArgumentException.class,()->migration.importAnnotations(changed));
        assertEquals("reviewer",jdbc.queryForObject("SELECT p.public_id FROM core_audit a JOIN core_principal p ON p.id=a.actor_id WHERE a.action='ui.annotation.update'",String.class));
    }
}
