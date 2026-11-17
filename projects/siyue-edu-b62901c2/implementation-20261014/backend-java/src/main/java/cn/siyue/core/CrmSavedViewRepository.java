package cn.siyue.core;

import cn.siyue.core.CrmSavedViewDtos.View;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores private, non-PII filters owned by one authenticated employee. */
@Repository
public class CrmSavedViewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    /** Uses the shared MySQL connection and JSON codec for saved filter sets. */
    public CrmSavedViewRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }
    /** Returns the current actor's views only, with stable public identifiers. */
    public List<View> list(Actor actor, String resourceType) {
        return jdbc.query("""
            SELECT public_id,resource_type,name,filters_json,version FROM core_crm_saved_view
            WHERE principal_id=? AND resource_type=? AND deleted=0 ORDER BY created_at,id LIMIT 20
            """, (rs, row) -> new View(rs.getString("public_id"), rs.getString("resource_type"),
                rs.getString("name"), readFilters(rs.getString("filters_json")), rs.getLong("version")),
            actor.id(), resourceType);
    }
    /** Reads a view only when its owner matches the authenticated actor. */
    public View get(Actor actor, String id) { return current(actor, id, true); }
    /** Creates a view; the principal row lock serializes the per-employee view limit. */
    public View create(Actor actor, String resourceType, String name, Map<String, String> filters) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM core_crm_saved_view
            WHERE principal_id=? AND resource_type=? AND deleted=0
            """, Integer.class, actor.id(), resourceType);
        if (count != null && count >= 20) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "每类队列最多保存 20 个视图");
        }
        String id = "VIEW_" + UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO core_crm_saved_view(public_id,principal_id,resource_type,name,filters_json)
                VALUES(?,?,?,?,?)
                """, id, actor.id(), resourceType, name, json(filters));
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "同一队列中已存在相同视图名称");
        }
        return new View(id, resourceType, name, Map.copyOf(filters), 1);
    }
    /** Updates an owned view only at the expected version. */
    public View update(Actor actor, String id, String name, Map<String, String> filters, long version) {
        long internalId = lockOwned(actor, id);
        try {
            int changed = jdbc.update("""
                UPDATE core_crm_saved_view SET name=?,filters_json=?,version=version+1,
                    updated_at=UTC_TIMESTAMP(3)
                WHERE id=? AND principal_id=? AND version=? AND deleted=0
                """, name, json(filters), internalId, actor.id(), version);
            if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "视图版本已变化，请刷新后重试"); }
        } catch (DuplicateKeyException error) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "同一队列中已存在相同视图名称");
        }
        return current(actor, id, false);
    }
    /** Soft deletes one owned view under optimistic version protection. */
    public String delete(Actor actor, String id, long version) {
        long internalId = lockOwned(actor, id);
        int changed = jdbc.update("""
            UPDATE core_crm_saved_view SET deleted=TRUE,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND principal_id=? AND version=? AND deleted=0
            """, internalId, actor.id(), version);
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "视图版本已变化，请刷新后重试"); }
        return id;
    }
    /** Confirms that a public view id belongs to the current employee. */
    public void authorize(Actor actor, String id) { lockOwned(actor, id); }
    private View current(Actor actor, String id, boolean includeDeleted) {
        List<View> views = jdbc.query("""
            SELECT public_id,resource_type,name,filters_json,version FROM core_crm_saved_view
            WHERE public_id=? AND principal_id=? AND (?=1 OR deleted=0)
            """, (rs, row) -> new View(rs.getString("public_id"), rs.getString("resource_type"),
                rs.getString("name"), readFilters(rs.getString("filters_json")), rs.getLong("version")),
            id, actor.id(), includeDeleted ? 1 : 0);
        if (views.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "保存视图不存在或不属于当前员工"); }
        return views.get(0);
    }
    private long lockOwned(Actor actor, String id) {
        List<Long> rows = jdbc.query("""
            SELECT id FROM core_crm_saved_view
            WHERE public_id=? AND principal_id=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> rs.getLong(1), id, actor.id());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "保存视图不存在或不属于当前员工"); }
        return rows.get(0);
    }
    private String json(Map<String, String> filters) {
        try { return mapper.writeValueAsString(filters); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("筛选条件无法编码", error); }
    }
    private Map<String, String> readFilters(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, String>>() { }); }
        catch (JsonProcessingException error) { throw new IllegalStateException("保存的筛选条件无法读取", error); }
    }
}
