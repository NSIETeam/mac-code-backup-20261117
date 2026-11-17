package cn.siyue.core;

import cn.siyue.core.CrmTaskReadDtos.AssigneePage;
import cn.siyue.core.CrmTaskReadDtos.AssigneeQuery;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmTaskReadServiceTest {
    private final CrmTaskAssignmentRepository assignments = mock(CrmTaskAssignmentRepository.class);
    private final CrmTaskReadRepository reads = mock(CrmTaskReadRepository.class);
    private final CrmTaskRepository tasks = mock(CrmTaskRepository.class);
    private final CrmTaskReadService service = new CrmTaskReadService(assignments, reads, tasks);
    private Actor actor(String... grants) { return new Actor(8, "EMP_8", "own", Set.of(), Set.of(grants)); }

    @Test void businessAssigneeDirectoryDoesNotRequireAccountAdministration() {
        Actor actor = actor("crm.task.write", "crm.task.assign");
        var query = new AssigneeQuery(" CAMP_1 ", "marketing", " EMP ", 2, 10);
        var normalized = new AssigneeQuery("CAMP_1", "marketing", "EMP", 2, 10);
        var page = new AssigneePage(List.of(), 0, 2, 10, "CAMP_1", "marketing", Instant.EPOCH, "current_actor_scope");
        when(assignments.list(actor, normalized)).thenReturn(page);
        assertSame(page, service.assignees(actor, query));
        verify(assignments).list(actor, normalized);
    }
    @Test void directoryRequiresTaskWriteAndValidFiltersBeforeReading() {
        assertThrows(BusinessException.class, () -> service.assignees(actor("system.access.manage"),
            new AssigneeQuery(null, null, null, null, null)));
        Actor actor = actor("crm.task.write");
        for (var query : List.of(new AssigneeQuery(null, "wrong", null, 1, 20),
                new AssigneeQuery(null, "marketing", null, 0, 20),
                new AssigneeQuery(null, "marketing", null, 1, 101),
                new AssigneeQuery("x".repeat(121), "marketing", null, 1, 20),
                new AssigneeQuery(null, "marketing", "x".repeat(121), 1, 20))) {
            assertThrows(BusinessException.class, () -> service.assignees(actor, query));
        }
        verifyNoInteractions(assignments);
    }
    @Test void queryDefaultsAreBoundedAndExplicit() {
        var query = new AssigneeQuery(null, null, null, null, null);
        assertEquals("marketing", query.taskType()); assertEquals(1, query.page()); assertEquals(20, query.size());
    }
    @Test void taskOnlyReaderUsesAuthorizedTaskBeforeMinimalContext() {
        Actor actor = actor("crm.task.read");
        var task = task();
        var context = new CrmTaskReadDtos.Context(task, new CrmTaskReadDtos.Customer("CUS_1", "合成家庭"),
            null, List.of(), null, null, "masked_guardian_contacts");
        when(tasks.get(actor, "TASK_1")).thenReturn(task);
        when(reads.context(actor, task)).thenReturn(context);
        assertSame(context, service.context(actor, "TASK_1"));
        var order = inOrder(tasks, reads); order.verify(tasks).get(actor, "TASK_1"); order.verify(reads).context(actor, task);
    }
    @Test void reassignedOrMissingTaskCannotReadHouseholdContext() {
        Actor actor = actor("crm.task.read");
        when(tasks.get(actor, "TASK_1")).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "已改派"));
        assertThrows(BusinessException.class, () -> service.context(actor, "TASK_1"));
        assertThrows(BusinessException.class, () -> service.context(actor("crm.customer.read"), "TASK_1"));
        verifyNoInteractions(reads);
    }
    @Test void matchingOpportunitySelectionNeedsSeparateGrantButNotGlobalCustomerAccess() {
        Actor actor = actor("crm.task.read", "crm.opportunity.read");
        var page = new CrmTaskReadDtos.OpportunityPage(List.of(), 0, 1, 20);
        when(reads.opportunities(actor, "TASK_1", 1, 20)).thenReturn(page);
        assertSame(page, service.opportunities(actor, "TASK_1", 1, 20));
        var order = inOrder(tasks, reads); order.verify(tasks).authorize(actor, "TASK_1");
        order.verify(reads).opportunities(actor, "TASK_1", 1, 20);
    }
    @Test void missingSalesPermissionOrTaskAccessPreventsOpportunityLookup() {
        assertThrows(BusinessException.class, () -> service.opportunities(actor("crm.task.read"), "TASK_1", 1, 20));
        Actor actor = actor("crm.task.read", "crm.opportunity.read");
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "已改派")).when(tasks).authorize(actor, "TASK_1");
        assertThrows(BusinessException.class, () -> service.opportunities(actor, "TASK_1", 1, 20));
        assertThrows(BusinessException.class, () -> service.opportunities(actor, "TASK_1", 1, 101));
        verifyNoInteractions(reads);
    }
    private CrmDtos.CrmTask task() {
        return new CrmDtos.CrmTask("TASK_1", "BATCH_1", "CUS_1", "CAMP_1", "EMP_8", "marketing", null,
            "合成联系任务", "数学", "2099秋", "pending", Instant.parse("2099-01-01T00:00:00Z"),
            "说明", null, null, null, null, 1, "STU_1", null, null, List.of(), "unknown",null,null);
    }
}
