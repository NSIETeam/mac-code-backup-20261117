package cn.siyue.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import cn.siyue.core.AcademicDtos.Communication;
import cn.siyue.core.AcademicDtos.CommunicationInput;
import cn.siyue.core.AcademicDtos.AttendanceRecord;
import cn.siyue.core.AcademicDtos.Student;
import cn.siyue.core.AcademicDtos.StudentPage;
import cn.siyue.core.AcademicDtos.StudentInput;
import cn.siyue.core.AcademicDtos.EnrollmentInput;
import cn.siyue.core.AcademicDtos.EnrollmentReceipt;
import cn.siyue.core.AcademicDtos.TeachingMetrics;
import cn.siyue.core.AcademicDtos.CourseInput;
import cn.siyue.core.AcademicDtos.CourseReceipt;
import cn.siyue.core.AcademicDtos.CoursePage;
import cn.siyue.core.AcademicDtos.LessonSummary;
import cn.siyue.core.AcademicDtos.ClassInput;
import cn.siyue.core.AcademicDtos.ClassUpdateInput;
import cn.siyue.core.AcademicDtos.ClassUpdateReceipt;
import cn.siyue.core.AcademicDtos.ClassReceipt;
import cn.siyue.core.AcademicDtos.ClassSessionSummary;
import cn.siyue.core.AcademicDtos.ClassPage;
import cn.siyue.core.AcademicDtos.SessionRoster;
import cn.siyue.core.AcademicDtos.SessionStudent;
import cn.siyue.core.AcademicDtos.SessionScheduleInput;
import cn.siyue.core.AcademicDtos.SessionScheduleReceipt;

/** Indexed academic reads and append-only communication persistence. */
@Repository
public class AcademicRepository {
    record Member(long studentId, long classId) { }
    record AttendanceMember(long studentId,long classId,long sessionId,long enrollmentId,Long orderId,
            String classPublicId,String studentPublicId) { }
    record SessionScheduleRow(long id,long classId,long campusId,long classCampusId,long teacherId,String publicId,
            String classPublicId,String room,Instant startsAt,Instant endsAt,String status,
            long version,int durationMinutes) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentProfileRepository studentProfiles;
    /** Uses explicit columns and encrypted personal data. */
    public AcademicRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.studentProfiles = new StudentProfileRepository(jdbc, cipher);
    }
    /** Queries active students and their total using the same scope and class filter. */
    public StudentPage students(Actor actor, String classId, int page, int size) {
        return students(actor, new AcademicDtos.StudentQuery("scope", classId, page, size));
    }
    /** Narrows students to actual teaching relationships for a personal view. */
    public StudentPage students(Actor actor, AcademicDtos.StudentQuery query) {
        int page = query.page(), size = query.size();
        ScopeSql.Clause clause = AcademicReadScope.students(actor, query);
        Long total = jdbc.queryForObject("SELECT COUNT(s.id) FROM core_student s WHERE "
            + clause.sql(), Long.class, clause.args().toArray());
        List<Object> args = new ArrayList<>(clause.args());
        args.add(size);
        args.add(Math.multiplyExact((long) page - 1, size));
        List<Student> items = jdbc.query("""
            SELECT s.public_id, s.name_cipher, s.grade, s.phone_cipher, s.version,
                cp.public_id AS campus_public_id,l.public_id AS lead_public_id,c.public_id AS customer_public_id,
                owner.public_id AS owner_public_id,assignee.public_id AS assignee_public_id
            FROM core_student s LEFT JOIN core_campus cp ON cp.id=s.campus_id
                LEFT JOIN core_crm_lead l ON l.id=s.origin_lead_id
                LEFT JOIN core_customer c ON c.id=s.customer_id
                LEFT JOIN core_principal owner ON owner.id=s.owner_id
                LEFT JOIN core_principal assignee ON assignee.id=s.assignee_id
            WHERE
            """ + clause.sql() + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new Student(rs.getString("public_id"), cipher.decrypt(rs.getString("name_cipher")),
                rs.getString("grade"), cipher.maskedPhone(rs.getString("phone_cipher")),
                rs.getLong("version"), rs.getString("campus_public_id"), rs.getString("lead_public_id"),
                rs.getString("customer_public_id"), rs.getString("owner_public_id"),
                rs.getString("assignee_public_id")), args.toArray());
        return new StudentPage(items, total == null ? 0 : total, page, size);
    }
    /** Queries every authorized profile, including students without current enrollment. */
    public StudentPage studentArchives(Actor actor, int page, int size) {
        ScopeSql.Clause scope = ScopeSql.studentReads(actor);
        Long total = jdbc.queryForObject("SELECT COUNT(s.id) FROM core_student s WHERE s.deleted=0 AND ("
            + scope.sql() + ")", Long.class, scope.args().toArray());
        List<Object> args = new ArrayList<>(scope.args());
        args.add(size);
        args.add(Math.multiplyExact((long) page - 1, size));
        List<Student> items = jdbc.query("SELECT s.public_id,s.name_cipher,s.grade,s.phone_cipher,s.version,"
            + "cp.public_id campus_public_id,l.public_id lead_public_id,c.public_id customer_public_id,"
            + "owner.public_id owner_public_id,assignee.public_id assignee_public_id FROM core_student s "
            + "LEFT JOIN core_campus cp ON cp.id=s.campus_id LEFT JOIN core_crm_lead l ON l.id=s.origin_lead_id "
            + "LEFT JOIN core_customer c ON c.id=s.customer_id LEFT JOIN core_principal owner ON owner.id=s.owner_id "
            + "LEFT JOIN core_principal assignee ON assignee.id=s.assignee_id WHERE s.deleted=0 AND ("
            + scope.sql() + ") ORDER BY s.id DESC LIMIT ? OFFSET ?", (rs, row) -> new Student(
                rs.getString("public_id"), cipher.decrypt(rs.getString("name_cipher")), rs.getString("grade"),
                cipher.maskedPhone(rs.getString("phone_cipher")), rs.getLong("version"),
                rs.getString("campus_public_id"), rs.getString("lead_public_id"),
                rs.getString("customer_public_id"), rs.getString("owner_public_id"),
                rs.getString("assignee_public_id")), args.toArray());
        return new StudentPage(items, total == null ? 0 : total, page, size);
    }
    /** Delegates authorized profile history to the profile persistence boundary. */
    public AcademicDtos.StudentHistoryPage studentHistory(Actor actor, String studentId, int page, int size) {
        return studentProfiles.history(actor, studentId, page, size);
    }
    /** Creates one owned student profile while encrypting name and phone before persistence. */
    public Student createStudent(Actor actor, StudentInput input) {
        return studentProfiles.createStudent(actor, input);
    }
    /** Acquires the identity lock before the service locks the acting principal. */
    public void lockCreateIdentity(StudentInput input) { studentProfiles.lockCreateIdentity(input); }
    /** Acquires the proposed identity lock before the service locks the acting principal. */
    public void lockUpdateIdentity(Actor actor, String publicId, AcademicDtos.StudentUpdateInput input) {
        studentProfiles.lockUpdateIdentity(actor, publicId, input);
    }
    /** Updates one existing student profile under the legacy ownership and campus scope. */
    public Student updateStudent(Actor actor, String publicId, AcademicDtos.StudentUpdateInput input) {
        return studentProfiles.updateStudent(actor, publicId, input);
    }
    /** Validates current row scope before a retry can reveal a stored response. */
    public void authorizeStudentUpdate(Actor actor, String publicId) {
        studentProfiles.authorizeStudentUpdate(actor, publicId);
    }
    /** Checks both class and student visibility before an idempotency replay. */
    public void authorizeEnrollment(Actor actor, String classPublicId, String studentPublicId,
            String orderPublicId) {
        EnrollmentClass target = enrollmentClass(actor, classPublicId, false);
        EnrollmentOrder order = enrollmentOrder(actor, orderPublicId, false);
        validatePaidEnrollment(target, studentPublicId, order);
    }
    /** Creates the active enrollment and lesson-price snapshot from a fully paid CRM order. */
    public EnrollmentReceipt enrollStudent(Actor actor, String classPublicId, String studentPublicId,
            String orderPublicId) {
        EnrollmentClass target = enrollmentClass(actor, classPublicId, true);
        EnrollmentOrder order = enrollmentOrder(actor, orderPublicId, true);
        validatePaidEnrollment(target, studentPublicId, order);
        // Authorization may have established an older REPEATABLE READ snapshot.
        // After the class/order locks, current reads must observe the previous holder's commit.
        Integer orderUsed = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_effective_enrollment WHERE order_id=? AND deleted=0 FOR UPDATE
            """, Integer.class, order.id());
        if (orderUsed != null && orderUsed > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该已付款订单已关联报名");
        }
        Integer existing = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_effective_enrollment
            WHERE class_id = ? AND student_id = ? AND status = 'active' AND deleted = 0 FOR UPDATE
            """, Integer.class, target.id(), order.studentId());
        if (existing != null && existing > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "学生已在该班级有效在读");
        }
        Integer current = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_effective_enrollment
            WHERE class_id = ? AND status = 'active' AND deleted = 0 FOR UPDATE
            """, Integer.class, target.id());
        if (current != null && current >= target.capacity()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "班级人数已达到容量上限");
        }
        Instant activatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String enrollmentPublicId = "ENR_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_enrollment (public_id,student_id,class_id,order_id,status,activated_at)
            VALUES (?,?,?,?,'active',?)
            """, enrollmentPublicId, order.studentId(), target.id(), order.id(), Timestamp.from(activatedAt));
        long enrollmentId = internalId("SELECT id FROM core_effective_enrollment WHERE public_id=?", enrollmentPublicId);
        addEnrollmentLessons(enrollmentId, target.courseId(), order);
        return new EnrollmentReceipt(enrollmentPublicId, studentPublicId, classPublicId, "active", activatedAt);
    }
    private void validatePaidEnrollment(EnrollmentClass target, String studentPublicId, EnrollmentOrder order) {
        List<Long> boundClasses=jdbc.query("SELECT class_id FROM core_crm_order_class_terms WHERE order_id=?",(rs,row)->rs.getLong(1),order.id());
        if(!boundClasses.isEmpty() && boundClasses.get(0)!=target.id()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"订单已绑定其他班级，不能用于本班报名");
        }
        if (!studentPublicId.equals(order.studentPublicId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "报名学生须与商机订单中的学生一致");
        }
        if (!order.status().equals("received")
                || order.received().compareTo(order.total()) != 0 || order.refunded().signum() != 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "报名须关联已足额收款且未退款的订单");
        }
        if (order.campusId() == null || order.campusId() != target.campusId()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "订单校区与报名班级不一致");
        }
        String subject = jdbc.queryForObject("""
            SELECT subject FROM core_course WHERE id=? AND active=1 AND deleted=0
            """, String.class, target.courseId());
        if (!order.subject().equals(subject)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "订单意向学科与报名课程不一致");
        }
        Integer lessonCount = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_lesson WHERE course_id=? AND deleted=0
            """, Integer.class, target.courseId());
        if (lessonCount == null || lessonCount < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "课程没有可关联的教学讲次");
        }
    }

    private EnrollmentOrder enrollmentOrder(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = enrollmentOrderScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<EnrollmentOrder> orders = jdbc.query("""
            SELECT o.id,o.student_id,st.public_id AS student_public_id,o.campus_id,COALESCE(opp.subject,o.subject_snapshot) AS subject,
                   o.total_amount,o.received_amount,o.refunded_amount,o.status
            FROM core_crm_order o LEFT JOIN core_opportunity opp ON opp.id=o.opportunity_id
            LEFT JOIN core_student st ON st.id=o.student_id AND st.deleted=0
            WHERE o.public_id=? AND o.deleted=0 AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new EnrollmentOrder(rs.getLong("id"), rs.getLong("student_id"),
                rs.getString("student_public_id"), rs.getObject("campus_id", Long.class), rs.getString("subject"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("refunded_amount"), rs.getString("status")), args.toArray());
        if (orders.isEmpty() || orders.get(0).studentPublicId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在、无学籍学生或不在授权范围");
        }
        return orders.get(0);
    }

    private ScopeSql.Clause enrollmentOrderScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus "
                + "WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "assigned", "own" -> new ScopeSql.Clause("o.attributed_owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }

    private void addEnrollmentLessons(long enrollmentId, long courseId, EnrollmentOrder order) {
        List<EnrollmentLesson> lessons = jdbc.query("""
            SELECT id,sequence_no,title FROM core_lesson WHERE course_id=? AND deleted=0 ORDER BY sequence_no,id
            """, (rs, row) -> new EnrollmentLesson(rs.getLong("id"), rs.getInt("sequence_no"),
                rs.getString("title")), courseId);
        var terms=new CrmClassOrderRepository(jdbc).read(order.id());
        java.math.BigDecimal tuition=terms==null ? order.total() : terms.fees().courseFee();
        long cents = tuition.movePointRight(2).longValueExact();
        long base = cents / lessons.size();
        long remainder = cents % lessons.size();
        for (int index = 0; index < lessons.size(); index++) {
            EnrollmentLesson lesson = lessons.get(index);
            java.math.BigDecimal amount = java.math.BigDecimal.valueOf(base + (index < remainder ? 1 : 0), 2);
            jdbc.update("""
                INSERT INTO core_enrollment_lesson(public_id,enrollment_id,order_id,source_lesson_id,
                    sequence_no,title_snapshot,unit_amount) VALUES(?,?,?,?,?,?,?)
                """, "ENRL_" + UUID.randomUUID(), enrollmentId, order.id(), lesson.id(),
                lesson.sequence(), lesson.title(), amount);
        }
    }

    private long internalId(String sql, String publicId) {
        Long id = jdbc.queryForObject(sql, Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "报名写入失败"); }
        return id;
    }
    private EnrollmentClass enrollmentClass(Actor actor, String classPublicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>();
        args.add(classPublicId);
        args.addAll(scope.args());
        List<EnrollmentClass> classes = jdbc.query("""
            SELECT c.id,c.capacity,c.campus_id,c.course_id FROM core_class c
            WHERE c.public_id = ? AND c.active = 1 AND c.deleted = 0 AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new EnrollmentClass(rs.getLong("id"), rs.getInt("capacity"),
                rs.getLong("campus_id"), rs.getLong("course_id")), args.toArray());
        if (classes.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级不存在或不在授权范围"); }
        return classes.get(0);
    }
    /** Aggregates authorized class capacity and monthly attendance/consumption facts. */
    public TeachingMetrics teachingMetrics(Actor actor, String month, Instant from, Instant until) {
        return teachingMetrics(actor, new AcademicDtos.TeachingPeriod(month, "scope", from, until));
    }
    /** Reads monthly facts using the same teaching view as the class and student queues. */
    public TeachingMetrics teachingMetrics(Actor actor, AcademicDtos.TeachingPeriod period) {
        String month = period.month();
        Instant from = period.fromAt(), until = period.untilAt();
        ScopeSql.Clause scope = AcademicReadScope.classes(actor, period.view());
        List<Object> scopeArgs = scope.args();
        List<ClassOccupancy> classes = jdbc.query("""
            SELECT c.id, c.capacity, COUNT(DISTINCT st.id) AS students
            FROM core_class c LEFT JOIN core_effective_enrollment e
              ON e.class_id = c.id AND e.status = 'active' AND e.deleted = 0
            LEFT JOIN core_student st ON st.id = e.student_id AND st.deleted = 0
            WHERE c.deleted = 0 AND c.active = 1 AND (
            """ + scope.sql() + ") GROUP BY c.id,c.capacity",
            (rs, row) -> new ClassOccupancy(rs.getLong("id"), rs.getLong("capacity"),
                rs.getLong("students")), scopeArgs.toArray());
        Long visibleStudents = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT st.id)
            FROM core_effective_enrollment e JOIN core_student st ON st.id = e.student_id AND st.deleted = 0
            JOIN core_class c ON c.id = e.class_id
            WHERE e.status = 'active' AND e.deleted = 0 AND c.deleted = 0 AND c.active = 1 AND (
            """ + scope.sql() + ")", Long.class, scopeArgs.toArray());
        long students = visibleStudents == null ? 0 : visibleStudents;
        long capacity = classes.stream().mapToLong(ClassOccupancy::capacity).sum();
        long fullClasses = classes.stream().filter(c -> c.students() >= c.capacity()).count();
        Map<String, Long> attendance = new LinkedHashMap<>();
        for (String status : List.of("attended", "late", "left_early", "leave", "absent")) {
            attendance.put(status, 0L);
        }
        List<Object> periodArgs = new ArrayList<>();
        periodArgs.add(Timestamp.from(from));
        periodArgs.add(Timestamp.from(until));
        periodArgs.addAll(scope.args());
        ScopeSql.Clause sessionScope = AcademicReadScope.sessions(actor, period.view());
        String sessionPredicate = sessionScope.sql().replace("s.teacher_id", "ss.teacher_id");
        periodArgs.addAll(sessionScope.args());
        jdbc.query("""
            SELECT a.status, COUNT(a.id) AS amount
            FROM core_attendance a JOIN core_session ss ON ss.id = a.session_id AND ss.deleted = 0
            JOIN core_class c ON c.id = ss.class_id AND c.deleted = 0
            WHERE a.deleted = 0 AND ss.starts_at >= ? AND ss.starts_at < ? AND (
            """ + scope.sql() + ") AND (" + sessionPredicate + ") GROUP BY a.status",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                attendance.put(rs.getString("status"), rs.getLong("amount")), periodArgs.toArray());
        periodArgs = new ArrayList<>();
        periodArgs.add(Timestamp.from(from));
        periodArgs.add(Timestamp.from(until));
        periodArgs.addAll(scope.args());
        periodArgs.addAll(sessionScope.args());
        ConsumptionTotal consumed = jdbc.queryForObject("""
            SELECT COALESCE(SUM(x.units),0) AS amount, COALESCE(SUM(x.amount),0) AS total
            FROM core_consumption x JOIN core_session ss ON ss.id = x.session_id AND ss.deleted = 0
            JOIN core_class c ON c.id = ss.class_id AND c.deleted = 0
            WHERE x.deleted = 0 AND ss.starts_at >= ? AND ss.starts_at < ? AND (
            """ + scope.sql() + ") AND (" + sessionPredicate + ")", (rs, row) -> new ConsumptionTotal(rs.getLong("amount"),
                rs.getBigDecimal("total")), periodArgs.toArray());
        BigDecimal fullRate = rate(fullClasses, classes.size());
        BigDecimal seatRate = rate(classes.stream().mapToLong(ClassOccupancy::students).sum(), capacity);
        return new TeachingMetrics(month, classes.size(), students, fullClasses, fullRate, seatRate,
            Map.copyOf(attendance), consumed == null ? 0 : consumed.count(),
            consumed == null ? BigDecimal.ZERO : consumed.amount());
    }
    private static BigDecimal rate(long value, long total) {
        return total == 0 ? BigDecimal.ZERO.setScale(2)
            : BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
    /** Resolves authorized membership without a write lock for read-only requests. */
    public Member member(Actor actor, String studentId, String classId) {
        return membership(actor, studentId, classId, false);
    }
    /** Locks the membership for a communication write. */
    public Member lockMember(Actor actor, String studentId, String classId) {
        return membership(actor, studentId, classId, true);
    }
    private Member membership(Actor actor, String studentId, String classId, boolean lock) {
        ScopeSql.Clause clause = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(List.of(studentId, classId));
        args.addAll(clause.args());
        List<Member> members = jdbc.query("""
            SELECT s.id AS student_id, c.id AS class_id FROM core_student s
            JOIN core_effective_enrollment e ON e.student_id = s.id AND e.status = 'active' AND e.deleted = 0
            JOIN core_class c ON c.id = e.class_id AND c.deleted = 0 AND c.active = 1
            WHERE s.public_id = ? AND c.public_id = ? AND s.deleted = 0 AND (
            """ + clause.sql() + ") ORDER BY e.id LIMIT 1" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new Member(rs.getLong("student_id"), rs.getLong("class_id")), args.toArray());
        if (members.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "学生或班级不在授权的在读范围");
        }
        return members.get(0);
    }
    /** Reads an authorized active session before a schedule update or idempotent replay. */
    public SessionScheduleRow scheduleRow(Actor actor,String sessionPublicId) {
        ScopeSql.Clause scope=ScopeSql.classes(actor);
        List<Object> args=new ArrayList<>(List.of(sessionPublicId));args.addAll(scope.args());
        List<SessionScheduleRow> rows=jdbc.query("""
            SELECT ss.id,ss.class_id,ss.public_id,c.public_id AS class_public_id,
                   COALESCE(ss.campus_id,c.campus_id) campus_id,c.campus_id class_campus_id,
                   COALESCE(ss.room,c.room) room,
                   ss.teacher_id,ss.starts_at,ss.ends_at,ss.status,ss.version,c.duration_minutes
            FROM core_session ss JOIN core_class c ON c.id=ss.class_id
            WHERE ss.public_id=? AND ss.deleted=0 AND c.deleted=0 AND c.active=1 AND (
            """+scope.sql()+")",
            (rs,row)->new SessionScheduleRow(rs.getLong("id"),rs.getLong("class_id"),
                rs.getLong("campus_id"),rs.getLong("class_campus_id"),rs.getLong("teacher_id"),rs.getString("public_id"),
                rs.getString("class_public_id"),rs.getString("room"),rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),rs.getString("status"),rs.getLong("version"),
                rs.getInt("duration_minutes")),args.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"课次不存在或不在授权范围");
        return rows.get(0);
    }

    /** Moves an unprotected future session and appends an encrypted schedule-change reason. */
    public SessionScheduleReceipt moveSession(Actor actor,SessionScheduleRow current,SessionScheduleInput input) {
        lockScheduleCampuses(current.classCampusId(), current.campusId());
        lockSessionClass(actor,current.classId());
        // Class metadata and assignments are now stable; all schedule writers take this same lock first.
        List<SessionScheduleRow> refreshed=readScheduleRow(actor,current.publicId(),false);
        if(refreshed.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"课次不存在或不在授权范围");
        SessionScheduleRow fresh=refreshed.get(0);
        if (fresh.classCampusId()!=current.classCampusId() || fresh.campusId()!=current.campusId()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,"课次地点已变化，请刷新后重试");
        }
        current=fresh;
        if(current.version()!=input.expectedVersion())throw new BusinessException(ErrorCode.ALREADY_EXISTS,"课次版本已变化");
        if(!current.status().equals("scheduled")||!current.startsAt().isAfter(Instant.now()))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"已开始或已取消课次不能调整");
        Integer protectedFacts=jdbc.queryForObject("""
            SELECT (SELECT COUNT(id) FROM core_attendance WHERE session_id=? AND deleted=0)
                 +(SELECT COUNT(id) FROM core_consumption WHERE session_id=? AND deleted=0)
                 +(SELECT COUNT(id) FROM core_session_adjustment WHERE deleted=0 AND status IN ('active','completed')
                    AND (source_session_id=? OR target_session_id=?))
            """,Integer.class,current.id(),current.id(),current.id(),current.id());
        if(protectedFacts!=null&&protectedFacts>0)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"已有考勤或课消的课次不能调整");
        Instant startsAt=input.startsAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant endsAt=startsAt.plusSeconds(current.durationMinutes()*60L);
        if(!startsAt.isAfter(Instant.now())||startsAt.equals(current.startsAt()))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"新时间必须位于未来且不同于原时间");
        lockScheduleTeacher(current.teacherId());
        jdbc.query("SELECT id FROM core_session WHERE id=? FOR UPDATE",(rs,row)->rs.getLong(1),current.id());
        if(hasScheduleConflict(current,startsAt,endsAt))
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,"老师或教室在该时段已有排课");
        new AcademicSchedulePolicyRepository(jdbc,cipher).validateMove(current,startsAt,endsAt);
        int updated=jdbc.update("""
            UPDATE core_session SET starts_at=?,ends_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status='scheduled' AND deleted=0
            """,Timestamp.from(startsAt),Timestamp.from(endsAt),current.id(),input.expectedVersion());
        if(updated!=1)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"课次状态已变化");
        jdbc.update("UPDATE core_class SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND deleted=0",
            current.classId());
        jdbc.update("""
            INSERT INTO core_session_event(public_id,session_id,actor_id,event_type,from_starts_at,to_starts_at,reason_cipher)
            VALUES (?, ?, ?, 'rescheduled', ?, ?, ?)
            ""","SESEVT_"+UUID.randomUUID(),current.id(),actor.id(),Timestamp.from(current.startsAt()),
            Timestamp.from(startsAt),cipher.encrypt(input.reason().trim()));
        return new SessionScheduleReceipt(current.publicId(),current.classPublicId(),startsAt,endsAt,current.version()+1);
    }

    /** Checks the same room and teacher constraints before a teacher's request enters review. */
    void validateMoveRequest(Actor actor, SessionScheduleRow current, SessionScheduleInput input) {
        if (current.version() != input.expectedVersion() || !"scheduled".equals(current.status())
                || !current.startsAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "原课次已开始或发生变化");
        }
        Integer protectedFacts = jdbc.queryForObject("""
            SELECT (SELECT COUNT(id) FROM core_attendance WHERE session_id=? AND deleted=0)
                 +(SELECT COUNT(id) FROM core_consumption WHERE session_id=? AND deleted=0)
                 +(SELECT COUNT(id) FROM core_session_adjustment WHERE deleted=0 AND status IN ('active','completed')
                    AND (source_session_id=? OR target_session_id=?))
            """, Integer.class, current.id(), current.id(), current.id(), current.id());
        if (protectedFacts != null && protectedFacts > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "已有考勤、课消或补课预约的课次不能申请调整");
        }
        Instant startsAt = input.startsAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant endsAt = startsAt.plusSeconds(current.durationMinutes() * 60L);
        if (!startsAt.isAfter(Instant.now()) || startsAt.equals(current.startsAt())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "新时间必须位于未来且不同于原时间");
        }
        lockScheduleTeacher(current.teacherId());
        if (hasScheduleConflict(current, startsAt, endsAt)) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "老师或教室在该时段已有排课");
        }
        new AcademicSchedulePolicyRepository(jdbc,cipher).validateMove(current,startsAt,endsAt);
    }

    private void lockScheduleTeacher(long teacherId) {
        List<Long> teachers=jdbc.query("SELECT id FROM core_principal WHERE id=? AND active=1 AND deleted=0 FOR UPDATE",
            (rs,row)->rs.getLong(1),teacherId);
        if(teachers.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"教师已停用");
    }

    private void lockScheduleCampuses(long... campusIds) {
        for (long campusId : java.util.Arrays.stream(campusIds).distinct().sorted().toArray()) {
            List<Long> campuses=jdbc.query("SELECT id FROM core_campus WHERE id=? AND active=1 AND deleted=0 FOR UPDATE",
                (rs,row)->rs.getLong(1),campusId);
            if (campuses.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND,"校区已停用"); }
        }
    }

    private void lockSessionClass(Actor actor,long classId) {
        ScopeSql.Clause scope=ScopeSql.classes(actor);
        List<Object> args=new ArrayList<>(List.of(classId));args.addAll(scope.args());
        List<Long> rows=jdbc.query("""
            SELECT c.id FROM core_class c WHERE c.id=? AND c.active=1 AND c.deleted=0 AND (
            """+scope.sql()+") FOR UPDATE",(rs,row)->rs.getLong(1),args.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"班级不存在或不在授权范围");
    }

    private List<SessionScheduleRow> readScheduleRow(Actor actor,String sessionPublicId,boolean lock) {
        ScopeSql.Clause scope=ScopeSql.classes(actor);
        List<Object> args=new ArrayList<>(List.of(sessionPublicId));args.addAll(scope.args());
        return jdbc.query("""
            SELECT ss.id,ss.class_id,ss.public_id,c.public_id AS class_public_id,
                   COALESCE(ss.campus_id,c.campus_id) campus_id,c.campus_id class_campus_id,
                   COALESCE(ss.room,c.room) room,
                   ss.teacher_id,ss.starts_at,ss.ends_at,ss.status,ss.version,c.duration_minutes
            FROM core_session ss JOIN core_class c ON c.id=ss.class_id
            WHERE ss.public_id=? AND ss.deleted=0 AND c.deleted=0 AND c.active=1 AND (
            """+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,row)->new SessionScheduleRow(rs.getLong("id"),rs.getLong("class_id"),
                rs.getLong("campus_id"),rs.getLong("class_campus_id"),rs.getLong("teacher_id"),rs.getString("public_id"),
                rs.getString("class_public_id"),rs.getString("room"),rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),rs.getString("status"),rs.getLong("version"),
                rs.getInt("duration_minutes")),args.toArray());
    }

    private boolean hasScheduleConflict(SessionScheduleRow current,Instant startsAt,Instant endsAt) {
        Integer conflicts=jdbc.queryForObject("""
            SELECT COUNT(ss.id) FROM core_session ss JOIN core_class c ON c.id=ss.class_id
            WHERE ss.id<>? AND ss.deleted=0 AND c.deleted=0 AND c.active=1
              AND ss.status NOT IN ('cancelled','canceled') AND ss.starts_at<? AND ss.ends_at>?
              AND (ss.teacher_id=? OR (COALESCE(ss.campus_id,c.campus_id)=? AND COALESCE(ss.room,c.room)=?))
            """,Integer.class,current.id(),Timestamp.from(endsAt),Timestamp.from(startsAt),
            current.teacherId(),current.campusId(),current.room());
        return conflicts!=null&&conflicts>0;
    }

    /** Locks an authorized active enrollment and a started class session for attendance. */
    public AttendanceMember attendanceMember(Actor actor, String studentId, String classId,
            String sessionId) {
        // Serialize attendance with class edits and session changes using class-before-session lock order.
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> lockArgs = new ArrayList<>(List.of(sessionId));
        lockArgs.addAll(scope.args());
        List<Long> classIds = jdbc.query("""
            SELECT c.id FROM core_session ss JOIN core_class c ON c.id=ss.class_id
            WHERE ss.public_id=? AND ss.deleted=0 AND c.active=1 AND c.deleted=0 AND (
            """ + scope.sql() + ")", (rs,row)->rs.getLong(1), lockArgs.toArray());
        if (classIds.size()!=1) throw new BusinessException(ErrorCode.NOT_FOUND,"课次不存在或不在授权范围");
        List<Long> campusIds=jdbc.queryForList("SELECT c.campus_id FROM core_session ss JOIN core_class c "
            + "ON c.id=ss.class_id WHERE ss.public_id=? AND ss.deleted=0 UNION SELECT ss.campus_id "
            + "FROM core_session ss WHERE ss.public_id=? AND ss.deleted=0 AND ss.campus_id IS NOT NULL",
            Long.class, sessionId, sessionId);
        lockScheduleCampuses(campusIds.stream().mapToLong(Long::longValue).toArray());
        lockSessionClass(actor,classIds.get(0));
        List<Long> lockedSessions=jdbc.query("SELECT id FROM core_session WHERE public_id=? FOR UPDATE",
            (rs,row)->rs.getLong(1),sessionId);
        if(lockedSessions.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"课次已不存在");
        ScopeSql.Clause clause = scope;
        List<Object> args = new ArrayList<>(List.of(sessionId, studentId, classId));
        args.addAll(clause.args());
        String sessionTeacherScope = "";
        if (actor.scope().equals("assigned")) {
            sessionTeacherScope = " AND (ss.teacher_id = ? OR c.teacher_id = ?)";
            args.add(actor.id());
            args.add(actor.id());
        }
        List<AttendanceMember> members = jdbc.query("""
            SELECT s.id AS student_id,c.id AS class_id,ss.id AS session_id,e.id AS enrollment_id,
                   e.order_id,
                   c.public_id AS class_public_id,s.public_id AS student_public_id
            FROM core_student s JOIN core_session_enrollment e ON e.student_id = s.id
              AND e.status = 'active' AND e.deleted = 0
            JOIN core_class c ON c.id = e.class_id AND c.deleted = 0 AND c.active = 1
            JOIN core_session ss ON ss.class_id = c.id AND ss.public_id = ? AND ss.deleted = 0
              AND ss.starts_at <= UTC_TIMESTAMP(3) AND ss.status NOT IN ('cancelled','canceled')
            WHERE s.public_id = ? AND c.public_id = ? AND e.session_id=ss.id AND s.deleted = 0 AND (
            """ + clause.sql() + ")" + sessionTeacherScope + " LIMIT 1 FOR UPDATE",
            (rs, row) -> new AttendanceMember(rs.getLong("student_id"), rs.getLong("class_id"),
                rs.getLong("session_id"),rs.getLong("enrollment_id"),
                rs.getObject("order_id", Long.class),rs.getString("class_public_id"),
                rs.getString("student_public_id")),
            args.toArray());
        if (members.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "学生、班级或已开始课次不在授权范围");
        }
        return members.get(0);
    }
    /** Stores a first attendance fact and rejects silent edits to historical records. */
    public void appendAttendance(AttendanceMember member, Actor actor, AttendanceRecord record) {
        appendAttendance(member, actor, record, null);
    }
    /** Persists the Node-compatible explicit/default consumption choice with its attendance fact. */
    public void appendAttendance(AttendanceMember member, Actor actor, AttendanceRecord record, Boolean consume) {
        Integer existing = jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_attendance
            WHERE session_id = ? AND enrollment_id = ? AND deleted = 0
            """, Integer.class, member.sessionId(), member.enrollmentId());
        if (existing != null && existing > 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该学生本节课考勤已登记，请走更正流程");
        }
        try {
            jdbc.update("""
                INSERT INTO core_attendance
                (public_id, session_id, student_id, enrollment_id, actor_id, status, note_cipher,
                 created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id(), member.sessionId(), member.studentId(), member.enrollmentId(), actor.id(),
                record.status(), record.note() == null || record.note().isBlank() ? null : cipher.encrypt(record.note()),
                timestamp(record.createdAt()), timestamp(record.createdAt()));
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该学生本节课考勤已登记，请走更正流程");
        }
        boolean shouldConsume = consume == null ? shouldConsume(record.status()) : consume;
        if (shouldConsume && !consumeAllowed(record.status())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "当前考勤规则不允许扣课");
        }
        if (member.orderId() != null && shouldConsume) {
            appendConsumption(member, actor);
        }
    }

    private boolean shouldConsume(String status) {
        return switch (status) {
            case "attended", "late", "left_early" -> setting("presentConsume", true);
            case "leave" -> setting("leaveConsume", false);
            case "absent" -> setting("absentConsume", false);
            default -> false;
        };
    }

    private boolean setting(String name, boolean fallback) {
        List<String> encrypted = jdbc.queryForList("SELECT value_cipher FROM core_system_setting "
            + "WHERE section='attendance' AND field_name=? AND deleted=0", String.class, name);
        if (encrypted.isEmpty()) { return fallback; }
        if (encrypted.size() != 1) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "考勤设置重复"); }
        return Boolean.parseBoolean(cipher.decrypt(encrypted.get(0)));
    }

    private boolean consumeAllowed(String status) {
        return List.of("attended", "late", "left_early").contains(status)
            || status.equals("leave") && setting("leaveConsume", false)
            || status.equals("absent") && setting("absentConsume", false);
    }

    void appendConsumption(AttendanceMember member, Actor actor) {
        List<ConsumptionEntitlement> entitlements = jdbc.query("""
            SELECT el.id AS entitlement_id,el.unit_amount,ss.id AS session_id
            FROM core_enrollment_lesson el
            JOIN core_session_enrollment se ON se.entitlement_lesson_id=el.id AND se.session_id=?
            JOIN core_session ss ON ss.id=se.session_id AND ss.deleted=0
            WHERE el.enrollment_id=? AND el.order_id=? AND el.deleted=0
            """, (rs, row) -> new ConsumptionEntitlement(rs.getLong("entitlement_id"),
                rs.getBigDecimal("unit_amount"), rs.getLong("session_id")),
            member.sessionId(), member.enrollmentId(), member.orderId());
        if (entitlements.size() != 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "课次未匹配报名订单中的有效课时权益");
        }
        OrderBalance balance = jdbc.queryForObject("""
            SELECT received_amount,refunded_amount FROM core_crm_order WHERE id=? AND deleted=0 FOR UPDATE
            """, (rs, row) -> new OrderBalance(rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("refunded_amount")), member.orderId());
        if (balance == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "报名订单不存在"); }
        BigDecimal consumed = jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount),0) FROM core_consumption WHERE order_id=? AND deleted=0
            """, BigDecimal.class, member.orderId());
        BigDecimal reserved = jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount),0) FROM core_crm_refund_request
            WHERE order_id=? AND deleted=0 AND status IN ('pending_approval','approved')
            """, BigDecimal.class, member.orderId());
        BigDecimal amount = entitlements.get(0).amount();
        if (consumed == null || reserved == null || balance.received().subtract(balance.refunded())
                .subtract(consumed).subtract(reserved)
                .compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "订单可用课时金额不足，不能登记已出勤");
        }
        Long transferId=consumptionTransfer(member,entitlements.get(0).id());
        jdbc.update("""
            INSERT INTO core_consumption(public_id,attendance_id,session_id,student_id,enrollment_id,
                order_id,entitlement_lesson_id,actor_id,amount,transfer_id)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """, "CON_" + UUID.randomUUID(), attendanceId(member), member.sessionId(), member.studentId(),
            member.enrollmentId(), member.orderId(), entitlements.get(0).id(), actor.id(), amount,transferId);
        jdbc.update("""
            UPDATE core_session_adjustment SET status='completed',version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE enrollment_id=? AND order_id=? AND student_id=? AND target_session_id=?
              AND status='active' AND deleted=0
            """, member.enrollmentId(), member.orderId(), member.studentId(), member.sessionId());
    }

    private Long consumptionTransfer(AttendanceMember member,long entitlementId) {
        Long consumed=jdbc.queryForObject("SELECT COALESCE(SUM(units),0) FROM core_consumption WHERE entitlement_lesson_id=? AND deleted=0",
            Long.class,entitlementId);
        if(consumed!=null&&consumed>0)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"该课时权益已消费，不能重复登记");
        Long previous=jdbc.queryForObject("SELECT COUNT(*) FROM core_consumption WHERE entitlement_lesson_id=? AND deleted=0",
            Long.class,entitlementId);
        if(previous==null||previous==0)return null;
        var ids=jdbc.query("SELECT transfer_id FROM core_enrollment_placement WHERE enrollment_id=?",(rs,row)->rs.getLong(1),member.enrollmentId());
        if(ids.size()!=1)throw new BusinessException(ErrorCode.INVALID_INPUT,"历史课时权益须通过原考勤更正流程恢复");
        return ids.get(0);
    }
    private long attendanceId(AttendanceMember member) {
        Long id = jdbc.queryForObject("""
            SELECT id FROM core_attendance WHERE session_id=? AND enrollment_id=? AND deleted=0
            """, Long.class, member.sessionId(), member.enrollmentId());
        if (id == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "考勤记录不存在"); }
        return id;
    }
    /** Reads an authorized session roster with masked identities and any saved attendance. */
    public SessionRoster sessionRoster(Actor actor, String sessionPublicId) {
        ScopeSql.Clause classScope = ScopeSql.classes(actor);
        List<Object> sessionArgs = new ArrayList<>();
        sessionArgs.add(sessionPublicId);
        sessionArgs.addAll(classScope.args());
        String sessionTeacherScope = "";
        if (actor.scope().equals("assigned")) {
            sessionTeacherScope = " AND ss.teacher_id = ?";
            sessionArgs.add(actor.id());
        }
        List<SessionMeta> sessions = jdbc.query("""
            SELECT ss.id,ss.public_id,c.public_id AS class_public_id,ss.starts_at,ss.ends_at,ss.status,ss.version
            FROM core_session ss JOIN core_class c ON c.id = ss.class_id
            WHERE ss.public_id = ? AND ss.deleted = 0 AND c.deleted = 0 AND c.active = 1 AND (
            """ + classScope.sql() + ")" + sessionTeacherScope,
            (rs, row) -> new SessionMeta(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("class_public_id"), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(), rs.getString("status"), rs.getLong("version")), sessionArgs.toArray());
        if (sessions.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "课次不存在或不在授权范围"); }
        SessionMeta session = sessions.get(0);
        List<Object> rosterArgs = new ArrayList<>(List.of(session.id()));
        rosterArgs.addAll(classScope.args());
        String rosterTeacherScope = actor.scope().equals("assigned")
            ? " AND ss.teacher_id = ?" : "";
        if (actor.scope().equals("assigned")) {
            rosterArgs.add(actor.id());
        }
        List<SessionStudent> students = jdbc.query("""
            SELECT st.public_id,st.name_cipher,st.grade,st.phone_cipher,e.public_id enrollment_public_id,
                   e.version enrollment_version,
                   a.public_id AS attendance_public_id,a.status AS attendance_status,a.version AS attendance_version,
                   a.note_cipher,p.public_id AS attendance_actor,a.created_at AS attendance_at,
                   CASE WHEN e.order_id IS NULL THEN 'legacy_without_order'
                        WHEN el.id IS NOT NULL AND o.id IS NOT NULL THEN 'paid_entitlement'
                        ELSE 'no_valid_entitlement' END AS consumption_state,
                   CASE WHEN e.order_id IS NULL THEN 0.00
                        WHEN o.id IS NOT NULL THEN el.unit_amount ELSE NULL END AS consumption_amount
            FROM core_session ss JOIN core_class c ON c.id = ss.class_id
            JOIN core_session_enrollment e ON e.class_id = c.id AND e.session_id=ss.id AND e.deleted = 0
            JOIN core_student st ON st.id = e.student_id AND st.deleted = 0
            LEFT JOIN core_attendance a ON a.session_id = ss.id AND a.enrollment_id = e.id AND a.deleted = 0
            LEFT JOIN core_principal p ON p.id = a.actor_id
            LEFT JOIN core_enrollment_lesson el ON el.enrollment_id=e.id AND el.order_id=e.order_id
              AND el.id=e.entitlement_lesson_id AND el.deleted=0
            LEFT JOIN core_crm_order o ON o.id=e.order_id AND o.deleted=0
            WHERE ss.id = ? AND ss.deleted = 0 AND c.deleted = 0 AND c.active = 1 AND (
            """ + classScope.sql() + ")" + rosterTeacherScope + " ORDER BY st.id",
            (rs, row) -> new SessionStudent(rs.getString("public_id"),
                cipher.decrypt(rs.getString("name_cipher")), rs.getString("grade"),
                cipher.maskedPhone(rs.getString("phone_cipher")), rs.getString("attendance_public_id"),
                rs.getString("attendance_status"), rs.getString("note_cipher") == null ? null
                    : cipher.decrypt(rs.getString("note_cipher")), rs.getString("attendance_actor"),
                instant(rs.getTimestamp("attendance_at")), rs.getString("consumption_state"),
                decimalAmount(rs.getBigDecimal("consumption_amount")),
                rs.getObject("attendance_version",Long.class), rs.getString("enrollment_public_id"),
                rs.getObject("enrollment_version",Long.class)), rosterArgs.toArray());
        return new SessionRoster(session.publicId(), session.classPublicId(), session.startsAt(),
            session.endsAt(), session.status(), students, session.version());
    }
    private static String decimalAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
    /** Appends a communication whose content is encrypted at rest. */
    public void append(Member member, Actor actor, Communication record) {
        jdbc.update("""
            INSERT INTO core_communication
            (public_id, student_id, class_id, actor_id, channel, content_cipher, next_follow_at,
             created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, record.id(), member.studentId(), member.classId(), actor.id(), record.channel(),
            cipher.encrypt(record.content()), timestamp(record.nextFollowAt()),
            timestamp(record.createdAt()), timestamp(record.createdAt()));
    }
    /** Lists only records in the authorized class, with a bounded page size. */
    public List<Communication> communications(Actor actor, String studentId, String classId) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(List.of(studentId, classId));
        args.addAll(scope.args());
        return jdbc.query("""
            SELECT m.public_id, s.public_id AS student_public, c.public_id AS class_public,
                   p.public_id AS actor_public, m.channel, m.content_cipher,
                   m.next_follow_at, m.created_at
            FROM core_communication m JOIN core_student s ON s.id = m.student_id AND s.deleted = 0
            JOIN core_class c ON c.id = m.class_id AND c.deleted = 0
            JOIN core_principal p ON p.id = m.actor_id
            WHERE s.public_id = ? AND c.public_id = ? AND m.deleted = 0 AND (
            """ + scope.sql() + ") ORDER BY m.id DESC LIMIT 100",
            (rs, row) -> new Communication(rs.getString("public_id"), rs.getString("student_public"),
                rs.getString("class_public"), rs.getString("actor_public"), rs.getString("channel"),
                cipher.decrypt(rs.getString("content_cipher")), instant(rs.getTimestamp("next_follow_at")),
                instant(rs.getTimestamp("created_at"))), args.toArray());
    }
    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record ClassOccupancy(long id, long capacity, long students) { }
    private record ConsumptionTotal(long count, BigDecimal amount) { }
    private record SessionMeta(long id, String publicId, String classPublicId, Instant startsAt,
            Instant endsAt, String status, long version) { }
    private record EnrollmentClass(long id, int capacity, long campusId, long courseId) { }
    private record EnrollmentOrder(long id, long studentId, String studentPublicId, Long campusId,
            String subject, BigDecimal total, BigDecimal received, BigDecimal refunded, String status) { }
    private record EnrollmentLesson(long id, int sequence, String title) { }
    private record ConsumptionEntitlement(long id, BigDecimal amount, long sessionId) { }
    private record OrderBalance(BigDecimal received, BigDecimal refunded) { }
}
