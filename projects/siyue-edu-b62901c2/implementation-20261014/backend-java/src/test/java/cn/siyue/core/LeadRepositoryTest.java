package cn.siyue.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeadRepositoryTest {
    private final Actor actor = new Actor(7, "seller", "own", Set.of(2L), Set.of());
    private final CrmDtos.LeadInput input = new CrmDtos.LeadInput("CUS_1", "ACTIVITY_1", null,
        "front_desk", "活动咨询", "数学", "2026秋", null, null, null);

    @Test void sameCampusActivityUsesInternalIdsEvenWhenPublicIdsAreDifferentStrings() {
        var repository = new LeadRepository(new CampusJdbc(2L), mock(PiiCipher.class));
        assertDoesNotThrow(() -> repository.authorizeCreate(actor, input));
    }

    @Test void activityFromAnotherCampusIsRejected() {
        var repository = new LeadRepository(new CampusJdbc(3L), mock(PiiCipher.class));
        BusinessException error = assertThrows(BusinessException.class,
            () -> repository.authorizeCreate(actor, input));
        assertTrue(error.getMessage().contains("同一校区"));
    }

    @Test void globalActivityHasSqlNullCampusAndDoesNotPretendToBeAnotherCampus() {
        var repository = new LeadRepository(new CampusJdbc(null), mock(PiiCipher.class));
        assertDoesNotThrow(() -> repository.authorizeCreate(actor, input));
    }

    private static final class CampusJdbc extends JdbcTemplate {
        private final Long activityCampus;
        CampusJdbc(Long activityCampus) { this.activityCampus = activityCampus; }

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            assertTrue(sql.contains("FROM core_customer"));
            return type.cast(2L);
        }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            try {
                ResultSet row = mock(ResultSet.class);
                if (sql.contains("FROM core_activity a")) {
                    when(row.getLong("id")).thenReturn(9L);
                    when(row.getObject("campus_id", Long.class)).thenReturn(activityCampus);
                    when(row.getString("campus_public_id")).thenReturn("CAMPUS_" + activityCampus);
                } else {
                    assertTrue(sql.contains("FROM core_customer c"));
                    when(row.getLong(1)).thenReturn(4L);
                }
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException error) { throw new AssertionError(error); }
        }
    }
}
