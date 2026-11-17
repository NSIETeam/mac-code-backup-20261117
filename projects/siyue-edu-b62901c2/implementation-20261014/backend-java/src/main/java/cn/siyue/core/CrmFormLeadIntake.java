package cn.siyue.core;

import cn.siyue.core.CrmDtos.CustomerContactInput;
import cn.siyue.core.CrmDtos.CustomerInput;
import cn.siyue.core.CrmDtos.FormSubmissionReviewInput;
import cn.siyue.core.CrmDtos.LeadInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Creates only the explicitly configured customer and lead facts from an approved public form. */
@Component
public class CrmFormLeadIntake {
    private static final Pattern PHONE=Pattern.compile("\\+?[0-9 ()-]{7,32}");
    private final CrmService crm;
    private final CrmRepository customers;
    private final LeadService leads;

    /** Wires the existing customer and lead services used by an approved form intake.
     * @param crm customer creation service, including its own permission and retry checks
     * @param customers customer lookup and same-campus phone/identity validation
     * @param leads lead creation service invoked with a submission-derived secondary retry key
     */
    public CrmFormLeadIntake(CrmService crm,CrmRepository customers,LeadService leads){
        this.crm=crm;this.customers=customers;this.leads=leads;
    }

    /** Creates the configured household/lead facts within the calling form-review transaction.
     * @param actor authorized reviewer passed to the underlying customer and lead services
     * @param row locked submission with published field mapping and frozen answers
     * @param input review choices, including an optional existing customer or separate-household confirmation
     * @param submissionId public submission ID used to derive stable secondary creation keys
     * @param reviewNote review text to retain, augmented when a duplicate-phone household was explicitly confirmed
     * @return customer and lead identifiers plus the resulting review note and confirmation fact
     * @throws BusinessException if published mapping, phone, customer matching, duplicate confirmation or delegated creation checks fail
     * @throws JsonProcessingException if delegated customer or lead request/receipt serialization fails
     */
    public Capture create(Actor actor,CrmFormSubmissionRepository.ReviewRow row,
            FormSubmissionReviewInput input,String submissionId,String reviewNote)throws JsonProcessingException{
        String studentName=value(row.answers().get(row.leadMapping().get("studentName")));
        String phone=value(row.answers().get(row.leadMapping().get("guardianPhone")));
        if(studentName.isBlank()||!PHONE.matcher(phone).matches())throw invalid("已发布表单的潜客映射数据不完整");
        // This same-campus row lock serializes direct customer creation and concurrent form review.
        customers.authorizeCampus(actor,row.campusId());
        String customerId;String note=reviewNote;boolean distinctConfirmed=false;
        if(input.customerId()!=null&&!input.customerId().isBlank()){
            if(input.confirmSeparateHousehold())throw invalid("关联已有客户时不能同时确认新建另一家庭");
            customers.requirePhoneMatchesCustomer(actor,input.customerId(),phone);customerId=input.customerId();
        }else{
            boolean duplicate=customers.phoneExistsInCampus(row.campusId(),phone);
            if(duplicate!=input.confirmSeparateHousehold())throw invalid(duplicate
                ?"该校区已存在相同监护人电话；请先检索并关联家庭，若为另一家庭请明确确认"
                :"没有发现同电话客户，无需确认新建另一家庭");
            if(duplicate){distinctConfirmed=true;note+="（已确认该号码对应另一家庭）";}
            customerId=crm.createCustomer(actor,secondaryKey("form-customer-",submissionId),
                new CustomerInput(row.campusId(),studentName+"家庭",
                    List.of(new CustomerContactInput("guardian","phone",phone,true)))).id();
        }
        LeadInput lead=new LeadInput(customerId,row.activityId(),null,"online_form",
            studentName+" 在线咨询",optionalValue(row.answers().get(row.leadMapping().get("subject"))),
            optionalValue(row.answers().get(row.leadMapping().get("term"))),
            "表单 "+row.formId()+" 提交："+optionalValue(row.answers().get(row.leadMapping().get("detail"))),null, null);
        String leadId=leads.create(actor,secondaryKey("form-lead-",submissionId),lead).id();
        return new Capture(customerId,leadId,note,distinctConfirmed);
    }

    private static String value(Object value){return value instanceof String text?text.trim():"";}
    private static String optionalValue(Object value){return value instanceof List<?> list
        ?list.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", "))
        :value instanceof String text?text.trim():"";}
    private static String secondaryKey(String prefix,String id){return prefix+id;}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_INPUT,message);}
    /** Facts produced by the approved form intake and carried into its review receipt.
     * @param customerId existing or newly created customer public identifier
     * @param leadId newly created source lead public identifier
     * @param reviewNote review note including any explicit separate-household acknowledgement
     * @param distinctHouseholdConfirmed whether the reviewer confirmed a different household sharing the phone
     */
    public record Capture(String customerId,String leadId,String reviewNote,boolean distinctHouseholdConfirmed) { }
}
