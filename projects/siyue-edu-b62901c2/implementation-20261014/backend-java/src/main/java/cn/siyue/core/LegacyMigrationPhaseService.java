package cn.siyue.core;

import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns one offline migration phase's database transaction, including its immutable checkpoint.
 * Importers validate source authorization and their fewer-than-1000-row budget before invoking this service.
 * REQUIRED preserves the former TransactionTemplate behavior: an existing transaction is joined.
 * The candidate CLI invokes phases sequentially without an enclosing transaction.
 */
@Service
public class LegacyMigrationPhaseService {
    /**
     * Executes only the current phase's local database work, atomically with its checkpoint.
     * @param phase validated, bounded import work; must not perform HTTP, RPC or file I/O
     * @return the phase's imported business-row count, or its identical prior checkpoint count
     * @throws IllegalStateException if called without the annotation transaction proxy
     * @throws RuntimeException propagates source, constraint and database failures to trigger rollback
     */
    @Transactional(rollbackFor = Exception.class)
    public int execute(IntSupplier phase) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Migration phase requires a Service transaction proxy");
        }
        return phase.getAsInt();
    }
}
