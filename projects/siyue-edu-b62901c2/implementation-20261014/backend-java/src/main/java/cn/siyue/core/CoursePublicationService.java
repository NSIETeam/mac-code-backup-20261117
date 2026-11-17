package cn.siyue.core;

import cn.siyue.core.CoursePublicationDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and publishes immutable teaching snapshots independently of storefront availability.
 * Both methods lock and reload the active caller inside a transaction. Publication also locks the
 * course and commits the snapshot, course version, replay receipt and audit together. Checked
 * exceptions roll back; snapshot cache reads are bypassed inside transactions and writes wait for commit.
 */
@Service
public class CoursePublicationService {
    private final AccessRepository access;
    private final AcademicCatalogRepository catalog;
    private final CoursePublicationRepository publications;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /**
     * Creates the teaching publication transaction boundary.
     * @param access reloads and locks active principals
     * @param catalog reads scoped course status and locks courses for publication
     * @param publications loads and appends immutable teaching snapshots
     * @param mutations hashes commands and stores replay receipts and audit events
     * @param mapper serializes publication commands and receipts
     */
    public CoursePublicationService(AccessRepository access,AcademicCatalogRepository catalog,
            CoursePublicationRepository publications,MutationRepository mutations,ObjectMapper mapper) {
        this.access=access;this.catalog=catalog;this.publications=publications;this.mutations=mutations;this.mapper=mapper;
    }
    /**
     * Reads current teaching-publication status and the most recent immutable snapshot.
     * This transactional path reloads the active caller and checks catalog read scope; the snapshot can be
     * from an earlier publication when the current course is draft, or null if it has never been published.
     * @param actor caller requiring either {@code course.read} or {@code course.publish}
     * @param courseId course public identifier
     * @return current course version and publication flag with its latest stored teaching snapshot
     * @throws BusinessException when the caller is inactive, lacks permission, or cannot read the course
     */
    @Transactional(rollbackFor = Exception.class)
    public View get(Actor actor,String courseId) {
        actor=access.lockActive(actor.id());actor.requireAny("course.read","course.publish");
        return publications.view(catalog.readCourseStatus(actor,courseId));
    }
    /**
     * Publishes the current teaching version after locking the active caller and scoped course.
     * A new publication requires an active, unpublished course at the expected version with 1–200 live
     * lessons. It appends the frozen teaching snapshot and increments the course version. An identical retry
     * returns its original receipt after current permission and course write scope have been rechecked.
     * @param actor caller requiring the exact {@code course.publish} grant; non-all scope also requires
     *     a non-global course in one of the caller's campuses
     * @param courseId course public identifier
     * @param key idempotency key matching {@code [A-Za-z0-9_-]{16,100}}
     * @param input nonnull publication command with expected course version of at least 1
     * @return published snapshot and incremented course version, or the stored receipt for an identical retry
     * @throws BusinessException when permission, active caller, course scope, key, expected version,
     *     active/published state or lesson-count checks fail, or the key conflicts with an earlier command
     * @throws JsonProcessingException when command or receipt JSON cannot be encoded or decoded
     */
    @Transactional(rollbackFor=Exception.class)
    public View publish(Actor actor,String courseId,String key,Input input) throws JsonProcessingException {
        actor.require("course.publish");
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}")||input.expectedVersion()<1)throw new BusinessException(ErrorCode.INVALID_INPUT,"发布版本或幂等键无效");
        actor=access.lockActive(actor.id());actor.require("course.publish");
        var current=catalog.lockCourseStatus(actor,courseId);
        String action="academic.course.publish",hash=mutations.hash(mapper.writeValueAsString(new Command(courseId,input)));
        String replay=mutations.replay(actor,key,action,hash);
        if(replay!=null)return mapper.readValue(replay,View.class);
        View result=publications.publish(actor,current,input);
        mutations.complete(actor,new MutationRepository.RetryWrite(key,action,hash,mapper.writeValueAsString(result),courseId));
        return result;
    }
}
