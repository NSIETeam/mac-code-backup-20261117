package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Checks only source-authenticated campaign promotion rows from a private snapshot. */
@EnabledIfEnvironmentVariable(named = "SIYUE_TEST_MYSQL_URL", matches = ".+")
class LegacyCampaignPromotionSnapshotMySqlTest {
    private static final String PLACEHOLDER_ISSUER = "https://migration-rehearsal.invalid";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void importsTheAuthenticatedPromotionSubsetWithoutClaimingFullSnapshotReadiness() throws Exception {
        String sourcePath = System.getenv("SIYUE_PROMOTION_SOURCE_PATH");
        assumeTrue(sourcePath != null && !sourcePath.isBlank(), "private snapshot path is required for this subset test");
        Path path = Path.of(sourcePath);
        JsonNode envelope = MAPPER.readTree(Files.readAllBytes(path));
        JsonNode state = envelope.path("state");
        JsonNode campaigns = state.path("campaigns");
        JsonNode audit = envelope.path("auditProvenance");
        assertEquals(1, totalNested(campaigns, "promotionLinks"));
        assertEquals(1, totalNested(campaigns, "formIds"));
        assertEquals(1, matchingActionCount(audit, "campaign.linkForm"));
        assertEquals(1, matchingActionCount(audit, "campaign.promotionLink.create"));

        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().endsWith("_test"));
        var dataSource = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

        seedPromotionPrerequisites(jdbc, cipher, state, campaigns, audit);
        insertMatchingPhase(jdbc, cipher, envelope, "crm_households_v1", householdsHash(state), 0);
        insertMatchingPhase(jdbc, cipher, envelope, "form_trials_v1", LegacyCatalogRehearsal.hash(state.toString()), 0);
        LegacyCampaignPromotionRehearsal importer = new LegacyCampaignPromotionRehearsal(jdbc, cipher, manager);
        int expected = 2;
        assertEquals(expected, importer.importAcquisition(envelope, null));
        assertEquals(expected, importer.importAcquisition(envelope, null));

        JsonNode campaign = campaignWithPromotions(campaigns);
        JsonNode link = campaign.path("promotionLinks").get(0);
        JsonNode edgeAudit = matchingEdgeAudit(audit, text(campaign, "id"), text(link, "formId"));
        JsonNode promotionAudit = matchingPromotionAudit(audit, text(campaign, "id"), text(link, "id"));
        assertEquals(text(edgeAudit, "actorId"), jdbc.queryForObject("SELECT p.public_id FROM core_activity_form af "
            + "JOIN core_activity a ON a.id=af.activity_id JOIN core_crm_form f ON f.id=af.form_id "
            + "JOIN core_principal p ON p.id=af.linked_by WHERE a.public_id=? AND f.public_id=?",
            String.class, text(campaign, "id"), text(link, "formId")));
        assertEquals(text(promotionAudit, "actorId"), jdbc.queryForObject("SELECT p.public_id "
            + "FROM core_campaign_promotion_link l JOIN core_principal p ON p.id=l.created_by "
            + "WHERE l.public_id=?", String.class, text(link, "id")));
        assertEquals(text(link, "promoterId"), jdbc.queryForObject("SELECT p.public_id "
            + "FROM core_campaign_promotion_link l JOIN core_principal p ON p.id=l.promoter_id "
            + "WHERE l.public_id=? AND p.active=0", String.class, text(link, "id")));
        assertEquals(text(link, "name"), jdbc.queryForObject("SELECT name FROM core_campaign_promotion_link "
            + "WHERE public_id=?", String.class, text(link, "id")));
        assertEquals(text(link, "promoterName"), cipher.decrypt(jdbc.queryForObject(
            "SELECT promoter_name_cipher FROM core_campaign_promotion_link WHERE public_id=?", String.class,
            text(link, "id"))));
        assertEquals(date(link, "createdAt"), jdbc.queryForObject("SELECT created_at "
            + "FROM core_campaign_promotion_link WHERE public_id=?", Timestamp.class, text(link, "id")));
        assertEquals(sourceProjectionHash(campaigns, state.path("forms"), state.path("touchpoints"), audit),
            jdbc.queryForObject("SELECT source_hash FROM core_migration_checkpoint "
                + "WHERE phase='campaign_acquisition_v1'", String.class));
        assertEquals(expected, jdbc.queryForObject("SELECT imported_rows FROM core_migration_checkpoint "
            + "WHERE phase='campaign_acquisition_v1'", Integer.class));
    }

    private static void seedPromotionPrerequisites(JdbcTemplate jdbc, PiiCipher cipher, JsonNode state,
            JsonNode campaigns, JsonNode audit) {
        Set<String> principals = new HashSet<>();
        Set<String> campuses = new HashSet<>();
        Set<String> forms = new HashSet<>();
        for (JsonNode campaign : campaigns) {
            for (JsonNode formId : campaign.path("formIds")) { forms.add(formId.asText()); }
            for (JsonNode link : campaign.path("promotionLinks")) {
                forms.add(text(link, "formId"));
                principals.add(text(link, "promoterId"));
                principals.add(text(link, "createdBy"));
            }
        }
        for (JsonNode campaign : campaigns) {
            if (campaign.path("formIds").isEmpty() && campaign.path("promotionLinks").isEmpty()) { continue; }
            if (campaign.hasNonNull("campusId")) { campuses.add(text(campaign, "campusId")); }
            principals.add(campaign.hasNonNull("ownerId") ? text(campaign, "ownerId") : text(campaign, "createdBy"));
        }
        for (JsonNode event : audit) {
            String action = event.path("action").asText();
            if (!Set.of("campaign.linkForm", "campaign.create", "campaign.update", "campaign.promotionLink.create")
                    .contains(action)) { continue; }
            if (event.hasNonNull("actorId")) { principals.add(text(event, "actorId")); }
        }
        for (String publicId : principals) {
            jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
                + "VALUES(?,?,?,'all',0)", publicId, PLACEHOLDER_ISSUER, publicId);
        }
        for (String publicId : campuses) {
            jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,?)", publicId,
                cipher.encrypt("migration subset campus"));
        }
        for (JsonNode campaign : campaigns) {
            if (campaign.path("formIds").isEmpty() && campaign.path("promotionLinks").isEmpty()) { continue; }
            String owner = campaign.hasNonNull("ownerId") ? text(campaign, "ownerId") : text(campaign, "createdBy");
            jdbc.update("INSERT INTO core_activity(public_id,campus_id,owner_id,title,activity_type,status,starts_at,"
                + "ends_at,version,created_at,updated_at) VALUES(?,(SELECT id FROM core_campus WHERE public_id=?),"
                + "(SELECT id FROM core_principal WHERE public_id=?),'subset campaign','campaign','active',"
                + "'2026-10-01 00:00:00.000','2026-11-01 00:00:00.000',1,'2026-10-01 00:00:00.000',"
                + "'2026-10-01 00:00:00.000')", text(campaign, "id"), text(campaign, "campusId"), owner);
        }
        for (String publicId : forms) {
            JsonNode sourceForm = find(state.path("forms"), publicId);
            String campusId = text(sourceForm, "campusId");
            String ownerId = text(sourceForm, "createdBy");
            if (!campuses.contains(campusId)) {
                jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,?)", campusId,
                    cipher.encrypt("migration subset campus"));
                campuses.add(campusId);
            }
            if (!principals.contains(ownerId)) {
                jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
                    + "VALUES(?,?,?,'all',0)", ownerId, PLACEHOLDER_ISSUER, ownerId);
                principals.add(ownerId);
            }
            jdbc.update("INSERT INTO core_crm_form(public_id,campus_id,owner_id,title,form_kind,collect_lead,status,"
                + "current_revision,fields_json,lead_mapping_json,version,created_at,updated_at) "
                + "VALUES(?,(SELECT id FROM core_campus WHERE public_id=?),(SELECT id FROM core_principal "
                + "WHERE public_id=?),'subset form','lead',0,'published',1,'[]','{}',1,"
                + "'2026-10-01 00:00:00.000','2026-10-01 00:00:00.000')", publicId, campusId, ownerId);
        }
    }

    private static void insertMatchingPhase(JdbcTemplate jdbc, PiiCipher cipher, JsonNode envelope, String phase,
            String sourceHash, int importedRows) {
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
            + "VALUES(?,?,?,?,?)", phase, envelope.path("revision").longValue(), sourceHash,
            cipher.encrypt("synthetic prerequisite marker"), importedRows);
    }

    private static String householdsHash(JsonNode state) {
        ObjectNode source = MAPPER.createObjectNode();
        for (String field : List.of("customers", "campaigns", "touchpoints", "leads", "opportunities")) {
            source.set(field, state.path(field).deepCopy());
        }
        return LegacyCatalogRehearsal.hash(source.toString());
    }

    private static String sourceProjectionHash(JsonNode campaigns, JsonNode forms,
            JsonNode touchpoints, JsonNode audit) {
        ObjectNode source = MAPPER.createObjectNode();
        source.set("campaigns", campaigns.deepCopy());
        source.set("forms", forms.deepCopy());
        source.set("touchpoints", touchpoints.deepCopy());
        source.set("auditProvenance", audit.deepCopy());
        return LegacyCatalogRehearsal.hash(source.toString());
    }

    private static JsonNode campaignWithPromotions(JsonNode campaigns) {
        for (JsonNode campaign : campaigns) {
            if (!campaign.path("promotionLinks").isEmpty()) { return campaign; }
        }
        throw new IllegalStateException("promotion source is missing");
    }

    private static JsonNode matchingEdgeAudit(JsonNode audit, String campaignId, String formId) {
        return matchingAudit(audit, "campaign.linkForm", campaignId);
    }

    private static JsonNode matchingPromotionAudit(JsonNode audit, String campaignId, String linkId) {
        return matchingAudit(audit, "campaign.promotionLink.create", campaignId);
    }

    private static JsonNode matchingAudit(JsonNode audit, String action, String campaignId) {
        JsonNode found = null;
        for (JsonNode event : audit) {
            if (!action.equals(event.path("action").asText())
                    || !"success".equals(event.path("outcome").asText())) { continue; }
            for (JsonNode change : event.path("changes")) {
                if (!"campaigns".equals(change.path("collection").asText())
                        || !campaignId.equals(change.path("id").asText())) { continue; }
                if (found != null) { throw new IllegalStateException("promotion audit is ambiguous"); }
                found = event;
            }
        }
        if (found != null) { return found; }
        throw new IllegalStateException("promotion source audit cannot be matched");
    }

    private static int totalNested(JsonNode rows, String field) {
        int count = 0;
        for (JsonNode row : rows) { count = Math.addExact(count, row.path(field).size()); }
        return count;
    }

    private static int matchingActionCount(JsonNode audit, String action) {
        int count = 0;
        for (JsonNode event : audit) { if (action.equals(event.path("action").asText())) { count++; } }
        return count;
    }

    private static JsonNode find(JsonNode rows, String id) {
        for (JsonNode row : rows) { if (id.equals(row.path("id").asText())) { return row; } }
        throw new IllegalStateException("promotion form source missing");
    }

    private static String text(JsonNode row, String field) { return LegacyCatalogRehearsal.text(row, field); }

    private static Timestamp date(JsonNode row, String field) { return LegacyCatalogRehearsal.date(row, field); }
}
