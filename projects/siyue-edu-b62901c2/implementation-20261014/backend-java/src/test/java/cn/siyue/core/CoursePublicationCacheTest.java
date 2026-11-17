package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.siyue.core.CoursePublicationDtos.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CoursePublicationCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Snapshot snapshot = new Snapshot(2, 1, "Synthetic", "onsite", "math", "grade4",
        new BigDecimal("600.00"), Instant.parse("2026-10-17T00:00:00Z"), List.of());
    private CoursePublicationCache cache(boolean enabled) {
        when(redis.opsForValue()).thenReturn(values);
        return new CoursePublicationCache(redis, mapper,
            new PublicationCacheProperties(enabled, "test_dataset_01", 300));
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    @Test void disabledNeverContactsRedis() {
        assertSame(snapshot, cache(false).get(1, () -> snapshot));
        verifyNoInteractions(values);
    }
    @Test void hitBypassesOnlySnapshotLoader() throws Exception {
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(
            java.util.Map.of("publicationId", 1, "snapshot", snapshot)));
        assertEquals(snapshot, cache(true).get(1, () -> { throw new AssertionError("Unexpected SQL"); }));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
    @Test void missWritesBoundedNamespacedValue() {
        assertSame(snapshot, cache(true).get(1, () -> snapshot));
        verify(values).set(eq("siyue:publication:v1:test_dataset_01:1"), anyString(), eq(Duration.ofSeconds(300)));
    }
    @Test void redisFailureFallsBackToDatabase() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        assertSame(snapshot, cache(true).get(1, () -> snapshot));
    }
    @Test void malformedOrWrongIdentityFallsBack() {
        when(values.get(anyString())).thenReturn("not json", "{\"publicationId\":2,\"snapshot\":null}");
        var cache = cache(true);
        assertSame(snapshot, cache.get(1, () -> snapshot));
        assertSame(snapshot, cache.get(1, () -> snapshot));
    }
    @Test void databaseFailureIsNotSwallowed() {
        assertThrows(IllegalArgumentException.class, () -> cache(true).get(1,
            () -> { throw new IllegalArgumentException("database unavailable"); }));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
    @Test void writeOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertSame(snapshot, cache(true).get(1, () -> snapshot));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(values).set(anyString(), anyString(), eq(Duration.ofSeconds(300)));
    }
    @Test void rollbackDoesNotPopulateCache() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        cache(true).get(1, () -> snapshot);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
    @Test void cacheFailureSuppressesRepeatedNetworkAttempts() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        var cache = cache(true);
        for (int i = 0; i < 10; i++) { assertSame(snapshot, cache.get(1, () -> snapshot)); }
        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
    @Test void activeDatabaseTransactionNeverPerformsRemoteCacheRead() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertSame(snapshot, cache(true).get(1, () -> snapshot));
        verifyNoInteractions(values);
    }
    @Test void enabledNeedsDatasetIsolationAndBoundedTtl() {
        assertThrows(IllegalArgumentException.class, () -> new PublicationCacheProperties(true, "", 300));
        assertThrows(IllegalArgumentException.class, () -> new PublicationCacheProperties(true, "test_dataset", 0));
        assertThrows(IllegalArgumentException.class, () -> new PublicationCacheProperties(true, "test_dataset", 3601));
    }
}
