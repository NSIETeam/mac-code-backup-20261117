package cn.siyue.core;

import cn.siyue.core.StudentIdentifierDtos.*;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentIdentifierRepositoryTest {
    final PiiCipher cipher=new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));
    final IdentifierJdbc jdbc=new IdentifierJdbc(cipher);
    final StudentIdentifierRepository repository=new StudentIdentifierRepository(jdbc,cipher);
    final Actor intake=new Actor(1,"EMP_1","own",Set.of(),Set.of("crm.customer.read","crm.opportunity.intake","crm.opportunity.read"));
    final Target target=new Target("CUS_1","STU_1");
    @Test void intakeLinkedChildReadUsesCustomerReadScopeWithoutEnrollmentButWritesStayOwned(){
        repository.authorize(intake,target,false);assertTrue(jdbc.lastSql.contains("core_customer_student"));assertTrue(jdbc.lastSql.contains("assigned_deal"));assertFalse(jdbc.lastSql.contains("core_effective_enrollment"));
        repository.authorize(intake,target,true);assertFalse(jdbc.lastSql.contains("assigned_deal"));assertTrue(jdbc.lastSql.contains("c.owner_id = ?"));assertTrue(jdbc.lastSql.endsWith("FOR UPDATE"));
    }
    @Test void academicFallbackRequiresExistingStudentScopeAndMissingRelationIsNotFound(){
        Actor teacher=new Actor(2,"EMP_2","assigned",Set.of(),Set.of("crm.customer.read","student.read"));repository.authorize(teacher,target,false);assertTrue(jdbc.lastSql.contains("core_effective_enrollment"));
        jdbc.missingLink=true;assertEquals(ErrorCode.NOT_FOUND,assertThrows(BusinessException.class,()->repository.authorize(intake,target,true)).code());
    }
    @Test void exactQueryIsParameterizedAndIncludesOnlyActiveMappingsAcrossNamespaces(){
        ScopeSql.Clause clause=repository.numberMatches("001AbC");assertTrue(clause.sql().contains("si.student_id=s.id"));assertTrue(clause.sql().contains("si.active=1 AND ix.active=1"));assertFalse(clause.sql().contains("001AbC"));
        assertEquals("test",clause.args().get(0));assertArrayEquals(cipher.exactBlindIndexes("student_number","001AbC").get("test"),(byte[])clause.args().get(1));
    }
    @Test void missingKeyCoverageFailsClosedBeforeQueryOrInsert(){
        jdbc.total=1;jdbc.missing=1;jdbc.indexed=0;
        assertEquals(ErrorCode.SEARCH_INDEX_NOT_READY,assertThrows(BusinessException.class,()->repository.numberMatches("001")).code());
        assertEquals(ErrorCode.SEARCH_INDEX_NOT_READY,assertThrows(BusinessException.class,()->repository.create(intake,new Link(7,"STU_1",3),new Create("source","001",3))).code());assertTrue(jdbc.writes.isEmpty());
    }
    @Test void filingChecksStudentVersionAndDuplicateWithoutReturningOtherStudent(){
        assertEquals(ErrorCode.PREVIEW_STALE,assertThrows(BusinessException.class,()->repository.create(intake,new Link(7,"STU_1",3),new Create("source","001",2))).code());assertTrue(jdbc.writes.isEmpty());
        jdbc.duplicate=1;BusinessException error=assertThrows(BusinessException.class,()->repository.create(intake,new Link(7,"STU_1",3),new Create("source","001AbC",3)));
        assertEquals(ErrorCode.ALREADY_EXISTS,error.code());assertFalse(error.getMessage().contains("001AbC"));assertTrue(jdbc.writes.isEmpty());
    }
    @Test void filingWritesAllKeyHashesWithoutStudentMutationOrAmbiguousDuplicateKeyUpsert(){
        byte[] other=new byte[32];other[0]=1;PiiCipher rotation=new PiiCipher(Map.of("old",Base64.getEncoder().encodeToString(new byte[32]),"new",Base64.getEncoder().encodeToString(other)),"new");
        IdentifierJdbc rotatedJdbc=new IdentifierJdbc(rotation);var result=new StudentIdentifierRepository(rotatedJdbc,rotation).create(intake,new Link(7,"STU_1",3),new Create("Source","001AbC",3));assertEquals(1,result.version());
        assertEquals(2,rotatedJdbc.writes.stream().filter(s->s.startsWith("INSERT INTO core_student_identifier_index")).count());assertTrue(rotatedJdbc.writes.stream().noneMatch(s->s.contains("UPDATE core_student SET")||s.contains("ON DUPLICATE KEY")));
        assertTrue(rotatedJdbc.writes.stream().allMatch(s->!s.contains("001AbC")));
    }
    @Test void deactivationRetainsRecordAndOnlySwitchesIndexEligibility(){
        Identifier result=repository.deactivate(intake,new Link(7,"STU_1",3),"SID_1",new Deactivate(1,"错误号码停用"));assertFalse(result.active());assertEquals(2,result.version());
        assertTrue(jdbc.writes.stream().anyMatch(s->s.contains("SET ix.active=0")));assertTrue(jdbc.writes.stream().noneMatch(s->s.contains("DELETE")));
        int count=jdbc.writes.size();repository.deactivate(intake,new Link(7,"STU_1",3),"SID_1",new Deactivate(2,"再次确认"));assertEquals(count,jdbc.writes.size());
    }
    @Test void paginatedListingContainsOnlyTargetChildNoOtherHouseholdProjection(){
        var page=repository.list(new Link(7,"STU_1",3),new Query("all",2,10));assertEquals(3,page.studentVersion());assertEquals(10L,jdbc.lastArgs.get(jdbc.lastArgs.size()-1));assertEquals("STU_1",page.items().get(0).studentId());
        assertFalse(jdbc.lastSql.contains("core_customer_contact"));assertFalse(jdbc.lastSql.contains("core_customer_student"));
    }
    @Test void maintenanceStatusReportsCurrentKeyCoverageWithoutInventingReadiness(){
        jdbc.total=3;jdbc.indexed=1;jdbc.missing=1;var status=repository.indexStatus();
        assertEquals(3,status.totalIdentifiers());assertEquals(1,status.indexedIdentifiers());assertEquals(2,status.pendingIdentifiers());assertEquals(1,status.missingSearchable());
    }
    @Test void boundedMaintenanceRebuildsEveryLoadedKeyWithoutBusinessVersionChanges(){
        byte[] next=new byte[32];next[0]=1;PiiCipher rotated=new PiiCipher(Map.of("test",Base64.getEncoder().encodeToString(new byte[32]),"next",Base64.getEncoder().encodeToString(next)),"next");
        var result=new StudentIdentifierRepository(jdbc,rotated).rebuildIndexes(0,1);
        assertEquals(1,result.processed());assertEquals(7,result.nextAfterId());assertFalse(result.hasMore());
        assertEquals(2,jdbc.writes.stream().filter(sql->sql.startsWith("INSERT INTO core_student_identifier_index")).count());
        assertTrue(jdbc.writes.stream().anyMatch(sql->sql.startsWith("UPDATE core_student_identifier SET namespace_cipher")));
        assertTrue(jdbc.writes.stream().noneMatch(sql->sql.contains("version=")||sql.contains("UPDATE core_student SET")));
    }
    @Test void removedEncryptionKeyStopsMaintenanceBeforeAnyWriteAndKeepsRetryRecoverable(){
        byte[] next=new byte[32];next[0]=1;PiiCipher missingOld=new PiiCipher(Map.of("next",Base64.getEncoder().encodeToString(next)),"next");
        assertEquals(ErrorCode.SEARCH_INDEX_NOT_READY,assertThrows(BusinessException.class,()->new StudentIdentifierRepository(jdbc,missingOld).rebuildIndexes(0,100)).code());assertTrue(jdbc.writes.isEmpty());
        assertThrows(BusinessException.class,()->repository.rebuildIndexes(-1,100));assertThrows(BusinessException.class,()->repository.rebuildIndexes(0,201));
    }
    @Test void listingCannotDecryptPrivateStringsReturnsNotReadyWithoutLeakingOriginal(){
        byte[] next=new byte[32];next[0]=7;var missingOld=new PiiCipher(Map.of("next",Base64.getEncoder().encodeToString(next)),"next");
        var error=assertThrows(BusinessException.class,()->new StudentIdentifierRepository(jdbc,missingOld).list(new Link(7,"STU_1",3),new Query("all",1,20)));
        assertEquals(ErrorCode.SEARCH_INDEX_NOT_READY,error.code());assertFalse(error.getMessage().contains("001AbC"));assertFalse(error.getMessage().contains("Source"));assertTrue(jdbc.writes.isEmpty());
    }
    static final class IdentifierJdbc extends JdbcTemplate {
        final PiiCipher cipher;IdentifierJdbc(PiiCipher cipher){this.cipher=cipher;}
        long total,indexed,missing,duplicate;boolean missingLink,active=true;String lastSql;List<Object> lastArgs;List<String>writes=new ArrayList<>();
        @Override public <T>T queryForObject(String sql,Class<T> type){return queryForObject(sql,type,new Object[0]);}
        @Override public int update(String sql){return update(sql,new Object[0]);}
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){lastSql=sql;lastArgs=List.of(args);long value=0;
            if(sql.startsWith("SELECT revision"))value=1;
            else if(sql.startsWith("SELECT id FROM"))value=7;
            else if(sql.contains("NOT EXISTS"))value=missing;
            else if(sql.contains("(SELECT COUNT(*)"))value=indexed;
            else if(sql.contains("ix.active=1 AND"))value=duplicate;
            else if(sql.contains("FROM core_student_identifier WHERE deleted=0"))value=total;
            return type.cast(value);
        }
        @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object...args){lastSql=sql;lastArgs=List.of(args);if(missingLink&&sql.contains("FROM core_customer c"))return List.of();
            try{ResultSet rs=mock(ResultSet.class);when(rs.getLong("id")).thenReturn(7L);when(rs.getLong("version")).thenReturn(active?1L:2L);when(rs.getString("public_id")).thenReturn(sql.contains("FROM core_customer c")?"STU_1":"SID_1");when(rs.getString("student_public_id")).thenReturn("STU_1");when(rs.getString("namespace_cipher")).thenReturn(cipher.encrypt("Source"));when(rs.getString("value_cipher")).thenReturn(cipher.encrypt("001AbC"));when(rs.getBoolean("active")).thenReturn(active);when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(java.time.Instant.EPOCH));when(rs.getTimestamp("deactivated_at")).thenReturn(active?null:Timestamp.from(java.time.Instant.EPOCH.plusSeconds(10)));return List.of(mapper.mapRow(rs,0));}catch(java.sql.SQLException e){throw new AssertionError(e);}
        }
        @Override public int update(String sql,Object...args){writes.add(sql);if(sql.contains("SET active=0,version=version+1"))active=false;return 1;}
    }
}
