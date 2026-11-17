package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.array;
import static cn.siyue.core.LegacyCatalogRehearsal.date;
import static cn.siyue.core.LegacyCatalogRehearsal.hash;
import static cn.siyue.core.LegacyCatalogRehearsal.positive;
import static cn.siyue.core.LegacyCatalogRehearsal.text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Offline import of student communication, CRM consultation and service ticket history.
 * Imports only explicitly mapped records whose source identities and audit provenance can be verified.
 * @see LegacyMigrationPhaseService
 * @see PiiCipher
 */
public final class LegacyServiceHistoryRehearsal {
    private static final Set<String> LOOKUPS=Set.of("core_principal","core_campus","core_student","core_class",
        "core_customer","core_activity","core_opportunity","core_customer_touchpoint","core_enrollment",
        "core_crm_refund_request","core_crm_service_ticket_type","core_crm_service_ticket",
        "core_crm_consultation","core_communication");
    private static final List<String> COLLECTIONS=List.of("studentCommunications","serviceTickets","consultations");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;

    /** Creates the rehearsal importer with a transaction phase backed by the supplied manager.
     * @param jdbc JDBC template for target-schema lookups and writes
     * @param cipher cipher used to encrypt imported source payloads and personal fields
     * @param manager transaction manager for atomic phase execution
     */
    public LegacyServiceHistoryRehearsal(JdbcTemplate jdbc,PiiCipher cipher,PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyServiceHistoryRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc=jdbc;this.cipher=cipher;this.transaction=phases;
    }

    /** Imports only rows with exact source identity and audit provenance, after catalog and CRM phases.
     * This public rehearsal entry point accepts only an authorized {@code *_test} schema. Repeated execution
     * with the same revision and source hash returns the recorded row count without duplicating imported rows.
     * @param envelope source snapshot containing schema/revision, state and audit provenance
     * @return number of history rows imported in the migration phase, or the existing checkpoint count
     * @throws IllegalArgumentException if the envelope, source mapping, audit provenance, prerequisites, or
     *                                  target schema fails closed validation
     * @throws org.springframework.dao.DataAccessException if target database access or persistence fails
     */
    public int importServiceHistory(JsonNode envelope) {
        return importServiceHistory(envelope, null);
    }

    /** Candidate-only entry point; ordinary rehearsal calls remain restricted to *_test schemas. */
    int importServiceHistory(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database,jdbc,envelope,authorization);
        if(!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())) throw invalid("快照格式不支持");
        if(!envelope.path("revision").isIntegralNumber()) throw invalid("快照版本无效");
        JsonNode state=envelope.path("state"),audit=envelope.path("auditProvenance");
        if(!audit.isArray()) throw invalid("迁移审计摘要缺失");
        requireCheckpoint("academic_catalog_v1",envelope.path("revision").longValue(),hash(state.toString()));
        ObjectNode crmPayload=JsonNodeFactory.instance.objectNode();
        for(String key:List.of("customers","campaigns","touchpoints","leads","opportunities")) crmPayload.set(key,state.path(key).deepCopy());
        requireCheckpoint("crm_households_v1",envelope.path("revision").longValue(),hash(crmPayload.toString()));
        ObjectNode payload=JsonNodeFactory.instance.objectNode();
        for(String key:COLLECTIONS) payload.set(key,state.path(key).deepCopy());
        payload.set("auditProvenance",audit.deepCopy());
        int rows=count(payload);String source=payload.toString(),fingerprint=hash(source);
        return transaction.execute(() ->{
            List<Checkpoint> previous=jdbc.query("SELECT source_revision,source_hash,imported_rows FROM core_migration_checkpoint WHERE phase='crm_service_history_v1' FOR UPDATE",
                (rs,n)->new Checkpoint(rs.getLong(1),rs.getString(2),rs.getInt(3)));
            if(!previous.isEmpty()) {
                Checkpoint old=previous.get(0);
                if(old.revision()!=envelope.path("revision").longValue()||!old.hash().equals(fingerprint)) throw invalid("咨询/沟通/工单来源变化，禁止覆盖已导入历史");
                return old.rows();
            }
            jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) VALUES('crm_service_history_v1',?,?,?,?)",
                envelope.path("revision").longValue(),fingerprint,cipher.encrypt(source),rows);
            for(JsonNode row:array(payload,"studentCommunications")) communication(row,audit);
            for(JsonNode row:array(payload,"consultations")) consultation(row,audit);
            for(JsonNode row:array(payload,"serviceTickets")) ticket(row,audit);
            reconcile(payload);
            return rows;
        });
    }

    private record Checkpoint(long revision,String hash,int rows) { }
    private void requireCheckpoint(String phase,long revision,String sourceHash) {
        Integer count=jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase=? AND source_revision=? AND source_hash=? AND deleted=0",Integer.class,phase,revision,sourceHash);
        if(count==null||count!=1) throw invalid("缺少同revision前置迁移阶段："+phase);
    }
    private int count(JsonNode payload) {
        int rows=1;
        for(String collection:COLLECTIONS) rows=Math.addExact(rows,array(payload,collection).size());
        for(JsonNode consultation:array(payload,"consultations")) rows=Math.addExact(rows,array(consultation,"history").size());
        for(JsonNode ticket:array(payload,"serviceTickets")) {
            rows=Math.addExact(rows,array(ticket,"history").size());
            rows=Math.addExact(rows,auditMatches(payload.path("auditProvenance"),"serviceTickets",text(ticket,"id")).size());
        }
        if(rows>=1000) throw invalid("服务历史迁移阶段超过单事务行数限制");return rows-1;
    }
    private void communication(JsonNode row,JsonNode audit) {
        String channel=text(row,"channel");if(!Set.of("phone","wecom","text").contains(channel)) throw invalid("学生沟通渠道未映射");
        JsonNode event=uniqueAudit(audit,"studentCommunications",text(row,"id"),"student.communicate");
        String actor=text(row,"actorId");if(!actor.equals(text(event,"actorId"))) throw invalid("学生沟通记录人与审计操作人不一致");
        Timestamp created=date(row,"createdAt");date(event,"at");
        long student=id("core_student",row,"studentId"),klass=id("core_class",row,"classId"),campus=id("core_campus",row,"campusId");
        Long classCampus=jdbc.queryForObject("SELECT campus_id FROM core_class WHERE id=?",Long.class,klass);
        if(classCampus!=campus) throw invalid("学生沟通记录班级与校区不一致");
        jdbc.update("INSERT INTO core_communication(public_id,source_audit_event_id,student_id,class_id,actor_id,channel,content_cipher,next_follow_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),text(event,"id"),student,klass,id("core_principal",actor),channel,cipher.encrypt(text(row,"note")),optionalDate(row,"nextFollowAt"),created,created);
    }
    private void consultation(JsonNode row,JsonNode audit) {
        if(!"recorded".equals(text(row,"status"))) throw invalid("咨询状态未映射");
        String channel=text(row,"channel");if(!Set.of("phone","wechat","visit","online","other").contains(channel)) throw invalid("咨询渠道未映射");
        JsonNode auditEvent=uniqueAudit(audit,"consultations",text(row,"id"),"crm.consultation.register");
        String creator=text(row,"createdBy"),owner=text(row,"ownerId");
        if(!creator.equals(text(auditEvent,"actorId"))||!creator.equals(owner)) throw invalid("咨询创建人/负责人与创建审计操作人不一致");
        date(auditEvent,"at");
        Long campus=optionalId("core_campus",row,"campusId"),activity=optionalId("core_activity",row,"campaignId");
        Long opportunity=optionalId("core_opportunity",row,"opportunityId"),touchpoint=optionalId("core_customer_touchpoint",row,"touchpointId");
        long consultationId;
        jdbc.update("INSERT INTO core_crm_consultation(public_id,source_audit_event_id,customer_id,campus_id,owner_id,created_by,activity_id,opportunity_id,touchpoint_id,title_cipher,customer_name_cipher,campus_name_cipher,owner_name_cipher,channel,note_cipher,source_profile_cipher,next_follow_at,occurred_at,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),text(auditEvent,"id"),id("core_customer",row,"customerId"),campus,id("core_principal",owner),id("core_principal",creator),activity,opportunity,touchpoint,
            cipher.encrypt(text(row,"title")),encryptOptional(row,"customerName"),encryptOptional(row,"campus"),encryptOptional(row,"owner"),channel,cipher.encrypt(text(row,"note")),cipher.encrypt(row.toString()),optionalDate(row,"nextFollowAt"),date(row,"at"),text(row,"status"),positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
        consultationId=id("core_crm_consultation",row,"id");JsonNode history=array(row,"history");HashSet<String> ids=new HashSet<>();
        for(JsonNode item:history) {
            String eventId=text(item,"id");if(!ids.add(eventId)) throw invalid("咨询历史事件ID重复");
            String actor=text(item,"actorId"),kind=text(item,"kind");if(kind.length()>80) throw invalid("咨询历史事件类型过长");
            jdbc.update("INSERT INTO core_crm_consultation_history(public_id,consultation_id,actor_id,source_kind,detail_cipher,actor_name_cipher,occurred_at,source_audit_event_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                eventId,consultationId,id("core_principal",actor),kind,encryptOptional(item,"detail"),encryptOptional(item,"actor"),date(item,"at"),actor.equals(creator)?text(auditEvent,"id"):null,date(item,"at"),date(item,"at"));
        }
        if(history.isEmpty()) throw invalid("咨询缺少来源历史事件");
    }
    private void ticket(JsonNode row,JsonNode audit) {
        String state=text(row,"status");if(!Set.of("open","processing","closed").contains(state)) throw invalid("工单状态未映射");
        if(!"open".equals(state)) throw invalid("非open工单缺少可验证的完整转派/处理状态历史");
        if(!array(row,"history").isEmpty()) throw invalid("工单来源历史需逐事件映射");
        String sourceId=text(row,"id"),creator=text(row,"createdBy"),owner=text(row,"ownerId");
        List<JsonNode> events=auditMatches(audit,"serviceTickets",sourceId);
        if(events.size()!=1) throw invalid("当前open工单必须唯一匹配创建审计事件");
        JsonNode event=events.get(0);
        if(!"serviceTicket.create".equals(text(event,"action"))||!creator.equals(text(event,"actorId"))) throw invalid("工单创建人与审计事件不一致");
        Long campus=optionalId("core_campus",row,"campusId"),enrollment=optionalId("core_enrollment",row,"enrollmentId");
        Long classroom=optionalId("core_class",row,"classId"),refund=optionalId("core_crm_refund_request",row,"refundId");
        if(row.hasNonNull("sessionId")) throw invalid("服务工单课次关联需要单独扩展目标模型");
        Long typeId=jdbc.queryForObject("SELECT id FROM core_crm_service_ticket_type WHERE type_code=? AND campus_id IS NULL AND deleted=0",Long.class,text(row,"type"));
        if(typeId==null) throw invalid("服务工单类型未迁移");
        long customer=id("core_customer",row,"customerId"),ownerId=id("core_principal",owner),creatorId=id("core_principal",creator);
        validateCustomerLinks(row,customer,enrollment,classroom,refund);
        jdbc.update("INSERT INTO core_crm_service_ticket(public_id,customer_id,campus_id,ticket_type_id,owner_id,created_by,enrollment_id,refund_request_id,class_id,title_cipher,description_cipher,status,due_at,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'open',?,?,?,?)",
            sourceId,customer,campus,typeId,ownerId,creatorId,enrollment,refund,classroom,cipher.encrypt(text(row,"title")),cipher.encrypt(text(row,"description")),optionalDate(row,"dueAt"),positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
        long ticket=id("core_crm_service_ticket",row,"id");
        jdbc.update("INSERT INTO core_crm_service_ticket_event(public_id,source_audit_event_id,ticket_id,actor_id,event_type,to_owner_id,to_status,occurred_at,created_at,updated_at) VALUES(?,?,?,?,'created',?,'open',?,?,?)",
            "TICK_EVT_LEGACY_"+text(event,"id"),text(event,"id"),ticket,creatorId,ownerId,date(event,"at"),date(event,"at"),date(event,"at"));
    }
    private void validateCustomerLinks(JsonNode ticket,long customer,Long enrollment,Long classroom,Long refund) {
        if(enrollment!=null) {
            Long student=jdbc.queryForObject("SELECT student_id FROM core_enrollment WHERE id=?",Long.class,enrollment);
            requireCustomerStudent(customer,student);
        }
        if(classroom!=null&&enrollment!=null) {
            Long enrolledClass=jdbc.queryForObject("SELECT class_id FROM core_enrollment WHERE id=?",Long.class,enrollment);
            if(!enrolledClass.equals(classroom)) throw invalid("工单报名与班级关系不一致");
        }
        if(refund!=null) {
            Long order=jdbc.queryForObject("SELECT order_id FROM core_crm_refund_request WHERE id=?",Long.class,refund);
            Long student=jdbc.queryForObject("SELECT student_id FROM core_crm_order WHERE id=?",Long.class,order);
            requireCustomerStudent(customer,student);
        }
    }
    private void requireCustomerStudent(long customer,Long student) {
        Integer count=jdbc.queryForObject("SELECT COUNT(id) FROM core_customer_student WHERE customer_id=? AND student_id=? AND deleted=0",Integer.class,customer,student);
        if(count==null||count!=1) throw invalid("工单关联学员未关联到客户档案");
    }
    private void reconcile(JsonNode payload) {
        for(String key:COLLECTIONS) {
            Set<String> ids=new HashSet<>();for(JsonNode row:array(payload,key)) {
                String id=text(row,"id");if(!ids.add(id)) throw invalid("历史来源ID重复");
                String table=switch(key) {case "studentCommunications"->"core_communication";case "consultations"->"core_crm_consultation";case "serviceTickets"->"core_crm_service_ticket";default->throw invalid("未知历史集合");};
                if(id(table,id)<1) throw invalid("历史来源ID对账失败");
            }
        }
    }
    private JsonNode uniqueAudit(JsonNode audit,String collection,String id,String action) {
        List<JsonNode> rows=auditMatches(audit,collection,id);List<JsonNode> matches=rows.stream().filter(e->action.equals(e.path("action").asText())).toList();
        if(matches.size()!=1) throw invalid("历史记录没有唯一匹配成功审计事件");return matches.get(0);
    }
    private List<JsonNode> auditMatches(JsonNode audit,String collection,String id) {
        List<JsonNode> matches=new ArrayList<>();for(JsonNode event:audit) if("success".equals(event.path("outcome").asText()))
            for(JsonNode change:event.path("changes")) if(collection.equals(change.path("collection").asText())&&id.equals(change.path("id").asText())) matches.add(event);
        return matches;
    }
    private long id(String table,String publicId) {
        if(!LOOKUPS.contains(table)) throw invalid("未知目标关系表");
        List<Long> ids=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),publicId);
        if(ids.size()!=1) throw invalid("历史记录关联未迁移或ID不唯一");return ids.get(0);
    }
    private long id(String table,JsonNode row,String field) {return id(table,text(row,field));}
    private Long optionalId(String table,JsonNode row,String field) {return row.hasNonNull(field)&&!row.path(field).asText().isBlank()?id(table,text(row,field)):null;}
    private Timestamp optionalDate(JsonNode row,String field) {return row.hasNonNull(field)&&!row.path(field).asText().isBlank()?date(row,field):null;}
    private String encryptOptional(JsonNode row,String field) {return row.hasNonNull(field)&&!row.path(field).asText().isBlank()?cipher.encrypt(text(row,field)):null;}
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
