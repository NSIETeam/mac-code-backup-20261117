package cn.siyue.core;

import cn.siyue.core.CrmDtos.ServiceTicketInput;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.ReplaceInput;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.TypeValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL checks for atomic service-ticket catalog replacement and retained history. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:53306/siyue_ticket_types_[a-f0-9]{8}_test(?:\\?.*)?")
class ServiceTicketTypeReplacementMySqlTest {
    private static final String ISSUER = "https://ticket-type-replacement-test.invalid";
    private static final String ADMIN_ONE = "synthetic-ticket-type-admin-one";
    private static final String ADMIN_TWO = "synthetic-ticket-type-admin-two";

    @Test void replacementIsAtomicVersionedAndKeepsHistoricalTickets() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(53306, uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_ticket_types_[a-f0-9]{8}_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        seedPrincipal(jdbc, ADMIN_ONE, "all", Set.of("lead.assign", "crm.ticket.write", "crm.customer.read"));
        seedPrincipal(jdbc, ADMIN_TWO, "all", Set.of("lead.assign"));
        seedPrincipal(jdbc, "synthetic-ticket-type-campus", "campus", Set.of("lead.assign"));
        seedPrincipal(jdbc, "synthetic-ticket-type-no-grant", "all", Set.of());
        AccessRepository access = new AccessRepository(jdbc);
        CrmServiceTicketRepository ticketRepository = new CrmServiceTicketRepository(jdbc, cipher);
        MutationRepository mutations = new MutationRepository(jdbc, cipher);
        CrmServiceTicketService service = transactional(new CrmServiceTicketService(access,
            ticketRepository, mutations, mapper), source);
        Actor admin = access.resolve(ISSUER, ADMIN_ONE);
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            assertCode(ErrorCode.FORBIDDEN, () -> service.typeRegistry(access.resolve(ISSUER,
                "synthetic-ticket-type-campus")));
            assertCode(ErrorCode.FORBIDDEN, () -> service.replaceTypeRegistry(access.resolve(ISSUER,
                "synthetic-ticket-type-campus"), "ticket-types-campus-scope-002",
                new ReplaceInput(List.of(new TypeValue("question", "无权", true)), 1)));
            assertCode(ErrorCode.FORBIDDEN, () -> service.typeRegistry(access.resolve(ISSUER,
                "synthetic-ticket-type-no-grant")));
            seedCustomer(jdbc, cipher, principalId(jdbc, ADMIN_ONE));
            var ticket = service.create(admin, "ticket-before-type-removal-001",
                new ServiceTicketInput("CUS_TICKET_TYPE_SYNTH", "appointment", "合成历史工单", "类型移除历史保留",
                    null, null, null, null));
            long priorVersion = service.typeRegistry(admin).version();
            ReplaceInput replacement = new ReplaceInput(List.of(
                new TypeValue("complaint", "投诉", true), new TypeValue("question", "咨询", true),
                new TypeValue("retention", "挽留", true)), priorVersion);
            var first = service.replaceTypeRegistry(admin, "ticket-types-replace-first-001", replacement);
            assertEquals(first, service.replaceTypeRegistry(admin, "ticket-types-replace-first-001", replacement));
            assertEquals(priorVersion + 1, first.version());
            assertEquals(0, jdbc.queryForObject("SELECT active FROM core_crm_service_ticket_type "
                + "WHERE campus_id IS NULL AND type_code='appointment'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_service_ticket t "
                + "JOIN core_crm_service_ticket_type ty ON ty.id=t.ticket_type_id "
                + "WHERE t.public_id=? AND ty.type_code='appointment' AND ty.active=0", Integer.class, ticket.id()));
            assertThrows(BusinessException.class, () -> service.create(admin, "ticket-after-type-removal-001",
                new ServiceTicketInput("CUS_TICKET_TYPE_SYNTH", "appointment", "不应成功", "已停用类型",
                    null, null, null, null)));

            seedCampusType(jdbc, cipher, "complaint");
            ReplaceInput conflictsWithCampusOverride = new ReplaceInput(List.of(
                new TypeValue("question", "咨询", true), new TypeValue("retention", "挽留", true)), first.version());
            assertCode(ErrorCode.INVALID_INPUT, () -> service.replaceTypeRegistry(admin,
                "ticket-types-campus-conflict-001", conflictsWithCampusOverride));
            assertEquals(first.version(), service.typeRegistry(admin).version());
            assertEquals(1, jdbc.queryForObject("SELECT active FROM core_crm_service_ticket_type "
                + "WHERE campus_id IS NULL AND type_code='complaint'", Integer.class));

            exerciseConcurrentReplacement(service, access, first.version());
        } finally {
            MDC.remove("traceId");
        }
    }

    private void exerciseConcurrentReplacement(CrmServiceTicketService service, AccessRepository access,
            long version) throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> replaceAfterBarrier(service, access, ADMIN_ONE, "ticket-types-race-one-001",
                new ReplaceInput(List.of(new TypeValue("complaint", "投诉", true),
                    new TypeValue("question", "并发甲", true), new TypeValue("retention", "挽留", true)), version), ready, start));
            var two = pool.submit(() -> replaceAfterBarrier(service, access, ADMIN_TWO, "ticket-types-race-two-001",
                new ReplaceInput(List.of(new TypeValue("complaint", "投诉", true),
                    new TypeValue("question", "并发乙", true), new TypeValue("retention", "挽留", true)), version), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Object> results = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(ServiceTicketTypeRegistryDtos.RegistryState.class::isInstance).count());
            assertEquals(1, results.stream().filter(ErrorCode.VERSION_CONFLICT::equals).count());
            assertEquals(version + 1, service.typeRegistry(access.resolve(ISSUER, ADMIN_ONE)).version());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Object replaceAfterBarrier(CrmServiceTicketService service, AccessRepository access, String subject,
            String key, ReplaceInput input, CountDownLatch ready, CountDownLatch start) throws Exception {
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            Actor actor = access.resolve(ISSUER, subject);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("concurrent replacement timed out"); }
            return service.replaceTypeRegistry(actor, key, input);
        } catch (BusinessException error) {
            if (error.code() != ErrorCode.VERSION_CONFLICT) { throw error; }
            return error.code();
        } finally {
            MDC.remove("traceId");
        }
    }

    private void seedCampusType(JdbcTemplate jdbc, PiiCipher cipher, String code) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,?)", "CAMPUS_TICKET_TYPES_SYNTH",
            cipher.encrypt("合成校区"));
        jdbc.update("INSERT INTO core_crm_service_ticket_type(public_id,campus_id,type_code,name,active) "
            + "SELECT 'TICKET_TYPE_CAMPUS_OVERRIDE',id,?,'校区自定义投诉',1 FROM core_campus "
            + "WHERE public_id='CAMPUS_TICKET_TYPES_SYNTH'", code);
    }

    private void seedCustomer(JdbcTemplate jdbc, PiiCipher cipher, long ownerId) {
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) VALUES(?,NULL,?,?)",
            "CUS_TICKET_TYPE_SYNTH", ownerId, cipher.encrypt("合成客户"));
    }

    private void seedPrincipal(JdbcTemplate jdbc, String publicId, String scope, Set<String> permissions) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,?)",
            publicId, ISSUER, publicId, scope);
        Long id = principalId(jdbc, publicId);
        permissions.forEach(permission -> jdbc.update(
            "INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)", id, permission));
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
