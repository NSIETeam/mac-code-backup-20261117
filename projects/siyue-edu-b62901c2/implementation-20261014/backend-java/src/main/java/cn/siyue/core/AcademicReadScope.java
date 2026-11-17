package cn.siyue.core;

import java.util.ArrayList;
import java.util.List;

/** Personal teaching views always intersect the existing database authorization scope. */
final class AcademicReadScope {
    private AcademicReadScope() { }

    static String view(String value) {
        if (value == null) { return "scope"; }
        if (!value.equals("mine") && !value.equals("scope")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "查看范围应为 mine 或 scope");
        }
        return value;
    }

    static ScopeSql.Clause classes(Actor actor, String requestedView) {
        ScopeSql.Clause base = "own".equals(actor.scope()) && actor.permissions().contains("schedule.read")
            && !actor.permissions().contains("class.read")
            ? new ScopeSql.Clause("1 = 1", List.of()) : ScopeSql.classes(actor);
        if (!view(requestedView).equals("mine")) { return base; }
        List<Object> args = new ArrayList<>(base.args());
        args.add(actor.id()); args.add(actor.id());
        return new ScopeSql.Clause("(" + base.sql() + ") AND (c.teacher_id=? OR EXISTS "
            + "(SELECT mine_session.id FROM core_session mine_session WHERE mine_session.class_id=c.id "
            + "AND mine_session.teacher_id=? AND mine_session.deleted=0 "
            + "AND mine_session.status NOT IN ('cancelled','canceled')))", List.copyOf(args));
    }

    static ScopeSql.Clause students(Actor actor, AcademicDtos.StudentQuery query) {
        ScopeSql.Clause base = ScopeSql.students(actor, query.classId());
        if (!view(query.view()).equals("mine")) { return base; }
        ScopeSql.Clause classes = classes(actor, "mine");
        List<Object> args = new ArrayList<>(base.args());
        args.addAll(classes.args());
        String classFilter = "";
        if (query.classId() != null && !query.classId().isBlank()) {
            classFilter = " AND c.public_id=?"; args.add(query.classId());
        }
        return new ScopeSql.Clause("(" + base.sql() + ") AND EXISTS (SELECT e.id FROM core_effective_enrollment e "
            + "JOIN core_class c ON c.id=e.class_id AND c.active=1 AND c.deleted=0 "
            + "WHERE e.student_id=s.id AND e.status='active' AND e.deleted=0 AND ("
            + classes.sql() + ")" + classFilter + ")", List.copyOf(args));
    }

    /** Uses s for session and c for class; personal totals contain only that teacher's actual lessons. */
    static ScopeSql.Clause sessions(Actor actor, String requestedView) {
        if (view(requestedView).equals("mine")) {
            return new ScopeSql.Clause("s.teacher_id=?", List.of(actor.id()));
        }
        if (actor.scope().equals("assigned")) {
            return new ScopeSql.Clause("(s.teacher_id=? OR c.teacher_id=?)", List.of(actor.id(), actor.id()));
        }
        return new ScopeSql.Clause("1=1", List.of());
    }
}
