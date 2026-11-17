package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.MarkRead;
import cn.siyue.core.OpportunityNeedDtos.Notification;
import cn.siyue.core.OpportunityNeedDtos.NotificationQuery;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpportunityNotificationRepositoryTest {
    private final Actor actor = new Actor(7, "EMP_7", "own", Set.of(), Set.of("crm.opportunity.read"));
    private final NoticeJdbc database = new NoticeJdbc();
    private final OpportunityNotificationRepository repository = new OpportunityNotificationRepository(database);

    @Test void unreadListAndTotalUseRecipientFilterBeforePagination() {
        var page = repository.list(actor, new NotificationQuery("unread", 2, 20));
        assertEquals(21, page.total()); assertEquals(2, page.page()); assertEquals(20, page.size());
        assertTrue(database.countSql.contains("n.recipient_id=? AND n.read_at IS NULL"));
        assertEquals(List.of(7L), database.countArgs);
        assertTrue(database.pageSql.contains("o.owner_id=?"));
        assertTrue(database.pageSql.contains("n.recipient_id=? AND n.read_at IS NULL ORDER BY n.id DESC LIMIT ? OFFSET ?"));
        assertEquals(List.of(7L, 7L, 20, 20L), database.pageArgs);
        assertEquals("OPP_1", page.items().get(0).opportunityId());
        assertFalse(database.pageSql.contains("detail_cipher"));
    }

    @Test void lostResourceScopeReturnsOnlyRecipientShell() {
        database.visible = false;
        var row = repository.list(actor, new NotificationQuery("all", 1, 20)).items().get(0);
        assertEquals("restricted", row.visibility()); assertNull(row.opportunityId()); assertNull(row.consultationId());
        assertEquals("repeat_consultation", row.type()); assertEquals(1, row.version());
        assertTrue(database.pageSql.contains("o.deleted=0"));
        assertTrue(database.pageSql.contains("e.opportunity_id=n.opportunity_id"));
    }

    @Test void allScopeNeverGrantsAnotherRecipientsReminder() {
        Actor admin = new Actor(9, "EMP_9", "all", Set.of(), Set.of("crm.opportunity.read"));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
            () -> repository.get(admin, "NTF_1", true)).code());
        assertTrue(database.pageSql.endsWith("n.public_id=? AND n.recipient_id=? FOR UPDATE"));
        assertEquals(List.of("NTF_1", 9L), database.pageArgs);
    }

    @Test void markReadUsesRecipientAndVersionAndDoesNotAlterSalesFacts() {
        var current = repository.get(actor, "NTF_1", true);
        var receipt = repository.markRead(actor, current, new MarkRead(1));
        assertEquals(2, receipt.version()); assertEquals(NoticeJdbc.READ, receipt.readAt());
        assertEquals(1, database.updates); assertEquals(List.of("NTF_1", 7L, 1L), database.updateArgs);
        assertTrue(database.updateSql.contains("recipient_id=? AND version=? AND read_at IS NULL"));
        assertFalse(database.updateSql.contains("core_opportunity SET"));
    }

    @Test void staleVersionOrConcurrentChangeDoesNotProduceAReceipt() {
        var current = repository.get(actor, "NTF_1", true);
        assertEquals(ErrorCode.PREVIEW_STALE, assertThrows(BusinessException.class,
            () -> repository.markRead(actor, current, new MarkRead(2))).code());
        assertEquals(0, database.updates);
        database.updateResult = 0;
        assertEquals(ErrorCode.PREVIEW_STALE, assertThrows(BusinessException.class,
            () -> repository.markRead(actor, current, new MarkRead(1))).code());
    }

    @Test void alreadyReadCurrentVersionDoesNotChangeTimestampAgain() {
        database.read = true;
        var current = repository.get(actor, "NTF_1", true);
        assertSame(current, repository.markRead(actor, current, new MarkRead(2)));
        assertEquals(0, database.updates);
        repository.list(actor, new NotificationQuery("read", 1, 20));
        assertTrue(database.countSql.contains("n.read_at IS NOT NULL"));
    }

    @Test void replayPreservesCommittedVersionAndTimeButRecomputesVisibility() {
        Notification original = new Notification("NTF_1", "OPP_1", "NEVT_1", "repeat_consultation",
            Instant.EPOCH, NoticeJdbc.READ, 2, "visible");
        Notification current = new Notification("NTF_1", null, null, "repeat_consultation",
            Instant.EPOCH, Instant.now(), 8, "restricted");
        Notification result = repository.redactReplay(original, current);
        assertEquals(2, result.version()); assertEquals(NoticeJdbc.READ, result.readAt());
        assertNull(result.opportunityId()); assertNull(result.consultationId()); assertEquals("restricted", result.visibility());
    }

    private static final class NoticeJdbc extends JdbcTemplate {
        static final Instant READ = Instant.parse("2026-10-14T01:02:03.678Z");
        boolean visible = true, read;
        int updates, updateResult = 1;
        String countSql, pageSql, updateSql;
        List<Object> countArgs, pageArgs, updateArgs;
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            countSql = sql; countArgs = List.of(args); return type.cast(21L);
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            pageSql = sql; pageArgs = List.of(args);
            if (sql.contains("n.public_id=?") && !args[args.length - 1].equals(7L)) { return List.of(); }
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("public_id")).thenReturn("NTF_1");
                when(rs.getString("opportunity_public_id")).thenReturn("OPP_1");
                when(rs.getString("consultation_public_id")).thenReturn("NEVT_1");
                when(rs.getBoolean("opportunity_visible")).thenReturn(visible);
                when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));
                when(rs.getTimestamp("read_at")).thenReturn(read ? Timestamp.from(READ) : null);
                when(rs.getLong("version")).thenReturn(read ? 2L : 1L);
                return List.of(mapper.mapRow(rs, 0));
            } catch (java.sql.SQLException error) { throw new AssertionError(error); }
        }
        @Override public int update(String sql, Object... args) {
            updateSql = sql; updateArgs = List.of(args); updates++;
            if (updateResult == 1) { read = true; }
            return updateResult;
        }
    }
}
