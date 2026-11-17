package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import cn.siyue.core.CoursePublicationDtos.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Uses only a dedicated authenticated loopback Redis 7 instance; never flushes a database. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_REDIS_PORT",matches="56379")
class CoursePublicationRedisIntegrationTest {
    private final Snapshot snapshot = new Snapshot(2, 1, "Synthetic course", "onsite", "math", "grade4",
        new BigDecimal("600.00"), Instant.parse("2026-10-17T00:00:00Z"), List.of());

    @Test void realRedisRoundTripTtlAndConcurrentHits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CoursePublicationCache cache = fixture.cache;
            assertEquals(snapshot, cache.get(1, () -> snapshot));
            Long ttl = fixture.redis.getExpire(fixture.key(1));
            assertNotNull(ttl); assertTrue(ttl > 0 && ttl <= 30);
            var pool = Executors.newFixedThreadPool(8);
            try {
                var results = new java.util.ArrayList<java.util.concurrent.Future<Snapshot>>();
                for (int i = 0; i < 64; i++) {
                    results.add(pool.submit(() -> cache.get(1, () -> { throw new AssertionError("Warm cache missed"); })));
                }
                for (var result : results) { assertEquals(snapshot, result.get(10, TimeUnit.SECONDS)); }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
            assertEquals(600, cache.get(1, () -> snapshot).price().intValueExact());
        }
    }

    @Test void realRedisDoesNotReceiveRolledBackSnapshot() {
        try (Fixture fixture = new Fixture()) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                fixture.cache.get(1, () -> snapshot);
                assertNull(fixture.redis.opsForValue().get(fixture.key(1)));
                TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
                assertNull(fixture.redis.opsForValue().get(fixture.key(1)));
            } finally { TransactionSynchronizationManager.clear(); }
        }
    }

    @Test void realRedisNamespaceIsolationAndMalformedValueFallback() {
        try (Fixture fixture = new Fixture()) {
            fixture.redis.opsForValue().set(fixture.key(1), "malformed", Duration.ofSeconds(30));
            assertEquals(snapshot, fixture.cache.get(1, () -> snapshot));
            var other = new CoursePublicationCache(fixture.redis, new ObjectMapper().findAndRegisterModules(),
                new PublicationCacheProperties(true, fixture.namespace + "other", 30));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            assertNull(other.get(1, () -> { calls.incrementAndGet(); return null; }));
            assertEquals(1, calls.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String namespace = "isolated_" + UUID.randomUUID().toString().replace("-", "");
        final LettuceConnectionFactory factory;
        final StringRedisTemplate redis;
        final CoursePublicationCache cache;
        Fixture() {
            String password = System.getenv("SIYUE_TEST_REDIS_PASSWORD");
            assertNotNull(password); assertTrue(password.length() >= 32);
            var configuration = new RedisStandaloneConfiguration("127.0.0.1", 56379);
            configuration.setPassword(RedisPassword.of(password));
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build();
            factory = new LettuceConnectionFactory(configuration, client);
            factory.afterPropertiesSet(); factory.start();
            redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
            try (var connection = factory.getConnection()) {
                var info = connection.serverCommands().info("server");
                assertNotNull(info); assertTrue(info.getProperty("redis_version").startsWith("7."));
            }
            cache = new CoursePublicationCache(redis, new ObjectMapper().findAndRegisterModules(),
                new PublicationCacheProperties(true, namespace, 30));
        }
        String key(long id) { return "siyue:publication:v1:" + namespace + ":" + id; }
        @Override public void close() { redis.delete(key(1)); factory.destroy(); }
    }
}
