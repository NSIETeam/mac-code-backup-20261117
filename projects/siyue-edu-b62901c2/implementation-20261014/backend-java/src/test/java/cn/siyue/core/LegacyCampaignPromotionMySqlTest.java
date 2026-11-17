package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Verifies historical campaign acquisition imports, provenance, inactive promoters and replay. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class LegacyCampaignPromotionMySqlTest {
    private static final String ACTOR = "legacy-crm-actor";
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private PiiCipher cipher;
    private ObjectNode envelope;
    private LegacyCampaignPromotionRehearsal importer;

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (!"127.0.0.1".equals(uri.getHost()) || !uri.getPath().endsWith("_test")) {
            throw new IllegalStateException("promotion rehearsal requires a loopback *_test schema");
        }
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        envelope = (ObjectNode) new LegacyCrmRehearsalMySqlTest().fixture(mapper).deepCopy();
        addAcquisitionSource();
        preparePrerequisites(envelope);
        importer = new LegacyCampaignPromotionRehearsal(jdbc, cipher, manager);
    }

    private void preparePrerequisites(JsonNode sourceEnvelope) {
        Flyway.configure().dataSource(jdbc.getDataSource()).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(jdbc.getDataSource()).cleanDisabled(false).load().migrate();
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
            + "VALUES(?,'https://migration-rehearsal.invalid',?,'all',0)", ACTOR, ACTOR);
        var catalog = new LegacyCatalogRehearsal(jdbc, cipher, manager);
        catalog.importCatalog(sourceEnvelope);
        new LegacyCrmRehearsal(jdbc, cipher, manager).importCrm(sourceEnvelope);
        new LegacyFormTrialRehearsal(jdbc, cipher, manager).importForms(sourceEnvelope);
    }

    @Test
    void importsExactFormEdgeAndPromotionWithInactiveHistoricalPrincipalAndReplay() {
        assertEquals(4, importer.importAcquisition(envelope, null));
        assertEquals(4, importer.importAcquisition(envelope, null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(af.id) FROM core_activity_form af "
            + "JOIN core_activity a ON a.id=af.activity_id JOIN core_crm_form f ON f.id=af.form_id "
            + "JOIN core_principal p ON p.id=af.linked_by WHERE a.public_id='legacy-campaign' "
            + "AND f.public_id='legacy-form' AND p.public_id=?", Integer.class, ACTOR));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(l.id) FROM core_campaign_promotion_link l "
            + "JOIN core_principal p ON p.id=l.promoter_id JOIN core_principal creator ON creator.id=l.created_by "
            + "WHERE l.public_id='legacy-promotion' AND p.public_id=? AND p.active=0 "
            + "AND creator.public_id=?", Integer.class, ACTOR, ACTOR));
        assertEquals(Timestamp.from(Instant.parse("2026-10-01T00:00:02Z")), jdbc.queryForObject(
            "SELECT created_at FROM core_campaign_promotion_link WHERE public_id='legacy-promotion'", Timestamp.class));
        String encrypted = jdbc.queryForObject("SELECT promoter_name_cipher FROM core_campaign_promotion_link "
            + "WHERE public_id='legacy-promotion'", String.class);
        assertEquals("Legacy promoter", cipher.decrypt(encrypted));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint "
            + "WHERE phase='campaign_acquisition_v1' AND imported_rows=4", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(a.id) FROM core_form_acquisition a "
            + "JOIN core_crm_form_submission s ON s.id=a.submission_id JOIN core_crm_form f ON f.id=s.form_id "
            + "JOIN core_campaign_promotion_link l ON l.id=a.promotion_link_id "
            + "WHERE s.public_id='legacy-form-submission' AND f.public_id='legacy-form' "
            + "AND l.public_id='legacy-promotion' AND a.campaign_name='Synthetic campaign'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(a.id) FROM core_touchpoint_acquisition a "
            + "JOIN core_crm_form_submission s ON s.id=a.submission_id "
            + "JOIN core_customer_touchpoint t ON t.id=a.touchpoint_id "
            + "WHERE s.public_id='legacy-form-submission' AND t.public_id='legacy-touchpoint'", Integer.class));
    }

    @Test
    void rollsBackFormEdgeCheckpointAndPromotionWhenLaterInsertFails() {
        jdbc.execute("CREATE TRIGGER promotion_test_fail BEFORE INSERT ON core_campaign_promotion_link FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic promotion insert failure'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                () -> importer.importAcquisition(envelope, null));
        } finally {
            jdbc.execute("DROP TRIGGER promotion_test_fail");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_form", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_campaign_promotion_link", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint "
            + "WHERE phase='campaign_acquisition_v1'", Integer.class));
        assertEquals(4, importer.importAcquisition(envelope, null));
    }

    @Test
    void rejectsPromotionWithoutItsExactCreationAudit() {
        JsonNode changed = envelope.deepCopy();
        ((ObjectNode) changed.path("auditProvenance").get(changed.path("auditProvenance").size() - 1))
            .put("actorId", "unmapped-promotion-actor");
        assertThrows(IllegalArgumentException.class, () -> importer.importAcquisition(changed, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_form", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_campaign_promotion_link", Integer.class));
    }

    @Test
    void rejectsPromotionWhenDetailedCreationAuditConflictsWithSourceLink() {
        ObjectNode changed = envelope.deepCopy();
        ((ObjectNode) changed.path("auditProvenance").get(changed.path("auditProvenance").size() - 1)
            .path("changes").get(0).path("after").path("promotionLinks").get(0)).put("name", "conflicting name");
        assertThrows(IllegalArgumentException.class, () -> importer.importAcquisition(changed, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_form", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_campaign_promotion_link", Integer.class));
    }

    @Test
    void rejectsSameSourceTouchpointWhenFrozenFormAcquisitionDiffers() {
        ObjectNode changed = envelope.deepCopy();
        ((ObjectNode) changed.path("state").path("touchpoints").get(0).path("acquisitionSnapshot"))
            .put("campaignName", "different frozen source");
        preparePrerequisites(changed);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> importer.importAcquisition(changed, null));
        assertEquals("推广触点与冻结表单归因不一致", error.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_form_acquisition", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_touchpoint_acquisition", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint "
            + "WHERE phase='campaign_acquisition_v1'", Integer.class));
    }

    @Test
    void rejectsStageThatWouldReachOneThousandWritesIncludingCheckpoint() {
        ObjectNode changed = envelope.deepCopy();
        var formIds = ((ObjectNode) changed.path("state").path("campaigns").get(0)).putArray("formIds");
        for (int index = 0; index < 998; index++) { formIds.add("synthetic-form-" + index); }
        assertThrows(IllegalArgumentException.class, () -> importer.importAcquisition(changed, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint "
            + "WHERE phase='campaign_acquisition_v1'", Integer.class));
    }

    private void addAcquisitionSource() throws Exception {
        ObjectNode state = (ObjectNode) envelope.path("state");
        ObjectNode campaign = (ObjectNode) state.path("campaigns").get(0);
        campaign.putArray("formIds").add("legacy-form");
        campaign.set("promotionLinks", mapper.readTree("""
            [{"id":"legacy-promotion","name":"Synthetic promotion","formId":"legacy-form",
              "promoterId":"legacy-crm-actor","promoterName":"Legacy promoter",
              "createdBy":"legacy-crm-actor","createdAt":"2026-10-01T00:00:02Z"}]
            """));
        state.set("forms", mapper.readTree("""
            [{"id":"legacy-form","campusId":"legacy-campus","createdBy":"legacy-crm-actor",
              "title":"Synthetic form","kind":"lead","collectLead":false,"status":"已发布","published":true,
              "publishedBy":"legacy-crm-actor","publishedAt":"2026-10-01T00:00:01Z","publishedVersion":1,
              "version":1,"fields":[],"settings":{"quantity":5,"maxPerIdentity":1,"source":"synthetic"},
              "publishedSnapshot":{"version":1,"title":"Synthetic form","kind":"lead","collectLead":false,
                "fields":[],"settings":{"source":"synthetic"}},"submissions":[],
              "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:01Z"}]
            """));
        ObjectNode acquisition = mapper.createObjectNode().put("campaignId", "legacy-campaign")
            .put("formId", "legacy-form").put("linkId", "legacy-promotion").put("promoterId", ACTOR)
            .put("campaignName", "Synthetic campaign").put("promoterName", "Legacy promoter")
            .put("recordedAt", "2026-10-01T00:00:03Z");
        ObjectNode submission = mapper.createObjectNode().put("id", "legacy-form-submission")
            .put("formVersion", 1).put("requestKey", "a".repeat(64)).put("payloadHash", "b".repeat(64))
            .put("identityHash", "c".repeat(64)).put("status", "pending").put("at", "2026-10-01T00:00:03Z")
            .put("createdAt", "2026-10-01T00:00:03Z").put("submittedBy", "public");
        submission.putObject("answersById");
        submission.set("acquisitionSnapshot", acquisition.deepCopy());
        ObjectNode sourceForm = (ObjectNode) state.path("forms").get(0);
        submission.set("formSnapshot", sourceForm.path("publishedSnapshot").deepCopy());
        sourceForm.putArray("submissions").add(submission);
        ObjectNode sourceTouchpoint = (ObjectNode) state.path("touchpoints").get(0);
        sourceTouchpoint.put("submissionId", "legacy-form-submission")
            .set("acquisitionSnapshot", acquisition.deepCopy());
        state.putArray("trialBookings");
        state.putArray("rewardClaims");
        state.with("activities").putArray("coupon");
        ObjectNode formEdgeEvent = mapper.createObjectNode().put("id", "legacy-form-link-audit")
            .put("actorId", ACTOR).put("action", "campaign.linkForm").put("outcome", "success")
            .put("at", "2026-10-01T00:00:01Z");
        ObjectNode formChange = formEdgeEvent.putArray("changes").addObject().put("collection", "campaigns")
            .put("id", "legacy-campaign");
        ObjectNode formBefore = mapper.createObjectNode();
        formBefore.putArray("formIds");
        formChange.set("before", formBefore);
        ObjectNode formAfter = mapper.createObjectNode();
        formAfter.putArray("formIds").add("legacy-form");
        formChange.set("after", formAfter);
        ObjectNode promotionEvent = mapper.createObjectNode().put("id", "legacy-promotion-create-audit")
            .put("actorId", ACTOR).put("action", "campaign.promotionLink.create").put("outcome", "success")
            .put("at", "2026-10-01T00:00:02Z");
        ObjectNode promotionChange = promotionEvent.putArray("changes").addObject().put("collection", "campaigns")
            .put("id", "legacy-campaign");
        ObjectNode promotionBefore = mapper.createObjectNode();
        promotionBefore.putArray("promotionLinks");
        promotionChange.set("before", promotionBefore);
        ObjectNode promotionAfter = mapper.createObjectNode();
        promotionAfter.putArray("promotionLinks").addObject().put("id", "legacy-promotion");
        promotionChange.set("after", promotionAfter);
        ((com.fasterxml.jackson.databind.node.ArrayNode) envelope.path("auditProvenance")).add(formEdgeEvent)
            .add(promotionEvent);
    }
}
