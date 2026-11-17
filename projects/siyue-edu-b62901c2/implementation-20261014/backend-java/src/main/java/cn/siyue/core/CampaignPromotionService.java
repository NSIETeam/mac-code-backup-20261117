package cn.siyue.core;

import cn.siyue.core.CampaignPromotionDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns promotion authorization, campaign locks, retry receipts and audit in one local transaction. */
@Service
public class CampaignPromotionService {
    private final AccessRepository access;
    private final CampaignPromotionRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects only local authorization, SQL persistence and deterministic receipt serialization.
     * @param access current database grants and account locking
     * @param repository campaign links and scoped eligible choices
     * @param mutations encrypted retry receipts and minimal audit facts
     * @param mapper canonical request and result serializer
     */
    public CampaignPromotionService(AccessRepository access,CampaignPromotionRepository repository,
            MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;
    }
    /** Resolves current active grants and returns links in campaign and promoter scope.
     * @param actor authenticated employee; stale grants are reloaded under lock
     * @param id campaign public identifier
     * @return visible links, current version and eligible form/promoter choices
     * @throws BusinessException if the account is inactive, lead.create is absent or campaign is outside scope
     */
    @Transactional(rollbackFor=Exception.class)
    public Page list(Actor actor,String id) {
        actor=access.lockActive(actor.id());actor.require("lead.create");return repository.list(actor,id);
    }
    /** Creates one immutable link, advances the campaign version, and records audit and exact retry output.
     * @param actor authenticated employee; current active grants are reloaded
     * @param id campaign public identifier in write scope
     * @param key unique request key, 16 through 128 safe characters
     * @param input published form, active member promoter, nonblank label and expected campaign version
     * @return link and new campaign version, or the original result on identical retry
     * @throws BusinessException if scope, state, membership, version or idempotency fails
     * @throws JsonProcessingException if canonical request or result cannot be encoded
     */
    @Transactional(rollbackFor=Exception.class)
    public Created create(Actor actor,String id,String key,Create input)throws JsonProcessingException {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{16,128}")||input==null||input.expectedVersion()<1
                ||input.name()==null||input.name().isBlank()||input.name().length()>200
                ||input.formId()==null||input.promoterId()==null)throw new BusinessException(ErrorCode.INVALID_INPUT,"推广链接参数无效");
        actor=access.lockActive(actor.id());actor.require("lead.create");repository.authorize(actor,id,true);
        String action="campaign.promotionLink.create",hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String previous=mutations.replay(actor,key,action,hash);
        if(previous!=null)return mapper.readValue(previous,Created.class);
        Created result=repository.create(actor,id,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.link().id()));
        return result;
    }
}
