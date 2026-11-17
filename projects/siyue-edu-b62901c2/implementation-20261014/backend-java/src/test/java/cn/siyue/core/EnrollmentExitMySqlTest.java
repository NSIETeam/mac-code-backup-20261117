package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.siyue.core.EnrollmentExitDtos.CheckoutCancelInput;
import cn.siyue.core.EnrollmentExitDtos.Input;

/** Exercises enrollment exit and whole-checkout cancellation on an isolated MySQL schema. */
@EnabledIfEnvironmentVariable(named="SIYUE_TEST_MYSQL_URL",matches=".+")
class EnrollmentExitMySqlTest {
    private static final String ISSUER = "https://enrollment-exit-test.invalid";
    private static final String KEY = "synthetic-test-key-000000000000";
    private record OrderSeed(long customerId, long campusId, long actorId, String opportunityId,
            String orderId, String studentId, BigDecimal received, String status, String checkoutId) { }
    private record CheckoutSeed(String publicId, String firstOrderId, String secondOrderId,
            boolean includePaid) { }

    @Test void cancelWithdrawAndWholeCheckoutAreAtomicAndRetrySafe() throws Exception {
        String url = System.getenv("SIYUE_TEST_MYSQL_URL");
        URI uri = URI.create(url.substring(5));
        assertEquals("127.0.0.1", uri.getHost());
        assertTrue(uri.getPath().endsWith("_test"));
        var source = new DriverManagerDataSource(url, System.getenv("SIYUE_TEST_MYSQL_USER"),
            System.getenv("SIYUE_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PiiCipher cipher = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
        Fixture fixture = seed(jdbc, cipher);
        EnrollmentExitService service = transactionalService(jdbc, cipher, source);
        Actor actor = new AccessRepository(jdbc).resolve(ISSUER, "actor-subject");
        Actor noGrant = new AccessRepository(jdbc).resolve(ISSUER, "no-grant-subject");
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, () -> service.cancel(noGrant,
                "direct-unpaid-enrollment", "unauthorized-cancel-key-00001", new Input("未授权", 1L))).code());
            var cancelled = service.cancel(actor, "direct-unpaid-enrollment", KEY,
                new Input("合成取消原因", 1L));
            assertEquals("cancelled", cancelled.status());
            assertEquals(cancelled, service.cancel(actor, "direct-unpaid-enrollment", KEY,
                new Input("合成取消原因", 1L)));
            assertEquals("cancelled", jdbc.queryForObject("SELECT status FROM core_crm_order "
                + "WHERE public_id='order-direct-unpaid'", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment_exit_event "
                + "WHERE enrollment_id=? AND exit_type='cancel_unpaid'", Integer.class,
                enrollmentId(jdbc, "direct-unpaid-enrollment")));
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(BusinessException.class,
                () -> service.cancel(actor, "direct-unpaid-enrollment", KEY,
                    new Input("另一个原因", 1L))).code());

            var withdrawn = service.withdraw(actor, "paid-enrollment", "withdraw-key-000000000001",
                new Input("合成退课原因", 1L));
            assertEquals("withdrawn", withdrawn.status());
            assertEquals(new BigDecimal("80.00"), withdrawn.refundableAmount());
            assertNotNull(withdrawn.refundRequestId());
            assertEquals("closed", jdbc.queryForObject("SELECT status FROM core_crm_order "
                + "WHERE public_id='order-paid'", String.class));
            assertEquals("pending_approval", jdbc.queryForObject("SELECT status FROM core_crm_refund_request "
                + "WHERE public_id=?", String.class, withdrawn.refundRequestId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_refund_event "
                + "WHERE request_id=(SELECT id FROM core_crm_refund_request WHERE public_id=?) "
                + "AND event_type='requested'", Integer.class, withdrawn.refundRequestId()));
            assertEquals(1, withdrawn.cancelledAdjustments());
            assertEquals("cancelled", jdbc.queryForObject("SELECT status FROM core_session_adjustment "
                + "WHERE public_id='active-withdrawal-adjustment'", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment_event "
                + "WHERE adjustment_id=(SELECT id FROM core_session_adjustment "
                + "WHERE public_id='active-withdrawal-adjustment')", Integer.class));

            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BusinessException.class, () -> service.cancel(actor,
                "group-order-one-enrollment", "single-checkout-line-key-0001", new Input("不应单条取消", 1L)))
                .code());
            assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> service.cancelCheckout(
                actor, "accepted-checkout", "checkout-stale-version-key-01",
                new CheckoutCancelInput("过期版本", 2L))).code());
            var group = service.cancelCheckout(actor, "accepted-checkout", "checkout-cancel-key-00000001",
                new CheckoutCancelInput("合成整单取消", 1L));
            assertEquals(group, service.cancelCheckout(actor, "accepted-checkout",
                "checkout-cancel-key-00000001", new CheckoutCancelInput("合成整单取消", 1L)));
            assertEquals("cancelled", group.status());
            assertEquals(2, group.enrollments().size());
            assertTrue(group.enrollments().stream().allMatch(row -> "cancelled".equals(row.status())));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment_exit_event "
                + "WHERE checkout_id=? AND exit_type='cancel_unpaid'", Integer.class,
                checkoutId(jdbc, "accepted-checkout")));
            assertEquals("cancelled", jdbc.queryForObject("SELECT status FROM core_commerce_checkout "
                + "WHERE public_id='accepted-checkout'", String.class));

            var unaccepted = service.cancelCheckout(actor, "awaiting-checkout", "checkout-cancel-key-00000002",
                new CheckoutCancelInput("合成撤销待核对", 1L));
            assertEquals("cancelled", unaccepted.status());
            assertTrue(unaccepted.enrollments().isEmpty());

            seedCheckoutOrder(jdbc, fixture, new CheckoutSeed("mixed-checkout", "mixed-unpaid",
                "mixed-paid", true));
            long before = jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment_exit_event", Long.class);
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BusinessException.class, () -> service.cancelCheckout(
                actor, "mixed-checkout", "checkout-mixed-cancel-key-01",
                new CheckoutCancelInput("混合状态整单", 1L))).code());
            assertEquals("pending", jdbc.queryForObject("SELECT status FROM core_crm_order "
                + "WHERE public_id='mixed-unpaid'", String.class));
            assertEquals("pending", jdbc.queryForObject("SELECT status FROM core_enrollment "
                + "WHERE public_id='mixed-unpaid-enrollment'", String.class));
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(id) FROM core_enrollment_exit_event", Long.class));
        } finally {
            MDC.remove("traceId");
        }
    }

    private EnrollmentExitService transactionalService(JdbcTemplate jdbc, PiiCipher cipher,
            DriverManagerDataSource source) {
        var access = new AccessRepository(jdbc);
        var exits = new EnrollmentExitRepository(jdbc, cipher);
        var mutations = new MutationRepository(jdbc, cipher);
        var service = new EnrollmentExitService(access, exits, mutations, new ObjectMapper().findAndRegisterModules());
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        return (EnrollmentExitService) factory.getProxy();
    }

    private Fixture seed(JdbcTemplate jdbc, PiiCipher cipher) {
        jdbc.update("INSERT INTO core_campus(public_id,name_cipher) VALUES('exit-campus',?)", cipher.encrypt("合成校区"));
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
            + "VALUES('exit-actor',?,?, 'all',TRUE)", ISSUER, "actor-subject");
        jdbc.update("INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type,active) "
            + "VALUES('exit-no-grant',?,?, 'all',TRUE)", ISSUER, "no-grant-subject");
        long actorId = principalId(jdbc);
        grant(jdbc, actorId, "enrollment.create");
        grant(jdbc, actorId, "enrollment.withdraw");
        jdbc.update("INSERT INTO core_course(public_id,campus_id,name,course_type,grade,subject,price) "
            + "VALUES('exit-course',(SELECT id FROM core_campus WHERE public_id='exit-campus'),'合成课程',"
            + "'onsite','初中','数学',200.00)");
        jdbc.update("INSERT INTO core_class(public_id,course_id,campus_id,teacher_id,name,room,capacity,duration_minutes) "
            + "VALUES('exit-class',(SELECT id FROM core_course WHERE public_id='exit-course'),"
            + "(SELECT id FROM core_campus WHERE public_id='exit-campus'),?,'合成班级','A1',20,60)", actorId);
        jdbc.update("INSERT INTO core_lesson(public_id,course_id,sequence_no,title) "
            + "VALUES('exit-lesson',(SELECT id FROM core_course WHERE public_id='exit-course'),1,'合成课次')");
        jdbc.update("INSERT INTO core_session(public_id,class_id,lesson_id,teacher_id,starts_at,ends_at,status) "
            + "VALUES('exit-session',(SELECT id FROM core_class WHERE public_id='exit-class'),"
            + "(SELECT id FROM core_lesson WHERE public_id='exit-lesson'),?,"
            + "'2027-01-01 09:00:00.000','2027-01-01 10:00:00.000','scheduled')", actorId);
        jdbc.update("INSERT INTO core_customer(public_id,campus_id,owner_id,display_name_cipher) "
            + "VALUES('exit-customer',(SELECT id FROM core_campus WHERE public_id='exit-campus'),?,'cipher')", actorId);
        long customerId = jdbc.queryForObject("SELECT id FROM core_customer WHERE public_id='exit-customer'", Long.class);
        long campusId = jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id='exit-campus'", Long.class);
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,owner_id) VALUES('cancel-student','cipher',?)", actorId);
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,owner_id) VALUES('paid-student','cipher',?)", actorId);
        long classId = jdbc.queryForObject("SELECT id FROM core_class WHERE public_id='exit-class'", Long.class);
        createDirectOrder(jdbc, campusId, actorId, "order-direct-unpaid", "cancel-student");
        createOpportunityOrder(jdbc, new OrderSeed(customerId, campusId, actorId, "opp-paid", "order-paid",
            "paid-student", new BigDecimal("100.00"), "partially_received", null));
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status) "
            + "VALUES('direct-unpaid-enrollment',(SELECT id FROM core_student WHERE public_id='cancel-student'),?,"
            + "(SELECT id FROM core_crm_order WHERE public_id='order-direct-unpaid'),'pending')", classId);
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status,activated_at) "
            + "VALUES('paid-enrollment',(SELECT id FROM core_student WHERE public_id='paid-student'),?,"
            + "(SELECT id FROM core_crm_order WHERE public_id='order-paid'),'active',UTC_TIMESTAMP(3))", classId);
        long paidOrderId = jdbc.queryForObject("SELECT id FROM core_crm_order WHERE public_id='order-paid'", Long.class);
        jdbc.update("INSERT INTO core_crm_receipt(public_id,order_id,actor_id,amount,method,received_at) "
            + "VALUES('paid-receipt',?,?,100.00,'other',UTC_TIMESTAMP(3))", paidOrderId, actorId);
        long paidEnrollmentId = enrollmentId(jdbc, "paid-enrollment");
        long lessonId = jdbc.queryForObject("SELECT id FROM core_lesson WHERE public_id='exit-lesson'", Long.class);
        long sessionId = jdbc.queryForObject("SELECT id FROM core_session WHERE public_id='exit-session'", Long.class);
        long paidStudentId = jdbc.queryForObject("SELECT id FROM core_student WHERE public_id='paid-student'", Long.class);
        jdbc.update("INSERT INTO core_enrollment_lesson(public_id,enrollment_id,order_id,source_lesson_id,"
            + "sequence_no,title_snapshot,unit_amount) VALUES('paid-entitlement',?,?,?,1,'合成第一课',30.00)",
            paidEnrollmentId, paidOrderId, lessonId);
        jdbc.update("INSERT INTO core_attendance(public_id,session_id,student_id,enrollment_id,actor_id,status) "
            + "VALUES('paid-attendance',?,?,?,?,'attended')", sessionId, paidStudentId, paidEnrollmentId, actorId);
        long attendanceId = jdbc.queryForObject("SELECT id FROM core_attendance WHERE public_id='paid-attendance'",
            Long.class);
        long entitlementId = jdbc.queryForObject("SELECT id FROM core_enrollment_lesson "
            + "WHERE public_id='paid-entitlement'", Long.class);
        jdbc.update("INSERT INTO core_consumption(public_id,attendance_id,session_id,student_id,enrollment_id,"
            + "amount,order_id,entitlement_lesson_id,actor_id) VALUES('paid-consumption',?,?,?,?,30.00,?,?,?)",
            attendanceId, sessionId, paidStudentId, paidEnrollmentId, paidOrderId, entitlementId, actorId);
        jdbc.update("INSERT INTO core_attendance_correction(public_id,attendance_id,actor_id,previous_version,"
            + "previous_status,status,reason_cipher) VALUES('attendance-correction',?,?,1,'attended','leave','cipher')",
            attendanceId, actorId);
        long correctionId = jdbc.queryForObject("SELECT id FROM core_attendance_correction "
            + "WHERE public_id='attendance-correction'", Long.class);
        jdbc.update("INSERT INTO core_consumption(public_id,attendance_id,session_id,student_id,enrollment_id,"
            + "amount,order_id,entitlement_lesson_id,actor_id,correction_id,units) "
            + "VALUES('paid-consumption-correction',?,?,?,?, -10.00,?,?,?,?, -1)", attendanceId, sessionId,
            paidStudentId, paidEnrollmentId, paidOrderId, entitlementId, actorId, correctionId);
        jdbc.update("INSERT INTO core_session_adjustment(public_id,kind,status,enrollment_id,order_id,student_id,"
            + "source_class_id,source_session_id,lesson_id,target_class_id,target_session_id,created_by_id,reason_cipher) "
            + "VALUES('active-withdrawal-adjustment','makeup','active',?, ?,"
            + "(SELECT id FROM core_student WHERE public_id='paid-student'), ?,?,?, ?,?,?,'cipher')",
            paidEnrollmentId, paidOrderId, classId, sessionId, lessonId, classId, sessionId, actorId);
        jdbc.update("INSERT INTO core_commerce_checkout(public_id,checkout_type,status,buyer_count,created_by_id) "
            + "VALUES('awaiting-checkout','standard','awaiting_verification',1,?)", actorId);
        Fixture fixture = new Fixture(customerId, campusId, actorId, classId);
        seedCheckoutOrder(jdbc, fixture, new CheckoutSeed("accepted-checkout", "group-order-one",
            "group-order-two", false));
        return new Fixture(customerId, campusId, actorId, classId);
    }

    private void createOpportunityOrder(JdbcTemplate jdbc, OrderSeed seed) {
        Long opportunityId = null;
        Long customerId = null;
        String sourceKind = "commerce_checkout";
        if (seed.checkoutId() == null) {
            jdbc.update("INSERT INTO core_opportunity(public_id,customer_id,campus_id,owner_id,title,subject,term,need_key,stage) "
                + "VALUES(?, ?, ?, ?, '合成需求','数学','2026秋',?,'won')", seed.opportunityId(), seed.customerId(),
                seed.campusId(), seed.actorId(), UUID.randomUUID().toString().replace("-", "")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            opportunityId = jdbc.queryForObject("SELECT id FROM core_opportunity WHERE public_id=?", Long.class,
                seed.opportunityId());
            customerId = seed.customerId();
            sourceKind = "opportunity";
        }
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,student_id,campus_id,"
            + "attributed_owner_id,product_snapshot,total_amount,received_amount,status,source_kind,"
            + "commerce_checkout_id,subject_snapshot) VALUES(?,?,?,?,?,?,'合成课程',200.00,?,?,?,?, '数学')",
            seed.orderId(), opportunityId, customerId, jdbc.queryForObject(
                "SELECT id FROM core_student WHERE public_id=?", Long.class, seed.studentId()), seed.campusId(),
            seed.actorId(), seed.received(), seed.status(), sourceKind,
            seed.checkoutId() == null ? null : checkoutId(jdbc, seed.checkoutId()));
    }

    private void seedCheckoutOrder(JdbcTemplate jdbc, Fixture fixture, CheckoutSeed seed) {
        String firstStudent = seed.publicId() + "-student-one";
        String secondStudent = seed.publicId() + "-student-two";
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,owner_id) VALUES(?,'cipher',?)",
            firstStudent, fixture.actorId());
        jdbc.update("INSERT INTO core_student(public_id,name_cipher,owner_id) VALUES(?,'cipher',?)",
            secondStudent, fixture.actorId());
        jdbc.update("INSERT INTO core_commerce_checkout(public_id,checkout_type,status,buyer_count,created_by_id,"
            + "accepted_by_id,accepted_at) VALUES(?,'group','accepted',2,?,?,UTC_TIMESTAMP(3))",
            seed.publicId(), fixture.actorId(), fixture.actorId());
        long checkoutId = checkoutId(jdbc, seed.publicId());
        jdbc.update("INSERT INTO core_commerce_checkout_campus(checkout_id,campus_id) VALUES(?,?)",
            checkoutId, fixture.campusId());
        String[] orderIds = {seed.firstOrderId(), seed.secondOrderId()};
        String[] students = {firstStudent, secondStudent};
        for (int index = 0; index < orderIds.length; index++) {
            String opportunity = orderIds[index] + "-opp";
            BigDecimal received = seed.includePaid() && index == 1 ? new BigDecimal("1.00") : BigDecimal.ZERO;
            createOpportunityOrder(jdbc, new OrderSeed(fixture.customerId(), fixture.campusId(), fixture.actorId(),
                opportunity, orderIds[index], students[index], received, "pending", seed.publicId()));
            long orderId = jdbc.queryForObject("SELECT id FROM core_crm_order WHERE public_id=?", Long.class,
                orderIds[index]);
            if (seed.includePaid() && index == 1) {
                jdbc.update("UPDATE core_crm_order SET received_amount=1.00,status='partially_received' WHERE id=?",
                    orderId);
                jdbc.update("INSERT INTO core_crm_receipt(public_id,order_id,actor_id,amount,method,received_at) "
                    + "VALUES(?,?,?,?, 'other',UTC_TIMESTAMP(3))", orderIds[index] + "-receipt", orderId,
                    fixture.actorId(), new java.math.BigDecimal("1.00"));
            }
            jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status) VALUES(?,?,?,?,'pending')",
                orderIds[index] + "-enrollment", jdbc.queryForObject("SELECT id FROM core_student WHERE public_id=?",
                    Long.class, students[index]), fixture.classId(), orderId);
        }
    }

    private void createDirectOrder(JdbcTemplate jdbc, long campusId, long actorId, String orderId,
            String studentPublicId) {
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,student_id,campus_id,"
            + "attributed_owner_id,product_snapshot,total_amount,source_kind,subject_snapshot) "
            + "VALUES(?,NULL,NULL,(SELECT id FROM core_student WHERE public_id=?),?,?,'合成课程',"
            + "200.00,'direct_enrollment','数学')", orderId, studentPublicId, campusId, actorId);
    }

    private static long principalId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM core_principal WHERE public_id='exit-actor'", Long.class);
    }
    private static void grant(JdbcTemplate jdbc, long actor, String permission) {
        jdbc.update("INSERT INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)", actor, permission);
    }
    private static long enrollmentId(JdbcTemplate jdbc, String publicId) {
        return jdbc.queryForObject("SELECT id FROM core_enrollment WHERE public_id=?", Long.class, publicId);
    }
    private static long checkoutId(JdbcTemplate jdbc, String publicId) {
        return jdbc.queryForObject("SELECT id FROM core_commerce_checkout WHERE public_id=?", Long.class, publicId);
    }
    private record Fixture(long customerId, long campusId, long actorId, long classId) { }
}
