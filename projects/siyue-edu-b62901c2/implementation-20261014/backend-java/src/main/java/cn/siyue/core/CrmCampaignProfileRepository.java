package cn.siyue.core;

import static cn.siyue.core.CrmProfileValues.*;
import cn.siyue.core.CrmProfileDtos.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Draft campaign revisions retain publication, scope, membership and linked-form rules. */
@Repository
public class CrmCampaignProfileRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** Wires campaign drafts, scoped form links and encrypted descriptive fields.
     * @param jdbc transaction-bound database access
     * @param cipher keyring used for campaign descriptions and campus names
     */
    public CrmCampaignProfileRepository(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    /** Lists selectable campuses, forms or employees using the corresponding profile-edit rules.
     * @param actor caller; campuses require lead.create or lead.follow, other options require lead.create
     * @param query option kind, optional campus, page 1–100000 and size 1–100
     * @return scoped choices and total; explicit employee choices exclude inactive, archived and pending-password accounts
     * @throws BusinessException if option kind, grants, campus scope or pagination is invalid
     */
    public Options options(Actor actor,OptionsQuery query) {
        if(query.page()<1||query.page()>100000||query.size()<1||query.size()>100) throw invalid("分页参数无效");
        String kind=query.kind();List<Object> args=new ArrayList<>();String from,where,select;
        if("campuses".equals(kind)) {
            actor.requireAny("lead.create","lead.follow");from="core_campus cp";select="cp.public_id,cp.name_cipher,NULL";
            where="cp.active=1 AND cp.deleted=0";
            if(!actor.scope().equals("all")) {where+=" AND EXISTS(SELECT pc.id FROM core_principal_campus pc WHERE pc.campus_id=cp.id AND pc.principal_id=? AND pc.deleted=0)";args.add(actor.id());}
        } else {
            actor.require("lead.create");Long campus=campus(actor,query.campusId(),false);
            if("forms".equals(kind)) {
                ScopeSql.Clause scope=ScopeSql.forms(actor);from="core_crm_form f LEFT JOIN core_campus cp ON cp.id=f.campus_id";select="f.public_id,f.title,cp.public_id";
                where="f.deleted=0 AND f.status IN ('draft','published') AND ("+scope.sql()+")";args.addAll(scope.args());
                if(campus!=null) {where+=" AND (f.campus_id IS NULL OR f.campus_id=?)";args.add(campus);}
            } else if("employees".equals(kind)) {
                from="core_principal p";select="p.public_id,p.public_id,NULL";where="p.active=1 AND p.deleted=0 AND p.archived_at IS NULL AND p.credential_change_required=0 AND (p.scope_type IN ('all','own')";
                where+=" OR (p.scope_type='campus' AND EXISTS(SELECT pc.id FROM core_principal_campus pc WHERE pc.principal_id=p.id AND pc.campus_id=? AND pc.deleted=0))";args.add(campus);
                where+=" OR (p.scope_type='assigned' AND EXISTS(SELECT c.id FROM core_class c WHERE c.campus_id=? AND c.deleted=0 AND (c.teacher_id=p.id OR EXISTS(SELECT s.id FROM core_session s WHERE s.class_id=c.id AND s.teacher_id=p.id AND s.deleted=0)))))";args.add(campus);
            } else throw invalid("配置选项类型无效");
        }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+from+" WHERE "+where,Long.class,args.toArray());
        args.add(query.size());args.add(Math.multiplyExact((long)query.page()-1,query.size()));
        List<Choice> items=jdbc.query("SELECT "+select+" FROM "+from+" WHERE "+where+" ORDER BY 1 LIMIT ? OFFSET ?",
            (rs,row)->new Choice(rs.getString(1),"campuses".equals(kind)?cipher.decrypt(rs.getString(2)):rs.getString(2),rs.getString(3)),args.toArray());
        return new Options(items,total,query.page(),query.size());
    }
    /** Requires campaign write ownership independently of membership-based read visibility. */
    void authorizeWrite(Actor actor,String id) {activity(actor,id,true);}
    /** Reads campaign fields and current form, participant and promoter links under read scope.
     * @param actor caller whose campaign visibility is checked
     * @param publicId campaign public identifier
     * @return draft or published campaign profile including its optimistic version and optional planning fields
     * @throws BusinessException if the activity is missing, not a campaign or outside read scope
     */
    public Campaign profile(Actor actor,String publicId) {
        long activity=activity(actor,publicId,false);
        return jdbc.query("SELECT a.public_id,a.title,cp.public_id,a.channel,a.description_cipher,a.starts_at,a.ends_at,"
            + "a.budget_minor,a.target_count,a.status,a.version FROM core_activity a LEFT JOIN core_campus cp ON cp.id=a.campus_id WHERE a.id=?",
            (rs,row)->new Campaign(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),decrypt(rs.getString(5)),
                instant(rs.getTimestamp(6)),instant(rs.getTimestamp(7)),rs.getObject(8,Long.class),rs.getObject(9,Integer.class),
                forms(activity),members(activity,"participant"),members(activity,"promoter"),rs.getString(10),rs.getLong(11)),activity).get(0);
    }
    /** Creates a caller-owned campaign draft, validating its fields and associations through the draft updater.
     * @param actor caller already authorized for campaign creation by the service
     * @param input permitted campaign fields, selected forms and participant/promoter identifiers
     * @return validated draft profile at version 1
     * @throws BusinessException if patch fields, campus, schedule, monetary values, forms or selected employees are invalid
     */
    public Campaign create(Actor actor,Map<String,Object> input) {
        keys(input,Set.of("name","campusId","channel","description","start","end","budgetMinor","targetCount","formIds","participantIds","promoterIds"));
        String publicId="ACT_"+java.util.UUID.randomUUID();String name=text(input.get("name"),200,true);
        Long campus=campus(actor,input.get("campusId")==null?null:id(input.get("campusId")));
        jdbc.update("INSERT INTO core_activity(public_id,campus_id,owner_id,title,activity_type,status,starts_at,ends_at,legacy_schedule_missing,source_created_by_id) "
            + "VALUES(?,?,?,?,'campaign','draft',NULL,NULL,TRUE,?)",publicId,campus,actor.id(),name,actor.id());
        // Reuse all draft field, member, form and new-scope checks inside this transaction.
        update(actor,publicId,new Patch(input,1));
        jdbc.update("UPDATE core_activity SET version=1 WHERE public_id=?",publicId);return profile(actor,publicId);
    }
    /** Publishes a scoped draft campaign after checking its version and optional end time.
     * @param actor caller already authorized for publication by the service
     * @param publicId campaign public identifier
     * @param expectedVersion exact draft version approved by the caller
     * @return active campaign with its version advanced once
     * @throws BusinessException if write scope, version, draft status or expiration prevents publication
     */
    public Campaign publish(Actor actor,String publicId,long expectedVersion) {
        long activity=activity(actor,publicId,true);Campaign current=profile(actor,publicId);version(current.version(),expectedVersion);
        if(!current.status().equals("draft")) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"仅草稿活动可以发布");
        if(current.end()!=null&&!current.end().isAfter(Instant.now())) throw invalid("活动已过期");
        jdbc.update("UPDATE core_activity SET status='active',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",activity);return profile(actor,publicId);
    }
    /** Replaces allowed fields on a draft while preserving published campaign immutability.
     * @param actor caller already authorized for draft editing by the service
     * @param publicId campaign public identifier
     * @param input patch keys and expected version; absent values retain their prior values
     * @return updated draft after form/member consistency and current scope checks
     * @throws BusinessException if scope, version, draft status, fields, form associations or explicit member eligibility fail
     */
    public Campaign update(Actor actor,String publicId,Patch input) {
        Map<String,Object> patch=input.patch();keys(patch,Set.of("name","campusId","channel","description","start","end","budgetMinor","targetCount","formIds","participantIds","promoterIds"));
        long activity=activity(actor,publicId,true);Campaign old=profile(actor,publicId);version(old.version(),input.expectedVersion());
        if(!old.status().equals("draft")) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"活动发布后不能覆盖编辑，请创建新版本");
        Fields fields=fields(actor,patch,old);Long campus=fields.campus();
        List<String> forms=patch.containsKey("formIds")?ids(patch.get("formIds")):old.formIds();
        Set<String> participants=new LinkedHashSet<>(patch.containsKey("participantIds")?ids(patch.get("participantIds")):old.participantIds());
        List<String> promoters=patch.containsKey("promoterIds")?ids(patch.get("promoterIds")):old.promoterIds();
        // Validate explicit selections; automatic form actors retain the original historical-membership rule.
        if(patch.containsKey("participantIds")) for(String member:participants) member(member,campus);
        if(patch.containsKey("promoterIds")) for(String member:promoters) member(member,campus);
        for(String form:forms) participants.addAll(formOwners(actor,form,campus));
        if(participants.size()>200) throw invalid("参与员工过多");
        jdbc.update("UPDATE core_activity SET title=?,campus_id=?,channel=?,description_cipher=?,starts_at=?,ends_at=?,"
            + "legacy_schedule_missing=?,budget_minor=?,target_count=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
            fields.name(),campus,fields.channel(),fields.description()==null?null:cipher.encrypt(fields.description()),timestamp(fields.start()),timestamp(fields.end()),(fields.start()==null||fields.end()==null),fields.budget(),fields.target(),activity);
        replaceMembers(activity,List.copyOf(participants),"participant");replaceMembers(activity,promoters,"promoter");replaceForms(actor,activity,forms);
        return profile(actor,publicId);
    }
    /** Validated draft values after applying a partial campaign patch to the previous profile. */
    private record Fields(String name,String channel,String description,Long campus,Instant start,Instant end,Long budget,Integer target) { }
    private Fields fields(Actor actor,Map<String,Object> patch,Campaign old) {
        String name=patch.containsKey("name")?text(patch.get("name"),200,true):old.name();
        String channel=patch.containsKey("channel")?text(patch.get("channel"),100,false):old.channel();
        String description=patch.containsKey("description")?text(patch.get("description"),3000,false):old.description();
        String campusPublic=patch.containsKey("campusId")?(patch.get("campusId")==null?null:id(patch.get("campusId"))):old.campusId();
        Long campus=campus(actor,campusPublic);
        Instant start=patch.containsKey("start")?parseInstant(patch.get("start")):old.start(),end=patch.containsKey("end")?parseInstant(patch.get("end")):old.end();
        if(start!=null&&end!=null&&end.isBefore(start)) throw invalid("结束时间不能早于开始时间");
        Long budget=patch.containsKey("budgetMinor")?Long.valueOf(integer(patch.get("budgetMinor"),100000000000L)):old.budgetMinor();
        Integer target=patch.containsKey("targetCount")?Integer.valueOf(Math.toIntExact(integer(patch.get("targetCount"),1000000))):old.targetCount();
        return new Fields(name,channel,description,campus,start,end,budget,target);
    }
    private long activity(Actor actor,String publicId,boolean lock) {
        // Node writes belong to the campaign owner; membership alone grants no write capability.
        ScopeSql.Clause scope=lock?ScopeSql.campaignWrites(actor):ScopeSql.activities(actor);
        List<Object> args=new ArrayList<>(List.of(id(publicId)));args.addAll(scope.args());
        return jdbc.query("SELECT a.id FROM core_activity a WHERE a.public_id=? AND a.deleted=0 AND a.activity_type='campaign' AND ("
            + scope.sql()+")"+(lock?" FOR UPDATE":""),(rs,row)->rs.getLong(1),args.toArray()).stream().findFirst()
            .orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"营销活动不存在或不在授权范围"));
    }
    private Long campus(Actor actor,String publicId) {return campus(actor,publicId,true);}
    private Long campus(Actor actor,String publicId,boolean lock) {
        if(publicId==null) {if(actor.scope().equals("campus")) throw new BusinessException(ErrorCode.FORBIDDEN,"请选择授权校区");return null;}
        long campus=jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND active=1 AND deleted=0"+(lock?" FOR UPDATE":""),(rs,row)->rs.getLong(1),publicId)
            .stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"校区不存在或已停用"));
        if(!actor.scope().equals("all")&&!actor.campusIds().contains(campus)) throw new BusinessException(ErrorCode.FORBIDDEN,"校区不在授权范围");return campus;
    }
    private List<String> formOwners(Actor actor,String publicId,Long campus) {
        ScopeSql.Clause scope=ScopeSql.forms(actor);List<Object> args=new ArrayList<>(List.of(publicId));args.addAll(scope.args());
        List<Long> forms=jdbc.query("SELECT f.id FROM core_crm_form f WHERE f.public_id=? AND f.deleted=0 AND f.status IN ('draft','published') AND ("+scope.sql()+") FOR UPDATE",
            (rs,row)->rs.getLong(1),args.toArray());
        if(forms.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"表单不存在或不在授权范围");
        long form=forms.get(0);Long formCampus=jdbc.queryForObject("SELECT campus_id FROM core_crm_form WHERE id=?",Long.class,form);
        if(formCampus!=null&&campus!=null&&!Objects.equals(formCampus,campus)) throw invalid("关联表单须属于活动校区");
        return jdbc.query("SELECT DISTINCT p.public_id FROM core_principal p WHERE p.active=1 AND p.deleted=0 AND ("
            + "p.id=(SELECT owner_id FROM core_crm_form WHERE id=?) OR p.id IN (SELECT r.published_by FROM core_crm_form_revision r JOIN core_crm_form f ON f.id=r.form_id AND f.current_revision=r.revision_no WHERE r.form_id=? AND r.deleted=0))",
            (rs,row)->rs.getString(1),form,form);
    }
    private long member(String publicId,Long campus) {
        /** Selected employee identifier and current scope used to check campaign participation eligibility. */
        record Member(long id,String scope) { }
        Member member=jdbc.query("SELECT id,scope_type FROM core_principal WHERE public_id=? AND active=1 AND deleted=0 AND archived_at IS NULL AND credential_change_required=0 FOR UPDATE",
            (rs,row)->new Member(rs.getLong(1),rs.getString(2)),publicId).stream().findFirst()
            .orElseThrow(()->invalid("请选择有效参与员工"));
        if(member.scope().equals("campus")&&(campus==null||jdbc.queryForObject("SELECT COUNT(*) FROM core_principal_campus WHERE principal_id=? AND campus_id=? AND deleted=0",Long.class,member.id(),campus)==0)) throw invalid("参与员工不在活动校区范围");
        if(member.scope().equals("assigned")&&(campus==null||jdbc.queryForObject("SELECT COUNT(*) FROM core_class c WHERE c.campus_id=? AND c.deleted=0 AND (c.teacher_id=? OR EXISTS "
            + "(SELECT s.id FROM core_session s WHERE s.class_id=c.id AND s.deleted=0 AND s.teacher_id=?))",Long.class,campus,member.id(),member.id())==0)) throw invalid("参与教师没有活动校区授课关系");
        return member.id();
    }
    private List<String> forms(long activity) {return jdbc.query("SELECT f.public_id FROM core_activity_form af JOIN core_crm_form f ON f.id=af.form_id WHERE af.activity_id=? AND af.deleted=0 AND f.deleted=0 ORDER BY af.id",(rs,row)->rs.getString(1),activity);}
    private List<String> members(long activity,String role) {return jdbc.query("SELECT p.public_id FROM core_activity_member am JOIN core_principal p ON p.id=am.principal_id WHERE am.activity_id=? AND am.member_role=? AND am.deleted=0 ORDER BY am.id",(rs,row)->rs.getString(1),activity,role);}
    private void replaceMembers(long activity,List<String> members,String role) {
        jdbc.update("UPDATE core_activity_member SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE activity_id=? AND member_role=?",activity,role);
        for(String member:members) jdbc.update("INSERT INTO core_activity_member(activity_id,principal_id,member_role) SELECT ?,id,? FROM core_principal WHERE public_id=? "
            + "ON DUPLICATE KEY UPDATE deleted=0,updated_at=UTC_TIMESTAMP(3)",activity,role,member);
    }
    private void replaceForms(Actor actor,long activity,List<String> forms) {
        jdbc.update("UPDATE core_activity_form SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE activity_id=?",activity);
        for(String form:forms) jdbc.update("INSERT INTO core_activity_form(activity_id,form_id,linked_by) SELECT ?,id,? FROM core_crm_form WHERE public_id=? "
            + "ON DUPLICATE KEY UPDATE deleted=0,updated_at=UTC_TIMESTAMP(3)",activity,actor.id(),form);
    }
    private Instant parseInstant(Object value) {try{return Instant.parse(text(value,100,true));}catch(java.time.format.DateTimeParseException error){throw invalid("时间格式无效");}}
    private Instant instant(Timestamp value) {return value==null?null:value.toInstant();}
    private Timestamp timestamp(Instant value) {return value==null?null:Timestamp.from(value);}
    private String decrypt(String value) {return value==null?null:cipher.decrypt(value);}
}
