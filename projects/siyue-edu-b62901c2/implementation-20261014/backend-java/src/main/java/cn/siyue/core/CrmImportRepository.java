package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmImportInput;
import cn.siyue.core.CrmDtos.CrmImportRow;
import cn.siyue.core.CrmDtos.CrmImportReceipt;
import cn.siyue.core.CrmDtos.CrmImportSummary;
import cn.siyue.core.CrmDtos.CustomerContactInput;
import cn.siyue.core.CrmDtos.CustomerInput;
import cn.siyue.core.CrmDtos.LeadInput;
import cn.siyue.core.CrmDtos.TouchpointInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists reviewed import facts and rechecks identity before every acquisition write. */
@Repository
public class CrmImportRepository {
    private record CustomerMatch(long id, String publicId) { }
    private record LeadMatch(long id, String publicId) { }
    private record LeadContext(String activityId, String customerId, Map<String, String> data,
            String batchId, int rowNumber) { }
    private record IdentityMatches(List<CustomerMatch> exact, boolean phoneExists) { }
    private record RowResult(CrmImportLine line, Long customerId, Long leadId, Long touchpointId) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;
    private final CrmRepository crm;
    private final LeadRepository leads;
    /** Shares encrypted identity repositories and the same transaction-bound JDBC connection. */
    public CrmImportRepository(JdbcTemplate jdbc, PiiCipher cipher, ObjectMapper mapper,
            CrmRepository crm, LeadRepository leads) {
        this.jdbc = jdbc; this.cipher = cipher; this.mapper = mapper; this.crm = crm; this.leads = leads;
    }

    /** Checks that the selected active campus and optional activity are in the actor's scope. */
    public void authorize(Actor actor, CrmImportInput input, boolean lock) {
        if (lock) { crm.authorizeCampus(actor, input.campusId()); }
        else { authorizeCampusRead(actor, input.campusId()); }
        actor.require("crm.customer.read"); actor.require("crm.lead.read");
        if (input.activityId() != null && !input.activityId().isBlank()) {
            actor.require("crm.activity.read"); crm.authorizeActivity(actor, input.activityId());
            Long sameCampus = jdbc.queryForObject("""
                SELECT COUNT(a.id) FROM core_activity a JOIN core_campus cp ON cp.id=a.campus_id
                WHERE a.public_id=? AND cp.public_id=? AND a.status='active' AND a.deleted=0
                """, Long.class, input.activityId(), input.campusId());
            if (sameCampus == null || sameCampus == 0) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "活动须处于所选校区且保持启用");
            }
        }
    }

    /** Classifies a row by exact student-name/contact match without merging siblings by phone. */
    public String classify(Actor actor, String campusId, Map<String, String> data) {
        IdentityMatches matches = identityMatches(actor, campusId, data.get("name"), data.get("phone"));
        if (matches.exact().size() > 1) { return "invalid"; }
        if (matches.exact().isEmpty() && matches.phoneExists()) { return "needs_review"; }
        if (matches.exact().isEmpty()) { return "new_customer"; }
        return activeLead(actor, matches.exact().get(0).id(), data) == null ? "existing_touch" : "duplicate_demand";
    }

    /** Returns campus-supported grade and subject values for import mapping and validation. */
    public Map<String, List<String>> dictionaries(String campusId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0",
            Long.class, campusId);
        List<String> grades = jdbc.queryForList("""
            SELECT DISTINCT grade FROM core_course WHERE deleted=0 AND active=1
              AND (campus_id=? OR campus_id IS NULL) ORDER BY grade
            """, String.class, id);
        List<String> subjects = jdbc.queryForList("""
            SELECT DISTINCT subject FROM core_course WHERE deleted=0 AND active=1
              AND (campus_id=? OR campus_id IS NULL) ORDER BY subject
            """, String.class, id);
        return Map.of("grade", grades, "subject", subjects);
    }

    /** Stores each row and its outcome in one transaction with customer, lead, and touchpoint writes. */
    public CrmImportReceipt commit(Actor actor, CrmImportInput input, CrmImportPlan plan)
            throws JsonProcessingException {
        String batchId = "IMPB_" + UUID.randomUUID();
        long campusId = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=? AND deleted=0",
            Long.class, input.campusId());
        Long activityId = activityId(input.activityId());
        jdbc.update("""
            INSERT INTO core_crm_import_batch
                (public_id,created_by,campus_id,activity_id,file_name,file_format,status,total_rows,
                 new_customer_count,existing_touch_count,duplicate_demand_count,needs_review_count,invalid_count)
            VALUES (?,?,?,?,?,?,'processing',?,0,0,0,0,0)
            """, batchId, actor.id(), campusId, activityId, cleanFileName(input.fileName()),
            input.format(), plan.rows().size());
        long batchInternalId = jdbc.queryForObject("SELECT id FROM core_crm_import_batch WHERE public_id=?",
            Long.class, batchId);
        List<RowResult> results = new ArrayList<>();
        for (CrmImportLine row : plan.rows()) { results.add(commitRow(actor, input, batchId, row)); }
        CrmImportSummary summary = summarize(results.stream().map(RowResult::line).toList());
        jdbc.update("""
            UPDATE core_crm_import_batch SET status='completed',new_customer_count=?,existing_touch_count=?,
                duplicate_demand_count=?,needs_review_count=?,invalid_count=?,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND status='processing'
            """, summary.newCustomer(), summary.existingTouch(), summary.duplicateDemand(),
            summary.needsReview(), summary.invalid(), batchInternalId);
        for (RowResult result : results) { saveRow(batchInternalId, result); }
        return new CrmImportReceipt(batchId, summary,
            results.stream().map(RowResult::line).map(CrmImportRepository::dto).toList());
    }

    private RowResult commitRow(Actor actor, CrmImportInput input, String batchId, CrmImportLine original)
            throws JsonProcessingException {
        if (!original.errors().isEmpty()) {
            String category = original.category().equals("needs_review") ? "needs_review" : "invalid";
            return new RowResult(original.withOutcome(category, null, null, null), null, null, null);
        }
        IdentityMatches matches = identityMatches(actor, input.campusId(),
            original.data().get("name"), original.data().get("phone"));
        if (matches.exact().size() > 1) {
            return new RowResult(original.withOutcome("invalid", null, null, null), null, null, null);
        }
        if (matches.exact().isEmpty() && matches.phoneExists()) {
            return new RowResult(original.withOutcome("needs_review", null, null, null), null, null, null);
        }
        CustomerMatch customer = matches.exact().isEmpty()
            ? createCustomer(actor, input.campusId(), original.data()) : matches.exact().get(0);
        LeadMatch lead = activeLead(actor, customer.id(), original.data());
        String category;
        if (lead != null) { category = "duplicate_demand"; }
        else {
            LeadContext context = new LeadContext(input.activityId(), customer.publicId(),
                original.data(), batchId, original.rowNumber());
            lead = createLead(actor, context);
            category = matches.exact().isEmpty() ? "new_customer" : "existing_touch";
        }
        var touchpoint = crm.appendTouchpoint(actor, customer.publicId(), new TouchpointInput(
            input.activityId(), null, "import", "import", "批量导入 · 第 " + original.rowNumber() + " 行", Instant.now()));
        long touchpointId = jdbc.queryForObject("SELECT id FROM core_customer_touchpoint WHERE public_id=?",
            Long.class, touchpoint.id());
        CrmImportLine saved = original.withOutcome(category, customer.publicId(), lead.publicId(), touchpoint.id());
        return new RowResult(saved, customer.id(), internalLeadId(lead), touchpointId);
    }

    private CustomerMatch createCustomer(Actor actor, String campusId, Map<String, String> data) {
        List<CustomerContactInput> contacts = List.of(
            new CustomerContactInput("guardian", "phone", data.get("phone"), true));
        var customer = crm.createCustomer(actor, new CustomerInput(campusId, data.get("name"), contacts));
        Long id = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id=?", Long.class, customer.id());
        return new CustomerMatch(id, customer.id());
    }

    private LeadMatch createLead(Actor actor, LeadContext context) {
        Map<String, String> data = context.data();
        String title = data.get("name") + (data.get("subject").isBlank() ? "" : " · " + data.get("subject"));
        String detail = importDetail(data, context.batchId(), context.rowNumber());
        var lead = leads.create(actor, new LeadInput(context.customerId(), blankToNull(context.activityId()), null,
            "import", title, blankToNull(data.get("subject")), blankToNull(data.get("term")), detail, null, null));
        Long id = jdbc.queryForObject("SELECT id FROM core_crm_lead WHERE public_id=?", Long.class, lead.id());
        return new LeadMatch(id, lead.id());
    }

    private LeadMatch activeLead(Actor actor, long customerId, Map<String, String> data) {
        ScopeSql.Clause scope = ScopeSql.leads(actor);
        List<Object> args = new ArrayList<>(List.of(customerId, data.get("subject"), data.get("term")));
        args.addAll(scope.args());
        List<LeadMatch> found = jdbc.query("""
            SELECT l.id,l.public_id FROM core_crm_lead l
            WHERE l.customer_id=? AND COALESCE(l.subject,'')=? AND COALESCE(l.term,'')=?
              AND l.status IN ('new','contacting','qualified') AND l.deleted=0 AND (
            """ + scope.sql() + ") ORDER BY l.id LIMIT 1", (rs, row) ->
                new LeadMatch(rs.getLong("id"), rs.getString("public_id")), args.toArray());
        return found.isEmpty() ? null : found.get(0);
    }

    private IdentityMatches identityMatches(Actor actor, String campusId, String name, String phone) {
        List<CustomerMatch> exactMatches = customerMatches(actor, campusId, name, phone);
        return new IdentityMatches(exactMatches, customerPhoneExists(campusId, phone));
    }

    private List<CustomerMatch> customerMatches(Actor actor, String campusId, String name, String phone) {
        Map<String, byte[]> phoneIndexes = cipher.blindIndexes("phone", normalizePhone(phone));
        Map<String, byte[]> nameIndexes = name == null ? Map.of()
            : cipher.blindIndexes("customer_name", normalizeName(name));
        ScopeSql.Clause scope = ScopeSql.customers(actor);
        List<Object> args = new ArrayList<>(List.of(campusId)); args.addAll(scope.args());
        String phoneSql = indexSql("ci", phoneIndexes, args);
        String nameSql = nameIndexes.isEmpty() ? "" : indexSql("ni", nameIndexes, args);
        String nameJoin = nameIndexes.isEmpty() ? "" : " JOIN core_customer_name_index ni ON ni.customer_id=c.id AND ni.deleted=0";
        return jdbc.query("""
            SELECT DISTINCT c.id,c.public_id FROM core_customer c
            JOIN core_campus cp ON cp.id=c.campus_id
            JOIN core_customer_contact cc ON cc.customer_id=c.id AND cc.deleted=0 AND cc.channel='phone'
            JOIN core_customer_contact_index ci ON ci.contact_id=cc.id AND ci.deleted=0
            """ + nameJoin + " WHERE c.deleted=0 AND cp.deleted=0 AND cp.public_id=? AND ("
            + scope.sql() + ") AND (" + phoneSql + ")" + (nameSql.isEmpty() ? "" : " AND (" + nameSql + ")"),
            (rs, row) -> new CustomerMatch(rs.getLong("id"), rs.getString("public_id")), args.toArray());
    }

    /**
     * Checks for a shared campus phone without returning the other customer's identity or owner.
     * The existence-only result prevents imports from duplicating a household outside actor scope.
     */
    private boolean customerPhoneExists(String campusId, String phone) {
        Map<String, byte[]> indexes = cipher.blindIndexes("phone", normalizePhone(phone));
        List<Object> args = new ArrayList<>(List.of(campusId));
        String phoneSql = indexSql("ci", indexes, args);
        Integer matches = jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM core_customer c
                JOIN core_campus cp ON cp.id=c.campus_id
                JOIN core_customer_contact cc ON cc.customer_id=c.id AND cc.deleted=0 AND cc.channel='phone'
                JOIN core_customer_contact_index ci ON ci.contact_id=cc.id AND ci.deleted=0
                WHERE c.deleted=0 AND cp.deleted=0 AND cp.public_id=? AND (
            """ + phoneSql + "))", Integer.class, args.toArray());
        return matches != null && matches == 1;
    }

    private String indexSql(String alias, Map<String, byte[]> indexes, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        indexes.forEach((key, hash) -> {
            clauses.add("(" + alias + ".key_id=? AND " + alias + ".value_hash=?)");
            args.add(key); args.add(hash);
        });
        return String.join(" OR ", clauses);
    }

    private void saveRow(long batchId, RowResult result) throws JsonProcessingException {
        CrmImportLine line = result.line();
        String data = mapper.writeValueAsString(Map.of("data", line.data(), "errors", line.errors()));
        jdbc.update("""
            INSERT INTO core_crm_import_row
                (import_batch_id,`row_number`,outcome_type,customer_id,lead_id,touchpoint_id,source_data_cipher)
            VALUES (?,?,?,?,?,?,?)
            """, batchId, line.rowNumber(), line.category(), result.customerId(), result.leadId(),
            result.touchpointId(), cipher.encrypt(data));
    }

    private long internalLeadId(LeadMatch lead) { return lead.id(); }
    private Long activityId(String publicId) {
        if (publicId == null || publicId.isBlank()) { return null; }
        return jdbc.queryForObject("SELECT id FROM core_activity WHERE public_id=? AND deleted=0", Long.class, publicId);
    }
    private void authorizeCampusRead(Actor actor, String campusId) {
        List<Long> matches = jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0",
            (rs, row) -> rs.getLong(1), campusId);
        if (matches.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或已停用"); }
        long id = matches.get(0);
        if (!actor.scope().equals("all") && !actor.campusIds().contains(id)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权在该校区导入");
        }
    }
    private CrmImportSummary summarize(List<CrmImportLine> rows) {
        return new CrmImportSummary(count(rows, "new_customer"), count(rows, "existing_touch"),
            count(rows, "duplicate_demand"), count(rows, "needs_review"), count(rows, "invalid"));
    }
    private int count(List<CrmImportLine> rows, String category) {
        return (int) rows.stream().filter(row -> row.category().equals(category)).count();
    }
    private static CrmImportRow dto(CrmImportLine line) {
        return new CrmImportRow(line.rowNumber(), line.category(), line.data().getOrDefault("name", ""),
            maskPhone(line.data().getOrDefault("phone", "")), line.errors(), line.customerId(), line.leadId(), line.touchpointId());
    }
    private static String maskPhone(String phone) {
        return phone.matches("1[3-9][0-9]{9}") ? phone.substring(0, 3) + "****" + phone.substring(7) : "";
    }
    private String importDetail(Map<String, String> data, String batchId, int rowNumber) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("年级", data.get("grade")); detail.put("来源", data.get("source"));
        detail.put("备注", data.get("note")); detail.put("导入批次", batchId);
        detail.put("行号", Integer.toString(rowNumber));
        try { return mapper.writeValueAsString(detail); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法保存导入说明", error); }
    }
    private String cleanFileName(String value) {
        if (value == null || value.isBlank()) { return null; }
        String clean = value.replace('\\', '/');
        return clean.substring(clean.lastIndexOf('/') + 1, clean.length()).replaceAll("[\\p{Cntrl}]", "");
    }
    private static String normalizeName(String value) {
        return java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC).replaceAll("\\s+", " ");
    }
    private static String normalizePhone(String value) {
        String phone = value.replaceAll("[\\s()\\-]", "");
        return phone.replaceFirst("^(?:\\+86|0086)(?=1[3-9][0-9]{9}$)", "");
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
