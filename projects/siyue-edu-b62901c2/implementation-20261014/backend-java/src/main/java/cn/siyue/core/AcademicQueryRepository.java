package cn.siyue.core;

import cn.siyue.core.AcademicDtos.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded academic UI reads; every count and result uses the same authorized SQL predicate. */
final class AcademicQueryRepository {
    private record AttendanceSearchRow(AllStudentAttendance value,String rawPhone) { }
    private record ClassRow(long id, String publicId, String courseId, String campusId, String teacherId,
            String name, String room, int capacity, int durationMinutes, long version,ClassCatalog catalog) { }
    private static final String CLASS_SELECT = """
            SELECT c.id,c.public_id,co.public_id course_public_id,cp.public_id campus_public_id,
                   p.public_id teacher_public_id,c.name,c.room,c.capacity,c.duration_minutes,c.version,
                   co.name course_name,co.course_type,co.grade,co.subject,co.academic_year,co.term,co.class_type,co.course_kind,co.course_format,
                   (SELECT COUNT(DISTINCT e.student_id) FROM core_effective_enrollment e JOIN core_student st ON st.id=e.student_id
                       WHERE e.class_id=c.id AND e.deleted=0 AND st.deleted=0 AND e.status='active') enrolled_count,
                   (SELECT COUNT(DISTINCT e.student_id) FROM core_effective_enrollment e JOIN core_student st ON st.id=e.student_id
                       WHERE e.class_id=c.id AND e.deleted=0 AND st.deleted=0) registered_count,
                   (SELECT COUNT(*) FROM core_session s WHERE s.class_id=c.id AND s.deleted=0 AND s.status NOT IN ('cancelled','canceled')) session_count,
                   (SELECT COUNT(*) FROM core_session s WHERE s.class_id=c.id AND s.deleted=0 AND s.status NOT IN ('cancelled','canceled')
                       AND s.starts_at<=UTC_TIMESTAMP(3)) started_count,
                   (SELECT MIN(s.starts_at) FROM core_session s WHERE s.class_id=c.id AND s.deleted=0 AND s.status NOT IN ('cancelled','canceled')) first_starts_at,
                   (SELECT MAX(s.ends_at) FROM core_session s WHERE s.class_id=c.id AND s.deleted=0 AND s.status NOT IN ('cancelled','canceled')) last_ends_at
            FROM core_class c JOIN core_course co ON co.id=c.course_id
            JOIN core_campus cp ON cp.id=c.campus_id JOIN core_principal p ON p.id=c.teacher_id
            """;
    private static final String SESSION_COUNTS_SELECT="""
        ,(SELECT COUNT(DISTINCT e.student_id) FROM core_session_enrollment e JOIN core_student st ON st.id=e.student_id
            WHERE e.session_id=s.id AND e.class_id=s.class_id AND e.deleted=0 AND e.status='active' AND st.deleted=0) roster_count
        ,(SELECT COUNT(DISTINCT e.student_id) FROM core_session_enrollment e JOIN core_student st ON st.id=e.student_id
            WHERE e.session_id=s.id AND e.class_id=s.class_id AND e.deleted=0 AND e.status='active' AND st.deleted=0
            AND NOT EXISTS (SELECT a.id FROM core_attendance a WHERE a.session_id=s.id AND a.enrollment_id=e.id AND a.deleted=0)) unrecorded_count
        """;
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    AcademicQueryRepository(JdbcTemplate jdbc, PiiCipher cipher) { this.jdbc = jdbc; this.cipher = cipher; }

    ClassPage classes(Actor actor, ClassQuery query) {
        ScopeSql.Clause filter = classFilter(actor, query);
        Long total = jdbc.queryForObject("SELECT COUNT(c.id) FROM core_class c WHERE " + filter.sql(),
            Long.class, filter.args().toArray());

        Long enrolledTotal=jdbc.queryForObject("""
            SELECT COUNT(DISTINCT e.class_id,e.student_id) FROM core_effective_enrollment e
            JOIN core_class c ON c.id=e.class_id JOIN core_student st ON st.id=e.student_id
            WHERE e.deleted=0 AND e.status='active' AND st.deleted=0 AND (
            """+filter.sql()+")",Long.class,filter.args().toArray());
        List<Object> args = new ArrayList<>(filter.args());
        args.add(query.size()); args.add(Math.multiplyExact((long) query.page() - 1, query.size()));
        List<ClassRow> rows = jdbc.query(CLASS_SELECT + " WHERE " + filter.sql() + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new ClassRow(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("course_public_id"), rs.getString("campus_public_id"),
                rs.getString("teacher_public_id"), rs.getString("name"), rs.getString("room"),
                rs.getInt("capacity"), rs.getInt("duration_minutes"), rs.getLong("version"),classCatalog(rs)), args.toArray());
        List<ClassReceipt> items=classReceipts(actor,query,rows);
        return new ClassPage(items, total == null ? 0 : total, query.page(), query.size(),enrolledTotal==null ? 0 : enrolledTotal);
    }

    ClassExport exportClasses(Actor actor,ClassQuery query) {
        ScopeSql.Clause filter=classFilter(actor,query);
        List<ClassRow> rows=jdbc.query(CLASS_SELECT+" WHERE "+filter.sql()+" ORDER BY c.id DESC",
            (rs,row)->new ClassRow(rs.getLong("id"),rs.getString("public_id"),rs.getString("course_public_id"),
                rs.getString("campus_public_id"),rs.getString("teacher_public_id"),rs.getString("name"),rs.getString("room"),
                rs.getInt("capacity"),rs.getInt("duration_minutes"),rs.getLong("version"),classCatalog(rs)),filter.args().toArray());
        List<ClassReceipt> result=new ArrayList<>();
        for(int offset=0;offset<rows.size();offset+=200) {
            result.addAll(classReceipts(actor,query,rows.subList(offset,Math.min(offset+200,rows.size()))));
        }
        return new ClassExport(List.copyOf(result),result.size());
    }

    AllStudentAttendancePage allStudentAttendance(Actor actor,AllStudentAttendanceQuery query) {
        List<AllStudentAttendance> matching=allStudentAttendanceRows(actor,query);
        int page=query.page()==null ? 1 : query.page(),size=query.size()==null ? 20 : query.size();
        int start=Math.min(matching.size(),Math.multiplyExact(page-1,size));
        int end=Math.min(matching.size(),start+size);
        return new AllStudentAttendancePage(List.copyOf(matching.subList(start,end)),matching.size(),page,size);
    }

    AllStudentAttendanceExport exportAllStudentAttendance(Actor actor,AllStudentAttendanceQuery query) {
        List<AllStudentAttendance> rows=allStudentAttendanceRows(actor,query);
        return new AllStudentAttendanceExport(rows,rows.size());
    }

    /** Search encrypted names and phones only after SQL has narrowed to the authorized date and staff scope. */
    private List<AllStudentAttendance> allStudentAttendanceRows(Actor actor,AllStudentAttendanceQuery query) {
        validateAttendanceSearch(query);
        ScopeSql.Clause scope=ScopeSql.classes(actor);
        List<Object> args=new ArrayList<>();
        String sql="""
            SELECT c.public_id class_public_id,ss.public_id session_public_id,cp.public_id campus_public_id,
                   cp.name_cipher campus_name_cipher,co.name course_name,c.name class_name,ss.starts_at,
                   p.public_id teacher_public_id,st.public_id student_public_id,
                   st.name_cipher,st.phone_cipher,a.public_id attendance_public_id,a.status attendance_outcome
            FROM core_session ss JOIN core_class c ON c.id=ss.class_id AND c.deleted=0 AND c.active=1
            JOIN core_course co ON co.id=c.course_id AND co.deleted=0 AND co.course_type='onsite'
            JOIN core_campus cp ON cp.id=COALESCE(ss.campus_id,c.campus_id) AND cp.deleted=0 AND cp.active=1
            JOIN core_principal p ON p.id=ss.teacher_id
            JOIN core_session_enrollment e ON e.class_id=c.id AND e.session_id=ss.id AND e.deleted=0
            JOIN core_student st ON st.id=e.student_id AND st.deleted=0
            LEFT JOIN core_attendance a ON a.session_id=ss.id AND a.enrollment_id=e.id AND a.deleted=0
            WHERE ss.deleted=0 AND ss.status NOT IN ('cancelled','canceled')
              AND ss.starts_at>=? AND ss.starts_at<? AND (
            """+scope.sql()+")";
        args.add(Timestamp.from(query.fromAt()));args.add(Timestamp.from(query.untilAt()));args.addAll(scope.args());
        sql+=attendanceSearchFilters(actor,query,args);
        sql+=" AND (a.id IS NOT NULL OR e.status='active') ORDER BY ss.starts_at DESC,st.id";
        List<AttendanceSearchRow> rows=jdbc.query(sql,(rs,row)->{
            String phoneCipher=rs.getString("phone_cipher");
            String rawPhone=phoneCipher==null?null:cipher.decrypt(phoneCipher);
            return new AttendanceSearchRow(new AllStudentAttendance(rs.getString("class_public_id"),
                rs.getString("session_public_id"),rs.getString("campus_public_id"),cipher.decrypt(rs.getString("campus_name_cipher")),
                rs.getString("course_name"),rs.getString("class_name"),rs.getTimestamp("starts_at").toInstant(),
                rs.getString("teacher_public_id"),rs.getString("student_public_id"),
                cipher.decrypt(rs.getString("name_cipher")),cipher.maskedPhone(phoneCipher),
                rs.getString("attendance_public_id")==null?"unrecorded":"recorded",rs.getString("attendance_outcome")),rawPhone);
        },args.toArray());
        String search=query.search()==null?"":query.search().trim().toLowerCase(java.util.Locale.ROOT);
        return rows.stream().filter(row->matchesAttendanceSearch(row,search)).map(AttendanceSearchRow::value).toList();
    }
    private void validateAttendanceSearch(AllStudentAttendanceQuery query) {
        if(query.fromAt()==null||query.untilAt()==null||!query.fromAt().isBefore(query.untilAt())
                ||Duration.between(query.fromAt(),query.untilAt()).compareTo(Duration.ofDays(31))>0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"请提供有效日期范围，最多查询31天");
        }
        if(query.courseType()!=null&&!query.courseType().equals("onsite")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"全部学生考勤仅查询面授课程");
        }
    }
    private String attendanceSearchFilters(Actor actor,AllStudentAttendanceQuery query,List<Object> args) {
        String sql="";
        if(actor.scope().equals("assigned")) {
            sql+=" AND (ss.teacher_id=? OR c.teacher_id=?)";args.add(actor.id());args.add(actor.id());
        }
        if(query.campusId()!=null&&!query.campusId().isBlank()) { sql+=" AND cp.public_id=?";args.add(query.campusId()); }
        if(query.teacherId()!=null&&!query.teacherId().isBlank()) { sql+=" AND p.public_id=?";args.add(query.teacherId()); }
        if("recorded".equals(query.attendanceStatus()))sql+=" AND a.id IS NOT NULL";
        if("unrecorded".equals(query.attendanceStatus()))sql+=" AND a.id IS NULL";
        return sql;
    }
    private boolean matchesAttendanceSearch(AttendanceSearchRow row,String search) {
        return search.isEmpty()||row.value().studentName().toLowerCase(java.util.Locale.ROOT).contains(search)
            ||row.value().studentId().toLowerCase(java.util.Locale.ROOT).contains(search)
            ||row.rawPhone()!=null&&row.rawPhone().contains(search);
    }
    private List<ClassReceipt> classReceipts(Actor actor,ClassQuery query,List<ClassRow> rows) {
        Map<Long, List<ClassSessionSummary>> schedules = schedules(actor, query, rows);
        Map<Long,ClassFees> fees = new java.util.HashMap<>();
        if (!rows.isEmpty()) {
            String placeholders = String.join(",",java.util.Collections.nCopies(rows.size(),"?"));
            jdbc.query("SELECT class_id,course_fee,material_fee,course_refund_rule,refund_lesson_count,material_refund_rule FROM core_class_fee WHERE class_id IN (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> fees.put(rs.getLong("class_id"),new ClassFees(
                    rs.getBigDecimal("course_fee"),rs.getBigDecimal("material_fee"),rs.getString("course_refund_rule"),
                    rs.getObject("refund_lesson_count",Integer.class),rs.getString("material_refund_rule"))),
                rows.stream().map(ClassRow::id).toArray());
        }
        return rows.stream().map(row -> new ClassReceipt(row.publicId(), row.courseId(),
            row.campusId(), row.teacherId(), row.name(), row.room(), row.capacity(), row.durationMinutes(),
            row.version(), List.copyOf(schedules.getOrDefault(row.id(), List.of())),
            actor.permissions().contains("class.read") ? fees.get(row.id()) : null, row.catalog())).toList();
    }
    ClassDetail classDetail(Actor actor,String publicId) {
        ClassQuery query=new ClassQuery("scope",null,null,null,null,null,1,1,null,null,null,null,null,null,null,null,null,null);
        ScopeSql.Clause filter=classFilter(actor,query);
        List<Object> args=new ArrayList<>(filter.args());args.add(publicId);
        List<ClassRow> rows=jdbc.query(CLASS_SELECT+" WHERE "+filter.sql()+" AND c.public_id=?",
            (rs,row)->new ClassRow(rs.getLong("id"),rs.getString("public_id"),rs.getString("course_public_id"),rs.getString("campus_public_id"),
                rs.getString("teacher_public_id"),rs.getString("name"),rs.getString("room"),rs.getInt("capacity"),rs.getInt("duration_minutes"),rs.getLong("version"),classCatalog(rs)),args.toArray());
        if(rows.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"班级不存在或不在当前授权范围");
        ClassReceipt info=classReceipts(actor,query,rows).get(0);
        ScopeSql.Clause sessions=sessionFilter(actor,query);
        List<Object> lessonArgs=new ArrayList<>(List.of(rows.get(0).id()));lessonArgs.addAll(sessions.args());
        List<ClassLesson> lessons=jdbc.query("""
            SELECT s.public_id,l.public_id lesson_public_id,p.public_id teacher_public_id,s.starts_at,s.ends_at,s.version,
                   l.sequence_no,l.title,cp.public_id session_campus_public_id,COALESCE(s.room,c.room) session_room
            """+SESSION_COUNTS_SELECT+"""
            FROM core_session s JOIN core_class c ON c.id=s.class_id JOIN core_lesson l ON l.id=s.lesson_id
            JOIN core_principal p ON p.id=s.teacher_id
            LEFT JOIN core_campus cp ON cp.id=COALESCE(s.campus_id,c.campus_id) WHERE s.class_id=? AND
            """+sessions.sql()+" ORDER BY l.sequence_no,s.id",(rs,row)->new ClassLesson(new ClassSessionSummary(rs.getString("public_id"),
                rs.getString("lesson_public_id"),rs.getString("teacher_public_id"),
                new AcademicDtos.SessionSchedule(rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("ends_at").toInstant(),rs.getLong("version"),attendanceCounts(actor,rs)),
                new AcademicDtos.SessionLocation(rs.getString("session_campus_public_id"),rs.getString("session_room"))),rs.getInt("sequence_no"),
                rs.getString("title")),lessonArgs.toArray());
        return new ClassDetail(info,lessons);
    }
    private SessionAttendanceCounts attendanceCounts(Actor actor,java.sql.ResultSet rs) throws java.sql.SQLException {
        if(!actor.permissions().contains("attendance.read"))return null;
        return new SessionAttendanceCounts(rs.getLong("roster_count"),rs.getLong("unrecorded_count"));
    }
    private ClassCatalog classCatalog(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer year=rs.getObject("academic_year",Integer.class);
        CourseMetadata metadata=year==null ? null : new CourseMetadata(year,rs.getString("term"),rs.getString("class_type"),
            rs.getString("course_kind"),rs.getString("course_format"));
        Timestamp start=rs.getTimestamp("first_starts_at"),end=rs.getTimestamp("last_ends_at");
        return new ClassCatalog(rs.getString("course_name"),rs.getString("course_type"),rs.getString("grade"),rs.getString("subject"),metadata,
            rs.getLong("enrolled_count"),rs.getLong("registered_count"),rs.getLong("session_count"),rs.getLong("started_count"),
            start==null ? null : start.toInstant(),end==null ? null : end.toInstant());
    }
    private ScopeSql.Clause classFilter(Actor actor, ClassQuery query) {
        ScopeSql.Clause scope = AcademicReadScope.classes(actor, query.view());
        String sql = "c.deleted=0 AND c.active=1 AND (" + scope.sql() + ")";
        List<Object> args = new ArrayList<>(scope.args());
        if (present(query.campusId())) {
            sql += " AND c.campus_id IN (SELECT cp.id FROM core_campus cp WHERE cp.public_id=? AND cp.deleted=0 AND cp.active=1)";
            args.add(query.campusId());
        }
        if (present(query.room())) { sql += " AND c.room=?"; args.add(query.room()); }
        sql+=directoryFilters(query,args);
        if (present(query.teacherId()) || query.fromAt() != null) {
            ScopeSql.Clause sessions = sessionFilter(actor, query);
            sql += " AND EXISTS (SELECT s.id FROM core_session s WHERE s.class_id=c.id AND " + sessions.sql() + ")";
            args.addAll(sessions.args());
        }
        return new ScopeSql.Clause(sql, List.copyOf(args));
    }

    private String directoryFilters(ClassQuery query,List<Object> args) {
        StringBuilder sql=new StringBuilder();
        if(present(query.search())) {
            String value=query.search().trim(),like="%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            sql.append(" AND (c.public_id=? OR c.name LIKE ? ESCAPE '!' OR c.course_id IN (SELECT co.id FROM core_course co WHERE co.name LIKE ? ESCAPE '!'))");
            args.add(value);args.add(like);args.add(like);
        }
        String course=courseDirectoryFilters(query,args);
        if(!course.isEmpty())sql.append(" AND c.course_id IN (SELECT co.id FROM core_course co WHERE 1=1").append(course).append(")");
        sql.append(classPhaseFilter(query.phase()));return sql.toString();
    }
    private String courseDirectoryFilters(ClassQuery query,List<Object> args) {
        StringBuilder sql=new StringBuilder();
        String[][] filters={{"course_type",query.courseType()},{"term",query.term()},{"subject",query.subject()},
            {"grade",query.grade()},{"class_type",query.classType()},{"course_kind",query.courseKind()}};
        for(String[] filter:filters) if(present(filter[1])) { sql.append(" AND co.").append(filter[0]).append("=?");args.add(filter[1].trim()); }
        if(query.year()!=null) { sql.append(" AND co.academic_year=?");args.add(query.year()); }
        if("group".equals(query.format()))sql.append(" AND co.course_format IN ('face','intention')");
        else if(present(query.format())) { sql.append(" AND co.course_format=?");args.add(query.format()); }
        return sql.toString();
    }
    private String classPhaseFilter(String phase) {
        String sessions="SELECT s.id FROM core_session s WHERE s.class_id=c.id AND s.deleted=0 AND s.status NOT IN ('cancelled','canceled')";
        if(!present(phase))return "";
        return switch(phase) {
            case "open" -> " AND EXISTS ("+sessions+" AND s.ends_at>UTC_TIMESTAMP(3))";
            case "upcoming" -> " AND EXISTS ("+sessions+") AND NOT EXISTS ("+sessions+" AND s.starts_at<=UTC_TIMESTAMP(3))";
            case "current" -> " AND EXISTS ("+sessions+" AND s.starts_at<=UTC_TIMESTAMP(3)) AND EXISTS ("+sessions+" AND s.ends_at>UTC_TIMESTAMP(3))";
            case "finished" -> " AND EXISTS ("+sessions+") AND NOT EXISTS ("+sessions+" AND s.ends_at>UTC_TIMESTAMP(3))";
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT,"班级状态筛选无效");
        };
    }
    private ScopeSql.Clause sessionFilter(Actor actor, ClassQuery query) {
        ScopeSql.Clause scope = AcademicReadScope.sessions(actor, query.view());
        String sql = "s.deleted=0 AND s.status NOT IN ('cancelled','canceled') AND (" + scope.sql() + ")";
        List<Object> args = new ArrayList<>(scope.args());
        if (present(query.teacherId())) {
            sql += " AND s.teacher_id IN (SELECT p.id FROM core_principal p WHERE p.public_id=? AND p.deleted=0 AND p.active=1)";
            args.add(query.teacherId());
        }
        if (query.fromAt() != null) {
            sql += " AND s.starts_at<? AND s.ends_at>?";
            args.add(Timestamp.from(query.untilAt())); args.add(Timestamp.from(query.fromAt()));
        }
        return new ScopeSql.Clause(sql, List.copyOf(args));
    }

    private Map<Long, List<ClassSessionSummary>> schedules(Actor actor, ClassQuery query, List<ClassRow> rows) {
        Map<Long, List<ClassSessionSummary>> result = new java.util.HashMap<>();
        if (rows.isEmpty()) { return result; }
        String placeholders = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        List<Object> args = new ArrayList<>(rows.stream().map(row -> (Object) row.id()).toList());
        ScopeSql.Clause scope = sessionFilter(actor, query);
        args.addAll(scope.args());
        jdbc.query("""
            SELECT s.class_id,s.public_id,l.public_id lesson_public_id,p.public_id teacher_public_id,
                   s.starts_at,s.ends_at,s.version,cp.public_id session_campus_public_id,
                   COALESCE(s.room,c.room) session_room
            """+SESSION_COUNTS_SELECT+"""
            FROM core_session s JOIN core_lesson l ON l.id=s.lesson_id
            JOIN core_principal p ON p.id=s.teacher_id JOIN core_class c ON c.id=s.class_id
            LEFT JOIN core_campus cp ON cp.id=COALESCE(s.campus_id,c.campus_id)
            WHERE s.class_id IN (""" + placeholders + ") AND " + scope.sql() + " ORDER BY s.class_id,s.starts_at,s.id",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
                .computeIfAbsent(rs.getLong("class_id"), ignored -> new ArrayList<>())
                .add(new ClassSessionSummary(rs.getString("public_id"), rs.getString("lesson_public_id"),
                    rs.getString("teacher_public_id"), new AcademicDtos.SessionSchedule(
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                        rs.getLong("version"), attendanceCounts(actor,rs)), new AcademicDtos.SessionLocation(
                            rs.getString("session_campus_public_id"),rs.getString("session_room")))), args.toArray());
        return result;
    }

    StudentClassPage studentClasses(Actor actor, String studentId, StudentQuery query) {
        ScopeSql.Clause scope = AcademicReadScope.classes(actor, query.view());
        List<Object> args = new ArrayList<>(scope.args()); args.add(studentId);
        String where = "c.active=1 AND c.deleted=0 AND (" + scope.sql() + ") AND EXISTS "
            + "(SELECT e.id FROM core_effective_enrollment e JOIN core_student st ON st.id=e.student_id AND st.deleted=0 "
            + "WHERE e.class_id=c.id AND e.status='active' AND e.deleted=0 AND st.public_id=?)";
        Long total = jdbc.queryForObject("SELECT COUNT(c.id) FROM core_class c WHERE " + where, Long.class, args.toArray());
        args.add(query.size()); args.add(Math.multiplyExact((long) query.page() - 1, query.size()));
        List<StudentClass> items = jdbc.query("""
            SELECT c.public_id,c.name,cp.public_id campus_public_id,p.public_id teacher_public_id
            FROM core_class c JOIN core_campus cp ON cp.id=c.campus_id JOIN core_principal p ON p.id=c.teacher_id
            """ + " WHERE " + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new StudentClass(rs.getString("public_id"), rs.getString("name"),
                rs.getString("campus_public_id"), rs.getString("teacher_public_id")), args.toArray());
        return new StudentClassPage(items, total == null ? 0 : total, query.page(), query.size());
    }

    AcademicOptions options(Actor actor, String campusId) {
        ScopeSql.Clause scope = optionCampuses(actor, campusId);
        List<AcademicOption> campuses = jdbc.query("SELECT cp.public_id,cp.name_cipher FROM core_campus cp WHERE "
            + scope.sql() + " ORDER BY cp.id LIMIT 501", (rs, row) -> new AcademicOption(rs.getString("public_id"),
                cipher.decrypt(rs.getString("name_cipher"))), scope.args().toArray());
        List<AcademicOption> teachers = teachers(actor, scope);
        List<RoomOption> rooms = rooms(actor, campusId);
        if (campuses.size() > 500 || teachers.size() > 500 || rooms.size() > 500) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "选项超过 500 项，请先选择校区缩小范围");
        }
        return new AcademicOptions(List.copyOf(campuses), List.copyOf(teachers), List.copyOf(rooms));
    }

    private List<AcademicOption> teachers(Actor actor, ScopeSql.Clause campuses) {
        String visible = "";
        List<Object> args = new ArrayList<>(campuses.args());
        if (!actor.scope().equals("all") && !actor.scope().equals("campus")
                && !actor.permissions().contains("class.assign")) {
            visible = " AND p.id=?"; args.add(actor.id());
        }
        return jdbc.query("""
            SELECT p.public_id FROM core_principal p WHERE p.active=1 AND p.deleted=0 AND EXISTS (
              SELECT pc.id FROM core_principal_campus pc JOIN core_campus cp ON cp.id=pc.campus_id
              WHERE pc.principal_id=p.id AND pc.deleted=0 AND (""" + campuses.sql() + "))" + visible
            + " ORDER BY p.public_id LIMIT 501",
            (rs, row) -> new AcademicOption(rs.getString("public_id"), rs.getString("public_id")), args.toArray());
    }

    private List<RoomOption> rooms(Actor actor, String campusId) {
        ScopeSql.Clause scope = ScopeSql.classes(actor);
        List<Object> args = new ArrayList<>(scope.args());
        String filter = "";
        if (present(campusId)) { filter = " AND cp.public_id=?"; args.add(campusId); }
        return jdbc.query("""
            SELECT DISTINCT cp.public_id campus_public_id,c.room
            FROM core_class c JOIN core_campus cp ON cp.id=c.campus_id AND cp.active=1 AND cp.deleted=0
            WHERE c.active=1 AND c.deleted=0 AND (""" + scope.sql() + ")" + filter
            + " ORDER BY campus_public_id,c.room LIMIT 501",
            (rs, row) -> new RoomOption(rs.getString("campus_public_id"), rs.getString("room")), args.toArray());
    }

    private ScopeSql.Clause optionCampuses(Actor actor, String campusId) {
        String sql = "cp.active=1 AND cp.deleted=0";
        List<Object> args = new ArrayList<>();
        if (!actor.scope().equals("all")) {
            sql += " AND EXISTS (SELECT membership.id FROM core_principal_campus membership "
                + "WHERE membership.campus_id=cp.id AND membership.principal_id=? AND membership.deleted=0)";
            args.add(actor.id());
        }
        if (present(campusId)) { sql += " AND cp.public_id=?"; args.add(campusId); }
        return new ScopeSql.Clause(sql, List.copyOf(args));
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
