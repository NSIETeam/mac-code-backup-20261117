package cn.siyue.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit offline runner for an inactive, DBA-authorized local candidate schema. */
public final class LegacyMigrationCandidateCli {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyMigrationCandidateCli.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Phase> PHASES = List.of(new Phase("catalog", "academic_catalog_v1"),
        new Phase("campus-configuration", "campus_configuration_v1"),
        new Phase("system-settings", "system_settings_v1"), new Phase("crm", "crm_households_v1"),
        new Phase("service-history", "crm_service_history_v1"), new Phase("financial", "academic_financial_v1"),
        new Phase("forms-trials-coupons", "form_trials_v1"),
        new Phase("schedule-history", "academic_schedule_history_v1"),
        new Phase("crm-import-batches", "crm_import_batches_v1"),
        new Phase("crm-attribution-requests", "crm_attribution_requests_v1"),
        new Phase("crm-operations", "crm_operations_v1"),
        new Phase("campaign-acquisition", "campaign_acquisition_v1"),
        new Phase("ui-annotations", "auth_ui_annotations_v1"),
        new Phase("lead-reclaim-requests", "crm_lead_reclaim_requests_v1"));
    private static final List<String> DOMAIN_TABLES = List.of("core_campus", "core_course", "core_lesson",
        "core_class", "core_session", "core_student", "core_student_name_index", "core_student_identity_index",
        "core_customer", "core_customer_contact", "core_customer_student", "core_activity",
        "core_activity_member", "core_customer_touchpoint", "core_crm_lead", "core_crm_lead_collector",
        "core_crm_lead_decision_event", "core_crm_lead_reclaim_request", "core_crm_lead_reclaim_event",
        "core_opportunity", "core_communication", "core_crm_consultation",
        "core_crm_service_ticket", "core_crm_order", "core_crm_order_class_terms", "core_crm_receipt",
        "core_crm_refund", "core_commerce_checkout", "core_commerce_checkout_buyer",
        "core_commerce_checkout_campus", "core_commerce_checkout_line", "core_commerce_checkout_event", "core_enrollment",
        "core_enrollment_lesson", "core_attendance", "core_consumption", "core_crm_refund_request",
        "core_crm_form", "core_crm_form_revision", "core_crm_form_submission", "core_trial_booking",
        "core_coupon_offer", "core_coupon_offer_course", "core_coupon_claim", "core_customer_name_index",
        "core_customer_contact_index", "core_campus_room", "core_campus_academic_option", "core_class_event",
        "core_class_fee", "core_class_location_history", "core_crm_consultation_history",
        "core_crm_consultation_notice", "core_crm_lead_event", "core_crm_lead_followup",
        "core_crm_lead_conversion_snapshot", "core_crm_routing_event", "core_crm_routing_queue",
        "core_crm_routing_rule", "core_crm_saved_view", "core_crm_task", "core_crm_task_batch",
        "core_crm_task_event", "core_crm_task_routing_event", "core_crm_task_source",
        "core_crm_attribution_request", "core_crm_attribution_adjustment", "core_crm_import_batch",
        "core_crm_import_row", "core_crm_refund_event",
        "core_enrollment_exit_event", "core_enrollment_lesson_delivery", "core_enrollment_placement",
        "core_enrollment_transfer", "core_session_adjustment", "core_session_adjustment_event",
        "core_session_reschedule_request", "core_session_reschedule_event", "core_session_teaching_record",
        "core_form_collection_settings", "core_form_trial_config", "core_form_trial_slot",
        "core_form_reward_config", "core_commerce_product", "core_commerce_product_class",
        "core_commerce_promotion", "core_commerce_promotion_class",
        "core_activity_form", "core_staff_profile", "core_ui_annotation", "core_academic_dictionary_value",
        "core_attendance_correction", "core_course_publication",
        "core_course_publication_lesson", "core_crm_attribution_request_event", "core_crm_form_public_rate",
        "core_crm_form_submission_event", "core_crm_service_ticket_event",
        "core_crm_lead_policy_action", "core_crm_routing_rule_event",
        "core_opportunity_event", "core_opportunity_judgment", "core_opportunity_need_event",
        "core_opportunity_notification", "core_opportunity_option", "core_public_store_idempotency",
        "core_public_storefront_config", "core_public_store_rate", "core_session_event", "core_session_teacher_event",
        "core_student_identifier", "core_student_identifier_index", "core_campaign_promotion_link",
        "core_form_acquisition", "core_touchpoint_acquisition",
        "core_system_setting");
    private static final Set<String> IMPORTED_ARRAYS = Set.of("campuses", "courses", "classes", "students",
        "customers", "campaigns", "touchpoints", "leads", "opportunities", "studentCommunications", "staff",
        "consultations", "serviceTickets", "orders", "receipts", "attendance", "consumptions", "refunds",
        "enrollments", "forms", "rewardClaims", "trialBookings", "commerceCheckouts", "shelved",
        "hiddenShelved", "sessionAdjustments", "rescheduleRequests", "attributionRequests", "importBatches",
        "routingRules", "routingQueue", "savedCrmViews", "crmTaskBatches", "crmTasks", "leadReclaimRequests");
    private static final Set<String> IMPORTED_OBJECTS = Set.of("activities", "settings", "dictionary", "decor",
        "leadPolicy", "authFeedback");

    private LegacyMigrationCandidateCli() { }

    public static void main(String[] args) {
        int exit = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (exit != 0) { System.exit(exit); }
    }

    static int run(String[] args) {
        return run(args, new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
    }

    static int run(String[] args, PrintWriter out, PrintWriter err) {
        String stage = "OPTIONS";
        String traceId = UUID.randomUUID().toString();
        org.slf4j.MDC.put("traceId", traceId);
        try {
            Options options = Options.parse(args);
            if (options.help()) { out.println(Options.usage()); out.flush(); return 0; }
            stage = "SOURCE_READ";
            byte[] sourceBytes = readSource(options.snapshot());
            String actualHash = rawHash(sourceBytes);
            if (!actualHash.equals(options.sourceSha256())) { return blocked("source hash mismatch", err, traceId); }
            stage = "SOURCE_VALIDATE";
            JsonNode envelope = MAPPER.readTree(sourceBytes);
            validateEnvelope(envelope);
            List<String> blockers = sourceBlockers(envelope);
            if (!blockers.isEmpty()) { reportBlocked(options, blockers, out); return 2; }
            stage = "DATABASE_CONFIG";
            Connection connection = connection(options.targetSchema());
            DriverManagerDataSource dataSource = new DriverManagerDataSource(connection.url(), connection.user(),
                connection.password());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            stage = "GATE_AUTHORIZATION";
            LegacyMigrationTargetAuthorization.AuthorizationRequest authRequest =
                new LegacyMigrationTargetAuthorization.AuthorizationRequest(options.targetSchema(), actualHash,
                    options.checkpoint(), envelope, false);
            LegacyMigrationTargetAuthorization authorization = LegacyMigrationTargetAuthorization.authorize(jdbc,
                authRequest);
            stage = "SCHEMA_PREFLIGHT";
            verifySchemaIsCurrent(dataSource);
            stage = "IDENTITY_PREFLIGHT";
            verifyIdentityPrerequisites(jdbc, envelope);
            stage = "CANDIDATE_BOUNDARY";
            verifyCandidateDataBoundary(jdbc, envelope.path("revision").longValue());
            if (!options.execute()) { reportPlan(options, false, out); return 0; }
            stage = "EXECUTE";
            execute(dataSource, jdbc, envelope, new ExecutionRequest(authorization, options, out));
            reportPlan(options, true, out);
            return 0;
        } catch (Exception error) {
            String failedStage = error instanceof CandidateStageFailure failure ? failure.stage() : stage;
            Throwable cause = error instanceof CandidateStageFailure failure && failure.getCause() != null
                ? failure.getCause() : error;
            LOGGER.error("Candidate migration failed: stage={} errorType={} traceId={}", failedStage,
                cause.getClass().getSimpleName(), traceId);
            err.println("failureStage=" + failedStage + " errorType=" + cause.getClass().getSimpleName()
                + " traceId=" + traceId);
            err.println("迁移未完成；候选库保持未激活。详情已隐藏，需在受控运维环境排查。");
            err.flush();
            return 2;
        } finally {
            org.slf4j.MDC.remove("traceId");
        }
    }

    private static byte[] readSource(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) < 1 || Files.size(path) > 128L * 1024 * 1024) {
            throw new IllegalArgumentException("迁移快照文件无效");
        }
        return Files.readAllBytes(path);
    }

    private static void validateEnvelope(JsonNode envelope) {
        if (!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())
                || !envelope.path("revision").isIntegralNumber() || envelope.path("revision").longValue() < 0
                || !envelope.path("state").isObject()) { throw new IllegalArgumentException("快照格式不支持"); }
        LegacyCatalogSourcePreflight.requireMappedHistory(envelope.path("state"));
    }

    static List<String> sourceBlockers(JsonNode envelope) {
        List<String> blockers = new ArrayList<>();
        if (!envelope.path("auditProvenance").isArray() || envelope.path("auditProvenance").isEmpty()) {
            blockers.add("source auditProvenance missing or empty");
        }
        if (envelope.path("state").path("rewardClaims").isArray()
                && !envelope.path("state").path("rewardClaims").isEmpty()) {
            String key = System.getenv("SIYUE_LEGACY_REWARD_KEY_HEX");
            if (key == null || !key.matches("[a-fA-F0-9]{64}")) { blockers.add("legacy reward decryption key unavailable"); }
        }
        addUnsupportedCollections(blockers, envelope.path("state"));
        return blockers;
    }

    private static void addUnsupportedCollections(List<String> blockers, JsonNode state) {
        state.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isArray() && !value.isEmpty() && !IMPORTED_ARRAYS.contains(entry.getKey())) {
                blockers.add("unsupported source collection: " + entry.getKey());
            }
            if (value.isObject() && !value.isEmpty() && !IMPORTED_OBJECTS.contains(entry.getKey())) {
                blockers.add("unsupported source object: " + entry.getKey());
            }
        });
        addUnsupportedObjectKeys(blockers, state.path("dictionary"), Set.of("subjects", "grades", "terms", "levels",
            "classTypes"), "dictionary");
        addUnsupportedObjectKeys(blockers, state.path("activities"), Set.of("coupon", "group", "joint"),
            "activities");
    }

    private static void addUnsupportedObjectKeys(List<String> blockers, JsonNode source, Set<String> supported,
            String label) {
        if (!source.isObject()) { return; }
        source.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isEmpty() && !supported.contains(entry.getKey())) {
                blockers.add("unsupported source collection: " + label + "." + entry.getKey());
            }
        });
    }

    private static Connection connection(String targetSchema) {
        String url = requiredEnv("SIYUE_MYSQL_URL");
        String user = requiredEnv("SIYUE_MYSQL_USER");
        String password = requiredEnv("SIYUE_MYSQL_PASSWORD");
        URI parsed = URI.create(url.substring("jdbc:".length()));
        if (!"mysql".equals(parsed.getScheme()) || !Set.of("127.0.0.1", "localhost", "::1").contains(parsed.getHost())
                || !("/" + targetSchema).equals(parsed.getPath()) || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("连接仅可指向显式的本机候选schema");
        }
        return new Connection(url, user, password);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("迁移连接配置缺失"); }
        return value;
    }

    private static void verifySchemaIsCurrent(DriverManagerDataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(true).load();
        flyway.validate();
        var info = flyway.info();
        if (info.current() == null || info.pending().length != 0) {
            throw new IllegalStateException("候选schema未完成Flyway迁移；runner不会执行DDL");
        }
    }

    static void verifyIdentityPrerequisites(JdbcTemplate jdbc, JsonNode envelope) {
        Set<String> identities = requiredPrincipals(envelope.path("state"), envelope.path("auditProvenance"));
        for (String identity : identities) {
            Integer rows = jdbc.queryForObject("SELECT COUNT(id) FROM core_principal WHERE public_id=? AND deleted=0 "
                + "AND active=0 AND oidc_issuer='https://migration-rehearsal.invalid' AND oidc_subject=public_id",
                Integer.class, identity);
            if (rows == null || rows != 1) { throw new IllegalStateException("迁移身份依赖未完整准备"); }
        }
    }

    static Set<String> requiredPrincipals(JsonNode state, JsonNode audit) {
        Set<String> identities = new LinkedHashSet<>();
        for (JsonNode collection : state) {
            if (!collection.isArray()) { continue; }
            for (JsonNode row : collection) { addRowPrincipals(identities, row); }
        }
        addNestedPrincipals(identities, state);
        for (JsonNode row : state.path("authFeedback").path("annotations")) {
            addIdentity(identities, row.path("createdBy"));
        }
        for (JsonNode event : state.path("authFeedback").path("audit")) {
            addIdentity(identities, event.path("actorId"));
        }
        for (JsonNode profile : state.path("staff")) { addIdentity(identities, profile.path("id")); }
        if (audit.isArray()) {
            addAuditPrincipals(identities, audit);
        }
        identities.removeIf(String::isBlank);
        return identities;
    }

    private static void addRowPrincipals(Set<String> identities, JsonNode row) {
        for (String field : List.of("ownerId", "assigneeId", "createdBy", "teacherId", "confirmedBy",
                "recordedBy", "actorId", "publisherId", "reviewedBy", "applicantId", "waivedBy", "registrarId")) {
            addIdentity(identities, row.path(field));
        }
        addIdentity(identities, row.path("original").path("teacherId"));
        for (JsonNode event : row.path("history")) { addIdentity(identities, event.path("actorId")); }
        for (String field : List.of("participantIds", "promoterIds", "collectedByIds")) {
            for (JsonNode id : row.path(field)) { addIdentity(identities, id); }
        }
        for (JsonNode link : row.path("promotionLinks")) {
            addIdentity(identities, link.path("promoterId"));
            addIdentity(identities, link.path("createdBy"));
        }
        for (JsonNode session : row.path("schedule")) { addIdentity(identities, session.path("teacherId")); }
        for (JsonNode submission : row.path("submissions")) { addSubmissionPrincipals(identities, submission); }
    }

    private static void addSubmissionPrincipals(Set<String> identities, JsonNode submission) {
        for (String field : List.of("publisherId", "reviewedBy", "submittedBy")) {
            if (!"public".equals(submission.path(field).asText())) { addIdentity(identities, submission.path(field)); }
        }
        addIdentity(identities, submission.path("acquisitionSnapshot").path("promoterId"));
    }

    private static void addNestedPrincipals(Set<String> identities, JsonNode state) {
        for (JsonNode offer : state.path("activities").path("coupon")) { addIdentity(identities, offer.path("createdBy")); }
        addIdentity(identities, state.path("decor").path("updatedBy"));
    }

    private static void addAuditPrincipals(Set<String> identities, JsonNode audit) {
        Set<String> migrationActions = Set.of("order.attribution.request", "crm.import.commit", "makeup.assign",
            "makeup.cancel", "makeup.waive", "session.attendance", "session.attendance.exceptions",
            "session.reschedule.request", "session.reschedule.review", "campaign.linkForm", "campaign.create",
            "campaign.update", "campaign.promotionLink.create");
        for (JsonNode event : audit) {
            if (migrationActions.contains(event.path("action").asText())) {
                addIdentity(identities, event.path("actorId"));
            }
            if ("enrollment.withdraw".equals(event.path("action").asText()) && hasRefundChange(event)) {
                addIdentity(identities, event.path("actorId"));
            }
        }
    }

    private static boolean hasRefundChange(JsonNode event) {
        for (JsonNode change : event.path("changes")) {
            if ("refunds".equals(change.path("collection").asText())) { return true; }
        }
        return false;
    }

    private static void addIdentity(Set<String> identities, JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) { identities.add(node.asText()); }
    }

    static void verifyCandidateDataBoundary(JdbcTemplate jdbc, long revision) {
        List<CheckpointRow> checkpoints = jdbc.query("SELECT phase,source_revision,source_hash,imported_rows "
            + "FROM core_migration_checkpoint ORDER BY id", (rs, row) -> new CheckpointRow(rs.getString(1),
                rs.getLong(2), rs.getString(3), rs.getInt(4)));
        if (checkpoints.isEmpty()) {
            for (String table : DOMAIN_TABLES) {
                Integer rows = jdbc.queryForObject("SELECT COUNT(id) FROM " + table, Integer.class);
                if (rows == null || rows != 0) { throw new IllegalStateException("候选库含未归档业务数据"); }
            }
            return;
        }
        if (checkpoints.size() > PHASES.size()) { throw new IllegalStateException("候选库含过量迁移检查点"); }
        for (int index = 0; index < checkpoints.size(); index++) {
            CheckpointRow actual = checkpoints.get(index);
            if (!PHASES.get(index).checkpoint().equals(actual.phase()) || actual.revision() != revision
                    || !actual.hash().matches("[a-f0-9]{64}") || actual.importedRows() < 0) {
                throw new IllegalStateException("候选库检查点不属于当前来源或阶段顺序");
            }
        }
    }

    private static void execute(DriverManagerDataSource dataSource, JdbcTemplate jdbc, JsonNode envelope,
            ExecutionRequest execution) {
        LegacyMigrationTargetAuthorization authorization = execution.authorization();
        Options options = execution.options();
        PrintWriter out = execution.output();
        byte[] keyring = requiredEnv("SIYUE_PII_KEYS_JSON").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String activeKey = requiredEnv("SIYUE_PII_ACTIVE_KEY_ID");
        PiiCipher cipher = new PiiCipher(new String(keyring, java.nio.charset.StandardCharsets.UTF_8), activeKey,
            MAPPER);
        java.util.Arrays.fill(keyring, (byte) 0);
        var manager = new DataSourceTransactionManager(dataSource);
        try (java.sql.Connection lockConnection = dataSource.getConnection()) {
            acquireMigrationLock(lockConnection, options.targetSchema());
            boolean started = false;
            boolean completed = false;
            try {
                setGateStatus(jdbc, authorization, "migration_running");
                started = true;
                LegacyMigrationTargetAuthorization.AuthorizationRequest lockedRequest =
                    new LegacyMigrationTargetAuthorization.AuthorizationRequest(options.targetSchema(),
                        authorization.sourceSha256(), authorization.checkpoint(), envelope, true);
                LegacyMigrationTargetAuthorization locked = LegacyMigrationTargetAuthorization.authorize(jdbc,
                    lockedRequest);
                verifyCandidateDataBoundary(jdbc, envelope.path("revision").longValue());
                RunContext context = new RunContext(jdbc, envelope, locked, options, out);
                runPhases(jdbc, cipher, LegacyMigrationPhaseTransactions.create(manager), context);
                runStage("RECONCILE_COUNTS", () -> {
                    reconcileCounts(jdbc, envelope.path("state"));
                    reconcileReclaimAudit(jdbc, envelope);
                });
                runStage("RECONCILE_FINANCIAL_RELATIONS", () ->
                    reconcileFinancialAndRelations(jdbc, envelope.path("state")));
                runStage("RECONCILE_AUDIT_ARCHIVE", () ->
                    requireAuditArchive(jdbc, cipher, envelope.path("auditProvenance")));
                runStage("RECONCILE_CHECKPOINTS", () ->
                    verifyAllCheckpoints(jdbc, envelope.path("revision").longValue()));
                setGateStatus(jdbc, authorization, "migration_verified");
                completed = true;
            } finally {
                if (started && !completed) { setGateStatus(jdbc, authorization, "migration_incomplete"); }
                releaseMigrationLock(lockConnection, options.targetSchema());
            }
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("候选迁移锁失败", error);
        }
    }

    private static void runPhases(JdbcTemplate jdbc, PiiCipher cipher, LegacyMigrationPhaseService phases,
            RunContext context) {
        verifyAndRun(new LegacyCatalogRehearsal(jdbc, cipher, phases)::importCatalog, PHASES.get(0), context);
        verifyAndRun(new LegacyCampusConfigurationRehearsal(jdbc, cipher, phases)::importConfiguration,
            PHASES.get(1), context);
        verifyAndRun(new LegacySystemSettingsRehearsal(jdbc, cipher, phases)::importSettings, PHASES.get(2), context);
        verifyAndRun(new LegacyCrmRehearsal(jdbc, cipher, phases)::importCrm, PHASES.get(3), context);
        verifyAndRun(new LegacyServiceHistoryRehearsal(jdbc, cipher, phases)::importServiceHistory,
            PHASES.get(4), context);
        verifyAndRun(new LegacyFinancialRehearsal(jdbc, cipher, phases)::importFinancial, PHASES.get(5), context);
        verifyAndRun(new LegacyFormTrialRehearsal(jdbc, cipher, phases)::importForms, PHASES.get(6), context);
        verifyAndRun(new LegacyScheduleHistoryRehearsal(jdbc, cipher, phases)::importScheduleHistory,
            PHASES.get(7), context);
        verifyAndRun(new LegacyImportBatchRehearsal(jdbc, cipher, phases)::importBatches, PHASES.get(8), context);
        verifyAndRun(new LegacyAttributionRequestRehearsal(jdbc, cipher, phases)::importRequests,
            PHASES.get(9), context);
        verifyAndRun(new LegacyCrmOperationsRehearsal(jdbc, cipher, phases)::importCrmOperations,
            PHASES.get(10), context);
        verifyAndRun(new LegacyCampaignPromotionRehearsal(jdbc, cipher, phases)::importAcquisition,
            PHASES.get(11), context);
        verifyAndRun(new LegacyUiAnnotationRehearsal(jdbc, cipher, phases)::importAnnotations,
            PHASES.get(12), context);
        verifyAndRun(new LegacyLeadReclaimRequestRehearsal(jdbc, cipher, phases)::importRequests,
            PHASES.get(13), context);
    }

    private static void verifyAndRun(ImportAction action, Phase phase, RunContext context) {
        runStage("PHASE_" + phase.name().toUpperCase(java.util.Locale.ROOT), () -> {
            context.authorization().verify(context.jdbc(), context.options().targetSchema(), context.envelope());
            int rows = action.importSnapshot(context.envelope(), context.authorization());
            Integer matches = context.jdbc().queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase=? "
                + "AND source_revision=? AND imported_rows=? AND deleted=0", Integer.class, phase.checkpoint(),
                context.envelope().path("revision").longValue(), rows);
            if (matches == null || matches != 1) { throw new IllegalStateException("阶段检查点对账失败"); }
            context.output().println("phase=" + phase.name() + " status=staged rows=" + rows);
            context.output().flush();
        });
    }

    private static void runStage(String stage, Runnable action) {
        try { action.run(); } catch (RuntimeException error) { throw new CandidateStageFailure(stage, error); }
    }

    private static void verifyAllCheckpoints(JdbcTemplate jdbc, long revision) {
        List<CheckpointRow> checkpoints = jdbc.query("SELECT phase,source_revision,source_hash,imported_rows "
            + "FROM core_migration_checkpoint ORDER BY id", (rs, row) -> new CheckpointRow(rs.getString(1),
                rs.getLong(2), rs.getString(3), rs.getInt(4)));
        if (checkpoints.size() != PHASES.size()) { throw new IllegalStateException("迁移阶段未全部完成"); }
        for (int index = 0; index < PHASES.size(); index++) {
            CheckpointRow checkpoint = checkpoints.get(index);
            if (!PHASES.get(index).checkpoint().equals(checkpoint.phase()) || checkpoint.revision() != revision
                    || !checkpoint.hash().matches("[a-f0-9]{64}") || checkpoint.importedRows() < 0) {
                throw new IllegalStateException("迁移阶段来源或检查点对账失败");
            }
        }
    }

    private static void reconcileReclaimAudit(JdbcTemplate jdbc, JsonNode envelope) {
        Set<String> requests = new LinkedHashSet<>();
        for (JsonNode row : envelope.path("state").path("leadReclaimRequests")) {
            requests.add(row.path("id").asText());
        }
        int count = 0;
        for (JsonNode event : envelope.path("auditProvenance")) {
            if (!"success".equals(event.path("outcome").asText())) { continue; }
            for (JsonNode change : event.path("changes")) {
                if ("leadReclaimRequests".equals(change.path("collection").asText())
                        && requests.contains(change.path("id").asText())) { count++; break; }
            }
        }
        checkCount(jdbc, "core_crm_lead_reclaim_event", count);
    }

    private static void reconcileCounts(JdbcTemplate jdbc, JsonNode state) {
        checkCount(jdbc, "core_crm_lead_collector", totalNested(state.path("leads"), "collectedByIds"));
        checkCount(jdbc, "core_crm_lead_reclaim_request", state.path("leadReclaimRequests").size());
        checkCount(jdbc, "core_ui_annotation", state.path("authFeedback").path("annotations").size());
        checkCount(jdbc, "core_campus", state.path("campuses").size());
        checkCount(jdbc, "core_course", state.path("courses").size());
        checkCount(jdbc, "core_lesson", totalNested(state.path("courses"), "lessons"));
        checkCount(jdbc, "core_class", state.path("classes").size());
        checkCount(jdbc, "core_session", totalNested(state.path("classes"), "schedule"));
        checkCount(jdbc, "core_student", state.path("students").size());
        checkCount(jdbc, "core_customer", state.path("customers").size());
        checkCount(jdbc, "core_activity", state.path("campaigns").size());
        checkCount(jdbc, "core_customer_touchpoint", state.path("touchpoints").size());
        checkCount(jdbc, "core_crm_lead", state.path("leads").size());
        checkCount(jdbc, "core_opportunity", state.path("opportunities").size());
        checkCount(jdbc, "core_communication", state.path("studentCommunications").size());
        checkCount(jdbc, "core_crm_consultation", state.path("consultations").size());
        checkCount(jdbc, "core_crm_service_ticket", state.path("serviceTickets").size());
        checkCount(jdbc, "core_staff_profile", state.path("staff").size());
        checkCount(jdbc, "core_crm_order", state.path("orders").size());
        checkCount(jdbc, "core_commerce_checkout", state.path("commerceCheckouts").size());
        checkCount(jdbc, "core_crm_receipt", state.path("receipts").size());
        checkCount(jdbc, "core_attendance", state.path("attendance").size());
        checkCount(jdbc, "core_consumption", state.path("consumptions").size());
        checkCount(jdbc, "core_crm_form", state.path("forms").size());
        checkCount(jdbc, "core_activity_form", totalNested(state.path("campaigns"), "formIds"));
        checkCount(jdbc, "core_campaign_promotion_link", totalNested(state.path("campaigns"), "promotionLinks"));
        checkCount(jdbc, "core_form_acquisition", totalSubmissionAcquisitions(state.path("forms")));
        checkCount(jdbc, "core_touchpoint_acquisition", totalTouchpointAcquisitions(state.path("touchpoints")));
        checkCount(jdbc, "core_trial_booking", state.path("trialBookings").size());
        checkCount(jdbc, "core_crm_import_batch", state.path("importBatches").size());
        checkCount(jdbc, "core_crm_import_row", totalNested(state.path("importBatches"), "items"));
        checkCount(jdbc, "core_crm_attribution_request", state.path("attributionRequests").size());
        checkCount(jdbc, "core_crm_attribution_request_event", state.path("attributionRequests").size());
        checkCount(jdbc, "core_session_adjustment", state.path("sessionAdjustments").size());
        checkCount(jdbc, "core_session_adjustment_event", countStatus(state.path("sessionAdjustments"), "cancelled"));
        checkCount(jdbc, "core_session_reschedule_request", state.path("rescheduleRequests").size());
        checkCount(jdbc, "core_session_reschedule_event", totalRescheduleEvents(state.path("rescheduleRequests")));
        checkCount(jdbc, "core_coupon_claim", state.path("rewardClaims").size());
        checkCount(jdbc, "core_coupon_offer", state.path("activities").path("coupon").size());
        checkCount(jdbc, "core_enrollment", state.path("enrollments").size());
    }

    private static void reconcileFinancialAndRelations(JdbcTemplate jdbc, JsonNode state) {
        requireMinorTotal(jdbc, state.path("orders"), "amountCents", "core_crm_order", "total_amount");
        requireMinorTotal(jdbc, state.path("orders"), "paidCents", "core_crm_order", "received_amount");
        requireMinorTotal(jdbc, state.path("orders"), "refundedCents", "core_crm_order", "refunded_amount");
        requireMinorTotal(jdbc, state.path("receipts"), "amountCents", "core_crm_receipt", "amount");
        requireMinorTotal(jdbc, state.path("consumptions"), "amountCents", "core_consumption", "amount");
        requireLessonEntitlements(jdbc, state.path("orders"));
        requirePendingRefunds(jdbc, state.path("refunds"));
        requireNoOrphanRelations(jdbc);
    }

    private static void requireMinorTotal(JdbcTemplate jdbc, JsonNode source, String sourceField,
            String table, String amountColumn) {
        long expected = sumMinor(source, sourceField);
        Long actual = jdbc.queryForObject("SELECT CAST(ROUND(COALESCE(SUM(" + amountColumn
            + "),0)*100) AS SIGNED) FROM " + table, Long.class);
        if (actual == null || actual != expected) { throw new IllegalStateException("财务金额对账失败"); }
    }

    private static long sumMinor(JsonNode rows, String field) {
        long total = 0;
        for (JsonNode row : rows) { total = Math.addExact(total, row.path(field).asLong()); }
        return total;
    }

    private static void requireLessonEntitlements(JdbcTemplate jdbc, JsonNode orders) {
        int expectedRows = 0;
        long expectedAmount = 0;
        for (JsonNode order : orders) {
            expectedRows = Math.addExact(expectedRows, order.path("lessonSnapshot").size());
            expectedAmount = Math.addExact(expectedAmount, order.path("amountCents").asLong());
        }
        Integer actualRows = jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment_lesson", Integer.class);
        Long actualAmount = jdbc.queryForObject("SELECT CAST(ROUND(COALESCE(SUM(unit_amount),0)*100) AS SIGNED) "
            + "FROM core_enrollment_lesson", Long.class);
        if (actualRows == null || actualRows != expectedRows || actualAmount == null
                || actualAmount != expectedAmount) { throw new IllegalStateException("购买权益对账失败"); }
    }

    private static void requirePendingRefunds(JdbcTemplate jdbc, JsonNode refunds) {
        long expected = 0;
        for (JsonNode refund : refunds) {
            if ("pending".equals(refund.path("status").asText())) { expected++; }
        }
        Integer actual = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_refund_request "
            + "WHERE status='pending_approval'", Integer.class);
        if (actual == null || actual != expected) { throw new IllegalStateException("退款申请对账失败"); }
    }

    private static void requireAuditArchive(JdbcTemplate jdbc, PiiCipher cipher, JsonNode sourceAudit) {
        List<String> archives = jdbc.query("SELECT source_cipher FROM core_migration_checkpoint "
            + "WHERE phase='crm_service_history_v1' AND deleted=0", (rs, row) -> rs.getString(1));
        try {
            if (archives.size() != 1 || !MAPPER.readTree(cipher.decrypt(archives.get(0)))
                    .path("auditProvenance").equals(sourceAudit)) {
                throw new IllegalStateException("审计来源归档对账失败");
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("审计来源归档无法读取", error);
        }
    }

    private static void requireNoOrphanRelations(JdbcTemplate jdbc) {
        requireZero(jdbc, "SELECT COUNT(r.id) FROM core_crm_receipt r LEFT JOIN core_crm_order o "
            + "ON o.id=r.order_id WHERE o.id IS NULL");
        requireZero(jdbc, "SELECT COUNT(e.id) FROM core_enrollment e LEFT JOIN core_crm_order o ON o.id=e.order_id "
            + "LEFT JOIN core_student s ON s.id=e.student_id LEFT JOIN core_class c ON c.id=e.class_id "
            + "WHERE o.id IS NULL OR s.id IS NULL OR c.id IS NULL");
        requireZero(jdbc, "SELECT COUNT(a.id) FROM core_attendance a LEFT JOIN core_session s ON s.id=a.session_id "
            + "LEFT JOIN core_enrollment e ON e.id=a.enrollment_id WHERE s.id IS NULL OR e.id IS NULL");
        requireZero(jdbc, "SELECT COUNT(c.id) FROM core_consumption c LEFT JOIN core_crm_order o ON o.id=c.order_id "
            + "LEFT JOIN core_enrollment e ON e.id=c.enrollment_id WHERE o.id IS NULL OR e.id IS NULL");
        requireZero(jdbc, "SELECT COUNT(l.id) FROM core_campaign_promotion_link l LEFT JOIN core_activity_form af "
            + "ON af.activity_id=l.activity_id AND af.form_id=l.form_id AND af.deleted=0 "
            + "WHERE af.id IS NULL OR l.deleted=1");
        requireZero(jdbc, "SELECT COUNT(a.id) FROM core_form_acquisition a LEFT JOIN core_campaign_promotion_link l "
            + "ON l.id=a.promotion_link_id LEFT JOIN core_crm_form_submission s ON s.id=a.submission_id "
            + "WHERE l.id IS NULL OR s.id IS NULL OR s.form_id<>l.form_id OR a.deleted=1");
        requireZero(jdbc, "SELECT COUNT(a.id) FROM core_touchpoint_acquisition a LEFT JOIN core_campaign_promotion_link l "
            + "ON l.id=a.promotion_link_id LEFT JOIN core_crm_form_submission s ON s.id=a.submission_id "
            + "LEFT JOIN core_form_acquisition fa ON fa.submission_id=s.id AND fa.deleted=0 "
            + "LEFT JOIN core_customer_touchpoint t ON t.id=a.touchpoint_id "
            + "WHERE l.id IS NULL OR s.id IS NULL OR s.form_id<>l.form_id OR fa.id IS NULL "
            + "OR fa.promotion_link_id<>l.id OR t.id IS NULL OR a.deleted=1");
    }

    private static void requireZero(JdbcTemplate jdbc, String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        if (count == null || count != 0) { throw new IllegalStateException("迁移关系对账失败"); }
    }

    private static int totalNested(JsonNode rows, String field) {
        int total = 0;
        for (JsonNode row : rows) { total = Math.addExact(total, row.path(field).size()); }
        return total;
    }

    private static int totalSubmissionAcquisitions(JsonNode forms) {
        int total = 0;
        for (JsonNode form : forms) {
            for (JsonNode submission : form.path("submissions")) {
                if (submission.path("acquisitionSnapshot").isObject()) { total = Math.addExact(total, 1); }
            }
        }
        return total;
    }

    private static int totalTouchpointAcquisitions(JsonNode touchpoints) {
        int total = 0;
        for (JsonNode touchpoint : touchpoints) {
            if (touchpoint.path("acquisitionSnapshot").isObject()) { total = Math.addExact(total, 1); }
        }
        return total;
    }

    private static int countStatus(JsonNode rows, String status) {
        int total = 0;
        for (JsonNode row : rows) { if (status.equals(row.path("status").asText())) { total++; } }
        return total;
    }

    private static int totalRescheduleEvents(JsonNode requests) {
        int total = 0;
        for (JsonNode request : requests) {
            total = Math.addExact(total, "pending".equals(request.path("status").asText()) ? 1 : 2);
        }
        return total;
    }

    private static void checkCount(JdbcTemplate jdbc, String table, int expected) {
        Integer actual = jdbc.queryForObject("SELECT COUNT(id) FROM " + table, Integer.class);
        if (actual == null || actual != expected) { throw new IllegalStateException("迁移关系数量对账失败"); }
    }

    static void acquireMigrationLock(java.sql.Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
            statement.setString(1, lockName(schema));
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) { throw new IllegalStateException("候选迁移正在运行"); }
            }
        }
    }

    static void releaseMigrationLock(java.sql.Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName(schema));
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) { throw new IllegalStateException("候选迁移锁释放失败"); }
            }
        }
    }

    private static String lockName(String schema) {
        return "siyue:mig:" + LegacyCatalogRehearsal.hash(schema).substring(0, 40);
    }

    static void setGateStatus(JdbcTemplate jdbc, LegacyMigrationTargetAuthorization authorization,
            String status) {
        if (!Set.of("migration_running", "migration_incomplete", "migration_verified").contains(status)) {
            throw new IllegalArgumentException("候选迁移状态转换无效");
        }
        List<String> current = jdbc.query("SELECT status FROM core_migration_candidate_gate WHERE target_schema=? "
            + "AND source_sha256=? AND checkpoint_id=? AND deleted=0", (rs, row) -> rs.getString(1), authorization.schema(),
            authorization.sourceSha256(), authorization.checkpoint());
        if (current.size() != 1) { throw new IllegalStateException("候选迁移状态更新失败"); }
        if (status.equals(current.get(0))) { return; }
        String verifiedAt = "migration_verified".equals(status) ? "UTC_TIMESTAMP(3)" : "NULL";
        int changed = jdbc.update("UPDATE core_migration_candidate_gate SET status=?,verified_at=" + verifiedAt
            + ",updated_at=UTC_TIMESTAMP(3)"
            + " WHERE target_schema=? AND source_sha256=? AND checkpoint_id=? AND deleted=0 AND status=?", status,
            authorization.schema(), authorization.sourceSha256(), authorization.checkpoint(), current.get(0));
        if (changed != 1) { throw new IllegalStateException("候选迁移状态更新失败"); }
    }

    private static void reportBlocked(Options options, List<String> blockers, PrintWriter out) {
        out.println("mode=" + (options.execute() ? "execute" : "dry-run") + " target=" + options.targetSchema()
            + " sourceSha256=" + options.sourceSha256() + " checkpoint=" + options.checkpoint());
        blockers.forEach(blocker -> out.println("blocked=" + blocker));
        out.println("candidate remains inactive; no import was started");
        out.flush();
    }

    private static void reportPlan(Options options, boolean verified, PrintWriter out) {
        out.println("mode=" + (options.execute() ? "execute" : "dry-run") + " target=" + options.targetSchema()
            + " sourceSha256=" + options.sourceSha256() + " checkpoint=" + options.checkpoint());
        for (Phase phase : PHASES) { out.println("phase=" + phase.name() + " status=" + (verified ? "verified" : "planned")); }
        out.println(verified ? "candidate status=migration_verified; activation is a separate controlled step"
            : "read-only preflight complete; no import or schema migration was run");
        out.flush();
    }

    private static int blocked(String reason, PrintWriter err, String traceId) {
        LOGGER.warn("Candidate preflight blocked: reasonCode={}", reason);
        err.println("blocked=" + reason + " traceId=" + traceId);
        err.flush();
        return 2;
    }

    private static String rawHash(byte[] sourceBytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(sourceBytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private record Connection(String url, String user, String password) { }
    private record RunContext(JdbcTemplate jdbc, JsonNode envelope, LegacyMigrationTargetAuthorization authorization,
            Options options, PrintWriter output) { }
    private record ExecutionRequest(LegacyMigrationTargetAuthorization authorization, Options options,
            PrintWriter output) { }
    private record Phase(String name, String checkpoint) { }
    private record CheckpointRow(String phase, long revision, String hash, int importedRows) { }
    @FunctionalInterface private interface ImportAction {
        int importSnapshot(JsonNode envelope, LegacyMigrationTargetAuthorization authorization);
    }
    private static final class CandidateStageFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String stage;
        CandidateStageFailure(String stage, RuntimeException cause) {
            super("迁移阶段失败", cause);
            this.stage = stage;
        }
        String stage() { return stage; }
    }

    record Options(Path snapshot, String targetSchema, String sourceSha256, String checkpoint,
            boolean execute, boolean help) {
        static Options parse(String[] args) {
            Path snapshot = null;
            String target = null, hash = null, checkpoint = null;
            boolean execute = false, help = false;
            Set<String> seen = new LinkedHashSet<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (!seen.add(argument)) { throw new IllegalArgumentException("CLI参数重复"); }
                if (argument.equals("--help")) { help = true; continue; }
                if (argument.equals("--execute")) { execute = true; continue; }
                if (index + 1 >= args.length) { throw new IllegalArgumentException("CLI参数无效"); }
                String value = args[++index];
                switch (argument) {
                    case "--snapshot" -> snapshot = Path.of(value);
                    case "--target-schema" -> target = value;
                    case "--source-sha256" -> hash = value;
                    case "--checkpoint" -> checkpoint = value;
                    default -> throw new IllegalArgumentException("CLI参数无效");
                }
            }
            if (help) { return new Options(snapshot, target, hash, checkpoint, execute, true); }
            if (snapshot == null || target == null || hash == null || checkpoint == null) {
                throw new IllegalArgumentException("显式快照、目标、来源哈希和检查点均为必填项");
            }
            LegacyMigrationTargetAuthorization.requireCandidateSchema(target);
            LegacyMigrationTargetAuthorization.requireHash(hash);
            LegacyMigrationTargetAuthorization.requireCheckpoint(checkpoint);
            return new Options(snapshot.toAbsolutePath().normalize(), target, hash, checkpoint, execute, false);
        }

        static String usage() {
            return "java -jar siyue-core.jar legacy-migration-candidate --snapshot <offline-file> "
                + "--target-schema <siyue_migration_candidate_hex> --source-sha256 <64-lowercase-hex> "
                + "--checkpoint <operator-checkpoint> [--execute]";
        }
    }
}
