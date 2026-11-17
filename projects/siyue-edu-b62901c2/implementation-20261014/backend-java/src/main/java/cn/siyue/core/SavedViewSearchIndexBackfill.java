package cn.siyue.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs only explicitly enabled, bounded search-index backfills; each service call commits one resumable batch. */
@Component
@ConditionalOnProperty(name = "siyue.crm.saved-view-search-index.backfill.enabled", havingValue = "true")
public class SavedViewSearchIndexBackfill implements ApplicationRunner {
    /** 本次有界启动补建已提交批次数与处理源记录数；源记录数不等同实际SQL写行预算。 */
    private record BackfillResult(int batches, int processed) { }
    private static final Logger LOG = LoggerFactory.getLogger(SavedViewSearchIndexBackfill.class);
    private final SavedViewSearchIndexBackfillService service;
    private final int batchSize;
    private final int maxBatches;

    /** Wires the transaction-proxied service and operator-selected finite limits.
     * @param service one transaction per batch; the runner does not open an encompassing transaction
     * @param batchSize source/index selection bound per kind, validated before any work
     * @param maxBatches explicit maximum batches; the default zero refuses execution
     */
    public SavedViewSearchIndexBackfill(SavedViewSearchIndexBackfillService service,
            @Value("${siyue.crm.saved-view-search-index.backfill.batch-size:200}") int batchSize,
            @Value("${siyue.crm.saved-view-search-index.backfill.max-batches:0}") int maxBatches) {
        this.service = service;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    /** Runs finite atomic batches, keeping committed batches available for a later resume.
     * @param arguments application arguments; limits come exclusively from validated configuration
     * @throws IllegalStateException if limits are missing/outside bounds, source indexing fails, or work remains
     * @throws BusinessException if normalized search content is empty
     * @throws org.springframework.dao.DataAccessException if a database read or write fails
     */
    @Override
    public void run(ApplicationArguments arguments) {
        if (batchSize < 1 || batchSize > 500 || maxBatches < 1 || maxBatches > 10000) {
            throw new IllegalStateException("Saved-view index backfill requires explicit bounded limits");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Saved-view backfill runner cannot join an encompassing transaction");
        }
        BackfillResult result = runBatches();
        int remaining = service.remainingRows();
        LOG.info("Saved-view index backfill completed batches={} processed={} remaining={}",
            result.batches(), result.processed(), remaining);
        if (remaining > 0) {
            throw new IllegalStateException("Saved-view index backfill reached its configured batch limit");
        }
    }

    private BackfillResult runBatches() {
        int processed = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            SavedViewSearchIndexBackfillService.Batch count = service.processBatch(batchSize);
            if (count.processed() == 0) { return new BackfillResult(batch, processed); }
            processed += count.processed();
        }
        return new BackfillResult(maxBatches, processed);
    }
}
