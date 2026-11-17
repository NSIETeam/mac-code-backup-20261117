package cn.siyue.core;

import cn.siyue.core.CrmDtos.ReceiptInput;
import cn.siyue.core.CrmDtos.RefundRecordInput;
import cn.siyue.core.CrmDtos.RefundRequestInput;
import cn.siyue.core.CrmDtos.RefundReviewInput;
import cn.siyue.core.SalesReportDtos.Activity;
import cn.siyue.core.SalesReportDtos.ActivityPage;
import cn.siyue.core.SalesReportDtos.ActivityQuery;
import cn.siyue.core.SalesReportDtos.Metric;
import cn.siyue.core.SalesReportDtos.Report;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Sales metrics and activity acceptance against the exclusive local MySQL 8.0 *_test schema. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:53306/siyue_full_sales_[a-f0-9]{10}_test(?:\\?.*)?")
class SalesReportMySqlTest {
    private static final String ISSUER = "https://sales-report.test/realm";
    private static final String KEY = UUID.randomUUID().toString();
    private record Fixture(DriverManagerDataSource source, JdbcTemplate jdbc, PiiCipher cipher,
            Actor manager, Actor sellerA, Actor sellerB, long campusA, long campusB) { }

    @Test void firstReceiptFreezesOwnerAndReportHonorsCohortScopesRefundsAndLowSample() throws Exception {
        Fixture f = fixture();
        Instant cohortStart = LocalDate.now(ZoneOffset.UTC).minusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC);
        long convertedLead = lead(f, "converted", f.sellerA().id(), f.campusA(), cohortStart);
        lead(f, "other-a", f.sellerA().id(), f.campusA(), cohortStart.plusSeconds(60));
        lead(f, "other-campus", f.sellerB().id(), f.campusB(), cohortStart.plusSeconds(120));
        String orderId = opportunityOrder(f, "converted", convertedLead, f.sellerA().id(), f.campusA());
        receiveAndRefund(f, orderId);
        transferAfterConversion(f, convertedLead, f.sellerB().id(), cohortStart.plusSeconds(86400));
        assertFrozenSnapshot(f, convertedLead, f.sellerA().id());
        assertReportScopes(f, cohortStart);
    }

    private void assertReportScopes(Fixture f, Instant cohortStart) {
        LocalDate cohortDate = cohortStart.atZone(ZoneOffset.UTC).toLocalDate();
        Report all = reports(f).report(f.manager(), null, start(cohortDate), end(cohortDate));
        Metric ownerA = metric(all, f.sellerA().publicId());
        Metric ownerB = metric(all, f.sellerB().publicId());
        assertEquals(2, ownerA.assignedUnique());
        assertEquals(1, ownerA.convertedUnique());
        assertTrue(ownerA.lowSample());
        assertNull(ownerA.conversionRate());
        assertEquals(new BigDecimal("100.00"), ownerA.receivedAmount());
        assertEquals(new BigDecimal("20.00"), ownerA.refundedAmount());
        assertEquals(new BigDecimal("80.00"), ownerA.netReceivedAmount());
        assertEquals(1, ownerB.assignedUnique());
        Report campus = reports(f).report(campusActor(f), null, start(cohortDate), end(cohortDate));
        assertEquals(List.of(f.sellerA().publicId()), campus.items().stream().map(Metric::employeeId).toList());
        Report own = reports(f).report(ownActor(f), null, start(cohortDate), end(cohortDate));
        assertEquals(List.of(f.sellerA().publicId()), own.items().stream().map(Metric::employeeId).toList());
        Report assigned = reports(f).report(assignedActor(f), null, start(cohortDate), end(cohortDate));
        assertEquals(List.of(f.sellerA().publicId()), assigned.items().stream().map(Metric::employeeId).toList());
        Report afterTransfer = reports(f).report(f.manager(), f.sellerB().publicId(),
            start(cohortDate.plusDays(1)), end(cohortDate.plusDays(1)));
        assertEquals(1, afterTransfer.items().get(0).assignedUnique());
        assertEquals(0, afterTransfer.items().get(0).convertedUnique());
    }

    @Test void activityPaginationUsesOneStableTieKeyAcrossLeadEventsAndAuditRows() {
        Fixture f = fixture();
        Instant tied = Instant.now().minusSeconds(10);
        long leadOne = leadRecord(f, "cursor-one", f.sellerA().id(), f.campusA());
        long leadTwo = leadRecord(f, "cursor-two", f.sellerA().id(), f.campusA());
        insertEvent(f, "Z-event", leadOne, f.manager().id(), f.sellerA().id(), tied);
        insertEvent(f, "A-event", leadTwo, f.manager().id(), f.sellerA().id(), tied);
        insertAudit(f, "sales.action.z", "cursor-one", tied);
        insertAudit(f, "sales.action.a", "cursor-two", tied);
        List<Activity> pages = readAllActivity(f);
        assertEquals(4, pages.size());
        assertEquals(4, pages.stream().map(Activity::id).distinct().count());
        assertEquals(pages.stream().map(Activity::id).sorted(java.util.Comparator.reverseOrder()).toList(),
            pages.stream().map(Activity::id).toList());
    }

    private List<Activity> readAllActivity(Fixture f) {
        List<Activity> items = new ArrayList<>();
        String cursor = null;
        do {
            ActivityPage page = reports(f).activity(f.manager(), new ActivityQuery(null, null, null,
                null, null, 2, cursor));
            items.addAll(page.items());
            cursor = page.nextCursor();
            if (!page.hasMore()) { break; }
        } while (items.size() < 10);
        return items;
    }

    private Fixture fixture() {
        String url = required("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(53306, uri.getPort());
        assertTrue(uri.getPath().matches("/siyue_full_sales_[a-f0-9]{10}_test"));
        var source = new DriverManagerDataSource(url, required("SIYUE_TEST_MYSQL_USER"), required("SIYUE_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0."), "Sales acceptance requires MySQL 8.0");
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        var cipher = new PiiCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        long campusA = campus(jdbc, "campus-a");
        long campusB = campus(jdbc, "campus-b");
        long managerId = principal(jdbc, "manager", "all");
        long sellerAId = principal(jdbc, "seller-a", "own");
        long sellerBId = principal(jdbc, "seller-b", "own");
        grant(jdbc, sellerAId, "lead.follow");
        grant(jdbc, sellerBId, "lead.follow");
        linkCampus(jdbc, sellerAId, campusA);
        linkCampus(jdbc, sellerBId, campusB);
        linkCampus(jdbc, managerId, campusA);
        return new Fixture(source, jdbc, cipher, new Actor(managerId, id("manager"), "all", Set.of(),
            Set.of("report.read", "sales.activity.read")),
            new Actor(sellerAId, id("seller-a"), "own", Set.of(), Set.of("lead.conversion.read")),
            new Actor(sellerBId, id("seller-b"), "own", Set.of(), Set.of("lead.conversion.read")), campusA, campusB);
    }

    private long lead(Fixture f, String suffix, long owner, long campus, Instant assignedAt) {
        long lead = leadRecord(f, suffix, owner, campus);
        insertEvent(f, "assignment-" + suffix, lead, f.manager().id(), owner, assignedAt);
        return lead;
    }

    private long leadRecord(Fixture f, String suffix, long owner, long campus) {
        String key = id(suffix);
        jdbcCustomer(f.jdbc(), key, owner, campus);
        long customer = findId(f.jdbc(), "core_customer", key);
        f.jdbc().update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,status) "
            + "VALUES(?,?,?,?,'import',?,'new')", key, customer, campus, owner, "Synthetic lead " + key);
        long lead = findId(f.jdbc(), "core_crm_lead", key);
        return lead;
    }

    private String opportunityOrder(Fixture f, String suffix, long lead, long owner, long campus) throws Exception {
        String key = id("deal-" + suffix);
        long customer = f.jdbc().queryForObject("SELECT customer_id FROM core_crm_lead WHERE id=?", Long.class, lead);
        byte[] needKey = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        f.jdbc().update("INSERT INTO core_opportunity(public_id,customer_id,source_lead_id,campus_id,owner_id,title,subject,term,need_key) "
            + "VALUES(?,?,?,?,?,?, 'math','2026',?)", key, customer, lead, campus, owner, "Synthetic deal " + suffix, needKey);
        long opportunity = findId(f.jdbc(), "core_opportunity", key);
        String order = id("order-" + suffix);
        f.jdbc().update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,campus_id,attributed_owner_id,product_snapshot,total_amount) "
            + "VALUES(?,?,?,?,?,'Synthetic course',100.00)", order, opportunity, customer, campus, owner);
        return order;
    }

    private void receiveAndRefund(Fixture f, String orderId) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(f.source()));
        var orders = new CrmOrderRepository(f.jdbc(), f.cipher());
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> orders.receive(f.manager(), orderId,
            new ReceiptInput(new BigDecimal("100.00"), "cash", "synthetic-payment-" + id("receipt"), now, 1)));
        var refunds = new CrmRefundRepository(f.jdbc(), f.cipher());
        var request = refunds.request(f.sellerA(), orderId, new RefundRequestInput(new BigDecimal("20.00"),
            "Synthetic refund", null));
        var approved = refunds.review(f.manager(), request.id(), new RefundReviewInput("approve", "Synthetic approval", 1));
        refunds.record(f.manager(), request.id(), new RefundRecordInput("cash", "synthetic-refund-" + id("ref"),
            now.plusSeconds(1), approved.version()));
    }

    private void transferAfterConversion(Fixture f, long lead, long owner, Instant at) {
        f.jdbc().update("UPDATE core_crm_lead SET owner_id=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?", owner, lead);
        insertEvent(f, "transfer-after-payment", lead, f.manager().id(), owner, at);
    }

    private void assertFrozenSnapshot(Fixture f, long lead, long owner) {
        assertEquals(owner, f.jdbc().queryForObject("SELECT owner_id FROM core_crm_lead_conversion_snapshot WHERE lead_id=?",
            Long.class, lead));
        assertEquals(1, f.jdbc().queryForObject("SELECT COUNT(*) FROM core_crm_lead_conversion_snapshot WHERE lead_id=?",
            Integer.class, lead));
    }

    private void insertEvent(Fixture f, String suffix, long lead, long actor, long toOwner, Instant at) {
        String publicId = id("event-" + suffix);
        f.jdbc().update("INSERT INTO core_crm_lead_event(public_id,lead_id,actor_id,event_type,to_owner_id,occurred_at) "
            + "VALUES(?,?,?,'assigned',?,?)", publicId, lead, actor, toOwner, Timestamp.from(at));
    }

    private void insertAudit(Fixture f, String action, String resource, Instant at) {
        f.jdbc().update("INSERT INTO core_audit(created_at,updated_at,actor_id,action,resource_public_id,trace_id) "
            + "VALUES(?,?,?,?,?,?)", Timestamp.from(at), Timestamp.from(at), f.manager().id(), action,
            id(resource), UUID.randomUUID().toString());
    }

    private void jdbcCustomer(JdbcTemplate jdbc, String id, long owner, long campus) {
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) VALUES(?,?,?,'cipher')",
            id, campus, owner);
    }

    private long campus(JdbcTemplate jdbc, String suffix) {
        String campus = id(suffix);
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,'cipher')", campus);
        return findId(jdbc, "core_campus", campus);
    }

    private long principal(JdbcTemplate jdbc, String suffix, String scope) {
        String publicId = id(suffix);
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,?)",
            publicId, ISSUER, publicId, scope);
        return findId(jdbc, "core_principal", publicId);
    }

    private void grant(JdbcTemplate jdbc, long principal, String permission) {
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)", principal, permission);
    }

    private void linkCampus(JdbcTemplate jdbc, long principal, long campus) {
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(?,?)", principal, campus);
    }

    private long findId(JdbcTemplate jdbc, String table, String id) {
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE public_id=?", Long.class, id);
    }

    private SalesReportRepository reports(Fixture f) { return new SalesReportRepository(f.jdbc()); }

    private static Metric metric(Report report, String employee) {
        return report.items().stream().filter(item -> item.employeeId().equals(employee)).findFirst().orElseThrow();
    }

    private Actor campusActor(Fixture f) {
        return new Actor(f.manager().id(), f.manager().publicId(), "campus", Set.of(f.campusA()), Set.of("report.read"));
    }

    private Actor ownActor(Fixture f) { return f.sellerA(); }

    private Actor assignedActor(Fixture f) {
        return new Actor(f.sellerA().id(), f.sellerA().publicId(), "assigned", Set.of(), f.sellerA().permissions());
    }

    private static Instant start(LocalDate day) { return day.atStartOfDay().toInstant(ZoneOffset.UTC); }
    private static Instant end(LocalDate day) { return day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1); }
    private static String id(String suffix) { return "sales-" + suffix + "-" + KEY; }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException(name + " is required"); }
        return value;
    }
}
