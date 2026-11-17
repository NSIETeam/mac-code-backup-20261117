package cn.siyue.core;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopeSqlTest {
    private Actor actor(String scope) { return new Actor(7, "legacy-user", scope, Set.of(2L), Set.of()); }
    @Test void campusChecksMembershipInDatabaseRatherThanStudentHomeCampus() {
        var clause = ScopeSql.students(actor("campus"), "legacy-class");
        assertTrue(clause.sql().contains("e.status = 'active'"));
        assertTrue(clause.sql().contains("core_principal_campus"));
        assertEquals(java.util.List.of(7L, "legacy-class"), clause.args());
    }
    @Test void assignedScopeIncludesEffectiveTeachingAndBindsUserInput() {
        String hostile = "x' OR 1=1 --";
        var clause = ScopeSql.students(actor("assigned"), hostile);
        assertTrue(clause.sql().contains("core_session"));
        assertFalse(clause.sql().contains(hostile));
        assertEquals(java.util.List.of(7L, 7L, hostile), clause.args());
    }
    @Test void ownReadRequiresStudentOwnerButCannotAuthorizeClassWrite() {
        assertTrue(ScopeSql.students(actor("own"), null).sql().contains("s.owner_id = ?"));
        assertEquals("1 = 0", ScopeSql.classes(actor("own")).sql());
    }
    @Test void invalidScopeFailsClosed() {
        assertThrows(BusinessException.class, () -> ScopeSql.classes(actor("unknown")));
    }
    @Test void assignedSalesReadsDoNotGrantHouseholdMutationScope() {
        var seller = new Actor(7, "seller", "own", Set.of(2L),
            Set.of("crm.customer.read", "crm.customer.write", "crm.lead.read", "crm.opportunity.read"));
        var read = ScopeSql.customerReads(seller);
        assertTrue(read.sql().contains("assigned_lead.customer_id=c.id"));
        assertTrue(read.sql().contains("assigned_lead.owner_id=?"));
        assertTrue(read.sql().contains("assigned_lead.deleted=0"));
        assertTrue(read.sql().contains("assigned_lead.status<>'archived'"));
        assertTrue(read.sql().contains("assigned_deal.owner_id=?"));
        assertEquals(java.util.List.of(7L, 7L, 7L), read.args());
        var write = ScopeSql.customers(seller);
        assertEquals("c.owner_id = ?", write.sql());
        assertEquals(java.util.List.of(7L), write.args());
    }
    @Test void relationReadsRequireTheirPermissionsAndNeverExpandCampusScope() {
        assertFalse(ScopeSql.customerReads(actor("own")).sql().contains("EXISTS"));
        var leadOnly = new Actor(7, "seller", "assigned", Set.of(2L), Set.of("crm.lead.read"));
        assertTrue(ScopeSql.customerReads(leadOnly).sql().contains("assigned_lead"));
        assertFalse(ScopeSql.customerReads(leadOnly).sql().contains("assigned_deal"));
        var campus = new Actor(7, "seller", "campus", Set.of(2L),
            Set.of("crm.lead.read", "crm.opportunity.read"));
        assertEquals(ScopeSql.customers(campus), ScopeSql.customerReads(campus));
        assertThrows(BusinessException.class, () -> ScopeSql.customerReads(actor("unknown")));
    }

    @Test void legacyLeadReadKeepsItsCampusAndPersonalRecordVisibilityWithoutGrantingAssignment() {
        Actor campusReader = new Actor(7, "reader", "campus", Set.of(2L), Set.of("lead.read"));
        var campus = ScopeSql.leadReads(campusReader);
        assertTrue(campus.sql().contains("l.campus_id IN"));
        assertFalse(campus.sql().contains("l.owner_id = ?"));
        assertEquals(java.util.List.of(7L), campus.args());

        Actor personalReader = new Actor(7, "reader", "own", Set.of(), Set.of("lead.read"));
        var own = ScopeSql.leadReads(personalReader);
        assertTrue(own.sql().contains("l.decision_by_id=?"));
        assertTrue(own.sql().contains("l.source_created_by_id=?"));
        assertFalse(own.sql().contains("l.owner_id IS NULL"));
        assertEquals(java.util.List.of(7L, 7L, 7L, 7L, 7L), own.args());
        assertTrue(own.sql().contains("l.registrar_id=?"));
        assertTrue(own.sql().contains("core_crm_lead_collector"));
        assertFalse(ScopeSql.leadWrites(personalReader).sql().contains("registrar_id"));
        assertFalse(ScopeSql.leads(personalReader).sql().contains("source_created_by_id"));
    }

    @Test void legacyScheduleReadUsesLegacyOwnDirectoryVisibilityButKeepsMineFilterNarrow() {
        Actor scheduleReader = new Actor(7, "reader", "own", Set.of(), Set.of("schedule.read"));
        assertEquals("1 = 1", AcademicReadScope.classes(scheduleReader, "scope").sql());
        var mine = AcademicReadScope.classes(scheduleReader, "mine");
        assertTrue(mine.sql().contains("c.teacher_id=?"));
        assertTrue(mine.sql().contains("mine_session.teacher_id=?"));

        Actor nativeClassReader = new Actor(7, "reader", "own", Set.of(), Set.of("class.read"));
        assertEquals("1 = 0", AcademicReadScope.classes(nativeClassReader, "scope").sql());

        Actor nativeAndLegacyReader = new Actor(7, "reader", "own", Set.of(),
            Set.of("class.read", "schedule.read"));
        assertEquals("1 = 0", AcademicReadScope.classes(nativeAndLegacyReader, "scope").sql());
    }
}
