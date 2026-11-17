package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmImportInput;
import cn.siyue.core.CrmDtos.CrmImportPreview;
import cn.siyue.core.CrmDtos.CrmImportReceipt;
import cn.siyue.core.CrmDtos.CrmImportRow;
import cn.siyue.core.CrmDtos.CrmImportSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates customer-acquisition CSV/XLSX files and commits classified rows with per-row outcomes.
 * Import access requires customer, lead and touchpoint permissions; preview avoids exposing full phone numbers,
 * while commit revalidates rows and records the batch and its customer, lead and touchpoint effects atomically.
 */
@Service
public class CrmImportService {
    private static final List<String> FIELDS = List.of("name", "phone", "guardian", "grade",
        "subject", "term", "source", "note");
    private static final Set<String> REQUIRED = Set.of("name", "phone");
    private final AccessRepository access;
    private final CrmImportRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Creates the importer with current-access checks, persistence, idempotency and JSON serialization.
     * @param access active-principal lock service used before commit
     * @param repository import batch persistence and campus-scoped identity classification
     * @param mutations idempotency receipt repository
     * @param mapper JSON serializer used to fingerprint and replay commits
     */
    public CrmImportService(AccessRepository access, CrmImportRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access; this.repository = repository; this.mutations = mutations; this.mapper = mapper;
    }

    /** Parses and classifies the supplied file without committing CRM records or exposing full phone numbers.
     * Requires customer read/write, lead read/write and touchpoint-write permissions, plus access to the selected
     * campus and any selected activity.
     * @param actor authenticated caller with the required import grants and data scope
     * @param input CSV or base64-encoded XLSX content up to 950000 characters, selected campus/activity, file name,
     *              source and mappings for required name/phone columns plus optional supported fields; at most
     *              500 data rows are accepted
     * @return columns, supported dictionaries, masked row previews, row errors and category counts
     * @throws BusinessException when authorization, scope, format, file size, headers, mapping or rows are invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CrmImportPreview preview(Actor actor, CrmImportInput input) {
        requirePermissions(actor);
        repository.authorize(actor, input, false);
        return previewResult(prepare(actor, input));
    }

    /** Revalidates and commits the file, eligible CRM rows, import outcomes and idempotency receipt atomically.
     * Requires customer read/write, lead read/write and touchpoint-write permissions; the active principal,
     * campus scope and activity scope are checked again at commit time. The key is 16–100 letters, digits,
     * underscores or hyphens. The input accepts CSV or base64-encoded XLSX content up to 950000 characters and
     * at most 500 data rows; name and phone mappings are required.
     * @param actor authenticated caller with the required import grants and data scope
     * @param key idempotency key for this import request
     * @param input the CSV/XLSX content and campus/activity, file metadata and column mapping
     * @return committed per-row outcomes and aggregate counts, or the stored receipt for an identical retry
     * @throws BusinessException when authorization, scope, file, mapping, row or idempotency validation fails,
     *                            or when no valid row can be imported
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmImportReceipt commit(Actor actor, String key, CrmImportInput input)
            throws JsonProcessingException {
        requirePermissions(actor); requireKey(key);
        actor = access.lockActive(actor.id()); requirePermissions(actor);
        repository.authorize(actor, input, true);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.import.commit", hash);
        if (replay != null) { return mapper.readValue(replay, CrmImportReceipt.class); }
        CrmImportPlan plan = prepare(actor, input);
        if (plan.rows().stream().noneMatch(row -> row.errors().isEmpty())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "没有可导入的有效数据");
        }
        CrmImportReceipt receipt = repository.commit(actor, input, plan);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "crm.import.commit", hash,
            mapper.writeValueAsString(receipt), receipt.batchId()));
        return receipt;
    }

    private CrmImportPlan prepare(Actor actor, CrmImportInput input) {
        List<List<String>> rawRows = CrmImportParser.parse(input.format(), input.content());
        List<String> columns = normalizedHeaders(rawRows.get(0));
        Map<String, Integer> indexes = headerIndexes(columns);
        validateMapping(input.mapping(), indexes);
        if (rawRows.size() == 1) { throw new BusinessException(ErrorCode.INVALID_INPUT, "文件没有数据行"); }
        Map<String, List<String>> dictionaries = repository.dictionaries(input.campusId());
        List<CrmImportLine> rows = new ArrayList<>();
        Set<String> seenNeeds = new HashSet<>();
        Map<String, String> seenPhones = new LinkedHashMap<>();
        for (int index = 1; index < rawRows.size(); index++) {
            Map<String, String> data = mappedRow(rawRows.get(index), input, indexes);
            List<String> errors = validateRow(data, dictionaries);
            String category = "invalid";
            if (errors.isEmpty()) {
                category = repository.classify(actor, input.campusId(), data);
                String priorName = seenPhones.putIfAbsent(data.get("phone"), data.get("name").toLowerCase(java.util.Locale.ROOT));
                if (priorName != null && !priorName.equals(data.get("name").toLowerCase(java.util.Locale.ROOT))) {
                    category = "needs_review";
                }
                attachClassificationError(category, errors);
                String needKey = needKey(data);
                if (category.equals("new_customer") || category.equals("existing_touch")) {
                    if (!seenNeeds.add(needKey)) { category = "duplicate_demand"; }
                } else { seenNeeds.add(needKey); }
            }
            rows.add(new CrmImportLine(index + 1, Map.copyOf(data), List.copyOf(errors), category,
                null, null, null));
        }
        return new CrmImportPlan(columns, List.copyOf(rows), dictionaries);
    }

    private Map<String, String> mappedRow(List<String> row, CrmImportInput input, Map<String, Integer> indexes) {
        Map<String, String> data = new LinkedHashMap<>();
        for (String field : FIELDS) {
            String header = input.mapping().get(field);
            Integer column = header == null ? null : indexes.get(normalize(header));
            data.put(field, column == null || column >= row.size() ? "" : normalize(row.get(column)));
        }
        data.put("phone", normalizePhone(data.get("phone")));
        if (data.get("source").isBlank()) { data.put("source", defaultSource(input.source())); }
        return data;
    }

    private List<String> validateRow(Map<String, String> data, Map<String, List<String>> dictionaries) {
        List<String> errors = new ArrayList<>();
        if (data.get("name").isBlank() || data.get("name").length() > 100) { errors.add("学生姓名不能为空且不超过100字"); }
        if (!data.get("phone").matches("1[3-9][0-9]{9}")) { errors.add("手机号格式无效"); }
        checkLength(data, "guardian", 100, "联系人姓名", errors);
        checkLength(data, "grade", 40, "年级", errors);
        checkLength(data, "subject", 100, "学科", errors);
        checkLength(data, "term", 100, "学期", errors);
        checkLength(data, "source", 100, "来源", errors);
        checkLength(data, "note", 5000, "备注", errors);
        checkDictionary(data, "grade", dictionaries, "年级", errors);
        checkDictionary(data, "subject", dictionaries, "学科", errors);
        return errors;
    }

    private void checkDictionary(Map<String, String> data, String field,
            Map<String, List<String>> dictionaries, String label, List<String> errors) {
        List<String> values = dictionaries.getOrDefault(field, List.of());
        if (!data.get(field).isBlank() && !values.isEmpty() && !values.contains(data.get(field))) {
            errors.add(label + "不在所选校区的课程字典中");
        }
    }

    private void attachClassificationError(String category, List<String> errors) {
        if (category.equals("needs_review")) {
            errors.add("同校区已存在此监护人手机号，但学生姓名不同；请先人工核对家庭关系");
        } else if (category.equals("invalid")) {
            errors.add("存在多份相同姓名和手机号的客户档案，请先人工核对");
        }
    }

    private CrmImportPreview previewResult(CrmImportPlan plan) {
        List<CrmImportRow> rows = plan.rows().stream().map(row -> new CrmImportRow(row.rowNumber(),
            row.category(), row.data().get("name"), mask(row.data().get("phone")), row.errors(), null, null, null)).toList();
        return new CrmImportPreview(plan.columns(), FIELDS, plan.dictionaries(), rows,
            summarize(plan.rows()), rows.size());
    }

    private CrmImportSummary summarize(List<CrmImportLine> rows) {
        return new CrmImportSummary(count(rows, "new_customer"), count(rows, "existing_touch"),
            count(rows, "duplicate_demand"), count(rows, "needs_review"), count(rows, "invalid"));
    }

    private int count(List<CrmImportLine> rows, String category) {
        return (int) rows.stream().filter(row -> row.category().equals(category)).count();
    }

    private List<String> normalizedHeaders(List<String> headers) {
        List<String> result = new ArrayList<>(headers.stream().map(CrmImportService::normalize).toList());
        if (!result.isEmpty()) { result.set(0, result.get(0).replaceFirst("^\\uFEFF", "")); }
        Set<String> nonEmpty = new LinkedHashSet<>(result.stream().filter(value -> !value.isBlank()).toList());
        if (nonEmpty.size() != result.stream().filter(value -> !value.isBlank()).count()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "表头存在重复列");
        }
        return List.copyOf(result);
    }

    private Map<String, Integer> headerIndexes(List<String> columns) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) { indexes.put(columns.get(i), i); }
        return indexes;
    }

    private void validateMapping(Map<String, String> mapping, Map<String, Integer> indexes) {
        if (mapping == null || !mapping.keySet().containsAll(REQUIRED)
                || !FIELDS.containsAll(mapping.keySet())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "须映射学生姓名和手机号，且字段映射有效");
        }
        for (String column : mapping.values()) {
            if (!indexes.containsKey(normalize(column))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "字段映射列不存在");
            }
        }
    }

    private static String needKey(Map<String, String> data) {
        return normalize(data.get("name")).toLowerCase(java.util.Locale.ROOT) + "|" + data.get("phone")
            + "|" + normalize(data.get("subject")).toLowerCase(java.util.Locale.ROOT)
            + "|" + normalize(data.get("term")).toLowerCase(java.util.Locale.ROOT);
    }
    private static String defaultSource(String value) { return value == null || value.isBlank() ? "批量导入" : normalize(value); }
    private static String normalizePhone(String value) {
        String phone = value.replaceAll("[\\s()\\-]", "");
        return phone.replaceFirst("^(?:\\+86|0086)(?=1[3-9][0-9]{9}$)", "");
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim();
    }
    private static String mask(String phone) { return phone.matches("1[3-9][0-9]{9}") ? phone.substring(0, 3) + "****" + phone.substring(7) : ""; }
    private static void checkLength(Map<String, String> data, String key, int max, String label, List<String> errors) {
        if (data.get(key).length() > max) { errors.add(label + "不超过" + max + "字"); }
    }
    private void requirePermissions(Actor actor) {
        actor.require("crm.customer.read"); actor.require("crm.customer.write");
        actor.require("crm.lead.read"); actor.require("crm.lead.write"); actor.require("crm.touchpoint.write");
    }
    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
    }
}
