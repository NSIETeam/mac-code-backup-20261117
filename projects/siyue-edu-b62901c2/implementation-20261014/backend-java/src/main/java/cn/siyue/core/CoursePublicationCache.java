package cn.siyue.core;

import cn.siyue.core.CoursePublicationDtos.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Caches only immutable snapshots after authorization; never caches mutable access decisions. */
@Component
public class CoursePublicationCache {
    private static final Logger LOG = LoggerFactory.getLogger(CoursePublicationCache.class);
    private final AtomicLong unavailableUntil = new AtomicLong();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final PublicationCacheProperties properties;

    /** Injects Redis, the shared Java-time mapper and explicit cache bounds. */
    public CoursePublicationCache(StringRedisTemplate redis, ObjectMapper mapper,
            PublicationCacheProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** Caller must authorize the course before invoking; database failures remain visible. */
    public Snapshot get(long publicationId, Supplier<Snapshot> loader) {
        if (!properties.enabled() || System.currentTimeMillis() < unavailableUntil.get()) { return loader.get(); }
        String key = "siyue:publication:v1:" + properties.namespace() + ":" + publicationId;
        Snapshot cached = TransactionSynchronizationManager.isActualTransactionActive() ? null : read(key, publicationId);
        if (cached != null) { return cached; }
        Snapshot loaded = loader.get();
        if (loaded != null) { cacheAfterCommit(key, publicationId, loaded); }
        return loaded;
    }

    private Snapshot read(String key, long publicationId) {
        try {
            String value = redis.opsForValue().get(key);
            if (value == null || value.length() > 262144) { return null; }
            Entry entry = mapper.readValue(value, Entry.class);
            if (entry == null || entry.publicationId() != publicationId || entry.snapshot() == null
                    || entry.snapshot().courseVersion() < 1 || entry.snapshot().lessons() == null) { return null; }
            return entry.snapshot();
        } catch (RuntimeException | JsonProcessingException error) {
            unavailable();
            return null;
        }
    }

    private void cacheAfterCommit(String key, long id, Snapshot snapshot) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { write(key, id, snapshot); }
                });
            }
            return;
        }
        write(key, id, snapshot);
    }

    private void write(String key, long id, Snapshot snapshot) {
        if (System.currentTimeMillis() < unavailableUntil.get()) { return; }
        try {
            String value = mapper.writeValueAsString(new Entry(id, snapshot));
            if (value.length() <= 262144) {
                redis.opsForValue().set(key, value, Duration.ofSeconds(properties.ttlSeconds()));
            }
        } catch (RuntimeException | JsonProcessingException error) {
            unavailable();
        }
    }

    private void unavailable() {
        long now = System.currentTimeMillis();
        long previous = unavailableUntil.get();
        if (previous <= now && unavailableUntil.compareAndSet(previous, now + 5000)) {
            LOG.warn("Publication cache unavailable; database fallback enabled for five seconds");
        }
    }

    /** Redis存储的不可变教学版本包，携带版本主键以校验缓存内容与请求一致。 */
    private record Entry(long publicationId, Snapshot snapshot) { }
}
