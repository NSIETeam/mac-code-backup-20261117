package cn.siyue.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finds customer rows through authorized, keyed ngram candidates and verifies decrypted text server-side. */
final class SavedViewCustomerSearch {
    private record NameCandidate(long customerId, String valueCipher) { }
    private record ContactCandidate(long customerId, String nameCipher, String valueCipher) { }

    private SavedViewCustomerSearch() { }

    static Set<Long> matchingIds(JdbcTemplate jdbc, PiiCipher cipher, Actor actor, String rawSearch) {
        String needle = SavedViewSearchNgrams.normalize(rawSearch);
        ScopeSql.Clause scope = ScopeSql.customerReads(actor);
        Set<Long> matches = new LinkedHashSet<>();
        searchNames(jdbc, cipher, scope, needle, matches);
        searchContacts(jdbc, cipher, scope, needle, matches);
        if (matches.size() > 5000) { throw broadSearch(); }
        return Set.copyOf(matches);
    }

    static String addVisibleIds(String column, Set<Long> ids, List<Object> args) {
        if (ids.isEmpty()) { return ""; }
        args.addAll(ids);
        return " OR " + column + " IN (" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
    }

    private static void searchNames(JdbcTemplate jdbc, PiiCipher cipher, ScopeSql.Clause scope,
            String needle, Set<Long> matches) {
        ScopeSql.Clause candidate = SavedViewSearchNgrams.candidate("ni", "search_ngrams", cipher, needle);
        List<Object> args = new ArrayList<>(scope.args()); args.addAll(candidate.args());
        List<NameCandidate> rows = jdbc.query("SELECT c.id,c.display_name_cipher FROM core_customer c "
            + "JOIN core_customer_name_index ni ON ni.customer_id=c.id AND ni.deleted=0 "
            + "WHERE c.deleted=0 AND (" + scope.sql() + ") AND " + candidate.sql() + " LIMIT 5001",
            (rs, row) -> new NameCandidate(rs.getLong(1), rs.getString(2)), args.toArray());
        requireNarrow(rows.size());
        for (NameCandidate row : rows) {
            if (SavedViewSearchNgrams.contains(cipher.decrypt(row.valueCipher()), needle)) {
                matches.add(row.customerId());
            }
        }
    }

    private static void searchContacts(JdbcTemplate jdbc, PiiCipher cipher, ScopeSql.Clause scope,
            String needle, Set<Long> matches) {
        ScopeSql.Clause name = SavedViewSearchNgrams.candidate("ci", "name_search_ngrams", cipher, needle);
        ScopeSql.Clause value = SavedViewSearchNgrams.candidate("ci", "value_search_ngrams", cipher, needle);
        List<Object> args = new ArrayList<>(scope.args()); args.addAll(name.args()); args.addAll(value.args());
        List<ContactCandidate> rows = jdbc.query("SELECT c.id,cc.contact_name_cipher,cc.value_cipher "
            + "FROM core_customer c JOIN core_customer_contact cc ON cc.customer_id=c.id AND cc.deleted=0 "
            + "JOIN core_customer_contact_index ci ON ci.contact_id=cc.id AND ci.deleted=0 "
            + "WHERE c.deleted=0 AND (" + scope.sql() + ") AND (" + name.sql() + " OR " + value.sql() + ") LIMIT 5001",
            (rs, row) -> new ContactCandidate(rs.getLong(1), rs.getString(2), rs.getString(3)), args.toArray());
        requireNarrow(rows.size());
        for (ContactCandidate row : rows) {
            boolean nameMatch = row.nameCipher() != null
                && SavedViewSearchNgrams.contains(cipher.decrypt(row.nameCipher()), needle);
            if (nameMatch || SavedViewSearchNgrams.contains(cipher.decrypt(row.valueCipher()), needle)) {
                matches.add(row.customerId());
            }
        }
    }

    private static void requireNarrow(int candidates) { if (candidates > 5000) { throw broadSearch(); } }

    private static BusinessException broadSearch() {
        return new BusinessException(ErrorCode.INVALID_INPUT, "搜索结果范围过大，请补充更具体的关键词");
    }
}
