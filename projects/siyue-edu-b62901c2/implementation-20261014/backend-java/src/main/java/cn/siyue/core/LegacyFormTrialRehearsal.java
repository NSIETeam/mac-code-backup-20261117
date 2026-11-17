package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Offline, exact-source form/revision/submission/trial import; no invented students or publishers. */
public final class LegacyFormTrialRehearsal {
    private static final Set<String> TABLES=Set.of("core_principal","core_campus","core_class","core_session",
        "core_crm_form","core_crm_form_submission","core_crm_lead");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;
    private record Checkpoint(long revision,String hash,int count) { }
    public LegacyFormTrialRehearsal(JdbcTemplate jdbc,PiiCipher cipher,PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyFormTrialRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc=jdbc;this.cipher=cipher;this.transaction=phases;
    }

    public int importForms(JsonNode envelope) {
        return importForms(envelope, null);
    }

    /** Candidate-only entry point; ordinary rehearsal calls remain restricted to *_test schemas. */
    int importForms(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database,jdbc,envelope,authorization);
        require("siyue.legacy-business/v1".equals(envelope.path("schema").asText()),"快照格式不支持");
        require(envelope.path("revision").isIntegralNumber(),"快照版本无效");
        JsonNode state=envelope.path("state");long revision=envelope.path("revision").longValue();
        String fingerprint=hash(state.toString());
        int estimatedRows=estimateRows(state);
        require(estimatedRows<1000,"表单试课迁移超过单事务行数限制");
        return transaction.execute(() ->write(state,revision,fingerprint));
    }

    private int estimateRows(JsonNode state) {
        int count=couponRows(state);
        for(JsonNode form:array(state,"forms")) count+=estimateFormRows(form);
        count+=array(state,"trialBookings").size()+array(state,"rewardClaims").size();
        return count;
    }

    private int couponRows(JsonNode state) {
        int count=0;
        for(JsonNode offer:array(state.path("activities"),"coupon"))
            count+=1+array(offer,"courseIds").size();
        return count;
    }

    private int estimateFormRows(JsonNode form) {
        Map<Long,JsonNode> revisions=new LinkedHashMap<>();
        if(form.path("publishedSnapshot").isObject()) addRevision(revisions,form.path("publishedSnapshot"));
        for(JsonNode submission:array(form,"submissions"))
            addRevision(revisions,submission.path("formSnapshot"));
        int count=2+optionalRewardRows(form.path("settings").path("reward"));
        for(JsonNode revision:revisions.values()) {
            JsonNode trial=revision.path("settings").path("trial");
            count+=1+optionalTrialRows(trial)+optionalRewardRows(revision.path("rewardSnapshot"));
        }
        for(JsonNode submission:array(form,"submissions"))
            count+=submission.path("status").asText().equals("pending")?2:3;
        return count;
    }

    private int optionalTrialRows(JsonNode trial) {
        return trial.isMissingNode()||trial.isNull()?0:1+array(trial,"slots").size();
    }

    private int optionalRewardRows(JsonNode reward) {
        return reward.isMissingNode()||reward.isNull()?0:1;
    }

    private int write(JsonNode state,long revision,String fingerprint) {
        require(jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_catalog_v1' "
            + "AND source_revision=? AND source_hash=?",Integer.class,revision,fingerprint)==1,"须先导入同源目录");
        List<Checkpoint> previous=jdbc.query("SELECT source_revision,source_hash,imported_rows FROM core_migration_checkpoint "
            + "WHERE phase='form_trials_v1' FOR UPDATE",(rs,n)->new Checkpoint(rs.getLong(1),rs.getString(2),rs.getInt(3)));
        if(!previous.isEmpty()) {
            require(previous.get(0).revision()==revision&&previous.get(0).hash().equals(fingerprint),"表单来源变化，禁止覆盖历史");
            return previous.get(0).count();
        }
        int count=new LegacyCouponRehearsal(jdbc,cipher).offers(state);
        for(JsonNode form:array(state,"forms")) count+=form(form);
        for(JsonNode booking:array(state,"trialBookings")) { booking(booking,state);count++; }
        count+=new LegacyCouponRehearsal(jdbc,cipher).claims(state);
        require(count<1000,"表单试课迁移超过单事务行数限制");
        ObjectNode archive=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        archive.set("forms",state.path("forms"));archive.set("trialBookings",state.path("trialBookings"));
        archive.set("coupons",state.path("activities").path("coupon"));archive.set("rewardClaims",state.path("rewardClaims"));
        jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) "
            + "VALUES('form_trials_v1',?,?,?,?)",revision,fingerprint,cipher.encrypt(archive.toString()),count);
        return count;
    }

    private int form(JsonNode source) {
        String kind=text(source,"kind");require(Set.of("lead","survey","custom").contains(kind),"表单类型未映射");
        boolean collect=source.path("collectLead").asBoolean();require(!kind.equals("survey")||!collect,"问卷不能收集线索");
        String status=switch(text(source,"status")){case "草稿"->"draft";case "已发布"->"published";case "已停用"->"closed";
            default->throw invalid("表单状态未映射");};
        require(source.path("published").asBoolean()==status.equals("published"),"表单发布状态不一致");
        Map<Long,JsonNode> revisions=new LinkedHashMap<>();
        JsonNode published=source.path("publishedSnapshot");
        if(published.isObject()) addRevision(revisions,published);
        for(JsonNode sub:array(source,"submissions")) addRevision(revisions,sub.path("formSnapshot"));
        Long current=source.hasNonNull("publishedVersion")?positive(source,"publishedVersion"):null;
        require(!status.equals("published")||current!=null&&revisions.containsKey(current),"缺少当前发布快照");
        Timestamp created=date(source,"createdAt");
        jdbc.update("INSERT INTO core_crm_form(public_id,campus_id,owner_id,title,form_kind,collect_lead,status,current_revision,"
            + "fields_json,lead_mapping_json,version,created_at,updated_at,source_profile_cipher) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            text(source,"id"),id("core_campus",text(source,"campusId")),id("core_principal",text(source,"createdBy")),
            text(source,"title"),kind,collect,status,current,array(source,"fields").toString(),mapping(source).toString(),
            positive(source,"version"),created,date(source,"updatedAt"),cipher.encrypt(source.toString()));
        long formId=id("core_crm_form",text(source,"id"));int count=1;
        collection(formId,source.path("settings"));count++;
        count+=trialConfig(formId,0,source.path("settings").path("trial"));
        count+=new LegacyCouponRehearsal(jdbc,cipher).reward(formId,0,source.path("settings").path("reward"));
        for(var entry:revisions.entrySet()) {
            JsonNode snapshot=entry.getValue();String publisher=publisher(source,entry.getKey());
            jdbc.update("INSERT INTO core_crm_form_revision(public_id,form_id,revision_no,title,form_kind,collect_lead,fields_json,"
                + "lead_mapping_json,published_by,published_at,created_at,updated_at,source_profile_cipher,collection_source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "FREV_LEGACY_"+text(source,"id")+"_"+entry.getKey(),formId,entry.getKey(),text(snapshot,"title"),text(snapshot,"kind"),
                snapshot.path("collectLead").asBoolean(),array(snapshot,"fields").toString(),mapping(snapshot).toString(),
                id("core_principal",publisher),publishedAt(source,entry.getKey()),created,created,cipher.encrypt(snapshot.toString()),
                snapshot.path("settings").path("source").asText("表单获客"));
            count++;count+=trialConfig(formId,entry.getKey(),snapshot.path("settings").path("trial"));
            count+=new LegacyCouponRehearsal(jdbc,cipher).reward(formId,entry.getKey(),snapshot.path("rewardSnapshot"));
        }
        for(JsonNode sub:array(source,"submissions")) { submission(formId,sub);count+=sub.path("status").asText().equals("pending")?2:3; }
        return count;
    }

    private void collection(long formId,JsonNode source) {
        require(source.isObject()&&source.path("quantity").canConvertToInt()&&source.path("maxPerIdentity").canConvertToInt(),"历史表单缺少准确收集限制");
        var settings=new FormCollectionRepository.Settings(source.hasNonNull("start")?java.time.Instant.parse(text(source,"start")):null,
            source.hasNonNull("end")?java.time.Instant.parse(text(source,"end")):null,source.path("quantity").intValue(),
            source.path("maxPerIdentity").intValue(),text(source,"source"),optional(source,"ownerId"));
        Long owner=source.hasNonNull("ownerId")?id("core_principal",text(source,"ownerId")):null;
        new FormCollectionRepository(jdbc).store(formId,settings,owner);
    }
    private void addRevision(Map<Long,JsonNode> revisions,JsonNode snapshot) {
        require(snapshot.isObject(),"历史提交缺少发布快照");long version=positive(snapshot,"version");
        JsonNode previous=revisions.putIfAbsent(version,snapshot);
        require(previous==null||previous.equals(snapshot),"同一发布版本存在不同快照");
    }

    private ObjectNode mapping(JsonNode form) {
        ObjectNode result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode trial=form.path("settings").path("trial");
        if(trial.path("enabled").asBoolean()) {
            result.put("studentName",text(trial,"nameFieldId"));result.put("guardianPhone",text(trial,"phoneFieldId"));
            result.put("subject",text(trial,"subjectFieldId"));
        } else for(JsonNode field:array(form,"fields")) {
            if(field.path("type").asText().equals("name")&&!result.has("studentName")) result.put("studentName",text(field,"id"));
            if(field.path("type").asText().equals("phone")&&!result.has("guardianPhone")) result.put("guardianPhone",text(field,"id"));
        }
        return result;
    }

    private String publisher(JsonNode form,long revision) {
        if(form.path("publishedVersion").asLong()==revision) return text(form,"publishedBy");
        Set<String> publishers=new java.util.HashSet<>();
        for(JsonNode sub:array(form,"submissions")) if(sub.path("formVersion").asLong()==revision) publishers.add(text(sub,"publisherId"));
        require(publishers.size()==1,"历史发布人缺失或不唯一");return publishers.iterator().next();
    }
    private Timestamp publishedAt(JsonNode form,long revision) {
        require(form.path("publishedVersion").asLong()==revision,"历史版本缺少可验证发布时间");
        return date(form,"publishedAt");
    }

    private int trialConfig(long formId,long revision,JsonNode trial) {
        if(trial.isMissingNode()||trial.isNull()) return 0;
        require(trial.path("enabled").asBoolean(),"历史试课启用状态不支持");
        jdbc.update("INSERT INTO core_form_trial_config(public_id,form_id,revision_no,enabled,campus_field_id,grade_field_id,"
            + "subject_field_id,name_field_id,phone_field_id) VALUES(?,?,?,1,?,?,?,?,?)",
            "TRCFG_LEGACY_"+formId+"_"+revision,formId,revision,optional(trial,"campusFieldId"),text(trial,"gradeFieldId"),
            text(trial,"subjectFieldId"),text(trial,"nameFieldId"),text(trial,"phoneFieldId"));
        long config=jdbc.queryForObject("SELECT id FROM core_form_trial_config WHERE form_id=? AND revision_no=?",Long.class,formId,revision);
        int count=1,sequence=0;
        for(JsonNode slot:array(trial,"slots")) {
            long klass=id("core_class",text(slot,"classId")),session=id("core_session",text(slot,"sessionId"));
            require(jdbc.queryForObject("SELECT class_id FROM core_session WHERE id=?",Long.class,session)==klass,"试课课次班级关系不一致");
            jdbc.update("INSERT INTO core_form_trial_slot(public_id,config_id,slot_key,class_id,session_id,capacity,sequence_no) VALUES(?,?,?,?,?,?,?)",
                "TRSLOT_LEGACY_"+config+"_"+sequence,config,text(slot,"id"),klass,session,positive(slot,"capacity"),sequence++);count++;
        }
        require(sequence>0&&sequence<=100,"历史试课时段数量无效");return count;
    }

    private void submission(long formId,JsonNode sub) {
        long revision=revision(formId,positive(sub,"formVersion"));String status=text(sub,"status");
        require(Set.of("pending","approved","rejected").contains(status),"历史表单提交状态未映射");
        Long reviewer=status.equals("pending")?null:id("core_principal",text(sub,"reviewedBy"));
        Long lead=sub.hasNonNull("leadId")?id("core_crm_lead",text(sub,"leadId")):null;
        Long customer=lead==null?null:jdbc.queryForObject("SELECT customer_id FROM core_crm_lead WHERE id=?",Long.class,lead);
        Timestamp at=date(sub,"at"),reviewed=reviewer==null?null:date(sub,"reviewedAt");
        jdbc.update("INSERT INTO core_crm_form_submission(public_id,form_id,revision_id,request_key_hash,payload_hash,answers_cipher,"
            + "status,submitted_at,reviewer_id,review_note_cipher,reviewed_at,customer_id,lead_id,created_at,updated_at,source_profile_cipher,identity_hash) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",text(sub,"id"),formId,revision,hex(sub,"requestKey"),hex(sub,"payloadHash"),
            cipher.encrypt(sub.path("answersById").toString()),status,at,reviewer,encryptOptional(sub,"reviewNote"),reviewed,
            customer,lead,date(sub,"createdAt"),reviewed==null?at:reviewed,cipher.encrypt(sub.toString()),hex(sub,"identityHash"));
        long submission=id("core_crm_form_submission",text(sub,"id"));String by=text(sub,"submittedBy");
        event(submission,"submitted",by.equals("public")?null:id("core_principal",by),at);
        if(reviewer!=null) event(submission,status,reviewer,reviewed);
    }

    private void booking(JsonNode booking,JsonNode state) {
        long form=id("core_crm_form",text(booking,"formId")),revision=revision(form,positive(booking,"formVersion"));
        long submission=id("core_crm_form_submission",text(booking,"submissionId"));
        require(jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_form_submission WHERE id=? AND form_id=? AND revision_id=?",
            Integer.class,submission,form,revision)==1,"试课预约与提交发布快照不一致");
        long slot=jdbc.queryForObject("SELECT s.id FROM core_form_trial_slot s JOIN core_form_trial_config c ON c.id=s.config_id "
            + "WHERE c.form_id=? AND c.revision_no=? AND s.slot_key=?",Long.class,form,positive(booking,"formVersion"),text(booking,"slotId"));
        long klass=id("core_class",text(booking,"classId")),session=id("core_session",text(booking,"sessionId"));
        require(jdbc.queryForObject("SELECT COUNT(id) FROM core_form_trial_slot WHERE id=? AND class_id=? AND session_id=?",
            Integer.class,slot,klass,session)==1,"试课预约与配置班级课次不一致");
        String status=text(booking,"status");require(Set.of("confirmed","cancelled").contains(status),"试课状态未映射");
        Timestamp at=date(booking,"bookedAt"),cancelled=null;Long cancelledBy=null;
        if(status.equals("cancelled")) {
            JsonNode sub=sourceSubmission(state,booking);require(sub.path("status").asText().equals("rejected"),"试课取消缺少拒绝审核事实");
            cancelled=date(sub,"reviewedAt");cancelledBy=id("core_principal",text(sub,"reviewedBy"));
        }
        jdbc.update("INSERT INTO core_trial_booking(public_id,form_id,revision_id,submission_id,slot_id,class_id,session_id,campus_id,"
            + "name_cipher,phone_cipher,grade,subject,slot_snapshot_cipher,status,booked_at,publisher_id,cancelled_at,cancelled_by,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",text(booking,"id"),form,revision,submission,slot,klass,session,
            id("core_campus",text(booking,"campusId")),cipher.encrypt(text(booking,"name")),cipher.encrypt(text(booking,"phone")),
            text(booking,"grade"),text(booking,"subject"),cipher.encrypt(booking.path("slotSnapshot").toString()),status,at,
            id("core_principal",text(booking,"publisherId")),cancelled,cancelledBy,at,cancelled==null?at:cancelled);
    }

    private JsonNode sourceSubmission(JsonNode state,JsonNode booking) {
        for(JsonNode form:array(state,"forms")) if(text(form,"id").equals(text(booking,"formId")))
            for(JsonNode sub:array(form,"submissions")) if(text(sub,"id").equals(text(booking,"submissionId"))) return sub;
        throw invalid("缺少试课来源提交");
    }
    private void event(long submission,String type,Long actor,Timestamp at) {
        jdbc.update("INSERT INTO core_crm_form_submission_event(public_id,submission_id,actor_id,event_type,from_status,to_status,occurred_at) "
            + "VALUES(?,?,?,?,?,?,?)","FSEVT_LEGACY_"+submission+"_"+type,submission,actor,type,type.equals("submitted")?null:"pending",
            type.equals("submitted")?"pending":type,at);
    }
    private long revision(long form,long revision) {
        return jdbc.queryForObject("SELECT id FROM core_crm_form_revision WHERE form_id=? AND revision_no=?",Long.class,form,revision);
    }
    private long id(String table,String publicId) {
        require(TABLES.contains(table),"不支持的迁移关联");
        List<Long> ids=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),publicId);
        require(ids.size()==1,"表单迁移缺少准确的关联主体："+table);return ids.get(0);
    }
    private byte[] hex(JsonNode row,String key) {
        String value=text(row,key);require(value.matches("[a-f0-9]{64}"),"历史请求摘要格式无效");return HexFormat.of().parseHex(value);
    }
    private String optional(JsonNode row,String key) { return row.hasNonNull(key)?text(row,key):null; }
    private String encryptOptional(JsonNode row,String key) { String value=optional(row,key);return value==null?null:cipher.encrypt(value); }
    private static void require(boolean condition,String message) { if(!condition) throw invalid(message); }
    private static IllegalArgumentException invalid(String text) { return new IllegalArgumentException(text); }
}
