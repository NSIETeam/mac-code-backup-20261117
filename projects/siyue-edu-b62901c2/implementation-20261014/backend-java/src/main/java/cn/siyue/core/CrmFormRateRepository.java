package cn.siyue.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists anonymous form rate counters within the caller's independent transaction. */
@Repository
public class CrmFormRateRepository {
    private final JdbcTemplate jdbc;
    /** @param jdbc parameterized SQL access */
    public CrmFormRateRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    /**
     * @param formId internal form identifier
     * @param sourceHash keyed fingerprint of the source address; never the address itself
     * @param now current UTC observation time
     * @return hit count for the minute, including this attempt
     * @throws org.springframework.dao.DataAccessException if the counter cannot be persisted
     */
    public Integer hit(long formId,byte[] sourceHash,Instant now) {
        Instant window=now.truncatedTo(ChronoUnit.MINUTES);
        jdbc.update("""
            INSERT INTO core_crm_form_public_rate(form_id,source_hash,window_start,request_count)
            VALUES (?,?,?,1) ON DUPLICATE KEY UPDATE request_count=request_count+1,updated_at=UTC_TIMESTAMP(3)
            """,formId,sourceHash,Timestamp.from(window));
        Integer count=jdbc.queryForObject("SELECT request_count FROM core_crm_form_public_rate WHERE form_id=? AND source_hash=? AND window_start=?",
            Integer.class,formId,sourceHash,Timestamp.from(window));
        // One hit plus at most 998 expired operational counters stays below 1000 affected rows.
        jdbc.update("DELETE FROM core_crm_form_public_rate WHERE window_start<? LIMIT 998",
            Timestamp.from(window.minus(1,ChronoUnit.HOURS)));
        return count;
    }
}
