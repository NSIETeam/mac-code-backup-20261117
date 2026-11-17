package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Expected qualification outcomes are values; failed evidence reads remain failures. */
class TaskRoutingOutcomeTest {
    private Actor actor(Set<String> permissions) {
        return new Actor(1,"routing-manager","all",Set.of(),permissions);
    }

    @Test void missingSourcePermissionDoesNotQueryOrDiscloseEvidence() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var sources=new CrmTaskSourceRepository(jdbc);
        assertTrue(sources.findVisible(actor(Set.of("crm.customer.read","crm.activity.read")),
            new CrmDtos.TaskSourceRef("lead","synthetic-lead",1L),true).isEmpty());
        verifyNoInteractions(jdbc);
    }

    @Test void missingAssignmentPermissionDoesNotQueryTheReceiver() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var staff=new CrmTaskAssignmentRepository(jdbc);
        assertTrue(staff.findEligible(actor(Set.of("crm.task.write")),"other-worker",List.of(1L),"marketing",true).isEmpty());
        verifyNoInteractions(jdbc);
    }

    @Test void failedVisibilityQueryIsNotConvertedToLeftScope() {
        var failure=new DataAccessResourceFailureException("synthetic evidence read failure");
        JdbcTemplate jdbc=mock(JdbcTemplate.class,invocation->{throw failure;});
        var writes=new TaskRoutingMutationRepository(jdbc,NeedWorkflowFixture.CIPHER);
        var saved=new TaskRoutingDtos.Receipt(true,"synthetic-batch","synthetic-event","left_scope",null,List.of());
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->
            writes.replay(actor(Set.of("crm.task.read","crm.task.manage")),saved)));
    }

    @Test void sourceSummaryDoesNotHideInternalBusinessFailures() {
        var failure=new BusinessException(ErrorCode.INTERNAL_ERROR,"synthetic source corruption");
        JdbcTemplate jdbc=mock(JdbcTemplate.class,invocation->{
            String sql=invocation.getArgument(0,String.class);
            if(sql.contains("FROM core_crm_task_source"))return List.of(new CrmDtos.TaskSourceRef("lead","synthetic-lead",1L));
            if(sql.contains("FROM core_crm_lead"))throw failure;
            return List.of();
        });
        var sources=new CrmTaskSourceRepository(jdbc);
        assertSame(failure,assertThrows(BusinessException.class,()->sources.summary(
            actor(Set.of("crm.activity.read","crm.customer.read","crm.lead.read")),1)));
    }
}
