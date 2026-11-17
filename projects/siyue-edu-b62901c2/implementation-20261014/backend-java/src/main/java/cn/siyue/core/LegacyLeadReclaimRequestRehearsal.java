package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.hash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Imports exact legacy lead reclaim requests and their successful audit events atomically. */
final class LegacyLeadReclaimRequestRehearsal {
    private static final String PHASE = "crm_lead_reclaim_requests_v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("id", "leadId", "leadVersion", "applicantId", "applicantName",
        "reason", "status", "createdAt", "rejectReason", "reviewedAt", "reviewedBy", "note");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService phases;

    LegacyLeadReclaimRequestRehearsal(JdbcTemplate jdbc, PiiCipher cipher, LegacyMigrationPhaseService phases) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.phases = phases;
    }

    int importRequests(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database, jdbc, envelope, authorization);
        require("siyue.legacy-business/v1".equals(text(envelope, "schema"))
            && envelope.path("revision").canConvertToLong() && envelope.path("revision").longValue() >= 0,
            "线索取回申请快照版本无效");
        JsonNode requests = array(envelope.path("state"), "leadReclaimRequests");
        JsonNode audit = envelope.path("auditProvenance");
        require(audit.isArray(), "线索取回申请缺少审计来源");
        List<ImportRow> rows = validate(requests, audit);
        JsonNode archive = archive(requests, rows);
        int writes = 1 + rows.stream().mapToInt(row -> 1 + row.events().size()).sum();
        require(writes < 1000, "线索取回申请阶段超过单事务行数限制");
        String sourceHash = hash(archive.toString());
        return phases.execute(() -> write(envelope, archive, rows, sourceHash, writes));
    }

    private int write(JsonNode envelope, JsonNode archive, List<ImportRow> rows, String sourceHash, int writes) {
        requireCrmPhase(envelope);
        long revision = envelope.path("revision").longValue();
        List<Checkpoint> checkpoints = jdbc.query("SELECT source_revision,source_hash,imported_rows FROM "
            + "core_migration_checkpoint WHERE phase=? AND deleted=0 FOR UPDATE",
            (rs, index) -> new Checkpoint(rs.getLong(1), rs.getString(2), rs.getInt(3)), PHASE);
        if (!checkpoints.isEmpty()) {
            Checkpoint old = checkpoints.get(0);
            require(old.revision() == revision && old.hash().equals(sourceHash), "线索取回申请来源变化，禁止覆盖");
            return old.rows();
        }
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
            + "VALUES(?,?,?,?,?)", PHASE, revision, sourceHash, cipher.encrypt(archive.toString()), writes);
        for (ImportRow row : rows) { insert(row); }
        return writes;
    }

    private List<ImportRow> validate(JsonNode requests, JsonNode audit) {
        List<ImportRow> rows = validateSource(requests, audit);
        for (ImportRow row : rows) {
            JsonNode request = row.request();
            requireLead(text(request, "leadId"));
            requirePrincipal(text(request, "applicantId"));
            if (request.hasNonNull("reviewedBy")) { requirePrincipal(text(request, "reviewedBy")); }
        }
        return rows;
    }

    static List<ImportRow> validateSource(JsonNode requests, JsonNode audit) {
        List<ImportRow> result = new ArrayList<>();
        Set<String> ids = new HashSet<>(), auditIds = new HashSet<>();
        for (JsonNode request : requests) {
            validateFields(request);
            String id = identifier(request, "id");
            require(ids.add(id), "线索取回申请编号重复");
            String status = text(request, "status");
            require(Set.of("pending", "approved", "rejected").contains(status), "线索取回申请状态无法映射");
            List<Audit> events = sourceEvents(request, audit, auditIds);
            validateRequestState(request, status, events);
            result.add(new ImportRow(request, events));
        }
        require(auditIds.size() == relevantAuditCount(audit, ids), "线索取回申请存在无法关联的审计事件");
        return result;
    }

    private static void validateRequestState(JsonNode request, String status, List<Audit> events) {
        require(events.size() == (status.equals("pending") ? 1 : 2), "线索取回申请审计链不完整");
        Audit created = events.get(0);
        require(created.action().equals("lead.reclaim.request")
            && created.actor().equals(text(request, "applicantId"))
            && !created.at().before(timestamp(request, "createdAt")), "线索取回申请创建审计不匹配");
        require(positive(request, "leadVersion") > 0,
            "线索取回申请关联版本无效");
        if (!status.equals("pending")) { validateReview(request, status, events.get(1)); }
        else {
            require(!request.hasNonNull("reviewedAt") && !request.hasNonNull("reviewedBy")
                && !request.hasNonNull("rejectReason"), "待审批申请含审核字段");
        }
    }

    private static void validateReview(JsonNode request, String status, Audit review) {
        String reviewedBy = text(request, "reviewedBy");
        Timestamp reviewedAt = timestamp(request, "reviewedAt");
        require(review.action().equals("lead.reclaim.review") && review.actor().equals(reviewedBy)
            && !review.at().before(reviewedAt), "线索取回申请审核审计不匹配");
        String rejectReason = request.path("rejectReason").asText("");
        require(status.equals("approved") ? rejectReason.isEmpty() : rejectReason.isEmpty()
            || rejectReason.equals("pool_state_changed"), "线索取回拒绝原因无法映射");
        if (request.hasNonNull("note")) { optionalText(request, "note", 1000); }
    }

    private static List<Audit> sourceEvents(JsonNode request, JsonNode audit, Set<String> seen) {
        List<Audit> result = new ArrayList<>();
        String id = text(request, "id");
        for (JsonNode event : audit) {
            if (!event.path("outcome").asText().equals("success") || !event.path("changes").isArray()) { continue; }
            boolean matches = false;
            for (JsonNode change : event.path("changes")) {
                if (change.path("collection").asText().equals("leadReclaimRequests")
                        && change.path("id").asText().equals(id)) { matches = true; }
            }
            if (matches) {
                String action = text(event, "action");
                require(action.equals("lead.reclaim.request") || action.equals("lead.reclaim.review"),
                    "线索取回申请审计动作不支持");
                String auditId = uuid(event, "id");
                require(seen.add(auditId), "线索取回申请审计编号重复");
                result.add(new Audit(auditId, text(event, "actorId"), action, timestamp(event, "at"), event));
            }
        }
        result.sort(java.util.Comparator.comparing(Audit::at));
        return result;
    }

    private static int relevantAuditCount(JsonNode audit, Set<String> ids) {
        int count = 0;
        for (JsonNode event : audit) {
            if (!event.path("outcome").asText().equals("success") || !event.path("changes").isArray()) { continue; }
            for (JsonNode change : event.path("changes")) {
                if (change.path("collection").asText().equals("leadReclaimRequests")
                        && ids.contains(change.path("id").asText())) { count++; break; }
            }
        }
        return count;
    }

    private void insert(ImportRow item) {
        JsonNode request = item.request();
        String publicId = text(request, "id");
        Timestamp created = timestamp(request, "createdAt");
        Timestamp updated = request.hasNonNull("reviewedAt") ? timestamp(request, "reviewedAt") : created;
        Long reviewer = request.hasNonNull("reviewedBy") ? principal(text(request, "reviewedBy")) : null;
        jdbc.update("INSERT INTO core_crm_lead_reclaim_request(public_id,lead_id,lead_version,applicant_id,"
            + "applicant_name_cipher,reason_cipher,status,reject_reason,reviewed_by,reviewed_at,note_cipher,"
            + "payload_cipher,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)", publicId,
            leadId(text(request, "leadId")), positive(request, "leadVersion"), principal(text(request, "applicantId")),
            cipher.encrypt(text(request, "applicantName")), cipher.encrypt(text(request, "reason")),
            text(request, "status"), request.path("rejectReason").asText(null), reviewer,
            request.hasNonNull("reviewedAt") ? timestamp(request, "reviewedAt") : null,
            request.hasNonNull("note") ? cipher.encrypt(optionalText(request, "note", 1000)) : null,
            cipher.encrypt(request.toString()), created, updated);
        long rowId = internalId("core_crm_lead_reclaim_request", publicId);
        for (int index = 0; index < item.events().size(); index++) {
            Audit event = item.events().get(index);
            jdbc.update("INSERT INTO core_crm_lead_reclaim_event(public_id,request_id,actor_id,event_type,"
                + "source_audit_id,detail_cipher,occurred_at) VALUES(?,?,?,?,?,?,?)", eventPublicId(publicId, index),
                rowId, principal(event.actor()), index == 0 ? "requested" : "reviewed", event.id(),
                cipher.encrypt(event.source().toString()), event.at());
        }
    }

    private JsonNode archive(JsonNode requests, List<ImportRow> rows) {
        var result = JsonNodeFactory.instance.objectNode();
        result.set("leadReclaimRequests", requests.deepCopy());
        var audit = result.putArray("auditProvenance");
        rows.forEach(row -> row.events().forEach(event -> audit.add(event.source().deepCopy())));
        return result;
    }

    private void requireCrmPhase(JsonNode envelope) {
        JsonNode state = envelope.path("state");
        var crm = JsonNodeFactory.instance.objectNode();
        for (String field : List.of("customers", "campaigns", "touchpoints", "leads", "opportunities")) {
            crm.set(field, state.path(field).deepCopy());
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase=? "
            + "AND source_revision=? AND source_hash=? AND deleted=0", Integer.class, "crm_households_v1",
            envelope.path("revision").longValue(), hash(crm.toString()));
        require(count != null && count == 1, "线索取回申请所依赖CRM阶段未完成");
    }

    private void requireLead(String publicId, long version) {
        require(version > 0, "线索取回申请版本无效");
        requireLead(publicId);
    }

    private void requireLead(String publicId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_lead WHERE public_id=? AND deleted=0",
            Integer.class, publicId);
        require(count != null && count == 1, "线索取回申请所关联线索不存在");
    }

    private static void validateFields(JsonNode request) {
        require(request.isObject(), "线索取回申请格式无效");
        request.fieldNames().forEachRemaining(name -> require(FIELDS.contains(name), "线索取回申请包含未映射字段"));
        for (String field : List.of("id", "leadId", "applicantId", "applicantName", "reason", "status", "createdAt")) {
            text(request, field);
        }
        require(text(request, "reason").length() <= 1000 && positive(request, "leadVersion") > 0,
            "线索取回申请字段无效");
        identifier(request, "id");
    }

    private long principal(String publicId) { return internalId("core_principal", publicId); }
    private long leadId(String publicId) { return internalId("core_crm_lead", publicId); }

    private void requirePrincipal(String publicId) { principal(publicId); }

    private long internalId(String table, String publicId) {
        require(Set.of("core_principal", "core_crm_lead", "core_crm_lead_reclaim_request").contains(table),
            "线索取回申请关联目标不支持");
        List<Long> ids = jdbc.query("SELECT id FROM " + table + " WHERE public_id=? AND deleted=0",
            (rs, index) -> rs.getLong(1), publicId);
        require(ids.size() == 1, "线索取回申请关联记录不存在或不唯一");
        return ids.get(0);
    }

    private static JsonNode array(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value.isArray(), "线索取回申请集合无效");
        return value;
    }

    private static String text(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value.isTextual() && !value.asText().isBlank(), "线索取回申请字段缺失");
        return value.asText();
    }

    private static String optionalText(JsonNode source, String field, int max) {
        JsonNode value = source.path(field);
        require(value.isTextual() && value.asText().length() <= max, "线索取回申请文本字段无效");
        return value.asText();
    }

    private static long positive(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value.canConvertToLong() && value.longValue() > 0, "线索取回申请版本无效");
        return value.longValue();
    }

    private static Timestamp timestamp(JsonNode source, String field) {
        try { return Timestamp.from(Instant.parse(text(source, field))); }
        catch (RuntimeException error) { throw invalid("线索取回申请时间格式无效"); }
    }

    private static String uuid(JsonNode source, String field) {
        String value = text(source, field);
        try { require(UUID.fromString(value).toString().equals(value), "线索取回申请编号无效"); }
        catch (RuntimeException error) { throw invalid("线索取回申请编号无效"); }
        return value;
    }

    private static String identifier(JsonNode source, String field) {
        String value = text(source, field);
        require(value.matches("[A-Za-z0-9_-]{1,120}"), "线索取回申请编号无效");
        return value;
    }

    private static String eventPublicId(String requestId, int ordinal) {
        return "LRQE_" + hash(requestId + ":" + ordinal);
    }

    private static void require(boolean condition, String message) { if (!condition) { throw invalid(message); } }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private record ImportRow(JsonNode request, List<Audit> events) { }
    private record Audit(String id, String actor, String action, Timestamp at, JsonNode source) { }
    private record Checkpoint(long revision, String hash, int rows) { }
}
