package cn.siyue.core;

import cn.siyue.core.CrmDtos.TaskSourceRef;
import cn.siyue.core.CrmDtos.TaskTargetInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmTaskSourceTest {
    private record LegacyTarget(String customerId,String studentId,String subject,String term) { }
    private final Actor actor=new Actor(7,"EMP_7","all",Set.of(),Set.of("crm.activity.read","crm.customer.read","crm.lead.read","crm.touchpoint.read"));
    private TaskTargetInput target(String customer,String student,String subject,Long version) {
        return new TaskTargetInput(customer,student,subject,"2030春",List.of(new TaskSourceRef("lead","LEAD_1",version)));
    }
    @Test void legacyTargetJsonAndEmptySourcesPreserveTheStoredRetryHash() throws Exception {
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        String json=mapper.writeValueAsString(new LegacyTarget("CUS_1","STU_1","数学","2030春"));
        assertEquals(json,mapper.writeValueAsString(mapper.readValue(json,TaskTargetInput.class)));
        assertEquals(json,mapper.writeValueAsString(new TaskTargetInput("CUS_1","STU_1","数学","2030春",List.of())));
        assertEquals("unknown",mapper.readValue("{\"id\":\"old\"}",CrmDtos.CrmTask.class).sourceStatus());
    }
    @Test void sourceShapeIsBoundedCanonicalAndNeverInventsTouchpointVersion() {
        var lead=new TaskSourceRef("lead","L",2L);var touch=new TaskSourceRef("touchpoint","T",null);
        assertEquals(List.of(lead,touch),CrmTaskSourceRepository.normalized(List.of(touch,lead)));
        for(var refs:List.of(List.of(new TaskSourceRef("lead","L",null)),List.of(new TaskSourceRef("touchpoint","T",1L)),
                List.of(lead,lead),List.of(new TaskSourceRef("unknown","L",1L)))) {
            assertThrows(BusinessException.class,()->CrmTaskSourceRepository.normalized(refs));
        }
    }
    @Test void currentSourceVersionRequiredForNewWriteButNotSuccessfulReplay() {
        var repository=new CrmTaskSourceRepository(new SourceJdbc());
        var stale=List.of(target("CUS_1","STU_1","数学",1L));
        BusinessException error=assertThrows(BusinessException.class,()->repository.validate(actor,"ACT_1",stale,true));
        assertEquals(ErrorCode.PREVIEW_STALE,error.code());
        assertDoesNotThrow(()->repository.validate(actor,"ACT_1",stale,false));
        assertDoesNotThrow(()->repository.validate(actor,"ACT_1",List.of(target("CUS_1","STU_1","数学",2L)),true));
    }
    @Test void activityCustomerStudentAndKnownDemandCannotBeForged() {
        var repository=new CrmTaskSourceRepository(new SourceJdbc());
        for(var target:List.of(target("CUS_OTHER","STU_1","数学",2L),target("CUS_1","STU_OTHER","数学",2L),
                target("CUS_1",null,"数学",2L),target("CUS_1","STU_1","英语",2L))) {
            assertThrows(BusinessException.class,()->repository.validate(actor,"ACT_1",List.of(target),true));
        }
        assertThrows(BusinessException.class,()->repository.validate(actor,"ACT_OTHER",List.of(target("CUS_1","STU_1","数学",2L)),true));
        var noEvidence=List.of(new TaskTargetInput("CUS_1","STU_1","数学","2030春"));
        assertThrows(BusinessException.class,()->repository.validate(actor,"ACT_1",noEvidence,true));
        assertDoesNotThrow(()->repository.validate(actor,"ACT_1",noEvidence,false));
        assertThrows(BusinessException.class,()->repository.validate(actor,null,List.of(target("CUS_1","STU_1","数学",2L)),true));
    }
    @Test void sourceReadRequiresBothResourceAndCustomerPermissions() {
        var jdbc=mock(JdbcTemplate.class);var repository=new CrmTaskSourceRepository(jdbc);
        var limited=new Actor(7,"EMP_7","own",Set.of(),Set.of("crm.task.read","crm.activity.read"));
        assertThrows(BusinessException.class,()->repository.source(limited,new TaskSourceRef("lead","L",1L),false));
        verifyNoInteractions(jdbc);
    }
    private static class SourceJdbc extends JdbcTemplate {
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            try {
                ResultSet row=mock(ResultSet.class);when(row.getLong("id")).thenReturn(11L);
                when(row.getString("activity_id")).thenReturn("ACT_1");when(row.getString("customer_id")).thenReturn("CUS_1");
                when(row.getString("student_id")).thenReturn("STU_1");when(row.getString("subject")).thenReturn("数学");
                when(row.getString("term")).thenReturn("2030春");when(row.getObject("version",Long.class)).thenReturn(2L);
                return List.of(mapper.mapRow(row,0));
            }catch(SQLException error){throw new AssertionError(error);}
        }
    }
}
