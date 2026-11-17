package cn.siyue.core;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies REQUIRED transaction boundaries to bounded search-index batches and read-only progress queries. */
@Service
public class SavedViewSearchIndexBackfillService {
    /** Counts completed source groups and the reserved upper bound on modified index rows in one transaction. */
    public record Batch(int processed, int reservedWrites) { }
    private final SavedViewSearchIndexBackfillRepository repository;

    /** Wires the repository that locks and indexes encrypted source rows.
     * @param repository missing-index selection and bounded writes
     */
    public SavedViewSearchIndexBackfillService(SavedViewSearchIndexBackfillRepository repository) {
        this.repository = repository;
    }

    /** Processes one atomic bounded batch; a failed source group rolls back all writes in this batch.
     * @param batchSize maximum source/index rows to select per kind, from 1 through 500
     * @return completed source count and reserved write count (at most 999)
     * @throws IllegalArgumentException if the source-row bound is outside its supported range
     * @throws IllegalStateException if decryption/HMAC fails, a key is unavailable, or a source group cannot fit within 999 rows
     * @throws BusinessException if normalized search content is empty
     * @throws org.springframework.dao.DataAccessException if a database read or write fails
     */
    @Transactional(rollbackFor = Exception.class)
    public Batch processBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 500) { throw new IllegalArgumentException("Invalid backfill batch size"); }
        return repository.processBatch(batchSize);
    }

    /** Reads unfinished source/index counts without acquiring a write transaction.
     * @return unfinished rows across customer names, contacts, leads and tickets
     * @throws org.springframework.dao.DataAccessException if a progress-count query fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public int remainingRows() { return repository.remainingRows(); }
}
