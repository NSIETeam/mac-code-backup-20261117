package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists eligible opportunity assignees and performs versioned, audited opportunity handoffs. */
@Service
public class OpportunityAssignmentService {
    private final AccessRepository access;
    private final OpportunityAssignmentRepository repository;
    private final OpportunityNeedReadRepository reads;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Creates the assignment service with actor rechecks, eligibility/scope queries, persistence, and receipts.
     * @param access active-principal lookup and lock for write-time permission rechecks
     * @param repository eligible-assignee directory and assignment persistence
     * @param reads opportunity/customer authorization and current-value reads
     * @param mutations idempotency receipt and audit persistence
     * @param mapper serializer used to hash requests and persist replay results
     */
    public OpportunityAssignmentService(AccessRepository access,OpportunityAssignmentRepository repository,
            OpportunityNeedReadRepository reads,MutationRepository mutations,ObjectMapper mapper) {
        this.access=access; this.repository=repository; this.reads=reads; this.mutations=mutations; this.mapper=mapper;
    }
    /** Lists employees eligible for a customer or opportunity campus.
     * @param actor caller requiring {@code crm.opportunity.assign}; customer selection also requires
     *              {@code crm.opportunity.intake} and {@code crm.customer.read}, while opportunity selection requires
     *              {@code crm.opportunity.read}
     * @param query exactly one of customerId or opportunityId, with optional page and size (defaults 1 and 20)
     * @return eligible employees and bounded page metadata
     * @throws BusinessException when permissions, record scope, selector exclusivity, or pagination validation fails
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
public AssigneePage list(Actor actor,AssigneeQuery query) { return repository.list(actor,query); }
    /** Assigns an opportunity to an eligible owner if its expected version is still current.
     * @param actor caller requiring {@code crm.opportunity.assign} and {@code crm.opportunity.write}; active status
     *              and permissions are rechecked after the caller row is locked
     * @param id public ID of the opportunity in the actor's authorized scope
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons, or hyphens
     * @param input eligible owner public ID, nonblank reason, and expected opportunity version
     * @return assignment receipt with the new owner, incremented version, and whether the opportunity remains visible
     * @throws BusinessException when permission, scope, owner eligibility, reason, key, or expected version fails
     * @throws JsonProcessingException when request hashing or replay-result serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
public AssignmentReceipt assign(Actor actor,String id,String key,Assignment input) throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.opportunity.assign"); CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); OpportunityNeedSupport.key(key);
        actor=access.lockActive(actor.id()); CrmLegacyAccess.operation(actor, "lead.assign", "crm.opportunity.assign"); CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write");
        String hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String replay=mutations.replay(actor,key,"crm.opportunity.assign",hash);
        if(replay!=null) {
            AssignmentReceipt saved=mapper.readValue(replay,AssignmentReceipt.class);
            boolean priorOwnHandoff=List.of("own","assigned").contains(actor.scope())&&"left_scope".equals(saved.scopeStatus());
            if(!priorOwnHandoff)reads.authorized(actor,id,false);
            boolean visible=reads.readable(actor,reads.raw(id,false));
            return new AssignmentReceipt(saved.opportunityId(),saved.ownerId(),saved.version(),visible?"visible":"left_scope");
        }
        reads.authorized(actor,id,false);
        AssignmentReceipt result=repository.assign(actor,id,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,"crm.opportunity.assign",hash,mapper.writeValueAsString(result),id));
        return result;
    }
}
