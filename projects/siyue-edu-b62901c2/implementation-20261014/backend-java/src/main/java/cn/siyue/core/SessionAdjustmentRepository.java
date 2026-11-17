package cn.siyue.core;

import cn.siyue.core.SessionAdjustmentDtos.AssignInput;
import cn.siyue.core.SessionAdjustmentDtos.CancelInput;
import cn.siyue.core.SessionAdjustmentDtos.Receipt;
import cn.siyue.core.SessionAdjustmentDtos.WaiveInput;
import cn.siyue.core.SessionAdjustmentDtos.Query;
import cn.siyue.core.SessionAdjustmentDtos.Item;
import cn.siyue.core.SessionAdjustmentDtos.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Applies fixed, versioned makeup and waiver mutations against the V54 evidence model. */
@Repository
public class SessionAdjustmentRepository {
    private record Enrollment(long id, String publicId, long studentId, String studentPublicId, long orderId,
            long classId, String status, long version, Long ownerId, Long assigneeId) { }
    private record ClassRow(long id, String publicId, long campusId, long courseId, String grade, String subject,
            int capacity, String room, boolean active) { }
    private record SessionRow(long id, String publicId, long classId, long lessonId, int sequence,
            long campusId, String room, Instant start, Instant end, String status, long version) { }
    private record Initial(Enrollment enrollment, ClassRow sourceClass, SessionRow source, ClassRow targetClass,
            SessionRow target, long entitlementLessonId, long orderId) { }
    private record Adjustment(long id, String publicId, String kind, String status, long enrollmentId,
            String enrollmentPublicId, long studentId, long sourceSessionId, String sourceSessionPublicId,
            Long targetSessionId, String targetSessionPublicId, long version, Instant targetStartsAt) { }
    private record ReceiptData(String id, String kind, String status, String enrollmentId,
            String sourceId, String targetId, long version, Instant occurredAt) { }

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final SeatAvailabilityRepository seats;

    public SessionAdjustmentRepository(JdbcTemplate jdbc, PiiCipher cipher, SeatAvailabilityRepository seats) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.seats = seats;
    }

    Page list(Actor actor, Query query) {
        int page = query.page() == null ? 1 : query.page();
        int size = query.size() == null ? 20 : query.size();
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> filters = new ArrayList<>();
        String filter = "";
        if (query.kind() != null) { filter += " AND a.kind=?"; filters.add(query.kind()); }
        if (query.status() != null && !"all".equals(query.status())) {
            filter += " AND a.status=?"; filters.add(query.status());
        }
        String from = " FROM core_session_adjustment a JOIN core_enrollment e ON e.id=a.enrollment_id "
            + "JOIN core_student st ON st.id=a.student_id JOIN core_class c "
            + "ON c.id=COALESCE(a.target_class_id,a.source_class_id) "
            + "JOIN core_class source_class ON source_class.id=a.source_class_id "
            + "JOIN core_session source_session ON source_session.id=a.source_session_id "
            + "LEFT JOIN core_class target_class ON target_class.id=a.target_class_id "
            + "LEFT JOIN core_session target_session ON target_session.id=a.target_session_id "
            + "WHERE a.deleted=0 AND e.deleted=0 AND st.deleted=0 AND (" + scope.sql() + ")" + filter;
        List<Object> args = new ArrayList<>(scope.args()); args.addAll(filters);
        Long total = jdbc.queryForObject("SELECT COUNT(a.id)" + from, Long.class, args.toArray());
        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(size); rowArgs.add(Math.multiplyExact((long) page - 1, size));
        List<Item> items = jdbc.query("SELECT a.public_id,a.kind,a.status,st.public_id student_id,st.name_cipher,"
            + "e.public_id enrollment_id,source_class.public_id source_class_id,source_session.public_id source_session_id,"
            + "source_session.starts_at source_starts_at,target_class.public_id target_class_id,"
            + "target_session.public_id target_session_id,target_session.starts_at target_starts_at,a.version,a.created_at"
            + from + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?", (rs, row) -> new Item(
                rs.getString("public_id"), rs.getString("kind"), rs.getString("status"), rs.getString("student_id"),
                cipher.decrypt(rs.getString("name_cipher")), rs.getString("enrollment_id"),
                rs.getString("source_class_id"), rs.getString("source_session_id"),
                instant(rs.getTimestamp("source_starts_at")), rs.getString("target_class_id"),
                rs.getString("target_session_id"), instant(rs.getTimestamp("target_starts_at")),
                rs.getLong("version"), instant(rs.getTimestamp("created_at"))), rowArgs.toArray());
        return new Page(List.copyOf(items), total == null ? 0 : total, page, size);
    }

    void authorizeAssign(Actor actor, AssignInput input) {
        enrollment(actor, input.enrollmentId());
        session(actor, input.sourceSessionId());
        classRow(actor, input.targetClassId());
        session(actor, input.targetSessionId());
    }

    Receipt assign(Actor actor, AssignInput input) {
        Initial rows = lockInitial(actor, input.enrollmentId(), input.sourceSessionId(),
            input.targetClassId(), input.targetSessionId());
        validateVersions(rows, input.enrollmentVersion(), input.sourceSessionVersion(), input.targetSessionVersion());
        validateAssignment(rows, input.kind());
        String id = "SADJ_" + UUID.randomUUID();
        Instant now = now();
        jdbc.update("""
            INSERT INTO core_session_adjustment(public_id,kind,status,enrollment_id,order_id,student_id,
              source_class_id,source_session_id,lesson_id,target_class_id,target_session_id,campus_id,
              owner_id,assignee_id,created_by_id,reason_cipher)
            VALUES(?,?,'active',?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, input.kind(), rows.enrollment().id(), rows.orderId(), rows.enrollment().studentId(),
            rows.sourceClass().id(), rows.source().id(), rows.entitlementLessonId(), rows.targetClass().id(),
            rows.target().id(), rows.target().campusId(), rows.enrollment().ownerId(),
            rows.enrollment().assigneeId(), actor.id(), cipher.encrypt(input.reason().trim()));
        return receipt(new ReceiptData(id, input.kind(), "active", rows.enrollment().publicId(),
            rows.source().publicId(), rows.target().publicId(), 1, now));
    }

    void authorizeWaive(Actor actor, WaiveInput input) {
        enrollment(actor, input.enrollmentId());
        session(actor, input.sourceSessionId());
    }

    Receipt waive(Actor actor, WaiveInput input) {
        Initial rows = lockInitial(actor, input.enrollmentId(), input.sourceSessionId(), null, null);
        validateVersions(rows, input.enrollmentVersion(), input.sourceSessionVersion(), null);
        requireActive(rows.enrollment());
        requireMakeupSource(rows);
        requireNoPrior(rows);
        String id = "SADJ_" + UUID.randomUUID();
        Instant now = now();
        jdbc.update("""
            INSERT INTO core_session_adjustment(public_id,kind,status,enrollment_id,order_id,student_id,
              source_class_id,source_session_id,lesson_id,campus_id,owner_id,assignee_id,created_by_id,reason_cipher)
            VALUES(?,'waiver','waived',?,?,?,?,?,?,?,?,?,?,?)
            """, id, rows.enrollment().id(), rows.orderId(), rows.enrollment().studentId(),
            rows.sourceClass().id(), rows.source().id(), rows.entitlementLessonId(), rows.source().campusId(),
            rows.enrollment().ownerId(), rows.enrollment().assigneeId(), actor.id(), cipher.encrypt(input.reason().trim()));
        return receipt(new ReceiptData(id, "waiver", "waived", rows.enrollment().publicId(),
            rows.source().publicId(), null, 1, now));
    }

    void authorizeCancel(Actor actor, String adjustmentId) { adjustment(actor, adjustmentId); }

    Receipt cancel(Actor actor, String adjustmentId, CancelInput input) {
        Adjustment row = adjustment(actor, adjustmentId);
        lockTimetable(row.sourceSessionId(), row.targetSessionId());
        List<Adjustment> locked = jdbc.query(adjustmentSql(actor) + " FOR UPDATE", this::readAdjustment, adjustmentArgs(actor, adjustmentId));
        if (locked.size() != 1) { throw notFound(); }
        row = locked.get(0);
        if (row.version() != input.expectedVersion() || !"active".equals(row.status())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "预约已更新、完成或取消");
        }
        if (row.targetSessionId() == null || row.targetStartsAt() == null || row.targetStartsAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "目标课次已开始或预约不存在");
        }
        Integer attendance = jdbc.queryForObject("SELECT COUNT(id) FROM core_attendance WHERE session_id=? "
            + "AND student_id=? AND deleted=0", Integer.class, row.targetSessionId(), row.studentId());
        if (attendance != null && attendance > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "已登记考勤的预约不能取消");
        }
        Instant now = now();
        int changed = jdbc.update("UPDATE core_session_adjustment SET status='cancelled',cancelled_at=?,"
            + "cancelled_by_id=?,cancel_reason_cipher=?,version=version+1,updated_at=? "
            + "WHERE id=? AND status='active' AND version=? AND deleted=0", Timestamp.from(now), actor.id(),
            cipher.encrypt(input.reason().trim()), Timestamp.from(now), row.id(), row.version());
        if (changed != 1) { throw new BusinessException(ErrorCode.VERSION_CONFLICT, "预约已被其他操作更新"); }
        jdbc.update("INSERT INTO core_session_adjustment_event(public_id,adjustment_id,actor_id,event_type,"
            + "from_status,to_status,reason_cipher,occurred_at) VALUES(?, ?, ?, 'cancelled','active','cancelled',?,?)",
            "SADJE_" + UUID.randomUUID(), row.id(), actor.id(), cipher.encrypt(input.reason().trim()), Timestamp.from(now));
        return receipt(new ReceiptData(row.publicId(), row.kind(), "cancelled", row.enrollmentPublicId(),
            row.sourceSessionPublicId(), row.targetSessionPublicId(), row.version() + 1, now));
    }

    private Initial lockInitial(Actor actor, String enrollmentId, String sourceSessionId,
            String targetClassId, String targetSessionId) {
        Enrollment observed = enrollment(actor, enrollmentId);
        SessionRow sourceObserved = session(actor, sourceSessionId);
        ClassRow sourceClassObserved = classById(actor, sourceObserved.classId());
        ClassRow targetClassObserved = targetClassId == null ? null : classRow(actor, targetClassId);
        SessionRow targetObserved = targetSessionId == null ? null : session(actor, targetSessionId);
        List<Long> campusIds = new ArrayList<>(List.of(sourceObserved.campusId(), sourceClassObserved.campusId()));
        if (targetObserved != null) {
            campusIds.add(targetObserved.campusId()); campusIds.add(targetClassObserved.campusId());
        }
        campusIds.stream().distinct().sorted().forEach(this::lockCampus);
        List<Long> classIds = new ArrayList<>(List.of(sourceClassObserved.id()));
        if (targetClassObserved != null) { classIds.add(targetClassObserved.id()); }
        classIds.stream().distinct().sorted().forEach(id -> jdbc.queryForObject(
            "SELECT id FROM core_class WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id));
        List<Long> sessionIds = new ArrayList<>(List.of(sourceObserved.id()));
        if (targetObserved != null) { sessionIds.add(targetObserved.id()); }
        sessionIds.stream().distinct().sorted().forEach(id -> jdbc.queryForObject(
            "SELECT id FROM core_session WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id));
        jdbc.queryForObject("SELECT id FROM core_enrollment WHERE id=? AND deleted=0 FOR UPDATE", Long.class, observed.id());
        jdbc.queryForObject("SELECT id FROM core_student WHERE id=? AND deleted=0 FOR UPDATE", Long.class, observed.studentId());
        jdbc.queryForObject("SELECT id FROM core_crm_order WHERE id=? AND deleted=0 FOR UPDATE", Long.class, observed.orderId());
        Enrollment current = enrollment(actor, enrollmentId);
        SessionRow source = session(actor, sourceSessionId);
        ClassRow sourceClass = classById(actor, source.classId());
        ClassRow targetClass = targetClassId == null ? null : classRow(actor, targetClassId);
        SessionRow target = targetSessionId == null ? null : session(actor, targetSessionId);
        if (!campusIds.contains(sourceClass.campusId())
                || targetClass != null && !campusIds.contains(targetClass.campusId())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "校区或课次地点已调整，请刷新后重试");
        }
        if (target != null && target.classId() != targetClass.id()) { throw invalid("目标课次不属于目标班级"); }
        long orderId = current.orderId();
        long entitlement = entitlementLesson(current, source, sourceClass);
        return new Initial(current, sourceClass, source, targetClass, target, entitlement, orderId);
    }

    private long entitlementLesson(Enrollment enrollment, SessionRow source, ClassRow sourceClass) {
        List<Long> ids = jdbc.queryForList("SELECT el.id FROM core_enrollment_lesson el JOIN core_lesson source_lesson "
            + "ON source_lesson.id=? JOIN core_effective_enrollment_lesson effective_el "
            + "ON effective_el.id=el.id WHERE el.enrollment_id=? AND el.order_id=? AND el.sequence_no=? "
            + "AND el.deleted=0 AND (effective_el.delivery_lesson_id=source_lesson.id "
            + "OR el.source_lesson_id=source_lesson.id)", Long.class, source.lessonId(), enrollment.id(),
            enrollment.orderId(), source.sequence());
        if (ids.size() != 1) { throw invalid("原课次未匹配唯一报名课时权益"); }
        return ids.get(0);
    }

    private void validateAssignment(Initial rows, String kind) {
        requireActive(rows.enrollment());
        requireAssignableTarget(rows);
        if ("makeup".equals(kind)) { validateMakeup(rows); }
        else if ("temporary".equals(kind)) { validateTemporary(rows); }
        else { throw invalid("预约类型无效"); }
        validateCourseRules(rows);
        if (rows.source().sequence() != rows.target().sequence()) { throw invalid("目标课次教学进度不一致"); }
        requireNoPrior(rows);
        List<Long> consumed = jdbc.query("SELECT id FROM core_consumption WHERE order_id=? "
            + "AND entitlement_lesson_id=? AND deleted=0 FOR UPDATE", (rs, row) -> rs.getLong(1),
            rows.orderId(), rows.entitlementLessonId());
        if (!consumed.isEmpty()) { throw invalid("原课次已课消"); }
        requireTargetUnused(rows);
        validateCapacityAndConflict(rows);
    }

    private void requireAssignableTarget(Initial rows) {
        if (rows.target() == null || rows.targetClass() == null || !rows.targetClass().active()
                || rows.source().id() == rows.target().id()
                || !"scheduled".equals(rows.target().status()) || !rows.target().start().isAfter(Instant.now())) {
            throw invalid("目标课次无效或已开始");
        }
        if (rows.source().status().equals("cancelled") || rows.source().status().equals("canceled")) {
            throw invalid("原课次无效");
        }
        new CampusResourceRules(jdbc).validate(rows.target().campusId(),
            new CampusResourceRules.Request(rows.target().room(), rows.targetClass().capacity(),
                rows.targetClass().subject(), rows.targetClass().grade()));
    }

    private void validateMakeup(Initial rows) {
        requireMakeupSource(rows);
        if (!rows.target().start().isAfter(rows.source().start())) { throw invalid("补课只能安排在原课次之后"); }
        if (!settingBoolean("attendance", "allowMakeup", true)) { throw invalid("考勤设置未允许补课"); }
    }

    private void validateTemporary(Initial rows) {
        if (rows.enrollment().classId() != rows.sourceClass().id()
                || !rows.source().start().isAfter(Instant.now()) || sessionProtected(rows.source().id())) {
            throw invalid("临时转班只能办理未开始且无考勤或课消的原课次");
        }
    }

    private void validateCourseRules(Initial rows) {
        if (!sameGrade(rows.sourceClass().grade(), rows.targetClass().grade())
                || !rows.sourceClass().subject().equals(rows.targetClass().subject())) {
            throw invalid("目标课次年级学科不一致");
        }
        if (settingBoolean("move", "sameCourse", false) && rows.sourceClass().courseId() != rows.targetClass().courseId()) {
            throw invalid("调课配置要求同一课程");
        }
        Integer maxDays = settingInteger("move", "maxDays");
        if (maxDays != null && (maxDays < 0 || maxDays > 365
                || Math.abs(java.time.temporal.ChronoUnit.DAYS.between(shanghaiDate(rows.source().start()),
                    shanghaiDate(rows.target().start()))) > maxDays)) {
            throw invalid("补课超出配置天数");
        }
    }

    private void requireTargetUnused(Initial rows) {
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(id) FROM core_session_adjustment WHERE enrollment_id=? "
            + "AND target_session_id=? AND status IN ('active','completed') AND deleted=0", Integer.class,
            rows.enrollment().id(), rows.target().id());
        if (duplicate != null && duplicate > 0) { throw invalid("该学生已有目标课次预约"); }
    }

    private void requireMakeupSource(Initial rows) {
        List<Long> attendance = jdbc.query("SELECT a.id FROM core_attendance a "
            + "WHERE a.enrollment_id=? AND a.session_id=? AND a.status IN ('leave','absent') AND a.deleted=0 "
            + "FOR UPDATE",
            (rs, row) -> rs.getLong(1), rows.enrollment().id(), rows.source().id());
        if (attendance.isEmpty()) { throw invalid("补课须有原课次未扣课请假或缺勤依据"); }
        List<Long> consumed = jdbc.query("SELECT id FROM core_consumption WHERE order_id=? "
            + "AND entitlement_lesson_id=? AND deleted=0 FOR UPDATE", (rs, row) -> rs.getLong(1),
            rows.orderId(), rows.entitlementLessonId());
        if (!consumed.isEmpty()) { throw invalid("原课次权益已课消"); }
    }

    private void requireNoPrior(Initial rows) {
        List<Long> prior = jdbc.query("SELECT id FROM core_session_adjustment WHERE order_id=? "
            + "AND (lesson_id=? OR source_session_id=?) AND status IN ('active','completed','waived') AND deleted=0 "
            + "FOR UPDATE", (rs, row) -> rs.getLong(1), rows.orderId(), rows.entitlementLessonId(),
            rows.source().id());
        if (!prior.isEmpty()) { throw invalid("原讲次已有补课预约或放弃记录"); }
    }

    private void validateCapacityAndConflict(Initial rows) {
        List<Long> roster = seats.roster(rows.target().id());
        if (roster.contains(rows.enrollment().studentId())) { throw invalid("学生已在目标课次名单"); }
        SeatAvailabilityRepository.SessionSeats targetSeats = seats.sessions(rows.targetClass().id()).stream()
            .filter(session -> session.id() == rows.target().id()).findFirst()
            .orElseThrow(() -> invalid("目标课次容量信息不存在"));
        List<Long> knownStudents = new ArrayList<>(roster);
        knownStudents.add(rows.enrollment().studentId());
        int trialOccupancy = seats.trialOccupied(rows.target().id(), knownStudents);
        if (roster.size() >= targetSeats.capacity()
                || roster.size() + trialOccupancy >= targetSeats.capacity() + targetSeats.virtualSeats()) {
            throw invalid("目标课次容量已满");
        }
        Integer conflict = jdbc.queryForObject("SELECT COUNT(DISTINCT s.id) FROM core_session_enrollment e "
            + "JOIN core_session s ON s.id=e.session_id AND s.deleted=0 AND s.status='scheduled' "
            + "WHERE e.student_id=? AND s.id NOT IN (?,?) AND s.starts_at<? AND s.ends_at>? "
            + "AND NOT EXISTS (SELECT a.id FROM core_session_adjustment a WHERE a.enrollment_id=e.id "
            + "AND a.source_session_id=s.id AND a.status IN ('active','completed') AND a.deleted=0)", Integer.class,
            rows.enrollment().studentId(), rows.target().id(), rows.source().id(), Timestamp.from(rows.target().end()),
            Timestamp.from(rows.target().start()));
        Integer adjustmentConflict = jdbc.queryForObject("SELECT COUNT(DISTINCT s.id) FROM core_session_adjustment a "
            + "JOIN core_session s ON s.id=a.target_session_id AND s.deleted=0 AND s.status='scheduled' "
            + "WHERE a.student_id=? AND a.status='active' AND a.deleted=0 AND s.id<>? "
            + "AND s.starts_at<? AND s.ends_at>?", Integer.class, rows.enrollment().studentId(),
            rows.target().id(), Timestamp.from(rows.target().end()), Timestamp.from(rows.target().start()));
        if ((conflict != null && conflict > 0) || (adjustmentConflict != null && adjustmentConflict > 0)) {
            throw invalid("学生目标时间已有其他课程");
        }
    }

    private boolean sessionProtected(long sessionId) {
        Integer count = jdbc.queryForObject("SELECT (SELECT COUNT(id) FROM core_attendance WHERE session_id=? AND deleted=0) "
            + "+(SELECT COUNT(id) FROM core_consumption WHERE session_id=? AND deleted=0)", Integer.class,
            sessionId, sessionId);
        return count != null && count > 0;
    }

    private boolean settingBoolean(String section, String field, boolean fallback) {
        List<String> values = jdbc.queryForList("SELECT value_cipher FROM core_system_setting WHERE section=? "
            + "AND field_name=? AND deleted=0", String.class, section, field);
        if (values.isEmpty()) { return fallback; }
        if (values.size() != 1) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "系统设置重复"); }
        String value = cipher.decrypt(values.get(0));
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "考勤配置布尔值无效");
        }
        return Boolean.parseBoolean(value);
    }

    private Integer settingInteger(String section, String field) {
        List<String> values = jdbc.queryForList("SELECT value_cipher FROM core_system_setting WHERE section=? "
            + "AND field_name=? AND deleted=0", String.class, section, field);
        if (values.isEmpty()) { return null; }
        if (values.size() != 1) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "系统设置重复"); }
        try { return Integer.valueOf(cipher.decrypt(values.get(0))); }
        catch (NumberFormatException error) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "调课天数设置无效"); }
    }

    private Enrollment enrollment(Actor actor, String publicId) {
        ScopeSql.Clause classScope = ScopeSql.classes(actor), orderScope = ScopeSql.orders(actor);
        String sql = "SELECT e.id,e.public_id,e.student_id,s.public_id student_public_id,e.order_id,effective_e.class_id,"
            + "e.status,effective_e.version,s.owner_id,s.assignee_id FROM core_enrollment e "
            + "JOIN core_student s ON s.id=e.student_id AND s.deleted=0 "
            + "JOIN core_crm_order o ON o.id=e.order_id AND o.deleted=0 "
            + "JOIN core_effective_enrollment effective_e ON effective_e.id=e.id "
            + "JOIN core_class c ON c.id=effective_e.class_id AND c.deleted=0 "
            + "WHERE e.public_id=? AND e.deleted=0 AND (" + classScope.sql() + ") AND (" + orderScope.sql() + ")";
        List<Object> args = new ArrayList<>(); args.add(publicId); args.addAll(classScope.args()); args.addAll(orderScope.args());
        List<Enrollment> rows = jdbc.query(sql, (rs, ignored) -> readEnrollment(rs), args.toArray());
        if (rows.size() != 1) { throw notFound(); }
        return rows.get(0);
    }

    private Enrollment readEnrollment(ResultSet rs) throws SQLException {
        return new Enrollment(rs.getLong("id"), rs.getString("public_id"), rs.getLong("student_id"),
            rs.getString("student_public_id"), rs.getLong("order_id"), rs.getLong("class_id"),
            rs.getString("status"), rs.getLong("version"), rs.getObject("owner_id", Long.class),
            rs.getObject("assignee_id", Long.class));
    }

    private ClassRow classRow(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<ClassRow> rows = jdbc.query("SELECT c.id,c.public_id,c.campus_id,c.course_id,co.grade,co.subject,"
            + "c.capacity,c.room,c.active FROM core_class c JOIN core_course co ON co.id=c.course_id "
            + "WHERE c.public_id=? AND c.deleted=0 AND (" + scope.sql() + ")", this::readClass,
            args(publicId, scope.args()));
        if (rows.size() != 1) { throw notFound(); }
        return rows.get(0);
    }

    private ClassRow classById(Actor actor, long id) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<ClassRow> rows = jdbc.query("SELECT c.id,c.public_id,c.campus_id,c.course_id,co.grade,co.subject,"
            + "c.capacity,c.room,c.active FROM core_class c JOIN core_course co ON co.id=c.course_id "
            + "WHERE c.id=? AND c.deleted=0 AND (" + scope.sql() + ")", this::readClass,
            args(id, scope.args()));
        if (rows.size() != 1) { throw notFound(); }
        return rows.get(0);
    }

    private ClassRow readClass(ResultSet rs, int row) throws SQLException {
        return new ClassRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("campus_id"),
            rs.getLong("course_id"), rs.getString("grade"), rs.getString("subject"), rs.getInt("capacity"),
            rs.getString("room"), rs.getBoolean("active"));
    }

    private SessionRow session(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<SessionRow> rows = jdbc.query("SELECT s.id,s.public_id,s.class_id,s.lesson_id,l.sequence_no,"
            + "COALESCE(s.campus_id,c.campus_id) campus_id,COALESCE(s.room,c.room) room,"
            + "s.starts_at,s.ends_at,s.status,s.version "
            + "FROM core_session s JOIN core_class c ON c.id=s.class_id JOIN core_lesson l ON l.id=s.lesson_id "
            + "WHERE s.public_id=? AND s.deleted=0 AND c.deleted=0 AND (" + scope.sql() + ")",
            this::readSession, args(publicId, scope.args()));
        if (rows.size() != 1) { throw notFound(); }
        return rows.get(0);
    }

    private SessionRow readSession(ResultSet rs, int row) throws SQLException {
        return new SessionRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("class_id"),
            rs.getLong("lesson_id"), rs.getInt("sequence_no"), rs.getLong("campus_id"),
            rs.getString("room"),
            rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
            rs.getString("status"), rs.getLong("version"));
    }

    private Adjustment adjustment(Actor actor, String publicId) {
        List<Adjustment> rows = jdbc.query(adjustmentSql(actor), this::readAdjustment, adjustmentArgs(actor, publicId));
        if (rows.size() != 1) { throw notFound(); }
        return rows.get(0);
    }

    private String adjustmentSql(Actor actor) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        return "SELECT a.id,a.public_id,a.kind,a.status,a.enrollment_id,e.public_id enrollment_public_id,"
            + "a.student_id,a.source_session_id,source_s.public_id source_session_public_id,a.target_session_id,"
            + "target_s.public_id target_session_public_id,a.version,target_s.starts_at target_starts_at "
            + "FROM core_session_adjustment a JOIN core_enrollment e ON e.id=a.enrollment_id "
            + "JOIN core_class c ON c.id=COALESCE(a.target_class_id,a.source_class_id) "
            + "JOIN core_session source_s ON source_s.id=a.source_session_id "
            + "LEFT JOIN core_session target_s ON target_s.id=a.target_session_id "
            + "WHERE a.public_id=? AND a.deleted=0 AND (" + scope.sql() + ")";
    }

    private List<Object> adjustmentArgs(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(); args.add(publicId); args.addAll(scope.args()); return args;
    }

    private Adjustment readAdjustment(ResultSet rs, int row) throws SQLException {
        Timestamp starts = rs.getTimestamp("target_starts_at");
        return new Adjustment(rs.getLong("id"), rs.getString("public_id"), rs.getString("kind"),
            rs.getString("status"), rs.getLong("enrollment_id"), rs.getString("enrollment_public_id"),
            rs.getLong("student_id"), rs.getLong("source_session_id"), rs.getString("source_session_public_id"),
            rs.getObject("target_session_id", Long.class), rs.getString("target_session_public_id"),
            rs.getLong("version"), starts == null ? null : starts.toInstant());
    }

    private void lockTimetable(long sourceSession, Long targetSession) {
        List<Long> classIds = new ArrayList<>(jdbc.queryForList("SELECT class_id FROM core_session WHERE id=?",
            Long.class, sourceSession));
        if (targetSession != null) { classIds.addAll(jdbc.queryForList("SELECT class_id FROM core_session WHERE id=?",
            Long.class, targetSession)); }
        List<Long> sessions = new ArrayList<>(List.of(sourceSession));
        if (targetSession != null) { sessions.add(targetSession); }
        List<Long> campuses = new ArrayList<>();
        for (Long sessionId : sessions) {
            campuses.add(jdbc.queryForObject("SELECT COALESCE(s.campus_id,c.campus_id) FROM core_session s "
                + "JOIN core_class c ON c.id=s.class_id WHERE s.id=?", Long.class, sessionId));
        }
        for (Long classId : classIds) {
            campuses.add(jdbc.queryForObject("SELECT campus_id FROM core_class WHERE id=?", Long.class, classId));
        }
        campuses.stream().distinct().sorted().forEach(this::lockCampus);
        classIds.stream().distinct().sorted().forEach(id -> jdbc.queryForObject(
            "SELECT id FROM core_class WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id));
        sessions.stream().distinct().sorted().forEach(id -> jdbc.queryForObject(
            "SELECT id FROM core_session WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id));
        for (Long sessionId : sessions) {
            Long currentCampus = jdbc.queryForObject("SELECT COALESCE(s.campus_id,c.campus_id) FROM core_session s "
                + "JOIN core_class c ON c.id=s.class_id WHERE s.id=?", Long.class, sessionId);
            if (!campuses.contains(currentCampus)) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, "课次地点已调整，请刷新后重试");
            }
        }
    }

    private void lockCampus(long id) {
        jdbc.queryForObject("SELECT id FROM core_campus WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id);
    }

    private void validateVersions(Initial rows, long enrollmentVersion, long sourceVersion, Long targetVersion) {
        if (rows.enrollment().version() != enrollmentVersion || rows.source().version() != sourceVersion
                || targetVersion != null && rows.target().version() != targetVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "报名或课次已更新，请刷新后重试");
        }
    }

    private void requireActive(Enrollment enrollment) {
        if (!"active".equals(enrollment.status())) { throw invalid("报名权益未生效"); }
    }

    private boolean sameGrade(String left, String right) {
        return normalizeGrade(left).equals(normalizeGrade(right));
    }

    private String normalizeGrade(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) { return raw; }
        String key = Normalizer.normalize(raw, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("^(小学|小|初中|初|高中|高)?([一二三四五六七八九1-9])(?:年级|年)?$")
            .matcher(key);
        if (!match.matches()) { return raw; }
        String token = match.group(2);
        int grade = token.matches("[1-9]") ? Integer.parseInt(token) : "一二三四五六七八九".indexOf(token) + 1;
        String stage = match.group(1);
        if (stage == null) {
            if (!key.endsWith("年级") && !key.endsWith("年")) { return raw; }
            if (grade > 6) { stage = "初中"; grade -= 6; } else { stage = "小学"; }
        } else if (stage.startsWith("小")) { stage = "小学"; }
        else if (stage.startsWith("初")) { stage = "初中"; }
        else { stage = "高中"; }
        int maxGrade = "小学".equals(stage) ? 6 : 3;
        return grade < 1 || grade > maxGrade ? raw : stage + "一二三四五六七八九".charAt(grade - 1) + "年级";
    }

    private java.time.LocalDate shanghaiDate(Instant value) {
        return value.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private Receipt receipt(ReceiptData data) {
        return new Receipt(data.id(), data.kind(), data.status(), data.enrollmentId(), data.sourceId(),
            data.targetId(), data.version(), data.occurredAt());
    }

    private Object[] args(Object first, List<Object> rest) {
        Object[] result = new Object[rest.size() + 1]; result[0] = first;
        for (int index = 0; index < rest.size(); index++) { result[index + 1] = rest.get(index); }
        return result;
    }

    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_INPUT, message); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.NOT_FOUND, "报名、课次或预约不在当前范围"); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private Instant now() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); }
}
