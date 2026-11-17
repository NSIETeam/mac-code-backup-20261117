package cn.siyue.core;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcademicReadScopeTest {
    @Test void managerMineViewStillIntersectsAssignedCampusAndActualTeaching() {
        Actor manager = new Actor(7, "manager", "campus", Set.of(2L), Set.of());
        var classes = AcademicReadScope.classes(manager, "mine");
        assertTrue(classes.sql().contains("core_principal_campus"));
        assertTrue(classes.sql().contains("mine_session.teacher_id=?"));
        assertEquals(java.util.List.of(7L, 7L, 7L), classes.args());
        var sessions = AcademicReadScope.sessions(manager, "mine");
        assertEquals("s.teacher_id=?", sessions.sql());
        assertEquals(java.util.List.of(7L), sessions.args());
        assertEquals(ScopeSql.classes(manager), AcademicReadScope.classes(manager, "scope"));
    }
    @Test void studentMineViewKeepsEnrollmentAndTheSameSelectedClass() {
        Actor manager = new Actor(7, "manager", "all", Set.of(), Set.of());
        var clause = AcademicReadScope.students(manager, new AcademicDtos.StudentQuery("mine", "class-1", 1, 20));
        assertTrue(clause.sql().contains("e.status='active'"));
        assertTrue(clause.sql().contains("mine_session.teacher_id=?"));
        assertEquals(java.util.List.of("class-1", 7L, 7L, "class-1"), clause.args());
        var own = new Actor(7, "sales", "own", Set.of(), Set.of());
        assertTrue(AcademicReadScope.classes(own, "mine").sql().contains("1 = 0"));
    }
    @Test void invalidViewsFailBeforeAnyScopeIsWidened() {
        assertEquals("scope", AcademicReadScope.view(null));
        assertThrows(BusinessException.class, () -> AcademicReadScope.view("all"));
        assertThrows(BusinessException.class, () -> AcademicReadScope.view(""));
    }
}
