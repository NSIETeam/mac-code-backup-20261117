package cn.siyue.core;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Financial lists use the same order scope and immutable ledgers as financial mutations. */
@Repository
public class FinanceWorkspaceRepository {
    public record Filter(Integer page,Integer size,String status,String orderId,Instant fromAt,Instant untilAt) { }
    public record Page<T>(List<T> items,long total,int page,int size) { }
    public record Arrear(String id,String product,String studentId,String campusId,String status,long version,String checkoutId,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalAmount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal receivedAmount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dueAmount,Instant createdAt) { }
    public record Refund(String id,String orderId,String product,String applicantId,String reviewerId,String status,long version,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,String reason,Instant createdAt) { }
    public record Consumption(String id,String orderId,String product,String studentId,String classId,String sessionId,String lesson,
            int units,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,Instant occurredAt) { }
    private record Query(String sql,List<Object> args,int page,int size) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public FinanceWorkspaceRepository(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    public Page<Arrear> arrears(Actor actor,Filter filter) {
        LegacyFinancePermissions.readOrders(actor);Query q=query(actor,filter,"o.created_at",Set.of("pending","partially_received"),"o.status");
        String where=q.sql()+" AND o.status IN ('pending','partially_received') AND o.total_amount>o.received_amount";
        String from=" FROM core_crm_order o LEFT JOIN core_student st ON st.id=o.student_id LEFT JOIN core_campus cp ON cp.id=o.campus_id "
            + "LEFT JOIN core_commerce_checkout ck ON ck.id=o.commerce_checkout_id WHERE "+where;
        long total=count(from,q);List<Arrear> rows=jdbc.query("SELECT o.public_id,o.product_snapshot,st.public_id,cp.public_id,o.status,o.version,ck.public_id,"
            + "o.total_amount,o.received_amount,o.total_amount-o.received_amount,o.created_at"+from+" ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",
            (rs,n)->new Arrear(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getString(7),
                rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getTimestamp(11).toInstant()),pageArgs(q));
        return new Page<>(rows,total,q.page(),q.size());
    }
    public Page<Refund> refunds(Actor actor,Filter filter) {
        LegacyFinancePermissions.readRefunds(actor);
        if (!LegacyFinancePermissions.legacyRefundReader(actor)) { actor.require("crm.order.read"); }
        Query q=query(actor,filter,"r.created_at",Set.of("pending_approval","approved","rejected","settled"),"r.status");
        String from=" FROM core_crm_refund_request r JOIN core_crm_order o ON o.id=r.order_id JOIN core_principal applicant ON applicant.id=r.applicant_id "
            + "LEFT JOIN core_principal reviewer ON reviewer.id=r.reviewer_id WHERE r.deleted=0 AND "+q.sql();
        long total=count(from,q);List<Refund> rows=jdbc.query("SELECT r.public_id,o.public_id,o.product_snapshot,applicant.public_id,reviewer.public_id,"
            + "r.status,r.version,r.amount,r.reason_cipher,r.created_at"+from+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs,n)->new Refund(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7),
                rs.getBigDecimal(8),(actor.permissions().contains("crm.refund.read_detail")||LegacyFinancePermissions.legacyRefundReader(actor)||actor.publicId().equals(rs.getString(4)))?
                    cipher.decrypt(rs.getString(9)):null,rs.getTimestamp(10).toInstant()),pageArgs(q));
        return new Page<>(rows,total,q.page(),q.size());
    }
    public Page<Consumption> consumptions(Actor actor,Filter filter) {
        actor.requireAny("consumption.read","report.read");Query q=query(actor,filter,"x.created_at",Set.of("charge","reversal"),null);
        String from=" FROM core_consumption x JOIN core_crm_order o ON o.id=x.order_id JOIN core_student st ON st.id=x.student_id "
            + "JOIN core_session se ON se.id=x.session_id JOIN core_class c ON c.id=se.class_id "
            + "JOIN core_enrollment_lesson el ON el.id=x.entitlement_lesson_id WHERE x.deleted=0 AND "+q.sql();
        if("charge".equals(filter.status())) from+=" AND x.units=1";if("reversal".equals(filter.status())) from+=" AND x.units=-1";
        long total=count(from,q);List<Consumption> rows=jdbc.query("SELECT x.public_id,o.public_id,o.product_snapshot,st.public_id,c.public_id,se.public_id,"
            + "el.title_snapshot,x.units,x.amount,x.created_at"+from+" ORDER BY x.created_at DESC,x.id DESC LIMIT ? OFFSET ?",
            (rs,n)->new Consumption(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),
                rs.getInt(8),rs.getBigDecimal(9),rs.getTimestamp(10).toInstant()),pageArgs(q));return new Page<>(rows,total,q.page(),q.size());
    }
    private Query query(Actor actor,Filter filter,String date,Set<String> statuses,String statusColumn) {
        int page=filter.page()==null?1:filter.page(),size=filter.size()==null?50:filter.size();
        pageBounds(page,size);validateWindow(filter);
        ScopeSql.Clause scope=ScopeSql.orders(actor);String where="o.deleted=0 AND ("+scope.sql()+")";List<Object> args=new ArrayList<>(scope.args());
        if(hasText(filter.status())) {
            if(!statuses.contains(filter.status())) throw invalid("状态筛选无效");
            if(statusColumn!=null) {where+=" AND "+statusColumn+"=?";args.add(filter.status());}
        }
        if(hasText(filter.orderId())) {
            if(!filter.orderId().matches("[A-Za-z0-9_-]{1,120}")) throw invalid("订单编号无效");where+=" AND o.public_id=?";args.add(filter.orderId());
        }
        if(filter.fromAt()!=null) {where+=" AND "+date+">=?";args.add(Timestamp.from(filter.fromAt()));}
        if(filter.untilAt()!=null) {where+=" AND "+date+"<?";args.add(Timestamp.from(filter.untilAt()));}
        return new Query(where,args,page,size);
    }
    private boolean hasText(String value) {return value!=null&&!value.isBlank();}
    private void pageBounds(int page,int size) {if(page<1||page>100000||size<1||size>100) throw invalid("分页无效");}
    private void validateWindow(Filter filter) {
        if(filter.fromAt()!=null&&filter.untilAt()!=null&&!filter.untilAt().isAfter(filter.fromAt())) throw invalid("筛选结束时间须晚于开始时间");
    }
    private long count(String from,Query query) {return jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,query.args().toArray());}
    private Object[] pageArgs(Query query) {List<Object> args=new ArrayList<>(query.args());args.add(query.size());args.add((long)(query.page()-1)*query.size());return args.toArray();}
    private BusinessException invalid(String message) {return new BusinessException(ErrorCode.INVALID_INPUT,message);}
}
