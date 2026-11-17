package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and updates collection settings for authorized CRM forms while preserving draft-version and retry rules. */
@Service
public class FormCollectionService {
    /** Versioned replacement for a form's collection settings.
     * @param settings complete validated collection settings for the draft form
     * @param expectedVersion current form version; must be at least 1 and match the locked draft
     */
    public record Update(@NotNull @Valid FormCollectionRepository.Settings settings,@Min(1) long expectedVersion) { }
    private final AccessRepository access;
    private final CrmFormRepository forms;
    private final FormCollectionRepository settings;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Creates the form-settings service with actor locking, form authorization, persistence, receipts, and JSON support.
     * @param access active-principal lookup and lock for write-time authorization rechecks
     * @param forms form scope checks, state/version reads, and draft locking
     * @param settings collection-settings persistence
     * @param mutations idempotency receipt and audit persistence
     * @param mapper serializer used to hash updates and persist replay results
     */
    public FormCollectionService(AccessRepository access,CrmFormRepository forms,FormCollectionRepository settings,MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.forms=forms;this.settings=settings;this.mutations=mutations;this.mapper=mapper;
    }
    /** Reads collection settings for a form visible to the actor.
     * @param actor caller requiring {@code crm.form.read}
     * @param id public ID of the authorized form
     * @return current settings associated with that form
     * @throws BusinessException when permission is denied or the form is outside the actor's scope
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public FormCollectionRepository.Settings read(Actor actor,String id) {
        CrmLegacyAccess.read(actor, "crm.form.read");forms.authorizeForm(actor,id);return settings.read(settings.formId(id));
    }
    /** Replaces settings only while the form remains a draft at the submitted version.
     * @param actor caller requiring {@code crm.form.write}; active status and form scope are rechecked under lock
     * @param id public ID of the form to update
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input nonnull settings and expected form version
     * @return the updated draft form, or the prior result for an identical retry
     * @throws BusinessException when permission, scope, key, form state, version, or input validation fails
     * @throws JsonProcessingException when request hashing or replay-result serialization fails
     */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CrmDtos.MarketingForm update(Actor actor,String id,String key,Update input) throws JsonProcessingException {
        actor=access.lockActive(actor.id());CrmLegacyAccess.operation(actor, "lead.create", "crm.form.write");forms.authorizeForm(actor,id);
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}")) throw new BusinessException(ErrorCode.INVALID_INPUT,"幂等键无效");
        String hash=mutations.hash(mapper.writeValueAsString(java.util.List.of(id,input)));
        String replay=mutations.replay(actor,key,"crm.form.collection.update",hash);if(replay!=null) return mapper.readValue(replay,CrmDtos.MarketingForm.class);
        forms.lockPublicOrDraft(id);var form=forms.read(actor,id);
        if(!form.status().equals("draft")||form.version()!=input.expectedVersion()) throw new BusinessException(ErrorCode.PREVIEW_STALE,"表单状态或版本已变化");
        settings.save(actor,form,input.settings());forms.advanceDraftVersion(id);
        var result=forms.read(actor,id);mutations.complete(actor,new MutationRepository.RetryWrite(key,"crm.form.collection.update",hash,mapper.writeValueAsString(result),id));return result;
    }
}
