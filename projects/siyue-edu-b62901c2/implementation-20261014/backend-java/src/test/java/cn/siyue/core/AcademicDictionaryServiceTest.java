package cn.siyue.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicDictionaryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccessRepository access = mock(AccessRepository.class);
    private final MutationRepository mutations = mock(MutationRepository.class);
    private final AcademicDictionaryService service = new AcademicDictionaryService(jdbc, access, mutations, new ObjectMapper());
    private final AcademicDictionaryService.Input input = new AcademicDictionaryService.Input("subjects", List.of("数学"));
    @Test void rejectsCampusScopeBeforeWrite() {
        assertThrows(BusinessException.class, () -> service.update(actor("campus"), "dictionary-key-0001", input));
        verifyNoInteractions(jdbc, access, mutations);
    }
    @Test void rechecksScopeAfterActorLock() {
        when(access.lockActive(1)).thenReturn(actor("campus"));
        assertThrows(BusinessException.class, () -> service.update(actor("all"), "dictionary-key-0001", input));
        verifyNoInteractions(jdbc, mutations);
    }
    @Test void validatesTrimmedUniqueValuesAndPreservesOrder() {
        assertEquals(List.of("数学", "历史自定义"), AcademicDictionaryService.validate(
            new AcademicDictionaryService.Input("subjects", List.of(" 数学 ", "历史自定义"))).values());
        for (var values : List.of(List.<String>of(), List.of("数学", " 数学 "), List.of(" "))) {
            assertThrows(BusinessException.class, () -> AcademicDictionaryService.validate(new AcademicDictionaryService.Input("subjects", values)));
        }
        assertThrows(BusinessException.class, () -> AcademicDictionaryService.validate(new AcademicDictionaryService.Input(null, List.of("x"))));
        assertThrows(BusinessException.class, () -> AcademicDictionaryService.validate(new AcademicDictionaryService.Input("unknown", List.of("x"))));
    }
    private Actor actor(String scope) { return new Actor(1, "admin", scope, Set.of(1L), Set.of("settings.update")); }
}
