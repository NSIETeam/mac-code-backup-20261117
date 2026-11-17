package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.array;
import static cn.siyue.core.LegacyCatalogRehearsal.date;
import static cn.siyue.core.LegacyCatalogRehearsal.hash;
import static cn.siyue.core.LegacyCatalogRehearsal.positive;
import static cn.siyue.core.LegacyCatalogRehearsal.text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Imports completed CRM batch provenance only after its referenced CRM records exist. */
final class LegacyImportBatchRehearsal {
    private static final Set<String> OUTCOMES = Set.of("new_customer", "existing_touch", "duplicate_demand",
        "needs_review", "invalid");
    private static final Set<String> DATA_FIELDS = Set.of("name", "phone", "guardian", "grade", "subject",
        "term", "source", "campusId", "note");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;

    LegacyImportBatchRehearsal(JdbcTemplate jdbc, PiiCipher cipher, PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyImportBatchRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transaction = phases;
    }

    int importBatches(JsonNode envelope) {
        return importBatches(envelope, null);
    }

    int importBatches(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database, jdbc, envelope, authorization);
        JsonNode state = envelope.path("state");
        ObjectNode source = JsonNodeFactory.instance.objectNode();
        source.set("importBatches", array(state, "importBatches").deepCopy());
        JsonNode audit = envelope.path("auditProvenance");
        require(audit.isArray(), "CRM导入批次来源审计缺失");
        source.set("auditProvenance", audit.deepCopy());
        long revision = positive(envelope, "revision");
        String sourceHash = hash(source.toString());
        int rows = countRows(source.path("importBatches"));
        require(rows < 1000, "CRM导入批次超过单事务行数限制");
        return transaction.execute(() -> write(envelope, source, sourceHash, revision, rows));
    }

    private int write(JsonNode envelope, ObjectNode source, String sourceHash, long revision, int rows) {
        requirePriorPhase("crm_households_v1", revision, crmHouseholdsHash(envelope.path("state")));
        requirePriorPhase("crm_service_history_v1", revision, serviceHistoryHash(envelope));
        List<Checkpoint> old = jdbc.query("SELECT source_revision,source_hash,imported_rows FROM "
            + "core_migration_checkpoint WHERE phase='crm_import_batches_v1' FOR UPDATE",
            (rs, row) -> new Checkpoint(rs.getLong(1), rs.getString(2), rs.getInt(3)));
        if (!old.isEmpty()) {
            Checkpoint checkpoint = old.get(0);
            require(checkpoint.revision() == revision && checkpoint.hash().equals(sourceHash),
                "CRM导入批次来源变化，禁止覆盖");
            return checkpoint.rows();
        }
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
            + "VALUES('crm_import_batches_v1',?,?,?,?)", revision, sourceHash, cipher.encrypt(source.toString()), rows);
        for (JsonNode batch : array(source, "importBatches")) { insertBatch(batch, source.path("auditProvenance")); }
        return rows;
    }

    private int countRows(JsonNode batches) {
        int rows = 0;
        for (JsonNode batch : batches) {
            JsonNode items = array(batch, "items");
            rows = Math.addExact(rows, Math.addExact(1, items.size()));
            require(text(batch, "status").equals("completed"), "未完成的CRM导入批次缺少可恢复阶段事实");
            require(positive(batch, "version") == 1, "CRM导入批次存在未映射的后续修改历史");
        }
        return rows;
    }

    private void insertBatch(JsonNode batch, JsonNode audit) {
        String publicId = text(batch, "id");
        String creator = text(batch, "createdBy");
        require(creator.equals(text(batch, "ownerId")), "CRM导入批次负责人字段无法无损映射");
        require(text(batch, "status").equals("completed") && positive(batch, "version") == 1,
            "CRM导入批次状态/版本历史无法无损映射");
        String fileFormat = fileFormat(batch);
        AuditEvent auditEvent = auditEvent(audit, "crm.import.commit", "importBatches", publicId);
        require(creator.equals(auditEvent.actor()), "CRM导入批次审计主体不一致");
        requireAuditNotBeforeBatch(auditEvent, batch);
        Long campusId = internalId("core_campus", text(batch, "campusId"));
        Long activityId = optionalInternalId("core_activity", batch, "campaignId");
        Set<String> campuses = strings(array(batch, "campusIds"));
        Set<String> rowCampuses = new HashSet<>();
        JsonNode items = array(batch, "items");
        Map<String, Integer> totals = new HashMap<>();
        for (JsonNode item : items) { validateItem(item, rowCampuses, totals); }
        require(campuses.equals(rowCampuses) && campuses.contains(text(batch, "campusId")),
            "批次校区集合与来源行或批次主校区不一致");
        JsonNode summary = batch.path("summary");
        require(summary.isObject(), "CRM导入批次汇总缺失");
        checkSummary(summary, totals, items.size());
        jdbc.update("INSERT INTO core_crm_import_batch(public_id,created_by,campus_id,activity_id,file_name,file_format,"
            + "status,total_rows,new_customer_count,existing_touch_count,duplicate_demand_count,needs_review_count,"
            + "invalid_count,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'completed',?,?,?,?,?,?,?,?,?)", publicId,
            internalId("core_principal", creator), campusId, activityId, fileName(batch), fileFormat, items.size(),
            totals.getOrDefault("new_customer", 0), totals.getOrDefault("existing_touch", 0),
            totals.getOrDefault("duplicate_demand", 0), totals.getOrDefault("needs_review", 0),
            totals.getOrDefault("invalid", 0), positive(batch, "version"), date(batch, "createdAt"),
            date(batch, "updatedAt"));
        long batchId = internalId("core_crm_import_batch", publicId);
        int rowNumber = 0;
        for (JsonNode item : items) { insertItem(batchId, item, ++rowNumber); }
    }

    private void validateItem(JsonNode item, Set<String> campuses, Map<String, Integer> totals) {
        int rowNumber = Math.toIntExact(positive(item, "rowNumber"));
        require(rowNumber <= 5000, "CRM导入批次行号超出限制");
        String category = text(item, "category");
        require(OUTCOMES.contains(category), "CRM导入批次结果类型未映射");
        totals.merge(category, 1, Integer::sum);
        JsonNode data = item.path("data");
        require(data.isObject() && data.fieldNames().hasNext(), "CRM导入行缺少原始字段");
        data.fieldNames().forEachRemaining(field -> require(DATA_FIELDS.contains(field), "CRM导入行含未映射字段"));
        if (data.hasNonNull("campusId")) { campuses.add(text(data, "campusId")); }
        JsonNode errors = array(item, "errors");
        for (JsonNode error : errors) { require(error.isTextual(), "CRM导入行错误记录格式无效"); }
        if (OUTCOMES.contains(category) && !category.equals("invalid") && !category.equals("needs_review")) {
            require(errors.isEmpty(), "成功导入行仍带校验错误");
            require(data.hasNonNull("campusId"), "成功导入行缺少校区");
            long sourceCampusId = internalId("core_campus", text(data, "campusId"));
            long customer = internalId("core_customer", text(item, "customerId"));
            long lead = internalId("core_crm_lead", text(item, "leadId"));
            long touchpoint = internalId("core_customer_touchpoint", text(item, "touchpointId"));
            require(relatedCustomer(lead, "core_crm_lead") == customer
                && relatedCustomer(touchpoint, "core_customer_touchpoint") == customer,
                "CRM导入行与客户/线索/触点关系不一致");
            require(customerCampus(customer) == sourceCampusId, "CRM导入行校区与客户归属不一致");
        } else {
            require(!errors.isEmpty(), "未导入行缺少错误原因");
            require(item.path("customerId").isNull() && item.path("leadId").isNull()
                && item.path("touchpointId").isNull(), "未导入行不应引用已创建CRM关系");
        }
    }

    private void insertItem(long batchId, JsonNode item, int index) {
        Long customerId = nullableInternalId("core_customer", item, "customerId");
        Long leadId = nullableInternalId("core_crm_lead", item, "leadId");
        Long touchpointId = nullableInternalId("core_customer_touchpoint", item, "touchpointId");
        jdbc.update("INSERT INTO core_crm_import_row(import_batch_id,`row_number`,outcome_type,customer_id,lead_id,"
            + "touchpoint_id,source_data_cipher) VALUES(?,?,?,?,?,?,?)", batchId,
            Math.toIntExact(positive(item, "rowNumber")), text(item, "category"), customerId, leadId, touchpointId,
            cipher.encrypt(item.toString()));
        require(index <= 5000, "CRM导入批次行数超出限制");
    }

    private void checkSummary(JsonNode summary, Map<String, Integer> totals, int total) {
        require(sum(summary, "new_customer") == totals.getOrDefault("new_customer", 0)
            && sum(summary, "existing_touch") == totals.getOrDefault("existing_touch", 0)
            && sum(summary, "duplicate_demand") == totals.getOrDefault("duplicate_demand", 0)
            && sum(summary, "invalid") == totals.getOrDefault("invalid", 0)
            && sum(summary, "needs_review") == totals.getOrDefault("needs_review", 0),
            "CRM导入批次汇总与逐行结果不一致");
        require(totals.values().stream().mapToInt(Integer::intValue).sum() == total,
            "CRM导入批次行数与分类结果不一致");
    }

    private int sum(JsonNode summary, String field) {
        JsonNode value = summary.path(field);
        if (value.isMissingNode() && field.equals("needs_review")) { return 0; }
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0,
            "CRM导入批次汇总字段无效");
        return value.intValue();
    }

    private String fileFormat(JsonNode batch) {
        String name = fileName(batch).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".csv")) { return "csv"; }
        if (name.endsWith(".xlsx")) { return "xlsx"; }
        throw invalid("CRM导入批次文件格式无法从来源确认");
    }

    private String fileName(JsonNode batch) {
        String fileName = text(batch, "fileName");
        require(fileName.length() <= 200 && !fileName.contains("/") && !fileName.contains("\\"),
            "CRM导入批次文件名无效");
        return fileName;
    }

    private AuditEvent auditEvent(JsonNode audit, String action, String collection, String publicId) {
        List<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode event : audit) {
            if (!event.path("action").asText().equals(action) || !event.path("outcome").asText().equals("success")) {
                continue;
            }
            if (!event.path("changes").isArray()) { continue; }
            for (JsonNode change : event.path("changes")) {
                if (change.path("collection").asText().equals(collection)
                        && change.path("id").asText().equals(publicId)) { matches.add(event); }
            }
        }
        require(matches.size() == 1, "CRM导入批次缺少唯一成功审计事件");
        return new AuditEvent(text(matches.get(0), "actorId"), date(matches.get(0), "at"));
    }

    static void requireAuditNotBeforeBatch(AuditEvent auditEvent, JsonNode batch) {
        // The business timestamps use request context time; the system audit samples its clock separately.
        long auditMillis = auditEvent.at().toInstant().toEpochMilli();
        long createdMillis = date(batch, "createdAt").toInstant().toEpochMilli();
        long updatedMillis = date(batch, "updatedAt").toInstant().toEpochMilli();
        require(auditMillis >= createdMillis && auditMillis >= updatedMillis,
            "CRM导入批次审计早于业务记录");
    }

    private Set<String> strings(JsonNode values) {
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) { require(value.isTextual() && !value.asText().isBlank(), "校区集合无效");
            require(result.add(value.asText()), "校区集合重复"); }
        return result;
    }

    private long internalId(String table, String publicId) {
        require(Set.of("core_principal", "core_campus", "core_activity", "core_customer", "core_crm_lead",
            "core_customer_touchpoint", "core_crm_import_batch").contains(table), "CRM导入关联目标不支持");
        List<Long> matches = jdbc.query("SELECT id FROM " + table + " WHERE public_id=? AND deleted=0",
            (rs, row) -> rs.getLong(1), publicId);
        require(matches.size() == 1, "CRM导入缺少精确迁移关联");
        return matches.get(0);
    }

    private Long optionalInternalId(String table, JsonNode row, String field) {
        return row.hasNonNull(field) && !row.path(field).asText().isBlank()
            ? internalId(table, text(row, field)) : null;
    }

    private Long nullableInternalId(String table, JsonNode row, String field) {
        return optionalInternalId(table, row, field);
    }

    private long relatedCustomer(long crmId, String table) {
        require(Set.of("core_crm_lead", "core_customer_touchpoint").contains(table), "CRM导入关联目标不支持");
        Long value = jdbc.queryForObject("SELECT customer_id FROM " + table + " WHERE id=? AND deleted=0",
            Long.class, crmId);
        require(value != null, "CRM导入关系缺失");
        return value;
    }

    private long customerCampus(long customerId) {
        Long value = jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id=? AND deleted=0",
            Long.class, customerId);
        require(value != null, "CRM导入客户校区缺失");
        return value;
    }

    private void requirePriorPhase(String phase, long revision, String expectedHash) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase=? "
            + "AND source_revision=? AND source_hash=? AND deleted=0", Integer.class, phase, revision, expectedHash);
        require(rows != null && rows == 1, "CRM导入批次依赖阶段未完成");
    }

    private String crmHouseholdsHash(JsonNode state) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        for (String field : List.of("customers", "campaigns", "touchpoints", "leads", "opportunities")) {
            payload.set(field, array(state, field).deepCopy());
        }
        return hash(payload.toString());
    }

    private String serviceHistoryHash(JsonNode envelope) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        JsonNode state = envelope.path("state");
        for (String field : List.of("studentCommunications", "serviceTickets", "consultations")) {
            payload.set(field, array(state, field).deepCopy());
        }
        payload.set("auditProvenance", envelope.path("auditProvenance").deepCopy());
        return hash(payload.toString());
    }

    private record Checkpoint(long revision, String hash, int rows) { }
    record AuditEvent(String actor, java.sql.Timestamp at) { }
    private static void require(boolean condition, String message) { if (!condition) { throw invalid(message); } }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
