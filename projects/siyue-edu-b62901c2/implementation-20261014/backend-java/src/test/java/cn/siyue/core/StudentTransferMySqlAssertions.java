package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;

final class StudentTransferMySqlAssertions {
    static void verify(DataSource source,PiiCipher cipher,Actor actor,String studentId) throws Exception {
        var jdbc=new JdbcTemplate(source);var access=new AccessRepository(jdbc);
        var academic=new AcademicRepository(jdbc,cipher);
        var mapper=new ObjectMapper().findAndRegisterModules();var mutations=new MutationRepository(jdbc,cipher);
        var service=proxy(source,new StudentTransferService(access,new StudentTransferRepository(jdbc,cipher),mutations,mapper));
        var corrections=proxy(source,new AttendanceCorrectionService(access,new AttendanceCorrectionRepository(jdbc,cipher,academic),mutations,mapper));
        var attendance=proxy(source,new AcademicService(access,academic,new AcademicCatalogRepository(jdbc,cipher),mutations,mapper));
        String enrollmentId=jdbc.queryForObject("SELECT public_id FROM core_enrollment WHERE order_id=(SELECT id FROM core_crm_order WHERE public_id='enrollment-paid-order')",String.class);
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) VALUES('transfer-target',1,1,1,'转班目标','转班教室',2,90)");
        Long targetId=jdbc.queryForObject("SELECT id FROM core_class WHERE public_id='transfer-target'",Long.class);
        Instant future=Instant.now().plusSeconds(400000);
        jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) VALUES('transfer-session',?,1,1,?,?,'scheduled')",
            targetId,Timestamp.from(future),Timestamp.from(future.plusSeconds(5400)));
        corrections.correct(actor,new AttendanceCorrectionDtos.Target(studentId,"session"),"transfer-undo-consumption-001",
            new AttendanceCorrectionDtos.Input("class",3,"leave",null,"核对后未上课"));
        var orderBefore=jdbc.queryForMap("SELECT student_id,campus_id,total_amount,received_amount,refunded_amount,product_snapshot FROM core_crm_order WHERE public_id='enrollment-paid-order'");
        var entitlementBefore=jdbc.queryForList("SELECT id,enrollment_id,order_id,source_lesson_id,sequence_no,title_snapshot,unit_amount FROM core_enrollment_lesson WHERE enrollment_id=(SELECT id FROM core_enrollment WHERE public_id=?) ORDER BY id",enrollmentId);
        var input=new StudentTransferDtos.Input(List.of(new StudentTransferDtos.Item(enrollmentId,1)),"transfer-target","调换上课时段");
        assertEquals(1,service.options(actor,studentId,1,100).enrollments().size());
        assertTrue(service.options(actor,studentId,1,100).targets().stream().anyMatch(t->t.id().equals("transfer-target")));
        jdbc.update("UPDATE core_class SET capacity=1 WHERE id=?",targetId);
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,status) VALUES('transfer-capacity',1,?,'active')",targetId);
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-full-capacity-001",input));
        jdbc.update("UPDATE core_enrollment SET status='withdrawn' WHERE public_id='transfer-capacity'");
        jdbc.update("UPDATE core_class SET capacity=2 WHERE id=?",targetId);
        jdbc.update("UPDATE core_course SET price=450 WHERE id=1");
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-price-changed-001",input));
        jdbc.update("UPDATE core_course SET price=400 WHERE id=1");
        jdbc.update("INSERT INTO core_course(public_id,campus_id,name,course_type,grade,subject,price) VALUES('transfer-incompatible-course',1,'不兼容课程','onsite','小五','数学',400)");
        Long incompatible=jdbc.queryForObject("SELECT id FROM core_course WHERE public_id='transfer-incompatible-course'",Long.class);
        jdbc.update("UPDATE core_class SET course_id=? WHERE id=?",incompatible,targetId);
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-grade-mismatch-001",input));
        jdbc.update("UPDATE core_course SET grade='小四' WHERE id=?",incompatible);
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-lessons-mismatch-001",input));
        jdbc.update("UPDATE core_class SET course_id=1 WHERE id=?",targetId);
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,owner_id) VALUES('transfer-legacy-student',?,?)",cipher.encrypt("转班校验学生"),actor.id());
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,status) SELECT 'transfer-invalid-enrollment',id,1,'active' FROM core_student WHERE public_id='transfer-legacy-student'");
        var mixed=new StudentTransferDtos.Input(List.of(input.items().get(0),new StudentTransferDtos.Item("transfer-invalid-enrollment",1)),"transfer-target","整批回滚");
        assertThrows(BusinessException.class,()->service.transfer(actor,"transfer-mixed-batch-001",mixed));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM core_enrollment_transfer",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM core_enrollment_placement",Long.class));
        org.slf4j.MDC.remove("traceId");
        assertThrows(Exception.class,()->service.transfer(actor,"transfer-audit-rollback-001",input));
        org.slf4j.MDC.put("traceId",java.util.UUID.randomUUID().toString());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM core_enrollment_transfer",Long.class));
        var winner=concurrentTransfer(service,actor,input);
        var result=winner.receipt();
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM core_enrollment_transfer",Long.class));
        assertEquals(1,result.total());assertEquals(2,result.items().get(0).version());
        assertEquals(result,service.transfer(actor,winner.key(),input));
        assertEquals(ErrorCode.PREVIEW_STALE,assertThrows(BusinessException.class,
            ()->service.transfer(actor,"transfer-stale-version-001",input)).code());
        jdbc.update("DELETE FROM core_principal_permission WHERE principal_id=? AND permission_code='enrollment.manage'",actor.id());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,
            ()->service.transfer(actor,winner.key(),input)).code());
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,'enrollment.manage')",actor.id());
        assertEquals(1L,jdbc.queryForObject("SELECT class_id FROM core_enrollment WHERE public_id=?",Long.class,enrollmentId));
        assertEquals(targetId,jdbc.queryForObject("SELECT class_id FROM core_effective_enrollment WHERE public_id=?",Long.class,enrollmentId));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM core_effective_enrollment WHERE public_id=? AND class_id=1",Long.class,enrollmentId));
        assertEquals("leave",academic.sessionRoster(actor,"session").students().stream().filter(s->s.id().equals(studentId)).findFirst().orElseThrow().attendanceStatus());
        assertTrue(academic.sessionRoster(actor,"transfer-session").students().stream().anyMatch(s->s.id().equals(studentId)));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE core_enrollment SET class_id=? WHERE public_id=?",targetId,enrollmentId));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE core_enrollment_transfer SET previous_version=5"));
        // Simulate the target lesson starting after its placement, without waiting on wall time.
        jdbc.update("UPDATE core_enrollment_placement SET effective_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE enrollment_id=(SELECT id FROM core_enrollment WHERE public_id=?)",enrollmentId);
        jdbc.update("UPDATE core_session SET starts_at=UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE,ends_at=UTC_TIMESTAMP(3)+INTERVAL 89 MINUTE WHERE id=(SELECT target.id FROM (SELECT id FROM core_session WHERE public_id='transfer-session') target)");
        var attended=attendance.recordAttendance(actor,studentId,"transfer-session","transfer-new-attendance-001",new AcademicDtos.AttendanceInput("transfer-target","attended",null));
        assertEquals("attended",attended.status());
        assertEquals(new BigDecimal("400.00"),jdbc.queryForObject("SELECT SUM(amount) FROM core_consumption WHERE order_id=(SELECT id FROM core_crm_order WHERE public_id='enrollment-paid-order')",BigDecimal.class));
        assertEquals(1L,jdbc.queryForObject("SELECT SUM(units) FROM core_consumption WHERE order_id=(SELECT id FROM core_crm_order WHERE public_id='enrollment-paid-order')",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM core_consumption WHERE transfer_id IS NOT NULL",Long.class));
        assertThrows(BusinessException.class,()->corrections.correct(actor,new AttendanceCorrectionDtos.Target(studentId,"session"),"transfer-old-restore-denied",
            new AttendanceCorrectionDtos.Input("class",4,"attended",null,"禁止双课次重复消费")));
        assertEquals(orderBefore,jdbc.queryForMap("SELECT student_id,campus_id,total_amount,received_amount,refunded_amount,product_snapshot FROM core_crm_order WHERE public_id='enrollment-paid-order'"));
        assertEquals(entitlementBefore,jdbc.queryForList("SELECT id,enrollment_id,order_id,source_lesson_id,sequence_no,title_snapshot,unit_amount FROM core_enrollment_lesson WHERE enrollment_id=(SELECT id FROM core_enrollment WHERE public_id=?) ORDER BY id",enrollmentId));
        assertEquals("class",jdbc.queryForObject("SELECT c.public_id FROM core_crm_order o JOIN core_enrollment e ON e.order_id=o.id JOIN core_class c ON c.id=e.class_id WHERE o.public_id='enrollment-paid-order'",String.class));
        verifyCapacityRace(jdbc,cipher,service,access,actor,entitlementBefore);
    }
    private static void verifyCapacityRace(JdbcTemplate jdbc,PiiCipher cipher,StudentTransferService service,
            AccessRepository access,Actor actor,List<java.util.Map<String,Object>> entitlementSnapshot) throws Exception {
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) VALUES('transfer-race-target',1,1,1,'并发容量目标','并发教室',1,90)");
        String target="transfer-race-target";
        Long targetId=jdbc.queryForObject("SELECT id FROM core_class WHERE public_id=?",Long.class,target);
        Instant firstStart=Instant.now().plusSeconds(600000);
        int lessonIndex=0;
        for(var lesson:entitlementSnapshot) {
            Instant startsAt=firstStart.plusSeconds(lessonIndex++*86400L);
            jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) VALUES(?,?,?,1,?,?,'scheduled')",
                "transfer-race-target-session-"+lesson.get("sequence_no"),targetId,lesson.get("source_lesson_id"),
                Timestamp.from(startsAt),Timestamp.from(startsAt.plusSeconds(5400)));
        }
        List<Actor> raceActors=new java.util.ArrayList<>();
        String issuer="https://issuer.example";
        for(int index=1;index<=2;index++) {
            String publicId="transfer-race-actor-"+index;
            String subject="transfer-race-subject-"+java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,'campus')",
                publicId,issuer,subject);
            Long principalId=jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",Long.class,publicId);
            jdbc.update("INSERT INTO core_principal_campus(principal_id,campus_id) VALUES(?,1)",principalId);
            jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,'enrollment.transfer')",principalId);
            raceActors.add(access.resolve(issuer,subject));
        }
        List<String> enrollmentIds=new java.util.ArrayList<>();
        for(int index=1;index<=2;index++) {
            String studentPublicId="transfer-race-student-"+index;
            String enrollmentPublicId="transfer-race-enrollment-"+index;
            jdbc.update("INSERT INTO core_student(public_id,name_cipher,owner_id) VALUES(?,?,?)",
                studentPublicId,cipher.encrypt("并发容量学生"+index),actor.id());
            Long studentId=jdbc.queryForObject("SELECT id FROM core_student WHERE public_id=?",Long.class,studentPublicId);
            String orderPublicId="transfer-race-order-"+index;
            jdbc.update("""
                INSERT INTO core_crm_order(public_id,student_id,campus_id,attributed_owner_id,product_snapshot,
                    total_amount,received_amount,status,source_kind,subject_snapshot)
                VALUES(?,?,1,?,'Schema test fixture',400.00,400.00,'received','direct_enrollment','数学')
                """,orderPublicId,studentId,actor.id());
            Long orderId=jdbc.queryForObject("SELECT id FROM core_crm_order WHERE public_id=?",Long.class,orderPublicId);
            jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status) VALUES(?,?,1,?,'active')",
                enrollmentPublicId,studentId,orderId);
            Long enrollmentDbId=jdbc.queryForObject("SELECT id FROM core_enrollment WHERE public_id=?",Long.class,enrollmentPublicId);
            for(var lesson:entitlementSnapshot) {
                jdbc.update("INSERT INTO core_enrollment_lesson(public_id,enrollment_id,order_id,source_lesson_id,sequence_no,title_snapshot,unit_amount) VALUES(?,?,?,?,?,?,?)",
                    "transfer-race-entitlement-"+java.util.UUID.randomUUID(),enrollmentDbId,orderId,
                    lesson.get("source_lesson_id"),lesson.get("sequence_no"),lesson.get("title_snapshot"),lesson.get("unit_amount"));
            }
            enrollmentIds.add(enrollmentPublicId);
        }
        var first=new StudentTransferDtos.Input(List.of(new StudentTransferDtos.Item(enrollmentIds.get(0),1)),target,"并发抢最后一席");
        var second=new StudentTransferDtos.Input(List.of(new StudentTransferDtos.Item(enrollmentIds.get(1),1)),target,"并发抢最后一席");
        var attempts=concurrentCapacityTransfers(service,raceActors.get(0),first,raceActors.get(1),second);
        assertEquals(1,attempts.stream().filter(a->a.receipt()!=null).count());
        assertEquals(1,attempts.stream().filter(a->a.error()==ErrorCode.ALREADY_EXISTS).count());
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM core_effective_enrollment WHERE class_id=? AND status='active' AND deleted=0",Long.class,targetId));
    }
    private record TransferAttempt(String key,StudentTransferDtos.Receipt receipt,ErrorCode error) { }
    private static TransferAttempt concurrentTransfer(StudentTransferService service,Actor actor,
            StudentTransferDtos.Input input) throws Exception {
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->attemptTransfer(service,actor,input,"transfer-race-key-0001",barrier));
            var second=pool.submit(()->attemptTransfer(service,actor,input,"transfer-race-key-0002",barrier));
            var attempts=List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),
                second.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1L,attempts.stream().filter(a->a.receipt()!=null).count());
            assertEquals(1L,attempts.stream().filter(a->a.error()==ErrorCode.PREVIEW_STALE).count());
            return attempts.stream().filter(a->a.receipt()!=null).findFirst().orElseThrow();
        } finally { pool.shutdownNow(); }
    }
    private static TransferAttempt attemptTransfer(StudentTransferService service,Actor actor,
            StudentTransferDtos.Input input,String key,java.util.concurrent.CyclicBarrier barrier) throws Exception {
        org.slf4j.MDC.put("traceId",java.util.UUID.randomUUID().toString());
        try {
            barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);
            return new TransferAttempt(key,service.transfer(actor,key,input),null);
        } catch(BusinessException error) { return new TransferAttempt(key,null,error.code()); }
        finally { org.slf4j.MDC.remove("traceId"); }
    }
    private static List<TransferAttempt> concurrentCapacityTransfers(StudentTransferService service,Actor firstActor,
            StudentTransferDtos.Input first,Actor secondActor,StudentTransferDtos.Input second) throws Exception {
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->attemptTransfer(service,firstActor,first,"transfer-capacity-race-key-01",barrier));
            var b=pool.submit(()->attemptTransfer(service,secondActor,second,"transfer-capacity-race-key-02",barrier));
            return List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(DataSource source,T service) {
        var factory=new ProxyFactory(service);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
}
