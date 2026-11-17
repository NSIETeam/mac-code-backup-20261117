package cn.siyue.core;

import java.util.List;
import java.util.Set;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentExportTest {
    @Test void unauthorizedOrInvalidViewCannotReadExport() {
        var repository=mock(StudentExportRepository.class);var service=new StudentExportService(repository);
        assertThrows(BusinessException.class,()->service.export(new Actor(1,"staff","all",Set.of(),Set.of()),"scope",null));
        assertThrows(BusinessException.class,()->service.export(actor(),"other",null));
        verifyNoInteractions(repository);
    }
    @Test void exportPreservesPersonalAndSelectedClassScopeAndNeverUsesPaginationOrRawPhone() throws Exception {
        var cipher=mock(PiiCipher.class);var jdbc=new ExportJdbc();
        when(cipher.decrypt("encrypted-name")).thenReturn("=危险公式名");
        when(cipher.maskedPhone("encrypted-phone")).thenReturn("138****1234");
        var service=new StudentExportService(new StudentExportRepository(jdbc,cipher));
        var exported=service.export(actor(),"mine","CLASS");
        assertEquals(1,exported.total());assertEquals("mine",exported.view());assertEquals("CLASS",exported.classId());
        assertEquals("138****1234",exported.items().get(0).phone());
        assertFalse(jdbc.sql.contains("LIMIT"));assertTrue(jdbc.sql.contains("e.status='active'"));
        assertTrue(jdbc.sql.contains("mine_session.teacher_id=?"));
        assertEquals(List.of("CLASS",1L,1L,"CLASS"),jdbc.args);
        verify(cipher,never()).decrypt("encrypted-phone");
    }
    private Actor actor() { return new Actor(1,"teacher","all",Set.of(),Set.of("student.read")); }
    private static class ExportJdbc extends JdbcTemplate {
        String sql;List<Object> args;
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            this.sql=sql;this.args=List.of(args);
            try {var row=mock(ResultSet.class);when(row.getString("public_id")).thenReturn("STU");
                when(row.getString("name_cipher")).thenReturn("encrypted-name");when(row.getString("phone_cipher")).thenReturn("encrypted-phone");
                when(row.getLong("version")).thenReturn(1L);return List.of(mapper.mapRow(row,0));
            } catch(Exception error) {throw new AssertionError(error);}
        }
    }
}
