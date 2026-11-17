package cn.siyue.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists temporary storefront rate windows without taking ownership of transaction boundaries. */
@Repository
public class PublicStoreRateRepository {
    private final JdbcTemplate jdbc;
    /** @param jdbc parameterized SQL access */
    public PublicStoreRateRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    /**
     * @param bucket service-validated public or submission bucket
     * @param source keyed source fingerprint, never a raw address
     * @param now current UTC observation time
     * @return minute hit count including this attempt
     * @throws org.springframework.dao.DataAccessException if counter persistence fails
     */
    public Integer hit(String bucket,byte[] source,Instant now) {
        Instant window=now.truncatedTo(ChronoUnit.MINUTES);
        jdbc.update("INSERT INTO core_public_store_rate(source_hash,bucket,window_start,request_count) "
            + "VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE request_count=request_count+1,"
            + "updated_at=UTC_TIMESTAMP(3)",source,bucket,Timestamp.from(window));
        Integer count=jdbc.queryForObject("SELECT request_count FROM core_public_store_rate "
            + "WHERE source_hash=? AND bucket=? AND window_start=?",Integer.class,source,bucket,Timestamp.from(window));
        // Keep expiration bounded independently of the size of the traffic history.
        jdbc.update("DELETE FROM core_public_store_rate WHERE window_start<? LIMIT 998",
            Timestamp.from(window.minus(1,ChronoUnit.HOURS)));
        return count;
    }
}
