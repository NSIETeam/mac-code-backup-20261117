package cn.siyue.core;

import cn.siyue.core.LeadBatchAssignmentDtos.AssignedLead;
import cn.siyue.core.LeadBatchAssignmentDtos.Input;
import cn.siyue.core.LeadBatchAssignmentDtos.Quota;
import cn.siyue.core.LeadBatchAssignmentDtos.Result;
import cn.siyue.core.LeadBatchAssignmentDtos.OwnerChoice;
import cn.siyue.core.LeadBatchAssignmentDtos.OwnerOptions;
import cn.siyue.core.LeadBatchAssignmentDtos.SkippedLead;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Locks, allocates and records a legacy explicit-owner batch in one transaction. */
@Repository
public class LeadBatchAssignmentRepository {
    private record LeadRow(long id, String publicId, Long campusId, String campusPublicId,
            Long ownerId, String ownerPublicId, String status, String legacyStatus, String legacyStage,
            String decisionStatus, Timestamp assignedAt, Timestamp assignmentFollowAt, Timestamp nextFollowAt,
            String nextAction, long version) { }
    record LockedParticipants(Actor actor, Map<String, Actor> owners) { }
    private record Planned(LeadRow lead, String ownerPublicId, long ownerInternalId) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;

    /** Uses the active transaction's MySQL connection and configured PII keyring. */
    public LeadBatchAssignmentRepository(JdbcTemplate jdbc, PiiCipher cipher, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    /** Locks actor and destination principals together in the global numeric-ID order. */
    public LockedParticipants lockParticipants(Actor actor, Input input, AccessRepository access) {
        Map<String, Long> ownerIds = new LinkedHashMap<>();
        for (String publicId : input.ownerIds().stream().sorted().toList()) {
            List<Long> ids = jdbc.query("SELECT id FROM core_principal WHERE public_id=? AND deleted=0",
                (rs, row) -> rs.getLong(1), publicId);
            if (ids.size() != 1) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "请选择有效并具有跟进权限的员工");
            }
            ownerIds.put(publicId, ids.get(0));
        }
        List<Long> principalIds = new ArrayList<>(ownerIds.values());
        principalIds.add(actor.id());
        Map<Long, Actor> locked = access.lockActiveMany(principalIds);
        Map<String, Actor> owners = new LinkedHashMap<>();
        ownerIds.forEach((publicId, id) -> {
            Actor owner = locked.get(id);
            if (!owner.permissions().contains("lead.follow")) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "请选择有效并具有跟进权限的员工");
            }
            owners.put(publicId, owner);
        });
        return new LockedParticipants(locked.get(actor.id()), Map.copyOf(owners));
    }

    /** Resolves every lead and destination owner inside the caller's authorized scope. */
    public void authorize(Actor actor, Input input, Map<String, Actor> owners) {
        validateQuotas(input);
        List<LeadRow> leads = new ArrayList<>();
        for (String leadId : input.leadIds().stream().sorted().toList()) {
            leads.add(lead(actor, leadId, false));
        }
        validateOwnerScopes(input, owners, leads);
    }

    /** Returns only active lead-follow employees who can receive every selected lead. */
    public OwnerOptions ownerOptions(Actor actor, List<String> leadIds, int page, int size) {
        actor.require("lead.assign");
        if (leadIds == null || leadIds.isEmpty() || leadIds.size() > 498 || page < 1 || page > 100000
                || size < 1 || size > 100 || new HashSet<>(leadIds).size() != leadIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "线索或分页参数无效");
        }
        ScopeSql.Clause scope = ScopeSql.leadWrites(actor);
        List<Object> leadArgs = new ArrayList<>(leadIds);
        leadArgs.addAll(scope.args());
        String placeholders = String.join(",", java.util.Collections.nCopies(leadIds.size(), "?"));
        List<Long> leadCampuses = jdbc.query("SELECT l.campus_id FROM core_crm_lead l WHERE l.public_id IN ("
                + placeholders + ") AND l.deleted=0 AND (" + scope.sql() + ")", (rs, row) -> {
                    long value = rs.getLong(1); return rs.wasNull() ? null : value;
                }, leadArgs.toArray());
        if (leadCampuses.size() != leadIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分线索不存在或不在授权范围");
        }
        Set<Long> campusIds = new HashSet<>(leadCampuses);
        boolean allHaveCampus = !campusIds.contains(null);
        campusIds.remove(null);
        String campusClause = "1=0";
        List<Object> campusArgs = new ArrayList<>();
        if (allHaveCampus && !campusIds.isEmpty()) {
            String campusMarks = String.join(",", java.util.Collections.nCopies(campusIds.size(), "?"));
            campusClause = "(SELECT COUNT(DISTINCT pc.campus_id) FROM core_principal_campus pc "
                + "WHERE pc.principal_id=p.id AND pc.deleted=0 AND pc.campus_id IN (" + campusMarks + "))=?";
            campusArgs.addAll(campusIds.stream().sorted().toList());
            campusArgs.add(campusIds.size());
        }
        String scopeFilter = "(p.scope_type IN ('all','own') OR (p.scope_type='campus' AND " + campusClause + "))";
        String permission = "((EXISTS(SELECT 1 FROM core_principal_permission direct WHERE direct.principal_id=p.id "
            + "AND direct.deleted=0 AND direct.permission_code='lead.follow') OR EXISTS(SELECT 1 "
            + "FROM core_principal_position link JOIN core_access_position position ON position.id=link.position_id "
            + "AND position.deleted=0 AND position.active=1 JOIN core_access_position_permission grantrow "
            + "ON grantrow.position_id=position.id AND grantrow.deleted=0 WHERE link.principal_id=p.id "
            + "AND link.deleted=0 AND grantrow.permission_code='lead.follow')) AND NOT EXISTS("
            + "SELECT 1 FROM core_principal_denied_permission denied WHERE denied.principal_id=p.id "
            + "AND denied.deleted=0 AND denied.permission_code='lead.follow'))";
        String from = "FROM core_principal p LEFT JOIN core_staff_profile sp ON sp.principal_id=p.id AND sp.deleted=0 "
            + "WHERE p.active=1 AND p.deleted=0 AND p.archived_at IS NULL AND p.credential_change_required=0 AND p.scope_type IN ('all','own','campus') AND "
            + scopeFilter + " AND " + permission;
        List<Object> args = new ArrayList<>(campusArgs);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());
        args.add(size); args.add(Math.multiplyExact((long) page - 1, size));
        List<OwnerChoice> items = jdbc.query("SELECT p.public_id,sp.title_cipher " + from
            + " ORDER BY p.public_id LIMIT ? OFFSET ?", (rs, row) -> {
                String id = rs.getString(1), title = rs.getString(2);
                return new OwnerChoice(id, id, title == null ? null : cipher.decrypt(title));
            }, args.toArray());
        return new OwnerOptions(items, total == null ? 0 : total, page, size);
    }

    /** Applies a deterministic allocation plan, skipping unchanged owners and appending history. */
    public Result assign(Actor actor, Input input, Map<String, Actor> owners) throws JsonProcessingException {
        List<LeadRow> leads = lockLeads(actor, input.leadIds());
        validateVersions(input, leads);
        validateOwnerScopes(input, owners, leads);
        validateQuotas(input);
        Map<String, Integer> counts = new LinkedHashMap<>();
        input.ownerIds().forEach(id -> counts.put(id, 0));
        Map<String, Integer> quotas = quotaMap(input.quotas());
        List<Planned> plan = new ArrayList<>();
        for (LeadRow lead : orderByRequest(leads, input.leadIds())) {
            String selected = choose(lead, input, owners, counts, quotas);
            counts.compute(selected, (key, value) -> value + 1);
            plan.add(new Planned(lead, selected, owners.get(selected).id()));
        }
        return writePlan(actor, input, plan, counts, owners);
    }

    private void validateVersions(Input input, List<LeadRow> leads) {
        Map<String, LeadRow> byId = new HashMap<>();
        leads.forEach(lead -> byId.put(lead.publicId(), lead));
        for (String id : input.leadIds()) {
            LeadRow lead = byId.get(id);
            if (lead == null || lead.version() != input.expectedVersions().get(id)) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, "所选线索已变化，请刷新后重新选择");
            }
        }
    }

    private List<LeadRow> lockLeads(Actor actor, List<String> ids) {
        List<LeadRow> result = new ArrayList<>();
        for (String id : ids.stream().sorted().toList()) {
            LeadRow row = lead(actor, id, true);
            validateLead(row);
            result.add(row);
        }
        return result;
    }

    private LeadRow lead(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.leadWrites(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        String sql = """
            SELECT l.id,l.public_id,l.campus_id,cp.public_id AS campus_public_id,l.owner_id,
                   op.public_id AS owner_public_id,l.status,l.legacy_status,l.legacy_stage,
                   l.decision_status,l.assigned_at,l.assignment_follow_at,l.next_follow_at,l.next_action,l.version
            FROM core_crm_lead l
            LEFT JOIN core_campus cp ON cp.id=l.campus_id
            LEFT JOIN core_principal op ON op.id=l.owner_id
            WHERE l.public_id=? AND l.deleted=0 AND (""" + scope.sql() + ")" + (lock ? " FOR UPDATE" : "");
        List<LeadRow> rows = jdbc.query(sql, (rs, index) -> mapLead(rs), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在或不在授权范围"); }
        return rows.get(0);
    }

    private LeadRow mapLead(ResultSet rs) throws SQLException {
        long campus = rs.getLong("campus_id");
        Long campusId = rs.wasNull() ? null : campus;
        long owner = rs.getLong("owner_id");
        Long ownerId = rs.wasNull() ? null : owner;
        return new LeadRow(rs.getLong("id"), rs.getString("public_id"), campusId,
            rs.getString("campus_public_id"), ownerId, rs.getString("owner_public_id"),
            rs.getString("status"), rs.getString("legacy_status"), rs.getString("legacy_stage"),
            rs.getString("decision_status"), rs.getTimestamp("assigned_at"),
            rs.getTimestamp("assignment_follow_at"), rs.getTimestamp("next_follow_at"),
            rs.getString("next_action"), rs.getLong("version"));
    }

    private void validateLead(LeadRow lead) {
        if ("cold".equals(lead.legacyStatus()) || "archived".equals(lead.status())
                || "已归档".equals(lead.legacyStage())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "归档线索须先恢复再分配");
        }
        if ("pending".equals(lead.decisionStatus())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "待决策线索须先完成去向决策");
        }
    }

    private void validateOwnerScopes(Input input, Map<String, Actor> owners, List<LeadRow> leads) {
        for (String publicId : input.ownerIds()) {
            Actor owner = owners.get(publicId);
            if (owner == null) { throw new BusinessException(ErrorCode.FORBIDDEN, "负责人不可用"); }
            if ("assigned".equals(owner.scope())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "仅授课分配范围的员工不能接收销售线索");
            }
            for (LeadRow lead : leads) { validateOwnerCampus(owner, lead); }
        }
    }

    private void validateOwnerCampus(Actor owner, LeadRow lead) {
        if ("campus".equals(owner.scope()) && (lead.campusId() == null || !owner.campusIds().contains(lead.campusId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "负责人不具有该校区权限");
        }
    }

    private void validateQuotas(Input input) {
        if (!"quota".equals(input.strategy())) {
            if (input.quotas() != null && !input.quotas().isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "只有 quota 策略可以设置 quotas");
            }
            return;
        }
        if (input.quotas() == null || input.quotas().size() != input.ownerIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "按数量分配需要每个员工的名额");
        }
        Set<String> selected = new HashSet<>(input.ownerIds());
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (Quota quota : input.quotas()) {
            if (!selected.contains(quota.ownerId()) || !seen.add(quota.ownerId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "数量配置员工重复或不在分配名单");
            }
            total = Math.addExact(total, quota.count());
        }
        if (total != input.leadIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分配数量之和须等于待分配线索数");
        }
    }

    private Map<String, Integer> quotaMap(List<Quota> quotas) {
        Map<String, Integer> result = new HashMap<>();
        if (quotas != null) { quotas.forEach(item -> result.put(item.ownerId(), item.count())); }
        return result;
    }

    private String choose(LeadRow lead, Input input, Map<String, Actor> owners,
            Map<String, Integer> counts, Map<String, Integer> quotas) {
        if ("owner_first".equals(input.strategy()) && lead.ownerPublicId() != null
                && owners.containsKey(lead.ownerPublicId())) { return lead.ownerPublicId(); }
        return input.ownerIds().stream().filter(id -> !"quota".equals(input.strategy())
                || counts.get(id) < quotas.get(id))
            .min(Comparator.comparingInt((String id) -> counts.get(id))
                .thenComparingInt(input.ownerIds()::indexOf))
            .filter(owners::containsKey).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                "没有可分配的员工"));
    }

    private List<LeadRow> orderByRequest(List<LeadRow> leads, List<String> request) {
        Map<String, LeadRow> byId = new HashMap<>();
        leads.forEach(row -> byId.put(row.publicId(), row));
        return request.stream().map(byId::get).toList();
    }

    private Result writePlan(Actor actor, Input input, List<Planned> plan,
            Map<String, Integer> counts, Map<String, Actor> owners) throws JsonProcessingException {
        List<AssignedLead> records = new ArrayList<>();
        List<SkippedLead> skipped = new ArrayList<>();
        Timestamp now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
        for (Planned assignment : plan) {
            LeadRow lead = assignment.lead();
            String ownerPublicId = assignment.ownerPublicId();
            long ownerInternalId = assignment.ownerInternalId();
            if (ownerInternalId == (lead.ownerId() == null ? -1L : lead.ownerId())) {
                skipped.add(new SkippedLead(lead.publicId(), "same_owner"));
                continue;
            }
            String nextStatus = "已报名".equals(lead.legacyStage()) ? "converted" : "assigned";
            String nextStage = "已报名".equals(lead.legacyStage()) ? lead.legacyStage() : "新分配";
            int changed = jdbc.update("""
                UPDATE core_crm_lead SET owner_id=?,legacy_status=?,legacy_stage=?,assigned_at=?,
                    assignment_follow_at=NULL,next_follow_at=NULL,next_action='未约定',
                    version=version+1,updated_at=UTC_TIMESTAMP(3)
                WHERE id=? AND version=? AND deleted=0
                """, ownerInternalId, nextStatus, nextStage, now, lead.id(), lead.version());
            if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "线索版本已变化，请刷新后重试"); }
            appendEvent(actor, input, assignment, now);
            records.add(new AssignedLead(lead.publicId(), ownerPublicId, nextStatus, nextStage,
                now.toInstant(), null, null, "未约定", lead.version() + 1));
        }
        return new Result(List.copyOf(records), List.copyOf(skipped),
            java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts)));
    }

    private void appendEvent(Actor actor, Input input, Planned assignment, Timestamp now)
            throws JsonProcessingException {
        LeadRow lead = assignment.lead();
        String ownerPublicId = assignment.ownerPublicId();
        Map<String, Object> detail = Map.ofEntries(
            Map.entry("kind", "批量分配"), Map.entry("strategy", input.strategy()),
            Map.entry("reason", input.reason().trim()),
            Map.entry("fromOwnerId", lead.ownerPublicId() == null ? "" : lead.ownerPublicId()),
            Map.entry("toOwnerId", ownerPublicId),
            Map.entry("fromStatus", lead.legacyStatus() == null ? "" : lead.legacyStatus()),
            Map.entry("toStatus", "已报名".equals(lead.legacyStage()) ? "converted" : "assigned"),
            Map.entry("fromStage", lead.legacyStage() == null ? "" : lead.legacyStage()),
            Map.entry("toStage", "已报名".equals(lead.legacyStage()) ? lead.legacyStage() : "新分配"),
            Map.entry("fromAssignedAt", iso(lead.assignedAt())),
            Map.entry("toAssignedAt", now.toInstant().toString()),
            Map.entry("fromAssignmentFollowAt", iso(lead.assignmentFollowAt())),
            Map.entry("toAssignmentFollowAt", ""),
            Map.entry("fromNextFollowAt", iso(lead.nextFollowAt())),
            Map.entry("toNextFollowAt", ""),
            Map.entry("fromNextAction", lead.nextAction() == null ? "" : lead.nextAction()),
            Map.entry("toNextAction", "未约定"));
        jdbc.update("""
            INSERT INTO core_crm_lead_event
                (public_id,lead_id,actor_id,event_type,from_owner_id,to_owner_id,from_status,to_status,occurred_at,detail_cipher)
            VALUES (?,?,?,'assigned',?,?,?,?,?,?)
            """, "LEADEVT_" + UUID.randomUUID(), lead.id(), actor.id(), lead.ownerId(), assignment.ownerInternalId(),
            lead.status(), lead.status(), now, cipher.encrypt(mapper.writeValueAsString(detail)));
    }

    private String iso(Timestamp value) { return value == null ? "" : value.toInstant().toString(); }
}
