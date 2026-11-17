package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.flywaydb.core.Flyway;
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
import org.springframework.transaction.support.TransactionTemplate;

/** Uses real Spring proxies and a dedicated database to exercise the moved transaction boundaries. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class TransactionBoundaryMySqlTest {
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    static class Transactions { }

    @Test void realServiceProxiesRetainRateHitsAfterOuterRollbackAndRecheckFinancialScope() {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPath().endsWith("_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var flyway=Flyway.configure().dataSource(source).cleanDisabled(false).load();flyway.clean();flyway.migrate();
        var jdbc=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));seed(jdbc,cipher);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(JdbcTemplate.class,()->jdbc);
            context.registerBean(PiiCipher.class,()->cipher);
            context.registerBean(PlatformTransactionManager.class,()->manager);
            context.register(AccessRepository.class,FinanceWorkspaceRepository.class,FinanceWorkspaceService.class,
                CrmFormRateRepository.class,CrmFormRateLimiter.class,PublicStoreRateRepository.class,PublicStoreRateLimiter.class);
            context.refresh();
            var forms=context.getBean(CrmFormRateLimiter.class);var store=context.getBean(PublicStoreRateLimiter.class);
            var finance=context.getBean(FinanceWorkspaceService.class);
            assertTrue(AopUtils.isAopProxy(forms));assertTrue(AopUtils.isAopProxy(store));assertTrue(AopUtils.isAopProxy(finance));
            independentRates(jdbc,forms,store,manager);
            financialScope(jdbc,finance);
        }
    }
    private void independentRates(JdbcTemplate jdbc,CrmFormRateLimiter forms,PublicStoreRateLimiter store,
            DataSourceTransactionManager manager) {
        Instant now=Instant.parse("2026-10-17T00:00:00Z");
        for(int index=0;index<10;index++) {
            assertTrue(forms.allow(1,"192.0.2.1",now));assertTrue(store.allow("submission","192.0.2.1",now));
        }
        var transaction=new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{
            assertFalse(forms.allow(1,"192.0.2.1",now));assertFalse(store.allow("submission","192.0.2.1",now));
            throw new IllegalStateException("synthetic outer rollback");
        }));
        assertEquals(11,jdbc.queryForObject("SELECT request_count FROM core_crm_form_public_rate",Integer.class));
        assertEquals(11,jdbc.queryForObject("SELECT request_count FROM core_public_store_rate",Integer.class));
        assertThrows(IllegalArgumentException.class,()->store.allow("unknown","192.0.2.1",now));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_public_store_rate",Integer.class));
    }
    private void financialScope(JdbcTemplate jdbc,FinanceWorkspaceService finance) {
        var filter=new FinanceWorkspaceRepository.Filter(1,20,null,null,null,null);
        assertEquals(1,finance.arrears("https://boundary.test","reader",filter).total());
        assertEquals(0,finance.refunds("https://boundary.test","reader",filter).total());
        assertEquals(0,finance.consumptions("https://boundary.test","reader",filter).total());
        jdbc.update("UPDATE core_principal_campus SET deleted=1 WHERE principal_id=1");
        assertEquals(0,finance.arrears("https://boundary.test","reader",filter).total());
        jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=1 AND permission_code='crm.order.read'");
        assertThrows(BusinessException.class,()->finance.arrears("https://boundary.test","reader",filter));
        jdbc.update("UPDATE core_principal SET active=0 WHERE id=1");
        assertThrows(BusinessException.class,()->finance.consumptions("https://boundary.test","reader",filter));
    }
    private void seed(JdbcTemplate jdbc,PiiCipher cipher) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('boundary-campus',?)",cipher.encrypt("合成校区"));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES('boundary-reader','https://boundary.test','reader','campus')");
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(1,1)");
        for(String permission:List.of("crm.order.read","crm.refund.read","consumption.read"))
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(1,?)",permission);
        jdbc.update("INSERT INTO core_crm_form(public_id,campus_id,owner_id,title,form_kind,fields_json) VALUES('boundary-form',1,1,'合成表单','survey',JSON_ARRAY())");
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,campus_id,owner_id) VALUES('boundary-student',?,1,1)",cipher.encrypt("合成学员"));
        jdbc.update("INSERT INTO core_crm_order(public_id,student_id,campus_id,attributed_owner_id,product_snapshot,subject_snapshot,total_amount,source_kind) VALUES('boundary-order',1,1,1,'合成课程','数学',100,'direct_enrollment')");
    }
}
