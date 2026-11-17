package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Shared authorization, retry, and serialization operations for form reviews. */
@Component
public class CrmFormReviewCommands {
    private final AccessRepository access;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Wires actor locking and the fixed form-review mutation journal.
     * @param access reloads current principal activity and grants inside the review transaction
     * @param mutations hashes requests and stores actor/key-bound receipts and audit entries
     * @param mapper application JSON mapper used for request and receipt serialization
     */
    public CrmFormReviewCommands(AccessRepository access,MutationRepository mutations,ObjectMapper mapper){
        this.access=access;this.mutations=mutations;this.mapper=mapper;
    }

    /** Reloads and locks the reviewer before the caller checks review authorization.
     * @param actorId internal principal identifier from the authenticated request
     * @return current active principal and effective grants
     * @throws BusinessException if the principal is missing, inactive or otherwise unavailable
     */
    public Actor lockActive(long actorId){return access.lockActive(actorId);}

    /** Hashes the serialized pair of submission identifier and review body.
     * @param submissionId public submission identifier bound into the request digest
     * @param input original review decision, version and optional customer/intake choices
     * @return digest used to distinguish identical retries from conflicting key reuse
     * @throws JsonProcessingException if the review body cannot be serialized
     */
    public String requestHash(String submissionId,CrmDtos.FormSubmissionReviewInput input)
            throws JsonProcessingException {
        return mutations.hash(mapper.writeValueAsString(List.of(submissionId,input)));
    }

    /** Loads the actor-bound receipt for the fixed crm.form.submission.review action.
     * @param actor reviewer whose current permissions and submission scope have already been checked
     * @param key original idempotency key
     * @param hash digest of the submission identifier and original review body
     * @return decoded prior submission result, or null when this key has no completed matching receipt
     * @throws BusinessException if the key is already bound to another action or digest
     * @throws JsonProcessingException if a stored receipt cannot be decoded
     */
    public CrmDtos.CrmFormSubmission replay(Actor actor,String key,String hash)
            throws JsonProcessingException {
        String result=mutations.replay(actor,key,"crm.form.submission.review",hash);
        return result==null?null:mapper.readValue(result,CrmDtos.CrmFormSubmission.class);
    }

    /** Stores the review receipt and audit fact in the caller's transaction.
     * @param actor current locked reviewer recorded as the mutation actor
     * @param key idempotency key reserved for this review
     * @param hash digest matching the original submission and review body
     * @param result successful submission projection, whose ID becomes the mutation resource
     * @throws JsonProcessingException if the completed receipt cannot be serialized
     */
    public void complete(Actor actor,String key,String hash,CrmDtos.CrmFormSubmission result)
            throws JsonProcessingException {
        mutations.complete(actor,new MutationRepository.RetryWrite(key,"crm.form.submission.review",hash,
            mapper.writeValueAsString(result),result.id()));
    }
}
