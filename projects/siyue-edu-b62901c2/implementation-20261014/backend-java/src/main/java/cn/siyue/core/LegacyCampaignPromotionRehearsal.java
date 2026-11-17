package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.array;
import static cn.siyue.core.LegacyCatalogRehearsal.date;
import static cn.siyue.core.LegacyCatalogRehearsal.hash;
import static cn.siyue.core.LegacyCatalogRehearsal.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Imports immutable campaign form links and their historical promotion provenance. */
final class LegacyCampaignPromotionRehearsal {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transactions;

    LegacyCampaignPromotionRehearsal(JdbcTemplate jdbc, PiiCipher cipher, PlatformTransactionManager manager) {
        this(jdbc, cipher, LegacyMigrationPhaseTransactions.create(manager));
    }

    LegacyCampaignPromotionRehearsal(JdbcTemplate jdbc, PiiCipher cipher, LegacyMigrationPhaseService transactions) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transactions = transactions;
    }

    int importAcquisition(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(
            jdbc.queryForObject("SELECT DATABASE()", String.class), jdbc, envelope, authorization);
        if (!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())
                || !envelope.path("revision").isIntegralNumber()) {
            throw invalid("推广链接快照格式或版本无效");
        }
        JsonNode state = envelope.path("state");
        JsonNode audit = envelope.path("auditProvenance");
        if (!audit.isArray()) { throw invalid("推广链接来源审计缺失"); }
        ObjectNode source = source(state, audit);
        long revision = envelope.path("revision").longValue();
        int rows = countRows(source);
        if (rows >= 999) { throw invalid("推广链接阶段超过单事务行数限制"); }
        String sourceHash = hash(source.toString());
        return transactions.execute(() -> write(state, source, revision, sourceHash, rows));
    }

    private ObjectNode source(JsonNode state, JsonNode audit) {
        ObjectNode source = JsonNodeFactory.instance.objectNode();
        source.set("campaigns", array(state, "campaigns").deepCopy());
        source.set("forms", array(state, "forms").deepCopy());
        source.set("touchpoints", array(state, "touchpoints").deepCopy());
        source.set("auditProvenance", audit.deepCopy());
        return source;
    }

    private int countRows(JsonNode source) {
        int rows = 0;
        for (JsonNode campaign : array(source, "campaigns")) {
            rows = Math.addExact(rows, optionalArray(campaign, "formIds").size());
            rows = Math.addExact(rows, optionalArray(campaign, "promotionLinks").size());
        }
        for (JsonNode form : array(source, "forms")) {
            for (JsonNode submission : array(form, "submissions")) {
                if (hasSnapshot(submission, "acquisitionSnapshot")) { rows = Math.addExact(rows, 1); }
            }
        }
        for (JsonNode touchpoint : array(source, "touchpoints")) {
            if (hasSnapshot(touchpoint, "acquisitionSnapshot")) { rows = Math.addExact(rows, 1); }
            else if (touchpoint.hasNonNull("promotionLinkId")) {
                throw invalid("推广触点缺少冻结的表单提交来源");
            }
        }
        return rows;
    }

    private int write(JsonNode state, JsonNode source, long revision, String sourceHash, int rows) {
        requirePhase("crm_households_v1", revision, householdsHash(state));
        requirePhase("form_trials_v1", revision, hash(state.toString()));
        List<Checkpoint> previous = jdbc.query("SELECT source_revision,source_hash,imported_rows "
            + "FROM core_migration_checkpoint WHERE phase='campaign_acquisition_v1' FOR UPDATE",
            (rs, index) -> new Checkpoint(rs.getLong(1), rs.getString(2), rs.getInt(3)));
        if (!previous.isEmpty()) {
            Checkpoint checkpoint = previous.get(0);
            if (checkpoint.revision() != revision || !checkpoint.hash().equals(sourceHash)) {
                throw invalid("推广链接来源变化，禁止覆盖历史");
            }
            return checkpoint.rows();
        }
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
            + "VALUES('campaign_acquisition_v1',?,?,?,?)", revision, sourceHash, cipher.encrypt(source.toString()), rows);
        Map<String, JsonNode> campaigns = index(array(source, "campaigns"));
        Map<String, JsonNode> forms = index(array(source, "forms"));
        Map<String, Long> linkRows = new HashMap<>();
        importFormEdges(campaigns, forms, source.path("auditProvenance"));
        importPromotionLinks(campaigns, forms, source.path("auditProvenance"), linkRows);
        importFormAcquisitions(forms, campaigns, linkRows);
        importTouchpointAcquisitions(array(source, "touchpoints"), campaigns, linkRows);
        return rows;
    }

    private void importFormEdges(Map<String, JsonNode> campaigns, Map<String, JsonNode> forms, JsonNode audit) {
        for (JsonNode campaign : campaigns.values()) {
            for (JsonNode formId : optionalArray(campaign, "formIds")) {
                String formPublicId = formId.asText();
                JsonNode form = forms.get(formPublicId);
                if (form == null) { throw invalid("活动关联的表单不存在"); }
                JsonNode event = matchingAudit(audit, new AuditMatch(List.of("campaign.linkForm", "campaign.create",
                    "campaign.update"), text(campaign, "id"), "formIds", formPublicId,
                    optionalArray(campaign, "formIds").size()));
                if (event == null) { throw invalid("活动表单关系缺少可核验的创建审计"); }
                long activityId = id("core_activity", text(campaign, "id"));
                long formIdValue = id("core_crm_form", formPublicId);
                long actorId = id("core_principal", text(event, "actorId"));
                jdbc.update("INSERT INTO core_activity_form(activity_id,form_id,linked_by,created_at,updated_at) "
                    + "VALUES(?,?,?,?,?)", activityId, formIdValue, actorId, date(event, "at"), date(event, "at"));
            }
        }
    }

    private void importPromotionLinks(Map<String, JsonNode> campaigns, Map<String, JsonNode> forms,
            JsonNode audit, Map<String, Long> linkRows) {
        for (JsonNode campaign : campaigns.values()) {
            for (JsonNode link : optionalArray(campaign, "promotionLinks")) {
                String id = text(link, "id");
                String formPublicId = text(link, "formId");
                String campaignId = text(campaign, "id");
                if (!forms.containsKey(formPublicId)
                        || !contains(optionalArray(campaign, "formIds"), formPublicId)) {
                    throw invalid("推广链接未关联当前活动表单");
                }
                JsonNode event = matchingAudit(audit, new AuditMatch(List.of("campaign.promotionLink.create"),
                    campaignId, "promotionLinks", id, optionalArray(campaign, "promotionLinks").size()));
                if (event == null || !text(event, "actorId").equals(text(link, "createdBy"))
                        || date(event, "at").before(date(link, "createdAt"))) {
                    throw invalid("推广链接缺少一致的创建审计");
                }
                validateDetailedLinkAudit(event, campaignId, link);
                long activityId = id("core_activity", campaignId);
                long formId = id("core_crm_form", formPublicId);
                long promoterId = id("core_principal", text(link, "promoterId"));
                long createdBy = id("core_principal", text(link, "createdBy"));
                jdbc.update("INSERT INTO core_campaign_promotion_link(public_id,activity_id,form_id,promoter_id,"
                    + "created_by,name,promoter_name_cipher,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, activityId, formId, promoterId, createdBy, text(link, "name"),
                    cipher.encrypt(text(link, "promoterName")), date(link, "createdAt"), date(link, "createdAt"));
                linkRows.put(id, jdbc.queryForObject("SELECT id FROM core_campaign_promotion_link WHERE public_id=?",
                    Long.class, id));
            }
        }
    }

    private void importFormAcquisitions(Map<String, JsonNode> forms, Map<String, JsonNode> campaigns,
            Map<String, Long> linkRows) {
        for (JsonNode form : forms.values()) {
            for (JsonNode submission : array(form, "submissions")) {
                JsonNode snapshot = optionalSnapshot(submission, "acquisitionSnapshot");
                if (snapshot == null) { continue; }
                validateSnapshot(snapshot, text(form, "id"), campaigns, linkRows);
                Long submissionId = findNullable("core_crm_form_submission", text(submission, "id"));
                if (submissionId == null) { throw invalid("推广来源表单提交未导入"); }
                jdbc.update("INSERT INTO core_form_acquisition(submission_id,promotion_link_id,campaign_name,"
                    + "promoter_name_cipher,recorded_at) VALUES(?,?,?,?,?)", submissionId,
                    linkRows.get(text(snapshot, "linkId")), text(snapshot, "campaignName"),
                    cipher.encrypt(text(snapshot, "promoterName")), date(snapshot, "recordedAt"));
            }
        }
    }

    private void importTouchpointAcquisitions(JsonNode touchpoints, Map<String, JsonNode> campaigns,
            Map<String, Long> linkRows) {
        for (JsonNode touchpoint : touchpoints) {
            JsonNode snapshot = optionalSnapshot(touchpoint, "acquisitionSnapshot");
            if (snapshot == null) { continue; }
            String formId = text(snapshot, "formId");
            validateSnapshot(snapshot, formId, campaigns, linkRows);
            String submissionPublicId = optionalText(touchpoint, "submissionId");
            if (submissionPublicId == null) { throw invalid("推广触点未保留表单提交关联"); }
            SubmissionRow submission = findSubmission(submissionPublicId);
            Long touchpointId = findNullable("core_customer_touchpoint", text(touchpoint, "id"));
            Long formIdValue = findNullable("core_crm_form", formId);
            if (submission == null || touchpointId == null || formIdValue == null) {
                throw invalid("推广触点或表单提交未导入");
            }
            if (submission.formId() != formIdValue) { throw invalid("推广触点提交不属于来源表单"); }
            requireMatchingFormAcquisition(snapshot, submission.id(), linkRows.get(text(snapshot, "linkId")));
            jdbc.update("INSERT INTO core_touchpoint_acquisition(touchpoint_id,submission_id,promotion_link_id) "
                + "VALUES(?,?,?)", touchpointId, submission.id(), linkRows.get(text(snapshot, "linkId")));
        }
    }

    private void requireMatchingFormAcquisition(JsonNode snapshot, long submissionId, Long linkId) {
        List<FormAcquisitionRow> matches = jdbc.query("SELECT promotion_link_id,campaign_name,promoter_name_cipher,"
            + "recorded_at FROM core_form_acquisition WHERE submission_id=? AND deleted=0",
            (rs, index) -> new FormAcquisitionRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4)), submissionId);
        if (matches.size() != 1) { throw invalid("推广触点缺少唯一冻结表单归因"); }
        FormAcquisitionRow stored = matches.get(0);
        if (linkId == null || stored.linkId() != linkId || !stored.campaignName().equals(text(snapshot, "campaignName"))
                || !cipher.decrypt(stored.promoterNameCipher()).equals(text(snapshot, "promoterName"))
                || !stored.recordedAt().equals(date(snapshot, "recordedAt"))) {
            throw invalid("推广触点与冻结表单归因不一致");
        }
    }

    private SubmissionRow findSubmission(String publicId) {
        List<SubmissionRow> rows = jdbc.query("SELECT s.id,s.form_id FROM core_crm_form_submission s "
            + "WHERE s.public_id=? AND s.deleted=0", (rs, index) -> new SubmissionRow(rs.getLong(1), rs.getLong(2)),
            publicId);
        if (rows.size() > 1) { throw invalid("推广来源表单提交内部主键不唯一"); }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void validateSnapshot(JsonNode snapshot, String formId, Map<String, JsonNode> campaigns,
            Map<String, Long> linkRows) {
        String linkId = text(snapshot, "linkId");
        JsonNode campaign = campaigns.get(text(snapshot, "campaignId"));
        if (campaign == null || !linkRows.containsKey(linkId) || !text(snapshot, "formId").equals(formId)) {
            throw invalid("推广来源快照与推广链接不匹配");
        }
        JsonNode link = findLink(campaign, linkId);
        if (!text(link, "formId").equals(formId)
                || !text(link, "promoterId").equals(text(snapshot, "promoterId"))) {
            throw invalid("推广来源快照身份或表单关系不一致");
        }
    }

    private JsonNode findLink(JsonNode campaign, String linkId) {
        for (JsonNode link : optionalArray(campaign, "promotionLinks")) {
            if (text(link, "id").equals(linkId)) { return link; }
        }
        throw invalid("推广来源链接不存在");
    }

    private JsonNode matchingAudit(JsonNode audit, AuditMatch match) {
        AuditCandidates candidates = collectAuditCandidates(audit, match);
        if (candidates.detailedMatches().size() > 1) { throw invalid("推广来源审计无法唯一对应操作"); }
        if (!candidates.detailedMatches().isEmpty()) { return candidates.detailedMatches().get(0); }
        if (candidates.hasDetailedEvidence()) { throw invalid("推广来源审计内容与活动关系不一致"); }
        return coarseAuditCandidate(match, candidates.campaignMatches());
    }

    private AuditCandidates collectAuditCandidates(JsonNode audit, AuditMatch match) {
        List<JsonNode> detailedMatches = new ArrayList<>();
        Map<String, List<JsonNode>> campaignMatches = new HashMap<>();
        boolean hasDetailedEvidence = false;
        for (JsonNode event : audit) {
            if (!eligibleEvent(event, match)) { continue; }
            for (JsonNode change : optionalArray(event, "changes")) {
                if (!isCampaignChange(change, match.campaignId())) { continue; }
                campaignMatches.computeIfAbsent(event.path("action").asText(), ignored -> new ArrayList<>())
                    .add(event);
                JsonNode after = change.path("after");
                JsonNode before = change.path("before");
                JsonNode afterRows = after.path(match.arrayField());
                JsonNode beforeRows = before.path(match.arrayField());
                hasDetailedEvidence |= afterRows.isArray() || beforeRows.isArray();
                if (containsNewChild(afterRows, beforeRows, before, match)) { detailedMatches.add(event); }
            }
        }
        return new AuditCandidates(detailedMatches, campaignMatches, hasDetailedEvidence);
    }

    private boolean eligibleEvent(JsonNode event, AuditMatch match) {
        return match.actions().contains(event.path("action").asText())
            && "success".equals(event.path("outcome").asText());
    }

    private boolean isCampaignChange(JsonNode change, String campaignId) {
        return "campaigns".equals(change.path("collection").asText())
            && campaignId.equals(change.path("id").asText());
    }

    private boolean containsNewChild(JsonNode afterRows, JsonNode beforeRows, JsonNode before, AuditMatch match) {
        return afterRows.isArray() && containsNestedId(afterRows, match.childId())
            && (!before.isObject() || !before.path(match.arrayField()).isArray()
                || !containsNestedId(beforeRows, match.childId()));
    }

    private JsonNode coarseAuditCandidate(AuditMatch match, Map<String, List<JsonNode>> campaignMatches) {
        String explicitAction = match.actions().get(0);
        List<JsonNode> explicitMatches = campaignMatches.getOrDefault(explicitAction, List.of());
        if (!explicitMatches.isEmpty()) {
            if (match.relationCount() != 1 || explicitMatches.size() != 1) {
                throw invalid("推广来源审计无法唯一对应活动关系");
            }
            return explicitMatches.get(0);
        }
        List<JsonNode> fallbackMatches = new ArrayList<>();
        for (int index = 1; index < match.actions().size(); index++) {
            fallbackMatches.addAll(campaignMatches.getOrDefault(match.actions().get(index), List.of()));
        }
        if (fallbackMatches.isEmpty()) { return null; }
        if (match.relationCount() != 1 || fallbackMatches.size() != 1) {
            throw invalid("推广来源审计无法唯一对应活动关系");
        }
        return fallbackMatches.get(0);
    }

    private boolean containsNestedId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (row.isTextual() && id.equals(row.asText())) { return true; }
            if (id.equals(row.path("id").asText())) { return true; }
        }
        return false;
    }

    private void validateDetailedLinkAudit(JsonNode event, String campaignId, JsonNode sourceLink) {
        for (JsonNode change : optionalArray(event, "changes")) {
            if (!"campaigns".equals(change.path("collection").asText())
                    || !campaignId.equals(change.path("id").asText())) { continue; }
            for (JsonNode row : optionalArray(change.path("after"), "promotionLinks")) {
                if (!text(sourceLink, "id").equals(row.path("id").asText()) || !row.isObject()) { continue; }
                for (String field : List.of("name", "formId", "promoterId", "promoterName", "createdBy",
                        "createdAt")) {
                    if (row.has(field) && !row.path(field).equals(sourceLink.path(field))) {
                        throw invalid("推广链接来源与详细创建审计不一致");
                    }
                }
            }
        }
    }

    private JsonNode optionalArray(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) { return JsonNodeFactory.instance.arrayNode(); }
        if (!value.isArray()) { throw invalid("推广链接集合格式无效：" + field); }
        return value;
    }

    private JsonNode optionalSnapshot(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) { return null; }
        if (!value.isObject()) { throw invalid("推广来源快照格式无效：" + field); }
        return value;
    }

    private boolean hasSnapshot(JsonNode row, String field) { return optionalSnapshot(row, field) != null; }

    private boolean contains(JsonNode rows, String value) {
        for (JsonNode row : rows) { if (value.equals(row.asText())) { return true; } }
        return false;
    }

    private Map<String, JsonNode> index(JsonNode rows) {
        Map<String, JsonNode> values = new HashMap<>();
        for (JsonNode row : rows) {
            if (values.put(text(row, "id"), row) != null) { throw invalid("推广迁移来源主键重复"); }
        }
        return values;
    }

    private Long findNullable(String table, String publicId) {
        List<Long> ids = jdbc.query("SELECT id FROM " + table + " WHERE public_id=? AND deleted=0",
            (rs, index) -> rs.getLong(1), publicId);
        if (ids.size() > 1) { throw invalid("推广来源内部关联主键不唯一"); }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long id(String table, String publicId) {
        Long value = findNullable(table, publicId);
        if (value == null) { throw invalid("推广来源引用的迁移记录不存在"); }
        return value;
    }

    private String optionalText(JsonNode row, String field) {
        return row.hasNonNull(field) && !row.path(field).asText().isBlank() ? row.path(field).asText() : null;
    }

    private String householdsHash(JsonNode state) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        for (String key : List.of("customers", "campaigns", "touchpoints", "leads", "opportunities")) {
            payload.set(key, array(state, key).deepCopy());
        }
        return hash(payload.toString());
    }

    private void requirePhase(String phase, long revision, String sourceHash) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase=? "
            + "AND source_revision=? AND source_hash=? AND deleted=0", Integer.class, phase, revision, sourceHash);
        if (count == null || count != 1) { throw invalid("推广迁移依赖阶段未完成"); }
    }

    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private record SubmissionRow(long id, long formId) { }

    private record FormAcquisitionRow(long linkId, String campaignName, String promoterNameCipher,
            java.sql.Timestamp recordedAt) { }

    private record AuditMatch(List<String> actions, String campaignId, String arrayField, String childId,
            int relationCount) { }

    private record AuditCandidates(List<JsonNode> detailedMatches, Map<String, List<JsonNode>> campaignMatches,
            boolean hasDetailedEvidence) { }

    private record Checkpoint(long revision, String hash, int rows) { }
}
