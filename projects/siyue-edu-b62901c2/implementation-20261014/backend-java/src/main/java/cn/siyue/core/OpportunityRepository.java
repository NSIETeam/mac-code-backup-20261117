package cn.siyue.core;

import cn.siyue.core.CrmDtos.Opportunity;
import cn.siyue.core.CrmDtos.OpportunityEvent;
import cn.siyue.core.CrmDtos.OpportunityFollowup;
import cn.siyue.core.CrmDtos.OpportunityInput;
import cn.siyue.core.CrmDtos.OpportunityPage;
import cn.siyue.core.CrmDtos.OpportunityStage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists independent sales needs, follow-up events and versioned stage changes. */
@Repository
public class OpportunityRepository {
    /** 商机关系行投影，说明以密文保留；对外读取再执行关联来源披露。 */
    private record Row(long id, String publicId, String customerId, String sourceLeadId, String campusId, String ownerId,
            String activityId, String studentId, String title, String subject, String term,
            String stage, String detailCipher, Timestamp nextFollowAt, long version) { }
    /** 同一需求键下既有商机的主键、公开标识和阶段，用于选择复用或新建。 */
    private record Existing(long id, String publicId, String stage) { }
    /** 订单前需求创建时核验的来源线索及客户、活动、孩子和状态引用。 */
    private record LeadRef(long id, long customerId, Long campusId, Long activityId,
            String activityPublicId, Long studentId, String studentPublicId, String status) { }
    /** 待追加商机历史事件的类型、阶段变化、渠道和明文内容，持久化前加密。 */
    private record EventDraft(String type, String fromStage, String toStage, String channel, String detail) { }
    /** 已核验客户、校区及可选线索、活动、孩子关联，供商机创建使用。 */
    private record CreateLinks(long customerId,Long campusId,LeadRef sourceLead,Long activityId,Long studentId) { }
    /** 创建商机所需规范学科、学期、需求查重键和授权关联集合。 */
    private record OpportunityDraft(CreateLinks links,String subject,String term,byte[] needKey) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** Uses shared PII encryption for free-form opportunity notes. */
    public OpportunityRepository(JdbcTemplate jdbc, PiiCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    /** Returns the current scoped projection without changing any linked-resource permissions. */
    public Opportunity get(Actor actor,String publicId) { return opportunity(actor,publicId,false); }

    /** Old successful request hashes remain replayable; new classified demand uses explicit review. */
    public void requireLegacyCreateEnabled() {
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity_option WHERE kind='department'",Long.class);
        if(count!=null&&count>0)throw new BusinessException(ErrorCode.INVALID_INPUT,"请通过需求核对入口选择意向部门和项目");
    }

    /** Locks a visible customer so concurrent same-need creation cannot create duplicate active deals. */
    public Opportunity create(Actor actor, OpportunityInput input) {
        CreateLinks links=resolveCreateLinks(actor,input,true);
        String subject = input.subject().trim();
        String term = input.term().trim();
        byte[] needKey = needKey(links.studentId(),subject,term);
        Existing active=findActiveNeed(links.customerId(),needKey);
        if(active!=null)return appendRepeatedNeed(actor,input,active);
        return insertOpportunity(actor,input,new OpportunityDraft(links,subject,term,needKey));
    }

    private CreateLinks resolveCreateLinks(Actor actor,OpportunityInput input,boolean lock) {
        long customer=customerId(actor,input.customerId(),lock);
        LeadRef source=sourceLead(actor,input,customer,lock);
        Long campus=jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id = ?",Long.class,customer);
        if(source!=null&&!java.util.Objects.equals(campus,source.campusId()))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"来源线索与客户校区不一致");
        Long activity=resolveActivity(actor,input,source,campus);
        Long student=resolveStudent(actor,input,source,customer);
        return new CreateLinks(customer,campus,source,activity,student);
    }

    private Long resolveActivity(Actor actor,OpportunityInput input,LeadRef source,Long campus) {
        Long activity=source==null?null:source.activityId();
        if(activity!=null) {
            if(source.activityPublicId()==null)
                throw new BusinessException(ErrorCode.NOT_FOUND,"来源线索活动不存在或已归档");
            if(input.activityId()!=null&&!input.activityId().isBlank()
                    &&!input.activityId().equals(source.activityPublicId()))
                throw new BusinessException(ErrorCode.INVALID_INPUT,"商机活动须与来源线索一致");
        } else if(input.activityId()!=null&&!input.activityId().isBlank()) {
            Long requested=activityId(actor,input.activityId(),false);
            activity=requested;
        }
        if(activity!=null)validateActivityCampus(activity,campus);
        return activity;
    }

    private void validateActivityCampus(long activity,Long campus) {
        Long activityCampus=jdbc.queryForObject("SELECT campus_id FROM core_activity WHERE id = ?",Long.class,activity);
        if(!java.util.Objects.equals(campus,activityCampus))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"客户与获客活动必须属于同一校区");
    }

    private Long resolveStudent(Actor actor,OpportunityInput input,LeadRef source,long customer) {
        Long student=source==null?null:source.studentId();
        if(student!=null) {
            if(source.studentPublicId()==null)
                throw new BusinessException(ErrorCode.NOT_FOUND,"来源线索学生不存在或已归档");
            if(input.studentId()!=null&&!input.studentId().isBlank()
                    &&!input.studentId().equals(source.studentPublicId()))
                throw new BusinessException(ErrorCode.INVALID_INPUT,"商机学生须与来源线索一致");
        } else if(input.studentId()!=null&&!input.studentId().isBlank()) {
            Long requested=studentId(actor,input.studentId());
            student=requested;
        }
        if(student!=null)requireCustomerStudent(customer,student,"商机学生尚未关联到该客户档案");
        return student;
    }

    private byte[] needKey(Long studentId,String subject,String term) {
        String studentKey=studentId==null?"household":Long.toString(studentId);
        return digest((studentKey+"\n"+subject.toLowerCase(java.util.Locale.ROOT)+"\n"
            +term.toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    private Existing findActiveNeed(long customerId,byte[] needKey) {
        List<Existing> active = jdbc.query("""
            SELECT id,public_id,stage FROM core_opportunity
            WHERE customer_id = ? AND need_key = ? AND deleted = 0
              AND stage IN ('qualified','appointment','trial','negotiation')
            ORDER BY id LIMIT 1 FOR UPDATE
            """, (rs, row) -> new Existing(rs.getLong("id"), rs.getString("public_id"), rs.getString("stage")), customerId, needKey);
        return active.isEmpty()?null:active.get(0);
    }

    private Opportunity appendRepeatedNeed(Actor actor,OpportunityInput input,Existing existing) {
        String followup=blankToNull(input.detail());
        jdbc.update("UPDATE core_opportunity SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND deleted=0",existing.id());
        insertEvent(existing.id(),actor,new EventDraft("followup",existing.stage(),existing.stage(),"other",
            followup==null?"客户再次咨询同一购买需求":followup));
        return opportunity(actor,existing.publicId(),false);
    }

    private Opportunity insertOpportunity(Actor actor,OpportunityInput input,OpportunityDraft draft) {
        CreateLinks links=draft.links();
        String subject=draft.subject(),term=draft.term();
        String publicId = "OPP_" + UUID.randomUUID();
        String title = input.title().trim();
        String detail = blankToNull(input.detail());
        jdbc.update("""
            INSERT INTO core_opportunity
                (public_id,customer_id,source_lead_id,campus_id,owner_id,activity_id,student_id,title,subject,term,need_key,stage,detail_cipher,source_created_by_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'qualified',?,?)
            """, publicId, links.customerId(), links.sourceLead() == null ? null : links.sourceLead().id(), links.campusId(),
            actor.id(), links.activityId(), links.studentId(), title, subject,
            term, draft.needKey(), detail == null ? null : cipher.encrypt(detail),actor.id());
        long id = internalId(publicId);
        insertEvent(id, actor, new EventDraft("created", null, "qualified", null, detail));
        return opportunity(actor, publicId, false);
    }

    /** Revalidates all linked records before returning an idempotent replay. */
    public void authorizeInput(Actor actor, OpportunityInput input) {
        resolveCreateLinks(actor,input,false);
    }

    /** Lists a single consistent owner/campus scope with optional exact filters. */
    public OpportunityPage list(Actor actor, String customerPublicId, String stage, int page, int size) {
        return list(actor, new OpportunityListFilters(customerPublicId, stage, page, size), null);
    }
    /** Applies imported saved-view search and update ordering within the existing opportunity scope. */
    public OpportunityPage list(Actor actor, OpportunityListFilters query, LegacySavedViewQuery savedView) {
        String customerPublicId = query.customerId();
        String stage = query.stage();
        int page = query.page();
        int size = query.size();
        ScopeSql.Clause scope = ScopeSql.opportunityReads(actor);
        List<Object> filterArgs = new ArrayList<>();
        ScopeSql.Clause activityScope = CrmLegacyAccess.canRead(actor, "crm.activity.read")
            ? ScopeSql.activities(actor) : new ScopeSql.Clause("1=0", List.of());
        ScopeSql.Clause studentScope = actor.permissions().contains("student.read")
            ? ScopeSql.students(actor, null) : new ScopeSql.Clause("1=0", List.of());
        ScopeSql.Clause leadScope = CrmLegacyAccess.canRead(actor, "crm.lead.read")
            ? ScopeSql.leadReads(actor) : new ScopeSql.Clause("1=0", List.of());
        String filter = "";
        if (customerPublicId != null && !customerPublicId.isBlank()) {
            long customerRowId = customerId(actor, customerPublicId, false);
            filter += " AND o.customer_id = ?";
            filterArgs.add(customerRowId);
        }
        if (stage != null && !stage.isBlank()) { filter += " AND o.stage = ?"; filterArgs.add(stage); }
        if (savedView != null && savedView.searchPattern() != null) {
            String search = "(LOWER(CAST(o.public_id AS CHAR)) LIKE ? ESCAPE '!'"
                + " OR LOWER(o.title) LIKE ? ESCAPE '!' OR LOWER(o.subject) LIKE ? ESCAPE '!'"
                + " OR LOWER(o.term) LIKE ? ESCAPE '!'";
            for (int index = 0; index < 4; index++) { filterArgs.add(savedView.searchPattern()); }
            if (CrmLegacyAccess.canRead(actor, "crm.customer.read")) {
                search += SavedViewCustomerSearch.addVisibleIds("o.customer_id",
                    SavedViewCustomerSearch.matchingIds(jdbc, cipher, actor, savedView.search()), filterArgs);
            }
            filter += " AND " + search + ")";
        }
        String where = "o.deleted = 0 AND (" + scope.sql() + ")" + filter;
        List<Object> countArgs = new ArrayList<>(scope.args());
        countArgs.addAll(filterArgs);
        Long total = jdbc.queryForObject("SELECT COUNT(o.id) FROM core_opportunity o WHERE " + where,
            Long.class, countArgs.toArray());
        // Link-scope binds precede the opportunity WHERE clause in the SQL text.
        List<Object> pageArgs = new ArrayList<>(activityScope.args());
        pageArgs.addAll(studentScope.args());
        pageArgs.addAll(leadScope.args());
        pageArgs.addAll(scope.args());
        pageArgs.addAll(filterArgs);
        pageArgs.add(size);
        pageArgs.add(Math.multiplyExact((long) page - 1, size));
        String order = savedView != null && savedView.present()
            ? " ORDER BY o.updated_at DESC,o.id DESC" : " ORDER BY COALESCE(o.next_follow_at,o.updated_at),o.id";
        String selectSql = selectWithVisibleLinks(activityScope, studentScope, leadScope)
            + " WHERE " + where + order + " LIMIT ? OFFSET ?";
        List<Row> rows = jdbc.query(selectSql,
            (rs, row) -> row(rs), pageArgs.toArray());
        List<Opportunity> items = rows.stream().map(row -> dto(actor, row)).toList();
        return new OpportunityPage(items, total == null ? 0 : total, page, size);
    }

    /** Reads the latest immutable timeline only after current opportunity scope is verified. */
    public List<OpportunityEvent> events(Actor actor, String opportunityPublicId) {
        long opportunityId = opportunityId(actor, opportunityPublicId, false);
        return jdbc.query("""
            SELECT e.public_id,p.public_id AS actor_public_id,e.event_type,e.from_stage,e.to_stage,
                   e.channel,e.detail_cipher,e.occurred_at
            FROM core_opportunity_event e JOIN core_principal p ON p.id=e.actor_id
            WHERE e.opportunity_id=? AND e.deleted=0
            ORDER BY e.occurred_at DESC,e.id DESC LIMIT 100
            """, (rs, row) -> new OpportunityEvent(rs.getString("public_id"),
                rs.getString("actor_public_id"), rs.getString("event_type"), rs.getString("from_stage"),
                rs.getString("to_stage"), rs.getString("channel"),
                rs.getString("detail_cipher") == null ? null : cipher.decrypt(rs.getString("detail_cipher")),
                rs.getTimestamp("occurred_at").toInstant()), opportunityId);
    }

    /** Appends encrypted follow-up text and saves the next action atomically. */
    public Opportunity followup(Actor actor, String publicId, OpportunityFollowup input) {
        long id = opportunityId(actor, publicId, true);
        Row before = rowById(actor, id);
        if (before.version() != input.expectedVersion()) { throw versionConflict(); }
        Instant next = input.nextFollowAt();
        if (next != null && !next.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "下次跟进时间须晚于当前时间");
        }
        int changed = jdbc.update("""
            UPDATE core_opportunity SET next_follow_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND stage IN ('qualified','appointment','trial','negotiation') AND deleted=0
            """, next == null ? null : Timestamp.from(next), id, input.expectedVersion());
        if (changed != 1) { throw versionConflict(); }
        insertEvent(id, actor, new EventDraft("followup", before.stage(), before.stage(), input.channel(), input.detail().trim()));
        return opportunity(actor, publicId, false);
    }

    /** Applies a stage change with optimistic locking; won is intentionally receipt-only. */
    public Opportunity advance(Actor actor, String publicId, OpportunityStage input) {
        opportunityId(actor,publicId,false);
        jdbc.queryForObject("SELECT id FROM core_customer WHERE id=? FOR UPDATE",Long.class,customerInternalId(publicId));
        long id = opportunityId(actor, publicId, true);
        Row before = rowById(actor, id);
        validateStageChange(before,input);
        String reason = blankToNull(input.reason());
        validateCloseReason(input,reason);
        Instant next = input.stage().equals("closed") ? null : input.nextFollowAt();
        validateNextFollow(next);
        if(before.stage().equals("closed"))ensureNoDuplicateOnReopen(publicId,id);
        int changed = jdbc.update("""
            UPDATE core_opportunity SET stage=?,next_follow_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0 AND stage<>'won'
            """, input.stage(), next == null ? null : Timestamp.from(next), id, input.expectedVersion());
        if (changed != 1) { throw versionConflict(); }
        insertEvent(id, actor, new EventDraft("stage", before.stage(), input.stage(), null, reason));
        return opportunity(actor, publicId, false);
    }

    private void validateStageChange(Row before,OpportunityStage input) {
        if (before.version() != input.expectedVersion()) { throw versionConflict(); }
        if (before.stage().equals("won"))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "成交阶段只能由实际收款确认");
        if (before.stage().equals(input.stage()))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "商机阶段没有变化");
        if (before.stage().equals("closed") && !input.stage().equals("qualified"))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "已关闭商机只能重新开启到需求确认阶段");
    }

    private void validateCloseReason(OpportunityStage input,String reason) {
        if (input.stage().equals("closed") && reason == null)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "关闭商机需要填写原因");
    }

    private void validateNextFollow(Instant next) {
        if (next != null && !next.isAfter(Instant.now()))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "下次跟进时间须晚于当前时间");
    }

    private void ensureNoDuplicateOnReopen(String publicId,long id) {
        Integer duplicate = jdbc.queryForObject("""
            SELECT COUNT(o.id) FROM core_opportunity o JOIN core_opportunity source ON source.id=?
            WHERE o.customer_id=source.customer_id AND o.id<>source.id AND o.deleted=0
              AND o.stage IN ('qualified','appointment','trial','negotiation')
              AND ((source.need_version=2 AND o.need_version=2 AND o.need_key_v2=source.need_key_v2)
                OR (source.need_version=1 AND o.need_key=source.need_key))
            """, Integer.class, id);
        if (duplicate != null && duplicate > 0)
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "同一需求已有进行中的商机，不能重复开启");
    }

    /** Checks opportunity visibility before replay; creates and reads in transactional service calls. */
    public void authorize(Actor actor, String publicId) { opportunityId(actor, publicId, true); }
    private Opportunity opportunity(Actor actor, String publicId, boolean lock) {
        long id = opportunityId(actor, publicId, lock);
        return dto(actor, rowById(actor, id));
    }
    private Row rowById(Actor actor, long id) {
        ScopeSql.Clause activityScope = CrmLegacyAccess.canRead(actor, "crm.activity.read")
            ? ScopeSql.activities(actor) : new ScopeSql.Clause("1=0", List.of());
        ScopeSql.Clause studentScope = actor.permissions().contains("student.read")
            ? ScopeSql.students(actor, null) : new ScopeSql.Clause("1=0", List.of());
        ScopeSql.Clause leadScope = CrmLegacyAccess.canRead(actor, "crm.lead.read")
            ? ScopeSql.leadReads(actor) : new ScopeSql.Clause("1=0", List.of());
        List<Object> args = new ArrayList<>(activityScope.args());
        args.addAll(studentScope.args());
        args.addAll(leadScope.args());
        args.add(id);
        String selectSql = selectWithVisibleLinks(activityScope, studentScope, leadScope)
            + " WHERE o.id=? AND o.deleted=0";
        List<Row> rows = jdbc.query(selectSql, (rs, row) -> row(rs), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "商机不存在"); }
        return rows.get(0);
    }
    private String selectWithVisibleLinks(ScopeSql.Clause activityScope, ScopeSql.Clause studentScope,
            ScopeSql.Clause leadScope) {
        return "SELECT o.id,o.public_id,c.public_id AS customer_public_id,cp.public_id AS campus_public_id,"
            + "p.public_id AS owner_public_id,"
            + "(SELECT a.public_id FROM core_activity a WHERE a.id=o.activity_id AND a.deleted=0 AND ("
            + activityScope.sql() + ")) AS activity_public_id,"
            + "(SELECT s.public_id FROM core_student s WHERE s.id=o.student_id AND ("
            + studentScope.sql() + ")) AS student_public_id,"
            + "(SELECT l.public_id FROM core_crm_lead l WHERE l.id=o.source_lead_id AND l.deleted=0 AND ("
            + leadScope.sql() + ")) AS source_lead_public_id,"
            + "o.title,o.subject,o.term,o.stage,o.detail_cipher,o.next_follow_at,o.version "
            + "FROM core_opportunity o JOIN core_customer c ON c.id=o.customer_id "
            + "LEFT JOIN core_campus cp ON cp.id=o.campus_id JOIN core_principal p ON p.id=o.owner_id";
    }
    private Opportunity dto(Actor actor, Row row) {
        return new Opportunity(row.publicId(), row.customerId(),
            CrmLegacyAccess.canRead(actor, "crm.lead.read") ? row.sourceLeadId() : null, row.campusId(), row.ownerId(),
            row.activityId(), row.studentId(), row.title(), row.subject(), row.term(), row.stage(),
            row.detailCipher() == null ? null : cipher.decrypt(row.detailCipher()),
            row.nextFollowAt() == null ? null : row.nextFollowAt().toInstant(), row.version());
    }
    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getString("public_id"), rs.getString("customer_public_id"),
            rs.getString("source_lead_public_id"), rs.getString("campus_public_id"),
            rs.getString("owner_public_id"), rs.getString("activity_public_id"),
            rs.getString("student_public_id"), rs.getString("title"), rs.getString("subject"),
            rs.getString("term"), rs.getString("stage"), rs.getString("detail_cipher"),
            rs.getTimestamp("next_follow_at"), rs.getLong("version"));
    }
    private ScopeSql.Clause opportunityScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("o.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    private long customerId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.customerReads(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT c.id FROM core_customer c WHERE c.public_id=? AND c.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围"); }
        return ids.get(0);
    }
    private LeadRef sourceLead(Actor actor, OpportunityInput input, long customerId, boolean lock) {
        if (input.sourceLeadId() == null || input.sourceLeadId().isBlank()) { return null; }
        ScopeSql.Clause scope = ScopeSql.leadReads(actor);
        List<Object> args = new ArrayList<>(List.of(input.sourceLeadId())); args.addAll(scope.args());
        List<LeadRef> rows = jdbc.query("""
            SELECT l.id,l.customer_id,l.campus_id,l.activity_id,a.public_id AS activity_public_id,
                   l.student_id,s.public_id AS student_public_id,l.status
            FROM core_crm_lead l LEFT JOIN core_activity a ON a.id=l.activity_id AND a.deleted=0
            LEFT JOIN core_student s ON s.id=l.student_id AND s.deleted=0
            WHERE l.public_id=? AND l.deleted=0 AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new LeadRef(rs.getLong("id"), rs.getLong("customer_id"),
                rs.getObject("campus_id", Long.class), rs.getObject("activity_id", Long.class),
                rs.getString("activity_public_id"), rs.getObject("student_id", Long.class),
                rs.getString("student_public_id"), rs.getString("status")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "来源线索不存在或不在授权范围"); }
        LeadRef lead = rows.get(0);
        validateSourceLead(customerId,lead);
        return lead;
    }

    private void validateSourceLead(long customerId,LeadRef lead) {
        if (lead.customerId() != customerId) { throw new BusinessException(ErrorCode.INVALID_INPUT, "来源线索与商机客户不一致"); }
        if (!lead.status().equals("qualified")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "须先完成线索初筛，再建立商机");
        }
    }
    private void requireCustomerStudent(long customerId, long studentId, String message) {
        Integer linked = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_customer_student
            WHERE customer_id=? AND student_id=? AND deleted=0
            """, Integer.class, customerId, studentId);
        if (linked == null || linked == 0) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
    }
    private long activityId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.activities(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT a.id FROM core_activity a WHERE a.public_id=? AND a.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long studentId(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.students(actor, null);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT s.id FROM core_student s WHERE s.public_id=? AND s.deleted=0 AND ("
            + scope.sql() + ")", (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long opportunityId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = lock?opportunityScope(actor):ScopeSql.opportunityReads(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT o.id FROM core_opportunity o WHERE o.public_id=? AND o.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "商机不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long customerInternalId(String publicId) {
        Long id = jdbc.queryForObject("SELECT customer_id FROM core_opportunity WHERE public_id=?", Long.class, publicId);
        return id == null ? 0 : id;
    }
    private long internalId(String publicId) {
        Long id = jdbc.queryForObject("SELECT id FROM core_opportunity WHERE public_id=?", Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "商机创建失败"); }
        return id;
    }
    private void insertEvent(long id, Actor actor, EventDraft event) {
        jdbc.update("""
            INSERT INTO core_opportunity_event
                (public_id,opportunity_id,actor_id,event_type,from_stage,to_stage,channel,occurred_at,detail_cipher)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, "OPPEVT_" + UUID.randomUUID(), id, actor.id(), event.type(), event.fromStage(),
            event.toStage(), event.channel(), Timestamp.from(Instant.now()),
            event.detail() == null ? null : cipher.encrypt(event.detail()));
    }
    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static BusinessException versionConflict() {
        return new BusinessException(ErrorCode.ALREADY_EXISTS, "商机版本已变化或当前状态不允许此操作");
    }
}
