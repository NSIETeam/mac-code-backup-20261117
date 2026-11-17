package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes every workbench queue count and page against a fresh, isolated MySQL schema. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/siyue_[A-Za-z0-9_]+_test(?:\\?.*)?")
class CrmWorkbenchQueueMySqlTest {
    private static final String ISSUER = "https://workbench-queue-test.invalid";
    private static final String SUBJECT = "synthetic-workbench-reader";
    private static final Set<String> GRANTS = Set.of("crm.lead.read", "lead.assign", "lead.read", "lead.follow",
        "crm.form.read", "crm.form.submission.read", "crm.form.submission.review", "crm.lead.reclaim.read",
        "order.read", "crm.order.read", "crm.attribution.correct.request", "crm.attribution.correct.review",
        "crm.task.read", "crm.opportunity.read", "crm.ticket.read", "report.read");
    private record LeadSeed(String publicId, long customerId, Long ownerId, Instant assignedAt, Instant dueAt,
            String decisionStatus) { }
    private record ResourceSeed(Actor actor, Instant asOf, Instant noon, long campus, long customer, long course) { }
    private record SubmissionSeed(long form, long revision, String publicId, Instant submittedAt, int hash) { }
    private record BookingSeed(long form, long revision, long submission, long slot, long classId, long session,
            long campus, long publisher, String publicId, Instant bookedAt) { }

    @Test
    void allSeventeenQueueCountsAndPagesExecuteWithMatchingPredicates() {
        String url = required("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/siyue_[A-Za-z0-9_]+_test"));
        var source = new DriverManagerDataSource(url, required("SIYUE_TEST_MYSQL_USER"),
            required("SIYUE_TEST_MYSQL_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertTrue(uri.getPath().matches("/siyue_v103_[a-f0-9]{10}_test"),
            "the suite must only clean its own named isolated schema");
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        String trace = UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", trace);
        try {
            jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) "
                + "VALUES('synthetic-workbench-reader',? ,? ,'all')", ISSUER, SUBJECT);
            long principal = jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",
                Long.class, SUBJECT);
            for (String grant : GRANTS) {
                jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)",
                    principal, grant);
            }
            Actor actor = new AccessRepository(jdbc).resolve(ISSUER, SUBJECT);
            Instant asOf = Instant.now();
            Seed seed = seed(jdbc, actor, asOf);
            CrmWorkbenchService service = new CrmWorkbenchService(new CrmWorkbenchQueueRepository(jdbc));
            var workbench = service.get(actor, "scope", java.time.LocalDate.now());
            assertEquals(17, workbench.cards().size());
            for (var card : workbench.cards()) {
                var query = new CrmDtos.CrmWorkbenchQueueQuery("team", card.resource(), card.queue(),
                    workbench.asOf(), 1, 25);
                var page = service.queue(actor, query);
                assertEquals(card.count(), page.total(), card.key());
                assertEquals(seed.expectedCounts().get(card.key()), page.total(), card.key() + " rows="
                    + page.items().stream().map(CrmDtos.CrmWorkbenchQueueItem::id).toList());
                assertEquals(page.total(), page.items().size(), card.key());
            }
            for (int index = 0; index < seed.leadTodayIds().size(); index++) {
                var itemPage = service.queue(actor, new CrmDtos.CrmWorkbenchQueueQuery("team", "leads", "today",
                    workbench.asOf(), index + 1, 1));
                assertEquals(List.of(seed.leadTodayIds().get(index)), itemPage.items().stream()
                    .map(CrmDtos.CrmWorkbenchQueueItem::id).toList(), "stable order at page " + (index + 1));
            }
            Actor owner = insertOtherActor(jdbc);
            assertEquals(0, service.queue(owner, new CrmDtos.CrmWorkbenchQueueQuery("mine", "leads", "today",
                workbench.asOf(), 1, 25)).total(), "team-visible leads owned by another actor are excluded from mine");
            Actor denied = new Actor(actor.id(), actor.publicId(), "all", Set.of(), Set.of("crm.lead.read"));
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, () -> service.queue(denied,
                new CrmDtos.CrmWorkbenchQueueQuery("mine", "formSubmissions", "pending", workbench.asOf(), 1, 25)))
                .code());
        } finally {
            MDC.remove("traceId");
        }
    }

    private Seed seed(JdbcTemplate jdbc, Actor actor, Instant asOf) {
        LocalDate today = asOf.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        Instant noon = today.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        long campus = insertCampus(jdbc);
        long customer = insertCustomer(jdbc, actor.id());
        insertLead(jdbc, new LeadSeed("wb-lead-first", customer, actor.id(), asOf.minusSeconds(60), null, null));
        insertLead(jdbc, new LeadSeed("wb-lead-today-a", customer, actor.id(), asOf.minusSeconds(6 * 3600), noon, null));
        insertLead(jdbc, new LeadSeed("wb-lead-today-b", customer, actor.id(), asOf.minusSeconds(6 * 3600), noon, null));
        insertFollow(jdbc, "wb-lead-today-a", actor.id(), asOf.minusSeconds(6 * 3600));
        insertFollow(jdbc, "wb-lead-today-b", actor.id(), asOf.minusSeconds(6 * 3600));
        insertLead(jdbc, new LeadSeed("wb-lead-overdue", customer, actor.id(), asOf.minusSeconds(2 * 86400),
            asOf.minusSeconds(3600), null));
        insertFollow(jdbc, "wb-lead-overdue", actor.id(), asOf.minusSeconds(2 * 86400));
        insertLead(jdbc, new LeadSeed("wb-lead-future", customer, actor.id(), asOf.minusSeconds(3600), noon.plusSeconds(86400), null));
        insertFollow(jdbc, "wb-lead-future", actor.id(), asOf.minusSeconds(3600));
        insertLead(jdbc, new LeadSeed("wb-lead-unassigned", customer, null, null, null, null));
        insertLead(jdbc, new LeadSeed("wb-lead-decision", customer, actor.id(), asOf.minusSeconds(60), null, "pending"));
        insertFollow(jdbc, "wb-lead-decision", actor.id(), asOf.minusSeconds(60));
        insertLead(jdbc, new LeadSeed("wb-lead-recycle", customer, actor.id(), asOf.minusSeconds(20 * 86400), null, null));
        insertFollow(jdbc, "wb-lead-recycle", actor.id(), asOf.minusSeconds(20 * 86400));
        boolean overdueIsToday = asOf.minusSeconds(3600).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(today);
        boolean noonHasPassed = !noon.isAfter(asOf);
        jdbc.update("INSERT INTO core_course(public_id,name,course_type,grade,subject,price) "
            + "VALUES('wb-course','合成课程','onsite','合成','数学',0.00)");
        long course = jdbc.queryForObject("SELECT id FROM core_course WHERE public_id='wb-course'", Long.class);
        seedResources(jdbc, new ResourceSeed(actor, asOf, noon, campus, customer, course));
        return new Seed(java.util.Map.ofEntries(java.util.Map.entry("leads:firstContact", 1L),
            java.util.Map.entry("leads:today", 2L + (overdueIsToday ? 1 : 0)),
            java.util.Map.entry("leads:overdue", 1L + (noonHasPassed ? 2 : 0)),
            java.util.Map.entry("leads:future", 1L), java.util.Map.entry("leads:unassigned", 1L),
            java.util.Map.entry("leads:decision", 1L), java.util.Map.entry("leads:recycleSoon", 1L),
            java.util.Map.entry("trialBookings:today", 1L), java.util.Map.entry("trialBookings:future", 1L),
            java.util.Map.entry("formSubmissions:pending", 2L), java.util.Map.entry("leadReclaimRequests:pending", 1L),
            java.util.Map.entry("attributionRequests:pending", 1L), java.util.Map.entry("crmTasks:today", 1L),
            java.util.Map.entry("opportunities:today", 1L + (overdueIsToday ? 1 : 0)),
            java.util.Map.entry("opportunities:overdue", 1L + (noonHasPassed ? 1 : 0)),
            java.util.Map.entry("opportunities:future", 1L), java.util.Map.entry("serviceTickets:overdue", 1L)),
            leadTodayIds(asOf, noon));
    }

    @Test
    void todayPaginationExpectationPreservesChronologyBeforeAndAfterNoon() {
        Instant noon = Instant.parse("2026-10-17T04:00:00Z");
        assertEquals(List.of("wb-lead-overdue", "wb-lead-today-a", "wb-lead-today-b"),
            leadTodayIds(noon.minusSeconds(3600), noon));
        assertEquals(List.of("wb-lead-today-a", "wb-lead-today-b", "wb-lead-overdue"),
            leadTodayIds(noon.plusSeconds(7200), noon));
        assertEquals(List.of("wb-lead-today-a", "wb-lead-today-b", "wb-lead-overdue"),
            leadTodayIds(noon.plusSeconds(3600), noon));
        assertEquals(List.of("wb-lead-today-a", "wb-lead-today-b"),
            leadTodayIds(noon.minusSeconds(12 * 3600), noon));
    }

    private static List<String> leadTodayIds(Instant asOf, Instant noon) {
        Instant overdueAt = asOf.minusSeconds(3600);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<String> expected = new java.util.ArrayList<>(List.of("wb-lead-today-a", "wb-lead-today-b"));
        if (overdueAt.atZone(zone).toLocalDate().equals(noon.atZone(zone).toLocalDate())) {
            // Equal due times use insertion id: a/b were inserted before the overdue lead.
            expected.add(overdueAt.isBefore(noon) ? 0 : expected.size(), "wb-lead-overdue");
        }
        return List.copyOf(expected);
    }

    private void seedResources(JdbcTemplate jdbc, ResourceSeed data) {
        Actor actor = data.actor();
        Instant asOf = data.asOf();
        Instant noon = data.noon();
        long campus = data.campus();
        long customer = data.customer();
        long course = data.course();
        long principal = actor.id();
        long lessonToday = insertLesson(jdbc, course, "wb-lesson-today");
        long lessonFuture = insertLesson(jdbc, course, "wb-lesson-future");
        long classroom = insertClass(jdbc, course, campus, principal);
        long sessionToday = insertSession(jdbc, classroom, lessonToday, principal, noon);
        long sessionFuture = insertSession(jdbc, classroom, lessonFuture, principal, noon.plusSeconds(86400));
        long form = insertForm(jdbc, actor, campus, asOf);
        long revision = jdbc.queryForObject("SELECT id FROM core_crm_form_revision WHERE public_id='wb-revision'", Long.class);
        long todaySubmission = insertSubmission(jdbc, new SubmissionSeed(form, revision,
            "wb-submission-today", asOf, 1));
        long futureSubmission = insertSubmission(jdbc, new SubmissionSeed(form, revision,
            "wb-submission-future", asOf, 2));
        insertReviewedSubmission(jdbc, form, revision, actor.id(), asOf);
        long slotToday = insertSlot(jdbc, form, classroom, sessionToday, "today");
        long slotFuture = insertSlot(jdbc, form, classroom, sessionFuture, "future");
        insertBooking(jdbc, new BookingSeed(form, revision, todaySubmission, slotToday, classroom, sessionToday,
            campus, principal, "wb-booking-today", asOf));
        insertBooking(jdbc, new BookingSeed(form, revision, futureSubmission, slotFuture, classroom, sessionFuture,
            campus, principal, "wb-booking-future", asOf));
        long todayOpp = insertOpportunity(jdbc, actor, customer, "wb-opportunity-today", noon);
        insertOpportunity(jdbc, actor, customer, "wb-opportunity-overdue", asOf.minusSeconds(3600));
        insertOpportunity(jdbc, actor, customer, "wb-opportunity-future", noon.plusSeconds(86400));
        insertWonOpportunity(jdbc, actor, customer, noon);
        long order = insertOrder(jdbc, actor, customer, todayOpp);
        insertAttribution(jdbc, actor, order);
        insertRejectedAttribution(jdbc, actor, order, asOf);
        insertTask(jdbc, actor, customer, campus, noon);
        insertCompletedTask(jdbc, actor, customer, campus, noon);
        insertTicket(jdbc, actor, customer, campus, asOf);
        insertClosedTicket(jdbc, actor, customer, campus, asOf);
        long lead = jdbc.queryForObject("SELECT id FROM core_crm_lead WHERE public_id='wb-lead-first'", Long.class);
        insertReclaim(jdbc, actor, lead);
        insertApprovedReclaim(jdbc, actor, lead, asOf);
    }

    private long insertCampus(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('wb-campus','synthetic-campus')");
        return jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id='wb-campus'", Long.class);
    }

    private Actor insertOtherActor(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) "
            + "VALUES('synthetic-workbench-other',?,'synthetic-workbench-other','own')", ISSUER);
        long id = jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?", Long.class,
            "synthetic-workbench-other");
        return new Actor(id, "synthetic-workbench-other", "own", Set.of(), Set.of("lead.read", "crm.lead.read"));
    }

    private long insertCustomer(JdbcTemplate jdbc, long owner) {
        jdbc.update("INSERT INTO core_customer(public_id,owner_id,display_name_cipher) VALUES('wb-customer',?,'synthetic')", owner);
        return jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id='wb-customer'", Long.class);
    }

    private long insertLesson(JdbcTemplate jdbc, long course, String publicId) {
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES(?,?,?,?)",
            publicId, course, publicId.endsWith("today") ? 1 : 2, "合成课时");
        return jdbc.queryForObject("SELECT id FROM core_lesson WHERE public_id=?", Long.class, publicId);
    }

    private long insertClass(JdbcTemplate jdbc, long course, long campus, long teacher) {
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) "
            + "VALUES('wb-class',?,?,?,'合成班级','合成教室',10,60)", course, campus, teacher);
        return jdbc.queryForObject("SELECT id FROM core_class WHERE public_id='wb-class'", Long.class);
    }

    private long insertSession(JdbcTemplate jdbc, long classId, long lesson, long teacher, Instant starts) {
        jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) "
            + "VALUES(?,?,?,?,?,?,'scheduled')", "wb-session-" + lesson, classId, lesson, teacher,
            Timestamp.from(starts), Timestamp.from(starts.plusSeconds(3600)));
        return jdbc.queryForObject("SELECT id FROM core_session WHERE class_id=? AND lesson_id=?", Long.class,
            classId, lesson);
    }

    private long insertForm(JdbcTemplate jdbc, Actor actor, long campus, Instant published) {
        jdbc.update("INSERT INTO core_crm_form(public_id,campus_id,owner_id,title,form_kind,collect_lead,status,"
            + "current_revision,fields_json) VALUES('wb-form',?,?,'合成表单','lead',TRUE,'published',1,'[]')",
            campus, actor.id());
        long form = jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id='wb-form'", Long.class);
        jdbc.update("INSERT INTO core_crm_form_revision(public_id,form_id,revision_no,title,form_kind,collect_lead,"
            + "fields_json,published_by,published_at) VALUES('wb-revision',?,1,'合成表单','lead',TRUE,'[]',?,?)",
            form, actor.id(), Timestamp.from(published));
        jdbc.update("INSERT INTO core_form_trial_config(public_id,form_id,revision_no,enabled,grade_field_id,"
            + "subject_field_id,name_field_id,phone_field_id) VALUES('wb-trial-config',?,1,TRUE,'grade','subject','name','phone')",
            form);
        return form;
    }

    private long insertSubmission(JdbcTemplate jdbc, SubmissionSeed seed) {
        byte[] keyHash = new byte[32]; keyHash[0] = (byte) seed.hash();
        byte[] payloadHash = new byte[32]; payloadHash[0] = (byte) (seed.hash() + 10);
        jdbc.update("INSERT INTO core_crm_form_submission(public_id,form_id,revision_id,request_key_hash,payload_hash,"
            + "answers_cipher,status,submitted_at) VALUES(?,?,?,?,?,'synthetic','pending',?)", seed.publicId(),
            seed.form(), seed.revision(), keyHash, payloadHash, Timestamp.from(seed.submittedAt()));
        return jdbc.queryForObject("SELECT id FROM core_crm_form_submission WHERE public_id=?", Long.class, seed.publicId());
    }

    private void insertReviewedSubmission(JdbcTemplate jdbc, long form, long revision, long reviewer, Instant at) {
        byte[] keyHash = new byte[32]; keyHash[0] = 3;
        byte[] payloadHash = new byte[32]; payloadHash[0] = 13;
        jdbc.update("INSERT INTO core_crm_form_submission(public_id,form_id,revision_id,request_key_hash,payload_hash,"
            + "answers_cipher,status,submitted_at,reviewer_id,reviewed_at) "
            + "VALUES('wb-submission-rejected',?,?,?,?,?,'rejected',?,?,?)", form, revision, keyHash, payloadHash,
            "synthetic", Timestamp.from(at.minusSeconds(60)), reviewer, Timestamp.from(at));
    }

    private long insertSlot(JdbcTemplate jdbc, long form, long classId, long session, String key) {
        long config = jdbc.queryForObject("SELECT id FROM core_form_trial_config WHERE public_id='wb-trial-config'", Long.class);
        jdbc.update("INSERT INTO core_form_trial_slot(public_id,config_id,slot_key,class_id,session_id,capacity,sequence_no) "
            + "VALUES(?,?,?,?,?,10,?)", "wb-slot-" + key, config, key, classId, session, "today".equals(key) ? 1 : 2);
        return jdbc.queryForObject("SELECT id FROM core_form_trial_slot WHERE public_id=?", Long.class, "wb-slot-" + key);
    }

    private void insertBooking(JdbcTemplate jdbc, BookingSeed seed) {
        jdbc.update("INSERT INTO core_trial_booking(public_id,form_id,revision_id,submission_id,slot_id,class_id,session_id,"
            + "campus_id,name_cipher,phone_cipher,grade,subject,slot_snapshot_cipher,booked_at,publisher_id) "
            + "VALUES(?,?,?,?,?,?,?,?, 'synthetic','synthetic','合成','数学','synthetic',?,?)", seed.publicId(),
            seed.form(), seed.revision(), seed.submission(), seed.slot(), seed.classId(), seed.session(), seed.campus(),
            Timestamp.from(seed.bookedAt()), seed.publisher());
    }

    private long insertOpportunity(JdbcTemplate jdbc, Actor actor, long customer, String publicId, Instant due) {
        jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,owner_id,title,subject,term,need_key,next_follow_at) "
            + "VALUES(?,?,?,'合成商机','数学','2026-10',REPEAT(CHAR(1),32),?)", publicId, customer, actor.id(),
            Timestamp.from(due));
        return jdbc.queryForObject("SELECT id FROM core_opportunity WHERE public_id=?", Long.class, publicId);
    }

    private long insertOrder(JdbcTemplate jdbc, Actor actor, long customer, long opportunity) {
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,attributed_owner_id,product_snapshot,"
            + "total_amount,status) VALUES('wb-order',?,? ,?,'合成项目',1.00,'pending')", opportunity, customer, actor.id());
        return jdbc.queryForObject("SELECT id FROM core_crm_order WHERE public_id='wb-order'", Long.class);
    }

    private void insertAttribution(JdbcTemplate jdbc, Actor actor, long order) {
        jdbc.update("INSERT INTO core_crm_attribution_request(public_id,order_id,applicant_id,from_owner_id,proposed_owner_id,"
            + "reason_cipher,status) VALUES('wb-attribution',?,?,?,?,'synthetic','pending_approval')", order, actor.id(),
            actor.id(), actor.id());
    }

    private void insertRejectedAttribution(JdbcTemplate jdbc, Actor actor, long order, Instant at) {
        jdbc.update("INSERT INTO core_crm_attribution_request(public_id,order_id,applicant_id,from_owner_id,"
            + "proposed_owner_id,reason_cipher,status,reviewer_id,reviewed_at) "
            + "VALUES('wb-attribution-rejected',?,?,?,?,?,'rejected',?,?)", order, actor.id(), actor.id(), actor.id(),
            "synthetic", actor.id(), Timestamp.from(at));
    }

    private void insertTask(JdbcTemplate jdbc, Actor actor, long customer, long campus, Instant due) {
        jdbc.update("INSERT INTO core_crm_task_batch(public_id,created_by,title,task_type,campus_id,due_at) "
            + "VALUES('wb-task-batch',?,'合成任务','marketing',?,?)", actor.id(), campus, Timestamp.from(due));
        long batch = jdbc.queryForObject("SELECT id FROM core_crm_task_batch WHERE public_id='wb-task-batch'", Long.class);
        jdbc.update("INSERT INTO core_crm_task(public_id,batch_id,customer_id,campus_id,owner_id,task_type,title,due_at) "
            + "VALUES('wb-task',?,?,?,?,'marketing','合成任务',?)", batch, customer, campus, actor.id(), Timestamp.from(due));
    }

    private void insertCompletedTask(JdbcTemplate jdbc, Actor actor, long customer, long campus, Instant due) {
        jdbc.update("INSERT INTO core_crm_task_batch(public_id,created_by,title,task_type,campus_id,due_at) "
            + "VALUES('wb-completed-batch',?,'合成已完成任务','marketing',?,?)", actor.id(), campus,
            Timestamp.from(due));
        long batch = jdbc.queryForObject("SELECT id FROM core_crm_task_batch WHERE public_id='wb-completed-batch'",
            Long.class);
        jdbc.update("INSERT INTO core_crm_task(public_id,batch_id,customer_id,campus_id,owner_id,task_type,title,"
            + "status,due_at,result,completed_at) VALUES('wb-completed-task',?,?,?,?,'marketing','合成已完成任务',"
            + "'completed',?,'completed',?)", batch, customer, campus, actor.id(), Timestamp.from(due),
            Timestamp.from(due.plusSeconds(3600)));
    }

    private void insertTicket(JdbcTemplate jdbc, Actor actor, long customer, long campus, Instant now) {
        long type = jdbc.queryForObject("SELECT id FROM core_crm_service_ticket_type WHERE type_code='question' "
            + "AND campus_id IS NULL AND deleted=0", Long.class);
        jdbc.update("INSERT INTO core_crm_service_ticket(public_id,customer_id,campus_id,ticket_type_id,owner_id,created_by,"
            + "title_cipher,description_cipher,status,due_at) VALUES('wb-ticket',?,?,?,?,?,'synthetic','synthetic','open',?)",
            customer, campus, type, actor.id(), actor.id(), Timestamp.from(now.minusSeconds(1)));
    }

    private void insertClosedTicket(JdbcTemplate jdbc, Actor actor, long customer, long campus, Instant now) {
        long type = jdbc.queryForObject("SELECT id FROM core_crm_service_ticket_type WHERE type_code='question' "
            + "AND campus_id IS NULL AND deleted=0", Long.class);
        jdbc.update("INSERT INTO core_crm_service_ticket(public_id,customer_id,campus_id,ticket_type_id,owner_id,created_by,"
            + "title_cipher,description_cipher,status,due_at,completed_at) "
            + "VALUES('wb-ticket-closed',?,?,?,?,?,'synthetic','synthetic','closed',?,?)", customer, campus, type,
            actor.id(), actor.id(), Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now));
    }

    private void insertWonOpportunity(JdbcTemplate jdbc, Actor actor, long customer, Instant due) {
        jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,owner_id,title,subject,term,need_key,stage,"
            + "next_follow_at) VALUES('wb-opportunity-won',?,?,'已成交合成商机','数学','2026-10',REPEAT(CHAR(2),32),"
            + "'won',?)", customer, actor.id(), Timestamp.from(due));
    }

    private void insertApprovedReclaim(JdbcTemplate jdbc, Actor actor, long lead, Instant at) {
        jdbc.update("INSERT INTO core_crm_lead_reclaim_request(public_id,lead_id,lead_version,applicant_id,"
            + "applicant_name_cipher,reason_cipher,status,reviewed_by,reviewed_at,payload_cipher) "
            + "VALUES('wb-reclaim-approved',?,1,?,'synthetic','synthetic','approved',?,?,'{}')", lead, actor.id(),
            actor.id(), Timestamp.from(at));
    }

    private void insertReclaim(JdbcTemplate jdbc, Actor actor, long lead) {
        jdbc.update("INSERT INTO core_crm_lead_reclaim_request(public_id,lead_id,lead_version,applicant_id,"
            + "applicant_name_cipher,reason_cipher,status,payload_cipher) VALUES('wb-reclaim',?,1,?,'synthetic','synthetic',"
            + "'pending','{}')", lead, actor.id());
    }

    private void insertLead(JdbcTemplate jdbc, LeadSeed seed) {
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,owner_id,source,title,status,legacy_stage,"
            + "legacy_status,assigned_at,next_follow_at,decision_status,decision_by_id) "
            + "VALUES(?,?,?,'other',?,'new','新线索','new',?,?,?,?)", seed.publicId(), seed.customerId(),
            seed.ownerId(), seed.publicId(), seed.assignedAt() == null ? null : Timestamp.from(seed.assignedAt()),
            seed.dueAt() == null ? null : Timestamp.from(seed.dueAt()),
            seed.decisionStatus() == null ? "public" : seed.decisionStatus(),
            "pending".equals(seed.decisionStatus()) ? seed.ownerId() : null);
    }

    private void insertFollow(JdbcTemplate jdbc, String lead, long actor, Instant at) {
        long leadId = jdbc.queryForObject("SELECT id FROM core_crm_lead WHERE public_id=?", Long.class, lead);
        jdbc.update("INSERT INTO core_crm_lead_followup(public_id,lead_id,actor_id,channel,summary_cipher,occurred_at,"
            + "from_status,to_status) VALUES(?,?,?,'other','synthetic',?,'new','new')", lead + "-follow", leadId,
            actor, Timestamp.from(at));
    }

    private record Seed(java.util.Map<String, Long> expectedCounts, List<String> leadTodayIds) { }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException(name + " is required"); }
        return value;
    }
}
