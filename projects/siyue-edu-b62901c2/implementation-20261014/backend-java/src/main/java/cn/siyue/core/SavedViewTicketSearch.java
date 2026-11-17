package cn.siyue.core;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Indexes and verifies encrypted service-ticket titles for imported saved-view searches. */
final class SavedViewTicketSearch {
    private record Candidate(long id, String titleCipher) { }
    record Query(String search, List<Object> args, ScopeSql.Clause scope) { }

    private SavedViewTicketSearch() { }

    static void save(JdbcTemplate jdbc, PiiCipher cipher, long ticketId, String title) {
        SavedViewSearchNgrams.encode(cipher, title).forEach((key, json) -> jdbc.update(
            "INSERT INTO core_crm_service_ticket_search_index(ticket_id,key_id,search_ngrams) VALUES(?,?,?)",
            ticketId, key, json));
    }

    static String visibleTitleIds(JdbcTemplate jdbc, PiiCipher cipher, Query query) {
        if (query.search() == null || query.search().isBlank()) { return ""; }
        ScopeSql.Clause candidate = SavedViewSearchNgrams.candidate("si", "search_ngrams", cipher, query.search());
        List<Object> candidateArgs = new ArrayList<>(query.scope().args()); candidateArgs.addAll(candidate.args());
        List<Candidate> rows = jdbc.query("SELECT t.id,t.title_cipher FROM core_crm_service_ticket t "
            + "JOIN core_crm_service_ticket_search_index si ON si.ticket_id=t.id AND si.deleted=0 "
            + "WHERE t.deleted=0 AND (" + query.scope().sql() + ") AND " + candidate.sql() + " LIMIT 5001",
            (rs, row) -> new Candidate(rs.getLong("id"), rs.getString("title_cipher")), candidateArgs.toArray());
        if (rows.size() > 5000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "搜索结果范围过大，请补充更具体的关键词");
        }
        List<Long> matches = rows.stream().filter(row -> SavedViewSearchNgrams.contains(
            cipher.decrypt(row.titleCipher()), query.search())).map(Candidate::id).toList();
        if (matches.isEmpty()) { return ""; }
        query.args().addAll(matches);
        return " OR t.id IN (" + String.join(",", java.util.Collections.nCopies(matches.size(), "?")) + ")";
    }
}
