package cn.siyue.core;

import cn.siyue.core.CommerceCheckoutRepository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Coupon eligibility is reused and the accepted order is matched to the immutable checkout line. */
final class CommerceCheckoutCouponRepository {
    /** 报价所引用的优惠券内部主键、公开标识和版本，提交时用于拒绝配置漂移。 */
    record Snapshot(long offerId,String publicId,long version) { }
    /** 已校验优惠券版本快照与本次报价计算出的实际优惠金额。 */
    record Priced(Snapshot snapshot,BigDecimal discount) { }
    private final CouponClaimRepository coupons;
    private final Map<Long,Integer> totals=new HashMap<>();
    private final Map<String,Integer> perBuyer=new HashMap<>();
    CommerceCheckoutCouponRepository(JdbcTemplate jdbc,PiiCipher cipher,ObjectMapper mapper) {coupons=new CouponClaimRepository(jdbc,cipher,mapper);}
    Priced quote(Actor actor,String couponId,Buyer buyer,ProductClass item) {
        if(couponId==null||couponId.isBlank()) return new Priced(null,new BigDecimal("0.00"));
        if(actor==null) throw new BusinessException(ErrorCode.IDENTITY_VERIFICATION_REQUIRED,"公开报价不能指定未经核对的优惠券");
        var quote=buyer.selectedStudent()==null?coupons.quoteDraft(actor,couponId,new CouponClaimRepository.DraftPurchase(item.classId(),buyer.phone()))
            :coupons.quoteDirect(actor,couponId,buyer.selectedStudent(),item.classId());
        var offer=coupons.offer(quote.promotionId(),false);String buyerKey=offer.id()+":"+buyer.index();
        coupons.requireAdditional(offer,buyer.selectedStudent()==null?0:buyer.selectedStudent(),buyer.phone(),totals.getOrDefault(offer.id(),0),perBuyer.getOrDefault(buyerKey,0));
        totals.merge(offer.id(),1,Integer::sum);perBuyer.merge(buyerKey,1,Integer::sum);
        if(quote.base().compareTo(item.price())!=0) throw new BusinessException(ErrorCode.PREVIEW_STALE,"优惠券基价与商城价格不一致");
        return new Priced(new Snapshot(offer.id(),offer.publicId(),offer.version()),quote.discount());
    }
    void current(Actor actor,Snapshot snapshot) {
        if(snapshot==null) return;var offer=coupons.offer(snapshot.offerId(),true);coupons.authorize(actor,offer);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(!offer.published()||offer.version()!=snapshot.version()||today.isBefore(offer.start())||today.isAfter(offer.end()))
            throw new BusinessException(ErrorCode.PREVIEW_STALE,"叠加优惠券已失效或版本已变化");
    }
    void redeem(Actor actor,Snapshot snapshot,String orderId) {if(snapshot!=null) coupons.redeemCheckout(actor,snapshot.offerId(),orderId);}
}
