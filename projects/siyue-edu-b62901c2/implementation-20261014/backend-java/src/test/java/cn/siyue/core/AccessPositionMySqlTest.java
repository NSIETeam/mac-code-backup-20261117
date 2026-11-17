package cn.siyue.core;

import cn.siyue.core.AccessPositionDtos.CreateInput;
import cn.siyue.core.AccessPositionDtos.MembersInput;
import cn.siyue.core.AccessPositionDtos.Position;
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

/** Real-MySQL position grant, revocation, audit, and idempotency checks using synthetic principals only. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:53306/siyue_full_[a-f0-9]{10}_test(?:\\?.*)?")
class AccessPositionMySqlTest {
    private static final String ISSUER = "https://access-position-test.invalid";
    private static final String ADMIN = "synthetic-position-admin";
    private static final String EMPLOYEE = "synthetic-position-employee";

    @Test void inheritedPermissionIsEffectiveAndRevocationBlocksNewWritesAndOldIdempotencyReplay() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(53306, uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_full_[a-f0-9]{10}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        seedPrincipal(jdbc, ADMIN);
        seedPrincipal(jdbc, EMPLOYEE);
        long adminId = principalId(jdbc, ADMIN);
        long employeeId = principalId(jdbc, EMPLOYEE);
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)",
            adminId, "system.access.manage");

        AccessRepository access = new AccessRepository(jdbc);
        MutationRepository mutations = new MutationRepository(jdbc, cipher);
        AccessAdminRepository adminRepository = new AccessAdminRepository(jdbc, mapper);
        AccessPositionService positionService = transactional(new AccessPositionService(access,
            new AccessPositionRepository(jdbc, mapper), adminRepository, mutations, mapper), source);
        SystemSettingsService settings = transactional(new SystemSettingsService(jdbc, access, mutations,
            cipher, mapper), source);
        Actor manager = access.resolve(ISSUER, ADMIN);
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            Position position = positionService.create(manager, "position-create-key-0001",
                new CreateInput(null, "Synthetic settings role", "Synthetic only", "teacher", List.of("settings.update")));
            assertEquals("teacher", jdbc.queryForObject("SELECT directory_kind FROM core_access_position WHERE public_id=?",
                String.class, position.id()));
            position = positionService.members(manager, position.id(), "position-member-key-0001",
                new MembersInput(position.version(), List.of(EMPLOYEE)));
            Actor inherited = access.resolve(ISSUER, EMPLOYEE);
            assertTrue(inherited.permissions().contains("settings.update"));
            assertFalse(inherited.permissions().contains("system.access.manage"));
            assertEquals(List.of("settings.update"), position.permissionCodes());

            SystemSettingsService.Input firstChange = new SystemSettingsService.Input("organization",
                java.util.Map.of("name", "Synthetic settings value"));
            settings.update(inherited, "settings-role-write-key-0001", firstChange);
            assertEquals(settings.get(inherited), settings.update(inherited, "settings-role-write-key-0001", firstChange));
            assertEquals("Synthetic settings value", settings.get(inherited).sections().get("organization").get("name"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_audit WHERE actor_id=? AND action='settings.update'",
                Integer.class, employeeId));

            Position assigned = position;
            var pool = Executors.newFixedThreadPool(2);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var revoke = pool.submit(() -> {
                MDC.put("traceId", UUID.randomUUID().toString());
                try {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("race start timed out"); }
                    return positionService.members(manager, assigned.id(), "position-revoke-key-0001",
                        new MembersInput(assigned.version(), List.of()));
                } finally { MDC.remove("traceId"); }
            });
            var competingWrite = pool.submit(() -> {
                MDC.put("traceId", UUID.randomUUID().toString());
                try {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("race start timed out"); }
                    settings.update(inherited, "settings-race-write-key-0001", new SystemSettingsService.Input(
                        "organization", java.util.Map.of("name", "Synthetic racing value")));
                    return true;
                } catch (BusinessException error) {
                    if (error.code() != ErrorCode.FORBIDDEN) { throw error; }
                    return false;
                } finally { MDC.remove("traceId"); }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            position = revoke.get(30, TimeUnit.SECONDS);
            boolean raceWriteCommitted = competingWrite.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(access.lockActive(employeeId).permissions().stream().noneMatch("settings.update"::equals));
            assertCode(ErrorCode.FORBIDDEN, () -> settings.update(inherited,
                "settings-role-write-key-0001", firstChange));
            assertCode(ErrorCode.FORBIDDEN, () -> settings.update(inherited,
                "settings-role-write-key-0002", new SystemSettingsService.Input("organization",
                    java.util.Map.of("name", "Must not persist"))));
            assertCode(ErrorCode.FORBIDDEN, () -> settings.update(inherited,
                "settings-race-write-key-0001", new SystemSettingsService.Input("organization",
                    java.util.Map.of("name", "Synthetic racing value"))));
            assertEquals(raceWriteCommitted ? "Synthetic racing value" : "Synthetic settings value",
                settings.get(new Actor(employeeId, EMPLOYEE, "all",
                java.util.Set.of(), java.util.Set.of("settings.read"))).sections().get("organization").get("name"));
            assertEquals(raceWriteCommitted ? 2 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM core_audit WHERE actor_id=? AND action='settings.update'",
                Integer.class, employeeId));

            position = positionService.members(manager, position.id(), "position-reassign-key-0001",
                new MembersInput(position.version(), List.of(EMPLOYEE)));
            assertTrue(access.resolve(ISSUER, EMPLOYEE).permissions().contains("settings.update"));
            position = positionService.update(manager, position.id(), "position-disable-key-0001",
                new AccessPositionDtos.UpdateInput(position.version(), position.name(), position.description(),
                    false, null, position.permissionCodes()));
            assertEquals("teacher", jdbc.queryForObject("SELECT directory_kind FROM core_access_position WHERE public_id=?",
                String.class, position.id()));
            assertFalse(access.resolve(ISSUER, EMPLOYEE).permissions().contains("settings.update"));
            assertCode(ErrorCode.FORBIDDEN, () -> settings.update(inherited,
                "settings-role-write-key-0001", firstChange));
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM core_access_position_change_event WHERE position_id=?",
                Integer.class, jdbc.queryForObject("SELECT id FROM core_access_position WHERE public_id=?", Long.class, position.id())));
            jdbc.update("UPDATE core_principal SET archived_at=?,archived_by_id=?,archive_reason_cipher=? WHERE id=?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-17T00:00:00Z")), adminId,
                cipher.encrypt("synthetic archival"), employeeId);
            Position archivedMembership = position;
            assertCode(ErrorCode.FORBIDDEN, () -> positionService.members(manager, archivedMembership.id(),
                "position-archived-member-key-0001", new MembersInput(archivedMembership.version(), List.of(EMPLOYEE))));
        } finally {
            MDC.remove("traceId");
        }
    }

    private static void seedPrincipal(JdbcTemplate jdbc, String publicId) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,'all')",
            publicId, ISSUER, publicId);
    }

    private static long principalId(JdbcTemplate jdbc, String publicId) {
        return jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?", Long.class, publicId);
    }

    private static <T> T transactional(T service, DriverManagerDataSource source) {
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T result = (T) proxy.getProxy();
        return result;
    }

    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
