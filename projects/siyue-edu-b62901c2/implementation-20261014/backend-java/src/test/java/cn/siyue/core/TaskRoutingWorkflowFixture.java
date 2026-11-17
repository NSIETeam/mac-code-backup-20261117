package cn.siyue.core;

import static cn.siyue.core.TaskRoutingDtos.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic data only; uses the actual transaction proxies and public request records. */
final class TaskRoutingWorkflowFixture {
    final NeedWorkflowFixture base;
    final JdbcTemplate jdbc;
    final Actor manager, supervisor, successor, worker, replacement;
    final TaskRoutingService routing;
    final CrmTaskService tasks;
    final Instant start = Instant.parse("2020-01-01T00:00:00Z");
    final Instant due = Instant.parse("2099-01-01T00:00:00Z");
    final List<String> taskPermissions = List.of("crm.task.read", "crm.task.write", "crm.task.assign", "crm.task.manage");

    TaskRoutingWorkflowFixture(JdbcTemplate jdbc) throws Exception {
        this.jdbc = jdbc; base = new NeedWorkflowFixture(jdbc);
        manager = grant(base.manager, taskPermissions);
        supervisor = base.staff("supervisor", "own", taskPermissions);
        successor = base.staff("successor", "own", taskPermissions);
        worker = grant(base.worker, List.of("crm.task.read", "crm.task.write", "crm.task.assign"));
        replacement = grant(base.replacement, List.of("crm.task.read", "crm.task.write"));
        var cipher = NeedWorkflowFixture.CIPHER;
        routing = base.proxy(new TaskRoutingService(base.access, new TaskRoutingReadRepository(jdbc, cipher),
            new TaskRoutingPlanRepository(jdbc, cipher), new TaskRoutingMutationRepository(jdbc, cipher),
            new MutationRepository(jdbc, cipher)));
        tasks = base.proxy(new CrmTaskService(base.access, new CrmTaskRepository(jdbc, cipher),
            new MutationRepository(jdbc, cipher), NeedWorkflowFixture.MAPPER));
    }

    Actor grant(Actor actor, List<String> permissions) {
        for (String permission : permissions) jdbc.update("INSERT IGNORE INTO core_principal_permission(principal_id,permission_code) VALUES(?,?)", actor.id(), permission);
        return base.access.resolve("https://needs-fixture.example", actor.publicId());
    }
    CrmDtos.TaskTargetInput target(String child) {
        return new CrmDtos.TaskTargetInput(base.customer, child, "数学", "2026秋", List.of(),
            new OpportunityNeedDtos.Identity(base.department, base.project));
    }
    CreateInput createInput(List<CrmDtos.TaskTargetInput> targets, Delivery delivery) {
        return new CreateInput("合成主管任务", "marketing", base.campus, null, start, due, delivery, targets, "合成验收");
    }
    CreateInput supervisorInput() {
        return createInput(List.of(target(base.childA), target(base.childB)), new Delivery("supervisor", supervisor.publicId(), null));
    }
    Receipt create(CreateInput input) throws Exception {
        return routing.commitCreate(manager, base.key("create-routing"), new CreateCommit(input, routing.previewCreate(manager, input)));
    }
    Receipt create() throws Exception { return create(supervisorInput()); }
    Query query(String batch) { return query(batch, Map.of()); }
    Query query(String batch, Map<String, Object> fields) {
        Map<String, Object> values = new LinkedHashMap<>(fields);
        if (batch != null) { values.put("batchId", batch); values.putIfAbsent("view", "scope"); }
        return NeedWorkflowFixture.MAPPER.convertValue(values, Query.class);
    }
    List<TaskItem> rows(Actor actor, String batch) { return routing.tasks(actor, query(batch)).items(); }
    ChangeInput command(Batch batch, Map<String, Object> fields) {
        Map<String, Object> values = new LinkedHashMap<>(fields);
        values.put("expectedManagementVersion", batch.managementVersion());
        return NeedWorkflowFixture.MAPPER.convertValue(values, ChangeInput.class);
    }
    ChangeInput allocation(Actor actor, String batch, List<TaskItem> selected) {
        return command(routing.get(actor, batch), Map.of("selection", selected.stream().map(t -> new Selection(t.id(), t.version())).toList(),
            "strategy", "even", "ownerIds", List.of(worker.publicId(), replacement.publicId()), "sourceOwnerPolicy", "none"));
    }
    Receipt change(Actor actor, String batch, String action, ChangeInput input) throws Exception {
        return routing.commitChange(actor, batch, action, base.key(action), new ChangeCommit(input, routing.previewChange(actor, batch, action, input)));
    }
    Receipt allocate(String batch, List<TaskItem> selected) throws Exception {
        return change(supervisor, batch, "allocation", allocation(supervisor, batch, selected));
    }
    CrmDtos.CrmTask complete(Actor actor, TaskItem task) throws Exception {
        return tasks.complete(actor, task.id(), base.key("complete"), new CrmDtos.TaskCompletion("no_need", "合成无需求", null, task.version()));
    }
    CandidateQuery candidates() { return new CandidateQuery(base.campus, null, null, 1, 100); }
    List<CrmDtos.TaskSourceRef> leads(String child, List<Actor> owners) {
        String activity = base.prefix + "activity-" + UUID.randomUUID();
        jdbc.update("INSERT INTO core_activity(public_id,title,campus_id,owner_id,activity_type,status,starts_at,ends_at) VALUES(?,?,?,?,'other','active',?,?)",
            activity, "合成来源活动", base.campusRow, manager.id(), java.sql.Timestamp.from(start), java.sql.Timestamp.from(due));
        long activityId = base.id("core_activity", activity);
        List<CrmDtos.TaskSourceRef> result = new ArrayList<>();
        for (Actor owner : owners) {
            String id = base.prefix + "lead-" + UUID.randomUUID();
            jdbc.update("INSERT INTO core_crm_lead(public_id,customer_id,campus_id,activity_id,student_id,owner_id,source,subject,term,title) VALUES(?,?,?,?,?,?,'other','数学','2026秋','合成来源线索')",
                id, base.customerRow, base.campusRow, activityId, base.id("core_student", child), owner.id());
            result.add(new CrmDtos.TaskSourceRef("lead", id, 1L));
        }
        return result;
    }
    CreateInput withSources(List<CrmDtos.TaskSourceRef> sources) {
        String activity = jdbc.queryForObject("SELECT a.public_id FROM core_crm_lead l JOIN core_activity a ON a.id=l.activity_id WHERE l.public_id=?", String.class, sources.get(0).id());
        var target = new CrmDtos.TaskTargetInput(base.customer, base.childA, "数学", "2026秋", sources,
            new OpportunityNeedDtos.Identity(base.department, base.project));
        return new CreateInput("合成来源任务", "marketing", base.campus, activity, start, due,
            new Delivery("supervisor", supervisor.publicId(), null), List.of(target), null);
    }
}
