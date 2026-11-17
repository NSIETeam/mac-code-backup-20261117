package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One transaction validates and moves the entire selection, with actor-bound replay and audit.
 * @see StudentTransferRepository
 * @see MutationRepository
 */
@Service
public class StudentTransferService {
    private final AccessRepository access;
    private final StudentTransferRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Injects actor locking, transfer persistence and retry tracking.
     * @param access repository used to reload and lock the active actor
     * @param repository scoped enrollment transfer and target-option persistence
     * @param mutations idempotency and audit persistence
     * @param mapper serializer for stable mutation request and response bodies
     */
    public StudentTransferService(AccessRepository access,StudentTransferRepository repository,
            MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.repository=repository;this.mutations=mutations;this.mapper=mapper;
    }
    /** Lists paginated target-class options permitted for transferring the selected student.
     * @param actor authenticated actor requesting target options
     * @param studentId public student identifier used to filter compatible targets
     * @param page one-based page number
     * @param size requested page size, from 1 through 100
     * @return scoped compatible target options
     * @throws BusinessException if permission, pagination, student scope, or target checks fail
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public StudentTransferDtos.Options options(Actor actor,String studentId,int page,int size) {
        actor.requireAny("enrollment.manage", "enrollment.transfer", "enrollment.batchTransfer");
        if(page<1||size<1||size>100)throw new BusinessException(ErrorCode.INVALID_INPUT,"分页参数无效");
        return repository.options(actor,studentId,page,size);
    }
    /** Moves one to 500 active enrollments to a scoped target class as one audited, idempotent operation.
     * A single item accepts the exact {@code enrollment.transfer} permission; a multi-item request requires
     * enrollment management or batch-transfer permission. The expected enrollment versions and target validity
     * are checked before the transfer is committed.
     * @param actor authenticated caller whose active status, permission, and enrollment/class scope are checked
     * @param key idempotency key of 16–100 letters, digits, underscores, or hyphens
     * @param input 1–500 unique enrollment IDs with expected versions at least 1, target class ID, and nonblank
     *              transfer reason of at most 500 characters
     * @return the resulting enrollment placements and transfer identifiers
     * @throws BusinessException when permission, input, version, capacity, class compatibility, or scope checks fail
     * @throws JsonProcessingException when request hashing or receipt serialization fails
     */
    @Transactional(rollbackFor=Exception.class)
    public StudentTransferDtos.Receipt transfer(Actor actor,String key,StudentTransferDtos.Input input)
            throws JsonProcessingException {
        validate(key,input);requireTransferPermission(actor,input);
        actor=access.lockActive(actor.id());requireTransferPermission(actor,input);
        repository.authorize(actor,input);
        String action="academic.enrollment.batchTransfer";
        String hash=mutations.hash(mapper.writeValueAsString(input));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,StudentTransferDtos.Receipt.class);
        var results=repository.transfer(actor,input);
        var receipt=new StudentTransferDtos.Receipt(List.copyOf(results),results.size());
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(receipt),input.targetClassId()));
        return receipt;
    }
    private void validate(String key,StudentTransferDtos.Input input) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}"))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"请提供有效幂等键");
        if(input==null||input.items()==null||input.items().isEmpty()||input.items().size()>500
                ||input.items().stream().anyMatch(Objects::isNull))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"请选择1至500条报名");
        if(input.items().stream().anyMatch(i->i.expectedVersion()<1||i.enrollmentId()==null||i.enrollmentId().isBlank())
                ||input.items().stream().map(StudentTransferDtos.Item::enrollmentId).distinct().count()!=input.items().size())
            throw new BusinessException(ErrorCode.INVALID_INPUT,"报名列表重复或版本无效");
        validateTarget(input);
    }
    private void requireTransferPermission(Actor actor,StudentTransferDtos.Input input) {
        if(input.items().size()==1)actor.requireAny("enrollment.manage","enrollment.transfer","enrollment.batchTransfer");
        else actor.requireAny("enrollment.manage","enrollment.batchTransfer");
    }
    private void validateTarget(StudentTransferDtos.Input input) {
        if(input.targetClassId()==null||input.targetClassId().isBlank()||input.reason()==null||input.reason().isBlank()
                ||input.reason().length()>500)throw new BusinessException(ErrorCode.INVALID_INPUT,"请选择目标班级并填写转班原因");
    }
}
