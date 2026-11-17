package cn.siyue.core;

import cn.siyue.core.CrmDtos.ActivityFormLink;
import cn.siyue.core.CrmDtos.FormFieldInput;
import cn.siyue.core.CrmDtos.MarketingForm;
import cn.siyue.core.CrmDtos.MarketingFormPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores versioned marketing forms and their retained acquisition-activity links. */
@Repository
public class CrmFormRepository {
    /** Resolved immutable form revision and optional validated acquisition activity used by anonymous read/write services. */
    record PublicDefinition(long formRowId,long revisionId,String id,String campusId,String title,
            String description,String kind,boolean collectLead,long revision,List<FormFieldInput> fields,
            Map<String,String> leadMapping,String activityId) { }
    /** Scoped persisted form definition retaining encrypted description and serialized field/mapping data until projection. */
    private record FormRow(long rowId, String id, String campusId, String ownerId, String title,
            String descriptionCipher, String kind, boolean collectLead, String status,
            Long revision, String fieldsJson, String mappingJson, long version) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** Wires form definitions, immutable published revisions and encrypted descriptive fields.
     * @param jdbc database access participating in the form service transaction
     * @param cipher keyring for form descriptions and revision-dependent integrations
     * @param mapper codec for typed field definitions and acquisition mappings
     */
    public CrmFormRepository(JdbcTemplate jdbc, PiiCipher cipher,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    /** Creates a caller-owned draft in an authorized campus.
     * @param actor caller already authorized for form creation by the service
     * @param input validated title, kind, fields, collection flag and lead-field mapping
     * @return newly created draft definition and version
     * @throws BusinessException if the campus is unavailable or outside the caller's scope
     * @throws com.fasterxml.jackson.core.JsonProcessingException if fields or mappings cannot be encoded
     */
    public MarketingForm create(Actor actor, CrmDtos.MarketingFormInput input) throws com.fasterxml.jackson.core.JsonProcessingException {
        long campus = campus(actor, input.campusId(), true);
        String id = "FORM_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_form
                (public_id,campus_id,owner_id,title,description_cipher,form_kind,collect_lead,fields_json,lead_mapping_json)
            VALUES (?,?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON))
            """, id,campus,actor.id(),input.title().trim(),encrypted(input.description()),input.formKind(),
            input.collectLead(),mapper.writeValueAsString(input.fields()),json(input.leadFieldMapping()));
        return form(actor,id,false);
    }

    /** Replaces draft fields after locking the scoped form and checking its expected version.
     * @param actor caller whose form and destination campus scopes are checked
     * @param id form public identifier
     * @param input validated replacement draft fields and collection mapping
     * @param expectedVersion exact draft version read by the caller
     * @return updated draft with version advanced once
     * @throws BusinessException if scope, draft status, campus or optimistic version rejects the update
     * @throws com.fasterxml.jackson.core.JsonProcessingException if fields or mappings cannot be encoded
     */
    public MarketingForm update(Actor actor, String id, CrmDtos.MarketingFormInput input,
            long expectedVersion) throws com.fasterxml.jackson.core.JsonProcessingException {
        FormRow current = row(actor,id,true);
        if (!current.status().equals("draft")) { throw invalid("已发布表单需复制为草稿后修改"); }
        if (current.version()!=expectedVersion) { throw conflict("表单版本已变化"); }
        long campus = campus(actor,input.campusId(),true);
        String fieldsJson=mapper.writeValueAsString(input.fields());
        String mappingJson=json(input.leadFieldMapping());
        int changed=jdbc.update("""
            UPDATE core_crm_form SET campus_id=?,title=?,description_cipher=?,form_kind=?,collect_lead=?,
                fields_json=CAST(? AS JSON),lead_mapping_json=CAST(? AS JSON),version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status='draft' AND deleted=0
            """,campus,input.title().trim(),encrypted(input.description()),input.formKind(),input.collectLead(),
            fieldsJson,mappingJson,current.rowId(),expectedVersion);
        if(changed!=1){throw conflict("表单版本已变化");}
        return form(actor,id,false);
    }

    /** Snapshots a draft and its trial/reward configuration into a new immutable published revision.
     * @param actor caller already authorized for publishing by the service
     * @param id form public identifier
     * @param expectedVersion exact draft version to publish
     * @return published form with its new revision and advanced version
     * @throws BusinessException if scope, draft/version, collection recipient, trial slots or reward eligibility is invalid
     */
    public MarketingForm publish(Actor actor,String id,long expectedVersion) {
        FormRow current=row(actor,id,true);
        if(!current.status().equals("draft") || current.version()!=expectedVersion) { throw conflict("表单状态或版本已变化"); }
        long revision=current.revision()==null?1:current.revision()+1;
        new FormCollectionRepository(jdbc).validatePublish(current.rowId());
        new FormTrialRepository(jdbc,cipher,mapper).publish(actor,form(actor,id,false),revision);
        new FormRewardRepository(jdbc,cipher,mapper).publish(actor,form(actor,id,false),revision);
        jdbc.update("""
            INSERT INTO core_crm_form_revision
                (public_id,form_id,revision_no,title,description_cipher,form_kind,collect_lead,
                 fields_json,lead_mapping_json,published_by,published_at,collection_source)
            VALUES (?,?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,UTC_TIMESTAMP(3),?)
            """, "FREV_"+UUID.randomUUID(),current.rowId(),revision,current.title(),current.descriptionCipher(),
            current.kind(),current.collectLead(),current.fieldsJson(),current.mappingJson(),actor.id(),
            new FormCollectionRepository(jdbc).read(current.rowId()).source());
        int changed=jdbc.update("""
            UPDATE core_crm_form SET status='published',current_revision=?,version=version+1,
                updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND status='draft' AND deleted=0
            """,revision,current.rowId(),expectedVersion);
        if(changed!=1){throw conflict("表单版本已变化");}
        return form(actor,id,false);
    }

    /** Closes public intake without deleting published revisions or received submissions.
     * @param actor caller whose scoped form row is locked
     * @param id form public identifier
     * @param expectedVersion exact current form version
     * @return closed form with version advanced once
     * @throws BusinessException if the form is unavailable, outside scope or stale
     */
    public MarketingForm unpublish(Actor actor,String id,long expectedVersion) {
        FormRow current=row(actor,id,true);
        if(current.version()!=expectedVersion){throw conflict("表单版本已变化");}
        int changed=jdbc.update("""
            UPDATE core_crm_form SET status='closed',version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """,current.rowId(),expectedVersion);
        if(changed!=1){throw conflict("表单版本已变化");}
        return form(actor,id,false);
    }

    /** Lists retained form definitions under the caller's current form scope.
     * @param actor caller whose form visibility predicate is applied
     * @param campusId optional public campus identifier filter
     * @param status optional form state, validated by the service
     * @param page validated one-based page
     * @param size validated maximum row count
     * @return form definitions and matching count, newest update first
     * @throws BusinessException if scope is unsupported or a stored form definition cannot be decoded
     */
    public MarketingFormPage list(Actor actor,String campusId,String status,int page,int size) {
        ScopeSql.Clause scope=formScope(actor);
        List<Object> args=new ArrayList<>(scope.args());String filter="";
        if(campusId!=null&&!campusId.isBlank()){filter+=" AND cp.public_id=?";args.add(campusId);}
        if(status!=null&&!status.isBlank()){filter+=" AND f.status=?";args.add(status);}
        String where="f.deleted=0 AND ("+scope.sql()+")"+filter;
        Long total=jdbc.queryForObject("SELECT COUNT(f.id) FROM core_crm_form f JOIN core_campus cp ON cp.id=f.campus_id WHERE "+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add(Math.multiplyExact((long)page-1,size));
        List<String> ids=jdbc.query("SELECT f.public_id FROM core_crm_form f JOIN core_campus cp ON cp.id=f.campus_id WHERE "+where+" ORDER BY f.updated_at DESC,f.id DESC LIMIT ? OFFSET ?",
            (rs,row)->rs.getString(1),pageArgs.toArray());
        return new MarketingFormPage(ids.stream().map(id->form(actor,id,false)).toList(),total==null?0:total,page,size);
    }

    /** Reads one retained form definition after checking current scope.
     * @param actor caller whose form visibility is applied
     * @param id form public identifier
     * @return decrypted description, typed fields, mapping and current draft/publication versions
     * @throws BusinessException if the form is missing, out of scope or its stored definition is invalid
     */
    public MarketingForm read(Actor actor,String id){return form(actor,id,false);}

    /** Adds the modern published-form activity link while retaining existing link history.
     * @param actor caller whose activity and form scopes are checked
     * @param activityId activity public identifier
     * @param formId published form public identifier
     * @return link projection, including an already-existing identical link
     * @throws BusinessException if either record is out of scope, the form is not published or campuses differ
     */
    public ActivityFormLink link(Actor actor,String activityId,String formId) {
        long activity=activity(actor,activityId,true);
        FormRow form=row(actor,formId,true);
        long activityCampus=jdbc.queryForObject("SELECT campus_id FROM core_activity WHERE id=?",Long.class,activity);
        if(form.status().equals("closed")||!form.status().equals("published")){throw invalid("只能关联已发布表单");}
        if(activityCampus!=campusId(form.rowId())){throw invalid("活动与表单必须属于同一校区");}
        try { jdbc.update("INSERT INTO core_activity_form(activity_id,form_id,linked_by) VALUES (?,?,?)",activity,form.rowId(),actor.id()); }
        catch(DuplicateKeyException duplicate) { /* An existing link is an idempotent business result. */ }
        return linkRow(activityId,formId);
    }

    /** Applies the exact original versioned campaign link operation in the caller transaction. */
    ActivityFormLink linkCampaign(Actor actor,String activityId,CrmDtos.ActivityFormLinkInput input) {
        new LegacyCampaignFormLink(jdbc).link(actor,activityId,input);
        return linkRow(activityId,input.formId());
    }
    /** Checks original campaign write scope before returning a stored mutation receipt. */
    void authorizeCampaignLink(Actor actor,String activityId) {
        new LegacyCampaignFormLink(jdbc).authorize(actor,activityId,false);
    }

    /** Lists retained activity-form links, including each form's current publication state.
     * @param actor caller whose activity visibility and form scope are checked
     * @param activityId activity public identifier
     * @return visible nondeleted links in insertion order; closed forms are not represented as published
     * @throws BusinessException if the activity is missing or outside scope
     */
    public List<ActivityFormLink> activityForms(Actor actor,String activityId) {
        long activity=activity(actor,activityId,false);
        ScopeSql.Clause scope=formScope(actor);
        List<Object> args=new ArrayList<>(List.of(activity));args.addAll(scope.args());
        String sql="""
            SELECT a.public_id activity_public_id,f.public_id form_public_id,f.title,f.status,
                   COALESCE(f.current_revision,0) revision,af.created_at
            FROM core_activity_form af JOIN core_activity a ON a.id=af.activity_id
            JOIN core_crm_form f ON f.id=af.form_id
            WHERE af.activity_id=? AND af.deleted=0 AND f.deleted=0 AND (
            """+scope.sql()+") ORDER BY af.id";
        return jdbc.query(sql,(rs,row)->new ActivityFormLink(rs.getString("activity_public_id"),rs.getString("form_public_id"),
            rs.getString("title"),rs.getString("status"),rs.getLong("revision"),rs.getTimestamp("created_at").toInstant()),args.toArray());
    }

    /** Checks campus visibility before a form mutation can be replayed.
     * @param actor caller whose all/campus eligibility is required
     * @param campusId campus public identifier
     * @throws BusinessException if the campus is missing or outside authorized scope
     */
    public void authorizeCampus(Actor actor,String campusId){campus(actor,campusId,false);}
    /** Checks current form scope before a retained mutation receipt is returned.
     * @param actor caller whose form visibility is required
     * @param id form public identifier
     * @throws BusinessException if the form is missing or outside scope
     */
    public void authorizeForm(Actor actor,String id){row(actor,id,false);}
    /** Checks current activity scope before an activity/form mutation receipt is returned.
     * @param actor caller whose activity visibility is required
     * @param id activity public identifier
     * @throws BusinessException if the activity is missing or outside scope
     */
    public void authorizeActivity(Actor actor,String id){activity(actor,id,false);}

    /** Reads the current immutable revision of a publicly available form and validates any activity link.
     * @param formId public form identifier
     * @param activityId optional activity identifier; when supplied it must be active and linked to this form
     * @return published fields and acquisition mapping, without submission answers or employee credentials
     * @throws BusinessException if the form/link is unavailable or its published definition is corrupt
     */
    public PublicDefinition publicDefinition(String formId,String activityId) {
        boolean filterActivity=activityId!=null&&!activityId.isBlank();
        String activityJoin=filterActivity
            ?"JOIN core_activity_form af ON af.form_id=f.id AND af.deleted=0 JOIN core_activity a ON a.id=af.activity_id AND a.public_id=? AND a.status='active' AND a.deleted=0"
            :"";
        String activitySelect=filterActivity?"a.public_id":"NULL";
        List<Object> args=new ArrayList<>();if(filterActivity){args.add(activityId);}args.add(formId);
        String sql="""
            SELECT f.id form_row_id,r.id revision_id,f.public_id,cp.public_id campus_public_id,
                   r.title,r.description_cipher,r.form_kind,r.collect_lead,r.revision_no,
                   CAST(r.fields_json AS CHAR) fields_json,CAST(r.lead_mapping_json AS CHAR) mapping_json,
            """ + activitySelect + """
                   activity_public_id
            FROM core_crm_form f JOIN core_campus cp ON cp.id=f.campus_id
            JOIN core_crm_form_revision r ON r.form_id=f.id AND r.revision_no=f.current_revision AND r.deleted=0
            """ + activityJoin + "\n" + """
            WHERE f.public_id=? AND f.deleted=0 AND f.status='published'
            """;
        List<PublicDefinition> rows=jdbc.query(sql,(rs,n)->{
                try {
                    List<FormFieldInput> fields=List.of(mapper.readValue(rs.getString("fields_json"),FormFieldInput[].class));
                    Map<String,String> mapping=rs.getString("mapping_json")==null?Map.of():mapper.readValue(rs.getString("mapping_json"),mapper.getTypeFactory().constructMapType(Map.class,String.class,String.class));
                    return new PublicDefinition(rs.getLong("form_row_id"),rs.getLong("revision_id"),rs.getString("public_id"),
                        rs.getString("campus_public_id"),rs.getString("title"),decrypt(rs.getString("description_cipher")),
                        rs.getString("form_kind"),rs.getBoolean("collect_lead"),rs.getLong("revision_no"),fields,mapping,
                        rs.getString("activity_public_id"));
                } catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"表单发布定义损坏");}
            },args.toArray());
        if(rows.isEmpty()){throw new BusinessException(ErrorCode.NOT_FOUND,"表单未发布或链接无效");}return rows.get(0);
    }

    /** Locks a retained form row before configuration services check its current state.
     * @param id public form identifier; this method itself does not authorize an employee or require draft status
     * @throws org.springframework.dao.EmptyResultDataAccessException if the retained form does not exist
     */
    public void lockPublicOrDraft(String id) {
        jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=? AND deleted=0 FOR UPDATE",Long.class,id);
    }
    /** Advances a retained draft's version after a configuration update in the same transaction.
     * @param id form public identifier already authorized and locked by the configuration service
     */
    public void advanceDraftVersion(String id) {
        jdbc.update("UPDATE core_crm_form SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE public_id=? AND status='draft' AND deleted=0",id);
    }
    /** Reads collection settings for a resolved published form.
     * @param definition previously resolved public definition containing the internal form identifier
     * @return current submission limits, source and collection owner configuration
     */
    public FormCollectionRepository.Settings collection(PublicDefinition definition) {
        return new FormCollectionRepository(jdbc).read(definition.formRowId());
    }
    /** Locks a currently published form before checking its immutable revision and optional active activity link.
     * @param formId public form identifier
     * @param activityId optional active linked activity identifier
     * @return the current published definition under the caller transaction's form lock
     * @throws BusinessException if intake has stopped, the activity link is invalid or the stored revision is corrupt
     */
    public PublicDefinition lockPublicDefinition(String formId,String activityId) {
        List<Long> rows=jdbc.query("SELECT id FROM core_crm_form WHERE public_id=? AND status='published' AND deleted=0 FOR UPDATE",
            (rs,n)->rs.getLong(1),formId);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"表单未发布或已停止");
        return publicDefinition(formId,activityId);
    }

    /** Projects the reward metadata frozen for this published form revision.
     * @param definition previously resolved current public definition
     * @return public reward description, or null when the revision has no reward configuration
     * @throws BusinessException if a configured coupon no longer resolves to a retained offer
     */
    public Map<String,Object> publicReward(PublicDefinition definition) {
        return new FormRewardRepository(jdbc,cipher,mapper).publicReward(definition);
    }

    /** Projects enabled trial fields and currently available slots for the published revision.
     * @param definition previously resolved current public definition
     * @return public trial configuration and availability, or null when no enabled trial configuration exists
     */
    public FormTrialDtos.PublicTrial publicTrial(PublicDefinition definition) {
        return new FormTrialRepository(jdbc,cipher,mapper).publicTrial(definition);
    }

    private MarketingForm form(Actor actor,String id,boolean lock) {
        FormRow row=row(actor,id,lock);
        try {
            List<FormFieldInput> fields=List.of(mapper.readValue(row.fieldsJson(),FormFieldInput[].class));
            Map<String,String> mapping=row.mappingJson()==null?Map.of():mapper.readValue(row.mappingJson(),mapper.getTypeFactory().constructMapType(Map.class,String.class,String.class));
            return new MarketingForm(row.id(),row.campusId(),row.ownerId(),row.title(),decrypt(row.descriptionCipher()),row.kind(),
                row.collectLead(),row.status(),row.revision()==null?0:row.revision(),fields,mapping,row.version());
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"表单定义数据损坏"); }
    }

    private FormRow row(Actor actor,String publicId,boolean lock) {
        ScopeSql.Clause scope=formScope(actor);List<Object> args=new ArrayList<>(List.of(publicId));args.addAll(scope.args());
        List<FormRow> rows=jdbc.query("""
            SELECT f.id,f.public_id,cp.public_id campus_public_id,p.public_id owner_public_id,f.title,
                   f.description_cipher,f.form_kind,f.collect_lead,f.status,f.current_revision,
                   CAST(f.fields_json AS CHAR) fields_json,CAST(f.lead_mapping_json AS CHAR) mapping_json,f.version
            FROM core_crm_form f JOIN core_campus cp ON cp.id=f.campus_id JOIN core_principal p ON p.id=f.owner_id
            WHERE f.public_id=? AND f.deleted=0 AND (
            """+scope.sql()+")"+(lock?" FOR UPDATE":""),(rs,n)->{
                long revision=rs.getLong("current_revision");Long current=rs.wasNull()?null:revision;
                return new FormRow(rs.getLong("id"),rs.getString("public_id"),rs.getString("campus_public_id"),
                    rs.getString("owner_public_id"),rs.getString("title"),rs.getString("description_cipher"),
                    rs.getString("form_kind"),rs.getBoolean("collect_lead"),rs.getString("status"),current,
                    rs.getString("fields_json"),rs.getString("mapping_json"),rs.getLong("version"));
            },args.toArray());
        if(rows.isEmpty()){throw new BusinessException(ErrorCode.NOT_FOUND,"表单不存在或不在授权范围");}return rows.get(0);
    }

    private ActivityFormLink linkRow(String activityId,String formId) {
        return jdbc.queryForObject("""
            SELECT a.public_id activity_public_id,f.public_id form_public_id,f.title,f.status,
                   COALESCE(f.current_revision,0) revision,af.created_at FROM core_activity_form af
            JOIN core_activity a ON a.id=af.activity_id JOIN core_crm_form f ON f.id=af.form_id
            WHERE a.public_id=? AND f.public_id=? AND af.deleted=0 AND f.deleted=0
            """,(rs,row)->new ActivityFormLink(rs.getString("activity_public_id"),rs.getString("form_public_id"),
                rs.getString("title"),rs.getString("status"),rs.getLong("revision"),rs.getTimestamp("created_at").toInstant()),activityId,formId);
    }

    private long activity(Actor actor,String id,boolean lock) {
        ScopeSql.Clause scope=ScopeSql.activities(actor);List<Object> args=new ArrayList<>(List.of(id));args.addAll(scope.args());
        List<Long> rows=jdbc.query("SELECT a.id FROM core_activity a WHERE a.public_id=? AND a.deleted=0 AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),(rs,row)->rs.getLong(1),args.toArray());
        if(rows.isEmpty()){throw new BusinessException(ErrorCode.NOT_FOUND,"活动不存在或不在授权范围");}return rows.get(0);
    }

    private long campus(Actor actor,String id,boolean lock) {
        String campusScope=actor.scope().equals("all")?"1=1":"EXISTS (SELECT pc.id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.campus_id=cp.id AND pc.deleted=0)";
        List<Object> args=new ArrayList<>(List.of(id));if(!actor.scope().equals("all")){args.add(actor.id());}
        List<Long> rows=jdbc.query("SELECT cp.id FROM core_campus cp WHERE cp.public_id=? AND cp.deleted=0 AND ("+campusScope+")"+(lock?" FOR UPDATE":""),(rs,row)->rs.getLong(1),args.toArray());
        if(rows.isEmpty()){throw new BusinessException(ErrorCode.NOT_FOUND,"校区不存在或不在授权范围");}return rows.get(0);
    }

    private long campusId(long formId){return jdbc.queryForObject("SELECT campus_id FROM core_crm_form WHERE id=?",Long.class,formId);}
    private ScopeSql.Clause formScope(Actor actor){return ScopeSql.forms(actor);}
    private String encrypted(String text){return text==null||text.isBlank()?null:cipher.encrypt(text.trim());}
    private String decrypt(String value){return value==null?null:cipher.decrypt(value);}
    private String json(Map<String,String> value) throws com.fasterxml.jackson.core.JsonProcessingException{return value==null||value.isEmpty()?null:mapper.writeValueAsString(value);}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_INPUT,message);}
    private static BusinessException conflict(String message){return new BusinessException(ErrorCode.ALREADY_EXISTS,message);}
}
