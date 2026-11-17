package cn.siyue.core;

import cn.siyue.core.CrmDtos.CrmTask;
import cn.siyue.core.CrmDtos.TaskBatch;
import cn.siyue.core.CrmDtos.TaskBatchInput;
import cn.siyue.core.CrmDtos.TaskBatchCommitInput;
import cn.siyue.core.CrmDtos.TaskBatchReceipt;
import cn.siyue.core.CrmDtos.TaskBatchPage;
import cn.siyue.core.CrmDtos.TaskBatchPreview;
import cn.siyue.core.CrmDtos.TaskPage;
import cn.siyue.core.CrmDtos.TaskUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CrmTaskController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {"siyue.web-origin=https://crm.example.com",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
    "spring.security.oauth2.resourceserver.jwt.audiences=siyue-core"})
class CrmTaskControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AccessRepository access;
    @MockitoBean private CrmTaskService service;
    private Actor actor() { return new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.task.read", "crm.task.write")); }
    @Test void targetStudentsHaveTheirOwnPagedSafeProjection() throws Exception {
        Actor author = new Actor(5, "staff-5", "own", Set.of(), Set.of("crm.task.write", "crm.customer.read"));
        when(access.resolve("https://issuer.example", "subject")).thenReturn(author);
        when(service.targetStudents(author, "CUS_1", 2, 1)).thenReturn(new CrmDtos.TaskTargetStudentPage(
            List.of(new CrmDtos.TaskTargetStudent("STU_2", "未报名孩子乙", "四年级", "child")), 2, 2, 1));
        mvc.perform(get("/api/v2/crm/tasks/targets?customerId=CUS_1&page=2&size=1")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.size").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value("STU_2"))
            .andExpect(jsonPath("$.data.items[0].relationship").value("child"))
            .andExpect(jsonPath("$.data.items[0].phone").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].orderId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].oidcSubject").doesNotExist());
        verify(service).targetStudents(author, "CUS_1", 2, 1);
        mvc.perform(get("/api/v2/crm/tasks/targets")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isBadRequest());
    }
    @Test void batchContractIncludesDistributionAndDuplicateSummary() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var input = new TaskBatchInput("秋季数学初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("staff-5"), "even", null, Instant.parse("2026-10-15T00:00:00Z"), "联系家长", null);
        var task = new CrmTask("TASK_1", "TASKB_1", "CUS_1", null, "staff-5", "marketing", null,
            "秋季数学初筛", "数学", "2026秋", "pending", input.dueAt(), "联系家长", null, null, null, null, 1, null, null, null, java.util.List.of(), "unknown",null,null);
        var batch = new TaskBatch("TASKB_1", "秋季数学初筛", "marketing", null, null,
            input.dueAt(), "active", 1, 0, List.of("TASK_1"), Map.of("staff-5", 1L), 1, null);
        when(service.createBatch(actor(), "crm-task-batch-key-123", input))
            .thenReturn(new TaskBatchReceipt(batch, List.of(task), List.of()));
        mvc.perform(post("/api/v2/crm/tasks/batches")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "crm-task-batch-key-123")
            .contentType("application/json").content("""
                {"title":"秋季数学初筛","taskType":"marketing","customerIds":["CUS_1"],
                 "subject":"数学","term":"2026秋","ownerIds":["staff-5"],"strategy":"even",
                 "dueAt":"2026-10-15T00:00:00Z","note":"联系家长"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.batch.createdCount").value(1))
            .andExpect(jsonPath("$.data.batch.assignmentCounts.staff-5").value(1));
        verify(service).createBatch(actor(), "crm-task-batch-key-123", input);
    }
    @Test void batchPreviewDoesNotRequireAnIdempotencyKey() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var input = new TaskBatchInput("秋季数学初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("staff-5"), "even", null,
            Instant.parse("2026-10-15T00:00:00Z"), "联系家长", null);
        when(service.previewBatch(actor(), input)).thenReturn(
            new TaskBatchPreview(1, 1, 0, "CAMPUS_1", Map.of("staff-5", 1L)));
        mvc.perform(post("/api/v2/crm/tasks/batches/preview")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").contentType("application/json").content("""
                {"title":"秋季数学初筛","taskType":"marketing","customerIds":["CUS_1"],
                 "subject":"数学","term":"2026秋","ownerIds":["staff-5"],"strategy":"even",
                 "dueAt":"2026-10-15T00:00:00Z","note":"联系家长"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.eligibleCount").value(1))
            .andExpect(jsonPath("$.data.assignmentCounts.staff-5").value(1));
        verify(service).previewBatch(actor(), input);
    }
    @Test void batchCommitRequiresExactServerPreviewAndAnIdempotencyKey() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var taskInput = new TaskBatchInput("秋季数学初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("staff-5"), "even", null,
            Instant.parse("2026-10-15T00:00:00Z"), "联系家长", null);
        var preview = new TaskBatchPreview(1, 1, 0, "CAMPUS_1", Map.of("staff-5", 1L),
            Map.of("CUS_1", "staff-5"), List.of("CUS_1"), List.of(), List.of(), List.of());
        var input = new TaskBatchCommitInput(taskInput, preview);
        when(service.createBatch(actor(), "crm-task-batch-check-123", taskInput, preview))
            .thenReturn(new TaskBatchReceipt(null, List.of(), List.of()));
        mvc.perform(post("/api/v2/crm/tasks/batches/commit-preview")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "crm-task-batch-check-123")
            .contentType("application/json").content(objectMapper.writeValueAsString(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.tasks").isArray());
        verify(service).createBatch(actor(), "crm-task-batch-check-123", taskInput, preview);
        mvc.perform(post("/api/v2/crm/tasks/batches/commit-preview")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").contentType("application/json")
            .content(objectMapper.writeValueAsString(input)))
            .andExpect(status().isBadRequest());
    }
    @Test void batchPreviewAcceptsTwoChildrenOfOneCustomerAsDistinctTargets() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var input = new TaskBatchInput("兄弟姐妹初筛", "marketing", null, null, null, "数学", "2026秋",
            List.of("staff-5"), "even", null, Instant.parse("2030-01-01T00:00:00Z"), null,
            List.of(new CrmDtos.TaskTargetInput("CUS_1", "STU_1", null, null),
                new CrmDtos.TaskTargetInput("CUS_1", "STU_2", null, null)));
        var assignments = List.of(new CrmDtos.TaskTargetAssignment("a".repeat(64), "CUS_1", "STU_1", "数学", "2026秋", "staff-5", List.of(),null),
            new CrmDtos.TaskTargetAssignment("b".repeat(64), "CUS_1", "STU_2", "数学", "2026秋", "staff-5", List.of(),null));
        when(service.previewBatch(actor(), input)).thenReturn(new TaskBatchPreview(2, 2, 0, null,
            Map.of("staff-5", 2L), Map.of(), List.of(), List.of(), assignments, List.of()));
        mvc.perform(post("/api/v2/crm/tasks/batches/preview")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").contentType("application/json")
            .content(objectMapper.writeValueAsString(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.selectedCount").value(2))
            .andExpect(jsonPath("$.data.eligibleTargets[0].studentId").value("STU_1"))
            .andExpect(jsonPath("$.data.eligibleTargets[1].studentId").value("STU_2"))
            .andExpect(jsonPath("$.data.eligibleTargets[1].targetKey").value("b".repeat(64)))
            .andExpect(jsonPath("$.data.eligibleCustomerIds").isEmpty());
        verify(service).previewBatch(actor(), input);
    }
    @Test void taskQueuesArePagedAndRejectUnknownTypes() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        var query = new CrmDtos.TaskQuery("pending", "service", null, null, 1, 25);
        when(service.list(actor(), query)).thenReturn(new TaskPage(List.of(), 0, 1, 25));
        mvc.perform(get("/api/v2/crm/tasks?status=pending&taskType=service&page=1&size=25")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(service).list(actor(), query);
        mvc.perform(get("/api/v2/crm/tasks?taskType=other")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isBadRequest());
    }
    @Test void taskBatchQueueAcceptsScopedFiltersAndRejectsUnknownStatus() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        when(service.listBatches(actor(), "active", "marketing", 1, 20))
            .thenReturn(new TaskBatchPage(List.of(), 0, 1, 20));
        mvc.perform(get("/api/v2/crm/tasks/batches?status=active&taskType=marketing&page=1&size=20")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(service).listBatches(actor(), "active", "marketing", 1, 20);
        mvc.perform(get("/api/v2/crm/tasks/batches?status=pending")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com"))
            .andExpect(status().isBadRequest());
    }
    @Test void updateRequiresVersionAndSupportsStartedStatus() throws Exception {
        when(access.resolve("https://issuer.example", "subject")).thenReturn(actor());
        Instant due = Instant.parse("2026-10-15T00:00:00Z");
        var input = new TaskUpdate(null, "in_progress", null, null, 1);
        var task = new CrmTask("TASK_1", "TASKB_1", "CUS_1", null, "staff-5", "service", "回访",
            "退班回访", null, null, "in_progress", due, null, null, null, null, null, 2, null, null, null, java.util.List.of(), "unknown",null,null);
        when(service.update(actor(), "TASK_1", "crm-task-update-key-123", input)).thenReturn(task);
        mvc.perform(put("/api/v2/crm/tasks/TASK_1")
            .with(jwt().jwt(token -> token.issuer("https://issuer.example").subject("subject")))
            .header("Origin", "https://crm.example.com").header("Idempotency-Key", "crm-task-update-key-123")
            .contentType("application/json").content("{\"status\":\"in_progress\",\"expectedVersion\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("in_progress"))
            .andExpect(jsonPath("$.data.version").value(2));
    }
}
