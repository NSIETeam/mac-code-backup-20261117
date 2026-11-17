package cn.siyue.core;

import cn.siyue.core.CrmDtos.Lead;
import cn.siyue.core.CrmDtos.LeadEvent;
import cn.siyue.core.CrmDtos.LeadFollowupInput;
import cn.siyue.core.CrmDtos.LeadInput;
import cn.siyue.core.CrmDtos.LeadOwner;
import cn.siyue.core.CrmDtos.LeadPage;
import cn.siyue.core.CrmDtos.LeadQuery;
import cn.siyue.core.CrmDtos.LeadRelease;
import cn.siyue.core.CrmDtos.LeadStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Coordinates permission-checked lead creation, assignment, release and qualification.
 * @see LeadRepository
 * @see MutationRepository
 */
@Service
public class LeadService {
    private static final Set<String> STATUSES = Set.of("new", "contacting", "qualified", "no_need",
        "unreachable", "invalid_contact", "archived");
    private final AccessRepository access;
    private final LeadRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects persistence, principal locking, retry serialization and JSON handling.
     * @param access active-principal locking repository
     * @param repository scoped lead persistence and authorization repository
     * @param mutations idempotency and audit persistence repository
     * @param mapper JSON serializer used to bind retry bodies
     */
    public LeadService(AccessRepository access, LeadRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
    }
    /** Creates a sourced lead under the current actor after rechecking linked-record scope.
     * @param actor authenticated actor requesting creation
     * @param key stable idempotency key for this request
     * @param input validated lead fields and optional linked source records
     * @return created lead or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, linked-record scope, validation, or retry checks fail
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Lead create(Actor actor, String key, LeadInput input) throws JsonProcessingException {
        actor.requireAny("crm.lead.write", "lead.create");
        if (!actor.permissions().contains("lead.create")) { actor.require("crm.lead.follow"); }
        validateFollowChoice(actor, input.followChoice());
        if (input.activityId() != null && !input.activityId().isBlank()) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        if (input.studentId() != null && !input.studentId().isBlank()) { actor.require("student.read"); }
        requireKey(key);
        validateFollowAt(input.nextFollowAt());
        repository.lockCreateIdentity(actor, input);
        actor = active(actor, "crm.lead.write", "lead.create");
        if (!actor.permissions().contains("lead.create")) { actor.require("crm.lead.follow"); }
        validateFollowChoice(actor, input.followChoice());
        if (input.activityId() != null && !input.activityId().isBlank()) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        if (input.studentId() != null && !input.studentId().isBlank()) { actor.require("student.read"); }
        repository.authorizeCreate(actor, input);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        Lead replay = replay(actor, key, "crm.lead.create", hash, Lead.class);
        if (replay != null) { return replay; }
        Lead lead = repository.create(actor, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.create", hash,
            mapper.writeValueAsString(lead), lead.id()));
        return lead;
    }
    /** Lists leads with aligned filters, totals and bounded pagination.
     * @param actor authenticated actor whose data scope is applied
     * @param query filters, optional campus/owner constraints, and requested page
     * @return a scoped lead page with totals
     * @throws BusinessException if neither read grant is present, the additional CRM grants are missing, or filters are invalid
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeadPage list(Actor actor, LeadQuery query) {
        return list(actor, query, null);
    }
    /** Lists leads using independent read scope and validated ordinary or imported saved-view filters.
     * @param actor caller with modern lead-read or an exact legacy lead read capability
     * @param query optional state, customer, activity, owner, source and follow-up filters with pagination
     * @param savedView optional leads saved-view criteria authorized for this actor
     * @return bounded lead rows and the total from their matching visibility predicate
     * @throws BusinessException if permissions, filter combinations, saved-view resource or pagination fail
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeadPage list(Actor actor, LeadQuery query, LegacySavedViewQuery savedView) {
        CrmLegacyAccess.read(actor, "crm.lead.read");
        if (!CrmLegacyAccess.legacyRead(actor, "crm.lead.read")) { actor.require("crm.customer.read"); }
        if (query.activityId() != null && !query.activityId().isBlank()) { CrmLegacyAccess.read(actor, "crm.activity.read"); }
        int page = query.page() == null ? 1 : query.page();
        int size = query.size() == null ? 50 : query.size();
        validateListQuery(query, page, size);
        if (savedView != null) { savedView.authorize(actor, "leads"); }
        return repository.list(actor, new LeadQuery(query.status(), query.customerId(), query.activityId(), page, size,
            query.campusId(), query.ownerId(), query.source(), query.subject(), query.unassignedOnly(), query.activeOnly(),
            query.nextFollowFrom(), query.nextFollowUntil()), savedView);
    }
    private void validateListQuery(LeadQuery query, int page, int size) {
        boolean invalidStatus = present(query.status()) && !STATUSES.contains(query.status());
        boolean invalidOwnerPool = present(query.ownerId()) && Boolean.TRUE.equals(query.unassignedOnly());
        boolean invalidFollowRange = query.nextFollowFrom() != null && query.nextFollowUntil() != null
            && query.nextFollowFrom().isAfter(query.nextFollowUntil());
        if (page < 1 || page > 100000 || size < 1 || size > 100 || invalidStatus
                || invalidOwnerPool || invalidFollowRange) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "线索筛选或分页范围无效");
        }
    }
    private boolean present(String value) { return value != null && !value.isBlank(); }
    /** Reassigns one lead to an eligible staff member and records the reason and previous owner.
     * @param actor authenticated actor requesting assignment
     * @param leadId public identifier of the lead
     * @param key stable idempotency key for this request
     * @param input destination owner and assignment reason
     * @return updated lead or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Lead assign(Actor actor, String leadId, String key, LeadOwner input) throws JsonProcessingException {
        actor.requireAny("crm.lead.assign", "lead.assign");
        requireKey(key);
        actor = active(actor, "crm.lead.assign", "lead.assign");
        if(actor.permissions().contains("lead.assign"))repository.authorizeLegacyLead(actor,leadId);
        else repository.authorizeLead(actor, leadId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(leadId, input)));
        Lead replay = replay(actor, key, "crm.lead.assign", hash, Lead.class);
        if (replay != null) { return replay; }
        Lead lead = repository.assign(actor, leadId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.assign", hash,
            mapper.writeValueAsString(lead), lead.id()));
        return lead;
    }
    /** Transfers one scoped lead under its distinct legacy action grant.
     * @param actor authenticated actor requesting transfer
     * @param leadId public identifier of the lead
     * @param key stable idempotency key for this request
     * @param input destination owner and transfer reason
     * @return updated lead or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Lead transfer(Actor actor, String leadId, String key, LeadOwner input) throws JsonProcessingException {
        actor.requireAny("crm.lead.assign", "lead.transfer");
        requireKey(key);
        actor = active(actor, "crm.lead.assign", "lead.transfer");
        if(actor.permissions().contains("lead.transfer"))repository.authorizeLegacyLead(actor,leadId);
        else repository.authorizeLead(actor, leadId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(leadId, input)));
        Lead replay = replay(actor, key, "crm.lead.transfer", hash, Lead.class);
        if (replay != null) { return replay; }
        Lead lead = repository.transfer(actor, leadId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.transfer", hash,
            mapper.writeValueAsString(lead), lead.id()));
        return lead;
    }
    /** Releases one visible lead into the public pool with an auditable reason.
     * @param actor authenticated actor requesting release
     * @param leadId public identifier of the lead
     * @param key stable idempotency key for this request
     * @param input release reason
     * @return updated lead or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Lead release(Actor actor, String leadId, String key, LeadRelease input) throws JsonProcessingException {
        actor.requireAny("crm.lead.assign", "lead.release");
        requireKey(key);
        actor = active(actor, "crm.lead.assign", "lead.release");
        if(actor.permissions().contains("lead.release"))repository.authorizeLegacyLead(actor,leadId);
        else repository.authorizeLead(actor, leadId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(leadId, input)));
        Lead replay = replay(actor, key, "crm.lead.release", hash, Lead.class);
        if (replay != null) { return replay; }
        Lead lead = repository.release(actor, leadId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.release", hash,
            mapper.writeValueAsString(lead), lead.id()));
        return lead;
    }
    /** Reads the original sales stage using the lead resource's exact read alternatives.
     * @param actor authenticated caller
     * @param leadId scoped public lead identifier
     * @return sales stage and current version
     * @throws BusinessException if permission or data scope rejects the read
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public CrmDtos.LeadSalesStage salesStage(Actor actor,String leadId) {
        CrmLegacyAccess.read(actor,"crm.lead.read");return repository.salesStage(actor,leadId);
    }
    /** Updates the legacy sales stage under a fresh principal and lead lock.
     * @param actor authenticated caller with lead.follow
     * @param leadId scoped public lead identifier
     * @param key stable idempotency key retained for retries
     * @param input exact stage, optional reason and expected version
     * @return the updated stage or the original receipt for an identical authorized retry
     * @throws BusinessException if permission, scope, state, version, input or retry checks fail
     * @throws JsonProcessingException if the request/event/receipt cannot be serialized
     */
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public CrmDtos.LeadSalesStage changeSalesStage(Actor actor,String leadId,String key,CrmDtos.LeadSalesStageInput input)
            throws JsonProcessingException {
        actor.require("lead.follow");requireKey(key);
        if(input==null||input.stage()==null||!Set.of("待报名","已流失","跟进中").contains(input.stage())||input.expectedVersion()<1
                ||input.reason()!=null&&input.reason().length()>1000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"销售状态、原因或版本无效");
        }
        actor=active(actor,"lead.follow");repository.authorizeSalesStage(actor,leadId);
        String hash=mutations.hash(mapper.writeValueAsString(List.of(leadId,input)));
        var previous=replay(actor,key,"lead.status.update",hash,CrmDtos.LeadSalesStage.class);
        if(previous!=null)return previous;
        var result=repository.changeSalesStage(actor,leadId,input);
        complete(actor,new MutationRepository.RetryWrite(key,"lead.status.update",hash,mapper.writeValueAsString(result),leadId));
        return result;
    }
    /** Records screening progress and its next action without treating qualification as a sale.
     * @param actor authenticated actor requesting the status transition
     * @param leadId public identifier of the lead
     * @param key stable idempotency key for this request
     * @param input new screening status, reason, and optional next follow-up time
     * @return updated lead or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, transition, scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Lead changeStatus(Actor actor, String leadId, String key, LeadStatus input) throws JsonProcessingException {
        actor.require("crm.lead.write"); CrmLegacyAccess.read(actor, "crm.customer.read");
        requireKey(key);
        validateFollowAt(input.nextFollowAt());
        actor = active(actor, "crm.lead.write"); CrmLegacyAccess.read(actor, "crm.customer.read");
        if (Set.of("qualified", "no_need", "unreachable", "invalid_contact", "archived")
                .contains(input.status()) && (input.reason() == null || input.reason().isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "初筛结果或归档状态需要填写处理说明");
        }
        repository.authorizeLead(actor, leadId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(leadId, input)));
        Lead replay = replay(actor, key, "crm.lead.status", hash, Lead.class);
        if (replay != null) { return replay; }
        Lead lead = repository.changeStatus(actor, leadId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.status", hash,
            mapper.writeValueAsString(lead), lead.id()));
        return lead;
    }
    /** Appends one encrypted contact fact and future action without invoking a channel provider.
     * @param actor authenticated actor recording the follow-up
     * @param leadId public identifier of the lead
     * @param key stable idempotency key for this request
     * @param input contact channel, note, outcome, and optional next follow-up time
     * @return updated lead or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if authorization, scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor = Exception.class)
    public Lead followup(Actor actor, String leadId, String key, LeadFollowupInput input)
            throws JsonProcessingException {
        actor.requireAny("crm.lead.write", "lead.follow");
        requireKey(key);
        if (input.summary() == null || input.summary().isBlank() || input.summary().length() > 2000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请填写有效的跟进内容或流失原因");
        }
        if (!"lost_cold".equals(input.disposition())) { validateFollowAt(input.nextFollowAt()); }
        actor = active(actor, "crm.lead.write", "lead.follow");
        if (input.disposition() != null) {
            actor.requireAny("lead.follow", "crm.lead.follow");
            if (!Set.of("high", "medium", "low", "lost_cold").contains(input.disposition())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "跟进结果无效");
            }
        }
        if(actor.permissions().contains("lead.follow"))repository.authorizeLegacyLead(actor,leadId);
        else repository.authorizeLead(actor, leadId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(leadId, input)));
        Lead replay = replay(actor, key, "crm.lead.followup", hash, Lead.class);
        if (replay != null) { return replay; }
        Lead lead = repository.followup(actor, leadId, input);
        complete(actor, new MutationRepository.RetryWrite(key, "crm.lead.followup", hash,
            mapper.writeValueAsString(lead), lead.id()));
        return lead;
    }
    /** Reads append-only lead assignment, screening and status events.
     * @param actor authenticated actor whose lead-read scope is applied
     * @param leadId public identifier of the lead
     * @return ordered event history visible to the actor
     * @throws BusinessException if the actor lacks lead-read permission or cannot access the lead
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeadEvent> events(Actor actor, String leadId) {
        CrmLegacyAccess.read(actor, "crm.lead.read");
        return repository.events(actor, leadId);
    }
    private void validateFollowChoice(Actor actor, String choice) {
        if (choice == null) { return; }
        if (!Set.of("self", "public").contains(choice)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "登记后跟进选择无效");
        }
        if ("self".equals(choice)) { actor.requireAny("lead.follow", "crm.lead.follow"); }
    }
    private Actor active(Actor actor, String... permissions) {
        Actor locked = access.lockActive(actor.id());
        locked.requireAny(permissions);
        return locked;
    }
    private <T> T replay(Actor actor, String key, String action, String hash, Class<T> type)
            throws JsonProcessingException {
        String stored = mutations.replay(actor, key, action, hash);
        return stored == null ? null : mapper.readValue(stored, type);
    }
    private void complete(Actor actor, MutationRepository.RetryWrite write) {
        mutations.complete(actor, write);
    }
    private static void validateFollowAt(Instant value) {
        if (value != null && !value.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "下次跟进时间须晚于当前时间");
        }
    }
    private static void requireKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 100 || key.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Idempotency-Key 须为 16 到 100 个字符");
        }
    }
}
