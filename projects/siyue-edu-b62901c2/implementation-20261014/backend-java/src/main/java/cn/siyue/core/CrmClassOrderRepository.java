package cn.siyue.core;

import cn.siyue.core.CrmDtos.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads scoped campus class quotes and preserves immutable order terms. */
final class CrmClassOrderRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    CrmClassOrderRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SELECT = """
        SELECT c.id,c.public_id,c.name,c.version,co.public_id course_public_id,
            f.course_fee,f.material_fee,f.course_refund_rule,f.refund_lesson_count,f.material_refund_rule
        FROM core_class c JOIN core_course co ON co.id=c.course_id JOIN core_class_fee f ON f.class_id=c.id
        WHERE c.deleted=0 AND c.active=1 AND co.deleted=0 AND co.active=1 AND c.campus_id=?
            AND f.course_fee+f.material_fee>0
        """;
    ClassQuotePage quotes(Long campus,int page,int size) {
        if (campus==null) { return new ClassQuotePage(List.of(),0,page,size); }
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM ("+SELECT+") q",Long.class,campus);
        List<ClassQuote> rows=jdbc.query(SELECT+" ORDER BY c.id DESC LIMIT ? OFFSET ?",(rs,row)->quote(rs),
            campus,size,Math.multiplyExact((long)page-1,size));
        return new ClassQuotePage(rows,total==null?0:total,page,size);
    }
    ClassQuote confirmed(Long campus,ClassOrderInput input,BigDecimal amount) {
        List<ClassQuote> found=jdbc.query(SELECT+" AND c.public_id=? FOR SHARE",(rs,row)->quote(rs),campus,input.classId());
        if(found.size()!=1) throw new BusinessException(ErrorCode.NOT_FOUND,"班级收费不存在、已停用或不属于商机校区");
        ClassQuote quote=found.get(0);
        if(quote.terms().classVersion()!=input.expectedVersion()) throw new BusinessException(ErrorCode.PREVIEW_STALE,"班级版本已变化，请重新选择");
        if(quote.totalAmount().compareTo(amount)!=0) throw new BusinessException(ErrorCode.INVALID_INPUT,"订单金额必须与班级课程费和教辅费合计一致");
        return quote;
    }
    private ClassQuote quote(java.sql.ResultSet rs) throws java.sql.SQLException {
        var fees=new AcademicDtos.ClassFees(rs.getBigDecimal("course_fee"),rs.getBigDecimal("material_fee"),
            rs.getString("course_refund_rule"),rs.getObject("refund_lesson_count",Integer.class),rs.getString("material_refund_rule"));
        return new ClassQuote(new ClassOrderTerms(rs.getString("public_id"),rs.getString("name"),rs.getString("course_public_id"),
            rs.getLong("version"),fees),fees.courseFee().add(fees.materialFee()));
    }
    void save(long orderId,ClassOrderTerms terms) {
        try {
            jdbc.update("""
                INSERT INTO core_crm_order_class_terms(order_id,class_id,terms_snapshot)
                VALUES(?,(SELECT id FROM core_class WHERE public_id=?),?)
                """,orderId,terms.classId(),mapper.writeValueAsString(terms));
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Unable to serialize class terms",error); }
    }
    ClassOrderTerms read(long orderId) {
        List<String> values=jdbc.query("SELECT terms_snapshot FROM core_crm_order_class_terms WHERE order_id=?",(rs,row)->rs.getString(1),orderId);
        if(values.isEmpty()) return null;
        try { return mapper.readValue(values.get(0),ClassOrderTerms.class); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Unable to read class terms",error); }
    }
}
