package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Safe intake projections use customer relationships, never broad academic/student grants. */
@Repository
public class OpportunityNeedReadRepository {
    public record CustomerRow(long id,String publicId,Long campusId,String campusPublicId,String name,long version) { }
    public record Raw(long id,String publicId,long customerId,String customerPublicId,Long campusId,
            Long studentId,String studentPublicId,long ownerId,String ownerPublicId,String title,String subject,
            String term,String stage,long version,int needVersion,Long departmentId,Long projectId,
            String identityJson,String profileJson) { }
    private static final String SELECT="""
        SELECT o.id,o.public_id,o.customer_id,o.campus_id,o.student_id,o.owner_id,o.title,o.subject,o.term,
        o.stage,o.version,o.need_version,o.department_id,o.project_id,o.identity_snapshot,o.profile_snapshot,c.public_id AS customer_public_id,p.public_id AS owner_public_id,
        CASE WHEN s.deleted=0 AND EXISTS(SELECT l.id FROM core_customer_student l
          WHERE l.customer_id=o.customer_id AND l.student_id=s.id AND l.deleted=0)
          THEN s.public_id ELSE NULL END AS student_public_id
        FROM core_opportunity o JOIN core_customer c ON c.id=o.customer_id AND c.deleted=0
        JOIN core_principal p ON p.id=o.owner_id LEFT JOIN core_student s ON s.id=o.student_id
        """;
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;
    private final OpportunityConfigRepository config;
    private final OpportunityRepository opportunities;
    public OpportunityNeedReadRepository(JdbcTemplate jdbc,PiiCipher cipher,ObjectMapper mapper,
            OpportunityConfigRepository config,OpportunityRepository opportunities) {
        this.jdbc=jdbc; this.cipher=cipher; this.mapper=mapper; this.config=config; this.opportunities=opportunities;
    }
    public CustomerRow customer(Actor actor,String id,boolean lock) {
        var scope=ScopeSql.customerReads(actor); var args=OpportunityNeedSupport.args(id,scope);
        var rows=jdbc.query("SELECT c.id,c.public_id,c.campus_id,cp.public_id AS campus_public_id,c.display_name_cipher,c.version "
            +"FROM core_customer c LEFT JOIN core_campus cp ON cp.id=c.campus_id WHERE c.public_id=? AND c.deleted=0 AND ("
            +scope.sql()+")"+(lock?" FOR UPDATE":""),(rs,n)->new CustomerRow(rs.getLong("id"),rs.getString("public_id"),
                rs.getObject("campus_id",Long.class),rs.getString("campus_public_id"),cipher.decrypt(rs.getString("display_name_cipher")),rs.getLong("version")),args.toArray());
        if(rows.isEmpty())OpportunityNeedSupport.missing(); return rows.get(0);
    }
    public Context context(Actor actor,String id) {
        OpportunityNeedSupport.intake(actor); CustomerRow c=customer(actor,id,false);
        var contacts=jdbc.query("SELECT public_id,person_role,channel,value_cipher,is_primary FROM core_customer_contact WHERE customer_id=? AND deleted=0 ORDER BY id LIMIT 100",
            (rs,n)->new CrmDtos.CustomerContact(rs.getString("public_id"),rs.getString("person_role"),rs.getString("channel"),
                cipher.maskedContact(rs.getString("channel"),rs.getString("value_cipher")),rs.getBoolean("is_primary")),c.id());
        return new Context(new Customer(c.publicId(),c.name(),c.campusPublicId(),c.version()),contacts,"customer_linked");
    }
    public StudentPage students(Actor actor,String id,PageQuery query) {
        OpportunityNeedSupport.intake(actor); CustomerRow customer=customer(actor,id,false);
        int page=OpportunityNeedSupport.page(query.page()),size=OpportunityNeedSupport.size(query.size());
        String from=" FROM core_customer_student l JOIN core_student s ON s.id=l.student_id WHERE l.customer_id=? AND l.deleted=0 AND s.deleted=0 "
            +"AND l.id=(SELECT MIN(canonical.id) FROM core_customer_student canonical WHERE canonical.customer_id=l.customer_id AND canonical.student_id=l.student_id AND canonical.deleted=0)";
        long count=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,customer.id());
        var items=jdbc.query("SELECT s.public_id,s.name_cipher,s.grade,l.relationship"+from+" ORDER BY l.id LIMIT ? OFFSET ?",
            (rs,n)->new Student(rs.getString(1),cipher.decrypt(rs.getString(2)),rs.getString(3),rs.getString(4)),customer.id(),size,(long)(page-1)*size);
        return new StudentPage(items,count,page,size);
    }
    public Long student(long customer,String publicId) {
        if(OpportunityNeedSupport.clean(publicId)==null)return null;
        var ids=jdbc.query("SELECT s.id FROM core_student s JOIN core_customer_student l ON l.student_id=s.id AND l.deleted=0 WHERE l.customer_id=? AND s.public_id=? AND s.deleted=0",
            (rs,n)->rs.getLong(1),customer,publicId);
        if(ids.isEmpty())OpportunityNeedSupport.invalid("学生不是该客户的有效关联孩子"); return ids.get(0);
    }
    public Raw raw(String id,boolean lock) {
        var rows=jdbc.query(SELECT+" WHERE o.public_id=? AND o.deleted=0"+(lock?" FOR UPDATE":""),(rs,n)->row(rs),id);
        if(rows.isEmpty())OpportunityNeedSupport.missing(); return rows.get(0);
    }
    public Raw authorized(Actor actor,String id,boolean lock) {
        var scope=OpportunityNeedSupport.scope(actor); var args=OpportunityNeedSupport.args(id,scope);
        var rows=jdbc.query(SELECT+" WHERE o.public_id=? AND o.deleted=0 AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,n)->row(rs),args.toArray());
        if(rows.isEmpty())OpportunityNeedSupport.missing(); return rows.get(0);
    }
    public boolean readable(Actor actor,Raw row) {
        if(!CrmLegacyAccess.canRead(actor, "crm.opportunity.read"))return false;
        return switch(actor.scope()) { case "all" -> true; case "campus" -> actor.campusIds().contains(row.campusId());
            case "own","assigned" -> row.ownerId()==actor.id(); default -> false; };
    }
    public CandidatePage existing(Actor actor,String customerId,PageQuery query) {
        OpportunityNeedSupport.intake(actor); var customer=customer(actor,customerId,false);
        int page=OpportunityNeedSupport.page(query.page()),size=OpportunityNeedSupport.size(query.size());
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity WHERE customer_id=? AND deleted=0",Long.class,customer.id());
        var items=jdbc.query(SELECT+" WHERE o.customer_id=? AND o.deleted=0 ORDER BY o.id DESC LIMIT ? OFFSET ?",
            (rs,n)->candidate(actor,row(rs),null),customer.id(),size,(long)(page-1)*size);
        return new CandidatePage(items,total,page,size);
    }
    public List<Raw> activeBase(long customer,Long student,String subject,String term,boolean lock) {
        return jdbc.query(SELECT+" WHERE o.customer_id=? AND o.student_id <=> ? AND BINARY LOWER(o.subject)=BINARY LOWER(?) "
            +"AND BINARY LOWER(o.term)=BINARY LOWER(?) AND o.stage IN('qualified','appointment','trial','negotiation') AND o.deleted=0 ORDER BY o.public_id"+(lock?" FOR UPDATE":""),
            (rs,n)->row(rs),customer,student,subject.trim(),term.trim());
    }
    public Candidate candidate(Actor actor,Raw row,String match) {
        boolean visible=readable(actor,row); String status=row.needVersion()==2?"classified":"legacy_unknown";
        return new Candidate(row.publicId(),row.version(),row.title(),row.studentPublicId(),row.subject(),row.term(),
            status,decode(row.identityJson(),IdentityView.class),match,visible,visible?row.ownerPublicId():null,
            visible?row.stage():null,row.studentId()!=null&&row.studentPublicId()==null?"restricted":"visible");
    }
    public Detail detail(Actor actor,String id) {
        CrmLegacyAccess.read(actor, "crm.opportunity.read"); Raw row=authorized(actor,id,false);
        return new Detail(opportunities.get(actor,id),row.needVersion()==2?"classified":"legacy_unknown",
            decode(row.identityJson(),IdentityView.class),decode(row.profileJson(),ProfileView.class),judgment(row.id()));
    }
    public Options optionsFor(Actor actor,String id) {
        CrmLegacyAccess.read(actor, "crm.opportunity.read"); return config.options(authorized(actor,id,false).campusId());
    }
    public CrmDtos.Opportunity redact(Actor actor,CrmDtos.Opportunity saved) {
        if(saved==null)return null;
        CrmDtos.Opportunity current=opportunities.get(actor,saved.id());
        return new CrmDtos.Opportunity(saved.id(),saved.customerId(),current.sourceLeadId()==null?null:saved.sourceLeadId(),
            saved.campusId(),saved.ownerId(),current.activityId()==null?null:saved.activityId(),current.studentId()==null?null:saved.studentId(),
            saved.title(),saved.subject(),saved.term(),saved.stage(),saved.detail(),saved.nextFollowAt(),saved.version());
    }
    public Detail redact(Actor actor,Detail saved) {
        return new Detail(redact(actor,saved.opportunity()),saved.identityStatus(),saved.identity(),saved.profile(),saved.judgment());
    }
    public JudgmentView judgment(long id) {
        var values=jdbc.queryForList("SELECT snapshot_cipher FROM core_opportunity_judgment WHERE opportunity_id=? ORDER BY id DESC LIMIT 1",String.class,id);
        return values.isEmpty()?null:decode(cipher.decrypt(values.get(0)),JudgmentView.class);
    }
    public ConsultationPage consultations(Actor actor,String id,PageQuery query) {
        CrmLegacyAccess.read(actor, "crm.opportunity.read"); Raw row=authorized(actor,id,false);
        int page=OpportunityNeedSupport.page(query.page()),size=OpportunityNeedSupport.size(query.size());
        String where=" FROM core_opportunity_need_event e JOIN core_principal p ON p.id=e.actor_id WHERE e.opportunity_id=? AND e.event_type='consultation'";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,row.id());
        var items=jdbc.query("SELECT e.public_id,p.public_id,e.detail_cipher,e.occurred_at"+where+" ORDER BY e.id DESC LIMIT ? OFFSET ?",
            (rs,n)->new Consultation(rs.getString(1),rs.getString(2),cipher.decrypt(rs.getString(3)),rs.getTimestamp(4).toInstant()),row.id(),size,(long)(page-1)*size);
        return new ConsultationPage(items,total,page,size);
    }
    public String encode(Object value) {
        try { return mapper.writeValueAsString(value); } catch(JsonProcessingException error) { throw new IllegalStateException(error); }
    }
    public <T> T decode(String json,Class<T> type) {
        if(json==null)return null;
        try { return mapper.readValue(json,type); } catch(JsonProcessingException error) { throw new IllegalStateException(error); }
    }
    private Raw row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Raw(rs.getLong("id"),rs.getString("public_id"),rs.getLong("customer_id"),rs.getString("customer_public_id"),
            rs.getObject("campus_id",Long.class),rs.getObject("student_id",Long.class),rs.getString("student_public_id"),
            rs.getLong("owner_id"),rs.getString("owner_public_id"),rs.getString("title"),rs.getString("subject"),rs.getString("term"),
            rs.getString("stage"),rs.getLong("version"),rs.getInt("need_version"),rs.getObject("department_id",Long.class),
            rs.getObject("project_id",Long.class),rs.getString("identity_snapshot"),rs.getString("profile_snapshot"));
    }
}
