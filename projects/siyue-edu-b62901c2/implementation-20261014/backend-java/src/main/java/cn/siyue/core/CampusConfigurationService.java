package cn.siyue.core;

import cn.siyue.core.CampusConfigurationDtos.Campus;
import cn.siyue.core.CampusConfigurationDtos.CampusFields;
import cn.siyue.core.CampusConfigurationDtos.CampusPage;
import cn.siyue.core.CampusConfigurationDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads campus configuration under settings permission and campus visibility, and applies versioned changes.
 * Mutations reload and lock the active principal, serialize directory changes, and commit configuration,
 * idempotency receipt and audit together at READ_COMMITTED isolation. Checked exceptions roll back writes.
 */
@Service
public class CampusConfigurationService {
    private final AccessRepository access;
    private final CampusConfigurationRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /**
     * Creates the campus configuration transaction boundary.
     * @param access reloads and locks the active caller for mutations
     * @param repository reads scoped configuration and locks or changes campus resources
     * @param mutations stores request hashes, retry receipts and audit events
     * @param mapper serializes commands and stored campus receipts
     */
    public CampusConfigurationService(AccessRepository access, CampusConfigurationRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access; this.repository = repository; this.mutations = mutations; this.mapper = mapper;
    }
    /**
     * Lists visible, nondeleted campuses with their rooms, subjects and grades in a read-only transaction.
     * @param actor caller requiring {@code settings.update}; all scope sees all campuses, campus scope sees memberships
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return matching campuses and total count; unsupported data scopes yield no rows
     * @throws BusinessException when permission is absent or pagination is invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CampusPage list(Actor actor, int page, int size) {
        actor.require("settings.update");
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "校区分页范围无效");
        }
        return repository.list(actor, page, size);
    }
    /**
     * Reads one campus and its current resource configuration in a read-only transaction.
     * @param actor caller requiring {@code settings.update} and all scope or membership in the campus
     * @param id campus public identifier
     * @return current campus fields, rooms, academic options and version
     * @throws BusinessException when permission is absent or the campus is deleted, missing or outside scope
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Campus get(Actor actor, String id) { actor.require("settings.update"); return repository.get(actor, id, false); }

    /**
     * Creates a campus under the directory lock, or returns an identical request's stored result.
     * The active caller must still hold settings permission and all scope before a retry is accepted.
     * @param actor authenticated caller requiring {@code settings.update} and {@code all} data scope
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input new campus fields; name is required, omitted active defaults to true, omitted collections are empty
     * @return created campus at version 1, or the original stored receipt for the same request
     * @throws BusinessException when the caller is inactive or unauthorized, the key or configuration is invalid,
     *     the name already exists, or the key conflicts with an earlier request
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Campus create(Actor actor, String key, CampusFields input) throws JsonProcessingException {
        actor.require("settings.update"); global(actor); key(key);
        actor = access.lockActive(actor.id()); actor.require("settings.update"); global(actor);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "campus.create", hash);
        if (replay != null) { return mapper.readValue(replay, Campus.class); }
        repository.lockDirectory();
        Campus result = repository.create(input);
        complete(actor, key, "campus.create", hash, result);
        return result;
    }
    /**
     * Merges a versioned patch into a visible campus and commits its resources and retry receipt together.
     * Omitted fields retain their values. Active classes and future sessions prevent incompatible room,
     * capacity, subject, grade or campus activation changes. Scope is checked before replay as well as on write.
     * @param actor authenticated caller requiring {@code settings.update} and access to this campus
     * @param id campus public identifier
     * @param key idempotency key matching {@code [A-Za-z0-9._:-]{16,128}}
     * @param input patch and expected current campus version
     * @return updated campus with incremented version, or the stored receipt for an identical retry
     * @throws BusinessException when permission, active principal, scope, version, key, uniqueness or configuration
     *     checks fail, or the patch conflicts with current classes or future room usage
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Campus update(Actor actor, String id, String key, UpdateInput input) throws JsonProcessingException {
        actor.require("settings.update"); key(key);
        actor = access.lockActive(actor.id()); actor.require("settings.update");
        repository.get(actor, id, false);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, input)));
        String replay = mutations.replay(actor, key, "campus.update", hash);
        if (replay != null) { return mapper.readValue(replay, Campus.class); }
        repository.lockDirectory();
        Campus result = repository.update(actor, id, input);
        complete(actor, key, "campus.update", hash, result);
        return result;
    }
    private void complete(Actor actor, String key, String action, String hash, Campus result) throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash, mapper.writeValueAsString(result), result.id()));
    }
    private void global(Actor actor) {
        if (!actor.scope().equals("all")) { throw new BusinessException(ErrorCode.FORBIDDEN, "创建校区需要全部数据范围"); }
    }
    private void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "幂等键格式无效");
        }
    }
}
