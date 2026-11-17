package cn.siyue.core;

import cn.siyue.core.CrmDtos.PublicFormReceipt;
import cn.siyue.core.CrmDtos.PublicFormSubmissionInput;
import cn.siyue.core.CrmDtos.PublicFormView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves published CRM forms to anonymous visitors and accepts encrypted, rate-limited submissions.
 * It performs no employee authentication; campaign/promotion context is validated against the published form.
 */
@Service
public class PublicCrmFormService {
    /** Public-request attribution passed by the controller; it does not represent an authenticated employee.
     * @param address normalized source address used for rate limiting
     * @param visitor optional opaque visitor identity used to associate public submission facts; address-only calls
     *                may omit it
     */
    public record VisitorContext(String address,String visitor) { }
    private static final Pattern PHONE=Pattern.compile("1[3-9][0-9]{9}");
    private static final Pattern EMAIL=Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}");
    private static final Set<String> LAYOUT=Set.of("description","divider","image");
    private final CrmFormRepository forms;
    private final CrmFormSubmissionRepository submissions;
    private final CrmFormRateLimiter limiter;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Creates the anonymous form workflow with published-form reads, submission persistence, limits, and receipts.
     * @param forms published definitions and form collection/trial/reward settings
     * @param submissions campaign-link validation and encrypted submission persistence
     * @param limiter public submission rate limiter
     * @param mutations idempotency receipt and audit persistence
     * @param mapper serializer used to validate and hash submission bodies
     */
    public PublicCrmFormService(CrmFormRepository forms,CrmFormSubmissionRepository submissions,
            CrmFormRateLimiter limiter,MutationRepository mutations,ObjectMapper mapper){
        this.forms=forms;this.submissions=submissions;this.limiter=limiter;
        this.mutations=mutations;this.mapper=mapper;
    }

    /** Returns the public definition for the requested published form/activity pair.
     * @param formId public ID of the form
     * @param activityId optional public ID of the associated activity
     * @return published form fields and public collection settings, with a fresh submission key
     * @throws BusinessException when the form/activity pair is unavailable or outside the public form contract
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public PublicFormView publicForm(String formId,String activityId){return publicForm(formId,activityId,null);}

    /** Resolves an optional promotion link before returning the matching published form revision.
     * @param formId public ID of the form
     * @param activityId optional public ID of the associated activity
     * @param promotionLinkId optional public ID of a link validated against this form and activity
     * @return published form fields and public collection settings, with a fresh submission key
     * @throws BusinessException when the form/activity/link association is invalid or unavailable
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public PublicFormView publicForm(String formId,String activityId,String promotionLinkId){
        submissions.validatePromotion(formId,activityId,promotionLinkId);
        CrmFormRepository.PublicDefinition definition=forms.publicDefinition(formId,activityId);
        var trial=forms.publicTrial(definition);
        Map<String,Object> settings=new java.util.LinkedHashMap<>();
        var collection=forms.collection(definition);if(collection!=null) {
            settings.put("start",collection.start());settings.put("end",collection.end());settings.put("quantity",collection.quantity());
            settings.put("maxPerIdentity",collection.maxPerIdentity());settings.put("source",collection.source());
        }
        if(trial!=null) settings.put("trial",trial);
        var reward=forms.publicReward(definition);if(reward!=null) settings.put("reward",reward);
        return new PublicFormView(definition.id(),definition.title(),definition.description(),definition.kind(),definition.collectLead(),
            definition.revision(),definition.activityId(),definition.fields(),UUID.randomUUID().toString(),settings,definition.campusId());
    }

    /** Accepts a public submission using the request address for rate limiting and stores answers encrypted.
     * @param formId public ID of the form being submitted
     * @param headerKey submission key supplied in the request header; must equal the body submissionId
     * @param sourceAddress normalized request source address used by the public limiter
     * @param input answer map, optional activity, trial booking, and promotion link validated against the published form
     * @return receipt describing whether the submission was received and any trial/reward result
     * @throws BusinessException when the key, form, answers, activity, rate limit, or submission state is invalid
     * @throws JsonProcessingException when the submission body cannot be serialized for hashing
     */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PublicFormReceipt submit(String formId,String headerKey,String sourceAddress,PublicFormSubmissionInput input) throws JsonProcessingException {
        return submitFromVisitor(formId,headerKey,new VisitorContext(sourceAddress,null),input);
    }

    /** Accepts a public submission with explicit visitor attribution and stores its answers encrypted.
     * No employee identity or staff permission is required or inferred from the visitor context.
     * @param formId public ID of the form being submitted
     * @param headerKey submission key supplied in the request header; must equal the body submissionId
     * @param context normalized source address and opaque public visitor identity
     * @param input answer map, optional activity, trial booking, and promotion link validated against the published form
     * @return receipt describing whether the submission was received and any trial/reward result
     * @throws BusinessException when the key, form, answers, visitor context, rate limit, or submission state is invalid
     * @throws JsonProcessingException when the submission body cannot be serialized for hashing
     */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PublicFormReceipt submitFromVisitor(String formId,String headerKey,VisitorContext context,PublicFormSubmissionInput input)
            throws JsonProcessingException {
        if(input==null||input.answersById()==null||headerKey==null||!headerKey.equals(input.submissionId()))throw invalid("提交编号无效");
        CrmFormRepository.PublicDefinition definition=forms.lockPublicDefinition(formId,input.activityId());
        Map<String,Object> answers=validateAnswers(definition.fields(),input.answersById());
        String body=mapper.writeValueAsString(input);
        if(body.length()>64000)throw invalid("表单内容超过限制");
        if(!limiter.allow(definition.formRowId(),context.address(),Instant.now()))throw new BusinessException(ErrorCode.RATE_LIMITED,"提交频率过高，请稍后重试");
        String hash=mutations.hash(body);
        return submissions.submit(new CrmFormSubmissionRepository.PublicSubmissionWrite(definition,
            input.submissionId(),hash,answers,input.activityId(),input.trialBooking()==null?null:input.trialBooking().slotId(),context.visitor(),input.promotionLinkId()));
    }

    private Map<String,Object> validateAnswers(List<CrmDtos.FormFieldInput> fields,Map<String,Object> supplied){
        Map<String,CrmDtos.FormFieldInput> byId=new HashMap<>();fields.forEach(field->byId.put(field.id(),field));
        if(supplied.size()>fields.size()||supplied.keySet().stream().anyMatch(id->!byId.containsKey(id)))throw invalid("表单包含未定义字段");
        Map<String,Object> result=new HashMap<>();
        for(CrmDtos.FormFieldInput field:fields){
            validateAnswer(field,supplied,result);
        }
        return Map.copyOf(result);
    }
    private void validateAnswer(CrmDtos.FormFieldInput field,Map<String,Object> supplied,Map<String,Object> result){
        if(LAYOUT.contains(field.type())){if(supplied.containsKey(field.id()))throw invalid("展示字段不能提交答案");return;}
        Object raw=supplied.get(field.id());
        if(raw==null){requireIfMissing(field);return;}
        if(field.type().equals("multiple")){validateMultiple(field,raw,result);return;}
        validateScalar(field,raw,result);
    }
    private void requireIfMissing(CrmDtos.FormFieldInput field){
        if(field.required())throw invalid("请填写必填项："+field.title());
    }
    private void validateMultiple(CrmDtos.FormFieldInput field,Object raw,Map<String,Object> result){
        if(!(raw instanceof List<?> values)||values.size()>100||values.stream().anyMatch(v->!(v instanceof String s)||!field.options().contains(s)))throw invalid("多选答案无效："+field.title());
        if(field.required()&&values.isEmpty())throw invalid("请填写必填项："+field.title());
        result.put(field.id(),List.copyOf(values));
    }
    private void validateScalar(CrmDtos.FormFieldInput field,Object raw,Map<String,Object> result){
        if(!(raw instanceof String value)||value.length()>2000)throw invalid("答案格式无效："+field.title());
        value=value.trim();if(field.type().equals("phone")) value=value.replaceAll("[\\s()-]","").replaceFirst("^(?:\\+86|0086)(?=1\\d{10}$)","");
        if(value.isBlank()){requireIfMissing(field);return;}
        validateScalarFormat(field,value);
        result.put(field.id(),value);
    }
    private void validateScalarFormat(CrmDtos.FormFieldInput field,String value){
        if(field.type().equals("phone")&&!PHONE.matcher(value).matches())throw invalid("电话格式无效");
        if(field.type().equals("email")&&!EMAIL.matcher(value).matches())throw invalid("邮箱格式无效");
        validateChoice(field,value);
        validateDateTime(field,value);
    }
    private void validateChoice(CrmDtos.FormFieldInput field,String value){
        if(Set.of("single","dropdown","gender").contains(field.type())&&!field.options().contains(value))throw invalid("请选择有效选项："+field.title());
    }
    private void validateDateTime(CrmDtos.FormFieldInput field,String value){
        if(field.type().equals("date")){try{java.time.LocalDate.parse(value);}catch(Exception ex){throw invalid("日期格式无效："+field.title());}}
        if(field.type().equals("time")){try{java.time.LocalTime.parse(value);}catch(Exception ex){throw invalid("时间格式无效："+field.title());}}
    }
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_INPUT,message);}
}
