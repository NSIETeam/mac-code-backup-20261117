package cn.siyue.core;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class DirectEnrollmentOrderMySqlTest {
    @Test void directOrdersRemainVisibleScopedReceivableAndImmutable() {
        String url=System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri=URI.create(url.substring(5));
        assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPath().endsWith("_test"));
        var source=new DriverManagerDataSource(url,System.getenv("SIYUE_TEST_MYSQL_USER"),System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);
        var cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String tag=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES(?,?,?,'own')",tag,"https://test.example",tag);
        long owner=jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id=?",Long.class,tag);
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES(?,?)",tag,cipher.encrypt("模拟校区"));
        long campus=jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?",Long.class,tag);
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,owner_id) VALUES(?,?,?)",tag,cipher.encrypt("模拟学员"),owner);
        long student=jdbc.queryForObject("SELECT id FROM core_student WHERE public_id=?",Long.class,tag);
        jdbc.update("""
            INSERT INTO core_crm_order(public_id,student_id,campus_id,attributed_owner_id,
                product_snapshot,total_amount,source_kind,subject_snapshot)
            VALUES(?,?,?,?,'模拟课程',100,'direct_enrollment','数学')
            """,tag,student,campus,owner);
        var actor=new Actor(owner,tag,"own",Set.of(),Set.of());
        var repo=new CrmOrderRepository(jdbc,cipher);
        var page=repo.list(actor,null,1,100);
        assertEquals(1,page.total());assertEquals(1,page.items().size());
        assertNull(page.items().get(0).opportunityId());assertNull(page.items().get(0).customerId());
        var outsider=new Actor(-1,"outsider","own",Set.of(),Set.of());
        assertEquals(0,repo.list(outsider,null,1,100).total());
        assertThrows(BusinessException.class,()->repo.authorize(outsider,tag));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        Instant now=Instant.now();
        tx.execute(s->repo.receive(actor,tag,new CrmDtos.ReceiptInput(new BigDecimal("100.00"),"cash","模拟凭据",now,1)));
        assertEquals("received",repo.list(actor,null,1,100).items().get(0).status());
        var report=repo.receiptReport(actor,now.minusSeconds(10),now.plusSeconds(10),1,100);
        assertEquals(1,report.orderCount());assertEquals(1,report.items().size());
        assertEquals(new BigDecimal("100.00"),report.totalReceived());
        var cash=new CrmRefundRepository(jdbc,cipher).cashFlowReport(actor,now.minusSeconds(10),now.plusSeconds(10),1,100);
        assertEquals(1,cash.items().size());assertEquals(tag,cash.items().get(0).orderId());
        verifyAcademicEnrollment(jdbc,cipher,tx,actor,tag);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE core_crm_order SET subject_snapshot='英语' WHERE public_id=?",tag));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE core_crm_order SET source_kind='opportunity' WHERE public_id=?",tag));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            INSERT INTO core_crm_order(public_id,attributed_owner_id,product_snapshot,total_amount)
            VALUES(?,?,'无商机订单',100)
            """,tag+"-invalid",owner));
    }
    private void verifyAcademicEnrollment(JdbcTemplate jdbc,PiiCipher cipher,TransactionTemplate tx,
            Actor actor,String tag) {
        long campus=jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?",Long.class,tag);
        jdbc.update("""
            INSERT INTO core_course(public_id,campus_id,name,course_type,grade,subject,price)
            VALUES(?,?,'模拟课程','onsite','小学四年级','数学',100)
            """,tag,campus);
        long course=jdbc.queryForObject("SELECT id FROM core_course WHERE public_id=?",Long.class,tag);
        for(int i=1;i<=3;i++) jdbc.update(
            "INSERT INTO core_lesson(public_id,course_id,sequence_no,title) VALUES(?,?,?,'模拟讲次')",tag+"-"+i,course,i);
        jdbc.update("""
            INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes)
            VALUES(?,?,?,?,'模拟班级','模拟教室',10,90)
            """,tag,course,campus,actor.id());
        var academic=new AcademicRepository(jdbc,cipher);
        assertThrows(BusinessException.class,()->tx.execute(s->academic.enrollStudent(actor,tag,tag,tag)));
        var teacher=new Actor(actor.id(),actor.publicId(),"assigned",Set.of(),Set.of());
        var receipt=tx.execute(s->academic.enrollStudent(teacher,tag,tag,tag));
        assertEquals("active",receipt.status());
        long enrollment=jdbc.queryForObject("SELECT id FROM core_enrollment WHERE public_id=?",Long.class,receipt.id());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment_lesson WHERE enrollment_id=?",Integer.class,enrollment));
        assertEquals(new BigDecimal("100.00"),jdbc.queryForObject(
            "SELECT SUM(unit_amount) FROM core_enrollment_lesson WHERE enrollment_id=?",BigDecimal.class,enrollment));
        assertEquals(new BigDecimal("33.34"),jdbc.queryForObject(
            "SELECT unit_amount FROM core_enrollment_lesson WHERE enrollment_id=? AND sequence_no=1",BigDecimal.class,enrollment));
        assertThrows(BusinessException.class,()->tx.execute(s->academic.enrollStudent(teacher,tag,tag,tag)));
    }

}
