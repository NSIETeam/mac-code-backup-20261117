package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;

/** Real database proof that closing public forms preserves publication history. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",
    matches="jdbc:mysql://127\\.0\\.0\\.1:53306/siyue_full_[a-f0-9]{10}_test([?].*)?")
class CrmFormLifecycleMySqlTest {
    @Test void closingIsVersionedIdempotentAuditedAndRollbackSafe()throws Exception{
        var source=new DriverManagerDataSource(System.getenv("SIYUE_TEST_MYSQL_URL"),
            System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var mapper=new ObjectMapper().findAndRegisterModules();
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('form-campus',?)",cipher.encrypt("合成校区"));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) "
            +"VALUES('form-admin','https://form-test.invalid','form-admin','all')");
        long actorId=jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id='form-admin'",Long.class);
        for(String permission:List.of("crm.form.write","crm.form.publish","crm.form.read"))
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)",actorId,permission);
        var access=new AccessRepository(jdbc);
        var forms=new CrmFormRepository(jdbc,cipher,mapper);
        var plain=new CrmFormService(access,forms,new MutationRepository(jdbc,cipher),mapper);
        var proxy=new ProxyFactory(plain);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service=(CrmFormService)proxy.getProxy();
        Actor actor=access.resolve("https://form-test.invalid","form-admin");
        MDC.put("traceId","form-lifecycle-synthetic");
        try{
            var input=new CrmDtos.MarketingFormInput("form-campus","合成问卷",null,"survey",false,
                List.of(new CrmDtos.FormFieldInput("q","问题","text",null,false,List.of())),Map.of());
            var draft=service.create(actor,"form-create-test-0001",input);
            var published=service.publish(actor,draft.id(),"form-publish-test-0001",new CrmDtos.MarketingFormVersion(draft.version()));
            assertNotNull(forms.publicDefinition(draft.id(),null));
            var version=new CrmDtos.MarketingFormVersion(published.version());
            jdbc.execute("CREATE TRIGGER form_stop_audit_failure BEFORE INSERT ON core_audit FOR EACH ROW "
                +"BEGIN IF NEW.action='crm.form.unpublish' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'; END IF; END");
            assertThrows(RuntimeException.class,()->service.unpublish(actor,draft.id(),"form-stop-test-0001",version));
            assertNotNull(forms.publicDefinition(draft.id(),null));
            assertEquals(published.version(),forms.read(actor,draft.id()).version());
            jdbc.execute("DROP TRIGGER form_stop_audit_failure");
            var closed=service.unpublish(actor,draft.id(),"form-stop-test-0001",version);
            assertEquals("closed",closed.status());
            assertEquals(closed,service.unpublish(actor,draft.id(),"form-stop-test-0001",version));
            assertThrows(BusinessException.class,()->forms.publicDefinition(draft.id(),null));
            assertThrows(BusinessException.class,()->service.unpublish(actor,draft.id(),"form-stop-stale-0001",version));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_form_revision",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM core_audit WHERE action='crm.form.unpublish'",Integer.class));
            jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=? AND permission_code='crm.form.publish'",actorId);
            assertThrows(BusinessException.class,()->service.unpublish(actor,draft.id(),"form-stop-test-0001",version));
        }finally{MDC.remove("traceId");}
    }
}
