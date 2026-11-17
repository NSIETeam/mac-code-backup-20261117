package cn.siyue.core;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Validates public link tuples and preserves immutable submission and approved-touchpoint provenance. */
@Repository
public class FormAcquisitionRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** Supplies local persistence for public provenance and immutable approval evidence.
     * @param jdbc transaction-participating MySQL connection access
     * @param cipher authenticated encryption for retained promoter labels and touchpoint text
     */
    public FormAcquisitionRepository(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    /** 提交时从已发布活动、表单和推广链接关系解析的来源；推广员姓名保持原密文快照。 */
    record Source(long link,String campaignName,String promoterNameCipher) { }

    /** Resolves an exact published campaign/form/link tuple; names and promoter identity come only from SQL.
     * Historical links remain usable for attribution even when their promoter later becomes inactive.
     * @param form published form public identifier
     * @param activity activity public identifier; mandatory when a promotion link is supplied
     * @param promotion optional immutable promotion-link public identifier
     * @param lock whether the caller is submitting and must hold publication-state locks through commit
     * @return immutable source fields, or null for an ordinary unattributed form
     * @throws BusinessException if the tuple is incomplete, unrelated or no longer published
     */
    Source resolve(String form,String activity,String promotion,boolean lock) {
        if(promotion==null)return null;
        if(promotion.isBlank()||activity==null||activity.isBlank())throw unavailable();
        return jdbc.query("SELECT l.id,a.title,l.promoter_name_cipher FROM core_campaign_promotion_link l "
            +"JOIN core_activity a ON a.id=l.activity_id JOIN core_crm_form f ON f.id=l.form_id "
            +"JOIN core_activity_form af ON af.activity_id=a.id AND af.form_id=f.id "
            +"WHERE l.public_id=? AND a.public_id=? AND f.public_id=? AND l.deleted=0 AND a.deleted=0 "
            +"AND a.activity_type='campaign' AND a.status='active' AND f.deleted=0 AND f.status='published' AND af.deleted=0"
            +(lock?" FOR UPDATE":""),(rs,n)->new Source(rs.getLong(1),rs.getString(2),rs.getString(3)),promotion,activity,form)
            .stream().findFirst().orElseThrow(FormAcquisitionRepository::unavailable);
    }

    /** Saves the server-resolved source exactly once alongside the pending submission. */
    void freeze(long submission,Source source) {
        if(source!=null)jdbc.update("INSERT INTO core_form_acquisition(submission_id,promotion_link_id,campaign_name,promoter_name_cipher,recorded_at) "
            +"VALUES(?,?,?,?,UTC_TIMESTAMP(3))",submission,source.link(),source.campaignName(),source.promoterNameCipher());
    }

    /** Adds one form touchpoint for an approved lead, retaining frozen origin even after campaign closure.
     * The review transaction owns the submission lock and rolls back both decision and touchpoint on any error.
     */
    void approved(Actor actor,long submission,Long customer,Long lead) {
        if(customer==null||lead==null)return;
        /** 已冻结来源对应的推广链接、活动和推广员内部主键，审核时用于创建可追溯触点。 */
        record Origin(long link,long activity,long promoter) { }
        List<Origin> sources=jdbc.query("SELECT fa.promotion_link_id,l.activity_id,l.promoter_id FROM core_form_acquisition fa "
            +"JOIN core_campaign_promotion_link l ON l.id=fa.promotion_link_id WHERE fa.submission_id=? AND fa.deleted=0",
            (rs,n)->new Origin(rs.getLong(1),rs.getLong(2),rs.getLong(3)),submission);
        if(sources.isEmpty())return;
        actor.require("lead.create");
        Origin source=sources.get(0);String id="TOUCH_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_customer_touchpoint(public_id,customer_id,activity_id,actor_id,owner_id,source,channel,summary_cipher,occurred_at) "
            +"VALUES(?,?,?,?,?,'online_form','form',?,UTC_TIMESTAMP(3))",id,customer,source.activity(),actor.id(),jdbc.queryForObject("SELECT owner_id FROM core_customer WHERE id=?",Long.class,customer),cipher.encrypt("推广表单审核通过"));
        long touch=jdbc.queryForObject("SELECT id FROM core_customer_touchpoint WHERE public_id=?",Long.class,id);
        jdbc.update("INSERT INTO core_touchpoint_acquisition(touchpoint_id,submission_id,promotion_link_id) VALUES(?,?,?)",touch,submission,source.link());
    }
    private static BusinessException unavailable() {return new BusinessException(ErrorCode.NOT_FOUND,"推广链接不存在或已失效");}
}
