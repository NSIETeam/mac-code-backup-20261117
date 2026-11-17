package cn.siyue.core;

import cn.siyue.core.CrmDtos.ServiceTicket;
import cn.siyue.core.CrmDtos.ServiceTicketClose;
import cn.siyue.core.CrmDtos.ServiceTicketEvent;
import cn.siyue.core.CrmDtos.ServiceTicketFollowup;
import cn.siyue.core.CrmDtos.ServiceTicketInput;
import cn.siyue.core.CrmDtos.ServiceTicketOwner;
import cn.siyue.core.CrmDtos.ServiceTicketPage;
import cn.siyue.core.CrmDtos.ServiceTicketRefundLink;
import cn.siyue.core.CrmDtos.ServiceTicketType;
import cn.siyue.core.CrmDtos.ServiceTicketTypeInput;
import cn.siyue.core.CrmDtos.ServiceTicketTypeUpdate;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.RegistryState;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.ReplaceInput;
import cn.siyue.core.ServiceTicketTypeRegistryDtos.TypeValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies explicit permissions, current-scope rechecks, transaction and idempotency to service tickets. */
@Service
public class CrmServiceTicketService {
    private final AccessRepository access;
    private final CrmServiceTicketRepository tickets;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /**
     * Wires the scoped access, persistence, idempotency and JSON collaborators.
     * @param access actor resolution and active-principal access repository.
     * @param tickets ticket persistence repository.
     * @param mutations idempotency and mutation-audit repository.
     * @param mapper JSON serializer used for request fingerprints and stored replay responses.
     */
    public CrmServiceTicketService(AccessRepository access, CrmServiceTicketRepository tickets,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.tickets = tickets;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /**
     * Returns active global and applicable campus ticket types.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param campusId optional campus filter.
     * @return The visible active ticket types for the requested scope.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<ServiceTicketType> types(Actor actor, String campusId) {
        return types(actor, campusId, false);
    }

    /**
     * Returns ticket types in scope; inactive entries require type-management permission.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param campusId optional campus filter.
     * @param includeInactive include inactive values when authorized.
     * @return The visible active ticket types for the requested scope.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<ServiceTicketType> types(Actor actor, String campusId, boolean includeInactive) {
        CrmLegacyAccess.read(actor, "crm.ticket.read");
        if (includeInactive) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.type.manage"); }
        return tickets.types(actor, campusId, includeInactive);
    }

    /**
     * Creates a global or campus ticket type under the serialized registry version and idempotency key.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The created or updated type, or its stored idempotent result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicketType createType(Actor actor, String key, ServiceTicketTypeInput input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.type.manage");
        requireKey(key);
        requireType(input);
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.type.manage");
        long registryVersion = tickets.lockTypeRegistry();
        if ((input.campusId() == null || input.campusId().isBlank()) && !actor.scope().equals("all")) {
            invalid("全局工单类型只能由全部范围账号创建");
        }
        tickets.authorizeTypeInput(actor, input);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.ticket.type.create", hash);
        if (replay != null) { return mapper.readValue(replay, ServiceTicketType.class); }
        ServiceTicketType result = tickets.createType(actor, input);
        tickets.bumpTypeRegistryVersion(registryVersion);
        completeType(actor, key, "crm.ticket.type.create", hash, result);
        return result;
    }

    /**
     * Updates a ticket type name or active state using its expected version and an idempotency key.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param typeId ticket-type public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The created or updated type, or its stored idempotent result.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicketType updateType(Actor actor, String typeId, String key, ServiceTicketTypeUpdate input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.type.manage");
        requireKey(key);
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 100
                || input.expectedVersion() < 1) { invalid("工单类型名称或版本无效"); }
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.type.manage");
        long registryVersion = tickets.lockTypeRegistry();
        tickets.authorizeType(actor, typeId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(typeId, input)));
        String replay = mutations.replay(actor, key, "crm.ticket.type.update", hash);
        if (replay != null) { return mapper.readValue(replay, ServiceTicketType.class); }
        ServiceTicketType result = tickets.updateType(actor, typeId, input);
        tickets.bumpTypeRegistryVersion(registryVersion);
        completeType(actor, key, "crm.ticket.type.update", hash, result);
        return result;
    }

    /**
     * Reads the global type registry only for an all-scope type administrator.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @return The global type values and current registry version.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public RegistryState typeRegistry(Actor actor) {
        requireGlobalTypeAdmin(actor);
        return tickets.typeRegistry();
    }

    /**
     * Replaces the global type catalog atomically, preserving omitted historical types as inactive.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The global type values and current registry version.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public RegistryState replaceTypeRegistry(Actor actor, String key, ReplaceInput input)
            throws JsonProcessingException {
        requireGlobalTypeAdmin(actor);
        requireKey(key);
        requireReplacement(input);
        actor = access.lockActive(actor.id());
        requireGlobalTypeAdmin(actor);
        long registryVersion = tickets.lockTypeRegistry();
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "serviceTicket.types.update", hash);
        if (replay != null) { return mapper.readValue(replay, RegistryState.class); }
        RegistryState result = tickets.replaceTypeRegistry(input, registryVersion);
        completeTypeRegistry(actor, key, hash, result);
        return result;
    }

    /**
     * Creates a customer ticket after rechecking customer, enrollment, class and owner scopes.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket create(Actor actor, String key, ServiceTicketInput input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        CrmLegacyAccess.read(actor, "crm.customer.read");
        requireKey(key);
        requireTicket(input);
        requireLinkedPermissions(actor, input);
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        CrmLegacyAccess.read(actor, "crm.customer.read");
        requireLinkedPermissions(actor, input);
        tickets.authorizeCreate(actor, input);
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "crm.ticket.create", hash);
        if (replay != null) { return mapper.readValue(replay, ServiceTicket.class); }
        ServiceTicket result = tickets.create(actor, input);
        complete(actor, key, "crm.ticket.create", hash, result);
        return result;
    }

    /**
     * Returns a bounded page of tickets visible to the current owner or authorized campus.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param status optional ticket status filter.
     * @param page 1-based page number.
     * @param size maximum number of records in the page.
     * @return A bounded page of tickets visible to the actor.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ServiceTicketPage list(Actor actor, String status, int page, int size) {
        return list(actor, status, page, size, null);
    }
    /** Lists tickets after checking the exact ticket-read alternatives and saved-view resource.
     * @param actor caller with modern ticket-read or an accepted legacy CRM read grant
     * @param status optional open, processing or closed status
     * @param page one-based page from 1 through 100000
     * @param size page size from 1 through 100
     * @param savedView optional service-ticket saved-view filters
     * @return visible tickets and their matching total; read relationships do not authorize treatment
     * @throws BusinessException if read permission, saved-view authorization, status or pagination fails
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ServiceTicketPage list(Actor actor, String status, int page, int size,
            LegacySavedViewQuery savedView) {
        CrmLegacyAccess.read(actor, "crm.ticket.read");
        if (status != null && !status.isBlank()
                && !Set.of("open", "processing", "closed").contains(status)
                || page < 1 || page > 100000 || size < 1 || size > 100) {
            invalid("工单状态或分页范围无效");
        }
        if (savedView != null) { savedView.authorize(actor, "serviceTickets"); }
        return tickets.list(actor, status, page, size, savedView);
    }

    /**
     * Transfers a live ticket to an eligible employee and records the supplied reason.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket assign(Actor actor, String ticketId, String key, ServiceTicketOwner input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.assign");
        requireKey(key);
        if (input == null || input.ownerId() == null || input.ownerId().isBlank()
                || input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000
                || input.expectedVersion() < 1) { invalid("工单负责人、原因或版本无效"); }
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.assign");
        tickets.authorize(actor, ticketId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(ticketId, input)));
        String replay = mutations.replay(actor, key, "crm.ticket.assign", hash);
        if (replay != null) { return mapper.readValue(replay, ServiceTicket.class); }
        ServiceTicket result = tickets.assign(actor, ticketId, input);
        complete(actor, key, "crm.ticket.assign", hash, result);
        return result;
    }

    /**
     * Records a treatment step and optional future callback for the current ticket owner.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket followup(Actor actor, String ticketId, String key, ServiceTicketFollowup input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        requireKey(key);
        if (input == null || input.detail() == null || input.detail().isBlank()
                || input.detail().length() > 5000 || input.expectedVersion() < 1) { invalid("处理内容或版本无效"); }
        validateFuture(input.nextFollowAt());
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        tickets.authorizeOwner(actor, ticketId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(ticketId, input)));
        String replay = mutations.replay(actor, key, "crm.ticket.followup", hash);
        if (replay != null) { return mapper.readValue(replay, ServiceTicket.class); }
        ServiceTicket result = tickets.followup(actor, ticketId, input);
        complete(actor, key, "crm.ticket.followup", hash, result);
        return result;
    }

    /**
     * Closes a ticket for its current owner and snapshots the linked enrollment result.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket close(Actor actor, String ticketId, String key, ServiceTicketClose input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        requireKey(key);
        if (input == null || input.resolution() == null || input.resolution().isBlank()
                || input.resolution().length() > 5000 || input.expectedVersion() < 1) { invalid("办结说明或版本无效"); }
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        tickets.authorizeOwner(actor, ticketId);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(ticketId, input)));
        String replay = mutations.replay(actor, key, "crm.ticket.close", hash);
        if (replay != null) { return mapper.readValue(replay, ServiceTicket.class); }
        ServiceTicket result = tickets.close(actor, ticketId, input);
        complete(actor, key, "crm.ticket.close", hash, result);
        return result;
    }

    /**
     * Links a matching in-scope refund request to a current-owner ticket.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param ticketId ticket public identifier.
     * @param key Idempotency-Key header for the mutation.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws JsonProcessingException if request or replay JSON cannot be serialized or parsed.
     * @throws BusinessException if permission, scope, input, state, version or idempotency checks fail.
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket linkRefund(Actor actor, String ticketId, String key, ServiceTicketRefundLink input)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        actor.require("crm.refund.read");
        actor.require("crm.order.read");
        requireKey(key);
        if (input == null || input.refundRequestId() == null || input.refundRequestId().isBlank()
                || input.refundRequestId().length() > 120 || input.expectedVersion() < 1) {
            invalid("退款申请编号或工单版本无效");
        }
        actor = access.lockActive(actor.id());
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.ticket.write");
        actor.require("crm.refund.read");
        actor.require("crm.order.read");
        tickets.authorizeRefundLink(actor, ticketId, input);
        String hash = mutations.hash(mapper.writeValueAsString(List.of(ticketId, input)));
        String replay = mutations.replay(actor, key, "crm.ticket.refund.link", hash);
        if (replay != null) { return mapper.readValue(replay, ServiceTicket.class); }
        ServiceTicket result = tickets.linkRefund(actor, ticketId, input);
        complete(actor, key, "crm.ticket.refund.link", hash, result);
        return result;
    }

    /**
     * Returns the latest append-only ticket history after current ticket scope validation.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param ticketId ticket public identifier.
     * @return The latest visible ticket events, in repository order.
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<ServiceTicketEvent> events(Actor actor, String ticketId) {
        CrmLegacyAccess.read(actor, "crm.ticket.read");
        return tickets.events(actor, ticketId);
    }

    private void requireLinkedPermissions(Actor actor, ServiceTicketInput input) {
        if (input.enrollmentId() != null && !input.enrollmentId().isBlank()) {
            actor.require("enrollment.manage");
            actor.require("class.read");
        }
        if (input.classId() != null && !input.classId().isBlank()) { actor.require("class.read"); }
        if (input.ownerId() != null && !input.ownerId().isBlank()
                && !input.ownerId().equals(actor.publicId())) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.assign"); }
    }
    private void requireGlobalTypeAdmin(Actor actor) {
        actor.require("lead.assign");
        if (!actor.scope().equals("all")) { throw new BusinessException(ErrorCode.FORBIDDEN, "工单类型全局配置需要全部数据范围"); }
    }
    private static void requireReplacement(ReplaceInput input) {
        if (input == null || input.types() == null || input.types().isEmpty() || input.types().size() > 30
                || input.expectedVersion() < 1) { invalid("工单类型列表或目录版本无效"); }
        Set<String> ids = new HashSet<>();
        for (TypeValue type : input.types()) {
            if (type == null || type.id() == null || !type.id().matches("[a-z][a-z0-9_]{1,39}")
                    || !ids.add(type.id()) || type.name() == null || type.name().isBlank()
                    || type.name().length() > 100 || type.active() == null) {
                invalid("工单类型编号重复、名称无效或状态缺失");
            }
        }
    }
    private void completeTypeRegistry(Actor actor, String key, String hash, RegistryState result)
            throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "serviceTicket.types.update", hash,
            mapper.writeValueAsString(result), "service-ticket-types:global"));
    }
    private void completeType(Actor actor, String key, String action, String hash, ServiceTicketType result)
            throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(result), result.id()));
    }
    private void complete(Actor actor, String key, String action, String hash, ServiceTicket result)
            throws JsonProcessingException {
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(result), result.id()));
    }
    private static void requireType(ServiceTicketTypeInput input) {
        if (input == null || input.typeCode() == null || !input.typeCode().matches("[a-z][a-z0-9_]{1,39}")
                || input.name() == null || input.name().isBlank() || input.name().length() > 100) {
            invalid("工单类型编号或名称无效");
        }
    }
    private static void requireTicket(ServiceTicketInput input) {
        if (input == null || input.customerId() == null || input.customerId().isBlank()
                || input.typeCode() == null || input.title() == null || input.title().isBlank()
                || input.title().length() > 200 || input.description() == null || input.description().isBlank()
                || input.description().length() > 5000 || input.dueAt() != null && input.dueAt().isBefore(Instant.now())) {
            invalid("客户、工单类型、标题、描述或截止时间无效");
        }
    }
    private static void validateFuture(Instant value) {
        if (value != null && !value.isAfter(Instant.now())) { invalid("下次回访时间须晚于当前时间"); }
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) { invalid("Idempotency-Key 格式无效"); }
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
