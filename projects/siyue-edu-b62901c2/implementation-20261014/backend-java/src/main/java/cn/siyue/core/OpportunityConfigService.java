package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.OptionCreate;
import cn.siyue.core.OpportunityNeedDtos.OptionPage;
import cn.siyue.core.OpportunityNeedDtos.OptionQuery;
import cn.siyue.core.OpportunityNeedDtos.OptionReceipt;
import cn.siyue.core.OpportunityNeedDtos.OptionUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned catalog changes follow the same authorization, idempotency and audit rules as business writes.
 * @see OpportunityConfigRepository
 * @see MutationRepository
 */
@Service
public class OpportunityConfigService {
    private final AccessRepository access;
    private final OpportunityConfigRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects principal locking, catalog persistence and mutation tracking.
     * @param access repository used to reload and lock the active actor
     * @param repository scoped opportunity-option persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public OpportunityConfigService(AccessRepository access,OpportunityConfigRepository repository,
            MutationRepository mutations,ObjectMapper mapper) {
        this.access=access; this.repository=repository; this.mutations=mutations; this.mapper=mapper;
    }
    /** Lists opportunity options through repository-enforced actor visibility.
     * @param actor authenticated actor whose access is applied
     * @param query option filters and pagination
     * @return matching option page
     * @throws BusinessException if the repository rejects the actor or query
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public OptionPage list(Actor actor,OptionQuery query) { return repository.list(actor,query); }
    /** Creates an option and records its idempotent audit receipt atomically.
     * @param actor authenticated actor requesting creation
     * @param key stable idempotency key
     * @param input option values and applicable campus
     * @return created option receipt or the prior result for an identical retry
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     * @throws BusinessException if permission, campus scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public OptionReceipt create(Actor actor,String key,OptionCreate input) throws JsonProcessingException {
        actor.require("crm.opportunity.config.manage"); OpportunityNeedSupport.key(key);
        actor=access.lockActive(actor.id()); actor.require("crm.opportunity.config.manage");
        repository.campus(actor,input.campusId());
        String hash=mutations.hash(mapper.writeValueAsString(input));
        String replay=mutations.replay(actor,key,"crm.opportunity.config.create",hash);
        if(replay!=null)return mapper.readValue(replay,OptionReceipt.class);
        OptionReceipt result=repository.create(actor,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,"crm.opportunity.config.create",hash,
            mapper.writeValueAsString(result),result.item().id())); return result;
    }
    /** Updates an option with repository-enforced version and scope rules.
     * @param actor authenticated actor requesting the update
     * @param id public identifier of the option
     * @param key stable idempotency key
     * @param input replacement option values and expected version
     * @return updated option receipt or the prior result for an identical retry
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     * @throws BusinessException if permission, scope, validation, version, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public OptionReceipt update(Actor actor,String id,String key,OptionUpdate input) throws JsonProcessingException {
        actor.require("crm.opportunity.config.manage"); OpportunityNeedSupport.key(key);
        actor=access.lockActive(actor.id()); repository.authorize(actor,id);
        String hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String replay=mutations.replay(actor,key,"crm.opportunity.config.update",hash);
        if(replay!=null)return mapper.readValue(replay,OptionReceipt.class);
        OptionReceipt result=repository.update(actor,id,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,"crm.opportunity.config.update",hash,
            mapper.writeValueAsString(result),id)); return result;
    }
}
