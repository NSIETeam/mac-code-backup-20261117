package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Offline transaction-ledger migration; requires an identical completed catalog snapshot. */
public final class LegacyFinancialRehearsal {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final LegacyMigrationPhaseService tx;
    private static final Set<String> TABLES=Set.of("core_student","core_class","core_course","core_campus",
        "core_principal","core_crm_order","core_enrollment","core_lesson","core_session","core_attendance","core_commerce_checkout");
    /** Uses the production cipher and database transaction manager, without registering a web endpoint. */
    public LegacyFinancialRehearsal(JdbcTemplate jdbc,PiiCipher cipher,PlatformTransactionManager manager) {
        this(jdbc,cipher,LegacyMigrationPhaseTransactions.create(manager));
    }

    /** Shares the CLI annotation proxy; SQL, source validation and phase row limits remain local. */
    LegacyFinancialRehearsal(JdbcTemplate jdbc,PiiCipher cipher,LegacyMigrationPhaseService phases) {
        this.jdbc=jdbc;this.cipher=cipher;this.tx=phases;
    }
    /** Imports immutable historical facts exactly once into an isolated rehearsal database. */
    public int importFinancial(JsonNode envelope) {
        return importFinancial(envelope, null);
    }

    /** Candidate-only entry point; ordinary rehearsal calls remain restricted to *_test schemas. */
    int importFinancial(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        FinancialSource source=prepare(envelope, authorization);
        return tx.execute(() ->{
            requireCatalog(source.revision(),source.catalogFingerprint());
            var previous=jdbc.query("SELECT source_hash,source_revision,imported_rows FROM core_migration_checkpoint WHERE phase='academic_financial_v1' FOR UPDATE",
                (rs,row)->new Checkpoint(rs.getString(1),rs.getLong(2),rs.getInt(3)));
            if(!previous.isEmpty()) {
                var old=previous.get(0);
                if(!old.hash().equals(source.fingerprint())||old.revision()!=source.revision()) throw invalid("来源变化，禁止覆盖财务历史");
                return old.rows();
            }
            jdbc.update("INSERT INTO core_migration_checkpoint(phase,source_revision,source_hash,source_cipher,imported_rows) VALUES('academic_financial_v1',?,?,?,?)",
                source.revision(),source.fingerprint(),cipher.encrypt(source.archive()),source.count());
            importRows(source.state(),source.audit());
            return source.count();
        });
    }
    private record Checkpoint(String hash,long revision,int rows) { }
    private record FinancialSource(JsonNode state,JsonNode audit,long revision,String catalogFingerprint,
            String archive,String fingerprint,int count) { }
    private FinancialSource prepare(JsonNode envelope, LegacyMigrationTargetAuthorization authorization) {
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        LegacyMigrationTargetAuthorization.requireRehearsalOrCandidate(database,jdbc,envelope,authorization);
        if(!"siyue.legacy-business/v1".equals(envelope.path("schema").asText())) throw invalid("快照格式错误");
        if(!envelope.path("revision").isIntegralNumber()) throw invalid("快照版本错误");
        JsonNode state=envelope.path("state"),auditNode=envelope.path("auditProvenance");
        JsonNode audit=auditNode.isMissingNode()?JsonNodeFactory.instance.arrayNode():auditNode;
        if(!audit.isArray()) throw invalid("迁移审计摘要格式错误");
        String archive=archive(state,audit);
        return new FinancialSource(state,audit,envelope.path("revision").longValue(),hash(state.toString()),archive,hash(archive),count(state));
    }
    private void importRows(JsonNode state,JsonNode audit) {
        new LegacyStorefrontRehearsal(jdbc,cipher).importRows(state);
        for(JsonNode order:array(state,"orders")) order(order,state);
        for(JsonNode enrollment:array(state,"enrollments")) enrollment(enrollment,state,audit);
        for(JsonNode receipt:array(state,"receipts")) receipt(receipt);
        for(JsonNode attendance:array(state,"attendance")) attendance(attendance);
        for(JsonNode consumption:array(state,"consumptions")) consumption(consumption);
        for(JsonNode refund:array(state,"refunds")) refund(refund,state,audit);
        reconcile(state);
    }
    private void requireCatalog(long revision,String hash) {
        int matches=jdbc.queryForObject("SELECT COUNT(id) FROM core_migration_checkpoint WHERE phase='academic_catalog_v1' AND source_revision=? AND source_hash=? AND deleted=0",Integer.class,revision,hash);
        if(matches!=1) throw invalid("必须先导入相同版本的目录快照");
    }
    private int count(JsonNode state) {
        validateRefundStatuses(state);validateOrderStatuses(state);
        int count=1+LegacyStorefrontRehearsal.countRows(state);
        count=addCollectionCounts(state,count);count=addRefundEvents(state,count);count=addLessonRows(state,count);
        if(count>=1000) throw invalid("迁移事务超过行数限制");return count-1;
    }
    private void validateRefundStatuses(JsonNode state) {
        for(JsonNode refund:array(state,"refunds")) {
            String status=text(refund,"status");if(!Set.of("pending","no_refund").contains(status)) throw invalid("退款审批/到账历史需独立迁移处理");
            if("no_refund".equals(status)&&money(refund,"amountCents").signum()!=0) throw invalid("无需退款状态与退款金额不一致");
        }
    }
    private void validateOrderStatuses(JsonNode state) {
        for(JsonNode order:array(state,"orders")) {
            String status=text(order,"status");if(!Set.of("paid","pending","closed").contains(status)) throw invalid("未映射的订单状态");
            if(money(order,"refundedCents").signum()!=0) throw invalid("订单退款历史需独立迁移处理");
            if("closed".equals(status)&&!"withdrawn".equals(text(find(array(state,"enrollments"),text(order,"enrollmentId")),"status"))) throw invalid("closed订单必须匹配withdrawn报名");
        }
    }
    private int addCollectionCounts(JsonNode state,int count) {
        for(String key:Set.of("orders","enrollments","receipts","attendance","consumptions")) count=Math.addExact(count,array(state,key).size());
        return count;
    }
    private int addRefundEvents(JsonNode state,int count) {
        for(JsonNode refund:array(state,"refunds")) if("pending".equals(text(refund,"status"))) count=Math.addExact(count,1);
        return count;
    }
    private int addLessonRows(JsonNode state,int count) {
        for(JsonNode order:array(state,"orders")) count=Math.addExact(count,array(order,"lessonSnapshot").size());
        return count;
    }
    private void order(JsonNode row,JsonNode state) {
        if(row.hasNonNull("opportunityId")||row.hasNonNull("customerId")) throw invalid("CRM来源订单需使用对应来源映射");
        BigDecimal total=money(row,"amountCents"),paid=money(row,"paidCents"),refunded=money(row,"refundedCents");
        if(total.signum()<=0||paid.compareTo(total)>0||refunded.signum()!=0) throw invalid("订单余额或退款需要专门处理");
        String status=paid.compareTo(total)==0?"received":paid.signum()==0?"pending":"partially_received";
        String sourceStatus=text(row,"status");
        if(!Set.of("paid","pending","closed").contains(sourceStatus)) throw invalid("未映射的订单状态");
        if("closed".equals(sourceStatus)) {
            JsonNode enrollment=find(array(state,"enrollments"),text(row,"enrollmentId"));
            if(!"withdrawn".equals(text(enrollment,"status"))) throw invalid("closed订单必须匹配withdrawn报名");
        }
        if("closed".equals(sourceStatus)&&paid.signum()==0) throw invalid("未收款closed订单需单独核实");
        if("closed".equals(sourceStatus)) status="closed";
        jdbc.update("""
            INSERT INTO core_crm_order(public_id,student_id,campus_id,attributed_owner_id,product_snapshot,
                total_amount,received_amount,refunded_amount,status,version,source_kind,subject_snapshot,commerce_checkout_id,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,text(row,"id"),id("core_student",row,"studentId"),id("core_campus",row,"campusId"),
            id("core_principal",row,"ownerId"),text(row,"product"),total,paid,refunded,status,
            positive(row,"version"),row.hasNonNull("commerceCheckoutId")?"commerce_checkout":"direct_enrollment",text(row,"subject"),
            checkoutOrder(row,state),date(row,"createdAt"),date(row,"updatedAt"));
    }
    private Long checkoutOrder(JsonNode order,JsonNode state) {
        if(!order.hasNonNull("commerceCheckoutId")) return null;
        JsonNode checkout=find(array(state,"commerceCheckouts"),text(order,"commerceCheckoutId"));
        int index=-1;JsonNode orders=array(checkout,"orderIds");
        for(int i=0;i<orders.size();i++) if(orders.get(i).asText().equals(text(order,"id"))) index=i;
        if(index<0||index>=array(checkout.path("quote"),"lines").size()) throw invalid("商城订单未关联原报价行");
        JsonNode line=checkout.path("quote").path("lines").get(index);
        if(!text(line,"classId").equals(text(order,"classId"))||money(line,"amountCents").compareTo(money(order,"amountCents"))!=0
                ||!checkout.path("studentIds").path(line.path("buyerIndex").intValue()).asText().equals(text(order,"studentId"))) throw invalid("商城订单与核对学生或报价不一致");
        return id("core_commerce_checkout",text(checkout,"id"));
    }
    private void enrollment(JsonNode row,JsonNode state,JsonNode audit) {
        validateEnrollmentMappings(row);
        JsonNode order=find(array(state,"orders"),text(row,"orderId"));validateEnrollmentOrder(row,order);
        WithdrawalActor withdrawal=withdrawalActor(row,order,state,audit);
        insertEnrollment(row,order,withdrawal);
        entitlements(row,order);
    }
    private record WithdrawalActor(String principalId,String eventId) { }
    private void validateEnrollmentMappings(JsonNode row) {
        String classId=text(row,"classId");if(row.hasNonNull("initialClassId")&&!classId.equals(text(row,"initialClassId"))) throw invalid("转班历史需迁移安置记录");
        for(JsonNode mapped:row.path("lessonMap")) if(!mapped.isTextual()) throw invalid("讲次映射无效");
        row.path("lessonMap").fields().forEachRemaining(e->{if(!e.getKey().equals(e.getValue().asText())) throw invalid("讲次替换需独立迁移");});
    }
    private void validateEnrollmentOrder(JsonNode row,JsonNode order) {
        if(!text(row,"id").equals(text(order,"enrollmentId"))||!text(row,"studentId").equals(text(order,"studentId"))) throw invalid("报名与订单对应不一致");
        String status=text(row,"status");BigDecimal paid=money(order,"paidCents"),total=money(order,"amountCents");
        if(!Set.of("active","pending","withdrawn").contains(status)||("active".equals(status)&&paid.compareTo(total)!=0)
            ||("pending".equals(status)&&paid.signum()!=0)||("withdrawn".equals(status)&&(!"closed".equals(text(order,"status"))||paid.signum()==0))) throw invalid("报名状态与收款状态不一致");
    }
    private WithdrawalActor withdrawalActor(JsonNode row,JsonNode order,JsonNode state,JsonNode audit) {
        if(!"withdrawn".equals(text(row,"status"))) return new WithdrawalActor(null,null);
        text(row,"withdrawalReason");date(row,"withdrawnAt");
        JsonNode refund=findBy(array(state,"refunds"),"orderId",text(order,"id"));JsonNode event=withdrawalEvent(audit,text(refund,"id"));
        return new WithdrawalActor(text(event,"actorId"),text(event,"id"));
    }
    private void insertEnrollment(JsonNode row,JsonNode order,WithdrawalActor withdrawal) {
        String status=text(row,"status");
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status,activated_at,withdrawn_at,withdrawal_reason_cipher,withdrawn_by_id,withdrawal_audit_event_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),id("core_student",row,"studentId"),id("core_class",row,"classId"),id("core_crm_order",row,"orderId"),status,
            row.hasNonNull("activatedAt")?date(row,"activatedAt"):null,
            "withdrawn".equals(status)?date(row,"withdrawnAt"):null,
            "withdrawn".equals(status)?cipher.encrypt(text(row,"withdrawalReason")):null,
            withdrawal.principalId()==null?null:id("core_principal",withdrawal.principalId()),withdrawal.eventId(),date(row,"createdAt"),date(row,"updatedAt"));
    }
    private void entitlements(JsonNode enrollment,JsonNode order) {
        JsonNode lessons=array(order,"lessonSnapshot");int count=lessons.size();
        if(count<1||count>200) throw invalid("购买讲次数量无效");
        long cents=money(order,"amountCents").movePointRight(2).longValueExact();int sequence=0;
        for(JsonNode lesson:lessons) {
            int position=sequence++;
            jdbc.update("""
                INSERT INTO core_enrollment_lesson(public_id,enrollment_id,order_id,source_lesson_id,sequence_no,
                    title_snapshot,unit_amount,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)
                """,entitlementId(text(order,"id"),text(lesson,"id")),id("core_enrollment",enrollment,"id"),id("core_crm_order",order,"id"),
                id("core_lesson",lesson,"id"),positive(lesson,"sequence"),text(lesson,"title"),
                BigDecimal.valueOf(cents/count+(position<cents%count?1:0),2),date(enrollment,"createdAt"),date(enrollment,"updatedAt"));
        }
    }
    private void receipt(JsonNode row) {
        String method=switch(text(row,"method")) {case "cash"->"cash";case "bank"->"bank_transfer";default->throw invalid("收款方式未映射");};
        jdbc.update("INSERT INTO core_crm_receipt(public_id,order_id,actor_id,amount,method,reference_cipher,reference_hash,received_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),id("core_crm_order",row,"orderId"),id("core_principal",row,"confirmedBy"),money(row,"amountCents"),method,
            cipher.encrypt(text(row,"reference")),cipher.fingerprint("payment_ref",text(row,"reference").trim()),date(row,"createdAt"),date(row,"createdAt"),date(row,"updatedAt"));
    }
    private void attendance(JsonNode row) {
        String status=text(row,"attendanceStatus");
        if(!Set.of("attended","late","left_early","leave","absent").contains(status)) throw invalid("考勤状态未映射");
        jdbc.update("INSERT INTO core_attendance(public_id,session_id,student_id,enrollment_id,actor_id,status,note_cipher,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,?)",
            text(row,"id"),id("core_session",row,"sessionId"),id("core_student",row,"studentId"),id("core_enrollment",row,"enrollmentId"),
            id("core_principal",row,"recordedBy"),status,cipher.encrypt(row.path("note").asText("")),date(row,"createdAt"),date(row,"updatedAt"),positive(row,"version"));
    }
    private void consumption(JsonNode row) {
        if(!"confirmed".equals(text(row,"status"))) throw invalid("课消冲正记录需独立迁移");
        Long entitlement=jdbc.queryForObject("SELECT id FROM core_enrollment_lesson WHERE public_id=?",Long.class,
            entitlementId(text(row,"orderId"),text(row,"lessonId")));
        jdbc.update("""
            INSERT INTO core_consumption(public_id,attendance_id,session_id,student_id,enrollment_id,amount,
                order_id,entitlement_lesson_id,actor_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,text(row,"id"),id("core_attendance",row,"attendanceId"),id("core_session",row,"sessionId"),
            id("core_student",row,"studentId"),id("core_enrollment",row,"enrollmentId"),money(row,"amountCents"),
            id("core_crm_order",row,"orderId"),entitlement,id("core_principal",row,"recordedBy"),date(row,"createdAt"),date(row,"updatedAt"));
    }
    private void refund(JsonNode row,JsonNode state,JsonNode audit) {
        String status=text(row,"status");if("no_refund".equals(status)) return;
        JsonNode order=find(array(state,"orders"),text(row,"orderId"));
        JsonNode enrollment=find(array(state,"enrollments"),text(row,"enrollmentId"));
        if(!"closed".equals(text(order,"status"))||!"withdrawn".equals(text(enrollment,"status"))) throw invalid("退款申请与退课订单/报名不一致");
        BigDecimal amount=money(row,"amountCents");if(amount.signum()<=0) throw invalid("待审退款金额必须为正数");
        JsonNode event=withdrawalEvent(audit,text(row,"id"));String actorId=text(event,"actorId");
        long orderId=id("core_crm_order",row,"orderId"),actor=id("core_principal",actorId);
        BigDecimal received=jdbc.queryForObject("SELECT received_amount FROM core_crm_order WHERE id=?",BigDecimal.class,orderId);
        BigDecimal refunded=jdbc.queryForObject("SELECT refunded_amount FROM core_crm_order WHERE id=?",BigDecimal.class,orderId);
        BigDecimal spent=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM core_consumption WHERE order_id=?",BigDecimal.class,orderId);
        if(amount.add(refunded).add(spent).compareTo(received)>0) throw invalid("退款申请超过已收款扣除课消后的余额");
        jdbc.update("INSERT INTO core_crm_refund_request(public_id,order_id,applicant_id,amount,reason_cipher,status,version,source_audit_event_id,created_at,updated_at) VALUES(?,?,?,?,?,'pending_approval',?,?,?,?)",
            text(row,"id"),orderId,actor,amount,cipher.encrypt(text(row,"reason")),positive(row,"version"),text(event,"id"),date(row,"createdAt"),date(row,"updatedAt"));
    }
    private static JsonNode withdrawalEvent(JsonNode audit,String refundId) {
        JsonNode match=null;for(JsonNode event:audit) {
            if(!"enrollment.withdraw".equals(event.path("action").asText())||!"success".equals(event.path("outcome").asText())) continue;
            for(JsonNode change:array(event,"changes")) if("refunds".equals(change.path("collection").asText())&&refundId.equals(change.path("id").asText())) {
                if(match!=null) throw invalid("退课审计事件关联不唯一");match=event;
            }
        }
        if(match==null||!match.path("actorId").isTextual()||!match.path("id").isTextual()) throw invalid("缺少可核验的退课审计申请人");return match;
    }
    private void reconcile(JsonNode state) {
        for(JsonNode order:array(state,"orders")) {
            long id=id("core_crm_order",order,"id");
            BigDecimal received=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM core_crm_receipt WHERE order_id=?",BigDecimal.class,id);
            BigDecimal lessons=jdbc.queryForObject("SELECT COALESCE(SUM(unit_amount),0) FROM core_enrollment_lesson WHERE order_id=?",BigDecimal.class,id);
            BigDecimal expectedLessons=money(order,"amountCents");
            if(received.compareTo(money(order,"paidCents"))!=0||lessons.compareTo(expectedLessons)!=0) throw invalid("订单实收或购买权益对账失败");
        }
    }
    private long id(String table,JsonNode row,String key) {
        if(!TABLES.contains(table)) throw invalid("未知迁移目标表");
        var values=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),text(row,key));
        if(values.size()!=1) throw invalid("关联未迁移或已删除");return values.get(0);
    }
    private long id(String table,String publicId) {
        if(!TABLES.contains(table)||publicId==null||publicId.isBlank()) throw invalid("未知迁移目标表或身份引用");
        var values=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),publicId);
        if(values.size()!=1) throw invalid("关联未迁移或已删除");return values.get(0);
    }
    private static JsonNode find(JsonNode rows,String id) {
        for(JsonNode row:rows) if(id.equals(text(row,"id"))) return row;throw invalid("关联源记录缺失");
    }
    private static JsonNode findBy(JsonNode rows,String field,String value) {
        for(JsonNode row:rows) if(value.equals(text(row,field))) return row;throw invalid("关联源记录缺失");
    }
    private static String archive(JsonNode state,JsonNode audit) { return "state="+state+"\nauditProvenance="+audit; }
    private static String entitlementId(String order,String lesson) { return "legacy-el-"+hash(order+"\u0000"+lesson); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
