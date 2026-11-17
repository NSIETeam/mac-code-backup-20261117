package cn.siyue.core;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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

/** Course and class catalog persistence, including safe class maintenance. */
@Repository
public class AcademicCatalogRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final CampusResourceRules campusResourceRules;
    private final AcademicClassSchedulePersistence classSchedule;
    /**
     * Creates the catalog persistence adapter with campus-resource validation support.
     * @param jdbc database access used for catalog and timetable records
     * @param cipher decrypts protected academic fields returned by delegated query repositories
     */
    public AcademicCatalogRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.campusResourceRules = new CampusResourceRules(jdbc);
        this.classSchedule = new AcademicClassSchedulePersistence(jdbc);
    }
    record ClassUpdateRow(long id,String publicId,long campusId,long teacherId,String teacherPublicId,
            String name,String room,int capacity,long version) { }
    private record FutureSession(long id,long teacherId,Instant startsAt,Instant endsAt) { }
    private record ClassUpdatePlan(String name,String room,long teacherId,List<FutureSession> movable) { }
    private record ClassRow(long id, String publicId, String courseId, String campusId,
            String teacherId, String name, String room, int capacity, int durationMinutes, long version) { }
    private record SubjectGrade(String subject, String grade) { }
    private record CourseLink(long id, Long campusId) { }
    private record CourseRow(long id, String publicId, String campusPublicId, String name,
            String courseType, String grade, String subject, BigDecimal price, long version,
            AcademicDtos.CourseMetadata metadata, boolean active) { }
    record CourseStatusRow(long id, String publicId, Long campusId, boolean active, long version) { }
    /**
     * Creates a typed course and its ordered lesson titles; callers must run this in a transaction. Campus-scoped actors must choose a linked active campus; all-scope actors may create an unassigned course.
     * @param actor caller whose campus scope constrains the new course
     * @param input course fields and ordered lesson titles, already validated by the service boundary
     * @return the new course snapshot with version 1 and its ordered lessons
     * @throws BusinessException when campus is missing, inactive, or outside the actor's scope
     * @throws IllegalStateException if MySQL does not return the generated course key
     */
    public CourseReceipt createCourse(Actor actor, CourseInput input) {
        Long campusId = null;
        if (input.campusId() != null && !input.campusId().isBlank()) {
            List<Long> campuses = jdbc.query("""
                SELECT id FROM core_campus WHERE public_id = ? AND active = 1 AND deleted = 0
                """, (rs, row) -> rs.getLong("id"), input.campusId());
            if (campuses.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或已停用"); }
            campusId = campuses.get(0);
            if (!actor.scope().equals("all") && !actor.campusIds().contains(campusId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权在此校区创建课程");
            }
        } else if (!actor.scope().equals("all")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "校区范围账号创建课程时必须选择授权校区");
        }
        String coursePublicId = "COURSE_" + UUID.randomUUID();
        KeyHolder key = new GeneratedKeyHolder();
        Long selectedCampus = campusId;
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO core_course (public_id,campus_id,name,course_type,grade,subject,price)
                VALUES (?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, coursePublicId);
            if (selectedCampus == null) { statement.setNull(2, java.sql.Types.BIGINT); }
            else { statement.setLong(2, selectedCampus); }
            statement.setString(3, input.name().trim());
            statement.setString(4, input.courseType());
            statement.setString(5, input.grade().trim());
            statement.setString(6, input.subject().trim());
            statement.setBigDecimal(7, input.price().setScale(2, RoundingMode.UNNECESSARY));
            return statement;
        }, key);
        Number generated = key.getKey();
        if (generated == null) { throw new IllegalStateException("MySQL did not return the course key"); }
        long courseId = generated.longValue();
        saveCourseMetadata(courseId, input.metadata());
        List<LessonSummary> lessons = new ArrayList<>();
        int sequence = 0;
        for (var lesson : input.lessons()) {
            sequence++;
            String lessonPublicId = "LESSON_" + UUID.randomUUID();
            String title = lesson.title().trim();
            jdbc.update("""
                INSERT INTO core_lesson (public_id,course_id,sequence_no,title)
                VALUES (?,?,?,?)
                """, lessonPublicId, courseId, sequence, title);
            lessons.add(new LessonSummary(lessonPublicId, sequence, title));
        }
        return new CourseReceipt(coursePublicId, input.campusId(), input.name().trim(), input.courseType(),
            input.grade().trim(), input.subject().trim(), input.price().setScale(2, RoundingMode.UNNECESSARY),
            1, List.copyOf(lessons), normalizedMetadata(input.metadata()), true);
    }
    private AcademicDtos.CourseMetadata normalizedMetadata(AcademicDtos.CourseMetadata metadata) {
        if (metadata == null) { return null; }
        return new AcademicDtos.CourseMetadata(metadata.year(), metadata.term().trim(),
            metadata.classType() == null ? null : metadata.classType().trim(), metadata.courseKind(), metadata.format());
    }
    private void saveCourseMetadata(long courseId, AcademicDtos.CourseMetadata metadata) {
        AcademicDtos.CourseMetadata normalized = normalizedMetadata(metadata);
        if (normalized == null) { return; }
        jdbc.update("""
            UPDATE core_course SET academic_year=?,term=?,class_type=?,course_kind=?,course_format=? WHERE id=?
            """, normalized.year(), normalized.term(), normalized.classType(), normalized.courseKind(), normalized.format(), courseId);
    }
    private AcademicDtos.CourseMetadata courseMetadata(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer year = rs.getObject("academic_year", Integer.class);
        if (year == null) { return null; }
        return new AcademicDtos.CourseMetadata(year, rs.getString("term"), rs.getString("class_type"),
            rs.getString("course_kind"), rs.getString("course_format"));
    }
    /**
     * Returns scoped courses and ordered lesson names using default course filters.
     * @param actor caller whose course scope is applied
     * @param campusPublicId optional campus public identifier; blank means no explicit campus filter
     * @param page one-based page number
     * @param size page size
     * @return scoped course page with course metadata and active lesson titles
     * @throws BusinessException when the selected campus is outside scope or scope is invalid
     */
    public CoursePage courses(Actor actor, String campusPublicId, int page, int size) {
        return courses(actor, new AcademicDtos.CourseQuery(campusPublicId, null, null, null,
            null, null, null, null, null, page, size, null, null));
    }
    /**
     * Uses the same authorization and filter predicates for both count and result rows.
     * @param actor caller whose catalog scope is applied
     * @param query optional campus, status, course attributes, year, search, and pagination filters
     * @return matching course page and total count
     * @throws BusinessException when a campus filter is outside scope or the actor scope is invalid
     */
    public CoursePage courses(Actor actor, AcademicDtos.CourseQuery query) {
        int page = query.page() == null ? 1 : query.page(), size = query.size() == null ? 50 : query.size();
        String campusPublicId = query.campusId();
        ScopeSql.Clause scope = courseScope(actor);
        List<Object> args = new ArrayList<>(scope.args());
        String campusFilter = "";
        if (campusPublicId != null && !campusPublicId.isBlank()) {
            long selectedCampus = campus(actor, campusPublicId);
            campusFilter = " AND (co.campus_id IS NULL OR co.campus_id = ?)";
            args.add(selectedCampus);
        }
        campusFilter += courseFilters(query, args);
        Long total = jdbc.queryForObject("""
            SELECT COUNT(co.id) FROM core_course co
            WHERE co.deleted = 0 AND (
            """ + scope.sql() + ")" + campusFilter, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.multiplyExact((long) page - 1, size));
        List<CourseRow> courses = jdbc.query("""
            SELECT co.id,co.public_id,cp.public_id AS campus_public_id,co.name,co.course_type,
                   co.grade,co.subject,co.price,co.version,
                   co.academic_year,co.term,co.class_type,co.course_kind,co.course_format,co.active
            FROM core_course co LEFT JOIN core_campus cp ON cp.id = co.campus_id
            WHERE co.deleted = 0 AND (
            """ + scope.sql() + ")" + campusFilter + " ORDER BY co.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new CourseRow(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("campus_public_id"), rs.getString("name"), rs.getString("course_type"),
                rs.getString("grade"), rs.getString("subject"), rs.getBigDecimal("price"),
                rs.getLong("version"), courseMetadata(rs), rs.getBoolean("active")), pageArgs.toArray());
        if (courses.isEmpty()) { return new CoursePage(List.of(), total == null ? 0 : total, page, size); }
        String placeholders = String.join(",", java.util.Collections.nCopies(courses.size(), "?"));
        List<Object> lessonArgs = courses.stream().map(CourseRow::id).map(id -> (Object) id).toList();
        Map<Long, List<LessonSummary>> lessons = new java.util.HashMap<>();
        jdbc.query("""
            SELECT course_id,public_id,sequence_no,title FROM core_lesson
            WHERE deleted = 0 AND course_id IN (
            """ + placeholders + ") ORDER BY course_id,sequence_no",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> lessons
                .computeIfAbsent(rs.getLong("course_id"), ignored -> new ArrayList<>())
                .add(new LessonSummary(rs.getString("public_id"), rs.getInt("sequence_no"), rs.getString("title"))),
            lessonArgs.toArray());
        List<CourseReceipt> items = courses.stream().map(course -> new CourseReceipt(course.publicId(),
            course.campusPublicId(), course.name(), course.courseType(), course.grade(), course.subject(),
            course.price(), course.version(), List.copyOf(lessons.getOrDefault(course.id(), List.of())), course.metadata(), course.active())).toList();
        return new CoursePage(items, total == null ? 0 : total, page, size);
    }
    private String courseFilters(AcademicDtos.CourseQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        if (!"all".equals(query.status())) {
            sql.append(" AND co.active = ?"); args.add(!"inactive".equals(query.status()));
        }
        String[][] filters = {{"co.course_type", query.courseType()}, {"co.course_format", query.format()}, {"co.term", query.term()},
            {"co.grade", query.grade()}, {"co.subject", query.subject()},
            {"co.class_type", query.classType()}, {"co.course_kind", query.courseKind()}};
        for (String[] filter : filters) {
            if (filter[1] != null && !filter[1].isBlank()) {
                sql.append(" AND ").append(filter[0]).append(" = ?"); args.add(filter[1].trim());
            }
        }
        if (query.year() != null) { sql.append(" AND co.academic_year = ?"); args.add(query.year()); }
        if (query.search() != null && !query.search().isBlank()) {
            sql.append(" AND co.name LIKE ? ESCAPE '!'");
            args.add("%" + query.search().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        return sql.toString();
    }
    /**
     * Locks a course row for a status or curriculum mutation without widening catalog scope.
     * @param actor caller whose catalog scope is checked
     * @param courseId course public identifier
     * @return locked course identity, campus, active flag, and current version
     * @throws BusinessException when the course is unavailable in scope or write scope is insufficient
     */
    public CourseStatusRow lockCourseStatus(Actor actor,String courseId) { return courseStatus(actor,courseId,true); }
    /**
     * Reads the current course status and version without acquiring a write lock.
     * @param actor caller whose catalog scope is checked
     * @param courseId course public identifier
     * @return current course identity, campus, active flag, and version
     * @throws BusinessException when the course is unavailable in scope
     */
    public CourseStatusRow readCourseStatus(Actor actor,String courseId) { return courseStatus(actor,courseId,false); }
    private CourseStatusRow courseStatus(Actor actor,String courseId,boolean write) {
        ScopeSql.Clause scope = courseScope(actor);
        List<Object> args = new ArrayList<>(List.of(courseId)); args.addAll(scope.args());
        List<CourseStatusRow> found = jdbc.query("""
            SELECT co.id,co.public_id,co.campus_id,co.active,co.version FROM core_course co
            WHERE co.public_id=? AND co.deleted=0 AND (
            """ + scope.sql() + ")" + (write?" FOR UPDATE":""), (rs,row) -> new CourseStatusRow(rs.getLong("id"),
                rs.getString("public_id"), rs.getObject("campus_id", Long.class), rs.getBoolean("active"),
                rs.getLong("version")), args.toArray());
        if (found.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "课程不存在或不在授权范围"); }
        CourseStatusRow current = found.get(0);
        if (write && !actor.scope().equals("all") && (current.campusId() == null || !actor.campusIds().contains(current.campusId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权修改此课程");
        }
        return current;
    }
    /**
     * Updates only the course active flag and version, preserving linked teaching and financial history.
     * @param current status row obtained through {@link #lockCourseStatus(Actor, String)}
     * @param input target active flag and expected version
     * @return status receipt with the incremented version
     * @throws BusinessException when the expected version is stale or the requested status is unchanged
     */
    public AcademicDtos.CourseStatusReceipt setCourseStatus(CourseStatusRow current, AcademicDtos.CourseStatusInput input) {
        if (current.version() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "课程版本已变化，请刷新后办理");
        }
        if (current.active() == input.active()) { throw new BusinessException(ErrorCode.INVALID_INPUT, "课程状态未变化"); }
        int changed = jdbc.update("""
            UPDATE core_course SET active=?,teaching_published=0,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, input.active(), current.id(), current.version());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "课程版本已变化"); }
        return new AcademicDtos.CourseStatusReceipt(current.publicId(), input.active(), current.version() + 1);
    }
    /**
     * Updates course details while preserving existing lesson identifiers and referenced history. Teaching changes are rejected once sessions, enrollment entitlements, or frozen order terms reference the course.
     * @param current course row locked within the caller's authorized scope
     * @param input updated course fields, ordered lessons, and expected version
     * @return update receipt containing the new version, lesson identifiers, and resulting price
     * @throws BusinessException when metadata is incomplete, referenced teaching would change, or version is stale
     */
    public AcademicDtos.CourseUpdateReceipt updateCourse(CourseStatusRow current, AcademicDtos.CourseUpdateInput input) {
        if (current.version() != input.expectedVersion()) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "课程版本已变化，请刷新后编辑");
        }
        Integer year = jdbc.queryForObject("SELECT academic_year FROM core_course WHERE id=?", Integer.class, current.id());
        if ((year == null) != (input.year() == null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "历史分类资料不完整，不能单独改变年份");
        }
        List<LessonSummary> existing = jdbc.query("""
            SELECT public_id,sequence_no,title FROM core_lesson WHERE course_id=? AND deleted=0
            ORDER BY sequence_no FOR UPDATE
            """, (rs,row) -> new LessonSummary(rs.getString("public_id"), rs.getInt("sequence_no"), rs.getString("title")), current.id());
        boolean teachingChanged = existing.size()!=input.lessons().size();
        for(int i=0;i<Math.min(existing.size(),input.lessons().size());i++) {
            teachingChanged |= !existing.get(i).title().equals(input.lessons().get(i).title().trim());
        }
        if(teachingChanged && jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM core_session s JOIN core_class c ON c.id=s.class_id WHERE c.course_id=?)"
                +"+(SELECT COUNT(*) FROM core_enrollment_lesson e JOIN core_lesson l ON l.id=e.source_lesson_id WHERE l.course_id=?)"
                +"+(SELECT COUNT(*) FROM core_crm_order_class_terms t JOIN core_class c ON c.id=t.class_id WHERE c.course_id=?)",Long.class,current.id(),current.id(),current.id())>0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"已有排期或报名权益引用教学版本，请新建课程版本");
        }
        for (int i = input.lessons().size(); i < existing.size(); i++) {
            String id = existing.get(i).id();
            Long references = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM core_session s JOIN core_lesson l ON l.id=s.lesson_id WHERE l.public_id=?)
                    + (SELECT COUNT(*) FROM core_enrollment_lesson e JOIN core_lesson l ON l.id=e.source_lesson_id WHERE l.public_id=?)
                """, Long.class, id, id);
            if (references != null && references > 0) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "最后一讲已关联班级排期或报名权益，不能删除");
            }
        }
        for (int i = input.lessons().size(); i < existing.size(); i++) {
            jdbc.update("UPDATE core_lesson SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE public_id=? AND course_id=? AND deleted=0", existing.get(i).id(), current.id());
        }
        List<LessonSummary> lessons = new ArrayList<>();
        for (int i = 0; i < input.lessons().size(); i++) {
            String title = input.lessons().get(i).title().trim();
            String id = i < existing.size() ? existing.get(i).id() : "LESSON_" + UUID.randomUUID();
            if (i < existing.size()) {
                jdbc.update("UPDATE core_lesson SET title=?,updated_at=UTC_TIMESTAMP(3) WHERE public_id=? AND course_id=?", title, id, current.id());
            } else {
                jdbc.update("INSERT INTO core_lesson (public_id,course_id,sequence_no,title) VALUES (?,?,?,?)", id, current.id(), i + 1, title);
            }
            lessons.add(new LessonSummary(id, i + 1, title));
        }
        int changed = jdbc.update("""
            UPDATE core_course SET name=?,academic_year=?,price=COALESCE(?,price),teaching_published=0,curriculum_version=curriculum_version+?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """, input.name().trim(), input.year(), input.price(), teachingChanged?1:0, current.id(), current.version());
        if (changed != 1) { throw new BusinessException(ErrorCode.ALREADY_EXISTS, "课程版本已变化"); }
        return new AcademicDtos.CourseUpdateReceipt(current.publicId(), input.name().trim(), input.year(),
            current.version() + 1, List.copyOf(lessons),jdbc.queryForObject("SELECT price FROM core_course WHERE id=?",BigDecimal.class,current.id()));
    }
    /**
     * Returns classes using the legacy campus/page filter mapped to a class query.
     * @param actor caller whose class scope is enforced by the query repository
     * @param campusPublicId optional campus public identifier
     * @param page one-based page number
     * @param size page size
     * @return scoped class page with persisted schedule data
     * @throws BusinessException when campus filtering is unauthorized or actor scope is invalid
     */
    public ClassPage classes(Actor actor, String campusPublicId, int page, int size) {
        return classes(actor, new AcademicDtos.ClassQuery("scope", campusPublicId, null, null, null, null, page, size, null, null, null, null, null, null, null, null, null, null));
    }
    /**
     * Delegates class listing so authorization and timetable filters match between count and rows.
     * @param actor caller whose class scope is enforced
     * @param query class filters and pagination
     * @return scoped class page
     * @throws BusinessException when filters are invalid or outside the caller's scope
     */
    public ClassPage classes(Actor actor, AcademicDtos.ClassQuery query) {
        return new AcademicQueryRepository(jdbc, cipher).classes(actor, query);
    }
    /**
     * Exports classes matching the same scoped filters used by class listing.
     * @param actor caller whose class scope is enforced
     * @param query class filters; export limits and validation are applied by the query repository
     * @return export rows and their filtered metadata
     * @throws BusinessException when filters are invalid, unauthorized, or exceed export limits
     */
    public AcademicDtos.ClassExport exportClasses(Actor actor,AcademicDtos.ClassQuery query) {
        return new AcademicQueryRepository(jdbc,cipher).exportClasses(actor,query);
    }
    /**
     * Reads one class and its schedule only if visible within the caller's current scope.
     * @param actor caller whose class scope is enforced
     * @param classId class public identifier
     * @return class detail and schedule
     * @throws BusinessException when the class is missing or outside scope
     */
    public AcademicDtos.ClassDetail classDetail(Actor actor,String classId) {
        return new AcademicQueryRepository(jdbc,cipher).classDetail(actor,classId);
    }
    /**
     * Lists students visible in a class roster using the class and student scope rules.
     * @param actor caller whose class/roster scope is enforced
     * @param classId class public identifier
     * @param query roster filters and pagination
     * @return scoped student roster page
     * @throws BusinessException when the class is missing/out of scope or query is invalid
     */
    public AcademicDtos.ClassStudentPage classStudents(Actor actor,String classId,AcademicDtos.ClassStudentQuery query) {
        return new ClassStudentRepository(jdbc,cipher).students(actor,classId,query);
    }
    /**
     * Exports a scoped class roster subject to repository export limits.
     * @param actor caller whose roster scope is enforced
     * @param classId class public identifier
     * @param query roster filters and export options
     * @return matching roster export
     * @throws BusinessException when class scope, filters, or export limits are violated
     */
    public AcademicDtos.ClassStudentExport exportClassStudents(Actor actor,String classId,AcademicDtos.ClassStudentExportQuery query) {
        return new ClassStudentRepository(jdbc,cipher).exportStudents(actor,classId,query);
    }
    /**
     * Lists attendance across the students visible to the caller, including effective enrollment scope.
     * @param actor caller whose student and attendance scope is enforced
     * @param query attendance filters and pagination
     * @return attendance page with scoped totals
     * @throws BusinessException when scope is invalid or filters are rejected
     */
    public AcademicDtos.AllStudentAttendancePage allStudentAttendance(Actor actor,AcademicDtos.AllStudentAttendanceQuery query) {
        return new AcademicQueryRepository(jdbc,cipher).allStudentAttendance(actor,query);
    }
    /**
     * Exports attendance for students visible to the caller under the same scope rules as listing.
     * @param actor caller whose attendance scope is enforced
     * @param query attendance filters and export options
     * @return matching attendance export
     * @throws BusinessException when scope, filters, or export limits are violated
     */
    public AcademicDtos.AllStudentAttendanceExport exportAllStudentAttendance(Actor actor,AcademicDtos.AllStudentAttendanceQuery query) {
        return new AcademicQueryRepository(jdbc,cipher).exportAllStudentAttendance(actor,query);
    }
    /**
     * Provides non-sensitive academic form choices; no access-administration capability is required.
     * @param actor caller whose campus scope limits returned choices
     * @param campusId optional campus public identifier
     * @return selectable courses, teachers, campuses, and related catalog values
     * @throws BusinessException when the requested campus is unavailable or outside scope
     */
    public AcademicDtos.AcademicOptions options(Actor actor, String campusId) {
        return new AcademicQueryRepository(jdbc, cipher).options(actor, campusId);
    }
    /**
     * Lists active enrollment classes for a student only when the student and classes are in caller scope.
     * @param actor caller whose student and class scope is enforced
     * @param studentId student public identifier
     * @param query status/search filters and pagination
     * @return scoped active-enrollment class page
     * @throws BusinessException when the student is missing/out of scope or query is invalid
     */
    public AcademicDtos.StudentClassPage studentClasses(Actor actor, String studentId, AcademicDtos.StudentQuery query) {
        return new AcademicQueryRepository(jdbc, cipher).studentClasses(actor, studentId, query);
    }
    private ScopeSql.Clause courseScope(Actor actor) {
        return switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1 = 1", List.of());
            case "campus" -> new ScopeSql.Clause("""
                (co.campus_id IS NULL OR co.campus_id IN (
                SELECT pc.campus_id FROM core_principal_campus pc
                WHERE pc.principal_id = ? AND pc.deleted = 0))
                """, List.of(actor.id()));
            case "assigned" -> {
                ScopeSql.Clause classScope = ScopeSql.classes(actor);
                yield new ScopeSql.Clause("""
                    (co.campus_id IS NULL OR EXISTS (
                    SELECT c.id FROM core_class c WHERE c.course_id = co.id
                    AND c.active = 1 AND c.deleted = 0 AND (
                    """ + classScope.sql() + ")))" , classScope.args());
            }
            case "own" -> new ScopeSql.Clause("co.campus_id IS NULL", List.of());
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
    }
    /**
     * Revalidates campus access before a create or idempotent replay.
     * @param actor caller whose campus scope is checked
     * @param campusPublicId campus public identifier; null/blank is allowed only for all-scope callers
     * @throws BusinessException when campus is inactive, missing, or outside the caller's scope
     */
    public void authorizeCampus(Actor actor, String campusPublicId) { campus(actor, campusPublicId); }
    /**
     * Locks an active class after locking its campus and confirming the caller's current class scope.
     * @param actor caller whose class scope is enforced
     * @param classPublicId class public identifier
     * @return locked class snapshot used for optimistic updates
     * @throws BusinessException when class/campus is unavailable or the class is outside scope
     */
    public ClassUpdateRow lockClass(Actor actor,String classPublicId) {
        List<Long> campuses = jdbc.queryForList("SELECT campus_id FROM core_class "
            + "WHERE public_id=? AND deleted=0 AND active=1", Long.class, classPublicId);
        if (campuses.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND,"班级不存在或不在授权范围"); }
        lockActiveCampus(campuses.get(0));
        ScopeSql.Clause scope=ScopeSql.classes(actor);
        List<Object> args=new ArrayList<>(List.of(classPublicId));args.addAll(scope.args());
        List<ClassUpdateRow> rows=jdbc.query("""
            SELECT c.id,c.public_id,c.campus_id,c.teacher_id,p.public_id AS teacher_public_id,
                   c.name,c.room,c.capacity,c.version
            FROM core_class c JOIN core_principal p ON p.id=c.teacher_id
            WHERE c.public_id=? AND c.active=1 AND c.deleted=0 AND (
            """+scope.sql()+") FOR UPDATE",
            (rs,row)->new ClassUpdateRow(rs.getLong("id"),rs.getString("public_id"),rs.getLong("campus_id"),
                rs.getLong("teacher_id"),rs.getString("teacher_public_id"),rs.getString("name"),
                rs.getString("room"),rs.getInt("capacity"),rs.getLong("version")),args.toArray());
        if(rows.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"班级不存在或不在授权范围");
        return rows.get(0);
    }
    /**
     * Updates class details and relocates only eligible future sessions without recorded attendance/consumption.
     * @param actor caller performing the audited update
     * @param current class row locked through {@link #lockClass(Actor, String)}
     * @param input new class fields and expected version
     * @return update receipt with resulting class details and incremented version
     * @throws BusinessException when version, capacity, teacher/campus, resource, uniqueness, or timetable checks fail
     */
    public ClassUpdateReceipt updateClass(Actor actor,ClassUpdateRow current,ClassUpdateInput input) {
        validateClassUpdate(current,input);
        String name=input.name().trim(),room=input.room()==null||input.room().isBlank()?current.room():input.room().trim();
        long teacherId=resolveUpdateTeacher(actor,current,input);
        validateCampusResources(current.campusId(), current.id(), room, input.capacity());
        validateUniqueClassName(current,name);
        validateClassChanged(current,input,name,room,teacherId);
        List<FutureSession> movable=movableSessions(current.id());
        if (!current.room().equals(room)) {
            preserveAllSessionLocations(current.id(), current.campusId(), current.room());
            appendClassLocation(actor, current, current.campusId(), room);
        }
        validateNoSessionConflicts(current,room,teacherId,movable);
        persistClassUpdate(actor,current,input,new ClassUpdatePlan(name,room,teacherId,movable));
        return new ClassUpdateReceipt(current.publicId(),name,room,input.teacherId(),input.capacity(),current.version()+1);
    }

    private void validateClassUpdate(ClassUpdateRow current,ClassUpdateInput input) {
        if(current.version()!=input.expectedVersion())throw new BusinessException(ErrorCode.ALREADY_EXISTS,"班级版本已变化");
        Integer enrolled=jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_effective_enrollment WHERE class_id=? AND status='active' AND deleted=0
            """,Integer.class,current.id());
        if(enrolled!=null&&input.capacity()<enrolled)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"班级容量不能小于当前在读人数");
        if(input.name()==null)throw new BusinessException(ErrorCode.INVALID_INPUT,"班级名称不能为空");
        String name=input.name().trim();
        if(name.isEmpty())throw new BusinessException(ErrorCode.INVALID_INPUT,"班级名称不能为空");
    }

    private long resolveUpdateTeacher(Actor actor,ClassUpdateRow current,ClassUpdateInput input) {
        List<Long> campuses=jdbc.query("SELECT id FROM core_campus WHERE id=? AND active=1 AND deleted=0 FOR UPDATE",
            (rs,row)->rs.getLong(1),current.campusId());
        List<Long> teachers=jdbc.query("SELECT id FROM core_principal WHERE public_id=? AND active=1 AND deleted=0 FOR UPDATE",
            (rs,row)->rs.getLong(1),input.teacherId());
        if(campuses.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"班级校区已停用");
        if(teachers.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"老师账号不存在或已停用");
        long teacherId=teachers.get(0);
        validateTeacherCampus(actor,current,teacherId);
        return teacherId;
    }

    private void validateTeacherCampus(Actor actor,ClassUpdateRow current,long teacherId) {
        if(!actor.scope().equals("campus"))return;
        Integer linked=jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_principal_campus WHERE principal_id=? AND campus_id=? AND deleted=0
            """,Integer.class,teacherId,current.campusId());
        if(linked==null||linked==0)throw new BusinessException(ErrorCode.FORBIDDEN,"所选老师不属于当前授权校区");
    }

    private void validateUniqueClassName(ClassUpdateRow current,String name) {
        Integer duplicate=jdbc.queryForObject("""
            SELECT COUNT(id) FROM core_class WHERE campus_id=? AND name=? AND id<>?
              AND active=1 AND deleted=0
            """,Integer.class,current.campusId(),name,current.id());
        if(duplicate!=null&&duplicate>0)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"该校区已有同名班级");
    }

    private void lockActiveCampus(long campusId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM core_campus WHERE id=? AND active=1 "
            + "AND deleted=0 FOR UPDATE", Long.class, campusId);
        if (ids.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "班级校区已停用"); }
    }

    private void validateCampusResources(long campusId, long classId, String room, int capacity) {
        List<SubjectGrade> details = jdbc.query("""
            SELECT co.subject,co.grade FROM core_class c JOIN core_course co ON co.id=c.course_id
            WHERE c.id=? AND c.deleted=0 AND co.deleted=0
            """, (rs, row) -> new SubjectGrade(rs.getString("subject"), rs.getString("grade")), classId);
        if (details.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "课程不存在"); }
        validateCampusResources(campusId, details.get(0), room, capacity);
    }

    private void validateCourseCampusResources(long campusId, long courseId, String room, int capacity) {
        List<SubjectGrade> details = jdbc.query("SELECT subject,grade FROM core_course WHERE id=? AND deleted=0",
            (rs, row) -> new SubjectGrade(rs.getString("subject"), rs.getString("grade")), courseId);
        if (details.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "课程不存在"); }
        validateCampusResources(campusId, details.get(0), room, capacity);
    }

    private void validateCampusResources(long campusId, SubjectGrade subjectGrade, String room, int capacity) {
        campusResourceRules.validate(campusId, new CampusResourceRules.Request(room, capacity,
            subjectGrade.subject(), subjectGrade.grade()));
    }

    private void preserveAllSessionLocations(long classId, long campusId, String room) {
        jdbc.update("UPDATE core_session SET campus_id=COALESCE(campus_id,?),room=COALESCE(room,?),"
            + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE class_id=? AND deleted=0 "
            + "AND status NOT IN ('cancelled','canceled')", campusId, room, classId);
    }

    private void appendClassLocation(Actor actor, ClassUpdateRow current, long campusId, String room) {
        jdbc.update("""
            INSERT INTO core_class_location_history(public_id,class_id,actor_id,from_campus_id,to_campus_id,
                from_room,to_room,occurred_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
            """, "CLH_" + UUID.randomUUID(), current.id(), actor.id(), current.campusId(), campusId,
            current.room(), room);
    }

    private void validateClassChanged(ClassUpdateRow current,ClassUpdateInput input,String name,String room,long teacherId) {
        if(name.equals(current.name())&&room.equals(current.room())&&teacherId==current.teacherId()
                &&input.capacity()==current.capacity())
            throw new BusinessException(ErrorCode.INVALID_INPUT,"班级信息没有变化");
    }

    private List<FutureSession> movableSessions(long classId) {
        List<FutureSession> future=jdbc.query("""
            SELECT s.id,s.teacher_id,s.starts_at,s.ends_at FROM core_session s
            WHERE s.class_id=? AND s.deleted=0 AND s.status='scheduled' ORDER BY s.id FOR UPDATE
            """,(rs,row)->new FutureSession(rs.getLong("id"),rs.getLong("teacher_id"),
                rs.getTimestamp("starts_at").toInstant(),rs.getTimestamp("ends_at").toInstant()),classId);
        List<FutureSession> movable=new ArrayList<>();
        for(FutureSession session:future) {
            if(session.startsAt().isBefore(Instant.now()))continue;
            Integer facts=jdbc.queryForObject("""
                SELECT (SELECT COUNT(id) FROM core_attendance WHERE session_id=? AND deleted=0)
                     +(SELECT COUNT(id) FROM core_consumption WHERE session_id=? AND deleted=0)
                     +(SELECT COUNT(id) FROM core_session_adjustment WHERE deleted=0 AND status IN ('active','completed')
                       AND (source_session_id=? OR target_session_id=?))
                """,Integer.class,session.id(),session.id(),session.id(),session.id());
            if(facts==null||facts==0)movable.add(session);
        }
        return movable;
    }

    private void validateNoSessionConflicts(ClassUpdateRow current,String room,long teacherId,List<FutureSession> movable) {
        for(FutureSession session:movable) {
            Integer conflict=jdbc.queryForObject("""
                SELECT COUNT(s.id) FROM core_session s JOIN core_class c ON c.id=s.class_id
                WHERE c.id<>? AND s.deleted=0 AND c.deleted=0 AND c.active=1
                  AND s.status NOT IN ('cancelled','canceled') AND s.starts_at<? AND s.ends_at>?
                  AND (s.teacher_id=? OR (COALESCE(s.campus_id,c.campus_id)=? AND COALESCE(s.room,c.room)=?))
                """,Integer.class,current.id(),Timestamp.from(session.endsAt()),Timestamp.from(session.startsAt()),
                teacherId,current.campusId(),room);
            if(conflict!=null&&conflict>0)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"老师或教室在该时段已有排课");
        }
    }

    private void persistClassUpdate(Actor actor,ClassUpdateRow current,ClassUpdateInput input,
            ClassUpdatePlan plan) {
        String name=plan.name(),room=plan.room();
        long teacherId=plan.teacherId();
        List<FutureSession> movable=plan.movable();
        int changed=jdbc.update("""
            UPDATE core_class SET name=?,room=?,teacher_id=?,capacity=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND active=1 AND deleted=0
            """,name,room,teacherId,input.capacity(),current.id(),input.expectedVersion());
        if(changed!=1)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"班级状态已变化");
        for(FutureSession session:movable)
            jdbc.update("UPDATE core_session SET teacher_id=?,campus_id=?,room=?,version=version+1,"
                + "updated_at=UTC_TIMESTAMP(3) WHERE id=?", teacherId, current.campusId(), room, session.id());
        jdbc.update("""
            INSERT INTO core_class_event
            (public_id,class_id,actor_id,event_type,from_name,to_name,from_room,to_room,
             from_teacher_id,to_teacher_id,from_capacity,to_capacity)
            VALUES (?,?,?,'class_updated',?,?,?,?,?,?,?,?)
            ""","CLSEVT_"+UUID.randomUUID(),current.id(),actor.id(),current.name(),name,current.room(),room,
            current.teacherId(),teacherId,current.capacity(),input.capacity());
    }
    /**
     * Checks whether the actor may create a course at the optional campus.
     * @param actor caller whose scope is checked
     * @param campusPublicId campus public identifier; omitted only for all-scope callers
     * @throws BusinessException when the requested campus is invalid or outside scope
     */
    public void authorizeCourseCampus(Actor actor, String campusPublicId) {
        if (campusPublicId == null || campusPublicId.isBlank()) {
            if (!actor.scope().equals("all")) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "校区范围账号创建课程时必须选择授权校区");
            }
            return;
        }
        campus(actor, campusPublicId);
    }
    /**
     * Rechecks campus, course, and active-teacher eligibility before creation or idempotent replay.
     * @param actor caller whose campus/teacher scope is checked
     * @param input requested class relationship identifiers
     * @throws BusinessException when the campus/course/teacher is unavailable or out of scope
     */
    public void authorizeClassCreation(Actor actor, ClassInput input) {
        long campusId = campus(actor, input.campusId());
        findCourse(input, campusId);
        findClassTeacher(actor, input, campusId);
    }
    /**
     * Creates a class and one conflict-checked UTC session for each active course lesson. Resource validation follows the campus lock; fees and schedules are persisted in the caller's transaction.
     * @param actor caller creating the class within campus/course/teacher scope
     * @param input class fields, capacity, duration, schedule, and optional fee rules
     * @return class receipt with version 1 and created session summaries
     * @throws BusinessException when links, capacity/resources, fees, or timetable conflicts are invalid
     */
    public ClassReceipt createClass(Actor actor, ClassInput input) {
        long campusId = campus(actor, input.campusId());
        CourseLink course = findCourse(input, campusId);
        long teacherId = findClassTeacher(actor, input, campusId);
        classSchedule.lockClassResources(campusId, teacherId, input.name());
        String room = resolveClassroomName(campusId, input.room());
        validateCourseCampusResources(campusId, course.id(), room, input.capacity());
        List<AcademicClassSchedulePersistence.LessonLink> lessons = classSchedule.courseLessons(course.id());
        List<AcademicClassSchedulePersistence.InstantRange> ranges = classSchedule.validateSchedule(
            input, lessons, teacherId, campusId, room);
        if (input.fees() != null && input.fees().refundLessonCount() != null
                && input.fees().refundLessonCount() > lessons.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"退款规则讲次数不能超过班级讲次数");
        }
        String classPublicId = "CLASS_" + UUID.randomUUID();
        long classId = classSchedule.insertClass(new AcademicClassSchedulePersistence.ClassInsertPlan(
            input, course.id(), campusId, teacherId, classPublicId, room));
        List<ClassSessionSummary> sessions = classSchedule.insertSessions(input, lessons, ranges, classId, teacherId);
        if (input.fees() != null) {
            var fee = input.fees();
            jdbc.update("""
                INSERT INTO core_class_fee(class_id,course_fee,material_fee,course_refund_rule,refund_lesson_count,material_refund_rule)
                VALUES(?,?,?,?,?,?)
                """,classId,fee.courseFee(),fee.materialFee(),fee.courseRefundRule(),fee.refundLessonCount(),fee.materialRefundRule());
        }
        return new ClassReceipt(classPublicId, input.courseId(), input.campusId(), input.teacherId(),
            input.name().trim(), room, input.capacity(), input.durationMinutes(), 1, sessions, input.fees(), null);
    }
    private String resolveClassroomName(long campusId,String requestedRoom) {
        if(requestedRoom!=null&&!requestedRoom.isBlank())return requestedRoom.trim();
        List<String> rooms=jdbc.query("SELECT DISTINCT room FROM core_class WHERE campus_id=? AND deleted=0",
            (rs,row)->rs.getString("room"),campusId);
        Set<String> existing=new HashSet<>(rooms);
        for(int number=1;number<=10000;number++) {
            String candidate="教室"+number;
            if(!existing.contains(candidate))return candidate;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT,"无法分配默认教室名称，请手动填写教室名称");
    }
    private CourseLink findCourse(ClassInput input, long campusId) {
        List<CourseLink> courses = jdbc.query("""
            SELECT id,campus_id FROM core_course
            WHERE public_id = ? AND active = 1 AND deleted = 0 FOR SHARE
            """, (rs, row) -> new CourseLink(rs.getLong("id"), rs.getObject("campus_id", Long.class)),
            input.courseId());
        if (courses.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "课程不存在或已停用"); }
        CourseLink course = courses.get(0);
        if (course.campusId() != null && course.campusId() != campusId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "课程不适用于所选校区");
        }
        return course;
    }
    private long findClassTeacher(Actor actor, ClassInput input, long campusId) {
        List<Long> teachers = jdbc.query("""
            SELECT id FROM core_principal WHERE public_id = ? AND active = 1 AND deleted = 0
            """, (rs, row) -> rs.getLong("id"), input.teacherId());
        if (teachers.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "老师账号不存在或已停用"); }
        long teacherId = teachers.get(0);
        if (actor.scope().equals("campus")) {
            Integer linked = jdbc.queryForObject("""
                SELECT COUNT(id) FROM core_principal_campus
                WHERE principal_id = ? AND campus_id = ? AND deleted = 0
                """, Integer.class, teacherId, campusId);
            if (linked == null || linked == 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "所选老师不属于当前授权校区");
            }
        }
        return teacherId;
    }
    private long campus(Actor actor, String campusPublicId) {
        List<Long> matches = jdbc.query("""
            SELECT id FROM core_campus WHERE public_id = ? AND active = 1 AND deleted = 0
            """, (rs, row) -> rs.getLong("id"), campusPublicId);
        if (matches.size() != 1) { throw new BusinessException(ErrorCode.NOT_FOUND, "校区不存在或已停用"); }
        long campusId = matches.get(0);
        if (!actor.scope().equals("all") && !actor.campusIds().contains(campusId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权在此校区创建班级");
        }
        return campusId;
    }
}
