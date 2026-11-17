package cn.siyue.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Locks missing substring indexes and writes complete source groups under the caller's bounded transaction. */
@Repository
public class SavedViewSearchIndexBackfillRepository {
    /** 客户或联系人待补建索引行；包含所属资源、当前密钥版本及需解密的源值。 */
    private record IndexRow(long ownerId, String keyId, String valueCipher, String nameCipher) { }
    /** 固定白名单索引表、所属主键列和目标索引列组合，不接受请求方提供SQL标识符。 */
    private record IndexTarget(String table, String ownerColumn, String targetColumn) { }
    /** 工单标题搜索补建的内部主键及源标题密文。 */
    private record TicketRow(long id, String titleCipher) { }
    /** 线索搜索补建的四个来源字段与线索主键，保持姓名、本人电话、联系人电话和监护人区分。 */
    private record LeadRow(long id, String name, String phone, String contactPhone, String guardian) { }
    /** 准备写入的线索字段名、密钥版本和HMAC片段JSON，供写预算与批次执行使用。 */
    private record LeadIndex(String field, String key, String json) { }

    /** Tracks a conservative row-write upper bound, shared across every index kind and configured key. */
    static final class Budget {
        static final int LIMIT = 999;
        private int used;
        int remaining() { return LIMIT - used; }
        boolean reserve(int rows) {
            if (rows < 1 || rows > LIMIT) {
                throw new IllegalStateException("Saved-view index source group exceeds the transaction write budget");
            }
            if (rows > remaining()) { return false; }
            used += rows;
            return true;
        }
    }

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    /** Wires database access and the existing encryption/search-key ring.
     * @param jdbc database access participating in the service transaction
     * @param cipher decryptor and HMAC key ring; plaintext is never persisted by this repository
     */
    public SavedViewSearchIndexBackfillRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Backfills complete source groups, reserving at most 999 row writes across all four kinds.
     * @param batchSize maximum source rows selected per kind, validated by the service
     * @return number of completed source/index rows and reserved row writes
     * @throws IllegalStateException if no transaction is active, a source group is unindexable or exceeds the budget,
     *     ciphertext authentication fails, or an existing index refers to an unavailable encryption key
     * @throws BusinessException if normalized search content is empty
     * @throws org.springframework.dao.DataAccessException if a database read or write fails
     */
    public SavedViewSearchIndexBackfillService.Batch processBatch(int batchSize) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Saved-view index backfill requires a service transaction");
        }
        Budget budget = new Budget();
        int processed = backfillNames(batchSize, budget);
        processed += backfillContacts(batchSize, budget);
        processed += backfillLeads(batchSize, budget);
        processed += backfillTickets(batchSize, budget);
        return new SavedViewSearchIndexBackfillService.Batch(processed, budget.used);
    }

    private int backfillNames(int batchSize, Budget budget) {
        List<IndexRow> rows = jdbc.query("SELECT ni.customer_id,ni.key_id,c.display_name_cipher,NULL "
            + "FROM core_customer_name_index ni JOIN core_customer c ON c.id=ni.customer_id "
            + "WHERE ni.deleted=0 AND c.deleted=0 AND ni.search_ngrams IS NULL "
            + "ORDER BY ni.customer_id,ni.key_id LIMIT ? FOR UPDATE",
            (rs, row) -> new IndexRow(rs.getLong(1), rs.getString(2), rs.getString(3), null), Math.min(batchSize, budget.remaining()));
        for (IndexRow row : rows) {
            budget.reserve(1);
            updateIndex(new IndexTarget("core_customer_name_index", "customer_id", "search_ngrams"), row);
        }
        return rows.size();
    }

    private int backfillContacts(int batchSize, Budget budget) {
        List<IndexRow> rows = jdbc.query("SELECT ci.contact_id,ci.key_id,cc.value_cipher,cc.contact_name_cipher "
            + "FROM core_customer_contact_index ci JOIN core_customer_contact cc ON cc.id=ci.contact_id "
            + "WHERE ci.deleted=0 AND cc.deleted=0 AND (ci.value_search_ngrams IS NULL "
            + "OR (cc.contact_name_cipher IS NOT NULL AND ci.name_search_ngrams IS NULL)) "
            + "ORDER BY ci.contact_id,ci.key_id LIMIT ? FOR UPDATE",
            (rs, row) -> new IndexRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), Math.min(batchSize, budget.remaining()));
        for (IndexRow row : rows) { budget.reserve(1); updateContact(row); }
        return rows.size();
    }

    private void updateContact(IndexRow row) {
        String valueJson = valueForKey(row.keyId(), cipher.decrypt(row.valueCipher()));
        String nameJson = row.nameCipher() == null ? null : valueForKey(row.keyId(), cipher.decrypt(row.nameCipher()));
        jdbc.update("UPDATE core_customer_contact_index SET value_search_ngrams=COALESCE(value_search_ngrams,?),"
            + "name_search_ngrams=COALESCE(name_search_ngrams,?),updated_at=UTC_TIMESTAMP(3) "
            + "WHERE contact_id=? AND key_id=? AND deleted=0", valueJson, nameJson, row.ownerId(), row.keyId());
    }

    private int backfillTickets(int batchSize, Budget budget) {
        List<TicketRow> rows = jdbc.query("SELECT t.id,t.title_cipher FROM core_crm_service_ticket t "
            + "WHERE t.deleted=0 AND NOT EXISTS (SELECT si.id FROM core_crm_service_ticket_search_index si "
            + "WHERE si.ticket_id=t.id AND si.deleted=0) ORDER BY t.id LIMIT ? FOR UPDATE",
            (rs, row) -> new TicketRow(rs.getLong(1), rs.getString(2)), Math.min(batchSize, budget.remaining()));
        int processed = 0;
        for (TicketRow row : rows) {
            Map<String, String> indexes = SavedViewSearchNgrams.encode(cipher, cipher.decrypt(row.titleCipher()));
            if (!budget.reserve(indexes.size())) { break; }
            saveTicket(row, indexes);
            processed++;
        }
        return processed;
    }

    private int backfillLeads(int batchSize, Budget budget) {
        List<LeadRow> rows = jdbc.query("SELECT l.id,l.name_cipher,l.phone_cipher,l.contact_phone_cipher,"
            + "l.guardian_name_cipher FROM core_crm_lead l WHERE l.deleted=0 AND "
            + "(l.name_cipher IS NOT NULL OR l.phone_cipher IS NOT NULL OR l.contact_phone_cipher IS NOT NULL "
            + "OR l.guardian_name_cipher IS NOT NULL) AND NOT EXISTS (SELECT si.id FROM "
            + "core_crm_lead_search_index si WHERE si.lead_id=l.id AND si.deleted=0) ORDER BY l.id LIMIT ? FOR UPDATE",
            (rs, row) -> new LeadRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5)), Math.min(batchSize, budget.remaining()));
        int processed = 0;
        for (LeadRow row : rows) {
            List<LeadIndex> indexes = leadIndexes(row);
            if (!budget.reserve(indexes.size())) { break; }
            for (LeadIndex index : indexes) {
                jdbc.update("INSERT INTO core_crm_lead_search_index(lead_id,field_name,key_id,search_ngrams) "
                    + "VALUES(?,?,?,?)", row.id(), index.field(), index.key(), index.json());
            }
            processed++;
        }
        return processed;
    }

    private List<LeadIndex> leadIndexes(LeadRow row) {
        List<LeadIndex> indexes = new ArrayList<>();
        addLeadIndexes(indexes, "name", row.name());
        addLeadIndexes(indexes, "phone", row.phone());
        addLeadIndexes(indexes, "contact_phone", row.contactPhone());
        addLeadIndexes(indexes, "guardian", row.guardian());
        if (indexes.isEmpty()) { throw new IllegalStateException("Saved-view lead has no indexable fields"); }
        return indexes;
    }

    private void addLeadIndexes(List<LeadIndex> indexes, String field, String encrypted) {
        String value = decrypt(encrypted);
        if (value == null || value.isBlank()) { return; }
        SavedViewSearchNgrams.encode(cipher, value).forEach((key, json) -> indexes.add(new LeadIndex(field, key, json)));
    }

    private void saveTicket(TicketRow row, Map<String, String> indexes) {
        indexes.forEach((key, json) -> jdbc.update("INSERT IGNORE INTO core_crm_service_ticket_search_index "
            + "(ticket_id,key_id,search_ngrams) VALUES(?,?,?)", row.id(), key, json));
    }

    private void updateIndex(IndexTarget target, IndexRow row) {
        String json = valueForKey(row.keyId(), cipher.decrypt(row.valueCipher()));
        jdbc.update("UPDATE " + target.table() + " SET " + target.targetColumn() + "=?,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE " + target.ownerColumn() + "=? AND key_id=? AND deleted=0 AND "
            + target.targetColumn() + " IS NULL", json, row.ownerId(), row.keyId());
    }

    private String valueForKey(String keyId, String value) {
        String json = SavedViewSearchNgrams.encode(cipher, value).get(keyId);
        if (json == null) { throw new IllegalStateException("Saved-view index key is unavailable"); }
        return json;
    }

    /** Counts source/index rows still requiring work, without decrypting their contents.
     * @return number of unfinished source/index rows across all four kinds
     * @throws org.springframework.dao.DataAccessException if a progress-count query fails
     */
    public int remainingRows() {
        Integer names = jdbc.queryForObject("SELECT COUNT(*) FROM core_customer_name_index ni "
            + "JOIN core_customer c ON c.id=ni.customer_id WHERE ni.deleted=0 AND c.deleted=0 "
            + "AND ni.search_ngrams IS NULL", Integer.class);
        Integer contacts = jdbc.queryForObject("SELECT COUNT(*) FROM core_customer_contact_index ci "
            + "JOIN core_customer_contact cc ON cc.id=ci.contact_id WHERE ci.deleted=0 AND cc.deleted=0 "
            + "AND (ci.value_search_ngrams IS NULL OR (cc.contact_name_cipher IS NOT NULL "
            + "AND ci.name_search_ngrams IS NULL))", Integer.class);
        Integer tickets = jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_service_ticket t "
            + "WHERE t.deleted=0 AND NOT EXISTS (SELECT si.id FROM core_crm_service_ticket_search_index si "
            + "WHERE si.ticket_id=t.id AND si.deleted=0)", Integer.class);
        Integer leads = jdbc.queryForObject("SELECT COUNT(*) FROM core_crm_lead l WHERE l.deleted=0 "
            + "AND (l.name_cipher IS NOT NULL OR l.phone_cipher IS NOT NULL OR l.contact_phone_cipher IS NOT NULL "
            + "OR l.guardian_name_cipher IS NOT NULL) AND NOT EXISTS (SELECT si.id FROM "
            + "core_crm_lead_search_index si WHERE si.lead_id=l.id AND si.deleted=0)", Integer.class);
        return safe(names) + safe(contacts) + safe(leads) + safe(tickets);
    }

    private int safe(Integer count) { return count == null ? 0 : count; }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
}
