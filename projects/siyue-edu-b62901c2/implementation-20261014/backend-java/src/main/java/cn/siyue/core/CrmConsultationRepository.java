package cn.siyue.core;

import cn.siyue.core.ConsultationDtos.Consultation;
import cn.siyue.core.ConsultationDtos.History;
import cn.siyue.core.ConsultationDtos.Input;
import cn.siyue.core.ConsultationDtos.Notice;
import cn.siyue.core.ConsultationDtos.NoticePage;
import cn.siyue.core.ConsultationDtos.NoticeQuery;
import cn.siyue.core.ConsultationDtos.Page;
import cn.siyue.core.ConsultationDtos.Query;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists immutable CRM consultation facts, their timeline touchpoint and recipient-only notices. */
@Repository
public class CrmConsultationRepository {
    /** Scoped customer identity and campus used to validate and title a consultation. */
    private record CustomerRef(long id, Long campusId, String publicId, String nameCipher) { }
    /** Authorized campaign or opportunity relation, including customer, campus, owner and current state. */
    private record LinkRef(long id,String publicId,long customerId,Long campusId,Long ownerId,String status) { }
    /** Consultation text and occurrence time reused when appending an opportunity interaction or recipient notice. */
    private record FollowupDraft(CustomerRef customer,String consultationId,String channel,String note,
            Instant nextFollowAt,Instant now) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** Wires consultation facts, handoff notices and encrypted descriptive fields.
     * @param jdbc database operations joining the consultation service transaction
     * @param cipher keyring for consultation notes, titles and recipient notice text
     */
    public CrmConsultationRepository(JdbcTemplate jdbc,PiiCipher cipher) { this.jdbc=jdbc;this.cipher=cipher; }

    /** Checks the source customer, optional campus and campaign/opportunity links before mutation replay.
     * @param actor caller whose customer and linked-resource scopes apply
     * @param input proposed consultation and its referenced customer, campaign and opportunity
     * @throws BusinessException if a reference is unavailable, out of scope or inconsistent with the customer/campus
     */
    public void authorizeInput(Actor actor,Input input) {
        customer(actor,input.customerId(),false);
        validateCampus(actor,input.campusId(),input.customerId());
        if(input.campaignId()!=null&&!input.campaignId().isBlank())campaign(actor,input.campaignId(),input.customerId(),false);
        if(input.opportunityId()!=null&&!input.opportunityId().isBlank())opportunity(actor,input.opportunityId(),input.customerId(),false);
    }

    /** Writes a consultation, customer touchpoint and history, then any linked opportunity handoff facts.
     * @param actor caller already authorized for registration by the service
     * @param input validated customer, channel, note, optional links and next follow-up time
     * @return persisted consultation including its immutable history
     * @throws BusinessException if locked customer, campus or linked-resource consistency checks fail
     */
    public Consultation register(Actor actor,Input input) {
        CustomerRef customer=customer(actor,input.customerId(),true);
        Long campusId=validateCampus(actor,input.campusId(),input.customerId());
        LinkRef campaign=input.campaignId()==null||input.campaignId().isBlank()?null:campaign(actor,input.campaignId(),input.customerId(),true);
        LinkRef opportunity=input.opportunityId()==null||input.opportunityId().isBlank()?null:opportunity(actor,input.opportunityId(),input.customerId(),true);
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String consultationId="CONSULT_"+UUID.randomUUID();
        String touchpointId="TOUCH_"+UUID.randomUUID();
        String note=input.note().trim();
        String channel=input.channel();
        jdbc.update("""
            INSERT INTO core_customer_touchpoint
                (public_id,customer_id,activity_id,student_id,actor_id,source,channel,summary_cipher,occurred_at)
            VALUES (?,?,?,NULL,?,'other',?,?,?)
            """,touchpointId,customer.id(),campaign==null?null:campaign.id(),actor.id(),touchpointChannel(channel),
            cipher.encrypt(note),Timestamp.from(now));
        jdbc.update("""
            INSERT INTO core_crm_consultation
                (public_id,source_audit_event_id,customer_id,campus_id,owner_id,created_by,activity_id,
                 opportunity_id,touchpoint_id,title_cipher,customer_name_cipher,campus_name_cipher,owner_name_cipher,
                 channel,note_cipher,source_profile_cipher,next_follow_at,occurred_at,status,version,created_at,updated_at)
            VALUES (?,NULL,?,?,?,?,?,?,?,?,?,NULL,NULL,?,?,?, ?,?,'recorded',1,?,?)
            """,consultationId,customer.id(),campusId,actor.id(),actor.id(),campaign==null?null:campaign.id(),
            opportunity==null?null:opportunity.id(),internalId("core_customer_touchpoint",touchpointId),
            cipher.encrypt(cipher.decrypt(customer.nameCipher())+" · 咨询"),customer.nameCipher(),channel,cipher.encrypt(note),cipher.encrypt("{}"),
            input.nextFollowAt()==null?null:Timestamp.from(input.nextFollowAt()),Timestamp.from(now),
            Timestamp.from(now),Timestamp.from(now));
        long consultationRowId=internalId("core_crm_consultation",consultationId);
        jdbc.update("""
            INSERT INTO core_crm_consultation_history
                (public_id,consultation_id,actor_id,source_kind,detail_cipher,actor_name_cipher,occurred_at,source_audit_event_id,created_at,updated_at)
            VALUES (?,?,?,'registered',?,NULL,?,NULL,?,?)
            """, "CONSHIST_"+UUID.randomUUID(),consultationRowId,actor.id(),cipher.encrypt(note),
            Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
        if(opportunity!=null)appendOpportunityFollowup(actor,opportunity,
            new FollowupDraft(customer,consultationId,channel,note,input.nextFollowAt(),now));
        return consultation(actor,consultationId,false);
    }

    private void appendOpportunityFollowup(Actor actor,LinkRef ref,FollowupDraft draft) {
        String stage=jdbc.queryForObject("SELECT stage FROM core_opportunity WHERE id=? AND deleted=0 FOR UPDATE",String.class,ref.id());
        if(draft.nextFollowAt()!=null&&List.of("qualified","appointment","trial","negotiation").contains(stage)
                &&canWriteOpportunity(actor,ref)) {
            jdbc.update("UPDATE core_opportunity SET next_follow_at=?,version=version+1,updated_at=? WHERE id=?",
                Timestamp.from(draft.nextFollowAt()),Timestamp.from(draft.now()),ref.id());
        }
        jdbc.update("""
            INSERT INTO core_opportunity_event(public_id,opportunity_id,actor_id,event_type,from_stage,to_stage,channel,occurred_at,detail_cipher)
            VALUES (?,?,?,'followup',?,?,?, ?,?)
            """, "OPPEVT_"+UUID.randomUUID(),ref.id(),actor.id(),stage,stage,opportunityChannel(draft.channel()),
            Timestamp.from(draft.now()),cipher.encrypt("新增接待咨询: "+draft.note()));
        if(!java.util.Objects.equals(ref.ownerId(),actor.id())) {
            jdbc.update("""
                INSERT INTO core_crm_consultation_notice
                    (public_id,consultation_id,opportunity_id,customer_id,recipient_id,title_cipher,text_cipher)
                VALUES (?,?,?,?,?,?,?)
                """, "NOTICE_"+UUID.randomUUID(),internalId("core_crm_consultation",draft.consultationId()),ref.id(),
                ref.customerId(),ref.ownerId(),cipher.encrypt("接待咨询交接"),
                cipher.encrypt(cipher.decrypt(draft.customer().nameCipher())+" 有新的咨询记录"));
        }
    }

    private boolean canWriteOpportunity(Actor actor,LinkRef opportunity) {
        if(!actor.permissions().contains("crm.opportunity.write")&&!actor.permissions().contains("lead.follow"))return false;
        if(actor.scope().equals("all"))return true;
        if(actor.scope().equals("campus"))return opportunity.campusId()!=null&&actor.campusIds().contains(opportunity.campusId());
        return java.util.Objects.equals(opportunity.ownerId(),actor.id());
    }

    /** Lists consultation facts visible by ownership, creation or current customer read relationships.
     * @param actor caller already authorized for consultation reading by the service
     * @param query validated mine/scope selection, resource/status/date filters and pagination
     * @return matching consultation rows with their histories and total
     * @throws BusinessException if the caller's customer read scope is unsupported
     */
    public Page list(Actor actor,Query query) {
        ScopeSql.Clause customerScope=ScopeSql.customerReads(actor);
        List<Object> args=new ArrayList<>();
        String where="x.deleted=0 AND (x.owner_id=? OR x.created_by=? OR EXISTS (SELECT c.id FROM core_customer c WHERE c.id=x.customer_id AND c.deleted=0 AND ("
            +customerScope.sql()+")))";
        args.add(actor.id());args.add(actor.id());args.addAll(customerScope.args());
        if("mine".equals(query.scope())){where+=" AND (x.owner_id=? OR x.created_by=?)";args.add(actor.id());args.add(actor.id());}
        if(query.customerId()!=null&&!query.customerId().isBlank()){where+=" AND cu.public_id=?";args.add(query.customerId());}
        if(query.campaignId()!=null&&!query.campaignId().isBlank()){where+=" AND ac.public_id=?";args.add(query.campaignId());}
        if(query.ownerId()!=null&&!query.ownerId().isBlank()){where+=" AND ow.public_id=?";args.add(query.ownerId());}
        if(query.status()!=null&&!query.status().isBlank()){where+=" AND x.status=?";args.add(query.status());}
        if(query.from()!=null){where+=" AND x.occurred_at>=?";args.add(Timestamp.from(query.from()));}
        if(query.to()!=null){where+=" AND x.occurred_at<=?";args.add(Timestamp.from(query.to()));}
        Long total=jdbc.queryForObject("SELECT COUNT(x.id) FROM core_crm_consultation x JOIN core_customer cu ON cu.id=x.customer_id LEFT JOIN core_activity ac ON ac.id=x.activity_id LEFT JOIN core_principal ow ON ow.id=x.owner_id WHERE "+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(query.pageSize());pageArgs.add(Math.multiplyExact((long)query.page()-1,query.pageSize()));
        List<Consultation> items=jdbc.query(selectSql()+" WHERE "+where+" ORDER BY x.occurred_at DESC,x.id DESC LIMIT ? OFFSET ?",this::map,pageArgs.toArray());
        return new Page(items,total==null?0:total,query.page(),query.pageSize());
    }

    /** Reads one consultation through owner, creator or current customer read visibility.
     * @param actor caller whose consultation visibility is applied
     * @param publicId consultation public identifier
     * @param lock whether the caller's transaction must lock the selected row
     * @return consultation and chronological persisted history
     * @throws BusinessException if the consultation is missing or outside read scope
     */
    public Consultation get(Actor actor,String publicId,boolean lock) { return consultation(actor,publicId,lock); }

    private Consultation consultation(Actor actor,String publicId,boolean lock) {
        ScopeSql.Clause customerScope=ScopeSql.customerReads(actor);
        List<Object> args=new ArrayList<>(List.of(publicId,actor.id(),actor.id()));args.addAll(customerScope.args());
        List<Consultation> rows=jdbc.query(selectSql()+" WHERE x.public_id=? AND x.deleted=0 AND (x.owner_id=? OR x.created_by=? OR EXISTS (SELECT c.id FROM core_customer c WHERE c.id=x.customer_id AND c.deleted=0 AND ("+customerScope.sql()+")))"+(lock?" FOR UPDATE":""),this::map,args.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"咨询不存在或不在授权范围");
        return rows.get(0);
    }

    private String selectSql() { return """
        SELECT x.id,x.public_id,cu.public_id AS customer_id,cp.public_id AS campus_id,ow.public_id AS owner_id,
               x.title_cipher,x.channel,x.note_cipher,ac.public_id AS campaign_id,op.public_id AS opportunity_id,
               tp.public_id AS touchpoint_id,x.next_follow_at,x.occurred_at,x.status,x.version
        FROM core_crm_consultation x JOIN core_customer cu ON cu.id=x.customer_id
        LEFT JOIN core_campus cp ON cp.id=x.campus_id LEFT JOIN core_principal ow ON ow.id=x.owner_id
        LEFT JOIN core_activity ac ON ac.id=x.activity_id LEFT JOIN core_opportunity op ON op.id=x.opportunity_id
        LEFT JOIN core_customer_touchpoint tp ON tp.id=x.touchpoint_id
        """; }

    private Consultation map(ResultSet rs,int row)throws SQLException {
        long id=rs.getLong("id");String publicId=rs.getString("public_id");
        return new Consultation(publicId,rs.getString("customer_id"),rs.getString("campus_id"),rs.getString("owner_id"),
            cipher.decrypt(rs.getString("title_cipher")),rs.getString("channel"),cipher.decrypt(rs.getString("note_cipher")),
            rs.getString("campaign_id"),rs.getString("opportunity_id"),rs.getString("touchpoint_id"),
            instant(rs.getTimestamp("next_follow_at")),instant(rs.getTimestamp("occurred_at")),rs.getString("status"),
            rs.getLong("version"),history(id));
    }

    private List<History> history(long id) {
        return jdbc.query("""
            SELECT h.public_id,p.public_id AS actor_id,h.source_kind,h.detail_cipher,h.occurred_at
            FROM core_crm_consultation_history h JOIN core_principal p ON p.id=h.actor_id
            WHERE h.consultation_id=? AND h.deleted=0 ORDER BY h.occurred_at,h.id
            """,(rs,row)->new History(rs.getString("public_id"),rs.getString("actor_id"),rs.getString("source_kind"),
                rs.getString("detail_cipher")==null?null:cipher.decrypt(rs.getString("detail_cipher")),instant(rs.getTimestamp("occurred_at"))),id);
    }

    /** Lists only notices addressed to this caller, regardless of broader CRM data scope.
     * @param actor authenticated notice recipient
     * @param query validated read/unread selection and pagination
     * @return matching recipient-only notices and total, newest first
     */
    public NoticePage notices(Actor actor,NoticeQuery query) {
        String status=switch(query.status()){case "unread"->" AND n.read_at IS NULL";case "read"->" AND n.read_at IS NOT NULL";default->"";};
        Long total=jdbc.queryForObject("SELECT COUNT(n.id) FROM core_crm_consultation_notice n WHERE n.recipient_id=? AND n.deleted=0"+status,Long.class,actor.id());
        List<Notice> items=jdbc.query("""
            SELECT n.public_id,o.public_id AS opportunity_id,c.public_id AS consultation_id,n.title_cipher,n.text_cipher,n.created_at,n.read_at,n.version
            FROM core_crm_consultation_notice n JOIN core_opportunity o ON o.id=n.opportunity_id
            JOIN core_crm_consultation c ON c.id=n.consultation_id
            WHERE n.recipient_id=? AND n.deleted=0
            """+status+" ORDER BY n.id DESC LIMIT ? OFFSET ?",(rs,row)->notice(rs),actor.id(),query.pageSize(),Math.multiplyExact((long)query.page()-1,query.pageSize()));
        return new NoticePage(items,total==null?0:total,query.page(),query.pageSize());
    }

    /** Marks an addressed notice read after locking it and checking its optimistic version.
     * @param actor authenticated recipient; broader scope does not authorize another recipient's notice
     * @param publicId notice public identifier
     * @param expectedVersion exact notice version supplied by the recipient
     * @return updated notice, or the already-read notice when its version still matches
     * @throws BusinessException if the notice is absent, addressed to another employee or stale
     */
    public Notice markRead(Actor actor,String publicId,long expectedVersion) {
        List<Notice> rows=jdbc.query("""
            SELECT n.public_id,o.public_id AS opportunity_id,c.public_id AS consultation_id,n.title_cipher,n.text_cipher,n.created_at,n.read_at,n.version
            FROM core_crm_consultation_notice n JOIN core_opportunity o ON o.id=n.opportunity_id
            JOIN core_crm_consultation c ON c.id=n.consultation_id
            WHERE n.public_id=? AND n.recipient_id=? AND n.deleted=0 FOR UPDATE
            """,(rs,row)->notice(rs),publicId,actor.id());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"提醒不存在");
        Notice current=rows.get(0);
        if(current.version()!=expectedVersion)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"提醒版本已变化");
        if(current.readAt()!=null)return current;
        jdbc.update("UPDATE core_crm_consultation_notice SET read_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE public_id=? AND recipient_id=? AND version=? AND read_at IS NULL",publicId,actor.id(),expectedVersion);
        List<Notice> updated=jdbc.query("""
            SELECT n.public_id,o.public_id AS opportunity_id,c.public_id AS consultation_id,n.title_cipher,n.text_cipher,n.created_at,n.read_at,n.version
            FROM core_crm_consultation_notice n JOIN core_opportunity o ON o.id=n.opportunity_id
            JOIN core_crm_consultation c ON c.id=n.consultation_id WHERE n.public_id=? AND n.recipient_id=?
            """,(rs,row)->notice(rs),publicId,actor.id());
        return updated.get(0);
    }

    /** Reads one notice only for its recorded recipient before returning a mutation receipt.
     * @param actor authenticated recipient
     * @param publicId notice public identifier
     * @return decrypted recipient notice with its current read time and version
     * @throws BusinessException if the notice is missing or belongs to another recipient
     */
    public Notice getNotice(Actor actor,String publicId) {
        List<Notice> rows=jdbc.query("""
            SELECT n.public_id,o.public_id AS opportunity_id,c.public_id AS consultation_id,n.title_cipher,n.text_cipher,n.created_at,n.read_at,n.version
            FROM core_crm_consultation_notice n JOIN core_opportunity o ON o.id=n.opportunity_id
            JOIN core_crm_consultation c ON c.id=n.consultation_id
            WHERE n.public_id=? AND n.recipient_id=? AND n.deleted=0
            """,(rs,row)->notice(rs),publicId,actor.id());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"提醒不存在");
        return rows.get(0);
    }

    private Notice notice(ResultSet rs)throws SQLException {
        return new Notice(rs.getString("public_id"),rs.getString("opportunity_id"),rs.getString("consultation_id"),
            cipher.decrypt(rs.getString("title_cipher")),cipher.decrypt(rs.getString("text_cipher")),instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("read_at")),rs.getLong("version"));
    }

    private CustomerRef customer(Actor actor,String publicId,boolean lock) {
        ScopeSql.Clause scope=ScopeSql.customerReads(actor);List<Object> args=new ArrayList<>(List.of(publicId));args.addAll(scope.args());
        List<CustomerRef> rows=jdbc.query("SELECT c.id,c.campus_id,c.public_id,c.display_name_cipher FROM core_customer c WHERE c.public_id=? AND c.deleted=0 AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,row)->new CustomerRef(rs.getLong("id"),nullableLong(rs,"campus_id"),rs.getString("public_id"),rs.getString("display_name_cipher")),args.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"客户不存在或不在授权范围");return rows.get(0);
    }

    private Long validateCampus(Actor actor,String requested,String customerPublicId) {
        Long customerCampus=jdbc.queryForObject("SELECT c.campus_id FROM core_customer c WHERE c.public_id=? AND c.deleted=0",Long.class,customerPublicId);
        if(requested==null||requested.isBlank())return customerCampus;
        List<Long> campus=jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0",(rs,row)->rs.getLong(1),requested);
        if(campus.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"校区不存在或已停用");
        long id=campus.get(0);if(!actor.scope().equals("all")&&!actor.campusIds().contains(id))throw new BusinessException(ErrorCode.FORBIDDEN,"当前账号无权访问该校区");
        if(customerCampus!=null&&customerCampus!=id)throw new BusinessException(ErrorCode.INVALID_INPUT,"咨询校区与客户校区不一致");return id;
    }

    private LinkRef campaign(Actor actor,String publicId,String customerPublicId,boolean lock) {
        CrmLegacyAccess.read(actor, "crm.activity.read");ScopeSql.Clause scope=ScopeSql.activities(actor);List<Object> args=new ArrayList<>(List.of(publicId));args.addAll(scope.args());
        List<Object> values=new ArrayList<>(List.of(customerPublicId,publicId));values.addAll(scope.args());
        List<LinkRef> rows=jdbc.query("SELECT a.id,a.public_id,a.campus_id,a.owner_id,a.status,c.id AS customer_id FROM core_activity a JOIN core_customer c ON c.public_id=? WHERE a.public_id=? AND a.deleted=0 AND a.status='active' AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,row)->new LinkRef(rs.getLong("id"),rs.getString("public_id"),rs.getLong("customer_id"),nullableLong(rs,"campus_id"),nullableLong(rs,"owner_id"),rs.getString("status")),values.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"活动不存在、已结束或不在授权范围");
        LinkRef found=rows.get(0);Long customerCampus=jdbc.queryForObject("SELECT campus_id FROM core_customer WHERE id=?",Long.class,found.customerId());
        if(!java.util.Objects.equals(customerCampus,found.campusId()))throw new BusinessException(ErrorCode.INVALID_INPUT,"客户与活动校区不一致");return found;
    }

    private LinkRef opportunity(Actor actor,String publicId,String customerPublicId,boolean lock) {
        CrmLegacyAccess.read(actor, "crm.opportunity.read");ScopeSql.Clause scope=opportunityScope(actor);List<Object> args=new ArrayList<>(List.of(publicId));args.addAll(scope.args());
        List<LinkRef> rows=jdbc.query("SELECT o.id,o.public_id,o.customer_id,o.campus_id,o.owner_id,o.stage FROM core_opportunity o WHERE o.public_id=? AND o.deleted=0 AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,row)->new LinkRef(rs.getLong("id"),rs.getString("public_id"),rs.getLong("customer_id"),nullableLong(rs,"campus_id"),nullableLong(rs,"owner_id"),rs.getString("stage")),args.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"商机不存在或不在授权范围");LinkRef found=rows.get(0);
        String actual=jdbc.queryForObject("SELECT public_id FROM core_customer WHERE id=?",String.class,found.customerId());
        if(!customerPublicId.equals(actual))throw new BusinessException(ErrorCode.INVALID_INPUT,"商机与客户不一致");return found;
    }

    private ScopeSql.Clause opportunityScope(Actor actor) {
        return switch(actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1",List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)",List.of(actor.id()));
            case "own","assigned" -> new ScopeSql.Clause("o.owner_id=?",List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN,"账号数据范围无效");
        };
    }

    private long internalId(String table,String publicId) {
        if(!List.of("core_customer_touchpoint","core_crm_consultation","core_principal").contains(table))throw new IllegalArgumentException("Unsupported internal table");
        Long id=jdbc.queryForObject("SELECT id FROM "+table+" WHERE public_id=?",Long.class,publicId);
        if(id==null)throw new BusinessException(ErrorCode.INTERNAL_ERROR,"咨询关联记录创建失败");return id;
    }
    private String touchpointChannel(String channel) { return switch(channel){case "phone"->"phone";case "wechat"->"wecom";case "visit"->"in_person";case "online"->"form";default->"import";}; }
    private String opportunityChannel(String channel) { return switch(channel){case "phone"->"phone";case "wechat"->"wecom";case "visit"->"in_person";case "online"->"online";default->"other";}; }
    private Long nullableLong(ResultSet rs,String col)throws SQLException { long value=rs.getLong(col);return rs.wasNull()?null:value; }
    private Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
