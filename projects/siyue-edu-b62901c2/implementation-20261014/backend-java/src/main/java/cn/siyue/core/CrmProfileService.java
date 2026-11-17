package cn.siyue.core;

import cn.siyue.core.CrmProfileDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Locks current grants and source records before replaying or committing profile revisions. */
@Service
public class CrmProfileService {
    private final AccessRepository access;
    private final CrmCustomerProfileRepository customers;
    private final CrmCampaignProfileRepository campaigns;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /**
     * Builds the profile application service with the repositories used for scoped reads,
     * mutations, active-principal checks, and idempotent receipts.
     * @param access resolves and locks the current active principal for writes
     * @param customers reads and updates customer and lead-linked profiles
     * @param campaigns reads and updates campaign profiles
     * @param mutations stores mutation hashes and replay receipts
     * @param mapper serializes mutation input and stored receipts
     */
    public CrmProfileService(AccessRepository access,CrmCustomerProfileRepository customers,
            CrmCampaignProfileRepository campaigns,MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.customers=customers;this.campaigns=campaigns;this.mutations=mutations;this.mapper=mapper;
    }
    /** Returns profile form choices filtered by the supplied actor's data scope.
     * @param actor authenticated caller whose scope is applied by the campaign repository
     * @param input optional campus and other option filters
     * @return available CRM profile choices
     * @throws BusinessException when the campus filter is outside the actor's scope or invalid
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public Options options(Actor actor,OptionsQuery input) {return campaigns.options(actor,input);}
    /** Reads a customer profile after requiring lead-follow capability and repository scope.
     * @param actor authenticated caller
     * @param id customer public identifier
     * @return the scoped customer profile
     * @throws BusinessException when permission is missing or the record is unavailable in scope
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public Customer customer(Actor actor,String id) {CrmLegacyAccess.read(actor, "crm.customer.read");return customers.profile(actor,id);}
    /** Reads the lead-facing profile and its scoped customer link.
     * @param actor authenticated caller
     * @param id lead public identifier
     * @return the lead profile and associated profile data
     * @throws BusinessException when permission is missing or the lead is outside scope
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public LeadProfile leadProfile(Actor actor,String id) {CrmLegacyAccess.read(actor, "crm.lead.read");return customers.leadProfile(actor,id);}
    /** Reads a campaign profile for a caller with lead-creation capability.
     * @param actor authenticated caller
     * @param id campaign public identifier
     * @return the scoped campaign profile
     * @throws BusinessException when permission is missing or the campaign is unavailable
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public Campaign campaign(Actor actor,String id) {CrmLegacyAccess.read(actor, "crm.activity.read");return campaigns.profile(actor,id);}
    /** Creates a campaign profile atomically and records an idempotent replay receipt.
     * @param actor authenticated caller; active status and lead.create are rechecked under lock
     * @param key idempotency key, 16–128 characters from letters, digits, dot, underscore, colon, or hyphen
     * @param input campaign fields accepted by the campaign repository
     * @return the created campaign, or the prior result for the same key and payload
     * @throws BusinessException for invalid input, missing permission, scope violations, or key conflicts
     * @throws JsonProcessingException if the input or receipt cannot be serialized
     */
    @Transactional(rollbackFor=Exception.class)
    public Campaign createCampaign(Actor actor,String key,java.util.Map<String,Object> input) throws JsonProcessingException {
        key(key);actor=access.lockActive(actor.id());actor.require("lead.create");
        String action="campaign.create",hash=mutations.hash(mapper.writeValueAsString(input));String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) {Campaign result=mapper.readValue(replay,Campaign.class);campaigns.profile(actor,result.id());return result;}
        Campaign result=campaigns.create(actor,input);complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.id()));return result;
    }
    /** Publishes the specified campaign version using an idempotent mutation.
     * @param actor authenticated caller; active status and lead.create are rechecked under lock
     * @param id campaign public identifier
     * @param key idempotency key in the service's 16–128 character format
     * @param input expected campaign version; it must be at least 1
     * @return published campaign or the stored result for an identical retry
     * @throws BusinessException for invalid version/key, missing permission, scope violations, or stale state
     * @throws JsonProcessingException if request or result serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public Campaign publishCampaign(Actor actor,String id,String key,CrmDtos.ActivityVersion input) throws JsonProcessingException {
        key(key);if(input==null||input.expectedVersion()<1) throw CrmProfileValues.invalid("活动版本无效");
        actor=access.lockActive(actor.id());actor.require("lead.create");campaigns.authorizeWrite(actor,id);
        String action="campaign.publish",hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) return mapper.readValue(replay,Campaign.class);
        Campaign result=campaigns.publish(actor,id,input.expectedVersion());complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),id));return result;
    }
    /** Applies a version-checked customer patch; changing campus additionally requires lead.assign.
     * @param actor authenticated caller; active status and permissions are rechecked under lock
     * @param id customer public identifier
     * @param key idempotency key in the service's 16–128 character format
     * @param input nonempty patch with expectedVersion at least 1
     * @return updated customer or the stored result for an identical retry
     * @throws BusinessException for invalid patch/key, missing permission, scope violations, or version conflict
     * @throws JsonProcessingException if request or result serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public Customer updateCustomer(Actor actor,String id,String key,Patch input) throws JsonProcessingException {
        validate(key,input);actor=access.lockActive(actor.id());actor.require("lead.follow");
        if(input.patch().containsKey("campusId")) actor.require("lead.assign");customers.authorizeWrite(actor,id);
        String action="customer.update",hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String replay=mutations.replay(actor,key,action,hash);if(replay!=null) return mapper.readValue(replay,Customer.class);
        Customer result=customers.update(actor,id,input);complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),id));return result;
    }
    /** Applies a version-checked campaign profile patch and records its retry receipt.
     * @param actor authenticated caller; active status and lead.create are rechecked under lock
     * @param id campaign public identifier
     * @param key idempotency key in the service's 16–128 character format
     * @param input nonempty patch with expectedVersion at least 1
     * @return updated campaign or the stored result for an identical retry
     * @throws BusinessException for invalid patch/key, missing permission, scope violations, or version conflict
     * @throws JsonProcessingException if request or result serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public Campaign updateCampaign(Actor actor,String id,String key,Patch input) throws JsonProcessingException {
        validate(key,input);actor=access.lockActive(actor.id());actor.require("lead.create");campaigns.authorizeWrite(actor,id);
        String action="campaign.update",hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String replay=mutations.replay(actor,key,action,hash);if(replay!=null) return mapper.readValue(replay,Campaign.class);
        Campaign result=campaigns.update(actor,id,input);complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),id));return result;
    }
    /** Creates or links a customer profile from a lead at the caller-supplied lead version.
     * @param actor authenticated caller; active status and lead.follow are rechecked under lock
     * @param key idempotency key in the service's 16–128 character format
     * @param input lead/customer identifiers and expected lead version (at least 1)
     * @return the linked customer and resulting relationship
     * @throws BusinessException for invalid version/key, permission or scope failure, or stale lead state
     * @throws JsonProcessingException if request or result serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public Link fromLead(Actor actor,String key,FromLead input) throws JsonProcessingException {
        key(key);if(input==null||input.expectedVersion()<1) throw CrmProfileValues.invalid("线索版本无效");
        actor=access.lockActive(actor.id());actor.require("lead.follow");customers.authorizeLead(actor,input.leadId());
        if(input.customerId()!=null) customers.profile(actor,input.customerId());
        String action="customer.fromLead",hash=mutations.hash(mapper.writeValueAsString(input));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null) {Link result=mapper.readValue(replay,Link.class);customers.profile(actor,result.record().id());return result;}
        Link result=customers.fromLead(actor,input);complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.record().id()));return result;
    }
    private void complete(Actor actor,MutationRepository.RetryWrite write) {mutations.complete(actor,write);}
    private void validate(String key,Patch input) {key(key);if(input==null||input.patch()==null||input.expectedVersion()<1) throw CrmProfileValues.invalid("修改字段和当前版本不能为空");}
    private void key(String value) {if(value==null||!value.matches("[A-Za-z0-9._:-]{16,128}")) throw CrmProfileValues.invalid("幂等键无效");}
}
