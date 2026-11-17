package cn.siyue.core;

import cn.siyue.core.ActivityAudienceDtos.Query;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityAudienceTest {
    private Actor actor(Set<String> permissions) { return new Actor(7,"EMP_7","campus",Set.of(2L),permissions); }
    private Set<String> permissions() { return Set.of("crm.activity.read","crm.customer.read","crm.task.write",
        "crm.task.read","crm.lead.read","crm.lead.assign","crm.touchpoint.read"); }

    @Test void countAndPageUseSameAuthorizedUnionFiltersAndBoundedPaging() {
        RecordingJdbc jdbc = new RecordingJdbc();
        var repository = new ActivityAudienceRepository(jdbc,mock(PiiCipher.class));
        var query = new Query(null,"CUS_1",Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-02-01T00:00:00Z"),"without_evidence",2,10);
        var result = repository.list(actor(permissions()),"ACT_1",query);
        assertEquals("source",result.unit()); assertEquals(3,result.total());
        Call count=jdbc.calls.get(1), page=jdbc.calls.get(2);
        int relationStart = page.sql.indexOf(" FROM (");
        assertTrue(relationStart > 0);
        assertFalse(page.sql.substring(0, relationStart).contains("*"));
        assertEquals(count.sql.substring("SELECT COUNT(*)".length()),
            page.sql.substring(relationStart,page.sql.indexOf(" ORDER BY")));
        assertEquals(count.args,page.args.subList(0,page.args.size()-2));
        assertEquals(List.of(10,10L),page.args.subList(page.args.size()-2,page.args.size()));
        assertTrue(count.sql.contains("UNION ALL")); assertTrue(count.sql.contains("has_task_evidence=0"));
        assertTrue(count.sql.contains("core_customer_student"));
        assertTrue(count.sql.contains("l.status<>'archived'"));
        assertTrue(count.sql.contains("t.activity_id=tp.activity_id"));
        assertFalse(count.sql.contains("phone")); assertFalse(count.sql.contains("oidc"));
    }

    @Test void unavailableSourcePermissionRemovesBranchAndExplicitFilterIsRejected() {
        RecordingJdbc jdbc=new RecordingJdbc();
        Set<String> permissions=Set.of("crm.activity.read","crm.customer.read","crm.task.write","crm.touchpoint.read");
        Actor actor=actor(permissions);
        var service=new ActivityAudienceService(new ActivityAudienceRepository(jdbc,mock(PiiCipher.class)));
        service.list(actor,"ACT_1",new ActivityAudienceDtos.Query(null,null,null,null,null,1,20));
        assertFalse(jdbc.calls.get(1).sql.contains("core_crm_lead"));
        assertTrue(jdbc.calls.get(1).sql.contains("AND (1=0)"));
        assertThrows(BusinessException.class,()->service.list(actor,"ACT_1",new ActivityAudienceDtos.Query("lead",null,null,null,null,1,20)));
        assertThrows(BusinessException.class,()->service.list(actor,"ACT_1",new ActivityAudienceDtos.Query(null,null,null,null,"without_evidence",1,20)));
    }

    @Test void invalidRangesAndMissingActivityPermissionFailBeforeQuery() {
        var repository=mock(ActivityAudienceRepository.class); var service=new ActivityAudienceService(repository);
        Actor actor=actor(permissions());
        assertThrows(BusinessException.class,()->service.list(actor,"ACT_1",new ActivityAudienceDtos.Query(null,null,null,null,null,1,101)));
        assertThrows(BusinessException.class,()->service.list(actor,"ACT_1",new ActivityAudienceDtos.Query(null,null,Instant.MAX,Instant.MIN,null,1,20)));
        assertThrows(BusinessException.class,()->service.list(actor,"ACT_1",new ActivityAudienceDtos.Query("fake",null,null,null,null,1,20)));
        assertThrows(BusinessException.class,()->service.list(actor(Set.of("crm.task.write")),"ACT_1",new ActivityAudienceDtos.Query(null,null,null,null,null,1,20)));
        verifyNoInteractions(repository);
    }

    private record Call(String sql,List<Object> args) { }
    private static class RecordingJdbc extends JdbcTemplate {
        final List<Call> calls=new ArrayList<>();
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            calls.add(new Call(sql,List.of(args))); return type.cast(3L);
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            calls.add(new Call(sql,List.of(args)));
            if (!sql.startsWith("SELECT a.id"))return List.of();
            try { ResultSet row=mock(ResultSet.class);when(row.getLong(1)).thenReturn(8L);return List.of(mapper.mapRow(row,0)); }
            catch(SQLException error){throw new AssertionError(error);}
        }
    }
}
