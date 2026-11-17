package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.NameIndexRebuild;
import cn.siyue.core.CustomerSearchDtos.NameIndexStatus;
import cn.siyue.core.CustomerSearchDtos.Rebuild;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provides global, separately permissioned and audited maintenance for searchable student-name and identifier indexes.
 * Write operations recheck the active principal, use idempotent receipts, and rebuild bounded batches.
 */
@Service
public class CustomerSearchMaintenanceService {
    private final AccessRepository access;
    private final StudentNameIndexRepository names;
    private final StudentIdentifierRepository numbers;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Creates the maintenance service with authorization, both index stores, mutation receipts, and JSON support.
     * @param access repository used to recheck the active administrator before index writes
     * @param names student-name index status and rebuild operations
     * @param numbers student-identifier index status and rebuild operations
     * @param mutations idempotency and audit receipt store
     * @param mapper serializer used to hash requests and persist replay results
     */
    public CustomerSearchMaintenanceService(AccessRepository access,StudentNameIndexRepository names,
            StudentIdentifierRepository numbers,MutationRepository mutations,ObjectMapper mapper) {
        this.access=access; this.names=names; this.numbers=numbers; this.mutations=mutations; this.mapper=mapper;
    }
    /** Reads aggregate status for the student-name search index.
     * @param actor caller requiring {@code crm.customer.search.manage} with global scope
     * @return total, indexed, pending, and index-version counts
     * @throws BusinessException when the caller lacks the maintenance grant or global scope
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public NameIndexStatus nameStatus(Actor actor) { requireManager(actor); return names.status(); }
    /** Rebuilds a bounded page of the student-name index and records an idempotent audit receipt.
     * @param actor caller requiring {@code crm.customer.search.manage} with global scope
     * @param key idempotency key of 16–128 supported letters, digits, dots, underscores, colons, or hyphens
     * @param input nonnegative starting row ID and a batch limit from 1 through 200
     * @return processed count, continuation position, completion flag, and resulting index status
     * @throws BusinessException when permission, scope, key, or batch bounds are invalid
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public NameIndexRebuild rebuildNames(Actor actor,String key,Rebuild input) throws JsonProcessingException {
        actor=writeActor(actor,key,input); String hash=mutations.hash(mapper.writeValueAsString(input));
        String action="crm.customer.search.names.rebuild";
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,NameIndexRebuild.class);
        var result=names.rebuild(input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),"student-name-index"));
        return result;
    }
    /** Reads aggregate status for the student-identifier search index.
     * @param actor caller requiring {@code crm.customer.search.manage} with global scope
     * @return total, indexed, pending, and index-version counts
     * @throws BusinessException when the caller lacks the maintenance grant or global scope
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public StudentIdentifierDtos.IndexStatus numberStatus(Actor actor) { requireManager(actor); return numbers.indexStatus(); }
    /** Rebuilds a bounded page of student-identifier index entries and records an idempotent audit receipt.
     * @param actor caller requiring {@code crm.customer.search.manage} with global scope
     * @param key idempotency key of 16–128 supported letters, digits, dots, underscores, colons, or hyphens
     * @param input nonnegative starting row ID and a batch limit from 1 through 200
     * @return processed count, continuation position, and completion flag
     * @throws BusinessException when permission, scope, key, or batch bounds are invalid
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public StudentIdentifierDtos.RebuildResult rebuildNumbers(Actor actor,String key,Rebuild input) throws JsonProcessingException {
        actor=writeActor(actor,key,input); String hash=mutations.hash(mapper.writeValueAsString(input));
        String action="crm.customer.search.numbers.rebuild";
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,StudentIdentifierDtos.RebuildResult.class);
        var result=numbers.rebuildIndexes(input.afterId(),input.limit());
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),"student-number-index"));
        return result;
    }
    private Actor writeActor(Actor actor,String key,Rebuild input) {
        requireManager(actor);
        if(key==null||!key.matches("[A-Za-z0-9._:-]{16,128}")||input==null||input.afterId()<0||input.limit()<1||input.limit()>200)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"索引维护请求或幂等键无效");
        Actor current=access.lockActive(actor.id()); requireManager(current); return current;
    }
    private static void requireManager(Actor actor) {
        actor.require("crm.customer.search.manage");
        if(!"all".equals(actor.scope()))throw new BusinessException(ErrorCode.FORBIDDEN,"索引维护仅允许全范围授权管理员执行");
    }
}
