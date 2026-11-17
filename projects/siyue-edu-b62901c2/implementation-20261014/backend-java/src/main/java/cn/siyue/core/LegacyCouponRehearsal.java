package cn.siyue.core;

import static cn.siyue.core.LegacyCatalogRehearsal.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Relational coupon facts imported inside the form migration transaction. */
final class LegacyCouponRehearsal {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    LegacyCouponRehearsal(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    int offers(JsonNode state) {
        int count=0;
        for(JsonNode offer:array(state.path("activities"),"coupon")) {
            String scope=offer.path("scope").asText("全部课程");require(Set.of("全部商品","全部课程","指定课程").contains(scope),"优惠券范围未映射");
            jdbc.update("INSERT INTO core_coupon_offer(public_id,owner_id,campus_id,name,description,template,scope_name,start_date,end_date,"
                + "discount_amount,threshold_amount,total_limit,max_per_user,published,version,source_profile_cipher,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",text(offer,"id"),id("core_principal",text(offer,"createdBy")),optionalId("core_campus",offer,"campusId"),
                text(offer,"name"),offer.path("description").asText(""),offer.path("template").asText("通用优惠"),scope,
                Date.valueOf(text(offer,"start")),Date.valueOf(text(offer,"end")),money(offer,"discountMinor"),money(offer,"thresholdMinor"),
                positive(offer,"total"),positive(offer,"maxPerUser"),offer.path("published").asBoolean(),positive(offer,"version"),
                cipher.encrypt(offer.toString()),date(offer,"createdAt"),date(offer,"updatedAt"));count++;
            long offerId=id("core_coupon_offer",text(offer,"id"));
            for(JsonNode course:array(offer,"courseIds")) {
                jdbc.update("INSERT INTO core_coupon_offer_course(offer_id,course_id) VALUES(?,?)",offerId,id("core_course",course.asText()));count++;
            }
        }
        return count;
    }
    int reward(long formId,long revision,JsonNode reward) {
        if(reward.isNull()||reward.isMissingNode()) return 0;
        String type=text(reward,"type");Long offer=null,version=null;String title=null,url=null;
        if(type.equals("coupon")) {
            offer=id("core_coupon_offer",text(reward,"promotionId"));
            version=reward.has("promotionVersion")?positive(reward,"promotionVersion"):
                jdbc.queryForObject("SELECT version FROM core_coupon_offer WHERE id=?",Long.class,offer);
        } else if(type.equals("material_link")) {
            title=cipher.encrypt(text(reward,"title"));url=cipher.encrypt(text(reward,"url"));
        } else throw invalid("奖励类型未映射");
        jdbc.update("INSERT INTO core_form_reward_config(public_id,form_id,revision_no,reward_type,offer_id,offer_version,title_cipher,url_cipher) "
            + "VALUES(?,?,?,?,?,?,?,?)","FREWARD_LEGACY_"+formId+"_"+revision,formId,revision,type,offer,version,title,url);return 1;
    }
    int claims(JsonNode state) {
        int count=0;
        for(JsonNode claim:array(state,"rewardClaims")) {
            long offer=id("core_coupon_offer",text(claim,"promotionId")),submission=id("core_crm_form_submission",text(claim,"submissionId"));
            require(jdbc.queryForObject("SELECT COUNT(s.id) FROM core_crm_form_submission s JOIN core_crm_form f ON f.id=s.form_id "
                + "JOIN core_crm_form_revision r ON r.id=s.revision_id WHERE s.id=? AND f.public_id=? AND r.revision_no=?",Integer.class,
                submission,text(claim,"formId"),positive(claim,"formVersion"))==1,"优惠券领取与表单发布关联不符");
            JsonNode terms=claim.path("terms");String status=text(claim,"status");
            require(Set.of("available","redeemed","cancelled").contains(status),"奖励券状态未映射");
            Long order=status.equals("redeemed")?id("core_crm_order",text(claim,"orderId")):null;
            jdbc.update("INSERT INTO core_coupon_claim(public_id,offer_id,offer_version,submission_id,offer_name,discount_amount,threshold_amount,phone_hash,"
                + "token_hash,token_cipher,status,issued_at,created_by,redeemed_order_id,redeemed_at,source_profile_cipher,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",text(claim,"id"),offer,positive(claim,"promotionVersion"),submission,text(terms,"name"),
                money(terms,"discountMinor"),money(terms,"thresholdMinor"),hex(claim,"phoneHash"),hex(claim,"tokenHash"),
                LegacyRewardTokenRewrapper.rewrap(text(claim,"claimTokenCipher"),text(claim,"tokenHash"),cipher),status,date(claim,"issuedAt"),
                id("core_principal",text(claim,"createdBy")),order,order==null?null:date(claim,"redeemedAt"),cipher.encrypt(claim.toString()),
                date(claim,"issuedAt"),order==null?date(claim,"issuedAt"):date(claim,"redeemedAt"));count++;
            if(order!=null) jdbc.update("UPDATE core_crm_order SET coupon_offer_id=?,coupon_claim_id=?,coupon_version=? WHERE id=?",
                offer,id("core_coupon_claim",text(claim,"id")),positive(claim,"promotionVersion"),order);
        }
        return count;
    }
    private Long optionalId(String table,JsonNode row,String key) {return row.hasNonNull(key)&&!row.path(key).asText().isBlank()?id(table,text(row,key)):null;}
    private long id(String table,String value) {
        require(Set.of("core_principal","core_campus","core_course","core_coupon_offer","core_crm_form_submission","core_crm_order","core_coupon_claim").contains(table),"迁移关联表不支持");
        List<Long> rows=jdbc.query("SELECT id FROM "+table+" WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),value);
        require(rows.size()==1,"奖励迁移缺少准确关联："+table);return rows.get(0);
    }
    private byte[] hex(JsonNode row,String key) {String text=text(row,key);require(text.matches("[a-f0-9]{64}"),"来源凭证摘要无效");return HexFormat.of().parseHex(text);}
    private static void require(boolean condition,String message) {if(!condition) throw invalid(message);}
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
