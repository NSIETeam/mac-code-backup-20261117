package cn.siyue.core;

import cn.siyue.core.AcademicDtos.StudentScheduleItem;
import cn.siyue.core.AcademicDtos.StudentSchedulePage;
import cn.siyue.core.AcademicDtos.StudentScheduleQuery;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads a bounded, scope-filtered projection of effective student lesson schedules. */
@Repository
class AcademicStudentScheduleRepository {
    private record QueryPlan(String rowsSql, List<Object> args, StudentScheduleQuery query) { }

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    AcademicStudentScheduleRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    StudentSchedulePage list(Actor actor, StudentScheduleQuery query) {
        QueryPlan plan = plan(actor, normalize(query));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + plan.rowsSql()
            + ") matching WHERE identity_rank=1", Long.class, plan.args().toArray());
        List<Object> pageArgs = new ArrayList<>(plan.args());
        pageArgs.add(plan.query().size());
        pageArgs.add(Math.multiplyExact((long) plan.query().page() - 1, plan.query().size()));
        List<StudentScheduleItem> items = jdbc.query("SELECT student_public_id,name_cipher,class_public_id,class_name,"
            + "course_name,campus_public_id,session_room,teacher_public_id,session_public_id,lesson_title,sequence_no,"
            + "starts_at,ends_at,session_status,version,attendance_status,adjustment_kind,student_id,session_id "
            + "FROM (" + plan.rowsSql()
            + ") matching WHERE identity_rank=1 ORDER BY starts_at,student_id,session_id LIMIT ? OFFSET ?",
            (rs, row) -> new StudentScheduleItem(rs.getString("student_public_id"),
                cipher.decrypt(rs.getString("name_cipher")), rs.getString("class_public_id"),
                rs.getString("class_name"), rs.getString("course_name"), rs.getString("campus_public_id"),
                rs.getString("session_room"), rs.getString("teacher_public_id"), rs.getString("session_public_id"),
                rs.getString("lesson_title"), rs.getInt("sequence_no"), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(), rs.getString("session_status"), rs.getLong("version"),
                rs.getString("attendance_status"), rs.getString("adjustment_kind")), pageArgs.toArray());
        return new StudentSchedulePage(List.copyOf(items), total == null ? 0 : total,
            plan.query().page(), plan.query().size());
    }

    private QueryPlan plan(Actor actor, StudentScheduleQuery query) {
        ScopeSql.Clause classes = AcademicReadScope.classes(actor, query.view());
        ScopeSql.Clause students = ScopeSql.studentReads(actor);
        List<Object> args = new ArrayList<>();
        String membership = membership(query, args);
        String from = " FROM " + membership + " membership"
            + " JOIN core_student s ON s.id=membership.student_id AND s.deleted=0"
            + " JOIN core_session ss ON ss.id=membership.session_id AND ss.deleted=0"
            + " JOIN core_class c ON c.id=ss.class_id AND c.deleted=0 AND c.active=1"
            + " JOIN core_lesson l ON l.id=ss.lesson_id AND l.deleted=0"
            + " JOIN core_principal p ON p.id=ss.teacher_id AND p.deleted=0"
            + " LEFT JOIN core_course co ON co.id=c.course_id AND co.deleted=0"
            + " LEFT JOIN core_campus cp ON cp.id=COALESCE(ss.campus_id,c.campus_id) AND cp.deleted=0"
            + " LEFT JOIN core_attendance att ON att.enrollment_id=membership.enrollment_id"
            + " AND att.session_id=ss.id AND att.deleted=0";
        String where = " WHERE ss.status NOT IN ('cancelled','canceled')"
            + " AND (" + classes.sql() + ") AND (" + students.sql() + ")"
            + optionalFilters(query);
        args.addAll(classes.args());
        args.addAll(students.args());
        addFilterArgs(args, query);
        String rows = "SELECT s.id student_id,s.public_id student_public_id,s.name_cipher,"
            + "c.public_id class_public_id,c.name class_name,co.name course_name,cp.public_id campus_public_id,"
            + "COALESCE(ss.room,c.room) session_room,p.public_id teacher_public_id,ss.id session_id,"
            + "ss.public_id session_public_id,l.title lesson_title,l.sequence_no,ss.starts_at,ss.ends_at,"
            + "ss.status session_status,ss.version,att.status attendance_status,membership.adjustment_kind,"
            + "ROW_NUMBER() OVER (PARTITION BY s.id,ss.id ORDER BY membership.priority,"
            + "membership.enrollment_id,membership.adjustment_id) identity_rank" + from + where;
        return new QueryPlan(rows, List.copyOf(args), query);
    }

    private String membership(StudentScheduleQuery query, List<Object> args) {
        String studentFilter = query.studentId() == null ? "" : " AND student_filter.public_id=?";
        String regular = "SELECT se.student_id,se.id enrollment_id,se.session_id,"
            + "CAST(NULL AS CHAR(16)) adjustment_kind,CASE WHEN EXISTS (SELECT recorded.id "
            + "FROM core_attendance recorded WHERE recorded.enrollment_id=se.id "
            + "AND recorded.session_id=se.session_id AND recorded.deleted=0) THEN 1 ELSE 2 END priority,0 adjustment_id "
            + "FROM core_session_enrollment se "
            + "JOIN core_session regular_session ON regular_session.id=se.session_id AND regular_session.deleted=0 "
            + "JOIN core_student student_filter ON student_filter.id=se.student_id AND student_filter.deleted=0 "
            + "WHERE se.deleted=0 AND regular_session.starts_at<? AND regular_session.ends_at>?"
            + studentFilter
            + " AND NOT EXISTS (SELECT source_adjustment.id FROM core_session_adjustment source_adjustment"
            + " WHERE source_adjustment.enrollment_id=se.id AND source_adjustment.source_session_id=se.session_id"
            + " AND source_adjustment.status IN ('active','completed') AND source_adjustment.deleted=0)";
        args.add(Timestamp.from(query.untilAt()));
        args.add(Timestamp.from(query.fromAt()));
        if (query.studentId() != null) { args.add(query.studentId()); }
        String adjusted = "SELECT target_adjustment.student_id,target_adjustment.enrollment_id,"
            + "target_adjustment.target_session_id,target_adjustment.kind,0 priority,target_adjustment.id "
            + "FROM core_session_adjustment target_adjustment "
            + "JOIN core_session target_session ON target_session.id=target_adjustment.target_session_id "
            + "AND target_session.deleted=0 "
            + "JOIN core_enrollment target_enrollment ON target_enrollment.id=target_adjustment.enrollment_id "
            + "AND target_enrollment.status='active' AND target_enrollment.deleted=0 "
            + "JOIN core_student student_filter ON student_filter.id=target_adjustment.student_id "
            + "AND student_filter.deleted=0 "
            + "WHERE target_adjustment.target_session_id IS NOT NULL "
            + "AND target_adjustment.status IN ('active','completed') AND target_adjustment.deleted=0 "
            + "AND target_session.starts_at<? AND target_session.ends_at>?" + studentFilter;
        args.add(Timestamp.from(query.untilAt()));
        args.add(Timestamp.from(query.fromAt()));
        if (query.studentId() != null) { args.add(query.studentId()); }
        return "(SELECT DISTINCT roster.student_id,roster.enrollment_id,roster.session_id,"
            + "roster.adjustment_kind,roster.priority,roster.adjustment_id FROM (" + regular
            + " UNION ALL " + adjusted + ") roster)";
    }

    private String optionalFilters(StudentScheduleQuery query) {
        return (query.campusId() == null ? "" : " AND COALESCE(ss.campus_id,c.campus_id) IN "
            + "(SELECT selected_campus.id FROM core_campus selected_campus WHERE selected_campus.public_id=? "
            + "AND selected_campus.active=1 AND selected_campus.deleted=0)")
            + (query.teacherId() == null ? "" : " AND ss.teacher_id IN "
                + "(SELECT selected_teacher.id FROM core_principal selected_teacher "
                + "WHERE selected_teacher.public_id=? AND selected_teacher.active=1 AND selected_teacher.deleted=0)")
            + (query.room() == null ? "" : " AND COALESCE(ss.room,c.room)=?");
    }

    private void addFilterArgs(List<Object> args, StudentScheduleQuery query) {
        if (query.campusId() != null) { args.add(query.campusId()); }
        if (query.teacherId() != null) { args.add(query.teacherId()); }
        if (query.room() != null) { args.add(query.room()); }
    }

    private StudentScheduleQuery normalize(StudentScheduleQuery query) {
        if (query == null || query.fromAt() == null || query.untilAt() == null) {
            throw invalid("学生课表必须提供开始和结束时间");
        }
        Duration range = Duration.between(query.fromAt(), query.untilAt());
        if (range.isNegative() || range.isZero() || range.compareTo(Duration.ofDays(31)) > 0) {
            throw invalid("学生课表日期范围必须大于0且不超过31天");
        }
        int page = query.page() == null ? 1 : query.page();
        int size = query.size() == null ? 20 : query.size();
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw invalid("学生课表分页范围无效");
        }
        return new StudentScheduleQuery(AcademicReadScope.view(query.view()), optional(query.studentId()),
            optional(query.campusId()), optional(query.teacherId()), optional(query.room()), query.fromAt(),
            query.untilAt(), page, size);
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}
