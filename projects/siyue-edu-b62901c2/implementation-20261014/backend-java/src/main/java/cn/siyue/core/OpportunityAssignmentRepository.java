package cn.siyue.core;

import cn.siyue.core.OpportunityNeedDtos.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Receiving eligibility is shared by the directory, creation and explicit handoff. */
@Repository
public class OpportunityAssignmentRepository {
    /** Resolved eligible principal identifiers; no credential or contact data is exposed.
     * @param id database principal identifier used by assignment writes
     * @param publicId public principal identifier returned to the caller
     */
    public record Staff(long id,String publicId) { }
    private static final String ACTIVE="p.active=1 AND p.deleted=0 AND p.archived_at IS NULL "
        + "AND p.credential_change_required=0 AND (("
        + CrmPrincipalEligibility.permission("crm.opportunity.read") + " AND "
        + CrmPrincipalEligibility.permission("crm.opportunity.write") + ") OR (p.scope_type<>'assigned' AND "
        + CrmPrincipalEligibility.permission("lead.follow") + "))";
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final OpportunityNeedReadRepository reads;
    /** Uses the caller's transaction for eligibility locks, handoff and encrypted event insertion.
     * @param jdbc transaction-bound MySQL access
     * @param cipher encryption keyring for assignment event details
     * @param reads opportunity/customer scope and row-lock queries
     */
    public OpportunityAssignmentRepository(JdbcTemplate jdbc,PiiCipher cipher,OpportunityNeedReadRepository reads) {
        this.jdbc=jdbc; this.cipher=cipher; this.reads=reads;
    }
    /** Resolves a ready recipient with effective grants and a compatible opportunity campus.
     * @param actor current assigner; selecting another employee also requires assignment permission
     * @param owner proposed owner's public identifier
     * @param campus opportunity campus identifier, or null for a campus-less record
     * @param lock whether to lock the matching principal for a mutation
     * @return eligible internal and public principal identifiers
     * @throws BusinessException if assignment permission or recipient readiness, grants or scope fails
     */
    public Staff eligible(Actor actor,String owner,Long campus,boolean lock) {
        if(!actor.publicId().equals(owner))CrmLegacyAccess.operation(actor, "lead.assign", "crm.opportunity.assign");
        var args=new ArrayList<Object>(); args.add(owner); args.add(campus);
        var rows=jdbc.query("SELECT p.id,p.public_id FROM core_principal p WHERE p.public_id=? AND "+ACTIVE+" AND ("+eligibility()+")"+(lock?" FOR UPDATE":""),
            (rs,n)->new Staff(rs.getLong(1),rs.getString(2)),args.toArray());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.FORBIDDEN,"员工未启用、缺少商机办理权限或不能接收该校区商机");
        return rows.get(0);
    }
    private String eligibility() {
        return "p.scope_type IN('all','own','assigned') OR p.scope_type='campus' AND EXISTS(SELECT id FROM core_principal_campus WHERE principal_id=p.id AND campus_id=? AND deleted=0)";
    }
    /** Lists recipients for exactly one currently accessible customer or opportunity.
     * @param actor current assigner, also checked for customer intake or opportunity read access
     * @param query one source identifier and bounded pagination
     * @return eligible principal choices and count under the same readiness and campus predicate
     * @throws BusinessException if permissions, source selection, source scope or pagination are invalid
     */
    public AssigneePage list(Actor actor,AssigneeQuery query) {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.opportunity.assign"); boolean byCustomer=OpportunityNeedSupport.clean(query.customerId())!=null;
        if(byCustomer==(OpportunityNeedSupport.clean(query.opportunityId())!=null))OpportunityNeedSupport.invalid("客户与商机必须且只能选择一项");
        Long campus;
        if(byCustomer) { OpportunityNeedSupport.intake(actor); campus=reads.customer(actor,query.customerId(),false).campusId(); }
        else { CrmLegacyAccess.read(actor, "crm.opportunity.read"); campus=reads.authorized(actor,query.opportunityId(),false).campusId(); }
        int page=OpportunityNeedSupport.page(query.page()),size=OpportunityNeedSupport.size(query.size());
        String where=" WHERE "+ACTIVE+" AND ("+eligibility()+")";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_principal p"+where,Long.class,campus);
        var items=jdbc.query("SELECT p.public_id FROM core_principal p"+where+" ORDER BY p.public_id LIMIT ? OFFSET ?",
            (rs,n)->new Assignee(rs.getString(1),rs.getString(1)),campus,size,(long)(page-1)*size);
        return new AssigneePage(items,total,page,size);
    }
    /** Locks and transfers an opportunity, appending an encrypted assignment event.
     * @param actor current writer with assignment permission
     * @param id opportunity public identifier
     * @param input proposed owner, reason and expected version
     * @return minimal handoff receipt indicating whether the actor retains read visibility
     * @throws BusinessException if permissions, source scope, version or recipient eligibility fail, or owner is unchanged
     */
    public AssignmentReceipt assign(Actor actor,String id,Assignment input) {
        CrmLegacyAccess.operation(actor, "lead.assign", "crm.opportunity.assign"); CrmLegacyAccess.operation(actor, "lead.follow", "crm.opportunity.write");
        var old=reads.authorized(actor,id,true);
        if(old.version()!=input.expectedVersion())OpportunityNeedSupport.stale();
        Staff owner=eligible(actor,input.ownerId(),old.campusId(),true);
        if(owner.id()==old.ownerId())OpportunityNeedSupport.invalid("负责人没有变化");
        int count=jdbc.update("UPDATE core_opportunity SET owner_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND version=?",
            owner.id(),old.id(),input.expectedVersion());
        if(count!=1)OpportunityNeedSupport.stale();
        jdbc.update("INSERT INTO core_opportunity_need_event(public_id,opportunity_id,actor_id,event_type,detail_cipher) VALUES(?,?,?,'assigned',?)",
            "NEVT_"+UUID.randomUUID(),old.id(),actor.id(),cipher.encrypt("原负责人："+old.ownerPublicId()+"；新负责人："+owner.publicId()+"；"+input.reason().trim()));
        boolean visible=reads.readable(actor,reads.raw(id,false));
        return new AssignmentReceipt(id,owner.publicId(),old.version()+1,visible?"visible":"left_scope");
    }
}
