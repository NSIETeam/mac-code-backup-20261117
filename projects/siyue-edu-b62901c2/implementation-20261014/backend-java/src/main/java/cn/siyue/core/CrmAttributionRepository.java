package cn.siyue.core;

import cn.siyue.core.CrmDtos.AttributionAdjustment;
import cn.siyue.core.CrmDtos.AttributionEvent;
import cn.siyue.core.CrmDtos.AttributionRequest;
import cn.siyue.core.CrmDtos.AttributionRequestInput;
import cn.siyue.core.CrmDtos.AttributionRequestPage;
import cn.siyue.core.CrmDtos.AttributionReviewInput;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores approved order-attribution corrections separately from immutable sale snapshots. */
@Repository
public class CrmAttributionRepository {
    /** Scoped order identity, immutable original attribution and latest effective owner used in correction checks. */
    private record OrderRow(long id, String publicId, long originalOwnerId, String originalOwnerPublicId,
            long currentOwnerId, String currentOwnerPublicId, Long campusId) { }
    /** Correction request state and encrypted evidence joined with its order and current effective owner. */
    private record RequestRow(long id, String publicId, long orderId, String orderPublicId,
            long applicantId, String applicantPublicId, long fromOwnerId, String fromOwnerPublicId,
            long proposedOwnerId, String proposedOwnerPublicId, long currentOwnerId,
            String currentOwnerPublicId, String reasonCipher, String evidenceCipher,
            String status, Long reviewerId, String reviewerPublicId, String reviewNoteCipher,
            Timestamp reviewedAt, long version) { }
    /** Previous and resulting request states recorded in an immutable attribution-review event. */
    private record AttributionTransition(String from,String to) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    /** Wires the separate correction ledger and encryption for reasons, evidence and review notes.
     * @param jdbc database access joining the attribution service transaction
     * @param cipher keyring for protected correction and review details
     */
    public CrmAttributionRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Locks a visible order and creates its sole pending attribution correction request.
     * @param actor caller already authorized to request a correction by the service
     * @param orderPublicId order identifier whose current effective owner is snapshotted
     * @param input proposed owner, correction reason and optional evidence
     * @return pending request projection with disclosure based on the caller's detail permissions
     * @throws BusinessException if order scope, an existing pending request or proposed-owner checks reject the request
     */
    public AttributionRequest request(Actor actor, String orderPublicId, AttributionRequestInput input) {
        OrderRow order = order(actor, orderPublicId, true);
        Integer pending = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_crm_attribution_request
            WHERE order_id=? AND status='pending_approval' AND deleted=0
            """, Integer.class, order.id());
        if (pending != null && pending > 0) { throw invalid("该订单已有待审批的归因纠错申请"); }
        long nextOwner = eligibleOwner(actor, input.proposedOwnerId(), order.campusId());
        if (nextOwner == order.currentOwnerId()) { throw invalid("新归属人与订单当前归属相同"); }
        String publicId = "ATTRREQ_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_attribution_request
                (public_id,order_id,applicant_id,from_owner_id,proposed_owner_id,reason_cipher,evidence_cipher)
            VALUES (?,?,?,?,?,?,?)
            """, publicId, order.id(), actor.id(), order.currentOwnerId(), nextOwner,
            cipher.encrypt(input.reason().trim()), input.evidence() == null || input.evidence().isBlank()
                ? null : cipher.encrypt(input.evidence().trim()));
        long id = requestId(publicId);
        event(id,actor,"requested",new AttributionTransition(null,"pending_approval"),input.reason());
        return requestDto(actor, request(actor, publicId, false));
    }

    /** Reads correction requests after checking current access to their order.
     * @param actor caller whose order scope and sensitive-detail permissions are applied
     * @param orderPublicId order public identifier
     * @param page service-validated one-based page
     * @param size service-validated maximum row count
     * @return paged correction requests, newest first, with matching total
     * @throws BusinessException if the order is missing or outside the caller's current scope
     */
    public AttributionRequestPage requests(Actor actor, String orderPublicId, int page, int size) {
        OrderRow order = order(actor, orderPublicId, false);
        Long total = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_attribution_request WHERE order_id=? AND deleted=0",
            Long.class, order.id());
        List<AttributionRequest> items = jdbc.query(requestSelect()
            + " WHERE r.order_id=? AND r.deleted=0 ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> requestDto(actor, requestRow(rs, row)), order.id(), size,
            Math.multiplyExact((long) page - 1, size));
        return new AttributionRequestPage(items,total == null ? 0 : total,page,size);
    }

    /** Reviews a pending request without editing the immutable original order attribution.
     * @param actor service-authorized reviewer, who must differ from the applicant
     * @param requestPublicId correction request identifier
     * @param input approval/rejection decision, note and expected request version
     * @return reviewed request; approval appends a separate effective-owner adjustment
     * @throws BusinessException if scope, self-review, request state/version, changed ownership or recipient eligibility rejects review
     */
    public AttributionRequest review(Actor actor, String requestPublicId, AttributionReviewInput input) {
        RequestRow request = request(actor, requestPublicId, true);
        if (request.applicantId() == actor.id()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "申请人不能审批自己的归因纠错");
        }
        if (!request.status().equals("pending_approval")) { throw invalid("归因纠错申请已处理"); }
        OrderRow order = order(actor, request.orderPublicId(), true);
        if (input.decision().equals("approve")) {
            if (order.currentOwnerId() != request.fromOwnerId()) {
                throw invalid("订单归属在申请后已有调整，请重新提交纠错申请");
            }
            eligibleOwner(actor, request.proposedOwnerPublicId(), order.campusId());
            appendAdjustment(actor, request, order, input.note());
        }
        String next = input.decision().equals("approve") ? "approved" : "rejected";
        int changed = jdbc.update("""
            UPDATE core_crm_attribution_request SET status=?,reviewer_id=?,review_note_cipher=?,reviewed_at=?,
                version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status='pending_approval' AND deleted=0
            """, next, actor.id(), cipher.encrypt(input.note().trim()), Timestamp.from(Instant.now()),
            request.id(), input.expectedVersion());
        if (changed != 1) { throw conflict("归因纠错申请版本已变化"); }
        event(request.id(),actor,input.decision().equals("approve")?"approved":"rejected",
            new AttributionTransition("pending_approval",next),input.note());
        return requestDto(actor, request(actor, requestPublicId, false));
    }

    /** Reads approved attribution adjustments while preserving separate detail disclosure rules.
     * @param actor caller whose order visibility is required; reasons/evidence require detail permission or involvement as an owner
     * @param orderPublicId order public identifier
     * @return at most 100 newest immutable adjustments with protected details omitted when unauthorized
     * @throws BusinessException if the order is missing or outside the caller's current scope
     */
    public List<AttributionAdjustment> adjustments(Actor actor, String orderPublicId) {
        OrderRow order = order(actor, orderPublicId, false);
        boolean readSensitiveDetail = actor.permissions().contains("crm.attribution.read_detail");
        return jdbc.query("""
            SELECT a.public_id,r.public_id request_public_id,o.public_id order_public_id,
                   fp.public_id from_owner_public_id,tp.public_id to_owner_public_id,
                   rp.public_id reviewer_public_id,a.reason_cipher,a.evidence_cipher,a.adjusted_at
            FROM core_crm_attribution_adjustment a
            JOIN core_crm_attribution_request r ON r.id=a.request_id
            JOIN core_crm_order o ON o.id=a.order_id
            JOIN core_principal fp ON fp.id=a.from_owner_id
            JOIN core_principal tp ON tp.id=a.to_owner_id
            JOIN core_principal rp ON rp.id=a.reviewer_id
            WHERE a.order_id=? AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 100
            """, (rs, row) -> new AttributionAdjustment(rs.getString("public_id"),
                rs.getString("request_public_id"),rs.getString("order_public_id"),
                rs.getString("from_owner_public_id"),rs.getString("to_owner_public_id"),
                rs.getString("reviewer_public_id"),decrypt(readSensitiveDetail
                    || actor.publicId().equals(rs.getString("from_owner_public_id"))
                    || actor.publicId().equals(rs.getString("to_owner_public_id")) ? rs.getString("reason_cipher") : null),
                decrypt(readSensitiveDetail || actor.publicId().equals(rs.getString("from_owner_public_id"))
                    || actor.publicId().equals(rs.getString("to_owner_public_id")) ? rs.getString("evidence_cipher") : null),
                rs.getTimestamp("adjusted_at").toInstant()), order.id());
    }

    /** Reads request history after checking its order access and applicant/detail disclosure rule.
     * @param actor caller whose order scope and request-detail visibility are applied
     * @param requestPublicId correction request identifier
     * @return up to 100 newest request events, with detail omitted where the caller cannot read it
     * @throws BusinessException if the request is missing or its order is outside current scope
     */
    public List<AttributionEvent> events(Actor actor, String requestPublicId) {
        RequestRow request = request(actor, requestPublicId, false);
        boolean readDetail = canReadDetail(actor, request.applicantPublicId());
        return jdbc.query("""
            SELECT e.public_id,p.public_id actor_public_id,e.event_type,e.from_status,e.to_status,
                   e.detail_cipher,e.occurred_at
            FROM core_crm_attribution_request_event e JOIN core_principal p ON p.id=e.actor_id
            WHERE e.request_id=? AND e.deleted=0 ORDER BY e.occurred_at DESC,e.id DESC LIMIT 100
            """, (rs, row) -> new AttributionEvent(rs.getString("public_id"),rs.getString("actor_public_id"),
                rs.getString("event_type"),rs.getString("from_status"),rs.getString("to_status"),
                decrypt(readDetail ? rs.getString("detail_cipher") : null),rs.getTimestamp("occurred_at").toInstant()),
            request.id());
    }

    /** Checks current order visibility before a correction-request receipt can be replayed.
     * @param actor caller already checked for the operation grant by the service
     * @param orderId order public identifier
     * @throws BusinessException if the order is missing or outside current scope
     */
    public void authorizeOrder(Actor actor, String orderId) { order(actor, orderId, false); }

    /** Checks current access to a request and its order before a review receipt can be replayed.
     * @param actor caller already checked for the review grant by the service
     * @param requestId correction request public identifier
     * @throws BusinessException if the request is missing or its order is outside current scope
     */
    public void authorizeRequest(Actor actor, String requestId) { request(actor, requestId, false); }

    /** Resolves the latest approved owner adjustment, falling back to the original sale owner.
     * @param actor caller whose current order visibility is required
     * @param orderId order public identifier
     * @return effective attributed employee's public identifier
     * @throws BusinessException if the order is missing or outside current scope
     */
    public String currentOwner(Actor actor, String orderId) { return order(actor, orderId, false).currentOwnerPublicId(); }

    private void appendAdjustment(Actor actor, RequestRow request, OrderRow order, String note) {
        jdbc.update("""
            INSERT INTO core_crm_attribution_adjustment
                (public_id,request_id,order_id,from_owner_id,to_owner_id,reviewer_id,
                 reason_cipher,evidence_cipher,adjusted_at)
            SELECT ?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3)
            """, "ATTRADJ_" + UUID.randomUUID(),request.id(),order.id(),order.currentOwnerId(),
            request.proposedOwnerId(),actor.id(),request.reasonCipher(),request.evidenceCipher());
    }

    private OrderRow order(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = orderAccessScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<OrderRow> rows = jdbc.query("""
            SELECT o.id,o.public_id,o.attributed_owner_id original_owner_id,original.public_id original_owner_public_id,
                   COALESCE((SELECT a.to_owner_id FROM core_crm_attribution_adjustment a
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       o.attributed_owner_id) current_owner_id,
                   COALESCE((SELECT current_owner.public_id FROM core_crm_attribution_adjustment a
                       JOIN core_principal current_owner ON current_owner.id=a.to_owner_id
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       original.public_id) current_owner_public_id,
                   o.campus_id
            FROM core_crm_order o JOIN core_principal original ON original.id=o.attributed_owner_id
            WHERE o.public_id=? AND o.deleted=0 AND (""" + scope.sql() + ")"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new OrderRow(rs.getLong("id"),rs.getString("public_id"),rs.getLong("original_owner_id"),
                rs.getString("original_owner_public_id"),rs.getLong("current_owner_id"),
                rs.getString("current_owner_public_id"),rs.getObject("campus_id", Long.class)),args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不在授权范围"); }
        return rows.get(0);
    }

    private RequestRow request(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = orderAccessScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<RequestRow> rows = jdbc.query(requestSelect() + " WHERE r.public_id=? AND r.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), this::requestRow, args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "归因纠错申请不存在或不在授权范围"); }
        return rows.get(0);
    }

    private String requestSelect() {
        return """
            SELECT r.id,r.public_id,r.order_id,o.public_id order_public_id,r.applicant_id,applicant.public_id applicant_public_id,
                   r.from_owner_id,from_owner.public_id from_owner_public_id,
                   r.proposed_owner_id,proposed.public_id proposed_owner_public_id,
                   COALESCE((SELECT a.to_owner_id FROM core_crm_attribution_adjustment a
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       o.attributed_owner_id) current_owner_id,
                   COALESCE((SELECT target.public_id FROM core_crm_attribution_adjustment a
                       JOIN core_principal target ON target.id=a.to_owner_id
                       WHERE a.order_id=o.id AND a.deleted=0 ORDER BY a.adjusted_at DESC,a.id DESC LIMIT 1),
                       original.public_id) current_owner_public_id,
                   r.reason_cipher,r.evidence_cipher,r.status,r.reviewer_id,reviewer.public_id reviewer_public_id,
                   r.review_note_cipher,r.reviewed_at,r.version
            FROM core_crm_attribution_request r JOIN core_crm_order o ON o.id=r.order_id
            JOIN core_principal original ON original.id=o.attributed_owner_id
            JOIN core_principal applicant ON applicant.id=r.applicant_id
            JOIN core_principal from_owner ON from_owner.id=r.from_owner_id
            JOIN core_principal proposed ON proposed.id=r.proposed_owner_id
            LEFT JOIN core_principal reviewer ON reviewer.id=r.reviewer_id
            """;
    }

    private RequestRow requestRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        long reviewer = rs.getLong("reviewer_id");
        Long reviewerId = rs.wasNull() ? null : reviewer;
        return new RequestRow(rs.getLong("id"),rs.getString("public_id"),rs.getLong("order_id"),
            rs.getString("order_public_id"),rs.getLong("applicant_id"),rs.getString("applicant_public_id"),
            rs.getLong("from_owner_id"),rs.getString("from_owner_public_id"),rs.getLong("proposed_owner_id"),
            rs.getString("proposed_owner_public_id"),rs.getLong("current_owner_id"),
            rs.getString("current_owner_public_id"),rs.getString("reason_cipher"),rs.getString("evidence_cipher"),
            rs.getString("status"),reviewerId,rs.getString("reviewer_public_id"),rs.getString("review_note_cipher"),
            rs.getTimestamp("reviewed_at"),rs.getLong("version"));
    }

    private AttributionRequest requestDto(Actor actor, RequestRow row) {
        boolean readDetail = canReadDetail(actor, row.applicantPublicId());
        return new AttributionRequest(row.publicId(),row.orderPublicId(),row.applicantPublicId(),
            row.fromOwnerPublicId(),row.currentOwnerPublicId(),row.proposedOwnerPublicId(),
            decrypt(readDetail ? row.reasonCipher() : null),decrypt(readDetail ? row.evidenceCipher() : null),
            row.status(),row.reviewerPublicId(),decrypt(readDetail ? row.reviewNoteCipher() : null),
            instant(row.reviewedAt()),row.version());
    }

    private long eligibleOwner(Actor actor, String publicId, Long campusId) {
        if (campusId == null && !actor.scope().equals("all")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无校区订单的归属调整需要全部范围账号");
        }
        String campusFilter = campusId == null ? " AND p.scope_type='all'"
            : " AND EXISTS (SELECT pc.id FROM core_principal_campus pc WHERE pc.principal_id=p.id AND pc.campus_id=? AND pc.deleted=0)";
        List<Long> ids = jdbc.query("""
            SELECT p.id FROM core_principal p
            WHERE p.public_id=? AND p.active=1 AND p.deleted=0
              AND EXISTS (SELECT pp.id FROM core_principal_permission pp
                WHERE pp.principal_id=p.id AND pp.permission_code IN ('crm.order.read','crm.opportunity.write') AND pp.deleted=0)
            """ + campusFilter, (rs, row) -> rs.getLong(1),
            campusId == null ? new Object[] { publicId } : new Object[] { publicId, campusId });
        if (ids.isEmpty()) { throw invalid("新归属员工未启用、无销售权限或不在订单校区"); }
        return ids.get(0);
    }

    private ScopeSql.Clause orderAccessScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1",List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)",List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("(o.attributed_owner_id=? OR EXISTS (SELECT a.id FROM core_crm_attribution_adjustment a "
                + "WHERE a.order_id=o.id AND a.deleted=0 AND (a.from_owner_id=? OR a.to_owner_id=?)))",
                List.of(actor.id(),actor.id(),actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN,"账号数据范围无效");
        };
    }

    private void event(long id,Actor actor,String type,AttributionTransition transition,String detail) {
        jdbc.update("""
            INSERT INTO core_crm_attribution_request_event
                (public_id,request_id,actor_id,event_type,from_status,to_status,detail_cipher,occurred_at)
            VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
            ""","ATTREVT_" + UUID.randomUUID(),id,actor.id(),type,transition.from(),transition.to(),
            detail == null ? null : cipher.encrypt(detail));
    }

    private long requestId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_crm_attribution_request WHERE public_id=?",Long.class,publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"归因纠错申请创建失败"); }
        return id;
    }

    private boolean canReadDetail(Actor actor, String applicantPublicId) {
        return actor.permissions().contains("crm.attribution.read_detail")
            || applicantPublicId != null && applicantPublicId.equals(actor.publicId());
    }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT,message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.ALREADY_EXISTS,message); }
}
