package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.Set;
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

/** Real transaction coverage for explicit registration decisions and atomic follow-up outcomes. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class LeadRegistrationMySqlTest {
    @Test void registrationDispositionPreservesPermissionsReplayAndAtomicColdTransition() throws Exception {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri=URI.create(url.substring(5));assertEquals("127.0.0.1",uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_[a-z0-9_]+_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var flyway=Flyway.configure().dataSource(source).cleanDisabled(false).load();flyway.clean();flyway.migrate();
        var jdbc=new JdbcTemplate(source);var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var mapper=new ObjectMapper().findAndRegisterModules();
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('registration-campus',?)",cipher.encrypt("合成校区"));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES('registration-actor','https://registration.test','subject','all')");
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(1,'lead.create')");
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) VALUES('registration-customer',1,1,?)",cipher.encrypt("合成客户"));
        var repository=new LeadRepository(jdbc,cipher);
        var factory=new ProxyFactory(new LeadService(new AccessRepository(jdbc),repository,new MutationRepository(jdbc,cipher),mapper));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service=(LeadService)factory.getProxy();
        Actor creator=new Actor(1,"registration-actor","all",Set.of(),Set.of("lead.create"));
        MDC.put("traceId","ede49e8b-049a-4412-9d61-5265d93eac59");
        try {
            var publicLead=service.create(creator,"registration-default-001",input(null));
            assertNull(publicLead.ownerId());assertEquals("public",publicLead.decisionStatus());
            assertEquals(publicLead,service.create(creator,"registration-default-001",input(null)));
            assertThrows(BusinessException.class,()->service.create(creator,"registration-no-perm-01",input("self")));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead",Integer.class));
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(1,'lead.follow')");
            Actor seller=new Actor(1,"registration-actor","all",Set.of(),Set.of("lead.create","lead.follow"));
            var self=service.create(seller,"registration-self-001",input("self"));
            assertEquals("registration-actor",self.ownerId());assertEquals("retained",self.decisionStatus());
            assertEquals("contacting",self.status());
            assertEquals("contacting",service.list(seller,new CrmDtos.LeadQuery(null,null,null,1,20))
                .items().stream().filter(row->row.id().equals(self.id())).findFirst().orElseThrow().status());
            assertEquals("跟进中",service.salesStage(seller,self.id()).stage());
            assertNull(service.salesStage(seller,self.id()).intent());
            var publicChoice=service.create(seller,"registration-public-001",input("public"));
            assertNull(publicChoice.ownerId());assertEquals("public",publicChoice.decisionStatus());
            var high=new CrmDtos.LeadFollowupInput("phone","合成高意向",null,1,"high");
            var first=service.followup(seller,self.id(),"registration-high-001",high);
            assertEquals(first,service.followup(seller,self.id(),"registration-high-001",high));
            assertEquals("high",service.salesStage(seller,self.id()).intent());
            assertThrows(BusinessException.class,()->service.followup(seller,self.id(),"registration-stale-001",high));
            jdbc.execute("CREATE TRIGGER registration_fail_follow BEFORE INSERT ON core_crm_lead_followup "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic transaction rollback'");
            assertThrows(org.springframework.dao.DataAccessException.class,()->service.followup(seller,self.id(),
                "registration-rollback-01",new CrmDtos.LeadFollowupInput("phone","合成失败",null,2,"lost_cold")));
            assertEquals(2,service.salesStage(seller,self.id()).version());
            assertEquals("assigned",service.salesStage(seller,self.id()).status());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead_followup",Integer.class));
            jdbc.execute("DROP TRIGGER registration_fail_follow");
            var lost=new CrmDtos.LeadFollowupInput("phone","合成流失",java.time.Instant.now().minusSeconds(3600),2,"lost_cold");
            var cold=service.followup(seller,self.id(),"registration-lost-001",lost);
            assertEquals("archived",cold.status());assertNull(cold.nextFollowAt());
            assertEquals(cold,service.followup(seller,self.id(),"registration-lost-001",lost));
            var stage=service.salesStage(seller,self.id());
            assertEquals("已流失",stage.stage());assertEquals("cold",stage.status());assertEquals("high",stage.intent());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead_followup",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_idempotency WHERE request_key='registration-stale-001'",Integer.class));
            verifyIntentsAndPaidProtection(jdbc,service,seller);
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(1,'lead.follow')");
            assertThrows(BusinessException.class,()->service.followup(seller,self.id(),"registration-lost-001",lost));
            assertThrows(BusinessException.class,()->service.create(seller,"registration-self-001",input("self")));
        } finally { MDC.remove("traceId"); }
    }

    private void verifyIntentsAndPaidProtection(JdbcTemplate jdbc,LeadService service,Actor seller) throws Exception {
        var unmarked=service.create(seller,"registration-unmarked-01",input("self"));
        assertNull(service.salesStage(seller,unmarked.id()).intent());
        service.followup(seller,unmarked.id(),"registration-no-intent-01",
            new CrmDtos.LeadFollowupInput("phone","合成未标记跟进",null,1));
        assertNull(service.salesStage(seller,unmarked.id()).intent());
        var lead=service.create(seller,"registration-intents-001",input("self"));
        int version=1;
        for(String intent:java.util.List.of("high","medium","low")) {
            service.followup(seller,lead.id(),"registration-intent-"+intent,
                new CrmDtos.LeadFollowupInput("phone","合成意向",null,version++,intent));
            assertEquals(intent,service.salesStage(seller,lead.id()).intent());
        }
        jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,campus_id,owner_id,title,subject,term,need_key,stage,source_lead_id) "
            +"VALUES('registration-paid-need',1,1,1,'合成需求','数学','春季',?,'qualified',(SELECT id FROM core_crm_lead WHERE public_id=?))",new byte[32],lead.id());
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,campus_id,attributed_owner_id,product_snapshot,total_amount,received_amount,status) "
            +"VALUES('registration-paid-order',1,1,1,1,'合成商品',100,1,'partially_received')");
        assertThrows(BusinessException.class,()->service.followup(seller,lead.id(),"registration-paid-lost",
            new CrmDtos.LeadFollowupInput("phone","已付款不能流失",null,4,"lost_cold")));
        assertEquals(4,service.salesStage(seller,lead.id()).version());
        assertEquals("assigned",service.salesStage(seller,lead.id()).status());
        assertThrows(BusinessException.class,()->service.followup(seller,lead.id(),"registration-empty-note",
            new CrmDtos.LeadFollowupInput("phone"," ",null,4,"lost_cold")));
    }

    private CrmDtos.LeadInput input(String choice) {
        return new CrmDtos.LeadInput("registration-customer",null,null,"front_desk","合成登记",null,null,null,null,choice);
    }
}
