package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.array;
import static cn.siyue.core.LegacyCatalogRehearsal.date;
import static cn.siyue.core.LegacyCatalogRehearsal.hash;
import static cn.siyue.core.LegacyCatalogRehearsal.positive;
import static cn.siyue.core.LegacyCatalogRehearsal.text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Imports only pending, single-order attribution requests with exact source audit evidence. */
final class LegacyAttributionRequestRehearsal {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;

    LegacyAttributionRequestRehearsal(JdbcTemplate jdbc, PiiCipher cipher, PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyAttributionRequestRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transaction = phases;
    }

    int importRequests(JsonNode envelope) { return importRequests(envelope, null); }

    int importRequests(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database, jdbc, envelope, authorization);
        JsonNode state = envelope.path("state");
        JsonNode audit = envelope.path("auditProvenance");
        require(audit.isArray(), "归因申请来源审计缺失");
        ObjectNode source = JsonNodeFactory.instance.objectNode();
        source.set("attributionRequests", array(state, "attributionRequests").deepCopy());
        require(envelope.path("revision").isIntegralNumber() && envelope.path("revision").longValue() >= 0,
            "快照版本无效");
        long revision = envelope.path("revision").longValue();
        int rows = countRows(source.path("attributionRequests"));
        require(rows < 1000, "归因申请阶段超过单事务行数限制");
        source.set("auditProvenance", relevantAudit(source.path("attributionRequests"), audit));
        String sourceHash = hash(source.toString());
        return transaction.execute(() -> write(envelope, source, sourceHash, revision, rows));
    }

    private int write(JsonNode envelope, ObjectNode source, String sourceHash, long revision, int rows) {
        requirePhase("crm_households_v1", revision, crmHouseholdsHash(envelope.path("state")));
        requirePhase("academic_financial_v1", revision, financialHash(envelope));
        List<Checkpoint> old = jdbc.query("SELECT source_revision,source_hash,imported_rows FROM "
            + "core_migration_checkpoint WHERE phase='crm_attribution_requests_v1' FOR UPDATE",
            (rs, row) -> new Checkpoint(rs.getLong(1), rs.getString(2), rs.getInt(3)));
        if (!old.isEmpty()) {
            Checkpoint checkpoint = old.get(0);
            require(checkpoint.revision() == revision && checkpoint.hash().equals(sourceHash),
                "归因申请来源变化，禁止覆盖");
            return checkpoint.rows();
        }
        JsonNode audit = source.path("auditProvenance");
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
            + "VALUES('crm_attribution_requests_v1',?,?,?,?)", revision, sourceHash, cipher.encrypt(source.toString()), rows);
        for (JsonNode request : array(source, "attributionRequests")) { insertRequest(request, audit, envelope.path("state")); }
        return rows;
    }

    private int countRows(JsonNode requests) {
        int rows = 0;
        for (JsonNode request : requests) {
            require(text(request, "status").equals("pending"),
                "已审核归因申请缺少可验证的审核状态和调整历史");
            require(positive(request, "version") == 1, "归因申请存在未映射的修改历史");
            require(array(request, "orderIds").size() == 1 && array(request, "before").size() == 1,
                "多订单归因申请无法无损映射到逐订单审核模型");
            rows = Math.addExact(rows, 2);
        }
        return rows;
    }

    private void insertRequest(JsonNode request, JsonNode audit, JsonNode state) {
        String publicId = text(request, "id");
        String applicant = text(request, "applicantId");
        require(applicant.equals(text(request, "createdBy")), "归因申请人与创建审计主体不一致");
        JsonNode before = array(request, "before").get(0);
        // `orderIds` is the legacy string array, while `before` carries the exact corresponding order snapshot.
        String orderPublicId = array(request, "orderIds").get(0).asText();
        require(!orderPublicId.isBlank() && text(before, "orderId").equals(orderPublicId),
            "归因申请前置订单版本不匹配");
        require(positive(before, "version") == orderVersion(orderPublicId),
            "订单在归因申请后发生变化，来源无法准确重建");
        require(before.path("attribution").isNull(), "已有订单归因快照无法无损迁移为当前申请");
        String opportunityPublicId = text(request, "opportunityId");
        String customerPublicId = text(request, "customerId");
        String campusPublicId = text(request, "campusId");
        String proposedOwner = text(request, "ownerId");
        validateLegacyOpportunityLink(request, state);
        String fromOwner = orderOwner(orderPublicId, opportunityPublicId, customerPublicId, campusPublicId,
            text(findById(array(state, "orders"), orderPublicId), "studentId"));
        AuditEvent created = createdEvent(audit, publicId);
        require(created.actorPublicId().equals(applicant), "归因申请成功审计主体不一致");
        requireAuditNotBeforeRequest(created, request);
        JsonNode evidence = array(request, "evidence");
        require(!evidence.isEmpty() && evidence.size() <= 20, "归因申请证据缺失或超出数量限制");
        for (JsonNode item : evidence) { validateEvidence(item); }
        jdbc.update("INSERT INTO core_crm_attribution_request(public_id,order_id,applicant_id,from_owner_id,"
            + "proposed_owner_id,reason_cipher,evidence_cipher,status,reviewer_id,review_note_cipher,reviewed_at,version,"
            + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,'pending_approval',NULL,NULL,NULL,?,?,?)", publicId,
            internalId("core_crm_order", orderPublicId), internalId("core_principal", applicant),
            internalId("core_principal", fromOwner), internalId("core_principal", proposedOwner),
            cipher.encrypt(text(request, "reason")), cipher.encrypt(evidence.toString()), positive(request, "version"),
            date(request, "createdAt"), date(request, "updatedAt"));
        long requestId = internalId("core_crm_attribution_request", publicId);
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.set("sourceRequest", request.deepCopy());
        jdbc.update("INSERT INTO core_crm_attribution_request_event(public_id,request_id,actor_id,event_type,"
            + "from_status,to_status,detail_cipher,occurred_at) VALUES(?,?,?,'requested',NULL,'pending_approval',?,?)",
            "ATTREVT_LEGACY_" + publicId, requestId, internalId("core_principal", created.actorPublicId()),
            cipher.encrypt(details.toString()), created.at());
    }

    static void requireAuditNotBeforeRequest(AuditEvent auditEvent, JsonNode request) {
        // The business timestamp uses request context time; the system audit samples its clock separately.
        long auditMillis = auditEvent.at().toInstant().toEpochMilli();
        long createdMillis = date(request, "createdAt").toInstant().toEpochMilli();
        long updatedMillis = date(request, "updatedAt").toInstant().toEpochMilli();
        require(auditMillis >= createdMillis && auditMillis >= updatedMillis,
            "归因申请审计早于业务记录");
    }

    private void validateEvidence(JsonNode evidence) {
        String label = text(evidence, "label");
        require(label.length() <= 5000, "归因证据说明超长");
        evidence.fieldNames().forEachRemaining(field -> require(field.equals("label") || field.equals("url"),
            "归因证据含未映射字段"));
        if (evidence.hasNonNull("url")) {
            String url = text(evidence, "url");
            require(url.startsWith("https://") && url.length() <= 2000, "归因证据链接不符合来源规则");
        }
    }

    private String orderOwner(String orderPublicId, String opportunityPublicId, String customerPublicId,
            String campusPublicId, String studentPublicId) {
        List<String> owners = jdbc.query("SELECT owner.public_id FROM core_crm_order o "
            + "JOIN core_student s ON s.id=o.student_id AND s.deleted=0 "
            + "JOIN core_opportunity op ON op.public_id=? AND op.deleted=0 "
            + "JOIN core_customer cu ON cu.public_id=? AND cu.deleted=0 "
            + "JOIN core_principal owner ON owner.id=o.attributed_owner_id AND owner.deleted=0 "
            + "JOIN core_campus cp ON cp.id=o.campus_id AND cp.deleted=0 "
            + "WHERE o.public_id=? AND s.public_id=? AND cp.public_id=? AND op.campus_id=o.campus_id "
            + "AND EXISTS(SELECT cs.id FROM core_customer_student cs WHERE cs.customer_id=cu.id "
            + "AND cs.student_id=s.id AND cs.deleted=0) AND o.deleted=0",
            (rs, row) -> rs.getString(1), opportunityPublicId, customerPublicId, orderPublicId, studentPublicId,
            campusPublicId);
        require(owners.size() == 1, "归因申请订单、商机、客户或校区关系不一致");
        return owners.get(0);
    }

    private void validateLegacyOpportunityLink(JsonNode request, JsonNode state) {
        JsonNode opportunity = findById(array(state, "opportunities"), text(request, "opportunityId"));
        JsonNode customer = findById(array(state, "customers"), text(request, "customerId"));
        JsonNode order = findById(array(state, "orders"), array(request, "orderIds").get(0).asText());
        JsonNode student = findById(array(state, "students"), text(order, "studentId"));
        require(text(opportunity, "customerId").equals(text(request, "customerId"))
            && text(opportunity, "campusId").equals(text(request, "campusId"))
            && text(order, "campusId").equals(text(request, "campusId")), "来源订单、商机、客户校区不一致");
        boolean linked = sameId(customer.path("studentId"), text(student, "id"))
            || containsId(arrayIfPresent(customer, "studentIds"), text(student, "id"))
            || sameId(student.path("customerId"), text(customer, "id"))
            || leadLinksStudent(state, opportunity, text(student, "id"))
            || student.hasNonNull("originLeadId") && text(student, "originLeadId").equals(
                opportunity.path("leadId").asText());
        require(linked, "订单学生与归因商机客户缺少明确档案关联");
    }

    private boolean leadLinksStudent(JsonNode state, JsonNode opportunity, String studentId) {
        if (!opportunity.hasNonNull("leadId")) { return false; }
        JsonNode lead = findById(array(state, "leads"), text(opportunity, "leadId"));
        return lead.path("studentId").asText().equals(studentId);
    }

    private JsonNode findById(JsonNode rows, String id) {
        for (JsonNode row : rows) { if (row.path("id").asText().equals(id)) { return row; } }
        throw invalid("归因申请缺少精确来源关系");
    }

    private JsonNode arrayIfPresent(JsonNode row, String key) {
        JsonNode values = row.path(key);
        return values.isArray() ? values : JsonNodeFactory.instance.arrayNode();
    }

    private boolean sameId(JsonNode value, String id) { return value.isTextual() && value.asText().equals(id); }

    private boolean containsId(JsonNode values, String id) {
        for (JsonNode value : values) { if (sameId(value, id)) { return true; } }
        return false;
    }

    private long orderVersion(String orderPublicId) {
        Long version = jdbc.queryForObject("SELECT version FROM core_crm_order WHERE public_id=? AND deleted=0",
            Long.class, orderPublicId);
        require(version != null, "归因申请订单缺失");
        return version;
    }

    private AuditEvent createdEvent(JsonNode audit, String publicId) {
        List<AuditEvent> events = new ArrayList<>();
        for (JsonNode event : audit) {
            if (!event.path("action").asText().equals("order.attribution.request")
                    || !event.path("outcome").asText().equals("success") || !event.path("changes").isArray()) {
                continue;
            }
            for (JsonNode change : event.path("changes")) {
                if (change.path("collection").asText().equals("attributionRequests")
                        && change.path("id").asText().equals(publicId)) {
                    events.add(new AuditEvent(text(event, "actorId"), date(event, "at")));
                }
            }
        }
        require(events.size() == 1, "归因申请缺少唯一成功创建审计事件");
        return events.get(0);
    }

    private JsonNode relevantAudit(JsonNode requests, JsonNode audit) {
        var relevant = JsonNodeFactory.instance.arrayNode();
        for (JsonNode request : requests) {
            String publicId = text(request, "id");
            for (JsonNode event : audit) {
                if (event.path("action").asText().equals("order.attribution.request")
                        && event.path("outcome").asText().equals("success") && event.path("changes").isArray()
                        && containsChange(event.path("changes"), publicId)) { relevant.add(event.deepCopy()); }
            }
        }
        return relevant;
    }

    private boolean containsChange(JsonNode changes, String publicId) {
        for (JsonNode change : changes) {
            if (change.path("collection").asText().equals("attributionRequests")
                    && change.path("id").asText().equals(publicId)) { return true; }
        }
        return false;
    }

    private long internalId(String table, String publicId) {
        require(List.of("core_crm_order", "core_principal", "core_crm_attribution_request").contains(table),
            "归因申请关联目标不支持");
        List<Long> ids = jdbc.query("SELECT id FROM " + table + " WHERE public_id=? AND deleted=0",
            (rs, row) -> rs.getLong(1), publicId);
        require(ids.size() == 1, "归因申请缺少准确关联记录");
        return ids.get(0);
    }

    private void requirePhase(String phase, long revision, String expectedHash) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase=? "
            + "AND source_revision=? AND source_hash=? AND deleted=0", Integer.class, phase, revision, expectedHash);
        require(count != null && count == 1, "归因申请依赖阶段未完成");
    }

    private String crmHouseholdsHash(JsonNode state) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        for (String field : List.of("customers", "campaigns", "touchpoints", "leads", "opportunities")) {
            payload.set(field, array(state, field).deepCopy());
        }
        return hash(payload.toString());
    }

    private String financialHash(JsonNode envelope) {
        return hash("state=" + envelope.path("state") + "\nauditProvenance=" + envelope.path("auditProvenance"));
    }

    record AuditEvent(String actorPublicId, java.sql.Timestamp at) { }
    private record Checkpoint(long revision, String hash, int rows) { }
    private static void require(boolean condition, String message) { if (!condition) { throw invalid(message); } }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
