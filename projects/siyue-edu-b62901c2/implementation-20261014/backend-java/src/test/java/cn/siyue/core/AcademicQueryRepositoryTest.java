package cn.siyue.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicQueryRepositoryTest {
    private final Actor actor = new Actor(7, "teacher", "campus", Set.of(2L), Set.of("class.read", "student.read"));
    private static final Instant FROM = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2027-02-01T00:00:00Z");

    @Test void classCountsPagesAndNestedSchedulesUseIdenticalTeacherAndTimeRestrictions() {
        var database = new RecordingJdbc();
        var repository = new AcademicQueryRepository(database, mock(PiiCipher.class));
        var result = repository.classes(actor,
            new AcademicDtos.ClassQuery("mine", "campus-2", "teacher", "101", FROM, UNTIL, 2, 10, null, null, null, null, null, null, null, null, null, null));
        assertEquals(1, result.total());
        assertEquals(2, result.page());
        assertEquals(8, result.items().get(0).schedule().get(0).version());
        Call count = database.calls.get(0), enrolled = database.calls.get(1), page = database.calls.get(2), schedule = database.calls.get(3);
        String countWhere = count.sql.substring(count.sql.indexOf(" WHERE ") + 7);
        assertTrue(page.sql.contains("WHERE " + countWhere + " ORDER BY"));
        assertTrue(enrolled.sql.replaceAll("\\s+","").contains(("AND ("+countWhere+")").replaceAll("\\s+","")));assertEquals(count.args,enrolled.args);
        assertEquals(1L,result.enrolledTotal());
        assertEquals(count.args, page.args.subList(0, page.args.size() - 2));
        assertEquals(List.of(10, 10L), page.args.subList(page.args.size() - 2, page.args.size()));
        assertTrue(count.sql.contains("core_principal_campus"));
        assertTrue(count.sql.contains("s.starts_at<? AND s.ends_at>?"));
        assertTrue(schedule.sql.contains("s.teacher_id=?"));
        assertTrue(schedule.sql.contains("p.active=1"));
        assertTrue(schedule.sql.contains("s.starts_at<? AND s.ends_at>?"));
        assertEquals(List.of(11L, 7L, "teacher", Timestamp.from(UNTIL), Timestamp.from(FROM)), schedule.args);
    }

    @Test void studentClassChoicesExcludeInactiveEnrollmentsAndRespectMineAndPaging() {
        var database = new RecordingJdbc();
        var repository = new AcademicQueryRepository(database, mock(PiiCipher.class));
        repository.studentClasses(actor, "student-1", new AcademicDtos.StudentQuery("mine", null, 2, 25));
        Call count = database.calls.get(0), page = database.calls.get(1);
        assertTrue(count.sql.contains("e.status='active'"));
        assertTrue(count.sql.contains("st.deleted=0"));
        assertTrue(count.sql.contains("mine_session.teacher_id=?"));
        assertEquals(List.of(7L, 7L, 7L, "student-1"), count.args);
        assertEquals(count.args, page.args.subList(0, page.args.size() - 2));
    }

    @Test void staffOptionsNeverReadOidcClaimsAndOrdinaryTeachersCannotEnumerateOtherCampuses() {
        var database = new RecordingJdbc();
        var repository = new AcademicQueryRepository(database, mock(PiiCipher.class));
        Actor teacher = new Actor(7, "teacher", "assigned", Set.of(2L), Set.of("class.read"));
        repository.options(teacher, "campus-2");
        Call staff = database.calls.stream().filter(call -> call.sql.contains("FROM core_principal p")).findFirst().orElseThrow();
        assertFalse(staff.sql.contains("oidc_"));
        assertTrue(staff.sql.contains("membership.principal_id=?"));
        assertTrue(staff.sql.contains("AND p.id=?"));
        assertTrue(staff.sql.contains("p.active=1 AND p.deleted=0"));
        assertEquals(List.of(7L, "campus-2", 7L), staff.args);
    }

    private record Call(String sql, List<Object> args) { }
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<Call> calls = new ArrayList<>();

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            calls.add(new Call(sql, List.of(args)));
            return type.cast(1L);
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            calls.add(new Call(sql, List.of(args)));
            if (!sql.stripLeading().startsWith("SELECT c.id,c.public_id")) { return List.of(); }
            try {
                ResultSet row = mock(ResultSet.class);
                when(row.getLong("id")).thenReturn(11L);
                when(row.getString("public_id")).thenReturn("class-1");
                when(row.getString("course_public_id")).thenReturn("course-1");
                when(row.getString("campus_public_id")).thenReturn("campus-2");
                when(row.getString("teacher_public_id")).thenReturn("teacher");
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException error) { throw new AssertionError(error); }
        }
        @Override public void query(String sql, RowCallbackHandler handler, Object... args) {
            calls.add(new Call(sql, List.of(args)));
            try {
                ResultSet row = mock(ResultSet.class);
                when(row.getLong("class_id")).thenReturn(11L);
                when(row.getString("public_id")).thenReturn("session-1");
                when(row.getString("lesson_public_id")).thenReturn("lesson-1");
                when(row.getString("teacher_public_id")).thenReturn("teacher");
                when(row.getTimestamp("starts_at")).thenReturn(Timestamp.from(FROM.plusSeconds(3600)));
                when(row.getTimestamp("ends_at")).thenReturn(Timestamp.from(FROM.plusSeconds(7200)));
                when(row.getLong("version")).thenReturn(8L);
                handler.processRow(row);
            } catch (SQLException error) { throw new AssertionError(error); }
        }
    }
}
