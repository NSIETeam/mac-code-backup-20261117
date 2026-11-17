package cn.siyue.core;

import cn.siyue.core.CampaignPromotionDtos.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores immutable campaign links and resolves employee visibility independently of public attribution. */
@Repository
public class CampaignPromotionRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** Supplies local persistence for immutable promotion links and employee scope.
     * @param jdbc transaction-participating MySQL connection access
     * @param cipher authenticated encryption for retained promoter labels and touchpoint text
     */
    public CampaignPromotionRepository(JdbcTemplate jdbc,PiiCipher cipher) {this.jdbc=jdbc;this.cipher=cipher;}
    /** 推广链接创建时读取或锁定的活动身份、负责人、创建者、版本和发布状态。 */
    record Campaign(long id,long owner,long creator,long version,String status) { }

    /** Requires a visible campaign; writes additionally restrict own/assigned scope to its owner. */
    Campaign authorize(Actor actor,String id,boolean write) {
        ScopeSql.Clause scope=write?ScopeSql.campaignWrites(actor):ScopeSql.activities(actor);
        List<Object> args=new ArrayList<>(List.of(id));args.addAll(scope.args());
        return jdbc.query("SELECT a.id,a.owner_id,COALESCE(a.source_created_by_id,a.owner_id),a.version,a.status "
            +"FROM core_activity a WHERE a.public_id=? AND a.activity_type='campaign' AND a.deleted=0 AND ("
            +scope.sql()+")"+(write?" FOR UPDATE":""),(rs,n)->new Campaign(rs.getLong(1),rs.getLong(2),
                rs.getLong(3),rs.getLong(4),rs.getString(5)),args.toArray()).stream().findFirst().orElseThrow(CampaignPromotionRepository::unavailable);
    }

    /** Lists only the caller's links unless all-scope, owner/creator, or lead.assign permits broader visibility. */
    Page list(Actor actor,String id) {
        Campaign campaign=authorize(actor,id,false);
        boolean restricted=!actor.scope().equals("all")&&actor.id()!=campaign.owner()&&actor.id()!=campaign.creator()
            &&!actor.permissions().contains("lead.assign");
        List<Object> args=new ArrayList<>(List.of(campaign.id()));if(restricted)args.add(actor.id());
        List<Link> items=jdbc.query(linkSql()+" WHERE l.activity_id=? AND l.deleted=0"+(restricted?" AND l.promoter_id=?":"")
            +" ORDER BY l.id",(rs,n)->link(rs,id),args.toArray());
        ScopeSql.Clause formScope=ScopeSql.forms(actor);List<Object> formArgs=new ArrayList<>(List.of(campaign.id()));formArgs.addAll(formScope.args());
        List<Choice> forms=jdbc.query("SELECT f.public_id,f.title FROM core_activity_form af JOIN core_crm_form f ON f.id=af.form_id "
            +"WHERE af.activity_id=? AND af.deleted=0 AND f.deleted=0 AND f.status='published' AND ("+formScope.sql()+") ORDER BY f.id",
            (rs,n)->new Choice(rs.getString(1),rs.getString(2)),formArgs.toArray());
        List<Choice> promoters=jdbc.query("SELECT p.public_id FROM core_principal p WHERE p.active=1 AND p.deleted=0 AND p.archived_at IS NULL AND p.credential_change_required=0 AND "
            +"(p.id=? OR EXISTS(SELECT am.id FROM core_activity_member am WHERE am.activity_id=? AND am.principal_id=p.id AND am.deleted=0)) "
            +"ORDER BY p.id",(rs,n)->new Choice(rs.getString(1),accountLabel(rs.getString(1))),campaign.creator(),campaign.id());
        return new Page(items,campaign.version(),forms,promoters);
    }

    /** Creates a link once under the campaign lock after form scope, membership and active-account checks. */
    Created create(Actor actor,String id,Create input) {
        Campaign campaign=authorize(actor,id,true);
        if(campaign.version()!=input.expectedVersion())throw new BusinessException(ErrorCode.VERSION_CONFLICT,"活动版本已变化");
        if(!campaign.status().equals("active"))throw unavailable();
        ScopeSql.Clause scope=ScopeSql.forms(actor);List<Object> args=new ArrayList<>(List.of(input.formId(),campaign.id()));args.addAll(scope.args());
        long form=jdbc.query("SELECT f.id FROM core_crm_form f JOIN core_activity_form af ON af.form_id=f.id "
            +"WHERE f.public_id=? AND af.activity_id=? AND af.deleted=0 AND f.deleted=0 AND f.status='published' AND ("
            +scope.sql()+") FOR UPDATE",(rs,n)->rs.getLong(1),args.toArray()).stream().findFirst().orElseThrow(CampaignPromotionRepository::unavailable);
        long promoter=jdbc.query("SELECT p.id FROM core_principal p WHERE p.public_id=? AND p.active=1 AND p.deleted=0 AND p.archived_at IS NULL AND p.credential_change_required=0 AND "
            +"(p.id=? OR EXISTS(SELECT am.id FROM core_activity_member am WHERE am.activity_id=? AND am.principal_id=p.id AND am.deleted=0)) FOR UPDATE",
            (rs,n)->rs.getLong(1),input.promoterId(),campaign.creator(),campaign.id()).stream().findFirst().orElseThrow(CampaignPromotionRepository::unavailable);
        String publicId="PROMOTION_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_campaign_promotion_link(public_id,activity_id,form_id,promoter_id,created_by,name,promoter_name_cipher) VALUES(?,?,?,?,?,?,?)",
            publicId,campaign.id(),form,promoter,actor.id(),input.name().trim(),cipher.encrypt(accountLabel(input.promoterId())));
        jdbc.update("UPDATE core_activity SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",campaign.id());
        Link result=jdbc.query(linkSql()+" WHERE l.public_id=?",(rs,n)->link(rs,id),publicId).get(0);
        return new Created(result,campaign.version()+1);
    }
    private String linkSql() {return "SELECT l.public_id,l.name,f.public_id form_public,p.public_id promoter_public,l.promoter_name_cipher,"
        +"l.created_at,c.public_id creator_public FROM core_campaign_promotion_link l JOIN core_crm_form f ON f.id=l.form_id "
        +"JOIN core_principal p ON p.id=l.promoter_id JOIN core_principal c ON c.id=l.created_by";}
    private Link link(java.sql.ResultSet rs,String activity)throws java.sql.SQLException {
        String id=rs.getString(1),form=rs.getString(3);
        return new Link(id,rs.getString(2),form,rs.getString(4),cipher.decrypt(rs.getString(5)),rs.getTimestamp(6).toInstant(),
            rs.getString(7),"public-form.html?id="+encode(form)+"&activityId="+encode(activity)+"&promotionLinkId="+encode(id));
    }
    private String accountLabel(String publicId) {return "账号（"+publicId+"）";}
    private String encode(String value) {return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    private static BusinessException unavailable() {return new BusinessException(ErrorCode.NOT_FOUND,"推广活动、表单或推广人员不可用");}
}
