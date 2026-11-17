package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemSettingsServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccessRepository access = mock(AccessRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final PiiCipher cipher = mock(PiiCipher.class);
    private final SystemSettingsService service = new SystemSettingsService(jdbc, access, mutations, cipher, new ObjectMapper());
    @Test void rejectsUnknownAndUnsafeSettings() {
        for (var input : java.util.List.of(
                new SystemSettingsService.Input("move", Map.of("sameGrade", false)),
                new SystemSettingsService.Input("move", Map.of("maxDays", 1.5)),
                new SystemSettingsService.Input("move", Map.of("maxDays", -1)),
                new SystemSettingsService.Input("move", Map.of("maxDays", 366)),
                new SystemSettingsService.Input("enrollment", Map.of("allowOverCapacity", true)),
                new SystemSettingsService.Input("organization", Map.of("__proto__", "x")),
                new SystemSettingsService.Input("attendance", Map.of("presentConsume", "false")))) {
            assertThrows(BusinessException.class, () -> SystemSettingsService.validate(input));
        }
    }
    @Test void normalizesTextAndPreservesBooleanAndIntegerTypes() {
        assertEquals("机构", SystemSettingsService.validate(new SystemSettingsService.Input("organization", Map.of("name", " 机构 "))).values().get("name"));
        assertEquals(false, SystemSettingsService.validate(new SystemSettingsService.Input("attendance", Map.of("presentConsume", false))).values().get("presentConsume"));
        assertEquals(30, SystemSettingsService.validate(new SystemSettingsService.Input("move", Map.of("maxDays", 30))).values().get("maxDays"));
        assertEquals(0, SystemSettingsService.validate(new SystemSettingsService.Input("move", Map.of("maxDays", 0))).values().get("maxDays"));
    }
    @Test void rechecksGlobalScopeAfterLock() {
        Actor all = new Actor(1, "admin", "all", Set.of(), Set.of("settings.update"));
        when(access.lockActive(1)).thenReturn(new Actor(1, "admin", "campus", Set.of(1L), Set.of("settings.update")));
        assertThrows(BusinessException.class, () -> service.update(all, "settings-test-key-0001",
            new SystemSettingsService.Input("attendance", Map.of("presentConsume", false))));
        verifyNoInteractions(jdbc, mutations, cipher);
    }
}
