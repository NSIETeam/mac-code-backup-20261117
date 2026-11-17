package cn.siyue.core;

import cn.siyue.core.AcademicDtos.ClassFees;
import cn.siyue.core.AcademicDtos.EnrollmentBatchItemReceipt;
import cn.siyue.core.AcademicDtos.EnrollmentBatchRow;
import cn.siyue.core.AcademicDtos.EnrollmentReceipt;
import cn.siyue.core.AcademicDtos.EnrollmentStudentDraft;
import cn.siyue.core.AcademicDtos.Student;
import cn.siyue.core.AcademicDtos.StudentInput;
import cn.siyue.core.CrmDtos.ClassOrderTerms;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists Node-compatible batch enrollment orders and their source attribution atomically. */
@Repository
public class AcademicBatchEnrollmentRepository {
    private record ClassSnapshot(long id, String publicId, String name, long version, long campusId,
            String campusPublicId, long courseId, String coursePublicId, String courseName,
            long courseVersion, String grade, String subject, BigDecimal price, String room,
            int capacity) { }
    private record OpportunityLink(long id, long customerId, Long campusId, long ownerId,
            String subject, String stage, Long studentId, Long sourceLeadId) { }
    private record StudentRow(long id, String publicId, String name, String grade, String phone, long version,
            Long campusId, Long customerId, Long originLeadId, Long ownerId, Long assigneeId) { }
    public record BatchDefaults(String couponId, String couponClaimToken, String referrerStudentId) { }
    private record CouponRequest(String couponId, String token, StudentRow student, ClassSnapshot klass,
            Long referrerId, List<Long> batchStudentIds) { }
    private record Resolved(EnrollmentBatchRow input, StudentRow student, OpportunityLink opportunity,
            Long referrerId, CouponClaimRepository.ClaimQuote coupon, List<Long> batchStudentIds) { }
    private record Lesson(long id, int sequence, String title) { }

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentProfileRepository studentProfiles;
    private final CouponClaimRepository couponClaims;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Uses the same database transaction for identity, quote, order, enrollment and coupon writes. */
    public AcademicBatchEnrollmentRepository(JdbcTemplate jdbc, PiiCipher cipher,
            StudentProfileRepository studentProfiles, CouponClaimRepository couponClaims) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.studentProfiles = studentProfiles;
        this.couponClaims = couponClaims;
    }

    /** Acquires every new identity mutex in deterministic order before any actor or row lock. */
    public void lockDraftIdentities(List<EnrollmentBatchRow> rows) {
        rows.stream().map(EnrollmentBatchRow::studentDraft).filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparing((EnrollmentStudentDraft draft) -> draft.name().trim())
                .thenComparing(draft -> draft.phone().trim()))
            .forEach(draft -> studentProfiles.lockCreateIdentity(studentInput(draft)));
    }

    /** Rechecks every referenced record before exposing a stored idempotent result. */
    public void authorize(Actor actor, String classId, List<EnrollmentBatchRow> rows, String referrerStudentId) {
        ClassSnapshot klass = visibleClass(actor, classId);
        for (EnrollmentBatchRow row : rows) {
            if (row.studentId() != null && !row.studentId().isBlank()) {
                visibleStudent(actor, row.studentId(), row.opportunityId() == null, false,
                    row.opportunityId() == null ? klass.campusId() : null);
            }
            if (row.opportunityId() != null && !row.opportunityId().isBlank()) { opportunity(actor, row.opportunityId(), false); }
            String referrer = first(row.referrerStudentId(), referrerStudentId);
            if (referrer != null) { resolveReferrer(actor, referrer); }
        }
        if (klass.id() <= 0) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级不存在或不在当前范围"); }
    }

    /** Creates pending orders, enrollments, referral facts and coupon redemptions in one transaction. */
    public List<EnrollmentBatchItemReceipt> create(Actor actor, String classPublicId,
            List<EnrollmentBatchRow> rows, BatchDefaults defaults) {
        ClassSnapshot klass = lockClass(actor, classPublicId);
        List<StudentRow> initialStudents = rows.stream().map(row -> row.studentId() == null ? null
            : visibleStudent(actor, row.studentId(), row.opportunityId() == null, false,
                row.opportunityId() == null ? klass.campusId() : null)).toList();
        List<Long> initialIds = initialStudents.stream().filter(java.util.Objects::nonNull)
            .map(StudentRow::id).toList();
        List<Long> referrerIds = rows.stream().map(row -> first(row.referrerStudentId(), defaults.referrerStudentId()))
            .filter(java.util.Objects::nonNull).distinct().sorted()
            .map(publicId -> resolveReferrer(actor, publicId)).toList();
        lockExistingStudents(concat(initialIds, referrerIds));
        lockOpportunities(actor, rows);
        List<StudentRow> students = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            EnrollmentBatchRow row = rows.get(index);
            StudentRow student = row.studentId() == null
                ? createDraft(actor, klass, row.studentDraft()) : initialStudents.get(index);
            students.add(student);
        }
        if (students.stream().map(StudentRow::id).distinct().count() != students.size()) {
            invalid(ErrorCode.INVALID_INPUT, "批量报名不能重复使用同一学生身份");
        }
        requireClassCapacity(klass, students);
        List<Long> batchStudentIds = students.stream().map(StudentRow::id).toList();
        List<Resolved> resolved = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            EnrollmentBatchRow row = rows.get(index);
            StudentRow student = students.get(index);
            String rowCouponId = first(row.couponId(), defaults.couponId());
            String rowClaim = first(row.couponClaimToken(), defaults.couponClaimToken());
            if (rowCouponId != null && rowClaim != null) {
                invalid(ErrorCode.INVALID_INPUT, "优惠券编号与兑换码不能同时使用");
            }
            OpportunityLink opportunity = row.opportunityId() == null ? null
                : opportunity(actor, row.opportunityId(), true);
            if (opportunity != null) {
                if (row.studentDraft() != null) {
                    linkDraftToOpportunityCustomer(actor, student, opportunity);
                    student = studentByPublicId(student.publicId(), true);
                    students.set(index, student);
                }
                validateOpportunityStudent(klass, student, opportunity);
            }
            Long referrer = resolveReferrer(actor, first(row.referrerStudentId(), defaults.referrerStudentId()));
            CouponClaimRepository.ClaimQuote quote = couponQuote(actor,
                new CouponRequest(rowCouponId, rowClaim, student, klass, referrer, batchStudentIds));
            resolved.add(new Resolved(row, student, opportunity, referrer, quote, batchStudentIds));
        }
        return resolved.stream().map(item -> createOne(actor, klass, item)).toList();
    }

    private ClassSnapshot lockClass(Actor actor, String publicId) {
        ClassSnapshot before = visibleClass(actor, publicId);
        List<Long> initialSessionCampuses = jdbc.query("SELECT DISTINCT COALESCE(s.campus_id,c.campus_id) "
            + "FROM core_class c LEFT JOIN core_session s ON s.class_id=c.id AND s.deleted=0 "
            + "WHERE c.id=? ORDER BY COALESCE(s.campus_id,c.campus_id)", (rs, row) -> rs.getLong(1), before.id());
        List<Long> campusLocks = new ArrayList<>();
        campusLocks.add(before.campusId());
        campusLocks.addAll(initialSessionCampuses);
        campusLocks.stream().distinct().sorted().forEach(campusId -> jdbc.queryForObject(
            "SELECT id FROM core_campus WHERE id=? AND deleted=0 FOR UPDATE", Long.class, campusId));
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<ClassSnapshot> locked = jdbc.query(classSelect() + " WHERE c.id=? AND c.deleted=0 AND c.active=1 "
            + "AND co.deleted=0 AND co.active=1 AND (" + scope.sql() + ") FOR UPDATE",
            (rs, row) -> mapClass(rs), concat(before.id(), scope.args()).toArray());
        if (locked.isEmpty() || locked.get(0).campusId() != before.campusId()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "班级校区已变化，请重试");
        }
        ClassSnapshot klass = locked.get(0);
        jdbc.query("SELECT id FROM core_session WHERE class_id=? AND deleted=0 ORDER BY id FOR UPDATE",
            (rs, row) -> rs.getLong(1), klass.id());
        List<Long> lockedSessionCampuses = jdbc.query("SELECT DISTINCT COALESCE(s.campus_id,c.campus_id) "
            + "FROM core_class c LEFT JOIN core_session s ON s.class_id=c.id AND s.deleted=0 "
            + "WHERE c.id=? ORDER BY COALESCE(s.campus_id,c.campus_id)", (rs, row) -> rs.getLong(1), klass.id());
        if (!initialSessionCampuses.equals(lockedSessionCampuses)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "课次校区已变化，请重试");
        }
        new CampusResourceRules(jdbc).validate(klass.campusId(),
            new CampusResourceRules.Request(klass.room(), klass.capacity(), klass.subject(), klass.grade()));
        return klass;
    }

    private ClassSnapshot visibleClass(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<ClassSnapshot> rows = jdbc.query(classSelect() + " WHERE c.public_id=? AND c.deleted=0 AND c.active=1 "
            + "AND co.deleted=0 AND co.active=1 AND (" + scope.sql() + ")",
            (rs, row) -> mapClass(rs), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级不存在或不在当前范围"); }
        return rows.get(0);
    }

    private String classSelect() {
        return "SELECT c.id,c.public_id,c.name,c.version,c.campus_id,cp.public_id campus_public_id,c.course_id,"
            + "co.public_id course_public_id,co.name course_name,co.version course_version,co.grade,co.subject,"
            + "co.price,c.room,c.capacity FROM core_class c JOIN core_campus cp ON cp.id=c.campus_id "
            + "JOIN core_course co ON co.id=c.course_id ";
    }

    private ClassSnapshot mapClass(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClassSnapshot(rs.getLong("id"), rs.getString("public_id"), rs.getString("name"),
            rs.getLong("version"), rs.getLong("campus_id"), rs.getString("campus_public_id"),
            rs.getLong("course_id"), rs.getString("course_public_id"), rs.getString("course_name"),
            rs.getLong("course_version"), rs.getString("grade"), rs.getString("subject"),
            rs.getBigDecimal("price"), rs.getString("room"), rs.getInt("capacity"));
    }

    private StudentRow createDraft(Actor actor, ClassSnapshot klass, EnrollmentStudentDraft draft) {
        if (draft == null) { invalid(ErrorCode.INVALID_INPUT, "每条报名行必须且只能选择已有学生或填写新生资料"); }
        String campus = first(draft.campusId(), klass.campusPublicId());
        String grade = first(draft.grade(), klass.grade());
        Student student = studentProfiles.createStudent(actor,
            new StudentInput(draft.name(), grade, draft.phone(), campus, draft.originLeadId()));
        return studentByPublicId(student.id(), true);
    }

    private StudentRow visibleStudent(Actor actor, String publicId, boolean writeScope, boolean lock,
            Long fallbackCampusId) {
        ScopeSql.Clause scope = writeScope ? ScopeSql.studentWrite(actor) : ScopeSql.studentReads(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        String scopeSql = scope.sql();
        if (writeScope && "campus".equals(actor.scope()) && fallbackCampusId != null) {
            scopeSql = "(" + scopeSql + " OR (s.campus_id IS NULL AND ? IN "
                + "(SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)))";
            args.add(fallbackCampusId);
            args.add(actor.id());
        }
        List<StudentRow> rows = jdbc.query("SELECT s.id,s.public_id,s.name_cipher,s.grade,s.phone_cipher,s.version,s.campus_id,"
            + "s.customer_id,s.origin_lead_id,s.owner_id,s.assignee_id FROM core_student s WHERE s.public_id=? "
            + "AND s.deleted=0 AND " + scopeSql + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> student(rs), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生不存在或不在当前范围"); }
        return rows.get(0);
    }

    private StudentRow studentByPublicId(String publicId, boolean lock) {
        List<StudentRow> rows = jdbc.query("SELECT s.id,s.public_id,s.name_cipher,s.grade,s.phone_cipher,s.version,s.campus_id,"
            + "s.customer_id,s.origin_lead_id,s.owner_id,s.assignee_id FROM core_student s WHERE s.public_id=? "
            + "AND s.deleted=0" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> student(rs), publicId);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "新建学生档案不存在"); }
        return rows.get(0);
    }

    private StudentRow student(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StudentRow(rs.getLong("id"), rs.getString("public_id"),
            cipher.decrypt(rs.getString("name_cipher")), rs.getString("grade"),
            cipher.decrypt(rs.getString("phone_cipher")), rs.getLong("version"), nullableLong(rs, "campus_id"),
            nullableLong(rs, "customer_id"), nullableLong(rs, "origin_lead_id"),
            nullableLong(rs, "owner_id"), nullableLong(rs, "assignee_id"));
    }

    private void lockExistingStudents(List<Long> ids) {
        ids.stream().distinct().sorted().forEach(id -> jdbc.queryForObject(
            "SELECT id FROM core_student WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id));
    }

    private List<Long> concat(List<Long> first, List<Long> second) {
        List<Long> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private void lockOpportunities(Actor actor, List<EnrollmentBatchRow> rows) {
        rows.stream().map(EnrollmentBatchRow::opportunityId).filter(java.util.Objects::nonNull).distinct().sorted()
            .forEach(publicId -> opportunity(actor, publicId, true));
    }

    private OpportunityLink opportunity(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = opportunityScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<OpportunityLink> rows = jdbc.query("SELECT o.id,o.customer_id,o.campus_id,o.owner_id,o.subject,o.stage,"
            + "o.student_id,o.source_lead_id FROM core_opportunity o WHERE o.public_id=? AND o.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> new OpportunityLink(rs.getLong("id"),
                rs.getLong("customer_id"), nullableLong(rs, "campus_id"), rs.getLong("owner_id"),
                rs.getString("subject"), rs.getString("stage"), nullableLong(rs, "student_id"),
                nullableLong(rs, "source_lead_id")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "商机不存在或不在当前范围"); }
        return rows.get(0);
    }

    private ScopeSql.Clause opportunityScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT campus_id FROM core_principal_campus "
                + "WHERE principal_id=? AND deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("o.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }

    private void validateOpportunityStudent(ClassSnapshot klass, StudentRow student, OpportunityLink opportunity) {
        if ("closed".equals(opportunity.stage())) { invalid(ErrorCode.VERSION_CONFLICT, "商机已关闭"); }
        if (opportunity.campusId() != null && opportunity.campusId() != klass.campusId()) {
            invalid(ErrorCode.INVALID_INPUT, "班级校区与商机不一致");
        }
        if (opportunity.subject() != null && !opportunity.subject().equals(klass.subject())) {
            invalid(ErrorCode.INVALID_INPUT, "报名课程与商机意向学科不一致");
        }
        boolean linked = student.customerId() != null && student.customerId() == opportunity.customerId()
            || customerStudentLink(opportunity.customerId(), student.id())
            || opportunity.sourceLeadId() != null && opportunity.sourceLeadId().equals(student.originLeadId())
            || opportunity.studentId() != null && opportunity.studentId() == student.id();
        if (!linked) { invalid(ErrorCode.INVALID_INPUT, "学生与商机客户没有明确关联"); }
    }

    private boolean customerStudentLink(long customerId, long studentId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(id) FROM core_customer_student WHERE customer_id=? "
            + "AND student_id=? AND deleted=0", Integer.class, customerId, studentId);
        return count != null && count > 0;
    }

    private void linkDraftToOpportunityCustomer(Actor actor, StudentRow student, OpportunityLink opportunity) {
        boolean linked = student.customerId() != null && student.customerId() == opportunity.customerId()
            || customerStudentLink(opportunity.customerId(), student.id())
            || opportunity.sourceLeadId() != null && opportunity.sourceLeadId().equals(student.originLeadId())
            || opportunity.studentId() != null && opportunity.studentId() == student.id();
        if (linked) { return; }
        Integer existing = jdbc.queryForObject("SELECT (SELECT COUNT(id) FROM core_customer_student WHERE "
            + "customer_id=? AND deleted=0)+(SELECT COUNT(id) FROM core_student WHERE customer_id=? AND deleted=0)",
            Integer.class, opportunity.customerId(), opportunity.customerId());
        if (existing != null && existing > 0 || opportunity.studentId() != null) {
            invalid(ErrorCode.ALREADY_EXISTS, "商机客户已关联学籍，请选择已关联学生");
        }
        requireCustomerName(actor, opportunity.customerId(), student.name());
        requireCustomerPhone(opportunity.customerId(), student.phone());
        int updated = jdbc.update("UPDATE core_student SET customer_id=?,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE id=? AND customer_id IS NULL", opportunity.customerId(), student.id());
        if (updated != 1) { invalid(ErrorCode.ALREADY_EXISTS, "学生档案已关联其他客户"); }
        jdbc.update("INSERT INTO core_customer_student(public_id,customer_id,student_id,relationship) "
            + "VALUES(?,?,?,'unspecified')", "CUSSTU_" + UUID.randomUUID(), opportunity.customerId(), student.id());
    }

    private void requireCustomerName(Actor actor, long customerId, String studentName) {
        ScopeSql.Clause scope = ScopeSql.customerReads(actor);
        List<Object> args = new ArrayList<>(List.of(customerId));
        args.addAll(scope.args());
        List<String> names = jdbc.query("SELECT c.display_name_cipher FROM core_customer c WHERE c.id=? "
            + "AND c.deleted=0 AND (" + scope.sql() + ")", (rs, row) -> cipher.decrypt(rs.getString(1)),
            args.toArray());
        if (names.isEmpty() || !normalizeName(names.get(0)).equals(normalizeName(studentName))) {
            invalid(ErrorCode.INVALID_INPUT, "新生姓名与选定商机客户不一致");
        }
    }

    private String normalizeName(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
    }

    private void requireCustomerPhone(long customerId, String phone) {
        Map<String, byte[]> indexes = cipher.blindIndexes("phone", phone);
        String predicates = String.join(" OR ", indexes.keySet().stream()
            .map(key -> "(ci.key_id=? AND ci.value_hash=?)").toList());
        List<Object> args = new ArrayList<>(List.of(customerId));
        indexes.forEach((key, hash) -> { args.add(key); args.add(hash); });
        Integer matches = jdbc.queryForObject("SELECT COUNT(cc.id) FROM core_customer_contact cc "
            + "JOIN core_customer_contact_index ci ON ci.contact_id=cc.id WHERE cc.customer_id=? "
            + "AND cc.channel='phone' AND cc.deleted=0 AND ci.deleted=0 AND (" + predicates + ")",
            Integer.class, args.toArray());
        if (matches == null || matches == 0) {
            invalid(ErrorCode.INVALID_INPUT, "新生手机号与商机客户登记联系方式不一致");
        }
    }

    private Long resolveReferrer(Actor actor, String publicId) {
        if (publicId == null) { return null; }
        ScopeSql.Clause scope = ScopeSql.studentReads(actor);
        List<Object> args = new ArrayList<>(List.of(publicId));
        args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT s.id FROM core_student s WHERE s.public_id=? AND s.deleted=0 AND "
            + scope.sql(), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "推荐学生不存在或不在当前范围"); }
        return ids.get(0);
    }

    private CouponClaimRepository.ClaimQuote couponQuote(Actor actor, CouponRequest request) {
        if (request.couponId() == null && request.token() == null) {
            BigDecimal price = request.klass().price();
            return new CouponClaimRepository.ClaimQuote(0, 0, price, BigDecimal.ZERO, price);
        }
        if (request.couponId() != null) {
            return couponClaims.quoteDirect(actor, request.couponId(), request.student().id(), request.klass().id(),
                new CouponClaimRepository.DirectContext(request.referrerId(), request.batchStudentIds()));
        }
        return couponClaims.quote(actor, request.token(), request.student().id(), request.klass().id());
    }

    private void requireClassCapacity(ClassSnapshot klass, List<StudentRow> students) {
        SeatAvailabilityRepository seats = new SeatAvailabilityRepository(jdbc, cipher);
        if (seats.remaining(klass.id()) < students.size()) {
            invalid(ErrorCode.ALREADY_EXISTS, "班级或未来课次容量不足");
        }
        for (StudentRow student : students) {
            if (student.grade() != null && !student.grade().isBlank()
                    && !sameGrade(student.grade(), klass.grade())) {
                invalid(ErrorCode.INVALID_INPUT, "学生年级不符合课程年级");
            }
            requireNoEnrollment(student.id(), klass.id());
            requireNoScheduleConflict(student.id(), klass.id());
        }
    }

    private void requireNoEnrollment(long studentId, long classId) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(id) FROM core_effective_enrollment WHERE student_id=? "
            + "AND class_id=? AND status IN ('active','pending') AND deleted=0 FOR UPDATE",
            Integer.class, studentId, classId);
        if (existing != null && existing > 0) { invalid(ErrorCode.ALREADY_EXISTS, "已存在该班级有效报名"); }
    }

    private void requireNoScheduleConflict(long studentId, long classId) {
        Integer conflicts = jdbc.queryForObject("SELECT COUNT(DISTINCT target.id) FROM core_session target "
            + "JOIN core_session existing ON existing.class_id<>target.class_id AND existing.starts_at<target.ends_at "
            + "AND existing.ends_at>target.starts_at AND existing.deleted=0 AND existing.status='scheduled' "
            + "JOIN core_effective_enrollment e ON e.class_id=existing.class_id AND e.student_id=? "
            + "AND e.status IN ('active','pending') AND e.deleted=0 WHERE target.class_id=? "
            + "AND target.deleted=0 AND target.status='scheduled' AND target.starts_at>=UTC_TIMESTAMP(3)",
            Integer.class, studentId, classId);
        if (conflicts != null && conflicts > 0) { invalid(ErrorCode.ALREADY_EXISTS, "学生存在未来课次时间冲突"); }
    }

    private boolean sameGrade(String left, String right) {
        return AcademicGradeNormalizer.normalize(left).equals(AcademicGradeNormalizer.normalize(right));
    }

    private EnrollmentBatchItemReceipt createOne(Actor actor, ClassSnapshot klass, Resolved item) {
        StudentRow student = item.student();
        OpportunityLink opportunity = item.opportunity();
        CouponClaimRepository.ClaimQuote quote = item.coupon();
        if (quote.base().compareTo(klass.price()) != 0 || quote.amount().signum() < 0
                || quote.discount().signum() < 0 || quote.base().subtract(quote.discount()).compareTo(quote.amount()) != 0) {
            invalid(ErrorCode.PREVIEW_STALE, "优惠金额与当前课程价格不匹配");
        }
        String orderPublicId = "ORD_" + UUID.randomUUID();
        long attributedOwner = opportunity == null ? requireOwner(student) : opportunity.ownerId();
        jdbc.update("INSERT INTO core_crm_order(public_id,opportunity_id,customer_id,student_id,referrer_student_id,"
            + "campus_id,current_campus_id,attributed_owner_id,product_snapshot,total_amount,received_amount,"
            + "refunded_amount,status,refund_status,source_kind,commerce_checkout_id,subject_snapshot) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,0,0,'pending','none',?,NULL,?)", orderPublicId,
            opportunity == null ? null : opportunity.id(), opportunity == null ? null : opportunity.customerId(),
            student.id(), item.referrerId(), klass.campusId(), klass.campusId(), attributedOwner,
            klass.courseName(), quote.amount(), opportunity == null ? "direct_enrollment" : "opportunity",
            klass.subject());
        long orderId = internalId("SELECT id FROM core_crm_order WHERE public_id=?", orderPublicId);
        saveTerms(orderId, klass, quote.amount());
        String enrollmentPublicId = "ENR_" + UUID.randomUUID();
        jdbc.update("INSERT INTO core_enrollment(public_id,student_id,class_id,order_id,status,activated_at) "
            + "VALUES(?,?,?,?,'pending',NULL)", enrollmentPublicId, student.id(), klass.id(), orderId);
        long enrollmentId = internalId("SELECT id FROM core_enrollment WHERE public_id=?", enrollmentPublicId);
        saveLessons(enrollmentId, orderId, klass.courseId(), quote.amount());
        if (item.coupon() != null && item.coupon().promotionId() > 0) {
            if (item.coupon().claimId() > 0) { couponClaims.redeem(actor, item.coupon().claimId(), orderPublicId); }
            else { couponClaims.redeemDirect(actor, item.coupon().promotionId(), orderPublicId,
                new CouponClaimRepository.DirectContext(item.referrerId(), item.batchStudentIds())); }
        }
        return new EnrollmentBatchItemReceipt(studentDto(student),
            new EnrollmentReceipt(enrollmentPublicId, student.publicId(), klass.publicId(), "pending", null),
            orderPublicId, quote.amount());
    }

    private long requireOwner(StudentRow student) {
        if (student.ownerId() == null) { throw new BusinessException(ErrorCode.INVALID_INPUT, "学生档案缺少订单归属人"); }
        return student.ownerId();
    }

    private Student studentDto(StudentRow student) {
        return new Student(student.publicId(), student.name(), student.grade(), maskedPhone(student.phone()),
            student.version(), publicId("core_campus", student.campusId()), publicId("core_crm_lead", student.originLeadId()),
            publicId("core_customer", student.customerId()), publicId("core_principal", student.ownerId()),
            publicId("core_principal", student.assigneeId()));
    }

    private String maskedPhone(String phone) {
        return phone == null || phone.isBlank() ? "" : phone.length() < 7 ? "****"
            : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private void saveTerms(long orderId, ClassSnapshot klass, BigDecimal amount) {
        ClassFees fees = new ClassFees(amount, BigDecimal.ZERO, "unused_lessons", null, "after_enrollment");
        new CrmClassOrderRepository(jdbc).save(orderId,
            new ClassOrderTerms(klass.publicId(), klass.name(), klass.coursePublicId(), klass.version(), fees));
    }

    private void saveLessons(long enrollmentId, long orderId, long courseId, BigDecimal amount) {
        List<Lesson> lessons = jdbc.query("SELECT id,sequence_no,title FROM core_lesson WHERE course_id=? "
            + "AND deleted=0 ORDER BY sequence_no,id", (rs, row) -> new Lesson(rs.getLong("id"),
                rs.getInt("sequence_no"), rs.getString("title")), courseId);
        if (lessons.isEmpty()) { invalid(ErrorCode.INVALID_INPUT, "课程没有可关联的教学讲次"); }
        long cents = amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        long base = cents / lessons.size(), residual = cents % lessons.size();
        for (int index = 0; index < lessons.size(); index++) {
            Lesson lesson = lessons.get(index);
            BigDecimal unit = BigDecimal.valueOf(base + (index < residual ? 1 : 0), 2);
            jdbc.update("INSERT INTO core_enrollment_lesson(public_id,enrollment_id,order_id,source_lesson_id,"
                + "sequence_no,title_snapshot,unit_amount) VALUES(?,?,?,?,?,?,?)", "ENRL_" + UUID.randomUUID(),
                enrollmentId, orderId, lesson.id(), lesson.sequence(), lesson.title(), unit);
        }
    }

    private long internalId(String sql, String publicId) {
        Long id = jdbc.queryForObject(sql, Long.class, publicId);
        if (id == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报名订单写入失败"); }
        return id;
    }

    private String publicId(String table, Long id) {
        if (id == null) { return null; }
        List<String> values = jdbc.query("SELECT public_id FROM " + table + " WHERE id=?", (rs, row) -> rs.getString(1), id);
        return values.isEmpty() ? null : values.get(0);
    }

    private StudentInput studentInput(EnrollmentStudentDraft draft) {
        return new StudentInput(draft.name(), draft.grade(), draft.phone(), draft.campusId(), draft.originLeadId());
    }

    private String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private Long nullableLong(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        return rs.getObject(name, Long.class);
    }

    private List<Object> concat(long first, List<Object> tail) {
        List<Object> values = new ArrayList<>(List.of(first));
        values.addAll(tail);
        return values;
    }

    private void invalid(ErrorCode code, String message) { throw new BusinessException(code, message); }
}
