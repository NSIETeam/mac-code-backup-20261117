package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class LegacyScheduleHistoryRehearsalMySqlTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private PiiCipher cipher;
    private ObjectNode envelope;
    private LegacyScheduleHistoryRehearsal importer;

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPath().endsWith("_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var flyway=Flyway.configure().dataSource(source).cleanDisabled(false).load();flyway.clean();flyway.migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        for(String actor:new String[]{"legacy-actor","legacy-reviewer"}) jdbc.update(
            "INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) VALUES(?,'https://synthetic.invalid',?,'all',0)",actor,actor);
        envelope=(ObjectNode)new LegacyFinancialRehearsalMySqlTest().fixture(mapper);
        ObjectNode state=(ObjectNode)envelope.path("state");
        ObjectNode target=((ObjectNode)state.path("classes").get(0)).deepCopy();target.put("id","legacy-target");
        ((ObjectNode)target.path("schedule").get(0)).put("id","legacy-target-session");
        ((ArrayNode)state.path("classes")).add(target);
        ArrayNode adjustments=state.putArray("sessionAdjustments");
        adjustments.add(adjustment("legacy-active","active"));adjustments.add(adjustment("legacy-cancelled","cancelled"));
        ArrayNode requests=state.putArray("rescheduleRequests");
        requests.add(request("legacy-pending","pending"));requests.add(request("legacy-approved","approved"));requests.add(request("legacy-rejected","rejected"));
        for(var row:adjustments) {
            audit(row.path("id").asText(),"sessionAdjustments","makeup.assign","legacy-actor");
            if("cancelled".equals(row.path("status").asText())) audit(row.path("id").asText(),"sessionAdjustments","makeup.cancel","legacy-reviewer");
        }
        for(var row:requests) {
            audit(row.path("id").asText(),"rescheduleRequests","session.reschedule.request","legacy-actor");
            if(!"pending".equals(row.path("status").asText())) audit(row.path("id").asText(),"rescheduleRequests","session.reschedule.review","legacy-reviewer");
        }
        importer=new LegacyScheduleHistoryRehearsal(jdbc,cipher,manager);
    }
    private void dependencies() {
        new LegacyCatalogRehearsal(jdbc,cipher,manager).importCatalog(envelope);
        new LegacyFinancialRehearsal(jdbc,cipher,manager).importFinancial(envelope);
    }
    @Test void preservesActorsOriginalFingerprintDecisionsAndExactReplay() {
        dependencies();assertEquals(11,importer.importScheduleHistory(envelope));assertEquals(11,importer.importScheduleHistory(envelope));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_reschedule_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment_event",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_reschedule_request WHERE reviewer_id=(SELECT id FROM core_principal WHERE public_id='legacy-reviewer')",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_reschedule_request WHERE applicant_id=original_teacher_id AND original_session_version=1 AND original_class_version=1",Integer.class));
        assertEquals("合成原因",cipher.decrypt(jdbc.queryForObject("SELECT reason_cipher FROM core_session_adjustment LIMIT 1",String.class)));
        String archive=jdbc.queryForObject("SELECT source_cipher FROM core_migration_checkpoint WHERE phase='academic_schedule_history_v1'",String.class);
        assertTrue(cipher.decrypt(archive).contains("applicantName"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE core_session_reschedule_event SET to_status='rejected'"));
        ObjectNode changed=envelope.deepCopy();((ObjectNode)changed.path("state").path("rescheduleRequests").get(0)).put("reason","changed");
        assertThrows(IllegalArgumentException.class,()->importer.importScheduleHistory(changed));
    }
    @Test void preservesBusinessAndAuditTimesAsSeparateUtcInstants() {
        for(var event:envelope.path("auditProvenance")) {
            if(event.path("action").asText().startsWith("makeup.")
                    ||event.path("action").asText().startsWith("session.reschedule.")) {
                Instant occurred=Instant.parse(event.path("at").asText()).plusMillis(125);
                ((ObjectNode)event).put("at",occurred.atOffset(ZoneOffset.ofHours(8)).toString());
            }
        }
        dependencies();
        assertEquals(11,importer.importScheduleHistory(envelope));
        Timestamp businessCreated=jdbc.queryForObject(
            "SELECT created_at FROM core_session_adjustment WHERE public_id='legacy-active'",Timestamp.class);
        Timestamp auditOccurred=jdbc.queryForObject(
            "SELECT occurred_at FROM core_session_reschedule_event WHERE event_type='requested' LIMIT 1",Timestamp.class);
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"),businessCreated.toInstant());
        assertEquals(Instant.parse("2026-10-01T00:00:00.125Z"),auditOccurred.toInstant());
        String archive=jdbc.queryForObject(
            "SELECT source_cipher FROM core_migration_checkpoint WHERE phase='academic_schedule_history_v1'",String.class);
        assertTrue(cipher.decrypt(archive).contains("2026-10-01T08:00:00.125+08:00"));
    }
    @Test void rejectsAnAuditThatPredatesItsBusinessEvent() {
        for(var event:envelope.path("auditProvenance")) if("makeup.assign".equals(event.path("action").asText())) {
            ((ObjectNode)event).put("at",Instant.parse(event.path("at").asText()).minusMillis(1).toString());
            break;
        }
        dependencies();
        assertThrows(IllegalArgumentException.class,()->importer.importScheduleHistory(envelope));
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_schedule_history_v1'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment",Integer.class));
    }
    @Test void rollsBackEveryHistoryRowAndCheckpointOnLateFailure() {
        dependencies();jdbc.execute("CREATE TRIGGER schedule_test_fail BEFORE INSERT ON core_session_reschedule_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        try {assertThrows(org.springframework.dao.DataAccessException.class,()->importer.importScheduleHistory(envelope));}
        finally {jdbc.execute("DROP TRIGGER schedule_test_fail");}
        for(String table:new String[]{"core_session_adjustment","core_session_adjustment_event","core_session_reschedule_request","core_session_reschedule_event"})
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM "+table,Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_schedule_history_v1'",Integer.class));
        assertEquals(11,importer.importScheduleHistory(envelope));
    }
    @Test void rejectsMissingReviewerMapping() {
        ((ObjectNode)envelope.path("state").path("rescheduleRequests").get(1)).put("reviewedBy","unmapped-reviewer");
        for(var audit:envelope.path("auditProvenance")) if(audit.path("id").asText().equals("legacy-approved-session.reschedule.review")) ((ObjectNode)audit).put("actorId","unmapped-reviewer");
        dependencies();assertThrows(IllegalArgumentException.class,()->importer.importScheduleHistory(envelope));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment",Integer.class));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) VALUES('unmapped-reviewer','https://synthetic.invalid','unmapped-reviewer','all',0)");
        assertEquals(11,importer.importScheduleHistory(envelope));
    }
    @Test void preservesCompletedConsumptionAndWaiverActor() {
        ObjectNode state=(ObjectNode)envelope.path("state");
        ((ObjectNode)state.path("sessionAdjustments").get(0)).put("status","completed").put("completedAt","2026-10-01T00:00:00Z");
        ((ObjectNode)state.path("attendance").get(0)).put("sessionId","legacy-target-session");
        ((ObjectNode)state.path("consumptions").get(0)).put("sessionId","legacy-target-session");
        audit("legacy-active","sessionAdjustments","session.attendance","legacy-actor");
        ObjectNode waiver=adjustment("legacy-waiver","waived");waiver.put("kind","waiver").put("waivedBy","legacy-actor");
        waiver.remove("targetClassId");waiver.remove("targetSessionId");
        ((ArrayNode)state.path("sessionAdjustments")).add(waiver);
        audit("legacy-waiver","sessionAdjustments","makeup.waive","legacy-actor");
        dependencies();assertEquals(12,importer.importScheduleHistory(envelope));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment WHERE status='completed'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment WHERE status='waived' AND target_session_id IS NULL AND created_by_id=(SELECT id FROM core_principal WHERE public_id='legacy-actor')",Integer.class));
    }
    @Test void staleOriginalCannotBecomeApprovableAfterImport() {
        ObjectNode original=(ObjectNode)envelope.path("state").path("rescheduleRequests").get(0).path("original");
        original.put("classVersion",2).put("lessonVersion",7);
        dependencies();assertEquals(11,importer.importScheduleHistory(envelope));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_reschedule_request WHERE public_id='legacy-pending' AND original_class_version=2 AND original_session_version=0",Integer.class));
    }
    @Test void oneWithdrawalAuditCanCancelMultipleReservationsWithoutLosingEvents() {
        ((ObjectNode)envelope.path("state").path("sessionAdjustments").get(0)).put("status","cancelled").put("cancelReason","原报名退课");
        for(var event:envelope.path("auditProvenance")) {
            if("makeup.cancel".equals(event.path("action").asText())) {
                ((ObjectNode)event).put("action","enrollment.withdraw");
                ((ArrayNode)event.path("changes")).addObject().put("collection","sessionAdjustments").put("id","legacy-active");
            }
        }
        dependencies();assertEquals(12,importer.importScheduleHistory(envelope));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment_event",Integer.class));
    }
    private ObjectNode adjustment(String id,String status) {
        ObjectNode row=mapper.createObjectNode();row.put("id",id).put("kind","temporary").put("status",status)
            .put("enrollmentId","legacy-enrollment").put("orderId","legacy-order").put("studentId","legacy-student")
            .put("sourceClassId","legacy-class").put("sourceSessionId","legacy-session").put("lessonId","legacy-lesson-1")
            .put("targetClassId","legacy-target").put("targetSessionId","legacy-target-session").put("campusId","legacy-campus")
            .put("ownerId","legacy-actor").put("reason","合成原因").put("version",1).put("createdAt","2026-10-01T00:00:00Z").put("updatedAt","2026-10-01T00:00:00Z");
        if("cancelled".equals(status)) row.put("cancelReason","合成取消");return row;
    }
    private ObjectNode request(String id,String status) {
        ObjectNode row=mapper.createObjectNode();row.put("id",id).put("classId","legacy-class").put("sessionId","legacy-session")
            .put("campusId","legacy-campus").put("applicantId","legacy-actor").put("applicantName","合成教师")
            .put("date","2026-10-03").put("time","10:00–11:00").put("reason","合成原因").put("status",status)
            .put("version",1).put("createdAt","2026-10-01T00:00:00Z").put("updatedAt","2026-10-01T00:00:00Z");
        row.putObject("original").put("date","2026-10-02").put("time","10:00–11:00").put("teacherId","legacy-actor")
            .put("lessonId","legacy-lesson-1").putNull("lessonVersion").put("room","R1").put("campusId","legacy-campus").put("classVersion",1);
        if(!"pending".equals(status)) row.put("reviewedBy","legacy-reviewer").put("reviewedAt","2026-10-01T00:00:00Z").put("note","合成审批");return row;
    }
    private void audit(String id,String collection,String action,String actor) {
        ObjectNode event=((ArrayNode)envelope.path("auditProvenance")).addObject();event.put("id",id+"-"+action)
            .put("actorId",actor).put("action",action).put("outcome","success").put("at","2026-10-01T00:00:00Z");
        event.putArray("changes").addObject().put("collection",collection).put("id",id);
    }
}
