package cn.siyue.core;

import cn.siyue.core.AccessPositionDtos.CreateInput;
import cn.siyue.core.AccessPositionDtos.MembersInput;
import cn.siyue.core.AccessPositionDtos.Position;
import cn.siyue.core.AccessPositionDtos.PositionPage;
import cn.siyue.core.AccessPositionDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies the existing global access-admin boundary to position grant bundles. */
@Service
public class AccessPositionService {
    private final AccessRepository access;
    private final AccessPositionRepository repository;
    private final AccessAdminRepository adminRepository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /**
     * Connects position administration to account locks, grant storage and atomic retry receipts.
     * @param access refreshes active administrator grants for mutations
     * @param repository reads and writes position definitions and memberships
     * @param adminRepository serializes access-administration mutations
     * @param mutations stores request fingerprints, receipts and audit entries
     * @param mapper encodes validated requests and returned positions
     */
    public AccessPositionService(AccessRepository access, AccessPositionRepository repository,
            AccessAdminRepository adminRepository, MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.adminRepository = adminRepository;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /**
     * Returns position definitions within the global access-administration boundary.
     * @param actor authenticated caller with all scope and system.access.manage
     * @param page one-based page, from 1 through 100000
     * @param size number of records, from 1 through 100
     * @return position page with repository-provided totals
     * @throws BusinessException if the supplied actor lacks authority or paging is invalid
     */
    @Transactional(rollbackFor = Exception.class)
    public PositionPage list(Actor actor, int page, int size) {
        authorize(actor);
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "职位分页范围无效");
        }
        return repository.list(page, size);
    }

    /**
     * Creates a position grant bundle after refreshing the administrator and normalizing input.
     * @param actor authenticated administrator, rechecked under the administration lock
     * @param key request key of 16 to 128 safe identifier characters
     * @param input optional position identifier, name, description and at most 100 assignable grants
     * @return created position or the original receipt for an identical retry
     * @throws BusinessException if authority, input, uniqueness or retry binding is invalid
     * @throws JsonProcessingException if canonical request or receipt JSON cannot be processed
     */
    @Transactional(rollbackFor = Exception.class)
    public Position create(Actor actor, String key, CreateInput input) throws JsonProcessingException {
        actor = lockAdministrator(actor);
        requireKey(key);
        CreateInput clean = validateCreate(input);
        String hash = mutations.hash(mapper.writeValueAsString(clean));
        String replay = mutations.replay(actor, key, "system.position.create", hash);
        if (replay != null) { return mapper.readValue(replay, Position.class); }
        Position created = repository.create(actor, clean);
        complete(actor, key, "system.position.create", hash, created);
        return created;
    }

    /**
     * Replaces a position's editable definition using its observed version.
     * @param actor authenticated administrator, rechecked under the administration lock
     * @param id public position identifier, at most 120 safe characters
     * @param key request key of 16 to 128 safe identifier characters
     * @param input positive expected version, name, description, active state and assignable grants
     * @return updated position or the original receipt for an identical retry
     * @throws BusinessException if authority, input, target, version or retry binding is invalid
     * @throws JsonProcessingException if canonical request or receipt JSON cannot be processed
     */
    @Transactional(rollbackFor = Exception.class)
    public Position update(Actor actor, String id, String key, UpdateInput input) throws JsonProcessingException {
        actor = lockAdministrator(actor);
        requireId(id);
        requireKey(key);
        UpdateInput clean = validateUpdate(input);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, clean)));
        String replay = mutations.replay(actor, key, "system.position.update:" + id, hash);
        if (replay != null) { return mapper.readValue(replay, Position.class); }
        Position current = repository.current(id);
        if (current.version() != clean.expectedVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "职位版本已变化，请刷新后重试");
        }
        Position updated = repository.update(actor, id, clean);
        complete(actor, key, "system.position.update:" + id, hash, updated);
        return updated;
    }

    /**
     * Atomically replaces a position's member set while serializing access administration.
     * @param actor authenticated administrator, rechecked under the administration lock
     * @param id public position identifier, at most 120 safe characters
     * @param key request key of 16 to 128 safe identifier characters
     * @param input positive expected version and at most 500 distinct principal identifiers
     * @return position with its replacement membership, or the original identical-retry receipt
     * @throws BusinessException if authority, members, target, version or retry binding is invalid
     * @throws JsonProcessingException if canonical request or receipt JSON cannot be processed
     */
    @Transactional(rollbackFor = Exception.class)
    public Position members(Actor actor, String id, String key, MembersInput input)
            throws JsonProcessingException {
        actor = lockAdministrator(actor);
        requireId(id);
        requireKey(key);
        MembersInput clean = validateMembers(input);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(id, clean)));
        String replay = mutations.replay(actor, key, "system.position.members:" + id, hash);
        if (replay != null) { return mapper.readValue(replay, Position.class); }
        Position current = repository.current(id);
        if (current.version() != clean.expectedVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "职位成员版本已变化，请刷新后重试");
        }
        Position updated = repository.replaceMembers(actor, id, clean);
        complete(actor, key, "system.position.members:" + id, hash, updated);
        return updated;
    }

    private Actor lockAdministrator(Actor actor) {
        adminRepository.lockAdminChanges();
        return authorize(access.lockActive(actor.id()));
    }

    private Actor authorize(Actor actor) {
        actor.require("system.access.manage");
        if (!"all".equals(actor.scope())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "职位模板只允许全局管理员办理");
        }
        return actor;
    }

    private CreateInput validateCreate(CreateInput input) {
        if (input == null) { throw invalid(); }
        String id = input.positionId() == null ? null : positionId(input.positionId());
        return new CreateInput(id, name(input.name()), description(input.description()),
            directoryKind(input.directoryKind(), "staff"), permissions(input.permissionCodes()));
    }

    private UpdateInput validateUpdate(UpdateInput input) {
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1 || input.active() == null) {
            throw invalid();
        }
        return new UpdateInput(input.expectedVersion(), name(input.name()), description(input.description()),
            input.active(), input.directoryKind() == null ? null : directoryKind(input.directoryKind(), null),
            permissions(input.permissionCodes()));
    }

    private MembersInput validateMembers(MembersInput input) {
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1
                || input.principalIds() == null || input.principalIds().size() > 500) { throw invalid(); }
        List<String> ids = input.principalIds().stream().map(AccessPositionService::principalId).toList();
        if (new HashSet<>(ids).size() != ids.size()) { throw invalid(); }
        return new MembersInput(input.expectedVersion(), ids);
    }

    private List<String> permissions(List<String> codes) {
        if (codes == null || codes.size() > 100 || new HashSet<>(codes).size() != codes.size()
                || codes.stream().anyMatch(code -> code == null || !AccessPermissionCatalog.isAssignable(code))) { throw invalid(); }
        return List.copyOf(codes);
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 100) { throw invalid(); }
        return value.trim();
    }

    private static String description(String value) {
        if (value == null) { return ""; }
        if (value.length() > 500) { throw invalid(); }
        return value.trim();
    }

    private static String directoryKind(String value, String fallback) {
        String result = value == null ? fallback : value;
        if (!"staff".equals(result) && !"teacher".equals(result)) { throw invalid(); }
        return result;
    }

    private static String principalId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")) { throw invalid(); }
        return value;
    }

    private static String positionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,120}")) { throw invalid(); }
        return value;
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "幂等键格式无效");
        }
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,120}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "职位编号无效");
        }
    }

    private void complete(Actor actor, String key, String action, String hash, Position position)
            throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(position), position.id()));
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT, "职位、权限或成员输入无效");
    }
}
