package cn.siyue.core;

import cn.siyue.core.ConsultationDtos.Consultation;
import cn.siyue.core.ConsultationDtos.Input;
import cn.siyue.core.ConsultationDtos.MarkRead;
import cn.siyue.core.ConsultationDtos.Notice;
import cn.siyue.core.ConsultationDtos.NoticePage;
import cn.siyue.core.ConsultationDtos.NoticeQuery;
import cn.siyue.core.ConsultationDtos.Page;
import cn.siyue.core.ConsultationDtos.Query;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultation registration, scoped reads and handoff-notice acknowledgements.
 * @see CrmConsultationRepository
 * @see MutationRepository
 */
@Service
public class CrmConsultationService {
    private static final String REGISTER="crm.consultation.register";
    private static final String NOTICE_READ="crm.consultation.notice.read";
    private final AccessRepository access;
    private final CrmConsultationRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects active-actor locking, consultation persistence and retry serialization.
     * @param access active-principal locking repository
     * @param repository scoped consultation and notice persistence repository
     * @param mutations idempotency and audit persistence repository
     * @param mapper JSON serializer used to bind retry bodies
     */
    public CrmConsultationService(AccessRepository access,CrmConsultationRepository repository,
            MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;
    }

    /** Lists consultations with validated date, scope and bounded pagination filters.
     * @param actor authenticated actor whose visibility is applied
     * @param query requested consultation filters; null selects defaults
     * @return a page of consultations visible to the actor
     * @throws BusinessException if permissions, team scope, filters, or pagination are invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Page list(Actor actor,Query query) {
        requireRead(actor);
        Query filters=query==null?new Query(null,null,null,null,null,null,null,null,null):query;
        if(filters.page()<1||filters.page()>100000||filters.pageSize()<1||filters.pageSize()>100
                ||filters.from()!=null&&filters.to()!=null&&filters.from().isAfter(filters.to())) {
            invalid("咨询查询条件或分页范围无效");
        }
        if("team".equals(filters.scope())&&(!Set.of("all","campus").contains(actor.scope())
                ||!actor.permissions().contains("crm.lead.assign")&&!actor.permissions().contains("lead.assign")&&!actor.permissions().contains("report.read"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN,"没有团队范围查看权限");
        }
        return repository.list(actor,filters);
    }

    /** Reads one consultation in the actor's permitted data scope.
     * @param actor authenticated actor requesting the consultation
     * @param id public consultation identifier
     * @return the scoped consultation
     * @throws BusinessException if permission or identifier validation fails, or the consultation is inaccessible
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public Consultation get(Actor actor,String id) {
        requireRead(actor);
        if(id==null||id.isBlank()||id.length()>120)invalid("咨询编号无效");
        return repository.get(actor,id,false);
    }

    /** Registers a consultation and its audit/idempotency record atomically.
     * @param actor authenticated actor registering the consultation
     * @param key stable idempotency key for this request
     * @param input customer, channel, note and optional next follow-up time
     * @return created consultation or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public Consultation register(Actor actor,String key,Input input)throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.create", "crm.lead.write", "crm.customer.read");requireKey(key);
        validate(input);
        actor=access.lockActive(actor.id());CrmLegacyAccess.operation(actor, "lead.create", "crm.lead.write", "crm.customer.read");
        repository.authorizeInput(actor,input);
        String hash=mutations.hash(mapper.writeValueAsString(input));
        String replay=mutations.replay(actor,key,REGISTER,hash);
        if(replay!=null)return mapper.readValue(replay,Consultation.class);
        Consultation consultation=repository.register(actor,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,REGISTER,hash,
            mapper.writeValueAsString(consultation),consultation.id()));
        return consultation;
    }

    /** Lists handoff notices with recipient status and bounded pagination.
     * @param actor authenticated actor receiving the notices
     * @param query requested status and pagination; null selects defaults
     * @return notice page visible to the actor
     * @throws BusinessException if permission, status, or pagination checks fail
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public NoticePage notices(Actor actor,NoticeQuery query) {
        requireRead(actor);
        NoticeQuery filters=query==null?new NoticeQuery(null,null,null):query;
        if(!Set.of("unread","read","all").contains(filters.status())||filters.page()<1
                ||filters.page()>100000||filters.pageSize()<1||filters.pageSize()>100)invalid("提醒查询条件无效");
        return repository.notices(actor,filters);
    }

    /** Marks one visible notice read using optimistic version control and idempotent replay.
     * @param actor authenticated actor acknowledging the notice
     * @param id public notice identifier
     * @param key stable idempotency key for this request
     * @param input expected notice version
     * @return updated notice or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, scope, validation, version, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public Notice markRead(Actor actor,String id,String key,MarkRead input)throws JsonProcessingException {
        requireRead(actor);requireKey(key);
        if(id==null||id.isBlank()||id.length()>120||input==null)invalid("提醒编号或版本无效");
        actor=access.lockActive(actor.id());requireRead(actor);
        Notice current=repository.getNotice(actor,id);
        String hash=mutations.hash(mapper.writeValueAsString(List.of(id,input)));
        String replay=mutations.replay(actor,key,NOTICE_READ,hash);
        if(replay!=null)return mapper.readValue(replay,Notice.class);
        Notice updated=repository.markRead(actor,id,input.expectedVersion());
        mutations.complete(actor,new MutationRepository.RetryWrite(key,NOTICE_READ,hash,
            mapper.writeValueAsString(updated),updated.id()));
        return updated;
    }

    private void requireRead(Actor actor) {
        actor.requireAny("crm.lead.read", "crm.lead.write", "crm.lead.follow", "lead.read", "lead.create", "lead.follow");
    }
    private void validate(Input input) {
        if(input==null||input.customerId()==null||input.customerId().isBlank()
                ||input.channel()==null||!Set.of("phone","wechat","visit","online","other").contains(input.channel())
                ||input.note()==null||input.note().isBlank()||input.note().length()>5000)invalid("咨询内容或方式无效");
        if(input.nextFollowAt()!=null&&!input.nextFollowAt().isAfter(Instant.now()))invalid("下次联系时间须晚于当前时间");
    }
    private void requireKey(String key) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{16,128}"))invalid("Idempotency-Key 格式无效");
    }
    private void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT,message); }
}
