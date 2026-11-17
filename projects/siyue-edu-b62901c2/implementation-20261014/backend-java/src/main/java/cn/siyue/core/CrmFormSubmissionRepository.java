package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmFormSubmission;
import cn.siyue.core.CrmDtos.CrmFormSubmissionPage;
import cn.siyue.core.CrmDtos.PublicFormReceipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists anonymous submissions encrypted and exposes them only through scoped review queries. */
@Repository
public class CrmFormSubmissionRepository {
    /** Authorized review projection combining a submission with its immutable published form mapping. */
    record ReviewRow(long rowId,long formRowId,long revisionId,String id,String formId,String campusId,
            String activityId,String kind,boolean collectLead,Map<String,String> leadMapping,
            Map<String,Object> answers,String status,long version) { }
    /** Validated anonymous write inputs, including retry identity and optional frozen campaign/trial provenance. */
    record PublicSubmissionWrite(CrmFormRepository.PublicDefinition form,String requestKey,
            String payloadHash,Map<String,Object> answers,String activityId,String trialSlotId,String visitor,String promotionLinkId) {
        PublicSubmissionWrite(CrmFormRepository.PublicDefinition form,String requestKey,String payloadHash,
                Map<String,Object> answers,String activityId) { this(form,requestKey,payloadHash,answers,activityId,null,null,null); }
    }
    /** Previous and resulting submission states captured in an append-only review event. */
    record StatusTransition(String from,String to) { }
    /** Submission event identity, optional employee actor and descriptive transition payload. */
    record SubmissionEventWrite(long submissionId,Long actorId,String type,
            StatusTransition transition,String detail) { }
    /** Validated review decision and any created customer/lead links, bound to the expected submission version. */
    record ReviewWrite(String decision,String note,long expectedVersion,String customerId,String leadId) { }
    /** Stored receipt identity and digest, with optional encrypted legacy source needed for migration-compatible retries. */
    private record RetryRow(String id,byte[] payloadHash,String status,String legacyProfile) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;
    private final MutationRepository mutations;

    /** Wires encrypted answer storage, JSON decoding and the existing deterministic request digest.
     * @param jdbc transaction-bound submission and review database access
     * @param cipher keyring for answers, retained source profiles and review notes
     * @param mapper codec for answers, mappings and migrated replay comparisons
     * @param mutations shared digest implementation used to derive stored request-key hashes
     */
    public CrmFormSubmissionRepository(JdbcTemplate jdbc,PiiCipher cipher,ObjectMapper mapper,MutationRepository mutations){
        this.jdbc=jdbc;this.cipher=cipher;this.mapper=mapper;this.mutations=mutations;
    }

    /** Stores an anonymous submission and its frozen provenance, trial booking and configured reward atomically.
     * @param write published definition, normalized answers, request digest/key, visitor identity and optional trial/promotion references
     * @return new pending receipt or the matching existing receipt; migrated submissions also verify normalized historical content
     * @throws BusinessException if provenance, collection limits, retry content, trial capacity or reward eligibility is invalid
     * @throws JsonProcessingException if answers or retained migrated replay data cannot be encoded or decoded
     */
    public PublicFormReceipt submit(PublicSubmissionWrite write)throws JsonProcessingException {
        CrmFormRepository.PublicDefinition form=write.form();String requestKey=write.requestKey();
        FormAcquisitionRepository acquisition=new FormAcquisitionRepository(jdbc,cipher);
        var source=acquisition.resolve(form.id(),write.activityId(),write.promotionLinkId(),true);
        String payloadHash=write.payloadHash();Map<String,Object> answers=write.answers();String activityId=write.activityId();
        byte[] keyHash=hash(write.visitor()==null?requestKey:form.id()+"|visitor:"+write.visitor()+"|"+requestKey),bodyHash=HexFormat.of().parseHex(payloadHash);
        RetryRow previous=retry(form.formRowId(),keyHash);
        if(previous!=null){return replay(previous,legacyReplayHash(previous,write,bodyHash));}
        byte[] identity=new FormCollectionRepository(jdbc).admit(form,answers,write.visitor());
        String id="FSUB_"+UUID.randomUUID();
        long revisionRow=form.revisionId();
        Long activity=activityId==null||activityId.isBlank()?null:activityRowId(form.formRowId(),activityId);
        try {
            jdbc.update("""
                INSERT INTO core_crm_form_submission
                    (public_id,form_id,revision_id,activity_id,request_key_hash,payload_hash,answers_cipher,identity_hash,submitted_at)
                VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """,id,form.formRowId(),revisionRow,activity,keyHash,bodyHash,cipher.encrypt(mapper.writeValueAsString(answers)),identity);
        } catch(DuplicateKeyException race) {
            RetryRow winner=retry(form.formRowId(),keyHash);
            if(winner!=null){return replay(winner,legacyReplayHash(winner,write,bodyHash));}
            throw race;
        }
        long rowId=jdbc.queryForObject("SELECT id FROM core_crm_form_submission WHERE public_id=?",Long.class,id);
        acquisition.freeze(rowId,source);
        event(new SubmissionEventWrite(rowId,null,"submitted",new StatusTransition(null,"pending"),null));
        var trial=new FormTrialRepository(jdbc,cipher,mapper).book(form,id,answers,write.trialSlotId());
        var reward=new FormRewardRepository(jdbc,cipher,mapper).issue(form,id,answers);
        return new PublicFormReceipt(id,true,"pending",trial,reward);
    }

    /** Validates optional acquisition identifiers before an anonymous form response is served.
     * @param form public form identifier
     * @param activity optional active campaign identifier
     * @param promotion optional campaign promotion-link identifier; it must match the form and campaign
     * @throws BusinessException if a supplied acquisition link is unavailable or inconsistent
     */
    public void validatePromotion(String form,String activity,String promotion) {
        new FormAcquisitionRepository(jdbc,cipher).resolve(form,activity,promotion,false);
    }

    /** Applies form scope and the modern/legacy submission disclosure rule before decrypting answers.
     * @param actor caller whose current grants and publisher/trusted-administrator relationship are checked
     * @param formId form public identifier
     * @param status optional service-validated submission status
     * @param page validated one-based page
     * @param size validated maximum row count
     * @return newest visible submissions and a count using the identical visibility predicate
     * @throws BusinessException if caller scope is invalid or stored answers cannot be decoded
     */
    public CrmFormSubmissionPage list(Actor actor,String formId,String status,int page,int size){
        ScopeSql.Clause scope=ScopeSql.forms(actor);List<Object> args=new ArrayList<>(List.of(formId));args.addAll(scope.args());
        ScopeSql.Clause visibility=LegacyFormSubmissionAccess.visibility(jdbc,actor,false);args.addAll(visibility.args());
        String filter=status==null||status.isBlank()?"":" AND s.status=?";if(!filter.isEmpty()){args.add(status);}
        String where="f.public_id=? AND f.deleted=0 AND s.deleted=0 AND ("+scope.sql()+") AND ("+visibility.sql()+")"+filter;
        Long total=jdbc.queryForObject("SELECT COUNT(s.id) FROM core_crm_form_submission s JOIN core_crm_form f ON f.id=s.form_id WHERE "+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add(Math.multiplyExact((long)page-1,size));
        List<CrmFormSubmission> rows=jdbc.query("""
            SELECT s.public_id submission_public_id,f.public_id form_public_id,r.revision_no,
                   a.public_id activity_public_id,s.submitted_at,s.status,s.answers_cipher,
                   reviewer.public_id reviewer_public_id,s.review_note_cipher,c.public_id customer_public_id,
                   l.public_id lead_public_id,s.version
            FROM core_crm_form_submission s JOIN core_crm_form f ON f.id=s.form_id
            JOIN core_crm_form_revision r ON r.id=s.revision_id
            LEFT JOIN core_activity a ON a.id=s.activity_id
            LEFT JOIN core_principal reviewer ON reviewer.id=s.reviewer_id
            LEFT JOIN core_customer c ON c.id=s.customer_id LEFT JOIN core_crm_lead l ON l.id=s.lead_id
            """ + " WHERE "+where+" ORDER BY s.submitted_at DESC,s.id DESC LIMIT ? OFFSET ?",
            (rs,row)->new CrmFormSubmission(rs.getString("submission_public_id"),rs.getString("form_public_id"),
                rs.getLong("revision_no"),rs.getString("activity_public_id"),rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("status"),answers(rs.getString("answers_cipher")),rs.getString("reviewer_public_id"),
                decrypt(rs.getString("review_note_cipher")),rs.getString("customer_public_id"),rs.getString("lead_public_id"),
                rs.getLong("version")),pageArgs.toArray());
        return new CrmFormSubmissionPage(rows,total==null?0:total,page,size);
    }

    /** Reads a review-eligible submission using form scope and the precise reviewer relationship predicate.
     * @param actor caller whose current reviewer visibility is checked before answers are decrypted
     * @param submissionId submission public identifier
     * @param lock whether to lock the selected submission for a decision in the caller transaction
     * @return review row with published mapping, answers, current state/version and eligible activity link
     * @throws BusinessException if review visibility fails or the stored mapping/answers cannot be decoded
     */
    public ReviewRow reviewRow(Actor actor,String submissionId,boolean lock){
        ScopeSql.Clause scope=ScopeSql.forms(actor);List<Object> args=new ArrayList<>(List.of(submissionId));args.addAll(scope.args());
        ScopeSql.Clause visibility=LegacyFormSubmissionAccess.visibility(jdbc,actor,true);args.addAll(visibility.args());
        List<ReviewRow> rows=jdbc.query("""
            SELECT s.id submission_row_id,f.id form_row_id,r.id revision_id,s.public_id submission_public_id,
                   f.public_id form_public_id,cp.public_id campus_public_id,
                   CASE WHEN a.status<>'active' AND EXISTS(SELECT fa.id FROM core_form_acquisition fa
                       WHERE fa.submission_id=s.id AND fa.deleted=0) THEN NULL ELSE a.public_id END activity_public_id,
                   r.form_kind,r.collect_lead,CAST(r.lead_mapping_json AS CHAR) mapping_json,
                   s.answers_cipher,s.status,s.version
            FROM core_crm_form_submission s JOIN core_crm_form f ON f.id=s.form_id
            JOIN core_campus cp ON cp.id=f.campus_id JOIN core_crm_form_revision r ON r.id=s.revision_id
            LEFT JOIN core_activity a ON a.id=s.activity_id
            WHERE s.public_id=? AND s.deleted=0 AND f.deleted=0 AND (
            """+scope.sql()+") AND ("+visibility.sql()+")"+(lock?" FOR UPDATE":""),(rs,row)->{
                try {
                    String json=rs.getString("mapping_json");
                    Map<String,String> mapping=json==null?Map.of():mapper.readValue(json,mapper.getTypeFactory().constructMapType(Map.class,String.class,String.class));
                    return new ReviewRow(rs.getLong("submission_row_id"),rs.getLong("form_row_id"),rs.getLong("revision_id"),
                        rs.getString("submission_public_id"),rs.getString("form_public_id"),rs.getString("campus_public_id"),
                        rs.getString("activity_public_id"),rs.getString("form_kind"),rs.getBoolean("collect_lead"),mapping,
                        answers(rs.getString("answers_cipher")),rs.getString("status"),rs.getLong("version"));
                }catch(JsonProcessingException ex){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"表单线索映射损坏");}
            },args.toArray());
        if(rows.isEmpty()){throw new BusinessException(ErrorCode.NOT_FOUND,"表单提交不存在或不在授权范围");}return rows.get(0);
    }

    /** Completes a previously authorized pending review and records its immutable decision event.
     * @param actor reviewer already locked and checked by the review service
     * @param row authorized submission row whose identity is retained throughout the transaction
     * @param write validated decision, note, expected version and resulting customer/lead identifiers
     * @return reviewed submission; rejection cancels a confirmed trial, while approval preserves acquisition provenance
     * @throws BusinessException if linked records are missing or the pending status/version changed
     */
    public CrmFormSubmission finishReview(Actor actor,ReviewRow row,ReviewWrite write){
        String next=write.decision().equals("approve")?"approved":"rejected";
        Long customer=write.customerId()==null?null:internalId("core_customer",write.customerId());
        Long lead=write.leadId()==null?null:internalId("core_crm_lead",write.leadId());
        int changed=jdbc.update("""
            UPDATE core_crm_form_submission SET status=?,reviewer_id=?,review_note_cipher=?,reviewed_at=UTC_TIMESTAMP(3),
                customer_id=?,lead_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status='pending' AND deleted=0
            """,next,actor.id(),cipher.encrypt(write.note().trim()),customer,lead,row.rowId(),write.expectedVersion());
        if(changed!=1){throw new BusinessException(ErrorCode.ALREADY_EXISTS,"表单提交状态或版本已变化");}
        if(next.equals("rejected")) {
            jdbc.update("UPDATE core_trial_booking SET status='cancelled',cancelled_at=UTC_TIMESTAMP(3),cancelled_by=?,"
                + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE submission_id=? AND status='confirmed' AND deleted=0",
                actor.id(),row.rowId());
        }
        if(next.equals("approved"))new FormAcquisitionRepository(jdbc,cipher).approved(actor,row.rowId(),customer,lead);
        event(new SubmissionEventWrite(row.rowId(),actor.id(),next,new StatusTransition("pending",next),write.note()));
        return listOne(actor,row.id());
    }

    private CrmFormSubmission listOne(Actor actor,String id){
        ScopeSql.Clause scope=ScopeSql.forms(actor);List<Object> args=new ArrayList<>(List.of(id));args.addAll(scope.args());
        List<CrmFormSubmission> rows=jdbc.query("""
            SELECT s.public_id submission_public_id,f.public_id form_public_id,r.revision_no,
                   a.public_id activity_public_id,s.submitted_at,s.status,s.answers_cipher,
                   reviewer.public_id reviewer_public_id,s.review_note_cipher,c.public_id customer_public_id,
                   l.public_id lead_public_id,s.version
            FROM core_crm_form_submission s JOIN core_crm_form f ON f.id=s.form_id
            JOIN core_crm_form_revision r ON r.id=s.revision_id LEFT JOIN core_activity a ON a.id=s.activity_id
            LEFT JOIN core_principal reviewer ON reviewer.id=s.reviewer_id
            LEFT JOIN core_customer c ON c.id=s.customer_id LEFT JOIN core_crm_lead l ON l.id=s.lead_id
            WHERE s.public_id=? AND s.deleted=0 AND f.deleted=0 AND (
            """+scope.sql()+")",(rs,row)->new CrmFormSubmission(rs.getString("submission_public_id"),
                rs.getString("form_public_id"),rs.getLong("revision_no"),rs.getString("activity_public_id"),
                rs.getTimestamp("submitted_at").toInstant(),rs.getString("status"),answers(rs.getString("answers_cipher")),
                rs.getString("reviewer_public_id"),decrypt(rs.getString("review_note_cipher")),
                rs.getString("customer_public_id"),rs.getString("lead_public_id"),rs.getLong("version")),args.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"表单提交不存在或不在授权范围");return rows.get(0);
    }
    private RetryRow retry(long formId,byte[] keyHash){
        List<RetryRow> rows=jdbc.query("SELECT public_id,payload_hash,status,source_profile_cipher FROM core_crm_form_submission WHERE form_id=? AND request_key_hash=? AND deleted=0",
            (rs,row)->new RetryRow(rs.getString("public_id"),rs.getBytes("payload_hash"),rs.getString("status"),rs.getString("source_profile_cipher")),formId,keyHash);
        return rows.isEmpty()?null:rows.get(0);
    }
    private byte[] legacyReplayHash(RetryRow previous,PublicSubmissionWrite write,byte[] current) throws JsonProcessingException {
        if(previous.legacyProfile()==null)return current;
        var source=mapper.readTree(cipher.decrypt(previous.legacyProfile()));
        Map<String,Object> normalized=new java.util.LinkedHashMap<>();
        for(var field:write.form().fields()) {
            if(java.util.Set.of("description","divider","image").contains(field.type()))continue;
            normalized.put(field.id(),write.answers().getOrDefault(field.id(),field.type().equals("multiple")?List.of():""));
        }
        List<String> slots=jdbc.query("SELECT s.slot_key FROM core_trial_booking b JOIN core_form_trial_slot s ON s.id=b.slot_id "
            +"JOIN core_crm_form_submission f ON f.id=b.submission_id WHERE f.public_id=? AND b.deleted=0",
            (rs,row)->rs.getString(1),previous.id());
        String slot=slots.isEmpty()?null:slots.get(0);
        var acquisition=source.path("acquisitionSnapshot");
        String activity=acquisition.path("campaignId").isTextual()?acquisition.path("campaignId").asText():null;
        String promotion=acquisition.path("linkId").isTextual()?acquisition.path("linkId").asText():null;
        if(!java.util.Objects.equals(activity,write.activityId()) || !java.util.Objects.equals(promotion,write.promotionLinkId())
                || !mapper.valueToTree(normalized).equals(source.path("answersById"))
                || !java.util.Objects.equals(slot,write.trialSlotId())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"提交编号已用于不同内容");
        }
        return previous.payloadHash();
    }
    private PublicFormReceipt replay(RetryRow previous,byte[] bodyHash){
        if(!java.security.MessageDigest.isEqual(previous.payloadHash(),bodyHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"提交编号已用于不同内容");
        return new PublicFormReceipt(previous.id(),true,previous.status(),new FormTrialRepository(jdbc,cipher,mapper).receipt(previous.id()),new FormRewardRepository(jdbc,cipher,mapper).receipt(previous.id()));
    }
    private long activityRowId(long formId,String activityPublicId){
        List<Long> rows=jdbc.query("""
            SELECT a.id FROM core_activity_form af JOIN core_activity a ON a.id=af.activity_id
            WHERE af.form_id=? AND af.deleted=0 AND a.public_id=? AND a.status='active' AND a.deleted=0
            """,(rs,row)->rs.getLong(1),formId,activityPublicId);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"活动表单链接无效");return rows.get(0);
    }
    private long internalId(String table,String publicId){
        if(!SetHolder.TABLES.contains(table))throw new IllegalArgumentException("Unsupported table");
        List<Long> ids=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,row)->rs.getLong(1),publicId);
        if(ids.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"表单审核关联记录不存在");return ids.get(0);
    }
    private void event(SubmissionEventWrite write){
        jdbc.update("""
            INSERT INTO core_crm_form_submission_event(public_id,submission_id,actor_id,event_type,from_status,to_status,detail_cipher,occurred_at)
            VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
            ""","FSEVT_"+UUID.randomUUID(),write.submissionId(),write.actorId(),write.type(),
            write.transition().from(),write.transition().to(),encrypted(write.detail()));
    }
    private Map<String,Object> answers(String encrypted){
        try{return mapper.readValue(cipher.decrypt(encrypted),mapper.getTypeFactory().constructMapType(Map.class,String.class,Object.class));}
        catch(Exception ex){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"表单答案解密失败");}
    }
    private String encrypted(String value){return value==null||value.isBlank()?null:cipher.encrypt(value.trim());}
    private String decrypt(String value){return value==null?null:cipher.decrypt(value);}
    private byte[] hash(String input){return HexFormat.of().parseHex(mutations.hash(input));}
    /** Fixed table allowlist for resolving review-created customer and lead identifiers. */
    private static final class SetHolder { private static final java.util.Set<String> TABLES=java.util.Set.of("core_customer","core_crm_lead"); }
}
