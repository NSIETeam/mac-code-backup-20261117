package cn.siyue.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Indexes and verifies only the encrypted lead fields exposed by the CRM row projection. */
final class SavedViewLeadSearch {
    /** HMAC片段命中后待精确核对的线索主键及四个源字段密文，不跨范围扫描全表。 */
    private record Candidate(long leadId, String name, String phone, String contactPhone, String guardian) { }
    /** 单个线索搜索字段的固定列名与原值，分别构建密钥版本化片段索引。 */
    private record FieldValue(String name, String value) { }
    /** 线索写入路径提供的姓名、本人电话、联系人电话和监护人明文；仅用于派生搜索索引。 */
    record Fields(String name, String phone, String contactPhone, String guardian) { }

    private SavedViewLeadSearch() { }

    static void save(JdbcTemplate jdbc, PiiCipher cipher, long leadId, Fields fields) {
        List<FieldValue> values = List.of(new FieldValue("name", fields.name()), new FieldValue("phone", fields.phone()),
            new FieldValue("contact_phone", fields.contactPhone()), new FieldValue("guardian", fields.guardian()));
        for (FieldValue field : values) { saveField(jdbc, cipher, leadId, field); }
    }

    private static void saveField(JdbcTemplate jdbc, PiiCipher cipher, long leadId, FieldValue field) {
        if (field.value() == null || field.value().isBlank()) { return; }
        SavedViewSearchNgrams.encode(cipher, field.value()).forEach((key, json) -> jdbc.update(
            "INSERT INTO core_crm_lead_search_index(lead_id,field_name,key_id,search_ngrams) VALUES(?,?,?,?)",
            leadId, field.name(), key, json));
    }

    static Set<Long> matchingIds(JdbcTemplate jdbc, PiiCipher cipher, Actor actor, String rawSearch) {
        String needle = SavedViewSearchNgrams.normalize(rawSearch);
        ScopeSql.Clause scope = ScopeSql.leadReads(actor);
        ScopeSql.Clause candidate = SavedViewSearchNgrams.candidate("si", "search_ngrams", cipher, needle);
        List<Object> args = new ArrayList<>(scope.args()); args.addAll(candidate.args());
        List<Candidate> rows = jdbc.query("SELECT DISTINCT l.id,l.name_cipher,l.phone_cipher,"
            + "l.contact_phone_cipher,l.guardian_name_cipher FROM core_crm_lead l "
            + "JOIN core_crm_lead_search_index si ON si.lead_id=l.id AND si.deleted=0 "
            + "WHERE l.deleted=0 AND (" + scope.sql() + ") AND " + candidate.sql() + " LIMIT 5001",
            (rs, row) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), args.toArray());
        requireNarrow(rows.size());
        Set<Long> matches = new LinkedHashSet<>();
        for (Candidate row : rows) {
            if (contains(cipher, row.name(), needle) || contains(cipher, row.phone(), needle)
                    || contains(cipher, row.contactPhone(), needle) || contains(cipher, row.guardian(), needle)) {
                matches.add(row.leadId());
            }
        }
        requireNarrow(matches.size());
        return Set.copyOf(matches);
    }

    private static boolean contains(PiiCipher cipher, String encrypted, String needle) {
        return encrypted != null && SavedViewSearchNgrams.contains(cipher.decrypt(encrypted), needle);
    }

    private static void requireNarrow(int count) {
        if (count > 5000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "搜索结果范围过大，请补充更具体的关键词");
        }
    }
}
