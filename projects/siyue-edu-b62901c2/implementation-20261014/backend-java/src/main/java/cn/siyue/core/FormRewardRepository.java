package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Versioned form rewards and transactional coupon issuance. */
@Repository
public class FormRewardRepository {
    public record Configuration(@jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp="coupon|material_link") String type,
            @jakarta.validation.constraints.Size(max=120) String promotionId,
            @jakarta.validation.constraints.Size(max=120) String title,@jakarta.validation.constraints.Size(max=2048) String url) { }
    public record Reward(String type,String title,String url,String claimToken,Long discountMinor,Long thresholdMinor) { }
    private record Config(long id,String type,Long offerId,Long offerVersion,String title,String url) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final CouponClaimRepository coupons;
    public FormRewardRepository(JdbcTemplate jdbc,PiiCipher cipher,ObjectMapper mapper) {
        this.jdbc=jdbc;this.cipher=cipher;this.coupons=new CouponClaimRepository(jdbc,cipher,mapper);
    }

    public record CouponChoice(String id,String name,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.math.BigDecimal discountAmount) { }
    public record CouponPage(java.util.List<CouponChoice> items,long total,int page,int size) { }
    public CouponPage options(Actor actor,CrmDtos.MarketingForm form,int page,int size) {
        java.util.List<Object> args=new java.util.ArrayList<>(java.util.List.of(form.campusId()));
        String where="o.published=1 AND o.deleted=0 AND o.template IN ('通用优惠','满减券','代金券') "
            + "AND (o.campus_id IS NULL OR o.campus_id=(SELECT id FROM core_campus WHERE public_id=?)) "
            + "AND o.start_date<=? AND o.end_date>=?";
        var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));args.add(today);args.add(today);
        if(actor.scope().equals("assigned")) {where+=" AND o.owner_id=?";args.add(actor.id());}
        long total=jdbc.queryForObject("SELECT COUNT(o.id) FROM core_coupon_offer o WHERE "+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var rows=jdbc.query("SELECT o.public_id,o.name,o.discount_amount FROM core_coupon_offer o WHERE "+where+" ORDER BY o.id DESC LIMIT ? OFFSET ?",
            (rs,n)->new CouponChoice(rs.getString(1),rs.getString(2),rs.getBigDecimal(3)),args.toArray());return new CouponPage(rows,total,page,size);
    }
    public long formId(String id,boolean lock) {
        return jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=? AND deleted=0"+(lock?" FOR UPDATE":""),Long.class,id);
    }
    public void advanceVersion(String id) {
        jdbc.update("UPDATE core_crm_form SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE public_id=?",id);
    }

    public Map<String,Object> publicReward(CrmFormRepository.PublicDefinition form) {
        Config config=config(form.formRowId(),form.revision());if(config==null) return null;
        String title=config.type().equals("coupon")?coupons.offer(config.offerId(),false).name():config.title();
        return Map.of("type",config.type(),"title",title);
    }

    public Configuration read(long formId) {
        Config config=config(formId,0);if(config==null) return null;
        return new Configuration(config.type(),config.offerId()==null?null:coupons.offer(config.offerId(),false).publicId(),config.title(),config.url());
    }
    public void saveDraft(Actor actor,CrmDtos.MarketingForm form,Configuration input) {
        long formId=jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=?",Long.class,form.id());
        if(input==null) {jdbc.update("UPDATE core_form_reward_config SET deleted=1 WHERE form_id=? AND revision_no=0",formId);return;}
        Long offerId=null,version=null;String title=null,url=null;
        if(input.type().equals("coupon")) {
            offerId=jdbc.query("SELECT id FROM core_coupon_offer WHERE public_id=? AND deleted=0",(rs,n)->rs.getLong(1),input.promotionId())
                .stream().findFirst().orElseThrow(()->invalid("奖励优惠券不存在"));
            var offer=coupons.offer(offerId,true);coupons.authorize(actor,offer);validateOffer(form,offer);version=offer.version();
        } else if(input.type().equals("material_link")) {
            title=input.title();url=input.url();validateLink(title,url);
        } else throw invalid("奖励类型无效");
        jdbc.update("INSERT INTO core_form_reward_config(public_id,form_id,revision_no,reward_type,offer_id,offer_version,title_cipher,url_cipher) "
            + "VALUES(?,?,0,?,?,?,?,?) ON DUPLICATE KEY UPDATE reward_type=VALUES(reward_type),offer_id=VALUES(offer_id),"
            + "offer_version=VALUES(offer_version),title_cipher=VALUES(title_cipher),url_cipher=VALUES(url_cipher),deleted=0,updated_at=UTC_TIMESTAMP(3)",
            "FREWARD_"+UUID.randomUUID(),formId,input.type(),offerId,version,encrypt(title),encrypt(url));
    }

    public void publish(Actor actor,CrmDtos.MarketingForm form,long revision) {
        long formId=jdbc.queryForObject("SELECT id FROM core_crm_form WHERE public_id=?",Long.class,form.id());
        Config config=config(formId,0);if(config==null) return;
        Long version=config.offerVersion();
        if(config.offerId()!=null) {
            var offer=coupons.offer(config.offerId(),true);coupons.authorize(actor,offer);validateOffer(form,offer);version=offer.version();
        }
        jdbc.update("INSERT INTO core_form_reward_config(public_id,form_id,revision_no,reward_type,offer_id,offer_version,title_cipher,url_cipher) "
            + "VALUES(?,?,?,?,?,?,?,?)","FREWARD_"+UUID.randomUUID(),formId,revision,config.type(),config.offerId(),version,
            encrypt(config.title()),encrypt(config.url()));
    }

    public Reward issue(CrmFormRepository.PublicDefinition form,String submissionId,Map<String,Object> answers) {
        Config config=config(form.formRowId(),form.revision());if(config==null) return null;
        if(config.type().equals("material_link")) return new Reward("material_link",config.title(),config.url(),null,null,null);
        Reward replay=receipt(submissionId);if(replay!=null) return replay;
        var offer=coupons.offer(config.offerId(),true);
        if(!offer.published()||offer.version()!=config.offerVersion()) throw invalid("奖励优惠券已停止或版本已更新");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(today.isBefore(offer.start())||today.isAfter(offer.end())) throw invalid("奖励优惠券不在有效期");
        String phoneField=form.fields().stream().filter(field->field.type().equals("phone")).map(CrmDtos.FormFieldInput::id)
            .findFirst().orElseThrow(()->invalid("奖励表单缺少电话字段"));
        Object raw=answers.get(phoneField);if(!(raw instanceof String phone)||phone.isBlank()) throw invalid("奖励领取需要家长电话");
        byte[] phoneHash=CouponClaimRepository.digest(phone);
        long studentDirect=directPhoneUse(offer.id(),phone);
        if(coupons.directUse(offer.id(),null)+coupons.claims(offer.id(),null)>=offer.total()
                ||studentDirect+coupons.claims(offer.id(),phoneHash)>=offer.perUser()) throw invalid("奖励优惠券额度已用尽");
        byte[] random=new byte[24];new java.security.SecureRandom().nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        int count=jdbc.update("INSERT INTO core_coupon_claim(public_id,offer_id,offer_version,submission_id,offer_name,discount_amount,threshold_amount,phone_hash,token_hash,"
            + "token_cipher,issued_at,created_by) SELECT ?,?,?,s.id,?,?,?,?,?,?,UTC_TIMESTAMP(3),r.published_by "
            + "FROM core_crm_form_submission s JOIN core_crm_form_revision r ON r.id=s.revision_id "
            + "WHERE s.public_id=? AND s.form_id=? AND s.revision_id=?","RCL_"+UUID.randomUUID(),offer.id(),offer.version(),offer.name(),offer.discount(),offer.threshold(),phoneHash,
            CouponClaimRepository.digest(token),cipher.encrypt(token),submissionId,form.formRowId(),form.revisionId());
        if(count!=1) throw invalid("奖励提交关联不符");return receipt(submissionId);
    }

    public Reward receipt(String submissionId) {
        Reward material=jdbc.query("SELECT r.title_cipher,r.url_cipher FROM core_form_reward_config r "
            + "JOIN core_crm_form_revision v ON v.form_id=r.form_id AND v.revision_no=r.revision_no "
            + "JOIN core_crm_form_submission s ON s.revision_id=v.id WHERE s.public_id=? AND r.reward_type='material_link' AND r.deleted=0",
            (rs,n)->new Reward("material_link",decrypt(rs.getString(1)),decrypt(rs.getString(2)),null,null,null),submissionId)
            .stream().findFirst().orElse(null);
        if(material!=null) return material;
        return jdbc.query("SELECT c.offer_name,c.token_cipher,c.discount_amount,c.threshold_amount FROM core_coupon_claim c "
            + "JOIN core_coupon_offer o ON o.id=c.offer_id JOIN core_crm_form_submission s ON s.id=c.submission_id "
            + "WHERE s.public_id=? AND c.deleted=0",(rs,n)->new Reward("coupon",rs.getString(1),null,
                rs.getString(2)==null?null:cipher.decrypt(rs.getString(2)),rs.getBigDecimal(3).movePointRight(2).longValueExact(),
                rs.getBigDecimal(4).movePointRight(2).longValueExact()),submissionId).stream().findFirst().orElse(null);
    }
    private long directPhoneUse(long offerId,String phone) {
        List<String> phones=jdbc.query("SELECT s.phone_cipher FROM core_crm_order o JOIN core_student s ON s.id=o.student_id "
            + "WHERE o.coupon_offer_id=? AND o.coupon_claim_id IS NULL AND o.deleted=0 AND "
            + "(o.status NOT IN ('cancelled','closed') OR o.received_amount>0)",(rs,n)->rs.getString(1),offerId);
        return phones.stream().filter(value->value!=null&&cipher.decrypt(value).equals(phone)).count();
    }
    private void validateOffer(CrmDtos.MarketingForm form,CouponClaimRepository.Offer offer) {
        if(!Set.of("通用优惠","满减券","代金券").contains(offer.template())) throw invalid("表单奖励仅支持通用优惠券");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(!offer.published()||today.isBefore(offer.start())||today.isAfter(offer.end())) throw invalid("奖励优惠券不可用");
        long campus=jdbc.queryForObject("SELECT id FROM core_campus WHERE public_id=?",Long.class,form.campusId());
        if(offer.campusId()!=null&&offer.campusId()!=campus) throw invalid("奖励优惠券与表单校区不一致");
        if(form.fields().stream().noneMatch(field->field.type().equals("phone")&&field.required())) throw invalid("奖励表单必须收集必填家长电话");
    }
    private void validateLink(String title,String url) {
        try {
            java.net.URI uri=java.net.URI.create(url);
            if(title==null||title.isBlank()||title.length()>120||url.length()>2048||!uri.getScheme().equals("https")
                    ||uri.getUserInfo()!=null||uri.getHost()==null) throw invalid("资料奖励必须提供标题和安全HTTPS链接");
        } catch(IllegalArgumentException|NullPointerException error) {throw invalid("资料奖励链接格式无效");}
    }
    private Config config(long formId,long revision) {
        return jdbc.query("SELECT id,reward_type,offer_id,offer_version,title_cipher,url_cipher FROM core_form_reward_config "
            + "WHERE form_id=? AND revision_no=? AND deleted=0",(rs,n)->new Config(rs.getLong(1),rs.getString(2),rs.getObject(3,Long.class),
                rs.getObject(4,Long.class),decrypt(rs.getString(5)),decrypt(rs.getString(6))),formId,revision).stream().findFirst().orElse(null);
    }
    private String encrypt(String value) {return value==null?null:cipher.encrypt(value);}
    private String decrypt(String value) {return value==null?null:cipher.decrypt(value);}
    private BusinessException invalid(String text) {return new BusinessException(ErrorCode.INVALID_INPUT,text);}
}
