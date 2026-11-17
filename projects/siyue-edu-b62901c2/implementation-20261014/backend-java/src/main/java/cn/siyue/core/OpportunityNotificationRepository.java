package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.MarkRead;
import cn.siyue.core.OpportunityNeedDtos.Notification;
import cn.siyue.core.OpportunityNeedDtos.NotificationPage;
import cn.siyue.core.OpportunityNeedDtos.NotificationQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Recipient-only reminders never grant access to the underlying opportunity or consultation. */
@Repository
public class OpportunityNotificationRepository {
    private final JdbcTemplate jdbc;
    public OpportunityNotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Counts all recipient reminders, exposing only a shell after opportunity scope is lost. */
    public NotificationPage list(Actor actor, NotificationQuery query) {
        String filter = switch (query.status()) {
            case "unread" -> " AND n.read_at IS NULL";
            case "read" -> " AND n.read_at IS NOT NULL";
            default -> "";
        };
        String where = "n.recipient_id=?" + filter;
        Long total = jdbc.queryForObject("SELECT COUNT(n.id) FROM core_opportunity_notification n WHERE " + where,
            Long.class, actor.id());
        List<Object> values = new ArrayList<>(List.of(actor.id(), query.size(),
            Math.multiplyExact((long) query.page() - 1, query.size())));
        List<Notification> items = select(actor, where, values, " ORDER BY n.id DESC LIMIT ? OFFSET ?");
        return new NotificationPage(items, total == null ? 0 : total, query.page(), query.size());
    }

    /** Checks recipient ownership even for administrators; locking does not require opportunity ownership. */
    public Notification get(Actor actor, String id, boolean lock) {
        List<Notification> rows = select(actor, "n.public_id=? AND n.recipient_id=?",
            List.of(id, actor.id()), lock ? " FOR UPDATE" : "");
        if (rows.isEmpty()) { OpportunityNeedSupport.missing(); }
        return rows.get(0);
    }

    private List<Notification> select(Actor actor, String where, List<Object> values, String suffix) {
        ScopeSql.Clause scope = OpportunityNeedSupport.scope(actor);
        List<Object> args = new ArrayList<>(scope.args());
        args.addAll(values);
        String sql = """
            SELECT n.public_id,n.created_at,n.read_at,n.version,
                   o.public_id AS opportunity_public_id,e.public_id AS consultation_public_id,
                   CASE WHEN o.deleted=0 AND e.opportunity_id=n.opportunity_id
                     AND e.event_type='consultation' AND (
            """ + scope.sql() + """
                     ) THEN 1 ELSE 0 END AS opportunity_visible
            FROM core_opportunity_notification n
            LEFT JOIN core_opportunity o ON o.id=n.opportunity_id
            LEFT JOIN core_opportunity_need_event e ON e.id=n.consultation_id
            WHERE
            """ + where + suffix;
        return jdbc.query(sql, this::map, args.toArray());
    }

    private Notification map(ResultSet rs, int row) throws SQLException {
        boolean visible = rs.getBoolean("opportunity_visible");
        return new Notification(rs.getString("public_id"),
            visible ? rs.getString("opportunity_public_id") : null,
            visible ? rs.getString("consultation_public_id") : null, "repeat_consultation",
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("read_at")),
            rs.getLong("version"), visible ? "visible" : "restricted");
    }

    /** A first read advances the reminder version; repeated current-state reads leave it unchanged. */
    public Notification markRead(Actor actor, Notification current, MarkRead input) {
        if (current.version() != input.expectedVersion()) { OpportunityNeedSupport.stale(); }
        if (current.readAt() != null) { return current; }
        int changed = jdbc.update("""
            UPDATE core_opportunity_notification SET read_at=UTC_TIMESTAMP(3),version=version+1
            WHERE public_id=? AND recipient_id=? AND version=? AND read_at IS NULL
            """, current.id(), actor.id(), input.expectedVersion());
        if (changed != 1) { OpportunityNeedSupport.stale(); }
        return get(actor, current.id(), false);
    }

    /** Keeps the original successful business facts while applying present resource visibility. */
    public Notification redactReplay(Notification receipt, Notification current) {
        return new Notification(receipt.id(), current.opportunityId(), current.consultationId(),
            receipt.type(), receipt.createdAt(), receipt.readAt(), receipt.version(), current.visibility());
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
