package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import cn.siyue.core.AcademicDtos.SessionScheduleInput;
import cn.siyue.core.AcademicDtos.SessionScheduleReceipt;
import cn.siyue.core.AcademicRescheduleDtos.RequestInput;
import cn.siyue.core.AcademicRescheduleDtos.RequestReceipt;
import cn.siyue.core.AcademicRescheduleDtos.ReviewInput;
import cn.siyue.core.AcademicRescheduleDtos.ReviewReceipt;
import cn.siyue.core.AcademicRescheduleDtos.ListQuery;
import cn.siyue.core.AcademicRescheduleDtos.RequestPage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores teacher reschedule requests separately from the timetable and applies approved changes atomically.
 * Commands recheck active grants, retain request/decision history and store encrypted idempotency receipts.
 * Requests freeze original class/session facts; approval checks those facts and delegates the actual timetable
 * move to AcademicService in the same rollback-on-exception transaction. Date/time input uses Asia/Shanghai.
 */
@Service
public class AcademicRescheduleService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final AccessRepository access;
    private final AcademicRepository academicRepository;
    private final AcademicRescheduleRepository requests;
    private final AcademicService academic;
    private final AcademicRescheduleMutations mutations;

    /** Connects identity, timetable authorization, request storage and command retry persistence.
     * @param access database-resolved current grants and active-principal locking
     * @param academicRepository scoped schedule lookup and proposed-move resource validation
     * @param requests request listing, timetable locks, frozen originals and decision/event persistence
     * @param academic transactional timetable move invoked only for approved requests
     * @param mutations request hashing, encrypted retry receipts, serialization and audit completion
     */
    public AcademicRescheduleService(AccessRepository access, AcademicRepository academicRepository,
            AcademicRescheduleRepository requests, AcademicService academic, AcademicRescheduleMutations mutations) {
        this.access = access;
        this.academicRepository = academicRepository;
        this.requests = requests;
        this.academic = academic;
        this.mutations = mutations;
    }

    /** Lists visible requests after rechecking current active grants.
     * Applicants with session.reschedule.request see their own requests; reviewers with session.move see all
     * requests in all-data scope or requests in their assigned campuses. These visibility alternatives are combined.
     * @param actor caller with session.reschedule.request or session.move
     * @param query page 1–100000, size 1–100 and optional pending, approved or rejected status
     * @return visible requests, total count and paging metadata, with requested business date/time projections
     * @throws BusinessException for missing/revoked grants, no applicable read scope or invalid query fields
     */
    @Transactional(rollbackFor = Exception.class)
    public RequestPage list(Actor actor, ListQuery query) {
        if (query == null) { throw new BusinessException(ErrorCode.INVALID_INPUT, "调课查询参数无效"); }
        actor.requireAny("session.reschedule.request", "session.move");
        Actor current = access.lockActive(actor.id());
        current.requireAny("session.reschedule.request", "session.move");
        return requests.listVisible(current, query);
    }

    /** Creates a pending request without moving the session, preserving its original timetable facts.
     * Only the actual session teacher may request a change. The original session must be future/scheduled,
     * version-current and without protected attendance, consumption or adjustments; the proposed time must
     * differ, remain in the future and satisfy room/teacher constraints.
     * @param actor caller with session.reschedule.request, revalidated under the active-principal lock
     * @param key retry key containing 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input matching class/session identifiers, nonblank reason up to 500 characters, expected session
     *              version at least 1, ISO local date and HH:mm–HH:mm range of the original duration;
     *              ASCII hyphen or em dash is also accepted, and an end time of 24:00 denotes midnight
     * @return pending request identifier, requested start and request version, or the retained retry receipt
     * @throws BusinessException for invalid fields/time/key, authority/scope or teacher mismatch, stale/protected
     *         session, schedule/resource conflict or conflicting retry key
     * @throws JsonProcessingException if request hashing or receipt serialization/deserialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public RequestReceipt request(Actor actor, String key, RequestInput input) throws JsonProcessingException {
        validateRequest(key, input);
        actor.require("session.reschedule.request");
        Actor current = access.lockActive(actor.id());
        current.require("session.reschedule.request");
        AcademicRepository.SessionScheduleRow row = academicRepository.scheduleRow(current, input.sessionId());
        if (!row.classPublicId().equals(input.classId()) || row.teacherId() != current.id()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅实际授课教师可申请本课次调课");
        }
        Instant requestedStartsAt = requestedStarts(input, row);
        requests.lockTimetable(row.classId(), row.id());
        row = academicRepository.scheduleRow(current, input.sessionId());
        academicRepository.validateMoveRequest(current, row, new SessionScheduleInput(requestedStartsAt,
            input.reason().trim(), input.expectedVersion()));
        String hash = mutations.hash(List.of(input));
        String action = "academic.session.reschedule.request";
        RequestReceipt replay = mutations.replay(current, key, action, hash, RequestReceipt.class);
        if (replay != null) { return replay; }
        long classVersion = requests.classVersion(row.classId());
        RequestReceipt receipt = requests.create(current, row, classVersion,
            requestedStartsAt, input.reason().trim());
        mutations.complete(new AcademicRescheduleMutations.Completion(current, key, action, hash,
            mutations.serialize(receipt), receipt.requestId()));
        return receipt;
    }

    /** Approves or rejects one pending request at its expected version and appends a decision event.
     * A new decision requires all-data or authorized-campus scope. Approval cannot be performed by the applicant
     * and rechecks the original class/session snapshot before moving the timetable; rejection leaves it unchanged.
     * @param actor caller with session.move, revalidated under the active-principal lock
     * @param requestId nonblank public identifier of the request to decide
     * @param key retry key containing 16–128 letters, digits, dots, underscores, colons or hyphens
     * @param input approve or reject, expected request version at least 1 and note up to 500 characters;
     *              rejection requires a nonblank note
     * @return updated request receipt and, for approval, the schedule-move receipt; rejection has no move receipt
     * @throws BusinessException for invalid fields/key, missing authority or request scope, stale/non-pending
     *         request, self-approval, changed original timetable, failed move validation or conflicting retry key
     * @throws JsonProcessingException if hashing, move-command processing or receipt serialization fails
     */
    @Transactional(rollbackFor = Exception.class)
    public ReviewReceipt review(Actor actor, String requestId, String key, ReviewInput input)
            throws JsonProcessingException {
        validateReview(key, requestId, input);
        actor.require("session.move");
        Actor current = access.lockActive(actor.id());
        current.require("session.move");
        String action = "academic.session.reschedule.review";
        String hash = mutations.hash(List.of(requestId, input));
        ReviewReceipt replay = mutations.replay(current, key, action, hash, ReviewReceipt.class);
        if (replay != null) { return replay; }
        AcademicRescheduleRepository.Request request = requests.lock(current, requestId);
        if (request.version() != input.expectedVersion() || !"pending".equals(request.status())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "调课申请已更新或处理");
        }
        if (request.applicantId() == current.id() && "approve".equals(input.decision())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能批准自己的调课申请");
        }
        SessionScheduleReceipt moved = null;
        if ("approve".equals(input.decision())) { moved = approve(current, request, key); }
        RequestReceipt result = requests.decide(new AcademicRescheduleRepository.Decision(current, request,
            input.decision(), cleanNote(input.note())));
        ReviewReceipt receipt = new ReviewReceipt(result, moved);
        mutations.complete(new AcademicRescheduleMutations.Completion(current, key, action, hash,
            mutations.serialize(receipt), request.publicId()));
        return receipt;
    }

    private SessionScheduleReceipt approve(Actor actor, AcademicRescheduleRepository.Request request, String key)
            throws JsonProcessingException {
        AcademicRepository.SessionScheduleRow current = academicRepository.scheduleRow(actor, request.sessionPublicId());
        requests.assertUnchanged(request, current);
        String moveKey = "rs-move-" + mutations.hash(key + ":" + request.publicId());
        return academic.moveSession(actor, request.sessionPublicId(), moveKey,
            new SessionScheduleInput(request.requestedStarts(), "审批调课申请", current.version()));
    }

    private static String cleanNote(String note) {
        if (note != null && note.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "审批说明超过500字");
        }
        return note == null || note.isBlank() ? null : note.trim();
    }

    private static void validateRequest(String key, RequestInput input) {
        requireKey(key);
        if (input == null || input.classId() == null || input.sessionId() == null || input.reason() == null
                || input.reason().isBlank() || input.reason().length() > 500 || input.date() == null
                || input.time() == null
                || input.expectedVersion() < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "调课申请字段无效");
        }
    }

    private Instant requestedStarts(RequestInput input, AcademicRepository.SessionScheduleRow row) {
        final LocalDate date;
        final String[] range;
        try {
            date = LocalDate.parse(input.date());
            range = input.time().split("[–—-]", -1);
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "调课日期或时段无效");
        }
        if (range.length != 2 || !range[0].matches("\\d{2}:\\d{2}") || !range[1].matches("\\d{2}:\\d{2}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "时段须为 HH:mm–HH:mm");
        }
        try {
            LocalTime start = LocalTime.parse(range[0]);
            int endMinutes = endMinutes(range[1]);
            int startMinutes = start.getHour() * 60 + start.getMinute();
            long requiredMinutes = java.time.Duration.between(row.startsAt(), row.endsAt()).toMinutes();
            if (endMinutes <= startMinutes || endMinutes - startMinutes != requiredMinutes) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "调课时段长度须与课次一致");
            }
            return date.atTime(start).atZone(BUSINESS_ZONE).toInstant();
        } catch (java.time.DateTimeException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "调课时段无效");
        }
    }

    private int endMinutes(String value) {
        if ("24:00".equals(value)) { return 1440; }
        LocalTime end = LocalTime.parse(value);
        return end.getHour() * 60 + end.getMinute();
    }

    private static void validateReview(String key, String requestId, ReviewInput input) {
        requireKey(key);
        if (requestId == null || requestId.isBlank() || input == null || input.expectedVersion() < 1
                || !List.of("approve", "reject").contains(input.decision())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "调课审批字段无效");
        }
        cleanNote(input.note());
        if ("reject".equals(input.decision()) && (input.note() == null || input.note().isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "驳回时必须填写原因");
        }
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Idempotency-Key 格式无效");
        }
    }
}
