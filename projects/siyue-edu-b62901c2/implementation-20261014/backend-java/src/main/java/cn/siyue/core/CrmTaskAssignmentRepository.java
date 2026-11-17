package cn.siyue.core;

import cn.siyue.core.CrmTaskReadDtos.Assignee;
import cn.siyue.core.CrmTaskReadDtos.AssigneePage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Uses the same receiving eligibility for the business directory and actual assignments. */
@Repository
public class CrmTaskAssignmentRepository {
    /**
     * Receiver identity and current assignment scope read from the authorization tables.
     * @param id internal principal key used for locked assignment writes
     * @param publicId non-secret employee reference returned to authorized callers
     * @param scopeType current receiver scope; it does not expand the assigning actor's scope
     * @param campuses immutable current campus memberships
     */
    public record Staff(long id, String publicId, String scopeType, Set<Long> campuses) { }
    private static final String ACTIVE = "p.active=1 AND p.deleted=0 AND p.archived_at IS NULL "
        + "AND p.credential_change_required=0 AND ";
    private final JdbcTemplate jdbc;
    /** @param jdbc MySQL access sharing the caller's transaction for receiver qualification and locking */
    public CrmTaskAssignmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Requires current assignment permission and receiver eligibility for every target campus.
     * @param actor current assigning identity
     * @param publicId proposed receiver
     * @param customerCampusIds every campus covered by this assignment
     * @param taskType task domain controlling assigned-scope teaching eligibility
     * @param lock whether to lock the eligible receiver through transaction completion
     * @return the qualified receiver and immutable campus memberships
     * @throws BusinessException when assigning rights, receiver status or receiver scope deny the assignment
     */
    public Staff requireEligible(Actor actor, String publicId, List<Long> customerCampusIds,
            String taskType, boolean lock) {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.write");
        if (!actor.publicId().equals(publicId)) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.task.assign"); }
        Eligibility result = qualification(publicId, customerCampusIds, taskType, lock);
        if (result.staff() == null) { forbidden(result.reason()); }
        return result.staff();
    }

    /**
     * Queries assignment eligibility without using denied qualification as an exception branch.
     * @param actor current assigning identity; no permissions are inferred from the receiver
     * @param publicId receiver identifier
     * @param customerCampusIds every campus covered by the proposed assignment
     * @param taskType task domain, used for assigned-scope teaching eligibility
     * @param lock whether the receiver row must remain locked until transaction completion
     * @return the eligible receiver, or empty for missing permission, disabled account or scope mismatch
     * @throws org.springframework.dao.DataAccessException if the eligibility query cannot be completed
     */
    Optional<Staff> findEligible(Actor actor, String publicId, List<Long> customerCampusIds,
            String taskType, boolean lock) {
        if (!actor.permissions().contains("crm.task.write") && !actor.permissions().contains("lead.assign")
                || !actor.publicId().equals(publicId) && !actor.permissions().contains("crm.task.assign") && !actor.permissions().contains("lead.assign")) {
            return Optional.empty();
        }
        return Optional.ofNullable(qualification(publicId, customerCampusIds, taskType, lock).staff());
    }

    private Eligibility qualification(String publicId, List<Long> customerCampusIds, String taskType, boolean lock) {
        List<Staff> people = jdbc.query("SELECT p.id,p.public_id,p.scope_type FROM core_principal p "
            + "WHERE p.public_id=? AND " + ACTIVE + CrmPrincipalEligibility.task(taskType) + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Staff(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("scope_type"), Set.of()), publicId);
        if (people.isEmpty()) { return new Eligibility(null, "员工未启用或缺少任务办理权限"); }
        Staff person = people.get(0);
        for (Long campusId : new HashSet<>(customerCampusIds)) {
            var eligible = eligibility(campusId, taskType);
            List<Object> args = new ArrayList<>(List.of(person.id())); args.addAll(eligible.args());
            Integer count = jdbc.queryForObject("SELECT COUNT(p.id) FROM core_principal p WHERE p.id=? AND ("
                + eligible.sql() + ")", Integer.class, args.toArray());
            if (count == null || count == 0) { return new Eligibility(null, "员工不能接收该校区或类型的任务"); }
        }
        Set<Long> campuses = new HashSet<>(jdbc.query("SELECT campus_id FROM core_principal_campus "
            + "WHERE principal_id=? AND deleted=0", (rs, n) -> rs.getLong(1), person.id()));
        return new Eligibility(new Staff(person.id(), person.publicId(), person.scopeType(), Set.copyOf(campuses)), null);
    }

    /**
     * Lists only qualified receivers and workload visible to the assigning employee.
     * @param actor current caller; lacking assign permission limits results to that same employee
     * @param input campus, task domain, identifier prefix and bounded pagination
     * @return a page whose total and rows use the same eligibility predicate
     * @throws BusinessException when the requested campus is absent or outside the caller's customer scope
     */
    public AssigneePage list(Actor actor, CrmTaskReadDtos.AssigneeQuery input) {
        String campusPublicId = input.campusId(), taskType = input.taskType(), query = input.query();
        int page = input.page(), size = input.size();
        Long campusId = authorizedCampus(actor, campusPublicId);
        var eligible = eligibility(campusId, taskType);
        List<Object> args = new ArrayList<>(eligible.args());
        String where = " WHERE " + ACTIVE + CrmPrincipalEligibility.task(taskType) + " AND (" + eligible.sql() + ")";
        if (!actor.permissions().contains("crm.task.assign") && !actor.permissions().contains("lead.assign")) { where += " AND p.id=?"; args.add(actor.id()); }
        if (query != null) { where += " AND LEFT(CONVERT(p.public_id USING utf8mb4),CHAR_LENGTH(?))=?";
            args.add(query); args.add(query); }
        Long total = jdbc.queryForObject("SELECT COUNT(p.id) FROM core_principal p" + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        List<Staff> people = jdbc.query("SELECT p.id,p.public_id,p.scope_type FROM core_principal p" + where
            + " ORDER BY p.public_id,p.id LIMIT ? OFFSET ?",
            (rs, n) -> new Staff(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("scope_type"), Set.of()), pageArgs.toArray());
        List<Assignee> items = people.stream().map(person -> new Assignee(person.publicId(), person.publicId(),
            pendingCount(actor, person.id(), campusId, taskType))).toList();
        return new AssigneePage(items, total == null ? 0 : total, page, size, campusPublicId,
            taskType, Instant.now(), "current_actor_scope");
    }

    private Long authorizedCampus(Actor actor, String publicId) {
        Long campusId = null;
        if (publicId != null) {
            List<Long> ids = jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND deleted=0 AND active=1",
                (rs, n) -> rs.getLong(1), publicId);
            if (ids.isEmpty()) { notFound(); }
            campusId = ids.get(0);
        }
        if (actor.scope().equals("all")) { return campusId; }
        var scope = ScopeSql.customers(actor);
        List<Object> args = new ArrayList<>(); args.add(campusId); args.addAll(scope.args());
        Long count = jdbc.queryForObject("SELECT COUNT(c.id) FROM core_customer c WHERE c.deleted=0 "
            + "AND c.campus_id <=> ? AND (" + scope.sql() + ")", Long.class, args.toArray());
        if (count == null || count == 0) { notFound(); }
        return campusId;
    }

    private long pendingCount(Actor actor, long ownerId, Long campusId, String taskType) {
        var scope = CrmTaskReadRepository.taskScope(actor);
        List<Object> args = new ArrayList<>(); args.add(ownerId); args.add(campusId); args.add(taskType);
        args.addAll(scope.args());
        Long count = jdbc.queryForObject("SELECT COUNT(t.id) FROM core_crm_task t WHERE t.owner_id=? "
            + "AND t.campus_id <=> ? AND t.task_type=? AND t.status IN ('pending','in_progress') "
            + "AND t.deleted=0 AND (" + scope.sql() + ")", Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private ScopeSql.Clause eligibility(Long campusId, String taskType) {
        if (campusId == null) { return new ScopeSql.Clause("p.scope_type IN ('all','own')", List.of()); }
        return new ScopeSql.Clause("""
            p.scope_type IN ('all','own') OR (p.scope_type='campus' AND EXISTS (
              SELECT pc.id FROM core_principal_campus pc WHERE pc.principal_id=p.id
                AND pc.campus_id=? AND pc.deleted=0))
            OR (p.scope_type='assigned' AND ?='service' AND EXISTS (
              SELECT cls.id FROM core_class cls WHERE cls.campus_id=? AND cls.deleted=0 AND
                (cls.teacher_id=p.id OR EXISTS (SELECT se.id FROM core_session se
                  WHERE se.class_id=cls.id AND se.teacher_id=p.id AND se.deleted=0))))
            """, List.of(campusId, taskType, campusId));
    }
    private record Eligibility(Staff staff, String reason) { }
    private static void forbidden(String message) { throw new BusinessException(ErrorCode.FORBIDDEN, message); }
    private static void notFound() { throw new BusinessException(ErrorCode.NOT_FOUND, "目标校区不存在或没有可分派的客户范围"); }
}
