package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only explicitly reviewed new writes reach identity resolution; retries keep their original payload.
 * @see OpportunityNeedRepository
 * @see OpportunityNeedReadRepository
 * @see MutationRepository
 */
@Service
public class OpportunityNeedService {
    private final AccessRepository access;
    private final OpportunityNeedRepository repository;
    private final OpportunityNeedReadRepository reads;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects actor locking, need persistence, scoped reads and retry tracking.
     * @param access repository used to reload and lock the active actor
     * @param repository opportunity-need write and preview persistence
     * @param reads scoped opportunity-need read repository
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public OpportunityNeedService(AccessRepository access,OpportunityNeedRepository repository,
            OpportunityNeedReadRepository reads,MutationRepository mutations,ObjectMapper mapper) {
        this.access=access; this.repository=repository; this.reads=reads; this.mutations=mutations; this.mapper=mapper;
    }
    /** Previews candidate matching without committing a need or resolving a new identity.
     * @param actor authenticated actor requesting the preview
     * @param input requested intake and matching criteria
     * @return preview candidates and review requirements
     * @throws BusinessException if authorization, scope, or preview input validation fails
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Preview preview(Actor actor,Input input) { return repository.preview(actor,input); }
    /** Commits an explicitly reviewed intake with idempotent audit tracking.
     * @param actor authenticated actor committing the reviewed intake
     * @param key stable idempotency key
     * @param input reviewed intake payload and source references
     * @return committed receipt, redacted to current scope, or the prior retry result
     * @throws JsonProcessingException if the request or receipt cannot be serialized
     * @throws BusinessException if permission, source review, scope, validation, or replay checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public Receipt commit(Actor actor,String key,Commit input) throws JsonProcessingException {
        OpportunityNeedSupport.intake(actor); OpportunityNeedSupport.key(key); actor=access.lockActive(actor.id());
        repository.authorizeReplay(actor,input);
        String hash=mutations.hash(mapper.writeValueAsString(input));
        String replay=mutations.replay(actor,key,"crm.opportunity.need.commit",hash);
        if(replay!=null)return repository.redact(actor,mapper.readValue(replay,Receipt.class));
        Receipt result=repository.commit(actor,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,"crm.opportunity.need.commit",hash,
            mapper.writeValueAsString(result),result.opportunityId())); return result;
    }
    /** Reads one scoped opportunity need.
     * @param actor authenticated actor requesting the detail
     * @param id public opportunity identifier
     * @return scoped opportunity detail
     * @throws BusinessException if the actor cannot read the opportunity
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Detail detail(Actor actor,String id) { return reads.detail(actor,id); }
    /** Reads the context projection for a scoped opportunity.
     * @param actor authenticated actor requesting the context
     * @param id public opportunity identifier
     * @return opportunity context projection
     * @throws BusinessException if the actor cannot read the opportunity
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Context context(Actor actor,String id) { return reads.context(actor,id); }
    /** Lists student candidates for a scoped opportunity.
     * @param actor authenticated actor requesting candidates
     * @param id public opportunity identifier
     * @param query candidate filters and pagination
     * @return scoped student candidate page
     * @throws BusinessException if the actor cannot read the opportunity or query is invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public StudentPage students(Actor actor,String id,PageQuery query) { return reads.students(actor,id,query); }
    /** Lists existing opportunity/student associations for a scoped opportunity.
     * @param actor authenticated actor requesting existing associations
     * @param id public opportunity identifier
     * @param query filters and pagination
     * @return scoped existing-association page
     * @throws BusinessException if the actor cannot read the opportunity or query is invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public CandidatePage existing(Actor actor,String id,PageQuery query) { return reads.existing(actor,id,query); }
    /** Reads applicable options for a scoped opportunity.
     * @param actor authenticated actor requesting options
     * @param id public opportunity identifier
     * @return option projection available to the actor
     * @throws BusinessException if the actor cannot read the opportunity
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Options optionsFor(Actor actor,String id) { return reads.optionsFor(actor,id); }
    /** Lists consultations related to a scoped opportunity.
     * @param actor authenticated actor requesting consultations
     * @param id public opportunity identifier
     * @param query filters and pagination
     * @return scoped consultation page
     * @throws BusinessException if the actor cannot read the opportunity or query is invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public ConsultationPage consultations(Actor actor,String id,PageQuery query) { return reads.consultations(actor,id,query); }
    /** Updates opportunity profile fields with current-scope checks and idempotent replay.
     * @param actor authenticated actor requesting the profile update
     * @param id public opportunity identifier
     * @param key stable idempotency key
     * @param input profile patch and expected version
     * @return updated detail redacted to the actor or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, scope, validation, version, or replay checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public Detail profile(Actor actor,String id,String key,ProfileUpdate input) throws JsonProcessingException {
        actor=writeActor(actor,id,key);
        String hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String replay=mutations.replay(actor,key,"crm.opportunity.need.profile",hash);
        if(replay!=null)return reads.redact(actor,mapper.readValue(replay,Detail.class));
        Detail result=repository.profile(actor,id,input);
        complete(actor,id,new Write(key,"crm.opportunity.need.profile",hash,result)); return result;
    }
    /** Records an opportunity judgment with current-scope checks and idempotent replay.
     * @param actor authenticated actor recording the judgment
     * @param id public opportunity identifier
     * @param key stable idempotency key
     * @param input judgment values and expected version
     * @return updated detail redacted to the actor or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, scope, validation, version, or replay checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public Detail judge(Actor actor,String id,String key,JudgmentInput input) throws JsonProcessingException {
        actor=writeActor(actor,id,key);
        String hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String replay=mutations.replay(actor,key,"crm.opportunity.need.judgment",hash);
        if(replay!=null)return reads.redact(actor,mapper.readValue(replay,Detail.class));
        Detail result=repository.judge(actor,id,input);
        complete(actor,id,new Write(key,"crm.opportunity.need.judgment",hash,result)); return result;
    }
    private Actor writeActor(Actor actor,String id,String key) {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write", "crm.opportunity.read"); OpportunityNeedSupport.key(key);
        actor=access.lockActive(actor.id()); CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write", "crm.opportunity.read");
        reads.authorized(actor,id,false); return actor;
    }
    private record Write(String key,String action,String hash,Detail result) { }
    private void complete(Actor actor,String id,Write write) throws JsonProcessingException {
        mutations.complete(actor,new MutationRepository.RetryWrite(write.key(),write.action(),write.hash(),mapper.writeValueAsString(write.result()),id));
    }
}
