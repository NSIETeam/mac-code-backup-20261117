package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import cn.siyue.core.OpportunityNeedReadRepository.Raw;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit, customer-serialized resolution of classified and historical sales needs. */
@Repository
public class OpportunityNeedRepository {
    private record Plan(OpportunityNeedSourceRepository.Links links,OpportunityConfigRepository.IdentityRefs identity,
            ProfileView profile,Preview preview,OpportunityAssignmentRepository.Staff owner) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final OpportunityNeedReadRepository reads;
    private final OpportunityConfigRepository config;
    private final OpportunityNeedSourceRepository sources;
    private final OpportunityAssignmentRepository assignment;
    public OpportunityNeedRepository(JdbcTemplate jdbc,PiiCipher cipher,OpportunityNeedReadRepository reads,
            OpportunityConfigRepository config,OpportunityNeedSourceRepository sources) {
        this.jdbc=jdbc; this.cipher=cipher; this.reads=reads; this.config=config; this.sources=sources;
        this.assignment=new OpportunityAssignmentRepository(jdbc,cipher,reads);
    }
    public Preview preview(Actor actor,Input input) { return plan(actor,input,false).preview(); }
    private Plan plan(Actor actor,Input input,boolean lock) {
        var links=sources.resolve(actor,input,lock,true);
        long catalogVersion=config.version(lock);
        var identity=config.identity(input.identity(),links.customer().campusId(),true);
        List<Raw> rows=reads.activeBase(links.customer().id(),links.studentId(),input.subject(),input.term(),lock);
        List<Candidate> candidates=rows.stream().map(row->reads.candidate(actor,row,match(row,identity))).toList();
        boolean exact=candidates.stream().anyMatch(c->"exact".equals(c.match()));
        if(!exact)config.identity(input.identity(),links.customer().campusId(),false);
        ProfileView profile=config.profile(input.profile(),links.customer().campusId(),null);
        var owner=selectedOwner(actor,input,links.customer().campusId(),lock);
        List<String> actions=actions(actor,rows,candidates);
        Preview preview=new Preview(catalogVersion,HexFormat.of().formatHex(OpportunityNeedSupport.digest(reads.encode(input))),
            links.customer().version(),links.leadVersion(),links.activityVersion(),links.customer().campusPublicId(),
            candidates,actions,owner==null?null:owner.publicId(),differences(actor,rows,input,profile));
        return new Plan(links,identity,profile,preview,owner);
    }
    private OpportunityAssignmentRepository.Staff selectedOwner(Actor actor,Input input,Long campus,boolean lock) {
        if(!(actor.permissions().contains("crm.opportunity.write") || actor.permissions().contains("lead.follow"))) {
            if(OpportunityNeedSupport.clean(input.ownerId())!=null)OpportunityNeedSupport.invalid("接待咨询不能指定商机负责人");
            return null;
        }
        String owner=OpportunityNeedSupport.clean(input.ownerId());
        return assignment.eligible(actor,owner==null?actor.publicId():owner,campus,lock);
    }
    private String match(Raw row,OpportunityConfigRepository.IdentityRefs identity) {
        if(row.needVersion()!=2)return "legacy_unknown";
        Long project=identity.project()==null?null:identity.project().id();
        return Objects.equals(row.departmentId(),identity.department().id())&&Objects.equals(row.projectId(),project)?"exact":"different_need";
    }
    private List<String> actions(Actor actor,List<Raw> rows,List<Candidate> candidates) {
        if(candidates.stream().anyMatch(c->"exact".equals(c.match())))return List.of("reuse");
        if(!(actor.permissions().contains("crm.opportunity.write") || actor.permissions().contains("lead.follow")))return List.of();
        var legacy=rows.stream().filter(row->row.needVersion()!=2).toList();
        if(legacy.isEmpty())return List.of("create");
        if(legacy.stream().allMatch(row->reads.readable(actor,row)))return List.of("classify_existing","create_distinct");
        return List.of();
    }
    private List<Difference> differences(Actor actor,List<Raw> rows,Input input,ProfileView profile) {
        List<Difference> result=new ArrayList<>();
        for(Raw row:rows) {
            if(!reads.readable(actor,row))continue;
            if(!row.title().equals(input.title().trim()))result.add(new Difference(row.publicId(),"title",input.title().trim(),row.title()));
            ProfileView previous=reads.decode(row.profileJson(),ProfileView.class);
            if(input.profile()!=null&&!Objects.equals(profile,previous))result.add(new Difference(row.publicId(),"profile",reads.encode(profile),reads.encode(previous)));
        }
        return List.copyOf(result);
    }
    /** Replay checks only current permissions and associations, not new-write versions or configuration. */
    public void authorizeReplay(Actor actor,Commit request) {
        var links=sources.resolve(actor,request.input(),false,false);
        String target=OpportunityNeedSupport.clean(request.decision().targetRef());
        if(target!=null) {
            Raw row=reads.raw(target,false);
            if(row.customerId()!=links.customer().id())OpportunityNeedSupport.missing();
            if("classify_existing".equals(request.decision().action())) {
                CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); reads.authorized(actor,target,false);
            }
        }
        if(!"reuse".equals(request.decision().action()))CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write");
        if(OpportunityNeedSupport.clean(request.input().ownerId())!=null&&!actor.publicId().equals(request.input().ownerId()))CrmLegacyAccess.operation(actor, "lead.assign", "crm.opportunity.assign");
    }
    public Receipt commit(Actor actor,Commit request) {
        Plan plan=plan(actor,request.input(),true);
        if(!plan.preview().equals(request.expectedPreview()))OpportunityNeedSupport.stale();
        Decision decision=request.decision();
        if(!plan.preview().allowedActions().contains(decision.action()))OpportunityNeedSupport.invalid("当前需求不能执行所选办理方式");
        String id;
        boolean created=List.of("create","create_distinct").contains(decision.action());
        if(created) {
            if(decision.targetRef()!=null)OpportunityNeedSupport.invalid("新建不能指定已有商机");
            if("create_distinct".equals(decision.action())&&OpportunityNeedSupport.clean(decision.reason())==null)OpportunityNeedSupport.invalid("另建需求须说明与历史需求的区别");
            id=insert(actor,request.input(),plan);
        } else {
            id=existing(actor,request,plan);
        }
        Raw row=reads.raw(id,true);
        if(!created)jdbc.update("UPDATE core_opportunity SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",row.id());
        String consultation=event(actor,row.id(),"consultation",request.input().detail().trim());
        if(!created) {
            jdbc.update("INSERT INTO core_opportunity_event(public_id,opportunity_id,actor_id,event_type,from_stage,to_stage,channel,occurred_at,detail_cipher) VALUES(?,?,?,'followup',?,?,'other',UTC_TIMESTAMP(3),?)",
                "OEVT_"+UUID.randomUUID(),row.id(),actor.id(),row.stage(),row.stage(),cipher.encrypt(request.input().detail().trim()));
            notifyOwner(row,consultation);
        }
        if("create_distinct".equals(decision.action()))event(actor,row.id(),"distinct",reads.encode(request.expectedPreview().candidates())+"\n"+decision.reason().trim());
        String outcome=switch(decision.action()) { case "reuse" -> "reused"; case "classify_existing" -> "classified"; case "create_distinct" -> "created_distinct"; default -> "created"; };
        var visible=reads.readable(actor,reads.raw(id,false))?reads.detail(actor,id).opportunity():null;
        return new Receipt(outcome,id,consultation,false,created?"not_required":"recorded",visible,"classified");
    }
    private String existing(Actor actor,Commit request,Plan plan) {
        Decision decision=request.decision();
        Candidate candidate=plan.preview().candidates().stream().filter(row->row.targetRef().equals(decision.targetRef())).findFirst().orElse(null);
        if(candidate==null)OpportunityNeedSupport.invalid("请选择本客户核对清单中的需求");
        String expected="reuse".equals(decision.action())?"exact":"legacy_unknown";
        if(!expected.equals(candidate.match()))OpportunityNeedSupport.invalid("所选需求不符合办理方式");
        Raw row=reads.raw(candidate.targetRef(),true);
        if(row.version()!=candidate.version())OpportunityNeedSupport.stale();
        if(OpportunityNeedSupport.clean(request.input().ownerId())!=null&&!row.ownerPublicId().equals(request.input().ownerId()))OpportunityNeedSupport.invalid("复用需求保留原负责人，请使用独立分配操作");
        if("classify_existing".equals(decision.action())) {
            CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); reads.authorized(actor,row.publicId(),false);
            if(OpportunityNeedSupport.clean(decision.reason())==null)OpportunityNeedSupport.invalid("识别历史需求须填写核对依据");
            classify(row,plan,request.input()); event(actor,row.id(),"identified",reads.encode(plan.identity().view())+"\n"+decision.reason().trim());
        }
        return row.publicId();
    }
    private String insert(Actor actor,Input input,Plan plan) {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); var link=plan.links(); String id="OPP_"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_opportunity(public_id,customer_id,source_lead_id,campus_id,owner_id,activity_id,student_id,
              title,subject,term,need_key,stage,detail_cipher,need_version,department_id,project_id,need_key_v2,identity_snapshot)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,'qualified',?,2,?,?,?,?)
            """,id,link.customer().id(),link.sourceLeadId(),link.customer().campusId(),plan.owner().id(),link.activityId(),link.studentId(),
            input.title().trim(),input.subject().trim(),input.term().trim(),OpportunityNeedSupport.legacyKey(link.studentId(),input.subject().trim(),input.term().trim()),
            cipher.encrypt(input.detail().trim()),plan.identity().department().id(),projectId(plan),key(plan,input.subject().trim(),input.term().trim()),reads.encode(plan.identity().view()));
        Raw row=reads.raw(id,false); saveProfile(row.id(),plan.profile());
        jdbc.update("INSERT INTO core_opportunity_event(public_id,opportunity_id,actor_id,event_type,to_stage,occurred_at,detail_cipher) VALUES(?,?,?,'created','qualified',UTC_TIMESTAMP(3),?)",
            "OEVT_"+UUID.randomUUID(),row.id(),actor.id(),cipher.encrypt(input.detail().trim())); return id;
    }
    private void classify(Raw row,Plan plan,Input input) {
        jdbc.update("UPDATE core_opportunity SET need_version=2,department_id=?,project_id=?,need_key_v2=?,identity_snapshot=? WHERE id=? AND need_version=1",
            plan.identity().department().id(),projectId(plan),key(plan,row.subject(),row.term()),reads.encode(plan.identity().view()),row.id());
        if(input.profile()!=null)saveProfile(row.id(),plan.profile());
    }
    private Long projectId(Plan plan) { return plan.identity().project()==null?null:plan.identity().project().id(); }
    private byte[] key(Plan plan,String subject,String term) {
        return OpportunityNeedSupport.demandKey(new OpportunityNeedSupport.IdentityKey(plan.links().studentId(),plan.identity().department().id(),projectId(plan),subject,term));
    }
    public String event(Actor actor,long opportunity,String type,String detail) {
        String id="NEVT_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_opportunity_need_event(public_id,opportunity_id,actor_id,event_type,detail_cipher) VALUES(?,?,?,?,?)",
            id,opportunity,actor.id(),type,cipher.encrypt(detail)); return id;
    }
    private void notifyOwner(Raw row,String consultation) {
        jdbc.update("INSERT INTO core_opportunity_notification(public_id,opportunity_id,consultation_id,recipient_id) SELECT ?,?,id,? FROM core_opportunity_need_event WHERE public_id=?",
            "ONOT_"+UUID.randomUUID(),row.id(),row.ownerId(),consultation);
    }
    public void saveProfile(long opportunity,ProfileView profile) {
        jdbc.update("""
            UPDATE core_opportunity SET profile_snapshot=?,grade_option_id=?,preferred_campus_id=?,class_type_option_id=?,
              level_option_id=?,fiscal_year_option_id=?,quarter_option_id=? WHERE id=?
            """,reads.encode(profile),optionId(profile.gradeId()),campusId(profile.preferredCampusId()),optionId(profile.classTypeId()),
            optionId(profile.levelId()),optionId(profile.fiscalYearId()),optionId(profile.quarterId()),opportunity);
    }
    private Long optionId(String id) { return id==null?null:config.find(id).id(); }
    private Long campusId(String id) { return id==null?null:jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?",Long.class,id); }
    public Receipt redact(Actor actor,Receipt receipt) {
        Raw row=reads.raw(receipt.opportunityId(),false);
        return new Receipt(receipt.outcome(),receipt.opportunityId(),receipt.consultationId(),receipt.ownerChanged(),receipt.notificationState(),
            reads.readable(actor,row)?reads.redact(actor,receipt.opportunity()):null,receipt.identityStatus());
    }
    public Detail profile(Actor actor,String id,ProfileUpdate input) {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); CrmLegacyAccess.read(actor, "crm.opportunity.read");
        long catalog=config.version(true); Raw row=reads.authorized(actor,id,true);
        if(row.version()!=input.expectedVersion()||catalog!=input.expectedCatalogVersion())OpportunityNeedSupport.stale();
        ProfileView previous=reads.decode(row.profileJson(),ProfileView.class);
        ProfileView desired=config.profile(input.profile(),row.campusId(),previous);
        saveProfile(row.id(),desired);
        jdbc.update("UPDATE core_opportunity SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",row.id());
        event(actor,row.id(),"profile",reads.encode(previous)+"\n"+reads.encode(desired)); return reads.detail(actor,id);
    }
    public Detail judge(Actor actor,String id,JudgmentInput input) {
        CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write"); CrmLegacyAccess.read(actor, "crm.opportunity.read");
        long catalog=config.version(true); Raw row=reads.authorized(actor,id,true);
        if(row.version()!=input.expectedVersion()||catalog!=input.expectedCatalogVersion())OpportunityNeedSupport.stale();
        var judgment=config.required(input.judgmentId(),"judgment",row.campusId(),false);
        var reason=OpportunityNeedSupport.clean(input.reasonId())==null?null:config.required(input.reasonId(),"judgment_reason",row.campusId(),false);
        if(reason!=null&&!Objects.equals(reason.parentId(),judgment.id()))OpportunityNeedSupport.invalid("判定原因不属于所选判定");
        boolean close="close".equals(judgment.option().effect());
        if(close&&List.of("won","closed").contains(row.stage()))OpportunityNeedSupport.invalid("成交或已关闭需求不能再次关闭");
        JudgmentView snapshot=new JudgmentView(judgment.option().id(),judgment.option().label(),reason==null?null:reason.option().id(),
            reason==null?null:reason.option().label(),input.detail().trim(),judgment.option().effect(),java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        jdbc.update("INSERT INTO core_opportunity_judgment(opportunity_id,actor_id,judgment_id,reason_id,snapshot_cipher) VALUES(?,?,?,?,?)",
            row.id(),actor.id(),judgment.id(),reason==null?null:reason.id(),cipher.encrypt(reads.encode(snapshot)));
        jdbc.update("UPDATE core_opportunity SET version=version+1,updated_at=UTC_TIMESTAMP(3)"+(close?",stage='closed',next_follow_at=NULL":"")+" WHERE id=?",row.id());
        event(actor,row.id(),"judgment",reads.encode(snapshot));
        if(close)jdbc.update("INSERT INTO core_opportunity_event(public_id,opportunity_id,actor_id,event_type,from_stage,to_stage,occurred_at,detail_cipher) VALUES(?,?,?,'stage',?,'closed',UTC_TIMESTAMP(3),?)",
            "OEVT_"+UUID.randomUUID(),row.id(),actor.id(),row.stage(),cipher.encrypt(input.detail().trim()));
        return reads.detail(actor,id);
    }
}
