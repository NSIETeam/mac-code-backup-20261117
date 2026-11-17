package cn.siyue.core;

import cn.siyue.core.CrmDtos.ActivityFormLink;
import cn.siyue.core.CrmDtos.MarketingForm;
import cn.siyue.core.CrmDtos.MarketingFormInput;
import cn.siyue.core.CrmDtos.MarketingFormPage;
import cn.siyue.core.CrmDtos.MarketingFormVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates form design, immutable publication revisions and activity associations. */
@Service
public class CrmFormService {
    private static final Set<String> KINDS=Set.of("lead","survey","custom");
    private static final Set<String> FIELD_TYPES=Set.of("name","phone","email","text","textarea","single","multiple","dropdown","gender","date","time","description","divider","image");
    private final AccessRepository access;
    private final CrmFormRepository forms;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Injects permission locking, persistence, idempotency and JSON validation.
     * @param access active-principal locking repository
     * @param forms form, activity and scope persistence repository
     * @param mutations idempotency and audit persistence repository
     * @param mapper JSON serializer used to bind retry bodies
     */
    public CrmFormService(AccessRepository access,CrmFormRepository forms,MutationRepository mutations,ObjectMapper mapper){
        this.access=access;this.forms=forms;this.mutations=mutations;this.mapper=mapper;
    }

    /** Creates one validated form draft and an audit/idempotency record atomically.
     * @param actor authenticated actor creating the form
     * @param key stable idempotency key for this request
     * @param input form metadata, fields, and optional lead-field mapping
     * @return created form or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if permission, scope, validation, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public MarketingForm create(Actor actor,String key,MarketingFormInput input)throws JsonProcessingException{
        CrmLegacyAccess.operation(actor, "lead.create", "crm.form.write");validate(input);requireKey(key);
        actor=active(actor,"crm.form.write");forms.authorizeCampus(actor,input.campusId());
        String hash=mutations.hash(mapper.writeValueAsString(input));String replay=mutations.replay(actor,key,"crm.form.create",hash);
        if(replay!=null){return mapper.readValue(replay,MarketingForm.class);}
        MarketingForm form=forms.create(actor,input);
        complete(actor,new MutationRepository.RetryWrite(key,"crm.form.create",hash,mapper.writeValueAsString(form),form.id()));
        return form;
    }

    /** Updates an unpublished draft with optimistic version control.
     * @param actor authenticated actor updating the form
     * @param formId public identifier of the form
     * @param key stable idempotency key for this request
     * @param input replacement form metadata and fields
     * @param version expected current form version
     * @return updated form or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if permission, scope, draft state, version, validation, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public MarketingForm update(Actor actor,String formId,String key,MarketingFormInput input,MarketingFormVersion version)throws JsonProcessingException{
        CrmLegacyAccess.operation(actor, "lead.create", "crm.form.write");validate(input);requireKey(key);actor=active(actor,"crm.form.write");
        forms.authorizeForm(actor,formId);forms.authorizeCampus(actor,input.campusId());
        String hash=mutations.hash(mapper.writeValueAsString(List.of(formId,input,version)));
        String replay=mutations.replay(actor,key,"crm.form.update",hash);
        if(replay!=null){return mapper.readValue(replay,MarketingForm.class);}
        MarketingForm form=forms.update(actor,formId,input,version.expectedVersion());
        complete(actor,new MutationRepository.RetryWrite(key,"crm.form.update",hash,mapper.writeValueAsString(form),form.id()));return form;
    }

    /** Publishes a validated immutable revision; surveys can never create sales leads.
     * @param actor authenticated actor publishing the form
     * @param formId public identifier of the form
     * @param key stable idempotency key for this request
     * @param version expected current form version
     * @return published form or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if permission, scope, validation, version, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public MarketingForm publish(Actor actor,String formId,String key,MarketingFormVersion version)throws JsonProcessingException{
        CrmLegacyAccess.operation(actor, "lead.create", "crm.form.publish");requireKey(key);actor=active(actor,"crm.form.publish");forms.authorizeForm(actor,formId);
        MarketingForm current=forms.read(actor,formId);validate(current);
        String hash=mutations.hash(mapper.writeValueAsString(List.of(formId,version)));
        String replay=mutations.replay(actor,key,"crm.form.publish",hash);
        if(replay!=null){return mapper.readValue(replay,MarketingForm.class);}
        MarketingForm form=forms.publish(actor,formId,version.expectedVersion());
        complete(actor,new MutationRepository.RetryWrite(key,"crm.form.publish",hash,mapper.writeValueAsString(form),form.id()));return form;
    }

    /** Stops new public submissions without removing their history.
     * @param actor authenticated actor unpublishing the form
     * @param formId public identifier of the form
     * @param key stable idempotency key for this request
     * @param version expected current form version
     * @return unpublished form or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if permission, scope, version, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public MarketingForm unpublish(Actor actor,String formId,String key,MarketingFormVersion version)
            throws JsonProcessingException {
        CrmLegacyAccess.operation(actor, "lead.create", "crm.form.publish");requireKey(key);actor=active(actor,"crm.form.publish");
        forms.authorizeForm(actor,formId);
        String hash=mutations.hash(mapper.writeValueAsString(List.of(formId,version)));
        String replay=mutations.replay(actor,key,"crm.form.unpublish",hash);
        if(replay!=null){return mapper.readValue(replay,MarketingForm.class);}
        MarketingForm form=forms.unpublish(actor,formId,version.expectedVersion());
        complete(actor,new MutationRepository.RetryWrite(key,"crm.form.unpublish",hash,
            mapper.writeValueAsString(form),form.id()));
        return form;
    }

    /** Reads one form through the actor's permitted scope.
     * @param actor authenticated actor requesting the form
     * @param id public identifier of the form
     * @return the scoped form
     * @throws BusinessException if the actor lacks form-read permission or cannot access the form
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public MarketingForm read(Actor actor,String id) {CrmLegacyAccess.read(actor, "crm.form.read");return forms.read(actor,id);}

    /** Lists scoped forms with bounded filters and pagination.
     * @param actor authenticated actor whose scope is applied
     * @param campusId optional campus filter
     * @param status optional form lifecycle status
     * @param page one-based page number
     * @param size maximum number of rows requested
     * @return the scoped form page
     * @throws BusinessException if permission, filter, or pagination checks fail
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public MarketingFormPage list(Actor actor,String campusId,String status,int page,int size){
        CrmLegacyAccess.read(actor, "crm.form.read");
        if(page<1||page>100000||size<1||size>100||status!=null&&!status.isBlank()&&!Set.of("draft","published","closed").contains(status)){
            throw invalid("表单查询条件无效");}
        return forms.list(actor,campusId,status,page,size);
    }

    /** Links a published form to a same-campus activity with audit and idempotency.
     * @param actor authenticated actor creating the association
     * @param activityId public identifier of the activity
     * @param key stable idempotency key for this request
     * @param input form association details
     * @return the created association or the prior result for an identical retry
     * @throws JsonProcessingException if the request or result cannot be serialized for idempotency
     * @throws BusinessException if permission, scope, publication, validation, or retry checks fail
     */
    @Transactional(rollbackFor=Exception.class)
    public ActivityFormLink link(Actor actor,String activityId,String key,CrmDtos.ActivityFormLinkInput input)throws JsonProcessingException{
        CrmLegacyAccess.operation(actor, "lead.create", "crm.activity.write");CrmLegacyAccess.operation(actor, "lead.create", "crm.form.write");requireKey(key);actor=active(actor,"crm.activity.write");
        CrmLegacyAccess.operation(actor, "lead.create", "crm.form.write");forms.authorizeActivity(actor,activityId);forms.authorizeForm(actor,input.formId());
        boolean legacy=!actor.permissions().containsAll(Set.of("crm.activity.write","crm.form.write"));
        if(legacy) {
            if(input.expectedVersion()==null||input.expectedVersion()<1)throw invalid("关联活动表单需要当前活动版本");
            forms.authorizeCampaignLink(actor,activityId);
        }
        String hash=mutations.hash(mapper.writeValueAsString(List.of(activityId,input)));
        String replay=mutations.replay(actor,key,"crm.activity.form.link",hash);
        if(replay!=null){return mapper.readValue(replay,ActivityFormLink.class);}
        ActivityFormLink link=legacy||input.expectedVersion()!=null?forms.linkCampaign(actor,activityId,input)
            :forms.link(actor,activityId,input.formId());
        complete(actor,new MutationRepository.RetryWrite(key,"crm.activity.form.link",hash,mapper.writeValueAsString(link),activityId+":"+input.formId()));return link;
    }

    /** Lists published forms associated with an activity under both resource scopes.
     * @param actor authenticated actor whose activity and form scopes are checked
     * @param activityId public identifier of the activity
     * @return linked forms visible to the actor
     * @throws BusinessException if either read permission is missing or activity scope is denied
     */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public List<ActivityFormLink> activityForms(Actor actor,String activityId){
        CrmLegacyAccess.read(actor, "crm.activity.read");CrmLegacyAccess.read(actor, "crm.form.read");
        forms.authorizeActivity(actor,activityId);return forms.activityForms(actor,activityId);
    }

    private Actor active(Actor actor,String permission){Actor locked=access.lockActive(actor.id());CrmLegacyAccess.operation(locked, "lead.create", permission);return locked;}
    private void complete(Actor actor,MutationRepository.RetryWrite write){
        mutations.complete(actor,write);
    }
    private void validate(MarketingFormInput form){
        if(form==null||form.fields()==null||form.fields().isEmpty()||!KINDS.contains(form.formKind())
                ||form.formKind().equals("survey")&&form.collectLead())throw invalid("表单类型、字段或线索设置无效");
        Map<String,CrmDtos.FormFieldInput> fields=new HashMap<>();
        for(CrmDtos.FormFieldInput field:form.fields()) registerField(fields,field);
        Map<String,String> mapping=form.leadFieldMapping()==null?Map.of():form.leadFieldMapping();
        if(!form.collectLead())return;
        validateLeadMapping(mapping,fields);
    }
    private void registerField(Map<String,CrmDtos.FormFieldInput> fields,CrmDtos.FormFieldInput field){
        if(field==null||!FIELD_TYPES.contains(field.type())||fields.putIfAbsent(field.id(),field)!=null)throw invalid("表单字段类型或编号重复");
        boolean choice=Set.of("single","multiple","dropdown","gender").contains(field.type());
        if(choice)validateChoiceOptions(field);
        else if(field.options()!=null&&!field.options().isEmpty())throw invalid("非选择字段不能配置选项");
    }
    private void validateChoiceOptions(CrmDtos.FormFieldInput field){
        if(field.options()==null||field.options().isEmpty()||field.options().stream().distinct().count()!=field.options().size())throw invalid("选择字段须提供不重复选项");
    }
    private void validateLeadMapping(Map<String,String> mapping,Map<String,CrmDtos.FormFieldInput> fields){
        String name=mapping.get("studentName"),phone=mapping.get("guardianPhone");
        if(name==null||phone==null||!fields.containsKey(name)||!fields.containsKey(phone)
                ||!Set.of("name","text").contains(fields.get(name).type())||!fields.get(phone).type().equals("phone"))throw invalid("可生成线索的表单必须映射学生姓名与监护人电话");
        for(Map.Entry<String,String> entry:mapping.entrySet())validateMappingEntry(entry,fields);
    }
    private void validateMappingEntry(Map.Entry<String,String> entry,Map<String,CrmDtos.FormFieldInput> fields){
        if(!Set.of("studentName","guardianPhone","subject","term","detail").contains(entry.getKey())||!fields.containsKey(entry.getValue()))throw invalid("线索映射的字段不存在");
    }
    private void validate(MarketingForm form){
        Map<String,String> mapping=form.leadFieldMapping()==null?Map.of():form.leadFieldMapping();
        if(form.formKind().equals("survey")&&form.collectLead())throw invalid("问卷不能登记为销售线索");
        if(form.collectLead()&&(!mapping.containsKey("studentName")||!mapping.containsKey("guardianPhone")))throw invalid("缺少销售线索字段映射");
    }
    private static void requireKey(String key){if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}"))throw invalid("请提供有效幂等键");}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_INPUT,message);}
}
