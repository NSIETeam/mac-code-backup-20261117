package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;

final class AttendanceCorrectionMySqlAssertions {
    static void verify(DataSource source,PiiCipher cipher,Actor actor,String studentId) throws Exception {
        var jdbc=new JdbcTemplate(source);
        var repository=new AttendanceCorrectionRepository(jdbc,cipher,new AcademicRepository(jdbc,cipher));
        var service=new AttendanceCorrectionService(new AccessRepository(jdbc),repository,
            new MutationRepository(jdbc,cipher),new ObjectMapper().findAndRegisterModules());
        var factory=new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        var tx=(AttendanceCorrectionService)factory.getProxy();
        var target=new AttendanceCorrectionDtos.Target(studentId,"session");
        var absent=new AttendanceCorrectionDtos.Input("class",1,"absent","更正后备注","初次点名误录");
        var corrected=tx.correct(actor,target,"mysql-correction-absent-001",absent);
        assertEquals("0.00",corrected.consumptionAmount());
        assertEquals(2,corrected.version());
        assertEquals(corrected,tx.correct(actor,target,"mysql-correction-absent-001",absent));
        assertEquals(ErrorCode.PREVIEW_STALE,assertThrows(BusinessException.class,
            ()->tx.correct(actor,target,"mysql-correction-stale-001",absent)).code());
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM core_consumption WHERE order_id=(SELECT id FROM core_crm_order WHERE public_id='enrollment-paid-order')",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT SUM(units) FROM core_consumption WHERE order_id=(SELECT id FROM core_crm_order WHERE public_id='enrollment-paid-order')",Long.class));
        assertEquals(new BigDecimal("400.00"),jdbc.queryForObject("SELECT amount FROM core_consumption WHERE attendance_id=(SELECT id FROM core_attendance WHERE public_id=?) AND correction_id IS NULL",BigDecimal.class,corrected.id()));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE core_consumption SET amount=0 WHERE public_id='consumption'"));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE core_attendance_correction SET status='leave' WHERE public_id=?",corrected.correctionId()));
        String reason=jdbc.queryForObject("SELECT reason_cipher FROM core_attendance_correction WHERE public_id=?",String.class,corrected.correctionId());
        assertFalse(reason.contains("初次点名误录"));
        var attended=new AttendanceCorrectionDtos.Input("class",2,"attended",null,"核对签到表");
        jdbc.update("UPDATE core_crm_order SET refunded_amount=1 WHERE public_id='enrollment-paid-order'");
        assertThrows(BusinessException.class,()->tx.correct(actor,target,"mysql-correction-budget-001",attended));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM core_attendance_correction WHERE attendance_id=(SELECT id FROM core_attendance WHERE public_id=?)",Long.class,corrected.id()));
        jdbc.update("UPDATE core_crm_order SET refunded_amount=0 WHERE public_id='enrollment-paid-order'");
        var restored=tx.correct(actor,target,"mysql-correction-restore-001",attended);
        assertEquals("400.00",restored.consumptionAmount());
        assertEquals(3,restored.version());
        assertEquals(1L,jdbc.queryForObject("SELECT SUM(units) FROM core_consumption WHERE order_id=(SELECT id FROM core_crm_order WHERE public_id='enrollment-paid-order')",Long.class));
        Long eventCount=jdbc.queryForObject("SELECT COUNT(*) FROM core_attendance_correction",Long.class);
        org.slf4j.MDC.remove("traceId");
        assertThrows(Exception.class,()->tx.correct(actor,target,"mysql-correction-noaudit-001",
            new AttendanceCorrectionDtos.Input("class",3,"leave",null,"审计写入失败")));
        org.slf4j.MDC.put("traceId",UUID.randomUUID().toString());
        assertEquals(eventCount,jdbc.queryForObject("SELECT COUNT(*) FROM core_attendance_correction",Long.class));
        assertEquals(new BigDecimal("400.00"),jdbc.queryForObject(
            "SELECT SUM(amount) FROM core_consumption WHERE attendance_id=(SELECT id FROM core_attendance WHERE public_id=?)",
            BigDecimal.class,corrected.id()));
        Timestamp original=jdbc.queryForObject("SELECT created_at FROM core_attendance WHERE public_id=?",Timestamp.class,corrected.id());
        jdbc.update("UPDATE core_attendance SET created_at=? WHERE public_id=?",Timestamp.from(Instant.now().minusSeconds(172800)),corrected.id());
        assertEquals(restored,tx.correct(actor,target,"mysql-correction-restore-001",attended));
        assertThrows(BusinessException.class,()->tx.correct(actor,target,"mysql-correction-midnight-001",
            new AttendanceCorrectionDtos.Input("class",3,"leave",null,"过期更正")));
        jdbc.update("UPDATE core_attendance SET created_at=? WHERE public_id=?",original,corrected.id());
        var roster=new AcademicRepository(jdbc,cipher).sessionRoster(actor,"session");
        assertEquals(3L,roster.students().stream().filter(s->s.id().equals(studentId)).findFirst().orElseThrow().attendanceVersion());
    }
}
