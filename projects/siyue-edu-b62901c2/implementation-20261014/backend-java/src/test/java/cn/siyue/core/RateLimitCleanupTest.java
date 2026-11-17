package cn.siyue.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Leaves room for the current hit when expiring operational counters in one transaction. */
class RateLimitCleanupTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void formCleanupAndCurrentHitStayBelowOneThousandRows() {
        new CrmFormRateLimiter(new CrmFormRateRepository(jdbc), cipher).allow(1, "127.0.0.1", Instant.parse("2026-10-17T00:00:00Z"));
        verify(jdbc).update(eq("DELETE FROM core_crm_form_public_rate WHERE window_start<? LIMIT 998"),
            any(Timestamp.class));
    }

    @Test
    void storefrontCleanupAndCurrentHitStayBelowOneThousandRows() {
        new PublicStoreRateLimiter(new PublicStoreRateRepository(jdbc), cipher).allow("public", "127.0.0.1", Instant.parse("2026-10-17T00:00:00Z"));
        verify(jdbc).update(eq("DELETE FROM core_public_store_rate WHERE window_start<? LIMIT 998"),
            any(Timestamp.class));
    }
}
