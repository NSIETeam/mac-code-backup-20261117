package cn.siyue.core;

import cn.siyue.core.CampusConfigurationDtos.Campus;
import cn.siyue.core.CampusConfigurationDtos.CampusFields;
import cn.siyue.core.CampusConfigurationDtos.RoomInput;
import cn.siyue.core.CampusConfigurationDtos.UpdateInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL transactions for campus configuration, occupancy and duplicate creation. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class CampusConfigurationMySqlTest {
    private static final String ISSUER = "https://campus-test.invalid";
    @Test void campusConfigurationTransactionsAndConcurrentNames() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost()); assertEquals(53306, uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_full_[a-f0-9]{10}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var access = new AccessRepository(jdbc);
        seedActor(jdbc, "admin1", "all"); seedActor(jdbc, "admin2", "all"); seedActor(jdbc, "campus", "campus");
        var plain = new CampusConfigurationService(access, new CampusConfigurationRepository(jdbc, cipher),
            new MutationRepository(jdbc, cipher), new ObjectMapper().findAndRegisterModules());
        var proxy = new ProxyFactory(plain);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        var service = (CampusConfigurationService) proxy.getProxy();
        Actor admin = access.resolve(ISSUER, "admin1");
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            CampusFields input = fields("合成校区甲", List.of(new RoomInput(null, "教室1", 30, 2)));
            Campus first = service.create(admin, "campus-create-first-0001", input);
            assertEquals(first, service.create(admin, "campus-create-first-0001", input));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_campus", Integer.class));
            assertNotEquals(first.name(), jdbc.queryForObject("SELECT name_cipher FROM core_campus WHERE public_id=?", String.class, first.id()));
            assertTrue(jdbc.queryForObject("SELECT configuration_ready FROM core_campus WHERE public_id=?", Boolean.class, first.id()));
            exerciseUpdate(service, admin, first);
            exerciseScope(jdbc, service, access, admin, first);
            exerciseOccupancy(jdbc, service, admin, first);
            exerciseRollback(jdbc, service, admin, first);
            exerciseConcurrentCreate(service, access);
            exerciseDictionary(jdbc, source, cipher, access);
            exerciseSettings(jdbc, source, cipher, access);
        } finally { MDC.remove("traceId"); }
    }
    private void exerciseSettings(JdbcTemplate jdbc, DriverManagerDataSource source, PiiCipher cipher,
            AccessRepository access) throws Exception {
        var plain = new SystemSettingsService(jdbc, access, new MutationRepository(jdbc, cipher), cipher,
            new ObjectMapper().findAndRegisterModules());
        var proxy = new ProxyFactory(plain);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        var service = (SystemSettingsService) proxy.getProxy();
        Actor admin = access.resolve(ISSUER, "admin1");
        var input = new SystemSettingsService.Input("attendance", java.util.Map.of("presentConsume", false, "allowMakeup", true));
        var first = service.update(admin, "settings-first-key-0001", input);
        assertEquals(first, service.update(admin, "settings-first-key-0001", input));
        assertEquals(input.values(), first.sections().get("attendance"));
        assertThrows(BusinessException.class, () -> service.update(access.resolve(ISSUER, "campus"), "settings-scope-key-0001", input));
        jdbc.execute("CREATE TRIGGER settings_failure BEFORE INSERT ON core_system_setting FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.update(admin,
                "settings-retry-key-0001", new SystemSettingsService.Input("organization", java.util.Map.of("name", "模拟机构"))));
        } finally { jdbc.execute("DROP TRIGGER settings_failure"); }
        assertEquals(first, service.get(admin));
        var next = service.update(admin, "settings-retry-key-0001", new SystemSettingsService.Input("organization", java.util.Map.of("name", "模拟机构")));
        assertEquals("模拟机构", next.sections().get("organization").get("name"));
        assertNotEquals("模拟机构", jdbc.queryForObject("SELECT value_cipher FROM core_system_setting WHERE section='organization' AND field_name='name'", String.class));
    }
    private void exerciseDictionary(JdbcTemplate jdbc, DriverManagerDataSource source, PiiCipher cipher,
            AccessRepository access) throws Exception {
        var plain = new AcademicDictionaryService(jdbc, access, new MutationRepository(jdbc, cipher),
            new ObjectMapper().findAndRegisterModules());
        var proxy = new ProxyFactory(plain);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        var service = (AcademicDictionaryService) proxy.getProxy();
        Actor admin = access.resolve(ISSUER, "admin1");
        var input = new AcademicDictionaryService.Input("subjects", List.of("数学", "历史自定义"));
        var first = service.update(admin, "dictionary-first-key-0001", input);
        assertEquals(first, service.update(admin, "dictionary-first-key-0001", input));
        assertEquals(input.values(), first.values().get("subjects"));
        assertThrows(BusinessException.class, () -> service.update(access.resolve(ISSUER, "campus"),
            "dictionary-forbidden-0001", input));
        jdbc.execute("CREATE TRIGGER dictionary_failure BEFORE INSERT ON core_academic_dictionary_value "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.update(admin,
                "dictionary-retry-key-0001", new AcademicDictionaryService.Input("subjects", List.of("英语"))));
        } finally { jdbc.execute("DROP TRIGGER dictionary_failure"); }
        assertEquals(first, service.get(admin));
        var second = service.update(admin, "dictionary-retry-key-0001",
            new AcademicDictionaryService.Input("subjects", List.of("英语")));
        assertEquals(List.of("英语"), second.values().get("subjects"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(id) FROM core_academic_dictionary_value WHERE kind='subjects' AND deleted=1", Integer.class));
        var revived = service.update(admin, "dictionary-revive-key-0001",
            new AcademicDictionaryService.Input("subjects", List.of("历史自定义", "数学")));
        assertEquals(List.of("历史自定义", "数学"), revived.values().get("subjects"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(id) FROM core_academic_dictionary_value WHERE kind='subjects'", Integer.class));
    }
    private void exerciseUpdate(CampusConfigurationService service, Actor admin, Campus first) throws Exception {
        UpdateInput input = new UpdateInput(new CampusFields("合成校区新名", "合成地址", "10000", null, null, null, null), 1);
        Campus updated = service.update(admin, first.id(), "campus-update-first-0001", input);
        assertEquals(2, updated.version()); assertEquals(first.rooms(), updated.rooms());
        assertEquals(updated, service.update(admin, first.id(), "campus-update-first-0001", input));
        BusinessException conflict = assertThrows(BusinessException.class,
            () -> service.update(admin, first.id(), "campus-update-stale-0001", input));
        assertEquals(ErrorCode.VERSION_CONFLICT, conflict.code());
    }
    private void exerciseScope(JdbcTemplate jdbc, CampusConfigurationService service, AccessRepository access,
            Actor admin, Campus first) throws Exception {
        Campus second = service.create(admin, "campus-create-second-0001", fields("合成校区乙", List.of()));
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) SELECT p.id,c.id FROM core_principal p "
            + "JOIN core_campus c ON c.public_id=? WHERE p.public_id='campus'", first.id());
        Actor campus = access.resolve(ISSUER, "campus");
        assertEquals(1, service.list(campus, 1, 50).total());
        assertThrows(BusinessException.class, () -> service.get(campus, second.id()));
        assertThrows(BusinessException.class, () -> service.create(campus, "campus-scope-reject-0001", fields("越权", List.of())));
    }
    private void exerciseOccupancy(JdbcTemplate jdbc, CampusConfigurationService service, Actor admin, Campus first) {
        jdbc.update("INSERT INTO core_course(public_id,name,course_type,grade,subject,price) VALUES('COURSE_CAMPUS','合成课程','onsite','小学一年级','数学',10)");
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) "
            + "SELECT 'CLASS_CAMPUS',co.id,cp.id,p.id,'合成班级','教室1',20,60 FROM core_course co "
            + "JOIN core_campus cp ON cp.public_id=? JOIN core_principal p ON p.public_id='admin1' WHERE co.public_id='COURSE_CAMPUS'", first.id());
        for (CampusFields patch : List.of(new CampusFields(null, null, null, null, false, null, null),
                new CampusFields(null, null, null, List.of(), null, null, null),
                new CampusFields(null, null, null, List.of(new RoomInput(first.rooms().get(0).id(), "教室1", 10, 2)), null, null, null),
                new CampusFields(null, null, null, null, null, List.of("英语"), null))) {
            assertThrows(BusinessException.class, () -> service.update(admin, first.id(), "campus-occupancy-reject-0001", new UpdateInput(patch, 2)));
        }
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) "
            + "SELECT 'LESSON_CAMPUS_LOCATION',id,1,'合成课次' FROM core_course WHERE public_id='COURSE_CAMPUS'");
        jdbc.update("INSERT INTO core_session(public_id,class_id,campus_id,room,lesson_id,teacher_id,starts_at,ends_at,status) "
            + "SELECT 'SESSION_CAMPUS_LOCATION',c.id,?, '教室1',l.id,c.teacher_id,UTC_TIMESTAMP(3)+INTERVAL 1 DAY,"
            + "UTC_TIMESTAMP(3)+INTERVAL 1 DAY+INTERVAL 1 HOUR,'scheduled' FROM core_class c "
            + "JOIN core_lesson l ON l.public_id='LESSON_CAMPUS_LOCATION' WHERE c.public_id='CLASS_CAMPUS'",
            campusId(jdbc, first.id()));
        long otherCampus = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id<>? AND deleted=0",
            Long.class, first.id());
        jdbc.update("UPDATE core_class SET campus_id=?,room='已迁班教室' WHERE public_id='CLASS_CAMPUS'", otherCampus);
        assertThrows(BusinessException.class, () -> service.update(admin, first.id(),
            "campus-location-snapshot-room-protection-01", new UpdateInput(
                new CampusFields(null, null, null, List.of(), null, null, null), 2)));
        assertEquals(2, service.get(admin, first.id()).version());
    }
    private long campusId(JdbcTemplate jdbc, String publicId) {
        return jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?", Long.class, publicId);
    }
    private void exerciseRollback(JdbcTemplate jdbc, CampusConfigurationService service, Actor admin, Campus first) {
        jdbc.execute("CREATE TRIGGER campus_test_fail BEFORE UPDATE ON core_campus_room FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.update(admin, first.id(),
                "campus-rollback-test-0001", new UpdateInput(new CampusFields("回滚校区", null, null, null, null, null, null), 2)));
        } finally { jdbc.execute("DROP TRIGGER campus_test_fail"); }
        assertEquals("合成校区新名", service.get(admin, first.id()).name());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_idempotency WHERE request_key='campus-rollback-test-0001'", Integer.class));
    }
    private void exerciseConcurrentCreate(CampusConfigurationService service, AccessRepository access) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> race(service, access.resolve(ISSUER, "admin1"), ready, start));
            var second = pool.submit(() -> race(service, access.resolve(ISSUER, "admin2"), ready, start));
            assertTrue(ready.await(15, TimeUnit.SECONDS)); start.countDown();
            List<String> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("created"::equals).count());
            assertEquals(1, outcomes.stream().filter("ALREADY_EXISTS"::equals).count());
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)); }
    }
    private String race(CampusConfigurationService service, Actor actor, CountDownLatch ready, CountDownLatch start) throws Exception {
        MDC.put("traceId", UUID.randomUUID().toString()); ready.countDown();
        try {
            assertTrue(start.await(15, TimeUnit.SECONDS));
            service.create(actor, "campus-race-key-" + actor.publicId(), fields("合成并发同名", List.of()));
            return "created";
        } catch (BusinessException error) { return error.code().name(); }
        finally { MDC.remove("traceId"); }
    }
    private void seedActor(JdbcTemplate jdbc, String id, String scope) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,?)", id, ISSUER, id, scope);
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) SELECT id,'settings.update' FROM core_principal WHERE public_id=?", id);
    }
    private CampusFields fields(String name, List<RoomInput> rooms) {
        return new CampusFields(name, null, null, rooms, true, List.of("数学"), List.of("小学一年级"));
    }
}
