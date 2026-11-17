package cn.siyue.core;

import cn.siyue.core.AccessAdminDtos.CreateInput;
import cn.siyue.core.AccessAdminDtos.PrincipalView;
import cn.siyue.core.AccessAdminDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Restricts account administration to explicitly bootstrapped system administrators. */
@Service
public class AccessAdminService {
    private final AccessRepository access;
    private final AccessAdminRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /**
     * Connects account administration to grant refresh, persistence and retry auditing.
     * @param access reloads and locks the acting employee's active identity
     * @param repository serializes administrator changes and stores principal mappings
     * @param mutations persists request fingerprints, receipts and audit records
     * @param mapper encodes canonical request and receipt JSON
     */
    public AccessAdminService(AccessRepository access, AccessAdminRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /**
     * Lists employee access after serializing administrator changes and refreshing grants.
     * @param actor authenticated employee; must currently have all scope and system.access.manage
     * @param page one-based page, from 1 through 100000
     * @param size number of records, from 1 through 100
     * @return the requested page of current principal access settings
     * @throws BusinessException if the employee is inactive, lacks authority or supplies invalid paging
     */
    @Transactional(rollbackFor = Exception.class)
    public List<PrincipalView> list(Actor actor, int page, int size) {
        repository.lockAdminChanges();
        actor = requireAdministrator(access.lockActive(actor.id()));
        validatePage(page, size);
        return repository.list(page, size);
    }

    /**
     * Creates an administrator-supplied OIDC issuer/subject mapping and assignable grants.
     * This operation does not authenticate the external subject or issue a login credential.
     * The mapping, retry receipt and audit commit together; identical retries return the original receipt.
     * @param actor authenticated administrator, rechecked for active status and all scope
     * @param key request key of 16 to 100 letters, digits, underscores or hyphens
     * @param input validated identity mapping, scope, assignable grants, denials and campus identifiers
     * @return created principal settings, or the original successful receipt on an identical retry
     * @throws BusinessException if authority, identity URI, grants, campuses or retry binding is invalid
     * @throws JsonProcessingException if request or receipt JSON cannot be encoded or replayed
     */
    @Transactional(rollbackFor = Exception.class)
    public PrincipalView create(Actor actor, String key, CreateInput input) throws JsonProcessingException {
        repository.lockAdminChanges();
        actor = requireAdministrator(access.lockActive(actor.id()));
        requireKey(key);
        validateIdentity(input);
        validateInput(input.scope(), input.permissionCodes(), input.deniedPermissionCodes(), input.campusIds());
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "system.access.create", hash);
        if (replay != null) { return mapper.readValue(replay, PrincipalView.class); }
        PrincipalView created = repository.create(actor, input);
        complete(actor, new MutationRepository.RetryWrite(key, "system.access.create", hash,
            mapper.writeValueAsString(created), created.principalId()));
        return created;
    }

    /**
     * Updates another employee's active state and grants using an optimistic access version.
     * Self-modification is rejected before replay; current administrator authority is always rechecked.
     * @param actor authenticated administrator, rechecked for active status and all scope
     * @param principalId public identifier of a different employee
     * @param key request key of 16 to 100 letters, digits, underscores or hyphens
     * @param input validated settings and the version observed before editing
     * @return updated settings, or the original successful receipt for an identical retry
     * @throws BusinessException if authority, target, version, settings or retry binding is invalid
     * @throws JsonProcessingException if request or receipt JSON cannot be encoded or replayed
     */
    @Transactional(rollbackFor = Exception.class)
    public PrincipalView update(Actor actor, String principalId, String key, UpdateInput input)
            throws JsonProcessingException {
        repository.lockAdminChanges();
        actor = requireAdministrator(access.lockActive(actor.id()));
        if (actor.publicId().equals(principalId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员不能通过此接口修改自身授权");
        }
        requireKey(key);
        validateInput(input.scope(), input.permissionCodes(), input.deniedPermissionCodes(), input.campusIds());
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "system.access.update:" + principalId, hash);
        if (replay != null) { return mapper.readValue(replay, PrincipalView.class); }
        PrincipalView before = repository.current(principalId);
        if (before.version() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "员工授权版本已变化，请刷新后重试");
        }
        PrincipalView updated = repository.update(actor, principalId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "system.access.update:" + principalId,
            hash, mapper.writeValueAsString(updated), principalId));
        return updated;
    }

    private Actor requireAdministrator(Actor actor) {
        actor.require("system.access.manage");
        if (!"all".equals(actor.scope())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "员工账号授权只允许全局管理员办理");
        }
        return actor;
    }

    private void validateInput(String scope, List<String> permissions, List<String> denied, List<String> campuses) {
        if (new HashSet<>(permissions).size() != permissions.size()
                || permissions.stream().anyMatch(code -> !AccessPermissionCatalog.isAssignable(code))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "权限列表含重复项或未登记权限");
        }
        if (denied == null || new HashSet<>(denied).size() != denied.size()
                || denied.stream().anyMatch(code -> code == null || !AccessPermissionCatalog.contains(code))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "禁止权限列表含重复项或未登记权限");
        }
        if ("campus".equals(scope) && campuses.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "校区范围账号至少需要一个校区");
        }
        if ("all".equals(scope) && !campuses.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "全局范围账号不能配置校区限制");
        }
        if (new HashSet<>(campuses).size() != campuses.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "校区列表不能重复");
        }
    }

    private void validateIdentity(CreateInput input) {
        try {
            URI issuer = URI.create(input.issuer());
            String host = issuer.getHost();
            boolean localHttp = "http".equalsIgnoreCase(issuer.getScheme())
                && Set.of("localhost", "127.0.0.1", "::1").contains(host);
            boolean secure = "https".equalsIgnoreCase(issuer.getScheme()) || localHttp;
            boolean invalid = !issuer.isAbsolute() || host == null || !secure || issuer.getQuery() != null
                || issuer.getFragment() != null || input.issuer().getBytes(StandardCharsets.UTF_8).length > 1024
                || input.subject().getBytes(StandardCharsets.UTF_8).length > 512;
            if (invalid) { throw new BusinessException(ErrorCode.INVALID_INPUT, "OIDC issuer 或 subject 无效"); }
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "OIDC issuer 必须是有效的绝对 URI");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "员工账号分页范围无效");
        }
    }

    private void complete(Actor actor, MutationRepository.RetryWrite write) {
        mutations.complete(actor, write);
    }

    private void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
    }
}
