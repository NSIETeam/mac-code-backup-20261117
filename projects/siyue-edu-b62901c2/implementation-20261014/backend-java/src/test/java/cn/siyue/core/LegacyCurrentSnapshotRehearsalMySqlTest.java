package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.security.SecureRandom;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Rehearses all fourteen immutable import phases against a private snapshot and disposable _test schema. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class LegacyCurrentSnapshotRehearsalMySqlTest {
    @Test void catalogCrmAndFinancialPhasesReconcileAndReplayTogether() throws Exception {
        String snapshot=System.getenv("SIYUE_REAL_SNAPSHOT_PATH");
        Assumptions.assumeTrue(snapshot!=null&&!snapshot.isBlank(),"real source snapshot not configured; not an acceptance pass");
        JsonNode envelope=new ObjectMapper().readTree(Files.readAllBytes(Path.of(snapshot)));
        assertEquals("siyue.legacy-business/v1",envelope.path("schema").asText());
        assertTrue(envelope.path("revision").isIntegralNumber());JsonNode state=envelope.path("state");
        var blockers=LegacyMigrationCandidateCli.sourceBlockers(envelope);
        assertTrue(blockers.stream().allMatch("legacy reward decryption key unavailable"::equals),
            "Real snapshot has unsupported source data: "+String.join(",",blockers));
        assertTrue(blockers.isEmpty(),"Reward decryption key unavailable; full snapshot rehearsal cannot pass");
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPath().endsWith("_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        JdbcTemplate jdbc=new JdbcTemplate(source);
        assertEquals(0, scalar(jdbc, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"),
            "Real-source rehearsal requires a fresh empty isolated schema; existing tables must never be cleaned");
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
        Set<String> principals=sourcePrincipals(state,envelope.path("auditProvenance"));
        for(String publicId:principals) jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) VALUES(?,?,?,'assigned',0)",publicId,"https://migration-rehearsal.invalid",publicId);
        assertEquals(principals.size(),scalar(jdbc,"SELECT COUNT(id) FROM core_principal WHERE oidc_issuer='https://migration-rehearsal.invalid' AND active=0"));
        byte[] ephemeralKey=new byte[32];new SecureRandom().nextBytes(ephemeralKey);
        PiiCipher cipher=new PiiCipher(Base64.getEncoder().encodeToString(ephemeralKey));
        var manager=new DataSourceTransactionManager(source);
        LegacyCatalogRehearsal catalog=new LegacyCatalogRehearsal(jdbc,cipher,manager);
        int catalogRows=catalog.importCatalog(envelope);
        assertEquals(envelope.path("state").path("staff").size(),scalar(jdbc,"SELECT COUNT(id) FROM core_staff_profile"));
        assertEquals(catalogRows,catalog.importCatalog(envelope));
        var campusConfiguration = new LegacyCampusConfigurationRehearsal(jdbc, cipher, manager);
        int configurationRows = campusConfiguration.importConfiguration(envelope);
        assertEquals(configurationRows, campusConfiguration.importConfiguration(envelope));
        var settingsMigration = new LegacySystemSettingsRehearsal(jdbc, cipher, manager);
        int settingsRows = settingsMigration.importSettings(envelope);
        assertEquals(settingsRows, settingsMigration.importSettings(envelope));
        assertEquals(settingsRows, scalar(jdbc, "SELECT COUNT(id) FROM core_system_setting WHERE deleted=0"));
        assertEquals(state.path("campuses").size(), scalar(jdbc,
            "SELECT COUNT(id) FROM core_campus WHERE configuration_ready=1"));

        LegacyCrmRehearsal crm=new LegacyCrmRehearsal(jdbc,cipher,manager);
        jdbc.execute("CREATE TRIGGER rehearsal_combined_crm_fail BEFORE INSERT ON core_customer FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='combined rehearsal rollback'");
        try {assertThrows(DataAccessException.class,()->crm.importCrm(envelope));}
        finally {jdbc.execute("DROP TRIGGER rehearsal_combined_crm_fail");}
        assertEquals(0,scalar(jdbc,"SELECT COUNT(id) FROM core_customer"));
        assertEquals(0,scalar(jdbc,"SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='crm_households_v1'"));
        int crmRows=crm.importCrm(envelope);assertEquals(crmRows,crm.importCrm(envelope));
        LegacyServiceHistoryRehearsal serviceHistory=new LegacyServiceHistoryRehearsal(jdbc,cipher,manager);
        int serviceHistoryRows=serviceHistory.importServiceHistory(envelope);
        assertEquals(serviceHistoryRows,serviceHistory.importServiceHistory(envelope));

        LegacyFinancialRehearsal financial=new LegacyFinancialRehearsal(jdbc,cipher,manager);
        int financialRows=financial.importFinancial(envelope);assertEquals(financialRows,financial.importFinancial(envelope));
        verifyForms(envelope,jdbc,cipher,manager);
        verifyScheduleHistory(envelope,jdbc,cipher,manager);
        verifyImportBatches(envelope,jdbc,cipher,manager);
        verifyAttributionRequests(envelope,jdbc,cipher,manager);
        verifyCrmOperations(envelope,jdbc,cipher,manager);
        verifyCampaignAcquisition(envelope,jdbc,cipher,manager);
        assertCount(state,"orders","core_crm_order",jdbc);assertCount(state,"receipts","core_crm_receipt",jdbc);
        assertCount(state,"attendance","core_attendance",jdbc);assertCount(state,"consumptions","core_consumption",jdbc);
        assertCount(state,"customers","core_customer",jdbc);assertCount(state,"campaigns","core_activity",jdbc);
        assertCount(state,"touchpoints","core_customer_touchpoint",jdbc);assertCount(state,"leads","core_crm_lead",jdbc);
        assertCount(state,"opportunities","core_opportunity",jdbc);
        assertCount(state,"studentCommunications","core_communication",jdbc);
        assertCount(state,"consultations","core_crm_consultation",jdbc);
        assertCount(state,"serviceTickets","core_crm_service_ticket",jdbc);
        assertEquals(state.path("studentCommunications").size(),scalar(jdbc,"SELECT COUNT(id) FROM core_communication WHERE source_audit_event_id IS NOT NULL"));
        int consultationHistory=0;for(JsonNode row:state.path("consultations")) consultationHistory+=row.path("history").size();
        assertEquals(consultationHistory,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_consultation_history"));
        assertEquals(state.path("serviceTickets").size(),scalar(jdbc,"SELECT COUNT(id) FROM core_crm_service_ticket_event WHERE source_audit_event_id IS NOT NULL"));
        int missingCampaignDates=0;for(JsonNode campaign:state.path("campaigns")) if(!campaign.hasNonNull("start")&&!campaign.hasNonNull("end")) missingCampaignDates++;
        assertEquals(missingCampaignDates,scalar(jdbc,"SELECT COUNT(id) FROM core_activity WHERE legacy_schedule_missing=1 AND starts_at IS NULL AND ends_at IS NULL"));
        long readerId=jdbc.queryForObject("SELECT id FROM core_principal ORDER BY id LIMIT 1",Long.class);
        Actor reader=new Actor(readerId,"migration-reader","all",Set.of(),Set.of("crm.activity.read"));
        var activityPage=new CrmRepository(jdbc,cipher).activities(reader,new CrmActivityQuery(null,"scope","campaign",null,null,1,100));
        assertEquals(state.path("campaigns").size(),activityPage.total());
        assertEquals(missingCampaignDates,activityPage.items().stream().filter(a->a.startsAt()==null&&a.endsAt()==null).count());
        JsonNode firstCommunication=state.path("studentCommunications").get(0);
        Actor communicationReader=new Actor(readerId,"migration-reader","all",Set.of(),Set.of("student.communication.read"));
        var communicationTimeline=new AcademicRepository(jdbc,cipher)
            .communications(communicationReader,firstCommunication.path("studentId").asText(),firstCommunication.path("classId").asText());
        assertTrue(communicationTimeline.stream().anyMatch(c->c.id().equals(firstCommunication.path("id").asText())
            &&c.actorId().equals(firstCommunication.path("actorId").asText())
            &&c.createdAt().equals(java.time.Instant.parse(firstCommunication.path("createdAt").asText()))));
        assertEquals(sumMinor(state.path("orders"),"amountCents"),scalarLong(jdbc,"SELECT CAST(ROUND(COALESCE(SUM(total_amount),0)*100) AS SIGNED) FROM core_crm_order"));
        assertEquals(sumMinor(state.path("orders"),"paidCents"),scalarLong(jdbc,"SELECT CAST(ROUND(COALESCE(SUM(received_amount),0)*100) AS SIGNED) FROM core_crm_order"));
        assertEquals(sumMinor(state.path("orders"),"refundedCents"),scalarLong(jdbc,"SELECT CAST(ROUND(COALESCE(SUM(refunded_amount),0)*100) AS SIGNED) FROM core_crm_order"));
        assertEquals(sumMinor(state.path("consumptions"),"amountCents"),scalarLong(jdbc,"SELECT CAST(ROUND(COALESCE(SUM(amount),0)*100) AS SIGNED) FROM core_consumption"));
        assertEquals(1,scalar(jdbc,"SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_catalog_v1' AND source_revision="+envelope.path("revision").asLong()));
        assertEquals(1,scalar(jdbc,"SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='crm_households_v1' AND source_revision="+envelope.path("revision").asLong()));
        assertEquals(1,scalar(jdbc,"SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='crm_service_history_v1' AND source_revision="+envelope.path("revision").asLong()));
        assertEquals(1,scalar(jdbc,"SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_financial_v1' AND source_revision="+envelope.path("revision").asLong()));
        int withdrawn=0;
        for(JsonNode enrollment:state.path("enrollments")) if("withdrawn".equals(enrollment.path("status").asText())) withdrawn++;
        assertEquals(withdrawn,scalar(jdbc,"SELECT COUNT(id) FROM core_enrollment WHERE status='withdrawn'"));
        if(withdrawn>0) {
            assertEquals(withdrawn,scalar(jdbc,"SELECT COUNT(e.id) FROM core_enrollment e JOIN core_principal p ON p.id=e.withdrawn_by_id JOIN core_migration_checkpoint c ON c.phase='academic_financial_v1' WHERE e.status='withdrawn' AND e.withdrawal_audit_event_id IS NOT NULL AND p.active=0"));
            for(JsonNode enrollment:state.path("enrollments")) if("withdrawn".equals(enrollment.path("status").asText())) {
                JsonNode order=null;for(JsonNode candidate:state.path("orders")) if(enrollment.path("orderId").asText().equals(candidate.path("id").asText())) order=candidate;
                assertNotNull(order);JsonNode refund=null;for(JsonNode candidate:state.path("refunds")) if(order.path("id").asText().equals(candidate.path("orderId").asText())) refund=candidate;
                assertNotNull(refund);JsonNode event=matchingWithdrawal(envelope.path("auditProvenance"),refund.path("id").asText());
                assertNotNull(event,"withdrawal must have exact successful audit provenance");
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(e.id) FROM core_enrollment e JOIN core_principal p ON p.id=e.withdrawn_by_id WHERE e.public_id=? AND e.withdrawal_audit_event_id=? AND p.public_id=?",Integer.class,enrollment.path("id").asText(),event.path("id").asText(),event.path("actorId").asText()));
            }
        }
        var feedbackMigration = new LegacyUiAnnotationRehearsal(jdbc, cipher, manager);
        int feedbackRows = feedbackMigration.importAnnotations(envelope);
        assertEquals(feedbackRows, feedbackMigration.importAnnotations(envelope));
        assertEquals(state.path("authFeedback").path("annotations").size(), scalar(jdbc,"SELECT COUNT(*) FROM core_ui_annotation"));
        verifyLeadReclaims(envelope, jdbc, cipher, manager);
        assertTrue(catalogRows>0);assertTrue(crmRows>=0);assertTrue(serviceHistoryRows>0);assertTrue(financialRows>0);
    }

    private void verifyLeadReclaims(JsonNode envelope, JdbcTemplate jdbc, PiiCipher cipher,
            DataSourceTransactionManager manager) {
        var migration = new LegacyLeadReclaimRequestRehearsal(jdbc, cipher,
            LegacyMigrationPhaseTransactions.create(manager));
        int rows = migration.importRequests(envelope, null);
        assertEquals(rows, migration.importRequests(envelope, null));
        assertEquals(envelope.path("state").path("leadReclaimRequests").size(),
            scalar(jdbc, "SELECT COUNT(id) FROM core_crm_lead_reclaim_request"));
        assertEquals(expectedReclaimEvents(envelope),
            scalar(jdbc, "SELECT COUNT(id) FROM core_crm_lead_reclaim_event"));
        assertEquals(14, scalar(jdbc, "SELECT COUNT(id) FROM core_migration_checkpoint"));
    }

    private int expectedReclaimEvents(JsonNode envelope) {
        Set<String> requests = new java.util.HashSet<>();
        for (JsonNode row : envelope.path("state").path("leadReclaimRequests")) {
            requests.add(row.path("id").asText());
        }
        int count = 0;
        for (JsonNode event : envelope.path("auditProvenance")) {
            if (!event.path("outcome").asText().equals("success")) { continue; }
            for (JsonNode change : event.path("changes")) {
                if (change.path("collection").asText().equals("leadReclaimRequests")
                        && requests.contains(change.path("id").asText())) { count++; break; }
            }
        }
        return count;
    }

    private void verifyCampaignAcquisition(JsonNode envelope,JdbcTemplate jdbc,PiiCipher cipher,
            DataSourceTransactionManager manager) {
        JsonNode state=envelope.path("state");
        int formEdges=0,links=0,formSources=0,touchpointSources=0;
        for(JsonNode campaign:state.path("campaigns")) {
            formEdges+=campaign.path("formIds").size();
            links+=campaign.path("promotionLinks").size();
        }
        for(JsonNode form:state.path("forms")) for(JsonNode submission:form.path("submissions")) {
            if(submission.hasNonNull("acquisitionSnapshot")) formSources++;
        }
        for(JsonNode touchpoint:state.path("touchpoints")) {
            if(touchpoint.hasNonNull("acquisitionSnapshot")) touchpointSources++;
        }
        int expected=formEdges+links+formSources+touchpointSources;
        var importer=new LegacyCampaignPromotionRehearsal(jdbc,cipher,manager);
        assertEquals(expected,importer.importAcquisition(envelope,null));
        assertEquals(expected,importer.importAcquisition(envelope,null));
        assertPhaseRows(jdbc,"campaign_acquisition_v1",expected);
        assertEquals(formEdges,scalar(jdbc,"SELECT COUNT(id) FROM core_activity_form"));
        assertEquals(links,scalar(jdbc,"SELECT COUNT(id) FROM core_campaign_promotion_link"));
        assertEquals(formSources,scalar(jdbc,"SELECT COUNT(id) FROM core_form_acquisition"));
        assertEquals(touchpointSources,scalar(jdbc,"SELECT COUNT(id) FROM core_touchpoint_acquisition"));
    }

    private void verifyScheduleHistory(JsonNode envelope,JdbcTemplate jdbc,PiiCipher cipher,
            DataSourceTransactionManager manager) {
        JsonNode state=envelope.path("state");
        var importer=new LegacyScheduleHistoryRehearsal(jdbc,cipher,manager);
        int expectedRows=scheduleImportedRows(state);
        assertEquals(expectedRows,importer.importScheduleHistory(envelope));
        assertEquals(expectedRows,importer.importScheduleHistory(envelope));
        assertPhaseRows(jdbc,"academic_schedule_history_v1",expectedRows);
        assertCount(state,"sessionAdjustments","core_session_adjustment",jdbc);
        assertCount(state,"rescheduleRequests","core_session_reschedule_request",jdbc);
        assertEquals(statusCount(state.path("sessionAdjustments"),"cancelled"),
            scalar(jdbc,"SELECT COUNT(id) FROM core_session_adjustment_event WHERE event_type='cancelled'"));
        assertEquals(statusCount(state.path("rescheduleRequests"),"pending")
            +statusCount(state.path("rescheduleRequests"),"approved")
            +statusCount(state.path("rescheduleRequests"),"rejected"),
            scalar(jdbc,"SELECT COUNT(id) FROM core_session_reschedule_event WHERE event_type='requested'"));
        assertEquals(state.path("rescheduleRequests").size()-statusCount(state.path("rescheduleRequests"),"pending"),
            scalar(jdbc,"SELECT COUNT(id) FROM core_session_reschedule_event WHERE event_type='reviewed'"));
        assertStatusCounts(state.path("sessionAdjustments"),"status","core_session_adjustment","status",jdbc);
        assertStatusCounts(state.path("rescheduleRequests"),"status","core_session_reschedule_request","status",jdbc);
        for(JsonNode row:state.path("sessionAdjustments")) assertSourceTimes(jdbc,"core_session_adjustment",row);
        for(JsonNode row:state.path("rescheduleRequests")) {
            assertSourceTimes(jdbc,"core_session_reschedule_request",row);
            assertEquals(1,scalar(jdbc,"SELECT COUNT(r.id) FROM core_session_reschedule_request r "
                +"JOIN core_session s ON s.id=r.session_id JOIN core_class c ON c.id=r.class_id "
                +"JOIN core_principal a ON a.id=r.applicant_id JOIN core_principal t ON t.id=r.original_teacher_id "
                +"WHERE r.public_id='"+sqlLiteral(row.path("id").asText())+"' AND s.class_id=c.id "
                +"AND a.public_id='"+sqlLiteral(row.path("applicantId").asText())+"' "
                +"AND t.public_id='"+sqlLiteral(row.path("original").path("teacherId").asText())+"'"));
        }
        assertEquals(0,scalar(jdbc,"SELECT COUNT(a.id) FROM core_session_adjustment a "
            +"LEFT JOIN core_enrollment e ON e.id=a.enrollment_id AND e.order_id=a.order_id AND e.student_id=a.student_id "
            +"LEFT JOIN core_session s ON s.id=a.source_session_id AND s.class_id=a.source_class_id "
            +"LEFT JOIN core_enrollment_lesson l ON l.enrollment_id=a.enrollment_id AND l.order_id=a.order_id "
            +"AND l.source_lesson_id=a.lesson_id WHERE e.id IS NULL OR s.id IS NULL OR l.id IS NULL"));
    }

    private int scheduleImportedRows(JsonNode state) {
        int rows=0;
        for(JsonNode adjustment:state.path("sessionAdjustments")) rows+=Set.of("cancelled","completed")
            .contains(adjustment.path("status").asText())?2:1;
        for(JsonNode request:state.path("rescheduleRequests")) rows+=request.path("status").asText().equals("pending")?2:3;
        return rows;
    }

    private void verifyImportBatches(JsonNode envelope,JdbcTemplate jdbc,PiiCipher cipher,
            DataSourceTransactionManager manager) {
        JsonNode state=envelope.path("state"),batches=state.path("importBatches");
        var importer=new LegacyImportBatchRehearsal(jdbc,cipher,manager);
        int expected=importBatchRows(batches);
        assertEquals(expected,importer.importBatches(envelope));assertEquals(expected,importer.importBatches(envelope));
        assertPhaseRows(jdbc,"crm_import_batches_v1",expected);
        assertEquals(batches.size(),scalar(jdbc,"SELECT COUNT(id) FROM core_crm_import_batch"));
        assertEquals(totalNested(batches,"items"),scalar(jdbc,"SELECT COUNT(id) FROM core_crm_import_row"));
        for(JsonNode batch:batches) {
            assertSourceTimes(jdbc,"core_crm_import_batch",batch);
            assertEquals(batch.path("status").asText(),jdbc.queryForObject("SELECT status FROM core_crm_import_batch "
                +"WHERE public_id=?",String.class,batch.path("id").asText()));
        }
        for(String outcome:List.of("new_customer","existing_touch","duplicate_demand","needs_review","invalid")) {
            assertEquals(countOutcome(batches,outcome),scalar(jdbc,"SELECT COUNT(id) FROM core_crm_import_row WHERE outcome_type='"+outcome+"'"));
        }
        assertEquals(0,scalar(jdbc,"SELECT COUNT(r.id) FROM core_crm_import_row r "
            +"LEFT JOIN core_customer c ON c.id=r.customer_id LEFT JOIN core_crm_lead l ON l.id=r.lead_id "
            +"LEFT JOIN core_customer_touchpoint t ON t.id=r.touchpoint_id WHERE "
            +"(r.customer_id IS NOT NULL AND c.id IS NULL) OR (r.lead_id IS NOT NULL AND l.id IS NULL) "
            +"OR (r.touchpoint_id IS NOT NULL AND t.id IS NULL)"));
    }

    private int importBatchRows(JsonNode batches) {
        int rows=0;for(JsonNode batch:batches) rows+=1+batch.path("items").size();return rows;
    }

    private int countOutcome(JsonNode batches,String outcome) {
        int rows=0;for(JsonNode batch:batches) for(JsonNode item:batch.path("items"))
            if(outcome.equals(item.path("category").asText())) rows++;return rows;
    }

    private void verifyAttributionRequests(JsonNode envelope,JdbcTemplate jdbc,PiiCipher cipher,
            DataSourceTransactionManager manager) {
        JsonNode requests=envelope.path("state").path("attributionRequests");
        var importer=new LegacyAttributionRequestRehearsal(jdbc,cipher,manager);int expected=2*requests.size();
        assertEquals(expected,importer.importRequests(envelope));assertEquals(expected,importer.importRequests(envelope));
        assertPhaseRows(jdbc,"crm_attribution_requests_v1",expected);
        assertEquals(requests.size(),scalar(jdbc,"SELECT COUNT(id) FROM core_crm_attribution_request"));
        assertEquals(requests.size(),scalar(jdbc,"SELECT COUNT(id) FROM core_crm_attribution_request_event WHERE event_type='requested'"));
        assertStatusCounts(requests,"status","core_crm_attribution_request","status",jdbc,"pending","pending_approval");
        for(JsonNode request:requests) {
            assertSourceTimes(jdbc,"core_crm_attribution_request",request);
            assertEquals(1,scalar(jdbc,"SELECT COUNT(r.id) FROM core_crm_attribution_request r "
                +"JOIN core_crm_order o ON o.id=r.order_id JOIN core_principal a ON a.id=r.applicant_id "
                +"JOIN core_principal f ON f.id=r.from_owner_id JOIN core_principal p ON p.id=r.proposed_owner_id "
                +"WHERE r.public_id='"+sqlLiteral(request.path("id").asText())+"' "
                +"AND o.public_id='"+sqlLiteral(request.path("orderIds").get(0).asText())+"' "
                +"AND a.public_id='"+sqlLiteral(request.path("applicantId").asText())+"' "
                +"AND p.public_id='"+sqlLiteral(request.path("ownerId").asText())+"'"));
            JsonNode creationAudit=attributionCreationAudit(envelope.path("auditProvenance"),request);
            assertEquals(sourceTimestamp(creationAudit.path("at").asText()),jdbc.queryForObject("SELECT e.occurred_at "
                +"FROM core_crm_attribution_request_event e JOIN core_crm_attribution_request r ON r.id=e.request_id "
                +"WHERE r.public_id=? AND e.event_type='requested'",java.sql.Timestamp.class,request.path("id").asText()));
        }
    }

    private JsonNode attributionCreationAudit(JsonNode audit,JsonNode request) {
        var matches=new java.util.ArrayList<JsonNode>();
        for(JsonNode event:audit) {
            if(!"order.attribution.request".equals(event.path("action").asText())
                    || !"success".equals(event.path("outcome").asText())) continue;
            for(JsonNode change:event.path("changes")) {
                if("attributionRequests".equals(change.path("collection").asText())
                        && request.path("id").asText().equals(change.path("id").asText())) matches.add(event);
            }
        }
        assertEquals(1,matches.size(),"attribution request must have unique successful creation audit");
        assertEquals(request.path("applicantId").asText(),matches.get(0).path("actorId").asText());
        return matches.get(0);
    }

    private void verifyCrmOperations(JsonNode envelope,JdbcTemplate jdbc,PiiCipher cipher,
            DataSourceTransactionManager manager) {
        JsonNode state=envelope.path("state");var importer=new LegacyCrmOperationsRehearsal(jdbc,cipher,manager);
        int expected=1+state.path("routingRules").size()+state.path("routingQueue").size()
            +state.path("savedCrmViews").size()+state.path("crmTaskBatches").size()+state.path("crmTasks").size();
        assertEquals(expected,importer.importCrmOperations(envelope));
        assertEquals(expected,importer.importCrmOperations(envelope));assertPhaseRows(jdbc,"crm_operations_v1",expected);
        assertCount(state,"routingRules","core_crm_routing_rule",jdbc);
        assertCount(state,"routingQueue","core_crm_routing_queue",jdbc);
        assertCount(state,"savedCrmViews","core_crm_saved_view",jdbc);
        assertCount(state,"crmTaskBatches","core_crm_task_batch",jdbc);
        assertCount(state,"crmTasks","core_crm_task",jdbc);
        int activeRules=0;for(JsonNode rule:state.path("routingRules")) if(rule.path("active").asBoolean()) activeRules++;
        assertEquals(activeRules,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_routing_rule WHERE active=1"));
        assertEquals(state.path("routingRules").size()-activeRules,
            scalar(jdbc,"SELECT COUNT(id) FROM core_crm_routing_rule WHERE active=0"));
        assertStatusCounts(state.path("routingQueue"),"status","core_crm_routing_queue","status",jdbc);
        assertStatusCounts(state.path("crmTaskBatches"),"status","core_crm_task_batch","status",jdbc);
        assertStatusCounts(state.path("crmTasks"),"status","core_crm_task","status",jdbc);
        int archivedViews=statusCount(state.path("savedCrmViews"),"archived");
        assertEquals(archivedViews,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_saved_view WHERE deleted=1"));
        assertEquals(state.path("savedCrmViews").size()-archivedViews,
            scalar(jdbc,"SELECT COUNT(id) FROM core_crm_saved_view WHERE deleted=0"));
        for(String key:List.of("routingRules","routingQueue","savedCrmViews","crmTaskBatches","crmTasks"))
            for(JsonNode row:state.path(key)) assertOperationTimes(jdbc,key,row);
        int ruleEvents=0,queueEvents=state.path("routingQueue").size(),batchEvents=state.path("crmTaskBatches").size();
        int taskEvents=0;
        for(JsonNode row:state.path("routingRules")) ruleEvents+=Math.max(1,row.path("history").size());
        for(JsonNode row:state.path("crmTaskBatches")) batchEvents+=row.path("history").size();
        for(JsonNode row:state.path("crmTasks")) taskEvents+=row.path("history").size();
        assertEquals(ruleEvents,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_routing_rule_event"));
        assertEquals(queueEvents,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_routing_event"));
        assertEquals(batchEvents+totalNested(state.path("crmTasks"),"assignmentHistory"),
            scalar(jdbc,"SELECT COUNT(id) FROM core_crm_task_routing_event"));
        assertEquals(taskEvents,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_task_event"));
        assertEquals(0,scalar(jdbc,"SELECT COUNT(t.id) FROM core_crm_task t LEFT JOIN core_crm_task_batch b "
            +"ON b.id=t.batch_id LEFT JOIN core_customer c ON c.id=t.customer_id WHERE b.id IS NULL OR c.id IS NULL"));
        JsonNode policy=state.path("leadPolicy");
        assertEquals(policy.path("reserveDays").asInt(),scalar(jdbc,"SELECT reserve_days FROM core_crm_lead_policy WHERE singleton=1"));
    }

    private void assertOperationTimes(JdbcTemplate jdbc,String collection,JsonNode row) {
        String table=switch(collection) {case "routingRules"->"core_crm_routing_rule";case "routingQueue"->"core_crm_routing_queue";
            case "savedCrmViews"->"core_crm_saved_view";case "crmTaskBatches"->"core_crm_task_batch";default->"core_crm_task";};
        assertSourceTimes(jdbc,table,row);
    }

    private void assertSourceTimes(JdbcTemplate jdbc,String table,JsonNode row) {
        String publicId=sqlLiteral(row.path("id").asText());
        assertEquals(sourceTimestamp(row.path("createdAt").asText()),jdbc.queryForObject(
            "SELECT created_at FROM "+table+" WHERE public_id='"+publicId+"'",java.sql.Timestamp.class));
        assertEquals(sourceTimestamp(row.path("updatedAt").asText()),jdbc.queryForObject(
            "SELECT updated_at FROM "+table+" WHERE public_id='"+publicId+"'",java.sql.Timestamp.class));
    }

    private java.sql.Timestamp sourceTimestamp(String value) {
        return java.sql.Timestamp.from(java.time.Instant.parse(value));
    }

    private String sqlLiteral(String value) {return value.replace("'","''");}

    private void assertPhaseRows(JdbcTemplate jdbc,String phase,int expected) {
        assertEquals(expected,jdbc.queryForObject("SELECT imported_rows FROM core_migration_checkpoint WHERE phase=?",
            Integer.class,phase));
    }

    private void assertStatusCounts(JsonNode rows,String sourceField,String table,String databaseField,JdbcTemplate jdbc) {
        java.util.Map<String,Integer> expected=new java.util.HashMap<>();
        for(JsonNode row:rows) expected.merge(row.path(sourceField).asText(),1,Integer::sum);
        java.util.Map<String,Integer> actual=new java.util.HashMap<>();
        jdbc.query("SELECT "+databaseField+",COUNT(id) FROM "+table+" GROUP BY "+databaseField,
            (org.springframework.jdbc.core.RowCallbackHandler)rs->actual.put(rs.getString(1),rs.getInt(2)));
        assertEquals(expected,actual);
    }

    private void assertStatusCounts(JsonNode rows,String sourceField,String table,String databaseField,JdbcTemplate jdbc,
            String sourceStatus,String databaseStatus) {
        assertEquals(statusCount(rows,sourceStatus),scalar(jdbc,"SELECT COUNT(id) FROM "+table+" WHERE "
            +databaseField+"='"+databaseStatus+"'"));
    }

    private int statusCount(JsonNode rows,String status) {
        int count=0;for(JsonNode row:rows) if(status.equals(row.path("status").asText())) count++;return count;
    }

    private int totalNested(JsonNode rows,String field) {
        int count=0;for(JsonNode row:rows) count+=row.path(field).size();return count;
    }

    private void verifyForms(JsonNode envelope,JdbcTemplate jdbc,PiiCipher cipher,DataSourceTransactionManager manager) {
        JsonNode state=envelope.path("state");
        var importer=new LegacyFormTrialRehearsal(jdbc,cipher,manager);
        if(!state.path("forms").isEmpty()) {
            jdbc.execute("CREATE TRIGGER rehearsal_form_fail BEFORE INSERT ON core_crm_form FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='form rehearsal rollback'");
            try {assertThrows(DataAccessException.class,()->importer.importForms(envelope));}
            finally {jdbc.execute("DROP TRIGGER rehearsal_form_fail");}
            assertEquals(0,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_form"));
            assertEquals(0,scalar(jdbc,"SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='form_trials_v1'"));
        }
        int rows=importer.importForms(envelope);
        assertEquals(rows,importer.importForms(envelope));
        assertCount(state,"forms","core_crm_form",jdbc);
        assertCount(state,"forms","core_form_collection_settings",jdbc);
        assertCount(state,"trialBookings","core_trial_booking",jdbc);
        int submissions=0;
        for(JsonNode form:state.path("forms")) submissions+=form.path("submissions").size();
        assertEquals(submissions,scalar(jdbc,"SELECT COUNT(id) FROM core_crm_form_submission"));
        assertEquals(1,scalar(jdbc,"SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='form_trials_v1'"));
    }

    private JsonNode matchingWithdrawal(JsonNode audit,String refundId) {
        JsonNode match=null;for(JsonNode event:audit) if("enrollment.withdraw".equals(event.path("action").asText())&&"success".equals(event.path("outcome").asText()))
            for(JsonNode change:event.path("changes")) if("refunds".equals(change.path("collection").asText())&&refundId.equals(change.path("id").asText())) {
                if(match!=null)return null;match=event;
            }
        return match;
    }

    private Set<String> sourcePrincipals(JsonNode state,JsonNode audit) {
        Set<String> ids=new TreeSet<>();
        ids.addAll(LegacyMigrationCandidateCli.requiredPrincipals(state,audit));
        for(JsonNode collection:state) if(collection.isArray()) for(JsonNode row:collection) {
            for(String field:new String[]{"ownerId","createdBy","teacherId","confirmedBy","recordedBy","actorId",
                    "applicantId","reviewedBy","waivedBy"}) add(ids,row.path(field));
            add(ids,row.path("original").path("teacherId"));
            for(JsonNode history:row.path("history")) add(ids,history.path("actorId"));
            for(JsonNode history:row.path("assignmentHistory")) add(ids,history.path("actorId"));
            for(String field:new String[]{"participantIds","promoterIds"}) if(row.path(field).isArray()) for(JsonNode value:row.path(field)) add(ids,value);
            for(JsonNode child:row.path("schedule")) add(ids,child.path("teacherId"));
        }
        for(JsonNode form:state.path("forms")) {
            add(ids,form.path("publishedBy"));
            add(ids,form.path("settings").path("ownerId"));
            for(JsonNode submission:form.path("submissions")) {
                add(ids,submission.path("publisherId"));add(ids,submission.path("reviewedBy"));
                if(!"public".equals(submission.path("submittedBy").asText())) add(ids,submission.path("submittedBy"));
            }
        }
        for(JsonNode coupon:state.path("activities").path("coupon")) add(ids,coupon.path("createdBy"));
        add(ids,state.path("decor").path("updatedBy"));
        for(JsonNode event:audit) add(ids,event.path("actorId"));
        ids.removeIf(String::isBlank);return ids;
    }
    private void add(Set<String> ids,JsonNode value) {if(value.isTextual()&&!value.asText().isBlank()) ids.add(value.asText());}
    private void assertCount(JsonNode state,String collection,String table,JdbcTemplate jdbc) {
        assertEquals(state.path(collection).size(),scalar(jdbc,"SELECT COUNT(id) FROM "+table));
    }
    private long sumMinor(JsonNode rows,String field) {long total=0;for(JsonNode row:rows) total=Math.addExact(total,row.path(field).asLong());return total;}
    private int scalar(JdbcTemplate jdbc,String sql) {return jdbc.queryForObject(sql,Integer.class);}
    private long scalarLong(JdbcTemplate jdbc,String sql) {return jdbc.queryForObject(sql,Long.class);}
}
