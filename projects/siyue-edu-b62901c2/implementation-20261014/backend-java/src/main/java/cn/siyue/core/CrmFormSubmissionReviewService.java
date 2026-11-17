package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmFormSubmission;
import cn.siyue.core.CrmDtos.CrmFormSubmissionPage;
import cn.siyue.core.CrmDtos.FormSubmissionReviewInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves staff-scoped public-form submission queues and their one-time review workflow.
 * Review decisions are version-checked and idempotent; approving a lead-collecting form may create the mapped
 * customer and lead in the same transaction as the submission decision.
 */
@Service
public class CrmFormSubmissionReviewService {
    private final CrmFormRepository forms;
    private final CrmFormSubmissionRepository submissions;
    private final CrmFormLeadIntake leadIntake;
    private final CrmFormReviewCommands commands;

    /** Creates the review service with form authorization, submission storage, lead intake and retry commands.
     * @param forms form repository used to verify form visibility
     * @param submissions scoped submission queue and versioned review repository
     * @param leadIntake CRM lead/customer creation used for eligible approved submissions
     * @param commands active-principal locking, request hashing and idempotent review receipts
     */
    public CrmFormSubmissionReviewService(CrmFormRepository forms,CrmFormSubmissionRepository submissions,
            CrmFormLeadIntake leadIntake,CrmFormReviewCommands commands){
        this.forms=forms;this.submissions=submissions;this.leadIntake=leadIntake;this.commands=commands;
    }

    /** Lists a page of submissions visible through the selected form and allowed status filter.
     * Requires the modern read pair or an original submission-read grant; legacy reads also enforce publisher visibility.
     * @param actor authenticated caller with form and submission-read grants
     * @param formId public identifier of a form in the caller's scope
     * @param status optional status filter: {@code pending}, {@code approved} or {@code rejected}
     * @param page one-based page number from 1 through 100000
     * @param size page size from 1 through 100
     * @return scoped submissions with decrypted answers and review metadata
     * @throws BusinessException when permission, form scope, status or pagination is invalid
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public CrmFormSubmissionPage submissions(Actor actor,String formId,String status,int page,int size){
        CrmLegacyAccess.read(actor, "crm.form.read");CrmLegacyAccess.read(actor, "crm.form.submission.read");
        if(page<1||page>100000||size<1||size>100||status!=null&&!status.isBlank()
                &&!Set.of("pending","approved","rejected").contains(status))throw invalid("表单审核队列筛选无效");
        forms.authorizeForm(actor,formId);return submissions.list(actor,formId,status,page,size);
    }

    /** Reviews a pending submission once and atomically records any eligible mapped CRM lead.
     * Accepts original {@code lead.create} with publisher/original-superadmin checks, or the modern combination of
     * {@code crm.form.read}, {@code crm.form.submission.read} and
     * {@code crm.form.submission.review}. Lead collection additionally requires customer and lead grants, and
     * activity-backed forms require activity-read permission. The active principal is rechecked under lock;
     * decision, expected version, lead intake and idempotency receipt share the transaction.
     * @param actor authenticated reviewer with submission-review grants and applicable form/campus scope
     * @param submissionId public identifier of the visible submission
     * @param key idempotency key of 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input decision {@code approve} or {@code reject}, nonblank review note up to 2000 characters, optional
     *              customer ID only for an eligible collected lead, separate-household confirmation and version >= 1
     * @return the reviewed submission, or the stored result for an identical retry
     * @throws BusinessException when permission, scope, input, state, version, household confirmation or key checks fail
     * @throws JsonProcessingException when request hashing, lead intake or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public CrmFormSubmission review(Actor actor,String submissionId,String key,FormSubmissionReviewInput input)
            throws JsonProcessingException {
        authorize(actor);requireKey(key);validate(input);
        actor=commands.lockActive(actor.id());authorize(actor);
        submissions.reviewRow(actor,submissionId,false);
        String hash=commands.requestHash(submissionId,input);
        CrmFormSubmission replay=commands.replay(actor,key,hash);
        if(replay!=null)return replay;
        CrmFormSubmissionRepository.ReviewRow row=submissions.reviewRow(actor,submissionId,true);
        if(!row.status().equals("pending")||row.version()!=input.expectedVersion())
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,"表单提交已处理或版本已变化");
        CrmFormLeadIntake.Capture capture=createLeadIfNeeded(actor,row,input,submissionId);
        String customerId=capture==null?null:capture.customerId();
        String leadId=capture==null?null:capture.leadId();
        String note=capture==null?input.note():capture.reviewNote();
        if(input.confirmSeparateHousehold()&&(capture==null||!capture.distinctHouseholdConfirmed()))
            throw invalid("仅在确认为另一家庭时才能提交此确认");
        CrmFormSubmission result=submissions.finishReview(actor,row,new CrmFormSubmissionRepository.ReviewWrite(
            input.decision(),note,input.expectedVersion(),customerId,leadId));
        commands.complete(actor,key,hash,result);return result;
    }

    private CrmFormLeadIntake.Capture createLeadIfNeeded(Actor actor,
            CrmFormSubmissionRepository.ReviewRow row,FormSubmissionReviewInput input,String submissionId)
            throws JsonProcessingException {
        if(input.decision().equals("approve")&&row.kind().equals("lead")&&row.collectLead()){
            CrmLegacyAccess.operation(actor,"lead.create","crm.customer.write","crm.customer.read",
                "crm.lead.write","crm.lead.follow");
            if(row.activityId()!=null)CrmLegacyAccess.read(actor,"crm.activity.read");
            return leadIntake.create(actor,row,input,submissionId,input.note());
        }
        if(input.customerId()!=null&&!input.customerId().isBlank())
            throw invalid("问卷或不转销售线索的表单不能关联客户");
        return null;
    }

    private void authorize(Actor actor){
        CrmLegacyAccess.operation(actor,"lead.create","crm.form.read",
            "crm.form.submission.read","crm.form.submission.review");
    }

    private void validate(FormSubmissionReviewInput input){
        if(input==null||!Set.of("approve","reject").contains(input.decision())||input.note()==null
                ||input.note().isBlank()||input.expectedVersion()<1)throw invalid("表单审核意见、决定或版本无效");
    }

    private static void requireKey(String key){
        if(key==null||!key.matches("[A-Za-z0-9._:-]{16,128}"))throw invalid("幂等键格式无效");
    }

    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_INPUT,message);}
}
