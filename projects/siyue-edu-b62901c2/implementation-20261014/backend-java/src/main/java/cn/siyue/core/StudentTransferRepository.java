package cn.siyue.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit current-placement persistence; immutable enrollment and money tables are never rewritten. */
@Repository
public class StudentTransferRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public StudentTransferRepository(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    public StudentTransferDtos.Options options(Actor actor,String studentId,int page,int size) {
        var scope=ScopeSql.classes(actor);
        var args=new ArrayList<Object>(List.of(studentId));args.addAll(scope.args());
        var enrollments=jdbc.query("""
            SELECT e.public_id,c.public_id class_public_id,c.name,e.version
            FROM core_effective_enrollment e JOIN core_class c ON c.id=e.class_id
            JOIN core_student st ON st.id=e.student_id
            WHERE st.public_id=? AND st.deleted=0 AND e.deleted=0 AND e.status='active' AND c.deleted=0 AND (
            """+scope.sql()+") ORDER BY e.id",(rs,row)->new StudentTransferDtos.Enrollment(rs.getString("public_id"),
            rs.getString("class_public_id"),rs.getString("name"),rs.getLong("version")),args.toArray());
        if(enrollments.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"学生没有可管理的有效报名");
        String eligible=eligibleTargetPredicate(enrollments);
        String predicate="c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0 AND ("+scope.sql()+") AND "+eligible;
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_class c JOIN core_course co ON co.id=c.course_id WHERE "+predicate,
            Long.class,optionArgs(scope,enrollments).toArray());
        args=new ArrayList<>(optionArgs(scope,enrollments));args.add(size);args.add(Math.multiplyExact((long)page-1,size));
        var targets=jdbc.query("""
            SELECT c.public_id,c.name,co.public_id course_public_id,co.name course_name,cp.public_id campus_public_id,c.capacity,
                (SELECT COUNT(*) FROM core_effective_enrollment e WHERE e.class_id=c.id AND e.status='active' AND e.deleted=0) enrolled_count
            FROM core_class c JOIN core_course co ON co.id=c.course_id JOIN core_campus cp ON cp.id=c.campus_id WHERE
            """+predicate+" ORDER BY c.id LIMIT ? OFFSET ?",(rs,row)->new StudentTransferDtos.Target(rs.getString("public_id"),
            rs.getString("name"),rs.getString("course_public_id"),rs.getString("course_name"),rs.getString("campus_public_id"),
            rs.getInt("capacity"),rs.getLong("enrolled_count")),args.toArray());
        return new StudentTransferDtos.Options(studentId,enrollments,targets,total==null?0:total,page,size);
    }
    private List<Object> optionArgs(ScopeSql.Clause scope,List<StudentTransferDtos.Enrollment> enrollments) {
        List<Object> args=new ArrayList<>(scope.args());enrollments.forEach(e->args.add(e.enrollmentId()));return args;
    }
    private String eligibleTargetPredicate(List<StudentTransferDtos.Enrollment> enrollments) {
        String placeholders=String.join(",",java.util.Collections.nCopies(enrollments.size(),"?"));
        return """
            EXISTS(SELECT e.id FROM core_effective_enrollment e JOIN core_class source ON source.id=e.class_id
                JOIN core_course original ON original.id=source.course_id JOIN core_crm_order o ON o.id=e.order_id
                LEFT JOIN core_class_fee tf ON tf.class_id=c.id
                WHERE e.public_id IN (
            """+placeholders+"""
                ) AND source.id<>c.id AND original.grade=co.grade AND original.subject=co.subject
                AND o.total_amount=COALESCE(tf.course_fee+tf.material_fee,co.price)
                AND NOT EXISTS(SELECT dup.id FROM core_effective_enrollment dup
                    WHERE dup.class_id=c.id AND dup.student_id=e.student_id AND dup.status='active' AND dup.deleted=0))
            AND (SELECT COUNT(*) FROM core_effective_enrollment occupied
                WHERE occupied.class_id=c.id AND occupied.status='active' AND occupied.deleted=0)<c.capacity
            """;
    }
    public void authorize(Actor actor,StudentTransferDtos.Input input) {
        target(actor,input.targetClassId());
        for(var item:input.items())enrollment(actor,item.enrollmentId());
    }
    public List<StudentTransferDtos.Result> transfer(Actor actor,StudentTransferDtos.Input input) {
        Target target=target(actor,input.targetClassId());
        var rows=input.items().stream().map(i->enrollment(actor,i.enrollmentId())).toList();
        var classIds=new java.util.TreeSet<Long>();classIds.add(target.id());rows.forEach(r->classIds.add(r.classId()));
        for(Long id:classIds)jdbc.queryForObject("SELECT id FROM core_class WHERE id=? FOR UPDATE",Long.class,id);
        target=target(actor,input.targetClassId());
        List<Enrollment> locked=new ArrayList<>();
        for(var row:rows.stream().sorted(Comparator.comparingLong(Enrollment::id)).toList()) {
            jdbc.queryForObject("SELECT id FROM core_enrollment WHERE id=? FOR UPDATE",Long.class,row.id());
            jdbc.queryForObject("SELECT id FROM core_student WHERE id=? AND deleted=0 FOR UPDATE",Long.class,row.studentId());
            locked.add(enrollment(actor,row.publicId()));
        }
        validateCapacity(target,locked);
        List<StudentTransferDtos.Result> results=new ArrayList<>();
        for(var row:locked) {
            long expected=input.items().stream().filter(i->i.enrollmentId().equals(row.publicId())).findFirst().orElseThrow().expectedVersion();
            validate(row,target,expected);
            results.add(move(actor,row,target,input.reason()));
        }
        return results;
    }
    private Enrollment enrollment(Actor actor,String publicId) {
        var scope=ScopeSql.classes(actor);var args=new ArrayList<Object>(List.of(publicId));args.addAll(scope.args());
        var rows=jdbc.query("""
            SELECT e.id,e.public_id,e.student_id,st.public_id student_public_id,e.class_id,c.public_id class_public_id,
                e.order_id,e.version,co.grade,co.subject,o.total_amount price,co.id course_id
            FROM core_effective_enrollment e JOIN core_student st ON st.id=e.student_id
            JOIN core_class c ON c.id=e.class_id JOIN core_course co ON co.id=c.course_id
            LEFT JOIN core_crm_order o ON o.id=e.order_id AND o.deleted=0
            WHERE e.public_id=? AND e.status='active' AND e.deleted=0 AND st.deleted=0 AND c.deleted=0 AND (
            """+scope.sql()+")",(rs,row)->new Enrollment(rs.getLong("id"),rs.getString("public_id"),rs.getLong("student_id"),
            rs.getString("student_public_id"),rs.getLong("class_id"),rs.getString("class_public_id"),
            rs.getObject("order_id",Long.class),rs.getLong("version"),rs.getString("grade"),rs.getString("subject"),
            rs.getBigDecimal("price"),rs.getLong("course_id")),args.toArray());
        if(rows.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"报名不在可转班范围");
        return rows.get(0);
    }
    private Target target(Actor actor,String publicId) {
        var scope=ScopeSql.classes(actor);var args=new ArrayList<Object>(List.of(publicId));args.addAll(scope.args());
        var rows=jdbc.query("""
            SELECT c.id,c.public_id,c.course_id,c.capacity,co.grade,co.subject,COALESCE(f.course_fee+f.material_fee,co.price) price
            FROM core_class c JOIN core_course co ON co.id=c.course_id
            LEFT JOIN core_class_fee f ON f.class_id=c.id
            WHERE c.public_id=? AND c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0 AND (
            """+scope.sql()+")",(rs,row)->new Target(rs.getLong("id"),rs.getString("public_id"),rs.getLong("course_id"),
            rs.getInt("capacity"),rs.getString("grade"),rs.getString("subject"),rs.getBigDecimal("price")),args.toArray());
        if(rows.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"目标班级无效或不在授权范围");
        return rows.get(0);
    }
    private void validateCapacity(Target target,List<Enrollment> rows) {
        if(rows.stream().map(Enrollment::studentId).distinct().count()!=rows.size())
            throw new BusinessException(ErrorCode.INVALID_INPUT,"同一学生不能批量转入多个报名");
        // The class row serializes transfers into this class. Use a locking/current read
        // for the occupancy check: an earlier authorization SELECT may have established
        // a REPEATABLE READ snapshot before this transaction waited for that class lock.
        Long count=jdbc.queryForObject("SELECT COUNT(id) FROM core_effective_enrollment WHERE class_id=? AND status='active' AND deleted=0 FOR UPDATE",
            Long.class,target.id());
        if(count==null||count+rows.size()>target.capacity())
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,"目标班级容量不足，整批未转班");
    }
    private void validate(Enrollment row,Target target,long expected) {
        if(row.version()!=expected)throw new BusinessException(ErrorCode.PREVIEW_STALE,"报名已转班，请刷新后重试");
        if(row.classId()==target.id())throw new BusinessException(ErrorCode.INVALID_INPUT,"目标班级与当前班级相同");
        if(!java.util.Objects.equals(row.grade(),target.grade())||!java.util.Objects.equals(row.subject(),target.subject()))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"转班要求相同年级和学科");
        if(row.price()==null||row.price().compareTo(target.price())!=0)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"课程价格不同，请先办理差额收费方案");
        if(row.orderId()==null)throw new BusinessException(ErrorCode.INVALID_INPUT,"该报名缺少已付款课时权益，不能转班");
        jdbc.queryForObject("SELECT id FROM core_crm_order WHERE id=? AND deleted=0 FOR UPDATE",Long.class,row.orderId());
        requireNoDuplicate(row,target);
        requireLessonMatch(row,target);
        requireNoConflict(row,target);
    }
    private void requireNoDuplicate(Enrollment row,Target target) {
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM core_effective_enrollment WHERE class_id=? AND student_id=? AND status='active' AND deleted=0",
            Long.class,target.id(),row.studentId());
        if(count!=null&&count>0)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"学生已在目标班级报名");
    }
    private void requireLessonMatch(Enrollment row,Target target) {
        Long purchased=jdbc.queryForObject("SELECT COUNT(*) FROM core_enrollment_lesson WHERE enrollment_id=? AND deleted=0",Long.class,row.id());
        Long matched=jdbc.queryForObject("""
            SELECT COUNT(*) FROM core_enrollment_lesson el JOIN core_lesson l ON l.sequence_no=el.sequence_no AND l.course_id=? AND l.deleted=0
            WHERE el.enrollment_id=? AND el.deleted=0
            """,Long.class,target.courseId(),row.id());
        Long targetCount=jdbc.queryForObject("SELECT COUNT(*) FROM core_lesson WHERE course_id=? AND deleted=0",Long.class,target.courseId());
        if(purchased==null||purchased==0||!purchased.equals(matched)||!purchased.equals(targetCount))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"目标讲次与原报名权益不一致");
        Long missing=jdbc.queryForObject("""
            SELECT COUNT(*) FROM core_enrollment_lesson el
            WHERE el.enrollment_id=? AND el.deleted=0
              AND NOT EXISTS(SELECT x.entitlement_lesson_id FROM core_consumption x
                WHERE x.entitlement_lesson_id=el.id AND x.deleted=0 GROUP BY x.entitlement_lesson_id HAVING SUM(x.units)>0)
              AND NOT EXISTS(SELECT ss.id FROM core_session ss JOIN core_lesson l ON l.id=ss.lesson_id
                WHERE ss.class_id=? AND l.sequence_no=el.sequence_no AND ss.starts_at>UTC_TIMESTAMP(3)
                  AND ss.deleted=0 AND ss.status NOT IN ('cancelled','canceled'))
            """,Long.class,row.id(),target.id());
        if(missing!=null&&missing>0)throw new BusinessException(ErrorCode.INVALID_INPUT,"目标班缺少尚未消费权益对应的未来课次");
    }
    private void requireNoConflict(Enrollment row,Target target) {
        Long conflicts=jdbc.queryForObject("""
            SELECT COUNT(*) FROM core_session target
            JOIN core_lesson l ON l.id=target.lesson_id
            JOIN core_enrollment_lesson el ON el.enrollment_id=? AND el.sequence_no=l.sequence_no AND el.deleted=0
            JOIN core_session_enrollment other ON other.student_id=? AND other.id<>? AND other.status='active'
            JOIN core_session ss ON ss.id=other.session_id AND ss.deleted=0 AND ss.status NOT IN ('cancelled','canceled')
            WHERE target.class_id=? AND target.starts_at>=UTC_TIMESTAMP(3) AND target.deleted=0
              AND target.status NOT IN ('cancelled','canceled') AND target.starts_at<ss.ends_at AND target.ends_at>ss.starts_at
              AND NOT EXISTS(SELECT x.entitlement_lesson_id FROM core_consumption x WHERE x.entitlement_lesson_id=el.id
                AND x.deleted=0 GROUP BY x.entitlement_lesson_id HAVING SUM(x.units)>0)
            """,Long.class,row.id(),row.studentId(),row.id(),target.id());
        if(conflicts!=null&&conflicts>0)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"目标课次与学生已有安排冲突");
    }
    private StudentTransferDtos.Result move(Actor actor,Enrollment row,Target target,String reason) {
        String publicId="TRF_"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_enrollment_transfer(public_id,enrollment_id,from_class_id,target_class_id,previous_version,actor_id,reason_cipher)
            VALUES(?,?,?,?,?,?,?)
            """,publicId,row.id(),row.classId(),target.id(),row.version(),actor.id(),cipher.encrypt(reason));
        Long eventId=jdbc.queryForObject("SELECT id FROM core_enrollment_transfer WHERE public_id=?",Long.class,publicId);
        jdbc.update("""
            INSERT INTO core_enrollment_lesson_delivery(transfer_id,target_lesson_id,entitlement_lesson_id)
            SELECT ?,l.id,el.id FROM core_enrollment_lesson el JOIN core_lesson l ON l.sequence_no=el.sequence_no
            WHERE el.enrollment_id=? AND el.deleted=0 AND l.course_id=? AND l.deleted=0
            """,eventId,row.id(),target.courseId());
        jdbc.update("""
            INSERT INTO core_enrollment_placement(enrollment_id,class_id,transfer_id,version,effective_at)
            VALUES(?,?,?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE class_id=VALUES(class_id),transfer_id=VALUES(transfer_id),
                version=VALUES(version),effective_at=VALUES(effective_at)
            """,row.id(),target.id(),eventId,row.version()+1);
        return new StudentTransferDtos.Result(row.publicId(),row.studentPublicId(),row.classPublicId(),target.publicId(),row.version()+1,publicId);
    }
    private record Enrollment(long id,String publicId,long studentId,String studentPublicId,long classId,String classPublicId,
            Long orderId,long version,String grade,String subject,BigDecimal price,long courseId) { }
    private record Target(long id,String publicId,long courseId,int capacity,String grade,String subject,BigDecimal price) { }
}
