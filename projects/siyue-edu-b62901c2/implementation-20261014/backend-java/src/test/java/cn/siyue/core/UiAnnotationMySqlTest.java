package cn.siyue.core;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class UiAnnotationMySqlTest {
    @Test void encryptedQueueReplayVersionAndAuditUseRealMysql() throws Exception {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPath().endsWith("_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);var mapper=new ObjectMapper().findAndRegisterModules();
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String suffix=UUID.randomUUID().toString(),subject="annotation-"+suffix;
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,?)",subject,"https://test.example",subject,"all");
        Long principal=jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",Long.class,subject);
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)",principal,"system.access.manage");
        var access=new AccessRepository(jdbc);var actor=access.resolve("https://test.example",subject);
        var service=new UiAnnotationService(access,new UiAnnotationRepository(jdbc,cipher,mapper),new MutationRepository(jdbc,cipher),mapper);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var input=new UiAnnotationDtos.Create("/crm/#test","模拟页","other","流程图","#flow","模拟修改建议","workflow","模拟流程","甲->乙","甲->丙->乙");
        MDC.put("traceId",suffix);
        try {
            var created=tx.execute(s->call(()->service.create(actor,"annotation-create-"+suffix,input)));
            var replay=tx.execute(s->call(()->service.create(actor,"annotation-create-"+suffix,input)));
            assertEquals(created,replay);
            String stored=jdbc.queryForObject("SELECT payload_cipher FROM core_ui_annotation WHERE public_id=?",String.class,created.id());
            assertFalse(stored.contains("模拟"));assertEquals(input,mapper.readValue(cipher.decrypt(stored),UiAnnotationDtos.Create.class));
            var page=tx.execute(s->service.list(actor,"open",1,100));assertTrue(page.annotations().stream().anyMatch(a->a.id().equals(created.id())));
            var resolved=tx.execute(s->call(()->service.update(actor,created.id(),"annotation-resolve-"+suffix,new UiAnnotationDtos.Update("resolved",1,true))));
            assertEquals(2,resolved.version());assertEquals(input,resolved.request());
            assertThrows(BusinessException.class,()->tx.execute(s->call(()->service.update(actor,created.id(),"annotation-stale-"+suffix,new UiAnnotationDtos.Update("open",1,false)))));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_audit WHERE actor_id=?",Integer.class,principal));
        } finally {MDC.remove("traceId");}
    }
    private static <T> T call(Callable<T> body) {
        try {return body.call();} catch(RuntimeException e) {throw e;} catch(Exception e) {throw new IllegalStateException(e);}
    }
}
