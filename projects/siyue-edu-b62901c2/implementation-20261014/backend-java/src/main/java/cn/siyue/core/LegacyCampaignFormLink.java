package cn.siyue.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies the original versioned campaign.linkForm operation inside the enclosing service transaction. */
final class LegacyCampaignFormLink {
    private final JdbcTemplate jdbc;
    LegacyCampaignFormLink(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** Authorizes a campaign write without changing its version, including before idempotent replay. */
    long authorize(Actor actor,String activityId,boolean lock) {
        ScopeSql.Clause scope=ScopeSql.campaignWrites(actor);
        List<Object> args=new ArrayList<>(List.of(activityId));args.addAll(scope.args());
        return jdbc.query("SELECT a.id FROM core_activity a WHERE a.public_id=? AND a.deleted=0 "
            +"AND a.activity_type='campaign' AND ("+scope.sql()+")"+(lock?" FOR UPDATE":""),
            (rs,n)->rs.getLong(1),args.toArray()).stream().findFirst().orElseThrow(()->
                new BusinessException(ErrorCode.NOT_FOUND,"营销活动不存在或不在授权范围"));
    }

    /** Links a draft/published form once and records publication members at the reviewed campaign version. */
    void link(Actor actor,String activityId,CrmDtos.ActivityFormLinkInput input) {
        long activity=authorize(actor,activityId,true);
        record Campaign(Long campus,String status,long version) { }
        Campaign campaign=jdbc.queryForObject("SELECT campus_id,status,version FROM core_activity WHERE id=?",
            (rs,n)->new Campaign(rs.getObject(1,Long.class),rs.getString(2),rs.getLong(3)),activity);
        if(input.expectedVersion()==null||input.expectedVersion()<1)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"关联活动表单需要当前活动版本");
        if(campaign.version()!=input.expectedVersion())throw new BusinessException(ErrorCode.ALREADY_EXISTS,"活动版本已变化");
        if(!List.of("draft","active").contains(campaign.status()))throw new BusinessException(ErrorCode.INVALID_INPUT,"已结束活动不能新增表单");
        ScopeSql.Clause scope=ScopeSql.forms(actor);List<Object> args=new ArrayList<>(List.of(input.formId()));args.addAll(scope.args());
        record Form(long id,Long campus) { }
        Form form=jdbc.query("SELECT f.id,f.campus_id FROM core_crm_form f WHERE f.public_id=? AND f.deleted=0 "
            +"AND f.status IN ('draft','published') AND ("+scope.sql()+") FOR UPDATE",
            (rs,n)->new Form(rs.getLong(1),rs.getObject(2,Long.class)),args.toArray()).stream().findFirst().orElseThrow(()->
                new BusinessException(ErrorCode.NOT_FOUND,"表单不存在或不在授权范围"));
        if(campaign.campus()!=null&&form.campus()!=null&&!Objects.equals(campaign.campus(),form.campus()))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"关联表单须属于活动校区");
        Integer exists=jdbc.queryForObject("SELECT COUNT(id) FROM core_activity_form WHERE activity_id=? AND form_id=? AND deleted=0",
            Integer.class,activity,form.id());
        if(exists!=null&&exists>0)return;
        jdbc.update("INSERT INTO core_activity_form(activity_id,form_id,linked_by) VALUES(?,?,?)",activity,form.id(),actor.id());
        jdbc.update("INSERT INTO core_activity_member(activity_id,principal_id,member_role) "
            +"SELECT ?,p.id,'participant' FROM core_principal p WHERE p.active=1 AND p.deleted=0 AND ("
            +"p.id=(SELECT owner_id FROM core_crm_form WHERE id=?) OR p.id IN (SELECT r.published_by "
            +"FROM core_crm_form_revision r JOIN core_crm_form f ON f.id=r.form_id WHERE r.form_id=? "
            +"AND r.revision_no=f.current_revision AND r.deleted=0)) ON DUPLICATE KEY UPDATE deleted=0",
            activity,form.id(),form.id());
        jdbc.update("UPDATE core_activity SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",activity);
    }
}
