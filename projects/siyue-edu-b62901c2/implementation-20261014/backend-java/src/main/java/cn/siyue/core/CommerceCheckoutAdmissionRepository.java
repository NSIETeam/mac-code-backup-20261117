package cn.siyue.core;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Admission checks shared by checkout quote and reservation paths. */
final class CommerceCheckoutAdmissionRepository {
    private final JdbcTemplate jdbc;
    private final SeatAvailabilityRepository seats;
    CommerceCheckoutAdmissionRepository(JdbcTemplate jdbc,PiiCipher cipher) {
        this.jdbc=jdbc;this.seats=new SeatAvailabilityRepository(jdbc,cipher);
    }
    void requireQuoteCapacity(List<CommerceCheckoutRepository.Line> lines) {
        Map<Long,Long> quantities=lines.stream().collect(java.util.stream.Collectors.groupingBy(
            line->line.item().classId(),java.util.stream.Collectors.counting()));
        for(Map.Entry<Long,Long> entry:quantities.entrySet()) {
            if(seats.remaining(entry.getKey())<entry.getValue()) throw full();
        }
    }
    void requireStudentCapacity(long studentId,long classId) {
        Integer already=jdbc.queryForObject("SELECT COUNT(id) FROM core_effective_enrollment WHERE class_id=? "
            + "AND student_id=? AND status IN ('active','pending') AND deleted=0",Integer.class,classId,studentId);
        if(already!=null&&already>0) throw new BusinessException(ErrorCode.ALREADY_EXISTS,"学生已在该班级有效报名");
        if(seats.remaining(classId)<1) throw full();
        List<Long> conflicts=jdbc.query("SELECT DISTINCT existing.id FROM core_session target JOIN core_session existing "
            + "ON existing.starts_at<target.ends_at AND existing.ends_at>target.starts_at "
            + "WHERE target.class_id=? AND target.starts_at>=UTC_TIMESTAMP(3) AND target.deleted=0 "
            + "AND target.status='scheduled' AND existing.id<>target.id AND existing.deleted=0 AND existing.status='scheduled'",
            (rs,n)->rs.getLong(1),classId);
        for(Long sessionId:conflicts) {
            if(seats.roster(sessionId).contains(studentId))
                throw new BusinessException(ErrorCode.ALREADY_EXISTS,"学生当前报名存在未来课次时间冲突");
        }
    }
    void lockClasses(List<CommerceCheckoutRepository.Line> lines) {
        List<Long> classes=lines.stream().map(line->line.item().classId()).distinct().sorted().toList();
        java.util.Set<Long> campuses=new java.util.TreeSet<>();
        for(Long classId:classes) {
            campuses.addAll(jdbc.query("SELECT campus_id FROM core_class WHERE id=? UNION "
                + "SELECT COALESCE(s.campus_id,c.campus_id) FROM core_session s JOIN core_class c ON c.id=s.class_id "
                + "WHERE s.class_id=? AND s.deleted=0",(rs,n)->rs.getLong(1),classId,classId));
        }
        for(Long campus:campuses) jdbc.queryForObject("SELECT id FROM core_campus WHERE id=? FOR UPDATE",Long.class,campus);
        for(Long classId:classes) {
            jdbc.queryForObject("SELECT id FROM core_class WHERE id=? AND deleted=0 AND active=1 FOR UPDATE",Long.class,classId);
            jdbc.query("SELECT id FROM core_session WHERE class_id=? AND deleted=0 ORDER BY id FOR UPDATE",(rs,n)->rs.getLong(1),classId);
        }
    }
    private BusinessException full() { return new BusinessException(ErrorCode.ALREADY_EXISTS,"班级或未来课次剩余容量不足"); }
}
