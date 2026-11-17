package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.siyue.core.AcademicClassRuntimeDtos.RecordInput;
import cn.siyue.core.AcademicClassRuntimeDtos.RelocateClass;
import cn.siyue.core.AcademicClassRuntimeDtos.RelocateInput;
import cn.siyue.core.AcademicClassRuntimeDtos.ScheduleInput;
import cn.siyue.core.AcademicClassRuntimeDtos.ScheduleSession;
import cn.siyue.core.AcademicClassRuntimeDtos.SubstituteInput;
import cn.siyue.core.AcademicDtos.ClassUpdateInput;
import java.net.URI;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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

/** Exercises V56 class and session actions using real MySQL locks, constraints and append-only history. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class AcademicClassRuntimeMySqlTest {
    private static final String ISSUER = "https://academic-class-runtime-test.invalid";

    @Test
    void teacherRescheduleRequestIsVersionedAndOnlyReviewMovesTheSession() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_[a-z0-9_]+_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        seed(jdbc, cipher);
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) SELECT id,"
            + "'session.reschedule.request' FROM core_principal WHERE public_id='runtime-teacher-1'");
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) SELECT id,"
            + "'session.move' FROM core_principal WHERE public_id='runtime-admin'");
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) SELECT id,"
            + "'session.move' FROM core_principal WHERE public_id='runtime-campus'");
        jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) "
            + "SELECT 'runtime-reschedule-session',c.id,l.id,c.teacher_id,UTC_TIMESTAMP(3)+INTERVAL 2 DAY,"
            + "UTC_TIMESTAMP(3)+INTERVAL 2 DAY+INTERVAL 1 HOUR,'scheduled' FROM core_class c JOIN core_lesson l "
            + "ON l.course_id=c.course_id WHERE c.public_id='runtime-class-1' AND l.public_id='runtime-lesson-1'");
        var access = new AccessRepository(jdbc);
        var mutations = new MutationRepository(jdbc, cipher);
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var repository = new AcademicRescheduleRepository(jdbc, cipher);
        var rescheduleMutations = new AcademicRescheduleMutations(mutations, objectMapper);
        var academic = transactionalAcademicService(jdbc, cipher, source);
        var service = new AcademicRescheduleService(access, new AcademicRepository(jdbc, cipher), repository,
            academic, rescheduleMutations);
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        AcademicRescheduleService transactional = (AcademicRescheduleService) factory.getProxy();
        Actor teacher = access.resolve(ISSUER, "runtime-teacher-1-subject");
        Actor reviewer = access.resolve(ISSUER, "runtime-admin-subject");
            Actor campusReviewer = access.resolve(ISSUER, "runtime-campus-subject");
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            Instant original = jdbc.queryForObject("SELECT starts_at FROM core_session WHERE "
                + "public_id='runtime-reschedule-session'", Timestamp.class).toInstant();
            Instant requestedStarts = original.atZone(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1)
                .withSecond(0).withNano(0).toInstant();
            jdbc.update("INSERT INTO core_system_setting(section,field_name,value_type,value_cipher) "
                + "VALUES('move','maxDays','integer',?)", cipher.encrypt("0"));
            var requestInput = new AcademicRescheduleDtos.RequestInput("runtime-class-1",
                "runtime-reschedule-session", requestedStarts.atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toLocalDate().toString(), timeRange(requestedStarts, 60), "教师临时有事", 1);
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BusinessException.class, () ->
                transactional.request(teacher, "runtime-max-days-request-key-01", requestInput)).code());
            jdbc.update("DELETE FROM core_system_setting WHERE section='move' AND field_name='maxDays'");

            seedRescheduleStudentConflict(jdbc, requestedStarts);
            assertEquals(ErrorCode.ALREADY_EXISTS, assertThrows(BusinessException.class, () ->
                transactional.request(teacher, "runtime-student-conflict-key-01", requestInput)).code());
            jdbc.update("UPDATE core_session SET status='cancelled' WHERE public_id='runtime-conflict-session'");
            var requested = transactional.request(teacher, "runtime-reschedule-request-key-001", requestInput);
            assertEquals("pending", requested.status());
            assertEquals(requested, transactional.request(teacher, "runtime-reschedule-request-key-001", requestInput));
            var requesterPage = transactional.list(teacher, new AcademicRescheduleDtos.ListQuery("pending", 1, 50));
            assertEquals(1, requesterPage.total());
            assertEquals("教师临时有事", requesterPage.items().get(0).reason());
            assertEquals("runtime-teacher-1", requesterPage.items().get(0).applicantId());
            seedOtherCampusRequest(jdbc, cipher, requestedStarts);
            assertEquals(1, transactional.list(campusReviewer,
                new AcademicRescheduleDtos.ListQuery("pending", 1, 50)).total());
            assertEquals(2, transactional.list(reviewer,
                new AcademicRescheduleDtos.ListQuery("pending", 1, 50)).total());
            assertEquals(original, jdbc.queryForObject("SELECT starts_at FROM core_session WHERE "
                + "public_id='runtime-reschedule-session'", Timestamp.class).toInstant());
            var reviewed = transactional.review(reviewer, requested.requestId(),
                "runtime-reschedule-review-key-001", new AcademicRescheduleDtos.ReviewInput("approve", null, 1));
            assertEquals("approved", reviewed.request().status());
            var reviewedPage = transactional.list(reviewer,
                new AcademicRescheduleDtos.ListQuery("approved", 1, 50));
            assertEquals(1, reviewedPage.total());
            assertEquals("runtime-admin", reviewedPage.items().get(0).reviewerId());
            assertEquals(requestedStarts.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString(),
                reviewedPage.items().get(0).requestedDate());
            assertEquals(timeRange(requestedStarts, 60), reviewedPage.items().get(0).requestedTime());
            assertEquals("一校区教室", reviewedPage.items().get(0).originalRoom());
            var reviewedRow = repository.lock(reviewer, requested.requestId());
            assertEquals("approved", reviewedRow.status());
            assertEquals(Long.valueOf(reviewer.id()), reviewedRow.reviewerId());
            assertEquals(requestedStarts, reviewed.session().startsAt());
            assertEquals(reviewed, transactional.review(reviewer, requested.requestId(),
                "runtime-reschedule-review-key-001", new AcademicRescheduleDtos.ReviewInput("approve", null, 1)));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM core_session_reschedule_event", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT version FROM core_session WHERE "
                + "public_id='runtime-reschedule-session'", Integer.class));
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void scheduleRelocateSubstituteAndRecordHonorScopeVersionsAndIdempotency() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_[a-z0-9_]+_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        seed(jdbc, cipher);
        AcademicClassRuntimeService service = transactionalService(jdbc, cipher, source);
        AcademicService academicService = transactionalAcademicService(jdbc, cipher, source);
        AccessRepository access = new AccessRepository(jdbc);
        Actor actor = access.resolve(ISSUER, "runtime-admin-subject");
        Actor denied = access.resolve(ISSUER, "runtime-denied-subject");
        assertEquals("all", actor.scope());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(c.id) FROM core_class c JOIN core_principal p "
            + "ON p.id=c.teacher_id WHERE c.public_id='runtime-class-1' AND c.active=1 AND c.deleted=0 AND 1=1",
            Integer.class));
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            Instant startsAt = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            ScheduleInput schedule = new ScheduleInput(1, List.of(new ScheduleSession(null,
                "runtime-lesson-1", startsAt)));
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, () -> service.schedule(denied,
                "runtime-class-1", "runtime-denied-schedule-0001", schedule)).code());
            Actor secondWriter = access.resolve(ISSUER, "runtime-admin-2-subject");
            ScheduleInput staleBeforeRace = new ScheduleInput(2, List.of(new ScheduleSession(null,
                "runtime-lesson-1", startsAt)));
            assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> service.schedule(actor,
                "runtime-class-1", "runtime-schedule-preflight-stale-01", staleBeforeRace)).code());
            ScheduleRace race = concurrentSchedule(service, actor, secondWriter, schedule);
            assertEquals(1, race.receipts().size(), race.toString());
            assertEquals(1, race.conflicts(), race.toString());
            ScheduleWinner winner = race.receipts().get(0);
            var scheduled = winner.receipt();
            assertEquals(scheduled, service.schedule(winner.actor(), "runtime-class-1", winner.key(), schedule));
            ScheduleInput alteredSchedule = new ScheduleInput(1, List.of(new ScheduleSession(null,
                "runtime-lesson-1", startsAt.plusSeconds(3600))));
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BusinessException.class, () -> service.schedule(
                winner.actor(), "runtime-class-1", winner.key(), alteredSchedule)).code());
            assertEquals(2, scheduled.version());
            assertEquals(1, scheduled.sessions().size());
            assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> service.schedule(actor,
                "runtime-class-1", "runtime-schedule-stale-key-01", schedule)).code());
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BusinessException.class, () -> academicService.updateClass(
                actor, "runtime-class-1", "runtime-class-capacity-0001",
                new ClassUpdateInput("合成班级", "一校区教室", "runtime-teacher-1", 40, 2))).code());
            assertEquals(2, jdbc.queryForObject("SELECT version FROM core_class WHERE public_id='runtime-class-1'",
                Integer.class));
            String sessionId = scheduled.sessions().get(0).id();
            seedHistoricalSession(jdbc);

            RelocateInput missingRoom = new RelocateInput(List.of(new RelocateClass("runtime-class-1", 2)),
                "runtime-campus-2", "不存在教室");
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BusinessException.class, () -> service.relocate(actor,
                "runtime-relocate-invalid-01", missingRoom)).code());
            RelocateInput smallRoom = new RelocateInput(List.of(new RelocateClass("runtime-class-1", 2)),
                "runtime-campus-2", "小容量教室");
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BusinessException.class, () -> service.relocate(actor,
                "runtime-relocate-capacity-01", smallRoom)).code());
            Actor campusActor = access.resolve(ISSUER, "runtime-campus-subject");
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, () -> service.relocate(campusActor,
                "runtime-relocate-scope-0001", new RelocateInput(List.of(new RelocateClass("runtime-class-1", 2)),
                    "runtime-campus-2", "二校区教室"))).code());
            assertEquals(1, jdbc.queryForObject("SELECT campus_id=(SELECT id FROM core_campus WHERE public_id='runtime-campus-1') "
                + "FROM core_class WHERE public_id='runtime-class-1'", Integer.class));
            RelocateInput relocate = new RelocateInput(List.of(new RelocateClass("runtime-class-1", 2)),
                "runtime-campus-2", "二校区教室");
            var relocated = service.relocate(actor, "runtime-relocate-key-000001", relocate);
            assertEquals(relocated, service.relocate(actor, "runtime-relocate-key-000001", relocate));
            assertEquals("runtime-campus-2", jdbc.queryForObject("SELECT cp.public_id FROM core_class c "
                + "JOIN core_campus cp ON cp.id=c.campus_id WHERE c.public_id='runtime-class-1'", String.class));
            assertEquals("runtime-campus-1", jdbc.queryForObject("SELECT cp.public_id FROM core_crm_order o "
                + "JOIN core_campus cp ON cp.id=o.campus_id WHERE o.public_id='runtime-order'", String.class));
            assertEquals("runtime-campus-2", jdbc.queryForObject("SELECT cp.public_id FROM core_crm_order o "
                + "JOIN core_campus cp ON cp.id=o.current_campus_id WHERE o.public_id='runtime-order'", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_class_location_history "
                + "WHERE class_id=(SELECT id FROM core_class WHERE public_id='runtime-class-1')", Integer.class));
            assertEquals("runtime-campus-2", jdbc.queryForObject("SELECT cp.public_id FROM core_session s "
                + "JOIN core_campus cp ON cp.id=s.campus_id WHERE s.public_id=?", String.class, sessionId));
            assertEquals("runtime-campus-1", jdbc.queryForObject("SELECT cp.public_id FROM core_session s "
                + "JOIN core_campus cp ON cp.id=s.campus_id WHERE s.public_id='runtime-session-history'", String.class));
            assertEquals("一校区教室", jdbc.queryForObject("SELECT room FROM core_session "
                + "WHERE public_id='runtime-session-history'", String.class));
            assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> service.relocate(actor,
                "runtime-relocate-stale-key-01", relocate)).code());

            SubstituteInput substitute = new SubstituteInput("runtime-teacher-2", "合成代课原因", 2);
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, () -> service.substitute(denied,
                sessionId, "runtime-denied-substitute-01", substitute)).code());
            var substituted = service.substitute(actor, sessionId, "runtime-substitute-key-0001", substitute);
            assertEquals(substituted, service.substitute(actor, sessionId, "runtime-substitute-key-0001", substitute));
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BusinessException.class, () -> service.substitute(
                actor, sessionId, "runtime-substitute-key-0001",
                new SubstituteInput("runtime-teacher-2", "不同原因", 2))).code());
            assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> service.substitute(actor,
                sessionId, "runtime-substitute-stale-key-01", substitute)).code());
            assertEquals(3, substituted.version());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_session_teacher_event WHERE session_id="
                + "(SELECT id FROM core_session WHERE public_id=?)", Integer.class, sessionId));

            jdbc.update("UPDATE core_session SET starts_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR,"
                + "ends_at=UTC_TIMESTAMP(3)-INTERVAL 30 MINUTE WHERE public_id=?", sessionId);
            RecordInput record = new RecordInput("合成教学记录内容", 3);
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, () -> service.record(denied,
                sessionId, "runtime-denied-record-00001", record)).code());
            var recorded = service.record(actor, sessionId, "runtime-record-key-000001", record);
            assertEquals(recorded, service.record(actor, sessionId, "runtime-record-key-000001", record));
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BusinessException.class, () -> service.record(actor,
                sessionId, "runtime-record-key-000001", new RecordInput("其他内容", 3))).code());
            assertEquals(4, recorded.version());
            String contentCipher = jdbc.queryForObject("SELECT content_cipher FROM core_session_teaching_record "
                + "WHERE public_id=?", String.class, recorded.id());
            assertEquals("合成教学记录内容", cipher.decrypt(contentCipher));
            assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> service.record(actor,
                sessionId, "runtime-record-stale-key-01", new RecordInput("过期记录", 3))).code());
        } finally {
            MDC.remove("traceId");
        }
    }

    private AcademicClassRuntimeService transactionalService(JdbcTemplate jdbc, PiiCipher cipher,
            DriverManagerDataSource source) {
        var service = new AcademicClassRuntimeService(new AccessRepository(jdbc),
            new AcademicClassRuntimeRepository(jdbc, cipher), new MutationRepository(jdbc, cipher),
            new ObjectMapper().findAndRegisterModules());
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        return (AcademicClassRuntimeService) factory.getProxy();
    }

    private AcademicService transactionalAcademicService(JdbcTemplate jdbc, PiiCipher cipher,
            DriverManagerDataSource source) {
        var service = new AcademicService(new AccessRepository(jdbc), new AcademicRepository(jdbc, cipher),
            new AcademicCatalogRepository(jdbc, cipher), new MutationRepository(jdbc, cipher),
            new ObjectMapper().findAndRegisterModules());
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        return (AcademicService) factory.getProxy();
    }

    private ScheduleRace concurrentSchedule(AcademicClassRuntimeService service, Actor firstActor,
            Actor secondActor, ScheduleInput input) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ScheduleOutcome> first = pool.submit(() -> scheduleAttempt(service, firstActor,
                "runtime-schedule-race-key-0001", input, ready, start));
            Future<ScheduleOutcome> second = pool.submit(() -> scheduleAttempt(service, secondActor,
                "runtime-schedule-race-key-0002", input, ready, start));
            assertTrue(ready.await(15, TimeUnit.SECONDS), "schedule writers did not reach the barrier");
            start.countDown();
            List<ScheduleOutcome> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            List<ScheduleWinner> receipts = outcomes.stream().filter(item -> item.receipt() != null)
                .map(item -> new ScheduleWinner(item.actor(), item.key(), item.receipt())).toList();
            long conflicts = outcomes.stream().filter(item -> ErrorCode.VERSION_CONFLICT.equals(item.error())).count();
            List<String> errors = outcomes.stream().filter(item -> item.error() != null)
                .map(item -> item.actor().publicId() + ":" + item.error() + ":" + item.message()).toList();
            return new ScheduleRace(receipts, (int) conflicts, errors);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "schedule writer pool did not stop");
        }
    }

    private ScheduleOutcome scheduleAttempt(AcademicClassRuntimeService service, Actor actor, String key,
            ScheduleInput input, CountDownLatch ready, CountDownLatch start) throws Exception {
        MDC.put("traceId", UUID.randomUUID().toString());
        ready.countDown();
        try {
            assertTrue(start.await(15, TimeUnit.SECONDS), "schedule start barrier timed out");
            return new ScheduleOutcome(actor, key, service.schedule(actor, "runtime-class-1", key, input), null, null);
        } catch (BusinessException exception) {
            return new ScheduleOutcome(actor, key, null, exception.code(), exception.getMessage());
        } finally {
            MDC.remove("traceId");
        }
    }

    private record ScheduleOutcome(Actor actor, String key,
            AcademicClassRuntimeDtos.ScheduleReceipt receipt, ErrorCode error, String message) { }
    private record ScheduleWinner(Actor actor, String key, AcademicClassRuntimeDtos.ScheduleReceipt receipt) { }
    private record ScheduleRace(List<ScheduleWinner> receipts, int conflicts, List<String> errors) { }

    private void seed(JdbcTemplate jdbc, PiiCipher cipher) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher,configuration_ready) VALUES"
            + "('runtime-campus-1',?,TRUE),('runtime-campus-2',?,TRUE)", cipher.encrypt("合成一校区"), cipher.encrypt("合成二校区"));
        jdbc.update("INSERT INTO core_campus_room(public_id,campus_id,name,capacity,sequence_no) "
            + "SELECT 'runtime-room-1',id,'一校区教室',30,1 FROM core_campus WHERE public_id='runtime-campus-1'");
        jdbc.update("INSERT INTO core_campus_room(public_id,campus_id,name,capacity,sequence_no) "
            + "SELECT 'runtime-room-2',id,'二校区教室',30,1 FROM core_campus WHERE public_id='runtime-campus-2'");
        for (String campus : List.of("runtime-campus-1", "runtime-campus-2")) {
            jdbc.update("INSERT INTO core_campus_academic_option(campus_id,kind,value,sequence_no) "
                + "SELECT id,'subject','数学',1 FROM core_campus WHERE public_id=?", campus);
            jdbc.update("INSERT INTO core_campus_academic_option(campus_id,kind,value,sequence_no) "
                + "SELECT id,'grade','小学一年级',1 FROM core_campus WHERE public_id=?", campus);
        }
        seedPrincipal(jdbc, "runtime-admin", "runtime-admin-subject", "all");
        seedPrincipal(jdbc, "runtime-admin-2", "runtime-admin-2-subject", "all");
        seedPrincipal(jdbc, "runtime-campus", "runtime-campus-subject", "campus");
        seedPrincipal(jdbc, "runtime-denied", "runtime-denied-subject", "all");
        seedPrincipal(jdbc, "runtime-teacher-1", "runtime-teacher-1-subject", "assigned");
        seedPrincipal(jdbc, "runtime-teacher-2", "runtime-teacher-2-subject", "assigned");
        long campus1 = campusId(jdbc, "runtime-campus-1");
        long campus2 = campusId(jdbc, "runtime-campus-2");
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) SELECT p.id,? FROM core_principal p "
            + "WHERE p.public_id='runtime-teacher-1'", campus1);
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) SELECT p.id,? FROM core_principal p "
            + "WHERE p.public_id='runtime-campus'", campus1);
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) SELECT p.id,? FROM core_principal p "
            + "WHERE p.public_id='runtime-teacher-2'", campus2);
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) SELECT p.id,? FROM core_principal p "
            + "WHERE p.public_id='runtime-teacher-1'", campus2);
        for (String permission : List.of("class.create", "class.relocate", "session.substitute", "session.record")) {
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) SELECT id,? "
                + "FROM core_principal WHERE public_id='runtime-admin'", permission);
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) SELECT id,? "
                + "FROM core_principal WHERE public_id='runtime-admin-2'", permission);
        }
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) "
            + "SELECT id,'class.relocate' FROM core_principal WHERE public_id='runtime-campus'");
        jdbc.update("INSERT INTO core_course(public_id,campus_id,name,course_type,grade,subject,price) "
            + "VALUES('runtime-course',?,'合成课程','onsite','小学一年级','数学',0.00)", campus1);
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) "
            + "SELECT 'runtime-lesson-1',id,1,'合成第一讲' FROM core_course WHERE public_id='runtime-course'");
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) "
            + "SELECT 'runtime-class-1',co.id,cp.id,t.id,'合成班级','一校区教室',20,60 FROM core_course co "
            + "JOIN core_campus cp ON cp.public_id='runtime-campus-1' JOIN core_principal t "
            + "ON t.public_id='runtime-teacher-1' WHERE co.public_id='runtime-course'");
        jdbc.update("INSERT INTO core_campus_room(public_id,campus_id,name,capacity,sequence_no) "
            + "SELECT 'runtime-room-small',id,'小容量教室',10,2 FROM core_campus WHERE public_id='runtime-campus-2'");
        seedOrder(jdbc, cipher, campus1);
    }

    private void seedPrincipal(JdbcTemplate jdbc, String publicId, String subject, String scope) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,?)",
            publicId, ISSUER, subject, scope);
    }

    private void seedHistoricalSession(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) "
            + "SELECT 'runtime-session-history',c.id,l.id,c.teacher_id,UTC_TIMESTAMP(3)-INTERVAL 2 DAY,"
            + "UTC_TIMESTAMP(3)-INTERVAL 2 DAY+INTERVAL 1 HOUR,'completed' FROM core_class c "
            + "JOIN core_lesson l ON l.course_id=c.course_id WHERE c.public_id='runtime-class-1' "
            + "AND l.public_id='runtime-lesson-1'");
    }

    private String timeRange(Instant startsAt, int durationMinutes) {
        var start = startsAt.atZone(java.time.ZoneId.of("Asia/Shanghai"));
        var end = start.plusMinutes(durationMinutes);
        return String.format(java.util.Locale.ROOT, "%02d:%02d–%02d:%02d", start.getHour(), start.getMinute(),
            end.getHour(), end.getMinute());
    }

    private void seedOtherCampusRequest(JdbcTemplate jdbc, PiiCipher cipher, Instant requestedStarts) {
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) "
            + "SELECT 'runtime-class-other-campus',co.id,cp.id,t.id,'合成班级二','二校区教室',20,60 FROM core_course co "
            + "JOIN core_campus cp ON cp.public_id='runtime-campus-2' JOIN core_principal t "
            + "ON t.public_id='runtime-teacher-2' WHERE co.public_id='runtime-course'");
        jdbc.update("INSERT INTO core_session(public_id,class_id,campus_id,room,lesson_id,teacher_id,starts_at,ends_at,status) "
            + "SELECT 'runtime-session-other-campus',c.id,cp.id,'二校区教室',l.id,c.teacher_id,?,?, 'scheduled' "
            + "FROM core_class c JOIN core_campus cp ON cp.public_id='runtime-campus-2' JOIN core_lesson l "
            + "ON l.public_id='runtime-lesson-1' WHERE c.public_id='runtime-class-other-campus'",
            Timestamp.from(requestedStarts.minusSeconds(86400)), Timestamp.from(requestedStarts.minusSeconds(82800)));
        jdbc.update("INSERT INTO core_session_reschedule_request(public_id,class_id,session_id,campus_id,applicant_id,"
            + "original_starts_at,original_ends_at,original_teacher_id,original_lesson_id,original_room,"
            + "original_session_version,original_class_version,requested_starts_at,reason_cipher) "
            + "SELECT 'runtime-other-campus-request',c.id,s.id,cp.id,p.id,s.starts_at,s.ends_at,s.teacher_id,s.lesson_id,"
            + "'二校区教室',s.version,c.version,?,? FROM core_class c JOIN core_campus cp "
            + "ON cp.public_id='runtime-campus-2' JOIN core_session s ON s.class_id=c.id JOIN core_principal p "
            + "ON p.public_id='runtime-teacher-2' WHERE c.public_id='runtime-class-other-campus'",
            Timestamp.from(requestedStarts), cipher.encrypt("合成校区外申请"));
    }

    private void seedRescheduleStudentConflict(JdbcTemplate jdbc, Instant requestedStarts) {
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) "
            + "SELECT 'runtime-class-conflict',co.id,cp.id,t.id,'合成冲突班级','二校区教室',20,60 FROM core_course co "
            + "JOIN core_campus cp ON cp.public_id='runtime-campus-2' JOIN core_principal t "
            + "ON t.public_id='runtime-teacher-2' WHERE co.public_id='runtime-course'");
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,grade) VALUES('runtime-student-conflict','sealed','小学一年级')");
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,status) "
            + "SELECT 'runtime-enrollment-source',s.id,c.id,'active' FROM core_student s JOIN core_class c "
            + "ON c.public_id='runtime-class-1' WHERE s.public_id='runtime-student-conflict'");
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,status) "
            + "SELECT 'runtime-enrollment-conflict',s.id,c.id,'active' FROM core_student s JOIN core_class c "
            + "ON c.public_id='runtime-class-conflict' WHERE s.public_id='runtime-student-conflict'");
        jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) "
            + "SELECT 'runtime-conflict-session',c.id,l.id,c.teacher_id,?,?, 'scheduled' FROM core_class c "
            + "JOIN core_lesson l ON l.course_id=c.course_id WHERE c.public_id='runtime-class-conflict' "
            + "AND l.public_id='runtime-lesson-1'", Timestamp.from(requestedStarts),
            Timestamp.from(requestedStarts.plusSeconds(3600)));
    }

    private void seedOrder(JdbcTemplate jdbc, PiiCipher cipher, long campusId) {
        long adminId = jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id='runtime-admin'", Long.class);
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) "
            + "VALUES('runtime-customer',?,?,?)", campusId, adminId, cipher.encrypt("合成客户"));
        long customerId = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id='runtime-customer'", Long.class);
        jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,campus_id,owner_id,title,subject,term,need_key) "
            + "VALUES('runtime-opportunity',?,?,?,'合成需求','数学','测试学期',?)", customerId, campusId,
            adminId, new byte[32]);
        long opportunityId = jdbc.queryForObject("SELECT id FROM core_opportunity WHERE public_id='runtime-opportunity'", Long.class);
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,campus_id,attributed_owner_id,"
            + "product_snapshot,total_amount) VALUES('runtime-order',?,?,?,?,?,100.00)", opportunityId, customerId,
            campusId, adminId, "合成班级订单");
        jdbc.update("INSERT INTO core_crm_order_class_terms(order_id,class_id,terms_snapshot) "
            + "SELECT o.id,c.id,'{}' FROM core_crm_order o JOIN core_class c ON c.public_id='runtime-class-1' "
            + "WHERE o.public_id='runtime-order'");
    }

    private long campusId(JdbcTemplate jdbc, String publicId) {
        return jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?", Long.class, publicId);
    }
}
