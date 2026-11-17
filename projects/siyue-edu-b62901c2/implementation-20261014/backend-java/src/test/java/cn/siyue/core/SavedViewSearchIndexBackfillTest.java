package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exercises explicit enablement, finite execution and the common expanded-row reservation boundary. */
class SavedViewSearchIndexBackfillTest {
    @Test void budgetAccepts999AcrossGroupsButNeverSplitsOrReservesAThousandthRow() {
        var budget = new SavedViewSearchIndexBackfillRepository.Budget();
        assertTrue(budget.reserve(3));
        assertTrue(budget.reserve(2));
        assertTrue(budget.reserve(992));
        assertFalse(budget.reserve(8));
        assertEquals(2, budget.remaining());
        assertTrue(budget.reserve(2));
        assertEquals(0, budget.remaining());
        assertFalse(budget.reserve(1));
        assertThrows(IllegalStateException.class, () -> budget.reserve(1000));
        assertThrows(IllegalStateException.class, () -> budget.reserve(0));
    }

    @Test void operatorMustEnableAndSupplyPositiveFiniteLimitsBeforeDatabaseWork() {
        var condition = SavedViewSearchIndexBackfill.class.getAnnotation(ConditionalOnProperty.class);
        assertFalse(condition.matchIfMissing());
        assertEquals("true", condition.havingValue());
        var service = mock(SavedViewSearchIndexBackfillService.class);
        for (int[] limits : new int[][]{{200, 0}, {0, 1}, {501, 1}, {1, 10001}}) {
            assertThrows(IllegalStateException.class,
                () -> new SavedViewSearchIndexBackfill(service, limits[0], limits[1]).run(null));
        }
        verifyNoInteractions(service);
    }

    @Test void runnerRejectsOuterTransactionWhichWouldCombineMultipleBatchBudgets() {
        var service = mock(SavedViewSearchIndexBackfillService.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> new SavedViewSearchIndexBackfill(service, 500, 2).run(null));
            verifyNoInteractions(service);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void runnerStopsOnEmptyBatchAndRejectsUnfinishedWork() {
        var service = mock(SavedViewSearchIndexBackfillService.class);
        when(service.processBatch(200)).thenReturn(new SavedViewSearchIndexBackfillService.Batch(0, 0));
        when(service.remainingRows()).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> new SavedViewSearchIndexBackfill(service, 200, 10).run(null));
        verify(service, times(1)).processBatch(200);
    }

    @Test void serviceRejectsUnboundedSelectionAndRepositoryRequiresRealTransaction() {
        var repository = mock(SavedViewSearchIndexBackfillRepository.class);
        var service = new SavedViewSearchIndexBackfillService(repository);
        assertThrows(IllegalArgumentException.class, () -> service.processBatch(0));
        assertThrows(IllegalArgumentException.class, () -> service.processBatch(501));
        verifyNoInteractions(repository);
        assertThrows(IllegalStateException.class,
            () -> new SavedViewSearchIndexBackfillRepository(null, null).processBatch(1));
    }
}
