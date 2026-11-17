package cn.siyue.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists activity participants/promoters in the caller's existing CRM transaction.
 * This helper owns no transaction boundary and retains the original campus and member checks.
 */
final class CrmActivityMemberships {
    /** Current public member identifiers grouped by their activity role. */
    record MemberLists(List<String> participants, List<String> promoters) { }
    private final JdbcTemplate jdbc;
    /** Uses the caller-owned JDBC connection; no independent transaction is started.
     * @param jdbc transaction-bound MySQL access
     */
    CrmActivityMemberships(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** Loads both member roles for a nonempty set of already authorized activities.
     * @param activityIds internal activity identifiers from scoped queries
     * @return immutable member lists per requested activity, preserving membership row order
     */
    Map<Long, MemberLists> read(List<Long> activityIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(activityIds.size(), "?"));
        Map<Long, List<String>> participants = new HashMap<>();
        Map<Long, List<String>> promoters = new HashMap<>();
        jdbc.query("""
            SELECT am.activity_id,am.member_role,p.public_id
            FROM core_activity_member am JOIN core_principal p ON p.id = am.principal_id
            WHERE am.deleted = 0 AND am.activity_id IN (
            """ + placeholders + ") ORDER BY am.id", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                Map<Long, List<String>> target = rs.getString("member_role").equals("participant")
                    ? participants : promoters;
                target.computeIfAbsent(rs.getLong("activity_id"), ignored -> new ArrayList<>())
                    .add(rs.getString("public_id"));
            }, activityIds.toArray());
        Map<Long, MemberLists> result = new HashMap<>();
        for (Long id : activityIds) {
            result.put(id, new MemberLists(List.copyOf(participants.getOrDefault(id, List.of())),
                List.copyOf(promoters.getOrDefault(id, List.of()))));
        }
        return result;
    }
    /** Checks duplicate identifiers, locks eligible members and inserts one role in input order.
     * @param actor current actor and campus-management scope
     * @param campusId activity campus identifier
     * @param activityId newly inserted activity identifier
     * @param principals validated public member identifiers
     * @param role participant or promoter
     * @throws BusinessException if identifiers repeat or member/caller campus authorization fails
     */
    void insert(Actor actor, long campusId, long activityId,
            List<String> principals, String role) {
        if (new LinkedHashSet<>(principals).size() != principals.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "活动成员编号不能重复");
        }
        for (String publicId : principals) {
            long principalId = activityPrincipal(actor, campusId, publicId);
            jdbc.update("""
                INSERT INTO core_activity_member (activity_id,principal_id,member_role)
                VALUES (?,?,?)
                """, activityId, principalId, role);
        }
    }
    /** Soft-deletes the current role then restores or inserts its replacement members.
     * @param actor current actor and campus-management scope
     * @param campusId activity campus identifier
     * @param activityId authorized and locked activity identifier
     * @param publicIds caller-validated replacement identifiers
     * @param role participant or promoter
     * @throws BusinessException if a member or the caller lacks required campus authorization
     */
    void replace(Actor actor, long campusId, long activityId,
            List<String> publicIds, String role) {
        jdbc.update("""
            UPDATE core_activity_member SET deleted=1,updated_at=UTC_TIMESTAMP(3)
            WHERE activity_id=? AND member_role=? AND deleted=0
            """, activityId, role);
        for (String publicId : publicIds) {
            long principalId = activityPrincipal(actor, campusId, publicId);
            jdbc.update("""
                INSERT INTO core_activity_member (activity_id,principal_id,member_role)
                VALUES (?,?,?) ON DUPLICATE KEY UPDATE deleted=0,updated_at=UTC_TIMESTAMP(3)
                """, activityId, principalId, role);
        }
    }
    private long activityPrincipal(Actor actor, long campusId, String publicId) {
        List<Long> ids = jdbc.query("""
            SELECT p.id FROM core_principal p JOIN core_principal_campus pc ON pc.principal_id = p.id
            WHERE p.public_id = ? AND p.active = 1 AND p.deleted = 0 AND pc.campus_id = ? AND pc.deleted = 0
            FOR UPDATE
            """, (rs, row) -> rs.getLong("id"), publicId, campusId);
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.FORBIDDEN, "活动成员未授权到该校区"); }
        if (!actor.scope().equals("all") && !actor.campusIds().contains(campusId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号不能管理该校区活动成员");
        }
        return ids.get(0);
    }
}
