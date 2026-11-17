package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.Rebuild;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentNameIndexRepositoryTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PiiCipher cipher=mock(PiiCipher.class);
    private final StudentNameIndexRepository repository=new StudentNameIndexRepository(jdbc,cipher);
    private void counts() {
        when(cipher.blindIndexes(eq("student_name"),anyString())).thenReturn(Map.of("old",new byte[32],"new",new byte[32]));
        when(jdbc.queryForObject("SELECT COUNT(*) FROM core_student WHERE deleted=0",Long.class)).thenReturn(10L);
        when(jdbc.queryForObject(contains("AND (NOT EXISTS"),eq(Long.class),any(Object[].class))).thenReturn(3L);
        when(jdbc.queryForObject(contains("SELECT EXISTS"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
    }
    @SuppressWarnings("unchecked")
    private void oneRow(boolean deleted) throws Exception {
        var rs=mock(ResultSet.class); when(rs.getLong(1)).thenReturn(17L); when(rs.getString(2)).thenReturn("STU_17");
        when(rs.getString(3)).thenReturn("encrypted-name"); when(rs.getLong(4)).thenReturn(4L); when(rs.getBoolean(5)).thenReturn(deleted);
        doAnswer(invocation->{ RowMapper<Object> mapper=invocation.getArgument(1); return List.of(mapper.mapRow(rs,0)); })
            .when(jdbc).query(contains("LIMIT ? FOR UPDATE"),any(RowMapper.class),eq(10L),eq(20));
    }
    @Test void boundedRebuildDecryptsOnlyLockedBatchAndReturnsIndependentCursor() throws Exception {
        counts(); oneRow(false); when(cipher.decrypt("encrypted-name")).thenReturn(" 合成   姓名 ");
        var result=repository.rebuild(new Rebuild(10,20));
        assertEquals(1,result.processed()); assertEquals(17,result.nextAfterId()); assertTrue(result.hasMore());
        assertEquals(10,result.status().totalStudents()); assertEquals(3,result.status().pendingStudents());
        assertEquals(2,result.status().keyVersionCount());
        verify(cipher,times(1)).decrypt("encrypted-name");
        verify(jdbc,times(2)).update(contains("source_version=VALUES(source_version)"),anyString(),any(byte[].class),eq("STU_17"));
    }
    @Test void deletedStudentsOnlyInvalidateIndexesAndAreNeverDecrypted() throws Exception {
        counts(); oneRow(true); var result=repository.rebuild(new Rebuild(10,20));
        assertEquals(1,result.processed()); verify(cipher,never()).decrypt(anyString());
        verify(jdbc).update(contains("SET deleted=1"),eq(17L));
        verify(jdbc,never()).update(contains("INSERT INTO core_student_name_index"),any(Object[].class));
    }
    @Test void authenticationFailureStopsBatchBeforeAnyIndexMutationOrCursorReceipt() throws Exception {
        oneRow(false); when(cipher.decrypt("encrypted-name")).thenThrow(new IllegalStateException("PII decryption failed"));
        assertThrows(IllegalStateException.class,()->repository.rebuild(new Rebuild(10,20)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(jdbc,never()).queryForObject(contains("SELECT EXISTS"),eq(Boolean.class),any(Object[].class));
    }
}
