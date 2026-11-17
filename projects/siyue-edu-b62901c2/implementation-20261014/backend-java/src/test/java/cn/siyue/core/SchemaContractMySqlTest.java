package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.function.Executable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.api.MigrationVersion;

/** Verifies the mandatory storage contract against the migrated MySQL schema. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL", matches=".+")
class SchemaContractMySqlTest {
    @Test void migratedCoreTablesFollowStorageContract() {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        String expectedDatabase = System.getenv().getOrDefault("SIYUE_SCHEMA_CONTRACT_DATABASE", "siyue_schema_contract_test");
        assertTrue(expectedDatabase.matches("siyue_schema_contract(?:_[a-f0-9]{10})?_test"),
            "Only explicitly designated schema-contract test databases are permitted");
        assertEquals(expectedDatabase, jdbc.queryForObject("SELECT DATABASE()", String.class),
            "This test is destructive to schema state and must use its dedicated empty schema");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()", Integer.class),
            "Start with an empty dedicated schema so the V45-to-latest upgrade is exercised");
        Flyway.configure().dataSource(source).target(MigrationVersion.fromVersion("45")).load().migrate();
        seedVersion45Rows(jdbc);
        Flyway latestFlyway = Flyway.configure().dataSource(source).load();
        latestFlyway.migrate();
        var migrationInfo = latestFlyway.info();
        assertEquals(0, migrationInfo.pending().length, "All migrations in this source tree must be applied");
        var latestResolvedVersion = java.util.Arrays.stream(migrationInfo.all())
            .map(info -> info.getVersion())
            .filter(java.util.Objects::nonNull)
            .max(MigrationVersion::compareTo)
            .orElseThrow(() -> new AssertionError("No versioned migration was resolved"));
        assertEquals(latestResolvedVersion, migrationInfo.current().getVersion(),
            "The contract check must include the latest migration present in this source tree");

        assertVersion45HistorySurvives(jdbc);
        assertAppendOnlyRowsAcceptNewInserts(jdbc);
        assertLeadDecisionContract(jdbc);
        assertAppendOnlyUpdatesRemainBlocked(jdbc);
        List<String> tables = jdbc.queryForList("""
            SELECT TABLE_NAME FROM information_schema.TABLES
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME LIKE 'core\\_%'
            """, String.class);
        assertFalse(tables.isEmpty(), "The test must examine migrated core tables");
        List<Executable> checks = new ArrayList<>();
        for (String table : tables) {
            checks.add(() -> assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME=? AND ENGINE='InnoDB' AND TABLE_COLLATION='utf8mb4_0900_ai_ci'
                """, Integer.class, table), table + " engine/collation"));
            checks.add(() -> assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME=? AND COLUMN_NAME='id' AND DATA_TYPE='bigint'
                AND COLUMN_TYPE LIKE '%unsigned%' AND EXTRA LIKE '%auto_increment%' AND COLUMN_KEY='PRI'
                """, Integer.class, table), table + " unsigned auto-increment identity"));
            checks.add(() -> assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME=? AND COLUMN_NAME IN ('created_at','updated_at','deleted')
                """, Integer.class, table), table + " required lifecycle columns"));
            checks.add(() -> assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                AND TABLE_NAME=? AND (DATA_TYPE IN ('float','double','timestamp')
                  OR (DATA_TYPE='decimal' AND (NUMERIC_PRECISION<>18 OR NUMERIC_SCALE<>2))
                  OR (DATA_TYPE='datetime' AND DATETIME_PRECISION<>3))
                """, Integer.class, table), table + " exact decimal and millisecond time types"));
        }
        assertAll("Migrated MySQL storage contract", checks);
    }

    private static void seedVersion45Rows(JdbcTemplate jdbc) {
        jdbc.execute("SET time_zone = '+00:00'");
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,'all')",
            "schema-owner", "https://schema.test", "schema-owner");
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,?)", "schema-campus", "cipher");
        jdbc.update("INSERT INTO core_course(public_id,campus_id,name,course_type,grade,subject,price) VALUES(?,1,?,'onsite',?,?,?)",
            "schema-course", "Schema test", "G", "M", 10.00);
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES(?,1,1,?)", "schema-lesson", "Lesson");
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) VALUES(?,1,1,1,?,'R',10,60)",
            "schema-class", "Schema class");
        jdbc.update("INSERT INTO core_student(public_id,name_cipher) VALUES(?,?)", "schema-student", "cipher");
        jdbc.update("INSERT INTO core_customer(public_id,owner_id,display_name_cipher) VALUES(?,1,?)", "schema-customer", "cipher");
        jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,owner_id,title,subject,term,need_key) VALUES(?,1,1,?,?,?,UNHEX(REPEAT('a',64)))",
            "schema-opportunity", "Schema opportunity", "Math", "Fall");
        jdbc.update("INSERT INTO core_opportunity_need_event(public_id,opportunity_id,actor_id,event_type,detail_cipher,occurred_at) VALUES(?,1,1,'profile',?,?)",
            "schema-need-event", "cipher", "2026-10-10 03:04:05.123");
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,owner_id,source,title) VALUES(?,1,1,'import',?)",
            "schema-lead", "Schema lead");
        jdbc.update("INSERT INTO core_crm_task_batch(public_id,created_by,title,task_type,due_at) VALUES(?,1,?,'service',?)",
            "schema-task-batch", "Schema batch", "2026-10-11 03:04:05.123");
        jdbc.update("INSERT INTO core_crm_task(public_id,batch_id,customer_id,owner_id,task_type,title,due_at) VALUES(?,1,1,1,'service',?,?)",
            "schema-task", "Schema task", "2026-10-11 03:04:05.123");
        jdbc.update("INSERT INTO core_crm_task_source(task_id,source_type,source_public_id,lead_id,source_version) VALUES(1,'lead',?,1,1)",
            "schema-lead");
        jdbc.update("INSERT INTO core_crm_task_routing_event(public_id,batch_id,actor_id,action,changes_cipher) VALUES(?,1,1,'create',?)",
            "schema-routing-event", "cipher");
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,attributed_owner_id,product_snapshot,total_amount) VALUES(?,1,1,1,?,100)",
            "schema-order", "Schema order");
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status) VALUES(?,1,1,1,'active')", "schema-enrollment");
        jdbc.update("INSERT INTO core_enrollment_lesson(public_id,enrollment_id,order_id,source_lesson_id,sequence_no,title_snapshot,unit_amount) VALUES(?,1,1,1,1,?,10)",
            "schema-entitlement", "Lesson");
        jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) VALUES(?,1,1,1,?,?, 'scheduled')",
            "schema-session", "2026-10-12 03:04:05.123", "2026-10-12 04:04:05.123");
        jdbc.update("INSERT INTO core_attendance(public_id,session_id,student_id,enrollment_id,actor_id,status) VALUES(?,1,1,1,1,'attended')",
            "schema-attendance");
        jdbc.update("INSERT INTO core_attendance_correction(public_id,attendance_id,actor_id,previous_version,previous_status,status,reason_cipher) VALUES(?,1,1,1,'attended','late',?)",
            "schema-correction", "cipher");
        jdbc.update("INSERT INTO core_enrollment_transfer(public_id,enrollment_id,from_class_id,target_class_id,previous_version,actor_id,reason_cipher) VALUES(?,1,1,1,1,1,?)",
            "schema-transfer", "cipher");
        jdbc.update("INSERT INTO core_enrollment_lesson_delivery(transfer_id,target_lesson_id,entitlement_lesson_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO core_enrollment_placement(enrollment_id,class_id,transfer_id,version,effective_at) VALUES(1,1,1,2,?)",
            "2026-10-13 03:04:05.123");
        jdbc.update("INSERT INTO core_class_fee(class_id,course_fee,material_fee,course_refund_rule,material_refund_rule) VALUES(1,10,0,'unused_lessons','after_enrollment')");
        jdbc.update("INSERT INTO core_crm_order_class_terms(order_id,class_id,terms_snapshot) VALUES(1,1,'{}')");
    }

    private static void assertVersion45HistorySurvives(JdbcTemplate jdbc) {
        assertEquals("2026-10-10 03:04:05.123", jdbc.queryForObject(
            "SELECT DATE_FORMAT(occurred_at,'%Y-%m-%d %H:%i:%s.%f') FROM core_opportunity_need_event WHERE public_id='schema-need-event'",
            String.class).substring(0, 23));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity_need_event WHERE public_id='schema-need-event' AND created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted=0", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_task_source WHERE task_id=1 AND created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted=0", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_attendance_correction WHERE attendance_id=1 AND created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted=0", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_enrollment_transfer WHERE enrollment_id=1 AND created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted=0", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_task_source WHERE task_id=1 AND source_version=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_attendance_correction WHERE attendance_id=1 AND previous_version=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_enrollment_lesson_delivery WHERE transfer_id=1 AND target_lesson_id=1 AND entitlement_lesson_id=1 AND created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted=0", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_class_fee WHERE id=1 AND class_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_order_class_terms WHERE id=1 AND order_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity_catalog WHERE id=1 AND singleton=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_student_identifier_registry WHERE id=1 AND singleton=1 AND revision=1", Integer.class));
        assertUniqueIndex(jdbc, "core_class_fee", "class_id");
        assertUniqueIndex(jdbc, "core_crm_order_class_terms", "order_id");
        assertUniqueIndex(jdbc, "core_enrollment_placement", "enrollment_id");
        assertUniqueIndex(jdbc, "core_enrollment_lesson_delivery", "transfer_id,target_lesson_id");
        assertUniqueIndex(jdbc, "core_enrollment_lesson_delivery", "transfer_id,entitlement_lesson_id");
        assertUniqueIndex(jdbc, "core_opportunity_catalog", "singleton");
        assertUniqueIndex(jdbc, "core_student_identifier_registry", "singleton");
    }

    private static void assertAppendOnlyRowsAcceptNewInserts(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO core_crm_task_source(task_id,source_type,source_public_id,lead_id,source_version) VALUES(1,'lead','schema-lead-new',1,1)");
        jdbc.update("INSERT INTO core_attendance_correction(public_id,attendance_id,actor_id,previous_version,previous_status,status,reason_cipher) VALUES('schema-correction-new',1,1,2,'late','attended','cipher')");
        jdbc.update("INSERT INTO core_crm_task_routing_event(public_id,batch_id,actor_id,action,changes_cipher) VALUES('schema-routing-event-new',1,1,'create','cipher')");
        jdbc.update("INSERT INTO core_opportunity_need_event(public_id,opportunity_id,actor_id,event_type,detail_cipher) VALUES('schema-need-event-new',1,1,'profile','cipher')");
        jdbc.update("INSERT INTO core_enrollment_transfer(public_id,enrollment_id,from_class_id,target_class_id,previous_version,actor_id,reason_cipher) VALUES('schema-transfer-new',1,1,1,2,1,'cipher')");
        Long transferId = jdbc.queryForObject("SELECT id FROM core_enrollment_transfer WHERE public_id='schema-transfer-new'", Long.class);
        jdbc.update("INSERT INTO core_enrollment_lesson_delivery(transfer_id,target_lesson_id,entitlement_lesson_id) VALUES(?,1,1)", transferId);
        jdbc.update("INSERT INTO core_crm_order(public_id,student_id,campus_id,attributed_owner_id,product_snapshot,total_amount,received_amount,status,source_kind,subject_snapshot) VALUES('schema-order-new',1,1,1,'Schema order',10,10,'received','direct_enrollment','M')");
        Long orderId = jdbc.queryForObject("SELECT id FROM core_crm_order WHERE public_id='schema-order-new'", Long.class);
        jdbc.update("INSERT INTO core_crm_order_class_terms(order_id,class_id,terms_snapshot) VALUES(?,1,'{}')", orderId);
        assertNewLifecycle(jdbc,"core_crm_task_source","source_public_id=?","schema-lead-new");
        assertNewLifecycle(jdbc,"core_attendance_correction","public_id=?","schema-correction-new");
        assertNewLifecycle(jdbc,"core_crm_task_routing_event","public_id=?","schema-routing-event-new");
        assertNewLifecycle(jdbc,"core_opportunity_need_event","public_id=?","schema-need-event-new");
        assertNewLifecycle(jdbc,"core_enrollment_transfer","public_id=?","schema-transfer-new");
        assertNewLifecycle(jdbc,"core_enrollment_lesson_delivery","transfer_id=?",transferId);
        assertNewLifecycle(jdbc,"core_crm_order_class_terms","order_id=?",orderId);
    }

    private static void assertNewLifecycle(JdbcTemplate jdbc,String table,String predicate,Object key) {
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM " + table
            + " WHERE " + predicate + " AND created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted=0",
            Integer.class,key),table + " new row lifecycle timestamps");
    }

    private static void assertUniqueIndex(JdbcTemplate jdbc, String table, String columns) {
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
                SELECT INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND NON_UNIQUE=0
                GROUP BY INDEX_NAME
            ) AS unique_indexes
            WHERE indexed_columns=?
            """, Integer.class, table, columns), table + " unique business key " + columns);
    }

    private static void assertAppendOnlyUpdatesRemainBlocked(JdbcTemplate jdbc) {
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_opportunity_need_event SET event_type=event_type WHERE public_id='schema-need-event'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_crm_task_source SET source_version=source_version WHERE task_id=1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_attendance_correction SET status=status WHERE attendance_id=1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_enrollment_transfer SET reason_cipher=reason_cipher WHERE enrollment_id=1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_enrollment_lesson_delivery SET target_lesson_id=target_lesson_id WHERE transfer_id=1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_crm_task_routing_event SET action=action WHERE batch_id=1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_crm_order_class_terms SET terms_snapshot=terms_snapshot WHERE order_id=1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE core_crm_lead_decision_event SET decision=decision WHERE public_id='schema-decision-event'"));
    }

    private static void assertLeadDecisionContract(JdbcTemplate jdbc) {
        assertEquals(4, jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='core_crm_lead' AND COLUMN_NAME IN
                ('decision_status','decision_by_id','decision_deadline','decision_timed_out_at')
            """, Integer.class));
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,owner_id,source,title,decision_status,decision_by_id,decision_deadline) "
            + "VALUES('schema-lead-decision',1,1,'import','Decision test','pending',1,UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)");
        Long leadId = jdbc.queryForObject("SELECT id FROM core_crm_lead WHERE public_id='schema-lead-decision'", Long.class);
        jdbc.update("INSERT INTO core_crm_lead_decision_event(public_id,lead_id,actor_id,decision,occurred_at,source_ordinal,snapshot_cipher) "
            + "VALUES('schema-decision-event',?,1,'transfer',UTC_TIMESTAMP(3),0,'cipher')", leadId);
        assertNewLifecycle(jdbc, "core_crm_lead_decision_event", "public_id=?", "schema-decision-event");
    }
}
