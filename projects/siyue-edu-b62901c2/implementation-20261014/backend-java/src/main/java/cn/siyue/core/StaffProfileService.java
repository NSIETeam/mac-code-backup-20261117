package cn.siyue.core;

import cn.siyue.core.StaffProfileDtos.Profile;
import cn.siyue.core.StaffProfileDtos.UpdateInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Updates employee business details under global account administration permission only.
 * @see StaffProfileRepository
 * @see MutationRepository
 */
@Service
public class StaffProfileService {
    private final AccessRepository access;
    private final StaffProfileRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Injects actor locking, profile persistence and idempotency tracking.
     * @param access repository used to reload and lock the active actor
     * @param repository employee profile read and update persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public StaffProfileService(AccessRepository access, StaffProfileRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access; this.repository = repository; this.mutations = mutations; this.mapper = mapper;
    }

    /** Reads an employee profile after enforcing global account-update permission.
     * @param actor authenticated actor requesting the profile
     * @param employeeId public employee identifier
     * @return employee business profile, or an empty profile with version zero when no profile is stored
     * @throws BusinessException if permission or all-data scope is absent, or the employee account is not found
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Profile get(Actor actor, String employeeId) {
        authorize(actor);
        return repository.get(employeeId, false);
    }

    /** Updates allowed employee business fields with optimistic version and idempotency checks.
     * @param actor authenticated actor requesting the update
     * @param key stable idempotency key
     * @param employeeId public employee identifier
     * @param input profile patch and expected version
     * @return updated profile or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, scope, employee, validation, version, or retry checks fail
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Profile update(Actor actor, String key, String employeeId, UpdateInput input) throws JsonProcessingException {
        authorize(actor); validateKey(key);
        actor = access.lockActive(actor.id()); authorize(actor);
        String hash = mutations.hash(mapper.writeValueAsString(new Object[]{employeeId, input}));
        repository.ensureEditable(employeeId);
        String replay = mutations.replay(actor, key, "staff.profile.update", hash);
        if (replay != null) { return mapper.readValue(replay, Profile.class); }
        Profile result = repository.update(employeeId, input == null ? null : input.patch(),
            input == null ? null : input.expectedVersion());
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "staff.profile.update", hash,
            mapper.writeValueAsString(result), employeeId));
        return result;
    }

    private static void authorize(Actor actor) {
        actor.require("account.update");
        if (!"all".equals(actor.scope())) { throw new BusinessException(ErrorCode.FORBIDDEN, "编辑职工档案需要全部数据范围"); }
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "幂等键格式无效");
        }
    }
}
