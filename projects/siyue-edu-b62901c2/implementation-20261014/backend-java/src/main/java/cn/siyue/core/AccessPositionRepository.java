package cn.siyue.core;

import cn.siyue.core.AccessPositionDtos.CreateInput;
import cn.siyue.core.AccessPositionDtos.MembersInput;
import cn.siyue.core.AccessPositionDtos.Position;
import cn.siyue.core.AccessPositionDtos.PositionPage;
import cn.siyue.core.AccessPositionDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists role templates, assignments, and immutable before/after change history. */
@Repository
public class AccessPositionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AccessPositionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PositionPage list(int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(id) FROM core_access_position WHERE deleted=0", Long.class);
        List<Long> ids = jdbc.queryForList("SELECT id FROM core_access_position WHERE deleted=0 "
            + "ORDER BY name,id LIMIT ? OFFSET ?", Long.class, size, (page - 1) * size);
        return new PositionPage(ids.stream().map(this::view).toList(), total == null ? 0 : total, page, size);
    }

    public Position create(Actor actor, CreateInput input) {
        long id;
        String publicId = input.positionId() == null ? positionId() : input.positionId();
        try {
            jdbc.update("INSERT INTO core_access_position(public_id,name,description,directory_kind) VALUES(?,?,?,?)",
                publicId, input.name(), input.description(), input.directoryKind());
            id = jdbc.queryForObject("SELECT id FROM core_access_position WHERE public_id=?", Long.class, publicId);
        } catch (DuplicateKeyException error) {
            throw duplicate();
        }
        replacePermissions(id, input.permissionCodes());
        Position created = view(id);
        record(actor, id, "created", null, created);
        return created;
    }

    public Position current(String id) { return view(lockPosition(id)); }

    public Position update(Actor actor, String id, UpdateInput input) {
        long target = lockPosition(id);
        Position before = view(target);
        lockAssignedPrincipals(target, List.of());
        try {
            int changed = jdbc.update("UPDATE core_access_position SET name=?,description=?,active=?,"
                + "directory_kind=COALESCE(?,directory_kind),version=version+1,"
                + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND deleted=0",
                input.name(), input.description(), input.active(), input.directoryKind(), target, input.expectedVersion());
            if (changed != 1) { throw stale(); }
            replacePermissions(target, input.permissionCodes());
        } catch (DuplicateKeyException error) {
            throw duplicate();
        }
        Position updated = view(target);
        record(actor, target, "updated", before, updated);
        return updated;
    }

    public Position replaceMembers(Actor actor, String id, MembersInput input) {
        long target = lockPosition(id);
        Position before = view(target);
        validateUnarchivedMembers(input.principalIds());
        lockAssignedPrincipals(target, input.principalIds());
        int found = input.principalIds().isEmpty() ? 0 : countPrincipals(input.principalIds());
        if (found != input.principalIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "员工列表含不存在或重复编号");
        }
        int changed = jdbc.update("UPDATE core_access_position SET version=version+1,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE id=? AND version=? AND deleted=0", target, input.expectedVersion());
        if (changed != 1) { throw stale(); }
        jdbc.update("UPDATE core_principal_position SET deleted=TRUE,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE position_id=? AND deleted=0", target);
        for (String principalId : input.principalIds()) {
            jdbc.update("INSERT INTO core_principal_position(principal_id,position_id,deleted) "
                + "SELECT id,?,FALSE FROM core_principal WHERE public_id=? AND deleted=0 "
                + "ON DUPLICATE KEY UPDATE deleted=FALSE,updated_at=UTC_TIMESTAMP(3)", target, principalId);
        }
        Position updated = view(target);
        record(actor, target, "updated", before, updated);
        return updated;
    }

    private void validateUnarchivedMembers(List<String> principalIds) {
        if (principalIds.isEmpty()) { return; }
        String markers = String.join(",", java.util.Collections.nCopies(principalIds.size(), "?"));
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_principal WHERE deleted=0 "
            + "AND archived_at IS NULL AND public_id IN (" + markers + ")", Integer.class, principalIds.toArray());
        if (count == null || count != principalIds.size()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "已归档或不存在的员工不能加入职位");
        }
    }

    /** Replaces one legacy principal's mapped role memberships while versioning and auditing every affected template. */
    public void replaceLegacyMemberships(Actor actor, String principalId, List<String> desiredPublicIds) {
        Long principal = jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=? AND deleted=0 FOR UPDATE",
            Long.class, principalId);
        if (principal == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "迁移员工不存在"); }
        List<String> currentPublicIds = jdbc.queryForList("""
            SELECT position.public_id FROM core_principal_position link
            JOIN core_access_position position ON position.id=link.position_id AND position.deleted=0
            WHERE link.principal_id=? AND link.deleted=0 ORDER BY position.public_id
            """, String.class, principal);
        List<String> desired = desiredPublicIds.stream().sorted().toList();
        if (new java.util.HashSet<>(currentPublicIds).equals(new java.util.HashSet<>(desired))) { return; }
        java.util.TreeSet<String> affected = new java.util.TreeSet<>(currentPublicIds);
        affected.addAll(desired);
        String markers = String.join(",", java.util.Collections.nCopies(affected.size(), "?"));
        List<Long> ids = jdbc.query("SELECT id FROM core_access_position WHERE deleted=0 AND public_id IN ("
            + markers + ") ORDER BY id FOR UPDATE", (rs, row) -> rs.getLong(1), affected.toArray());
        if (ids.size() != affected.size()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "可信迁移引用了不存在的职位");
        }
        List<Position> before = ids.stream().map(this::view).toList();
        jdbc.update("UPDATE core_principal_position SET deleted=TRUE,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE principal_id=? AND deleted=FALSE", principal);
        for (String positionId : desired) {
            jdbc.update("INSERT INTO core_principal_position(principal_id,position_id,deleted) "
                + "SELECT ?,id,FALSE FROM core_access_position WHERE public_id=? AND deleted=FALSE "
                + "ON DUPLICATE KEY UPDATE deleted=FALSE,updated_at=UTC_TIMESTAMP(3)", principal, positionId);
        }
        for (int index = 0; index < ids.size(); index++) {
            long id = ids.get(index);
            jdbc.update("UPDATE core_access_position SET version=version+1,updated_at=UTC_TIMESTAMP(3) "
                + "WHERE id=? AND deleted=FALSE", id);
            record(actor, id, "updated", before.get(index), view(id));
        }
    }

    private Position view(long id) {
        List<PositionRow> rows = jdbc.query("SELECT public_id,name,description,directory_kind,active,version FROM core_access_position "
            + "WHERE id=? AND deleted=0", (rs, row) -> new PositionRow(rs.getString("public_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("directory_kind"), rs.getBoolean("active"), rs.getLong("version")), id);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "职位不存在"); }
        PositionRow row = rows.get(0);
        List<String> permissions = jdbc.queryForList("SELECT permission_code FROM core_access_position_permission "
            + "WHERE position_id=? AND deleted=0 ORDER BY permission_code", String.class, id);
        List<String> principals = jdbc.queryForList("SELECT p.public_id FROM core_principal_position pp "
            + "JOIN core_principal p ON p.id=pp.principal_id AND p.deleted=0 "
            + "WHERE pp.position_id=? AND pp.deleted=0 ORDER BY p.public_id", String.class, id);
        return new Position(row.id(), row.name(), row.description(), row.directoryKind(), row.active(), row.version(), permissions, principals);
    }

    private long lockPosition(String id) {
        List<Long> rows = jdbc.query("SELECT id FROM core_access_position WHERE public_id=? AND deleted=0 FOR UPDATE",
            (rs, row) -> rs.getLong(1), id);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "职位不存在"); }
        return rows.get(0);
    }

    private void lockAssignedPrincipals(long positionId, List<String> principalIds) {
        String suffix = principalIds.isEmpty() ? "" : " OR public_id IN ("
            + String.join(",", java.util.Collections.nCopies(principalIds.size(), "?")) + ")";
        List<Object> args = new ArrayList<>();
        args.add(positionId);
        args.addAll(principalIds);
        jdbc.query("SELECT id FROM core_principal WHERE deleted=0 AND (id IN "
            + "(SELECT principal_id FROM core_principal_position WHERE position_id=? AND deleted=0)"
            + suffix + ") ORDER BY id FOR UPDATE", (rs, row) -> rs.getLong(1), args.toArray());
    }

    private int countPrincipals(List<String> principalIds) {
        String marks = String.join(",", java.util.Collections.nCopies(principalIds.size(), "?"));
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_principal WHERE deleted=0 AND public_id IN ("
            + marks + ")", Integer.class, principalIds.toArray());
        return count == null ? 0 : count;
    }

    private void replacePermissions(long positionId, List<String> codes) {
        jdbc.update("UPDATE core_access_position_permission SET deleted=TRUE,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE position_id=? AND deleted=0", positionId);
        for (String code : codes) {
            jdbc.update("INSERT INTO core_access_position_permission(position_id,permission_code,deleted) "
                + "VALUES(?,?,FALSE) ON DUPLICATE KEY UPDATE deleted=FALSE,updated_at=UTC_TIMESTAMP(3)", positionId, code);
        }
    }

    private void record(Actor actor, long positionId, String action, Position before, Position after) {
        try {
            jdbc.update("INSERT INTO core_access_position_change_event(public_id,actor_id,position_id,action_type,"
                + "before_state,after_state) VALUES(?,?,?,?,?,?)", "POS_EVT_" + UUID.randomUUID(), actor.id(),
                positionId, action, before == null ? "{}" : mapper.writeValueAsString(before),
                mapper.writeValueAsString(after));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Position change event could not be serialized", error);
        }
    }

    private static String positionId() { return "POS_" + UUID.randomUUID().toString().replace("-", ""); }
    private static BusinessException duplicate() {
        return new BusinessException(ErrorCode.ALREADY_EXISTS, "职位名称已存在");
    }
    private static BusinessException stale() {
        return new BusinessException(ErrorCode.ALREADY_EXISTS, "职位版本已变化，请刷新后重试");
    }
    private record PositionRow(String id, String name, String description, String directoryKind, boolean active, long version) { }
}
