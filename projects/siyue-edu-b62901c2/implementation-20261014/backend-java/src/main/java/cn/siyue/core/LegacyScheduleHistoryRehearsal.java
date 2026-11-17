package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Offline historical schedule import; never replays scheduling commands against today's timetable. */
public final class LegacyScheduleHistoryRehearsal {
    private static final Set<String> TABLES=Set.of("core_principal","core_campus","core_class","core_session",
        "core_lesson","core_student","core_enrollment","core_crm_order","core_session_adjustment",
        "core_session_reschedule_request");
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService transaction;

    public LegacyScheduleHistoryRehearsal(JdbcTemplate jdbc,PiiCipher cipher,PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyScheduleHistoryRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc=jdbc;this.cipher=cipher;this.transaction=phases;
    }
    public int importScheduleHistory(JsonNode envelope) {return importScheduleHistory(envelope,null);}

    int importScheduleHistory(JsonNode envelope,LegacyMigrationTargetAuthorization authorization) {
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(
            jdbc.queryForObject("SELECT DATABASE()",String.class),jdbc,envelope,authorization);
        if(!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())
                ||!envelope.path("revision").isIntegralNumber()) throw invalid("调课快照格式或版本错误");
        JsonNode state=envelope.path("state"),audit=envelope.path("auditProvenance");
        if(!audit.isArray()) throw invalid("调课来源审计缺失");
        ObjectNode payload=JsonNodeFactory.instance.objectNode();
        for(String key:List.of("sessionAdjustments","rescheduleRequests")) payload.set(key,array(state,key));
        payload.set("auditProvenance",audit);
        String archive=payload.toString(),fingerprint=hash(archive);long revision=envelope.path("revision").longValue();
        int rows=count(payload);
        return transaction.execute(() ->{
            requireCheckpoint("academic_catalog_v1",revision,hash(state.toString()));
            requireCheckpoint("academic_financial_v1",revision,hash("state="+state+"\nauditProvenance="+audit));
            List<Checkpoint> previous=jdbc.query("SELECT source_revision,source_hash,imported_rows FROM core_migration_checkpoint WHERE phase='academic_schedule_history_v1' FOR UPDATE",
                (rs,n)->new Checkpoint(rs.getLong(1),rs.getString(2),rs.getInt(3)));
            if(!previous.isEmpty()) {
                Checkpoint old=previous.get(0);
                if(old.revision()!=revision||!old.hash().equals(fingerprint)) throw invalid("调课历史来源变化，禁止覆盖");
                return old.rows();
            }
            jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) VALUES('academic_schedule_history_v1',?,?,?,?)",
                revision,fingerprint,cipher.encrypt(archive),rows);
            for(JsonNode row:array(state,"sessionAdjustments")) adjustment(row,audit);
            for(JsonNode row:array(state,"rescheduleRequests")) request(row,audit,state);
            return rows;
        });
    }
    private record Checkpoint(long revision,String hash,int rows) { }
    private void requireCheckpoint(String phase,long revision,String fingerprint) {
        if(jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase=? AND source_revision=? AND source_hash=? AND deleted=0",
                Integer.class,phase,revision,fingerprint)!=1) throw invalid("缺少相同来源的前置阶段："+phase);
    }
    private int count(JsonNode payload) {
        int count=1;
        for(JsonNode row:array(payload,"sessionAdjustments")) count=Math.addExact(count,"cancelled".equals(text(row,"status"))?2:1);
        for(JsonNode row:array(payload,"rescheduleRequests")) count=Math.addExact(count,"pending".equals(text(row,"status"))?2:3);
        if(count>=1000) throw invalid("调课历史迁移超过单事务行数限制");return count-1;
    }
    private void adjustment(JsonNode row,JsonNode audit) {
        String kind=text(row,"kind"),status=text(row,"status");
        if(!Set.of("makeup","temporary","waiver").contains(kind)
                ||!Set.of("active","completed","cancelled","waived").contains(status)) throw invalid("课次调整类型或状态未映射");
        JsonNode creation=event(audit,row,"sessionAdjustments",Set.of("waiver".equals(kind)?"makeup.waive":"makeup.assign"));
        requireTime(creation,row,"createdAt");
        requireEventCount(audit,row,"sessionAdjustments",Set.of("cancelled","completed").contains(status)?2:1);
        if("waiver".equals(kind)&&!text(row,"waivedBy").equals(text(creation,"actorId"))) throw invalid("放弃补课操作人与审计不一致");
        long enrollment=id("core_enrollment",row,"enrollmentId"),order=id("core_crm_order",row,"orderId");
        long student=id("core_student",row,"studentId"),source=id("core_class",row,"sourceClassId");
        long session=id("core_session",row,"sourceSessionId"),lesson=id("core_lesson",row,"lessonId");
        if(jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment WHERE id=? AND order_id=? AND student_id=?",Integer.class,enrollment,order,student)!=1)
            throw invalid("课次调整报名/订单/学员关系不一致");
        requireSession(session,source);
        if(jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment_lesson WHERE enrollment_id=? AND order_id=? AND source_lesson_id=?",Integer.class,enrollment,order,lesson)!=1)
            throw invalid("课次调整讲次权益未迁移");
        Long target=optionalId("core_class",row,"targetClassId"),targetSession=optionalId("core_session",row,"targetSessionId");
        Long campus=optionalId("core_campus",row,"campusId");
        if(!"waiver".equals(kind)) {
            if(target==null||targetSession==null) throw invalid("课次调整目标缺失");
            requireSession(targetSession,target);requireCampus(target,campus);
        }
        Cancellation cancellation=cancellation(row,audit);
        if("completed".equals(status)) validateCompletion(row,audit);
        jdbc.update("""
            INSERT INTO core_session_adjustment(public_id,kind,status,enrollment_id,order_id,student_id,
              source_class_id,source_session_id,lesson_id,target_class_id,target_session_id,campus_id,
              owner_id,assignee_id,created_by_id,reason_cipher,cancelled_at,cancelled_by_id,cancel_reason_cipher,
              version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,text(row,"id"),kind,status,enrollment,order,student,source,session,lesson,target,targetSession,campus,
            optionalId("core_principal",row,"ownerId"),optionalId("core_principal",row,"assigneeId"),id("core_principal",creation,"actorId"),
            cipher.encrypt(text(row,"reason")),cancellation.at(),cancellation.actor(),cancellation.reason(),
            positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
        if(cancellation.event()!=null) adjustmentCancellation(row,cancellation.event());
    }
    private record Cancellation(JsonNode event,Timestamp at,Long actor,String reason) { }
    private Cancellation cancellation(JsonNode row,JsonNode audit) {
        if(!"cancelled".equals(text(row,"status"))) return new Cancellation(null,null,null,null);
        JsonNode event=event(audit,row,"sessionAdjustments",Set.of("makeup.cancel","enrollment.withdraw"));
        requireTime(event,row,"updatedAt");
        if(date(event,"at").before(date(row,"createdAt"))) throw invalid("取消预约早于创建");
        return new Cancellation(event,date(event,"at"),id("core_principal",event,"actorId"),cipher.encrypt(text(row,"cancelReason")));
    }
    private void validateCompletion(JsonNode row,JsonNode audit) {
        JsonNode completion=event(audit,row,"sessionAdjustments",Set.of("session.attendance","session.attendance.exceptions"));
        requireTime(completion,row,"completedAt");
        if(jdbc.queryForObject("SELECT COUNT(c.id) FROM core_consumption c JOIN core_enrollment_lesson l ON l.id=c.entitlement_lesson_id WHERE c.order_id=? AND l.source_lesson_id=? AND c.session_id=? AND c.student_id=? AND c.deleted=0",
                Integer.class,id("core_crm_order",row,"orderId"),id("core_lesson",row,"lessonId"),
                id("core_session",row,"targetSessionId"),id("core_student",row,"studentId"))!=1) throw invalid("完成补课缺少一致的课消事实");
    }
    private void adjustmentCancellation(JsonNode row,JsonNode event) {
        jdbc.update("""
            INSERT INTO core_session_adjustment_event(public_id,adjustment_id,actor_id,event_type,
              from_status,to_status,reason_cipher,occurred_at,created_at,updated_at)
            VALUES(?,?,?,'cancelled','active','cancelled',?,?,?,?)
            ""","LEGACY_ADJ_"+hash(text(event,"id")+"|"+text(row,"id")),id("core_session_adjustment",row,"id"),id("core_principal",event,"actorId"),
            cipher.encrypt(text(row,"cancelReason")),date(event,"at"),date(event,"at"),date(event,"at"));
    }
    private void request(JsonNode row,JsonNode audit,JsonNode state) {
        JsonNode original=row.path("original");String status=text(row,"status");
        if(!Set.of("pending","approved","rejected").contains(status)) throw invalid("调课申请状态未映射");
        JsonNode creation=event(audit,row,"rescheduleRequests",Set.of("session.reschedule.request"));
        requireTime(creation,row,"createdAt");
        requireEventCount(audit,row,"rescheduleRequests","pending".equals(status)?1:2);
        if(!text(row,"applicantId").equals(text(creation,"actorId"))
                ||!text(row,"applicantId").equals(text(original,"teacherId"))) throw invalid("调课申请人与原授课教师或审计不一致");
        long klass=id("core_class",row,"classId"),session=id("core_session",row,"sessionId"),campus=id("core_campus",row,"campusId");
        requireSession(session,klass);
        if(campus!=id("core_campus",original,"campusId")) throw invalid("调课申请原校区不一致");
        Timestamp[] before=times(original),after=times(row);
        if(before[1].getTime()-before[0].getTime()!=after[1].getTime()-after[0].getTime()) throw invalid("调课申请时长改变无法映射");
        JsonNode review="pending".equals(status)?null:event(audit,row,"rescheduleRequests",Set.of("session.reschedule.review"));
        validateReview(row,review,creation);
        long sessionVersion=originalSessionVersion(row,state);
        jdbc.update("""
            INSERT INTO core_session_reschedule_request(public_id,class_id,session_id,campus_id,applicant_id,
              original_starts_at,original_ends_at,original_teacher_id,original_lesson_id,original_room,
              original_session_version,original_class_version,requested_starts_at,reason_cipher,status,
              reviewer_id,reviewed_at,resolution_note_cipher,version,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,text(row,"id"),klass,session,campus,id("core_principal",row,"applicantId"),before[0],before[1],
            id("core_principal",original,"teacherId"),id("core_lesson",original,"lessonId"),text(original,"room"),
            sessionVersion,positive(original,"classVersion"),after[0],cipher.encrypt(text(row,"reason")),status,
            review==null?null:id("core_principal",row,"reviewedBy"),review==null?null:date(row,"reviewedAt"),
            review==null?null:cipher.encrypt(row.path("note").asText("")),positive(row,"version"),date(row,"createdAt"),date(row,"updatedAt"));
        requestEvent(row,creation,false);
        if(review!=null) requestEvent(row,review,true);
    }
    private void validateReview(JsonNode row,JsonNode review,JsonNode creation) {
        if(review==null) {
            if(row.hasNonNull("reviewedBy")||row.hasNonNull("reviewedAt")) throw invalid("待审批申请包含审批结果");
            return;
        }
        if(!text(review,"actorId").equals(text(row,"reviewedBy"))) throw invalid("调课审批人与审计不一致");
        requireTime(review,row,"reviewedAt");
        if(date(review,"at").before(date(creation,"at"))) throw invalid("调课审批早于申请");
        if("approved".equals(text(row,"status"))&&text(row,"applicantId").equals(text(row,"reviewedBy"))) throw invalid("调课批准人不能为申请人");
        if("rejected".equals(text(row,"status"))) text(row,"note");
    }
    private void requestEvent(JsonNode row,JsonNode event,boolean reviewed) {
        jdbc.update("""
            INSERT INTO core_session_reschedule_event(public_id,request_id,actor_id,event_type,from_status,
              to_status,note_cipher,occurred_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)
            ""","LEGACY_SR_"+hash(text(event,"id")),id("core_session_reschedule_request",row,"id"),id("core_principal",event,"actorId"),
            reviewed?"reviewed":"requested",reviewed?"pending":null,reviewed?text(row,"status"):"pending",
            cipher.encrypt(reviewed?row.path("note").asText(""):text(row,"reason")),date(event,"at"),date(event,"at"),date(event,"at"));
    }
    /** Node has no session version. Bind to the imported session only if its full fingerprint still matches. */
    private long originalSessionVersion(JsonNode row,JsonNode state) {
        JsonNode klass=find(array(state,"classes"),text(row,"classId"));
        JsonNode session=find(array(klass,"schedule"),text(row,"sessionId")),original=row.path("original");
        for(String key:List.of("date","time","lessonId","lessonVersion","teacherId","room","campusId")) {
            JsonNode current=session.hasNonNull(key)?session.get(key):klass.path(key);
            if(!sameFingerprintValue(original.path(key),current)) return 0;
        }
        return jdbc.queryForObject("SELECT version FROM core_session WHERE id=?",Long.class,id("core_session",row,"sessionId"));
    }
    private boolean sameFingerprintValue(JsonNode original,JsonNode current) {
        if((original.isMissingNode()||original.isNull())&&(current.isMissingNode()||current.isNull())) return true;
        return original.equals(current);
    }
    private JsonNode event(JsonNode audit,JsonNode row,String collection,Set<String> actions) {
        List<JsonNode> matches=new java.util.ArrayList<>();
        for(JsonNode event:audit) {
            if(!"success".equals(event.path("outcome").asText())||!actions.contains(event.path("action").asText())) continue;
            for(JsonNode change:event.path("changes")) {
                if(collection.equals(change.path("collection").asText())&&text(row,"id").equals(change.path("id").asText())) {
                    matches.add(event);break;
                }
            }
        }
        if(matches.size()!=1) throw invalid("调课历史没有唯一成功审计事件");return matches.get(0);
    }
    private void requireEventCount(JsonNode audit,JsonNode row,String collection,int expected) {
        int count=0;
        for(JsonNode event:audit) {
            if(!"success".equals(event.path("outcome").asText())) continue;
            for(JsonNode change:event.path("changes")) {
                if(collection.equals(change.path("collection").asText())&&text(row,"id").equals(change.path("id").asText())) {count++;break;}
            }
        }
        if(count!=expected) throw invalid("调课历史审计链不完整或含未映射事件");
    }
    private void requireTime(JsonNode event,JsonNode row,String field) {
        // The legacy business timestamp and its audit timestamp are sourced independently.
        long auditMillis=date(event,"at").toInstant().toEpochMilli();
        long recordMillis=date(row,field).toInstant().toEpochMilli();
        if(auditMillis<recordMillis) throw invalid("调课历史审计早于业务事件");
    }
    private void requireSession(long session,long klass) {
        if(jdbc.queryForObject("SELECT COUNT(id) FROM core_session WHERE id=? AND class_id=?",Integer.class,session,klass)!=1)
            throw invalid("调课历史课次与班级关系不一致");
    }
    private void requireCampus(long klass,Long campus) {
        if(campus==null||jdbc.queryForObject("SELECT COUNT(id) FROM core_class WHERE id=? AND campus_id=?",Integer.class,klass,campus)!=1)
            throw invalid("课次调整目标校区不一致");
    }
    private long id(String table,JsonNode row,String key) {
        if(!TABLES.contains(table)) throw invalid("调课迁移关联表无效");
        List<Long> ids=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),text(row,key));
        if(ids.size()!=1) throw invalid("调课历史关联未迁移");return ids.get(0);
    }
    private Long optionalId(String table,JsonNode row,String key) {
        return row.hasNonNull(key)&&!row.path(key).asText().isBlank()?id(table,row,key):null;
    }
    private Timestamp[] times(JsonNode row) {
        var match=Pattern.compile("(\\d{2}):(\\d{2})[–—-](\\d{2}):(\\d{2})").matcher(text(row,"time"));
        if(!match.matches()) throw invalid("调课时间格式无效");
        int sh=Integer.parseInt(match.group(1)),sm=Integer.parseInt(match.group(2));
        int eh=Integer.parseInt(match.group(3)),em=Integer.parseInt(match.group(4));
        if(sh>23||sm>59||eh>24||em>59||(eh==24&&em!=0)||eh*60+em<=sh*60+sm) throw invalid("调课时间无效");
        var day=LocalDate.parse(text(row,"date")).atStartOfDay(ZoneId.of("Asia/Shanghai"));
        return new Timestamp[]{Timestamp.from(day.plusMinutes(sh*60+sm).toInstant()),Timestamp.from(day.plusMinutes(eh*60+em).toInstant())};
    }
    private JsonNode find(JsonNode rows,String publicId) {
        for(JsonNode row:rows) if(publicId.equals(row.path("id").asText())) return row;
        throw invalid("调课源关联缺失");
    }
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
