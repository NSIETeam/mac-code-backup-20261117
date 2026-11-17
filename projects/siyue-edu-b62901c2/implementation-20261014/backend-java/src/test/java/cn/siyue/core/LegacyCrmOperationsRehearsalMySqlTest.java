package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class LegacyCrmOperationsRehearsalMySqlTest {
    @Test void rejectsRuleHistoryWithMissingActorAndRollsBack() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_crm_operations_[a-f0-9]{8}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode envelope = withOperations(new LegacyCrmRehearsalMySqlTest().fixture(mapper), mapper);
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("state").path("routingRules").get(0)
            .path("history").get(0)).put("actorId", "missing-synthetic-actor");
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
            + "VALUES('legacy-crm-actor','https://migration-rehearsal.invalid','legacy-crm-actor','all',0)");
        var cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var manager = new DataSourceTransactionManager(source);
        assertEquals(5, new LegacyCatalogRehearsal(jdbc, cipher, manager).importCatalog(envelope));
        assertEquals(10, new LegacyCrmRehearsal(jdbc, cipher, manager).importCrm(envelope));
        var importer = new LegacyCrmOperationsRehearsal(jdbc, cipher, manager);
        assertThrows(IllegalArgumentException.class, () -> importer.importCrmOperations(envelope));
        assertEquals(0, count(jdbc, "core_crm_routing_rule"));
        assertEquals(0, countPhase(jdbc));
    }

    @Test void importsCrmOperatingHistoryAtomicallyAndIdempotently() throws Exception {
        verifyImport(false);
    }

    @Test void preservesStructuredDetailsAndAssignmentEventsWithoutSourceIds() throws Exception {
        verifyImport(true);
    }

    @Test void preservesExactSourcesLatestVisibleTouchpointAndSavedViewAliases() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_crm_operations_[a-f0-9]{8}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        var jdbc = new JdbcTemplate(source);
        var mapper = new ObjectMapper();
        JsonNode envelope = withOperations(new LegacyCrmRehearsalMySqlTest().fixture(mapper), mapper);
        var state = envelope.path("state");
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("routingRules").get(0)).put("source", "SyntheticSource");
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("touchpoints").get(0))
            .put("leadId", "legacy-lead").put("campusId", "legacy-campus")
            .put("source", "SyntheticSource").put("subject", "数学");
        var views = (com.fasterxml.jackson.databind.node.ArrayNode) state.path("savedCrmViews");
        for (String resource : java.util.List.of("customers", "crmTasks", "crmTaskBatches")) {
            var view = (com.fasterxml.jackson.databind.node.ObjectNode) views.get(0).deepCopy();
            view.put("id", "view-" + resource).put("name", "synthetic-" + resource);
            ((com.fasterxml.jackson.databind.node.ObjectNode) view.path("query")).put("resource", resource);
            views.add(view);
        }
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
            + "VALUES('legacy-crm-actor','https://migration-rehearsal.invalid','legacy-crm-actor','all',1)");
        var cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var manager = new DataSourceTransactionManager(source);
        new LegacyCatalogRehearsal(jdbc, cipher, manager).importCatalog(envelope);
        new LegacyCrmRehearsal(jdbc, cipher, manager).importCrm(envelope);
        var importer = new LegacyCrmOperationsRehearsal(jdbc, cipher, manager);
        int imported = importer.importCrmOperations(envelope);
        assertEquals(imported, importer.importCrmOperations(envelope));
        assertEquals("模拟演示", jdbc.queryForObject("SELECT source_label FROM core_crm_lead "
            + "WHERE public_id='legacy-lead'", String.class));
        assertEquals("SyntheticSource", jdbc.queryForObject("SELECT source FROM core_crm_routing_rule "
            + "WHERE public_id='legacy-rule'", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_saved_view WHERE "
            + "(resource_type='customers' AND filters_json->>'$.resource'='customers') OR "
            + "(resource_type='tasks' AND filters_json->>'$.resource'='crmTasks') OR "
            + "(resource_type='taskBatches' AND filters_json->>'$.resource'='crmTaskBatches')", Integer.class));
        long actor = actorId(jdbc);
        long campus = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id='legacy-campus'", Long.class);
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,'crm.lead.follow')", actor);
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(?,?)", actor, campus);
        var routing = new LeadRoutingRepository(jdbc, cipher);
        Actor reader = new Actor(actor, "legacy-crm-actor", "campus", java.util.Set.of(campus), java.util.Set.of("crm.lead.assign"));
        var lead = routing.lead(reader, "legacy-lead", false);
        assertEquals("SyntheticSource", lead.source());
        assertEquals("legacy-rule", routing.simulate(lead).matchedRuleId());
        for (String flag : List.of("credential_change_required=1,credential_change_ticket_hash=REPEAT('a',64)", "archived_at=UTC_TIMESTAMP(3),archived_by_id=id")) {
            jdbc.update("UPDATE core_principal SET " + flag + " WHERE id=?", actor);
            try { assertNull(routing.simulate(lead).matchedRuleId()); }
            finally { jdbc.update("UPDATE core_principal SET credential_change_required=0,credential_change_ticket_hash=NULL,archived_at=NULL,archived_by_id=NULL WHERE id=?", actor); }
        }
        verifyLegacyReceiverGrant(jdbc, routing, lead, actor);
        jdbc.update("UPDATE core_crm_routing_rule SET source='syntheticsource' WHERE public_id='legacy-rule'");
        assertNull(routing.simulate(lead).matchedRuleId());
        jdbc.update("UPDATE core_crm_routing_rule SET source='other' WHERE public_id='legacy-rule'");
        assertNull(routing.simulate(lead).matchedRuleId());
        jdbc.update("INSERT INTO core_customer_touchpoint(public_id,customer_id,actor_id,lead_id,source,source_label,"
            + "channel,summary_cipher,occurred_at,created_at) SELECT 'outside-touch',customer_id,actor_id,lead_id,"
            + "source,'HiddenSource',channel,summary_cipher,occurred_at,DATE_ADD(created_at,INTERVAL 1 DAY) "
            + "FROM core_customer_touchpoint WHERE public_id='legacy-touchpoint'");
        assertEquals("SyntheticSource", routing.lead(reader, "legacy-lead", false).source());
        Actor all = new Actor(actor, "legacy-crm-actor", "all", java.util.Set.of(), java.util.Set.of("crm.lead.assign"));
        assertEquals("HiddenSource", routing.lead(all, "legacy-lead", false).source());
    }

    private void verifyImport(boolean structured) throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_crm_operations_[a-f0-9]{8}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode envelope = withOperations(new LegacyCrmRehearsalMySqlTest().fixture(mapper), mapper);
        if (structured) { structuredHistory(envelope, mapper); }
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
            + "VALUES('legacy-crm-actor','https://migration-rehearsal.invalid','legacy-crm-actor','all',0)");
        var cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var manager = new DataSourceTransactionManager(source);
        LegacyCatalogRehearsal catalog = new LegacyCatalogRehearsal(jdbc, cipher, manager);
        LegacyCrmRehearsal crm = new LegacyCrmRehearsal(jdbc, cipher, manager);
        assertEquals(5, catalog.importCatalog(envelope));
        assertEquals(10, crm.importCrm(envelope));
        LegacyCrmOperationsRehearsal importer = new LegacyCrmOperationsRehearsal(jdbc, cipher, manager);

        JsonNode bad = envelope.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) bad.path("state").path("savedCrmViews").get(0)
            .path("query")).put("unknownFilter", "reject");
        assertThrows(IllegalArgumentException.class, () -> importer.importCrmOperations(bad));
        assertEquals(0, count(jdbc, "core_crm_routing_rule"));
        assertEquals(0, countPhase(jdbc));

        JsonNode malformedDetail = envelope.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformedDetail.path("state").path("crmTasks")
            .get(0).path("history").get(0)).put("detail", 17);
        assertThrows(IllegalArgumentException.class, () -> importer.importCrmOperations(malformedDetail));
        assertEquals(0, countPhase(jdbc));

        if (!structured) { assertRuleEventWriteBoundary(importer, envelope); }

        jdbc.execute("CREATE TRIGGER rehearsal_crm_operations_fail BEFORE INSERT ON core_crm_task_event "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='operations rollback'");
        try { assertThrows(org.springframework.dao.DataAccessException.class, () -> importer.importCrmOperations(envelope)); }
        finally { jdbc.execute("DROP TRIGGER rehearsal_crm_operations_fail"); }
        assertEquals(0, count(jdbc, "core_crm_routing_rule"));
        assertEquals(0, count(jdbc, "core_crm_task_batch"));
        assertEquals(0, countPhase(jdbc));

        int imported = importer.importCrmOperations(envelope);
        assertEquals(imported, importer.importCrmOperations(envelope));
        assertEquals(1, count(jdbc, "core_crm_routing_rule"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_routing_rule_event WHERE rule_id="
            + "(SELECT id FROM core_crm_routing_rule WHERE public_id='legacy-rule') AND source_event_id='legacy-rule-history' "
            + "AND action='update' AND occurred_at=?", Integer.class,
            java.sql.Timestamp.from(java.time.Instant.parse("2026-10-02T00:00:00Z"))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_routing_queue WHERE status='pending'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_saved_view WHERE filters_json->>'$.resource'='leads'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_task_batch WHERE delivery_mode='legacy'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_task WHERE status='pending' AND owner_id IS NOT NULL", Integer.class));
        assertEquals(structured ? 4 : 3,
            jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_task_routing_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_task_event WHERE event_type='created'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT decision_hours FROM core_crm_lead_policy WHERE singleton=1", Integer.class));
        assertEquals(1, countPhase(jdbc));
        assertThrows(IllegalArgumentException.class, () -> importer.importCrmOperations(changed(envelope, mapper)));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
            "UPDATE core_crm_routing_rule_event SET action='create' WHERE source_event_id='legacy-rule-history'"));
        Actor routingManager = new Actor(actorId(jdbc), "legacy-crm-actor", "all", java.util.Set.of(),
            java.util.Set.of("crm.lead.assign"));
        var history = new LeadRoutingRepository(jdbc, cipher).ruleHistory(routingManager, "legacy-rule", 1, 10);
        assertEquals(1, history.total());
        assertEquals("legacy-rule-history", history.items().get(0).sourceEventId());
        if (structured) {
            assertNull(history.items().get(0).reason());
            assertStructuredHistory(jdbc, cipher, envelope, mapper);
        } else { assertEquals("synthetic", history.items().get(0).reason()); }

        long actorId = jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id='legacy-crm-actor'", Long.class);
        long campusId = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id='legacy-campus'", Long.class);
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher,version) VALUES(?,?,?,?,1)",
            "legacy-customer-for-decision", campusId, actorId, cipher.encrypt("合成新客户"));
        Actor followActor = new Actor(actorId, "legacy-crm-actor", "all", java.util.Set.of(), java.util.Set.of("lead.follow"));
        var newLead = new CrmDtos.LeadInput("legacy-customer-for-decision", null, null, "other", "合成新线索",
            null, null, "合成说明", null, null);
        var created = new LeadRepository(jdbc, cipher).create(followActor, newLead);
        assertEquals("pending", created.decisionStatus());
        assertEquals("legacy-crm-actor", created.decisionById());
        assertNotNull(created.decisionDeadline());
        assertEquals(1, jdbc.queryForObject("SELECT decision_hours FROM core_crm_lead_policy WHERE singleton=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead WHERE public_id=? AND decision_status='pending' "
            + "AND decision_deadline IS NOT NULL", Integer.class, created.id()));
        long deadlineSeconds = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,created_at,decision_deadline) "
            + "FROM core_crm_lead WHERE public_id=?", Long.class, created.id());
        assertTrue(deadlineSeconds >= 3599 && deadlineSeconds <= 3601);
    }

    private void assertRuleEventWriteBoundary(LegacyCrmOperationsRehearsal importer, JsonNode envelope) {
        JsonNode boundary = envelope.deepCopy();
        var rules = (com.fasterxml.jackson.databind.node.ArrayNode) boundary.path("state").path("routingRules");
        JsonNode template = rules.get(0).deepCopy();
        rules.removeAll();
        for (int index = 0; index < 494; index++) {
            var rule = (com.fasterxml.jackson.databind.node.ObjectNode) template.deepCopy();
            rule.put("id", "synthetic-boundary-rule-" + index);
            ((com.fasterxml.jackson.databind.node.ObjectNode) rule.path("history").get(0))
                .put("id", "synthetic-boundary-event-" + index);
            rules.add(rule);
        }
        // 494 rules + 494 events + 11 other writes = 999: count passes, exact-source prerequisite still rejects.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> importer.importCrmOperations(boundary))
            .getMessage().contains("前置迁移来源"));
        var history = (com.fasterxml.jackson.databind.node.ArrayNode) rules.get(0).path("history");
        history.add(history.get(0).deepCopy());
        // One additional event reaches exactly 1000 and must fail before any migration SQL can be issued.
        assertEquals("CRM运营迁移阶段超过单事务行数限制",
            assertThrows(IllegalArgumentException.class, () -> importer.importCrmOperations(boundary)).getMessage());
    }

    private void structuredHistory(JsonNode envelope, ObjectMapper mapper) throws Exception {
        for (String collection : java.util.List.of("routingRules", "crmTaskBatches", "crmTasks")) {
            var event = (com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("state")
                .path(collection).get(0).path("history").get(0);
            event.set("detail", mapper.readTree("{\"count\":1,\"active\":true,\"skipped\":[],\"prior\":null}"));
        }
        var history = (com.fasterxml.jackson.databind.node.ArrayNode) envelope.path("state")
            .path("crmTasks").get(0).path("assignmentHistory");
        ((com.fasterxml.jackson.databind.node.ObjectNode) history.get(0)).remove("id");
        history.add(history.get(0).deepCopy());
    }

    private void assertStructuredHistory(JdbcTemplate jdbc, PiiCipher cipher, JsonNode envelope,
            ObjectMapper mapper) throws Exception {
        for (String collection : java.util.List.of("routingRules", "crmTaskBatches", "crmTasks")) {
            JsonNode event = envelope.path("state").path(collection).get(0).path("history").get(0);
            String sql = switch (collection) {
                case "routingRules" -> "SELECT changes_cipher FROM core_crm_routing_rule_event WHERE public_id=?";
                case "crmTaskBatches" -> "SELECT changes_cipher FROM core_crm_task_routing_event WHERE public_id=?";
                case "crmTasks" -> "SELECT detail_cipher FROM core_crm_task_event WHERE public_id=?";
                default -> throw new IllegalArgumentException();
            };
            String encrypted = jdbc.queryForObject(sql, String.class, event.path("id").asText());
            assertFalse(encrypted.contains("skipped"));
            assertEquals(event, mapper.readTree(cipher.decrypt(encrypted)));
        }
        JsonNode task = envelope.path("state").path("crmTasks").get(0);
        for (int index = 0; index < task.path("assignmentHistory").size(); index++) {
            String publicId = "LEGACY_TASK_ASSIGN_" + LegacyCatalogRehearsal.hash(task.path("id").asText() + "\0" + index);
            String encrypted = jdbc.queryForObject("SELECT changes_cipher FROM core_crm_task_routing_event WHERE public_id=?",
                String.class, publicId);
            assertEquals(task.path("assignmentHistory").get(index), mapper.readTree(cipher.decrypt(encrypted)));
        }
    }

    private JsonNode withOperations(JsonNode envelope, ObjectMapper mapper) throws Exception {
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("state");
        state.putArray("routingRules").add(mapper.readTree("""
            {"id":"legacy-rule","name":"synthetic rule","campusId":"legacy-campus","campaignId":"legacy-campaign",
             "subject":"数学","source":"phone","ownerId":"legacy-crm-actor","priority":4,"active":true,
             "createdBy":"legacy-crm-actor","createdAt":"2026-10-02T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z",
             "version":1,"history":[{"id":"legacy-rule-history","actorId":"legacy-crm-actor","at":"2026-10-02T00:00:00Z","kind":"规则更新","detail":"synthetic"}]}
            """));
        state.putArray("routingQueue").add(mapper.readTree("""
            {"id":"legacy-route-queue","leadId":"legacy-lead","campusId":"legacy-campus","createdBy":"legacy-crm-actor",
             "status":"pending","reason":"synthetic unmatched","createdAt":"2026-10-02T00:00:00Z",
             "updatedAt":"2026-10-02T00:00:00Z","version":1}
            """));
        state.putArray("savedCrmViews").add(mapper.readTree("""
            {"id":"legacy-view","ownerId":"legacy-crm-actor","createdBy":"legacy-crm-actor","name":"synthetic view",
             "query":{"resource":"leads","scope":"mine","queue":"all"},"status":"active",
             "createdAt":"2026-10-02T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z","version":1}
            """));
        state.putArray("crmTaskBatches").add(mapper.readTree("""
            {"id":"legacy-task-batch","createdBy":"legacy-crm-actor","title":"synthetic batch","name":"synthetic batch",
             "taskType":"marketing","serviceType":null,"subject":"数学","term":"2026秋","campusId":"legacy-campus",
             "campusIds":["legacy-campus"],"campaignId":"legacy-campaign","dueAt":"2026-11-01T00:00:00Z",
             "status":"active","note":"synthetic note","version":1,"strategy":"even","ownerIds":["legacy-crm-actor"],
             "assignmentCounts":{"legacy-crm-actor":1},"customerIds":["legacy-customer"],"taskIds":["legacy-task"],
             "skipped":[],"history":[{"id":"legacy-batch-event","actorId":"legacy-crm-actor","actor":"synthetic",
             "at":"2026-10-03T00:00:00Z","kind":"create","detail":"synthetic"}],
             "createdAt":"2026-10-03T00:00:00Z","updatedAt":"2026-10-03T00:00:00Z"}
            """));
        state.putArray("crmTasks").add(mapper.readTree("""
            {"id":"legacy-task","batchId":"legacy-task-batch","customerId":"legacy-customer","campusId":"legacy-campus",
             "ownerId":"legacy-crm-actor","assigneeId":"legacy-crm-actor","createdBy":"legacy-crm-actor",
             "taskType":"marketing","serviceType":null,"title":"synthetic task","subject":"数学","term":"2026秋",
             "status":"pending","dueAt":"2026-11-01T00:00:00Z","note":"synthetic note","result":null,
             "resultNote":null,"opportunityId":null,"campaignId":"legacy-campaign","customerName":"synthetic",
             "owner":"synthetic","version":1,"history":[{"id":"legacy-task-event","actorId":"legacy-crm-actor",
             "actor":"synthetic","at":"2026-10-03T00:00:00Z","kind":"created","detail":"synthetic"}],
             "assignmentHistory":[{"id":"legacy-task-assignment","actorId":"legacy-crm-actor","at":"2026-10-03T00:00:00Z",
             "from":null,"to":"legacy-crm-actor","strategy":"even"}],
             "createdAt":"2026-10-03T00:00:00Z","updatedAt":"2026-10-03T00:00:00Z"}
            """));
        state.putObject("leadPolicy").put("reserveDays", 1).put("reclaimDays", 7).put("firstFollowHours", 4).put("decisionHours", 1);
        return envelope;
    }

    private JsonNode changed(JsonNode envelope, ObjectMapper mapper) {
        JsonNode copy = envelope.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("state").path("leadPolicy")).put("decisionHours", 2);
        return copy;
    }
    private int count(JdbcTemplate jdbc, String table) {
        if (!java.util.Set.of("core_crm_routing_rule", "core_crm_task_batch").contains(table)) { throw new IllegalArgumentException(); }
        return jdbc.queryForObject("SELECT COUNT(id) FROM " + table, Integer.class);
    }
    private int countPhase(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='crm_operations_v1'", Integer.class);
    }
    private long actorId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id='legacy-crm-actor'", Long.class);
    }
    private void verifyLegacyReceiverGrant(JdbcTemplate jdbc, LeadRoutingRepository routing,
            LeadRoutingRepository.RouteLead lead, long actor) {
        jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=? AND permission_code='crm.lead.follow'", actor);
        jdbc.update("UPDATE core_principal_campus SET deleted=1 WHERE principal_id=?", actor);
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,'lead.follow')", actor);
        try {
            assertEquals("legacy-rule", routing.simulate(lead).matchedRuleId(),
                "all-scope legacy receiver does not require an artificial campus membership");
            jdbc.update("INSERT INTO core_principal_denied_permission(principal_id,permission_code) VALUES(?,'lead.follow')", actor);
            assertNull(routing.simulate(lead).matchedRuleId(), "explicit denial must override the direct legacy grant");
        } finally {
            jdbc.update("UPDATE core_principal_denied_permission SET deleted=1 WHERE principal_id=? AND permission_code='lead.follow'", actor);
            jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=? AND permission_code='lead.follow'", actor);
            jdbc.update("UPDATE core_principal_permission SET deleted=0 WHERE principal_id=? AND permission_code='crm.lead.follow'", actor);
            jdbc.update("UPDATE core_principal_campus SET deleted=0 WHERE principal_id=?", actor);
        }
    }

}
