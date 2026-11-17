package cn.siyue.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpportunityRepositoryTest {
    private final Actor seller = new Actor(7, "seller", "own", Set.of(2L),
        Set.of("crm.customer.read", "crm.lead.read", "crm.opportunity.write"));

    private CrmDtos.OpportunityInput input(String activityId, String studentId) {
        return new CrmDtos.OpportunityInput("CUS_1", "LEAD_1", activityId, studentId,
            "明确需求", "数学", "2026秋", null);
    }

    @Test void assignedQualifiedLeadCanRetainItsExistingSourceLinks() {
        var database = new SourceJdbc();
        var repository = new OpportunityRepository(database, mock(PiiCipher.class));
        assertDoesNotThrow(() -> repository.authorizeInput(seller, input(null, null)));
        assertDoesNotThrow(() -> repository.authorizeInput(seller, input("ACTIVITY_1", "STUDENT_1")));
    }

    @Test void suppliedIdsCannotReplaceSourceLinksOrProbeOtherRecords() {
        var repository = new OpportunityRepository(new SourceJdbc(), mock(PiiCipher.class));
        assertThrows(BusinessException.class,
            () -> repository.authorizeInput(seller, input("ACTIVITY_OTHER", null)));
        assertThrows(BusinessException.class,
            () -> repository.authorizeInput(seller, input(null, "STUDENT_OTHER")));
    }

    @Test void foreignCustomerAndCampusCannotUseAssignedSourceLead() {
        var database = new SourceJdbc();
        var repository = new OpportunityRepository(database, mock(PiiCipher.class));
        database.customerId = 99L;
        assertThrows(BusinessException.class, () -> repository.authorizeInput(seller, input(null, null)));
        database.customerId = 4L;
        database.sourceCampusId = 3L;
        assertThrows(BusinessException.class, () -> repository.authorizeInput(seller, input(null, null)));
        database.sourceCampusId = 2L;
        database.activityCampusId = 3L;
        assertThrows(BusinessException.class, () -> repository.authorizeInput(seller, input(null, null)));
    }

    @Test void deletedLinksMissingStudentRelationshipAndRevokedAssignmentFailClosed() {
        var database = new SourceJdbc();
        var repository = new OpportunityRepository(database, mock(PiiCipher.class));
        database.studentPublicId = null;
        assertThrows(BusinessException.class, () -> repository.authorizeInput(seller, input(null, null)));
        database.studentPublicId = "STUDENT_1";
        database.activityPublicId = null;
        assertThrows(BusinessException.class, () -> repository.authorizeInput(seller, input(null, null)));
        database.activityPublicId = "ACTIVITY_1";
        database.linkedStudents = 0;
        assertThrows(BusinessException.class, () -> repository.authorizeInput(seller, input(null, null)));
        database.linkedStudents = 1;
        database.authorizedLead = false;
        assertThrows(BusinessException.class, () -> repository.authorizeInput(seller, input(null, null)));
    }

    @Test void sourceLeadVisibilityUsesCurrentOwnScopeAndKeepsCountIndependentOfLinkScopes() {
        var actor = new Actor(7, "seller", "own", Set.of(2L),
            Set.of("crm.opportunity.read", "crm.lead.read", "crm.activity.read", "student.read"));
        var database = new ListingJdbc("LEAD_VISIBLE");
        var repository = new OpportunityRepository(database, mock(PiiCipher.class));
        var result = repository.list(actor, null, "qualified", 2, 25);
        assertEquals(1L, result.total());
        assertEquals("LEAD_VISIBLE", result.items().get(0).sourceLeadId());
        assertTrue(database.pageSql.contains(leadScalar("l.owner_id = ?")));
        assertTrue(database.pageSql.indexOf("AS activity_public_id") < database.pageSql.indexOf("AS student_public_id"));
        assertTrue(database.pageSql.indexOf("AS student_public_id") < database.pageSql.indexOf("AS source_lead_public_id"));
        assertEquals(List.of(7L, 7L, 7L, 7L, 7L, "qualified", 25, 25L), database.pageArgs);
        assertEquals("SELECT COUNT(o.id) FROM core_opportunity o WHERE o.deleted = 0 AND (o.owner_id=?) AND o.stage = ?", database.countSql);
        assertEquals(List.of(7L, "qualified"), database.countArgs);
    }

    @Test void sourceLeadScopeIsNarrowerThanCampusOpportunityScopeWithoutLeadAssignmentPermission() {
        var actor = new Actor(7, "seller", "campus", Set.of(2L),
            Set.of("crm.opportunity.read", "crm.lead.read"));
        var database = new ListingJdbc(null);
        var repository = new OpportunityRepository(database, mock(PiiCipher.class));
        var result = repository.list(actor, null, null, 1, 20);
        assertTrue(database.pageSql.contains(leadScalar(ScopeSql.leads(actor).sql())));
        assertTrue(database.pageSql.contains("AND l.owner_id = ?"));
        assertEquals(List.of(7L, 7L, 7L, 20, 0L), database.pageArgs);
        assertEquals(List.of(7L), database.countArgs);
        assertEquals(1L, result.total());
        assertEquals(1, result.items().size());
        assertNull(result.items().get(0).sourceLeadId());
    }

    @Test void missingLeadReadPermissionFailsClosedEvenWithAllDataScope() {
        var actor = new Actor(7, "reader", "all", Set.of(2L), Set.of("crm.opportunity.read"));
        // The mapper also suppresses unexpected raw IDs when read permission is absent.
        var database = new ListingJdbc("LEAD_NOT_AUTHORIZED");
        var repository = new OpportunityRepository(database, mock(PiiCipher.class));
        var result = repository.list(actor, null, null, 1, 20);
        assertTrue(database.pageSql.contains(leadScalar("1=0")));
        assertEquals(List.of(20, 0L), database.pageArgs);
        assertEquals(List.of(), database.countArgs);
        assertEquals(1L, result.total());
        assertNull(result.items().get(0).sourceLeadId());
    }

    @Test void allScopeLeadReadsStillExcludeDeletedSourceLinks() {
        var actor = new Actor(7, "reader", "all", Set.of(2L),
            Set.of("crm.opportunity.read", "crm.lead.read"));
        var database = new ListingJdbc(null);
        var repository = new OpportunityRepository(database, mock(PiiCipher.class));
        var result = repository.list(actor, null, null, 1, 20);
        assertTrue(database.pageSql.contains(leadScalar("1 = 1")));
        assertEquals(List.of(20, 0L), database.pageArgs);
        assertEquals(1L, result.total());
        assertNull(result.items().get(0).sourceLeadId());
    }

    private static String leadScalar(String scope) {
        return "(SELECT l.public_id FROM core_crm_lead l WHERE l.id=o.source_lead_id AND l.deleted=0 AND ("
            + scope + ")) AS source_lead_public_id";
    }

    /** Captures the assembled SQL and bind order; real row-scope behavior is covered by MySQL tests. */
    private static final class ListingJdbc extends JdbcTemplate {
        private final String sourceLeadId;
        String countSql;
        List<Object> countArgs;
        String pageSql;
        List<Object> pageArgs;
        ListingJdbc(String sourceLeadId) { this.sourceLeadId = sourceLeadId; }

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            assertTrue(sql.startsWith("SELECT COUNT(o.id) FROM core_opportunity o WHERE "));
            countSql = sql;
            countArgs = List.of(args);
            return type.cast(1L);
        }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            assertTrue(sql.startsWith("SELECT o.id,o.public_id"));
            pageSql = sql;
            pageArgs = List.of(args);
            try {
                ResultSet row = mock(ResultSet.class);
                when(row.getLong("id")).thenReturn(1L);
                when(row.getString("public_id")).thenReturn("OPP_1");
                when(row.getString("customer_public_id")).thenReturn("CUS_1");
                when(row.getString("source_lead_public_id")).thenReturn(sourceLeadId);
                when(row.getString("owner_public_id")).thenReturn("seller");
                when(row.getString("title")).thenReturn("已授权商机");
                when(row.getString("stage")).thenReturn("qualified");
                when(row.getLong("version")).thenReturn(1L);
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException error) { throw new AssertionError(error); }
        }
    }

    private static final class SourceJdbc extends JdbcTemplate {
        long customerId = 4L;
        long sourceCampusId = 2L;
        long activityCampusId = 2L;
        String activityPublicId = "ACTIVITY_1";
        String studentPublicId = "STUDENT_1";
        int linkedStudents = 1;
        boolean authorizedLead = true;

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            if (sql.contains("FROM core_customer_student")) { return type.cast(linkedStudents); }
            if (sql.contains("FROM core_customer")) { return type.cast(2L); }
            if (sql.contains("FROM core_activity")) { return type.cast(activityCampusId); }
            throw new AssertionError("Unexpected lookup: " + sql);
        }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            try {
                ResultSet row = mock(ResultSet.class);
                if (sql.stripLeading().startsWith("SELECT c.id FROM core_customer")) {
                    assertTrue(sql.contains("assigned_lead.owner_id=?"));
                    assertEquals(List.of("CUS_1", 7L, 7L), List.of(args));
                    when(row.getLong(1)).thenReturn(4L);
                } else if (sql.stripLeading().startsWith("SELECT l.id,l.customer_id")) {
                    assertTrue(sql.contains("l.owner_id = ?"));
                    assertTrue(sql.contains("a.deleted=0"));
                    assertTrue(sql.contains("s.deleted=0"));
                    assertEquals(List.of("LEAD_1", 7L), List.of(args));
                    if (!authorizedLead) { return List.of(); }
                    when(row.getLong("id")).thenReturn(8L);
                    when(row.getLong("customer_id")).thenReturn(customerId);
                    when(row.getObject("campus_id", Long.class)).thenReturn(sourceCampusId);
                    when(row.getObject("activity_id", Long.class)).thenReturn(9L);
                    when(row.getObject("student_id", Long.class)).thenReturn(10L);
                    when(row.getString("activity_public_id")).thenReturn(activityPublicId);
                    when(row.getString("student_public_id")).thenReturn(studentPublicId);
                    when(row.getString("status")).thenReturn("qualified");
                } else { throw new AssertionError("Must not query an arbitrary linked record: " + sql); }
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException error) { throw new AssertionError(error); }
        }
    }
}
