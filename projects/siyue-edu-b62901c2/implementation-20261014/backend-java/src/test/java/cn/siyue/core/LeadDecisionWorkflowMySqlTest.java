package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.Base64;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises pending lead decisions against an isolated MySQL schema. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?")
class LeadDecisionWorkflowMySqlTest {
    @Test void pendingDecisionRequiresDecisionMakerBeforeDeadlineAndWritesImmutableHistory() {
        String url = required("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().matches("/[A-Za-z0-9_]+_test"));
        var source = new DriverManagerDataSource(url, required("SIYUE_TEST_MYSQL_USER"),
            required("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        LeadRepository repository = new LeadRepository(jdbc, cipher);
        Fixture fixture = seed(jdbc);
        Actor decider = actor(fixture.deciderId(), "decision-actor");
        Actor other = actor(fixture.otherId(), "other-actor");

        BusinessException wrongActor = assertThrows(BusinessException.class, () -> transaction.execute(status ->
            repository.transfer(other, "decision-lead-wrong-actor", owner("eligible-owner", 1))));
        assertEquals(ErrorCode.FORBIDDEN, wrongActor.code());
        BusinessException expired = assertThrows(BusinessException.class, () -> transaction.execute(status ->
            repository.assign(decider, "decision-lead-expired", owner("eligible-owner", 1))));
        assertEquals(ErrorCode.FORBIDDEN, expired.code());
        BusinessException noChange = assertThrows(BusinessException.class, () -> transaction.execute(status ->
            repository.transfer(decider, "decision-lead-no-change", owner("eligible-owner", 1))));
        assertEquals(ErrorCode.ALREADY_EXISTS, noChange.code());
        BusinessException wrongCampus = assertThrows(BusinessException.class, () -> transaction.execute(status ->
            repository.transfer(decider, "decision-lead-wrong-campus", owner("other-campus-owner", 1))));
        assertEquals(ErrorCode.FORBIDDEN, wrongCampus.code());
        BusinessException noFollow = assertThrows(BusinessException.class, () -> transaction.execute(status ->
            repository.transfer(decider, "decision-lead-no-follow", owner("no-follow-owner", 1))));
        assertEquals(ErrorCode.FORBIDDEN, noFollow.code());

        CrmDtos.Lead result = transaction.execute(status -> repository.transfer(decider,
            "decision-lead-success", owner("eligible-owner", 1)));
        assertNotNull(result);
        assertEquals("transferred", result.decisionStatus());
        assertEquals("decision-actor", result.decisionById());
        assertEquals("eligible-owner", result.ownerId());
        assertEquals(2, result.version());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead_event "
            + "WHERE lead_id=(SELECT id FROM core_crm_lead WHERE public_id='decision-lead-success') AND event_type='transferred'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead_decision_event "
            + "WHERE lead_id=(SELECT id FROM core_crm_lead WHERE public_id='decision-lead-success') "
            + "AND actor_id=? AND decision='transfer' AND owner_id=?", Integer.class,
            fixture.deciderId(), fixture.eligibleOwnerId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead_decision_event "
            + "WHERE lead_id=(SELECT id FROM core_crm_lead WHERE public_id='decision-lead-wrong-actor')", Integer.class));
    }

    private Fixture seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('decision-campus','cipher'),('decision-campus-other','cipher')");
        long decider = principal(jdbc, "decision-actor", true);
        long other = principal(jdbc, "other-actor", true);
        long eligible = principal(jdbc, "eligible-owner", true);
        long noFollow = principal(jdbc, "no-follow-owner", true);
        long otherCampus = principal(jdbc, "other-campus-owner", true);
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,'lead.follow'),(?,'lead.follow')",
            eligible, otherCampus);
        jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(?,1),(?,2)", eligible, otherCampus);
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) "
            + "VALUES('decision-customer',1,?,'cipher')", decider);
        long customer = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id='decision-customer'", Long.class);
        seedLead(jdbc, customer, decider, "decision-lead-wrong-actor", "decision-actor", true, false);
        seedLead(jdbc, customer, decider, "decision-lead-expired", "decision-actor", false, false);
        seedLead(jdbc, customer, decider, "decision-lead-no-change", "decision-actor", true, true, eligible);
        seedLead(jdbc, customer, decider, "decision-lead-wrong-campus", "decision-actor", true, false);
        seedLead(jdbc, customer, decider, "decision-lead-no-follow", "decision-actor", true, false);
        seedLead(jdbc, customer, decider, "decision-lead-success", "decision-actor", true, false);
        return new Fixture(decider, other, eligible);
    }

    private void seedLead(JdbcTemplate jdbc, long customer, long owner, String id, String decider,
            boolean future, boolean assigned) {
        seedLead(jdbc, customer, owner, id, decider, future, assigned, null);
    }

    private void seedLead(JdbcTemplate jdbc, long customer, long owner, String id, String decider,
            boolean future, boolean assigned, Long assignedOwner) {
        String deadline = future ? "UTC_TIMESTAMP(3)+INTERVAL 1 HOUR" : "UTC_TIMESTAMP(3)-INTERVAL 1 HOUR";
        jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,owner_id,source,title,status,decision_status,decision_by_id,decision_deadline) "
            + "VALUES(?,?,1,?,'import','Synthetic lead','new','pending',?," + deadline + ")",
            id, customer, assigned ? assignedOwner : null, owner);
    }

    private long principal(JdbcTemplate jdbc, String publicId, boolean active) {
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) VALUES(?, ?, ?, 'all', ?)",
            publicId, "https://lead-test.invalid", publicId, active);
        return jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?", Long.class, publicId);
    }

    private Actor actor(long id, String publicId) {
        return new Actor(id, publicId, "all", Set.of(), Set.of());
    }

    private CrmDtos.LeadOwner owner(String publicId, long version) {
        return new CrmDtos.LeadOwner(publicId, "synthetic decision reason", version);
    }

    private String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " must be set by the isolated acceptance runner");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }

    private record Fixture(long deciderId, long otherId, long eligibleOwnerId) { }
}
