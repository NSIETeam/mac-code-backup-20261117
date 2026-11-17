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
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists configurable service tickets and an append-only treatment timeline. */
@Repository
public class CrmServiceTicketRepository {
    /** Persisted ticket type, campus scope and optimistic version used to validate ticket creation and registry changes. */
    private record TypeRow(long id, String publicId, String campusId, String code,
            String name, boolean active, long version) { }
    /** Scoped ticket projection retaining encrypted text until the response mapper decrypts its permitted fields. */
    private record TicketRow(long id, String publicId, String customerId, String campusId,
            long typeId, String ownerId, String createdBy, String enrollmentId, String classId,
            String refundRequestId, String refundStatus, BigDecimal refundAmount, Timestamp refundAt,
            String titleCipher, String descriptionCipher,
            String status, Timestamp dueAt,
            Timestamp nextFollowAt, String resolutionCipher, String actualResultCipher,
            Timestamp completedAt, long version) { }
    /** Validated internal customer, campus, type, owner and optional academic links for one ticket insertion. */
    private record TicketDraft(long customerId, Long campusId, TypeRow type,
            Long enrollmentId, Long classId, long ownerId) { }
    /** Previous and resulting ownership/status facts stored together in a ticket event. */
    private record TicketTransition(Long fromOwner,Long toOwner,String fromStatus,String toStatus) { }
    /** Human treatment text and optional academic outcome serialized into the encrypted event payload. */
    private record TicketEventDetail(String detail,String actualResult) { }
    /** Single append-only ticket event write with its actor, transition and descriptive payload. */
    private record TicketEventWrite(long ticketId,Actor actor,String type,
            TicketTransition transition,TicketEventDetail detail) { }
    /** Refund and order identity used to verify that a ticket may reference the selected refund. */
    private record RefundLinkRow(long id, String publicId, long orderId,
            long customerId, Long campusId, Long enrollmentOrderId) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    /** Wires JDBC persistence and PII encryption for service-ticket records.
     * @param jdbc required dependency.
     * @param cipher PII encryption service for ticket text.
     */
    public CrmServiceTicketRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Reads active global and campus-applicable types for the actor; campus-scoped actors must specify a campus.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param campusPublicId optional campus public identifier.
     * @return The visible active ticket types for the requested scope.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public List<ServiceTicketType> types(Actor actor, String campusPublicId) {
        return types(actor, campusPublicId, false);
    }
    /** Reads types for the actor and optional campus, optionally including inactive rows for authorized callers.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param campusPublicId optional campus public identifier.
     * @param includeInactive include inactive values when authorized.
     * @return The visible active ticket types for the requested scope.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public List<ServiceTicketType> types(Actor actor, String campusPublicId, boolean includeInactive) {
        Long campusId = campusPublicId == null || campusPublicId.isBlank()
            ? null : campus(actor, campusPublicId);
        if (campusId == null && !actor.scope().equals("all")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "校区范围账号查询工单类型时须指定校区");
        }
        List<TypeRow> rows = campusId == null
            ? jdbc.query("SELECT t.id,t.public_id,cp.public_id campus_public_id,t.type_code,t.name,t.active,t.version "
                + "FROM core_crm_service_ticket_type t LEFT JOIN core_campus cp ON cp.id=t.campus_id "
                + "WHERE t.deleted=0 " + (includeInactive ? "" : "AND t.active=1 ")
                + "ORDER BY t.campus_id,t.type_code,t.id", this::typeRow)
            : jdbc.query("SELECT t.id,t.public_id,cp.public_id campus_public_id,t.type_code,t.name,t.active,t.version "
                + "FROM core_crm_service_ticket_type t LEFT JOIN core_campus cp ON cp.id=t.campus_id "
                + "WHERE t.deleted=0 " + (includeInactive ? "" : "AND t.active=1 ")
                + "AND (t.campus_id IS NULL OR t.campus_id=?) "
                + "ORDER BY (t.campus_id IS NOT NULL),t.type_code,t.id", this::typeRow, campusId);
        return rows.stream().map(this::typeDto).toList();
    }

    /** Inserts a type for the validated campus or global catalog and rejects a duplicate code in that catalog.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param input validated request payload.
     * @return The created or updated type, or its stored idempotent result.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public ServiceTicketType createType(Actor actor, ServiceTicketTypeInput input) {
        Long campusId = typeCampus(actor, input);
        Integer duplicate = jdbc.queryForObject("""
            SELECT COUNT(t.id) FROM core_crm_service_ticket_type t
            WHERE t.deleted=0 AND t.type_code=? AND ((t.campus_id IS NULL AND ? IS NULL) OR t.campus_id=?)
            """, Integer.class, input.typeCode(), campusId, campusId);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该校区已有相同工单类型编号");
        }
        String publicId = "TICKET_TYPE_" + UUID.randomUUID();
        jdbc.update("INSERT INTO core_crm_service_ticket_type (public_id,campus_id,type_code,name,active) VALUES (?,?,?,?,?)",
            publicId, campusId, input.typeCode(), input.name().trim(), input.active());
        return typeDto(type(actor, publicId, false));
    }

    /** Rechecks the requested type campus before an idempotent create replay.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param input validated request payload.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public void authorizeTypeInput(Actor actor, ServiceTicketTypeInput input) { typeCampus(actor, input); }

    /** Rechecks type visibility and prevents campus-scoped actors from changing a global type.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param typePublicId ticket-type public identifier.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public void authorizeType(Actor actor, String typePublicId) {
        TypeRow type = type(actor, typePublicId, false);
        if (type.campusId() == null && !actor.scope().equals("all")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "全局工单类型只能由全部范围账号修改");
        }
    }

    /** Changes a type label or active flag only at the expected row version.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param typePublicId ticket-type public identifier.
     * @param input validated request payload.
     * @return The created or updated type, or its stored idempotent result.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public ServiceTicketType updateType(Actor actor, String typePublicId, ServiceTicketTypeUpdate input) {
        TypeRow before = type(actor, typePublicId, true);
        if (before.version() != input.expectedVersion()) { throw conflict("工单类型版本已变化"); }
        int changed = jdbc.update("""
            UPDATE core_crm_service_ticket_type SET name=?,active=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, input.name().trim(), input.active(), before.id(), input.expectedVersion());
        if (changed != 1) { throw conflict("工单类型版本已变化"); }
        return typeDto(type(actor, typePublicId, false));
    }

    /** Locks the singleton global registry row and returns its current version.
     * @return The locked or current version used by the caller to serialize the next mutation.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public long lockTypeRegistry() {
        Long version = jdbc.queryForObject("SELECT version FROM core_crm_service_ticket_type_registry "
            + "WHERE registry_key='global' AND deleted=0 FOR UPDATE", Long.class);
        if (version == null) { throw new BusinessException(ErrorCode.CONFIGURATION_UNAVAILABLE, "工单类型目录不可用"); }
        return version;
    }

    /** Advances the global registry version only when it still equals the expected version.
     * @param expectedVersion version observed by the caller before the mutation.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public void bumpTypeRegistryVersion(long expectedVersion) {
        int changed = jdbc.update("UPDATE core_crm_service_ticket_type_registry SET version=version+1, "
            + "updated_at=UTC_TIMESTAMP(3) WHERE registry_key='global' AND version=? AND deleted=0", expectedVersion);
        if (changed != 1) { throw registryConflict("工单类型目录版本已变化"); }
    }

    /** Reads the global registry version and current global type values.
     * @return The global type values and current registry version.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public RegistryState typeRegistry() {
        long version = jdbc.queryForObject("SELECT version FROM core_crm_service_ticket_type_registry "
            + "WHERE registry_key='global' AND deleted=0", Long.class);
        List<TypeValue> values = jdbc.query("SELECT type_code,name,active FROM core_crm_service_ticket_type "
            + "WHERE campus_id IS NULL AND deleted=0 ORDER BY type_code,id",
            (rs, row) -> new TypeValue(rs.getString("type_code"), rs.getString("name"), rs.getBoolean("active")));
        return new RegistryState(values, version);
    }

    /** Replaces global types at the locked version; omitted types are deactivated and never deleted.
     * @param input validated request payload.
     * @param lockedVersion registry version obtained while holding its row lock.
     * @return The global type values and current registry version.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public RegistryState replaceTypeRegistry(ReplaceInput input, long lockedVersion) {
        if (lockedVersion != input.expectedVersion()) { throw registryConflict("工单类型目录版本已变化"); }
        Map<String, TypeRow> existing = new HashMap<>();
        List<TypeRow> rows = jdbc.query("SELECT t.id,t.public_id,NULL campus_public_id,t.type_code,t.name,t.active,t.version "
            + "FROM core_crm_service_ticket_type t WHERE t.campus_id IS NULL AND t.deleted=0 ORDER BY t.id FOR UPDATE",
            this::typeRow);
        rows.forEach(row -> existing.put(row.code(), row));
        java.util.Set<String> requested = input.types().stream().map(TypeValue::id).collect(java.util.stream.Collectors.toSet());
        rejectCampusOverrides(rows, requested);
        deactivateRemoved(rows, requested);
        upsertRequested(input.types(), existing);
        int changed = jdbc.update("UPDATE core_crm_service_ticket_type_registry SET version=version+1, "
            + "updated_at=UTC_TIMESTAMP(3) WHERE registry_key='global' AND version=? AND deleted=0", lockedVersion);
        if (changed != 1) { throw registryConflict("工单类型目录版本已变化"); }
        return typeRegistry();
    }

    private void rejectCampusOverrides(List<TypeRow> rows, java.util.Set<String> requested) {
        List<String> omitted = rows.stream().filter(row -> !requested.contains(row.code()))
            .map(TypeRow::code).distinct().toList();
        if (omitted.isEmpty()) { return; }
        String placeholders = String.join(",", java.util.Collections.nCopies(omitted.size(), "?"));
        List<String> conflicts = jdbc.queryForList("SELECT DISTINCT type_code FROM core_crm_service_ticket_type "
            + "WHERE campus_id IS NOT NULL AND active=1 AND deleted=0 AND type_code IN (" + placeholders + ") "
            + "ORDER BY type_code", String.class, omitted.toArray());
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                "移除的全局类型仍有校区自定义项，请先处理校区覆盖：" + String.join(",", conflicts));
        }
    }

    private void deactivateRemoved(List<TypeRow> rows, java.util.Set<String> requested) {
        for (TypeRow row : rows) {
            if (!requested.contains(row.code()) && row.active()) {
                int changed = jdbc.update("UPDATE core_crm_service_ticket_type SET active=0,version=version+1, "
                    + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND deleted=0", row.id(), row.version());
                if (changed != 1) { throw registryConflict("工单类型目录版本已变化"); }
            }
        }
    }

    private void upsertRequested(List<TypeValue> requested, Map<String, TypeRow> existing) {
        for (TypeValue value : requested) {
            TypeRow row = existing.get(value.id());
            if (row == null) { insertGlobalType(value); }
            else { updateGlobalType(value, row); }
        }
    }

    private void insertGlobalType(TypeValue value) {
        jdbc.update("INSERT INTO core_crm_service_ticket_type "
            + "(public_id,campus_id,type_code,name,active) VALUES (?,NULL,?,?,?)",
            "TICKET_TYPE_" + UUID.randomUUID(), value.id(), value.name().trim(), value.active());
    }

    private void updateGlobalType(TypeValue value, TypeRow row) {
        if (row.name().equals(value.name().trim()) && row.active() == value.active()) { return; }
        int changed = jdbc.update("UPDATE core_crm_service_ticket_type SET name=?,active=?,version=version+1, "
            + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND deleted=0",
            value.name().trim(), value.active(), row.id(), row.version());
        if (changed != 1) { throw registryConflict("工单类型目录版本已变化"); }
    }

    /** Inserts a ticket with validated customer and academic links, then appends its creation event.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public ServiceTicket create(Actor actor, ServiceTicketInput input) {
        TicketDraft draft = draft(actor, input, true);
        String publicId = "TICKET_" + UUID.randomUUID();
        String title = input.title().trim();
        jdbc.update("""
            INSERT INTO core_crm_service_ticket
                (public_id,customer_id,campus_id,ticket_type_id,owner_id,created_by,enrollment_id,class_id,
                 title_cipher,description_cipher,due_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, publicId, draft.customerId(), draft.campusId(), draft.type().id(), draft.ownerId(), actor.id(),
            draft.enrollmentId(), draft.classId(), cipher.encrypt(title), cipher.encrypt(input.description().trim()),
            input.dueAt() == null ? null : Timestamp.from(input.dueAt()));
        long id = ticketId(publicId);
        SavedViewTicketSearch.save(jdbc, cipher, id, title);
        event(new TicketEventWrite(id,actor,"created",new TicketTransition(null,draft.ownerId(),null,"open"),
            new TicketEventDetail(input.description(),null)));
        return ticket(actor, publicId, false);
    }

    /** Revalidates ticket links and scope without writing, including before replaying a stored response.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param input validated request payload.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public void authorizeCreate(Actor actor, ServiceTicketInput input) { draft(actor, input, false); }

    /** Reads ordinary ticket filters through the saved-view overload with no saved view.
     * @param actor caller whose ticket read scope is applied
     * @param status optional status filter, validated by the service
     * @param page one-based page, validated by the service
     * @param size maximum rows, validated by the service
     * @return scoped ticket page and total
     * @throws BusinessException if the caller has an unsupported scope
     */
    public ServiceTicketPage list(Actor actor, String status, int page, int size) {
        return list(actor, status, page, size, null);
    }

    /** Applies ticket read scope before saved-view search and field disclosure.
     * @param actor caller whose current grants control scope and linked-field visibility
     * @param status optional ticket state filter
     * @param page validated one-based page
     * @param size validated maximum row count
     * @param savedView optional validated ticket saved view
     * @return scoped ticket page with matching count
     * @throws BusinessException if the scope is unsupported
     */
    public ServiceTicketPage list(Actor actor, String status, int page, int size,
            LegacySavedViewQuery savedView) {
        ScopeSql.Clause scope = ticketReadScope(actor);
        String filter = status == null || status.isBlank() ? "" : " AND t.status=?";
        List<Object> countArgs = new ArrayList<>(scope.args());
        if (!filter.isEmpty()) { countArgs.add(status); }
        if (savedView != null && savedView.searchPattern() != null) {
            String search = "(LOWER(CAST(t.public_id AS CHAR)) LIKE ? ESCAPE '!'"
                + " OR LOWER(t.status) LIKE ? ESCAPE '!'";
            countArgs.add(savedView.searchPattern()); countArgs.add(savedView.searchPattern());
            search += SavedViewTicketSearch.visibleTitleIds(jdbc, cipher,
                new SavedViewTicketSearch.Query(savedView.search(), countArgs, scope));
            if (CrmLegacyAccess.canRead(actor, "crm.customer.read")) {
                search += SavedViewCustomerSearch.addVisibleIds("t.customer_id",
                    SavedViewCustomerSearch.matchingIds(jdbc, cipher, actor, savedView.search()), countArgs);
            }
            filter += " AND " + search + ")";
        }
        String where = "t.deleted=0 AND (" + scope.sql() + ")" + filter;
        Long total = jdbc.queryForObject("SELECT COUNT(t.id) FROM core_crm_service_ticket t WHERE " + where,
            Long.class, countArgs.toArray());
        List<Object> pageArgs = new ArrayList<>(countArgs);
        pageArgs.add(size);
        pageArgs.add(Math.multiplyExact((long) page - 1, size));
        String order = savedView != null && savedView.present()
            ? " ORDER BY t.updated_at DESC,t.id DESC" : " ORDER BY COALESCE(t.due_at,t.created_at),t.id";
        List<TicketRow> rows = jdbc.query(ticketSelect() + " WHERE " + where
            + order + " LIMIT ? OFFSET ?", this::ticketRow, pageArgs.toArray());
        return new ServiceTicketPage(rows.stream().map(row -> ticketDto(actor, row)).toList(),
            total == null ? 0 : total, page, size);
    }

    /** Transfers an open ticket to a different eligible owner and appends the transfer event.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param publicId ticket public identifier.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public ServiceTicket assign(Actor actor, String publicId, ServiceTicketOwner input) {
        TicketRow before = ticketRow(actor, publicId, true);
        if (before.version() != input.expectedVersion()) { throw conflict("工单版本已变化"); }
        if (before.status().equals("closed")) { throw invalid("已办结工单不能转派"); }
        long nextOwner = owner(actor, input.ownerId(), internalCampus(before.campusId()));
        if (nextOwner == internalOwner(before.ownerId())) { throw invalid("新负责人必须与当前负责人不同"); }
        int changed = jdbc.update("""
            UPDATE core_crm_service_ticket SET owner_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status<>'closed' AND deleted=0
            """, nextOwner, before.id(), input.expectedVersion());
        if (changed != 1) { throw conflict("工单版本已变化"); }
        event(new TicketEventWrite(before.id(),actor,"transferred",
            new TicketTransition(internalOwner(before.ownerId()),nextOwner,before.status(),before.status()),
            new TicketEventDetail(input.reason(),null)));
        return ticket(actor, publicId, false);
    }

    /** Appends treatment to a scoped, nonclosed ticket and advances its version.
     * @param actor caller permitted to treat this ticket; service checks the operation grant
     * @param publicId ticket identifier
     * @param input treatment text, next follow-up time and expected version
     * @return updated ticket; idempotent replay is handled by the calling service
     * @throws BusinessException if scope, treatment eligibility, closed state or version rejects the write
     */
    public ServiceTicket followup(Actor actor, String publicId, ServiceTicketFollowup input) {
        TicketRow before = ticketRow(actor, publicId, true);
        requireCurrentOwner(actor, before);
        if (before.version() != input.expectedVersion()) { throw conflict("工单版本已变化"); }
        if (before.status().equals("closed")) { throw invalid("已办结工单不能继续处理"); }
        int changed = jdbc.update("""
            UPDATE core_crm_service_ticket SET status='processing',next_follow_at=?,version=version+1,
                updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND status<>'closed' AND deleted=0
            """, input.nextFollowAt() == null ? null : Timestamp.from(input.nextFollowAt()),
            before.id(), input.expectedVersion());
        if (changed != 1) { throw conflict("工单版本已变化"); }
        event(new TicketEventWrite(before.id(),actor,"followup",
            new TicketTransition(null,null,before.status(),"processing"),new TicketEventDetail(input.detail(),null)));
        return ticket(actor, publicId, false);
    }

    /** Closes a nonclosed ticket and snapshots the linked enrollment outcome in its event.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param publicId ticket public identifier.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public ServiceTicket close(Actor actor, String publicId, ServiceTicketClose input) {
        TicketRow before = ticketRow(actor, publicId, true);
        requireCurrentOwner(actor, before);
        if (before.version() != input.expectedVersion()) { throw conflict("工单版本已变化"); }
        if (before.status().equals("closed")) { throw invalid("工单已经办结"); }
        String result = enrollmentResult(before.enrollmentId());
        Instant completedAt = Instant.now();
        int changed = jdbc.update("""
            UPDATE core_crm_service_ticket SET status='closed',resolution_cipher=?,actual_result_cipher=?,
                completed_at=?,next_follow_at=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status<>'closed' AND deleted=0
            """, cipher.encrypt(input.resolution().trim()), result == null ? null : cipher.encrypt(result),
            Timestamp.from(completedAt), before.id(), input.expectedVersion());
        if (changed != 1) { throw conflict("工单版本已变化"); }
        event(new TicketEventWrite(before.id(),actor,"closed",
            new TicketTransition(null,null,before.status(),"closed"),new TicketEventDetail(input.resolution(),result)));
        return ticket(actor, publicId, false);
    }

    /** Links one matching refund request at the expected ticket version and appends a timeline event.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param publicId ticket public identifier.
     * @param input validated request payload.
     * @return The resulting ticket or stored result of an identical retry.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public ServiceTicket linkRefund(Actor actor, String publicId, ServiceTicketRefundLink input) {
        TicketRow before = ticketRow(actor, publicId, true);
        requireCurrentOwner(actor, before);
        if (before.version() != input.expectedVersion()) { throw conflict("工单版本已变化"); }
        if (before.status().equals("closed")) { throw invalid("已办结工单不能关联退款"); }
        RefundLinkRow refund = refundLink(actor, before, input.refundRequestId(), true);
        if (before.refundRequestId() != null) {
            if (before.refundRequestId().equals(refund.publicId())) { return ticket(actor, publicId, false); }
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "工单已关联其他退款申请");
        }
        Integer linked = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_crm_service_ticket
            WHERE refund_request_id=? AND id<>? AND deleted=0
            """, Integer.class, refund.id(), before.id());
        if (linked != null && linked > 0) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "退款申请已关联其他服务工单"); }
        int changed = jdbc.update("""
            UPDATE core_crm_service_ticket SET refund_request_id=?,version=version+1,
                updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND refund_request_id IS NULL AND deleted=0
            """, refund.id(), before.id(), input.expectedVersion());
        if (changed != 1) { throw conflict("工单版本已变化或已关联退款申请"); }
        jdbc.update("""
            INSERT INTO core_crm_service_ticket_event
                (public_id,ticket_id,refund_request_id,actor_id,event_type,from_status,to_status,
                 detail_cipher,occurred_at)
            VALUES (?,?,?,?,'followup',?,?,?,UTC_TIMESTAMP(3))
            """, "TICK_EVT_" + UUID.randomUUID(), before.id(), refund.id(), actor.id(),
            before.status(), before.status(), cipher.encrypt("关联退款申请 " + refund.publicId()));
        return ticket(actor, publicId, false);
    }

    /** Rechecks ticket ownership and refund/customer/order/enrollment compatibility without writing.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param publicId ticket public identifier.
     * @param input validated request payload.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public void authorizeRefundLink(Actor actor, String publicId, ServiceTicketRefundLink input) {
        TicketRow ticket = ticketRow(actor, publicId, false);
        requireCurrentOwner(actor, ticket);
        refundLink(actor, ticket, input.refundRequestId(), false);
    }

    private RefundLinkRow refundLink(Actor actor, TicketRow ticket, String requestPublicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.orders(actor);
        List<Object> args = new ArrayList<>();
        args.add(ticket.enrollmentId());
        args.add(requestPublicId);
        args.addAll(scope.args());
        List<RefundLinkRow> rows = jdbc.query("""
            SELECT r.id,r.public_id,o.id order_id,o.customer_id,o.campus_id,e.order_id enrollment_order_id
            FROM core_crm_refund_request r JOIN core_crm_order o ON o.id=r.order_id AND o.deleted=0
            LEFT JOIN core_effective_enrollment e ON e.public_id=? AND e.deleted=0
            WHERE r.public_id=? AND r.deleted=0 AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new RefundLinkRow(rs.getLong("id"),rs.getString("public_id"),
                rs.getLong("order_id"),rs.getLong("customer_id"),rs.getObject("campus_id", Long.class),
                rs.getObject("enrollment_order_id", Long.class)), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "退款申请不存在或不在授权范围"); }
        RefundLinkRow refund = rows.get(0);
        if (refund.customerId() != customerId(actor, ticket.customerId(), false)
                || !java.util.Objects.equals(refund.campusId(), internalCampus(ticket.campusId()))
                || ticket.enrollmentId() != null && !java.util.Objects.equals(
                    refund.orderId(), refund.enrollmentOrderId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "退款订单与服务工单客户、校区或报名不匹配");
        }
        return refund;
    }

    /** Returns at most the latest 100 ticket events after verifying current ticket visibility.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param publicId ticket public identifier.
     * @return The latest visible ticket events, in repository order.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public List<ServiceTicketEvent> events(Actor actor, String publicId) {
        TicketRow ticket = ticketRow(actor, publicId, false);
        return jdbc.query("""
            SELECT e.public_id,p.public_id actor_public_id,e.event_type,rr.public_id refund_request_public_id,
                   fp.public_id from_owner_public_id,tp.public_id to_owner_public_id,
                   e.from_status,e.to_status,e.detail_cipher,e.actual_result_cipher,e.occurred_at
            FROM core_crm_service_ticket_event e JOIN core_principal p ON p.id=e.actor_id
            LEFT JOIN core_crm_refund_request rr ON rr.id=e.refund_request_id
            LEFT JOIN core_principal fp ON fp.id=e.from_owner_id LEFT JOIN core_principal tp ON tp.id=e.to_owner_id
            WHERE e.ticket_id=? AND e.deleted=0 ORDER BY e.occurred_at DESC,e.id DESC LIMIT 100
            """, (rs, row) -> new ServiceTicketEvent(rs.getString("public_id"),
                rs.getString("actor_public_id"), rs.getString("refund_request_public_id") == null
                    ? rs.getString("event_type") : "refund_linked",
                rs.getString("from_owner_public_id"), rs.getString("to_owner_public_id"),
                rs.getString("from_status"), rs.getString("to_status"),
                rs.getString("refund_request_public_id"), decrypt(rs.getString("detail_cipher")),
                decrypt(rs.getString("actual_result_cipher")),
                rs.getTimestamp("occurred_at").toInstant()), ticket.id());
    }

    /** Checks current ticket visibility before an idempotent request replay.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param publicId ticket public identifier.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public void authorize(Actor actor, String publicId) { ticketRow(actor, publicId, true); }

    /** Checks that the caller is the ticket’s current owner before treatment writes or replay.
     * @param actor authenticated actor whose current permissions and data scope are enforced.
     * @param publicId ticket public identifier.
     * @throws BusinessException if the requested resource is out of scope, invalid, missing, duplicated or has a stale version.
     */
    public void authorizeOwner(Actor actor, String publicId) {
        requireCurrentOwner(actor, ticketRow(actor, publicId, false));
    }

    private ServiceTicket ticket(Actor actor, String publicId, boolean lock) {
        return ticketDto(actor, ticketRow(actor, publicId, lock));
    }
    private TicketRow ticketRow(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = lock?ticketScope(actor):ticketReadScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<TicketRow> rows = jdbc.query(ticketSelect() + " WHERE t.public_id=? AND t.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), this::ticketRow, args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "服务工单不存在或不在授权范围"); }
        return rows.get(0);
    }
    private String ticketSelect() {
        return """
            SELECT t.id,t.public_id,c.public_id customer_public_id,cp.public_id campus_public_id,
                   t.ticket_type_id,t.owner_id,p.public_id owner_public_id,t.created_by,creator.public_id creator_public_id,
                   en.public_id enrollment_public_id,cl.public_id class_public_id,rr.public_id refund_request_public_id,
                   rr.status refund_status,rf.amount refund_amount,rf.refunded_at refund_at,
                   t.title_cipher,t.description_cipher,
                   t.status,t.due_at,t.next_follow_at,t.resolution_cipher,t.actual_result_cipher,t.completed_at,t.version
            FROM core_crm_service_ticket t JOIN core_customer c ON c.id=t.customer_id
            LEFT JOIN core_campus cp ON cp.id=t.campus_id JOIN core_principal p ON p.id=t.owner_id
            JOIN core_principal creator ON creator.id=t.created_by
            LEFT JOIN core_effective_enrollment en ON en.id=t.enrollment_id LEFT JOIN core_class cl ON cl.id=t.class_id
            LEFT JOIN core_crm_refund_request rr ON rr.id=t.refund_request_id
            LEFT JOIN core_crm_refund rf ON rf.refund_request_id=rr.id AND rf.deleted=0
            """;
    }
    private TicketRow ticketRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TicketRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("customer_public_id"),
            rs.getString("campus_public_id"), rs.getLong("ticket_type_id"), rs.getString("owner_public_id"),
            rs.getString("creator_public_id"), rs.getString("enrollment_public_id"), rs.getString("class_public_id"),
            rs.getString("refund_request_public_id"), rs.getString("refund_status"),
            rs.getBigDecimal("refund_amount"),rs.getTimestamp("refund_at"),
            rs.getString("title_cipher"), rs.getString("description_cipher"), rs.getString("status"),
            rs.getTimestamp("due_at"), rs.getTimestamp("next_follow_at"), rs.getString("resolution_cipher"),
            rs.getString("actual_result_cipher"), rs.getTimestamp("completed_at"), rs.getLong("version"));
    }
    private ServiceTicket ticketDto(Actor actor, TicketRow row) {
        TypeRow type = typeById(row.typeId());
        return new ServiceTicket(row.publicId(), row.customerId(), row.campusId(), typeDto(type),
            row.ownerId(), row.createdBy(), row.enrollmentId(), row.classId(), row.refundRequestId(), row.refundStatus(),
            row.refundAmount(), instant(row.refundAt()),
            cipher.decrypt(row.titleCipher()), cipher.decrypt(row.descriptionCipher()), row.status(), instant(row.dueAt()),
            instant(row.nextFollowAt()), decrypt(row.resolutionCipher()), decrypt(row.actualResultCipher()),
            instant(row.completedAt()), row.version());
    }
    private TypeRow type(Actor actor, String publicId, boolean lock) {
        List<TypeRow> rows = jdbc.query("""
            SELECT t.id,t.public_id,cp.public_id campus_public_id,t.type_code,t.name,t.active,t.version
            FROM core_crm_service_ticket_type t LEFT JOIN core_campus cp ON cp.id=t.campus_id
            WHERE t.public_id=? AND t.deleted=0
            """ + (lock ? " FOR UPDATE" : ""), this::typeRow, publicId);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "工单类型不存在"); }
        Long campusId = internalCampus(rows.get(0).campusId());
        if (campusId != null && !actor.campusIds().contains(campusId) && !actor.scope().equals("all")) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单类型不存在");
        }
        return rows.get(0);
    }
    private Long typeCampus(Actor actor, ServiceTicketTypeInput input) {
        Long campusId = input.campusId() == null || input.campusId().isBlank()
            ? null : campus(actor, input.campusId());
        if (campusId == null && !actor.scope().equals("all")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "全局工单类型只能由全部范围账号创建");
        }
        return campusId;
    }
    private TicketDraft draft(Actor actor, ServiceTicketInput input, boolean lockCustomer) {
        long customer = customerId(actor, input.customerId(), lockCustomer);
        Long customerCampus = jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id=?", Long.class, customer);
        TypeRow type = typeByCode(input.typeCode(), customerCampus, lockCustomer);
        Long enrollmentId = null;
        Long classId = null;
        if (input.enrollmentId() != null && !input.enrollmentId().isBlank()) {
            actor.require("enrollment.manage");
            actor.require("class.read");
            EnrollmentLink enrollment = enrollment(actor, input.enrollmentId(), lockCustomer);
            requireCustomerStudent(customer, enrollment.studentId());
            if (!java.util.Objects.equals(customerCampus, enrollment.campusId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "客户与关联报名必须属于同一校区");
            }
            enrollmentId = enrollment.id();
            classId = enrollment.classId();
        }
        if (input.classId() != null && !input.classId().isBlank()) {
            actor.require("class.read");
            ClassRef requestedClass = classRef(actor, input.classId());
            if (classId != null && classId.longValue() != requestedClass.id()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "报名与工单班级不一致");
            }
            classId = requestedClass.id();
            if (!java.util.Objects.equals(customerCampus, requestedClass.campusId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "客户与关联班级必须属于同一校区");
            }
        }
        if (customerCampus == null && classId != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "无校区客户不能关联班级工单");
        }
        String ownerPublicId = input.ownerId() == null || input.ownerId().isBlank() ? actor.publicId() : input.ownerId();
        long owner = owner(actor, ownerPublicId, customerCampus);
        if (owner != actor.id()) { CrmLegacyAccess.operation(actor, "lead.assign", "crm.ticket.assign"); }
        return new TicketDraft(customer, customerCampus, type, enrollmentId, classId, owner);
    }
    private TypeRow typeByCode(String code, Long campusId, boolean lock) {
        List<TypeRow> rows = jdbc.query("""
            SELECT t.id,t.public_id,cp.public_id campus_public_id,t.type_code,t.name,t.active,t.version
            FROM core_crm_service_ticket_type t LEFT JOIN core_campus cp ON cp.id=t.campus_id
            WHERE t.type_code=? AND t.active=1 AND t.deleted=0
              AND (t.campus_id=? OR t.campus_id IS NULL)
            ORDER BY (t.campus_id IS NOT NULL) DESC,t.id LIMIT 1
            """ + (lock ? " FOR SHARE" : ""), this::typeRow, code, campusId);
        if (rows.isEmpty()) { throw invalid("工单类型无效或已停用"); }
        return rows.get(0);
    }
    private TypeRow typeById(long id) {
        List<TypeRow> rows = jdbc.query("""
            SELECT t.id,t.public_id,cp.public_id campus_public_id,t.type_code,t.name,t.active,t.version
            FROM core_crm_service_ticket_type t LEFT JOIN core_campus cp ON cp.id=t.campus_id
            WHERE t.id=? AND t.deleted=0
            """, this::typeRow, id);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "工单类型不存在"); }
        return rows.get(0);
    }
    private TypeRow typeRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TypeRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("campus_public_id"),
            rs.getString("type_code"), rs.getString("name"), rs.getBoolean("active"), rs.getLong("version"));
    }
    private ServiceTicketType typeDto(TypeRow row) {
        return new ServiceTicketType(row.publicId(), row.campusId(), row.code(), row.name(), row.active(), row.version());
    }
    private long customerId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.customers(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT c.id FROM core_customer c WHERE c.public_id=? AND c.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围"); }
        return ids.get(0);
    }
    private Long campus(Actor actor, String publicId) {
        List<Long> ids = jdbc.query("SELECT cp.id FROM core_campus cp WHERE cp.public_id=? AND cp.deleted=0",
            (rs, row) -> rs.getLong(1), publicId);
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在"); }
        long id = ids.get(0);
        if (!actor.scope().equals("all") && !actor.campusIds().contains(id)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或不在授权范围");
        }
        return id;
    }
    private ClassRef classRef(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<ClassRef> rows = jdbc.query("""
            SELECT c.id,c.campus_id FROM core_class c WHERE c.public_id=? AND c.deleted=0 AND ("""
            + scope.sql() + ")", (rs, row) -> new ClassRef(rs.getLong("id"), rs.getLong("campus_id")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级不存在或不在授权范围"); }
        return rows.get(0);
    }
    private EnrollmentLink enrollment(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause classes = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(classes.args());
        List<EnrollmentLink> rows = jdbc.query("""
            SELECT e.id,e.student_id,e.class_id,c.campus_id FROM core_effective_enrollment e JOIN core_class c ON c.id=e.class_id
            WHERE e.public_id=? AND e.deleted=0 AND e.status IN ('active','pending') AND c.deleted=0
              AND (""" + classes.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new EnrollmentLink(rs.getLong("id"),rs.getLong("student_id"),
                rs.getLong("class_id"),rs.getLong("campus_id")),
            args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "报名不存在或不在授权范围"); }
        return rows.get(0);
    }
    private void requireCustomerStudent(long customerId, long studentId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_customer_student
            WHERE customer_id=? AND student_id=? AND deleted=0
            """, Integer.class, customerId, studentId);
        if (count == null || count == 0) { throw invalid("关联报名学生不属于该客户档案"); }
    }
    private long owner(Actor actor, String publicId, Long campusId) {
        if (campusId == null && !actor.scope().equals("all") && !publicId.equals(actor.publicId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无校区客户的工单须由当前员工负责");
        }
        String modern = CrmPrincipalEligibility.permission("crm.ticket.write")
            + " AND (? IS NULL OR EXISTS (SELECT pc.id FROM core_principal_campus pc "
            + "WHERE pc.principal_id=p.id AND pc.campus_id=? AND pc.deleted=0))";
        String legacy = CrmPrincipalEligibility.permission("lead.follow")
            + " AND p.scope_type<>'assigned' AND (p.scope_type<>'campus' OR EXISTS ("
            + "SELECT pc.id FROM core_principal_campus pc WHERE pc.principal_id=p.id "
            + "AND pc.campus_id=? AND pc.deleted=0))";
        List<Long> ids = jdbc.query("SELECT p.id FROM core_principal p WHERE p.public_id=? "
            + "AND p.active=1 AND p.deleted=0 AND p.archived_at IS NULL AND p.credential_change_required=0 "
            + "AND ((" + modern + ") OR (" + legacy + "))",
            (rs, row) -> rs.getLong(1), publicId, campusId, campusId, campusId);
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.INVALID_INPUT, "负责人未启用、无工单权限或不在该校区"); }
        return ids.get(0);
    }
    private static void requireCurrentOwner(Actor actor, TicketRow row) {
        if (!row.ownerId().equals(actor.publicId())&&!LegacyCrmScope.mayTreatTicket(actor,row.createdBy())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "工单已转派，当前账号不再有办理权限");
        }
    }
    private String enrollmentResult(String enrollmentPublicId) {
        if (enrollmentPublicId == null) { return null; }
        List<String> results = jdbc.query("""
            SELECT CONCAT('报名 ',e.public_id,' 状态=',e.status,' 更新时间=',DATE_FORMAT(e.updated_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
                '; 班级 ',c.public_id,' 状态=',IF(c.active=1,'active','inactive'))
            FROM core_effective_enrollment e JOIN core_class c ON c.id=e.class_id
            WHERE e.public_id=? AND e.deleted=0 AND c.deleted=0
            """, (rs, row) -> rs.getString(1), enrollmentPublicId);
        return results.isEmpty() ? null : results.get(0);
    }
    private void event(TicketEventWrite write) {
        jdbc.update("""
            INSERT INTO core_crm_service_ticket_event
                (public_id,ticket_id,actor_id,event_type,from_owner_id,to_owner_id,from_status,to_status,
                 detail_cipher,actual_result_cipher,occurred_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
            """, "TICK_EVT_" + UUID.randomUUID(), write.ticketId(), write.actor().id(), write.type(),
            write.transition().fromOwner(),write.transition().toOwner(),write.transition().fromStatus(),
            write.transition().toStatus(),encrypted(write.detail().detail()),encrypted(write.detail().actualResult()));
    }
    private ScopeSql.Clause ticketReadScope(Actor actor) { return CrmLegacyAccess.legacyRead(actor,"crm.ticket.read")?LegacyCrmScope.ticket(actor,true):ticketScope(actor); }
    private ScopeSql.Clause ticketScope(Actor actor) {
        if(actor.permissions().contains("lead.follow")||actor.permissions().contains("lead.assign"))return LegacyCrmScope.ticket(actor,false);
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("t.campus_id IN (SELECT campus_id FROM core_principal_campus "
                + "WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "assigned", "own" -> new ScopeSql.Clause("t.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    private long ticketId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_crm_service_ticket WHERE public_id=?", Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工单创建失败"); }
        return id;
    }
    private Long internalCampus(String publicId) {
        if (publicId == null) { return null; }
        Long id = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?", Long.class, publicId);
        return id;
    }
    private long internalOwner(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?", Long.class, publicId);
        return id == null ? 0 : id;
    }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private String encrypted(String value){return value==null||value.isBlank()?null:cipher.encrypt(value.trim());}
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.ALREADY_EXISTS, message); }
    private static BusinessException registryConflict(String message) { return new BusinessException(ErrorCode.VERSION_CONFLICT, message); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
    /** Authorized class identifier and campus used to compare ticket academic links. */
    private record ClassRef(long id, long campusId) { }
    /** Authorized enrollment, student, class and campus identifiers used to validate customer consistency. */
    private record EnrollmentLink(long id, long studentId, long classId, long campusId) { }
}
