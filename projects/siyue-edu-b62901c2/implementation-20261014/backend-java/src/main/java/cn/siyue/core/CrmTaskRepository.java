package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmTask;
import cn.siyue.core.CrmDtos.TaskBatch;
import cn.siyue.core.CrmDtos.TaskBatchInput;
import cn.siyue.core.CrmDtos.TaskBatchReceipt;
import cn.siyue.core.CrmDtos.TaskBatchPage;
import cn.siyue.core.CrmDtos.TaskBatchPreview;
import cn.siyue.core.CrmDtos.TaskBatchSummary;
import cn.siyue.core.CrmDtos.TaskCompletion;
import cn.siyue.core.CrmDtos.TaskPage;
import cn.siyue.core.CrmDtos.TaskQuota;
import cn.siyue.core.CrmDtos.TaskUpdate;
import cn.siyue.core.CrmDtos.TaskTargetInput;
import cn.siyue.core.CrmDtos.TaskTargetAssignment;
import cn.siyue.core.CrmDtos.TaskTargetSkipped;
import cn.siyue.core.CrmDtos.TaskTargetStudent;
import cn.siyue.core.CrmDtos.TaskTargetStudentPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores routed CRM work batches and task completion facts. */
@Repository
public class CrmTaskRepository {
    private record CustomerRow(String publicId, long id, Long campusId, String ownerId) { }
    private record StaffRow(String publicId, long id, String scopeType, Set<Long> campuses) { }
    private record TargetRow(CustomerRow customer, Long studentId, TaskTargetInput target, String key) { }
    private record BatchPlan(List<TargetRow> eligible, List<TargetRow> skipped, List<StaffRow> staff,
            List<StaffRow> planned, Map<Long, Long> counts, Long campusId, String subject, String term) { }
    private record TaskRow(long id, String publicId, String batchId, String customerId, String campusId,
            String ownerId, String taskType, String serviceType, String title, String subject,
            String term, String status, Timestamp dueAt, String noteCipher, String result,
            String resultNoteCipher, String opportunityId, Timestamp completedAt, long version,
            Long studentRowId, String studentId) { }
    private record Eligibility(List<TargetRow> eligible, List<TargetRow> skipped) { }
    private record TaskChange(boolean reassign, boolean reschedule, boolean cancel, String reason,
            String nextStatus, Timestamp dueAt, Long ownerId) { }
    private record ChangeFlags(boolean reassign,boolean reschedule,boolean cancel) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final CrmTaskSourceRepository sources;
    private final CrmTaskNeedRepository needs;
    /** Shares the AES keyring used for customer data and communications. */
    public CrmTaskRepository(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc; this.cipher = cipher; this.sources = new CrmTaskSourceRepository(jdbc);
        this.needs = new CrmTaskNeedRepository(jdbc,cipher);
    }

    /** Reads prospective children under customer write scope, without enrollment or academic access. */
    public TaskTargetStudentPage targetStudents(Actor actor, String customerPublicId, int page, int size) {
        CustomerRow customer = customer(actor, customerPublicId, false);
        String from = " FROM core_customer_student link JOIN core_student s ON s.id=link.student_id ";
        String where = """
            WHERE link.customer_id=? AND link.deleted=0 AND s.deleted=0
              AND link.id=(SELECT MIN(canonical.id) FROM core_customer_student canonical
                  WHERE canonical.customer_id=link.customer_id AND canonical.student_id=link.student_id
                    AND canonical.deleted=0)
            """;
        Long total = jdbc.queryForObject("SELECT COUNT(s.id)" + from + where, Long.class, customer.id());
        List<TaskTargetStudent> items = jdbc.query(
            "SELECT s.public_id,s.name_cipher,s.grade,link.relationship" + from + where
                + " ORDER BY s.id LIMIT ? OFFSET ?",
            (rs, row) -> new TaskTargetStudent(rs.getString("public_id"), cipher.decrypt(rs.getString("name_cipher")),
                rs.getString("grade"), rs.getString("relationship")),
            customer.id(), size, Math.multiplyExact((long) page - 1, size));
        return new TaskTargetStudentPage(items, total == null ? 0 : total, page, size);
    }

    /** Creates an auditable batch; duplicate open marketing demand is reported per customer. */
    public TaskBatchReceipt createBatch(Actor actor, TaskBatchInput input) {
        return createBatch(actor, input, null);
    }

    /** Commits only when the server's freshly calculated customer and owner plan matches review. */
    public TaskBatchReceipt createBatch(Actor actor, TaskBatchInput input, TaskBatchPreview expectedPreview) {
        BatchPlan plan = planBatch(actor, input, false);
        if (expectedPreview != null && !batchPreview(input, plan).equals(expectedPreview)) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE, "任务资格或分配结果已变化，请重新预览并确认");
        }
        String batchPublicId = insertBatch(actor, input, plan);
        List<CrmTask> tasks = insertTasks(actor, input, plan, batchPublicId);
        return batchReceipt(input, plan, batchPublicId, tasks);
    }

    /** Previews validated batch eligibility and assignment counts without inserting task records. */
    public TaskBatchPreview previewBatch(Actor actor, TaskBatchInput input) {
        BatchPlan plan = planBatch(actor, input, true);
        return batchPreview(input, plan);
    }

    private TaskBatchPreview batchPreview(TaskBatchInput input, BatchPlan plan) {
        Map<String, Long> assignments = new LinkedHashMap<>();
        for (StaffRow person : plan.staff()) { assignments.put(person.publicId(), plan.counts().get(person.id())); }
        Map<String, String> customerAssignments = new LinkedHashMap<>();
        List<TaskTargetAssignment> targetAssignments = new ArrayList<>();
        for (int index = 0; index < plan.eligible().size(); index++) {
            TargetRow row = plan.eligible().get(index);
            String owner = plan.planned().get(index).publicId();
            if (input.targets() == null) { customerAssignments.put(row.customer().publicId(), owner); }
            else { targetAssignments.add(new TaskTargetAssignment(row.key(), row.target().customerId(),
                row.target().studentId(), row.target().subject(), row.target().term(), owner, row.target().sources(),row.target().needIdentity())); }
        }
        return new TaskBatchPreview(plan.eligible().size() + plan.skipped().size(), plan.eligible().size(),
            plan.skipped().size(), plan.campusId() == null ? null : publicCampusId(plan.campusId()),
            assignments, customerAssignments, legacyCustomerIds(input, plan.eligible()),
            legacyCustomerIds(input, plan.skipped()), targetAssignments, skippedTargets(input, plan.skipped()));
    }

    private List<String> legacyCustomerIds(TaskBatchInput input, List<TargetRow> rows) {
        return input.targets() == null ? rows.stream().map(row -> row.customer().publicId()).toList() : List.of();
    }

    private List<TaskTargetSkipped> skippedTargets(TaskBatchInput input, List<TargetRow> rows) {
        return input.targets() == null ? List.of() : rows.stream().map(row -> new TaskTargetSkipped(row.key(),
            row.target().customerId(), row.target().studentId(), row.target().subject(), row.target().term(),
            needs.skipReason(needTarget(row)), row.target().sources(),row.target().needIdentity())).toList();
    }

    private String publicCampusId(Long campusId) {
        return jdbc.queryForObject("SELECT public_id FROM core_campus WHERE id=? AND deleted=0",
            String.class, campusId);
    }

    private BatchPlan planBatch(Actor actor, TaskBatchInput input, boolean allowEmpty) {
        List<TaskTargetInput> targets = normalizedTargets(input);
        validateBatchInput(input);
        Long activity = activityIdIfSelected(actor, input.activityId());
        List<CustomerRow> customers = loadCustomers(actor, targets, true);
        validateActivityCustomers(activity, customers);
        sources.validate(actor, input.activityId(), targets, true);
        Eligibility eligibility = eligibleTargets(loadTargets(customers, targets), input, allowEmpty);
        List<StaffRow> staff = loadStaff(actor, input, eligibility.eligible().stream().map(TargetRow::customer).toList());
        Map<Long, Long> counts = zeroCounts(staff);
        Map<Long, Long> quotas = allowEmpty && eligibility.eligible().isEmpty()
            ? Map.of() : resolveQuotas(input, staff, eligibility.eligible().size());
        List<StaffRow> planned = planOwners(input, eligibility.eligible(), staff, counts, quotas);
        Long campusId = singleCampus(eligibility.eligible().stream().map(TargetRow::customer).toList());
        return new BatchPlan(eligibility.eligible(), eligibility.skipped(), staff, planned, counts,
            campusId, blankToNull(input.subject()), blankToNull(input.term()));
    }

    private void validateBatchInput(TaskBatchInput input) {
        rejectDuplicates(input.ownerIds(), "任务负责人");
        if (input.taskType().equals("service") && blankToNull(input.serviceType()) == null) {
            invalid("服务任务必须填写服务事项");
        }
        if (input.dueAt() == null || !input.dueAt().isAfter(Instant.now())) {
            invalid("任务截止时间须晚于当前时间");
        }
    }

    /** Legacy customer targets remain unidentified household demand; no student is inferred. */
    static List<TaskTargetInput> normalizedTargets(TaskBatchInput input) {
        if (input == null || (input.targets() == null) == (input.customerIds() == null)) {
            invalid("customerIds 与 targets 必须且只能提供一个");
        }
        List<TaskTargetInput> source = input.targets() != null ? input.targets()
            : input.customerIds().stream().map(id -> new TaskTargetInput(id, null, null, null)).toList();
        if (source.isEmpty() || source.size() > 500) { invalid("任务目标须为 1–500 个需求"); }
        List<TaskTargetInput> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (TaskTargetInput target : source) {
            TaskTargetInput normalized = normalizedTarget(input, target);
            if (!keys.add(targetKey(normalized))) { invalid("同一学生和购买需求不能在批次中重复"); }
            rejectUnknownAlongsideClassified(result,normalized);
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static TaskTargetInput normalizedTarget(TaskBatchInput input, TaskTargetInput target) {
        if (target == null || blankToNull(target.customerId()) == null) { invalid("目标客户不能为空"); }
        String subject = blankToNull(target.subject() == null ? input.subject() : target.subject());
        String term = blankToNull(target.term() == null ? input.term() : target.term());
        if ("marketing".equals(input.taskType()) && (subject == null || term == null)) {
            invalid("每个营销需求必须填写意向学科和学期");
        }
        return new TaskTargetInput(target.customerId(), blankToNull(target.studentId()), subject, term,
            CrmTaskSourceRepository.normalized(target.sources()),target.needIdentity());
    }

    static String targetKey(TaskTargetInput target) {
        StringBuilder value = new StringBuilder();
        if(target.needIdentity()!=null) {
            var identity=target.needIdentity();
            value.append("v2:").append(identity.departmentId().length()).append(':').append(identity.departmentId());
            String project=identity.projectId(); value.append(project==null?-1:project.length()).append(':');
            if(project!=null)value.append(project);
        }
        for (String part : new String[] {target.customerId(), target.studentId(), demandText(target.subject()), demandText(target.term())}) {
            value.append(part == null ? -1 : part.length()).append(':');
            if (part != null) { value.append(part); }
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String demandText(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
    private static void rejectUnknownAlongsideClassified(List<TaskTargetInput> existing,TaskTargetInput added) {
        var base=new TaskTargetInput(added.customerId(),added.studentId(),added.subject(),added.term());
        String baseKey=targetKey(base);
        for(var row:existing) {
            if((row.needIdentity()==null)==(added.needIdentity()==null))continue;
            var other=new TaskTargetInput(row.customerId(),row.studentId(),row.subject(),row.term());
            if(baseKey.equals(targetKey(other)))invalid("同批次含未识别与已识别的相似需求，请先明确部门和项目");
        }
    }

    private Long activityIdIfSelected(Actor actor,String publicId) {
        if(publicId==null||publicId.isBlank())return null;
        if (!CrmLegacyAccess.canRead(actor, "crm.activity.read")) { throw new BusinessException(ErrorCode.FORBIDDEN, "缺少活动查看权限"); }
        return activityId(actor,publicId);
    }

    private List<CustomerRow> loadCustomers(Actor actor, List<TaskTargetInput> targets, boolean lock) {
        List<CustomerRow> customers = new ArrayList<>();
        for (String id : targets.stream().map(TaskTargetInput::customerId).distinct().sorted().toList()) {
            customers.add(customer(actor, id, lock));
        }
        return customers;
    }

    private List<TargetRow> loadTargets(List<CustomerRow> customers, List<TaskTargetInput> targets) {
        Map<String, CustomerRow> known = new HashMap<>();
        customers.forEach(customer -> known.put(customer.publicId(), customer));
        List<TargetRow> result = new ArrayList<>();
        for (TaskTargetInput target : targets) {
            CustomerRow customer = known.get(target.customerId());
            Long student = target.studentId() == null ? null : linkedStudent(customer.id(), target.studentId());
            TargetRow row=new TargetRow(customer, student, target, targetKey(target));
            needs.validate(needTarget(row)); result.add(row);
        }
        return result;
    }

    /** Customer authorization was already checked; CRM relationships do not require enrollment. */
    private long linkedStudent(long customerId, String studentId) {
        List<Long> ids = jdbc.query("""
            SELECT s.id FROM core_student s JOIN core_customer_student cs ON cs.student_id=s.id
            WHERE cs.customer_id=? AND s.public_id=? AND cs.deleted=0 AND s.deleted=0 FOR UPDATE
            """, (rs, row) -> rs.getLong(1), customerId, studentId);
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "学生未关联此客户或关系已失效"); }
        return ids.get(0);
    }

    private void validateActivityCustomers(Long activityId,List<CustomerRow> customers) {
        if(activityId==null)return;
        Long activityCampus = jdbc.queryForObject("SELECT campus_id FROM core_activity WHERE id=?", Long.class, activityId);
        for (CustomerRow customer : customers) {
            if (customer.campusId() == null || !customer.campusId().equals(activityCampus)) {
                invalid("客户与获客活动必须属于同一校区");
            }
        }
    }

    private Eligibility eligibleTargets(List<TargetRow> targets, TaskBatchInput input, boolean allowEmpty) {
        List<TargetRow> eligible = new ArrayList<>();
        List<TargetRow> skipped = new ArrayList<>();
        for (TargetRow target : targets) {
            if (input.taskType().equals("marketing") && needs.skipReason(needTarget(target))!=null) { skipped.add(target); }
            else { eligible.add(target); }
        }
        if (eligible.isEmpty() && !allowEmpty) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "所选需求均已有未完成营销任务");
        }
        return new Eligibility(eligible, skipped);
    }

    private List<StaffRow> loadStaff(Actor actor,TaskBatchInput input,List<CustomerRow> eligible) {
        List<StaffRow> staff = new ArrayList<>();
        for (String ownerId : input.ownerIds()) { staff.add(staff(actor, ownerId, eligible, input.taskType())); }
        return staff;
    }

    private Map<Long,Long> zeroCounts(List<StaffRow> staff) {
        Map<Long,Long> counts=new LinkedHashMap<>();
        staff.forEach(person -> counts.put(person.id(),0L));
        return counts;
    }

    private List<StaffRow> planOwners(TaskBatchInput input,List<TargetRow> eligible,
            List<StaffRow> staff,Map<Long,Long> counts,Map<Long,Long> quotas) {
        List<StaffRow> planned = new ArrayList<>();
        for (TargetRow target : eligible) {
            StaffRow chosen = chooseOwner(input,target.customer(),staff,counts,quotas);
            if (chosen == null) { invalid("没有可用员工满足当前分配策略"); }
            planned.add(chosen);
            counts.compute(chosen.id(), (key, value) -> value + 1);
        }
        return planned;
    }

    private StaffRow chooseOwner(TaskBatchInput input,CustomerRow customer,List<StaffRow> staff,
            Map<Long,Long> counts,Map<Long,Long> quotas) {
        StaffRow ownerFirst=input.strategy().equals("owner_first")
            ? staff.stream().filter(person -> person.publicId().equals(customer.ownerId())).findFirst().orElse(null)
            : null;
        if(ownerFirst!=null)return ownerFirst;
        return staff.stream().filter(person -> !input.strategy().equals("quota")
                || counts.get(person.id()) < quotas.get(person.id()))
            .min(java.util.Comparator.comparingLong((StaffRow person) -> counts.get(person.id()))
                .thenComparingInt(staff::indexOf)).orElse(null);
    }

    private Long singleCampus(List<CustomerRow> eligible) {
        return eligible.stream().map(CustomerRow::campusId).distinct().count() == 1
            ? eligible.get(0).campusId() : null;
    }

    private String insertBatch(Actor actor, TaskBatchInput input, BatchPlan plan) {
        String batchPublicId = "TASKB_" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_crm_task_batch
                (public_id,created_by,title,task_type,service_type,subject,term,campus_id,due_at,status,note_cipher,skipped_count,activity_id)
            VALUES (?,?,?,?,?,?,?,?,?,'active',?,?,?)
            """, batchPublicId, actor.id(), input.title().trim(), input.taskType(), blankToNull(input.serviceType()),
            plan.subject(), plan.term(), plan.campusId(), Timestamp.from(input.dueAt()), encrypt(input.note()),plan.skipped().size(), activityIdIfSelected(actor, input.activityId()));
        return batchPublicId;
    }

    private List<CrmTask> insertTasks(Actor actor, TaskBatchInput input, BatchPlan plan, String batchPublicId) {
        Long batchRowId = jdbc.queryForObject("SELECT id FROM core_crm_task_batch WHERE public_id=?", Long.class, batchPublicId);
        List<CrmTask> tasks = new ArrayList<>();
        for (int index = 0; index < plan.eligible().size(); index++) {
            TargetRow target = plan.eligible().get(index);
            CustomerRow customer = target.customer();
            StaffRow owner = plan.planned().get(index);
            String taskPublicId = "TASK_" + UUID.randomUUID();
            jdbc.update("""
                INSERT INTO core_crm_task
                    (public_id,batch_id,customer_id,campus_id,owner_id,task_type,service_type,title,subject,term,status,due_at,note_cipher,student_id,activity_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,'pending',?,?,?,?)
                """, taskPublicId, batchRowId, customer.id(), customer.campusId(), owner.id(), input.taskType(),
                blankToNull(input.serviceType()), input.title().trim(), target.target().subject(), target.target().term(),
                Timestamp.from(input.dueAt()), encrypt(input.note()), target.studentId(), activityIdIfSelected(actor, input.activityId()));
            long taskRowId = jdbc.queryForObject("SELECT id FROM core_crm_task WHERE public_id=?", Long.class, taskPublicId);
            sources.insert(taskRowId, target.target().sources());
            needs.insert(taskRowId,needTarget(target));
            addTaskEvent(taskRowId, actor, "created", "任务已分配给 " + owner.publicId());
            // The authorized creator may receive this write receipt even when the new owner has own scope.
            // Subsequent reads still enter taskId and enforce the receiver/campus scope.
            tasks.add(taskDto(actor, taskRow(actor, taskRowId)));
        }
        return List.copyOf(tasks);
    }

    private TaskBatchReceipt batchReceipt(TaskBatchInput input, BatchPlan plan,
            String batchPublicId, List<CrmTask> tasks) {
        String campusPublicId = plan.campusId() == null ? null : jdbc.queryForObject(
            "SELECT public_id FROM core_campus WHERE id=?", String.class, plan.campusId());
        TaskBatch batch = new TaskBatch(batchPublicId, input.title().trim(), input.taskType(),
            blankToNull(input.serviceType()), campusPublicId, input.dueAt(), "active", tasks.size(), plan.skipped().size(),
            tasks.stream().map(CrmTask::id).toList(), plan.staff().stream().collect(java.util.stream.Collectors.toMap(
                StaffRow::publicId, person -> plan.counts().get(person.id()), (left, right) -> left, LinkedHashMap::new)), 1, blankToNull(input.activityId()));
        return new TaskBatchReceipt(batch, tasks, legacyCustomerIds(input, plan.skipped()), skippedTargets(input, plan.skipped()));
    }

    /** Checks linked customers, activity and assignees before an idempotent replay is returned. */
    public void authorizeBatchInput(Actor actor, TaskBatchInput input) {
        List<TaskTargetInput> targets = normalizedTargets(input);
        if (input.ownerIds() == null || input.dueAt() == null) { invalid("任务批次字段不完整"); }
        rejectDuplicates(input.ownerIds(), "任务负责人");
        Long activity = activityIdIfSelected(actor, input.activityId());
        List<CustomerRow> customers = loadCustomers(actor, targets, true);
        validateActivityCustomers(activity, customers);
        loadTargets(customers, targets);
        sources.validate(actor, input.activityId(), targets, false);
        for (String ownerId : input.ownerIds()) { staff(actor, ownerId, customers, input.taskType()); }
    }

    /** Lists matching assignee or authorized campus tasks and the same filtered total. */
    public TaskPage list(Actor actor, String status, String taskType, int page, int size) {
        return list(actor, new CrmDtos.TaskQuery(status, taskType, null, null, page, size));
    }

    /** Filters source and batch before both counting and paging the authorized task queue. */
    public TaskPage list(Actor actor, CrmDtos.TaskQuery query) {
        return list(actor, query, null);
    }
    /** Applies imported saved-view search and update ordering under the unchanged task scope. */
    public TaskPage list(Actor actor, CrmDtos.TaskQuery query, LegacySavedViewQuery savedView) {
        String status = query.status(), taskType = query.taskType(), batchId = query.batchId(), activityId = query.activityId();
        int page = query.page(), size = query.size();
        ScopeSql.Clause scope = taskScope(actor);
        List<Object> args = new ArrayList<>(scope.args());
        String filters = "";
        if (status != null && !status.isBlank()) { filters += " AND t.status=?"; args.add(status); }
        if (taskType != null && !taskType.isBlank()) { filters += " AND t.task_type=?"; args.add(taskType); }
        if (batchId != null && !batchId.isBlank()) {
            filters += " AND t.batch_id IN (SELECT b.id FROM core_crm_task_batch b WHERE b.public_id=? AND b.deleted=0)";
            args.add(batchId);
        }
        if (activityId != null && !activityId.isBlank()) {
            filters += " AND t.activity_id=?"; args.add(activityIdIfSelected(actor, activityId));
        }
        filters += taskSavedViewFilter(actor, savedView, args);
        String where = "t.deleted=0 AND (" + scope.sql() + ")" + filters;
        Long total = jdbc.queryForObject("SELECT COUNT(t.id) FROM core_crm_task t WHERE " + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size); pageArgs.add(Math.multiplyExact((long) page - 1, size));
        String order = savedView != null && savedView.present()
            ? " ORDER BY t.updated_at DESC,t.id DESC" : " ORDER BY t.due_at,t.id";
        List<String> publicIds = jdbc.query("""
            SELECT t.public_id FROM core_crm_task t WHERE
            """ + where + order + " LIMIT ? OFFSET ?",
            (rs, row) -> rs.getString(1), pageArgs.toArray());
        return new TaskPage(publicIds.stream().map(id -> task(actor, id, false)).toList(),
            total == null ? 0 : total, page, size);
    }

    private String taskSavedViewFilter(Actor actor, LegacySavedViewQuery savedView, List<Object> args) {
        if (savedView == null || savedView.searchPattern() == null) { return ""; }
        String filter = " AND (LOWER(CAST(t.public_id AS CHAR)) LIKE ? ESCAPE '!'"
            + " OR LOWER(t.title) LIKE ? ESCAPE '!' OR LOWER(COALESCE(t.service_type,'')) LIKE ? ESCAPE '!'"
            + " OR LOWER(COALESCE(t.subject,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(t.term,'')) LIKE ? ESCAPE '!')";
        for (int index = 0; index < 5; index++) { args.add(savedView.searchPattern()); }
        if (CrmLegacyAccess.canRead(actor, "crm.customer.read")) {
            filter = filter.substring(0, filter.length() - 1)
                + SavedViewCustomerSearch.addVisibleIds("t.customer_id",
                    SavedViewCustomerSearch.matchingIds(jdbc, cipher, actor, savedView.search()), args) + ")";
        }
        return filter;
    }

    /** Lists batch-level progress using only tasks visible within the actor's current scope. */
    public TaskBatchPage listBatches(Actor actor,String status,String taskType,int page,int size) {
        return listBatches(actor, new CrmDtos.TaskBatchQuery(status, taskType, null, page, size));
    }

    /** Uses the same activity evidence for batch progress and task drill-down. */
    public TaskBatchPage listBatches(Actor actor, CrmDtos.TaskBatchQuery query) {
        String status = query.status(), taskType = query.taskType(), activityId = query.activityId();
        int page = query.page(), size = query.size();
        ScopeSql.Clause scope=taskScope(actor);
        List<Object> args=new ArrayList<>(scope.args());
        String filters=batchFilters(status,taskType,args);
        if (activityId != null && !activityId.isBlank()) {
            filters += " AND b.activity_id=?"; args.add(activityIdIfSelected(actor, activityId));
        }
        String join="b.id=t.batch_id AND t.deleted=0 AND ("+scope.sql()+")";
        Long total=jdbc.queryForObject("SELECT COUNT(DISTINCT b.id) FROM core_crm_task_batch b "
            +"JOIN core_crm_task t ON "+join+" WHERE b.deleted=0"+filters,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);
        pageArgs.add(size);pageArgs.add(Math.multiplyExact((long)page-1,size));
        List<TaskBatchSummary> items=jdbc.query("""
            SELECT b.public_id,b.title,b.task_type,b.service_type,cp.public_id AS campus_public_id,
                   b.due_at,b.status,b.skipped_count,b.version,COUNT(t.id) AS created_count,
                   SUM(CASE WHEN t.status IN ('pending','in_progress') THEN 1 ELSE 0 END) AS open_count,
                   SUM(CASE WHEN t.status='completed' THEN 1 ELSE 0 END) AS completed_count
            FROM core_crm_task_batch b JOIN core_crm_task t ON
            """+join+" LEFT JOIN core_campus cp ON cp.id=b.campus_id WHERE b.deleted=0"+filters+
            " GROUP BY b.id,b.public_id,b.title,b.task_type,b.service_type,cp.public_id,b.due_at,b.status,b.skipped_count,b.version"
            +" ORDER BY MAX(b.created_at) DESC,b.id DESC LIMIT ? OFFSET ?",
            (rs,row)->new TaskBatchSummary(rs.getString("public_id"),rs.getString("title"),
                rs.getString("task_type"),rs.getString("service_type"),rs.getString("campus_public_id"),
                rs.getTimestamp("due_at").toInstant(),rs.getString("status"),rs.getLong("created_count"),
                rs.getInt("skipped_count"),rs.getLong("open_count"),rs.getLong("completed_count"),rs.getLong("version"),
                sources.batchActivity(actor, rs.getString("public_id"))),
            pageArgs.toArray());
        return new TaskBatchPage(items,total==null?0:total,page,size);
    }

    private String batchFilters(String status,String taskType,List<Object> args) {
        String filters="";
        if(status!=null&&!status.isBlank()){filters+=" AND b.status=?";args.add(status);}
        if(taskType!=null&&!taskType.isBlank()){filters+=" AND b.task_type=?";args.add(taskType);}
        return filters;
    }

    /** Completes an open task only with a result permitted for its marketing/service type. */
    public CrmTask complete(Actor actor, String publicId, TaskCompletion input) {
        TaskRoutingScope.employeeComplete(jdbc,actor,publicId);
        long taskId = taskId(actor, publicId, true);
        TaskRow current = taskRow(actor, taskId);
        if (current.version() != input.expectedVersion()) { throw conflict(); }
        Set<String> marketing = Set.of("qualified", "no_need", "unreachable", "invalid_contact");
        Set<String> service = Set.of("completed", "refused", "unreachable", "invalid_contact");
        if (!(current.taskType().equals("marketing") ? marketing : service).contains(input.result())) {
            invalid("办理结果与任务类型不匹配");
        }
        Long opportunityId = null;
        if (input.result().equals("qualified")) {
            if (input.opportunityId() == null || input.opportunityId().isBlank()) { invalid("有效需求必须关联商机"); }
            opportunityId = opportunityId(actor, input.opportunityId(), current);
            needs.complete(actor,taskId,opportunityId,input.needResolution());
        } else if (input.opportunityId() != null && !input.opportunityId().isBlank()) {
            invalid("仅有效需求结果可关联商机");
        }
        if(!"qualified".equals(input.result())&&input.needResolution()!=null)invalid("只有有效需求结果可明确需求身份");
        int changed = jdbc.update("""
            UPDATE core_crm_task SET status='completed',result=?,result_note_cipher=?,opportunity_id=?,
                completed_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status IN ('pending','in_progress') AND deleted=0
            """, input.result(), encrypt(input.note()), opportunityId, taskId, input.expectedVersion());
        if (changed != 1) { throw conflict(); }
        addTaskEvent(taskId, actor, "completed", input.result() + (blankToNull(input.note()) == null ? "" : ": " + input.note().trim()));
        finishBatch(taskId);
        return task(actor, publicId, false);
    }

    /** Reassigns, reschedules, starts or cancels an open task with a version check and event. */
    public CrmTask update(Actor actor, String publicId, TaskUpdate input) {
        TaskRoutingScope.employeeChange(jdbc,actor,publicId,input);
        long id = taskId(actor, publicId, true);
        TaskRow current = taskRow(actor, id);
        validateTaskVersion(current,input);
        TaskChange change=prepareTaskChange(actor,current,input);
        updateTaskRecord(id,input,change);
        addTaskEvent(id, actor, change.cancel() ? "cancelled" : "updated", taskChangeDetail(input,change));
        if (change.cancel()) { finishBatch(id); }
        return taskDto(actor, taskRow(actor, id));
    }

    private void validateTaskVersion(TaskRow current,TaskUpdate input) {
        if (current.version() != input.expectedVersion()) { throw conflict(); }
        if (!Set.of("pending", "in_progress").contains(current.status())) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "已结束任务不能调整");
        }
    }

    private TaskChange prepareTaskChange(Actor actor,TaskRow current,TaskUpdate input) {
        ChangeFlags flags=changeFlags(current,input);
        boolean changeStatus = input.status() != null && !input.status().equals(current.status());
        if (!flags.reassign() && !flags.reschedule() && !changeStatus) { invalid("任务安排没有变化"); }
        boolean reasonRequired = flags.reassign() || flags.reschedule() || flags.cancel();
        String reason = blankToNull(input.reason());
        if (reasonRequired && reason == null) { invalid("改派、延期或取消任务必须填写原因"); }
        if (input.dueAt() != null && !input.dueAt().isAfter(Instant.now())) { invalid("任务截止时间须晚于当前时间"); }
        Long ownerId = flags.reassign() ? reassignmentOwner(actor,current,input) : null;
        String nextStatus = flags.cancel() ? "cancelled"
            : input.status() == null ? current.status() : input.status();
        Timestamp due = input.dueAt() == null ? current.dueAt() : Timestamp.from(input.dueAt());
        return new TaskChange(flags.reassign(),flags.reschedule(),flags.cancel(),reason,nextStatus,due,ownerId);
    }

    private ChangeFlags changeFlags(TaskRow current,TaskUpdate input) {
        boolean reassign = input.ownerId() != null && !input.ownerId().isBlank()
            && !input.ownerId().equals(current.ownerId());
        boolean reschedule = input.dueAt() != null && !input.dueAt().equals(current.dueAt().toInstant());
        return new ChangeFlags(reassign,reschedule,"cancelled".equals(input.status()));
    }

    private Long reassignmentOwner(Actor actor,TaskRow current,TaskUpdate input) {
        CustomerRow linkedCustomer = customer(actor, current.customerId(), false);
        return staff(actor, input.ownerId(), List.of(linkedCustomer), current.taskType()).id();
    }

    private void updateTaskRecord(long id,TaskUpdate input,TaskChange change) {
        int changed = jdbc.update("""
            UPDATE core_crm_task SET owner_id=COALESCE(?,owner_id),status=?,due_at=?,
                result_note_cipher=CASE WHEN ?='cancelled' THEN ? ELSE result_note_cipher END,
                cancelled_at=CASE WHEN ?='cancelled' THEN UTC_TIMESTAMP(3) ELSE cancelled_at END,
                version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=? AND version=? AND status IN ('pending','in_progress') AND deleted=0
            """, change.ownerId(), change.nextStatus(), change.dueAt(), change.nextStatus(),
            encrypt(change.reason()), change.nextStatus(), id, input.expectedVersion());
        if (changed != 1) { throw conflict(); }
    }

    private String taskChangeDetail(TaskUpdate input,TaskChange change) {
        return (change.reassign() ? "改派给 " + input.ownerId() + "；" : "")
            + (change.reschedule() ? "截止时间已调整；" : "")
            + (change.reason() == null ? "状态=" + change.nextStatus() : change.reason());
    }

    /** Rechecks the task's owner/campus scope before reading its data. */
    public void authorize(Actor actor, String publicId) { taskId(actor, publicId, false); }
    public void lockMutationBatch(String publicId) { TaskRoutingScope.lockTaskBatch(jdbc,publicId); }
    public void authorizeMutation(Actor actor,String publicId) {
        TaskRoutingScope.lockTaskBatch(jdbc,publicId); authorize(actor,publicId);
        if (actor.permissions().contains("lead.create") && !actor.permissions().contains("crm.task.write")) {
            String taskType=jdbc.queryForObject("SELECT task_type FROM core_crm_task WHERE public_id=? AND deleted=0", String.class,publicId);
            if ("marketing".equals(taskType)) { actor.require("lead.follow"); }
        }
    }
    /** Used by the task-only context reader after its task.read permission gate. */
    public CrmTask get(Actor actor, String publicId) { return task(actor, publicId, false); }
    /** Replays original task facts while redacting source links under current permissions. */
    public CrmTask redactSources(Actor actor, CrmTask saved) {
        CrmTaskSourceRepository.Summary source = sources.summary(actor, taskId(actor, saved.id(), false));
        return new CrmTask(saved.id(), saved.batchId(), saved.customerId(), saved.campusId(), saved.ownerId(),
            saved.taskType(), saved.serviceType(), saved.title(), saved.subject(), saved.term(), saved.status(),
            saved.dueAt(), saved.note(), saved.result(), saved.resultNote(), saved.opportunityId(), saved.completedAt(),
            saved.version(), saved.studentId(), saved.identityStatus(), source.activityId(), source.sources(), source.status(),saved.needIdentity(),saved.resolvedNeedIdentity());
    }
    private CrmTask task(Actor actor, String publicId, boolean lock) { return taskDto(actor, taskRow(actor, taskId(actor, publicId, lock))); }
    private CrmTask taskDto(Actor actor, TaskRow row) {
        CrmTaskSourceRepository.Summary source = sources.summary(actor, row.id());
        var identity=needs.keys(row.id());
        return new CrmTask(row.publicId(), row.batchId(), row.customerId(), row.campusId(), row.ownerId(),
            row.taskType(), row.serviceType(), row.title(), row.subject(), row.term(), row.status(),
            row.dueAt().toInstant(), decrypt(row.noteCipher()), row.result(), decrypt(row.resultNoteCipher()),
            row.opportunityId(), row.completedAt() == null ? null : row.completedAt().toInstant(), row.version(),
            row.studentId(), null, source.activityId(), source.sources(), source.status(),
            needs.identity(identity.department(),identity.project()),needs.identity(identity.resolvedDepartment(),identity.resolvedProject()));
    }
    private TaskRow taskRow(Actor actor, long id) {
        ScopeSql.Clause opportunityScope = CrmLegacyAccess.canRead(actor, "crm.opportunity.read") ? switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("o.owner_id=?", List.of(actor.id()));
            default -> new ScopeSql.Clause("1=0", List.of());
        } : new ScopeSql.Clause("1=0", List.of());
        List<Object> args = new ArrayList<>(opportunityScope.args()); args.add(id);
        String sql = """
            SELECT t.id,t.public_id,b.public_id AS batch_public_id,c.public_id AS customer_public_id,
                   cp.public_id AS campus_public_id,p.public_id AS owner_public_id,t.task_type,t.service_type,
                   t.title,t.subject,t.term,t.status,t.due_at,t.note_cipher,t.result,t.result_note_cipher,
                   o.public_id AS opportunity_public_id,t.completed_at,t.version,t.student_id,st.public_id AS student_public_id
            FROM core_crm_task t JOIN core_crm_task_batch b ON b.id=t.batch_id
            JOIN core_customer c ON c.id=t.customer_id LEFT JOIN core_student st ON st.id=t.student_id
            LEFT JOIN core_campus cp ON cp.id=t.campus_id
            LEFT JOIN core_principal p ON p.id=t.owner_id LEFT JOIN core_opportunity o ON o.id=t.opportunity_id AND EXISTS (
                SELECT v.id FROM core_opportunity v WHERE v.id=o.id AND (
            """ + opportunityScope.sql() + ")) WHERE t.id=? AND t.deleted=0";
        List<TaskRow> rows = jdbc.query(sql, (rs, row) -> new TaskRow(rs.getLong("id"), rs.getString("public_id"),
                rs.getString("batch_public_id"), rs.getString("customer_public_id"), rs.getString("campus_public_id"),
                rs.getString("owner_public_id"), rs.getString("task_type"), rs.getString("service_type"),
                rs.getString("title"), rs.getString("subject"), rs.getString("term"), rs.getString("status"),
                rs.getTimestamp("due_at"), rs.getString("note_cipher"), rs.getString("result"),
                rs.getString("result_note_cipher"), rs.getString("opportunity_public_id"),
                rs.getTimestamp("completed_at"), rs.getLong("version"), rs.getObject("student_id", Long.class),
                rs.getString("student_public_id")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在"); }
        return rows.get(0);
    }
    private long taskId(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = taskScope(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT t.id FROM core_crm_task t WHERE t.public_id=? AND t.deleted=0 AND ("
            + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或不在授权范围"); }
        return ids.get(0);
    }
    private ScopeSql.Clause taskScope(Actor actor) {
        ScopeSql.Clause legacy = switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("t.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> actor.permissions().contains("lead.assign")
                ?new ScopeSql.Clause("(t.owner_id=? OR EXISTS(SELECT cb.id FROM core_crm_task_batch cb WHERE cb.id=t.batch_id AND cb.created_by=?))",List.of(actor.id(),actor.id()))
                :new ScopeSql.Clause("t.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
        return TaskRoutingScope.execution(actor,legacy);
    }
    private CustomerRow customer(Actor actor, String publicId, boolean lock) {
        ScopeSql.Clause scope = ScopeSql.customers(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<CustomerRow> rows = jdbc.query("""
            SELECT c.id,c.public_id,c.campus_id,p.public_id AS owner_public_id
            FROM core_customer c LEFT JOIN core_principal p ON p.id=c.owner_id
            WHERE c.public_id=? AND c.deleted=0 AND (
            """ + scope.sql() + ")" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new CustomerRow(rs.getString("public_id"), rs.getLong("id"),
                rs.getObject("campus_id", Long.class), rs.getString("owner_public_id")), args.toArray());
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不在授权范围"); }
        return rows.get(0);
    }
    private StaffRow staff(Actor actor, String publicId, List<CustomerRow> customers, String taskType) {
        CrmTaskAssignmentRepository.Staff person = new CrmTaskAssignmentRepository(jdbc).requireEligible(actor,
            publicId, customers.stream().map(CustomerRow::campusId).toList(), taskType, true);
        return new StaffRow(person.publicId(), person.id(), person.scopeType(), person.campuses());
    }

    private Map<Long, Long> resolveQuotas(TaskBatchInput input, List<StaffRow> staff, int eligibleCount) {
        Map<Long, Long> quotas = new HashMap<>();
        if (!input.strategy().equals("quota")) {
            if (input.quotas() != null && !input.quotas().isEmpty()) { invalid("仅按名额分配时可填写名额"); }
            return quotas;
        }
        if (input.quotas() == null || input.quotas().size() != staff.size()) { invalid("按名额分配需要为每位员工设置数量"); }
        Map<String, Long> known = new HashMap<>(); staff.forEach(person -> known.put(person.publicId(), person.id()));
        long sum = 0;
        for (TaskQuota quota : input.quotas()) {
            Long id = known.get(quota.ownerId());
            if (id == null || quotas.containsKey(id) || quota.count() < 0 || quota.count() > 10000) {
                invalid("名额中的员工重复、不在分配名单或数量无效");
            }
            quotas.put(id, (long) quota.count()); sum += quota.count();
        }
        if (sum != eligibleCount) { invalid("分配数量之和须等于去重后可分配任务数"); }
        return quotas;
    }
    private CrmTaskNeedRepository.Target needTarget(TargetRow target) {
        return new CrmTaskNeedRepository.Target(target.customer().id(),target.studentId(),target.customer().campusId(),target.target());
    }
    private long activityId(Actor actor, String publicId) {
        ScopeSql.Clause scope = ScopeSql.activities(actor);
        List<Object> args = new ArrayList<>(List.of(publicId)); args.addAll(scope.args());
        List<Long> ids = jdbc.query("SELECT a.id FROM core_activity a WHERE a.public_id=? AND a.deleted=0 AND ("
            + scope.sql() + ")", (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或不在授权范围"); }
        return ids.get(0);
    }
    private long opportunityId(Actor actor, String publicId, TaskRow task) {
        ScopeSql.Clause scope = switch (actor.scope()) {
            case "all" -> new ScopeSql.Clause("1=1", List.of());
            case "campus" -> new ScopeSql.Clause("o.campus_id IN (SELECT pc.campus_id FROM core_principal_campus pc WHERE pc.principal_id=? AND pc.deleted=0)", List.of(actor.id()));
            case "own", "assigned" -> new ScopeSql.Clause("o.owner_id=?", List.of(actor.id()));
            default -> throw new BusinessException(ErrorCode.FORBIDDEN, "账号数据范围无效");
        };
        List<Object> args = new ArrayList<>(List.of(publicId, task.customerId()));
        args.add(task.studentRowId()); args.add(task.subject()); args.add(task.term()); args.addAll(scope.args());
        List<Long> ids = jdbc.query("""
            SELECT o.id FROM core_opportunity o JOIN core_customer c ON c.id=o.customer_id
            WHERE o.public_id=? AND c.public_id=? AND o.student_id <=> ?
              AND BINARY LOWER(o.subject) <=> BINARY LOWER(?) AND BINARY LOWER(o.term) <=> BINARY LOWER(?)
              AND o.deleted=0 AND (""" + scope.sql() + ") FOR UPDATE",
            (rs, row) -> rs.getLong(1), args.toArray());
        if (ids.isEmpty()) { throw new BusinessException(ErrorCode.INVALID_INPUT, "商机与任务的客户、学生或购买需求不匹配，或超出授权范围"); }
        return ids.get(0);
    }
    private void addTaskEvent(long taskId, Actor actor, String type, String detail) {
        jdbc.update("INSERT INTO core_crm_task_event (public_id,task_id,actor_id,event_type,occurred_at,detail_cipher) VALUES (?,?,?,?,?,?)",
            "TASKEVT_" + UUID.randomUUID(), taskId, actor.id(), type, Timestamp.from(Instant.now()), encrypt(detail));
    }
    private void finishBatch(long taskId) {
        Long batchId = jdbc.queryForObject("SELECT batch_id FROM core_crm_task WHERE id=?", Long.class, taskId);
        String mode=jdbc.queryForObject("SELECT delivery_mode FROM core_crm_task_batch WHERE id=?",String.class,batchId);
        if(mode!=null&&!mode.equals("legacy")){new TaskRoutingMutationRepository(jdbc,cipher).finish(batchId);return;}
        Integer openTasks = jdbc.queryForObject("SELECT COUNT(id) FROM core_crm_task WHERE batch_id=? AND status IN ('pending','in_progress') AND deleted=0",
            Integer.class, batchId);
        if (openTasks != null && openTasks == 0) {
            jdbc.update("UPDATE core_crm_task_batch SET status='completed',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='active' AND deleted=0",
                batchId);
        }
    }
    private String encrypt(String value) { return value == null || value.isBlank() ? null : cipher.encrypt(value.trim()); }
    private String decrypt(String value) { return value == null ? null : cipher.decrypt(value); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void rejectDuplicates(List<String> values, String label) {
        if (values == null || values.isEmpty() || new HashSet<>(values).size() != values.size()) { invalid(label + "不能为空或重复"); }
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.ALREADY_EXISTS, "任务版本已变化或已结束"); }
}
