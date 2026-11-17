package cn.siyue.core;

import cn.siyue.core.StudentIdentifierDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Filing external evidence does not change the student version, merge families or confer academic access.
 * @see StudentIdentifierRepository
 * @see MutationRepository
 */
@Service
public class StudentIdentifierService {
    private final AccessRepository access;
    private final StudentIdentifierRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects actor locking, identifier persistence and idempotency tracking.
     * @param access repository used to reload and lock the active actor
     * @param repository scoped student identifier persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public StudentIdentifierService(AccessRepository access,StudentIdentifierRepository repository,
            MutationRepository mutations,ObjectMapper mapper){this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;}
    /** Lists identifiers attached to the explicitly selected customer/student relationship.
     * @param actor authenticated actor requesting identifiers
     * @param target public customer and student identifiers defining the relationship
     * @param query status and pagination filters
     * @return scoped identifier page
     * @throws BusinessException if required read grants, relationship scope, status, or pagination are invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page list(Actor actor,Target target,Query query){
        actor.require("crm.customer.read");
        if(!actor.permissions().contains("crm.opportunity.intake"))actor.require("student.read");
        validateTarget(target);Link link=repository.authorize(actor,target,false);
        String status=query==null||query.status()==null?"active":query.status();
        if(!Set.of("active","inactive","all").contains(status))invalid();
        int page=query==null||query.page()==null?1:query.page(),size=query==null||query.size()==null?20:query.size();
        if(page<1||page>100000||size<1||size>100)invalid();
        return repository.list(link,new Query(status,page,size));
    }
    /** Creates an identifier record without changing the student version or family linkage.
     * @param actor authenticated actor creating the identifier
     * @param target public customer and student identifiers defining the relationship
     * @param key stable idempotency key
     * @param input identifier namespace/value and expected student version
     * @return created identifier or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, relationship scope, validation, version, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public Identifier create(Actor actor,Target target,String key,Create input)throws JsonProcessingException{
        requireWrite(actor);validateTarget(target);OpportunityNeedSupport.key(key);
        if(input==null||input.expectedStudentVersion()<1)invalid();
        StudentIdentifierRepository.exact(input.namespace(),100);StudentIdentifierRepository.exact(input.value(),120);
        actor=access.lockActive(actor.id());requireWrite(actor);
        Link link=repository.authorize(actor,target,true);
        String action="crm.customer.student.identifier.create",hash=mutations.hash(mapper.writeValueAsString(List.of(target,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,Identifier.class);
        Identifier result=repository.create(actor,link,input);complete(actor,key,action,hash,result);return result;
    }
    /** Deactivates an identifier with a reason and optimistic version check.
     * @param actor authenticated actor deactivating the identifier
     * @param target public customer and student identifiers defining the relationship
     * @param id public identifier-record identifier
     * @param key stable idempotency key
     * @param input expected identifier version and deactivation reason
     * @return deactivated identifier or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized
     * @throws BusinessException if permission, relationship scope, validation, version, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public Identifier deactivate(Actor actor,Target target,String id,String key,Deactivate input)throws JsonProcessingException{
        requireWrite(actor);validateTarget(target);identifier(id);OpportunityNeedSupport.key(key);
        if(input==null||input.expectedVersion()<1||input.reason()==null||input.reason().isBlank()||input.reason().length()>1000)invalid();
        actor=access.lockActive(actor.id());requireWrite(actor);
        Link link=repository.authorize(actor,target,true);repository.get(link,id);
        String action="crm.customer.student.identifier.deactivate",hash=mutations.hash(mapper.writeValueAsString(List.of(target,id,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,Identifier.class);
        Identifier result=repository.deactivate(actor,link,id,input);complete(actor,key,action,hash,result);return result;
    }
    private void complete(Actor actor,String key,String action,String hash,Identifier result)throws JsonProcessingException{
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),result.id()));
    }
    private static void requireWrite(Actor actor){actor.require("crm.customer.read");actor.require("crm.customer.identity.write");}
    private static void validateTarget(Target target){if(target==null)invalid();identifier(target.customerId());identifier(target.studentId());}
    private static void identifier(String id){if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}"))invalid();}
    private static void invalid(){throw new BusinessException(ErrorCode.INVALID_INPUT,"学号备案参数无效，请重新核对");}
}
