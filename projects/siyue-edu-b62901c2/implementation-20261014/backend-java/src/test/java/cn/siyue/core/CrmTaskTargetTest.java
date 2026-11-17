package cn.siyue.core;

import cn.siyue.core.CrmDtos.TaskBatchInput;
import cn.siyue.core.CrmDtos.TaskBatchPreview;
import cn.siyue.core.CrmDtos.TaskQuota;
import cn.siyue.core.CrmDtos.TaskTargetInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrmTaskTargetTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // These records freeze the pre-V27 input and reviewed-envelope JSON used by persisted retry hashes.
    private record LegacyInput(String title, String taskType, String serviceType, List<String> customerIds,
            String activityId, String subject, String term, List<String> ownerIds, String strategy,
            List<TaskQuota> quotas, Instant dueAt, String note) { }
    private record LegacyPreview(int selectedCount, int eligibleCount, int skippedCount, String campusId,
            Map<String, Long> assignmentCounts, Map<String, String> customerAssignments,
            List<String> eligibleCustomerIds, List<String> skippedCustomerIds) { }
    private record LegacyEnvelope(LegacyInput input, LegacyPreview expectedPreview) { }

    private TaskBatchInput input(List<TaskTargetInput> targets) {
        return new TaskBatchInput("初筛", "marketing", null, null, null, "数学", "2026秋",
            List.of("EMP_1"), "even", null, Instant.parse("2030-01-01T00:00:00Z"), null, targets);
    }

    @Test void siblingsAndRepeatPurchasesAreSeparateDemands() {
        var input = input(List.of(new TaskTargetInput("CUS_1", "STU_1", null, null),
            new TaskTargetInput("CUS_1", "STU_2", null, null),
            new TaskTargetInput("CUS_1", "STU_1", " 英语 ", "2027春"),
            new TaskTargetInput("CUS_1", null, null, null)));
        List<TaskTargetInput> targets = CrmTaskRepository.normalizedTargets(input);
        assertEquals(4, targets.size());
        assertEquals("数学", targets.get(0).subject());
        assertEquals("英语", targets.get(2).subject());
        assertEquals("2027春", targets.get(2).term());
        assertNull(targets.get(3).studentId());
        assertEquals(4, targets.stream().map(CrmTaskRepository::targetKey).distinct().count());
        assertEquals(CrmTaskRepository.targetKey(targets.get(0)), CrmTaskRepository.targetKey(
            new TaskTargetInput("CUS_1", "STU_1", "数学", "2026秋")));
    }

    @Test void duplicatedDemandsAndAmbiguousTargetModesAreRejected() {
        assertThrows(BusinessException.class, () -> CrmTaskRepository.normalizedTargets(input(List.of(
            new TaskTargetInput("CUS_1", "STU_1", null, null),
            new TaskTargetInput("CUS_1", "STU_1", "数学", "2026秋")))));
        assertThrows(BusinessException.class, () -> CrmTaskRepository.normalizedTargets(input(List.of(
            new TaskTargetInput("CUS_1", "STU_1", "Math", "Autumn"),
            new TaskTargetInput("CUS_1", "STU_1", "math", "AUTUMN")))));
        assertThrows(BusinessException.class, () -> CrmTaskRepository.normalizedTargets(input(List.of())));
        assertThrows(BusinessException.class, () -> CrmTaskRepository.normalizedTargets(input(null)));
        var both = new TaskBatchInput("初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("EMP_1"), "even", null, Instant.now().plusSeconds(3600), null,
            List.of(new TaskTargetInput("CUS_1", "STU_1", null, null)));
        assertThrows(BusinessException.class, () -> CrmTaskRepository.normalizedTargets(both));
        assertThrows(BusinessException.class, () -> CrmTaskRepository.normalizedTargets(input(List.of(
            new TaskTargetInput("CUS_1", "STU_1", " ", null)))));
    }

    @Test void legacyCustomerIdsStayUnidentifiedAndRetainExactRetryJson() throws Exception {
        var legacy = new LegacyInput("初筛", "marketing", null, List.of("CUS_1"), null,
            "数学", "2026秋", List.of("EMP_1"), "even", null, Instant.parse("2020-01-01T00:00:00Z"), null);
        String originalJson = mapper.writeValueAsString(legacy);
        TaskBatchInput restored = mapper.readValue(originalJson, TaskBatchInput.class);
        assertEquals(originalJson, mapper.writeValueAsString(restored));
        assertNull(restored.targets());
        assertNull(CrmTaskRepository.normalizedTargets(restored).get(0).studentId());
        var oldPreview = new LegacyPreview(1, 1, 0, null, Map.of("EMP_1", 1L),
            Map.of("CUS_1", "EMP_1"), List.of("CUS_1"), List.of());
        String originalEnvelope = mapper.writeValueAsString(new LegacyEnvelope(legacy, oldPreview));
        var restoredEnvelope = mapper.readValue(originalEnvelope, CrmDtos.TaskBatchCommitInput.class);
        assertEquals(originalEnvelope, mapper.writeValueAsString(restoredEnvelope));
        TaskBatchPreview currentPreview = restoredEnvelope.expectedPreview();
        assertTrue(currentPreview.eligibleTargets().isEmpty());
        assertTrue(currentPreview.skippedTargets().isEmpty());
    }

    @Test void oldTaskReceiptWithoutStudentIsExplicitlyUnidentified() throws Exception {
        var task = mapper.readValue("{\"id\":\"TASK_OLD\",\"customerId\":\"CUS_1\",\"version\":1}", CrmDtos.CrmTask.class);
        assertNull(task.studentId());
        assertEquals("household_unidentified", task.identityStatus());
    }
}
