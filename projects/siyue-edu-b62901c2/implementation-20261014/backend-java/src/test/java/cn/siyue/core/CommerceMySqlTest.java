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

class CommerceMySqlTest {
    @Test void publicationRetainsClassSelectionAndProtectsActivePromotionAndScope() throws Exception {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");
        Assumptions.assumeTrue(url!=null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('campus',?)",cipher.encrypt("校区"));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES('admin','https://issuer.example','admin','all')");
        for(String permission:List.of("commerce.read","commerce.manage"))
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(1,?)",permission);
        jdbc.update("INSERT INTO core_course(public_id,name,course_type,grade,subject,price) VALUES('COURSE','课程','onsite','一年级','数学',100)");
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES('LESSON',1,1,'合成讲次')");
        TeachingPublicationFixtures.published(jdbc,1);
        for(int i=1;i<=2;i++)jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) VALUES(?,1,1,1,?,'A',20,60)","CLASS_"+i,"班级"+i);
        var access=new AccessRepository(jdbc);var repo=new CommerceRepository(jdbc);
        var plain=new CommerceService(access,repo,new MutationRepository(jdbc,cipher),new ObjectMapper().findAndRegisterModules(),Validation.buildDefaultValidatorFactory().getValidator());
        var proxy=new ProxyFactory(plain);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service=(CommerceService)proxy.getProxy();var actor=access.resolve("https://issuer.example","admin");
        MDC.put("traceId","commerce-mysql-check");
        try {
            assertEquals(2,service.courseClasses(actor,"COURSE",1,1).total());
            assertNotEquals(service.courseClasses(actor,"COURSE",1,1).items().get(0).id(),service.courseClasses(actor,"COURSE",2,1).items().get(0).id());
            var input=new CommerceDtos.ProductInput("COURSE","商城课程",List.of("CLASS_1","CLASS_2"),true,null);
            var product=service.saveProduct(actor,null,"commerce-product-0001",input);
            assertEquals(product,service.saveProduct(actor,null,"commerce-product-0001",input));
            product=service.saveProduct(actor,product.id(),"commerce-product-0002",new CommerceDtos.ProductInput("COURSE","商城课程",List.of("CLASS_2"),true,1L));
            assertEquals(List.of("CLASS_2"),service.products(actor,1,50).items().get(0).classIds());
            var promotionInput=new CommerceDtos.PromotionInput("group","测试拼团","合成活动介绍",LocalDate.now(),LocalDate.now().plusDays(2),BigDecimal.TEN,2,24,false,List.of("在读学员"),List.of(new CommerceDtos.Selection("COURSE",List.of("CLASS_2"))),true,null);
            var promotion=service.savePromotion(actor,null,"commerce-promotion-01",promotionInput);
            assertEquals(promotion,service.savePromotion(actor,null,"commerce-promotion-01",promotionInput));
            assertEquals(1,service.promotions(actor,1,50,"group").total());
            assertEquals(0,service.promotions(actor,1,50,"joint").total());
            String productId=product.id();
            assertThrows(BusinessException.class,()->service.saveProduct(actor,productId,"commerce-product-0003",new CommerceDtos.ProductInput("COURSE","商城课程",List.of("CLASS_1"),true,2L)));
            assertEquals(2,service.products(actor,1,50).items().get(0).version());
            assertEquals(List.of("CLASS_2"),service.products(actor,1,50).items().get(0).classIds());
            assertThrows(BusinessException.class,()->service.saveProduct(actor,productId,"commerce-product-stale",new CommerceDtos.ProductInput("COURSE","商城课程",List.of("CLASS_2"),true,1L)));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM core_audit WHERE action LIKE 'commerce.%'",Integer.class));
            StudentExportMySqlAssertions.verify(jdbc,cipher);
            jdbc.update("UPDATE core_principal SET scope_type='own' WHERE id=1");
            assertThrows(BusinessException.class,()->service.saveProduct(actor,null,"commerce-product-0001",input));
        } finally { MDC.remove("traceId"); }
    }
}
