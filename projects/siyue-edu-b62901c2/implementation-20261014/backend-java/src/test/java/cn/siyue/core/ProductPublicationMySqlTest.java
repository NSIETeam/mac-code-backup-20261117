package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;

class ProductPublicationMySqlTest {
    @Test void legacyPublisherPreservesSnapshotScopeReplayAndTeachingGate() throws Exception {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");
        Assumptions.assumeTrue(url!=null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('campus',?)",cipher.encrypt("校区"));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES('admin','https://issuer.example','admin','all')");
        for(String permission:List.of("product.publish"))
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(1,?)",permission);
        jdbc.update("INSERT INTO core_course(public_id,name,course_type,grade,subject,price) VALUES('COURSE','课程','onsite','一年级','数学',100)");
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES('LESSON',1,1,'合成讲次')");
        TeachingPublicationFixtures.published(jdbc,1);
        for(int i=1;i<=2;i++)jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) VALUES(?,1,1,1,?,'A',20,60)","CLASS_"+i,"班级"+i);
        var access=new AccessRepository(jdbc);var repo=new CommerceRepository(jdbc);
        var plain=new ProductPublicationService(access,new AcademicCatalogRepository(jdbc,cipher),repo,
            new MutationRepository(jdbc,cipher),new ObjectMapper().findAndRegisterModules());
        var proxy=new ProxyFactory(plain);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service=(ProductPublicationService)proxy.getProxy();var actor=access.resolve("https://issuer.example","admin");
        MDC.put("traceId","publication-mysql-check");
        try {
            var input=new CommerceDtos.PublicationInput(null,1L);
            var product=service.publish(actor,"COURSE","product-publication-01",input,true);
            assertEquals(2,product.classIds().size());
            assertTrue(product.published());
            assertEquals(product,service.publish(actor,"COURSE","product-publication-01",input,true));
            assertEquals("课程",jdbc.queryForObject("SELECT storefront_title FROM core_commerce_product",String.class));
            assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT storefront_price FROM core_commerce_product",BigDecimal.class)));
            assertEquals(1,jdbc.queryForObject("SELECT storefront_course_version FROM core_commerce_product",Long.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM core_audit WHERE action='product.publish'",Integer.class));
            assertThrows(BusinessException.class,()->service.publish(actor,"COURSE","product-publication-01",new CommerceDtos.PublicationInput(List.of("CLASS_1"),1L),true));
            assertThrows(BusinessException.class,()->service.publish(actor,"COURSE","product-stale-00001",new CommerceDtos.PublicationInput(null,2L),true));
            assertThrows(BusinessException.class,()->service.publish(actor,"COURSE","product-scope-00001",new CommerceDtos.PublicationInput(List.of("UNKNOWN"),1L),true));
            var unpublished=service.publish(actor,"COURSE","product-unpublish-0001",input,false);
            assertFalse(unpublished.published());
            assertEquals(2,unpublished.version());
            jdbc.update("UPDATE core_course SET teaching_published=0 WHERE id=1");
            assertThrows(BusinessException.class,()->service.publish(actor,"COURSE","product-teaching-0001",input,true));
            assertFalse(jdbc.queryForObject("SELECT published FROM core_commerce_product",Boolean.class));
            jdbc.update("UPDATE core_principal SET scope_type='own' WHERE id=1");
            assertThrows(BusinessException.class,()->service.publish(actor,"COURSE","product-publication-01",input,true));
            jdbc.update("UPDATE core_principal SET scope_type='all' WHERE id=1");
            jdbc.update("DELETE FROM core_principal_permission WHERE principal_id=1");
            assertThrows(BusinessException.class,()->service.publish(actor,"COURSE","product-publication-01",input,true));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM core_audit WHERE action LIKE 'product.%'",Integer.class));
        } finally { MDC.remove("traceId"); }
    }
}
