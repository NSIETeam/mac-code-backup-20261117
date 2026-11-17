package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmTask;
import cn.siyue.core.CrmDtos.TaskBatch;
import cn.siyue.core.CrmDtos.TaskBatchInput;
import cn.siyue.core.CrmDtos.TaskBatchReceipt;
import cn.siyue.core.CrmDtos.TaskBatchPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrmTaskServiceTest {
    private final AccessRepository access = mock(AccessRepository.class);
    private final CrmTaskRepository repository = mock(CrmTaskRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final CrmTaskService service = new CrmTaskService(access, repository, mutations,
        new ObjectMapper().findAndRegisterModules());
    private final Actor actor = new Actor(5, "staff-5", "own", Set.of(),
        Set.of("crm.task.read", "crm.task.write"));
    @Test void targetStudentSelectorNeedsTaskWriteAndCustomerReadButNotAcademicRead() {
        Actor author = new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.task.write", "crm.customer.read"));
        var page = new CrmDtos.TaskTargetStudentPage(List.of(
            new CrmDtos.TaskTargetStudent("STU_PROSPECT", "未报名学生", "四年级", "child")), 1, 1, 20);
        when(repository.targetStudents(author, "CUS_1", 1, 20)).thenReturn(page);
        assertEquals(page, service.targetStudents(author, "CUS_1", 1, 20));
        verify(repository).targetStudents(author, "CUS_1", 1, 20);
        assertThrows(BusinessException.class, () -> service.targetStudents(actor, "CUS_1", 1, 20));
        Actor reader = new Actor(6, "reader", "own", Set.of(), Set.of("crm.customer.read", "student.read"));
        assertThrows(BusinessException.class, () -> service.targetStudents(reader, "CUS_1", 1, 20));
        assertThrows(BusinessException.class, () -> service.targetStudents(author, "CUS_1", 1, 101));
        assertThrows(BusinessException.class, () -> service.targetStudents(author, "CUS_1", 0, 20));
        assertThrows(BusinessException.class, () -> service.targetStudents(author, " ", 1, 20));
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(mutations);
    }
    @Test void createsTaskBatchWithScopeReplayAndAudit() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("task-batch-hash");
        Instant due = Instant.now().plusSeconds(86400);
        var input = new TaskBatchInput("初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("staff-5"), "even", null, due, "联系家长", null);
        var task = new CrmTask("TASK_1", "TASKB_1", "CUS_1", null, "staff-5", "marketing", null,
            "初筛", "数学", "2026秋", "pending", due, "联系家长", null, null, null, null, 1, null, null, null, java.util.List.of(), "unknown",null,null);
        var receipt = new TaskBatchReceipt(new TaskBatch("TASKB_1", "初筛", "marketing", null, null,
            due, "active", 1, 0, List.of("TASK_1"), Map.of("staff-5", 1L), 1, null), List.of(task), List.of());
        when(repository.createBatch(actor, input)).thenReturn(receipt);
        assertEquals(receipt, service.createBatch(actor, "crm-task-batch-key-123", input));
        verify(repository).authorizeBatchInput(actor, input);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.task.batch.create")
            && write.resourceId().equals("TASKB_1")));
        Actor reader = new Actor(6, "reader", "own", Set.of(), Set.of("crm.task.read"));
        assertThrows(BusinessException.class, () -> service.createBatch(reader, "crm-task-batch-key-123", input));
        verify(repository, times(1)).createBatch(actor, input);
    }
    @Test void previewsTaskBatchWithoutCreatingRecordsOrMutationLedgerEntries() {
        when(access.lockActive(5)).thenReturn(actor);
        Instant due = Instant.now().plusSeconds(86400);
        var input = new TaskBatchInput("初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("staff-5"), "even", null, due, "联系家长", null);
        var preview = new TaskBatchPreview(1, 1, 0, "CAMPUS_1", Map.of("staff-5", 1L));
        when(repository.previewBatch(actor, input)).thenReturn(preview);
        assertEquals(preview, service.previewBatch(actor, input));
        verify(repository).previewBatch(actor, input);
        verify(repository, never()).createBatch(any(), any());
        verifyNoInteractions(mutations);
        Actor reader = new Actor(6, "reader", "own", Set.of(), Set.of("crm.task.read"));
        assertThrows(BusinessException.class, () -> service.previewBatch(reader, input));
    }
    @Test void commitsTaskBatchOnlyAgainstTheReviewedPreviewAndKeepsIdempotentReplay() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("checked-task-batch-hash");
        Instant due = Instant.now().plusSeconds(86400);
        var input = new TaskBatchInput("初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("staff-5"), "even", null, due, "联系家长", null);
        var preview = new TaskBatchPreview(1, 1, 0, "CAMPUS_1", Map.of("staff-5", 1L),
            Map.of("CUS_1", "staff-5"), List.of("CUS_1"), List.of(), List.of(), List.of());
        var receipt = new TaskBatchReceipt(new TaskBatch("TASKB_1", "初筛", "marketing", null,
            "CAMPUS_1", due, "active", 1, 0, List.of("TASK_1"), Map.of("staff-5", 1L), 1, null),
            List.of(), List.of());
        when(repository.createBatch(actor, input, preview)).thenReturn(receipt);
        assertEquals(receipt, service.createBatch(actor, "crm-task-batch-checked-123", input, preview));
        verify(repository).createBatch(actor, input, preview);
        verify(mutations).complete(eq(actor), argThat(write -> write.action().equals("crm.task.batch.create")));
        when(mutations.replay(actor, "crm-task-batch-checked-123", "crm.task.batch.create", "checked-task-batch-hash"))
            .thenReturn("{}");
        assertNotNull(service.createBatch(actor, "crm-task-batch-checked-123", input, preview));
        verify(repository, times(1)).createBatch(actor, input, preview);
    }
    @Test void marketingQualifiedResultRequiresOpportunityReadPermission() {
        Actor noOpportunityRead = new Actor(7, "staff-7", "own", Set.of(), Set.of("crm.task.write"));
        assertThrows(BusinessException.class, () -> service.complete(noOpportunityRead, "TASK_1",
            "crm-task-complete-key-123", new CrmDtos.TaskCompletion("qualified", null, "OPP_1", 1)));
        verifyNoInteractions(repository, mutations);
    }
    @Test void expiredReviewedBatchReplaysAfterAuthorizationWithoutReplanning() throws Exception {
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("unchanged-old-hash");
        var input = new TaskBatchInput("初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("staff-5"), "even", null, Instant.parse("2020-01-01T00:00:00Z"), null, null);
        var preview = new TaskBatchPreview(1, 1, 0, null, Map.of("staff-5", 1L),
            Map.of("CUS_1", "staff-5"), List.of("CUS_1"), List.of(), List.of(), List.of());
        when(mutations.replay(actor, "original-old-batch-key-123", "crm.task.batch.create", "unchanged-old-hash"))
            .thenReturn("{}");
        assertNotNull(service.createBatch(actor, "original-old-batch-key-123", input, preview));
        var ordered = inOrder(repository, mutations);
        ordered.verify(repository).authorizeBatchInput(actor, input);
        ordered.verify(mutations).hash(argThat(json -> !json.contains("targets") && !json.contains("eligibleTargets")));
        ordered.verify(mutations).replay(actor, "original-old-batch-key-123", "crm.task.batch.create", "unchanged-old-hash");
        verify(repository, never()).createBatch(any(), any(), any());
        verify(repository, never()).createBatch(any(), any());
        verify(repository, never()).previewBatch(any(), any());
        verify(mutations, never()).complete(any(), any());
    }
    @Test void completionAndUpdateReplayRedactSourceLinksWithoutRepeatingBusinessWrites() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var saved = mapper.readValue("{\"id\":\"TASK_1\",\"version\":2,\"activityId\":\"ACT_1\","
            + "\"sources\":[{\"type\":\"lead\",\"id\":\"LEAD_1\",\"version\":1}],\"sourceStatus\":\"known\"}", CrmTask.class);
        var redacted = mapper.readValue("{\"id\":\"TASK_1\",\"version\":2,\"sourceStatus\":\"restricted\"}", CrmTask.class);
        when(access.lockActive(5)).thenReturn(actor);
        when(mutations.hash(anyString())).thenReturn("source-replay-hash");
        when(mutations.replay(eq(actor),anyString(),anyString(),eq("source-replay-hash")))
            .thenReturn(mapper.writeValueAsString(saved));
        when(repository.redactSources(actor,saved)).thenReturn(redacted);
        assertEquals(redacted,service.complete(actor,"TASK_1","completion-replay-key",new CrmDtos.TaskCompletion("no_need",null,null,1)));
        assertEquals(redacted,service.update(actor,"TASK_1","inprogress-replay-key",new CrmDtos.TaskUpdate(null,"in_progress",null,null,1)));
        verify(repository,times(2)).authorizeMutation(actor,"TASK_1");
        verify(repository,times(2)).redactSources(actor,saved);
        verify(repository,never()).complete(any(),any(),any());
        verify(repository,never()).update(any(),any(),any());
        verify(mutations,never()).complete(any(),any());
    }

}
