package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Imports the remaining historical CRM operating configuration and task records atomically. */
public final class LegacyCrmOperationsRehearsal {
    private static final String PHASE = "crm_operations_v1";
    private static final Set<String> PRINCIPAL_TABLE = Set.of("core_principal");
    private static final Set<String> CAMPUS_TABLE = Set.of("core_campus");
    private static final Set<String> LEAD_TABLE = Set.of("core_crm_lead");
    private static final Set<String> ACTIVITY_TABLE = Set.of("core_activity");
    private static final Set<String> CUSTOMER_TABLE = Set.of("core_customer");
    private static final Set<String> OPPORTUNITY_TABLE = Set.of("core_opportunity");
    private static final Set<String> BATCH_TABLE = Set.of("core_crm_task_batch");
    private static final Set<String> TASK_TABLE = Set.of("core_crm_task");
    private static final Set<String> RULE_TABLE = Set.of("core_crm_routing_rule");
    private static final Set<String> VIEW_KEYS = Set.of("resource", "scope", "queue", "id", "customerId",
        "campaignId", "ownerId", "campusId", "stage", "status", "taskType", "search", "from", "to",
        "dateField", "sort");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Uses only the application cipher and one bounded transaction per migration phase. */
    public LegacyCrmOperationsRehearsal(JdbcTemplate jdbc, PiiCipher cipher,
            PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyCrmOperationsRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transaction = phases;
    }

    /** Imports a strict source snapshot only into a disposable `_test` schema. */
    public int importCrmOperations(JsonNode envelope) {
        return importCrmOperations(envelope, null);
    }

    /** Candidate-only path, bound to the exact source envelope and candidate gate. */
    int importCrmOperations(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database, jdbc, envelope, authorization);
        verifyEnvelope(envelope);
        JsonNode state = envelope.path("state");
        validateRows(state);
        int writes = writeCount(state);
        if (writes >= 1000) { throw invalid("CRM运营迁移阶段将超过单事务1000行上限"); }
        long revision = envelope.path("revision").longValue();
        JsonNode payload = payload(state);
        String source = payload.toString();
        String hash = LegacyCatalogRehearsal.hash(source);
        int imported = sourceRows(payload);
        return transaction.execute(() -> {
            requirePrerequisites(revision, envelope.path("state"));
            List<Checkpoint> previous = jdbc.query("SELECT source_revision,source_hash,imported_rows "
                + "FROM core_migration_checkpoint WHERE phase=? FOR UPDATE",
                (rs, row) -> new Checkpoint(rs.getLong(1), rs.getString(2), rs.getInt(3)), PHASE);
            if (!previous.isEmpty()) {
                Checkpoint old = previous.get(0);
                if (old.revision() != revision || !old.hash().equals(hash)) {
                    throw invalid("CRM运营来源变化，禁止覆盖已导入历史");
                }
                return old.rows();
            }
            jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
                + "VALUES(?,?,?,?,?)", PHASE, revision, hash, cipher.encrypt(source), imported);
            importRules(payload);
            importQueue(payload);
            importViews(payload);
            importTaskBatches(payload);
            importTasks(payload);
            importPolicy(payload);
            reconcile(payload);
            return imported;
        });
    }

    private void verifyEnvelope(JsonNode envelope) {
        if (!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())
                || !envelope.path("revision").canConvertToLong() || envelope.path("revision").longValue() < 1
                || !envelope.path("state").isObject()) {
            throw invalid("快照格式或版本无效");
        }
    }

    private JsonNode payload(JsonNode state) {
        var result = mapper.createObjectNode();
        for (String name : List.of("routingRules", "routingQueue", "savedCrmViews", "crmTaskBatches", "crmTasks")) {
            result.set(name, state.path(name).deepCopy());
        }
        result.set("leadPolicy", state.path("leadPolicy").deepCopy());
        return result;
    }

    private void validateRows(JsonNode state) {
        for (String key : List.of("routingRules", "routingQueue", "savedCrmViews", "crmTaskBatches", "crmTasks")) {
            if (!state.path(key).isArray()) { throw invalid("迁移集合格式无效：" + key); }
        }
        if (!state.path("leadPolicy").isObject()) { throw invalid("线索策略快照无效"); }
        exactKeys(state.path("leadPolicy"), Set.of("reserveDays", "reclaimDays", "firstFollowHours", "decisionHours"));
        range(state.path("leadPolicy"), "reserveDays", 1, 3650);
        range(state.path("leadPolicy"), "reclaimDays", 1, 3650);
        range(state.path("leadPolicy"), "firstFollowHours", 1, 8760);
        range(state.path("leadPolicy"), "decisionHours", 1, 720);
        for (JsonNode row : rows(state, "routingRules")) { validateRule(row); }
        for (JsonNode row : rows(state, "routingQueue")) { validateQueue(row); }
        for (JsonNode row : rows(state, "savedCrmViews")) { validateView(row); }
        for (JsonNode row : rows(state, "crmTaskBatches")) { validateBatch(row); }
        for (JsonNode row : rows(state, "crmTasks")) { validateTask(row); }
        uniqueIds(state, "routingRules");
        uniqueIds(state, "routingQueue");
        uniqueIds(state, "savedCrmViews");
        uniqueIds(state, "crmTaskBatches");
        uniqueIds(state, "crmTasks");
    }

    private void validateRule(JsonNode row) {
        exactKeys(row, Set.of("id", "name", "campusId", "campaignId", "subject", "source", "ownerId",
            "priority", "active", "createdBy", "createdAt", "updatedAt", "version", "history"));
        requiredText(row, "id"); requiredText(row, "name"); requiredText(row, "campusId");
        requiredText(row, "ownerId"); requiredText(row, "createdBy");
        range(row, "priority", 0, 10000); positive(row, "version"); booleanValue(row, "active");
        optionalText(row, "campaignId"); optionalText(row, "subject"); source(row);
        instant(row, "createdAt"); instant(row, "updatedAt"); validateHistory(row.path("history"), false, true);
    }

    private void validateQueue(JsonNode row) {
        exactKeys(row, Set.of("id", "leadId", "campusId", "createdBy", "status", "reason", "createdAt",
            "updatedAt", "version", "ruleId", "completedAt"));
        requiredText(row, "id"); requiredText(row, "leadId"); requiredText(row, "createdBy");
        optionalText(row, "campusId"); optionalText(row, "ruleId"); requiredText(row, "reason");
        String status = requiredText(row, "status");
        if (!Set.of("pending", "completed").contains(status)
                || "completed".equals(status) && (!row.hasNonNull("ruleId") || !row.hasNonNull("completedAt"))) {
            throw invalid("路由队列状态缺少可映射的规则或完成时间");
        }
        instant(row, "createdAt"); instant(row, "updatedAt"); optionalInstant(row, "completedAt"); positive(row, "version");
    }

    private void validateView(JsonNode row) {
        exactKeys(row, Set.of("id", "ownerId", "createdBy", "name", "query", "status", "createdAt", "updatedAt", "version"));
        requiredText(row, "id"); requiredText(row, "ownerId"); requiredText(row, "createdBy");
        requiredText(row, "name"); positive(row, "version"); instant(row, "createdAt"); instant(row, "updatedAt");
        if (!Set.of("active", "archived").contains(requiredText(row, "status"))) { throw invalid("CRM视图状态未映射"); }
        validateViewQuery(row.path("query"));
    }

    private void validateBatch(JsonNode row) {
        exactKeys(row, Set.of("id", "createdBy", "title", "name", "taskType", "serviceType", "subject", "term",
            "campusId", "campusIds", "dueAt", "status", "note", "version", "campaignId", "strategy",
            "ownerIds", "assignmentCounts", "customerIds", "taskIds", "skipped", "history", "createdAt", "updatedAt"));
        requiredText(row, "id"); requiredText(row, "createdBy"); requiredText(row, "title");
        if (!Set.of("marketing", "service").contains(requiredText(row, "taskType"))) { throw invalid("CRM任务批次类型未映射"); }
        if (!Set.of("active", "completed", "cancelled").contains(requiredText(row, "status"))) { throw invalid("CRM任务批次状态未映射"); }
        optionalText(row, "serviceType"); optionalText(row, "subject"); optionalText(row, "term");
        optionalText(row, "campusId"); optionalText(row, "campaignId"); optionalText(row, "note");
        requiredText(row, "name"); requiredText(row, "strategy"); instant(row, "dueAt");
        instant(row, "createdAt"); instant(row, "updatedAt"); positive(row, "version");
        stringArray(row, "campusIds"); stringArray(row, "ownerIds"); stringArray(row, "customerIds");
        stringArray(row, "taskIds"); object(row, "assignmentCounts"); array(row, "skipped"); validateHistory(row.path("history"), true, true);
    }

    private void validateTask(JsonNode row) {
        exactKeys(row, Set.of("id", "batchId", "customerId", "campusId", "ownerId", "assigneeId", "createdBy",
            "taskType", "serviceType", "title", "subject", "term", "status", "dueAt", "note", "result",
            "resultNote", "opportunityId", "campaignId", "createdAt", "updatedAt", "version", "history",
            "assignmentHistory", "customerName", "owner"));
        requiredText(row, "id"); requiredText(row, "batchId"); requiredText(row, "customerId");
        requiredText(row, "taskType"); requiredText(row, "title"); requiredText(row, "status");
        if (!Set.of("marketing", "service").contains(row.path("taskType").asText())
                || !Set.of("unassigned", "pending", "in_progress", "completed", "cancelled").contains(row.path("status").asText())) {
            throw invalid("CRM任务类型或状态未映射");
        }
        optionalText(row, "campusId"); optionalText(row, "ownerId"); optionalText(row, "assigneeId");
        optionalText(row, "createdBy"); optionalText(row, "serviceType"); optionalText(row, "subject");
        optionalText(row, "term"); optionalText(row, "note"); optionalText(row, "result");
        optionalText(row, "resultNote"); optionalText(row, "opportunityId"); optionalText(row, "campaignId");
        optionalText(row, "customerName"); optionalText(row, "owner");
        if ("unassigned".equals(row.path("status").asText()) != !row.hasNonNull("ownerId")) {
            throw invalid("CRM任务负责人和未分配状态冲突");
        }
        if (row.hasNonNull("result") && !Set.of("qualified", "no_need", "unreachable", "invalid_contact", "completed", "refused").contains(row.path("result").asText())) {
            throw invalid("CRM任务结果未映射");
        }
        instant(row, "dueAt"); instant(row, "createdAt"); instant(row, "updatedAt"); positive(row, "version");
        validateHistory(row.path("history"), true, true); validateHistory(row.path("assignmentHistory"), false, false);
    }

    private void validateHistory(JsonNode history, boolean allowDisplayActor, boolean requireId) {
        if (!history.isArray()) { throw invalid("历史记录集合格式无效"); }
        for (JsonNode event : history) {
            Set<String> keys = allowDisplayActor
                ? Set.of("id", "actorId", "actor", "at", "kind", "detail", "from", "to", "strategy", "reason")
                : Set.of("id", "actorId", "at", "kind", "detail", "from", "to", "strategy", "reason");
            exactKeys(event, keys);
            if (requireId || event.hasNonNull("id")) { requiredText(event, "id"); }
            requiredText(event, "actorId"); instant(event, "at");
            optionalText(event, "actor"); optionalText(event, "kind"); validateHistoryDetail(event);
            optionalText(event, "strategy"); optionalText(event, "reason");
        }
    }

    /** Node history stores either display text or structured changed fields; never coerce an object to text. */
    private static void validateHistoryDetail(JsonNode event) {
        JsonNode detail = event.path("detail");
        if (detail.isObject()) { return; }
        optionalText(event, "detail");
    }

    /** Structured changes remain in the complete encrypted event; only textual detail is a reason projection. */
    private String historyReason(JsonNode event) {
        return event.path("detail").isObject() ? null : optionalEncrypted(event, "detail");
    }

    private void validateViewQuery(JsonNode query) {
        if (!query.isObject()) { throw invalid("CRM视图筛选格式无效"); }
        query.fieldNames().forEachRemaining(key -> {
            if (!VIEW_KEYS.contains(key)) { throw invalid("CRM视图包含不支持的筛选字段"); }
            JsonNode value = query.get(key);
            if (!value.isTextual() && !value.isIntegralNumber()) { throw invalid("CRM视图筛选值格式未映射"); }
        });
        String resource = query.path("resource").asText("leads");
        if (!Set.of("leads", "customers", "campaigns", "touchpoints", "consultations", "crmTaskBatches",
                "crmTasks", "opportunities", "orders", "receipts", "refunds", "enrollments", "serviceTickets",
                "attributionRequests", "importBatches", "routingRules", "routingQueue", "trialBookings",
                "formSubmissions", "leadReclaimRequests").contains(resource)) { throw invalid("CRM视图资源未映射"); }
        if (query.has("scope") && !Set.of("mine", "team").contains(query.path("scope").asText())) {
            throw invalid("CRM视图范围未映射");
        }
        if (query.has("dateField") && !Set.of("createdAt", "completedAt", "paidAt", "at", "receivedAt", "submittedAt")
                .contains(query.path("dateField").asText())) { throw invalid("CRM视图日期口径未映射"); }
        if (query.has("sort") && !Set.of("updatedAt:desc", "createdAt:desc", "createdAt:asc", "nextFollowAt:asc",
                "dueAt:asc", "name:asc").contains(query.path("sort").asText())) { throw invalid("CRM视图排序未映射"); }
    }

    private void requirePrerequisites(long revision, JsonNode state) {
        String catalogHash = LegacyCatalogRehearsal.hash(state.toString());
        var crmPayload = mapper.createObjectNode();
        for (String key : List.of("customers", "campaigns", "touchpoints", "leads", "opportunities")) {
            crmPayload.set(key, state.path(key).deepCopy());
        }
        String crmHash = LegacyCatalogRehearsal.hash(crmPayload.toString());
        requireCheckpoint("academic_catalog_v1", revision, catalogHash);
        requireCheckpoint("crm_households_v1", revision, crmHash);
    }

    private void requireCheckpoint(String phase, long revision, String hash) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint "
            + "WHERE phase=? AND source_revision=? AND source_hash=? AND deleted=0", Integer.class, phase, revision, hash);
        if (count == null || count != 1) { throw invalid("前置迁移来源与当前快照不一致：" + phase); }
    }

    private void importRules(JsonNode payload) {
        for (JsonNode row : rows(payload, "routingRules")) {
            jdbc.update("INSERT INTO core_crm_routing_rule(public_id,name,campus_id,activity_id,subject,source,owner_id,priority,active,version,created_by,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", text(row, "id"), text(row, "name"), id(CAMPUS_TABLE, text(row, "campusId")),
                optionalId(ACTIVITY_TABLE, row, "campaignId"), optionalText(row, "subject"), mappedSource(row),
                id(PRINCIPAL_TABLE, text(row, "ownerId")), integer(row, "priority"), row.path("active").booleanValue(),
                positive(row, "version"), id(PRINCIPAL_TABLE, text(row, "createdBy")), date(row, "createdAt"), date(row, "updatedAt"));
            long ruleId = id(RULE_TABLE, text(row, "id"));
            JsonNode history = array(row, "history");
            if (history.isEmpty()) {
                insertRuleEvent(new RuleEventWrite(text(row, "id") + "_migration_snapshot", null, ruleId,
                    text(row, "createdBy"), "import", date(row, "createdAt"), null, row.toString()));
            } else {
                for (JsonNode event : history) {
                    insertRuleEvent(new RuleEventWrite(text(event, "id"), text(event, "id"), ruleId,
                        text(event, "actorId"), ruleAction(text(event, "kind")), date(event, "at"),
                        historyReason(event), event.toString()));
                }
            }
        }
    }

    private void insertRuleEvent(RuleEventWrite event) {
        jdbc.update("INSERT INTO core_crm_routing_rule_event(public_id,source_event_id,rule_id,actor_id,action,occurred_at,reason_cipher,changes_cipher) "
            + "VALUES(?,?,?,?,?,?,?,?)", event.publicId(), event.sourceEventId(), event.ruleId(),
            id(PRINCIPAL_TABLE, event.actorPublicId()), event.action(), event.occurredAt(), event.reasonCipher(),
            cipher.encrypt(event.changes()));
    }

    private void importQueue(JsonNode payload) {
        for (JsonNode row : rows(payload, "routingQueue")) {
            boolean completed = "completed".equals(text(row, "status"));
            Long rule = completed ? id(Set.of("core_crm_routing_rule"), text(row, "ruleId")) : null;
            jdbc.update("INSERT INTO core_crm_routing_queue(public_id,lead_id,campus_id,created_by,status,rule_id,reason_cipher,version,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?)", text(row, "id"), id(LEAD_TABLE, text(row, "leadId")),
                optionalId(CAMPUS_TABLE, row, "campusId"), id(PRINCIPAL_TABLE, text(row, "createdBy")),
                completed ? "completed" : "pending", rule, cipher.encrypt(text(row, "reason")), positive(row, "version"),
                date(row, "createdAt"), date(row, "updatedAt"));
            long queueId = id(Set.of("core_crm_routing_queue"), text(row, "id"));
            Timestamp occurredAt = completed ? date(row, "completedAt") : date(row, "createdAt");
            jdbc.update("INSERT INTO core_crm_routing_event(public_id,queue_id,actor_id,event_type,rule_id,reason_cipher,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?)", text(row, "id") + "_migration_event", queueId,
                id(PRINCIPAL_TABLE, text(row, "createdBy")), completed ? "matched" : "unmatched", rule,
                cipher.encrypt(text(row, "reason")), occurredAt, occurredAt);
        }
    }

    private void importViews(JsonNode payload) {
        for (JsonNode row : rows(payload, "savedCrmViews")) {
            boolean archived = "archived".equals(text(row, "status"));
            jdbc.update("INSERT INTO core_crm_saved_view(public_id,principal_id,resource_type,name,filters_json,version,deleted,created_at,updated_at) "
                + "VALUES(?,?,?,?,CAST(? AS JSON),?,?,?,?)", text(row, "id"), id(PRINCIPAL_TABLE, text(row, "ownerId")),
                resource(row.path("query").path("resource").asText("leads")), text(row, "name"), row.path("query").toString(),
                positive(row, "version"), archived, date(row, "createdAt"), date(row, "updatedAt"));
        }
    }

    private void importTaskBatches(JsonNode payload) {
        for (JsonNode row : rows(payload, "crmTaskBatches")) {
            jdbc.update("INSERT INTO core_crm_task_batch(public_id,created_by,title,task_type,service_type,subject,term,campus_id,activity_id,due_at,status,note_cipher,skipped_count,version,delivery_mode,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'legacy',?,?)", text(row, "id"), id(PRINCIPAL_TABLE, text(row, "createdBy")),
                text(row, "title"), text(row, "taskType"), optionalText(row, "serviceType"), optionalText(row, "subject"),
                optionalText(row, "term"), optionalId(CAMPUS_TABLE, row, "campusId"), optionalId(ACTIVITY_TABLE, row, "campaignId"),
                date(row, "dueAt"), text(row, "status"), optionalEncrypted(row, "note"), array(row, "skipped").size(),
                positive(row, "version"), date(row, "createdAt"), date(row, "updatedAt"));
            long batchId = id(BATCH_TABLE, text(row, "id"));
            routingSnapshot(row, batchId);
            for (JsonNode event : array(row, "history")) { routingHistory(row, event, batchId); }
        }
    }

    private void routingSnapshot(JsonNode batch, long batchId) {
        jdbc.update("INSERT INTO core_crm_task_routing_event(public_id,batch_id,actor_id,action,occurred_at,reason_cipher,changes_cipher) "
            + "VALUES(?,?,?,'create',?,?,?)", text(batch, "id") + "_migration_snapshot", batchId,
            id(PRINCIPAL_TABLE, text(batch, "createdBy")), date(batch, "createdAt"), optionalEncrypted(batch, "note"),
            cipher.encrypt(batch.toString()));
    }

    private void routingHistory(JsonNode batch, JsonNode event, long batchId) {
        String kind = optionalText(event, "kind");
        String action = switch (kind == null ? "" : kind) {
            case "create", "创建", "新建" -> "create";
            case "cancel", "取消" -> "cancel";
            case "recall", "回收" -> "recall";
            case "transfer", "转派" -> "transfer";
            case "schedule", "排期" -> "schedule";
            case "allocation", "分配", "批量分配任务" -> "allocation";
            default -> throw invalid("任务批次历史动作未映射");
        };
        jdbc.update("INSERT INTO core_crm_task_routing_event(public_id,batch_id,actor_id,action,occurred_at,reason_cipher,changes_cipher) "
            + "VALUES(?,?,?,?,?,?,?)", text(event, "id"), batchId, id(PRINCIPAL_TABLE, text(event, "actorId")), action,
            date(event, "at"), historyReason(event), cipher.encrypt(event.toString()));
    }

    private void importTasks(JsonNode payload) {
        for (JsonNode row : rows(payload, "crmTasks")) { importTask(row); }
    }

    private void importTask(JsonNode row) {
        Long owner = optionalId(PRINCIPAL_TABLE, row, "ownerId");
        String status = text(row, "status");
        Timestamp completed = status.equals("completed") ? lastEventAt(row, "completed") : null;
        Timestamp cancelled = status.equals("cancelled") ? lastEventAt(row, "cancelled") : null;
        jdbc.update("INSERT INTO core_crm_task(public_id,batch_id,customer_id,campus_id,owner_id,task_type,service_type,title,subject,term,status,due_at,note_cipher,result,result_note_cipher,opportunity_id,activity_id,completed_at,cancelled_at,version,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", text(row, "id"), id(BATCH_TABLE, text(row, "batchId")),
            id(CUSTOMER_TABLE, text(row, "customerId")), optionalId(CAMPUS_TABLE, row, "campusId"), owner,
            text(row, "taskType"), optionalText(row, "serviceType"), text(row, "title"), optionalText(row, "subject"),
            optionalText(row, "term"), status, date(row, "dueAt"), optionalEncrypted(row, "note"), optionalText(row, "result"),
            optionalEncrypted(row, "resultNote"), optionalId(OPPORTUNITY_TABLE, row, "opportunityId"),
            optionalId(ACTIVITY_TABLE, row, "campaignId"), completed, cancelled, positive(row, "version"),
            date(row, "createdAt"), date(row, "updatedAt"));
        long taskId = id(TASK_TABLE, text(row, "id"));
        for (JsonNode event : array(row, "history")) { taskHistory(row, event, taskId); }
        JsonNode assignments = array(row, "assignmentHistory");
        for (int index = 0; index < assignments.size(); index++) { assignmentHistory(row, assignments.get(index), index); }
    }

    private void taskHistory(JsonNode task, JsonNode event, long taskId) {
        String kind = optionalText(event, "kind");
        String eventType = switch (kind == null ? "" : kind) {
            case "", "created", "创建", "新建" -> task.path("createdAt").asText().equals(event.path("at").asText())
                ? "created" : "updated";
            case "completed", "完成", "完成任务" -> "completed";
            case "cancelled", "取消", "取消任务" -> "cancelled";
            case "updated", "更新", "任务分配" -> "updated";
            default -> throw invalid("CRM任务历史动作未映射");
        };
        jdbc.update("INSERT INTO core_crm_task_event(public_id,task_id,actor_id,event_type,occurred_at,detail_cipher,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?)", text(event, "id"), taskId, id(PRINCIPAL_TABLE, text(event, "actorId")),
            eventType, date(event, "at"), cipher.encrypt(event.toString()), date(event, "at"), date(event, "at"));
    }

    private void assignmentHistory(JsonNode task, JsonNode event, int index) {
        long batchId = id(BATCH_TABLE, text(task, "batchId"));
        String eventId = event.hasNonNull("id") ? text(event, "id") + "_assignment"
            : "LEGACY_TASK_ASSIGN_" + LegacyCatalogRehearsal.hash(text(task, "id") + "\0" + index);
        jdbc.update("INSERT INTO core_crm_task_routing_event(public_id,batch_id,actor_id,action,occurred_at,reason_cipher,changes_cipher) "
            + "VALUES(?,?,?,'allocation',?,?,?)", eventId, batchId,
            id(PRINCIPAL_TABLE, text(event, "actorId")), date(event, "at"), optionalEncrypted(event, "reason"),
            cipher.encrypt(event.toString()));
    }

    private void importPolicy(JsonNode payload) {
        JsonNode policy = payload.path("leadPolicy");
        int updated = jdbc.update("UPDATE core_crm_lead_policy SET reserve_days=?,reclaim_days=?,first_follow_hours=?,decision_hours=?,version=1 "
            + "WHERE singleton=1 AND deleted=0", integer(policy, "reserveDays"), integer(policy, "reclaimDays"),
            integer(policy, "firstFollowHours"), integer(policy, "decisionHours"));
        if (updated != 1) { throw invalid("全局线索策略单例缺失或重复"); }
    }

    private void reconcile(JsonNode payload) {
        for (String collection : List.of("routingRules", "routingQueue", "savedCrmViews", "crmTaskBatches", "crmTasks")) {
            Set<String> seen = new HashSet<>();
            for (JsonNode row : rows(payload, collection)) {
                String id = text(row, "id");
                if (!seen.add(id) || id(Set.of(table(collection)), id) < 1) { throw invalid("CRM运营来源对账失败：" + collection); }
            }
        }
        for (JsonNode task : rows(payload, "crmTasks")) {
            Long actual = jdbc.queryForObject("SELECT batch_id FROM core_crm_task WHERE public_id=?", Long.class, text(task, "id"));
            if (actual == null || actual != id(BATCH_TABLE, text(task, "batchId"))) { throw invalid("CRM任务批次关系不一致"); }
        }
    }

    private int writeCount(JsonNode state) {
        long count = 1L + 1L;
        count += rows(state, "routingRules").size();
        for (JsonNode rule : rows(state, "routingRules")) {
            count += Math.max(1, array(rule, "history").size());
        }
        count += 2L * rows(state, "routingQueue").size();
        count += rows(state, "savedCrmViews").size();
        count += 2L * rows(state, "crmTaskBatches").size();
        count += rows(state, "crmTasks").size();
        for (JsonNode batch : rows(state, "crmTaskBatches")) {
            count += array(batch, "history").size();
        }
        for (JsonNode task : rows(state, "crmTasks")) {
            count += array(task, "history").size() + array(task, "assignmentHistory").size();
        }
        if (count >= 1000) { throw invalid("CRM运营迁移阶段超过单事务行数限制"); }
        return Math.toIntExact(count);
    }

    private int sourceRows(JsonNode payload) {
        int total = 0;
        for (String key : List.of("routingRules", "routingQueue", "savedCrmViews", "crmTaskBatches", "crmTasks")) {
            total = Math.addExact(total, array(payload, key).size());
        }
        return Math.addExact(total, 1);
    }

    private Timestamp lastEventAt(JsonNode task, String marker) {
        Timestamp result = null;
        for (JsonNode event : array(task, "history")) {
            String kind = optionalText(event, "kind");
            if (kind != null && kind.contains(marker.equals("completed") ? "完成" : "取消")) { result = date(event, "at"); }
        }
        return result;
    }

    private String mappedSource(JsonNode row) {
        String value = optionalText(row, "source");
        if (value == null) { return null; }
        if (value.length() > 120) { throw invalid("路由规则来源超过长度限制"); }
        return value;
    }

    private String ruleAction(String kind) {
        return switch (kind) {
            case "create", "created", "规则创建", "创建规则" -> "create";
            case "update", "规则更新", "更新规则" -> "update";
            default -> throw invalid("路由规则历史动作未映射");
        };
    }

    private String resource(String value) {
        if (!Set.of("leads", "customers", "campaigns", "touchpoints", "consultations", "crmTaskBatches",
                "crmTasks", "opportunities", "orders", "receipts", "refunds", "enrollments", "serviceTickets",
                "attributionRequests", "importBatches", "routingRules", "routingQueue", "trialBookings",
                "formSubmissions", "leadReclaimRequests").contains(value)) { throw invalid("CRM视图资源未映射"); }
        return switch (value) {
            case "crmTasks" -> "tasks";
            case "crmTaskBatches" -> "taskBatches";
            default -> value;
        };
    }

    private String table(String key) {
        return switch (key) {
            case "routingRules" -> "core_crm_routing_rule";
            case "routingQueue" -> "core_crm_routing_queue";
            case "savedCrmViews" -> "core_crm_saved_view";
            case "crmTaskBatches" -> "core_crm_task_batch";
            case "crmTasks" -> "core_crm_task";
            default -> throw invalid("未知CRM运营集合");
        };
    }

    private long id(Set<String> allowed, String publicId) {
        if (allowed.size() != 1) { throw invalid("迁移关系目标无效"); }
        String table = allowed.iterator().next();
        if (!Set.of("core_principal", "core_campus", "core_crm_lead", "core_activity", "core_customer",
                "core_opportunity", "core_crm_task_batch", "core_crm_task", "core_crm_routing_rule",
                "core_crm_routing_queue", "core_crm_saved_view").contains(table)) { throw invalid("迁移关系目标未允许"); }
        List<Long> ids = jdbc.query("SELECT id FROM " + table + " WHERE public_id=? AND deleted=0",
            (rs, row) -> rs.getLong(1), publicId);
        if (ids.size() != 1) { throw invalid("CRM运营关联尚未迁移或ID不唯一"); }
        return ids.get(0);
    }

    private Long optionalId(Set<String> allowed, JsonNode row, String field) {
        return row.hasNonNull(field) && !row.path(field).asText().isBlank()
            ? id(allowed, requiredText(row, field)) : null;
    }

    private static List<JsonNode> rows(JsonNode root, String key) {
        java.util.ArrayList<JsonNode> result = new java.util.ArrayList<>();
        array(root, key).forEach(result::add);
        return result;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode array(JsonNode root, String key) {
        JsonNode value = root.path(key);
        if (!value.isArray()) { throw invalid("迁移集合格式无效：" + key); }
        return (com.fasterxml.jackson.databind.node.ArrayNode) value;
    }

    private static void exactKeys(JsonNode row, Set<String> allowed) {
        if (!row.isObject()) { throw invalid("迁移对象格式无效"); }
        row.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) { throw invalid("迁移字段未映射：" + key); } });
    }

    private static String requiredText(JsonNode row, String key) {
        JsonNode value = row.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 120) {
            throw invalid("迁移文本字段无效：" + key);
        }
        return value.asText();
    }

    private static String text(JsonNode row, String key) { return requiredText(row, key); }
    private static String optionalText(JsonNode row, String key) {
        JsonNode value = row.path(key);
        if (!row.has(key) || value.isNull()) { return null; }
        if (!value.isTextual() || value.asText().length() > 5000) { throw invalid("迁移可选文本字段无效：" + key); }
        return value.asText().isBlank() ? null : value.asText();
    }
    private static void range(JsonNode row, String key, int min, int max) {
        JsonNode value = row.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw invalid("迁移整数范围无效：" + key);
        }
    }
    private static int integer(JsonNode row, String key) { range(row, key, Integer.MIN_VALUE, Integer.MAX_VALUE); return row.path(key).intValue(); }
    private static long positive(JsonNode row, String key) {
        JsonNode value = row.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) { throw invalid("迁移版本无效：" + key); }
        return value.longValue();
    }
    private static Timestamp date(JsonNode row, String key) { return Timestamp.from(Instant.parse(requiredText(row, key))); }
    private static void instant(JsonNode row, String key) { date(row, key); }
    private static void optionalInstant(JsonNode row, String key) { if (row.hasNonNull(key)) { instant(row, key); } }
    private static boolean booleanValue(JsonNode row, String key) {
        if (!row.path(key).isBoolean()) { throw invalid("迁移状态值无效：" + key); }
        return row.path(key).booleanValue();
    }
    private static void source(JsonNode row) { optionalText(row, "source"); }
    private static void object(JsonNode row, String key) { if (!row.path(key).isObject()) { throw invalid("迁移对象格式无效：" + key); } }
    private static void stringArray(JsonNode row, String key) {
        JsonNode values = row.path(key);
        if (!values.isArray()) { throw invalid("迁移集合格式无效：" + key); }
        for (JsonNode item : values) { if (!item.isTextual() || item.asText().isBlank()) { throw invalid("迁移集合元素无效：" + key); } }
    }
    private static void uniqueIds(JsonNode state, String key) {
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rows(state, key)) { if (!ids.add(requiredText(row, "id"))) { throw invalid("CRM运营集合ID重复：" + key); } }
    }
    private String optionalEncrypted(JsonNode row, String key) {
        String value = optionalText(row, key);
        return value == null ? null : cipher.encrypt(value);
    }
    private record Checkpoint(long revision, String hash, int rows) { }
    private record RuleEventWrite(String publicId, String sourceEventId, long ruleId, String actorPublicId,
            String action, Timestamp occurredAt, String reasonCipher, String changes) { }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
