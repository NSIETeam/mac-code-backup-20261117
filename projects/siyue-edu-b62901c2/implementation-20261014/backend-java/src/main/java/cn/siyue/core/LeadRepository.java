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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists qualified sales leads and append-only ownership and status history. */
@Repository
public class LeadRepository {
    /** Scoped lead row with encrypted detail, ownership, screening and decision fields for read or mutation checks. */
    private record Row(long id, String publicId, String customerId, String campusId,
            String activityId, String studentId, String ownerId, String source,
            String subject, String term, String title, String status, String detailCipher,
            Instant nextFollowAt, long version, String decisionStatus, String decisionById,
            Instant decisionDeadline, Instant decisionTimedOutAt) { }
    /** Ownership/status transition and actor information for one immutable encrypted lead event. */
    private record EventWrite(long leadId, long actorId, String type, Long fromOwner,
            Long toOwner, String fromStatus, String toStatus, String detail) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentIdentityMutationLock identityLocks;
    /** Wires lead persistence, field encryption and the student identity mutation lock.
     * @param jdbc database access that joins the caller's transaction
     * @param cipher keyring for lead details and identity lookup fingerprints
     */
    public LeadRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.identityLocks = new StudentIdentityMutationLock(jdbc, cipher);
    }
    /** Reserves the customer name/phone identity before the service locks its principal row.
     * @param actor caller whose customer mutation scope is checked
     * @param input proposed lead with its source customer identifier
     * @throws BusinessException if the customer is not visible for mutation
     */
    public void lockCreateIdentity(Actor actor, LeadInput input) {
        long customer = customerId(actor, input.customerId(), false);
        CustomerIdentity identity = customerIdentity(customer, false);
        if (identity.nameCipher() != null && identity.phoneCipher() != null) {
            identityLocks.lock(cipher.decrypt(identity.nameCipher()), cipher.decrypt(identity.phoneCipher()));
        }
    }
    /** Checks source customer and optional activity/student links before lead creation or replay.
     * @param actor caller whose customer, activity and student visibility rules apply
     * @param input proposed lead and its linked-record identifiers
     * @throws BusinessException if a record is out of scope, campus values conflict or the student is not linked to the customer
     */
    public void authorizeCreate(Actor actor, LeadInput input) {
        long customer = customerId(actor, input.customerId(), false);
        Long campus = jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id=?", Long.class, customer);
        if (input.activityId() != null) {
            Long activityCampus = activityCampus(actor, input.activityId(), false).campusId();
            if (activityCampus != null && !java.util.Objects.equals(campus, activityCampus)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "客户与活动必须属于同一校区");
            }
        }
        if (input.studentId() != null) {
            long student = studentId(actor, input.studentId(), false);
            Integer linked = jdbc.queryForObject("""
                SELECT COUNT(id) FROM core_customer_student
                WHERE customer_id=? AND student_id=? AND deleted=0
                """, Integer.class, customer, student);
            if (linked == null || linked == 0) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "学生尚未关联到该客户档案");
            }
        }
    }
    /** Creates a lead owned and registered by the caller after locking its source customer identity.
     * @param actor caller already authorized for lead creation by the service
     * @param input source customer, optional links, acquisition classification and descriptive details
     * @return new lead with decision deadline when the applicable lead policy enables one
     * @throws BusinessException if linked identity changes, conflicts with a student or fails a scoped record check
     */
    public Lead create(Actor actor, LeadInput input) {
        long observedCustomer = customerId(actor, input.customerId(), false);
        CustomerIdentity observed = customerIdentity(observedCustomer, false);
        if (observed.nameCipher() != null && observed.phoneCipher() != null) {
            String name = cipher.decrypt(observed.nameCipher());
            String phone = cipher.decrypt(observed.phoneCipher());
            // Identity mutex precedes the student row lock, matching StudentProfileRepository.
            identityLocks.lock(name, phone);
            assertStudentIdentityUnused(name, phone, input.studentId());
        }
        long customer = customerId(actor, input.customerId(), true);
        if (customer != observedCustomer || !observed.equals(customerIdentity(customer, true))) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "客户身份资料已变更，请刷新后重试");
        }
        String nameCipher = observed.nameCipher();
        String phoneCipher = observed.phoneCipher();
        Long campus = jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id=?", Long.class, customer);
        Long activity = input.activityId() == null ? null : activityCampus(actor, input.activityId(), true).id();
        Long student = input.studentId() == null ? null : studentId(actor, input.studentId(), true);
        String id = "LEAD_" + UUID.randomUUID();
        Instant createdAt = Instant.now();
        Registration registration = registration(actor,input,createdAt);
        jdbc.update("""
            INSERT INTO core_crm_lead
                (public_id,customer_id,campus_id,activity_id,student_id,owner_id,decision_status,decision_by_id,
                 decision_deadline,source,subject,term,title,status,detail_cipher,name_cipher,phone_cipher,
                 contact_phone_cipher,next_follow_at,created_at,updated_at)
            VALUES (?,?,?,?,?,?, ?,?,?, ?,?,?,?, ?,?,?,?,?,?,?,?)
            """, id, customer, campus, activity, student, registration.owner(), registration.decision(),
            registration.decider(), timestamp(registration.deadline()), input.source(), clean(input.subject()),
            clean(input.term()), input.title().trim(), "retained".equals(registration.decision()) ? "contacting" : "new",
            encrypted(input.detail()), nameCipher,
            phoneCipher, phoneCipher, timestamp(registration.owner() == null ? null : input.nextFollowAt()), timestamp(createdAt), timestamp(createdAt));
        long rowId = internalId(id);
        persistRegistration(actor,input,new CreatedLead(rowId,createdAt,registration));
        SavedViewLeadSearch.save(jdbc, cipher, rowId,
            new SavedViewLeadSearch.Fields(decrypt(nameCipher), decrypt(phoneCipher), decrypt(phoneCipher), null));
        event(new EventWrite(rowId, actor.id(), "created", null, registration.owner(), null,
            "retained".equals(registration.decision()) ? "contacting" : "new", input.detail()));
        return lead(actor, id, true);
    }
    /** Registration ownership is determined independently from the employee who entered the record. */
    private record Registration(Long owner,Long decider,String decision,Instant deadline) { }
    /** Newly inserted row and its decision, passed together to preserve one-transaction history writes. */
    private record CreatedLead(long id,Instant at,Registration registration) { }
    private Registration registration(Actor actor,LeadInput input,Instant createdAt) {
        boolean canFollow = actor.permissions().contains("lead.follow") || actor.permissions().contains("crm.lead.follow");
        boolean publicChoice = "public".equals(input.followChoice()) || !canFollow;
        boolean selfChoice = "self".equals(input.followChoice());
        if (selfChoice) {
            actor.requireAny("lead.follow", "crm.lead.follow");
            Integer ready = jdbc.queryForObject("SELECT COUNT(id) FROM core_principal WHERE id=? AND active=1 "
                + "AND deleted=0 AND archived_at IS NULL AND credential_change_required=0",Integer.class,actor.id());
            if (ready == null || ready != 1) { throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号不能接收跟进线索"); }
        }
        Instant decisionDeadline = publicChoice || selfChoice ? null
            : new LeadPolicyRepository(jdbc).newDecisionDeadline(actor, createdAt);
        boolean decisionPending = decisionDeadline != null;
        Long assignedOwner = publicChoice ? null : actor.id();
        String decision = selfChoice ? "retained" : decisionPending ? "pending" : "public";
        return new Registration(assignedOwner,decisionPending || selfChoice ? actor.id() : null,decision,decisionDeadline);
    }
    private void persistRegistration(Actor actor,LeadInput input,CreatedLead created) {
        boolean self = "self".equals(input.followChoice());
        Long owner = created.registration().owner();
        jdbc.update("UPDATE core_crm_lead SET registrar_id=?,legacy_stage=?,legacy_status=?,assigned_at=? WHERE id=?",
            actor.id(), self ? "跟进中" : "未跟进", owner == null ? "public" : "assigned",
            owner == null ? null : timestamp(created.at()), created.id());
        if (input.followChoice() != null) {
            jdbc.update("INSERT INTO core_crm_lead_decision_event(public_id,lead_id,actor_id,decision,owner_id,occurred_at) "
                + "VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))", "LEADDEC_" + UUID.randomUUID(), created.id(), actor.id(),
                self ? "retain" : "release", owner);
        }
    }
    /** Encrypted customer name and primary phone observed before and after acquiring identity locks. */
    private record CustomerIdentity(String nameCipher, String phoneCipher) { }
    private CustomerIdentity customerIdentity(long customer, boolean lock) {
        String name = jdbc.query("SELECT display_name_cipher FROM core_customer WHERE id=? AND deleted=0",
            (rs, row) -> rs.getString(1), customer).stream().findFirst().orElse(null);
        String phone = jdbc.query("""
            SELECT value_cipher FROM core_customer_contact WHERE customer_id=? AND deleted=0 AND channel='phone'
            ORDER BY is_primary DESC,id LIMIT 1
            """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getString(1), customer).stream().findFirst()
            .orElse(null);
        return new CustomerIdentity(name, phone);
    }
    private void assertStudentIdentityUnused(String name, String phone, String allowedStudentPublicId) {
        var indexes = cipher.exactBlindIndexes("student_name",
            StudentIdentityMutationLock.identityKey(name, phone));
        String terms = String.join(" OR ", indexes.keySet().stream()
            .map(key -> "(key_id=? AND value_hash=?)").toList());
        java.util.List<Object> args = new java.util.ArrayList<>();
        indexes.forEach((key, hash) -> { args.add(key); args.add(hash); });
        List<Long> matches = jdbc.query("SELECT DISTINCT si.student_id FROM core_student_identity_index si "
            + "WHERE si.deleted=0 AND (" + terms + ")", (rs, row) -> rs.getLong(1), args.toArray());
        if (matches.isEmpty()) { return; }
        Long allowedStudent = allowedStudentPublicId == null ? null : jdbc.query(
            "SELECT id FROM core_student WHERE public_id=? AND deleted=0", (rs, row) -> rs.getLong(1),
            allowedStudentPublicId).stream().findFirst().orElse(null);
        if (allowedStudent == null || matches.stream().anyMatch(id -> !id.equals(allowedStudent))) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索身份与现有学生档案重复");
        }
    }
    /** Reads ordinary lead filters through the saved-view overload with no saved view.
     * @param actor caller whose independent lead read scope is applied
     * @param query service-validated lead filters and pagination
     * @return visible lead rows and matching total
     * @throws BusinessException if the caller has an unsupported data scope
     */
    public LeadPage list(Actor actor, LeadQuery query) {
        return list(actor, query, null);
    }
    /** Applies lead read relationships before optional saved-view substring search and ordering.
     * @param actor caller whose grants also govern linked activity, student and customer disclosure
     * @param query validated ordinary lead filters and pagination
     * @param savedView optional authorized saved view; its search also uses keyed PII candidate indexes
     * @return visible lead rows and total produced by the same query predicate
     * @throws BusinessException if the caller has an unsupported lead scope
     */
    public LeadPage list(Actor actor, LeadQuery query, LegacySavedViewQuery savedView) {
        ScopeSql.Clause scope = ScopeSql.leadReads(actor);
        List<Object> args = new ArrayList<>(scope.args());
        String filters = filters(query, args);
        if (savedView != null && savedView.searchPattern() != null) {
            String search = "(LOWER(CAST(l.public_id AS CHAR)) LIKE ? ESCAPE '!' OR LOWER(l.title) LIKE ? ESCAPE '!'"
                + " OR LOWER(l.source) LIKE ? ESCAPE '!' OR LOWER(l.subject) LIKE ? ESCAPE '!' OR LOWER(l.term) LIKE ? ESCAPE '!'";
            for (int index = 0; index < 5; index++) { args.add(savedView.searchPattern()); }
            if (CrmLegacyAccess.canRead(actor, "crm.customer.read")) {
                search += SavedViewCustomerSearch.addVisibleIds("l.customer_id",
                    SavedViewCustomerSearch.matchingIds(jdbc, cipher, actor, savedView.search()), args);
            }
            search += SavedViewCustomerSearch.addVisibleIds("l.id",
                SavedViewLeadSearch.matchingIds(jdbc, cipher, actor, savedView.search()), args) + ")";
            filters += " AND " + search;
        }
        String where = "l.deleted=0 AND (" + scope.sql() + ")" + filters;
        Long total = jdbc.queryForObject("SELECT COUNT(l.id) FROM core_crm_lead l "
            + "LEFT JOIN core_customer c ON c.id=l.customer_id LEFT JOIN core_campus cp ON cp.id=l.campus_id "
            + "LEFT JOIN core_activity a ON a.id=l.activity_id LEFT JOIN core_principal p ON p.id=l.owner_id WHERE " + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add(Math.multiplyExact((long) query.page() - 1, query.size()));
        List<Row> rows = jdbc.query("""
            SELECT l.id,l.public_id,c.public_id AS customer_public_id,cp.public_id AS campus_public_id,
                   a.public_id AS activity_public_id,st.public_id AS student_public_id,p.public_id AS owner_public_id,
                   l.source,l.subject,l.term,l.title,l.status,l.detail_cipher,l.next_follow_at,l.version,
                   l.decision_status,dp.public_id AS decision_by_public_id,l.decision_deadline,l.decision_timed_out_at
            FROM core_crm_lead l LEFT JOIN core_customer c ON c.id=l.customer_id
            LEFT JOIN core_campus cp ON cp.id=l.campus_id LEFT JOIN core_activity a ON a.id=l.activity_id
            LEFT JOIN core_student st ON st.id=l.student_id LEFT JOIN core_principal p ON p.id=l.owner_id
            LEFT JOIN core_principal dp ON dp.id=l.decision_by_id
            """ + " WHERE " + where + (savedView != null && savedView.present()
                ? " ORDER BY l.updated_at DESC,l.id DESC" : " ORDER BY l.created_at DESC,l.id DESC") + " LIMIT ? OFFSET ?",
            (rs, row) -> row(rs), pageArgs.toArray());
        return new LeadPage(rows.stream().map(row -> dto(actor, row)).toList(), total == null ? 0 : total,
            query.page(), query.size());
    }
    private String filters(LeadQuery query, List<Object> args) {
        List<String> filters = new ArrayList<>();
        addLeadFields(filters, args, query);
        addAssignmentFilters(filters, args, query);
        addFollowUpFilters(filters, args, query);
        return String.join("", filters);
    }
    private void addLeadFields(List<String> filters, List<Object> args, LeadQuery query) {
        addTextFilter(filters, args, "l.status", query.status());
        addTextFilter(filters, args, "c.public_id", query.customerId());
        addTextFilter(filters, args, "a.public_id", query.activityId());
        addTextFilter(filters, args, "l.source", query.source());
        addTextFilter(filters, args, "l.subject", query.subject());
    }
    private void addAssignmentFilters(List<String> filters, List<Object> args, LeadQuery query) {
        addTextFilter(filters, args, "cp.public_id", query.campusId());
        addTextFilter(filters, args, "p.public_id", query.ownerId());
        if (Boolean.TRUE.equals(query.unassignedOnly())) { filters.add(" AND l.owner_id IS NULL"); }
        if (Boolean.TRUE.equals(query.activeOnly())) {
            filters.add(" AND l.status NOT IN ('archived','no_need','invalid_contact')");
        }
    }
    private void addFollowUpFilters(List<String> filters, List<Object> args, LeadQuery query) {
        if (query.nextFollowFrom() != null) {
            filters.add(" AND l.next_follow_at>=?"); args.add(timestamp(query.nextFollowFrom()));
        }
        if (query.nextFollowUntil() != null) {
            filters.add(" AND l.next_follow_at<=?"); args.add(timestamp(query.nextFollowUntil()));
        }
    }
    private void addTextFilter(List<String> filters, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) { filters.add(" AND " + column + "=?"); args.add(value.trim()); }
    }
    /** Checks the modern mutation scope before an existing idempotent receipt can be disclosed.
     * @param actor caller with a service-validated mutation grant
     * @param publicId lead public identifier
     * @throws BusinessException if the lead is absent or outside the caller's current mutation scope
     */
    public void authorizeLead(Actor actor, String publicId) { leadRow(actor, publicId, false,ScopeSql.leads(actor)); }
    /** Rechecks the original write scope before an identical legacy mutation may be replayed. */
    void authorizeLegacyLead(Actor actor,String publicId) {leadRow(actor,publicId,true,ScopeSql.leadWrites(actor));}
    /** Locks the authorized lead, validates its recipient and records a distinct assignment event.
     * @param actor caller with a service-validated modern or legacy assignment grant
     * @param publicId lead public identifier
     * @param input recipient, reason and expected lead version
     * @return projection of the authorized locked row after ownership changes
     * @throws BusinessException if the lead state, pending decision, recipient eligibility or version rejects assignment
     */
    public Lead assign(Actor actor, String publicId, LeadOwner input) {
        return assign(actor, publicId, input, "assigned");
    }
    /** Uses the assignment checks while recording the immutable event as a transfer.
     * @param actor caller authorized for the separate transfer operation by the service
     * @param publicId lead public identifier
     * @param input recipient, transfer reason and expected lead version
     * @return receipt projected only from this transaction's authorized locked lead
     * @throws BusinessException if source scope, pending decision, recipient readiness or version checks fail
     */
    public Lead transfer(Actor actor, String publicId, LeadOwner input) {
        return assign(actor, publicId, input, "transferred");
    }
    private Lead assign(Actor actor, String publicId, LeadOwner input, String eventType) {
        Row current = leadRow(actor, publicId, true,actor.permissions().contains("lead.assign")||actor.permissions().contains("lead.transfer") ? ScopeSql.leadWrites(actor):ScopeSql.leads(actor));
        if (current.status().equals("archived")) { throw new BusinessException(ErrorCode.INVALID_INPUT, "归档线索须先恢复再分配"); }
        if (current.ownerId() != null && current.ownerId().equals(input.ownerId())) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "NO_CHANGE：线索已经属于该负责人");
        }
        boolean pendingDecision = "pending".equals(current.decisionStatus());
        if (pendingDecision && !actor.publicId().equals(current.decisionById())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有当前待决策人可以处理该线索");
        }
        if (pendingDecision && (current.decisionDeadline() == null
                || !current.decisionDeadline().isAfter(Instant.now()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "线索决策期限已过");
        }
        long owner = eligibleOwner(actor, input.ownerId(), current.campusId(), eventType);
        Long previousOwner = currentOwnerId(current.id());
        updateOwner(current, owner, input.expectedVersion(), pendingDecision);
        event(new EventWrite(current.id(), actor.id(), eventType, previousOwner, owner,
            current.status(), current.status(), input.reason().trim()));
        if (pendingDecision) {
            jdbc.update("""
                INSERT INTO core_crm_lead_decision_event
                    (public_id,lead_id,actor_id,decision,owner_id,decision_deadline,occurred_at)
                VALUES (?,?,?,'transfer',?,?,UTC_TIMESTAMP(3))
                """, "LEADDEC_" + UUID.randomUUID(), current.id(), actor.id(), owner,
                timestamp(current.decisionDeadline()));
        }
        return mutationReceipt(actor,current);
    }
    /** Clears the owner and follow-up time while preserving the previous owner in a release event.
     * @param actor caller authorized for release by the service
     * @param publicId lead public identifier
     * @param input release reason and expected lead version
     * @return successful release projection even when ownership loss removes subsequent read access
     * @throws BusinessException if scope, archived/invalid state, won-order linkage or optimistic version rejects release
     */
    public Lead release(Actor actor, String publicId, LeadRelease input) {
        Row current = leadRow(actor, publicId, true, actor.permissions().contains("lead.release") ? ScopeSql.leadWrites(actor) : ScopeSql.leads(actor));
        if (current.status().equals("archived")) { throw new BusinessException(ErrorCode.INVALID_INPUT, "归档线索不能释放到公海"); }
        if (Set.of("no_need", "invalid_contact").contains(current.status())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "无需求或无效联系方式线索不能释放到公海");
        }
        Integer won = jdbc.queryForObject("""
            SELECT COUNT(o.id) FROM core_opportunity o
            WHERE o.source_lead_id=? AND o.won_order_id IS NOT NULL AND o.deleted=0
            """, Integer.class, current.id());
        if (won != null && won > 0) { throw new BusinessException(ErrorCode.INVALID_INPUT, "已有实收订单的线索不能释放到公海"); }
        Long previousOwner = currentOwnerId(current.id());
        int changed = jdbc.update("""
            UPDATE core_crm_lead SET owner_id=NULL,status='new',next_follow_at=NULL,
                version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND owner_id IS NOT NULL AND version=? AND deleted=0
            """, current.id(), input.expectedVersion());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索已变更或已在公海"); }
        event(new EventWrite(current.id(), actor.id(), "released", previousOwner, null,
            current.status(), "new", input.reason().trim()));
        return mutationReceipt(actor,current);
    }
    /** Reads the original sales stage after applying the existing lead data scope.
     * @param actor caller whose current scope controls visibility
     * @param publicId public lead identifier
     * @return sales stage and current shared lead version
     * @throws BusinessException if the lead is not visible
     */
    public CrmDtos.LeadSalesStage salesStage(Actor actor,String publicId) {
        Row current=leadRow(actor,publicId,false);
        return jdbc.queryForObject("SELECT legacy_stage,intent,legacy_status FROM core_crm_lead WHERE id=?",
            (rs, index) -> new CrmDtos.LeadSalesStage(publicId,rs.getString("legacy_stage"),current.version(),
                rs.getString("intent"),rs.getString("legacy_status")),current.id());
    }
    /** Rechecks owner/decision eligibility before both new writes and idempotent replays. */
    void authorizeSalesStage(Actor actor,String publicId) {
        Row current=leadRow(actor,publicId,true,ScopeSql.leadWrites(actor));
        if("pending".equals(current.decisionStatus())||current.ownerId()==null
                ||!current.ownerId().equals(actor.publicId())&&!actor.permissions().contains("lead.assign")) {
            throw new BusinessException(ErrorCode.FORBIDDEN,"仅负责人或管理员可更新完成决策的线索状态");
        }
    }
    /** Writes an original sales-stage event without changing screening or payment facts.
     * @param actor locked caller with the exact lead.follow capability
     * @param publicId scoped lead identifier
     * @param input requested sales stage, reason and expected version
     * @return the updated sales-stage projection
     * @throws BusinessException if ownership, decision, version or paid-conversion checks fail
     * @throws com.fasterxml.jackson.core.JsonProcessingException if the immutable event cannot be encoded
     */
    public CrmDtos.LeadSalesStage changeSalesStage(Actor actor,String publicId,CrmDtos.LeadSalesStageInput input)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        authorizeSalesStage(actor,publicId);Row current=leadRow(actor,publicId,true,ScopeSql.leadWrites(actor));
        if(current.version()!=input.expectedVersion()) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"记录已更新，请重新读取后操作");
        String oldStage=jdbc.queryForObject("SELECT legacy_stage FROM core_crm_lead WHERE id=?",String.class,current.id());
        // The legacy converted() predicate itself rejects 已报名, before its later redundant transition guard.
        if("已报名".equals(oldStage)||hasPaidConversion(current.id())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"有效报名或付款状态由报名收款自动更新");
        }
        boolean lost = "已流失".equals(input.stage());
        jdbc.update("UPDATE core_crm_lead SET legacy_stage=?,status=?,legacy_status=?,next_follow_at=?,"
            + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?", input.stage(),
            lost ? "archived" : current.status(), lost ? "cold" : "assigned",
            lost ? null : timestamp(current.nextFollowAt()), current.id());
        String reason=input.reason()==null?"":java.text.Normalizer.normalize(input.reason(),java.text.Normalizer.Form.NFKC).trim();
        String detail=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
            "kind","销售状态更新","stage",input.stage(),"reason",reason,"fromStage",oldStage));
        Long owner=currentOwnerId(current.id());
        event(new EventWrite(current.id(),actor.id(),"status",owner,owner,current.status(),lost ? "archived" : current.status(),detail));
        return salesStage(actor,publicId);
    }
    private boolean hasPaidConversion(long leadId) {
        // Lock linked orders so a receipt cannot race this decision; refunded receipts remain historical payments.
        List<java.math.BigDecimal> amounts=jdbc.query("SELECT o.received_amount FROM core_crm_order o "
            +"WHERE o.deleted=0 AND (o.student_id IN (SELECT s.id FROM core_student s WHERE s.origin_lead_id=?) "
            +"OR o.student_id=(SELECT l.student_id FROM core_crm_lead l WHERE l.id=?) "
            +"OR o.opportunity_id IN (SELECT op.id FROM core_opportunity op WHERE op.source_lead_id=?)) ORDER BY o.id FOR UPDATE",
            (rs,n)->rs.getBigDecimal(1),leadId,leadId,leadId);
        return amounts.stream().anyMatch(amount->amount.signum()>0);
    }
    /** Changes only the modern screening status, preserving its reason in an immutable status event.
     * @param actor caller authorized for the modern status operation
     * @param publicId lead public identifier
     * @param input requested screening status, reason, next follow-up and expected version
     * @return lead after the screening transition
     * @throws BusinessException if the lead is outside write scope, restoration is invalid, status is unchanged or version is stale
     */
    public Lead changeStatus(Actor actor, String publicId, LeadStatus input) {
        Row current = leadRow(actor, publicId, true);
        if (current.status().equals("archived") && !input.status().equals("new")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "归档线索只能恢复为新线索");
        }
        if (current.status().equals(input.status())) { throw new BusinessException(ErrorCode.INVALID_INPUT, "线索状态没有变化"); }
        int changed = jdbc.update("""
            UPDATE core_crm_lead SET status=?,next_follow_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, input.status(), timestamp(input.status().equals("archived") ? null : input.nextFollowAt()),
            current.id(), input.expectedVersion());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索版本已变化，请刷新后重试"); }
        Long owner = currentOwnerId(current.id());
        event(new EventWrite(current.id(), actor.id(), "status", owner, owner,
            current.status(), input.status(), input.reason()));
        return lead(actor, publicId, true);
    }
    /** Appends a human interaction and updates the lead's next follow-up in the caller's transaction.
     * @param actor current owner or authorized assignment manager with a service-validated follow grant
     * @param publicId lead public identifier
     * @param input channel, encrypted summary, next follow-up time and expected version
     * @return updated lead; a new screening state advances to contacting
     * @throws BusinessException if the lead is out of write scope, unassigned, archived, owned by another employee or stale
     */
    public Lead followup(Actor actor, String publicId, LeadFollowupInput input) {
        Row current = leadRow(actor, publicId, true, actor.permissions().contains("lead.follow") ? ScopeSql.leadWrites(actor) : ScopeSql.leads(actor));
        validateFollowup(actor,current,input);
        boolean lost = "lost_cold".equals(input.disposition());
        if (input.disposition() != null && "pending".equals(current.decisionStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先完成线索去向决策");
        }
        String stage = jdbc.queryForObject("SELECT legacy_stage FROM core_crm_lead WHERE id=?",String.class,current.id());
        if (Set.of("已流失", "已归档").contains(stage)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "已关闭线索不能继续跟进");
        }
        if (lost && ("已报名".equals(stage) || hasPaidConversion(current.id()))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "有效报名或付款线索不能转入冷宫");
        }
        String nextStatus = lost ? "archived" : current.status().equals("new") ? "contacting" : current.status();
        int changed = jdbc.update("""
            UPDATE core_crm_lead SET status=?,next_follow_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, nextStatus, timestamp(lost ? null : input.nextFollowAt()), current.id(), input.expectedVersion());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索版本已变化，请刷新后重试"); }
        persistDisposition(actor,current,input,new FollowupOutcome(lost,stage,nextStatus));
        jdbc.update("""
            INSERT INTO core_crm_lead_followup
                (public_id,lead_id,actor_id,channel,summary_cipher,occurred_at,next_follow_at,from_status,to_status)
            VALUES (?,?,?,?,?,UTC_TIMESTAMP(3),?,?,?)
            """, "LEADFU_" + UUID.randomUUID(), current.id(), actor.id(), input.channel(), encrypted(input.summary()),
            timestamp(lost ? null : input.nextFollowAt()), current.status(), nextStatus);
        return lead(actor, publicId, true);
    }
    private void validateFollowup(Actor actor,Row current,LeadFollowupInput input) {
        if (current.ownerId() == null) { throw new BusinessException(ErrorCode.INVALID_INPUT, "公海线索须先分配再跟进"); }
        if (!current.ownerId().equals(actor.publicId()) && !actor.permissions().contains("crm.lead.assign")
                && !actor.permissions().contains("lead.assign")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能跟进本人负责的线索");
        }
        if (current.status().equals("archived")) { throw new BusinessException(ErrorCode.INVALID_INPUT, "归档线索不能继续跟进"); }
        if (current.version() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索版本已变化，请刷新后重试");
        }
    }
    /** Locked before-state plus derived disposition; no second transaction or remote operation is introduced. */
    private record FollowupOutcome(boolean lost,String stage,String nextStatus) { }
    private void persistDisposition(Actor actor,Row current,LeadFollowupInput input,FollowupOutcome outcome) {
        boolean lost=outcome.lost();String stage=outcome.stage();String nextStatus=outcome.nextStatus();
        jdbc.update("UPDATE core_crm_lead SET intent=COALESCE(?,intent),legacy_stage=?,legacy_status=?,"
            + "assignment_follow_at=UTC_TIMESTAMP(3),next_action=? WHERE id=?",
            lost ? null : input.disposition(), lost ? "已流失" : Set.of("已报名", "待报名").contains(stage) ? stage : "跟进中",
            lost ? "cold" : "assigned", lost ? "已流失" : input.nextFollowAt() == null ? "未约定" : input.nextFollowAt().toString(),current.id());
        if (input.disposition() != null) {
            Long owner = currentOwnerId(current.id());
            event(new EventWrite(current.id(),actor.id(),"status",owner,owner,current.status(),nextStatus,
                "跟进结果:" + input.disposition()));
        }
    }
    /** Merges lead ownership, screening, interaction, policy and decision event streams.
     * @param actor caller whose lead read relationships are applied before history is loaded
     * @param publicId lead public identifier
     * @return at most 100 events in descending occurrence-time order
     * @throws BusinessException if the lead is missing or outside current read scope
     */
    public List<LeadEvent> events(Actor actor, String publicId) {
        Row lead = leadRow(actor, publicId, false);
        List<LeadEvent> events = new ArrayList<>(jdbc.query("""
            SELECT e.public_id,p.public_id AS actor_public_id,fp.public_id AS from_owner_public_id,
                   tp.public_id AS to_owner_public_id,e.event_type,e.from_status,e.to_status,
                   e.detail_cipher,e.occurred_at
            FROM core_crm_lead_event e JOIN core_principal p ON p.id=e.actor_id
            LEFT JOIN core_principal fp ON fp.id=e.from_owner_id LEFT JOIN core_principal tp ON tp.id=e.to_owner_id
            WHERE e.lead_id=? AND e.deleted=0 ORDER BY e.occurred_at DESC,e.id DESC LIMIT 100
            """, (rs, row) -> new LeadEvent(rs.getString("public_id"), rs.getString("actor_public_id"),
                rs.getString("event_type"), rs.getString("from_owner_public_id"), rs.getString("to_owner_public_id"),
                rs.getString("from_status"), rs.getString("to_status"), decrypt(rs.getString("detail_cipher")),
                rs.getTimestamp("occurred_at").toInstant(), null), lead.id()));
        events.addAll(jdbc.query("""
            SELECT f.public_id,p.public_id AS actor_public_id,f.from_status,f.to_status,
                   f.channel,f.summary_cipher,f.occurred_at
            FROM core_crm_lead_followup f JOIN core_principal p ON p.id=f.actor_id
            WHERE f.lead_id=? AND f.deleted=0 ORDER BY f.occurred_at DESC,f.id DESC LIMIT 100
            """, (rs, row) -> new LeadEvent(rs.getString("public_id"), rs.getString("actor_public_id"),
                "followup", null, null, rs.getString("from_status"), rs.getString("to_status"),
                decrypt(rs.getString("summary_cipher")), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("channel")), lead.id()));
        events.addAll(jdbc.query("""
            SELECT a.public_id,p.public_id AS actor_public_id,a.action_type,a.created_at
            FROM core_crm_lead_policy_action a JOIN core_principal p ON p.id=a.actor_id
            WHERE a.lead_id=? AND a.deleted=0
            ORDER BY a.created_at DESC,a.id DESC LIMIT 100
            """, (rs, row) -> new LeadEvent(rs.getString("public_id"), rs.getString("actor_public_id"),
                "warning".equals(rs.getString("action_type")) ? "policy_warning" : "policy_recycle",
                null, null, null, null, "warning".equals(rs.getString("action_type"))
                    ? "线索策略已提醒负责人" : "线索策略回收并释放至公海",
                rs.getTimestamp("created_at").toInstant(), null), lead.id()));
        events.addAll(jdbc.query("""
            SELECT e.public_id,p.public_id AS actor_public_id,op.public_id AS owner_public_id,
                   e.decision,e.snapshot_cipher,e.occurred_at
            FROM core_crm_lead_decision_event e
            LEFT JOIN core_principal p ON p.id=e.actor_id
            LEFT JOIN core_principal op ON op.id=e.owner_id
            WHERE e.lead_id=? AND e.deleted=0 ORDER BY e.occurred_at DESC,e.id DESC LIMIT 100
            """, (rs, row) -> new LeadEvent(rs.getString("public_id"), rs.getString("actor_public_id"),
                "decision_" + rs.getString("decision"), null, rs.getString("owner_public_id"), null,
                null, decrypt(rs.getString("snapshot_cipher")), rs.getTimestamp("occurred_at").toInstant(), null),
            lead.id()));
        events.sort(java.util.Comparator.comparing(LeadEvent::occurredAt).reversed());
        return events.size() <= 100 ? List.copyOf(events) : List.copyOf(events.subList(0, 100));
    }
    /** Reads only the row already authorized and locked by this mutation, even after its owner changes.
     * This receipt helper is private; ordinary reads and every retry still require current scope.
     */
    private Lead mutationReceipt(Actor actor,Row locked) {
        return dto(actor,leadRow(actor,locked.publicId(),true,new ScopeSql.Clause("l.id=?",List.of(locked.id()))));
    }
    private Lead lead(Actor actor, String publicId, boolean lock) {
        return dto(actor, leadRow(actor, publicId, lock));
    }
    private Row leadRow(Actor actor, String publicId, boolean lock) {
        return leadRow(actor,publicId,lock,lock?ScopeSql.leads(actor):ScopeSql.leadReads(actor));
    }
    private Row leadRow(Actor actor,String publicId,boolean lock,ScopeSql.Clause scope) {
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<Row> rows = jdbc.query("""
            SELECT l.id,l.public_id,c.public_id AS customer_public_id,cp.public_id AS campus_public_id,
                   a.public_id AS activity_public_id,st.public_id AS student_public_id,p.public_id AS owner_public_id,
                   l.source,l.subject,l.term,l.title,l.status,l.detail_cipher,l.next_follow_at,l.version,
                   l.decision_status,dp.public_id AS decision_by_public_id,l.decision_deadline,l.decision_timed_out_at
            FROM core_crm_lead l LEFT JOIN core_customer c ON c.id=l.customer_id
            LEFT JOIN core_campus cp ON cp.id=l.campus_id LEFT JOIN core_activity a ON a.id=l.activity_id
            LEFT JOIN core_student st ON st.id=l.student_id LEFT JOIN core_principal p ON p.id=l.owner_id
            LEFT JOIN core_principal dp ON dp.id=l.decision_by_id
            WHERE l.public_id=? AND l.deleted=0 AND (""" + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> row(rs), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "线索不存在或不在授权范围"); }
        return rows.get(0);
    }
    private Row row(ResultSet rs) throws SQLException {
        Timestamp next = rs.getTimestamp("next_follow_at");
        Timestamp decisionDeadline = rs.getTimestamp("decision_deadline");
        Timestamp timedOutAt = rs.getTimestamp("decision_timed_out_at");
        return new Row(rs.getLong("id"), rs.getString("public_id"), rs.getString("customer_public_id"),
            rs.getString("campus_public_id"), rs.getString("activity_public_id"), rs.getString("student_public_id"),
            rs.getString("owner_public_id"), rs.getString("source"), rs.getString("subject"), rs.getString("term"),
            rs.getString("title"), rs.getString("status"), rs.getString("detail_cipher"),
            next == null ? null : next.toInstant(), rs.getLong("version"), rs.getString("decision_status"),
            rs.getString("decision_by_public_id"), decisionDeadline == null ? null : decisionDeadline.toInstant(),
            timedOutAt == null ? null : timedOutAt.toInstant());
    }
    private Lead dto(Actor actor, Row row) {
        return new Lead(row.publicId(), row.customerId(), row.campusId(),
            CrmLegacyAccess.canRead(actor, "crm.activity.read") || actor.permissions().contains("lead.read")
                ? row.activityId() : null,
            actor.permissions().contains("student.read") || actor.permissions().contains("lead.read")
                ? row.studentId() : null,
            row.ownerId(), row.source(), row.subject(), row.term(), row.title(), row.status(),
            decrypt(row.detailCipher()), row.nextFollowAt(), row.version(), row.decisionStatus(), row.decisionById(),
            row.decisionDeadline(), row.decisionTimedOutAt());
    }
    private void updateOwner(Row row, long owner, long expectedVersion, boolean pendingDecision) {
        String decisionUpdate = pendingDecision ? ",decision_status='transferred'" : "";
        int changed = jdbc.update("""
            UPDATE core_crm_lead SET owner_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            """ + decisionUpdate + " WHERE id=? AND version=? AND deleted=0",
            owner, row.id(), expectedVersion);
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "线索版本已变化，请刷新后重试"); }
    }
    private long eligibleOwner(Actor actor, String publicId, String campusPublicId, String eventType) {
        boolean legacy=actor.permissions().contains("transferred".equals(eventType)?"lead.transfer":"lead.assign");
        String campus="EXISTS(SELECT pc.id FROM core_principal_campus pc JOIN core_campus cp ON cp.id=pc.campus_id"
            +" WHERE pc.principal_id=p.id AND pc.deleted=0 AND cp.deleted=0 AND cp.public_id=?)";
        String eligible="p.active=1 AND p.deleted=0 AND p.archived_at IS NULL AND p.credential_change_required=0";
        List<Object> args=new ArrayList<>(List.of(publicId));
        // Legacy operation permissions select the original Node receiving rules, never a modern-only caller.
        if(legacy) {
            eligible+=" AND "+CrmPrincipalEligibility.permission("lead.follow")
                +" AND (p.scope_type IN ('all','own') OR p.scope_type='campus' AND "+campus+")";
            args.add(campusPublicId);
        } else {
            eligible+=" AND ("+CrmPrincipalEligibility.permission("crm.lead.follow")
                +" OR "+CrmPrincipalEligibility.permission("lead.follow")+") AND (? IS NULL OR "+campus+")";
            args.add(campusPublicId);args.add(campusPublicId);
        }
        List<Long> rows=jdbc.query("SELECT p.id FROM core_principal p WHERE p.public_id=? AND "+eligible+" FOR UPDATE",
            (rs,row)->rs.getLong(1),args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.FORBIDDEN, "负责人未启用线索跟进权限或不属于该校区"); }
        return rows.get(0);
    }
    private long customerId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.customers(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT c.id FROM core_customer c WHERE c.public_id=? AND c.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long studentId(Actor actor, String publicId, boolean lock) {
        // CRM lead references may target an existing, not-yet-enrolled student. The academic
        // students scope is an active-roster filter, so use the profile reference scope instead.
        ScopeSql.Clause scope = ScopeSql.studentWrite(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT s.id FROM core_student s WHERE s.public_id=? AND s.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在授权范围"); }
        return ids.get(0);
    }
    private ActivityRow activityCampus(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.activities(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<ActivityRow> rows = jdbc.query("""
            SELECT a.id,cp.id AS campus_id FROM core_activity a
            LEFT JOIN core_campus cp ON cp.id=a.campus_id WHERE a.public_id=? AND a.deleted=0 AND ("""
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new ActivityRow(rs.getLong("id"), rs.getObject("campus_id", Long.class)), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或不在授权范围"); }
        return rows.get(0);
    }
    private long internalId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_crm_lead WHERE public_id=?", Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "线索创建失败"); }
        return id;
    }
    private Long currentOwnerId(long leadId) {
        return jdbc.queryForObject("SELECT owner_id FROM core_crm_lead WHERE id=?", Long.class, leadId);
    }
    private void event(EventWrite event) {
        jdbc.update("""
            INSERT INTO core_crm_lead_event
                (public_id,lead_id,actor_id,event_type,from_owner_id,to_owner_id,from_status,to_status,occurred_at,detail_cipher)
            VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?)
            """, "LEADEVT_" + UUID.randomUUID(), event.leadId(), event.actorId(), event.type(),
            event.fromOwner(), event.toOwner(), event.fromStatus(), event.toStatus(), encrypted(event.detail()));
    }
    private String encrypted(String value) { return value == null || value.isBlank() ? null : cipher.encrypt(value.trim()); }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    /** Authorized activity identifier and nullable campus; a null campus denotes a global activity. */
    private record ActivityRow(long id, Long campusId) { }
}
