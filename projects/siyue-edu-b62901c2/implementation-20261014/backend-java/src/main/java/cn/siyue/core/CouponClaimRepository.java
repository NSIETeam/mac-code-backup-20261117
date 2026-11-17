package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transaction-bound coupon pricing and exactly-once redemption for enrollment commands. */
@Repository
public class CouponClaimRepository {
    public record ClaimQuote(long claimId,long promotionId,BigDecimal base,BigDecimal discount,BigDecimal amount) { }
    public record DirectContext(Long referrerStudentId,List<Long> batchStudentIds) { }
    record Offer(long id,String publicId,long ownerId,Long campusId,String name,String template,String scope,
            LocalDate start,LocalDate end,BigDecimal discount,BigDecimal threshold,int total,int perUser,
            boolean published,long version) { }
    private record Purchase(long studentId,long classId,long courseId,long campusId,String subject,String phone,BigDecimal base) { }
    private record Claim(long id,long offerId,long version,byte[] phoneHash,String status) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public CouponClaimRepository(JdbcTemplate jdbc,PiiCipher cipher,ObjectMapper mapper) {this.jdbc=jdbc;this.cipher=cipher;}

    public ClaimQuote quote(Actor actor,String token,long studentId,long classId) {
        requireTransaction();
        if(token==null||token.length()<24||token.length()>128) throw invalid("优惠券兑换码无效");
        Claim claim=jdbc.query("SELECT id,offer_id,offer_version,phone_hash,status FROM core_coupon_claim "
            + "WHERE token_hash=? AND deleted=0",(rs,n)->new Claim(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBytes(4),rs.getString(5)),
            digest(token)).stream().findFirst().orElseThrow(()->invalid("优惠券不存在或不可使用"));
        Offer offer=offer(claim.offerId(),true);authorize(actor,offer);
        claim=jdbc.queryForObject("SELECT id,offer_id,offer_version,phone_hash,status FROM core_coupon_claim WHERE id=? FOR UPDATE",
            (rs,n)->new Claim(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBytes(4),rs.getString(5)),claim.id());
        Purchase purchase=purchase(actor,studentId,classId);
        if(!claim.status().equals("available")||offer.version()!=claim.version()) throw invalid("优惠券已使用或版本已变化");
        if(!java.security.MessageDigest.isEqual(claim.phoneHash(),digest(purchase.phone())))
            throw new BusinessException(ErrorCode.FORBIDDEN,"优惠券手机号与学生档案不一致");
        validateOffer(offer,purchase,true);
        if(directUse(offer.id(),studentId)+claims(offer.id(),claim.phoneHash())-1>=offer.perUser()) throw invalid("优惠使用次数已达上限");
        return price(claim.id(),offer,purchase);
    }

    public ClaimQuote quoteDirect(Actor actor,String couponId,long studentId,long classId) {
        return quoteDirect(actor,couponId,studentId,classId,new DirectContext(null,List.of()));
    }
    public ClaimQuote quoteDirect(Actor actor,String couponId,long studentId,long classId,DirectContext context) {
        requireTransaction();
        Long id=jdbc.query("SELECT id FROM core_coupon_offer WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),couponId)
            .stream().findFirst().orElseThrow(()->invalid("优惠券不存在"));
        Offer offer=offer(id,true);authorize(actor,offer);Purchase purchase=purchase(actor,studentId,classId);
        validateOffer(offer,purchase,false);eligibility(actor,offer,purchase,context);
        if(directUse(id,null)+claims(id,null)>=offer.total()
                ||directUse(id,studentId)+claims(id,digest(purchase.phone()))>=offer.perUser()) throw invalid("优惠额度已用尽");
        return price(0,offer,purchase);
    }

    record DraftPurchase(long classId,String phone) { }
    ClaimQuote quoteDraft(Actor actor,String couponId,DraftPurchase draft) {
        requireTransaction();long id=jdbc.query("SELECT id FROM core_coupon_offer WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),couponId)
            .stream().findFirst().orElseThrow(()->invalid("优惠券不存在"));
        Offer offer=offer(id,true);authorize(actor,offer);ScopeSql.Clause scope=ScopeSql.classes(actor);
        List<Object> args=new ArrayList<>(List.of(draft.classId()));args.addAll(scope.args());
        Purchase purchase=jdbc.query("SELECT c.course_id,c.campus_id,co.subject,co.price FROM core_class c "
            + "JOIN core_course co ON co.id=c.course_id "
            + "WHERE c.id=? AND c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0 AND ("+scope.sql()+")",
            (rs,n)->new Purchase(0,draft.classId(),rs.getLong(1),rs.getLong(2),rs.getString(3),draft.phone(),rs.getBigDecimal(4)),args.toArray())
            .stream().findFirst().orElseThrow(()->invalid("优惠班级不可用"));
        validateOffer(offer,purchase,false);eligibility(actor,offer,purchase,new DirectContext(null,List.of()));
        requireAdditional(offer,0,draft.phone(),0,0);return price(0,offer,purchase);
    }
    void requireAdditional(Offer offer,long studentId,String phone,int extraGlobal,int extraUser) {
        if(directUse(offer.id(),null)+claims(offer.id(),null)+extraGlobal>=offer.total()
                ||directUse(offer.id(),studentId)+claims(offer.id(),digest(phone))+extraUser>=offer.perUser()) throw invalid("优惠额度已用尽");
    }
    void redeemCheckout(Actor actor,long offerId,String orderPublicId) {
        requireTransaction();Offer offer=offer(offerId,true);authorize(actor,offer);Order row=order(orderPublicId);
        Purchase purchase=purchase(actor,row.studentId(),row.classId());validateOffer(offer,purchase,false);
        eligibility(actor,offer,purchase,new DirectContext(null,List.of()));ClaimQuote quote=price(0,offer,purchase);
        BigDecimal[] amounts=jdbc.query("SELECT l.coupon_discount,l.promotion_discount,l.amount,l.base_amount,l.coupon_version "
            + "FROM core_crm_order o JOIN core_commerce_checkout_buyer b ON b.checkout_id=o.commerce_checkout_id AND b.resolved_student_id=o.student_id "
            + "JOIN core_commerce_checkout_line l ON l.checkout_id=b.checkout_id AND l.buyer_index=b.buyer_index AND l.class_id=? "
            + "WHERE o.id=? AND l.coupon_offer_id=? AND l.deleted=0",(rs,n)->new BigDecimal[]{rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5)},
            row.classId(),row.id(),offerId).stream().findFirst().orElseThrow(()->invalid("商城优惠缺少准确报价关联"));
        if(amounts[4].longValueExact()!=offer.version()||quote.discount().compareTo(amounts[0])!=0||quote.base().compareTo(amounts[3])!=0
                ||quote.amount().subtract(amounts[1]).compareTo(amounts[2])!=0||row.amount().compareTo(amounts[2])!=0
                ||row.offerId()!=null||!row.status().equals("pending")) throw invalid("商城优惠报价已变化");
        requireAdditional(offer,row.studentId(),purchase.phone(),0,0);attach(row,offer,null);
    }

    public void redeem(Actor actor,long claimId,String orderPublicId) {
        requireTransaction();
        Long offerId=jdbc.queryForObject("SELECT offer_id FROM core_coupon_claim WHERE id=?",Long.class,claimId);
        Offer offer=offer(offerId,true);authorize(actor,offer);
        Claim claim=jdbc.queryForObject("SELECT id,offer_id,offer_version,phone_hash,status FROM core_coupon_claim WHERE id=? FOR UPDATE",
            (rs,n)->new Claim(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBytes(4),rs.getString(5)),claimId);
        Order row=order(orderPublicId);Purchase purchase=purchase(actor,row.studentId(),row.classId());
        if(!claim.status().equals("available")||claim.version()!=offer.version()
                ||!java.security.MessageDigest.isEqual(claim.phoneHash(),digest(purchase.phone()))) throw invalid("优惠券状态或身份已变化");
        validateOffer(offer,purchase,true);requireAmount(row,price(claimId,offer,purchase));
        if(directUse(offer.id(),purchase.studentId())+claims(offer.id(),claim.phoneHash())-1>=offer.perUser()) throw invalid("优惠使用次数已达上限");
        attach(row,offer,claimId);
        jdbc.update("UPDATE core_coupon_claim SET status='redeemed',redeemed_order_id=?,redeemed_at=UTC_TIMESTAMP(3),"
            + "version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",row.id(),claimId);
    }

    public void redeemDirect(Actor actor,long offerId,String orderPublicId) {
        redeemDirect(actor,offerId,orderPublicId,new DirectContext(null,List.of()));
    }
    public void redeemDirect(Actor actor,long offerId,String orderPublicId,DirectContext context) {
        requireTransaction();Offer offer=offer(offerId,true);authorize(actor,offer);Order row=order(orderPublicId);
        Purchase purchase=purchase(actor,row.studentId(),row.classId());validateOffer(offer,purchase,false);
        eligibility(actor,offer,purchase,context);
        requireAmount(row,price(0,offer,purchase));
        if(directUse(offerId,null)+claims(offerId,null)>=offer.total()
                ||directUse(offerId,purchase.studentId())+claims(offerId,digest(purchase.phone()))>=offer.perUser()) throw invalid("优惠额度已用尽");
        attach(row,offer,null);
    }

    private record Order(long id,long studentId,long classId,BigDecimal amount,Long offerId,String status) { }
    private Order order(String publicId) {
        return jdbc.query("SELECT o.id,o.student_id,t.class_id,o.total_amount,o.coupon_offer_id,o.status FROM core_crm_order o "
            + "JOIN core_crm_order_class_terms t ON t.order_id=o.id WHERE o.public_id=? AND o.deleted=0 FOR UPDATE",
            (rs,n)->new Order(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBigDecimal(4),rs.getObject(5,Long.class),rs.getString(6)),publicId)
            .stream().findFirst().orElseThrow(()->invalid("优惠订单缺少准确班级收费快照"));
    }
    private void requireAmount(Order order,ClaimQuote quote) {
        if(order.offerId()!=null||!order.status().equals("pending")||order.amount().compareTo(quote.amount())!=0)
            throw invalid("优惠订单状态、金额或已关联优惠不符");
    }
    private void attach(Order order,Offer offer,Long claim) {
        jdbc.update("UPDATE core_crm_order SET coupon_offer_id=?,coupon_claim_id=?,coupon_version=? WHERE id=?",
            offer.id(),claim,offer.version(),order.id());
    }
    private ClaimQuote price(long claimId,Offer offer,Purchase purchase) {
        BigDecimal cut=offer.discount().min(purchase.base());return new ClaimQuote(claimId,offer.id(),purchase.base(),cut,purchase.base().subtract(cut));
    }

    Offer offer(long id,boolean lock) {
        return jdbc.query("SELECT id,public_id,owner_id,campus_id,name,template,scope_name,start_date,end_date,discount_amount,"
            + "threshold_amount,total_limit,max_per_user,published,version FROM core_coupon_offer WHERE id=? AND deleted=0"
            +(lock?" FOR UPDATE":""),(rs,n)->new Offer(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getObject(4,Long.class),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getDate(8).toLocalDate(),rs.getDate(9).toLocalDate(),
                rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getInt(12),rs.getInt(13),rs.getBoolean(14),rs.getLong(15)),id)
            .stream().findFirst().orElseThrow(()->invalid("优惠券不存在"));
    }
    private Purchase purchase(Actor actor,long studentId,long classId) {
        ScopeSql.Clause scope=ScopeSql.studentWrite(actor);List<Object> args=new ArrayList<>(List.of(studentId));args.addAll(scope.args());
        String phone=jdbc.query("SELECT s.phone_cipher FROM core_student s WHERE s.id=? AND s.deleted=0 AND ("+scope.sql()+")",
            (rs,n)->cipher.decrypt(rs.getString(1)),args.toArray()).stream().findFirst().orElseThrow(()->invalid("学生不在授权范围"));
        ScopeSql.Clause classes=actor.scope().equals("own")?new ScopeSql.Clause("1=1",List.of()):ScopeSql.classes(actor);args=new ArrayList<>(List.of(classId));args.addAll(classes.args());
        return jdbc.query("SELECT c.course_id,c.campus_id,co.subject,co.price FROM core_class c JOIN core_course co ON co.id=c.course_id "
            + "WHERE c.id=? AND c.active=1 AND c.deleted=0 AND co.active=1 AND co.deleted=0 AND ("+classes.sql()+")",
            (rs,n)->new Purchase(studentId,classId,rs.getLong(1),rs.getLong(2),rs.getString(3),phone,rs.getBigDecimal(4)),args.toArray())
            .stream().findFirst().orElseThrow(()->invalid("课程班级不在授权范围"));
    }
    void authorize(Actor actor,Offer offer) {
        if(actor.scope().equals("all")||actor.scope().equals("own")) return;
        if(actor.scope().equals("campus")&&offer.campusId()==null) return;
        if(actor.scope().equals("campus")&&offer.campusId()!=null&&jdbc.queryForObject("SELECT COUNT(id) FROM core_principal_campus "
            + "WHERE principal_id=? AND campus_id=? AND deleted=0",Integer.class,actor.id(),offer.campusId())>0) return;
        if(Set.of("own","assigned").contains(actor.scope())&&offer.ownerId()==actor.id()) return;
        throw new BusinessException(ErrorCode.FORBIDDEN,"优惠券不在授权范围");
    }
    private void validateOffer(Offer offer,Purchase purchase,boolean claimed) {
        LocalDate now=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(!offer.published()||now.isBefore(offer.start())||now.isAfter(offer.end())) throw invalid("优惠券未发布或不在有效期");
        if(offer.campusId()!=null&&offer.campusId()!=purchase.campusId()) throw invalid("优惠券不适用当前校区");
        if(purchase.base().compareTo(offer.threshold())<0) throw invalid("未达到优惠门槛");
        List<Long> courses=jdbc.queryForList("SELECT course_id FROM core_coupon_offer_course WHERE offer_id=? AND deleted=0",Long.class,offer.id());
        if((claimed&&!courses.isEmpty()||offer.scope().equals("指定课程"))&&!courses.contains(purchase.courseId())) throw invalid("优惠券不适用当前课程");
        if(!Set.of("全部商品","全部课程","指定课程").contains(offer.scope())) throw invalid("优惠券范围无效");
    }
    private void eligibility(Actor actor,Offer offer,Purchase purchase,DirectContext context) {
        switch(offer.template()) {
            case "通用优惠","满减券","代金券" -> { }
            case "续费优惠","续费" -> { if(paidHistory(purchase.studentId(),purchase.subject())==0) throw invalid("续费优惠需要本人同学科有效实收报名"); }
            case "老带新优惠","老带新" -> {
                Long referrer=context.referrerStudentId();
                if(referrer==null||referrer==purchase.studentId()||paidHistory(purchase.studentId(),null)>0) throw invalid("老带新资格不符");
                purchase(actor,referrer,purchase.classId());if(paidHistory(referrer,null)==0) throw invalid("推荐学生没有有效实收报名");
            }
            case "双人同行优惠","双人同行" -> {
                List<Long> batch=context.batchStudentIds();
                if(batch==null||batch.size()<2||batch.stream().distinct().count()!=batch.size()||!batch.contains(purchase.studentId())) throw invalid("双人同行需要不同学生同批报名");
            }
            default -> throw invalid("优惠资格模板不支持");
        }
    }
    private long paidHistory(long studentId,String subject) {
        return jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_order WHERE student_id=? AND received_amount>0 "
            + "AND received_amount=total_amount AND received_amount>refunded_amount AND deleted=0"
            +(subject==null?"":" AND subject_snapshot=?"),Long.class,subject==null?new Object[]{studentId}:new Object[]{studentId,subject});
    }
    long directUse(long offerId,Long studentId) {
        return jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_order WHERE coupon_offer_id=? AND coupon_claim_id IS NULL "
            + "AND deleted=0 AND (status NOT IN ('cancelled','closed') OR received_amount>0)"
            +(studentId==null?"":" AND student_id=?"),Long.class,studentId==null?new Object[]{offerId}:new Object[]{offerId,studentId});
    }
    long claims(long offerId,byte[] phoneHash) {
        return jdbc.queryForObject("SELECT COUNT(id) FROM core_coupon_claim WHERE offer_id=? AND status<>'cancelled' AND deleted=0"
            +(phoneHash==null?"":" AND phone_hash=?"),Long.class,phoneHash==null?new Object[]{offerId}:new Object[]{offerId,phoneHash});
    }
    static byte[] digest(String value) {return HexFormat.of().parseHex(LegacyCatalogRehearsal.hash(value));}
    private void requireTransaction() {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Coupon operation requires an enclosing transaction");
    }
    private BusinessException invalid(String text) {return new BusinessException(ErrorCode.INVALID_INPUT,text);}
}
