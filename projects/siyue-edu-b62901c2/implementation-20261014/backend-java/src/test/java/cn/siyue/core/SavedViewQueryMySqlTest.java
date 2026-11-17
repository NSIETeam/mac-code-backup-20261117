package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Verifies imported saved-view filters against synthetic facts on the supported MySQL schema. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class SavedViewQueryMySqlTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void searchesAllFiveResourcesAndBackfillsExistingEncryptedIndexes() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_saved_view_[a-f0-9]{8}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode envelope = new LegacyCrmRehearsalMySqlTest().fixture(mapper);
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
            + "VALUES('legacy-crm-actor','https://migration-rehearsal.invalid','legacy-crm-actor','all',1)");
        PiiCipher cipher = new PiiCipher(KEY);
        DataSourceTransactionManager transaction = new DataSourceTransactionManager(source);
        assertEquals(5, new LegacyCatalogRehearsal(jdbc, cipher, transaction).importCatalog(envelope));
        assertEquals(10, new LegacyCrmRehearsal(jdbc, cipher, transaction).importCrm(envelope));
        assertEquals(5, new LegacyServiceHistoryRehearsal(jdbc, cipher, transaction)
            .importServiceHistory(envelope));
        insertSyntheticTask(jdbc);

        Actor actor = new Actor(actorId(jdbc), "legacy-crm-actor", "all", Set.of(), Set.of(
            "crm.customer.read", "crm.lead.read", "crm.opportunity.read", "crm.task.read",
            "crm.ticket.read", "report.read"));
        assertEquals("legacy-customer", new CrmRepository(jdbc, cipher).customers(actor,
            query("customers", "合成联系人"), 1, 20).items().get(0).id());
        assertEquals("legacy-lead", new LeadRepository(jdbc, cipher).list(actor,
            new CrmDtos.LeadQuery(null, null, null, 1, 20), query("leads", "合成线索"))
            .items().get(0).id());
        assertEquals("legacy-opportunity", new OpportunityRepository(jdbc, cipher).list(actor,
            new OpportunityListFilters(null, null, 1, 20), query("opportunities", "合成商机"))
            .items().get(0).id());
        assertEquals("synthetic-task", new CrmTaskRepository(jdbc, cipher).list(actor,
            new CrmDtos.TaskQuery(null, null, null, null, 1, 20), query("crmTasks", "合成任务"))
            .items().get(0).id());

        jdbc.update("UPDATE core_customer_name_index SET search_ngrams=NULL WHERE customer_id="
            + "(SELECT id FROM core_customer WHERE public_id='legacy-customer')");
        jdbc.update("UPDATE core_customer_contact_index SET name_search_ngrams=NULL,value_search_ngrams=NULL "
            + "WHERE contact_id IN (SELECT id FROM core_customer_contact WHERE customer_id="
            + "(SELECT id FROM core_customer WHERE public_id='legacy-customer'))");
        jdbc.update("DELETE FROM core_crm_lead_search_index WHERE lead_id="
            + "(SELECT id FROM core_crm_lead WHERE public_id='legacy-lead')");
        try (var context = backfillContext(jdbc, cipher, transaction)) {
            var service = context.getBean(SavedViewSearchIndexBackfillService.class);
            assertTrue(AopUtils.isAopProxy(service));
            new SavedViewSearchIndexBackfill(service, 50, 10).run(null);
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_customer_name_index WHERE search_ngrams IS NOT NULL",
            Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM core_customer_contact_index "
            + "WHERE value_search_ngrams IS NOT NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_customer_contact_index "
            + "WHERE name_search_ngrams IS NOT NULL", Integer.class));
        assertEquals("合成家长", new CrmRepository(jdbc, cipher).customers(actor,
            query("customers", "合成家长"), 1, 20).items().get(0).displayName());
        assertEquals("legacy-lead", new LeadRepository(jdbc, cipher).list(actor,
            new CrmDtos.LeadQuery(null, null, null, 1, 20), query("leads", "合成线索"))
            .items().get(0).id());
        assertEquals("legacy-ticket", new CrmServiceTicketRepository(jdbc, cipher).list(actor, null, 1, 20,
            query("serviceTickets", "合成工单")).items().get(0).id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_service_ticket_search_index",
            Integer.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class BackfillTransactions { }

    @Test
    void boundsFourKindsAndEveryKeyAt999AndResumesWholeLeadGroups() throws Exception {
        JdbcTemplate jdbc = freshDatabase();
        PiiCipher cipher = new PiiCipher(Map.of("test", KEY, "second", KEY), "test");
        seedBackfill(jdbc, cipher, 125);
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        try (var context = backfillContext(jdbc, cipher, manager)) {
            var service = context.getBean(SavedViewSearchIndexBackfillService.class);
            assertTrue(AopUtils.isAopProxy(service));
            assertThrows(IllegalStateException.class, () -> new SavedViewSearchIndexBackfill(service, 500, 1).run(null));
            // 3 name UPDATEs + 2 contact UPDATEs + 124*4*2 lead INSERTs + 1*2 ticket INSERTs.
            assertEquals(999, completedIndexRows(jdbc));
            assertEquals(1, service.remainingRows());
            assertEquals(124, jdbc.queryForObject("SELECT COUNT(DISTINCT lead_id) FROM core_crm_lead_search_index",
                Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT lead_id FROM core_crm_lead_search_index "
                + "GROUP BY lead_id HAVING COUNT(*)<>8) incomplete", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_service_ticket_search_index", Integer.class));
            assertEquals(new SavedViewSearchIndexBackfillService.Batch(1, 8), service.processBatch(500));
            assertEquals(1007, completedIndexRows(jdbc));
            assertEquals(0, service.remainingRows());
            new SavedViewSearchIndexBackfill(service, 500, 1).run(null);
            assertEquals(1007, completedIndexRows(jdbc));
            String json = jdbc.queryForObject("SELECT search_ngrams FROM core_crm_lead_search_index "
                + "WHERE lead_id=(SELECT MIN(id) FROM core_crm_lead) AND field_name='name' AND key_id='second'", String.class);
            assertEquals(new ObjectMapper().readTree(SavedViewSearchNgrams.encode(cipher, "合成姓名").get("second")),
                new ObjectMapper().readTree(json));
            assertFalse(json.contains("合成姓名"));
        }
    }

    @Test
    void failedBatchRollsBackEarlierKindsAndCanResumeAfterSourceRepair() {
        JdbcTemplate jdbc = freshDatabase();
        PiiCipher cipher = new PiiCipher(KEY);
        seedBackfill(jdbc, cipher, 1);
        jdbc.update("UPDATE core_crm_lead SET phone_cipher='invalid-ciphertext'");
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        try (var context = backfillContext(jdbc, cipher, manager)) {
            var service = context.getBean(SavedViewSearchIndexBackfillService.class);
            assertThrows(RuntimeException.class, () -> service.processBatch(500));
            assertEquals(0, completedIndexRows(jdbc));
            jdbc.update("UPDATE core_crm_lead SET phone_cipher=?", cipher.encrypt("合成电话"));
            assertEquals(new SavedViewSearchIndexBackfillService.Batch(7, 10), service.processBatch(500));
            assertEquals(10, completedIndexRows(jdbc));
            assertEquals(0, service.remainingRows());
        }
    }

    private AnnotationConfigApplicationContext backfillContext(JdbcTemplate jdbc, PiiCipher cipher,
            DataSourceTransactionManager manager) {
        var context = new AnnotationConfigApplicationContext();
        context.register(BackfillTransactions.class);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PiiCipher.class, () -> cipher);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.register(SavedViewSearchIndexBackfillRepository.class, SavedViewSearchIndexBackfillService.class);
        context.refresh();
        return context;
    }

    private JdbcTemplate freshDatabase() {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_saved_view_[a-f0-9]{8}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        return new JdbcTemplate(source);
    }

    private void seedBackfill(JdbcTemplate jdbc, PiiCipher cipher, int leads) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) "
            + "VALUES('backfill-actor','https://backfill.invalid','backfill','all')");
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO core_customer(id,public_id,owner_id,display_name_cipher) VALUES(?,?,1,?)",
                i, "customer-" + i, cipher.encrypt("合成客户"));
            jdbc.update("INSERT INTO core_customer_name_index(customer_id,key_id,value_hash) VALUES(?,'test',?)",
                i, new byte[32]);
        }
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO core_customer_contact(id,public_id,customer_id,person_role,channel,value_cipher) "
                + "VALUES(?,?,1,'guardian','phone',?)", i, "contact-" + i, cipher.encrypt("合成电话"));
            jdbc.update("INSERT INTO core_customer_contact_index(contact_id,key_id,value_hash) VALUES(?,'test',?)",
                i, new byte[32]);
        }
        for (int i = 0; i < leads; i++) {
            jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,source,title,name_cipher,phone_cipher,"
                + "contact_phone_cipher,guardian_name_cipher) VALUES(?,1,'front_desk','合成线索',?,?,?,?)",
                "lead-" + i, cipher.encrypt("合成姓名"), cipher.encrypt("合成电话"),
                cipher.encrypt("合成联系人电话"), cipher.encrypt("合成监护人"));
        }
        jdbc.update("INSERT INTO core_crm_service_ticket(public_id,customer_id,ticket_type_id,owner_id,created_by,"
            + "title_cipher,description_cipher) VALUES('ticket',1,1,1,1,?,?)", cipher.encrypt("合成工单"), cipher.encrypt("合成描述"));
    }

    private int completedIndexRows(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM core_customer_name_index WHERE search_ngrams IS NOT NULL)"
            + "+(SELECT COUNT(*) FROM core_customer_contact_index WHERE value_search_ngrams IS NOT NULL)"
            + "+(SELECT COUNT(*) FROM core_crm_lead_search_index)"
            + "+(SELECT COUNT(*) FROM core_crm_service_ticket_search_index)", Integer.class);
    }

    private LegacySavedViewQuery query(String resource, String search) {
        return new LegacySavedViewQuery(resource, "team", "all", search, "updatedAt:desc", "createdAt");
    }

    private void insertSyntheticTask(JdbcTemplate jdbc) {
        long actor = actorId(jdbc);
        long campus = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id='legacy-campus'", Long.class);
        long customer = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id='legacy-customer'", Long.class);
        jdbc.update("INSERT INTO core_crm_task_batch(public_id,created_by,title,task_type,campus_id,due_at) "
            + "VALUES('synthetic-task-batch',?,'synthetic batch','marketing',?,UTC_TIMESTAMP(3))", actor, campus);
        jdbc.update("INSERT INTO core_crm_task(public_id,batch_id,customer_id,campus_id,owner_id,task_type,title,due_at) "
            + "VALUES('synthetic-task',(SELECT id FROM core_crm_task_batch WHERE public_id='synthetic-task-batch'),"
            + "?,?,?,'marketing','合成任务',UTC_TIMESTAMP(3))", customer, campus, actor);
    }

    private long actorId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id='legacy-crm-actor'", Long.class);
    }
}
