package cn.siyue.core;

import cn.siyue.core.CrmDtos.*;
import cn.siyue.core.OpportunityNeedDtos.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import static cn.siyue.core.NeedWorkflowFixture.CIPHER;
import static cn.siyue.core.NeedWorkflowFixture.MAPPER;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-workflow checks use only the explicitly opted-in, isolated needs test database. */
@EnabledIfEnvironmentVariable(named = "SIYUE_NEEDS_MYSQL_CONFIG", matches = ".+")
class NeedIdentityWorkflowMySqlTest {
    static JdbcTemplate jdbc;
    NeedWorkflowFixture f;
    @BeforeAll static void useIsolatedDatabase() throws Exception {
        NeedWorkflowMySqlTest.migrateIsolatedDatabase(); jdbc = NeedWorkflowMySqlTest.jdbc;
    }
    @BeforeEach void seed() throws Exception {
        MDC.put("traceId", UUID.randomUUID().toString()); f = new NeedWorkflowFixture(jdbc);
    }
    @AfterEach void clearTrace() { MDC.remove("traceId"); }

    @Test void classifiedTasksSeparateProjectsAndChildrenButFlagUnknownIdentity() throws Exception {
        Actor actor = taskActor(); var service = tasks();
        var firstInput = batch(actor, f.childA, identity(f.project));
        var first = service.createBatch(actor, f.key("task"), firstInput, service.previewBatch(actor, firstInput));
        assertEquals(identity(f.project), first.tasks().get(0).needIdentity());
        assertNull(first.tasks().get(0).resolvedNeedIdentity());
        var same = service.previewBatch(actor, firstInput);
        assertEquals(0, same.eligibleCount()); assertEquals(1, same.skippedCount());
        assertEquals("open_marketing_task", same.skippedTargets().get(0).reason());
        var otherInput = batch(actor, f.childA, identity(f.otherProject));
        assertEquals(1, service.previewBatch(actor, otherInput).eligibleCount());
        service.createBatch(actor, f.key("other-project"), otherInput, service.previewBatch(actor, otherInput));
        var otherChild = batch(actor, f.childB, identity(f.project));
        assertEquals(1, service.previewBatch(actor, otherChild).eligibleCount());
        service.createBatch(actor, f.key("other-child"), otherChild, service.previewBatch(actor, otherChild));
        var unknown = service.previewBatch(actor, batch(actor, f.childA, null));
        assertEquals(1, unknown.selectedCount()); assertEquals(0, unknown.eligibleCount());
        assertEquals(1, unknown.skippedCount()); assertEquals("identity_review_required", unknown.skippedTargets().get(0).reason());
        assertEquals(3, f.count("core_crm_task"));
    }

    @Test void unknownTaskNeedsExplicitResolutionAndOriginalCompletionReplaysAfterOpportunityChanges() throws Exception {
        Actor actor = taskActor(); var service = tasks(); var task = createTask(actor, null);
        var classified = service.previewBatch(actor, batch(actor, f.childA, identity(f.project)));
        assertEquals("identity_review_required", classified.skippedTargets().get(0).reason());
        var created = f.create(f.input(f.childA, f.project, f.worker.publicId()));
        var candidates = taskReads().opportunities(actor, task.id(), 1, 20);
        assertEquals(1, candidates.total()); var candidate = candidates.items().get(0);
        assertEquals(identity(f.project), candidate.needIdentity());
        assertEquals("needs_identity_confirmation", candidate.match()); assertEquals(1, candidate.version());
        assertCode(ErrorCode.INVALID_INPUT, () -> service.complete(actor, task.id(), f.key("unreviewed"),
            new TaskCompletion("qualified", "合成初筛", created.opportunityId(), 1)));
        assertEquals("pending", taskRepository().get(actor, task.id()).status());
        var resolution = new NeedResolution(f.department, f.project, candidate.version(), "核对学生和部门项目后确认");
        var input = new TaskCompletion("qualified", "合成初筛", created.opportunityId(), 1, resolution);
        String key = f.key("resolved"); var completed = service.complete(actor, task.id(), key, input);
        assertEquals("completed", completed.status()); assertEquals(2, completed.version());
        assertNull(completed.needIdentity()); assertEquals(identity(f.project), completed.resolvedNeedIdentity());
        opportunities().followup(f.worker, created.opportunityId(), f.key("later-followup"),
            new OpportunityFollowup("phone", "合成后续咨询", null, 1));
        assertEquals(completed, service.complete(actor, task.id(), key, input));
        assertEquals(2, eventCount("core_crm_task_event", "task_id", f.id("core_crm_task", task.id())));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity_need_event WHERE opportunity_id=? AND event_type='task_resolution'",
            Long.class, f.id("core_opportunity", created.opportunityId())));
        revoke(actor, "crm.task.write");
        assertCode(ErrorCode.FORBIDDEN, () -> service.complete(actor, task.id(), key, input));
    }

    @Test void knownTaskMatchesOnlyItsProjectAndRejectsExplicitReidentification() throws Exception {
        Actor actor = taskActor(); var service = tasks(); var task = createTask(actor, identity(f.project));
        var matching = f.create(f.input(f.childA, f.project, f.worker.publicId()));
        var other = f.create(f.input(f.childA, f.otherProject, f.worker.publicId()));
        var candidates = taskReads().opportunities(actor, task.id(), 1, 20);
        assertEquals(List.of(matching.opportunityId()), candidates.items().stream().map(CrmTaskReadDtos.Opportunity::id).toList());
        assertEquals("exact", candidates.items().get(0).match());
        assertCode(ErrorCode.INVALID_INPUT, () -> service.complete(actor, task.id(), f.key("wrong-project"),
            new TaskCompletion("qualified", "项目不符", other.opportunityId(), 1)));
        assertCode(ErrorCode.INVALID_INPUT, () -> service.complete(actor, task.id(), f.key("reidentify"),
            new TaskCompletion("qualified", "重复识别", matching.opportunityId(), 1,
                new NeedResolution(f.department, f.project, 1, "不应改变原任务身份"))));
        assertEquals("pending", taskRepository().get(actor, task.id()).status());
        var completed = service.complete(actor, task.id(), f.key("matching"),
            new TaskCompletion("qualified", "同项目确认", matching.opportunityId(), 1));
        assertEquals(identity(f.project), completed.needIdentity()); assertNull(completed.resolvedNeedIdentity());
    }

    @Test void identityResolutionChecksReviewedOpportunityVersionAndRollsBack() throws Exception {
        Actor actor = taskActor(); var task = createTask(actor, null);
        var created = f.create(f.input(f.childA, f.project, f.worker.publicId()));
        opportunities().followup(f.worker, created.opportunityId(), f.key("changed"),
            new OpportunityFollowup("other", "预览后新增咨询", null, 1));
        assertCode(ErrorCode.PREVIEW_STALE, () -> tasks().complete(actor, task.id(), f.key("stale-resolution"),
            new TaskCompletion("qualified", "旧版本识别", created.opportunityId(), 1,
                new NeedResolution(f.department, f.project, 1, "合成识别依据"))));
        assertEquals("pending", taskRepository().get(actor, task.id()).status());
        assertNull(taskRepository().get(actor, task.id()).resolvedNeedIdentity());
        assertEquals(1, eventCount("core_crm_task_event", "task_id", f.id("core_crm_task", task.id())));
    }

    @Test void historicalCreateLedgerKeepsItsOriginalJsonHashAndReplaysBeforeLegacyCreationGuard() throws Exception {
        String legacy = f.legacy(null); var service = opportunities();
        var input = new OpportunityInput(f.customer, null, null, null, "合成历史需求", "数学", "2026秋", "历史原请求");
        String oldBody = "{\"customerId\":\"" + f.customer + "\",\"sourceLeadId\":null,\"activityId\":null,\"studentId\":null,"
            + "\"title\":\"合成历史需求\",\"subject\":\"数学\",\"term\":\"2026秋\",\"detail\":\"历史原请求\"}";
        assertEquals(oldBody, MAPPER.writeValueAsString(input));
        var stored = new OpportunityRepository(jdbc, CIPHER).get(f.manager, legacy);
        var mutations = new MutationRepository(jdbc, CIPHER); String key = f.key("legacy-original");
        mutations.complete(f.manager, new MutationRepository.RetryWrite(key, "crm.opportunity.create",
            mutations.hash(oldBody), MAPPER.writeValueAsString(stored), legacy));
        assertEquals(stored, service.create(f.manager, key, input));
        assertCode(ErrorCode.INVALID_INPUT, () -> service.create(f.manager, f.key("legacy-new"), input));
        f.configuration.update(f.manager, f.department, f.key("inactive-department"), new OptionUpdate("合成停用部门", false, 1));
        assertEquals(stored, service.create(f.manager, key, input));
        assertCode(ErrorCode.INVALID_INPUT, () -> service.create(f.manager, f.key("inactive-legacy-new"), input));
        assertEquals(1, f.count("core_opportunity"));
    }

    @Test void classifiedReopenUsesDepartmentProjectIdentityAndRejectsOnlyExactActiveDuplicate() throws Exception {
        var first = f.create(f.input(f.childA, f.project, f.worker.publicId()));
        var other = f.create(f.input(f.childA, f.otherProject, f.worker.publicId())); var service = opportunities();
        service.advance(f.worker, first.opportunityId(), f.key("close"), new OpportunityStage("closed", "合成暂缓", null, 1));
        var reopened = service.advance(f.worker, first.opportunityId(), f.key("reopen"), new OpportunityStage("qualified", null, null, 2));
        assertEquals("qualified", reopened.stage()); assertEquals(3, reopened.version());
        assertEquals("qualified", f.needs.detail(f.worker, other.opportunityId()).opportunity().stage());
        service.advance(f.worker, first.opportunityId(), f.key("close-again"), new OpportunityStage("closed", "再次暂缓", null, 3));
        var replacement = f.create(f.input(f.childA, f.project, f.worker.publicId()));
        assertNotEquals(first.opportunityId(), replacement.opportunityId());
        assertCode(ErrorCode.ALREADY_EXISTS, () -> service.advance(f.worker, first.opportunityId(), f.key("duplicate-reopen"),
            new OpportunityStage("qualified", null, null, 4)));
        assertEquals("closed", f.needs.detail(f.worker, first.opportunityId()).opportunity().stage());
    }

    @Test void sourceWithIdentifiedChildRequiresExplicitSameChildAndRetryRequiresCurrentSourceGrant() throws Exception {
        String lead = lead(); var input = sourceInput(f.childA, lead);
        assertCode(ErrorCode.INVALID_INPUT, () -> f.needs.preview(f.manager, sourceInput(null, lead)));
        assertCode(ErrorCode.INVALID_INPUT, () -> f.needs.preview(f.manager, sourceInput(f.childB, lead)));
        assertEquals(0, f.count("core_opportunity"));
        var command = new Commit(input, f.needs.preview(f.manager, input), new Decision("create", null, null));
        String key = f.key("source-create"); var created = f.needs.commit(f.manager, key, command);
        assertEquals(lead, created.opportunity().sourceLeadId());
        assertEquals(f.id("core_student", f.childA), jdbc.queryForObject("SELECT student_id FROM core_opportunity WHERE public_id=?",
            Long.class, created.opportunityId()));
        assertEquals(created, f.needs.commit(f.manager, key, command));
        revoke(f.manager, "crm.lead.read");
        assertCode(ErrorCode.FORBIDDEN, () -> f.needs.commit(f.manager, key, command));
        assertEquals(1, f.count("core_opportunity"));
    }

    @Test void profileRetryPreservesSavedBusinessVersionButRedactsLinksAfterPermissionRevocation() throws Exception {
        String lead = lead();
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,'student.read')", f.manager.id());
        Actor actor = f.access.resolve("https://needs-fixture.example", f.manager.publicId());
        var input = sourceInput(f.childA, lead);
        var created = f.needs.commit(actor, f.key("source"), new Commit(input, f.needs.preview(actor, input), new Decision("create", null, null)));
        assertNull(created.opportunity().studentId(), "CRM 家庭关联不授予教务学生范围");
        enrollChildForAcademicRead();
        var change = new ProfileUpdate(input.profile(), 1, f.catalog.version(false)); String key = f.key("profile");
        var saved = f.needs.profile(actor, created.opportunityId(), key, change);
        assertEquals(lead, saved.opportunity().sourceLeadId()); assertEquals(f.childA, saved.opportunity().studentId());
        opportunities().followup(f.worker, created.opportunityId(), f.key("new-version"), new OpportunityFollowup("phone", "后来跟进", null, 2));
        revoke(actor, "crm.lead.read"); revoke(actor, "student.read");
        var replay = f.needs.profile(actor, created.opportunityId(), key, change);
        assertEquals(2, replay.opportunity().version()); assertEquals(saved.profile(), replay.profile());
        assertNull(replay.opportunity().sourceLeadId()); assertNull(replay.opportunity().studentId());
        assertEquals(3, f.needs.detail(f.worker, created.opportunityId()).opportunity().version());
    }

    @Test void concurrentReviewedCreatesProduceOneOpportunityAndOneStalePreview() throws Exception {
        Actor second = f.staff("second-manager", "all", List.of("crm.customer.read", "crm.opportunity.read",
            "crm.opportunity.write", "crm.opportunity.intake", "crm.opportunity.assign"));
        var input = f.input(f.childA, f.project, f.worker.publicId());
        var first = new Commit(input, f.needs.preview(f.manager, input), new Decision("create", null, null));
        var next = new Commit(input, f.needs.preview(second, input), new Decision("create", null, null));
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> concurrentCommit(f.manager, first, ready, start));
            var b = executor.submit(() -> concurrentCommit(second, next, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Receipt.class::isInstance).count());
            assertEquals(1, results.stream().filter(value -> value == ErrorCode.PREVIEW_STALE).count());
            assertEquals(1, f.count("core_opportunity"));
            long opportunity = jdbc.queryForObject("SELECT id FROM core_opportunity WHERE customer_id=?", Long.class, f.customerRow);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity_need_event WHERE opportunity_id=? AND event_type='consultation'", Long.class, opportunity));
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private Object concurrentCommit(Actor actor, Commit input, CountDownLatch ready, CountDownLatch start) throws Exception {
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
            return f.needs.commit(actor, f.key("concurrent"), input);
        } catch (BusinessException error) { return error.code(); }
        finally { MDC.remove("traceId"); }
    }
    private Actor taskActor() {
        return f.staff("task-operator", "all", List.of("crm.customer.read", "crm.task.read", "crm.task.write", "crm.task.assign", "crm.opportunity.read"));
    }
    private Identity identity(String project) { return new Identity(f.department, project); }
    private TaskBatchInput batch(Actor actor, String student, Identity identity) {
        return new TaskBatchInput("合成营销任务", "marketing", null, null, null, null, null, List.of(actor.publicId()),
            "even", null, Instant.parse("2099-02-03T06:00:00Z"), "独立身份回归", List.of(new TaskTargetInput(f.customer, student, "数学", "2026秋", List.of(), identity)));
    }
    private CrmTask createTask(Actor actor, Identity identity) throws Exception {
        var input = batch(actor, f.childA, identity); var service = tasks();
        return service.createBatch(actor, f.key("task"), input, service.previewBatch(actor, input)).tasks().get(0);
    }
    private CrmTaskRepository taskRepository() { return new CrmTaskRepository(jdbc, CIPHER); }
    private CrmTaskService tasks() {
        return f.proxy(new CrmTaskService(f.access, taskRepository(), new MutationRepository(jdbc, CIPHER), MAPPER));
    }
    private CrmTaskReadService taskReads() {
        return f.proxy(new CrmTaskReadService(new CrmTaskAssignmentRepository(jdbc), new CrmTaskReadRepository(jdbc, CIPHER), taskRepository()));
    }
    private OpportunityService opportunities() {
        return f.proxy(new OpportunityService(f.access, new OpportunityRepository(jdbc, CIPHER), new MutationRepository(jdbc, CIPHER), MAPPER));
    }
    private long eventCount(String table, String column, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Long.class, id);
    }
    private void revoke(Actor actor, String permission) {
        jdbc.update("UPDATE core_principal_permission SET deleted=1 WHERE principal_id=? AND permission_code=?", actor.id(), permission);
    }
    private String lead() {
        String id = f.prefix + "lead";
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,student_id,owner_id,source,subject,term,title,status) VALUES(?,?,?,?,?,'front_desk','数学','2026秋','合成已确认孩子','qualified')",
            id, f.customerRow, f.campusRow, f.id("core_student", f.childA), f.manager.id());
        return id;
    }
    private void enrollChildForAcademicRead() {
        String course = f.prefix + "scope-course", group = f.prefix + "scope-class";
        jdbc.update("INSERT INTO core_course(public_id,campus_id,name,course_type,grade,subject,price) VALUES(?,?,'合成在读课程','onsite','三年级','数学',0)",
            course, f.campusRow);
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) VALUES(?,?,?,?,'合成在读班级','合成教室',10,60)",
            group, f.id("core_course", course), f.campusRow, f.worker.id());
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,status) VALUES(?,?,?,'active')",
            f.prefix + "scope-enrollment", f.id("core_student", f.childA), f.id("core_class", group));
    }
    private Input sourceInput(String student, String lead) {
        var input = f.input(student, f.project, f.worker.publicId());
        return new Input(input.customerId(), input.studentId(), lead, null, input.title(), input.subject(), input.term(),
            input.identity(), input.profile(), input.detail(), input.ownerId());
    }
    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).code());
    }
}
