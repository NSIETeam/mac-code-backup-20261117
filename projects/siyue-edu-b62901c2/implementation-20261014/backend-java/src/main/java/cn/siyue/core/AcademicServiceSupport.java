package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import cn.siyue.core.AcademicDtos.AttendanceRecord;
import cn.siyue.core.AcademicDtos.AttendanceBatchInput;
import cn.siyue.core.AcademicDtos.AttendanceBatchReceipt;
import cn.siyue.core.AcademicDtos.ClassInput;
import cn.siyue.core.AcademicDtos.ClassReceipt;
import cn.siyue.core.AcademicDtos.ClassBatchInput;
import cn.siyue.core.AcademicDtos.ClassBatchReceipt;
import cn.siyue.core.AcademicDtos.EnrollmentBatchInput;
import cn.siyue.core.AcademicDtos.EnrollmentBatchReceipt;
import cn.siyue.core.AcademicDtos.EnrollmentBatchItemReceipt;
import cn.siyue.core.AcademicDtos.EnrollmentBatchRow;
import cn.siyue.core.AcademicDtos.TeachingMetrics;

/**
 * Internal academic validation and batch execution used by {@link AcademicService} on its current thread.
 * This helper has no Spring proxy or transaction boundary: the service retains its public API and
 * transaction annotations. Extracted commands preserve permission/lock order, request hashing, replay
 * and audit operations; repository dependencies participate in the caller's existing transaction.
 */
final class AcademicServiceSupport {
    /** 保留原输入下标的班级草案，排序加锁后仍能恢复原批次结果顺序。 */
    private record IndexedClass(int index, ClassInput input) { }
    /** 出勤幂等摘要的固定字段顺序：课次、状态、备注及是否课消；移动类型不改变JSON形状。 */
    record AttendanceWrite(String sessionId, String status, String note, Boolean consume) { }
    private final AccessRepository access;
    private final AcademicRepository repository;
    private final AcademicCatalogRepository catalog;
    private final MutationRepository mutations;
    private final ObjectMapper mapper;

    /** Shares the service's dependencies without acquiring resources or creating a transaction. */
    AcademicServiceSupport(AccessRepository access, AcademicRepository repository, AcademicCatalogRepository catalog,
            MutationRepository mutations, ObjectMapper mapper) {
        this.access = access;
        this.repository = repository;
        this.catalog = catalog;
        this.mutations = mutations;
        this.mapper = mapper;
    }
    /** Normalizes student view and pagination without changing caller authorization. */
    AcademicDtos.StudentQuery studentQuery(AcademicDtos.StudentQuery query) {
        String view = AcademicReadScope.view(query.view());
        int page = query.page() == null ? 1 : query.page(), size = query.size() == null ? 50 : query.size();
        validatePage(page, size);
        return new AcademicDtos.StudentQuery(view, query.classId(), page, size);
    }

    /** Applies the shared one-based academic pagination bounds. */
    void validatePage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "分页范围无效");
        }
    }

    /** Requires opportunity write and source-lead read grants only when selected rows reference them. */
    void requireBatchPermissions(Actor actor, List<EnrollmentBatchRow> rows) {
        if (rows.stream().anyMatch(row -> row.opportunityId() != null)) { actor.require("crm.opportunity.write"); }
        if (rows.stream().anyMatch(row -> row.studentDraft() != null
                && row.studentDraft().originLeadId() != null)) { actor.require("crm.lead.read"); }
    }

    /** Hashes the original request and atomically replays or creates pending enrollment receipts in the caller transaction. */
    EnrollmentBatchReceipt writeEnrollmentBatch(Actor actor, String key, EnrollmentBatchInput input,
            List<EnrollmentBatchRow> rows, AcademicBatchEnrollmentRepository batchEnrollments) throws JsonProcessingException {
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(actor, key, "academic.enrollment.batchCreate", hash);
        if (replay != null) { return mapper.readValue(replay, EnrollmentBatchReceipt.class); }
        List<EnrollmentBatchItemReceipt> receipts = batchEnrollments.create(actor, input.classId(), rows,
            new AcademicBatchEnrollmentRepository.BatchDefaults(input.couponId(), input.couponClaimToken(),
                input.referrerStudentId()));
        EnrollmentBatchReceipt result = new EnrollmentBatchReceipt(receipts);
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "academic.enrollment.batchCreate", hash,
            mapper.writeValueAsString(result), input.classId()));
        return result;
    }

    /** Normalizes legacy student identifiers or explicit rows, rejecting duplicate or ambiguous identities. */
    List<EnrollmentBatchRow> enrollmentRows(EnrollmentBatchInput input) {
        validateBatchHeader(input);
        List<EnrollmentBatchRow> rows = input.studentIds() == null ? input.rows()
            : input.studentIds().stream().map(id -> new EnrollmentBatchRow(null, id, null, null, null, null)).toList();
        validateBatchRows(input, rows);
        List<String> studentIds = rows.stream().map(EnrollmentBatchRow::studentId)
            .filter(java.util.Objects::nonNull).toList();
        if (studentIds.stream().distinct().count() != studentIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "批量报名学生编号重复");
        }
        return rows;
    }

    /** Checks that the batch chooses exactly one row representation and one coupon representation. */
    void validateBatchHeader(EnrollmentBatchInput input) {
        if (input == null || input.classId() == null || input.classId().isBlank()
                || (input.studentIds() == null) == (input.rows() == null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "classId 与 studentIds/rows 参数无效");
        }
        if (input.couponId() != null && input.couponClaimToken() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "优惠券编号与兑换码不能同时使用");
        }
    }

    /** Checks bounded nonnull rows and exclusive existing-student or draft-student identity. */
    void validateBatchRows(EnrollmentBatchInput input, List<EnrollmentBatchRow> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 500 || rows.stream().anyMatch(java.util.Objects::isNull)
                || input.studentIds() != null && input.studentIds().stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "报名人数或学生编号无效");
        }
        if (rows.stream().anyMatch(row -> (row.studentId() == null) == (row.studentDraft() == null)
                || row.studentId() != null && row.studentId().isBlank()
                || row.couponId() != null && row.couponClaimToken() != null
                || !validDraft(row.studentDraft()))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "每条报名行必须且只能选择已有学生或填写新生资料");
        }
    }

    /** Accepts absent drafts or drafts with a nonblank name and valid mobile number. */
    boolean validDraft(AcademicDtos.EnrollmentStudentDraft draft) {
        return draft == null || draft.name() != null && !draft.name().isBlank()
            && draft.phone() != null && draft.phone().trim().matches("1[3-9][0-9]{9}");
    }

    /** Normalizes attendance pagination and default onsite course type without querying storage. */
    AcademicDtos.AllStudentAttendanceQuery attendanceQuery(AcademicDtos.AllStudentAttendanceQuery query) {
        if(query==null)throw new BusinessException(ErrorCode.INVALID_INPUT,"考勤筛选条件无效");
        int page=query.page()==null?1:query.page(),size=query.size()==null?20:query.size();
        validatePage(page,size);
        return new AcademicDtos.AllStudentAttendanceQuery(query.campusId(),query.teacherId(),
            query.courseType()==null?"onsite":query.courseType(),query.attendanceStatus(),
            query.search()==null?null:query.search().trim(),query.fromAt(),query.untilAt(),page,size);
    }

    /** Appends one attendance fact using the service transaction and already authorized membership. */
    AttendanceRecord appendAttendance(Actor actor, AcademicRepository.AttendanceMember member,
            AttendanceWrite write) {
        AttendanceRecord record = new AttendanceRecord("ATT_" + UUID.randomUUID(),
            member.classPublicId(), write.sessionId(), member.studentPublicId(), write.status(), write.note(), actor.publicId(),
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        repository.appendAttendance(member, actor, record, write.consume());
        return record;
    }

    /** Validates class view, pagination and paired date bounds without accessing storage. */
    AcademicDtos.ClassQuery validatedClassQuery(AcademicDtos.ClassQuery query) {
        String view = AcademicReadScope.view(query.view());
        int page = query.page() == null ? 1 : query.page(), size = query.size() == null ? 50 : query.size();
        validatePage(page, size);
        if ((query.fromAt() == null) != (query.untilAt() == null)
                || query.fromAt() != null && (!query.untilAt().isAfter(query.fromAt())
                    || java.time.Duration.between(query.fromAt(), query.untilAt()).compareTo(java.time.Duration.ofDays(366)) > 0)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "课表起止时间须成对提供且跨度不超过 366 天");
        }
        return new AcademicDtos.ClassQuery(view, query.campusId(), query.teacherId(),
            query.room(), query.fromAt(), query.untilAt(), page, size, query.search(),query.courseType(),query.format(),query.year(),query.term(),query.subject(),query.grade(),query.classType(),query.courseKind(),query.phase());
    }

    /** Requires assignment permission for another teacher when the caller is not globally scoped. */
    void requireTeacherAssignment(Actor actor, String teacherPublicId) {
        if (!actor.scope().equals("all") && !actor.publicId().equals(teacherPublicId)) {
            actor.require("class.assign");
        }
    }

    /** Checks the existing academic retry-key alphabet and length before any replay. */
    void requireIdempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "请提供有效幂等键");
        }
    }

    /** Runs the original batch enrollment permission, identity-lock, authorization and replay sequence. */
    EnrollmentBatchReceipt enrollBatch(Actor actor, String key, EnrollmentBatchInput input, AcademicBatchEnrollmentRepository batchEnrollments)
            throws JsonProcessingException {
        String action = "academic.enrollment.batchCreate";
        requireEnrollmentBatchPermission(actor);
        requireIdempotencyKey(key);
        List<EnrollmentBatchRow> rows = enrollmentRows(input);
        requireBatchPermissions(actor, rows);
        if (batchEnrollments == null) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "批量报名服务未配置"); }
        batchEnrollments.lockDraftIdentities(rows);
        Actor current = access.lockActive(actor.id());
        requireEnrollmentBatchPermission(current);
        requireBatchPermissions(current, rows);
        batchEnrollments.authorize(current, input.classId(), rows, input.referrerStudentId());
        return writeEnrollmentBatch(current, key, input, rows, batchEnrollments);
    }

    /** Retains the exact legacy batch grant without granting general order access. */
    private void requireEnrollmentBatchPermission(Actor actor) {
        actor.requireAny("enrollment.manage", "enrollment.batchCreate");
        if (!actor.permissions().contains("enrollment.batchCreate")) { actor.require("crm.order.read"); }
    }

    /** Checks and locks every member before replay, then appends all attendance facts and one receipt atomically. */
    AttendanceBatchReceipt recordAttendanceBatch(Actor actor, String sessionId, String key,
            AttendanceBatchInput input) throws JsonProcessingException {
        String action = "academic.attendance.batch.create";
        actor.requireAny("attendance.manage", "session.attendance");
        requireIdempotencyKey(key);
        if (input == null || input.rows() == null || input.rows().isEmpty() || input.rows().size() > 1000
                || input.rows().stream().anyMatch(java.util.Objects::isNull)
                || input.rows().stream().map(AcademicDtos.AttendanceBatchRow::studentId).distinct().count()
                    != input.rows().size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "考勤名单为空、重复或超过 1000 人");
        }
        actor = access.lockActive(actor.id());
        actor.requireAny("attendance.manage", "session.attendance");
        List<AcademicRepository.AttendanceMember> members = new java.util.ArrayList<>();
        for (var row : input.rows()) {
            members.add(repository.attendanceMember(actor, row.studentId(), input.classId(), sessionId));
        }
        String hash = mutations.hash(mapper.writeValueAsString(List.of(sessionId, input)));
        String replay = mutations.replay(actor, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, AttendanceBatchReceipt.class); }
        List<AttendanceRecord> records = new java.util.ArrayList<>();
        for (int index = 0; index < input.rows().size(); index++) {
            var row = input.rows().get(index);
            records.add(appendAttendance(actor, members.get(index),
                new AttendanceWrite(sessionId, row.status(), row.note(), row.consume())));
        }
        AttendanceBatchReceipt receipt = new AttendanceBatchReceipt(List.copyOf(records));
        mutations.complete(actor, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(receipt), sessionId));
        return receipt;
    }

    /** Authorizes the batch, replays before writing, and orders new class writes by shared resource keys. */
    ClassBatchReceipt createClasses(Actor actor, String key, ClassBatchInput input)
            throws JsonProcessingException {
        String action = "academic.class.batch.create";
        actor.requireAny("class.write", "class.batchCreate");
        requireIdempotencyKey(key);
        int sessionCount = input.classes().stream().mapToInt(row -> row.schedule().size()).sum();
        if (sessionCount > 400) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "单批排课总课次不能超过 400 次");
        }
        Actor current = access.lockActive(actor.id());
        current.requireAny("class.write", "class.batchCreate");
        for (ClassInput row : input.classes()) {
            requireTeacherAssignment(current, row.teacherId());
            catalog.authorizeClassCreation(current, row);
        }
        String hash = mutations.hash(mapper.writeValueAsString(input));
        String replay = mutations.replay(current, key, action, hash);
        if (replay != null) { return mapper.readValue(replay, ClassBatchReceipt.class); }
        List<IndexedClass> ordered = java.util.stream.IntStream.range(0, input.classes().size())
            .mapToObj(index -> new IndexedClass(index, input.classes().get(index)))
            .sorted(Comparator.comparing((IndexedClass row) -> row.input().campusId())
                .thenComparing(row -> row.input().teacherId()).thenComparing(row -> row.input().name())).toList();
        ClassReceipt[] results = new ClassReceipt[input.classes().size()];
        for (IndexedClass row : ordered) { results[row.index()] = catalog.createClass(current, row.input()); }
        List<ClassReceipt> created = List.of(results);
        ClassBatchReceipt receipt = new ClassBatchReceipt(created, created.size(), sessionCount);
        mutations.complete(current, new MutationRepository.RetryWrite(key, action, hash,
            mapper.writeValueAsString(receipt), "class-batch"));
        return receipt;
    }

    /** Builds Shanghai calendar-month boundaries and delegates to the existing scoped metrics query. */
    TeachingMetrics teachingMetrics(Actor actor, String month) {
        actor.require("report.read");
        final YearMonth requested;
        try { requested = YearMonth.parse(month); }
        catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "月份格式应为 YYYY-MM");
        }
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant from = requested.atDay(1).atStartOfDay(shanghai).toInstant();
        Instant until = requested.plusMonths(1).atDay(1).atStartOfDay(shanghai).toInstant();
        return repository.teachingMetrics(actor, month, from, until);
    }

    /** Builds Shanghai calendar-month boundaries and delegates to the existing scoped metrics query. */
    TeachingMetrics teachingMetrics(Actor actor, String month, String view) {
        String checkedView = AcademicReadScope.view(view);
        if (checkedView.equals("mine")) { actor.requireAny("report.read", "teacher.metrics.read"); }
        else { actor.require("report.read"); }
        final YearMonth requested;
        try { requested = YearMonth.parse(month); }
        catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "月份格式应为 YYYY-MM");
        }
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant from = requested.atDay(1).atStartOfDay(shanghai).toInstant();
        Instant until = requested.plusMonths(1).atDay(1).atStartOfDay(shanghai).toInstant();
        return repository.teachingMetrics(actor, new AcademicDtos.TeachingPeriod(month, checkedView, from, until));
    }
}
