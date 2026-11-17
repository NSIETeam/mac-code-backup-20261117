package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import cn.siyue.core.LeadBatchAssignmentDtos.AssignedLead;
import cn.siyue.core.LeadBatchAssignmentDtos.Input;
import cn.siyue.core.LeadBatchAssignmentDtos.Quota;
import cn.siyue.core.LeadBatchAssignmentDtos.Result;
import cn.siyue.core.LeadBatchAssignmentDtos.SkippedLead;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.oauth2.jwt.Jwt;

/** Real MySQL acceptance for explicit lead batch assignment and concurrent updates. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:53306/siyue_focused_[a-f0-9]{10}_test(?:\\?.*)?")
class LeadBatchAssignmentMySqlTest {
    private static final String ISSUER = "https://lead-batch-test.invalid";
    private static final String ADMIN_ONE = "batch-admin-one";
    private static final String ADMIN_TWO = "batch-admin-two";
    private static final String OWNER_ONE = "batch-owner-one";
    private static final String OWNER_TWO = "batch-owner-two";
    private static final String CONVERTED_LEAD = "batch-converted-lead";

    @Test void strategiesScopeHistoryIdempotencyAndConcurrentWritesAreAtomic() throws Exception {
        String url = required("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(53306, uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_focused_[a-f0-9]{10}_test"));
        var source = new DriverManagerDataSource(url, required("SIYUE_TEST_MYSQL_USER"),
            required("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        ObjectMapperHolder json = new ObjectMapperHolder();
        Fixture fixture = seed(jdbc, cipher);
        AccessRepository access = new AccessRepository(jdbc);
        MutationRepository mutations = new MutationRepository(jdbc, cipher);
        LeadBatchAssignmentRepository repository = new LeadBatchAssignmentRepository(jdbc, cipher, json.mapper());
        LeadBatchAssignmentService service = transactional(new LeadBatchAssignmentService(access, repository,
            mutations, json.mapper()), source);
        Actor admin = access.resolve(ISSUER, ADMIN_ONE);
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            verifyEvenAndIdempotency(jdbc, service, admin);
            verifyQuota(jdbc, service, admin);
            verifyOwnerFirstAndSameOwner(jdbc, service, admin, fixture);
            verifyRejectedTargetsAndScope(jdbc, service, access, admin);
            verifyOwnerPermissionsAndReadViews(jdbc, service, access, fixture);
            verifyOwnerOptionsAndVersionConflict(jdbc, service, admin);
            verifyOwnerReadiness(jdbc, service, admin);
            verifyBatchLimitAndAtomicity(jdbc, service, admin);
            verifyRollback(jdbc, service, admin);
            verifyConcurrentWriters(jdbc, service, access);
            verifyReciprocalActors(jdbc, service, access);
        } finally {
            MDC.remove("traceId");
        }
    }

    private void verifyEvenAndIdempotency(JdbcTemplate jdbc, LeadBatchAssignmentService service, Actor admin)
            throws Exception {
        Input input = new Input(List.of("batch-even-1", "batch-even-2", "batch-even-3", "batch-even-4"),
            List.of(OWNER_ONE, OWNER_TWO), "even", null, "均衡分配合成线索");
        Result first = service.assign(admin, "batch-even-idempotency-001", input);
        assertEquals(Map.of(OWNER_ONE, 2, OWNER_TWO, 2), first.assignmentCounts());
        assertEquals(List.of(OWNER_ONE, OWNER_TWO, OWNER_ONE, OWNER_TWO),
            first.records().stream().map(AssignedLead::ownerId).toList());
        Result retry = service.assign(admin, "batch-even-idempotency-001", input);
        assertEquals(first, retry);
        assertEquals(2, jdbc.queryForObject("SELECT version FROM core_crm_lead WHERE public_id='batch-even-1'", Integer.class));
        assertEquals(1, eventCount(jdbc, "batch-even-1"));
        assertLegacyAssignmentFields(jdbc, "batch-even-1", "assigned", "新分配");
        BusinessException reused = assertThrows(BusinessException.class, () -> service.assign(admin,
            "batch-even-idempotency-001", new Input(List.of("batch-even-1"), List.of(OWNER_ONE),
                "even", null, "different body")));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, reused.code());
    }

    private void verifyQuota(JdbcTemplate jdbc, LeadBatchAssignmentService service, Actor admin) throws Exception {
        Input input = new Input(List.of("batch-quota-1", "batch-quota-2", "batch-quota-3"),
            List.of(OWNER_ONE, OWNER_TWO), "quota", List.of(new Quota(OWNER_ONE, 1),
                new Quota(OWNER_TWO, 2)), "按配置名额分配");
        Result result = service.assign(admin, "batch-quota-idempotency-001", input);
        assertEquals(Map.of(OWNER_ONE, 1, OWNER_TWO, 2), result.assignmentCounts());
        assertEquals(List.of(OWNER_ONE, OWNER_TWO, OWNER_TWO),
            result.records().stream().map(AssignedLead::ownerId).toList());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead_event e "
            + "JOIN core_crm_lead l ON l.id=e.lead_id WHERE l.public_id LIKE 'batch-quota-%' "
            + "AND e.event_type='assigned' AND e.detail_cipher IS NOT NULL", Integer.class));
    }

    private void verifyOwnerFirstAndSameOwner(JdbcTemplate jdbc, LeadBatchAssignmentService service,
            Actor admin, Fixture fixture) throws Exception {
        Input ownerFirst = new Input(List.of("batch-owner-first-1", "batch-owner-first-2"),
            List.of(OWNER_ONE, OWNER_TWO), "owner_first", null, "优先保留原负责人");
        Result first = service.assign(admin, "batch-owner-first-key-0001", ownerFirst);
        assertEquals(List.of(new SkippedLead("batch-owner-first-1", "same_owner")), first.skipped());
        assertEquals(OWNER_ONE, first.records().get(0).ownerId());
        Input sameOwner = new Input(List.of("batch-same-owner-1"), List.of(OWNER_ONE, OWNER_TWO),
            "even", null, "校验无变化跳过");
        Result skipped = service.assign(admin, "batch-same-owner-key-0001", sameOwner);
        assertTrue(skipped.records().isEmpty());
        assertEquals(List.of(new SkippedLead("batch-same-owner-1", "same_owner")), skipped.skipped());
        assertEquals(0, eventCount(jdbc, "batch-same-owner-1"));
        assertTrue(fixture.ownerOneId() > 0);
    }

    private void verifyRejectedTargetsAndScope(JdbcTemplate jdbc, LeadBatchAssignmentService service,
            AccessRepository access, Actor admin) {
        assertRejected(ErrorCode.INVALID_INPUT, () -> service.assign(admin, "batch-pending-key-0001",
            even("batch-pending")));
        assertRejected(ErrorCode.INVALID_INPUT, () -> service.assign(admin, "batch-archived-key-0001",
            even("batch-archived")));
        assertRejected(ErrorCode.INVALID_INPUT, () -> service.assign(admin, "batch-cold-key-0001",
            even("batch-cold")));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.assign(admin, "batch-campus-owner-key-0001",
            new Input(List.of("batch-campus-owner"), List.of("batch-owner-campus-b"), "even", null, "范围拒绝")));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.assign(admin, "batch-assigned-owner-key-0001",
            new Input(List.of("batch-assigned-owner"), List.of("batch-owner-assigned"), "even", null, "范围拒绝")));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.assign(admin, "batch-no-follow-owner-key-0001",
            new Input(List.of("batch-no-follow-owner"), List.of("batch-owner-no-follow"), "even", null, "权限拒绝")));
        assertRejected(ErrorCode.FORBIDDEN, () -> service.assign(admin, "batch-crm-follow-owner-key-0001",
            new Input(List.of("batch-crm-follow-owner"), List.of("batch-owner-crm-follow"), "even", null, "精确权限拒绝")));
        Actor campus = access.resolve(ISSUER, "batch-campus-admin");
        assertRejected(ErrorCode.NOT_FOUND, () -> service.assign(campus, "batch-campus-scope-key-0001",
            even("batch-campus-b")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead_event e JOIN core_crm_lead l "
            + "ON l.id=e.lead_id WHERE l.public_id IN ('batch-pending','batch-archived','batch-cold') "
            + "AND e.event_type='assigned'", Integer.class));
    }

    private void verifyOwnerPermissionsAndReadViews(JdbcTemplate jdbc, LeadBatchAssignmentService service,
            AccessRepository access, Fixture fixture) throws Exception {
        Actor admin = access.resolve(ISSUER, ADMIN_ONE);
        Result result = service.assign(admin, "batch-converted-key-0001",
            new Input(List.of(CONVERTED_LEAD), List.of(OWNER_ONE), "even", null, "已报名线索保持转化状态"));
        assertEquals("converted", result.records().get(0).status());
        assertEquals("已报名", result.records().get(0).stage());
        Map<String, Object> actual = jdbc.queryForMap("SELECT status,legacy_status,legacy_stage,decision_status,owner_id "
            + "FROM core_crm_lead WHERE public_id=?", CONVERTED_LEAD);
        assertEquals("new", actual.get("status"));
        assertEquals("converted", actual.get("legacy_status"));
        assertEquals("已报名", actual.get("legacy_stage"));
        assertEquals("public", actual.get("decision_status"));
        assertEquals(fixture.ownerOneId(), ((Number) actual.get("owner_id")).longValue());
        String encryptedEvent = jdbc.queryForObject("SELECT e.detail_cipher FROM core_crm_lead_event e "
            + "JOIN core_crm_lead l ON l.id=e.lead_id WHERE l.public_id=? AND e.event_type='assigned'",
            String.class, CONVERTED_LEAD);
        String detail = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32])).decrypt(encryptedEvent);
        assertTrue(detail.contains("\"fromStage\":\"已报名\""));
        assertTrue(detail.contains("\"toStatus\":\"converted\""));

        Actor reader = access.resolve(ISSUER, "batch-lead-reader");
        LeadRepository leads = new LeadRepository(jdbc, new PiiCipher(Base64.getEncoder().encodeToString(new byte[32])));
        CrmDtos.LeadQuery publicQuery = new CrmDtos.LeadQuery(null, null, null, 1, 100, null, null,
            null, null, true, true, null, null);
        LeadService leadService = new LeadService(access, leads, new MutationRepository(jdbc,
            new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]))), new ObjectMapperHolder().mapper());
        Jwt readerJwt = Jwt.withTokenValue("synthetic-reader-token").header("alg", "none")
            .issuer(ISSUER).subject(reader.publicId()).build();
        LeadController leadController = new LeadController(access, leadService, service);
        CrmDtos.LeadPage publicPool = leadController.list(readerJwt, publicQuery, null).data();
        assertFalse(publicPool.items().stream().anyMatch(row -> row.id().equals("batch-even-1")
            || row.id().equals(CONVERTED_LEAD)), "assigned leads must not remain in the public/unassigned list");
        assertTrue(publicPool.items().stream().anyMatch(row -> row.id().equals("batch-rollback-1")));
        Actor manager = access.resolve(ISSUER, "batch-workbench-manager");
        CrmWorkbenchService workbench = new CrmWorkbenchService(new CrmWorkbenchQueueRepository(jdbc));
        CrmWorkbenchController workbenchController = new CrmWorkbenchController(access, workbench);
        Jwt managerJwt = Jwt.withTokenValue("synthetic-manager-token").header("alg", "none")
            .issuer(ISSUER).subject(manager.publicId()).build();
        CrmDtos.CrmWorkbench scope = workbenchController.get(managerJwt, "scope", java.time.LocalDate.now()).data();
        long publicCount = scope.cards().stream().filter(card -> card.resource().equals("leads")
                && card.queue().equals("unassigned"))
            .mapToLong(CrmDtos.CrmWorkbenchCard::count).findFirst().orElseThrow();
        var queuePage = workbench.queue(manager, new CrmDtos.CrmWorkbenchQueueQuery("team", "leads",
            "unassigned", scope.asOf(), 1, 50));
        assertEquals(queuePage.total(), publicCount, "workbench count and team queue must share one predicate");
    }

    private void verifyBatchLimitAndAtomicity(JdbcTemplate jdbc, LeadBatchAssignmentService service,
            Actor admin) throws Exception {
        long customer = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id='batch-customer'", Long.class);
        List<String> maximumIds = seedBatch(jdbc, customer, "batch-max-ok-", 498);
        Result maximum = service.assign(admin, "batch-max-atomic-key-0001",
            new Input(maximumIds, List.of(OWNER_ONE), "even", null, "498条完整事务上限合成验收"));
        assertEquals(498, maximum.records().size());
        assertEquals(498, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead l JOIN core_crm_lead_event e "
            + "ON e.lead_id=l.id AND e.event_type='assigned' WHERE l.public_id LIKE 'batch-max-ok-%' "
            + "AND l.owner_id=? AND l.version=2", Integer.class, fixtureOwnerId(jdbc, OWNER_ONE)));

        for (int size : List.of(499, 500)) {
            List<String> oversized = java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> "batch-over-" + size + "-" + index).toList();
            BusinessException rejected = assertThrows(BusinessException.class, () -> service.assign(admin,
                "batch-over-key-" + size + "-0000001", new Input(oversized, List.of(OWNER_ONE), "even", null,
                    "超过498时须无写入拒绝")));
            assertEquals(ErrorCode.INVALID_INPUT, rejected.code());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM core_idempotency "
                + "WHERE request_key=?", Integer.class, "batch-over-key-" + size + "-0000001"));
        }

        List<String> rollbackIds = seedBatch(jdbc, customer, "batch-max-rollback-", 498);
        long lastId = leadId(jdbc, rollbackIds.get(rollbackIds.size() - 1));
        jdbc.execute("CREATE TRIGGER batch_assignment_fail_max_event BEFORE INSERT ON core_crm_lead_event "
            + "FOR EACH ROW BEGIN IF NEW.lead_id=" + lastId + " THEN "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic final row failure'; END IF; END");
        try {
            assertThrows(RuntimeException.class, () -> service.assign(admin, "batch-max-rollback-key-0001",
                new Input(rollbackIds, List.of(OWNER_ONE), "even", null, "498条事务整体回滚验收")));
        } finally {
            jdbc.execute("DROP TRIGGER batch_assignment_fail_max_event");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead l "
            + "JOIN core_crm_lead_event e ON e.lead_id=l.id AND e.event_type='assigned' "
            + "WHERE l.public_id LIKE 'batch-max-rollback-%'", Integer.class));
        assertEquals(498, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead "
            + "WHERE public_id LIKE 'batch-max-rollback-%' AND owner_id IS NULL AND version=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM core_idempotency "
            + "WHERE request_key='batch-max-rollback-key-0001'", Integer.class));
    }

    private List<String> seedBatch(JdbcTemplate jdbc, long customer, String prefix, int size) {
        List<String> ids = new java.util.ArrayList<>();
        for (int index = 0; index < size; index++) {
            String id = prefix + index;
            lead(jdbc, customer, id, null, 1, "new", "新线索");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private void verifyRollback(JdbcTemplate jdbc, LeadBatchAssignmentService service, Actor admin) {
        long failedLead = leadId(jdbc, "batch-rollback-2");
        jdbc.execute("CREATE TRIGGER batch_assignment_fail_event BEFORE INSERT ON core_crm_lead_event "
            + "FOR EACH ROW BEGIN IF NEW.lead_id=" + failedLead + " THEN "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic event failure'; END IF; END");
        try {
            assertThrows(RuntimeException.class, () -> service.assign(admin, "batch-rollback-key-0001",
                new Input(List.of("batch-rollback-1", "batch-rollback-2"), List.of(OWNER_ONE),
                    "even", null, "验证整批回滚")));
        } finally {
            jdbc.execute("DROP TRIGGER batch_assignment_fail_event");
        }
        for (String id : List.of("batch-rollback-1", "batch-rollback-2")) {
            assertNull(jdbc.queryForObject("SELECT owner_id FROM core_crm_lead WHERE public_id=?", Long.class, id));
            assertEquals(1, jdbc.queryForObject("SELECT version FROM core_crm_lead WHERE public_id=?", Integer.class, id));
            assertEquals(0, eventCount(jdbc, id));
        }
    }

    private void verifyConcurrentWriters(JdbcTemplate jdbc, LeadBatchAssignmentService service,
            AccessRepository access) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentAssign(service, access, "batch-admin-one",
                "batch-race-key-one-0001", List.of("batch-race-1", "batch-race-2"), ready, start));
            var second = pool.submit(() -> concurrentAssign(service, access, "batch-admin-two",
                "batch-race-key-two-0001", List.of("batch-race-2", "batch-race-1"), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int success = 0, stale = 0;
            for (var result : List.of(first, second)) {
                try { assertNotNull(result.get(30, TimeUnit.SECONDS)); success++; }
                catch (java.util.concurrent.ExecutionException error) {
                    if (error.getCause() instanceof BusinessException business
                            && business.code() == ErrorCode.VERSION_CONFLICT) stale++;
                    else throw error;
                }
            }
            assertEquals(1, success, "only one actor may commit from the selected versions");
            assertEquals(1, stale, "the competing actor must refresh after a version conflict");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        for (String id : List.of("batch-race-1", "batch-race-2")) {
            assertEquals(2, jdbc.queryForObject("SELECT version FROM core_crm_lead WHERE public_id=?", Integer.class, id));
            assertEquals(1, eventCount(jdbc, id));
            assertTrue(List.of(fixtureOwnerId(jdbc, OWNER_ONE), fixtureOwnerId(jdbc, OWNER_TWO)).contains(
                jdbc.queryForObject("SELECT owner_id FROM core_crm_lead WHERE public_id=?", Long.class, id)));
        }
    }

    private void verifyOwnerOptionsAndVersionConflict(JdbcTemplate jdbc, LeadBatchAssignmentService service,
            Actor admin) {
        var options = service.ownerOptions(admin, List.of("batch-even-1"), 1, 50);
        List<String> ids = options.items().stream().map(LeadBatchAssignmentDtos.OwnerChoice::id).toList();
        assertTrue(ids.contains(OWNER_ONE));
        assertTrue(ids.contains(OWNER_TWO));
        assertFalse(ids.contains("batch-owner-assigned"));
        assertFalse(ids.contains("batch-owner-no-follow"));
        assertFalse(ids.contains("batch-owner-campus-b"), "a campus-only employee must cover every selected lead");
        assertTrue(options.items().stream().allMatch(choice -> choice.id().equals(choice.displayName())));

        var stale = new Input(List.of("batch-version-stale"), java.util.Map.of("batch-version-stale", 2L),
            List.of(OWNER_ONE), "even", null, "过期版本应拒绝");
        BusinessException error = assertThrows(BusinessException.class,
            () -> service.assign(admin, "batch-version-stale-key-0001", stale));
        assertEquals(ErrorCode.VERSION_CONFLICT, error.code());
        assertEquals(1, jdbc.queryForObject("SELECT version FROM core_crm_lead WHERE public_id='batch-version-stale'", Integer.class));
        assertEquals(0, eventCount(jdbc, "batch-version-stale"));
    }

    private void verifyOwnerReadiness(JdbcTemplate jdbc, LeadBatchAssignmentService service, Actor admin) {
        for (String flag : List.of("credential_change_required=1,credential_change_ticket_hash=REPEAT('a',64)", "archived_at=UTC_TIMESTAMP(3),archived_by_id=id")) {
            jdbc.update("UPDATE core_principal SET " + flag + " WHERE public_id=?", OWNER_ONE);
            try {
                var options = service.ownerOptions(admin, List.of("batch-even-1"), 1, 50);
                assertFalse(options.items().stream().anyMatch(owner -> owner.id().equals(OWNER_ONE)));
                assertTrue(options.items().stream().anyMatch(owner -> owner.id().equals(OWNER_TWO)));
            } finally {
                jdbc.update("UPDATE core_principal SET credential_change_required=0,credential_change_ticket_hash=NULL,archived_at=NULL,archived_by_id=NULL WHERE public_id=?", OWNER_ONE);
            }
        }
    }

    private void verifyReciprocalActors(JdbcTemplate jdbc, LeadBatchAssignmentService service,
            AccessRepository access) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> reciprocalAssign(service, access, ADMIN_ONE, ADMIN_TWO,
                "batch-mutual-1", "batch-mutual-key-one-0001", ready, start));
            var second = pool.submit(() -> reciprocalAssign(service, access, ADMIN_TWO, ADMIN_ONE,
                "batch-mutual-2", "batch-mutual-key-two-0001", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(30, TimeUnit.SECONDS).records().size());
            assertEquals(1, second.get(30, TimeUnit.SECONDS).records().size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(ADMIN_TWO, jdbc.queryForObject("SELECT p.public_id FROM core_crm_lead l "
            + "JOIN core_principal p ON p.id=l.owner_id WHERE l.public_id='batch-mutual-1'", String.class));
        assertEquals(ADMIN_ONE, jdbc.queryForObject("SELECT p.public_id FROM core_crm_lead l "
            + "JOIN core_principal p ON p.id=l.owner_id WHERE l.public_id='batch-mutual-2'", String.class));
    }

    private Result reciprocalAssign(LeadBatchAssignmentService service, AccessRepository access,
            String actorId, String targetId, String leadId, String key, CountDownLatch ready,
            CountDownLatch start) throws Exception {
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            Actor actor = access.resolve(ISSUER, actorId);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("mutual owner race timed out"); }
            return service.assign(actor, key, new Input(List.of(leadId), List.of(targetId), "even", null,
                "互为负责人并发锁序验证"));
        } finally {
            MDC.remove("traceId");
        }
    }

    private Result concurrentAssign(LeadBatchAssignmentService service, AccessRepository access, String admin,
            String key, List<String> leadIds, CountDownLatch ready, CountDownLatch start) throws Exception {
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            Actor actor = access.resolve(ISSUER, admin);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("batch race timed out"); }
            return service.assign(actor, key, new Input(leadIds, List.of(OWNER_ONE, OWNER_TWO), "even", null,
                "并发写入合成测试"));
        } finally {
            MDC.remove("traceId");
        }
    }

    private Fixture seed(JdbcTemplate jdbc, PiiCipher cipher) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('batch-campus-a','cipher'),('batch-campus-b','cipher')");
        Set<String> adminPermissions = Set.of("lead.assign", "lead.follow", "crm.lead.read", "crm.customer.read");
        long adminOne = principal(jdbc, ADMIN_ONE, "all", adminPermissions);
        principal(jdbc, ADMIN_TWO, "all", adminPermissions);
        long campusAdmin = principal(jdbc, "batch-campus-admin", "campus", Set.of("lead.assign"));
        long ownerOne = principal(jdbc, OWNER_ONE, "all", Set.of("lead.follow"));
        principal(jdbc, OWNER_TWO, "all", Set.of("lead.follow"));
        long campusOwner = principal(jdbc, "batch-owner-campus-b", "campus", Set.of("lead.follow"));
        principal(jdbc, "batch-owner-assigned", "assigned", Set.of("lead.follow"));
        principal(jdbc, "batch-owner-no-follow", "all", Set.of());
        principal(jdbc, "batch-owner-crm-follow", "all", Set.of("crm.lead.follow"));
        principal(jdbc, "batch-lead-reader", "all", Set.of("crm.lead.read", "crm.customer.read"));
        principal(jdbc, "batch-workbench-manager", "all",
            Set.of("crm.lead.read", "crm.customer.read", "crm.lead.assign"));
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(?,1),(?,2)", campusAdmin, campusOwner);
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) VALUES('batch-customer',1,?,?)",
            adminOne, cipher.encrypt("批量线索合成客户"));
        long customer = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id='batch-customer'", Long.class);
        for (int index = 1; index <= 4; index++) { lead(jdbc, customer, "batch-even-" + index, null, 1, "new", "新线索"); }
        for (int index = 1; index <= 3; index++) { lead(jdbc, customer, "batch-quota-" + index, null, 1, "new", "新线索"); }
        lead(jdbc, customer, "batch-owner-first-1", fixtureOwnerId(jdbc, OWNER_TWO), 1, "assigned", "新分配");
        lead(jdbc, customer, "batch-owner-first-2", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-same-owner-1", ownerOne, 1, "assigned", "新分配");
        lead(jdbc, customer, "batch-pending", null, 1, "new", "新线索", "pending");
        lead(jdbc, customer, "batch-archived", null, 1, "new", "已归档");
        lead(jdbc, customer, "batch-cold", null, 1, "cold", "新线索");
        lead(jdbc, customer, "batch-campus-owner", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-assigned-owner", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-no-follow-owner", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-crm-follow-owner", null, 1, "new", "新线索");
        lead(jdbc, customer, CONVERTED_LEAD, null, 1, "converted", "已报名");
        lead(jdbc, customer, "batch-campus-b", null, 2, "new", "新线索");
        lead(jdbc, customer, "batch-rollback-1", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-rollback-2", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-race-1", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-race-2", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-mutual-1", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-mutual-2", null, 1, "new", "新线索");
        lead(jdbc, customer, "batch-version-stale", null, 1, "new", "新线索");
        return new Fixture(ownerOne, campusAdmin);
    }

    private void lead(JdbcTemplate jdbc, long customer, String publicId, Long owner,
            long campus, String legacyStatus, String legacyStage) {
        lead(jdbc, customer, publicId, owner, campus, legacyStatus, legacyStage, "public");
    }

    private void lead(JdbcTemplate jdbc, long customer, String publicId, Long owner,
            long campus, String legacyStatus, String legacyStage, String decisionStatus) {
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,status,"
            + "legacy_status,legacy_stage,decision_status) VALUES(?,?,?,?,'import','Synthetic lead','new',?,?,?)",
            publicId, customer, campus, owner, legacyStatus, legacyStage, decisionStatus);
    }

    private long principal(JdbcTemplate jdbc, String publicId, String scope, Set<String> permissions) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,?)",
            publicId, ISSUER, publicId, scope);
        long id = principalId(jdbc, publicId);
        permissions.forEach(permission -> jdbc.update(
            "INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)", id, permission));
        return id;
    }

    private static long principalId(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?", Long.class, id);
    }

    private static long fixtureOwnerId(JdbcTemplate jdbc, String id) { return principalId(jdbc, id); }
    private static long leadId(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT id FROM core_crm_lead WHERE public_id=?", Long.class, id);
    }
    private static int eventCount(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead_event e JOIN core_crm_lead l "
            + "ON l.id=e.lead_id WHERE l.public_id=? AND e.event_type='assigned'", Integer.class, id);
    }
    private void assertLegacyAssignmentFields(JdbcTemplate jdbc, String id, String status, String stage) {
        Map<String, Object> row = jdbc.queryForMap("SELECT legacy_status,legacy_stage,assigned_at,"
            + "assignment_follow_at,next_follow_at,next_action FROM core_crm_lead WHERE public_id=?", id);
        assertEquals(status, row.get("legacy_status"));
        assertEquals(stage, row.get("legacy_stage"));
        assertNotNull(row.get("assigned_at"));
        assertNull(row.get("assignment_follow_at"));
        assertNull(row.get("next_follow_at"));
        assertEquals("未约定", row.get("next_action"));
    }

    private Input even(String id) { return new Input(List.of(id), List.of(OWNER_ONE), "even", null, "合成验证"); }

    private void assertRejected(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.code());
    }

    private String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " must be set by the isolated acceptance runner");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }

    private static <T> T transactional(T service, DriverManagerDataSource source) {
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T result = (T) proxy.getProxy();
        return result;
    }

    private record Fixture(long ownerOneId, long campusAdminId) { }
    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        com.fasterxml.jackson.databind.ObjectMapper mapper() { return mapper; }
    }
}
