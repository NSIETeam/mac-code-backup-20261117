package cn.siyue.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Current attendance projection plus immutable correction and signed consumption events. */
@Repository
public class AttendanceCorrectionRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final AcademicRepository academic;
    private final Clock clock;
    @Autowired
    public AttendanceCorrectionRepository(JdbcTemplate jdbc,PiiCipher cipher,AcademicRepository academic) {
        this(jdbc,cipher,academic,Clock.systemUTC());
    }
    AttendanceCorrectionRepository(JdbcTemplate jdbc,PiiCipher cipher,AcademicRepository academic,Clock clock) {
        this.jdbc=jdbc;this.cipher=cipher;this.academic=academic;this.clock=clock;
    }
    public AcademicRepository.AttendanceMember authorize(Actor actor,AttendanceCorrectionDtos.Target target,String classId) {
        return academic.attendanceMember(actor,target.studentId(),classId,target.sessionId());
    }
    public AttendanceCorrectionDtos.Receipt correct(Actor actor,AcademicRepository.AttendanceMember member,
            AttendanceCorrectionDtos.Target target,AttendanceCorrectionDtos.Input input) {
        Current current=lockCurrent(member);
        requireOpenWindow(current.createdAt(),clock.instant());
        if(current.version()!=input.expectedVersion())
            throw new BusinessException(ErrorCode.PREVIEW_STALE,"考勤已更正，请刷新后重试");
        String correctionId="ATC_"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_attendance_correction(public_id,attendance_id,actor_id,previous_version,
                previous_status,status,previous_note_cipher,note_cipher,reason_cipher)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,correctionId,current.id(),actor.id(),current.version(),current.status(),input.status(),
            current.noteCipher(),encryptNote(input.note()),cipher.encrypt(input.reason()));
        reconcileConsumption(actor,member,current,new Change(correctionId,input.status()));
        int changed=jdbc.update("""
            UPDATE core_attendance SET status=?,note_cipher=?,actor_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND deleted=0
            """,input.status(),encryptNote(input.note()),actor.id(),current.id(),current.version());
        if(changed!=1)throw new BusinessException(ErrorCode.PREVIEW_STALE,"考勤已更正，请刷新后重试");
        BigDecimal amount=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM core_consumption WHERE attendance_id=?",
            BigDecimal.class,current.id());
        return new AttendanceCorrectionDtos.Receipt(current.publicId(),correctionId,member.classPublicId(),
            target.sessionId(),target.studentId(),input.status(),input.note(),current.version()+1,
            actor.publicId(),clock.instant(),amount.setScale(2).toPlainString());
    }
    private Current lockCurrent(AcademicRepository.AttendanceMember member) {
        var rows=jdbc.query("""
            SELECT id,public_id,status,note_cipher,version,created_at FROM core_attendance
            WHERE session_id=? AND enrollment_id=? AND deleted=0 FOR UPDATE
            """,(rs,row)->new Current(rs.getLong("id"),rs.getString("public_id"),rs.getString("status"),
            rs.getString("note_cipher"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant()),member.sessionId(),member.enrollmentId());
        if(rows.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"考勤记录不存在，请先登记");
        return rows.get(0);
    }
    private void reconcileConsumption(Actor actor,AcademicRepository.AttendanceMember member,Current current,Change change) {
        if(member.orderId()!=null)jdbc.queryForObject("SELECT id FROM core_crm_order WHERE id=? AND deleted=0 FOR UPDATE",Long.class,member.orderId());
        var originals=jdbc.query("""
            SELECT id,amount FROM core_consumption WHERE attendance_id=? AND correction_id IS NULL AND deleted=0
            """,(rs,row)->new Original(rs.getLong("id"),rs.getBigDecimal("amount")),current.id());
        boolean wasConsumed=consumes(current.status()),willConsume=consumes(change.status());
        if(wasConsumed==willConsume)return;
        if(originals.isEmpty()) {
            if(member.orderId()==null)return;
            if(wasConsumed)throw new BusinessException(ErrorCode.INVALID_INPUT,"原考勤缺少课消流水，需核对账目");
            academic.appendConsumption(member,actor);
            return;
        }
        Original original=originals.get(0);
        if(willConsume)requireEntitlementAvailable(original.id());
        if(willConsume&&member.orderId()!=null)requireAvailableBalance(member.orderId(),original.amount());
        appendAdjustment(actor,original,new Adjustment(current.id(),change.publicId(),willConsume?1:-1));
    }
    private void appendAdjustment(Actor actor,Original original,Adjustment adjustment) {
        int changed=jdbc.update("""
            INSERT INTO core_consumption(public_id,attendance_id,session_id,student_id,enrollment_id,
                order_id,entitlement_lesson_id,actor_id,amount,correction_id,units)
            SELECT ?,x.attendance_id,x.session_id,x.student_id,x.enrollment_id,x.order_id,
                x.entitlement_lesson_id,?,x.amount*?,c.id,?
            FROM core_consumption x JOIN core_attendance_correction c ON c.public_id=? AND c.attendance_id=?
            WHERE x.id=?
            ""","CON_"+UUID.randomUUID(),actor.id(),adjustment.units(),adjustment.units(),adjustment.correctionId(),
            adjustment.attendanceId(),original.id());
        if(changed!=1)throw new BusinessException(ErrorCode.PREVIEW_STALE,"课消记录已变化，请刷新后重试");
    }
    private void requireEntitlementAvailable(long originalId) {
        Long used=jdbc.queryForObject("""
            SELECT COALESCE(SUM(x.units),0) FROM core_consumption x JOIN core_consumption original
                ON original.entitlement_lesson_id=x.entitlement_lesson_id WHERE original.id=? AND x.deleted=0
            """,Long.class,originalId);
        if(used!=null&&used>0)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"该课时权益已在其他课次消费");
    }
    private void requireAvailableBalance(long orderId,BigDecimal amount) {
        BigDecimal available=jdbc.queryForObject("""
            SELECT o.received_amount-o.refunded_amount
                -(SELECT COALESCE(SUM(x.amount),0) FROM core_consumption x WHERE x.order_id=o.id AND x.deleted=0)
                -(SELECT COALESCE(SUM(r.amount),0) FROM core_crm_refund_request r
                    WHERE r.order_id=o.id AND r.deleted=0 AND r.status IN ('pending_approval','approved'))
            FROM core_crm_order o WHERE o.id=? AND o.deleted=0
            """,BigDecimal.class,orderId);
        if(available==null||available.compareTo(amount)<0)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"订单可用课时金额不足，不能更正为已出勤");
    }
    static void requireOpenWindow(Instant recordedAt,Instant now) {
        Instant deadline=recordedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        if(!now.isBefore(deadline))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"该考勤已于登记日24点锁定，不能更正");
    }
    private boolean consumes(String status) {return List.of("attended","late","left_early").contains(status);}
    private String encryptNote(String note) {return note==null||note.isBlank()?null:cipher.encrypt(note);}
    private record Current(long id,String publicId,String status,String noteCipher,long version,Instant createdAt) { }
    private record Original(long id,BigDecimal amount) { }
    private record Change(String publicId,String status) { }
    private record Adjustment(long attendanceId,String correctionId,int units) { }
}
