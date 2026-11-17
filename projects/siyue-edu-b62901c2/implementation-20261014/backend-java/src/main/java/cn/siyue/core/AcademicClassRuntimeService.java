package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import cn.siyue.core.AcademicClassRuntimeDtos.RecordInput;
import cn.siyue.core.AcademicClassRuntimeDtos.RecordReceipt;
import cn.siyue.core.AcademicClassRuntimeDtos.RelocateInput;
import cn.siyue.core.AcademicClassRuntimeDtos.RelocateReceipt;
import cn.siyue.core.AcademicClassRuntimeDtos.ScheduleInput;
import cn.siyue.core.AcademicClassRuntimeDtos.ScheduleReceipt;
import cn.siyue.core.AcademicClassRuntimeDtos.SubstituteInput;
import cn.siyue.core.AcademicClassRuntimeDtos.SubstituteReceipt;

/** Coordinates whole-class schedules and locations, substitute teachers and teaching records.
 * Every command reloads active grants under the principal lock and shares one rollback-on-exception transaction
 * with repository mutations, encrypted retry receipts and audit. Repository writes enforce class scope,
 * resource constraints and expected versions; a matching retry returns its previously stored receipt.
 */
@Service
public class AcademicClassRuntimeService {
    private final AccessRepository access;
    private final AcademicClassRuntimeRepository repository;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;
    /** Supplies local identity, timetable persistence and retry/audit dependencies.
     * @param access current database grants and active-principal locking
     * @param repository scoped class/session mutations and resource/version validation
     * @param mutations deterministic request hashing, encrypted receipts and audit storage
     * @param mapper command and receipt JSON serializer
     */
    public AcademicClassRuntimeService(AccessRepository access, AcademicClassRuntimeRepository repository,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.mutations = mutations;
        this.mapper = mapper;
    }

    /** Replaces a class's proposed session schedule and increments its class version once.
     * Omitted eligible sessions are cancelled; historical or recorded sessions cannot be rewritten or removed.
     * Assigned teachers cannot change an existing timetable through this command. Teacher, room, student and
     * capacity conflicts are checked by the repository before the transaction commits.
     * @param actor caller with class.create or class.write; active status and grants are rechecked
     * @param classId public identifier of an active class in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input expected class version at least 1 and a non-null list of at most 200 sessions; each entry
     *              identifies a course lesson and future start, and may retain an existing session identifier
     * @return class identifier, incremented version and resulting non-cancelled session summaries, or replay
     * @throws BusinessException for authority/scope failure, invalid or duplicate lessons/sessions, protected
     *         history changes, resource/schedule conflicts, stale versions or conflicting retry keys
     * @throws JsonProcessingException if request hashing or receipt serialization/deserialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public ScheduleReceipt schedule(Actor actor, String classId, String key, ScheduleInput input)
            throws JsonProcessingException {
        require(actor, key, input == null || input.expectedVersion() < 1 || input.sessions() == null
            || input.sessions().size() > 200, "class.create", "class.write");
        Actor current = access.lockActive(actor.id());
        current.requireAny("class.create", "class.write");
        String hash = mutations.hash(mapper.writeValueAsString(List.of(classId, input)));
        ScheduleReceipt replay = replay(current, key, "academic.class.schedule", hash, ScheduleReceipt.class);
        if (replay != null) { return replay; }
        ScheduleReceipt result = repository.schedule(current, classId, input);
        complete(current, new MutationRepository.RetryWrite(key, "academic.class.schedule", hash,
            mapper.writeValueAsString(result), result.classId()));
        return result;
    }

    /** Relocates a batch of classes atomically and retains the original location of protected sessions.
     * Eligible future sessions move to the target room; class location history is appended and associated
     * orders' current-campus projection is updated without changing their original purchase terms.
     * @param actor caller with class.relocate; active grants, source class scope and target campus scope apply
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input 1–100 distinct class identifiers with expected versions at least 1, an active target campus
     *              and a room accepted by its subject, grade and capacity configuration
     * @return relocated class count and target campus/room, or the original receipt for an identical retry
     * @throws BusinessException for invalid input/key, missing authority, unavailable classes/campus, stale
     *         versions, unchanged location, duplicate class name, teacher-campus or room/schedule conflicts
     * @throws JsonProcessingException if request hashing or receipt serialization/deserialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public RelocateReceipt relocate(Actor actor, String key, RelocateInput input) throws JsonProcessingException {
        require(actor, key, input == null || input.classes() == null || input.classes().isEmpty()
            || input.classes().size() > 100 || input.classes().stream().anyMatch(item -> item == null
                || item.classId() == null || item.classId().isBlank() || item.expectedVersion() < 1)
            || input.classes().stream().map(AcademicClassRuntimeDtos.RelocateClass::classId).distinct().count()
                != input.classes().size(),
            "class.relocate");
        Actor current = access.lockActive(actor.id());
        current.require("class.relocate");
        String hash = mutations.hash(mapper.writeValueAsString(input));
        RelocateReceipt replay = replay(current, key, "academic.class.relocate", hash, RelocateReceipt.class);
        if (replay != null) { return replay; }
        RelocateReceipt result = repository.relocate(current, input);
        complete(current, new MutationRepository.RetryWrite(key, "academic.class.relocate", hash,
            mapper.writeValueAsString(result), "class-relocation"));
        return result;
    }

    /** Replaces the teacher of an eligible future session and appends encrypted substitution history.
     * Existing attendance, consumption or active/completed adjustments protect a session from substitution;
     * the replacement must be active, belong to the session campus and have no conflicting schedule.
     * @param actor caller with session.substitute, revalidated under the principal lock
     * @param sessionId public identifier of a non-cancelled session in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input different replacement teacher, nonblank reason and expected session version at least 1
     * @return session identifier, replacement teacher and new session version, or an identical retry receipt
     * @throws BusinessException for invalid fields/key, authority/scope failure, stale version, protected or
     *         past session, invalid/same teacher, campus/schedule conflict or conflicting retry key
     * @throws JsonProcessingException if request hashing or receipt serialization/deserialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public SubstituteReceipt substitute(Actor actor, String sessionId, String key, SubstituteInput input)
            throws JsonProcessingException {
        require(actor, key, input == null || input.expectedVersion() < 1 || input.teacherId() == null
            || input.teacherId().isBlank() || input.reason() == null || input.reason().isBlank(), "session.substitute");
        Actor current = access.lockActive(actor.id());
        current.require("session.substitute");
        String hash = mutations.hash(mapper.writeValueAsString(List.of(sessionId, input)));
        SubstituteReceipt replay = replay(current, key, "academic.session.substitute", hash, SubstituteReceipt.class);
        if (replay != null) { return replay; }
        SubstituteReceipt result = repository.substitute(current, sessionId, input);
        complete(current, new MutationRepository.RetryWrite(key, "academic.session.substitute", hash,
            mapper.writeValueAsString(result), result.sessionId()));
        return result;
    }

    /** Appends an encrypted teaching record for a started session and advances session/class versions.
     * @param actor caller with session.record, revalidated under the principal lock
     * @param sessionId public identifier of a started, non-cancelled session in the caller's class scope
     * @param key retry key containing 16–100 letters, digits, underscores or hyphens
     * @param input nonblank teaching content of at most 10000 characters and expected session version at least 1
     * @return new record identifier, session, actor, recorded time and session version, or an identical replay
     * @throws BusinessException for invalid fields/key, missing authority or session, stale version, a session
     *         that has not started, or conflicting retry key
     * @throws JsonProcessingException if request hashing or receipt serialization/deserialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public RecordReceipt record(Actor actor, String sessionId, String key, RecordInput input)
            throws JsonProcessingException {
        require(actor, key, input == null || input.expectedVersion() < 1 || input.content() == null
            || input.content().isBlank() || input.content().length() > 10000, "session.record");
        Actor current = access.lockActive(actor.id());
        current.require("session.record");
        String hash = mutations.hash(mapper.writeValueAsString(List.of(sessionId, input)));
        RecordReceipt replay = replay(current, key, "academic.session.record", hash, RecordReceipt.class);
        if (replay != null) { return replay; }
        RecordReceipt result = repository.record(current, sessionId, input);
        complete(current, new MutationRepository.RetryWrite(key, "academic.session.record", hash,
            mapper.writeValueAsString(result), result.id()));
        return result;
    }

    private void require(Actor actor, String key, boolean invalid, String... permissions) {
        actor.requireAny(permissions);
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}") || invalid) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请求字段或幂等键无效");
        }
    }
    private <T> T replay(Actor actor, String key, String action, String hash, Class<T> type)
            throws JsonProcessingException {
        String previous = mutations.replay(actor, key, action, hash);
        return previous == null ? null : mapper.readValue(previous, type);
    }
    private void complete(Actor actor, MutationRepository.RetryWrite write) {
        mutations.complete(actor, write);
    }
}
