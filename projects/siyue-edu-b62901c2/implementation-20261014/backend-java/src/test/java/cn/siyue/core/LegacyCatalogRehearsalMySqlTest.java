package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class LegacyCatalogRehearsalMySqlTest {
    @Test void atomicEncryptedIdempotentCatalogImport() throws Exception {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPath().endsWith("_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();var jdbc=new JdbcTemplate(source);
        var mapper=new ObjectMapper();JsonNode envelope=input(mapper);
        var state=envelope.get("state");fixturePrincipals(jdbc,state);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var importer=new LegacyCatalogRehearsal(jdbc,cipher,new DataSourceTransactionManager(source));
        int before=jdbc.queryForObject("SELECT COUNT(id) FROM core_course",Integer.class);
        JsonNode invalid=envelope.deepCopy();
        ((ObjectNode)invalid.get("state").get("classes").get(0)).put("teacherId","missing-"+UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->importer.importCatalog(invalid));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(id) FROM core_course",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint",Integer.class));
        int expected=state.get("campuses").size()+state.get("courses").size()+state.get("classes").size()+state.get("students").size();
        for(JsonNode c:state.get("courses")) expected+=c.get("lessons").size();
        for(JsonNode c:state.get("classes")) expected+=c.get("schedule").size();
        assertEquals(expected,importer.importCatalog(envelope));
        assertEquals(expected,importer.importCatalog(envelope));
        verifyRows(jdbc,cipher,state);
        verifyFinancial(jdbc,cipher,new DataSourceTransactionManager(source),envelope);
        assertEquals(before+state.get("courses").size(),jdbc.queryForObject("SELECT COUNT(id) FROM core_course",Integer.class));
        String encrypted=jdbc.queryForObject("SELECT source_cipher FROM core_migration_checkpoint WHERE phase='academic_catalog_v1'",String.class);
        assertTrue(encrypted.startsWith("v2."));assertTrue(cipher.decrypt(encrypted).equals(state.toString()));
        JsonNode changed=envelope.deepCopy();((ObjectNode)changed).put("revision",envelope.get("revision").longValue()+1);
        assertThrows(IllegalArgumentException.class,()->importer.importCatalog(changed));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE core_migration_checkpoint SET imported_rows=0"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(p.id) FROM core_principal p JOIN core_principal_permission x ON x.principal_id=p.id WHERE p.oidc_issuer='https://migration-rehearsal.invalid'",Integer.class));
    }
    private void verifyFinancial(JdbcTemplate jdbc,PiiCipher cipher,DataSourceTransactionManager manager,JsonNode envelope) {
        var importer=new LegacyFinancialRehearsal(jdbc,cipher,manager);var state=envelope.get("state");
        int before=jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_order",Integer.class);
        if(!state.get("receipts").isEmpty()) {
            jdbc.execute("CREATE TRIGGER rehearsal_receipt_fail BEFORE INSERT ON core_crm_receipt FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rehearsal rollback'");
            try { assertThrows(org.springframework.dao.DataAccessException.class,()->importer.importFinancial(envelope)); }
            finally { jdbc.execute("DROP TRIGGER rehearsal_receipt_fail"); }
            assertEquals(before,jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_order",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_financial_v1'",Integer.class));
        }
        int expected=0;
        for(String key:java.util.Set.of("orders","enrollments","receipts","attendance","consumptions")) expected+=state.get(key).size();
        for(JsonNode row:state.get("orders")) expected+=row.get("lessonSnapshot").size();
        assertEquals(expected,importer.importFinancial(envelope));assertEquals(expected,importer.importFinancial(envelope));
        assertEquals(before+state.get("orders").size(),jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_order",Integer.class));
        for(JsonNode row:state.get("consumptions")) {
            var amount=jdbc.queryForObject("SELECT amount FROM core_consumption WHERE public_id=?",java.math.BigDecimal.class,row.get("id").asText());
            assertTrue(amount.compareTo(java.math.BigDecimal.valueOf(row.get("amountCents").longValue(),2))==0);
        }
    }
    private void verifyRows(JdbcTemplate jdbc,PiiCipher cipher,JsonNode state) {
        for(JsonNode student:state.get("students")) {
            var rows=jdbc.query("SELECT name_cipher,phone_cipher,version FROM core_student WHERE public_id=?",
                (rs,row)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},student.get("id").asText());
            assertEquals(1,rows.size());
            assertTrue(cipher.decrypt(rows.get(0)[0]).equals(student.get("name").asText()));
            assertTrue(cipher.decrypt(rows.get(0)[1]).equals(student.get("phone").asText()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(n.id) FROM core_student_name_index n JOIN core_student s ON s.id=n.student_id WHERE s.public_id=? AND n.source_version=s.version AND n.deleted=0",Integer.class,student.get("id").asText()));
        }
        for(JsonNode klass:state.get("classes")) for(JsonNode session:klass.get("schedule")) {
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(s.id) FROM core_session s JOIN core_class c ON c.id=s.class_id JOIN core_lesson l ON l.id=s.lesson_id WHERE s.public_id=? AND c.public_id=? AND l.public_id=?",
                Integer.class,session.get("id").asText(),klass.get("id").asText(),session.get("lessonId").asText()));
        }
    }
    private void fixturePrincipals(JdbcTemplate jdbc,JsonNode state) {
        var ids=new HashSet<String>();
        for(JsonNode c:state.get("classes")) {
            ids.add(c.get("teacherId").asText());
            for(JsonNode s:c.get("schedule")) if(s.hasNonNull("teacherId")) ids.add(s.get("teacherId").asText());
        }
        for(JsonNode student:state.get("students")) if(student.hasNonNull("ownerId")) ids.add(student.get("ownerId").asText());
        for(JsonNode r:state.path("receipts")) ids.add(r.get("confirmedBy").asText());
        for(JsonNode r:state.path("attendance")) ids.add(r.get("recordedBy").asText());
        for(JsonNode r:state.path("consumptions")) ids.add(r.get("recordedBy").asText());
        for(String id:ids) jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) VALUES(?,'https://migration-rehearsal.invalid',?,'assigned',0)",id,id);
    }
    private JsonNode input(ObjectMapper mapper) throws Exception {
        String path=System.getenv("SIYUE_LEGACY_REHEARSAL_SNAPSHOT");
        if(path!=null) return mapper.readTree(Files.readString(Path.of(path)));
        return mapper.readTree("""
            {"schema":"siyue.legacy-business/v1","revision":1,"state":{"students":[],"orders":[],"enrollments":[],"receipts":[],"refunds":[],"attendance":[],"consumptions":[],
              "campuses":[{"id":"legacy-cp","name":"模拟校区","active":true,"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}],
              "courses":[{"id":"legacy-c","campusId":"legacy-cp","name":"模拟课程","courseType":"onsite","grade":"小学四年级","subject":"数学","priceCents":10000,"active":true,"version":1,"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z","lessons":[{"id":"legacy-l","sequence":1,"title":"模拟讲次"}]}],
              "classes":[{"id":"legacy-cl","courseId":"legacy-c","campusId":"legacy-cp","teacherId":"legacy-p","name":"模拟班级","room":"模拟教室","capacity":10,"durationMinutes":60,"active":true,"version":1,"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z","schedule":[{"id":"legacy-ss","lessonId":"legacy-l","date":"2026-10-16","time":"23:00–24:00","status":"scheduled"}]}]
            }}
            """);
    }
}
