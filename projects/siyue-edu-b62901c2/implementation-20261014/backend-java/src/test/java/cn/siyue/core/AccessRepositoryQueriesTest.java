package cn.siyue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Verifies identity and password state come from one row without weakening current grant reads. */
class AccessRepositoryQueriesTest {
    @Test void resolveUsesItsIdentityRowAndStillLoadsCurrentGrants() {
        RecordingJdbc jdbc = new RecordingJdbc(false, 1);
        Actor actor = new AccessRepository(jdbc).resolve("https://issuer.invalid", "subject");
        assertEquals(7L, actor.id());
        assertEquals(Set.of(11L), actor.campusIds());
        assertEquals(Set.of("student.create"), actor.permissions());
        assertEquals(3, jdbc.statements.size());
        assertFalse(jdbc.statements.get(0).contains("FOR UPDATE"));
    }

    @Test void lockActiveKeepsItsExclusiveLockAndLoadsCurrentGrants() {
        RecordingJdbc jdbc = new RecordingJdbc(false, 1);
        Actor actor = new AccessRepository(jdbc).lockActive(7L);
        assertEquals("all", actor.scope());
        assertEquals(3, jdbc.statements.size());
        assertTrue(jdbc.statements.get(0).contains("FOR UPDATE"));
        assertTrue(jdbc.statements.get(2).contains("core_principal_denied_permission"));
    }

    @Test void requiredIdentityStopsBeforeGrantQueries() {
        RecordingJdbc jdbc = new RecordingJdbc(true, 1);
        assertEquals(ErrorCode.PASSWORD_CHANGE_REQUIRED, assertThrows(BusinessException.class,
            () -> new AccessRepository(jdbc).resolve("https://issuer.invalid", "subject")).code());
        assertEquals(1, jdbc.statements.size());
    }

    @Test void requiredLockedIdentityStopsBeforeGrantQueries() {
        RecordingJdbc jdbc = new RecordingJdbc(true, 1);
        assertEquals(ErrorCode.PASSWORD_CHANGE_REQUIRED, assertThrows(BusinessException.class,
            () -> new AccessRepository(jdbc).lockActive(7L)).code());
        assertEquals(1, jdbc.statements.size());
    }

    @Test void missingOrAmbiguousIdentityDoesNotReceiveGrants() {
        for (int count : List.of(0, 2)) {
            RecordingJdbc jdbc = new RecordingJdbc(false, count);
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> new AccessRepository(jdbc).resolve("https://issuer.invalid", "subject")).code());
            assertEquals(1, jdbc.statements.size());
        }
        RecordingJdbc jdbc = new RecordingJdbc(false, 0);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
            () -> new AccessRepository(jdbc).lockActive(7L)).code());
        assertEquals(1, jdbc.statements.size());
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<String> statements = new ArrayList<>();
        private final boolean required;
        private final int count;
        RecordingJdbc(boolean required, int count) { this.required = required; this.count = count; }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            statements.add(sql);
            ResultSet row = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getLong" -> 7L;
                    case "getBoolean" -> required;
                    case "getString" -> "scope_type".equals(values[0]) ? "all" : "synthetic-actor";
                    default -> throw new AssertionError("Unexpected ResultSet method: " + method.getName());
                });
            List<T> rows = new ArrayList<>();
            try {
                for (int index = 0; index < count; index++) { rows.add(mapper.mapRow(row, index)); }
            } catch (SQLException error) { throw new AssertionError(error); }
            return rows;
        }

        @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            statements.add(sql);
            return List.of(type.cast(type == Long.class ? 11L : "student.create"));
        }

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            throw new AssertionError("No redundant password-state scalar query is allowed");
        }
    }
}
