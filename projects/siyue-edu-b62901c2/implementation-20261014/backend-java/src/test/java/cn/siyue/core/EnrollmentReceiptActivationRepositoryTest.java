package cn.siyue.core;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrollmentReceiptActivationRepositoryTest {
    @Test void activatesOnlyFullyReceivedDirectPendingEnrollmentWithFrozenTerms() {
        ActivationJdbc jdbc = new ActivationJdbc("received", "100.00", "100.00", "0.00", false,
            "pending", 3, "100.00");

        new EnrollmentReceiptActivationRepository(jdbc).activateAfterFullReceipt(42);

        assertEquals(1, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("status='active'"));
        assertTrue(jdbc.queries.stream().anyMatch(sql -> sql.contains("core_enrollment_lesson")));
        assertTrue(jdbc.queries.stream().noneMatch(sql -> sql.contains("core_crm_order_class_terms")));
    }

    @Test void partialReceiptAndCheckoutNeverActivatePendingEnrollment() {
        assertNoActivation(new ActivationJdbc("partially_received", "100.00", "50.00", "0.00",
            false, "pending", 3, "100.00"));
        assertNoActivation(new ActivationJdbc("received", "100.00", "100.00", "0.00", true,
            "pending", 3, "100.00"));
        assertNoActivation(new ActivationJdbc("received", "100.00", "100.00", "10.00", false,
            "pending", 3, "100.00"));
    }

    @Test void withdrawnEnrollmentIsNeverRevived() {
        ActivationJdbc jdbc = new ActivationJdbc("received", "100.00", "100.00", "0.00", false,
            "withdrawn", 3, "100.00");

        new EnrollmentReceiptActivationRepository(jdbc).activateAfterFullReceipt(42);

        assertTrue(jdbc.updates.isEmpty());
        assertTrue(jdbc.queries.stream().noneMatch(sql -> sql.contains("core_enrollment_lesson")));
    }

    @Test void refusesPendingEnrollmentWithoutImmutableLessonSnapshot() {
        ActivationJdbc jdbc = new ActivationJdbc("received", "100.00", "100.00", "0.00", false,
            "pending", 0, "0.00");

        BusinessException error = assertThrows(BusinessException.class,
            () -> new EnrollmentReceiptActivationRepository(jdbc).activateAfterFullReceipt(42));

        assertEquals(ErrorCode.INVALID_INPUT, error.code());
        assertTrue(jdbc.updates.isEmpty());
    }

    private void assertNoActivation(ActivationJdbc jdbc) {
        new EnrollmentReceiptActivationRepository(jdbc).activateAfterFullReceipt(42);
        assertTrue(jdbc.updates.isEmpty());
    }

    private static final class ActivationJdbc extends JdbcTemplate {
        private final String status;
        private final BigDecimal total;
        private final BigDecimal received;
        private final BigDecimal refunded;
        private final boolean checkout;
        private final String enrollmentStatus;
        private final int lessonCount;
        private final BigDecimal lessonTotal;
        private final List<String> queries = new ArrayList<>();
        private final List<String> updates = new ArrayList<>();

        ActivationJdbc(String status, String total, String received, String refunded, boolean checkout,
                String enrollmentStatus, int lessonCount, String lessonTotal) {
            this.status = status;
            this.total = new BigDecimal(total);
            this.received = new BigDecimal(received);
            this.refunded = new BigDecimal(refunded);
            this.checkout = checkout;
            this.enrollmentStatus = enrollmentStatus;
            this.lessonCount = lessonCount;
            this.lessonTotal = new BigDecimal(lessonTotal);
        }

        @Override public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            try {
                ResultSet rs = mock(ResultSet.class);
                if (sql.contains("FROM core_crm_order")) {
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getBigDecimal("total_amount")).thenReturn(total);
                    when(rs.getBigDecimal("received_amount")).thenReturn(received);
                    when(rs.getBigDecimal("refunded_amount")).thenReturn(refunded);
                    when(rs.getObject("commerce_checkout_id")).thenReturn(checkout ? 17L : null);
                } else {
                    when(rs.getInt("lesson_count")).thenReturn(lessonCount);
                    when(rs.getBigDecimal("lesson_total")).thenReturn(lessonTotal);
                }
                return mapper.mapRow(rs, 0);
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            if (!sql.contains("FROM core_enrollment WHERE")) { return List.of(); }
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("id")).thenReturn(9L);
                when(rs.getLong("class_id")).thenReturn(3L);
                when(rs.getString("status")).thenReturn(enrollmentStatus);
                return List.of(mapper.mapRow(rs, 0));
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override public int update(String sql, Object... args) {
            updates.add(sql);
            return 1;
        }
    }
}
